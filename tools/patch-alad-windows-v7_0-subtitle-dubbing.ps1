$ErrorActionPreference = 'Stop'

# v7 keeps the known-good v5.7 audio core via v6.2 Safe Browser Sync.
# Subtitle Dubbing is a separate path; Live Audio remains untouched.
& "$PSScriptRoot/patch-alad-windows-v6_2-safe-browser-sync.ps1"

$p = 'alad-windows-v2/Program.cs'
$c = Get-Content $p -Raw
$nl = [Environment]::NewLine

$c = $c.Replace('ALAD Windows v6.2 Safe Browser Sync', 'ALAD Windows v7.0 Subtitle Dubbing')

if ($c -notmatch 'using System\.Security\.Cryptography;') {
    $c = $c.Replace('using System.Runtime.InteropServices;', 'using System.Runtime.InteropServices;' + $nl + 'using System.Security.Cryptography;')
}

# Dedicated subtitle mode item; unlike Browser Sync it does not send source audio directly
# to the dubbing model. It either consumes page captions or Gemini 3.5 Transcribe output.
$processMarker = 'internal sealed class BrowserSyncItem'
$subtitleItem = @'
internal sealed class SubtitleDubbingItem
{
    public override string ToString() => "Subtitle Dubbing — phụ đề/timeline, có AI tạo phụ đề";
}

'@
if (-not $c.Contains($processMarker)) { throw 'BrowserSyncItem marker missing' }
$c = $c.Replace($processMarker, $subtitleItem + $processMarker)

# UI control: Auto / page captions / force AI-generated captions.
$fieldUiMarker = '    private readonly ComboBox mixMode = new() { DropDownStyle = ComboBoxStyle.DropDownList };'
$fieldUiReplacement = $fieldUiMarker + $nl + '    private readonly ComboBox subtitleSource = new() { DropDownStyle = ComboBoxStyle.DropDownList };'
if (-not $c.Contains($fieldUiMarker)) { throw 'mixMode field marker missing' }
$c = $c.Replace($fieldUiMarker, $fieldUiReplacement)

# Subtitle mode runtime state.
$fieldMarker = '    private double browserRate = 1.0;'
$fieldAdd = @'
    private double browserRate = 1.0;
    private bool subtitleDubbingMode;
    private GeminiTranscriberClient? transcriber;
    private SubtitleSrtCache? subtitleCache;
    private DateTime lastPageCaptionUtc = DateTime.MinValue;
    private DateTime subtitleModeStartedUtc = DateTime.MinValue;
    private string currentVideoId = "";
    private string currentVideoTitle = "";
    private double lastSubtitleEnd;
'@
if (-not $c.Contains($fieldMarker)) { throw 'browserRate field marker missing' }
$c = $c.Replace($fieldMarker, $fieldAdd.TrimEnd())

