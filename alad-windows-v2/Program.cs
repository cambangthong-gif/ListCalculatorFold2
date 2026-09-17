using System.Diagnostics;
using System.Net.WebSockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using System.Threading.Channels;
using NAudio.CoreAudioApi;
using NAudio.Wave;

namespace AladWindowsV2;

internal static class Program
{
    [STAThread]
    static void Main()
    {
        ApplicationConfiguration.Initialize();
        Application.Run(new MainForm());
    }
}

internal enum LiveMode { FastTranslate, CustomVoice }
internal enum MixMode { AutoDucking, VoiceOver, Parallel, FullDub }

internal sealed class ProcessItem
{
    public uint Pid { get; init; }
    public string Name { get; init; } = "";
    public string Title { get; init; } = "";
    public override string ToString() => string.IsNullOrWhiteSpace(Title)
        ? $"{Name} (PID {Pid})"
        : $"{Name} — {Title} (PID {Pid})";
}

internal sealed class MainForm : Form
{
    private readonly TextBox apiKey = new() { UseSystemPasswordChar = true };
    private readonly CheckBox rememberKey = new() { Text = "Nhớ API key trên máy này", Checked = true, AutoSize = true };
    private readonly ComboBox source = new() { DropDownStyle = ComboBoxStyle.DropDownList };
    private readonly ComboBox liveMode = new() { DropDownStyle = ComboBoxStyle.DropDownList };
    private readonly ComboBox voice = new() { DropDownStyle = ComboBoxStyle.DropDownList };
    private readonly ComboBox mixMode = new() { DropDownStyle = ComboBoxStyle.DropDownList };
    private readonly ComboBox language = new() { DropDownStyle = ComboBoxStyle.DropDownList };
    private readonly NumericUpDown syncMs = new() { Minimum = -2000, Maximum = 5000, Increment = 100, Value = 0 };
    private readonly CheckBox autoSync = new() { Text = "Auto Sync", Checked = true, AutoSize = true };
    private readonly CheckBox catchUp = new() { Text = "Catch-up", Checked = true, AutoSize = true };
    private readonly CheckBox lowLatency = new() { Text = "Low Latency", Checked = true, AutoSize = true };
    private readonly TrackBar aiVolume = new() { Minimum = 0, Maximum = 100, TickFrequency = 10, Value = 100 };
    private readonly Label aiVolumeLabel = new() { Text = "100%", AutoSize = true };
    private readonly Button refresh = new() { Text = "Làm mới" };
    private readonly Button start = new() { Text = "Bắt đầu lồng tiếng" };
    private readonly Button stop = new() { Text = "Dừng", Enabled = false };
    private readonly Label status = new() { Text = "Sẵn sàng", AutoSize = true };
    private readonly Label latency = new() { Text = "Buffer: 0 ms", AutoSize = true };
    private readonly Label inputState = new() { Text = "Audio vào: chờ", AutoSize = true };
    private readonly TextBox transcript = new() { Multiline = true, ReadOnly = true, ScrollBars = ScrollBars.Vertical };
    private readonly NotifyIcon tray = new();
    private readonly System.Windows.Forms.Timer uiTimer = new() { Interval = 200 };

    private WasapiRecorder? recorder;
    private WasapiPlayer? player;
    private BufferedWaveProvider? outputBuffer;
    private LiveGeminiClient? gemini;
    private AudioDucker? ducker;
    private CancellationTokenSource? runCts;
    private readonly PcmChunker chunker = new(40);
    private readonly LocalSilenceDetector localVad = new();
    private DateTime lastAiAudioUtc = DateTime.MinValue;
    private DateTime lastInputAudioUtc = DateTime.MinValue;
    private bool isRunning;
    private bool delayedBurstActive;

    public MainForm()
    {
        Text = "ALAD Windows v2 — Live Dubbing";
        Width = 980;
        Height = 820;
        MinimumSize = new Size(760, 650);
        StartPosition = FormStartPosition.CenterScreen;
        AutoScaleMode = AutoScaleMode.Dpi;
        Font = new Font("Segoe UI", 10f);
        BackColor = Color.FromArgb(245, 246, 248);
        FormClosing += async (_, _) => { if (isRunning) await StopAsync(); tray.Visible = false; };

        BuildUi();
        BindEvents();
        try { apiKey.Text = SecureKeyStore.Load() ?? ""; } catch { }
        RefreshProcesses();

        tray.Text = "ALAD Windows v2";
        tray.Icon = SystemIcons.Information;
        tray.Visible = true;
        tray.DoubleClick += (_, _) => RestoreWindow();
        var trayMenu = new ContextMenuStrip();
        trayMenu.Items.Add("Mở ALAD", null, (_, _) => RestoreWindow());
        trayMenu.Items.Add("Thoát", null, async (_, _) => { await StopAsync(); tray.Visible = false; Application.Exit(); });
        tray.ContextMenuStrip = trayMenu;
        Resize += (_, _) => { if (WindowState == FormWindowState.Minimized) Hide(); };
    }

