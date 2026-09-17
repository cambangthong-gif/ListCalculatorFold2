$ErrorActionPreference = 'Stop'

# Start from stable DPI-aware v5.4 + all v5.2 backend optimizations.
& "$PSScriptRoot/patch-alad-windows-v5_4a-compact-modern.ps1"

$p = 'alad-windows-v2/Program.cs'
$c = Get-Content $p -Raw
$nl = [Environment]::NewLine

$c = $c.Replace('ALAD Windows v5.4 Compact Modern', 'ALAD Windows v5.5 Smooth Dub')

# 1) Target-language speech should stay silent instead of being dubbed again.
# Official Gemini Live Translate behavior: echoTargetLanguage=false => silent when input is already target language.
$c = $c.Replace('echoTargetLanguage = true', 'echoTargetLanguage = false')

# 3.8 is instruction-driven rather than translationConfig-driven: explicitly request silence for target-language speech.
$c = $c.Replace(
    'Speak only the translation. Do not answer, explain, summarize, or add commentary. Start speaking the translation as early as possible and keep phrases short.',
    'Speak only the translation. If the source speech is already in the target language, remain completely silent and output no audio. Do not answer, explain, summarize, or add commentary. Start speaking the translation as early as possible and keep phrases short.'
)

# 2) Stabilize input streaming.
# 3.5 remains at the official 100ms recommendation. Use 80ms for 3.8 instead of 40ms to reduce WS overhead/jitter.
$c = $c.Replace(
    'SetChunkMs(SelectedLiveMode() == LiveMode.FastTranslate ? 100 : 40)',
    'SetChunkMs(SelectedLiveMode() == LiveMode.FastTranslate ? 100 : 80)'
)

# A 12-chunk DropOldest queue was too aggressive under short network stalls.
$c = $c.Replace('BoundedChannelOptions(12)', 'BoundedChannelOptions(24)')

# 3) Smoother playback: tiny 35ms device latency can underrun on irregular network audio.
$c = $c.Replace(
    '.WithLatency(lowLatency.Checked ? 35 : 80);',
    '.WithLatency(lowLatency.Checked ? 60 : 100);'
)

# Catch-up used to throw away too much translated speech at 420ms backlog.
# Only trim when backlog is clearly excessive, and keep a useful tail.
$c = $c.Replace(
    'int maxBacklog = lowLatency.Checked ? 420 : 800;',
    'int maxBacklog = lowLatency.Checked ? 850 : 1400;'
)
$c = $c.Replace(
    'int keepMs = lowLatency.Checked ? 80 : 180;',
    'int keepMs = lowLatency.Checked ? 320 : 500;'
)

# Avoid volume pumping when model audio chunks have normal small gaps.
$c = $c.Replace(
    '(DateTime.UtcNow - lastAiAudioUtc).TotalMilliseconds <= 320',
    '(DateTime.UtcNow - lastAiAudioUtc).TotalMilliseconds <= 650'
)
$c = $c.Replace(
    '(DateTime.UtcNow - lastAiAudioUtc).TotalMilliseconds > 320',
    '(DateTime.UtcNow - lastAiAudioUtc).TotalMilliseconds > 650'
)

# 4) One obvious Start/Stop toggle button.
# Remove separate stop-button action and make the primary button toggle.
$c = $c.Replace(
    '        start.Click += async (_, _) => await StartAsync();' + $nl +
    '        stop.Click += async (_, _) => await StopAsync();',
    '        start.Click += async (_, _) =>' + $nl +
    '        {' + $nl +
    '            if (isRunning)' + $nl +
    '            {' + $nl +
    '                start.Enabled = false;' + $nl +
    '                start.Text = "Đang dừng...";' + $nl +
    '                await StopAsync();' + $nl +
    '            }' + $nl +
    '            else' + $nl +
    '            {' + $nl +
    '                await StartAsync();' + $nl +
    '            }' + $nl +
    '        };' + $nl +
    '        stop.Visible = false;'
)

# Connecting state must be unmistakable.
$c = $c.Replace(
    '            SetControlsRunning(true);' + $nl +
    '            status.Text = "●  Đang kết nối Gemini...";',
    '            SetControlsRunning(true);' + $nl +
    '            start.Text = "Đang kết nối...";' + $nl +
    '            start.BackColor = Color.FromArgb(90, 90, 90);' + $nl +
    '            start.ForeColor = Color.White;' + $nl +
    '            status.Text = "● ĐANG KẾT NỐI";' + $nl +
    '            status.ForeColor = Color.FromArgb(230, 160, 40);'
)

# v5.4 source may have marker without bullet depending earlier patch order.
$c = $c.Replace(
    '            SetControlsRunning(true);' + $nl +
    '            status.Text = "Đang kết nối Gemini...";',
    '            SetControlsRunning(true);' + $nl +
    '            start.Text = "Đang kết nối...";' + $nl +
    '            start.BackColor = Color.FromArgb(90, 90, 90);' + $nl +
    '            start.ForeColor = Color.White;' + $nl +
    '            status.Text = "● ĐANG KẾT NỐI";' + $nl +
    '            status.ForeColor = Color.FromArgb(230, 160, 40);'
)