# Add subtitle source option to compact UI.
$uiNeedle = @'
        AddField(audio, "Kiểu lồng tiếng", mixMode, 3);
        AddRow(audio, new Label
'@
$uiReplacement = @'
        AddField(audio, "Kiểu lồng tiếng", mixMode, 3);

        subtitleSource.Items.AddRange(new object[]
        {
            "Tự động — ưu tiên phụ đề trang, thiếu thì tạo AI",
            "Chỉ dùng phụ đề trang",
            "Ép tạo phụ đề AI — bỏ qua phụ đề trang"
        });
        subtitleSource.SelectedIndex = 0;
        StyleCombo(subtitleSource);
        AddField(audio, "Nguồn phụ đề · dùng khi chọn Subtitle Dubbing", subtitleSource, 3);

        AddRow(audio, new Label
'@
if (-not $c.Contains($uiNeedle)) { throw 'audio UI marker missing' }
$c = $c.Replace($uiNeedle, $uiReplacement)

# Add subtitle mode before Browser Sync and keep process-index restoration aligned.
$refreshNeedle = @'
        source.Items.Clear();
        source.Items.Add(new BrowserSyncItem());
        source.Items.Add("Toàn hệ thống — trừ ALAD để tránh vọng tiếng");
'@
$refreshReplacement = @'
        source.Items.Clear();
        source.Items.Add(new SubtitleDubbingItem());
        source.Items.Add(new BrowserSyncItem());
        source.Items.Add("Toàn hệ thống — trừ ALAD để tránh vọng tiếng");
'@
if (-not $c.Contains($refreshNeedle)) { throw 'v6.2 source list marker missing' }
$c = $c.Replace($refreshNeedle, $refreshReplacement)
$c = $c.Replace('            for (int i = 2; i < source.Items.Count; i++)', '            for (int i = 3; i < source.Items.Count; i++)')

# Selecting Subtitle Dubbing automatically picks Gemini 3.8 for text->speech dubbing.
$bindMarker = '        refresh.Click += (_, _) => RefreshProcesses();'
$bindReplacement = @'
        refresh.Click += (_, _) => RefreshProcesses();
        source.SelectedIndexChanged += (_, _) =>
        {
            bool subtitle = source.SelectedItem is SubtitleDubbingItem;
            subtitleSource.Enabled = !isRunning && subtitle;
            if (subtitle && !isRunning)
            {
                liveMode.SelectedIndex = 1;
                voice.Enabled = true;
                inputState.Text = "Subtitle Dubbing: chọn nguồn phụ đề rồi bấm Bắt đầu";
            }
        };
'@
if (-not $c.Contains($bindMarker)) { throw 'BindEvents refresh marker missing' }
$c = $c.Replace($bindMarker, $bindReplacement.TrimEnd())

# Set mode before Gemini connects and force CustomVoice only for subtitle-text dubbing.
$geminiNeedle = @'
            gemini = new LiveGeminiClient(LogStatus, OnGeminiAudio, OnTranscript, ClearOutputBuffer);
            await gemini.ConnectAsync(
                apiKey.Text.Trim(),
                TargetLanguageCode(),
                SelectedLiveMode(),
                voice.SelectedItem?.ToString() ?? "Kore",
                runCts.Token);
'@
$geminiReplacement = @'
            subtitleDubbingMode = source.SelectedItem is SubtitleDubbingItem;
            browserSyncMode = source.SelectedItem is BrowserSyncItem || subtitleDubbingMode;

            LiveMode effectiveMode = subtitleDubbingMode ? LiveMode.CustomVoice : SelectedLiveMode();
            if (subtitleDubbingMode) chunker.SetChunkMs(100);

            gemini = new LiveGeminiClient(LogStatus, OnGeminiAudio, OnTranscript, ClearOutputBuffer);
            await gemini.ConnectAsync(
                apiKey.Text.Trim(),
                TargetLanguageCode(),
                effectiveMode,
                voice.SelectedItem?.ToString() ?? "Kore",
                runCts.Token);
'@
if (-not $c.Contains($geminiNeedle)) { throw 'Gemini connect marker missing' }
$c = $c.Replace($geminiNeedle, $geminiReplacement)

# v6.2 later reassigns browserSyncMode. Preserve subtitle mode there.
$c = $c.Replace(
    '            browserSyncMode = source.SelectedItem is BrowserSyncItem;',
    '            browserSyncMode = source.SelectedItem is BrowserSyncItem || subtitleDubbingMode;'
)

# In Subtitle Dubbing the recorder feeds the dedicated transcriber, never the dubbing model.
# Auto mode suppresses transcriber while page captions are arriving.
$dataNeedle = @'
                foreach (var chunk in chunker.Push(pcm))
                {
                    gemini.QueueAudio(chunk);
                }
'@
$dataReplacement = @'
                foreach (var chunk in chunker.Push(pcm))
                {
                    if (subtitleDubbingMode)
                    {
                        bool forceAi = subtitleSource.SelectedIndex == 2;
                        bool pageOnly = subtitleSource.SelectedIndex == 1;
                        bool pageFresh = (DateTime.UtcNow - lastPageCaptionUtc).TotalMilliseconds < 2400;

                        if (!pageOnly && (forceAi || !pageFresh))
                            transcriber?.QueueAudio(chunk);
                    }
                    else
                    {
                        gemini.QueueAudio(chunk);
                    }
                }
'@
if (-not $c.Contains($dataNeedle)) { throw 'recorder audio loop marker missing' }
$c = $c.Replace($dataNeedle, $dataReplacement)

# v6.2 starts browser bridge after recorder. Extend it with caption events and transcriber.
$bridgeStartNeedle = @'
                    browserSync = new BrowserSyncBridge(37921, LogStatus);
                    browserSync.StateChanged += OnBrowserSyncState;
                    await browserSync.StartAsync(runCts.Token);
                    inputState.Text = "Browser Sync: audio đang chạy · chờ extension";
'@
$bridgeStartReplacement = @'
                    browserSync = new BrowserSyncBridge(37921, LogStatus);
                    browserSync.StateChanged += OnBrowserSyncState;
                    await browserSync.StartAsync(runCts.Token);

                    if (subtitleDubbingMode)
                    {
                        subtitleModeStartedUtc = DateTime.UtcNow;
                        lastPageCaptionUtc = DateTime.MinValue;
                        lastSubtitleEnd = 0;

                        subtitleCache = new SubtitleSrtCache(LogStatus);

                        if (subtitleSource.SelectedIndex != 1)
                        {
                            transcriber = new GeminiTranscriberClient(
                                apiKey.Text.Trim(),
                                OnAiSubtitleFinal,
                                text => Ui(() =>
                                {
                                    if (subtitleDubbingMode && subtitleSource.SelectedIndex == 2)
                                        inputState.Text = "Đang tạo phụ đề AI: " + ShortText(text, 54);
                                }),
                                LogStatus);
                            await transcriber.ConnectAsync(runCts.Token);
                        }

                        inputState.Text = subtitleSource.SelectedIndex switch
                        {
                            2 => "Subtitle Dubbing: ÉP TẠO PHỤ ĐỀ AI",
                            1 => "Subtitle Dubbing: chỉ dùng phụ đề trang",
                            _ => "Subtitle Dubbing: tự động phụ đề trang → AI fallback"
                        };
                    }
                    else
                    {
                        inputState.Text = "Browser Sync: audio đang chạy · chờ extension";
                    }
'@
if (-not $c.Contains($bridgeStartNeedle)) { throw 'browser bridge start marker missing' }
$c = $c.Replace($bridgeStartNeedle, $bridgeStartReplacement)

# Caption + player sync. Caption messages become the authoritative subtitle source unless
# the user explicitly selected force-AI mode.
$methodStart = $c.IndexOf('    private void OnBrowserSyncState(BrowserSyncState s)')
$methodEnd = $c.IndexOf('    private static async Task<WasapiRecorder> BuildRecorderAsync', $methodStart)
if ($methodStart -lt 0 -or $methodEnd -lt 0) { throw 'OnBrowserSyncState anchors missing' }
$methods = @'
    private static string ShortText(string text, int max)
    {
        text = text.Replace("", " ").Replace("
", " ").Trim();
        return text.Length <= max ? text : text[..max] + "…";
    }

    private double CurrentSubtitleClock()
    {
        if (browserTime > 0.01) return browserTime;
        return subtitleModeStartedUtc == DateTime.MinValue
            ? 0
            : (DateTime.UtcNow - subtitleModeStartedUtc).TotalSeconds;
    }

    private void QueueSubtitleForDubbing(string text, double endTime, double durationHint, bool aiGenerated)
    {
        if (!subtitleDubbingMode || gemini == null || string.IsNullOrWhiteSpace(text)) return;

        text = string.Join(" ", text.Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries)).Trim();
        if (text.Length == 0) return;

        double estimated = durationHint > 0.15
            ? Math.Clamp(durationHint, 0.7, 7.5)
            : Math.Clamp(text.Length / 13.0, 1.0, 6.5);

        if (endTime <= 0) endTime = CurrentSubtitleClock();
        double startTime = Math.Max(lastSubtitleEnd, Math.Max(0, endTime - estimated));
        if (endTime <= startTime) endTime = startTime + Math.Max(0.8, estimated);
        lastSubtitleEnd = endTime;

        subtitleCache?.SetVideo(currentVideoId, currentVideoTitle);
        subtitleCache?.Add(startTime, endTime, text, aiGenerated);

        gemini.QueueSubtitleText(text);
        inputState.Text = aiGenerated
            ? $"AI subtitle {startTime:0.0}s → {endTime:0.0}s · {ShortText(text, 42)}"
            : $"Web subtitle {startTime:0.0}s → {endTime:0.0}s · {ShortText(text, 42)}";
    }

    private void OnAiSubtitleFinal(string text)
    {
        Ui(() =>
        {
            if (!subtitleDubbingMode || subtitleSource.SelectedIndex == 1) return;
            double end = CurrentSubtitleClock();
            QueueSubtitleForDubbing(text, end, 0, aiGenerated: true);
        });
    }

    private void OnBrowserSyncState(BrowserSyncState s)
    {
        if (!browserSyncMode) return;

        Ui(() =>
        {
            browserTime = s.CurrentTime;
            browserRate = s.PlaybackRate <= 0 ? 1.0 : s.PlaybackRate;

            if (!string.IsNullOrWhiteSpace(s.VideoId))
                currentVideoId = s.VideoId!;
            if (!string.IsNullOrWhiteSpace(s.Title))
                currentVideoTitle = s.Title!;

            if (s.Type == "caption")
            {
                if (!subtitleDubbingMode || string.IsNullOrWhiteSpace(s.Text)) return;

                lastPageCaptionUtc = DateTime.UtcNow;
                if (subtitleSource.SelectedIndex != 2)
                    QueueSubtitleForDubbing(s.Text!, s.CurrentTime, s.Duration, aiGenerated: false);
                return;
            }

            if (s.Type == "seek" || s.Type == "video")
            {
                ClearOutputBuffer();
                lastSubtitleEnd = Math.Max(0, s.CurrentTime);
                if (s.Type == "video")
                {
                    lastPageCaptionUtc = DateTime.MinValue;
                    subtitleCache?.SetVideo(currentVideoId, currentVideoTitle);
                }

                inputState.Text = subtitleDubbingMode
                    ? $"Subtitle Dubbing: tua · {browserTime:0.0}s"
                    : $"Browser Sync: tua · {browserTime:0.0}s";
                return;
            }

            if (s.Paused)
            {
                try { player?.Pause(); } catch { }
                inputState.Text = subtitleDubbingMode
                    ? $"Subtitle Dubbing: tạm dừng · {browserTime:0.0}s"
                    : $"Browser Sync: tạm dừng · {browserTime:0.0}s";
            }
            else
            {
                try { player?.Play(); } catch { }
                inputState.Text = subtitleDubbingMode
                    ? $"Subtitle Dubbing {browserRate:0.##}× · {browserTime:0.0}s"
                    : $"Browser Sync: audio chạy {browserRate:0.##}× · {browserTime:0.0}s";
            }
        });
    }

'@
$c = $c.Substring(0, $methodStart) + $methods + $c.Substring($methodEnd)

# Stop transcriber/cache before disposing the dubbing model.
$stopNeedle = @'
        browserSync = null;
        browserSyncMode = false;
        browserTime = 0;
        browserRate = 1.0;

        try { if (gemini != null) await gemini.DisposeAsync(); } catch { }
'@
$stopReplacement = @'
        browserSync = null;
        browserSyncMode = false;

        if (transcriber != null)
        {
            try { await transcriber.DisposeAsync(); } catch { }
        }
        transcriber = null;
        subtitleCache?.Dispose();
        subtitleCache = null;
        subtitleDubbingMode = false;
        lastPageCaptionUtc = DateTime.MinValue;
        subtitleModeStartedUtc = DateTime.MinValue;
        currentVideoId = "";
        currentVideoTitle = "";
        lastSubtitleEnd = 0;
        browserTime = 0;
        browserRate = 1.0;

        try { if (gemini != null) await gemini.DisposeAsync(); } catch { }
'@
if (-not $c.Contains($stopNeedle)) { throw 'v6.2 stop bridge marker missing' }
$c = $c.Replace($stopNeedle, $stopReplacement)

# Keep subtitle-source control locked while running.
$controlsNeedle = '        liveMode.Enabled = !startingOrRunning;'
$c = $c.Replace($controlsNeedle, $controlsNeedle + $nl + '        subtitleSource.Enabled = !startingOrRunning && source.SelectedItem is SubtitleDubbingItem;')

# Browser bridge state includes captions/video identity.
$recordOld = @'
internal sealed record BrowserSyncState(
    string Type,
    double CurrentTime,
    double PlaybackRate,
    bool Paused);
'@
$recordNew = @'
internal sealed record BrowserSyncState(
    string Type,
    double CurrentTime,
    double Duration,
    double PlaybackRate,
    bool Paused,
    string? Text,
    string? VideoId,
    string? Title);
'@
if (-not $c.Contains($recordOld)) { throw 'BrowserSyncState marker missing' }
$c = $c.Replace($recordOld, $recordNew)

# Parse captions as well as player-state messages.
$parseOld = @'
            if (type != "state" && type != "seek" && type != "video") return;

            double currentTime = root.TryGetProperty("currentTime", out var ct) && ct.TryGetDouble(out var cv) ? cv : 0;
            double rate = root.TryGetProperty("playbackRate", out var r) && r.TryGetDouble(out var rv) ? rv : 1;
            bool paused = root.TryGetProperty("paused", out var p) && p.ValueKind == JsonValueKind.True;

            StateChanged?.Invoke(new BrowserSyncState(type, currentTime, rate, paused));
'@
$parseNew = @'
            if (type != "state" && type != "seek" && type != "video" && type != "caption") return;

            double currentTime = root.TryGetProperty("currentTime", out var ct) && ct.TryGetDouble(out var cv) ? cv : 0;
            double duration = root.TryGetProperty("duration", out var d) && d.TryGetDouble(out var dv) ? dv : 0;
            double rate = root.TryGetProperty("playbackRate", out var r) && r.TryGetDouble(out var rv) ? rv : 1;
            bool paused = root.TryGetProperty("paused", out var p) && p.ValueKind == JsonValueKind.True;
            string? text = root.TryGetProperty("text", out var x) ? x.GetString() : null;
            string? videoId = root.TryGetProperty("videoId", out var v) ? v.GetString() : null;
            string? title = root.TryGetProperty("title", out var ti) ? ti.GetString() : null;

            StateChanged?.Invoke(new BrowserSyncState(type, currentTime, duration, rate, paused, text, videoId, title));
'@
if (-not $c.Contains($parseOld)) { throw 'BrowserSync parse marker missing' }
$c = $c.Replace($parseOld, $parseNew)

# LiveGeminiClient: add a sequential text queue so subtitle segments are dubbed one at a time.
$lockMarker = @'
    private readonly SemaphoreSlim sendLock = new(1, 1);
    private readonly SemaphoreSlim reconnectLock = new(1, 1);
'@
$lockReplacement = @'
    private readonly SemaphoreSlim sendLock = new(1, 1);
    private readonly SemaphoreSlim reconnectLock = new(1, 1);
    private readonly Channel<string> subtitleQueue = Channel.CreateBounded<string>(new BoundedChannelOptions(12)
    {
        FullMode = BoundedChannelFullMode.DropOldest,
        SingleReader = true,
        SingleWriter = false
    });
'@
if (-not $c.Contains($lockMarker)) { throw 'LiveGemini lock marker missing' }
$c = $c.Replace($lockMarker, $lockReplacement.TrimEnd())

$taskMarker = '    private Task? sendTask;'
$taskReplacement = @'
    private Task? sendTask;
    private Task? subtitleTask;
    private TaskCompletionSource<bool>? subtitleTurnDone;
'@
if (-not $c.Contains($taskMarker)) { throw 'sendTask field marker missing' }
$c = $c.Replace($taskMarker, $taskReplacement.TrimEnd())

$connectTaskMarker = '        sendTask = Task.Run(() => SendLoop(cts.Token), cts.Token);'
$connectTaskReplacement = @'
        sendTask = Task.Run(() => SendLoop(cts.Token), cts.Token);
        subtitleTask = Task.Run(() => SubtitleLoop(cts.Token), cts.Token);
'@
if (-not $c.Contains($connectTaskMarker)) { throw 'send task start marker missing' }
$c = $c.Replace($connectTaskMarker, $connectTaskReplacement.TrimEnd())

$queueAudioMarker = '    public void QueueAudio(byte[] pcm16k) => sendQueue.Writer.TryWrite(pcm16k);'
$subtitleSendCode = @'
    public void QueueAudio(byte[] pcm16k) => sendQueue.Writer.TryWrite(pcm16k);

    public void QueueSubtitleText(string text)
    {
        if (mode != LiveMode.CustomVoice || string.IsNullOrWhiteSpace(text)) return;
        subtitleQueue.Writer.TryWrite(text.Trim());
    }

    private async Task SubtitleLoop(CancellationToken ct)
    {
        await foreach (var text in subtitleQueue.Reader.ReadAllAsync(ct))
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

                subtitleTurnDone = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);

                string payload = JsonSerializer.Serialize(new
                {
                    clientContent = new
                    {
                        turns = new[]
                        {
                            new
                            {
                                role = "user",
                                parts = new[] { new { text } }
                            }
                        },
                        turnComplete = true
                    }
                });

                await SendTextAsync(socket, payload, ct);

                using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
                timeout.CancelAfter(TimeSpan.FromSeconds(9));
                try { await subtitleTurnDone.Task.WaitAsync(timeout.Token); }
                catch (OperationCanceledException) when (!ct.IsCancellationRequested)
                {
                    status("Subtitle Dubbing: câu trước quá lâu · chuyển câu tiếp");
                }
            }
            catch (OperationCanceledException) { break; }
            catch (Exception ex)
            {
                status("Subtitle Dubbing gửi text lỗi: " + ex.Message);
            }
        }
    }
