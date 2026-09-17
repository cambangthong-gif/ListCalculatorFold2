using System.Diagnostics;
using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using System.Threading.Channels;
using NAudio.CoreAudioApi;
using NAudio.Wave;

namespace AladWindows;

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
    public override string ToString() => string.IsNullOrWhiteSpace(Title) ? $"{Name} (PID {Pid})" : $"{Name} — {Title} (PID {Pid})";
}

internal sealed class MainForm : Form
{
    private readonly TextBox apiKey = new() { UseSystemPasswordChar = true };
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
    private readonly Button refresh = new() { Text = "Làm mới ứng dụng" };
    private readonly Button start = new() { Text = "Bắt đầu lồng tiếng" };
    private readonly Button stop = new() { Text = "Dừng", Enabled = false };
    private readonly Label status = new() { Text = "Sẵn sàng", AutoSize = true };
    private readonly Label latency = new() { Text = "Buffer: 0 ms", AutoSize = true };
    private readonly TextBox transcript = new() { Multiline = true, ReadOnly = true, ScrollBars = ScrollBars.Vertical };
    private readonly NotifyIcon tray = new();
    private readonly System.Windows.Forms.Timer uiTimer = new() { Interval = 250 };

    private WasapiRecorder? recorder;
    private WasapiPlayer? player;
    private BufferedWaveProvider? outputBuffer;
    private LiveGeminiClient? gemini;
    private AudioDucker? ducker;
    private CancellationTokenSource? runCts;
    private readonly PcmChunker chunker = new();
    private DateTime lastAiAudioUtc = DateTime.MinValue;
    private bool isRunning;

