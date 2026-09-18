$ErrorActionPreference = 'Stop'

# v6.0 builds on the long-session desktop backend.
& "$PSScriptRoot/patch-alad-windows-v5_7-long-session.ps1"

$p = 'alad-windows-v2/Program.cs'
$c = Get-Content $p -Raw
$nl = [Environment]::NewLine

$c = $c.Replace('ALAD Windows v5.7 Stable Long Session', 'ALAD Windows v6.0 Hybrid Dubbing')

# Networking / collections for the localhost browser bridge.
if ($c -notmatch 'using System\.Net;') {
    $c = $c.Replace('using System.Net.WebSockets;', 'using System.Net;' + $nl + 'using System.Net.WebSockets;')
}
if ($c -notmatch 'using System\.Collections\.Concurrent;') {
    $c = $c.Replace('using System.Buffers;', 'using System.Buffers;' + $nl + 'using System.Collections.Concurrent;')
}

# Sentinel source shown above normal WASAPI sources.
$processMarker = 'internal sealed class ProcessItem'
$hybridClass = @'
internal sealed class BrowserHybridItem
{
    public override string ToString() => "Hybrid YouTube / Browser — phụ đề + sync trình phát";
}

'@
if (-not $c.Contains($processMarker)) { throw 'ProcessItem marker missing' }
$c = $c.Replace($processMarker, $hybridClass + $processMarker)

# MainForm state for bridge + scheduler.
$fieldMarker = '    private bool delayedBurstActive;'
$fieldAdd = @'
    private bool delayedBurstActive;
    private BrowserBridge? browserBridge;
    private CaptionDubbingScheduler? captionScheduler;
    private bool hybridModeActive;
    private string hybridVideoId = "";
    private double hybridVideoTime;
    private double hybridPlaybackRate = 1.0;
'@
if (-not $c.Contains($fieldMarker)) { throw 'MainForm field marker missing' }
$c = $c.Replace($fieldMarker, $fieldAdd.TrimEnd())

# Source list: hybrid option first, then normal system/process loopback.
$refreshNeedle = @'
        source.Items.Clear();
        source.Items.Add("Toàn hệ thống — trừ ALAD để tránh vọng tiếng");
'@
$refreshReplacement = @'
        source.Items.Clear();
        source.Items.Add(new BrowserHybridItem());
        source.Items.Add("Toàn hệ thống — trừ ALAD để tránh vọng tiếng");
'@
if (-not $c.Contains($refreshNeedle)) { throw 'RefreshProcesses source marker missing' }
$c = $c.Replace($refreshNeedle, $refreshReplacement)

# Because one extra item was inserted, process scanning / old PID restoration begins at index 2.
$c = $c.Replace('            for (int i = 1; i < source.Items.Count; i++)', '            for (int i = 2; i < source.Items.Count; i++)')

# Hybrid uses Gemini 3.8 text input. Selecting it makes the correct engine obvious.
$bindMarker = '        refresh.Click += (_, _) => RefreshProcesses();'
$bindAdd = @'
        refresh.Click += (_, _) => RefreshProcesses();
        source.SelectedIndexChanged += (_, _) =>
        {
            if (source.SelectedItem is BrowserHybridItem && !isRunning)
            {
                liveMode.SelectedIndex = 1;
                voice.Enabled = true;
                inputState.Text = "Hybrid: cần tiện ích ALAD Browser Bridge";
            }
        };
'@
if (-not $c.Contains($bindMarker)) { throw 'BindEvents refresh marker missing' }
$c = $c.Replace($bindMarker, $bindAdd.TrimEnd())

# StartAsync: derive actual engine and launch localhost bridge before Gemini.
$connectNeedle = @'
            gemini = new LiveGeminiClient(LogStatus, OnGeminiAudio, OnTranscript, ClearOutputBuffer);
            await gemini.ConnectAsync(
                apiKey.Text.Trim(),
                TargetLanguageCode(),
                SelectedLiveMode(),
                voice.SelectedItem?.ToString() ?? "Kore",
                runCts.Token);

            uint targetPid;