'@
if (-not $c.Contains($queueAudioMarker)) { throw 'QueueAudio marker missing' }
$c = $c.Replace($queueAudioMarker, $subtitleSendCode.TrimEnd())

# Server turnComplete unlocks the next subtitle.
$serverMarker = @'
            if (!(root.TryGetProperty("serverContent", out var sc) || root.TryGetProperty("server_content", out sc)))
                return;
'@
$serverReplacement = @'
            if (!(root.TryGetProperty("serverContent", out var sc) || root.TryGetProperty("server_content", out sc)))
                return;

            if ((sc.TryGetProperty("turnComplete", out var turnDone) || sc.TryGetProperty("turn_complete", out turnDone)) &&
                turnDone.ValueKind == JsonValueKind.True)
            {
                subtitleTurnDone?.TrySetResult(true);
            }
'@
if (-not $c.Contains($serverMarker)) { throw 'serverContent marker missing' }
$c = $c.Replace($serverMarker, $serverReplacement.TrimEnd())

# Complete subtitle task on disposal.
$c = $c.Replace('        sendQueue.Writer.TryComplete();', '        sendQueue.Writer.TryComplete();' + $nl + '        subtitleQueue.Writer.TryComplete();')
$c = $c.Replace('        try { if (sendTask != null) await sendTask; } catch { }', '        try { if (sendTask != null) await sendTask; } catch { }' + $nl + '        try { if (subtitleTask != null) await subtitleTask; } catch { }')

