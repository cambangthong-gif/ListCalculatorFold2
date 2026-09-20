$ErrorActionPreference = 'Stop'

# v8 keeps v7.1's UI/text-dubbing queue, but replaces Force-AI Live Transcribe
# with file-based preprocessing: yt-dlp -> Gemini Files -> Gemini 3.5 Transcribe timestamps.
& "$PSScriptRoot/patch-alad-windows-v7_1-subtitle-capture-fix.ps1"

$p = 'alad-windows-v2/Program.cs'
$c = Get-Content $p -Raw
$nl = [Environment]::NewLine

$c = $c.Replace('ALAD Windows v7.1 Subtitle Capture Fix', 'ALAD Windows v8.0 Preprocessed Dubbing')

$c = $c.Replace(
    '"Tự động — ưu tiên phụ đề trang, thiếu thì tạo AI",',
    '"Tự động — ưu tiên phụ đề trang",'
)
$c = $c.Replace(
    '"Ép tạo phụ đề AI — bỏ qua phụ đề trang"',
    '"Ép tạo phụ đề AI TRƯỚC — tải audio + timestamp"'
)

$fieldNeedle = '    private double lastSubtitleEnd;'
$fieldAdd = @'
    private double lastSubtitleEnd;
    private readonly object preparedCueGate = new();
    private List<PreparedCue> preparedCues = new();
    private int preparedCueIndex;
    private bool preparedReady;
    private string preparedUrl = "";
    private TaskCompletionSource<string>? mediaUrlReady;
    private CancellationTokenSource? preprocessCts;
'@
if (-not $c.Contains($fieldNeedle)) { throw 'prepared cue field marker missing' }
$c = $c.Replace($fieldNeedle, $fieldAdd.TrimEnd())

$dataOld = @'
                    if (subtitleDubbingMode)
                    {
                        bool forceAi = subtitleSource.SelectedIndex == 2;
                        bool pageOnly = subtitleSource.SelectedIndex == 1;
                        bool pageFresh = (DateTime.UtcNow - lastPageCaptionUtc).TotalMilliseconds < 2400;

                        if (!pageOnly && (forceAi || !pageFresh))
                            transcriber?.QueueAudio(chunk);
                    }
                    else
'@
$dataNew = @'
                    if (subtitleDubbingMode)
                    {
                        // Timeline-first subtitle mode: do not feed source audio into
                        // the dubbing model or realtime transcription.
                    }
                    else
'@
if (-not $c.Contains($dataOld)) { throw 'old subtitle recorder routing missing' }
$c = $c.Replace($dataOld, $dataNew)

$oldTranscriber = @'
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
                            1 => "Subtitle Dubbing: chờ Bridge v1.2 bắt phụ đề trang",
                            _ => "Subtitle Dubbing: ưu tiên phụ đề trang → AI fallback"
                        };
'@
$newTranscriber = @'
                        subtitleCache = new SubtitleSrtCache(LogStatus);
                        mediaUrlReady = new TaskCompletionSource<string>(TaskCreationOptions.RunContinuationsAsynchronously);
                        preparedCues = new List<PreparedCue>();
                        preparedCueIndex = 0;
                        preparedReady = false;
                        preparedUrl = "";

                        inputState.Text = subtitleSource.SelectedIndex switch
                        {
                            2 => "AI preprocess: chờ URL video từ Bridge",
                            1 => "Subtitle Dubbing: chờ Bridge v1.2 bắt phụ đề trang",
                            _ => "Subtitle Dubbing: ưu tiên phụ đề trang"
                        };

                        if (subtitleSource.SelectedIndex == 2)
                        {
                            preprocessCts = CancellationTokenSource.CreateLinkedTokenSource(runCts.Token);
                            string key = apiKey.Text.Trim();
                            _ = Task.Run(() => PrepareOfflineSubtitlesAsync(key, preprocessCts.Token), preprocessCts.Token);
                        }
'@
if (-not $c.Contains($oldTranscriber)) { throw 'old GeminiTranscriber startup block missing' }
$c = $c.Replace($oldTranscriber, $newTranscriber)

$recordOld = @'
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
$recordNew = @'
internal sealed record BrowserSyncState(
    string Type,
    double CurrentTime,
    double Duration,
    double PlaybackRate,
    bool Paused,
    string? Text,
    string? VideoId,
    string? Title,
    string? PageUrl);