    public MainForm()
    {
        Text = "ALAD Windows — Live Dubbing";
        Width = 800;
        Height = 720;
        MinimumSize = new Size(720, 620);
        Font = new Font("Segoe UI", 10f);
        BackColor = Color.FromArgb(245, 246, 248);
        FormClosing += async (_, _) => { if (isRunning) await StopAsync(); tray.Visible = false; };

        var panel = new TableLayoutPanel { Dock = DockStyle.Fill, Padding = new Padding(18), ColumnCount = 2, RowCount = 14, AutoScroll = true };
        panel.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 190));
        panel.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        Controls.Add(panel);

        AddRow(panel, 0, "Gemini API key", apiKey);
        var srcPanel = new FlowLayoutPanel { Dock = DockStyle.Fill, AutoSize = true };
        source.Width = 430; srcPanel.Controls.Add(source); srcPanel.Controls.Add(refresh);
        AddRow(panel, 1, "Nguồn âm thanh", srcPanel);

        liveMode.Items.AddRange(new object[] { "Dịch nhanh (Gemini 3.5 Translate)", "Giọng tùy chọn (Gemini 3.8 Live)" });
        liveMode.SelectedIndex = 0;
        liveMode.SelectedIndexChanged += (_, _) => voice.Enabled = liveMode.SelectedIndex == 1;
        AddRow(panel, 2, "Engine", liveMode);

        language.Items.AddRange(new object[] { "Tiếng Việt|vi", "English|en", "日本語|ja", "한국어|ko", "中文|zh" });
        language.SelectedIndex = 0;
        AddRow(panel, 3, "Ngôn ngữ đích", language);

        voice.Items.AddRange(new object[] { "Kore", "Puck", "Aoede", "Charon", "Fenrir", "Leda", "Orus", "Zephyr" });
        voice.SelectedIndex = 0; voice.Enabled = false;
        AddRow(panel, 4, "Giọng AI", voice);

        mixMode.Items.AddRange(new object[] { "Auto Ducking", "Voice-over", "Song song", "Lồng hoàn toàn" });
        mixMode.SelectedIndex = 0;
        AddRow(panel, 5, "Kiểu lồng tiếng", mixMode);

        var volPanel = new FlowLayoutPanel { Dock = DockStyle.Fill, AutoSize = true };
        aiVolume.Width = 360;
        aiVolume.ValueChanged += (_, _) => { aiVolumeLabel.Text = $"{aiVolume.Value}%"; if (player != null) player.Volume = aiVolume.Value / 100f; };
        volPanel.Controls.Add(aiVolume); volPanel.Controls.Add(aiVolumeLabel);
        AddRow(panel, 6, "Âm lượng AI", volPanel);

        AddRow(panel, 7, "Bù đồng bộ (ms)", syncMs);
        var syncPanel = new FlowLayoutPanel { Dock = DockStyle.Fill, AutoSize = true };
        syncPanel.Controls.Add(autoSync); syncPanel.Controls.Add(catchUp); syncPanel.Controls.Add(lowLatency);
        AddRow(panel, 8, "Tự đồng bộ", syncPanel);

        var buttons = new FlowLayoutPanel { Dock = DockStyle.Fill, AutoSize = true };
        start.Width = 170; stop.Width = 100; buttons.Controls.Add(start); buttons.Controls.Add(stop);
        AddRow(panel, 9, "Điều khiển", buttons);

        var st = new FlowLayoutPanel { Dock = DockStyle.Fill, AutoSize = true };
        st.Controls.Add(status); st.Controls.Add(new Label { Text = "   " }); st.Controls.Add(latency);
        AddRow(panel, 10, "Trạng thái", st);

        transcript.Height = 190; transcript.Dock = DockStyle.Fill;
        AddRow(panel, 11, "Bản chép lời", transcript);
        panel.SetRowSpan(transcript, 3);

        refresh.Click += (_, _) => RefreshProcesses();
        start.Click += async (_, _) => await StartAsync();
        stop.Click += async (_, _) => await StopAsync();
        uiTimer.Tick += (_, _) => UpdateUiTelemetry();
        uiTimer.Start();

        tray.Text = "ALAD Windows"; tray.Icon = SystemIcons.Information; tray.Visible = true;
        tray.DoubleClick += (_, _) => { Show(); WindowState = FormWindowState.Normal; Activate(); };
        var trayMenu = new ContextMenuStrip();
        trayMenu.Items.Add("Mở ALAD", null, (_, _) => { Show(); WindowState = FormWindowState.Normal; Activate(); });
        trayMenu.Items.Add("Thoát", null, async (_, _) => { await StopAsync(); tray.Visible = false; Application.Exit(); });
        tray.ContextMenuStrip = trayMenu;
        Resize += (_, _) => { if (WindowState == FormWindowState.Minimized) Hide(); };
        RefreshProcesses();
    }

    private static void AddRow(TableLayoutPanel panel, int row, string label, Control control)
    {
        panel.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        var l = new Label { Text = label, AutoSize = true, Anchor = AnchorStyles.Left, Margin = new Padding(0, 8, 8, 8) };
        control.Anchor = AnchorStyles.Left | AnchorStyles.Right; control.Margin = new Padding(0, 5, 0, 5);
        panel.Controls.Add(l, 0, row); panel.Controls.Add(control, 1, row);
    }

    private void RefreshProcesses()
    {
        var old = (source.SelectedItem as ProcessItem)?.Pid;
        source.Items.Clear(); source.Items.Add("Toàn hệ thống (trừ ALAD để tránh vọng tiếng)");
        foreach (var p in Process.GetProcesses().OrderBy(p => p.ProcessName))
        {
            try
            {
                if (p.Id == Environment.ProcessId) continue;
                var title = p.MainWindowTitle; if (string.IsNullOrWhiteSpace(title)) continue;
                source.Items.Add(new ProcessItem { Pid = (uint)p.Id, Name = p.ProcessName, Title = title });
            }
            catch { }
        }
        source.SelectedIndex = 0;
        if (old.HasValue) for (int i = 1; i < source.Items.Count; i++) if (source.Items[i] is ProcessItem pi && pi.Pid == old) source.SelectedIndex = i;
    }

    private string TargetLanguageCode() => (language.SelectedItem?.ToString() ?? "Tiếng Việt|vi").Split('|').Last();
    private LiveMode SelectedLiveMode() => liveMode.SelectedIndex == 1 ? LiveMode.CustomVoice : LiveMode.FastTranslate;
    private MixMode SelectedMixMode() => mixMode.SelectedIndex switch { 1 => MixMode.VoiceOver, 2 => MixMode.Parallel, 3 => MixMode.FullDub, _ => MixMode.AutoDucking };

    private async Task StartAsync()
    {
        if (isRunning) return;
        if (string.IsNullOrWhiteSpace(apiKey.Text)) { MessageBox.Show("Nhập Gemini API key trước.", "ALAD Windows", MessageBoxButtons.OK, MessageBoxIcon.Warning); return; }
        try
        {
            start.Enabled = false; status.Text = "Đang khởi tạo..."; transcript.Clear(); runCts = new CancellationTokenSource();
            outputBuffer = new BufferedWaveProvider(new WaveFormat(24000, 16, 1)) { BufferDuration = TimeSpan.FromSeconds(12), DiscardOnBufferOverflow = true, ReadFully = true };
            var playerBuilder = new WasapiPlayerBuilder().WithSharedMode().WithLatency(lowLatency.Checked ? 45 : 120);
            if (lowLatency.Checked) playerBuilder.WithLowLatency();
            player = playerBuilder.Build(); player.Init(outputBuffer); player.Volume = aiVolume.Value / 100f;

            ducker = new AudioDucker(Environment.ProcessId, LogStatus);
            gemini = new LiveGeminiClient(LogStatus, OnGeminiAudio, OnTranscript);
            await gemini.ConnectAsync(apiKey.Text.Trim(), TargetLanguageCode(), SelectedLiveMode(), voice.SelectedItem?.ToString() ?? "Kore", runCts.Token);

            uint targetPid; ProcessLoopbackMode loopbackMode;
            if (source.SelectedItem is ProcessItem pi) { targetPid = pi.Pid; loopbackMode = ProcessLoopbackMode.IncludeTargetProcessTree; }
            else { targetPid = (uint)Environment.ProcessId; loopbackMode = ProcessLoopbackMode.ExcludeTargetProcessTree; }

            recorder = await BuildRecorderAsync(targetPid, loopbackMode);
            var format = recorder.WaveFormat;
            LogStatus($"Capture: {format.SampleRate} Hz, {format.Channels} ch, {format.BitsPerSample} bit, {format.Encoding}");
            recorder.DataAvailable += (buffer, flags, _, _) =>
            {
                if (runCts?.IsCancellationRequested != false) return;
                var pcm = AudioConvert.ToPcm16Mono16k(buffer, format); if (pcm.Length == 0) return;
                foreach (var chunk in chunker.Push(pcm)) gemini?.QueueAudio(chunk);
            };
            recorder.RecordingStopped += (_, e) => { if (e.Exception != null) LogStatus("Capture lỗi: " + e.Exception.Message); };
            recorder.StartRecording();

            isRunning = true; stop.Enabled = true; source.Enabled = false; refresh.Enabled = false; liveMode.Enabled = false; voice.Enabled = false; language.Enabled = false;
            status.Text = "Đang lồng tiếng";
        }
        catch (Exception ex)
        {
            LogStatus("Không khởi động được: " + ex.Message);
            MessageBox.Show(ex.ToString(), "ALAD Windows - lỗi khởi động", MessageBoxButtons.OK, MessageBoxIcon.Error);
            await StopAsync();
        }
        finally { if (!isRunning) start.Enabled = true; }
    }

    private static async Task<WasapiRecorder> BuildRecorderAsync(uint pid, ProcessLoopbackMode mode)
    {
        try
        {
            return await new WasapiRecorderBuilder().WithProcessLoopback(pid, mode).WithFormat(new WaveFormat(16000, 16, 1)).WithBufferLength(50).BuildAsync();
        }
        catch
        {
            return await new WasapiRecorderBuilder().WithProcessLoopback(pid, mode).WithFormat(WaveFormat.CreateIeeeFloatWaveFormat(44100, 2)).WithBufferLength(50).BuildAsync();
        }
    }

    private async Task StopAsync()
    {
        if (!isRunning && runCts == null) return;
        isRunning = false; try { runCts?.Cancel(); } catch { }
        try { recorder?.StopRecording(); } catch { } try { recorder?.Dispose(); } catch { } recorder = null;
        try { if (gemini != null) await gemini.DisposeAsync(); } catch { } gemini = null;
        try { player?.Stop(); } catch { } try { player?.Dispose(); } catch { } player = null; outputBuffer = null;
        ducker?.Restore(); ducker?.Dispose(); ducker = null; runCts?.Dispose(); runCts = null; chunker.Reset();
        if (!IsDisposed)
        {
            start.Enabled = true; stop.Enabled = false; source.Enabled = true; refresh.Enabled = true; liveMode.Enabled = true; language.Enabled = true; voice.Enabled = liveMode.SelectedIndex == 1; status.Text = "Đã dừng";
        }
    }

    private void OnGeminiAudio(byte[] pcm24k)
    {
        if (outputBuffer == null || player == null) return;
        lastAiAudioUtc = DateTime.UtcNow; ApplySyncPolicy(pcm24k); ApplyDucking(true);
    }

    private void ApplySyncPolicy(byte[] pcm)
    {
        if (outputBuffer == null || player == null) return;
        var currentMs = BufferedMs(outputBuffer); int manual = (int)syncMs.Value; int baseTarget = lowLatency.Checked ? 100 : 260; int target = Math.Max(0, baseTarget + manual);
        if (autoSync.Checked && catchUp.Checked && currentMs > target + 900)
        {
            int dropMs = currentMs - Math.Max(target, 120); int dropBytes = dropMs * 24000 * 2 / 1000; var temp = new byte[Math.Min(dropBytes, outputBuffer.BufferedBytes)]; outputBuffer.Read(temp, 0, temp.Length);
        }
        outputBuffer.AddSamples(pcm, 0, pcm.Length); var after = BufferedMs(outputBuffer);
        if (player.PlaybackState != PlaybackState.Playing && after >= target) player.Play();
    }

    private void ApplyDucking(bool aiSpeaking)
    {
        if (ducker == null) return; var mode = SelectedMixMode(); if (!aiSpeaking) { ducker.Restore(); return; }
        switch (mode)
        {
            case MixMode.AutoDucking: ducker.Duck(0.22f, source.SelectedItem as ProcessItem); break;
            case MixMode.VoiceOver: ducker.Duck(0.45f, source.SelectedItem as ProcessItem); break;
            case MixMode.FullDub: ducker.Duck(0.05f, source.SelectedItem as ProcessItem); break;
            case MixMode.Parallel: ducker.Restore(); break;
        }
    }

    private void UpdateUiTelemetry()
    {
        if (outputBuffer != null) latency.Text = $"Buffer: {BufferedMs(outputBuffer)} ms";
        if (isRunning && (DateTime.UtcNow - lastAiAudioUtc).TotalMilliseconds > 420) ApplyDucking(false);
    }

    private static int BufferedMs(BufferedWaveProvider p) => p.WaveFormat.AverageBytesPerSecond == 0 ? 0 : (int)(1000L * p.BufferedBytes / p.WaveFormat.AverageBytesPerSecond);
    private void OnTranscript(string kind, string text)
    {
        if (string.IsNullOrWhiteSpace(text)) return;
        Ui(() => { transcript.AppendText($"[{kind}] {text}\r\n"); if (transcript.TextLength > 15000) transcript.Text = transcript.Text[^10000..]; });
    }
    private void LogStatus(string text) => Ui(() => status.Text = text);
    private void Ui(Action action) { if (IsDisposed) return; if (InvokeRequired) BeginInvoke(action); else action(); }
}

