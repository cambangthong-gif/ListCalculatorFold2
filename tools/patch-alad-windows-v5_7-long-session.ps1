$ErrorActionPreference = 'Stop'

# v5.7 builds on v5.6: adaptive audio + stable compact UI.
& "$PSScriptRoot/patch-alad-windows-v5_6-adaptive-audio.ps1"

$p = 'alad-windows-v2/Program.cs'
$c = Get-Content $p -Raw
$nl = [Environment]::NewLine

$c = $c.Replace('ALAD Windows v5.6 Adaptive Audio', 'ALAD Windows v5.7 Stable Long Session')

# Track one authoritative socket generation. Old receive loops are not allowed to trigger
# reconnects after a newer connection has already taken over.
$fieldNeedle = '    private volatile bool stopping;'
$fieldAdd = @'
    private volatile bool stopping;
    private volatile bool sessionResumable;
    private long socketGeneration;
    private int connectionNumber;
    private Task? receiveTask;
'@
if (-not $c.Contains($fieldNeedle)) { throw 'LiveGeminiClient field marker missing' }
$c = $c.Replace($fieldNeedle, $fieldAdd.TrimEnd())

# Use a resumption handle only when the server most recently said the state is resumable.
$c = $c.Replace(
    'sessionResumption = new { handle = sessionHandle }',
    'sessionResumption = new { handle = sessionResumable ? sessionHandle : null }'
)

# v4.3's strict Live Translate setup ended after sessionResumption. Add official
# context-window compression so audio sessions are not limited by the ~15 minute
# uncompressed context lifetime.
$fastCompressionPattern = '(sessionResumption = new \{ handle = sessionResumable \? sessionHandle : null \})(\s*\r?\n\s*\};\s*\r?\n\s*\}\s*\r?\n\s*else)'
$fastCompressionReplacement = @'
$1,
                contextWindowCompression = new { slidingWindow = new { } }
$2
'@
$c2 = [regex]::Replace($c, $fastCompressionPattern, $fastCompressionReplacement.TrimEnd(), 1)
if ($c2 -eq $c) { throw '3.5 contextWindowCompression insertion failed' }
$c = $c2

# Clean socket lifecycle + proactive rollover before the ~10 minute connection lifetime.
$openPattern = '(?s)    private async Task OpenSocketAsync\(CancellationToken ct\)\s*\{.*?\r?\n    \}\r?\n\r?\n    private async Task SendSetupAsync'
$openReplacement = @'
    private static async Task CloseSocketQuietlyAsync(ClientWebSocket? socket, string reason)
    {
        if (socket == null) return;
        try
        {
            if (socket.State == WebSocketState.Open || socket.State == WebSocketState.CloseReceived)
            {
                using var closeCts = new CancellationTokenSource(TimeSpan.FromMilliseconds(650));
                await socket.CloseOutputAsync(WebSocketCloseStatus.NormalClosure, reason, closeCts.Token);
            }
        }
        catch { }
        try { socket.Abort(); } catch { }
        try { socket.Dispose(); } catch { }
    }

    private async Task OpenSocketAsync(CancellationToken ct)
    {
        status(sessionHandle == null ? "Đang kết nối Gemini..." : "Đang chuyển sang kết nối Gemini mới...");

        // Invalidate the old receive loop before closing its socket. This prevents its
        // finally block from racing a second reconnect against the intended rollover.
        long generation = Interlocked.Increment(ref socketGeneration);
        var oldSocket = ws;
        ws = null;
        await CloseSocketQuietlyAsync(oldSocket, "rotate");

        var socket = new ClientWebSocket();
        socket.Options.KeepAliveInterval = TimeSpan.FromSeconds(15);

        var uri = new Uri($"{Endpoint}?key={Uri.EscapeDataString(apiKey)}");
        await socket.ConnectAsync(uri, ct);

        ws = socket;
        setupReady = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        receiveTask = Task.Run(() => ReceiveLoop(socket, generation, ct), ct);
        await SendSetupAsync(socket, ct);

        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeout.CancelAfter(TimeSpan.FromSeconds(8));
        try
        {
            await setupReady.Task.WaitAsync(timeout.Token);
        }
        catch (OperationCanceledException) when (!ct.IsCancellationRequested)
        {
            await CloseSocketQuietlyAsync(socket, "setup-timeout");
            throw new TimeoutException("Gemini không trả setupComplete trong 8 giây.");
        }

        int number = Interlocked.Increment(ref connectionNumber);
        status($"Gemini sẵn sàng · kết nối #{number} · phiên dài");
        _ = Task.Run(() => ProactiveRolloverAsync(generation, ct), ct);
    }

    private async Task ProactiveRolloverAsync(long generation, CancellationToken ct)
    {
        try
        {
            // Google documents a connection lifetime of around 10 minutes. Rotate a bit
            // early so ALAD is not dependent on a GoAway frame arriving in time.
            await Task.Delay(TimeSpan.FromMinutes(8.75), ct);
            if (stopping || ct.IsCancellationRequested) return;
            if (generation != Interlocked.Read(ref socketGeneration)) return;

            var current = ws;
            if (current?.State != WebSocketState.Open) return;

            status("Sắp hết vòng kết nối · chuyển phiên sớm...");
            await ReconnectLoop(ct, immediate: true, reason: "định kỳ");
        }
        catch (OperationCanceledException) { }
    }

    private async Task SendSetupAsync