'@
$connectReplacement = @'
            hybridModeActive = source.SelectedItem is BrowserHybridItem;
            LiveMode effectiveMode = hybridModeActive ? LiveMode.CustomVoice : SelectedLiveMode();

            gemini = new LiveGeminiClient(LogStatus, OnGeminiAudio, OnTranscript, ClearOutputBuffer);
            await gemini.ConnectAsync(
                apiKey.Text.Trim(),
                TargetLanguageCode(),
                effectiveMode,
                voice.SelectedItem?.ToString() ?? "Kore",
                runCts.Token);

            if (hybridModeActive)
            {
                browserBridge = new BrowserBridge(37921, LogStatus);
                browserBridge.Message += OnBrowserBridgeMessage;
                await browserBridge.StartAsync(runCts.Token);

                captionScheduler = new CaptionDubbingScheduler(
                    text => gemini.QueueCaption(text),
                    () => hybridVideoTime,
                    LogStatus);

                await browserBridge.BroadcastAsync(new
                {
                    type = "config",
                    targetLanguage = TargetLanguageCode(),
                    originalVolume = originalVolume.Value / 100.0
                });

                isRunning = true;
                status.Text = "● HYBRID ĐANG CHẠY";
                status.ForeColor = Color.FromArgb(32, 165, 90);
                inputState.Text = "Hybrid: chờ YouTube / phụ đề";
                return;
            }

            uint targetPid;
'@
if (-not $c.Contains($connectNeedle)) { throw 'StartAsync Gemini marker missing' }
$c = $c.Replace($connectNeedle, $connectReplacement)

# Browser-driven playback and caption handling.
$buildRecorderMarker = '    private static async Task<WasapiRecorder> BuildRecorderAsync'
$browserMethods = @'
    private void OnBrowserBridgeMessage(BrowserBridgeMessage message)
    {
        if (!hybridModeActive || runCts?.IsCancellationRequested != false) return;

        Ui(() =>
        {
            switch (message.Type)
            {
                case "hello":
                    inputState.Text = "Hybrid: tiện ích đã kết nối";
                    break;

                case "video":
                    if (!string.Equals(hybridVideoId, message.VideoId, StringComparison.Ordinal))
                    {
                        hybridVideoId = message.VideoId ?? "";
                        hybridVideoTime = message.CurrentTime;
                        captionScheduler?.Reset();
                        ClearOutputBuffer();
                        inputState.Text = "Hybrid: video mới";
                    }
                    break;

                case "state":
                    hybridVideoTime = message.CurrentTime;
                    hybridPlaybackRate = message.PlaybackRate <= 0 ? 1.0 : message.PlaybackRate;
                    if (message.Paused)
                    {
                        try { player?.Pause(); } catch { }
                        inputState.Text = $"Hybrid: tạm dừng · {hybridVideoTime:0.0}s";
                    }
                    else
                    {
                        try { player?.Play(); } catch { }
                        inputState.Text = $"Hybrid: đang phát {hybridPlaybackRate:0.##}× · {hybridVideoTime:0.0}s";
                    }
                    break;

                case "seek":
                    hybridVideoTime = message.CurrentTime;
                    captionScheduler?.Reset();
                    ClearOutputBuffer();
                    inputState.Text = $"Hybrid: tua đến {hybridVideoTime:0.0}s";
                    break;

                case "caption":
                    hybridVideoTime = message.CurrentTime;
                    if (!string.IsNullOrWhiteSpace(message.Text))
                        captionScheduler?.Push(message.Text!, message.CurrentTime, message.Duration);
                    break;
            }
        });
    }

    private void SetHybridOriginalVolume(bool aiSpeaking)
    {
        if (!hybridModeActive || browserBridge == null) return;

        double baseVolume = originalVolume.Value / 100.0;
        double factor = 1.0;
        if (aiSpeaking)
        {
            factor = SelectedMixMode() switch
            {
                MixMode.AutoDucking => 0.22,
                MixMode.VoiceOver => 0.45,
                MixMode.FullDub => 0.05,
                _ => 1.0
            };
        }

        _ = browserBridge.BroadcastAsync(new
        {
            type = "setVolume",
            value = Math.Clamp(baseVolume * factor, 0.0, 1.0)
        });
    }