'@
if (-not $c.Contains($recordOld)) { throw 'BrowserSyncState marker missing' }
$c = $c.Replace($recordOld, $recordNew)

$parseOld = @'
            string? title = root.TryGetProperty("title", out var ti) ? ti.GetString() : null;

            StateChanged?.Invoke(new BrowserSyncState(
                type, currentTime, duration, rate, paused, text, videoId, title));
'@
$parseNew = @'
            string? title = root.TryGetProperty("title", out var ti) ? ti.GetString() : null;
            string? pageUrl = root.TryGetProperty("pageUrl", out var pu) ? pu.GetString() : null;

            StateChanged?.Invoke(new BrowserSyncState(
                type, currentTime, duration, rate, paused, text, videoId, title, pageUrl));
'@
if (-not $c.Contains($parseOld)) { throw 'BrowserSyncState Parse marker missing' }
$c = $c.Replace($parseOld, $parseNew)

$eventNeedle = @'
            browserTime = s.CurrentTime;
            browserRate = s.PlaybackRate <= 0 ? 1.0 : s.PlaybackRate;
'@
$eventReplacement = @'
            browserTime = s.CurrentTime;
            browserRate = s.PlaybackRate <= 0 ? 1.0 : s.PlaybackRate;

            if (subtitleDubbingMode && subtitleSource.SelectedIndex == 2 &&
                !string.IsNullOrWhiteSpace(s.PageUrl))
            {
                mediaUrlReady?.TrySetResult(s.PageUrl!);
            }
'@
if (-not $c.Contains($eventNeedle)) { throw 'browser event header missing' }
$c = $c.Replace($eventNeedle, $eventReplacement, 1)

$seekNeedle = @'
                ClearOutputBuffer();
                lastSubtitleEnd = Math.Max(0, s.CurrentTime);
'@
$seekReplacement = @'
                ClearOutputBuffer();
                lastSubtitleEnd = Math.Max(0, s.CurrentTime);
                if (preparedReady)
                    preparedCueIndex = FindPreparedCueIndex(s.CurrentTime);
'@
if (-not $c.Contains($seekNeedle)) { throw 'seek prepared reset marker missing' }
$c = $c.Replace($seekNeedle, $seekReplacement, 1)

$stateEndNeedle = @'
                inputState.Text = subtitleDubbingMode
                    ? $"Subtitle Dubbing {browserRate:0.##}× · {browserTime:0.0}s"
                    : $"Browser Sync: audio chạy {browserRate:0.##}× · {browserTime:0.0}s";
            }
        });
    }
'@
$stateEndReplacement = @'
                inputState.Text = subtitleDubbingMode
                    ? $"Subtitle Dubbing {browserRate:0.##}× · {browserTime:0.0}s"
                    : $"Browser Sync: audio chạy {browserRate:0.##}× · {browserTime:0.0}s";
            }

            if (subtitleDubbingMode && subtitleSource.SelectedIndex == 2 && !s.Paused)
                PumpPreparedDubbing();
        });
    }
'@
if (-not $c.Contains($stateEndNeedle)) { throw 'OnBrowserSyncState tail missing' }
$c = $c.Replace($stateEndNeedle, $stateEndReplacement)