internal sealed class PcmChunker
{
    private const int ChunkBytes = 16000 * 2 / 10;
    private readonly List<byte> pending = new(ChunkBytes * 2);
    public IEnumerable<byte[]> Push(byte[] bytes)
    {
        pending.AddRange(bytes);
        while (pending.Count >= ChunkBytes) { var chunk = pending.GetRange(0, ChunkBytes).ToArray(); pending.RemoveRange(0, ChunkBytes); yield return chunk; }
    }
    public void Reset() => pending.Clear();
}

internal static class AudioConvert
{
    public static byte[] ToPcm16Mono16k(ReadOnlySpan<byte> input, WaveFormat format)
    {
        if (format.SampleRate == 16000 && format.Channels == 1 && format.BitsPerSample == 16 && format.Encoding == WaveFormatEncoding.Pcm) return input.ToArray();
        int channels = Math.Max(1, format.Channels), bytesPerSample = Math.Max(1, format.BitsPerSample / 8), frameBytes = bytesPerSample * channels, frames = input.Length / frameBytes;
        if (frames <= 0) return Array.Empty<byte>();
        var mono = new float[frames];
        for (int i = 0; i < frames; i++)
        {
            double sum = 0;
            for (int c = 0; c < channels; c++)
            {
                int o = i * frameBytes + c * bytesPerSample; float sample = 0;
                if (format.Encoding == WaveFormatEncoding.IeeeFloat && format.BitsPerSample == 32) sample = BitConverter.ToSingle(input.Slice(o, 4));
                else if (format.BitsPerSample == 16) sample = BitConverter.ToInt16(input.Slice(o, 2)) / 32768f;
                else if (format.BitsPerSample == 32) sample = BitConverter.ToInt32(input.Slice(o, 4)) / 2147483648f;
                sum += sample;
            }
            mono[i] = (float)(sum / channels);
        }
        int outFrames = Math.Max(1, (int)Math.Round(frames * 16000.0 / format.SampleRate)); var output = new byte[outFrames * 2]; double ratio = format.SampleRate / 16000.0;
        for (int i = 0; i < outFrames; i++)
        {
            double pos = i * ratio; int a = Math.Min(frames - 1, (int)pos), b = Math.Min(frames - 1, a + 1); float t = (float)(pos - a); float v = mono[a] + (mono[b] - mono[a]) * t;
            short s = (short)Math.Clamp((int)(v * 32767f), short.MinValue, short.MaxValue); output[i * 2] = (byte)(s & 0xff); output[i * 2 + 1] = (byte)((s >> 8) & 0xff);
        }
        return output;
    }
}