    private void BuildUi()
    {
        var root = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            AutoScroll = true,
            Padding = new Padding(22, 18, 22, 20),
            ColumnCount = 1,
            RowCount = 0,
            BackColor = BackColor
        };
        root.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        Controls.Add(root);

        var title = new Label
        {
            Text = "ALAD Windows v2",
            Font = new Font("Segoe UI Semibold", 18f, FontStyle.Bold),
            AutoSize = true,
            Margin = new Padding(0, 0, 0, 2)
        };
        var subtitle = new Label
        {
            Text = "Lồng tiếng trực tiếp · Low latency · Auto sync",
            ForeColor = Color.DimGray,
            AutoSize = true,
            Margin = new Padding(0, 0, 0, 14)
        };
        AddAutoRow(root, title);
        AddAutoRow(root, subtitle);

        var keyGrid = TwoColumnGrid();
        apiKey.Dock = DockStyle.Fill;
        rememberKey.Margin = new Padding(12, 6, 0, 0);
        keyGrid.Controls.Add(apiKey, 0, 0);
        keyGrid.Controls.Add(rememberKey, 1, 0);
        AddSetting(root, "Gemini API key", keyGrid);

        var sourceGrid = TwoColumnGrid();
        source.Dock = DockStyle.Fill;
        refresh.AutoSize = true;
        refresh.MinimumSize = new Size(100, 32);
        refresh.Margin = new Padding(10, 0, 0, 0);
        sourceGrid.Controls.Add(source, 0, 0);
        sourceGrid.Controls.Add(refresh, 1, 0);
        AddSetting(root, "Nguồn âm thanh", sourceGrid);

        liveMode.Items.AddRange(new object[]
        {
            "Dịch nhanh — Gemini 3.5 Live Translate",
            "Giọng tùy chọn — Gemini 3.8 Live"
        });
        liveMode.SelectedIndex = 0;
        liveMode.Dock = DockStyle.Top;
        AddSetting(root, "Engine", liveMode);

        language.Items.AddRange(new object[] { "Tiếng Việt|vi", "English|en", "日本語|ja", "한국어|ko", "中文|zh" });
        language.SelectedIndex = 0;
        language.Dock = DockStyle.Top;
        AddSetting(root, "Ngôn ngữ đích", language);

        voice.Items.AddRange(new object[] { "Kore", "Puck", "Aoede", "Charon", "Fenrir", "Leda", "Orus", "Zephyr" });
        voice.SelectedIndex = 0;
        voice.Enabled = false;
        voice.Dock = DockStyle.Top;
        AddSetting(root, "Giọng AI", voice);

        mixMode.Items.AddRange(new object[] { "Auto Ducking", "Voice-over", "Song song", "Lồng hoàn toàn" });
        mixMode.SelectedIndex = 0;
        mixMode.Dock = DockStyle.Top;
        AddSetting(root, "Kiểu lồng tiếng", mixMode);

        var volGrid = TwoColumnGrid();
        aiVolume.Dock = DockStyle.Fill;
        aiVolumeLabel.Margin = new Padding(10, 8, 0, 0);
        volGrid.Controls.Add(aiVolume, 0, 0);
        volGrid.Controls.Add(aiVolumeLabel, 1, 0);
        AddSetting(root, "Âm lượng AI", volGrid);

        syncMs.Dock = DockStyle.Top;
        AddSetting(root, "Bù đồng bộ (ms)", syncMs);

        var options = new FlowLayoutPanel { Dock = DockStyle.Top, AutoSize = true, WrapContents = true, Margin = new Padding(0) };
        options.Controls.Add(autoSync);
        options.Controls.Add(catchUp);
        options.Controls.Add(lowLatency);
        AddSetting(root, "Đồng bộ và độ trễ", options);

        var buttons = new FlowLayoutPanel { Dock = DockStyle.Top, AutoSize = true, WrapContents = true, Margin = new Padding(0) };
        start.AutoSize = true;
        start.MinimumSize = new Size(190, 44);
        stop.AutoSize = true;
        stop.MinimumSize = new Size(110, 44);
        buttons.Controls.Add(start);
        buttons.Controls.Add(stop);
        AddSetting(root, "Điều khiển", buttons);

        var telemetry = new FlowLayoutPanel { Dock = DockStyle.Top, AutoSize = true, WrapContents = true, Margin = new Padding(0) };
        status.Font = new Font(Font, FontStyle.Bold);
        telemetry.Controls.Add(status);
        telemetry.Controls.Add(new Label { Text = "     " });
        telemetry.Controls.Add(latency);
        telemetry.Controls.Add(new Label { Text = "     " });
        telemetry.Controls.Add(inputState);
        AddSetting(root, "Trạng thái", telemetry);