# Dedicated Gemini 3.5 Transcribe Live client. It generates subtitle text only.
$insertMarker = 'internal sealed class BrowserSyncState'
$transcriberCode = @'
internal sealed class GeminiTranscriberClient : IAsyncDisposable
{
    private const string Endpoint =
        "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent";

    private readonly string apiKey;
    private readonly Action<string> finalTranscript;
    private readonly Action<string> interimTranscript;
    private readonly Action<string> status;
    private readonly Channel<byte[]> queue = Channel.CreateBounded<byte[]>(new BoundedChannelOptions(10)
    {
        FullMode = BoundedChannelFullMode.DropOldest,
        SingleReader = true,
        SingleWriter = false
    });
    private readonly SemaphoreSlim sendLock = new(1, 1);
    private readonly SemaphoreSlim reconnectLock = new(1, 1);

    private CancellationTokenSource? cts;
    private ClientWebSocket? ws;
    private TaskCompletionSource<bool>? setupReady;
    private Task? receiveTask;
    private Task? sendTask;
    private long generation;
    private volatile bool stopping;

    public GeminiTranscriberClient(
        string apiKey,
        Action<string> finalTranscript,
        Action<string> interimTranscript,
        Action<string> status)
    {
        this.apiKey = apiKey;
        this.finalTranscript = finalTranscript;
        this.interimTranscript = interimTranscript;
        this.status = status;
    }

