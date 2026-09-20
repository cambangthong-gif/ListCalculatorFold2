$ErrorActionPreference = 'Stop'

& "$PSScriptRoot/patch-alad-windows-v7_0-subtitle-dubbing.ps1"

$p = 'alad-windows-v2/Program.cs'
$c = Get-Content $p -Raw
$nl = [Environment]::NewLine

$c = $c.Replace('ALAD Windows v7.0 Subtitle Dubbing', 'ALAD Windows v7.1 Subtitle Capture Fix')

# Show page captions in transcript so it is obvious that the bridge is actually feeding ALAD.
$needle = @'
        subtitleCache?.SetVideo(currentVideoId, currentVideoTitle);
        subtitleCache?.Add(startTime, endTime, text, aiGenerated);

        gemini.QueueSubtitleText(text);
'@
$replacement = @'
        subtitleCache?.SetVideo(currentVideoId, currentVideoTitle);
        subtitleCache?.Add(startTime, endTime, text, aiGenerated);

        OnTranscript(aiGenerated ? "Phụ đề AI" : "Phụ đề trang", text);
        gemini.QueueSubtitleText(text);
'@
if (-not $c.Contains($needle)) { throw 'QueueSubtitleForDubbing marker missing' }
$c = $c.Replace($needle, $replacement)

# Caption status from bridge v1.2 gives clear feedback when page captions cannot be found.
$captionCase = @'
            if (s.Type == "caption")
            {
                if (!subtitleDubbingMode || string.IsNullOrWhiteSpace(s.Text)) return;

                lastPageCaptionUtc = DateTime.UtcNow;
                if (subtitleSource.SelectedIndex != 2)
                    QueueSubtitleForDubbing(s.Text!, s.CurrentTime, s.Duration, aiGenerated: false);
                return;
            }
'@
$captionCaseNew = @'
            if (s.Type == "captionStatus")
            {
                if (!subtitleDubbingMode) return;
                bool active = string.Equals(s.Text, "active", StringComparison.OrdinalIgnoreCase);
                if (active)
                {
                    inputState.Text = "Subtitle Dubbing: đã bắt được phụ đề trang";
                }
                else if (subtitleSource.SelectedIndex == 1)
                {
                    inputState.Text = "Chưa thấy phụ đề trang · Bridge v1.2 sẽ tự bật CC/TextTrack";
                }
                else if (subtitleSource.SelectedIndex == 0)
                {
                    inputState.Text = "Không thấy phụ đề trang · đang dùng AI fallback";
                }
                return;
            }

            if (s.Type == "caption")
            {
                if (!subtitleDubbingMode || string.IsNullOrWhiteSpace(s.Text)) return;

                lastPageCaptionUtc = DateTime.UtcNow;
                if (subtitleSource.SelectedIndex != 2)
                    QueueSubtitleForDubbing(s.Text!, s.CurrentTime, s.Duration, aiGenerated: false);
                return;
            }
'@
if (-not $c.Contains($captionCase)) { throw 'caption handler marker missing' }
$c = $c.Replace($captionCase, $captionCaseNew)

# Accept captionStatus messages from the extension.
$c = $c.Replace(
    'if (type != "state" && type != "seek" && type != "video" && type != "caption") return;',
    'if (type != "state" && type != "seek" && type != "video" && type != "caption" && type != "captionStatus") return;'
)

# Startup text makes the extension requirement explicit for page-subtitle modes.
$startupNeedle = @'
                        inputState.Text = subtitleSource.SelectedIndex switch
                        {
                            2 => "Subtitle Dubbing: ÉP TẠO PHỤ ĐỀ AI",
                            1 => "Subtitle Dubbing: chỉ dùng phụ đề trang",
                            _ => "Subtitle Dubbing: tự động phụ đề trang → AI fallback"
                        };
'@
$startupReplacement = @'
                        inputState.Text = subtitleSource.SelectedIndex switch
                        {
                            2 => "Subtitle Dubbing: ÉP TẠO PHỤ ĐỀ AI",
                            1 => "Subtitle Dubbing: chờ Bridge v1.2 bắt phụ đề trang",
                            _ => "Subtitle Dubbing: ưu tiên phụ đề trang → AI fallback"
                        };
'@
if (-not $c.Contains($startupNeedle)) { throw 'subtitle startup status marker missing' }
$c = $c.Replace($startupNeedle, $startupReplacement)

# Watchdog: page-only mode should never look silently broken.
$telemetryStart = $c.IndexOf('    private void UpdateUiTelemetry()')
if ($telemetryStart -lt 0) { throw 'UpdateUiTelemetry missing' }
$telemetryBrace = $c.IndexOf('{', $telemetryStart)
if ($telemetryBrace -lt 0) { throw 'UpdateUiTelemetry brace missing' }
$watchdog = @'

        if (isRunning && subtitleDubbingMode && subtitleSource.SelectedIndex == 1 &&
            lastPageCaptionUtc == DateTime.MinValue &&
            subtitleModeStartedUtc != DateTime.MinValue &&
            (DateTime.UtcNow - subtitleModeStartedUtc).TotalSeconds > 6)
        {
            inputState.Text = "Không nhận được phụ đề trang · kiểm tra Bridge v1.2 / reload tab";
        }
'@
$c = $c.Substring(0, $telemetryBrace + 1) + $watchdog + $c.Substring($telemetryBrace + 1)

Set-Content $p $c -Encoding UTF8

$c = Get-Content $p -Raw
if ($c -notmatch 'ALAD Windows v7\.1 Subtitle Capture Fix') { throw 'v7.1 label missing' }
if ($c -notmatch 'captionStatus') { throw 'caption status handling missing' }
if ($c -notmatch 'Phụ đề trang') { throw 'page-caption transcript missing' }
if ($c -notmatch 'Bridge v1\.2') { throw 'bridge guidance missing' }
if ($c -notmatch 'models/gemini-3\.5-transcribe-live') { throw 'AI subtitle mode lost' }
if ($c -notmatch 'AdaptiveJitterWaveProvider') { throw 'stable audio pipeline lost' }

Write-Host 'ALAD Windows v7.1 Subtitle Capture Fix verified.'
