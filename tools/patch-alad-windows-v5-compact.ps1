$ErrorActionPreference = 'Stop'

# Keep the proven v4.4 Gemini/WASAPI backend, then replace only the presentation layer.
& "$PSScriptRoot/patch-alad-windows-v4_4.ps1"

$p = 'alad-windows-v2/Program.cs'
$c = Get-Content $p -Raw
$nl = [Environment]::NewLine

$c = $c.Replace('ALAD Windows v4.4 — Live Dubbing', 'ALAD Windows v5 Compact Fluent')
$c = $c.Replace('ALAD Windows v4.4', 'ALAD Windows v5 Compact Fluent')

# Compact window defaults.
$c = $c.Replace('        Width = 980;', '        Width = 920;')
$c = $c.Replace('        Height = 820;', '        Height = 650;')
$c = $c.Replace('        MinimumSize = new Size(760, 650);', '        MinimumSize = new Size(820, 560);')
$c = $c.Replace('        Font = new Font("Segoe UI", 10f);', '        Font = new Font("Segoe UI Variable Text", 9.5f);')
$c = $c.Replace('        BackColor = Color.FromArgb(245, 246, 248);', '        BackColor = IsSystemDarkTheme() ? Color.FromArgb(31, 31, 31) : Color.FromArgb(243, 243, 243);')
$c = $c.Replace('        BuildUi();' + $nl + '        BindEvents();', '        BuildUi();' + $nl + '        ApplyWindowChrome();' + $nl + '        BindEvents();')

# Use the executable icon in the tray instead of the generic information icon.
$c = $c.Replace('        tray.Icon = SystemIcons.Information;', '        try { tray.Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath) ?? SystemIcons.Information; } catch { tray.Icon = SystemIcons.Information; }')

