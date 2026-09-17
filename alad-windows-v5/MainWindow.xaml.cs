using System.Diagnostics;
using Microsoft.UI;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using NAudio.Wave;
using Windows.ApplicationModel.DataTransfer;
using Windows.Graphics;

namespace AladWindowsV5;

public sealed partial class MainWindow : Window
{
    private WasapiRecorder? recorder;
    private WasapiPlayer? player;
    private BufferedWaveProvider? outputBuffer;
    private LiveGeminiClient? gemini;
    private AudioDucker? ducker;
    private CancellationTokenSource? runCts;
    private readonly PcmChunker chunker = new(40);
    private readonly DispatcherTimer uiTimer = new() { Interval = TimeSpan.FromMilliseconds(200) };

    private DateTime lastAiAudioUtc = DateTime.MinValue;
    private DateTime lastInputAudioUtc = DateTime.MinValue;
    private bool isRunning;
    private bool delayedBurstActive;

    // Cached settings used by background audio callbacks.
    private int syncValueMs;
    private bool autoSyncOn = true;
    private bool catchUpOn = true;
    private bool lowLatencyOn = true;
    private MixMode mixModeSetting = MixMode.AutoDucking;
    private ProcessItem? selectedProcess;
    private float originalVolumeFactor = 1f;

    public MainWindow()
    {
        InitializeComponent();
        Title = "ALAD";
        SystemBackdrop = new MicaBackdrop();

        ConfigureWindow();
        BindEvents();

        try { ApiKeyBox.Password = SecureKeyStore.Load() ?? ""; } catch { }
        RefreshProcesses();
        CacheUiSettings();

        uiTimer.Tick += (_, _) => UpdateTelemetry();
        uiTimer.Start();
        Closed += (_, _) => { _ = StopAsync(); };
    }

    private void ConfigureWindow()
    {
        try
        {
            var hwnd = WinRT.Interop.WindowNative.GetWindowHandle(this);
            var id = Microsoft.UI.Win32Interop.GetWindowIdFromWindow(hwnd);
            var appWindow = AppWindow.GetFromWindowId(id);
            appWindow.Resize(new SizeInt32(1080, 820));
            appWindow.SetPresenter(AppWindowPresenterKind.Overlapped);

            string icon = Path.Combine(AppContext.BaseDirectory, "Assets", "alad.ico");
            if (File.Exists(icon)) appWindow.SetIcon(icon);
        }
        catch { }
    }

    private void BindEvents()
    {
        GetKeyButton.Click += (_, _) => OpenApiKeyPage();
        PasteKeyButton.Click += async (_, _) => await PasteApiKeyAsync();
        RefreshSourceButton.Click += (_, _) => RefreshProcesses();
        StartButton.Click += async (_, _) => await StartAsync();
        StopButton.Click += async (_, _) => await StopAsync();
        ClearTranscriptButton.Click += (_, _) => TranscriptBox.Text = "";

        EngineCombo.SelectionChanged += (_, _) =>
        {
            VoiceCombo.IsEnabled = !isRunning && SelectedLiveMode() == LiveMode.CustomVoice;
            ModelText.Text = SelectedLiveMode() == LiveMode.FastTranslate
                ? "Gemini 3.5 Live Translate"
                : "Gemini 3.8 Live";
        };

        ThemeCombo.SelectionChanged += (_, _) =>
        {
            RootGrid.RequestedTheme = ThemeCombo.SelectedIndex switch
            {
                1 => ElementTheme.Light,
                2 => ElementTheme.Dark,
                _ => ElementTheme.Default
            };
        };

        OriginalVolumeSlider.ValueChanged += (_, _) =>
        {
            OriginalVolumeValue.Text = $"{(int)OriginalVolumeSlider.Value}%";
            originalVolumeFactor = (float)(OriginalVolumeSlider.Value / 100.0);
            if (ducker != null)
            {
                ducker.SetBaseFactor(originalVolumeFactor);
                if ((DateTime.UtcNow - lastAiAudioUtc).TotalMilliseconds <= 320)
                    ApplyDucking(true);
                else
                    ducker.ApplyBase(selectedProcess);
            }
        };

        AiVolumeSlider.ValueChanged += (_, _) =>
        {
            AiVolumeValue.Text = $"{(int)AiVolumeSlider.Value}%";
            if (player != null) player.Volume = (float)(AiVolumeSlider.Value / 100.0);
        };

        SyncNumber.ValueChanged += (_, _) => syncValueMs = double.IsNaN(SyncNumber.Value) ? 0 : (int)SyncNumber.Value;
        AutoSyncToggle.Toggled += (_, _) => autoSyncOn = AutoSyncToggle.IsOn;
        CatchUpToggle.Toggled += (_, _) => catchUpOn = CatchUpToggle.IsOn;
        LowLatencyToggle.Toggled += (_, _) =>
        {
            lowLatencyOn = LowLatencyToggle.IsOn;
            ModeBadge.Text = lowLatencyOn ? "LOW LATENCY" : "BALANCED";
        };
        MixCombo.SelectionChanged += (_, _) => mixModeSetting = SelectedMixMode();
        SourceCombo.SelectionChanged += (_, _) => selectedProcess = SourceCombo.SelectedItem as ProcessItem;
    }