$insertMethodMarker = '    private static async Task<WasapiRecorder> BuildRecorderAsync'
$offlineMethods = @'
    private async Task PrepareOfflineSubtitlesAsync(string key, CancellationToken ct)
    {
        try
        {
            Ui(() =>
            {
                status.Text = "● AI PREPROCESS · chờ video...";
                inputState.Text = "Mở video trên Chrome/Edge có Bridge v1.2";
            });

            if (mediaUrlReady == null)
                throw new InvalidOperationException("Bridge chưa sẵn sàng.");

            string url = await mediaUrlReady.Task.WaitAsync(TimeSpan.FromSeconds(15), ct);
            if (string.IsNullOrWhiteSpace(url))
                throw new InvalidOperationException("Không nhận được URL video.");

            Ui(() =>
            {
                status.Text = "● AI PREPROCESS · đang chuẩn bị";
                inputState.Text = "Đang tải audio nền...";
            });

            var processor = new OfflineSubtitlePreprocessor(
                key,
                text => Ui(() =>
                {
                    status.Text = "● AI PREPROCESS";
                    inputState.Text = text;
                }));

            var package = await processor.PrepareAsync(url, ct);

            lock (preparedCueGate)
            {
                preparedCues = package.Cues;
                preparedCueIndex = FindPreparedCueIndexNoLock(browserTime);
                preparedReady = preparedCues.Count > 0;
                preparedUrl = url;
            }

            Ui(() =>
            {
                if (!preparedReady)
                {
                    status.Text = "Không tạo được phụ đề AI";
                    inputState.Text = "Transcript không có timestamp/cue.";
                    return;
                }

                currentVideoTitle = string.IsNullOrWhiteSpace(package.Title) ? currentVideoTitle : package.Title;
                subtitleCache?.SetVideo(package.VideoId, package.Title);
                foreach (var cue in package.Cues)
                    subtitleCache?.Add(cue.Start, cue.End, cue.Text, aiGenerated: true);

                transcript.Clear();
                transcript.AppendText($"[AI Preprocess] {package.Cues.Count} đoạn · {package.Duration:0}s\r\n");
                transcript.AppendText($"[Cache] {package.CachePath}\r\n");
                status.Text = package.FromCache
                    ? $"● PHỤ ĐỀ AI CACHE · {package.Cues.Count} đoạn"
                    : $"● PHỤ ĐỀ AI XONG · {package.Cues.Count} đoạn";
                inputState.Text = "Đã chuẩn bị · phát video để lồng theo timeline";
                PumpPreparedDubbing();
            });
        }
        catch (OperationCanceledException) { }
        catch (TimeoutException)
        {
            Ui(() =>
            {
                status.Text = "Không nhận được URL video";
                inputState.Text = "Kiểm tra Bridge v1.2 và reload tab video.";
            });
        }
        catch (Exception ex)
        {
            Ui(() =>
            {
                status.Text = "AI preprocess lỗi";
                inputState.Text = ShortText(ex.Message, 80);
            });
        }
    }

    private int FindPreparedCueIndex(double time)
    {
        lock (preparedCueGate) return FindPreparedCueIndexNoLock(time);
    }

    private int FindPreparedCueIndexNoLock(double time)
    {
        int lo = 0, hi = preparedCues.Count;
        while (lo < hi)
        {
            int mid = lo + ((hi - lo) / 2);
            if (preparedCues[mid].End < time - 0.35) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    private void PumpPreparedDubbing()
    {
        if (!preparedReady || gemini == null || !subtitleDubbingMode ||
            subtitleSource.SelectedIndex != 2)
            return;

        PreparedCue? cue = null;
        lock (preparedCueGate)
        {
            while (preparedCueIndex < preparedCues.Count &&
                   preparedCues[preparedCueIndex].End < browserTime - 0.45)
                preparedCueIndex++;

            if (preparedCueIndex >= preparedCues.Count) return;

            var next = preparedCues[preparedCueIndex];
            if (next.Start <= browserTime + 1.15)
            {
                cue = next;
                preparedCueIndex++;
            }
        }

        if (cue != null)
        {
            OnTranscript("AI cue", $"{cue.Start:0.00}-{cue.End:0.00}s  {cue.Text}");
            gemini.QueueSubtitleText(cue.Text);
        }
    }

'@
if (-not $c.Contains($insertMethodMarker)) { throw 'BuildRecorderAsync insertion marker missing' }
$c = $c.Replace($insertMethodMarker, $offlineMethods + $insertMethodMarker)

$c = $c.Replace(
    '            if (!subtitleDubbingMode || subtitleSource.SelectedIndex == 1) return;',
    '            if (!subtitleDubbingMode || subtitleSource.SelectedIndex != 0) return;'
)

$cleanupNeedle = @'
        if (transcriber != null)
        {
            try { await transcriber.DisposeAsync(); } catch { }
        }
        transcriber = null;
'@
$cleanupReplacement = @'
        try { preprocessCts?.Cancel(); } catch { }
        preprocessCts?.Dispose();
        preprocessCts = null;
        mediaUrlReady = null;
        lock (preparedCueGate)
        {
            preparedCues.Clear();
            preparedCueIndex = 0;
            preparedReady = false;
            preparedUrl = "";
        }

        if (transcriber != null)
        {
            try { await transcriber.DisposeAsync(); } catch { }
        }
        transcriber = null;
'@
if (-not $c.Contains($cleanupNeedle)) { throw 'subtitle cleanup marker missing' }
$c = $c.Replace($cleanupNeedle, $cleanupReplacement)

$classMarker = 'internal sealed class GeminiTranscriberClient : IAsyncDisposable'
$offlineClasses = @'
internal sealed record PreparedCue(double Start, double End, string Text);

internal sealed class PreparedSubtitlePackage
{
    public string Url { get; set; } = "";
    public string VideoId { get; set; } = "";
    public string Title { get; set; } = "";
    public double Duration { get; set; }
    public List<PreparedCue> Cues { get; set; } = new();
    public bool FromCache { get; set; }
    public string CachePath { get; set; } = "";
}

internal sealed class OfflineSubtitlePreprocessor
{
    private const string FilesUploadEndpoint = "https://generativelanguage.googleapis.com/upload/v1beta/files";
    private const string InteractionsEndpoint = "https://generativelanguage.googleapis.com/v1beta/interactions";

    private readonly string apiKey;
    private readonly Action<string> progress;
    private readonly HttpClient http = new() { Timeout = TimeSpan.FromMinutes(30) };

    public OfflineSubtitlePreprocessor(string apiKey, Action<string> progress)
    {
        this.apiKey = apiKey;
        this.progress = progress;
    }

    public async Task<PreparedSubtitlePackage> PrepareAsync(string url, CancellationToken ct)
    {
        string key = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(url)))[..20].ToLowerInvariant();
        string root = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "ALAD", "Preprocessed", key);
        Directory.CreateDirectory(root);

        string cacheJson = Path.Combine(root, "cues.json");
        if (File.Exists(cacheJson))
        {
            try
            {
                var cached = JsonSerializer.Deserialize<PreparedSubtitlePackage>(
                    await File.ReadAllTextAsync(cacheJson, ct));
                if (cached != null && cached.Cues.Count > 0)
                {
                    cached.FromCache = true;
                    cached.CachePath = cacheJson;
                    progress($"Cache có sẵn: {cached.Cues.Count} đoạn");
                    return cached;
                }
            }
            catch { }
        }

        string ytdlp = await EnsureYtDlpAsync(ct);

        progress("Đọc thông tin video...");
        var metadata = await RunYtDlpJsonAsync(ytdlp, url, root, ct);
        string videoId = metadata.TryGetProperty("id", out var id) ? (id.GetString() ?? key) : key;
        string title = metadata.TryGetProperty("title", out var ti) ? (ti.GetString() ?? "") : "";
        double duration = metadata.TryGetProperty("duration", out var du) && du.TryGetDouble(out var dv) ? dv : 0;

        progress("Tải audio nền bằng yt-dlp...");
        string audioPath = await DownloadAudioAsync(ytdlp, url, root, ct);
        string mime = MimeForAudio(audioPath);

        progress($"Upload audio ({Path.GetFileName(audioPath)})...");
        var uploaded = await UploadGeminiFileAsync(audioPath, mime, ct);

        progress("Gemini 3.5 Transcribe · tạo word timestamps...");
        using JsonDocument interaction = await TranscribeAsync(uploaded.Uri, uploaded.MimeType, ct);
        var words = ExtractWords(interaction.RootElement);

        List<PreparedCue> cues;
        if (words.Count > 0)
        {
            cues = BuildCues(words);
        }
        else
        {
            string outputText = ExtractOutputText(interaction.RootElement);
            if (string.IsNullOrWhiteSpace(outputText))
                throw new InvalidOperationException("Gemini Transcribe không trả transcript.");

            double end = duration > 0 ? duration : Math.Max(4, outputText.Length / 12.0);
            cues = new List<PreparedCue> { new(0, end, outputText.Trim()) };
        }

        if (cues.Count == 0)
            throw new InvalidOperationException("Không tạo được cue có timestamp.");

        var package = new PreparedSubtitlePackage
        {
            Url = url,
            VideoId = videoId,
            Title = title,
            Duration = duration > 0 ? duration : cues[^1].End,
            Cues = cues,
            FromCache = false,
            CachePath = cacheJson
        };

        await File.WriteAllTextAsync(
            cacheJson,
            JsonSerializer.Serialize(package, new JsonSerializerOptions { WriteIndented = true }),
            new UTF8Encoding(false),
            ct);

        await WriteSrtAsync(Path.Combine(root, "ai-subtitle.srt"), cues, ct);
        progress($"Đã tạo {cues.Count} đoạn phụ đề AI");
        return package;
    }

    private async Task<string> EnsureYtDlpAsync(CancellationToken ct)
    {
        string tools = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "ALAD", "Tools");
        Directory.CreateDirectory(tools);
        string exe = Path.Combine(tools, "yt-dlp.exe");

        if (File.Exists(exe) && new FileInfo(exe).Length > 2_000_000)
            return exe;

        bool arm = RuntimeInformation.ProcessArchitecture == Architecture.Arm64;
        string asset = arm ? "yt-dlp_arm64.exe" : "yt-dlp.exe";
        string url = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/" + asset;

        progress("Tải yt-dlp chính thức lần đầu...");
        byte[] data = await http.GetByteArrayAsync(url, ct);
        if (data.Length < 2_000_000)
            throw new InvalidOperationException("yt-dlp tải về không hợp lệ.");

        await File.WriteAllBytesAsync(exe, data, ct);
        return exe;
    }

    private async Task<JsonElement> RunYtDlpJsonAsync(string exe, string url, string workDir, CancellationToken ct)
    {
        var result = await RunProcessAsync(
            exe,
            workDir,
            new[] { "--dump-single-json", "--no-playlist", "--no-warnings", url },
            ct);

        if (result.ExitCode != 0)
            throw new InvalidOperationException("yt-dlp metadata: " + ShortError(result.Error));

        using var doc = JsonDocument.Parse(result.Output);
        return doc.RootElement.Clone();
    }

    private async Task<string> DownloadAudioAsync(string exe, string url, string root, CancellationToken ct)
    {
        foreach (var old in Directory.EnumerateFiles(root, "source.*"))
        {
            try { File.Delete(old); } catch { }
        }

        var result = await RunProcessAsync(
            exe,
            root,
            new[]
            {
                "--no-playlist",
                "--no-warnings",
                "--no-part",
                "-f", "ba/b",
                "-o", "source.%(ext)s",
                "--print", "after_move:filepath",
                url
            },
            ct);

        if (result.ExitCode != 0)
            throw new InvalidOperationException("yt-dlp audio: " + ShortError(result.Error));

        var candidates = Directory.EnumerateFiles(root, "source.*")
            .Where(f => !f.EndsWith(".part", StringComparison.OrdinalIgnoreCase))
            .OrderByDescending(f => new FileInfo(f).Length)
            .ToList();

        if (candidates.Count == 0)
            throw new FileNotFoundException("yt-dlp không tạo file audio.");

        return candidates[0];
    }

    private async Task<(string Uri, string MimeType)> UploadGeminiFileAsync(
        string path, string mime, CancellationToken ct)
    {
        long size = new FileInfo(path).Length;

        using var start = new HttpRequestMessage(HttpMethod.Post, FilesUploadEndpoint);
        start.Headers.TryAddWithoutValidation("x-goog-api-key", apiKey);
        start.Headers.TryAddWithoutValidation("X-Goog-Upload-Protocol", "resumable");
        start.Headers.TryAddWithoutValidation("X-Goog-Upload-Command", "start");
        start.Headers.TryAddWithoutValidation("X-Goog-Upload-Header-Content-Length", size.ToString());
        start.Headers.TryAddWithoutValidation("X-Goog-Upload-Header-Content-Type", mime);
        start.Content = new StringContent(
            JsonSerializer.Serialize(new { file = new { display_name = Path.GetFileName(path) } }),
            Encoding.UTF8,
            "application/json");

        using var startResp = await http.SendAsync(start, ct);
        string startBody = await startResp.Content.ReadAsStringAsync(ct);
        if (!startResp.IsSuccessStatusCode)
            throw new InvalidOperationException($"Files API start {(int)startResp.StatusCode}: {ShortError(startBody)}");

        if (!startResp.Headers.TryGetValues("X-Goog-Upload-URL", out var uploadValues))
            throw new InvalidOperationException("Files API không trả upload URL.");
        string uploadUrl = uploadValues.First();

        await using var fs = File.OpenRead(path);
        using var upload = new HttpRequestMessage(HttpMethod.Post, uploadUrl);
        upload.Headers.TryAddWithoutValidation("X-Goog-Upload-Offset", "0");
        upload.Headers.TryAddWithoutValidation("X-Goog-Upload-Command", "upload, finalize");
        upload.Content = new StreamContent(fs);
        upload.Content.Headers.ContentLength = size;
        upload.Content.Headers.ContentType =
            new System.Net.Http.Headers.MediaTypeHeaderValue(mime);

        using var uploadResp = await http.SendAsync(upload, HttpCompletionOption.ResponseContentRead, ct);
        string body = await uploadResp.Content.ReadAsStringAsync(ct);
        if (!uploadResp.IsSuccessStatusCode)
            throw new InvalidOperationException($"Files API upload {(int)uploadResp.StatusCode}: {ShortError(body)}");

        using var doc = JsonDocument.Parse(body);
        var file = doc.RootElement.TryGetProperty("file", out var f) ? f : doc.RootElement;
        string uri = file.TryGetProperty("uri", out var u) ? (u.GetString() ?? "") : "";
        string returnedMime = file.TryGetProperty("mimeType", out var mt)
            ? (mt.GetString() ?? mime)
            : (file.TryGetProperty("mime_type", out var mts) ? (mts.GetString() ?? mime) : mime);

        if (string.IsNullOrWhiteSpace(uri))
            throw new InvalidOperationException("Files API upload xong nhưng thiếu file URI.");

        return (uri, returnedMime);
    }

    private async Task<JsonDocument> TranscribeAsync(string fileUri, string mime, CancellationToken ct)
    {
        var body = new
        {
            model = "gemini-3.5-transcribe",
            input = new[]
            {
                new
                {
                    type = "audio",
                    uri = fileUri,
                    mime_type = mime
                }
            },
            generation_config = new
            {
                transcription_config = new
                {
                    mode = new
                    {
                        type = "verbatim",
                        timestamp_granularities = new[] { "word" }
                    }
                }
            }
        };

        using var request = new HttpRequestMessage(HttpMethod.Post, InteractionsEndpoint);
        request.Headers.TryAddWithoutValidation("x-goog-api-key", apiKey);
        request.Content = new StringContent(
            JsonSerializer.Serialize(body), Encoding.UTF8, "application/json");

        using var response = await http.SendAsync(request, ct);
        string json = await response.Content.ReadAsStringAsync(ct);
        if (!response.IsSuccessStatusCode)
            throw new InvalidOperationException($"Transcribe {(int)response.StatusCode}: {ShortError(json)}");

        var first = JsonDocument.Parse(json);
        string status = first.RootElement.TryGetProperty("status", out var st)
            ? (st.GetString() ?? "")
            : "completed";

        if (status is "completed" or "incomplete")
            return first;

        string id = first.RootElement.TryGetProperty("id", out var ie) ? (ie.GetString() ?? "") : "";
        first.Dispose();
        if (string.IsNullOrWhiteSpace(id))
            throw new InvalidOperationException("Transcribe chưa xong nhưng không có interaction ID.");

        for (int i = 0; i < 600; i++)
        {
            ct.ThrowIfCancellationRequested();
            await Task.Delay(1500, ct);

            using var get = new HttpRequestMessage(HttpMethod.Get, InteractionsEndpoint + "/" + Uri.EscapeDataString(id));
            get.Headers.TryAddWithoutValidation("x-goog-api-key", apiKey);
            using var resp = await http.SendAsync(get, ct);
            string polled = await resp.Content.ReadAsStringAsync(ct);
            if (!resp.IsSuccessStatusCode)
                throw new InvalidOperationException($"Transcribe poll {(int)resp.StatusCode}: {ShortError(polled)}");

            var doc = JsonDocument.Parse(polled);
            string s = doc.RootElement.TryGetProperty("status", out var se) ? (se.GetString() ?? "") : "";
            if (s is "completed" or "incomplete") return doc;
            if (s is "failed" or "cancelled")
            {
                string msg = doc.RootElement.TryGetProperty("error", out var er) ? er.ToString() : s;
                doc.Dispose();
                throw new InvalidOperationException("Transcribe " + msg);
            }
            doc.Dispose();

            if (i % 10 == 0) progress($"Gemini đang nhận dạng... {(i + 1) * 1.5:0}s");
        }

        throw new TimeoutException("Gemini Transcribe quá 15 phút.");
    }

    private static List<(string Text, double Start, double End)> ExtractWords(JsonElement root)
    {
        var words = new List<(string Text, double Start, double End)>();
        if (!root.TryGetProperty("steps", out var steps) || steps.ValueKind != JsonValueKind.Array)
            return words;

        foreach (var step in steps.EnumerateArray())
        {
            if (!step.TryGetProperty("content", out var contents) || contents.ValueKind != JsonValueKind.Array)
                continue;

            foreach (var content in contents.EnumerateArray())
            {
                if (!content.TryGetProperty("annotations", out var anns) || anns.ValueKind != JsonValueKind.Array)
                    continue;

                foreach (var ann in anns.EnumerateArray())
                {
                    if (!ann.TryGetProperty("type", out var type) ||
                        type.GetString() != "word_info")
                        continue;

                    string text = ann.TryGetProperty("text", out var te) ? (te.GetString() ?? "") : "";
                    string start = ann.TryGetProperty("start_offset", out var so) ? (so.GetString() ?? "") : "";
                    string end = ann.TryGetProperty("end_offset", out var eo) ? (eo.GetString() ?? "") : "";

                    if (string.IsNullOrWhiteSpace(text)) continue;
                    double s = ParseOffset(start);
                    double e = ParseOffset(end);
                    if (e <= s) e = s + 0.15;
                    words.Add((text, s, e));
                }
            }
        }

        return words.OrderBy(w => w.Start).ToList();
    }

    private static List<PreparedCue> BuildCues(List<(string Text, double Start, double End)> words)
    {
        var cues = new List<PreparedCue>();
        var parts = new List<string>();
        double start = 0, end = 0, previousEnd = -1;

        void Flush()
        {
            if (parts.Count == 0) return;
            string text = string.Join(" ", parts)
                .Replace(" ,", ",").Replace(" .", ".").Replace(" !", "!")
                .Replace(" ?", "?").Replace(" :", ":").Replace(" ;", ";");
            cues.Add(new PreparedCue(start, Math.Max(end, start + 0.6), text.Trim()));
            parts.Clear();
        }

        foreach (var w in words)
        {
            bool gap = previousEnd >= 0 && w.Start - previousEnd > 0.85;
            bool tooLong = parts.Count > 0 && w.End - start > 5.8;
            if (gap || tooLong) Flush();

            if (parts.Count == 0) start = w.Start;
            parts.Add(w.Text);
            end = w.End;
            previousEnd = w.End;

            bool sentence = w.Text.EndsWith('.') || w.Text.EndsWith('!') ||
                            w.Text.EndsWith('?') || w.Text.EndsWith('。') ||
                            w.Text.EndsWith('！') || w.Text.EndsWith('？');

            if ((sentence && end - start >= 1.15) || parts.Count >= 18)
                Flush();
        }

        Flush();
        return cues;
    }

    private static string ExtractOutputText(JsonElement root)
    {
        var sb = new StringBuilder();
        if (!root.TryGetProperty("steps", out var steps) || steps.ValueKind != JsonValueKind.Array)
            return "";

        foreach (var step in steps.EnumerateArray())
        {
            if (!step.TryGetProperty("content", out var contents) || contents.ValueKind != JsonValueKind.Array)
                continue;
            foreach (var content in contents.EnumerateArray())
            {
                if (content.TryGetProperty("type", out var type) &&
                    type.GetString() == "text" &&
                    content.TryGetProperty("text", out var text))
                {
                    if (sb.Length > 0) sb.AppendLine();
                    sb.Append(text.GetString());
                }
            }
        }
        return sb.ToString().Trim();
    }

    private static double ParseOffset(string value)
    {
        if (string.IsNullOrWhiteSpace(value)) return 0;
        value = value.Trim();
        if (value.EndsWith("ms", StringComparison.OrdinalIgnoreCase) &&
            double.TryParse(value[..^2], System.Globalization.NumberStyles.Float,
                System.Globalization.CultureInfo.InvariantCulture, out var ms))
            return ms / 1000.0;
        if (value.EndsWith("s", StringComparison.OrdinalIgnoreCase) &&
            double.TryParse(value[..^1], System.Globalization.NumberStyles.Float,
                System.Globalization.CultureInfo.InvariantCulture, out var seconds))
            return seconds;
        return 0;
    }

    private static string MimeForAudio(string path) =>
        Path.GetExtension(path).ToLowerInvariant() switch
        {
            ".mp3" => "audio/mpeg",
            ".m4a" => "audio/mp4",
            ".mp4" => "audio/mp4",
            ".webm" => "audio/webm",
            ".ogg" => "audio/ogg",
            ".opus" => "audio/ogg",
            ".wav" => "audio/wav",
            ".flac" => "audio/flac",
            ".aac" => "audio/aac",
            _ => "application/octet-stream"
        };

    private static async Task WriteSrtAsync(string path, List<PreparedCue> cues, CancellationToken ct)
    {
        var sb = new StringBuilder();
        for (int i = 0; i < cues.Count; i++)
        {
            sb.AppendLine((i + 1).ToString());
            sb.Append(FormatSrt(cues[i].Start)).Append(" --> ").AppendLine(FormatSrt(cues[i].End));
            sb.AppendLine(cues[i].Text);
            sb.AppendLine();
        }
        await File.WriteAllTextAsync(path, sb.ToString(), new UTF8Encoding(false), ct);
    }

    private static string FormatSrt(double seconds)
    {
        var ts = TimeSpan.FromSeconds(Math.Max(0, seconds));
        return $"{(int)ts.TotalHours:00}:{ts.Minutes:00}:{ts.Seconds:00},{ts.Milliseconds:000}";
    }

    private static async Task<(int ExitCode, string Output, string Error)> RunProcessAsync(
        string exe, string workDir, IEnumerable<string> args, CancellationToken ct)
    {
        var psi = new ProcessStartInfo
        {
            FileName = exe,
            WorkingDirectory = workDir,
            UseShellExecute = false,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            CreateNoWindow = true
        };
        foreach (string arg in args) psi.ArgumentList.Add(arg);

        using var process = new Process { StartInfo = psi };
        if (!process.Start())
            throw new InvalidOperationException("Không chạy được yt-dlp.");

        Task<string> output = process.StandardOutput.ReadToEndAsync();
        Task<string> error = process.StandardError.ReadToEndAsync();

        using var reg = ct.Register(() =>
        {
            try { if (!process.HasExited) process.Kill(entireProcessTree: true); } catch { }
        });

        await process.WaitForExitAsync(ct);
        return (process.ExitCode, await output, await error);
    }

    private static string ShortError(string text)
    {
        text = text.Replace("\r", " ").Replace("\n", " ").Trim();
        return text.Length <= 240 ? text : text[^240..];
    }
}

