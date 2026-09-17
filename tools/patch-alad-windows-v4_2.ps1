$ErrorActionPreference = 'Stop'
$p = 'alad-windows-v2/Program.cs'
$c = Get-Content $p -Raw
$nl = [Environment]::NewLine

# Version labels
$c = $c.Replace('ALAD Windows v2 — Live Dubbing', 'ALAD Windows v4.2 — Live Dubbing')
$c = $c.Replace('ALAD Windows v2', 'ALAD Windows v4.2')
$c = $c.Replace('Gemini sẵn sàng · Low latency', 'Gemini sẵn sàng · continuous stream')

# Android-like continuous input: no client VAD flush, no forced NO_INTERRUPTION.
$c = $c.Replace('            localVad.Reset();' + $nl, '')
$c = $c.Replace('        localVad.Reset();' + $nl, '')
$c = $c.Replace('                    if (lowLatency.Checked && localVad.Push(chunk)) gemini.SignalAudioStreamEnd();' + $nl, '')
$rt = '(?s)        var realtimeInputConfig = new\s*\{.*?\r?\n        \};\r?\n\r?\n        object setup;'
$c = [regex]::Replace($c, $rt, '        object setup;', 1)
$c = [regex]::Replace($c, '(?m)^\s*realtimeInputConfig,\s*\r?\n', '')

# Gemini 3.5 Live Translate must follow Google's translation-specific setup exactly.
$fast = @'
        if (mode == LiveMode.FastTranslate)
        {
            setup = new
            {
                model = "models/gemini-3.5-live-translate-preview",
                generationConfig = new
                {
                    responseModalities = new[] { "AUDIO" },
                    inputAudioTranscription = new { },
                    outputAudioTranscription = new { },
                    translationConfig = new
                    {
                        targetLanguageCode = targetLang,
                        echoTargetLanguage = true
                    }
                }
            };
        }
        else
'@
$fastPattern = '(?s)        if \(mode == LiveMode\.FastTranslate\)\s*\{\s*setup = new\s*\{.*?\r?\n            \};\r?\n        \}\r?\n        else'
$c2 = [regex]::Replace($c, $fastPattern, $fast.TrimEnd(), 1)
if ($c2 -eq $c) { throw 'Gemini 3.5 setup replacement failed' }
$c = $c2