    private void CacheUiSettings()
    {
        syncValueMs = double.IsNaN(SyncNumber.Value) ? 0 : (int)SyncNumber.Value;
        autoSyncOn = AutoSyncToggle.IsOn;
        catchUpOn = CatchUpToggle.IsOn;
        lowLatencyOn = LowLatencyToggle.IsOn;
        mixModeSetting = SelectedMixMode();
        selectedProcess = SourceCombo.SelectedItem as ProcessItem;
        originalVolumeFactor = (float)(OriginalVolumeSlider.Value / 100.0);
    }

    private void OpenApiKeyPage()
    {
        try
        {
            Process.Start(new ProcessStartInfo
            {
                FileName = "https://aistudio.google.com/app/apikey",
                UseShellExecute = true
            });
            SetStatus("Đã mở Google AI Studio", StatusVisual.Info);
        }
        catch (Exception ex)
        {
            ShowAlert("Không mở được Google AI Studio", ex.Message, InfoBarSeverity.Error);
        }
    }

    private async Task PasteApiKeyAsync()
    {
        try
        {
            var view = Clipboard.GetContent();
            if (!view.Contains(StandardDataFormats.Text))
            {
                ShowAlert("Clipboard chưa có API key", "Sao chép API key từ Google AI Studio rồi thử lại.", InfoBarSeverity.Warning);
                return;
            }

            string text = (await view.GetTextAsync()).Trim();
            if (string.IsNullOrWhiteSpace(text)) return;
            ApiKeyBox.Password = text;
            if (RememberKeyToggle.IsOn) SecureKeyStore.Save(text);
            SetStatus(RememberKeyToggle.IsOn ? "Đã dán và lưu API key" : "Đã dán API key", StatusVisual.Ready);
        }
        catch (Exception ex)
        {
            ShowAlert("Không dán được API key", ex.Message, InfoBarSeverity.Error);
        }
    }

    private void RefreshProcesses()
    {
        uint? oldPid = (SourceCombo.SelectedItem as ProcessItem)?.Pid;
        SourceCombo.Items.Clear();
        SourceCombo.Items.Add("Toàn hệ thống · trừ ALAD để tránh vọng tiếng");

        foreach (var process in Process.GetProcesses().OrderBy(p => p.ProcessName))
        {
            try
            {
                if (process.Id == Environment.ProcessId || string.IsNullOrWhiteSpace(process.MainWindowTitle)) continue;
                SourceCombo.Items.Add(new ProcessItem
                {
                    Pid = (uint)process.Id,
                    Name = process.ProcessName,
                    Title = process.MainWindowTitle
                });
            }
            catch { }
        }

        SourceCombo.SelectedIndex = 0;
        if (oldPid.HasValue)
        {
            for (int i = 1; i < SourceCombo.Items.Count; i++)
            {
                if (SourceCombo.Items[i] is ProcessItem item && item.Pid == oldPid.Value)
                {
                    SourceCombo.SelectedIndex = i;
                    break;
                }
            }
        }
        selectedProcess = SourceCombo.SelectedItem as ProcessItem;
    }

