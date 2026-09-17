$ErrorActionPreference = 'Stop'

# Keep v5.2 backend/performance fixes, replace only presentation with a stable DPI-aware layout.
& "$PSScriptRoot/patch-alad-windows-v5_2-optimized.ps1"

$p = 'alad-windows-v2/Program.cs'
$c = Get-Content $p -Raw
$nl = [Environment]::NewLine

$c = $c.Replace('ALAD Windows v5.2 Compact Optimized', 'ALAD Windows v5.4 Compact Modern')

# Comfortable but still compact defaults. All actual placement below is layout-managed.
$c = $c.Replace('        Width = 920;', '        Width = 940;')
$c = $c.Replace('        Height = 650;', '        Height = 700;')
$c = $c.Replace('        MinimumSize = new Size(820, 560);', '        MinimumSize = new Size(820, 620);')

$ui = @'
    private void BuildUi()
    {
        bool dark = IsSystemDarkTheme();
        Color page = dark ? Color.FromArgb(30, 30, 30) : Color.FromArgb(246, 246, 246);
        Color surface = dark ? Color.FromArgb(42, 42, 42) : Color.White;
        Color surface2 = dark ? Color.FromArgb(50, 50, 50) : Color.FromArgb(250, 250, 250);
        Color text = dark ? Color.FromArgb(245, 245, 245) : Color.FromArgb(32, 32, 32);
        Color muted = dark ? Color.FromArgb(178, 178, 178) : Color.FromArgb(96, 96, 96);
        Color border = dark ? Color.FromArgb(75, 75, 75) : Color.FromArgb(218, 218, 218);
        Color accent = Color.FromArgb(0, 103, 192);

        BackColor = page;
        ForeColor = text;
        AutoScaleMode = AutoScaleMode.Dpi;

        void StyleButton(Button b, bool primary = false)
        {
            b.AutoSize = true;
            b.AutoSizeMode = AutoSizeMode.GrowAndShrink;
            b.MinimumSize = new Size(primary ? 138 : 86, 38);
            b.Padding = new Padding(12, 0, 12, 0);
            b.FlatStyle = FlatStyle.Flat;
            b.FlatAppearance.BorderSize = primary ? 0 : 1;
            b.FlatAppearance.BorderColor = border;
            b.BackColor = primary ? accent : surface2;
            b.ForeColor = primary ? Color.White : text;
            b.Font = new Font("Segoe UI", 9.25f, primary ? FontStyle.Bold : FontStyle.Regular);
            b.Cursor = Cursors.Hand;
            b.UseVisualStyleBackColor = false;
        }

        void StyleCombo(ComboBox cb)
        {
            cb.DropDownStyle = ComboBoxStyle.DropDownList;
            cb.Dock = DockStyle.Fill;
            cb.Margin = new Padding(0);
            cb.Font = new Font("Segoe UI", 9.25f);
            cb.BackColor = surface2;
            cb.ForeColor = text;
        }

        Label Caption(string s) => new()
        {
            Text = s,
            AutoSize = true,
            ForeColor = muted,
            Font = new Font("Segoe UI", 8.6f),
            Margin = new Padding(0, 0, 0, 4)
        };

        GroupBox Section(string title)
        {
            return new GroupBox
            {
                Text = title,
                Dock = DockStyle.Top,
                AutoSize = true,
                AutoSizeMode = AutoSizeMode.GrowAndShrink,
                Padding = new Padding(14, 12, 14, 14),
                Margin = new Padding(0, 0, 0, 10),
                ForeColor = text,
                BackColor = surface,
                Font = new Font("Segoe UI Semibold", 10.3f, FontStyle.Bold)
            };
        }

        TableLayoutPanel OneColumn()
        {
            var t = new TableLayoutPanel
            {
                Dock = DockStyle.Top,
                AutoSize = true,
                AutoSizeMode = AutoSizeMode.GrowAndShrink,
                ColumnCount = 1,
                RowCount = 0,
                Margin = new Padding(0),
                Padding = new Padding(0)
            };
            t.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
            return t;
        }

        void AddRow(TableLayoutPanel t, Control c, int bottom = 8)
        {
            c.Margin = new Padding(0, 0, 0, bottom);
            t.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            t.Controls.Add(c, 0, t.RowCount++);
        }

        void AddField(TableLayoutPanel t, string caption, Control body, int bottom = 9)
        {
            AddRow(t, Caption(caption), 3);
            body.Dock = DockStyle.Top;
            AddRow(t, body, bottom);
        }

        SuspendLayout();

        var root = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 1,
            RowCount = 3,
            Padding = new Padding(14, 12, 14, 12),
            Margin = new Padding(0),
            BackColor = page
        };
        root.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        root.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        Controls.Add(root);

        // Header: flexible columns only, no fixed-position status box.
        var header = new TableLayoutPanel
        {
            Dock = DockStyle.Top,
            AutoSize = true,
            ColumnCount = 2,
            RowCount = 1,
            Margin = new Padding(0, 0, 0, 8),
            BackColor = page
        };
        header.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        header.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));

        var titleHost = new FlowLayoutPanel
        {
            Dock = DockStyle.Fill,
            AutoSize = true,
            FlowDirection = FlowDirection.TopDown,
            WrapContents = false,
            Margin = new Padding(0)
        };
        titleHost.Controls.Add(new Label
        {
            Text = "ALAD",
            AutoSize = true,
            ForeColor = text,
            Font = new Font("Segoe UI", 16.5f, FontStyle.Bold),
            Margin = new Padding(0)
        });
        titleHost.Controls.Add(new Label
        {
            Text = "Lồng tiếng trực tiếp",
            AutoSize = true,
            ForeColor = muted,
            Font = new Font("Segoe UI", 8.8f),
            Margin = new Padding(1, 0, 0, 0)
        });

        var telemetry = new FlowLayoutPanel
        {
            AutoSize = true,
            Anchor = AnchorStyles.Right | AnchorStyles.Top,
            FlowDirection = FlowDirection.LeftToRight,
            WrapContents = true,
            Margin = new Padding(12, 5, 0, 0),
            Padding = new Padding(10, 7, 10, 7),
            BackColor = surface
        };
        status.Text = "● Sẵn sàng";
        status.ForeColor = Color.FromArgb(32, 165, 90);
        status.Font = new Font("Segoe UI Semibold", 8.8f, FontStyle.Bold);
        status.AutoSize = true;
        status.Margin = new Padding(0, 1, 12, 0);
        latency.ForeColor = muted;
        latency.Font = new Font("Segoe UI", 8.3f);
        latency.AutoSize = true;
        latency.Margin = new Padding(0, 1, 12, 0);
        inputState.ForeColor = muted;
        inputState.Font = new Font("Segoe UI", 8.3f);
        inputState.AutoSize = true;
        inputState.Margin = new Padding(0, 1, 0, 0);
        telemetry.Controls.Add(status);
        telemetry.Controls.Add(latency);
        telemetry.Controls.Add(inputState);

        header.Controls.Add(titleHost, 0, 0);
        header.Controls.Add(telemetry, 1, 0);
        root.Controls.Add(header, 0, 0);

        var tabs = new TabControl
        {
            Dock = DockStyle.Fill,
            Margin = new Padding(0),
            Padding = new Point(12, 6),
            Font = new Font("Segoe UI Semibold", 9.25f)
        };
        var controlTab = new TabPage("Điều khiển") { BackColor = page, Padding = new Padding(10) };
        var advancedTab = new TabPage("Nâng cao") { BackColor = page, Padding = new Padding(10) };
        tabs.TabPages.Add(controlTab);
        tabs.TabPages.Add(advancedTab);
        root.Controls.Add(tabs, 0, 1);

        // Main tab scrolls only if Windows scaling leaves insufficient height.
        var scroll = new Panel
        {
            Dock = DockStyle.Fill,
            AutoScroll = true,
            BackColor = page,
            Padding = new Padding(0)
        };
        controlTab.Controls.Add(scroll);

        var mainStack = new TableLayoutPanel
        {
            Dock = DockStyle.Top,
            AutoSize = true,
            AutoSizeMode = AutoSizeMode.GrowAndShrink,
            ColumnCount = 1,
            RowCount = 0,
            Margin = new Padding(0),
            Padding = new Padding(0)
        };
        mainStack.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        scroll.Controls.Add(mainStack);

        // 1) Connection.
        var connectBox = Section("1. Kết nối");
        var connect = OneColumn();
        connectBox.Controls.Add(connect);

        apiKey.Dock = DockStyle.Fill;
        apiKey.Font = new Font("Segoe UI", 9.25f);
        apiKey.BackColor = surface2;
        apiKey.ForeColor = text;
        apiKey.BorderStyle = BorderStyle.FixedSingle;
        apiKey.UseSystemPasswordChar = true;
        apiKey.Margin = new Padding(0);

        var keyActions = new FlowLayoutPanel
        {
            Dock = DockStyle.Top,
            AutoSize = true,
            FlowDirection = FlowDirection.LeftToRight,
            WrapContents = true,
            Margin = new Padding(0)
        };
        rememberKey.Text = "Nhớ API key";
        rememberKey.ForeColor = text;
        rememberKey.AutoSize = true;
        rememberKey.Margin = new Padding(0, 9, 14, 0);

        var showKey = new Button { Text = "Hiện key", Margin = new Padding(0, 0, 6, 0) };
        var pasteKey = new Button { Text = "Dán key", Margin = new Padding(0, 0, 6, 0) };
        var getKey = new Button { Text = "Lấy API key", Margin = new Padding(0) };
        StyleButton(showKey); StyleButton(pasteKey); StyleButton(getKey);

        showKey.Click += (_, _) =>
        {
            apiKey.UseSystemPasswordChar = !apiKey.UseSystemPasswordChar;
            showKey.Text = apiKey.UseSystemPasswordChar ? "Hiện key" : "Ẩn key";
        };
        pasteKey.Click += (_, _) =>
        {
            try
            {
                if (!Clipboard.ContainsText())
                {
                    MessageBox.Show("Clipboard chưa có API key.", "ALAD", MessageBoxButtons.OK, MessageBoxIcon.Information);
                    return;
                }
                string value = Clipboard.GetText().Trim();
                if (value.Length == 0) return;
                apiKey.Text = value;
                if (rememberKey.Checked) SecureKeyStore.Save(value);
                status.Text = "● Đã dán API key";
            }
            catch (Exception ex) { MessageBox.Show(ex.Message, "ALAD", MessageBoxButtons.OK, MessageBoxIcon.Error); }
        };
        getKey.Click += (_, _) =>
        {
            try { Process.Start(new ProcessStartInfo { FileName = "https://aistudio.google.com/app/apikey", UseShellExecute = true }); }
            catch (Exception ex) { MessageBox.Show(ex.Message, "ALAD", MessageBoxButtons.OK, MessageBoxIcon.Error); }
        };

        keyActions.Controls.Add(rememberKey);
        keyActions.Controls.Add(showKey);
        keyActions.Controls.Add(pasteKey);
        keyActions.Controls.Add(getKey);

        liveMode.Items.AddRange(new object[] { "Dịch nhanh · Gemini 3.5", "Giọng tùy chọn · Gemini 3.8" });
        liveMode.SelectedIndex = 0;
        StyleCombo(liveMode);

        AddField(connect, "Gemini API key", apiKey, 7);
        AddRow(connect, keyActions, 10);
        AddField(connect, "Engine", liveMode, 0);
        AddRow(mainStack, connectBox, 10);

        // 2) Audio. One setting per row prevents overlap at 125/150/200% DPI.
        var audioBox = Section("2. Âm thanh");
        var audio = OneColumn();
        audioBox.Controls.Add(audio);

        var sourceRow = new TableLayoutPanel
        {
            Dock = DockStyle.Top,
            AutoSize = true,
            ColumnCount = 2,
            RowCount = 1,
            Margin = new Padding(0)
        };
        sourceRow.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        sourceRow.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        StyleCombo(source);
        source.Margin = new Padding(0);
        refresh.Text = "Làm mới";
        refresh.Margin = new Padding(8, 0, 0, 0);
        StyleButton(refresh);
        sourceRow.Controls.Add(source, 0, 0);
        sourceRow.Controls.Add(refresh, 1, 0);

        mixMode.Items.AddRange(new object[] { "Auto Ducking", "Voice-over", "Song song", "Lồng hoàn toàn" });
        mixMode.SelectedIndex = 0;
        StyleCombo(mixMode);

        AddField(audio, "Nguồn âm thanh", sourceRow, 9);
        AddField(audio, "Kiểu lồng tiếng", mixMode, 3);
        AddRow(audio, new Label
        {
            Text = "Auto Ducking: ALAD tự giảm âm gốc khi giọng AI đang nói.",
            AutoSize = true,
            ForeColor = muted,
            Font = new Font("Segoe UI", 8.3f)
        }, 0);
        AddRow(mainStack, audioBox, 10);

        // 3) Volume. Two equal columns; labels and sliders are vertically stacked.
        var volBox = Section("3. Âm lượng");
        var vols = new TableLayoutPanel
        {
            Dock = DockStyle.Top,
            AutoSize = true,
            ColumnCount = 2,
            RowCount = 1,
            Margin = new Padding(0)
        };
        vols.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));
        vols.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));

        TableLayoutPanel VolumeColumn(string caption, TrackBar bar, Label hidden, bool right)
        {
            var col = OneColumn();
            col.Padding = right ? new Padding(8,0,0,0) : new Padding(0,0,8,0);
            var cap = new Label
            {
                Text = caption + ": 100%",
                AutoSize = true,
                ForeColor = text,
                Font = new Font("Segoe UI Semibold", 8.9f, FontStyle.Bold),
                Margin = new Padding(0,0,0,2)
            };
            hidden.Visible = false;
            bar.Dock = DockStyle.Fill;
            bar.TickStyle = TickStyle.None;
            bar.MinimumSize = new Size(260, 34);
            bar.Margin = new Padding(0);
            bar.ValueChanged += (_, _) => cap.Text = $"{caption}: {bar.Value}%";
            AddRow(col, cap, 0);
            AddRow(col, bar, 0);
            return col;
        }

        vols.Controls.Add(VolumeColumn("Âm gốc", originalVolume, originalVolumeLabel, false), 0, 0);
        vols.Controls.Add(VolumeColumn("Âm AI", aiVolume, aiVolumeLabel, true), 1, 0);
        volBox.Controls.Add(vols);
        AddRow(mainStack, volBox, 0);

        // Advanced tab.
        var advScroll = new Panel { Dock = DockStyle.Fill, AutoScroll = true, BackColor = page };
        advancedTab.Controls.Add(advScroll);
        var adv = new TableLayoutPanel
        {
            Dock = DockStyle.Top,
            AutoSize = true,
            ColumnCount = 1,
            RowCount = 0,
            Margin = new Padding(0),
            Padding = new Padding(0)
        };
        adv.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        advScroll.Controls.Add(adv);

        var langBox = Section("Dịch & giọng");
        var langGrid = new TableLayoutPanel
        {
            Dock = DockStyle.Top,
            AutoSize = true,
            ColumnCount = 2,
            RowCount = 1,
            Margin = new Padding(0)
        };
        langGrid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));
        langGrid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));

        var langCol = OneColumn(); langCol.Padding = new Padding(0,0,7,0);
        language.Items.AddRange(new object[] { "Tiếng Việt|vi", "English|en", "日本語|ja", "한국어|ko", "中文|zh" });
        language.SelectedIndex = 0; StyleCombo(language);
        AddField(langCol, "Ngôn ngữ đầu ra", language, 0);

        var voiceCol = OneColumn(); voiceCol.Padding = new Padding(7,0,0,0);
        voice.Items.AddRange(new object[] { "Kore", "Puck", "Aoede", "Charon", "Fenrir", "Leda", "Orus", "Zephyr" });
        voice.SelectedIndex = 0; voice.Enabled = false; StyleCombo(voice);
        AddField(voiceCol, "Giọng AI · Gemini 3.8", voice, 0);

        langGrid.Controls.Add(langCol, 0, 0);
        langGrid.Controls.Add(voiceCol, 1, 0);
        langBox.Controls.Add(langGrid);
        AddRow(adv, langBox, 10);

        var syncBox = Section("Đồng bộ");
        var syncLayout = OneColumn();
        syncBox.Controls.Add(syncLayout);

        var switches = new FlowLayoutPanel
        {
            Dock = DockStyle.Top,
            AutoSize = true,
            WrapContents = true,
            Margin = new Padding(0)
        };
        foreach (var cb in new[] { autoSync, catchUp, lowLatency })
        {
            cb.ForeColor = text;
            cb.AutoSize = true;
            cb.Margin = new Padding(0, 5, 18, 5);
            switches.Controls.Add(cb);
        }
        AddRow(syncLayout, switches, 6);

        var syncRow = new TableLayoutPanel
        {
            Dock = DockStyle.Top,
            AutoSize = true,
            ColumnCount = 2,
            RowCount = 1,
            Margin = new Padding(0)
        };
        syncRow.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        syncRow.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        syncMs.Width = 130;
        syncMs.Margin = new Padding(0,0,12,0);
        syncRow.Controls.Add(syncMs, 0, 0);
        syncRow.Controls.Add(new Label
        {
            Text = "Bù sync thủ công (ms) · chỉ chỉnh khi tiếng AI luôn sớm/chậm cố định.",
            AutoSize = true,
            ForeColor = muted,
            Font = new Font("Segoe UI", 8.3f),
            Anchor = AnchorStyles.Left,
            Margin = new Padding(0,5,0,0)
        }, 1, 0);
        AddField(syncLayout, "Bù thời gian", syncRow, 0);
        AddRow(adv, syncBox, 10);

        var logBox = Section("Nhật ký / bản chép lời");
        transcript.Dock = DockStyle.Top;
        transcript.Height = 220;
        transcript.BackColor = surface2;
        transcript.ForeColor = text;
        transcript.BorderStyle = BorderStyle.FixedSingle;
        logBox.Controls.Add(transcript);
        AddRow(adv, logBox, 0);

        // Sticky action footer outside tabs.
        var footer = new TableLayoutPanel
        {
            Dock = DockStyle.Top,
            AutoSize = true,
            ColumnCount = 3,
            RowCount = 1,
            Margin = new Padding(0, 9, 0, 0),
            BackColor = page
        };
        footer.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        footer.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        footer.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));

        var hint = new Label
        {
            Text = "Chọn API key và nguồn âm thanh, sau đó bấm Bắt đầu",
            AutoSize = true,
            ForeColor = muted,
            Font = new Font("Segoe UI", 8.4f),
            Anchor = AnchorStyles.Left,
            Margin = new Padding(2, 11, 12, 0)
        };

        stop.Text = "■ Dừng";
        stop.Margin = new Padding(0, 0, 8, 0);
        StyleButton(stop);
        start.Text = "▶ Bắt đầu";
        start.Margin = new Padding(0);
        StyleButton(start, true);

        footer.Controls.Add(hint, 0, 0);
        footer.Controls.Add(stop, 1, 0);
        footer.Controls.Add(start, 2, 0);
        root.Controls.Add(footer, 0, 2);

        var tips = new ToolTip { AutomaticDelay = 250, AutoPopDelay = 5000, ReshowDelay = 100 };
        tips.SetToolTip(source, "Ứng dụng hoặc toàn hệ thống mà ALAD sẽ nghe");
        tips.SetToolTip(refresh, "Cập nhật danh sách ứng dụng");
        tips.SetToolTip(mixMode, "Cách trộn âm thanh gốc với giọng dịch AI");
        tips.SetToolTip(originalVolume, "Mức âm lượng nền của nguồn gốc");
        tips.SetToolTip(aiVolume, "Âm lượng giọng AI");

        ResumeLayout(true);
    }

    private static bool IsSystemDarkTheme()