'@
$c2 = [regex]::Replace($c, $openPattern, $openReplacement.TrimEnd(), 1)
if ($c2 -eq $c) { throw 'OpenSocketAsync long-session replacement failed' }
$c = $c2

# ReceiveLoop gets a socket generation and only the currently authoritative socket may
# schedule a reconnect. This eliminates old-loop reconnect races.
$c = $c.Replace(
    '    private async Task ReceiveLoop(ClientWebSocket socket, CancellationToken ct)',
    '    private async Task ReceiveLoop(ClientWebSocket socket, long generation, CancellationToken ct)'
)

$receiveFinallyOld = @'
            if (!stopping && !ct.IsCancellationRequested)
                _ = Task.Run(() => ReconnectLoop(ct), ct);
'@
$receiveFinallyNew = @'
            bool stillCurrent =
                generation == Interlocked.Read(ref socketGeneration) &&
                ReferenceEquals(ws, socket);

            if (!stopping && !ct.IsCancellationRequested && stillCurrent)
                _ = Task.Run(() => ReconnectLoop(ct, immediate: false, reason: "mất kết nối"), ct);
'@
if (-not $c.Contains($receiveFinallyOld)) { throw 'ReceiveLoop finally marker missing' }
$c = $c.Replace($receiveFinallyOld, $receiveFinallyNew)

# While a clean rollover is setting up, hold the current source chunk briefly instead of
# immediately discarding it. The bounded queue still guarantees fresh audio wins.
$sendReadyOld = @'
                var socket = ws;
                if (socket?.State != WebSocketState.Open || setupReady?.Task.IsCompletedSuccessfully != true)
                    continue;
'@
$sendReadyNew = @'
                ClientWebSocket? socket = null;
                for (int wait = 0; wait < 60 && !ct.IsCancellationRequested; wait++)
                {
                    socket = ws;
                    if (socket?.State == WebSocketState.Open && setupReady?.Task.IsCompletedSuccessfully == true)
                        break;
                    await Task.Delay(25, ct);
                }

                if (socket?.State != WebSocketState.Open || setupReady?.Task.IsCompletedSuccessfully != true)
                    continue;
'@
if (-not $c.Contains($sendReadyOld)) { throw 'SendLoop readiness marker missing' }
$c = $c.Replace($sendReadyOld, $sendReadyNew)

# Do not reuse a stale session handle if the latest SessionResumptionUpdate says the
# current state is not resumable.
$srStart = $c.IndexOf('            if (root.TryGetProperty("sessionResumptionUpdate"')
$srEnd = $c.IndexOf('            if (root.TryGetProperty("goAway"', $srStart)
if ($srStart -lt 0 -or $srEnd -lt 0) { throw 'session resumption handler anchors missing' }
$srReplacement = @'
            if (root.TryGetProperty("sessionResumptionUpdate", out var sr) || root.TryGetProperty("session_resumption_update", out sr))
            {
                bool resumable = sr.TryGetProperty("resumable", out var canResume) &&
                                 canResume.ValueKind == JsonValueKind.True;
                sessionResumable = resumable;

                if (resumable && (sr.TryGetProperty("newHandle", out var h) || sr.TryGetProperty("new_handle", out h)))
                {
                    string? value = h.GetString();
                    if (!string.IsNullOrWhiteSpace(value))
                        sessionHandle = value;
                }
                return;
            }

'@
$c = $c.Substring(0, $srStart) + $srReplacement + $c.Substring($srEnd)

# GoAway contains timeLeft. Start an immediate controlled rollover instead of waiting for
# the server to terminate the socket.
$goStart = $c.IndexOf('            if (root.TryGetProperty("goAway"')
$goEnd = $c.IndexOf('            if (root.TryGetProperty("error"', $goStart)
if ($goStart -lt 0 -or $goEnd -lt 0) { throw 'GoAway handler anchors missing' }
$goReplacement = @'
            if (root.TryGetProperty("goAway", out var goAway) || root.TryGetProperty("go_away", out goAway))
            {
                string timeLeft = "";
                if (goAway.ValueKind == JsonValueKind.Object &&
                    (goAway.TryGetProperty("timeLeft", out var tl) || goAway.TryGetProperty("time_left", out tl)))
                    timeLeft = tl.ToString();

                status(string.IsNullOrWhiteSpace(timeLeft)
                    ? "Gemini báo GoAway · chuyển kết nối ngay..."
                    : $"Gemini báo GoAway ({timeLeft}) · chuyển kết nối ngay...");

                var token = cts?.Token ?? CancellationToken.None;
                if (!stopping && !token.IsCancellationRequested)
                    _ = Task.Run(() => ReconnectLoop(token, immediate: true, reason: "GoAway"), token);
                return;
            }