    private string TargetLanguageCode()
    {
        return (LanguageCombo.SelectedItem as ComboBoxItem)?.Tag?.ToString() ?? "vi";
    }

    private string SelectedVoice()
    {
        return (VoiceCombo.SelectedItem as ComboBoxItem)?.Content?.ToString() ?? "Kore";
    }

    private LiveMode SelectedLiveMode() => EngineCombo.SelectedIndex == 1 ? LiveMode.CustomVoice : LiveMode.FastTranslate;

    private MixMode SelectedMixMode() => MixCombo.SelectedIndex switch
    {
        1 => MixMode.VoiceOver,
        2 => MixMode.Parallel,
        3 => MixMode.FullDub,
        _ => MixMode.AutoDucking
    };

    private async Task StartAsync()
    {
        if (isRunning) return;
        string apiKey = ApiKeyBox.Password.Trim();
        if (string.IsNullOrWhiteSpace(apiKey))
        {
            ShowAlert("Chưa có API key", "Nhập Gemini API key trước khi bắt đầu.", InfoBarSeverity.Warning);
            return;
        }

        try
        {
            if (RememberKeyToggle.IsOn) SecureKeyStore.Save(apiKey);
            else SecureKeyStore.Delete();
        }
        catch (Exception ex)
        {
            ShowAlert("Không lưu được API key", ex.Message, InfoBarSeverity.Warning);
        }

        try
        {
            CacheUiSettings();
            SetRunningUi(true);
            SetStatus("Đang kết nối Gemini…", StatusVisual.Connecting);
            FooterTitle.Text = "Đang chuẩn bị phiên lồng tiếng";
            FooterSubtitle.Text = "Kết nối Gemini và mở luồng âm thanh";
            TranscriptBox.Text = "";
            runCts = new CancellationTokenSource();
            delayedBurstActive = false;

            LiveMode liveMode = SelectedLiveMode();
            chunker.SetChunkMs(liveMode == LiveMode.FastTranslate ? 100 : 40);
            chunker.Reset();

            outputBuffer = new BufferedWaveProvider(new WaveFormat(24000, 16, 1))
            {
                DiscardOnBufferOverflow = true,
                ReadFully = true
            };

            var playerBuilder = new WasapiPlayerBuilder()
                .WithSharedMode()
                .WithLatency(lowLatencyOn ? 35 : 80);
            if (lowLatencyOn) playerBuilder.WithLowLatency();
            player = playerBuilder.Build();
            player.Init(outputBuffer);
            player.Volume = (float)(AiVolumeSlider.Value / 100.0);
            player.Play();

            ducker = new AudioDucker(Environment.ProcessId, LogStatus);
            ducker.SetBaseFactor(originalVolumeFactor);
            ducker.ApplyBase(selectedProcess);

            gemini = new LiveGeminiClient(LogStatus, OnGeminiAudio, OnTranscript, ClearOutputBuffer);
            await gemini.ConnectAsync(apiKey, TargetLanguageCode(), liveMode, SelectedVoice(), runCts.Token);

            uint targetPid;
            ProcessLoopbackMode loopbackMode;
            if (selectedProcess != null)
            {
                targetPid = selectedProcess.Pid;
                loopbackMode = ProcessLoopbackMode.IncludeTargetProcessTree;
            }
            else
            {
                targetPid = (uint)Environment.ProcessId;
                loopbackMode = ProcessLoopbackMode.ExcludeTargetProcessTree;
            }

            recorder = await BuildRecorderAsync(targetPid, loopbackMode);
            var format = recorder.WaveFormat;
            LogStatus($"Đã kết nối · {format.SampleRate} Hz / {format.Channels} ch");

            recorder.DataAvailable += (buffer, _, _, _) =>
            {
                if (runCts?.IsCancellationRequested != false || gemini == null) return;
                var pcm = AudioConvert.ToPcm16Mono16k(buffer, format);
                if (pcm.Length == 0) return;
                lastInputAudioUtc = DateTime.UtcNow;
                foreach (var part in chunker.Push(pcm)) gemini.QueueAudio(part);
            };
            recorder.RecordingStopped += (_, e) =>
            {
                if (e.Exception != null) LogStatus("Capture lỗi: " + e.Exception.Message);
            };
            recorder.StartRecording();

            isRunning = true;
            SetStatus("Đang lồng tiếng", StatusVisual.Active);
            InputText.Text = "Audio vào: đang chạy";
            FooterTitle.Text = "ALAD đang hoạt động";
            FooterSubtitle.Text = "Âm thanh đang được gửi tới Gemini theo thời gian thực";
        }
        catch (Exception ex)
        {
            ShowAlert("Không khởi động được", ex.Message, InfoBarSeverity.Error);
            SetStatus("Khởi động thất bại", StatusVisual.Error);
            await StopAsync();
        }
    }