$buildUi = @'
    private void BuildUi()
    {
        bool dark = IsSystemDarkTheme();
        Color page = dark ? Color.FromArgb(31, 31, 31) : Color.FromArgb(243, 243, 243);
        Color card = dark ? Color.FromArgb(45, 45, 45) : Color.FromArgb(255, 255, 255);
        Color card2 = dark ? Color.FromArgb(52, 52, 52) : Color.FromArgb(248, 249, 251);
        Color text = dark ? Color.FromArgb(244, 244, 244) : Color.FromArgb(32, 32, 32);
        Color muted = dark ? Color.FromArgb(180, 180, 180) : Color.FromArgb(96, 96, 96);
        Color line = dark ? Color.FromArgb(68, 68, 68) : Color.FromArgb(226, 226, 226);
        Color accent = Color.FromArgb(0, 103, 192);
        Color accentHover = Color.FromArgb(0, 90, 170);

        BackColor = page;
        ForeColor = text;

        static void Round(Control control, int radius)
        {
            if (control.Width <= 0 || control.Height <= 0) return;
            int d = Math.Max(2, radius * 2);
            using var path = new System.Drawing.Drawing2D.GraphicsPath();
            var r = new Rectangle(0, 0, control.Width, control.Height);
            path.AddArc(r.Left, r.Top, d, d, 180, 90);
            path.AddArc(r.Right - d, r.Top, d, d, 270, 90);
            path.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
            path.AddArc(r.Left, r.Bottom - d, d, d, 90, 90);
            path.CloseFigure();
            control.Region?.Dispose();
            control.Region = new Region(path);
        }

        void StyleButton(Button b, bool primary = false)
        {
            b.FlatStyle = FlatStyle.Flat;
            b.FlatAppearance.BorderSize = primary ? 0 : 1;
            b.FlatAppearance.BorderColor = line;
            b.FlatAppearance.MouseOverBackColor = primary ? accentHover : card2;
            b.FlatAppearance.MouseDownBackColor = primary ? Color.FromArgb(0, 75, 145) : (dark ? Color.FromArgb(63,63,63) : Color.FromArgb(235,235,235));
            b.BackColor = primary ? accent : card2;
            b.ForeColor = primary ? Color.White : text;
            b.Height = 36;
            b.Padding = new Padding(10, 0, 10, 0);
            b.Cursor = Cursors.Hand;
            b.Resize += (_, _) => Round(b, 8);
            b.HandleCreated += (_, _) => Round(b, 8);
        }

        TableLayoutPanel Card(string title)
        {
            var box = new TableLayoutPanel
            {
                Dock = DockStyle.Fill,
                AutoSize = true,
                ColumnCount = 1,
                RowCount = 1,
                Padding = new Padding(15, 13, 15, 14),
                Margin = new Padding(0),
                BackColor = card
            };
            box.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
            var heading = new Label
            {
                Text = title,
                AutoSize = true,
                ForeColor = text,
                Font = new Font("Segoe UI Variable Display Semib", 11.2f, FontStyle.Bold),
                Margin = new Padding(0, 0, 0, 10)
            };
            box.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            box.Controls.Add(heading, 0, box.RowCount++);
            box.Paint += (_, e) =>
            {
                using var pen = new Pen(line);
                var rr = box.ClientRectangle;
                rr.Width -= 1; rr.Height -= 1;
                if (rr.Width > 1 && rr.Height > 1) e.Graphics.DrawRectangle(pen, rr);
            };
            box.Resize += (_, _) => Round(box, 12);
            box.HandleCreated += (_, _) => Round(box, 12);
            return box;
        }

        void AddField(TableLayoutPanel box, string caption, Control body, int bottom = 9)
        {
            var label = new Label
            {
                Text = caption,
                AutoSize = true,
                ForeColor = muted,
                Font = new Font("Segoe UI Variable Text", 8.6f),
                Margin = new Padding(0, 0, 0, 4)
            };
            body.Dock = DockStyle.Top;
            body.Margin = new Padding(0, 0, 0, bottom);
            box.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            box.Controls.Add(label, 0, box.RowCount++);
            box.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            box.Controls.Add(body, 0, box.RowCount++);
        }

        void StyleInput(Control ctl)
        {
            ctl.Font = new Font("Segoe UI Variable Text", 9.3f);
            ctl.ForeColor = text;
            ctl.BackColor = card2;
            if (ctl is TextBox tb) tb.BorderStyle = BorderStyle.FixedSingle;
            if (ctl is ComboBox cb) cb.FlatStyle = FlatStyle.Flat;
        }

        SuspendLayout();
        var root = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            AutoScroll = true,
            Padding = new Padding(16, 13, 16, 14),
            ColumnCount = 1,
            RowCount = 0,
            BackColor = page
        };
        root.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        Controls.Add(root);

        var header = new TableLayoutPanel
        {
            Dock = DockStyle.Top,
            Height = 54,
            ColumnCount = 2,
            Margin = new Padding(2, 0, 2, 10),
            BackColor = page
        };
        header.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        header.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        var titleHost = new FlowLayoutPanel { Dock = DockStyle.Fill, AutoSize = true, FlowDirection = FlowDirection.TopDown, WrapContents = false, Margin = new Padding(0) };
        titleHost.Controls.Add(new Label { Text = "ALAD", AutoSize = true, ForeColor = text, Font = new Font("Segoe UI Variable Display Semib", 18f, FontStyle.Bold), Margin = new Padding(0) });
        titleHost.Controls.Add(new Label { Text = "Lồng tiếng trực tiếp · Compact Fluent", AutoSize = true, ForeColor = muted, Font = new Font("Segoe UI Variable Text", 8.8f), Margin = new Padding(1, 1, 0, 0) });
        var liveBadge = new Label { Text = "  LIVE  ", AutoSize = true, ForeColor = accent, BackColor = dark ? Color.FromArgb(27, 50, 70) : Color.FromArgb(225, 242, 255), Font = new Font("Segoe UI Variable Text Semibold", 8.5f, FontStyle.Bold), Padding = new Padding(7, 6, 7, 6), Margin = new Padding(0, 7, 0, 0) };
        liveBadge.Resize += (_, _) => Round(liveBadge, 9);
        header.Controls.Add(titleHost, 0, 0);
        header.Controls.Add(liveBadge, 1, 0);
        AddAutoRow(root, header);

        var statusBar = new TableLayoutPanel
        {
            Dock = DockStyle.Top,
            AutoSize = true,
            ColumnCount = 3,
            Padding = new Padding(12, 9, 12, 9),
            Margin = new Padding(0, 0, 0, 10),
            BackColor = card
        };
        statusBar.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 45));
        statusBar.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 25));
        statusBar.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 30));
        status.Text = "●  Sẵn sàng";
        status.ForeColor = Color.FromArgb(24, 165, 88);
        status.Font = new Font("Segoe UI Variable Text Semibold", 9.3f, FontStyle.Bold);
        latency.ForeColor = muted; inputState.ForeColor = muted;
        latency.TextAlign = ContentAlignment.MiddleCenter;
        inputState.TextAlign = ContentAlignment.MiddleRight;
        statusBar.Controls.Add(status, 0, 0);
        statusBar.Controls.Add(latency, 1, 0);
        statusBar.Controls.Add(inputState, 2, 0);
        statusBar.Resize += (_, _) => Round(statusBar, 10);
        statusBar.HandleCreated += (_, _) => Round(statusBar, 10);
        AddAutoRow(root, statusBar);

        var main = new TableLayoutPanel
        {
            Dock = DockStyle.Top,
            AutoSize = true,
            ColumnCount = 2,
            RowCount = 1,
            Margin = new Padding(0, 0, 0, 10)
        };
        main.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));
        main.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));

        var connectCard = Card("Kết nối");
        connectCard.Margin = new Padding(0, 0, 6, 0);
        var keyHost = new TableLayoutPanel { Dock = DockStyle.Top, AutoSize = true, ColumnCount = 1, RowCount = 2, Margin = new Padding(0) };
        keyHost.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        StyleInput(apiKey); apiKey.Dock = DockStyle.Top;
        keyHost.Controls.Add(apiKey, 0, 0);
        var keyActions = new FlowLayoutPanel { Dock = DockStyle.Top, AutoSize = true, WrapContents = true, Margin = new Padding(0, 6, 0, 0) };
        var getApiKey = new Button { Text = "Lấy API key", AutoSize = true, Margin = new Padding(0, 0, 6, 0) };
        var pasteApiKey = new Button { Text = "Dán key", AutoSize = true, Margin = new Padding(0, 0, 8, 0) };
        StyleButton(getApiKey); StyleButton(pasteApiKey);
        rememberKey.Text = "Nhớ key"; rememberKey.ForeColor = text; rememberKey.Margin = new Padding(2, 7, 0, 0);
        getApiKey.Click += (_, _) =>
        {
            try
            {
                Process.Start(new ProcessStartInfo { FileName = "https://aistudio.google.com/app/apikey", UseShellExecute = true });
                status.Text = "●  Đã mở Google AI Studio";
            }
            catch (Exception ex) { MessageBox.Show(ex.Message, "ALAD", MessageBoxButtons.OK, MessageBoxIcon.Error); }
        };
        pasteApiKey.Click += (_, _) =>
        {
            try
            {
                if (!Clipboard.ContainsText()) { MessageBox.Show("Clipboard chưa có API key.", "ALAD", MessageBoxButtons.OK, MessageBoxIcon.Information); return; }
                var value = Clipboard.GetText().Trim();
                if (value.Length == 0) return;
                apiKey.Text = value;
                if (rememberKey.Checked) SecureKeyStore.Save(value);
                status.Text = "●  Đã dán API key";
            }
            catch (Exception ex) { MessageBox.Show(ex.Message, "ALAD", MessageBoxButtons.OK, MessageBoxIcon.Error); }
        };
        keyActions.Controls.Add(getApiKey); keyActions.Controls.Add(pasteApiKey); keyActions.Controls.Add(rememberKey);
        keyHost.Controls.Add(keyActions, 0, 1);
        AddField(connectCard, "Gemini API key", keyHost);

        liveMode.Items.AddRange(new object[] { "Dịch nhanh · Gemini 3.5", "Giọng tùy chọn · Gemini 3.8" });
        liveMode.SelectedIndex = 0; StyleInput(liveMode);
        AddField(connectCard, "Engine", liveMode, 0);

        var audioCard = Card("Âm thanh");
        audioCard.Margin = new Padding(6, 0, 0, 0);
        var srcGrid = new TableLayoutPanel { Dock = DockStyle.Top, AutoSize = true, ColumnCount = 2, RowCount = 1, Margin = new Padding(0) };
        srcGrid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100)); srcGrid.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        StyleInput(source); source.Dock = DockStyle.Fill;
        refresh.Text = "↻"; refresh.Width = 40; refresh.Margin = new Padding(6, 0, 0, 0); StyleButton(refresh);
        srcGrid.Controls.Add(source, 0, 0); srcGrid.Controls.Add(refresh, 1, 0);
        AddField(audioCard, "Nguồn", srcGrid);

        mixMode.Items.AddRange(new object[] { "Auto Ducking", "Voice-over", "Song song", "Lồng hoàn toàn" });
        mixMode.SelectedIndex = 0; StyleInput(mixMode);
        AddField(audioCard, "Kiểu lồng tiếng", mixMode);

        var vols = new TableLayoutPanel { Dock = DockStyle.Top, AutoSize = true, ColumnCount = 2, RowCount = 1, Margin = new Padding(0) };
        vols.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50)); vols.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));
        var origHost = new TableLayoutPanel { Dock = DockStyle.Fill, AutoSize = true, ColumnCount = 1, Padding = new Padding(0,0,6,0) };
        var origCap = new Label { Text = "Âm gốc  100%", AutoSize = true, ForeColor = muted, Font = new Font("Segoe UI Variable Text", 8.5f), Margin = new Padding(0) };
        originalVolumeLabel.Visible = false; originalVolume.TickStyle = TickStyle.None; originalVolume.Height = 30; originalVolume.Dock = DockStyle.Top;
        originalVolume.ValueChanged += (_, _) => origCap.Text = $"Âm gốc  {originalVolume.Value}%";
        origHost.Controls.Add(origCap); origHost.Controls.Add(originalVolume);
        var aiHost = new TableLayoutPanel { Dock = DockStyle.Fill, AutoSize = true, ColumnCount = 1, Padding = new Padding(6,0,0,0) };
        var aiCap = new Label { Text = "Âm AI  100%", AutoSize = true, ForeColor = muted, Font = new Font("Segoe UI Variable Text", 8.5f), Margin = new Padding(0) };
        aiVolumeLabel.Visible = false; aiVolume.TickStyle = TickStyle.None; aiVolume.Height = 30; aiVolume.Dock = DockStyle.Top;
        aiVolume.ValueChanged += (_, _) => aiCap.Text = $"Âm AI  {aiVolume.Value}%";
        aiHost.Controls.Add(aiCap); aiHost.Controls.Add(aiVolume);
        vols.Controls.Add(origHost, 0, 0); vols.Controls.Add(aiHost, 1, 0);
        AddField(audioCard, "Âm lượng", vols, 0);

        main.Controls.Add(connectCard, 0, 0); main.Controls.Add(audioCard, 1, 0);
        AddAutoRow(root, main);

        var advancedButton = new Button { Text = "Nâng cao  ▾", AutoSize = true, Height = 34, Margin = new Padding(0,0,0,8) };
        StyleButton(advancedButton);
        AddAutoRow(root, advancedButton);

        var advanced = Card("Tùy chọn nâng cao");
        advanced.Visible = false;
        advanced.Margin = new Padding(0, 0, 0, 10);
        var advGrid = new TableLayoutPanel { Dock = DockStyle.Top, AutoSize = true, ColumnCount = 3, RowCount = 1, Margin = new Padding(0) };
        advGrid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 34)); advGrid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 33)); advGrid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 33));

        language.Items.AddRange(new object[] { "Tiếng Việt|vi", "English|en", "日本語|ja", "한국어|ko", "中文|zh" });
        language.SelectedIndex = 0; StyleInput(language);
        voice.Items.AddRange(new object[] { "Kore", "Puck", "Aoede", "Charon", "Fenrir", "Leda", "Orus", "Zephyr" });
        voice.SelectedIndex = 0; voice.Enabled = false; StyleInput(voice);
        syncMs.Width = 120; syncMs.BackColor = card2; syncMs.ForeColor = text;

        var langHost = new TableLayoutPanel { Dock = DockStyle.Fill, AutoSize = true, Padding = new Padding(0,0,6,0) };
        langHost.Controls.Add(new Label { Text = "Ngôn ngữ", AutoSize = true, ForeColor = muted, Margin = new Padding(0,0,0,4) }); langHost.Controls.Add(language);
        var voiceHost = new TableLayoutPanel { Dock = DockStyle.Fill, AutoSize = true, Padding = new Padding(6,0,6,0) };
        voiceHost.Controls.Add(new Label { Text = "Giọng AI", AutoSize = true, ForeColor = muted, Margin = new Padding(0,0,0,4) }); voiceHost.Controls.Add(voice);
        var syncHost = new TableLayoutPanel { Dock = DockStyle.Fill, AutoSize = true, Padding = new Padding(6,0,0,0) };
        syncHost.Controls.Add(new Label { Text = "Bù sync (ms)", AutoSize = true, ForeColor = muted, Margin = new Padding(0,0,0,4) }); syncHost.Controls.Add(syncMs);
        advGrid.Controls.Add(langHost,0,0); advGrid.Controls.Add(voiceHost,1,0); advGrid.Controls.Add(syncHost,2,0);
        AddField(advanced, "Dịch & đồng bộ", advGrid);

        var flags = new FlowLayoutPanel { Dock = DockStyle.Top, AutoSize = true, WrapContents = true, Margin = new Padding(0) };
        foreach (var cb in new[] { autoSync, catchUp, lowLatency }) { cb.ForeColor = text; cb.Margin = new Padding(0, 4, 18, 2); flags.Controls.Add(cb); }
        AddField(advanced, "Tối ưu", flags);

        transcript.Height = 120; transcript.Dock = DockStyle.Top; transcript.BackColor = card2; transcript.ForeColor = text; transcript.BorderStyle = BorderStyle.FixedSingle;
        AddField(advanced, "Nhật ký / bản chép lời", transcript, 0);
        AddAutoRow(root, advanced);
        advancedButton.Click += (_, _) => { advanced.Visible = !advanced.Visible; advancedButton.Text = advanced.Visible ? "Nâng cao  ▴" : "Nâng cao  ▾"; };

        var footer = new TableLayoutPanel { Dock = DockStyle.Top, AutoSize = true, ColumnCount = 3, Margin = new Padding(0, 2, 0, 0), BackColor = page };
        footer.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100)); footer.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize)); footer.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        var hint = new Label { Text = "Sẵn sàng · chọn nguồn rồi bắt đầu", AutoSize = true, ForeColor = muted, Anchor = AnchorStyles.Left, Margin = new Padding(2,10,0,0) };
        stop.Text = "Dừng"; stop.AutoSize = true; stop.MinimumSize = new Size(95, 40); stop.Margin = new Padding(0,0,8,0); StyleButton(stop);
        start.Text = "▶  Bắt đầu lồng tiếng"; start.AutoSize = true; start.MinimumSize = new Size(185, 40); start.Margin = new Padding(0); StyleButton(start, true);
        footer.Controls.Add(hint,0,0); footer.Controls.Add(stop,1,0); footer.Controls.Add(start,2,0);
        AddAutoRow(root, footer);
        ResumeLayout(true);
    }

    private static bool IsSystemDarkTheme()
    {
        try
        {
            object? value = Microsoft.Win32.Registry.GetValue(@"HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize", "AppsUseLightTheme", 1);
            return value is int i && i == 0;
        }
        catch { return false; }
    }

    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attribute, ref int value, int size);

    private void ApplyWindowChrome()
    {
        try
        {
            int dark = IsSystemDarkTheme() ? 1 : 0;
            int corner = 2;   // DWMWCP_ROUND
            int backdrop = 2; // DWMSBT_MAINWINDOW (Mica on Windows 11)
            DwmSetWindowAttribute(Handle, 20, ref dark, sizeof(int));
            DwmSetWindowAttribute(Handle, 33, ref corner, sizeof(int));
            DwmSetWindowAttribute(Handle, 38, ref backdrop, sizeof(int));
        }
        catch { }
    }

    private static TableLayoutPanel TwoColumnGrid()