# Running state: same large primary button becomes red Stop.
$c = $c.Replace(
    '            isRunning = true;' + $nl +
    '            status.Text = "●  Đang lồng tiếng";' + $nl +
    '            inputState.Text = "Audio vào: đang chạy";',
    '            isRunning = true;' + $nl +
    '            start.Text = "■ Dừng lồng tiếng";' + $nl +
    '            start.BackColor = Color.FromArgb(196, 43, 28);' + $nl +
    '            start.ForeColor = Color.White;' + $nl +
    '            start.Enabled = true;' + $nl +
    '            status.Text = "● ĐANG LỒNG TIẾNG";' + $nl +
    '            status.ForeColor = Color.FromArgb(32, 165, 90);' + $nl +
    '            inputState.Text = "Audio vào: đang chạy";'
)
$c = $c.Replace(
    '            isRunning = true;' + $nl +
    '            status.Text = "Đang lồng tiếng";' + $nl +
    '            inputState.Text = "Audio vào: đang chạy";',
    '            isRunning = true;' + $nl +
    '            start.Text = "■ Dừng lồng tiếng";' + $nl +
    '            start.BackColor = Color.FromArgb(196, 43, 28);' + $nl +
    '            start.ForeColor = Color.White;' + $nl +
    '            start.Enabled = true;' + $nl +
    '            status.Text = "● ĐANG LỒNG TIẾNG";' + $nl +
    '            status.ForeColor = Color.FromArgb(32, 165, 90);' + $nl +
    '            inputState.Text = "Audio vào: đang chạy";'
)

# Stopped state resets primary button to blue Start.
$c = $c.Replace(
    '            SetControlsRunning(false);' + $nl +
    '            status.Text = "●  Đã dừng";',
    '            SetControlsRunning(false);' + $nl +
    '            start.Text = "▶ Bắt đầu";' + $nl +
    '            start.BackColor = Color.FromArgb(0, 103, 192);' + $nl +
    '            start.ForeColor = Color.White;' + $nl +
    '            start.Enabled = true;' + $nl +
    '            status.Text = "● ĐÃ DỪNG";' + $nl +
    '            status.ForeColor = Color.FromArgb(150, 150, 150);'
)
$c = $c.Replace(
    '            SetControlsRunning(false);' + $nl +
    '            status.Text = "Đã dừng";',
    '            SetControlsRunning(false);' + $nl +
    '            start.Text = "▶ Bắt đầu";' + $nl +
    '            start.BackColor = Color.FromArgb(0, 103, 192);' + $nl +
    '            start.ForeColor = Color.White;' + $nl +
    '            start.Enabled = true;' + $nl +
    '            status.Text = "● ĐÃ DỪNG";' + $nl +
    '            status.ForeColor = Color.FromArgb(150, 150, 150);'
)

# During connection SetControlsRunning(true) disables primary; when running we re-enable it above.
# The hidden legacy stop button is never needed.
$c = $c.Replace('        stop.Enabled = startingOrRunning;', '        stop.Enabled = false;')

# Do not occupy footer space with the hidden legacy stop button.
$c = $c.Replace('        footer.Controls.Add(stop, 1, 0);' + $nl, '')

# Make the skip behavior visible to users without adding another setting.
$c = $c.Replace(
    'Auto Ducking: ALAD tự giảm âm gốc khi giọng AI đang nói.',
    'Auto Ducking tự giảm âm gốc khi AI nói · lời đã là ngôn ngữ đích sẽ không lồng lại.'
)

Set-Content $p $c -Encoding UTF8

$c = Get-Content $p -Raw
if ($c -notmatch 'ALAD Windows v5\.5 Smooth Dub') { throw 'v5.5 label missing' }
if ($c -match 'echoTargetLanguage = true') { throw 'target-language echo still enabled' }
if ($c -notmatch 'echoTargetLanguage = false') { throw 'target-language silence missing' }
if ($c -notmatch 'BoundedChannelOptions\(24\)') { throw '24-chunk smoother queue missing' }
if ($c -notmatch '\? 100 : 80') { throw 'stable chunk timing missing' }
if ($c -notmatch '\? 850 : 1400') { throw 'gentler catch-up threshold missing' }
if ($c -notmatch '■ Dừng lồng tiếng') { throw 'toggle stop state missing' }
if ($c -notmatch '● ĐANG LỒNG TIẾNG') { throw 'clear running status missing' }
if ($c -notmatch 'BuildProcessTree\(preferred\.Pid\)') { throw 'process-tree ducking lost' }
Write-Host 'ALAD Windows v5.5 Smooth Dub patch verified.'