    private static async Task<WasapiRecorder> BuildRecorderAsync(uint pid, ProcessLoopbackMode mode)
    {
        try
        {
            return await new WasapiRecorderBuilder()
                .WithProcessLoopback(pid, mode)
                .WithFormat(new WaveFormat(16000, 16, 1))
                .WithBufferLength(40)
                .BuildAsync();
        }
        catch
        {
            return await new WasapiRecorderBuilder()
                .WithProcessLoopback(pid, mode)
                .WithFormat(WaveFormat.CreateIeeeFloatWaveFormat(48000, 2))
                .WithBufferLength(40)
                .BuildAsync();
        }
    }

    private async Task StopAsync()
    {
        bool hadSession = isRunning || gemini != null || recorder != null;
        isRunning = false;
        try { runCts?.Cancel(); } catch { }
        try { recorder?.StopRecording(); } catch { }
        try { recorder?.Dispose(); } catch { }
        recorder = null;
        try { if (gemini != null) await gemini.DisposeAsync(); } catch { }
        gemini = null;
        try { player?.Stop(); } catch { }
        try { player?.Dispose(); } catch { }
        player = null;
        outputBuffer = null;
        ducker?.Restore();
        ducker?.Dispose();
        ducker = null;
        runCts?.Dispose();
        runCts = null;
        chunker.Reset();
        delayedBurstActive = false;

        if (hadSession)
        {
            Ui(() =>
            {
                SetRunningUi(false);
                SetStatus("Đã dừng", StatusVisual.Ready);
                LatencyText.Text = "Buffer 0 ms";
                InputText.Text = "Audio vào: chờ";
                FooterTitle.Text = "Sẵn sàng để lồng tiếng";
                FooterSubtitle.Text = "Chọn nguồn âm thanh rồi bấm Bắt đầu";
            });
        }
    }

    private void SetRunningUi(bool running)
    {
        StartButton.IsEnabled = !running;
        StopButton.IsEnabled = running;
        SourceCombo.IsEnabled = !running;
        RefreshSourceButton.IsEnabled = !running;
        EngineCombo.IsEnabled = !running;
        LanguageCombo.IsEnabled = !running;
        VoiceCombo.IsEnabled = !running && SelectedLiveMode() == LiveMode.CustomVoice;
        RememberKeyToggle.IsEnabled = !running;
        GetKeyButton.IsEnabled = !running;
        PasteKeyButton.IsEnabled = !running;
    }

    private void OnGeminiAudio(byte[] pcm24k)
    {
        if (outputBuffer == null || player == null || pcm24k.Length == 0) return;
        lastAiAudioUtc = DateTime.UtcNow;
        ApplyDucking(true);

        int manual = syncValueMs;
        if (!delayedBurstActive && manual > 0)
        {
            int silenceBytes = Math.Min(manual, 5000) * 24000 * 2 / 1000;
            if (silenceBytes > 0) outputBuffer.AddSamples(new byte[silenceBytes], 0, silenceBytes);
            delayedBurstActive = true;
        }

        if (autoSyncOn && catchUpOn)
        {
            int buffered = BufferedMs(outputBuffer);
            int maxBacklog = lowLatencyOn ? 420 : 800;
            if (buffered > maxBacklog)
            {
                int keepMs = lowLatencyOn ? 80 : 180;
                int dropMs = buffered - keepMs;
                int dropBytes = Math.Min(outputBuffer.BufferedBytes, dropMs * 24000 * 2 / 1000);
                if (dropBytes > 0)
                {
                    var trash = new byte[dropBytes];
                    outputBuffer.Read(trash.AsSpan());
                }
            }
        }

        outputBuffer.AddSamples(pcm24k, 0, pcm24k.Length);
    }

