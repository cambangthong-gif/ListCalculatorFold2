using System.Diagnostics;
using System.Net.WebSockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using System.Threading.Channels;
using NAudio.CoreAudioApi;
using NAudio.Wave;

namespace AladWindowsV5;

internal enum LiveMode { FastTranslate, CustomVoice }
internal enum MixMode { AutoDucking, VoiceOver, Parallel, FullDub }

internal sealed class ProcessItem
{
    public uint Pid { get; init; }
    public string Name { get; init; } = "";
    public string Title { get; init; } = "";
    public override string ToString() => string.IsNullOrWhiteSpace(Title)
        ? $"{Name} (PID {Pid})"
        : $"{Name} — {Title}";
}

internal sealed class PcmChunker
{
    private int chunkBytes;
    private readonly List<byte> pending = new();

    public PcmChunker(int chunkMs) => SetChunkMs(chunkMs);

    public void SetChunkMs(int chunkMs)
    {
        chunkBytes = 16000 * 2 * Math.Clamp(chunkMs, 20, 500) / 1000;
        pending.Clear();
    }

    public IEnumerable<byte[]> Push(byte[] bytes)
    {
        pending.AddRange(bytes);
        while (pending.Count >= chunkBytes)
        {
            var chunk = pending.GetRange(0, chunkBytes).ToArray();
            pending.RemoveRange(0, chunkBytes);
            yield return chunk;
        }
    }