internal sealed class AudioDucker : IDisposable
{
    private readonly int selfPid; private readonly Action<string> log; private readonly Dictionary<uint, float> original = new(); private MMDeviceEnumerator? enumerator; private MMDevice? device;
    public AudioDucker(int selfPid, Action<string> log)
    {
        this.selfPid = selfPid; this.log = log;
        try { enumerator = new MMDeviceEnumerator(); device = enumerator.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia); }
        catch (Exception ex) { log("Ducking không khả dụng: " + ex.Message); }
    }
    public void Duck(float factor, ProcessItem? preferred)
    {
        if (device == null) return;
        try
        {
            var sessions = device.AudioSessionManager.Sessions; bool matchedPreferred = false;
            for (int i = 0; i < sessions.Count; i++)
            {
                var s = sessions[i]; uint pid; try { pid = s.GetProcessID; } catch { continue; }
                if (pid == selfPid || s.SimpleAudioVolume == null) continue; if (preferred != null && pid != preferred.Pid) continue; matchedPreferred = true;
                if (!original.ContainsKey(pid)) original[pid] = s.SimpleAudioVolume.Volume; s.SimpleAudioVolume.Volume = Math.Clamp(original[pid] * factor, 0f, 1f);
            }
            if (preferred != null && !matchedPreferred)
            {
                for (int i = 0; i < sessions.Count; i++)
                {
                    var s = sessions[i]; uint pid; try { pid = s.GetProcessID; } catch { continue; }
                    if (pid == selfPid || s.SimpleAudioVolume == null) continue;
                    if (!original.ContainsKey(pid)) original[pid] = s.SimpleAudioVolume.Volume; s.SimpleAudioVolume.Volume = Math.Clamp(original[pid] * factor, 0f, 1f);
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
                var s = sessions[i]; uint pid; try { pid = s.GetProcessID; } catch { continue; }
                if (original.TryGetValue(pid, out var v) && s.SimpleAudioVolume != null) s.SimpleAudioVolume.Volume = v;
            }
            original.Clear();
        }
        catch { original.Clear(); }
    }
    public void Dispose() { Restore(); device?.Dispose(); enumerator?.Dispose(); }
}

