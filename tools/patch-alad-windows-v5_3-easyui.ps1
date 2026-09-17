$ErrorActionPreference = 'Stop'

# Start from v5.2 so all backend/audio optimizations are preserved.
& "$PSScriptRoot/patch-alad-windows-v5_2-optimized.ps1"

$p = 'alad-windows-v2/Program.cs'
$c = Get-Content $p -Raw
$nl = [Environment]::NewLine

$c = $c.Replace('ALAD Windows v5.2 Compact Optimized', 'ALAD Windows v5.3 Easy UI')

# Safer, touch-friendly window defaults.
$c = $c.Replace('        Width = 920;', '        Width = 940;')
$c = $c.Replace('        Height = 650;', '        Height = 700;')
$c = $c.Replace('        MinimumSize = new Size(820, 560);', '        MinimumSize = new Size(860, 620);')

$easyUi = @'
    private void BuildUi()
    {
        bool dark = IsSystemDarkTheme();
        Color page = dark ? Color.FromArgb(32, 32, 32) : Color.FromArgb(246, 246, 246);
        Color surface = dark ? Color.FromArgb(43, 43, 43) : Color.White;
        Color surface2 = dark ? Color.FromArgb(50, 50, 50) : Color.FromArgb(250, 250, 250);
        Color text = dark ? Color.FromArgb(245, 245, 245) : Color.FromArgb(32, 32, 32);
        Color muted = dark ? Color.FromArgb(185, 185, 185) : Color.FromArgb(96, 96, 96);
        Color border = dark ? Color.FromArgb(72, 72, 72) : Color.FromArgb(220, 220, 220);
        Color accent = Color.FromArgb(0, 103, 192);

        BackColor = page;
        ForeColor = text;
        AutoScaleMode = AutoScaleMode.Dpi;

        void StyleButton(Button b, bool primary = false)
        {
            b.FlatStyle = FlatStyle.Flat;
            b.FlatAppearance.BorderSize = primary ? 0 : 1;
            b.FlatAppearance.BorderColor = border;
            b.BackColor = primary ? accent : surface2;
            b.ForeColor = primary ? Color.White : text;
            b.Font = new Font("Segoe UI", 9.5f, primary ? FontStyle.Bold : FontStyle.Regular);
            b.Height = 42;
            b.Padding = new Padding(12, 0, 12, 0);
            b.Cursor = Cursors.Hand;
            b.UseVisualStyleBackColor = false;
        }

        void StyleCombo(ComboBox cb)
        {
            cb.DropDownStyle = ComboBoxStyle.DropDownList;
            cb.FlatStyle = FlatStyle.Standard;
            cb.Font = new Font("Segoe UI", 9.5f);
            cb.BackColor = surface2;
            cb.ForeColor = text;
            cb.Height = 34;
        }

        Label Caption(string value) => new()
        {
            Text = value,
            AutoSize = true,
            ForeColor = muted,
            Font = new Font("Segoe UI", 8.8f),
            Margin = new Padding(0, 0, 0, 4)
        };

        Panel Section(string title, int height)
        {
            var p = new Panel
            {
                Dock = DockStyle.Top,
                Height = height,
                BackColor = surface,
                Padding = new Padding(16),
                Margin = new Padding(0, 0, 0, 10),
                BorderStyle = BorderStyle.FixedSingle
            };
            p.Controls.Add(new Label
            {
                Text = title,
                AutoSize = true,
                ForeColor = text,
                Font = new Font("Segoe UI Semibold", 11f, FontStyle.Bold),
                Location = new Point(16, 13)
            });
            return p;
        }

        SuspendLayout();

        var root = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 1,
            RowCount = 3,
            BackColor = page,
            Padding = new Padding(14)
        };
        root.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        root.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        Controls.Add(root);

        // Header: simple and stable, no Region clipping / fake rounded controls.
        var header = new TableLayoutPanel
        {
            Dock = DockStyle.Top,
            AutoSize = true,
            ColumnCount = 2,
            Margin = new Padding(0, 0, 0, 10),
            BackColor = page
        };
        header.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        header.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));

        var titleBox = new FlowLayoutPanel
        {
            AutoSize = true,
            FlowDirection = FlowDirection.TopDown,
            WrapContents = false,
            Margin = new Padding(0)
        };
        titleBox.Controls.Add(new Label
        {
            Text = "ALAD",
            AutoSize = true,
            ForeColor = text,
            Font = new Font("Segoe UI", 17f, FontStyle.Bold),
            Margin = new Padding(0)
        });
        titleBox.Controls.Add(new Label
        {
            Text = "Lồng tiếng trực tiếp",
            AutoSize = true,
            ForeColor = muted,
            Font = new Font("Segoe UI", 9f),
            Margin = new Padding(1, 1, 0, 0)
        });

        var statusBox = new Panel
        {
            Width = 315,
            Height = 48,
            BackColor = surface,
            BorderStyle = BorderStyle.FixedSingle,
            Margin = new Padding(8, 1, 0, 0)
        };
        status.Text = "●  Sẵn sàng";
        status.AutoSize = false;
        status.Location = new Point(10, 5);
        status.Size = new Size(295, 20);
        status.ForeColor = Color.FromArgb(32, 165, 90);
        status.Font = new Font("Segoe UI Semibold", 9f, FontStyle.Bold);
        latency.AutoSize = false;
        latency.Location = new Point(10, 26);
        latency.Size = new Size(135, 17);
        latency.ForeColor = muted;
        latency.Font = new Font("Segoe UI", 8.2f);
        inputState.AutoSize = false;
        inputState.Location = new Point(150, 26);
        inputState.Size = new Size(155, 17);
        inputState.TextAlign = ContentAlignment.MiddleRight;
        inputState.ForeColor = muted;
        inputState.Font = new Font("Segoe UI", 8.2f);
        statusBox.Controls.Add(status);
        statusBox.Controls.Add(latency);
        statusBox.Controls.Add(inputState);

        header.Controls.Add(titleBox, 0, 0);
        header.Controls.Add(statusBox, 1, 0);
        root.Controls.Add(header, 0, 0);

        var tabs = new TabControl
        {
            Dock = DockStyle.Fill,
            Font = new Font("Segoe UI Semibold", 9.5f),
            Padding = new Point(14, 7),
            Margin = new Padding(0),
            HotTrack = true
        };
        var mainTab = new TabPage("Điều khiển") { BackColor = page, Padding = new Padding(10) };
        var advTab = new TabPage("Nâng cao") { BackColor = page, Padding = new Padding(10) };
        tabs.TabPages.Add(mainTab);
        tabs.TabPages.Add(advTab);
        root.Controls.Add(tabs, 0, 1);

        // MAIN TAB
        var mainScroll = new Panel
        {
            Dock = DockStyle.Fill,
            AutoScroll = true,
            BackColor = page,
            Padding = new Padding(0)
        };
        mainTab.Controls.Add(mainScroll);

        var connect = Section("1. Kết nối Gemini", 150);
        connect.Dock = DockStyle.Top;

        var keyLabel = Caption("API key");
        keyLabel.Location = new Point(16, 47);
        connect.Controls.Add(keyLabel);

        apiKey.Location = new Point(16, 68);
        apiKey.Size = new Size(500, 32);
        apiKey.Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right;
        apiKey.Font = new Font("Segoe UI", 9.5f);
        apiKey.BorderStyle = BorderStyle.FixedSingle;
        apiKey.BackColor = surface2;
        apiKey.ForeColor = text;
        apiKey.UseSystemPasswordChar = true;
        connect.Controls.Add(apiKey);

        var showKey = new Button { Text = "Hiện", Location = new Point(524, 65), Width = 65 };
        StyleButton(showKey);
        showKey.Height = 36;
        showKey.Click += (_, _) =>
        {
            apiKey.UseSystemPasswordChar = !apiKey.UseSystemPasswordChar;
            showKey.Text = apiKey.UseSystemPasswordChar ? "Hiện" : "Ẩn";
        };
        connect.Controls.Add(showKey);

        var pasteKey = new Button { Text = "Dán key", Location = new Point(596, 65), Width = 88 };
        StyleButton(pasteKey);
        pasteKey.Height = 36;
        pasteKey.Click += (_, _) =>
        {
            try
            {
                if (!Clipboard.ContainsText())
                {
                    MessageBox.Show("Clipboard chưa có API key.", "ALAD", MessageBoxButtons.OK, MessageBoxIcon.Information);
                    return;
                }
                var value = Clipboard.GetText().Trim();
                if (value.Length == 0) return;
                apiKey.Text = value;
                if (rememberKey.Checked) SecureKeyStore.Save(value);
                status.Text = "●  Đã dán API key";
            }
            catch (Exception ex) { MessageBox.Show(ex.Message, "ALAD", MessageBoxButtons.OK, MessageBoxIcon.Error); }
        };
        connect.Controls.Add(pasteKey);

        var getKey = new Button { Text = "Lấy API key", Location = new Point(691, 65), Width = 110 };
        StyleButton(getKey);
        getKey.Height = 36;
        getKey.Click += (_, _) =>
        {
            try { Process.Start(new ProcessStartInfo { FileName = "https://aistudio.google.com/app/apikey", UseShellExecute = true }); }
            catch (Exception ex) { MessageBox.Show(ex.Message, "ALAD", MessageBoxButtons.OK, MessageBoxIcon.Error); }
        };
        connect.Controls.Add(getKey);

        rememberKey.Text = "Nhớ API key trên máy này";
        rememberKey.AutoSize = true;
        rememberKey.Location = new Point(18, 110);
        rememberKey.ForeColor = text;
        connect.Controls.Add(rememberKey);

        var engineLabel = Caption("Engine");
        engineLabel.Location = new Point(560, 110);
        connect.Controls.Add(engineLabel);
        liveMode.Items.AddRange(new object[] { "Dịch nhanh · Gemini 3.5", "Giọng tùy chọn · Gemini 3.8" });
        liveMode.SelectedIndex = 0;
        StyleCombo(liveMode);
        liveMode.Location = new Point(620, 105);
        liveMode.Width = 220;
        connect.Controls.Add(liveMode);

        var audio = Section("2. Nguồn âm thanh & kiểu lồng tiếng", 150);
        audio.Dock = DockStyle.Top;

        var srcLabel = Caption("Nguồn âm thanh");
        srcLabel.Location = new Point(16, 47);
        audio.Controls.Add(srcLabel);
        StyleCombo(source);
        source.Location = new Point(16, 69);
        source.Width = 500;
        audio.Controls.Add(source);

        refresh.Text = "Làm mới";
        refresh.Location = new Point(525, 66);
        refresh.Width = 92;
        StyleButton(refresh);
        refresh.Height = 36;
        audio.Controls.Add(refresh);

        var mixLabel = Caption("Kiểu lồng tiếng");
        mixLabel.Location = new Point(16, 108);
        audio.Controls.Add(mixLabel);
        mixMode.Items.AddRange(new object[] { "Auto Ducking", "Voice-over", "Song song", "Lồng hoàn toàn" });
        mixMode.SelectedIndex = 0;
        StyleCombo(mixMode);
        mixMode.Location = new Point(125, 103);
        mixMode.Width = 220;
        audio.Controls.Add(mixMode);

        var sourceHelp = new Label
        {
            Text = "Mẹo: chọn đúng ứng dụng đang phát video. Nếu dùng nhiều tab trình duyệt, chọn trình duyệt chính.",
            AutoSize = false,
            ForeColor = muted,
            Font = new Font("Segoe UI", 8.5f),
            Location = new Point(365, 105),
            Size = new Size(470, 38)
        };
        audio.Controls.Add(sourceHelp);

        var volume = Section("3. Âm lượng", 135);
        volume.Dock = DockStyle.Top;

        var origText = new Label
        {
            Text = "Âm gốc: 100%",
            AutoSize = true,
            ForeColor = text,
            Font = new Font("Segoe UI Semibold", 9f),
            Location = new Point(16, 48)
        };
        originalVolumeLabel.Visible = false;
        originalVolume.TickStyle = TickStyle.None;
        originalVolume.Location = new Point(16, 72);
        originalVolume.Width = 380;
        originalVolume.ValueChanged += (_, _) => origText.Text = $"Âm gốc: {originalVolume.Value}%";
        volume.Controls.Add(origText);
        volume.Controls.Add(originalVolume);

        var aiText = new Label
        {
            Text = "Âm AI: 100%",
            AutoSize = true,
            ForeColor = text,
            Font = new Font("Segoe UI Semibold", 9f),
            Location = new Point(430, 48)
        };
        aiVolumeLabel.Visible = false;
        aiVolume.TickStyle = TickStyle.None;
        aiVolume.Location = new Point(430, 72);
        aiVolume.Width = 380;
        aiVolume.ValueChanged += (_, _) => aiText.Text = $"Âm AI: {aiVolume.Value}%";
        volume.Controls.Add(aiText);
        volume.Controls.Add(aiVolume);

        // Add in reverse order because Dock=Top stacks newest first.
        mainScroll.Controls.Add(volume);
        mainScroll.Controls.Add(audio);
        mainScroll.Controls.Add(connect);

        // ADVANCED TAB
        var adv = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 2,
            RowCount = 4,
            Padding = new Padding(4),
            BackColor = page
        };
        adv.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));
        adv.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));
        adv.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        adv.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        adv.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        adv.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        advTab.Controls.Add(adv);

        var langHost = new Panel { Height = 76, Dock = DockStyle.Fill, Margin = new Padding(0,0,6,6), BackColor = surface, BorderStyle = BorderStyle.FixedSingle };
        var langLabel = Caption("Ngôn ngữ đầu ra");
        langLabel.Location = new Point(12, 10);
        langHost.Controls.Add(langLabel);
        language.Items.AddRange(new object[] { "Tiếng Việt|vi", "English|en", "日本語|ja", "한국어|ko", "中文|zh" });
        language.SelectedIndex = 0;
        StyleCombo(language);
        language.Location = new Point(12, 31);
        language.Width = 270;
        langHost.Controls.Add(language);

        var voiceHost = new Panel { Height = 76, Dock = DockStyle.Fill, Margin = new Padding(6,0,0,6), BackColor = surface, BorderStyle = BorderStyle.FixedSingle };
        var voiceLabel = Caption("Giọng AI · chỉ dùng với Gemini 3.8");
        voiceLabel.Location = new Point(12, 10);
        voiceHost.Controls.Add(voiceLabel);
        voice.Items.AddRange(new object[] { "Kore", "Puck", "Aoede", "Charon", "Fenrir", "Leda", "Orus", "Zephyr" });
        voice.SelectedIndex = 0;
        voice.Enabled = false;
        StyleCombo(voice);
        voice.Location = new Point(12, 31);
        voice.Width = 270;
        voiceHost.Controls.Add(voice);

        adv.Controls.Add(langHost, 0, 0);
        adv.Controls.Add(voiceHost, 1, 0);

        var syncPanel = new Panel { Height = 82, Dock = DockStyle.Fill, Margin = new Padding(0,0,0,6), BackColor = surface, BorderStyle = BorderStyle.FixedSingle };
        syncPanel.Controls.Add(new Label { Text = "Đồng bộ & độ trễ", AutoSize = true, ForeColor = text, Font = new Font("Segoe UI Semibold", 10f, FontStyle.Bold), Location = new Point(12, 9) });
        autoSync.Location = new Point(12, 39); autoSync.AutoSize = true; autoSync.ForeColor = text;
        catchUp.Location = new Point(135, 39); catchUp.AutoSize = true; catchUp.ForeColor = text;
        lowLatency.Location = new Point(255, 39); lowLatency.AutoSize = true; lowLatency.ForeColor = text;
        syncPanel.Controls.Add(autoSync); syncPanel.Controls.Add(catchUp); syncPanel.Controls.Add(lowLatency);
        adv.SetColumnSpan(syncPanel, 2);
        adv.Controls.Add(syncPanel, 0, 1);

        var syncValue = new Panel { Height = 72, Dock = DockStyle.Fill, Margin = new Padding(0,0,0,6), BackColor = surface, BorderStyle = BorderStyle.FixedSingle };
        syncValue.Controls.Add(new Label { Text = "Bù sync thủ công (ms)", AutoSize = true, ForeColor = muted, Font = new Font("Segoe UI", 8.8f), Location = new Point(12, 10) });
        syncMs.Location = new Point(12, 31);
        syncMs.Width = 150;
        syncValue.Controls.Add(syncMs);
        syncValue.Controls.Add(new Label { Text = "Chỉ chỉnh khi tiếng AI luôn sớm/chậm cố định.", AutoSize = true, ForeColor = muted, Font = new Font("Segoe UI", 8.5f), Location = new Point(180, 34) });
        adv.SetColumnSpan(syncValue, 2);
        adv.Controls.Add(syncValue, 0, 2);

        var logPanel = new Panel { Dock = DockStyle.Fill, BackColor = surface, BorderStyle = BorderStyle.FixedSingle, Margin = new Padding(0) };
        logPanel.Controls.Add(new Label { Text = "Nhật ký / bản chép lời", AutoSize = true, ForeColor = text, Font = new Font("Segoe UI Semibold", 10f, FontStyle.Bold), Location = new Point(12, 10) });
        transcript.Location = new Point(12, 36);
        transcript.Anchor = AnchorStyles.Top | AnchorStyles.Bottom | AnchorStyles.Left | AnchorStyles.Right;
        transcript.BackColor = surface2;
        transcript.ForeColor = text;
        transcript.BorderStyle = BorderStyle.FixedSingle;
        transcript.Size = new Size(780, 210);
        logPanel.Controls.Add(transcript);
        adv.SetColumnSpan(logPanel, 2);
        adv.Controls.Add(logPanel, 0, 3);

        // Sticky footer; always reachable.
        var footer = new TableLayoutPanel
        {
            Dock = DockStyle.Bottom,
            AutoSize = true,
            ColumnCount = 4,
            Padding = new Padding(0, 10, 0, 0),
            Margin = new Padding(0),
            BackColor = page
        };
        footer.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        footer.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        footer.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        footer.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));

        var hint = new Label
        {
            Text = "Chọn API key + nguồn âm thanh, sau đó bấm Bắt đầu",
            AutoSize = true,
            ForeColor = muted,
            Font = new Font("Segoe UI", 8.8f),
            Anchor = AnchorStyles.Left,
            Margin = new Padding(2, 13, 10, 0)
        };

        stop.Text = "■  Dừng";
        stop.AutoSize = false;
        stop.Size = new Size(110, 44);
        stop.Margin = new Padding(0,0,8,0);
        StyleButton(stop);

        start.Text = "▶  Bắt đầu";
        start.AutoSize = false;
        start.Size = new Size(150, 44);
        start.Margin = new Padding(0,0,0,0);
        StyleButton(start, true);

        footer.Controls.Add(hint, 0, 0);
        footer.Controls.Add(stop, 2, 0);
        footer.Controls.Add(start, 3, 0);
        root.Controls.Add(footer, 0, 2);

        // Improve discoverability / touch use.
        var tips = new ToolTip { AutomaticDelay = 250, AutoPopDelay = 5000, ReshowDelay = 100 };
        tips.SetToolTip(refresh, "Cập nhật danh sách ứng dụng đang phát âm thanh");
        tips.SetToolTip(source, "Chọn ứng dụng hoặc toàn hệ thống để ALAD nghe âm thanh");
        tips.SetToolTip(mixMode, "Auto Ducking tự giảm âm gốc khi AI đang nói");
        tips.SetToolTip(originalVolume, "Âm lượng video / ứng dụng gốc");
        tips.SetToolTip(aiVolume, "Âm lượng giọng dịch AI");

        ResumeLayout(true);
    }

    private static bool IsSystemDarkTheme()
