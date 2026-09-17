$ErrorActionPreference = 'Stop'

# Build on the proven v5 Compact Fluent UI + v4.4 Gemini/WASAPI backend.
& "$PSScriptRoot/patch-alad-windows-v5-compact.ps1"

$p = 'alad-windows-v2/Program.cs'
$c = Get-Content $p -Raw
$nl = [Environment]::NewLine

# Version label.
$c = $c.Replace('ALAD Windows v5 Compact Fluent', 'ALAD Windows v5.2 Compact Optimized')

# Pool large temporary buffers and update telemetry a little less often.
if ($c -notmatch 'using System\.Buffers;') {
    $c = $c.Replace('using System.Diagnostics;', 'using System.Buffers;' + $nl + 'using System.Diagnostics;')
}
$c = $c.Replace('private readonly System.Windows.Forms.Timer uiTimer = new() { Interval = 200 };', 'private readonly System.Windows.Forms.Timer uiTimer = new() { Interval = 250 };')

# Lower queued audio backlog. DropOldest remains active, but 12 chunks prevents seconds of stale audio.
$c = $c.Replace('Channel.CreateBounded<byte[]>(new BoundedChannelOptions(32)', 'Channel.CreateBounded<byte[]>(new BoundedChannelOptions(12)')

# Replace List<byte>-based chunker (GetRange + RemoveRange allocations/copies) with a compact rolling byte buffer.
$chunker = @'
internal sealed class PcmChunker
{
    private int chunkBytes;
    private byte[] pending;
    private int pendingCount;

    public PcmChunker(int chunkMs)
    {
        chunkBytes = 16000 * 2 * Math.Clamp(chunkMs, 20, 500) / 1000;
        pending = new byte[Math.Max(chunkBytes * 3, 4096)];
    }

    public void SetChunkMs(int chunkMs)
    {
        chunkBytes = 16000 * 2 * Math.Clamp(chunkMs, 20, 500) / 1000;
        pendingCount = 0;
        EnsureCapacity(chunkBytes * 3);
    }

    public IEnumerable<byte[]> Push(byte[] bytes)
    {
        if (bytes.Length == 0) yield break;
        EnsureCapacity(pendingCount + bytes.Length);
        Buffer.BlockCopy(bytes, 0, pending, pendingCount, bytes.Length);
        pendingCount += bytes.Length;

        int consumed = 0;
        while (pendingCount - consumed >= chunkBytes)
        {
            var chunk = new byte[chunkBytes];
            Buffer.BlockCopy(pending, consumed, chunk, 0, chunkBytes);
            consumed += chunkBytes;
            yield return chunk;
        }

        if (consumed > 0)
        {
            int remain = pendingCount - consumed;
            if (remain > 0) Buffer.BlockCopy(pending, consumed, pending, 0, remain);
            pendingCount = remain;
        }
    }

    public void Reset() => pendingCount = 0;

    private void EnsureCapacity(int need)
    {
        if (pending.Length >= need) return;
        int size = pending.Length;
        while (size < need) size *= 2;
        Array.Resize(ref pending, size);
    }
}
'@
$chunkPattern = '(?s)internal sealed class PcmChunker\s*\{.*?\r?\n\}\r?\n\r?\ninternal sealed class LocalSilenceDetector'
$c2 = [regex]::Replace($c, $chunkPattern, $chunker.TrimEnd() + $nl + $nl + 'internal sealed class LocalSilenceDetector', 1)
if ($c2 -eq $c) { throw 'PcmChunker optimization marker not found' }
$c = $c2