internal sealed class LiveGeminiClient : IAsyncDisposable
{
    private const string Endpoint = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent";
    private readonly Action<string> status; private readonly Action<byte[]> audio; private readonly Action<string, string> transcript;
    private readonly Channel<byte[]> sendQueue = Channel.CreateBounded<byte[]>(new BoundedChannelOptions(24) { FullMode = BoundedChannelFullMode.DropOldest });
    private readonly SemaphoreSlim sendLock = new(1, 1), reconnectLock = new(1, 1);
    private ClientWebSocket? ws; private CancellationTokenSource? cts; private Task? receiveTask; private Task? sendTask; private TaskCompletionSource<bool>? setupReady;
    private string apiKey = "", targetLang = "vi", voice = "Kore"; private LiveMode mode; private string? sessionHandle; private volatile bool stopping;

    public LiveGeminiClient(Action<string> status, Action<byte[]> audio, Action<string, string> transcript) { this.status = status; this.audio = audio; this.transcript = transcript; }

    public async Task ConnectAsync(string apiKey, string targetLang, LiveMode mode, string voice, CancellationToken externalToken)
    {
        this.apiKey = apiKey; this.targetLang = targetLang; this.mode = mode; this.voice = voice; stopping = false; cts = CancellationTokenSource.CreateLinkedTokenSource(externalToken);
        await OpenSocketAsync(cts.Token); sendTask = Task.Run(() => SendLoop(cts.Token), cts.Token);
    }
    public void QueueAudio(byte[] pcm16k) => sendQueue.Writer.TryWrite(pcm16k);