'@
$c = $c.Substring(0, $goStart) + $goReplacement + $c.Substring($goEnd)

# Replace reconnect state machine. GoAway/proactive rollover is immediate and preserves
# already-buffered translated audio; fault reconnects still clear stale playback.
$reconnectPattern = '(?s)    private async Task ReconnectLoop\(CancellationToken ct\)\s*\{.*?\r?\n    \}\r?\n\r?\n    public async ValueTask DisposeAsync'
$reconnectReplacement = @'
    private async Task ReconnectLoop(CancellationToken ct, bool immediate = false, string reason = "reconnect")
    {
        if (stopping || ct.IsCancellationRequested) return;
        if (!await reconnectLock.WaitAsync(0, ct)) return;

        try
        {
            if (!immediate)
                clearPlayback();

            for (int attempt = 1; !stopping && !ct.IsCancellationRequested; attempt++)
            {
                int delay = immediate && attempt == 1
                    ? 0
                    : attempt switch { 1 => 500, 2 => 1000, 3 => 2000, 4 => 4000, _ => 8000 };

                if (delay > 0)
                {
                    status($"{reason}: thử lại sau {delay / 1000.0:0.#}s...");
                    await Task.Delay(delay, ct);
                }
                else
                {
                    status($"{reason}: đang chuyển kết nối...");
                }

                try
                {
                    await OpenSocketAsync(ct);
                    status($"Đã chuyển kết nối · phiên vẫn tiếp tục · #{connectionNumber}");
                    return;
                }
                catch (OperationCanceledException) when (ct.IsCancellationRequested) { return; }
                catch (Exception ex)
                {
                    status($"{reason}: chưa nối được · {ex.Message}");
                    immediate = false;
                }
            }
        }
        finally
        {
            reconnectLock.Release();
        }
    }

    public async ValueTask DisposeAsync
'@
$c2 = [regex]::Replace($c, $reconnectPattern, $reconnectReplacement.TrimEnd(), 1)
if ($c2 -eq $c) { throw 'ReconnectLoop replacement failed' }
$c = $c2

# Dispose through the same bounded close path and wait briefly for the receive task.
$disposeCloseOld = @'
        try
        {
            if (ws?.State == WebSocketState.Open)
                await ws.CloseAsync(WebSocketCloseStatus.NormalClosure, "stop", CancellationToken.None);
        }
        catch { }
        try { if (sendTask != null) await sendTask; } catch { }
        ws?.Dispose();
        ws = null;
'@
$disposeCloseNew = @'
        var socket = ws;
        ws = null;
        await CloseSocketQuietlyAsync(socket, "stop");
        try { if (sendTask != null) await sendTask; } catch { }
        try
        {
            if (receiveTask != null)
                await receiveTask.WaitAsync(TimeSpan.FromMilliseconds(800));
        }
        catch { }
        receiveTask = null;
'@
if (-not $c.Contains($disposeCloseOld)) { throw 'Dispose socket marker missing' }
$c = $c.Replace($disposeCloseOld, $disposeCloseNew)

Set-Content $p $c -Encoding UTF8

# Verification.
$c = Get-Content $p -Raw
if ($c -notmatch 'ALAD Windows v5\.7 Stable Long Session') { throw 'v5.7 label missing' }
if (($c | Select-String -Pattern 'contextWindowCompression = new' -AllMatches).Matches.Count -lt 2) { throw 'compression not enabled for both live modes' }
if ($c -notmatch 'sessionResumable \? sessionHandle : null') { throw 'safe session handle selection missing' }
if ($c -notmatch 'TimeSpan\.FromMinutes\(8\.75\)') { throw 'proactive rollover watchdog missing' }
if ($c -notmatch 'long generation') { throw 'socket generation guard missing' }
if ($c -notmatch 'stillCurrent') { throw 'stale receive-loop guard missing' }
if ($c -notmatch 'immediate: true, reason: "GoAway"') { throw 'immediate GoAway rollover missing' }
if ($c -notmatch 'ReconnectLoop\(CancellationToken ct, bool immediate') { throw 'clean reconnect state machine missing' }
if ($c -notmatch 'wait < 60') { throw 'send-loop rollover hold missing' }
if ($c -notmatch 'BoundedChannelOptions\(8\)') { throw 'fresh input queue lost' }
if ($c -notmatch 'AdaptiveJitterWaveProvider') { throw 'v5.6 adaptive jitter lost' }
if ($c -notmatch 'echoTargetLanguage = false') { throw 'target-language silence lost' }

Write-Host 'ALAD Windows v5.7 Stable Long Session patch verified.'
