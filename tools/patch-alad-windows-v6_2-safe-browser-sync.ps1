$ErrorActionPreference = 'Stop'

# Build from the known-good long-session/audio pipeline, not from v6.0/v6.1 hybrid routing.
& "$PSScriptRoot/patch-alad-windows-v5_7-long-session.ps1"

$p = 'alad-windows-v2/Program.cs'
$c = Get-Content $p -Raw
$nl = [Environment]::NewLine

$c = $c.Replace('ALAD Windows v5.7 Stable Long Session', 'ALAD Windows v6.2 Safe Browser Sync')

if ($c -notmatch 'using System\.Net;') {
    $c = $c.Replace('using System.Net.WebSockets;', 'using System.Net;' + $nl + 'using System.Net.WebSockets;')
}

# Browser helper item: still captures browser/system audio through the proven v5.7 path.
$processMarker = 'internal sealed class ProcessItem'
$browserItem = @'
internal sealed class BrowserSyncItem
{
    public override string ToString() => "Browser Sync — audio ổn định + pause/tua/speed";
}

'@
if (-not $c.Contains($processMarker)) { throw 'ProcessItem marker missing' }
$c = $c.Replace($processMarker, $browserItem + $processMarker)

# Lightweight browser bridge state. It must never be required for dubbing to start.
$fieldMarker = '    private bool delayedBurstActive;'
$fieldAdd = @'
    private bool delayedBurstActive;
    private BrowserSyncBridge? browserSync;
    private bool browserSyncMode;
    private double browserTime;
    private double browserRate = 1.0;
'@
if (-not $c.Contains($fieldMarker)) { throw 'MainForm field marker missing' }
$c = $c.Replace($fieldMarker, $fieldAdd.TrimEnd())

# Add browser sync source above system audio.
$refreshNeedle = @'
        source.Items.Clear();
        source.Items.Add("Toàn hệ thống — trừ ALAD để tránh vọng tiếng");
'@
$refreshReplacement = @'
        source.Items.Clear();
        source.Items.Add(new BrowserSyncItem());
        source.Items.Add("Toàn hệ thống — trừ ALAD để tránh vọng tiếng");
'@
if (-not $c.Contains($refreshNeedle)) { throw 'source list marker missing' }
$c = $c.Replace($refreshNeedle, $refreshReplacement)
$c = $c.Replace('            for (int i = 1; i < source.Items.Count; i++)', '            for (int i = 2; i < source.Items.Count; i++)')

# Start: browser sync is optional. Gemini/audio capture must come first and keep working even if bridge fails.
$targetNeedle = @'
            uint targetPid;
            ProcessLoopbackMode loopbackMode;
            if (source.SelectedItem is ProcessItem selected)
            {
                targetPid = selected.Pid;
                loopbackMode = ProcessLoopbackMode.IncludeTargetProcessTree;
            }
            else
            {
                targetPid = (uint)Environment.ProcessId;
                loopbackMode = ProcessLoopbackMode.ExcludeTargetProcessTree;
            }
'@
$targetReplacement = @'
            browserSyncMode = source.SelectedItem is BrowserSyncItem;

            uint targetPid;
            ProcessLoopbackMode loopbackMode;
            if (source.SelectedItem is ProcessItem selected)
            {
                targetPid = selected.Pid;
                loopbackMode = ProcessLoopbackMode.IncludeTargetProcessTree;
            }
            else
            {
                // Browser Sync and Toàn hệ thống both use the proven system-loopback path.
                // ALAD itself is excluded to prevent feedback.
                targetPid = (uint)Environment.ProcessId;
                loopbackMode = ProcessLoopbackMode.ExcludeTargetProcessTree;
            }
'@
if (-not $c.Contains($targetNeedle)) { throw 'target capture marker missing' }
$c = $c.Replace($targetNeedle, $targetReplacement)

# After recorder starts successfully, try to attach the local browser bridge.
$startRecorderNeedle = @'
            recorder.StartRecording();

            isRunning = true;