# Reduce GC pressure in the resampler by renting the temporary float buffer.
$convert = @'
internal static class AudioConvert
{
    public static byte[] ToPcm16Mono16k(ReadOnlySpan<byte> input, WaveFormat format)
    {
        if (format.SampleRate == 16000 && format.Channels == 1 && format.BitsPerSample == 16 && format.Encoding == WaveFormatEncoding.Pcm)
            return input.ToArray();

        int channels = Math.Max(1, format.Channels);
        int bytesPerSample = Math.Max(1, format.BitsPerSample / 8);
        int frameBytes = bytesPerSample * channels;
        int frames = input.Length / frameBytes;
        if (frames <= 0) return Array.Empty<byte>();

        float[] mono = ArrayPool<float>.Shared.Rent(frames);
        try
        {
            for (int i = 0; i < frames; i++)
            {
                double sum = 0;
                int frameOffset = i * frameBytes;
                for (int ch = 0; ch < channels; ch++)
                {
                    int o = frameOffset + ch * bytesPerSample;
                    float sample = 0;
                    if (format.Encoding == WaveFormatEncoding.IeeeFloat && format.BitsPerSample == 32)
                        sample = BitConverter.ToSingle(input.Slice(o, 4));
                    else if (format.BitsPerSample == 16)
                        sample = BitConverter.ToInt16(input.Slice(o, 2)) / 32768f;
                    else if (format.BitsPerSample == 32)
                        sample = BitConverter.ToInt32(input.Slice(o, 4)) / 2147483648f;
                    sum += sample;
                }
                mono[i] = (float)(sum / channels);
            }

            int outFrames = Math.Max(1, (int)Math.Round(frames * 16000.0 / format.SampleRate));
            var output = new byte[outFrames * 2];
            double ratio = format.SampleRate / 16000.0;
            for (int i = 0; i < outFrames; i++)
            {
                double pos = i * ratio;
                int a = Math.Min(frames - 1, (int)pos);
                int b = Math.Min(frames - 1, a + 1);
                float t = (float)(pos - a);
                float v = mono[a] + (mono[b] - mono[a]) * t;
                short s = (short)Math.Clamp((int)(v * 32767f), short.MinValue, short.MaxValue);
                output[i * 2] = (byte)(s & 0xff);
                output[i * 2 + 1] = (byte)((s >> 8) & 0xff);
            }
            return output;
        }
        finally
        {
            ArrayPool<float>.Shared.Return(mono);
        }
    }
}
'@
$convertPattern = '(?s)internal static class AudioConvert\s*\{.*?\r?\n\}\r?\n\r?\ninternal sealed class AudioDucker'
$c2 = [regex]::Replace($c, $convertPattern, $convert.TrimEnd() + $nl + $nl + 'internal sealed class AudioDucker', 1)
if ($c2 -eq $c) { throw 'AudioConvert optimization marker not found' }
$c = $c2

# Duck only the selected process tree. Never fall back to changing every application's volume.
# This matches process-loopback semantics much better for browsers and multi-process apps.
$duckHelpers = @'
    private const uint TH32CS_SNAPPROCESS = 0x00000002;
    private static readonly IntPtr InvalidHandleValue = new(-1);
    private bool warnedNoAudioSession;

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct PROCESSENTRY32
    {
        public uint dwSize;
        public uint cntUsage;
        public uint th32ProcessID;
        public IntPtr th32DefaultHeapID;
        public uint th32ModuleID;
        public uint cntThreads;
        public uint th32ParentProcessID;
        public int pcPriClassBase;
        public uint dwFlags;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 260)]
        public string szExeFile;
    }

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr CreateToolhelp32Snapshot(uint dwFlags, uint th32ProcessID);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool Process32FirstW(IntPtr hSnapshot, ref PROCESSENTRY32 lppe);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool Process32NextW(IntPtr hSnapshot, ref PROCESSENTRY32 lppe);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool CloseHandle(IntPtr hObject);

    private static HashSet<uint> BuildProcessTree(uint rootPid)
    {
        var result = new HashSet<uint> { rootPid };
        IntPtr snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
        if (snapshot == InvalidHandleValue) return result;
        try
        {
            var children = new Dictionary<uint, List<uint>>();
            var entry = new PROCESSENTRY32 { dwSize = (uint)Marshal.SizeOf<PROCESSENTRY32>(), szExeFile = string.Empty };
            if (Process32FirstW(snapshot, ref entry))
            {
                do
                {
                    if (!children.TryGetValue(entry.th32ParentProcessID, out var list))
                    {
                        list = new List<uint>();
                        children[entry.th32ParentProcessID] = list;
                    }
                    list.Add(entry.th32ProcessID);
                    entry.dwSize = (uint)Marshal.SizeOf<PROCESSENTRY32>();
                }
                while (Process32NextW(snapshot, ref entry));
            }

            var queue = new Queue<uint>();
            queue.Enqueue(rootPid);
            while (queue.Count > 0)
            {
                uint parent = queue.Dequeue();
                if (!children.TryGetValue(parent, out var list)) continue;
                foreach (uint child in list)
                    if (result.Add(child)) queue.Enqueue(child);
            }
        }
        catch { }
        finally { CloseHandle(snapshot); }
        return result;
    }

    public void Duck(float factor, ProcessItem? preferred)
    {
        factor = Math.Clamp(factor * baseFactor, 0f, 1f);
        if (device == null) return;
        try
        {
            var sessions = device.AudioSessionManager.Sessions;
            HashSet<uint>? targetPids = preferred == null ? null : BuildProcessTree(preferred.Pid);
            bool matched = false;

            for (int i = 0; i < sessions.Count; i++)
            {
                var s = sessions[i];
                uint pid;
                try { pid = s.GetProcessID; } catch { continue; }
                if (pid == selfPid || s.SimpleAudioVolume == null) continue;
                if (targetPids != null && !targetPids.Contains(pid)) continue;

                matched = true;
                if (!original.ContainsKey(pid)) original[pid] = s.SimpleAudioVolume.Volume;
                s.SimpleAudioVolume.Volume = Math.Clamp(original[pid] * factor, 0f, 1f);
            }

            if (preferred != null && !matched && !warnedNoAudioSession)
            {
                warnedNoAudioSession = true;
                log("Chưa thấy audio session của ứng dụng đã chọn; ALAD sẽ không giảm âm lượng app khác.");
            }
            else if (matched)
            {
                warnedNoAudioSession = false;
            }
        }
        catch (Exception ex) { log("Ducking lỗi: " + ex.Message); }
    }
