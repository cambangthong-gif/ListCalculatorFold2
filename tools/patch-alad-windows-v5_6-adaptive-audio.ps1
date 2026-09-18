$ErrorActionPreference = 'Stop'

# v5.6 builds on v5.5: stable compact UI + language skip + clear toggle state.
& "$PSScriptRoot/patch-alad-windows-v5_5-smooth-dub.ps1"

$p = 'alad-windows-v2/Program.cs'
$c = Get-Content $p -Raw
$nl = [Environment]::NewLine

$c = $c.Replace('ALAD Windows v5.5 Smooth Dub', 'ALAD Windows v5.6 Adaptive Audio')

# INPUT LATENCY:
# Translation model explicitly recommends 100ms. General Live audio recommends 20-40ms.
# Keep 3.5 at 100ms, return 3.8 to 40ms for lower input latency.
$c = $c.Replace(
    'SetChunkMs(SelectedLiveMode() == LiveMode.FastTranslate ? 100 : 80)',
    'SetChunkMs(SelectedLiveMode() == LiveMode.FastTranslate ? 100 : 40)'
)

# Do not allow seconds of stale source audio to build up when the network hiccups.
# 8 chunks = ~800ms on 3.5, ~320ms on 3.8.
$c = $c.Replace('BoundedChannelOptions(24)', 'BoundedChannelOptions(8)')

# Add adaptive output jitter state.
$fieldNeedle = '    private BufferedWaveProvider? outputBuffer;'
if (-not $c.Contains($fieldNeedle)) { throw 'outputBuffer field marker missing' }
$c = $c.Replace($fieldNeedle, $fieldNeedle + $nl + '    private AdaptiveJitterWaveProvider? smoothOutput;')

# BufferedWaveProvider must expose actual availability to our jitter provider instead of fabricating silence.
$c = $c.Replace(
@'
            outputBuffer = new BufferedWaveProvider(new WaveFormat(24000, 16, 1))
            {
                DiscardOnBufferOverflow = true,
                ReadFully = true
            };
'@,
@'
            outputBuffer = new BufferedWaveProvider(new WaveFormat(24000, 16, 1))
            {
                DiscardOnBufferOverflow = true,
                ReadFully = false,
                BufferDuration = TimeSpan.FromSeconds(3)
            };
            smoothOutput = new AdaptiveJitterWaveProvider(outputBuffer, 120, 240);
'@
)

# Player consumes adaptive jitter wrapper.
$c = $c.Replace('            player.Init(outputBuffer);', '            player.Init(smoothOutput);')

# Notify jitter buffer after each model-audio write.
$c = $c.Replace(
    '        outputBuffer.AddSamples(pcm24k, 0, pcm24k.Length);',
    '        outputBuffer.AddSamples(pcm24k, 0, pcm24k.Length);' + $nl +
    '        smoothOutput?.NotifyWrite();'
)

# Clearing a turn must also put the adaptive provider back into prebuffer mode.
$c = $c.Replace(
    '            try { outputBuffer?.ClearBuffer(); } catch { }' + $nl +
    '            delayedBurstActive = false;',
    '            try { outputBuffer?.ClearBuffer(); } catch { }' + $nl +
    '            smoothOutput?.Reset();' + $nl +
    '            delayedBurstActive = false;'
)

# Stop/reset jitter provider.
$c = $c.Replace(
    '        outputBuffer = null;' + $nl +
    '        ducker?.Restore();',
    '        outputBuffer = null;' + $nl +
    '        smoothOutput = null;' + $nl +
    '        ducker?.Restore();'
)

# Less destructive catch-up. Only emergency-trim if the translated audio is very far behind.
$c = $c.Replace(
    'int maxBacklog = lowLatency.Checked ? 850 : 1400;',
    'int maxBacklog = lowLatency.Checked ? 1600 : 2200;'
)
$c = $c.Replace(
    'int keepMs = lowLatency.Checked ? 320 : 500;',
    'int keepMs = lowLatency.Checked ? 900 : 1200;'
)