    public async Task ConnectAsync(CancellationToken externalToken)
    {
        cts = CancellationTokenSource.CreateLinkedTokenSource(externalToken);
        await OpenAsync(cts.Token);
        sendTask = Task.Run(() => SendLoop(cts.Token), cts.Token);
    }

    public void QueueAudio(byte[] pcm16k)
    {
        if (!stopping && pcm16k.Length > 0)
            queue.Writer.TryWrite(pcm16k);
    }

    private async Task OpenAsync(CancellationToken ct)
    {
        long gen = Interlocked.Increment(ref generation);
        var old = ws;
        ws = null;
        try { old?.Abort(); } catch { }
        try { old?.Dispose(); } catch { }

        var socket = new ClientWebSocket();
        socket.Options.KeepAliveInterval = TimeSpan.FromSeconds(15);
        var uri = new Uri($"{Endpoint}?key={Uri.EscapeDataString(apiKey)}");
        await socket.ConnectAsync(uri, ct);

        ws = socket;
        setupReady = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        receiveTask = Task.Run(() => ReceiveLoop(socket, gen, ct), ct);

        string setup = JsonSerializer.Serialize(new
        {
            setup = new
            {
                model = "models/gemini-3.5-transcribe-live",
                generationConfig = new
                {
                    responseModalities = new[] { "TEXT" }
                },
                inputAudioTranscription = new
                {
                    languageCodes = Array.Empty<string>(),
                    mode = "SMART"
                }
            }
        });
        await SendTextAsync(socket, setup, ct);

        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeout.CancelAfter(TimeSpan.FromSeconds(8));
        await setupReady.Task.WaitAsync(timeout.Token);

        status("Phụ đề AI sẵn sàng · Gemini 3.5 Transcribe");
        _ = Task.Run(() => RotateAsync(gen, ct), ct);
    }