# API-key actions like Android: open AI Studio + paste from clipboard.
$keyUi = @'
        var keyHost = new TableLayoutPanel
        {
            Dock = DockStyle.Top,
            AutoSize = true,
            ColumnCount = 1,
            RowCount = 2,
            Margin = new Padding(0)
        };
        keyHost.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        apiKey.Dock = DockStyle.Top;
        keyHost.Controls.Add(apiKey, 0, 0);

        var keyActions = new FlowLayoutPanel
        {
            Dock = DockStyle.Top,
            AutoSize = true,
            WrapContents = true,
            Margin = new Padding(0, 8, 0, 0)
        };
        rememberKey.Margin = new Padding(0, 8, 14, 0);
        var getApiKey = new Button
        {
            Text = "Tạo/Lấy API key",
            AutoSize = true,
            MinimumSize = new Size(135, 34),
            Margin = new Padding(0, 0, 8, 0)
        };
        var pasteApiKey = new Button
        {
            Text = "Dán key",
            AutoSize = true,
            MinimumSize = new Size(90, 34),
            Margin = new Padding(0)
        };
        getApiKey.Click += (_, _) =>
        {
            try
            {
                Process.Start(new ProcessStartInfo
                {
                    FileName = "https://aistudio.google.com/app/apikey",
                    UseShellExecute = true
                });
                status.Text = "Đã mở Google AI Studio để tạo/lấy API key";
            }
            catch (Exception ex)
            {
                MessageBox.Show(ex.Message, "Không mở được Google AI Studio", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
        };
        pasteApiKey.Click += (_, _) =>
        {
            try
            {
                if (!Clipboard.ContainsText())
                {
                    MessageBox.Show("Clipboard chưa có API key.", "ALAD Windows", MessageBoxButtons.OK, MessageBoxIcon.Information);
                    return;
                }
                var value = Clipboard.GetText().Trim();
                if (string.IsNullOrWhiteSpace(value)) return;
                apiKey.Text = value;
                if (rememberKey.Checked) SecureKeyStore.Save(value);
                status.Text = "Đã dán API key" + (rememberKey.Checked ? " và lưu an toàn" : "");
            }
            catch (Exception ex)
            {
                MessageBox.Show(ex.Message, "Không dán được API key", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
        };
        keyActions.Controls.Add(rememberKey);
        keyActions.Controls.Add(getApiKey);
        keyActions.Controls.Add(pasteApiKey);
        keyHost.Controls.Add(keyActions, 0, 1);
        AddSetting(root, "Gemini API key", keyHost);
'@
$keyPattern = '(?s)        var keyGrid = TwoColumnGrid\(\);\r?\n        apiKey\.Dock = DockStyle\.Fill;\r?\n        rememberKey\.Margin = new Padding\(12, 6, 0, 0\);\r?\n        keyGrid\.Controls\.Add\(apiKey, 0, 0\);\r?\n        keyGrid\.Controls\.Add\(rememberKey, 1, 0\);\r?\n        AddSetting\(root, "Gemini API key", keyGrid\);'
$c2 = [regex]::Replace($c, $keyPattern, $keyUi.TrimEnd(), 1)
if ($c2 -eq $c) { throw 'API-key UI replacement failed' }
$c = $c2

# Add original-source volume slider.
$fieldNeedle = '    private readonly Label aiVolumeLabel = new() { Text = "100%", AutoSize = true };'
$fieldReplacement = $fieldNeedle + $nl + '    private readonly TrackBar originalVolume = new() { Minimum = 0, Maximum = 100, TickFrequency = 10, Value = 100 };' + $nl + '    private readonly Label originalVolumeLabel = new() { Text = "100%", AutoSize = true };'
if (-not $c.Contains($fieldNeedle)) { throw 'AI volume field marker not found' }
$c = $c.Replace($fieldNeedle, $fieldReplacement)

$volUi = @'
        var originalVolGrid = TwoColumnGrid();
        originalVolume.Dock = DockStyle.Fill;
        originalVolumeLabel.Margin = new Padding(10, 8, 0, 0);
        originalVolGrid.Controls.Add(originalVolume, 0, 0);
        originalVolGrid.Controls.Add(originalVolumeLabel, 1, 0);
        AddSetting(root, "Âm lượng gốc", originalVolGrid);
'@
$volMarker = '        AddSetting(root, "Âm lượng AI", volGrid);'
if (-not $c.Contains($volMarker)) { throw 'AI volume UI marker not found' }
$c = $c.Replace($volMarker, $volMarker + $nl + $nl + $volUi.TrimEnd())

# Original-volume slider applies immediately while ALAD is running.
$originalEvent = @'
        originalVolume.ValueChanged += (_, _) =>
        {
            originalVolumeLabel.Text = $"{originalVolume.Value}%";
            if (ducker != null)
            {
                ducker.SetBaseFactor(originalVolume.Value / 100f);
                if ((DateTime.UtcNow - lastAiAudioUtc).TotalMilliseconds <= 320)
                    ApplyDucking(true);
                else
                    ducker.ApplyBase(source.SelectedItem as ProcessItem);
            }
        };
'@
$evtMarker = '        uiTimer.Tick += (_, _) => UpdateUiTelemetry();'
if (-not $c.Contains($evtMarker)) { throw 'UI timer marker not found' }
$c = $c.Replace($evtMarker, $originalEvent.TrimEnd() + $nl + $evtMarker)

# Preserve Windows pre-ALAD source volume, and use slider as the baseline.
$deviceField = '    private MMDevice? device;'
$c = $c.Replace($deviceField, $deviceField + $nl + '    private float baseFactor = 1f;')

$duckMarker = '    public void Duck(float factor, ProcessItem? preferred)'
$baseMethods = @'
    public void SetBaseFactor(float factor)
    {
        baseFactor = Math.Clamp(factor, 0f, 1f);
    }

    public void ApplyBase(ProcessItem? preferred)
    {
        Duck(1f, preferred);
    }

'@
if (-not $c.Contains($duckMarker)) { throw 'AudioDucker Duck marker not found' }
$c = $c.Replace($duckMarker, $baseMethods + $duckMarker)

$duckOpen = @'
    public void Duck(float factor, ProcessItem? preferred)
    {
        if (device == null) return;
'@
$duckOpenNew = @'
    public void Duck(float factor, ProcessItem? preferred)
    {
        factor = Math.Clamp(factor * baseFactor, 0f, 1f);
        if (device == null) return;
'@
if (-not $c.Contains($duckOpen)) { throw 'AudioDucker body marker not found' }
$c = $c.Replace($duckOpen, $duckOpenNew)

# On service start, apply chosen original-source volume.
$duckerStart = '            ducker = new AudioDucker(Environment.ProcessId, LogStatus);'
if (-not $c.Contains($duckerStart)) { throw 'Ducker init marker not found' }
$c = $c.Replace($duckerStart, $duckerStart + $nl + '            ducker.SetBaseFactor(originalVolume.Value / 100f);' + $nl + '            ducker.ApplyBase(source.SelectedItem as ProcessItem);')

# During AI silence/Parallel mode, return to chosen baseline (not Windows 100%).
$applyDucking = @'
    private void ApplyDucking(bool aiSpeaking)
    {
        if (ducker == null) return;
        ducker.SetBaseFactor(originalVolume.Value / 100f);

        if (!aiSpeaking)
        {
            ducker.ApplyBase(source.SelectedItem as ProcessItem);
            delayedBurstActive = false;
            return;
        }

        switch (SelectedMixMode())
        {
            case MixMode.AutoDucking:
                ducker.Duck(0.22f, source.SelectedItem as ProcessItem);
                break;
            case MixMode.VoiceOver:
                ducker.Duck(0.45f, source.SelectedItem as ProcessItem);
                break;
            case MixMode.FullDub:
                ducker.Duck(0.05f, source.SelectedItem as ProcessItem);
                break;
            case MixMode.Parallel:
                ducker.ApplyBase(source.SelectedItem as ProcessItem);
                break;
        }
    }
'@
$applyPattern = '(?s)    private void ApplyDucking\(bool aiSpeaking\)\s*\{.*?\r?\n    \}\r?\n\r?\n    private void UpdateUiTelemetry'
$c2 = [regex]::Replace($c, $applyPattern, $applyDucking.TrimEnd() + $nl + $nl + '    private void UpdateUiTelemetry', 1)
if ($c2 -eq $c) { throw 'ApplyDucking replacement failed' }
$c = $c2

Set-Content $p $c -Encoding UTF8

# Compile-time-oriented verification.
$c = Get-Content $p -Raw
if ($c -match 'localVad\.Push\(') { throw 'local VAD still active' }
if ($c -match 'activityHandling\s*=\s*"NO_INTERRUPTION"') { throw 'NO_INTERRUPTION still active' }
if ($c -notmatch 'Tạo/Lấy API key') { throw 'Get API key missing' }
if ($c -notmatch 'Dán key') { throw 'Paste API key missing' }
if ($c -notmatch 'Âm lượng gốc') { throw 'Original volume slider missing' }
if ($c -notmatch 'baseFactor') { throw 'Original volume backend missing' }
if ($c -notmatch 'generationConfig = new[\s\S]*inputAudioTranscription = new[\s\S]*translationConfig = new') { throw 'Gemini 3.5 strict config missing' }
Write-Host 'ALAD Windows v4.2 source patch verified.'