'@
if (-not $c.Contains($buildRecorderMarker)) { throw 'BuildRecorder marker missing' }
$c = $c.Replace($buildRecorderMarker, $browserMethods + $buildRecorderMarker)

# Extend ducking: browser bridge controls the HTML5 video's own volume directly.
$duckStart = @'
    private void ApplyDucking(bool aiSpeaking)
    {
        if (ducker == null) return;
'@
$duckNew = @'
    private void ApplyDucking(bool aiSpeaking)
    {
        if (hybridModeActive)
        {
            SetHybridOriginalVolume(aiSpeaking);
            if (!aiSpeaking) delayedBurstActive = false;
            return;
        }

        if (ducker == null) return;
'@
if (-not $c.Contains($duckStart)) { throw 'ApplyDucking marker missing' }
$c = $c.Replace($duckStart, $duckNew)

# Stop bridge/scheduler and restore browser video volume to full before disposal.
$stopMarker = @'
        try { if (gemini != null) await gemini.DisposeAsync(); } catch { }
        gemini = null;
'@
$stopReplacement = @'
        if (browserBridge != null)
        {
            try { await browserBridge.BroadcastAsync(new { type = "setVolume", value = 1.0 }); } catch { }
            try { await browserBridge.DisposeAsync(); } catch { }
        }
        browserBridge = null;
        captionScheduler?.Dispose();
        captionScheduler = null;
        hybridModeActive = false;
        hybridVideoId = "";
        hybridVideoTime = 0;
        hybridPlaybackRate = 1.0;

        try { if (gemini != null) await gemini.DisposeAsync(); } catch { }
        gemini = null;
'@
if (-not $c.Contains($stopMarker)) { throw 'StopAsync Gemini marker missing' }
$c = $c.Replace($stopMarker, $stopReplacement)

# When the user changes original volume while hybrid mode is active, apply it to YouTube.
$volumeMarker = '            if (ducker != null)'
$volumeReplacement = @'
            if (hybridModeActive)
            {
                SetHybridOriginalVolume((DateTime.UtcNow - lastAiAudioUtc).TotalMilliseconds <= 650);
            }
            else if (ducker != null)
'@
$c = $c.Replace($volumeMarker, $volumeReplacement)

# Telemetry: keep hybrid state instead of reporting WASAPI silence.
$telemetryNeedle = @'
        if (isRunning)
        {
            inputState.Text = (DateTime.UtcNow - lastInputAudioUtc).TotalMilliseconds < 1000
                ? "Audio vào: đang chạy"
                : "Audio vào: im lặng";
        }
'@
$telemetryReplacement = @'
        if (isRunning && !hybridModeActive)
        {
            inputState.Text = (DateTime.UtcNow - lastInputAudioUtc).TotalMilliseconds < 1000
                ? "Audio vào: đang chạy"
                : "Audio vào: im lặng";
        }
'@
if (-not $c.Contains($telemetryNeedle)) { throw 'UpdateUiTelemetry input marker missing' }
$c = $c.Replace($telemetryNeedle, $telemetryReplacement)

# LiveGeminiClient: a bounded caption channel and sequential text-turn loop.
$queueMarker = @'
    private readonly SemaphoreSlim sendLock = new(1, 1);
    private readonly SemaphoreSlim reconnectLock = new(1, 1);
'@
$queueAdd = @'
    private readonly SemaphoreSlim sendLock = new(1, 1);
    private readonly SemaphoreSlim reconnectLock = new(1, 1);
    private readonly Channel<string> captionQueue = Channel.CreateBounded<string>(new BoundedChannelOptions(8)
    {
        FullMode = BoundedChannelFullMode.DropOldest,
        SingleReader = true,
        SingleWriter = false
    });
'@
if (-not $c.Contains($queueMarker)) { throw 'LiveGeminiClient semaphore marker missing' }
$c = $c.Replace($queueMarker, $queueAdd.TrimEnd())

$taskFieldMarker = '    private Task? sendTask;'
$taskFieldAdd = @'
    private Task? sendTask;
    private Task? captionTask;
    private TaskCompletionSource<bool>? captionTurnDone;
'@
if (-not $c.Contains($taskFieldMarker)) { throw 'sendTask field marker missing' }
$c = $c.Replace($taskFieldMarker, $taskFieldAdd.TrimEnd())

# Start caption sender along with audio sender.
$sendTaskMarker = '        sendTask = Task.Run(() => SendLoop(cts.Token), cts.Token);'
$sendTaskAdd = @'
        sendTask = Task.Run(() => SendLoop(cts.Token), cts.Token);
        captionTask = Task.Run(() => CaptionLoop(cts.Token), cts.Token);
'@
if (-not $c.Contains($sendTaskMarker)) { throw 'sendTask init marker missing' }
$c = $c.Replace($sendTaskMarker, $sendTaskAdd.TrimEnd())

# Public caption enqueue and caption send loop.
$queueAudioMarker = '    public void QueueAudio(byte[] pcm16k) => sendQueue.Writer.TryWrite(pcm16k);'
$captionCode = @'
    public void QueueAudio(byte[] pcm16k) => sendQueue.Writer.TryWrite(pcm16k);

    public void QueueCaption(string text)
    {
        if (mode != LiveMode.CustomVoice || string.IsNullOrWhiteSpace(text)) return;
        captionQueue.Writer.TryWrite(text.Trim());
    }

    private async Task CaptionLoop(CancellationToken ct)
    {
        await foreach (var caption in captionQueue.Reader.ReadAllAsync(ct))
        {
            try
            {
                ClientWebSocket? socket = null;
                for (int wait = 0; wait < 80 && !ct.IsCancellationRequested; wait++)
                {
                    socket = ws;
                    if (socket?.State == WebSocketState.Open && setupReady?.Task.IsCompletedSuccessfully == true)
                        break;
                    await Task.Delay(25, ct);
                }

                if (socket?.State != WebSocketState.Open || setupReady?.Task.IsCompletedSuccessfully != true)
                    continue;

                captionTurnDone = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);

                var payload = JsonSerializer.Serialize(new
                {
                    clientContent = new
                    {
                        turns = new[]
                        {
                            new
                            {
                                role = "user",
                                parts = new[] { new { text = caption } }
                            }
                        },
                        turnComplete = true
                    }
                });

                await SendTextAsync(socket, payload, ct);

                using var turnTimeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
                turnTimeout.CancelAfter(TimeSpan.FromSeconds(7));
                try { await captionTurnDone.Task.WaitAsync(turnTimeout.Token); }
                catch (OperationCanceledException) when (!ct.IsCancellationRequested)
                {
                    status("Hybrid: câu dịch quá lâu · chuyển sang phụ đề mới");
                }
            }
            catch (OperationCanceledException) { break; }
            catch (Exception ex)
            {
                status("Hybrid gửi phụ đề lỗi: " + ex.Message);
            }
        }
    }
'@
if (-not $c.Contains($queueAudioMarker)) { throw 'QueueAudio marker missing' }
$c = $c.Replace($queueAudioMarker, $captionCode.TrimEnd())

# For hybrid text turns, server turnComplete releases the next queued caption.
$serverContentMarker = @'
            if (!(root.TryGetProperty("serverContent", out var sc) || root.TryGetProperty("server_content", out sc)))
                return;
'@
$serverContentNew = @'
            if (!(root.TryGetProperty("serverContent", out var sc) || root.TryGetProperty("server_content", out sc)))
                return;

            if ((sc.TryGetProperty("turnComplete", out var done) || sc.TryGetProperty("turn_complete", out done)) &&
                done.ValueKind == JsonValueKind.True)
            {
                captionTurnDone?.TrySetResult(true);
            }
'@
if (-not $c.Contains($serverContentMarker)) { throw 'serverContent marker missing' }
$c = $c.Replace($serverContentMarker, $serverContentNew.TrimEnd())

# Hybrid-specific system instruction: text captions are authoritative source segments.
$instructionNeedle = 'Act only as a real-time interpreter. Translate every spoken utterance into {targetLang}.'
$instructionReplacement = 'Act only as a real-time interpreter. Translate every source segment, whether spoken audio or caption text, into {targetLang}.'
if (-not $c.Contains($instructionNeedle)) { throw '3.8 system instruction marker missing' }
$c = $c.Replace($instructionNeedle, $instructionReplacement)

# Dispose caption channel/task.
$disposeWriter = '        sendQueue.Writer.TryComplete();'
$c = $c.Replace($disposeWriter, $disposeWriter + $nl + '        captionQueue.Writer.TryComplete();')
$disposeTaskMarker = '        try { if (sendTask != null) await sendTask; } catch { }'
$c = $c.Replace($disposeTaskMarker, $disposeTaskMarker + $nl + '        try { if (captionTask != null) await captionTask; } catch { }')

# Browser bridge + scheduler classes.
$insertMarker = 'internal sealed class AdaptiveJitterWaveProvider'
$hybridBackend = @'
internal sealed record BrowserBridgeMessage(
    string Type,
    string? Text,
    string? VideoId,
    double CurrentTime,
    double Duration,
    double PlaybackRate,
    bool Paused);

internal sealed class BrowserBridge : IAsyncDisposable
{
    private readonly HttpListener listener = new();
    private readonly ConcurrentDictionary<Guid, WebSocket> clients = new();
    private readonly Action<string> log;
    private CancellationTokenSource? cts;
    private Task? acceptTask;

    public event Action<BrowserBridgeMessage>? Message;

    public BrowserBridge(int port, Action<string> log)
    {
        this.log = log;
        listener.Prefixes.Add($"http://127.0.0.1:{port}/alad/");
    }

    public Task StartAsync(CancellationToken externalToken)
    {
        cts = CancellationTokenSource.CreateLinkedTokenSource(externalToken);
        listener.Start();
        acceptTask = Task.Run(() => AcceptLoop(cts.Token), cts.Token);
        log("Hybrid bridge: ws://127.0.0.1:37921/alad/");
        return Task.CompletedTask;
    }

    private async Task AcceptLoop(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            HttpListenerContext ctx;
            try { ctx = await listener.GetContextAsync().WaitAsync(ct); }
            catch (OperationCanceledException) { break; }
            catch (Exception ex)
            {
                if (!ct.IsCancellationRequested) log("Hybrid bridge lỗi: " + ex.Message);
                break;
            }

            if (!ctx.Request.IsWebSocketRequest)
            {
                ctx.Response.StatusCode = 400;
                ctx.Response.Close();
                continue;
            }

            try
            {
                var accepted = await ctx.AcceptWebSocketAsync(null);
                var id = Guid.NewGuid();
                clients[id] = accepted.WebSocket;
                log("Hybrid: tiện ích trình duyệt đã kết nối");
                _ = Task.Run(() => ClientLoop(id, accepted.WebSocket, ct), ct);
            }
            catch (Exception ex) { log("Hybrid WebSocket lỗi: " + ex.Message); }
        }
    }

    private async Task ClientLoop(Guid id, WebSocket socket, CancellationToken ct)
    {
        byte[] buffer = ArrayPool<byte>.Shared.Rent(32 * 1024);
        using var ms = new MemoryStream(32 * 1024);
        try
        {
            while (!ct.IsCancellationRequested && socket.State == WebSocketState.Open)
            {
                ms.SetLength(0);
                WebSocketReceiveResult result;
                do
                {
                    result = await socket.ReceiveAsync(buffer, ct);
                    if (result.MessageType == WebSocketMessageType.Close) return;
                    if (result.Count > 0) ms.Write(buffer, 0, result.Count);
                }
                while (!result.EndOfMessage);

                if (ms.Length == 0) continue;
                ParseMessage(Encoding.UTF8.GetString(ms.GetBuffer(), 0, checked((int)ms.Length)));
            }
        }
        catch (OperationCanceledException) { }
        catch (Exception ex)
        {
            if (!ct.IsCancellationRequested) log("Hybrid bridge mất kết nối: " + ex.Message);
        }
        finally
        {
            ArrayPool<byte>.Shared.Return(buffer);
            clients.TryRemove(id, out _);
            try { socket.Dispose(); } catch { }
        }
    }

    private void ParseMessage(string json)
    {
        try
        {
            using var doc = JsonDocument.Parse(json);
            var root = doc.RootElement;
            string type = root.TryGetProperty("type", out var t) ? (t.GetString() ?? "") : "";
            string? text = root.TryGetProperty("text", out var x) ? x.GetString() : null;
            string? videoId = root.TryGetProperty("videoId", out var v) ? v.GetString() : null;
            double currentTime = root.TryGetProperty("currentTime", out var ct) && ct.TryGetDouble(out var ctv) ? ctv : 0;
            double duration = root.TryGetProperty("duration", out var d) && d.TryGetDouble(out var dv) ? dv : 0;
            double playbackRate = root.TryGetProperty("playbackRate", out var r) && r.TryGetDouble(out var rv) ? rv : 1;
            bool paused = root.TryGetProperty("paused", out var p) && p.ValueKind == JsonValueKind.True;
            Message?.Invoke(new BrowserBridgeMessage(type, text, videoId, currentTime, duration, playbackRate, paused));
        }
        catch (Exception ex) { log("Hybrid message lỗi: " + ex.Message); }
    }

    public async Task BroadcastAsync(object payload)
    {
        string json = JsonSerializer.Serialize(payload);
        byte[] bytes = Encoding.UTF8.GetBytes(json);

        foreach (var pair in clients.ToArray())
        {
            var socket = pair.Value;
            if (socket.State != WebSocketState.Open) continue;
            try { await socket.SendAsync(bytes, WebSocketMessageType.Text, true, CancellationToken.None); }
            catch { }
        }
    }

    public async ValueTask DisposeAsync()
    {
        try { cts?.Cancel(); } catch { }
        try { listener.Stop(); } catch { }
        foreach (var socket in clients.Values)
        {
            try
            {
                if (socket.State == WebSocketState.Open)
                    await socket.CloseOutputAsync(WebSocketCloseStatus.NormalClosure, "stop", CancellationToken.None);
            }
            catch { }
            try { socket.Dispose(); } catch { }
        }
        clients.Clear();
        try { if (acceptTask != null) await acceptTask; } catch { }
        cts?.Dispose();
        cts = null;
        try { listener.Close(); } catch { }
    }
}