'@
$startRecorderReplacement = @'
            recorder.StartRecording();

            if (browserSyncMode)
            {
                try
                {
                    browserSync = new BrowserSyncBridge(37921, LogStatus);
                    browserSync.StateChanged += OnBrowserSyncState;
                    await browserSync.StartAsync(runCts.Token);
                    inputState.Text = "Browser Sync: audio đang chạy · chờ extension";
                }
                catch (Exception ex)
                {
                    browserSync = null;
                    // Critical design rule: bridge failure NEVER stops dubbing.
                    LogStatus("Browser Sync không khởi động, tiếp tục Live Audio: " + ex.Message);
                    inputState.Text = "Browser Sync lỗi · Live Audio vẫn chạy";
                }
            }

            isRunning = true;
'@
if (-not $c.Contains($startRecorderNeedle)) { throw 'recorder start marker missing' }
$c = $c.Replace($startRecorderNeedle, $startRecorderReplacement)

# Browser events only control playback queue. They never replace/send Gemini source content.
$buildRecorderMarker = '    private static async Task<WasapiRecorder> BuildRecorderAsync'
$browserMethods = @'
    private void OnBrowserSyncState(BrowserSyncState s)
    {
        if (!browserSyncMode) return;

        Ui(() =>
        {
            browserTime = s.CurrentTime;
            browserRate = s.PlaybackRate <= 0 ? 1.0 : s.PlaybackRate;

            if (s.Type == "seek" || s.Type == "video")
            {
                ClearOutputBuffer();
                inputState.Text = $"Browser Sync: tua · {browserTime:0.0}s";
                return;
            }

            if (s.Paused)
            {
                try { player?.Pause(); } catch { }
                inputState.Text = $"Browser Sync: tạm dừng · {browserTime:0.0}s";
            }
            else
            {
                try { player?.Play(); } catch { }
                inputState.Text = $"Browser Sync: audio chạy {browserRate:0.##}× · {browserTime:0.0}s";
            }
        });
    }

'@
if (-not $c.Contains($buildRecorderMarker)) { throw 'BuildRecorder marker missing' }
$c = $c.Replace($buildRecorderMarker, $browserMethods + $buildRecorderMarker)

# Stop bridge safely.
$stopMarker = @'
        try { if (gemini != null) await gemini.DisposeAsync(); } catch { }
        gemini = null;
'@
$stopReplacement = @'
        if (browserSync != null)
        {
            try { await browserSync.DisposeAsync(); } catch { }
        }
        browserSync = null;
        browserSyncMode = false;
        browserTime = 0;
        browserRate = 1.0;

        try { if (gemini != null) await gemini.DisposeAsync(); } catch { }
        gemini = null;
'@
if (-not $c.Contains($stopMarker)) { throw 'Stop marker missing' }
$c = $c.Replace($stopMarker, $stopReplacement)

# Keep telemetry from overwriting browser status every 250ms.
$telemetryNeedle = @'
        if (isRunning)
        {
            inputState.Text = (DateTime.UtcNow - lastInputAudioUtc).TotalMilliseconds < 1000
                ? "Audio vào: đang chạy"
                : "Audio vào: im lặng";
        }
'@
$telemetryReplacement = @'
        if (isRunning && !browserSyncMode)
        {
            inputState.Text = (DateTime.UtcNow - lastInputAudioUtc).TotalMilliseconds < 1000
                ? "Audio vào: đang chạy"
                : "Audio vào: im lặng";
        }
'@
if (-not $c.Contains($telemetryNeedle)) { throw 'telemetry marker missing' }
$c = $c.Replace($telemetryNeedle, $telemetryReplacement)

# Local bridge implemented with ClientWebSocket-friendly HttpListener, but completely optional.
$insertMarker = 'internal sealed class AdaptiveJitterWaveProvider'
$bridgeCode = @'
internal sealed record BrowserSyncState(
    string Type,
    double CurrentTime,
    double PlaybackRate,
    bool Paused);