# Use pooled memory for the rare emergency trim instead of allocating a large trash array.
$trimOld = @'
                    var trash = new byte[dropBytes];
                    outputBuffer.Read(trash.AsSpan());
'@
$trimNew = @'
                    byte[] trash = ArrayPool<byte>.Shared.Rent(dropBytes);
                    try { outputBuffer.Read(trash.AsSpan(0, dropBytes)); }
                    finally { ArrayPool<byte>.Shared.Return(trash); }
'@
$c = $c.Replace($trimOld, $trimNew)

# Telemetry shows both real backlog and adaptive jitter target/underrun count.
$c = $c.Replace(
    '        if (outputBuffer != null) latency.Text = $"Buffer: {BufferedMs(outputBuffer)} ms";',
    '        if (outputBuffer != null)' + $nl +
    '        {' + $nl +
    '            int target = smoothOutput?.TargetMs ?? 0;' + $nl +
    '            int underruns = smoothOutput?.Underruns ?? 0;' + $nl +
    '            latency.Text = $"Đệm: {BufferedMs(outputBuffer)} ms · Jitter {target} ms · hụt {underruns}";' + $nl +
    '        }'
)

# 3.8 dubbing is a continuous interpreter use-case: source speech must not barge-in and cut generated dubbing.
# Current API supports NO_INTERRUPTION in realtimeInputConfig.
$customNeedle = '                systemInstruction = new'
$customReplacement = @'
                realtimeInputConfig = new
                {
                    automaticActivityDetection = new
                    {
                        disabled = false,
                        startOfSpeechSensitivity = "START_SENSITIVITY_HIGH",
                        endOfSpeechSensitivity = "END_SENSITIVITY_LOW",
                        prefixPaddingMs = 40,
                        silenceDurationMs = 220
                    },
                    activityHandling = "NO_INTERRUPTION"
                },
                systemInstruction = new
'@
if (-not $c.Contains($customNeedle)) { throw 'Gemini 3.8 systemInstruction marker missing' }
$c = $c.Replace($customNeedle, $customReplacement)

# Live Translate is continuous-stream translation, not a conversational barge-in flow.
# Splice by stable surrounding anchors instead of fragile whitespace regex.
$interruptStart = $c.IndexOf('            if ((sc.TryGetProperty("interrupted"')
$interruptEnd = $c.IndexOf('            if (sc.TryGetProperty("inputTranscription"', $interruptStart)
if ($interruptStart -lt 0 -or $interruptEnd -lt 0) { throw 'interrupted handler anchors missing' }
$interruptReplacement = @'
            if ((sc.TryGetProperty("interrupted", out var interrupted) && interrupted.ValueKind == JsonValueKind.True))
            {
                if (mode == LiveMode.CustomVoice)
                    clearPlayback();
                else
                    status("Live Translate tiếp tục luồng · bỏ qua tín hiệu interruption");
            }

'@
$c = $c.Substring(0, $interruptStart) + $interruptReplacement + $c.Substring($interruptEnd)

# Reconnect proactively on GoAway while the session-resumption handle is still available.
$goStart = $c.IndexOf('            if (root.TryGetProperty("goAway"')
$goEnd = $c.IndexOf('            if (root.TryGetProperty("error"', $goStart)
if ($goStart -lt 0 -or $goEnd -lt 0) { throw 'GoAway handler anchors missing' }
$goReplacement = @'
            if (root.TryGetProperty("goAway", out _) || root.TryGetProperty("go_away", out _))
            {
                status("Gemini chuyển phiên · đang nối lại an toàn...");
                var token = cts?.Token ?? CancellationToken.None;
                if (!stopping && !token.IsCancellationRequested)
                    _ = Task.Run(() => ReconnectLoop(token), token);
                return;
            }

'@
$c = $c.Substring(0, $goStart) + $goReplacement + $c.Substring($goEnd)