'@
if (-not $c.Contains($classMarker)) { throw 'OfflineSubtitlePreprocessor insertion marker missing' }
$c = $c.Replace($classMarker, $offlineClasses.TrimEnd() + $nl + $nl + $classMarker)

$c = $c.Replace(
    '"Subtitle Dubbing: ÉP TẠO PHỤ ĐỀ AI"',
    '"AI preprocess: tải audio → Gemini timestamps"'
)

Set-Content $p $c -Encoding UTF8

$c = Get-Content $p -Raw
if ($c -notmatch 'ALAD Windows v8\.0 Preprocessed Dubbing') { throw 'v8 label missing' }
if ($c -notmatch 'gemini-3\.5-transcribe') { throw 'file transcribe model missing' }
if ($c -notmatch 'timestamp_granularities = new\[\] \{ "word" \}') { throw 'word timestamps missing' }
if ($c -notmatch 'yt-dlp_arm64\.exe') { throw 'ARM64 yt-dlp helper missing' }
if ($c -notmatch 'FilesUploadEndpoint') { throw 'Gemini Files upload missing' }
if ($c -notmatch 'PumpPreparedDubbing') { throw 'prepared timeline scheduler missing' }
if ($c -notmatch 'QueueSubtitleText') { throw 'subtitle text dubbing queue lost' }
if ($c -notmatch 'AdaptiveJitterWaveProvider') { throw 'stable live output pipeline lost' }

Write-Host 'ALAD Windows v8.0 Preprocessed Dubbing verified.'