'@

$buildPattern = '(?s)    private void BuildUi\(\)\s*\{.*?\r?\n    \}\r?\n\r?\n    private static bool IsSystemDarkTheme\(\)'
$c2 = [regex]::Replace($c, $buildPattern, $easyUi.TrimEnd(), 1)
if ($c2 -eq $c) { throw 'v5.3 Easy UI BuildUi replacement failed' }
$c = $c2

# Avoid Mica backdrop glitches; keep only native dark title bar + rounded outer window.
$chrome = @'
    private void ApplyWindowChrome()
    {
        try
        {
            int dark = IsSystemDarkTheme() ? 1 : 0;
            int corner = 2; // DWMWCP_ROUND
            DwmSetWindowAttribute(Handle, 20, ref dark, sizeof(int));
            DwmSetWindowAttribute(Handle, 33, ref corner, sizeof(int));
        }
        catch { }
    }
'@
$chromePattern = '(?s)    private void ApplyWindowChrome\(\)\s*\{.*?\r?\n    \}'
$c2 = [regex]::Replace($c, $chromePattern, $chrome.TrimEnd(), 1)
if ($c2 -eq $c) { throw 'v5.3 ApplyWindowChrome replacement failed' }
$c = $c2

Set-Content $p $c -Encoding UTF8

$c = Get-Content $p -Raw
if ($c -notmatch 'ALAD Windows v5\.3 Easy UI') { throw 'v5.3 label missing' }
if ($c -notmatch 'new TabPage\("Điều khiển"\)') { throw 'Main tab missing' }
if ($c -notmatch 'new TabPage\("Nâng cao"\)') { throw 'Advanced tab missing' }
if ($c -notmatch 'UseSystemPasswordChar = true') { throw 'API key masking missing' }
if ($c -match 'DwmSetWindowAttribute\(Handle, 38') { throw 'Mica backdrop should be removed' }
if ($c -notmatch 'BuildProcessTree\(preferred\.Pid\)') { throw 'v5.2 process-tree optimization lost' }
if ($c -notmatch 'BoundedChannelOptions\(12\)') { throw 'v5.2 low-backlog optimization lost' }
Write-Host 'ALAD Windows v5.3 Easy UI patch verified.'