    private async Task RotateAsync(long gen, CancellationToken ct)
    {
        try
        {
            await Task.Delay(TimeSpan.FromMinutes(8.75), ct);
            if (stopping || gen != Interlocked.Read(ref generation)) return;
            await ReconnectAsync(ct);
        }
        catch (OperationCanceledException) { }
    }

    private async Task SendLoop(CancellationToken ct)
    {
        await foreach (var pcm in queue.Reader.ReadAllAsync(ct))
        {
            try
            {
                ClientWebSocket? socket = null;
                for (int i = 0; i < 50 && !ct.IsCancellationRequested; i++)
                {
                    socket = ws;
                    if (socket?.State == WebSocketState.Open && setupReady?.Task.IsCompletedSuccessfully == true)
                        break;
                    await Task.Delay(20, ct);
                }
                if (socket?.State != WebSocketState.Open || setupReady?.Task.IsCompletedSuccessfully != true)
                    continue;

                string msg = JsonSerializer.Serialize(new
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
                await SendTextAsync(socket, msg, ct);
            }
            catch (OperationCanceledException) { break; }
            catch (Exception ex)
            {
                status("Phụ đề AI gửi audio lỗi: " + ex.Message);
                _ = ReconnectAsync(ct);
            }
        }
    }

    private async Task ReceiveLoop(ClientWebSocket socket, long gen, CancellationToken ct)
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
                    if (result.MessageType == WebSocketMessageType.Close) return;
                    if (result.Count > 0) ms.Write(buffer, 0, result.Count);
                }
                while (!result.EndOfMessage);