'@

$buildPattern = '(?s)    private void BuildUi\(\)\s*\{.*?\r?\n    \}\r?\n\r?\n    private static TableLayoutPanel TwoColumnGrid\(\)'
$c2 = [regex]::Replace($c, $buildPattern, $buildUi.TrimEnd(), 1)
if ($c2 -eq $c) { throw 'BuildUi replacement failed' }
$c = $c2

# Slightly clearer status marker while connecting/running/stopped.
$c = $c.Replace('            status.Text = "Đang kết nối Gemini...";', '            status.Text = "●  Đang kết nối Gemini...";')
$c = $c.Replace('            status.Text = "Đang lồng tiếng";', '            status.Text = "●  Đang lồng tiếng";')
$c = $c.Replace('            status.Text = "Đã dừng";', '            status.Text = "●  Đã dừng";')

Set-Content $p $c -Encoding UTF8

$c = Get-Content $p -Raw
if ($c -notmatch 'Compact Fluent') { throw 'Compact Fluent label missing' }
if ($c -notmatch 'ApplyWindowChrome') { throw 'Windows 11 chrome helper missing' }
if ($c -notmatch 'Nâng cao') { throw 'Advanced collapsible UI missing' }
if ($c -notmatch 'Tạo/Lấy API key|Lấy API key') { throw 'API key action missing' }
if ($c -notmatch 'Âm gốc') { throw 'Original volume UI missing' }
if ($c -notmatch 'TimeSpan\.FromSeconds\(8\)') { throw 'v4.4 backend timeout missing' }
if ($c -notmatch 'gemini-3\.5-live-translate-preview') { throw 'Gemini 3.5 backend missing' }
Write-Host 'ALAD Windows v5 Compact Fluent patch verified.'