'@
$duckPattern = '(?s)    public void Duck\(float factor, ProcessItem\? preferred\)\s*\{.*?\r?\n    \}\r?\n\r?\n    public void Restore\(\)'
$c2 = [regex]::Replace($c, $duckPattern, $duckHelpers.TrimEnd() + $nl + $nl + '    public void Restore()', 1)
if ($c2 -eq $c) { throw 'AudioDucker optimization marker not found' }
$c = $c2

# Reuse receive buffers and surface close/error reasons without waiting for timeout.
$receive = @'
    private async Task ReceiveLoop(ClientWebSocket socket, CancellationToken ct)
    {
        byte[] buffer = ArrayPool<byte>.Shared.Rent(64 * 1024);
        using var ms = new MemoryStream(64 * 1024);
        try
        {
            while (!ct.IsCancellationRequested && socket.State == WebSocketState.Open)
            {
                ms.SetLength(0);
                WebSocketReceiveResult result;
                do
                {
                    result = await socket.ReceiveAsync(buffer, ct);
                    if (result.MessageType == WebSocketMessageType.Close)
                    {
                        string reason = $"{result.CloseStatus} {result.CloseStatusDescription}".Trim();
                        setupReady?.TrySetException(new InvalidOperationException("Gemini đóng kết nối trước setupComplete: " + reason));
                        if (!stopping) status("Gemini đóng kết nối: " + reason);
                        return;
                    }
                    if (result.Count > 0) ms.Write(buffer, 0, result.Count);
                }
                while (!result.EndOfMessage);

                if (ms.Length == 0) continue;
                HandleServerMessage(Encoding.UTF8.GetString(ms.GetBuffer(), 0, checked((int)ms.Length)));
            }
        }
        catch (OperationCanceledException) { }
        catch (Exception ex)
        {
            setupReady?.TrySetException(ex);
            if (!stopping) status("Gemini mất kết nối: " + ex.Message);
        }
        finally
        {
            ArrayPool<byte>.Shared.Return(buffer);
            if (!stopping && !ct.IsCancellationRequested)
                _ = Task.Run(() => ReconnectLoop(ct), ct);
        }
    }
'@
$receivePattern = '(?s)    private async Task ReceiveLoop\(ClientWebSocket socket, CancellationToken ct\)\s*\{.*?\r?\n    \}\r?\n\r?\n    private void HandleServerMessage'
$c2 = [regex]::Replace($c, $receivePattern, $receive.TrimEnd() + $nl + $nl + '    private void HandleServerMessage', 1)
if ($c2 -eq $c) { throw 'ReceiveLoop optimization marker not found' }
$c = $c2

# Reconnect quicker at first, then back off gently.
$delayPattern = 'int delay = Math\.Min\(15000, 1000 \* \(1 << Math\.Min\(attempt - 1, 4\)\)\);'
$delayReplacement = 'int delay = attempt switch { 1 => 500, 2 => 1000, 3 => 2000, 4 => 4000, _ => 8000 };'
$c2 = [regex]::Replace($c, $delayPattern, $delayReplacement, 1)
if ($c2 -eq $c) { throw 'Reconnect delay marker not found' }
$c = $c2

# Keep transcript responsive without retaining unnecessary text.
$c = $c.Replace('if (transcript.TextLength > 16000) transcript.Text = transcript.Text[^10000..];', 'if (transcript.TextLength > 12000) transcript.Text = transcript.Text[^8000..];')

# Improve visible status wording without changing the connection state machine.
$c = $c.Replace('status("Gemini sẵn sàng · continuous stream");', 'status("Gemini sẵn sàng · tối ưu realtime");')

Set-Content $p $c -Encoding UTF8

# Verification: fail CI if any intended optimization is missing.
$c = Get-Content $p -Raw
if ($c -notmatch 'ALAD Windows v5\.2 Compact Optimized') { throw 'v5.2 version label missing' }
if ($c -notmatch 'BoundedChannelOptions\(12\)') { throw 'low-backlog send queue missing' }
if ($c -notmatch 'ArrayPool<float>\.Shared\.Rent') { throw 'pooled resampler buffer missing' }
if ($c -notmatch 'BuildProcessTree\(preferred\.Pid\)') { throw 'process-tree ducking missing' }
if ($c -match 'preferred != null && !matched\)\s*\{\s*for') { throw 'unsafe duck-all fallback remains' }
if ($c -notmatch 'ArrayPool<byte>\.Shared\.Rent\(64 \* 1024\)') { throw 'pooled receive buffer missing' }
if ($c -notmatch '1 => 500, 2 => 1000') { throw 'fast reconnect schedule missing' }
Write-Host 'ALAD Windows v5.2 Compact Optimized patch verified.'