# Smooth source-volume ducking instead of abrupt jumps that sound like pumping.
$setVolumeOld = '                s.SimpleAudioVolume.Volume = Math.Clamp(original[pid] * factor, 0f, 1f);'
$setVolumeNew = @'
                float targetVolume = Math.Clamp(original[pid] * factor, 0f, 1f);
                float currentVolume = s.SimpleAudioVolume.Volume;
                float blend = factor < baseFactor ? 0.55f : 0.32f;
                float nextVolume = currentVolume + (targetVolume - currentVolume) * blend;
                if (Math.Abs(nextVolume - targetVolume) < 0.01f) nextVolume = targetVolume;
                s.SimpleAudioVolume.Volume = Math.Clamp(nextVolume, 0f, 1f);
'@
if (-not $c.Contains($setVolumeOld)) { throw 'AudioDucker volume assignment marker missing' }
$c = $c.Replace($setVolumeOld, $setVolumeNew)

# Keep main status compact. Full diagnostics still go to transcript.
$logOld = '    private void LogStatus(string text) => Ui(() => status.Text = text);'
$logNew = @'
    private void LogStatus(string text) => Ui(() =>
    {
        if (!string.IsNullOrWhiteSpace(text))
        {
            transcript.AppendText($"[Hệ thống] {text}\\r\\n");
            if (transcript.TextLength > 12000) transcript.Text = transcript.Text[^8000..];
        }

        string lower = text.ToLowerInvariant();
        if (lower.Contains("lỗi") || lower.Contains("mất kết nối") || lower.Contains("reconnect") || lower.Contains("không khởi động"))
        {
            status.Text = "⚠ " + (text.Length > 52 ? text[..52] + "…" : text);
            status.ForeColor = Color.FromArgb(220, 120, 50);
        }
        else if (isRunning)
        {
            status.Text = "● ĐANG LỒNG TIẾNG";
            status.ForeColor = Color.FromArgb(32, 165, 90);
        }
    });
'@
if (-not $c.Contains($logOld)) { throw 'LogStatus marker missing' }
$c = $c.Replace($logOld, $logNew)

# Adaptive jitter provider: prebuffers only model output, never source input.
# It starts at 120ms, raises target after genuine active-stream underruns, and slowly lowers it when stable.
$jitter = @'

internal sealed class AdaptiveJitterWaveProvider : IWaveProvider
{
    private readonly BufferedWaveProvider source;
    private readonly int minTargetMs;
    private readonly int maxTargetMs;
    private int targetMs;
    private int underruns;
    private bool buffering = true;
    private long lastWriteTicks;
    private long lastUnderrunTicks;
    private long lastAdjustTicks;

    public AdaptiveJitterWaveProvider(BufferedWaveProvider source, int minTargetMs, int maxTargetMs)
    {
        this.source = source;
        this.minTargetMs = Math.Clamp(minTargetMs, 60, 500);
        this.maxTargetMs = Math.Max(this.minTargetMs, maxTargetMs);
        targetMs = this.minTargetMs;
        long now = DateTime.UtcNow.Ticks;
        lastUnderrunTicks = now;
        lastAdjustTicks = now;
    }

    public WaveFormat WaveFormat => source.WaveFormat;
    public int TargetMs => Volatile.Read(ref targetMs);
    public int Underruns => Volatile.Read(ref underruns);

    public void NotifyWrite() => Interlocked.Exchange(ref lastWriteTicks, DateTime.UtcNow.Ticks);

    public void Reset()
    {
        buffering = true;
        Interlocked.Exchange(ref lastWriteTicks, 0);
    }

    public int Read(Span<byte> buffer)
    {
        byte[] temp = ArrayPool<byte>.Shared.Rent(buffer.Length);
        try
        {
            int read = Read(temp, 0, buffer.Length);
            temp.AsSpan(0, read).CopyTo(buffer);
            return read;
        }
        finally
        {
            ArrayPool<byte>.Shared.Return(temp);
        }
    }