internal sealed class CaptionDubbingScheduler : IDisposable
{
    private readonly Action<string> enqueue;
    private readonly Func<double> currentTime;
    private readonly Action<string> log;
    private readonly object gate = new();
    private readonly System.Threading.Timer timer;

    private string pending = "";
    private string lastSent = "";
    private double pendingAt;
    private double pendingDuration;

    public CaptionDubbingScheduler(Action<string> enqueue, Func<double> currentTime, Action<string> log)
    {
        this.enqueue = enqueue;
        this.currentTime = currentTime;
        this.log = log;
        timer = new System.Threading.Timer(_ => FlushIfStable(), null, 120, 120);
    }

    public void Push(string text, double at, double duration)
    {
        text = Normalize(text);
        if (text.Length == 0) return;

        lock (gate)
        {
            if (text == lastSent || text == pending) return;

            // YouTube auto-captions often grow word-by-word. Replace the pending cumulative
            // caption instead of speaking every intermediate DOM mutation.
            if (pending.Length > 0 && (text.StartsWith(pending, StringComparison.OrdinalIgnoreCase) ||
                                      pending.StartsWith(text, StringComparison.OrdinalIgnoreCase)))
            {
                pending = text.Length >= pending.Length ? text : pending;
                pendingAt = at;
                pendingDuration = duration;
                return;
            }

            // A distinct caption arrived. Send the previous complete phrase first.
            FlushLocked();
            pending = text;
            pendingAt = at;
            pendingDuration = duration;

            if (EndsPhrase(text))
                FlushLocked();
        }
    }