    private async Task OpenSocketAsync(CancellationToken ct)
    {
        status(sessionHandle == null ? "Đang kết nối Gemini..." : "Đang nối lại Gemini..."); ws?.Dispose(); ws = new ClientWebSocket(); ws.Options.KeepAliveInterval = TimeSpan.FromSeconds(15);
        var uri = new Uri($"{Endpoint}?key={Uri.EscapeDataString(apiKey)}"); await ws.ConnectAsync(uri, ct); setupReady = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        receiveTask = Task.Run(() => ReceiveLoop(ws, ct), ct); await SendSetupAsync(ws, ct);
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct); timeout.CancelAfter(TimeSpan.FromSeconds(12)); await setupReady.Task.WaitAsync(timeout.Token); status("Gemini sẵn sàng");
    }

    private async Task SendSetupAsync(ClientWebSocket socket, CancellationToken ct)
    {
        object generationConfig; object setup;
        if (mode == LiveMode.FastTranslate)
        {
            generationConfig = new { responseModalities = new[] { "AUDIO" }, inputAudioTranscription = new { }, outputAudioTranscription = new { }, translationConfig = new { targetLanguageCode = targetLang, echoTargetLanguage = false } };
            setup = new { model = "models/gemini-3.5-live-translate-preview", generationConfig, sessionResumption = new { handle = sessionHandle }, contextWindowCompression = new { slidingWindow = new { } } };
        }
        else
        {
            generationConfig = new { responseModalities = new[] { "AUDIO" }, speechConfig = new { voiceConfig = new { prebuiltVoiceConfig = new { voiceName = voice } } } };
            setup = new { model = "models/gemini-3.8-live", generationConfig, systemInstruction = new { parts = new[] { new { text = $"Act only as a real-time interpreter. Translate every spoken utterance into {targetLang}. Speak only the translation. Do not answer, explain, summarize, or add commentary. Keep the translation concise and preserve the speaker's tone." } } }, sessionResumption = new { handle = sessionHandle }, contextWindowCompression = new { slidingWindow = new { } } };
        }
        await SendTextAsync(socket, JsonSerializer.Serialize(new { setup }), ct);
    }

    private async Task SendLoop(CancellationToken ct)
    {
        await foreach (var pcm in sendQueue.Reader.ReadAllAsync(ct))
        {
            try
            {
                var socket = ws; if (socket?.State != WebSocketState.Open || setupReady?.Task.IsCompletedSuccessfully != true) continue;
                var payload = JsonSerializer.Serialize(new { realtimeInput = new { audio = new { data = Convert.ToBase64String(pcm), mimeType = "audio/pcm;rate=16000" } } }); await SendTextAsync(socket, payload, ct);
            }
            catch (OperationCanceledException) { break; }
            catch (Exception ex) { status("Gửi audio lỗi: " + ex.Message); _ = Task.Run(() => ReconnectLoop(ct), ct); }
        }
    }

    private async Task SendTextAsync(ClientWebSocket socket, string text, CancellationToken ct)
    {
        var bytes = Encoding.UTF8.GetBytes(text); await sendLock.WaitAsync(ct); try { await socket.SendAsync(bytes, WebSocketMessageType.Text, true, ct); } finally { sendLock.Release(); }
    }

    private async Task ReceiveLoop(ClientWebSocket socket, CancellationToken ct)
    {
        var buffer = new byte[128 * 1024];
        try
        {
            while (!ct.IsCancellationRequested && socket.State == WebSocketState.Open)
            {
                using var ms = new MemoryStream(); WebSocketReceiveResult r;
                do { r = await socket.ReceiveAsync(buffer, ct); if (r.MessageType == WebSocketMessageType.Close) break; ms.Write(buffer, 0, r.Count); } while (!r.EndOfMessage);
                if (r.MessageType == WebSocketMessageType.Close) break; HandleServerMessage(Encoding.UTF8.GetString(ms.ToArray()));
            }
        }
        catch (OperationCanceledException) { }
        catch (Exception ex) { if (!stopping) status("Gemini mất kết nối: " + ex.Message); }
        finally { if (!stopping && !ct.IsCancellationRequested) _ = Task.Run(() => ReconnectLoop(ct), ct); }
    }

    private void HandleServerMessage(string json)
    {
        try
        {
            using var doc = JsonDocument.Parse(json); var root = doc.RootElement;
            if (root.TryGetProperty("setupComplete", out _) || root.TryGetProperty("setup_complete", out _)) { setupReady?.TrySetResult(true); return; }
            if (root.TryGetProperty("sessionResumptionUpdate", out var sr) || root.TryGetProperty("session_resumption_update", out sr))
            {
                if (sr.TryGetProperty("newHandle", out var h) || sr.TryGetProperty("new_handle", out h)) { var v = h.GetString(); if (!string.IsNullOrWhiteSpace(v)) sessionHandle = v; }
                return;
            }
            if (root.TryGetProperty("goAway", out _) || root.TryGetProperty("go_away", out _)) { status("Gemini chuẩn bị reset kết nối..."); return; }
            if (root.TryGetProperty("error", out var err)) { status("Gemini API: " + err.ToString()); return; }
            if (!(root.TryGetProperty("serverContent", out var sc) || root.TryGetProperty("server_content", out sc))) return;
            if (sc.TryGetProperty("inputTranscription", out var it) || sc.TryGetProperty("input_transcription", out it)) if (it.TryGetProperty("text", out var t1)) transcript("Gốc", t1.GetString() ?? "");
            if (sc.TryGetProperty("outputTranscription", out var ot) || sc.TryGetProperty("output_transcription", out ot)) if (ot.TryGetProperty("text", out var t2)) transcript("Dịch", t2.GetString() ?? "");
            if (!(sc.TryGetProperty("modelTurn", out var mt) || sc.TryGetProperty("model_turn", out mt))) return; if (!mt.TryGetProperty("parts", out var parts) || parts.ValueKind != JsonValueKind.Array) return;
            foreach (var p in parts.EnumerateArray())
            {
                if (!(p.TryGetProperty("inlineData", out var inline) || p.TryGetProperty("inline_data", out inline))) continue; if (!inline.TryGetProperty("data", out var d)) continue;
                var b64 = d.GetString(); if (!string.IsNullOrEmpty(b64)) audio(Convert.FromBase64String(b64));
            }
        }
        catch { }
    }

    private async Task ReconnectLoop(CancellationToken ct)
    {
        if (stopping || ct.IsCancellationRequested) return; if (!await reconnectLock.WaitAsync(0, ct)) return;
        try
        {
            for (int attempt = 1; !stopping && !ct.IsCancellationRequested; attempt++)
            {
                var delay = Math.Min(15000, 1000 * (1 << Math.Min(attempt - 1, 4))); status($"Reconnect sau {delay / 1000.0:0.#}s..."); await Task.Delay(delay, ct);
                try { await OpenSocketAsync(ct); return; } catch (Exception ex) { status("Reconnect chưa được: " + ex.Message); }
            }
        }
        finally { reconnectLock.Release(); }
    }

    public async ValueTask DisposeAsync()
    {
        stopping = true; sendQueue.Writer.TryComplete(); try { cts?.Cancel(); } catch { }
        try { if (ws?.State == WebSocketState.Open) await ws.CloseAsync(WebSocketCloseStatus.NormalClosure, "stop", CancellationToken.None); } catch { }
        ws?.Dispose(); ws = null; cts?.Dispose(); cts = null; sendLock.Dispose(); reconnectLock.Dispose();
    }
}