                using var doc = JsonDocument.Parse(ms.GetBuffer().AsSpan(0, checked((int)ms.Length)));
                var root = doc.RootElement;

                if (root.TryGetProperty("setupComplete", out _) || root.TryGetProperty("setup_complete", out _))
                {
                    setupReady?.TrySetResult(true);
                    continue;
                }

                if (!(root.TryGetProperty("serverContent", out var sc) || root.TryGetProperty("server_content", out sc)))
                    continue;

                if ((sc.TryGetProperty("interimInputTranscription", out var interim) ||
                     sc.TryGetProperty("interim_input_transcription", out interim)) &&
                    interim.TryGetProperty("text", out var it))
                {
                    string? value = it.GetString();
                    if (!string.IsNullOrWhiteSpace(value)) interimTranscript(value);
                }

                if ((sc.TryGetProperty("inputTranscription", out var final) ||
                     sc.TryGetProperty("input_transcription", out final)) &&
                    final.TryGetProperty("text", out var ft))
                {
                    string? value = ft.GetString();
                    if (!string.IsNullOrWhiteSpace(value)) finalTranscript(value);
                }
            }
        }
        catch (OperationCanceledException) { }
        catch (Exception ex)
        {
            if (!stopping) status("Phụ đề AI mất kết nối: " + ex.Message);
        }
        finally
        {
            ArrayPool<byte>.Shared.Return(buffer);
            if (!stopping && gen == Interlocked.Read(ref generation) && ReferenceEquals(ws, socket))
                _ = ReconnectAsync(ct);
        }
    }

    private async Task ReconnectAsync(CancellationToken ct)
    {
        if (stopping || ct.IsCancellationRequested) return;
        if (!await reconnectLock.WaitAsync(0, ct)) return;
        try
        {
            for (int n = 0; n < 5 && !stopping && !ct.IsCancellationRequested; n++)
            {
                try
                {
                    if (n > 0) await Task.Delay(Math.Min(8000, 500 << Math.Min(n, 4)), ct);
                    await OpenAsync(ct);
                    return;
                }
                catch (OperationCanceledException) { return; }
                catch (Exception ex) { status("Phụ đề AI nối lại: " + ex.Message); }
            }
        }
        finally { reconnectLock.Release(); }
    }

    private async Task SendTextAsync(ClientWebSocket socket, string text, CancellationToken ct)
    {
        byte[] bytes = Encoding.UTF8.GetBytes(text);
        await sendLock.WaitAsync(ct);
        try { await socket.SendAsync(bytes, WebSocketMessageType.Text, true, ct); }
        finally { sendLock.Release(); }
    }

    public async ValueTask DisposeAsync()
    {
        stopping = true;
        queue.Writer.TryComplete();
        try { cts?.Cancel(); } catch { }
        var socket = ws;
        ws = null;
        try { socket?.Abort(); } catch { }
        try { socket?.Dispose(); } catch { }
        try { if (sendTask != null) await sendTask; } catch { }
        try
        {
            if (receiveTask != null)
                await receiveTask.WaitAsync(TimeSpan.FromMilliseconds(500));
        }
        catch { }
        cts?.Dispose();
        sendLock.Dispose();
        reconnectLock.Dispose();
    }
}