    private void FlushIfStable()
    {
        lock (gate)
        {
            if (pending.Length == 0) return;
            double now = currentTime();
            double age = Math.Abs(now - pendingAt);

            // Caption DOM can mutate many times during one sentence. Wait a little for
            // stability, but do not let a short segment sit indefinitely.
            if (age >= Math.Max(0.28, Math.Min(0.65, pendingDuration * 0.25)))
                FlushLocked();
        }
    }

    private void FlushLocked()
    {
        string text = pending.Trim();
        pending = "";
        if (text.Length == 0 || text == lastSent) return;

        // Avoid replaying a short overlapping tail from rolling captions.
        if (lastSent.Length > 8 && lastSent.Contains(text, StringComparison.OrdinalIgnoreCase))
            return;

        lastSent = text;
        enqueue(text);
        log("Hybrid phụ đề → Gemini: " + (text.Length > 72 ? text[..72] + "…" : text));
    }

    public void Reset()
    {
        lock (gate)
        {
            pending = "";
            lastSent = "";
            pendingAt = 0;
            pendingDuration = 0;
        }
    }

    private static bool EndsPhrase(string text)
        => text.EndsWith('.') || text.EndsWith('!') || text.EndsWith('?') ||
           text.EndsWith('。') || text.EndsWith('！') || text.EndsWith('？') ||
           text.EndsWith(':') || text.EndsWith(';');