    public int Read(byte[] buffer, int offset, int count)
    {
        long now = DateTime.UtcNow.Ticks;
        int bufferedMs = source.WaveFormat.AverageBytesPerSecond == 0
            ? 0
            : (int)(1000L * source.BufferedBytes / source.WaveFormat.AverageBytesPerSecond);

        if (buffering)
        {
            long lastWrite = Interlocked.Read(ref lastWriteTicks);
            double sinceWriteMs = lastWrite == 0 ? 0 : TimeSpan.FromTicks(Math.Max(0, now - lastWrite)).TotalMilliseconds;

            // Give normal network chunks a short chance to accumulate, but never trap a short utterance.
            if (source.BufferedBytes == 0 || (bufferedMs < TargetMs && sinceWriteMs < 90))
            {
                Array.Clear(buffer, offset, count);
                return count;
            }

            buffering = false;
        }

        int before = source.BufferedBytes;
        int read = source.Read(buffer.AsSpan(offset, count));
        if (read < count)
        {
            Array.Clear(buffer, offset + read, count - read);

            long lastWrite = Interlocked.Read(ref lastWriteTicks);
            double sinceWriteMs = lastWrite == 0
                ? double.MaxValue
                : TimeSpan.FromTicks(Math.Max(0, now - lastWrite)).TotalMilliseconds;

            // Count only an active-stream underrun, not the natural end of an utterance.
            long lastUnderrun = Interlocked.Read(ref lastUnderrunTicks);
            if (before > 0 && sinceWriteMs < 140 && TimeSpan.FromTicks(Math.Max(0, now - lastUnderrun)).TotalMilliseconds > 450)
            {
                Interlocked.Increment(ref underruns);
                Interlocked.Exchange(ref lastUnderrunTicks, now);
                int current = TargetMs;
                Volatile.Write(ref targetMs, Math.Min(maxTargetMs, current + 20));
            }

            if (source.BufferedBytes == 0)
                buffering = true;
        }

        // If playback has been stable for a while, slowly move back toward the low-latency target.
        long lastU = Interlocked.Read(ref lastUnderrunTicks);
        long lastA = Interlocked.Read(ref lastAdjustTicks);
        if (TargetMs > minTargetMs &&
            TimeSpan.FromTicks(Math.Max(0, now - lastU)).TotalSeconds > 12 &&
            TimeSpan.FromTicks(Math.Max(0, now - lastA)).TotalSeconds > 8)
        {
            Volatile.Write(ref targetMs, Math.Max(minTargetMs, TargetMs - 10));
            Interlocked.Exchange(ref lastAdjustTicks, now);
        }

        return count;
    }
}
'@

$insertMarker = 'internal sealed class LocalSilenceDetector'
if (-not $c.Contains($insertMarker)) { throw 'jitter insertion marker missing' }
$c = $c.Replace($insertMarker, $jitter.Trim() + $nl + $nl + $insertMarker)

Set-Content $p $c -Encoding UTF8

$c = Get-Content $p -Raw
if ($c -notmatch 'ALAD Windows v5\.6 Adaptive Audio') { throw 'v5.6 label missing' }
if ($c -notmatch 'AdaptiveJitterWaveProvider') { throw 'adaptive jitter provider missing' }
if ($c -notmatch 'BoundedChannelOptions\(8\)') { throw 'fresh input queue missing' }
if ($c -notmatch '\? 100 : 40') { throw 'model-specific chunk timing missing' }
if ($c -notmatch 'activityHandling = "NO_INTERRUPTION"') { throw '3.8 NO_INTERRUPTION missing' }
if ($c -notmatch 'mode == LiveMode\.CustomVoice') { throw 'mode-aware interruption handling missing' }
if ($c -notmatch 'Gemini chuyển phiên') { throw 'proactive GoAway reconnect missing' }
if ($c -notmatch 'blend = factor < baseFactor') { throw 'smooth ducking missing' }
if ($c -notmatch 'echoTargetLanguage = false') { throw 'target-language silence lost' }
Write-Host 'ALAD Windows v5.6 Adaptive Audio patch verified.'