internal sealed class SubtitleSrtCache : IDisposable
{
    private readonly Action<string> log;
    private readonly object gate = new();
    private readonly List<(double Start, double End, string Text, bool Ai)> cues = new();
    private string videoKey = "";
    private string title = "";
    private string? path;

    public SubtitleSrtCache(Action<string> log) => this.log = log;

    public void SetVideo(string videoId, string videoTitle)
    {
        string key = string.IsNullOrWhiteSpace(videoId) ? "unknown" : videoId;
        lock (gate)
        {
            if (key == videoKey && path != null) return;
            videoKey = key;
            title = videoTitle ?? "";
            cues.Clear();

            byte[] hash = SHA256.HashData(Encoding.UTF8.GetBytes(key));
            string id = Convert.ToHexString(hash)[..16].ToLowerInvariant();
            string dir = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "ALAD", "Subtitles");
            Directory.CreateDirectory(dir);
            path = Path.Combine(dir, id + ".srt");
            log("Cache phụ đề: " + path);
        }
    }

    public void Add(double start, double end, string text, bool aiGenerated)
    {
        lock (gate)
        {
            if (path == null) SetVideo(videoKey, title);
            cues.Add((start, end, text, aiGenerated));
            WriteLocked();
        }
    }

    private void WriteLocked()
    {
        if (path == null) return;
        var sb = new StringBuilder();
        for (int i = 0; i < cues.Count; i++)
        {
            var cue = cues[i];
            sb.AppendLine((i + 1).ToString());
            sb.Append(Format(cue.Start)).Append(" --> ").AppendLine(Format(cue.End));
            sb.AppendLine(cue.Text);
            sb.AppendLine();
        }
        File.WriteAllText(path, sb.ToString(), new UTF8Encoding(false));
    }

    private static string Format(double seconds)
    {
        var ts = TimeSpan.FromSeconds(Math.Max(0, seconds));
        return $"{(int)ts.TotalHours:00}:{ts.Minutes:00}:{ts.Seconds:00},{ts.Milliseconds:000}";
    }

    public void Dispose()
    {
        lock (gate) { WriteLocked(); }
    }
}

'@
if (-not $c.Contains($insertMarker)) { throw 'BrowserSyncState insertion marker missing' }
$c = $c.Replace($insertMarker, $transcriberCode.TrimEnd() + $nl + $nl + $insertMarker)

# The subtitle-text path must be understood by the 3.8 system instruction too.
$c = $c.Replace(
    'Act only as a real-time interpreter. Translate every spoken utterance into',
    'Act only as a real-time interpreter. Translate every source segment, including subtitle text, into'
)

Set-Content $p $c -Encoding UTF8

$c = Get-Content $p -Raw
if ($c -notmatch 'ALAD Windows v7\.0 Subtitle Dubbing') { throw 'v7 label missing' }
if ($c -notmatch 'Ép tạo phụ đề AI') { throw 'force-AI subtitle option missing' }
if ($c -notmatch 'models/gemini-3\.5-transcribe-live') { throw 'Gemini Transcribe model missing' }
if ($c -notmatch 'mode = "SMART"') { throw 'Smart transcription missing' }
if ($c -notmatch 'QueueSubtitleText') { throw 'subtitle-to-dubbing queue missing' }
if ($c -notmatch 'SubtitleSrtCache') { throw 'SRT cache missing' }
if ($c -notmatch 'type != "caption"') { throw 'browser caption bridge missing' }
if ($c -notmatch 'TimeSpan\.FromMinutes\(8\.75\)') { throw 'long-session rotation missing' }
if ($c -notmatch 'AdaptiveJitterWaveProvider') { throw 'stable audio pipeline lost' }

Write-Host 'ALAD Windows v7.0 Subtitle Dubbing + Force AI subtitles verified.'