    private static string Normalize(string text)
        => string.Join(" ", text.Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries)).Trim();

    public void Dispose() => timer.Dispose();
}

'@
if (-not $c.Contains($insertMarker)) { throw 'AdaptiveJitter insertion marker missing' }
$c = $c.Replace($insertMarker, $hybridBackend.TrimEnd() + $nl + $nl + $insertMarker)

Set-Content $p $c -Encoding UTF8

$c = Get-Content $p -Raw
if ($c -notmatch 'ALAD Windows v6\.0 Hybrid Dubbing') { throw 'v6 label missing' }
if ($c -notmatch 'Hybrid YouTube / Browser') { throw 'hybrid source missing' }
if ($c -notmatch 'BrowserBridge\(37921') { throw 'browser bridge missing' }
if ($c -notmatch 'clientContent = new') { throw 'Gemini text-turn path missing' }
if ($c -notmatch 'CaptionDubbingScheduler') { throw 'caption scheduler missing' }
if ($c -notmatch 'case "seek"') { throw 'seek sync missing' }
if ($c -notmatch 'player\?\.Pause') { throw 'pause sync missing' }
if ($c -notmatch 'contextWindowCompression') { throw 'long-session compression lost' }
if ($c -notmatch 'TimeSpan\.FromMinutes\(8\.75\)') { throw 'long-session rollover lost' }
Write-Host 'ALAD Windows v6.0 Hybrid Dubbing patch verified.'