internal sealed class BrowserSyncBridge : IAsyncDisposable
{
    private readonly HttpListener listener = new();
    private readonly Action<string> log;
    private CancellationTokenSource? cts;
    private Task? acceptTask;

    public event Action<BrowserSyncState>? StateChanged;

    public BrowserSyncBridge(int port, Action<string> log)
    {
        this.log = log;
        listener.Prefixes.Add($"http://127.0.0.1:{port}/alad/");
    }

    public Task StartAsync(CancellationToken externalToken)
    {
        cts = CancellationTokenSource.CreateLinkedTokenSource(externalToken);
        listener.Start();
        acceptTask = Task.Run(() => AcceptLoop(cts.Token), cts.Token);
        log("Browser Sync: chờ extension tại 127.0.0.1:37921");
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
                if (!ct.IsCancellationRequested) log("Browser Sync bridge lỗi: " + ex.Message);
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
                _ = Task.Run(() => ClientLoop(accepted.WebSocket, ct), ct);
            }
            catch (Exception ex)
            {
                log("Browser Sync WebSocket lỗi: " + ex.Message);
            }
        }
    }

    private async Task ClientLoop(WebSocket socket, CancellationToken ct)
    {
        byte[] buffer = ArrayPool<byte>.Shared.Rent(16 * 1024);
        using var ms = new MemoryStream(16 * 1024);

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
                Parse(Encoding.UTF8.GetString(ms.GetBuffer(), 0, checked((int)ms.Length)));
            }
        }
        catch (OperationCanceledException) { }
        catch (Exception ex)
        {
            if (!ct.IsCancellationRequested) log("Browser Sync mất kết nối: " + ex.Message);
        }
        finally
        {
            ArrayPool<byte>.Shared.Return(buffer);
            try { socket.Dispose(); } catch { }
        }
    }

    private void Parse(string json)
    {
        try
        {
            using var doc = JsonDocument.Parse(json);
            var root = doc.RootElement;
            string type = root.TryGetProperty("type", out var t) ? (t.GetString() ?? "") : "";

            if (type != "state" && type != "seek" && type != "video") return;

            double currentTime = root.TryGetProperty("currentTime", out var ct) && ct.TryGetDouble(out var cv) ? cv : 0;
            double rate = root.TryGetProperty("playbackRate", out var r) && r.TryGetDouble(out var rv) ? rv : 1;
            bool paused = root.TryGetProperty("paused", out var p) && p.ValueKind == JsonValueKind.True;

            StateChanged?.Invoke(new BrowserSyncState(type, currentTime, rate, paused));
        }
        catch { }
    }

    public async ValueTask DisposeAsync()
    {
        try { cts?.Cancel(); } catch { }
        try { listener.Stop(); } catch { }
        try { if (acceptTask != null) await acceptTask; } catch { }
        cts?.Dispose();
        cts = null;
        try { listener.Close(); } catch { }
    }
}

'@
if (-not $c.Contains($insertMarker)) { throw 'AdaptiveJitter marker missing' }
$c = $c.Replace($insertMarker, $bridgeCode.TrimEnd() + $nl + $nl + $insertMarker)

Set-Content $p $c -Encoding UTF8

$c = Get-Content $p -Raw
if ($c -notmatch 'ALAD Windows v6\.2 Safe Browser Sync') { throw 'v6.2 label missing' }
if ($c -notmatch 'Browser Sync — audio ổn định') { throw 'browser sync source missing' }
if ($c -match 'QueueCaption|clientContent = new') { throw 'caption-to-Gemini path must not exist in safe build' }
if ($c -notmatch 'ExcludeTargetProcessTree') { throw 'proven system audio capture lost' }
if ($c -notmatch 'AdaptiveJitterWaveProvider') { throw 'adaptive audio lost' }
if ($c -notmatch 'TimeSpan\.FromMinutes\(8\.75\)') { throw 'long-session rollover lost' }

Write-Host 'ALAD Windows v6.2 Safe Browser Sync patch verified.'