'@

$pattern = '(?s)    private void BuildUi\(\)\s*\{.*?\r?\n    \}\r?\n\r?\n    private static bool IsSystemDarkTheme\(\)'
$c2 = [regex]::Replace($c, $pattern, $ui.TrimEnd(), 1)
if ($c2 -eq $c) { throw 'v5.4 BuildUi replacement failed' }
$c = $c2

# Keep native dark title bar + native window rounding only. No Mica and no child-control Region clipping.
$chrome = @'
    private void ApplyWindowChrome()
    {
        try
        {
            int dark = IsSystemDarkTheme() ? 1 : 0;
            int corner = 2;
            DwmSetWindowAttribute(Handle, 20, ref dark, sizeof(int));
            DwmSetWindowAttribute(Handle, 33, ref corner, sizeof(int));
        }
        catch { }
    }
'@
$chromePattern = '(?s)    private void ApplyWindowChrome\(\)\s*\{.*?\r?\n    \}'
$c2 = [regex]::Replace($c, $chromePattern, $chrome.TrimEnd(), 1)
if ($c2 -eq $c) { throw 'v5.4 chrome replacement failed' }
$c = $c2

Set-Content $p $c -Encoding UTF8

$c = Get-Content $p -Raw
if ($c -notmatch 'ALAD Windows v5\.4 Compact Modern') { throw 'v5.4 label missing' }
if ($c -notmatch 'new TabPage\("Điều khiển"\)') { throw 'control tab missing' }
if ($c -notmatch 'new TabPage\("Nâng cao"\)') { throw 'advanced tab missing' }
if ($c -match 'control\.Region|Round\(') { throw 'unstable Region-based rounding remains' }
if ($c -match 'DwmSetWindowAttribute\(Handle, 38') { throw 'Mica should not be used' }
if ($c -notmatch 'BuildProcessTree\(preferred\.Pid\)') { throw 'v5.2 process-tree ducking lost' }
if ($c -notmatch 'BoundedChannelOptions\(12\)') { throw 'v5.2 low-backlog queue lost' }
Write-Host 'ALAD Windows v5.4 Compact Modern stable layout verified.'