        transcript.Height = 210;
        transcript.Dock = DockStyle.Top;
        AddSetting(root, "Bản chép lời", transcript);
    }

    private static TableLayoutPanel TwoColumnGrid()
    {
        var grid = new TableLayoutPanel { Dock = DockStyle.Top, AutoSize = true, ColumnCount = 2, RowCount = 1, Margin = new Padding(0) };
        grid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        grid.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        return grid;
    }

    private static void AddAutoRow(TableLayoutPanel root, Control control)
    {
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        control.Dock = DockStyle.Top;
        root.Controls.Add(control, 0, root.RowCount++);
    }

    private void AddSetting(TableLayoutPanel root, string caption, Control body)
    {
        var card = new TableLayoutPanel
        {
            Dock = DockStyle.Top,
            AutoSize = true,
            ColumnCount = 1,
            RowCount = 2,
            Margin = new Padding(0, 0, 0, 12),
            Padding = new Padding(12, 9, 12, 10),
            BackColor = Color.White
        };
        card.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        var label = new Label
        {
            Text = caption,
            AutoSize = true,
            Font = new Font(Font, FontStyle.Bold),
            Margin = new Padding(0, 0, 0, 6)
        };
        body.Dock = DockStyle.Top;
        body.Margin = new Padding(0);
        card.Controls.Add(label, 0, 0);
        card.Controls.Add(body, 0, 1);
        AddAutoRow(root, card);
    }

    private void BindEvents()
    {
        liveMode.SelectedIndexChanged += (_, _) => voice.Enabled = liveMode.SelectedIndex == 1 && !isRunning;
        refresh.Click += (_, _) => RefreshProcesses();
        start.Click += async (_, _) => await StartAsync();
        stop.Click += async (_, _) => await StopAsync();
        aiVolume.ValueChanged += (_, _) =>
        {
            aiVolumeLabel.Text = $"{aiVolume.Value}%";
            if (player != null) player.Volume = aiVolume.Value / 100f;
        };
        uiTimer.Tick += (_, _) => UpdateUiTelemetry();
        uiTimer.Start();
    }

    private void RestoreWindow()
    {
        Show();
        WindowState = FormWindowState.Normal;
        Activate();
    }

    private void RefreshProcesses()
    {
        var oldPid = (source.SelectedItem as ProcessItem)?.Pid;
        source.Items.Clear();
        source.Items.Add("Toàn hệ thống — trừ ALAD để tránh vọng tiếng");
        foreach (var p in Process.GetProcesses().OrderBy(p => p.ProcessName))
        {
            try
            {
                if (p.Id == Environment.ProcessId) continue;
                if (string.IsNullOrWhiteSpace(p.MainWindowTitle)) continue;
                source.Items.Add(new ProcessItem { Pid = (uint)p.Id, Name = p.ProcessName, Title = p.MainWindowTitle });
            }
            catch { }
        }
        source.SelectedIndex = 0;
        if (oldPid.HasValue)
        {
            for (int i = 1; i < source.Items.Count; i++)
            {
                if (source.Items[i] is ProcessItem item && item.Pid == oldPid.Value)
                {
                    source.SelectedIndex = i;
                    break;
                }
            }
        }
    }

    private string TargetLanguageCode() => (language.SelectedItem?.ToString() ?? "Tiếng Việt|vi").Split('|').Last();
    private LiveMode SelectedLiveMode() => liveMode.SelectedIndex == 1 ? LiveMode.CustomVoice : LiveMode.FastTranslate;
    private MixMode SelectedMixMode() => mixMode.SelectedIndex switch
    {
        1 => MixMode.VoiceOver,
        2 => MixMode.Parallel,
        3 => MixMode.FullDub,
        _ => MixMode.AutoDucking
    };

    private async Task StartAsync()
    {
        if (isRunning) return;
        if (string.IsNullOrWhiteSpace(apiKey.Text))
        {
            MessageBox.Show("Nhập Gemini API key trước.", "ALAD Windows v2", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            return;
        }

        try
        {
            if (rememberKey.Checked) SecureKeyStore.Save(apiKey.Text.Trim());
            else SecureKeyStore.Delete();
        }
        catch (Exception ex) { LogStatus("Không lưu được API key: " + ex.Message); }

        try
        {
            SetControlsRunning(true);
            status.Text = "Đang kết nối Gemini...";
            transcript.Clear();
            runCts = new CancellationTokenSource();
            chunker.Reset();
            localVad.Reset();
            delayedBurstActive = false;

            outputBuffer = new BufferedWaveProvider(new WaveFormat(24000, 16, 1))
            {
                DiscardOnBufferOverflow = true,
                ReadFully = true
            };

            var playerBuilder = new WasapiPlayerBuilder()
                .WithSharedMode()
                .WithLatency(lowLatency.Checked ? 35 : 80);
            if (lowLatency.Checked) playerBuilder.WithLowLatency();
            player = playerBuilder.Build();
            player.Init(outputBuffer);
            player.Volume = aiVolume.Value / 100f;
            player.Play(); // ReadFully=true outputs silence until first Gemini chunk arrives.

            ducker = new AudioDucker(Environment.ProcessId, LogStatus);
            gemini = new LiveGeminiClient(LogStatus, OnGeminiAudio, OnTranscript, ClearOutputBuffer);
            await gemini.ConnectAsync(
                apiKey.Text.Trim(),
                TargetLanguageCode(),
                SelectedLiveMode(),
                voice.SelectedItem?.ToString() ?? "Kore",
                runCts.Token);

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

            recorder = await BuildRecorderAsync(targetPid, loopbackMode);
            var format = recorder.WaveFormat;
            LogStatus($"Đã kết nối · Capture {format.SampleRate} Hz / {format.Channels} ch");

            recorder.DataAvailable += (buffer, _, _, _) =>
            {
                if (runCts?.IsCancellationRequested != false || gemini == null) return;
                var pcm = AudioConvert.ToPcm16Mono16k(buffer, format);
                if (pcm.Length == 0) return;
                lastInputAudioUtc = DateTime.UtcNow;

                foreach (var chunk in chunker.Push(pcm))
                {
                    gemini.QueueAudio(chunk);
                    if (lowLatency.Checked && localVad.Push(chunk)) gemini.SignalAudioStreamEnd();
                }
            };
            recorder.RecordingStopped += (_, e) =>
            {
                if (e.Exception != null) LogStatus("Capture lỗi: " + e.Exception.Message);
            };
            recorder.StartRecording();

            isRunning = true;
            status.Text = "Đang lồng tiếng";
            inputState.Text = "Audio vào: đang chạy";
        }
        catch (Exception ex)
        {
            LogStatus("Không khởi động được: " + ex.Message);
            MessageBox.Show(ex.ToString(), "ALAD Windows v2 - lỗi khởi động", MessageBoxButtons.OK, MessageBoxIcon.Error);
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
        localVad.Reset();
        delayedBurstActive = false;
        if (!IsDisposed)
        {
            SetControlsRunning(false);
            status.Text = "Đã dừng";
            latency.Text = "Buffer: 0 ms";
            inputState.Text = "Audio vào: chờ";
        }
    }

    private void SetControlsRunning(bool startingOrRunning)
    {
        start.Enabled = !startingOrRunning;
        stop.Enabled = startingOrRunning;
        source.Enabled = !startingOrRunning;
        refresh.Enabled = !startingOrRunning;
        liveMode.Enabled = !startingOrRunning;
        language.Enabled = !startingOrRunning;
        voice.Enabled = !startingOrRunning && liveMode.SelectedIndex == 1;
        rememberKey.Enabled = !startingOrRunning;
    }

    private void OnGeminiAudio(byte[] pcm24k)
    {
        if (outputBuffer == null || player == null || pcm24k.Length == 0) return;
        lastAiAudioUtc = DateTime.UtcNow;
        ApplyDucking(true);

        int manual = (int)syncMs.Value;
        if (!delayedBurstActive && manual > 0)
        {
            int silenceBytes = Math.Min(manual, 5000) * 24000 * 2 / 1000;
            if (silenceBytes > 0) outputBuffer.AddSamples(new byte[silenceBytes], 0, silenceBytes);
            delayedBurstActive = true;
        }

        if (autoSync.Checked && catchUp.Checked)
        {
            int buffered = BufferedMs(outputBuffer);
            int maxBacklog = lowLatency.Checked ? 420 : 800;
            if (buffered > maxBacklog)
            {
                int keepMs = lowLatency.Checked ? 80 : 180;
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
        Ui(() =>
        {
            try { outputBuffer?.ClearBuffer(); } catch { }
            delayedBurstActive = false;
        });
    }

    private void ApplyDucking(bool aiSpeaking)
    {
        if (ducker == null) return;
        if (!aiSpeaking)
        {
            ducker.Restore();
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
                ducker.Restore();
                break;
        }
    }

    private void UpdateUiTelemetry()
    {
        if (outputBuffer != null) latency.Text = $"Buffer: {BufferedMs(outputBuffer)} ms";
        if (isRunning)
        {
            inputState.Text = (DateTime.UtcNow - lastInputAudioUtc).TotalMilliseconds < 1000
                ? "Audio vào: đang chạy"
                : "Audio vào: im lặng";
        }
        if (isRunning && (DateTime.UtcNow - lastAiAudioUtc).TotalMilliseconds > 320)
            ApplyDucking(false);
    }

    private static int BufferedMs(BufferedWaveProvider p) => p.WaveFormat.AverageBytesPerSecond == 0
        ? 0
        : (int)(1000L * p.BufferedBytes / p.WaveFormat.AverageBytesPerSecond);

    private void OnTranscript(string kind, string text)
    {
        if (string.IsNullOrWhiteSpace(text)) return;
        Ui(() =>
        {
            transcript.AppendText($"[{kind}] {text}\r\n");
            if (transcript.TextLength > 16000) transcript.Text = transcript.Text[^10000..];
        });
    }

    private void LogStatus(string text) => Ui(() => status.Text = text);
    private void Ui(Action action)
    {
        if (IsDisposed) return;
        if (InvokeRequired) BeginInvoke(action);
        else action();
    }
}

internal sealed class PcmChunker
{
    private readonly int chunkBytes;
    private readonly List<byte> pending;

    public PcmChunker(int chunkMs)
    {
        chunkBytes = 16000 * 2 * chunkMs / 1000;
        pending = new List<byte>(chunkBytes * 3);
    }

    public IEnumerable<byte[]> Push(byte[] bytes)
    {
        pending.AddRange(bytes);
        while (pending.Count >= chunkBytes)
        {
            var chunk = pending.GetRange(0, chunkBytes).ToArray();
            pending.RemoveRange(0, chunkBytes);
            yield return chunk;
        }
    }

    public void Reset() => pending.Clear();
}

internal sealed class LocalSilenceDetector
{
    private int silenceMs;
    private bool heardAudio;
    private DateTime lastFlush = DateTime.MinValue;

    public bool Push(byte[] pcm16k40ms)
    {
        if (pcm16k40ms.Length < 2) return false;
        double sum = 0;
        int samples = pcm16k40ms.Length / 2;
        for (int i = 0; i + 1 < pcm16k40ms.Length; i += 2)
        {
            short sample = (short)(pcm16k40ms[i] | (pcm16k40ms[i + 1] << 8));
            double v = sample / 32768.0;
            sum += v * v;
        }
        double rms = Math.Sqrt(sum / Math.Max(1, samples));
        int ms = samples * 1000 / 16000;

        if (rms > 0.0075)
        {
            heardAudio = true;
            silenceMs = 0;
            return false;
        }

        if (!heardAudio) return false;
        silenceMs += ms;
        if (silenceMs < 160 || (DateTime.UtcNow - lastFlush).TotalMilliseconds < 420) return false;

        heardAudio = false;
        silenceMs = 0;
        lastFlush = DateTime.UtcNow;
        return true;
    }

    public void Reset()
    {
        silenceMs = 0;
        heardAudio = false;
        lastFlush = DateTime.MinValue;
    }
}

internal static class AudioConvert
{
    public static byte[] ToPcm16Mono16k(ReadOnlySpan<byte> input, WaveFormat format)
    {
        if (format.SampleRate == 16000 && format.Channels == 1 && format.BitsPerSample == 16 && format.Encoding == WaveFormatEncoding.Pcm)
            return input.ToArray();

        int channels = Math.Max(1, format.Channels);
        int bytesPerSample = Math.Max(1, format.BitsPerSample / 8);
        int frameBytes = bytesPerSample * channels;
        int frames = input.Length / frameBytes;
        if (frames <= 0) return Array.Empty<byte>();

        var mono = new float[frames];
        for (int i = 0; i < frames; i++)
        {
            double sum = 0;
            for (int c = 0; c < channels; c++)
            {
                int o = i * frameBytes + c * bytesPerSample;
                float sample = 0;
                if (format.Encoding == WaveFormatEncoding.IeeeFloat && format.BitsPerSample == 32)
                    sample = BitConverter.ToSingle(input.Slice(o, 4));
                else if (format.BitsPerSample == 16)
                    sample = BitConverter.ToInt16(input.Slice(o, 2)) / 32768f;
                else if (format.BitsPerSample == 32)
                    sample = BitConverter.ToInt32(input.Slice(o, 4)) / 2147483648f;
                sum += sample;
            }
            mono[i] = (float)(sum / channels);
        }

        int outFrames = Math.Max(1, (int)Math.Round(frames * 16000.0 / format.SampleRate));
        var output = new byte[outFrames * 2];
        double ratio = format.SampleRate / 16000.0;
        for (int i = 0; i < outFrames; i++)
        {
            double pos = i * ratio;
            int a = Math.Min(frames - 1, (int)pos);
            int b = Math.Min(frames - 1, a + 1);
            float t = (float)(pos - a);
            float v = mono[a] + (mono[b] - mono[a]) * t;
            short s = (short)Math.Clamp((int)(v * 32767f), short.MinValue, short.MaxValue);
            output[i * 2] = (byte)(s & 0xff);
            output[i * 2 + 1] = (byte)((s >> 8) & 0xff);
        }
        return output;
    }
}

internal sealed class AudioDucker : IDisposable
{
    private readonly int selfPid;
    private readonly Action<string> log;
    private readonly Dictionary<uint, float> original = new();
    private MMDeviceEnumerator? enumerator;
    private MMDevice? device;

    public AudioDucker(int selfPid, Action<string> log)
    {
        this.selfPid = selfPid;
        this.log = log;
        try
        {
            enumerator = new MMDeviceEnumerator();
            device = enumerator.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
        }
        catch (Exception ex) { log("Ducking không khả dụng: " + ex.Message); }
    }

    public void Duck(float factor, ProcessItem? preferred)
    {
        if (device == null) return;
        try
        {
            var sessions = device.AudioSessionManager.Sessions;
            bool matched = false;
            for (int i = 0; i < sessions.Count; i++)
            {
                var s = sessions[i];
                uint pid;
                try { pid = s.GetProcessID; } catch { continue; }
                if (pid == selfPid || s.SimpleAudioVolume == null) continue;
                if (preferred != null && pid != preferred.Pid) continue;
                matched = true;
                if (!original.ContainsKey(pid)) original[pid] = s.SimpleAudioVolume.Volume;
                s.SimpleAudioVolume.Volume = Math.Clamp(original[pid] * factor, 0f, 1f);
            }

            if (preferred != null && !matched)
            {
                for (int i = 0; i < sessions.Count; i++)
                {
                    var s = sessions[i];
                    uint pid;
                    try { pid = s.GetProcessID; } catch { continue; }
                    if (pid == selfPid || s.SimpleAudioVolume == null) continue;
                    if (!original.ContainsKey(pid)) original[pid] = s.SimpleAudioVolume.Volume;
                    s.SimpleAudioVolume.Volume = Math.Clamp(original[pid] * factor, 0f, 1f);
                }
            }
        }
        catch (Exception ex) { log("Ducking lỗi: " + ex.Message); }
    }

    public void Restore()
    {
        if (device == null || original.Count == 0) return;
        try
        {
            var sessions = device.AudioSessionManager.Sessions;
            for (int i = 0; i < sessions.Count; i++)
            {
                var s = sessions[i];
                uint pid;
                try { pid = s.GetProcessID; } catch { continue; }
                if (original.TryGetValue(pid, out var volume) && s.SimpleAudioVolume != null)
                    s.SimpleAudioVolume.Volume = volume;
            }
        }
        catch { }
        finally { original.Clear(); }
    }

    public void Dispose()
    {
        Restore();
        device?.Dispose();
        enumerator?.Dispose();
    }
}

internal sealed class LiveGeminiClient : IAsyncDisposable
{
    private const string Endpoint = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent";
    private readonly Action<string> status;
    private readonly Action<byte[]> audio;
    private readonly Action<string, string> transcript;
    private readonly Action clearPlayback;
    private readonly Channel<byte[]> sendQueue = Channel.CreateBounded<byte[]>(new BoundedChannelOptions(32)
    {
        FullMode = BoundedChannelFullMode.DropOldest,
        SingleReader = true,
        SingleWriter = false
    });
    private readonly SemaphoreSlim sendLock = new(1, 1);
    private readonly SemaphoreSlim reconnectLock = new(1, 1);

    private ClientWebSocket? ws;
    private CancellationTokenSource? cts;
    private Task? sendTask;
    private TaskCompletionSource<bool>? setupReady;
    private string apiKey = "";
    private string targetLang = "vi";
    private string voice = "Kore";
    private LiveMode mode;
    private string? sessionHandle;
    private volatile bool stopping;

    public LiveGeminiClient(Action<string> status, Action<byte[]> audio, Action<string, string> transcript, Action clearPlayback)
    {
        this.status = status;
        this.audio = audio;
        this.transcript = transcript;
        this.clearPlayback = clearPlayback;
    }

    public async Task ConnectAsync(string apiKey, string targetLang, LiveMode mode, string voice, CancellationToken externalToken)
    {
        this.apiKey = apiKey;
        this.targetLang = targetLang;
        this.mode = mode;
        this.voice = voice;
        stopping = false;
        cts = CancellationTokenSource.CreateLinkedTokenSource(externalToken);
        await OpenSocketAsync(cts.Token);
        sendTask = Task.Run(() => SendLoop(cts.Token), cts.Token);
    }

    public void QueueAudio(byte[] pcm16k) => sendQueue.Writer.TryWrite(pcm16k);

    public void SignalAudioStreamEnd()
    {
        var socket = ws;
        var token = cts?.Token ?? CancellationToken.None;
        if (socket?.State != WebSocketState.Open || setupReady?.Task.IsCompletedSuccessfully != true || token.IsCancellationRequested)
            return;

        _ = Task.Run(async () =>
        {
            try
            {
                var payload = JsonSerializer.Serialize(new { realtimeInput = new { audioStreamEnd = true } });
                await SendTextAsync(socket, payload, token);
            }
            catch { }
        }, token);
    }

    private async Task OpenSocketAsync(CancellationToken ct)
    {
        status(sessionHandle == null ? "Đang kết nối Gemini..." : "Đang nối lại Gemini...");
        try { ws?.Abort(); ws?.Dispose(); } catch { }
        var socket = new ClientWebSocket();
        socket.Options.KeepAliveInterval = TimeSpan.FromSeconds(15);
        ws = socket;

        var uri = new Uri($"{Endpoint}?key={Uri.EscapeDataString(apiKey)}");
        await socket.ConnectAsync(uri, ct);
        setupReady = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        _ = Task.Run(() => ReceiveLoop(socket, ct), ct);
        await SendSetupAsync(socket, ct);

        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeout.CancelAfter(TimeSpan.FromSeconds(25));
        await setupReady.Task.WaitAsync(timeout.Token);
        status("Gemini sẵn sàng · Low latency");
    }

    private async Task SendSetupAsync(ClientWebSocket socket, CancellationToken ct)
    {
        var realtimeInputConfig = new
        {
            automaticActivityDetection = new
            {
                disabled = false,
                startOfSpeechSensitivity = "START_SENSITIVITY_HIGH",
                endOfSpeechSensitivity = "END_SENSITIVITY_HIGH",
                prefixPaddingMs = 40,
                silenceDurationMs = 120
            },
            // Critical for dubbing: source speech must NOT interrupt translated output.
            activityHandling = "NO_INTERRUPTION"
        };

        object setup;
        if (mode == LiveMode.FastTranslate)
        {
            setup = new
            {
                model = "models/gemini-3.5-live-translate-preview",
                generationConfig = new
                {
                    responseModalities = new[] { "AUDIO" },
                    translationConfig = new { targetLanguageCode = targetLang, echoTargetLanguage = false }
                },
                inputAudioTranscription = new { },
                outputAudioTranscription = new { },
                realtimeInputConfig,
                sessionResumption = new { handle = sessionHandle },
                contextWindowCompression = new { slidingWindow = new { } }
            };
        }
        else
        {
            setup = new
            {
                model = "models/gemini-3.8-live",
                generationConfig = new
                {
                    responseModalities = new[] { "AUDIO" },
                    speechConfig = new { voiceConfig = new { prebuiltVoiceConfig = new { voiceName = voice } } }
                },
                inputAudioTranscription = new { },
                outputAudioTranscription = new { },
                realtimeInputConfig,
                systemInstruction = new
                {
                    parts = new[]
                    {
                        new
                        {
                            text = $"Act only as a real-time interpreter. Translate every spoken utterance into {targetLang}. Speak only the translation. Do not answer, explain, summarize, or add commentary. Start speaking the translation as early as possible and keep phrases short."
                        }
                    }
                },
                sessionResumption = new { handle = sessionHandle },
                contextWindowCompression = new { slidingWindow = new { } }
            };
        }

        await SendTextAsync(socket, JsonSerializer.Serialize(new { setup }), ct);
    }

    private async Task SendLoop(CancellationToken ct)
    {
        await foreach (var pcm in sendQueue.Reader.ReadAllAsync(ct))
        {
            try
            {
                var socket = ws;
                if (socket?.State != WebSocketState.Open || setupReady?.Task.IsCompletedSuccessfully != true)
                    continue;

                var payload = JsonSerializer.Serialize(new
                {
                    realtimeInput = new
                    {
                        audio = new { data = Convert.ToBase64String(pcm), mimeType = "audio/pcm;rate=16000" }
                    }
                });
                await SendTextAsync(socket, payload, ct);
            }
            catch (OperationCanceledException) { break; }
            catch (Exception ex)
            {
                status("Gửi audio lỗi: " + ex.Message);
                _ = Task.Run(() => ReconnectLoop(ct), ct);
            }
        }
    }

    private async Task SendTextAsync(ClientWebSocket socket, string text, CancellationToken ct)
    {
        byte[] bytes = Encoding.UTF8.GetBytes(text);
        await sendLock.WaitAsync(ct);
        try { await socket.SendAsync(bytes, WebSocketMessageType.Text, true, ct); }
        finally { sendLock.Release(); }
    }

    private async Task ReceiveLoop(ClientWebSocket socket, CancellationToken ct)
    {
        var buffer = new byte[128 * 1024];
        try
        {
            while (!ct.IsCancellationRequested && socket.State == WebSocketState.Open)
            {
                using var ms = new MemoryStream();
                WebSocketReceiveResult result;
                do
                {
                    result = await socket.ReceiveAsync(buffer, ct);
                    if (result.MessageType == WebSocketMessageType.Close) break;
                    ms.Write(buffer, 0, result.Count);
                }
                while (!result.EndOfMessage);

                if (result.MessageType == WebSocketMessageType.Close) break;
                HandleServerMessage(Encoding.UTF8.GetString(ms.ToArray()));
            }
        }
        catch (OperationCanceledException) { }
        catch (Exception ex)
        {
            if (!stopping) status("Gemini mất kết nối: " + ex.Message);
        }
        finally
        {
            if (!stopping && !ct.IsCancellationRequested)
                _ = Task.Run(() => ReconnectLoop(ct), ct);
        }
    }

    private void HandleServerMessage(string json)
    {
        try
        {
            using var doc = JsonDocument.Parse(json);
            var root = doc.RootElement;

            if (root.TryGetProperty("setupComplete", out _) || root.TryGetProperty("setup_complete", out _))
            {
                setupReady?.TrySetResult(true);
                return;
            }

            if (root.TryGetProperty("sessionResumptionUpdate", out var sr) || root.TryGetProperty("session_resumption_update", out sr))
            {
                if (sr.TryGetProperty("newHandle", out var h) || sr.TryGetProperty("new_handle", out h))
                {
                    string? value = h.GetString();
                    if (!string.IsNullOrWhiteSpace(value)) sessionHandle = value;
                }
                return;
            }

            if (root.TryGetProperty("goAway", out _) || root.TryGetProperty("go_away", out _))
            {
                status("Gemini chuẩn bị reset kết nối...");
                return;
            }

            if (root.TryGetProperty("error", out var error))
            {
                status("Gemini API: " + error.ToString());
                return;
            }

            if (!(root.TryGetProperty("serverContent", out var sc) || root.TryGetProperty("server_content", out sc)))
                return;

            if ((sc.TryGetProperty("interrupted", out var interrupted) && interrupted.ValueKind == JsonValueKind.True))
            {
                clearPlayback();
            }

            if (sc.TryGetProperty("inputTranscription", out var inputT) || sc.TryGetProperty("input_transcription", out inputT))
            {
                if (inputT.TryGetProperty("text", out var text)) transcript("Gốc", text.GetString() ?? "");
            }

            if (sc.TryGetProperty("outputTranscription", out var outputT) || sc.TryGetProperty("output_transcription", out outputT))
            {
                if (outputT.TryGetProperty("text", out var text)) transcript("Dịch", text.GetString() ?? "");
            }

            if (!(sc.TryGetProperty("modelTurn", out var modelTurn) || sc.TryGetProperty("model_turn", out modelTurn)))
                return;
            if (!modelTurn.TryGetProperty("parts", out var parts) || parts.ValueKind != JsonValueKind.Array)
                return;

            foreach (var part in parts.EnumerateArray())
            {
                if (!(part.TryGetProperty("inlineData", out var inline) || part.TryGetProperty("inline_data", out inline)))
                    continue;
                if (!inline.TryGetProperty("data", out var data)) continue;
                string? base64 = data.GetString();
                if (!string.IsNullOrEmpty(base64)) audio(Convert.FromBase64String(base64));
            }
        }
        catch { }
    }

    private async Task ReconnectLoop(CancellationToken ct)
    {
        if (stopping || ct.IsCancellationRequested) return;
        if (!await reconnectLock.WaitAsync(0, ct)) return;
        try
        {
            clearPlayback();
            for (int attempt = 1; !stopping && !ct.IsCancellationRequested; attempt++)
            {
                int delay = Math.Min(15000, 1000 * (1 << Math.Min(attempt - 1, 4)));
                status($"Reconnect sau {delay / 1000.0:0.#}s...");
                await Task.Delay(delay, ct);
                try
                {
                    await OpenSocketAsync(ct);
                    return;
                }
                catch (Exception ex) { status("Reconnect chưa được: " + ex.Message); }
            }
        }
        finally { reconnectLock.Release(); }
    }

    public async ValueTask DisposeAsync()
    {
        stopping = true;
        sendQueue.Writer.TryComplete();
        try { cts?.Cancel(); } catch { }
        try
        {
            if (ws?.State == WebSocketState.Open)
                await ws.CloseAsync(WebSocketCloseStatus.NormalClosure, "stop", CancellationToken.None);
        }
        catch { }
        try { if (sendTask != null) await sendTask; } catch { }
        ws?.Dispose();
        ws = null;
        cts?.Dispose();
        cts = null;
        sendLock.Dispose();
        reconnectLock.Dispose();
    }
}

internal static class SecureKeyStore
{
    private const string TargetName = "ALAD.Windows.V2.GeminiApiKey";
    private const uint CredTypeGeneric = 1;
    private const uint CredPersistLocalMachine = 2;

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct Credential
    {
        public uint Flags;
        public uint Type;
        public string TargetName;
        public string? Comment;
        public System.Runtime.InteropServices.ComTypes.FILETIME LastWritten;
        public uint CredentialBlobSize;
        public IntPtr CredentialBlob;
        public uint Persist;
        public uint AttributeCount;
        public IntPtr Attributes;
        public string? TargetAlias;
        public string UserName;
    }

    [DllImport("advapi32.dll", EntryPoint = "CredWriteW", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool CredWrite(ref Credential credential, uint flags);

    [DllImport("advapi32.dll", EntryPoint = "CredReadW", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool CredRead(string target, uint type, uint reservedFlag, out IntPtr credentialPtr);

    [DllImport("advapi32.dll", EntryPoint = "CredDeleteW", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool CredDelete(string target, uint type, uint flags);

    [DllImport("advapi32.dll")]
    private static extern void CredFree(IntPtr buffer);

    public static void Save(string value)
    {
        if (string.IsNullOrWhiteSpace(value)) { Delete(); return; }
        byte[] bytes = Encoding.Unicode.GetBytes(value);
        IntPtr blob = Marshal.AllocCoTaskMem(bytes.Length);
        try
        {
            Marshal.Copy(bytes, 0, blob, bytes.Length);
            var credential = new Credential
            {
                Type = CredTypeGeneric,
                TargetName = TargetName,
                CredentialBlobSize = (uint)bytes.Length,
                CredentialBlob = blob,
                Persist = CredPersistLocalMachine,
                UserName = Environment.UserName
            };
            if (!CredWrite(ref credential, 0))
                throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());
        }
        finally { Marshal.FreeCoTaskMem(blob); }
    }

    public static string? Load()
    {
        if (!CredRead(TargetName, CredTypeGeneric, 0, out var ptr)) return null;
        try
        {
            var credential = Marshal.PtrToStructure<Credential>(ptr);
            if (credential.CredentialBlob == IntPtr.Zero || credential.CredentialBlobSize == 0) return null;
            return Marshal.PtrToStringUni(credential.CredentialBlob, checked((int)credential.CredentialBlobSize / 2));
        }
        finally { CredFree(ptr); }
    }

    public static void Delete()
    {
        if (!CredDelete(TargetName, CredTypeGeneric, 0))
        {
            int error = Marshal.GetLastWin32Error();
            if (error != 1168) throw new System.ComponentModel.Win32Exception(error);
        }
    }
}