    public void Reset() => pending.Clear();
}

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

        var mono = new float[frames];
        for (int i = 0; i < frames; i++)
        {
            double sum = 0;
            for (int c = 0; c < channels; c++)
            {
                int o = i * frameBytes + c * bytesPerSample;
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
}

internal sealed class AudioDucker : IDisposable
{
    private readonly int selfPid;
    private readonly Action<string> log;
    private readonly Dictionary<uint, float> original = new();
    private MMDeviceEnumerator? enumerator;
    private MMDevice? device;
    private float baseFactor = 1f;

    public AudioDucker(int selfPid, Action<string> log)
    {
        this.selfPid = selfPid;
        this.log = log;
        try
        {
            enumerator = new MMDeviceEnumerator();
            device = enumerator.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
        }
        catch (Exception ex) { log("Ducking không khả dụng: " + ex.Message); }
    }

    public void SetBaseFactor(float factor) => baseFactor = Math.Clamp(factor, 0f, 1f);
    public void ApplyBase(ProcessItem? preferred) => Duck(1f, preferred);

    public void Duck(float factor, ProcessItem? preferred)
    {
        factor = Math.Clamp(factor * baseFactor, 0f, 1f);
        if (device == null) return;
        try
        {
            var sessions = device.AudioSessionManager.Sessions;
            bool matched = false;
            for (int i = 0; i < sessions.Count; i++)
            {
                var s = sessions[i];
                uint pid;
                try { pid = s.GetProcessID; } catch { continue; }
                if (pid == selfPid || s.SimpleAudioVolume == null) continue;
                if (preferred != null && pid != preferred.Pid) continue;
                matched = true;
                if (!original.ContainsKey(pid)) original[pid] = s.SimpleAudioVolume.Volume;
                s.SimpleAudioVolume.Volume = Math.Clamp(original[pid] * factor, 0f, 1f);
            }

            if (preferred != null && !matched)
            {
                for (int i = 0; i < sessions.Count; i++)
                {
                    var s = sessions[i];
                    uint pid;
                    try { pid = s.GetProcessID; } catch { continue; }
                    if (pid == selfPid || s.SimpleAudioVolume == null) continue;
                    if (!original.ContainsKey(pid)) original[pid] = s.SimpleAudioVolume.Volume;
                    s.SimpleAudioVolume.Volume = Math.Clamp(original[pid] * factor, 0f, 1f);
                }
            }
        }
        catch (Exception ex) { log("Ducking lỗi: " + ex.Message); }
    }

    public void Restore()
    {
        if (device == null || original.Count == 0) return;
        try
        {
            var sessions = device.AudioSessionManager.Sessions;
            for (int i = 0; i < sessions.Count; i++)
            {
                var s = sessions[i];
                uint pid;
                try { pid = s.GetProcessID; } catch { continue; }
                if (original.TryGetValue(pid, out var volume) && s.SimpleAudioVolume != null)
                    s.SimpleAudioVolume.Volume = volume;
            }
        }
        catch { }
        finally { original.Clear(); }
    }

    public void Dispose()
    {
        Restore();
        device?.Dispose();
        enumerator?.Dispose();
    }
}

internal sealed class LiveGeminiClient : IAsyncDisposable
{
    private const string Endpoint = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent";
    private readonly Action<string> status;
    private readonly Action<byte[]> audio;
    private readonly Action<string, string> transcript;
    private readonly Action clearPlayback;
    private readonly Channel<byte[]> sendQueue = Channel.CreateBounded<byte[]>(new BoundedChannelOptions(48)
    {
        FullMode = BoundedChannelFullMode.DropOldest,
        SingleReader = true,
        SingleWriter = false
    });
    private readonly SemaphoreSlim sendLock = new(1, 1);
    private readonly SemaphoreSlim reconnectLock = new(1, 1);

    private ClientWebSocket? ws;
    private CancellationTokenSource? cts;
    private Task? sendTask;
    private TaskCompletionSource<bool>? setupReady;
    private string apiKey = "";
    private string targetLang = "vi";
    private string voice = "Kore";
    private LiveMode mode;
    private string? sessionHandle;
    private volatile bool stopping;

    public LiveGeminiClient(Action<string> status, Action<byte[]> audio, Action<string, string> transcript, Action clearPlayback)
    {
        this.status = status;
        this.audio = audio;
        this.transcript = transcript;
        this.clearPlayback = clearPlayback;
    }

    public async Task ConnectAsync(string apiKey, string targetLang, LiveMode mode, string voice, CancellationToken externalToken)
    {
        this.apiKey = apiKey;
        this.targetLang = targetLang;
        this.mode = mode;
        this.voice = voice;
        stopping = false;
        cts = CancellationTokenSource.CreateLinkedTokenSource(externalToken);

        try
        {
            await OpenSocketAsync(cts.Token);
        }
        catch (TimeoutException)
        {
            status("Gemini chưa phản hồi · thử lại...");
            await Task.Delay(500, cts.Token);
            await OpenSocketAsync(cts.Token);
        }

        sendTask = Task.Run(() => SendLoop(cts.Token), cts.Token);
    }

    public void QueueAudio(byte[] pcm16k) => sendQueue.Writer.TryWrite(pcm16k);

    private async Task OpenSocketAsync(CancellationToken ct)
    {
        status(sessionHandle == null ? "Đang kết nối Gemini..." : "Đang nối lại Gemini...");
        try { ws?.Abort(); ws?.Dispose(); } catch { }

        var socket = new ClientWebSocket();
        socket.Options.KeepAliveInterval = TimeSpan.FromSeconds(15);
        ws = socket;

        var uri = new Uri($"{Endpoint}?key={Uri.EscapeDataString(apiKey)}");
        await socket.ConnectAsync(uri, ct);

        setupReady = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        _ = Task.Run(() => ReceiveLoop(socket, ct), ct);
        await SendSetupAsync(socket, ct);

        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeout.CancelAfter(TimeSpan.FromSeconds(8));
        try
        {
            await setupReady.Task.WaitAsync(timeout.Token);
        }
        catch (OperationCanceledException) when (!ct.IsCancellationRequested)
        {
            try { socket.Abort(); } catch { }
            throw new TimeoutException("Gemini không trả setupComplete trong 8 giây.");
        }

        status("Đã kết nối Gemini");
    }

    private async Task SendSetupAsync(ClientWebSocket socket, CancellationToken ct)
    {
        object setup;
        if (mode == LiveMode.FastTranslate)
        {
            setup = new
            {
                model = "models/gemini-3.5-live-translate-preview",
                generationConfig = new
                {
                    responseModalities = new[] { "AUDIO" },
                    translationConfig = new
                    {
                        targetLanguageCode = targetLang.Split('-')[0],
                        echoTargetLanguage = true
                    }
                },
                sessionResumption = new { handle = sessionHandle }
            };
        }
        else
        {
            setup = new
            {
                model = "models/gemini-3.8-live",
                generationConfig = new
                {
                    responseModalities = new[] { "AUDIO" },
                    speechConfig = new
                    {
                        voiceConfig = new
                        {
                            prebuiltVoiceConfig = new { voiceName = voice }
                        }
                    }
                },
                inputAudioTranscription = new { },
                outputAudioTranscription = new { },
                systemInstruction = new
                {
                    parts = new[]
                    {
                        new
                        {
                            text = $"Act only as a real-time interpreter. Translate every spoken utterance into {targetLang}. Speak only the translation. Do not answer, explain, summarize, or add commentary. Start speaking as early as possible and keep phrases short."
                        }
                    }
                },
                sessionResumption = new { handle = sessionHandle },
                contextWindowCompression = new { slidingWindow = new { } }
            };
        }

        await SendTextAsync(socket, JsonSerializer.Serialize(new { setup }), ct);
    }

    private async Task SendLoop(CancellationToken ct)
    {
        try
        {
            await foreach (var pcm in sendQueue.Reader.ReadAllAsync(ct))
            {
                var socket = ws;
                if (socket?.State != WebSocketState.Open || setupReady?.Task.IsCompletedSuccessfully != true)
                    continue;

                var payload = JsonSerializer.Serialize(new
                {
                    realtimeInput = new
                    {
                        audio = new
                        {
                            data = Convert.ToBase64String(pcm),
                            mimeType = "audio/pcm;rate=16000"
                        }
                    }
                });
                await SendTextAsync(socket, payload, ct);
            }
        }
        catch (OperationCanceledException) { }
        catch (Exception ex)
        {
            if (!stopping) status("Gửi audio lỗi: " + ex.Message);
        }
    }

    private async Task SendTextAsync(ClientWebSocket socket, string text, CancellationToken ct)
    {
        byte[] bytes = Encoding.UTF8.GetBytes(text);
        await sendLock.WaitAsync(ct);
        try
        {
            await socket.SendAsync(new ArraySegment<byte>(bytes), WebSocketMessageType.Text, true, ct);
        }
        finally { sendLock.Release(); }
    }

    private async Task ReceiveLoop(ClientWebSocket socket, CancellationToken ct)
    {
        var buffer = new byte[128 * 1024];
        try
        {
            while (!ct.IsCancellationRequested && socket.State == WebSocketState.Open)
            {
                using var ms = new MemoryStream();
                WebSocketReceiveResult result;
                do
                {
                    result = await socket.ReceiveAsync(new ArraySegment<byte>(buffer), ct);
                    if (result.MessageType == WebSocketMessageType.Close) break;
                    ms.Write(buffer, 0, result.Count);
                }
                while (!result.EndOfMessage);

                if (result.MessageType == WebSocketMessageType.Close)
                {
                    string reason = $"{result.CloseStatus} {result.CloseStatusDescription}".Trim();
                    setupReady?.TrySetException(new InvalidOperationException("Gemini đóng kết nối trước setupComplete: " + reason));
                    if (!stopping) status("Gemini đóng kết nối: " + reason);
                    break;
                }

                HandleServerMessage(Encoding.UTF8.GetString(ms.ToArray()));
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
            if (!stopping && !ct.IsCancellationRequested)
                _ = Task.Run(() => ReconnectLoop(ct), ct);
        }
    }

    private void HandleServerMessage(string json)
    {
        try
        {
            using var doc = JsonDocument.Parse(json);
            var root = doc.RootElement;

            if (root.TryGetProperty("setupComplete", out _) || root.TryGetProperty("setup_complete", out _))
            {
                setupReady?.TrySetResult(true);
                return;
            }

            if (root.TryGetProperty("error", out var error))
            {
                string message = error.TryGetProperty("message", out var m) ? (m.GetString() ?? error.ToString()) : error.ToString();
                setupReady?.TrySetException(new InvalidOperationException("Gemini API: " + message));
                status("Gemini API: " + message);
                return;
            }

            if (root.TryGetProperty("sessionResumptionUpdate", out var sr) || root.TryGetProperty("session_resumption_update", out sr))
            {
                if (sr.TryGetProperty("newHandle", out var h) || sr.TryGetProperty("new_handle", out h))
                {
                    string? value = h.GetString();
                    if (!string.IsNullOrWhiteSpace(value)) sessionHandle = value;
                }
                return;
            }

            if (root.TryGetProperty("goAway", out _) || root.TryGetProperty("go_away", out _))
            {
                status("Gemini chuẩn bị làm mới kết nối...");
                _ = Task.Run(() => ReconnectLoop(cts?.Token ?? CancellationToken.None));
                return;
            }

            if (!(root.TryGetProperty("serverContent", out var sc) || root.TryGetProperty("server_content", out sc)))
                return;

            if (sc.TryGetProperty("interrupted", out var interrupted) && interrupted.ValueKind == JsonValueKind.True)
                clearPlayback();

            if (sc.TryGetProperty("inputTranscription", out var inputT) || sc.TryGetProperty("input_transcription", out inputT))
            {
                if (inputT.TryGetProperty("text", out var text)) transcript("Gốc", text.GetString() ?? "");
            }

            if (sc.TryGetProperty("outputTranscription", out var outputT) || sc.TryGetProperty("output_transcription", out outputT))
            {
                if (outputT.TryGetProperty("text", out var text)) transcript("Dịch", text.GetString() ?? "");
            }

            if (!(sc.TryGetProperty("modelTurn", out var modelTurn) || sc.TryGetProperty("model_turn", out modelTurn)))
                return;
            if (!modelTurn.TryGetProperty("parts", out var parts) || parts.ValueKind != JsonValueKind.Array)
                return;

            foreach (var part in parts.EnumerateArray())
            {
                if (!(part.TryGetProperty("inlineData", out var inline) || part.TryGetProperty("inline_data", out inline)))
                    continue;
                if (!inline.TryGetProperty("data", out var data)) continue;
                string? base64 = data.GetString();
                if (!string.IsNullOrEmpty(base64)) audio(Convert.FromBase64String(base64));
            }
        }
        catch { }
    }

    private async Task ReconnectLoop(CancellationToken ct)
    {
        if (stopping || ct.IsCancellationRequested) return;
        if (!await reconnectLock.WaitAsync(0, ct)) return;
        try
        {
            clearPlayback();
            for (int attempt = 1; !stopping && !ct.IsCancellationRequested; attempt++)
            {
                int delay = Math.Min(15000, 1000 * (1 << Math.Min(attempt - 1, 4)));
                status($"Reconnect sau {delay / 1000.0:0.#} giây...");
                await Task.Delay(delay, ct);
                try
                {
                    await OpenSocketAsync(ct);
                    return;
                }
                catch (Exception ex)
                {
                    status("Reconnect chưa được: " + ex.Message);
                }
            }
        }
        finally { reconnectLock.Release(); }
    }

    public async ValueTask DisposeAsync()
    {
        stopping = true;
        sendQueue.Writer.TryComplete();
        try { cts?.Cancel(); } catch { }
        try
        {
            if (ws?.State == WebSocketState.Open)
                await ws.CloseAsync(WebSocketCloseStatus.NormalClosure, "stop", CancellationToken.None);
        }
        catch { }
        try { if (sendTask != null) await sendTask; } catch { }
        ws?.Dispose();
        ws = null;
        cts?.Dispose();
        cts = null;
        sendLock.Dispose();
        reconnectLock.Dispose();
    }
}

internal static class SecureKeyStore
{
    // Keep the existing credential target so v5 automatically sees the key saved by v4.x.
    private const string TargetName = "ALAD.Windows.V2.GeminiApiKey";
    private const uint CredTypeGeneric = 1;
    private const uint CredPersistLocalMachine = 2;

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct Credential
    {
        public uint Flags;
        public uint Type;
        public string TargetName;
        public string? Comment;
        public System.Runtime.InteropServices.ComTypes.FILETIME LastWritten;
        public uint CredentialBlobSize;
        public IntPtr CredentialBlob;
        public uint Persist;
        public uint AttributeCount;
        public IntPtr Attributes;
        public string? TargetAlias;
        public string UserName;
    }

    [DllImport("advapi32.dll", EntryPoint = "CredWriteW", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool CredWrite(ref Credential credential, uint flags);

    [DllImport("advapi32.dll", EntryPoint = "CredReadW", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool CredRead(string target, uint type, uint reservedFlag, out IntPtr credentialPtr);

    [DllImport("advapi32.dll", EntryPoint = "CredDeleteW", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool CredDelete(string target, uint type, uint flags);

    [DllImport("advapi32.dll")]
    private static extern void CredFree(IntPtr buffer);

    public static void Save(string value)
    {
        if (string.IsNullOrWhiteSpace(value)) { Delete(); return; }
        byte[] bytes = Encoding.Unicode.GetBytes(value);
        IntPtr blob = Marshal.AllocCoTaskMem(bytes.Length);
        try
        {
            Marshal.Copy(bytes, 0, blob, bytes.Length);
            var credential = new Credential
            {
                Type = CredTypeGeneric,
                TargetName = TargetName,
                CredentialBlobSize = (uint)bytes.Length,
                CredentialBlob = blob,
                Persist = CredPersistLocalMachine,
                UserName = Environment.UserName
            };
            if (!CredWrite(ref credential, 0))
                throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());
        }
        finally { Marshal.FreeCoTaskMem(blob); }
    }

    public static string? Load()
    {
        if (!CredRead(TargetName, CredTypeGeneric, 0, out var ptr)) return null;
        try
        {
            var credential = Marshal.PtrToStructure<Credential>(ptr);
            if (credential.CredentialBlob == IntPtr.Zero || credential.CredentialBlobSize == 0) return null;
            return Marshal.PtrToStringUni(credential.CredentialBlob, checked((int)credential.CredentialBlobSize / 2));
        }
        finally { CredFree(ptr); }
    }

    public static void Delete()
    {
        if (!CredDelete(TargetName, CredTypeGeneric, 0))
        {
            int error = Marshal.GetLastWin32Error();
            if (error != 1168) throw new System.ComponentModel.Win32Exception(error);
        }
    }
}