    private void ClearOutputBuffer()
    {
        try { outputBuffer?.ClearBuffer(); } catch { }
        delayedBurstActive = false;
    }

    private void ApplyDucking(bool aiSpeaking)
    {
        if (ducker == null) return;
        ducker.SetBaseFactor(originalVolumeFactor);

        if (!aiSpeaking)
        {
            ducker.ApplyBase(selectedProcess);
            delayedBurstActive = false;
            return;
        }

        switch (mixModeSetting)
        {
            case MixMode.AutoDucking:
                ducker.Duck(0.22f, selectedProcess);
                break;
            case MixMode.VoiceOver:
                ducker.Duck(0.45f, selectedProcess);
                break;
            case MixMode.FullDub:
                ducker.Duck(0.05f, selectedProcess);
                break;
            case MixMode.Parallel:
                ducker.ApplyBase(selectedProcess);
                break;
        }
    }

    private void UpdateTelemetry()
    {
        if (outputBuffer != null) LatencyText.Text = $"Buffer {BufferedMs(outputBuffer)} ms";
        if (isRunning)
        {
            InputText.Text = (DateTime.UtcNow - lastInputAudioUtc).TotalMilliseconds < 1000
                ? "Audio vào: đang chạy"
                : "Audio vào: im lặng";

            if ((DateTime.UtcNow - lastAiAudioUtc).TotalMilliseconds > 320)
                ApplyDucking(false);
        }
    }

    private static int BufferedMs(BufferedWaveProvider p) => p.WaveFormat.AverageBytesPerSecond == 0
        ? 0
        : (int)(1000L * p.BufferedBytes / p.WaveFormat.AverageBytesPerSecond);

    private void OnTranscript(string kind, string text)
    {
        if (string.IsNullOrWhiteSpace(text)) return;
        Ui(() =>
        {
            string line = $"[{kind}] {text}\r\n";
            TranscriptBox.Text += line;
            if (TranscriptBox.Text.Length > 16000)
                TranscriptBox.Text = TranscriptBox.Text[^10000..];
        });
    }

    private void LogStatus(string text) => Ui(() =>
    {
        SetStatus(text, text.Contains("lỗi", StringComparison.OrdinalIgnoreCase) || text.Contains("mất kết nối", StringComparison.OrdinalIgnoreCase)
            ? StatusVisual.Error
            : text.Contains("kết nối", StringComparison.OrdinalIgnoreCase) && !text.Contains("Đã kết nối", StringComparison.OrdinalIgnoreCase)
                ? StatusVisual.Connecting
                : isRunning ? StatusVisual.Active : StatusVisual.Info);
    });

    private void Ui(Action action)
    {
        if (!DispatcherQueue.HasThreadAccess)
            DispatcherQueue.TryEnqueue(() => action());
        else
            action();
    }

    private enum StatusVisual { Ready, Info, Connecting, Active, Error }

    private void SetStatus(string text, StatusVisual visual)
    {
        StatusText.Text = text;
        ConnectRing.IsActive = visual == StatusVisual.Connecting;
        ConnectRing.Visibility = visual == StatusVisual.Connecting ? Visibility.Visible : Visibility.Collapsed;
        StatusDot.Fill = new SolidColorBrush(visual switch
        {
            StatusVisual.Active => Colors.LimeGreen,
            StatusVisual.Connecting => Colors.DodgerBlue,
            StatusVisual.Error => Colors.OrangeRed,
            StatusVisual.Info => Colors.DeepSkyBlue,
            _ => Colors.Gray
        });
    }

    private void ShowAlert(string title, string message, InfoBarSeverity severity)
    {
        AlertBar.Title = title;
        AlertBar.Message = message;
        AlertBar.Severity = severity;
        AlertBar.IsOpen = true;
    }
}
