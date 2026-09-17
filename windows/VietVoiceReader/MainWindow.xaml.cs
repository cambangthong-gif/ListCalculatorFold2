using Microsoft.Win32;
using NAudio.Wave;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Documents;
using System.Windows.Media;
using Forms = System.Windows.Forms;
using VietVoiceReader.Models;
using VietVoiceReader.Services;

namespace VietVoiceReader;

public partial class MainWindow : Window
{
    private readonly TtsService tts = new();
    private readonly Mp3Exporter exporter;
    private readonly SettingsStore settingsStore = new();
    private readonly LibraryStore libraryStore = new();
    private BookDocument? book;
    private BookDisplaySettings currentSettings = new();
    private CancellationTokenSource? operationCts;
    private WaveOutEvent? waveOut;
    private AudioFileReader? waveReader;
    private CancellationTokenSource? playbackCts;
    private string? playbackTempWav;
    private int playbackGeneration;
    private bool userStopRequested;

    public MainWindow()
    {
        InitializeComponent();
        exporter = new Mp3Exporter(tts);
        tts.ProgressChanged += (p, s) => Dispatcher.Invoke(() =>
        {
            Progress.Value = Math.Clamp(p, 0, 1);
            StatusText.Text = s;
        });

        VoiceCombo.ItemsSource = VoiceCatalog.Voices;
        VoiceCombo.SelectedIndex = 0;

        var fonts = Fonts.SystemFontFamilies.OrderBy(f => f.Source, StringComparer.CurrentCultureIgnoreCase).ToList();
        FontCombo.ItemsSource = fonts;
        FontCombo.DisplayMemberPath = "Source";
        FontCombo.SelectedItem = fonts.FirstOrDefault(f => f.Source.Equals("Segoe UI", StringComparison.OrdinalIgnoreCase)) ?? fonts.FirstOrDefault();
        ThemeCombo.SelectedIndex = 0;
        ViewModeCombo.SelectedIndex = 0;
        UpdateVoiceStatus();
        RefreshLibrary();
        Loaded += Window_Loaded;
    }

    private async void Window_Loaded(object sender, RoutedEventArgs e)
    {
        Loaded -= Window_Loaded;

        var recent = libraryStore.GetRecent()
            .FirstOrDefault(x => File.Exists(x.SourcePath));

        if (recent != null)
            await OpenBookPathAsync(recent.SourcePath, resumeProgress: true, switchToContents: false);
    }

    private async void OpenBook_Click(object sender, RoutedEventArgs e)
    {
        var dlg = new OpenFileDialog
        {
            Filter = "EPUB (*.epub)|*.epub",
            Multiselect = false
        };

        if (dlg.ShowDialog() != true) return;
        await OpenBookPathAsync(dlg.FileName, resumeProgress: true, switchToContents: true);
    }

    private async Task OpenBookPathAsync(
        string filePath,
        bool resumeProgress,
        bool switchToContents)
    {
        if (!File.Exists(filePath))
        {
            MessageBox.Show(
                this,
                "Không tìm thấy file EPUB ở vị trí cũ:\n" + filePath,
                "VietVoice Reader",
                MessageBoxButton.OK,
                MessageBoxImage.Warning);
            return;
        }

        var previous = libraryStore.Find(filePath);

        try
        {
            operationCts?.Cancel();
            StopPlayback(true);
            Busy("Đang mở EPUB...", true);

            var loadedBook = await EpubService.LoadAsync(filePath);
            book = loadedBook;

            ChapterList.ItemsSource = book.Chapters;
            BookTitleText.Text = string.IsNullOrWhiteSpace(book.Author)
                ? book.Title
                : $"{book.Title} — {book.Author}";

            LoadBookSettings();

            int resumeIndex = 0;
            if (resumeProgress && previous != null)
                resumeIndex = Math.Clamp(previous.LastChapterIndex, 0, Math.Max(0, book.Chapters.Count - 1));

            ChapterList.SelectedIndex = resumeIndex;
            if (resumeIndex >= 0 && resumeIndex < book.Chapters.Count)
                ChapterList.ScrollIntoView(book.Chapters[resumeIndex]);

            libraryStore.UpsertBook(book, resumeIndex);
            RefreshLibrary(book.SourcePath);

            if (switchToContents)
                LeftTabs.SelectedIndex = 1;

            StatusText.Text = previous != null && resumeProgress
                ? $"Đọc tiếp: {book.Title} • chương {resumeIndex + 1}/{book.Chapters.Count}"
                : $"Đã mở {book.Title} • {book.Chapters.Count} chương";
        }
        catch (Exception ex)
        {
            MessageBox.Show(
                this,
                ex.Message,
                "Không mở được EPUB",
                MessageBoxButton.OK,
                MessageBoxImage.Error);
            StatusText.Text = "Mở EPUB thất bại";
        }
        finally
        {
            Busy(null, false);
        }
    }

    private void ChapterList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (ChapterList.SelectedItem is not BookChapter chapter)
            return;

        ShowChapter(chapter);

        if (book != null && ChapterList.SelectedIndex >= 0)
        {
            libraryStore.UpdateProgress(book, ChapterList.SelectedIndex);
            RefreshLibrary(book.SourcePath);
        }
    }

    private void RefreshLibrary(string? selectPath = null)
    {
        if (LibraryList == null) return;

        var recent = libraryStore.GetRecent();
        LibraryList.ItemsSource = recent;

        if (!string.IsNullOrWhiteSpace(selectPath))
        {
            var selected = recent.FirstOrDefault(
                x => string.Equals(
                    x.SourcePath,
                    selectPath,
                    StringComparison.OrdinalIgnoreCase));
            if (selected != null)
                LibraryList.SelectedItem = selected;
        }
    }

    private async void ContinueBook_Click(object sender, RoutedEventArgs e)
    {
        if (LibraryList.SelectedItem is not BookLibraryItem item)
        {
            MessageBox.Show(this, "Hãy chọn một sách trong thư viện.");
            return;
        }

        await OpenBookPathAsync(
            item.SourcePath,
            resumeProgress: true,
            switchToContents: true);
    }

    private async void LibraryList_MouseDoubleClick(object sender, System.Windows.Input.MouseButtonEventArgs e)
    {
        if (LibraryList.SelectedItem is not BookLibraryItem item)
            return;

        await OpenBookPathAsync(
            item.SourcePath,
            resumeProgress: true,
            switchToContents: true);
    }

    private void RemoveFromLibrary_Click(object sender, RoutedEventArgs e)
    {
        if (LibraryList.SelectedItem is not BookLibraryItem item)
        {
            MessageBox.Show(this, "Hãy chọn một sách trong thư viện.");
            return;
        }

        var result = MessageBox.Show(
            this,
            $"Bỏ “{item.Title}” khỏi thư viện?\n\nFile EPUB gốc sẽ không bị xóa.",
            "VietVoice Reader",
            MessageBoxButton.YesNo,
            MessageBoxImage.Question);

        if (result != MessageBoxResult.Yes)
            return;

        libraryStore.Remove(item.SourcePath);
        RefreshLibrary();

        StatusText.Text = "Đã bỏ sách khỏi thư viện";
    }

    private void ShowChapter(BookChapter chapter)
    {
        var doc = new FlowDocument
        {
            PagePadding = new Thickness(28, 24, 28, 40),
            FontFamily = new FontFamily(currentSettings.FontFamily),
            FontSize = currentSettings.FontSize,
            LineHeight = currentSettings.LineHeight,
            TextAlignment = TextAlignment.Justify
        };
        var blocks = chapter.Text.Split("\n\n", StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
        foreach (var text in blocks)
        {
            doc.Blocks.Add(new Paragraph(new Run(text))
            {
                Margin = new Thickness(0, 0, 0, currentSettings.FontSize * 0.65),
                TextAlignment = TextAlignment.Justify
            });
        }
        Reader.Document = doc;
        ApplyTheme();
    }

    private async void DownloadVoice_Click(object sender, RoutedEventArgs e)
    {
        if (VoiceCombo.SelectedItem is not VoiceDefinition voice) return;
        if (tts.IsInstalled(voice))
        {
            MessageBox.Show(this, "Giọng này đã được tải.", "VietVoice Reader");
            return;
        }
        operationCts?.Cancel();
        operationCts = new CancellationTokenSource();
        try
        {
            Busy("Đang tải giọng...", true);
            await tts.InstallAsync(voice, operationCts.Token);
            UpdateVoiceStatus();
        }
        catch (OperationCanceledException) { StatusText.Text = "Đã hủy tải"; }
        catch (Exception ex)
        {
            MessageBox.Show(this, ex.Message, "Lỗi tải giọng", MessageBoxButton.OK, MessageBoxImage.Error);
            StatusText.Text = "Tải giọng thất bại";
        }
        finally { Busy(null, false); }
    }

    private void RemoveVoice_Click(object sender, RoutedEventArgs e)
    {
        if (VoiceCombo.SelectedItem is not VoiceDefinition voice || !tts.IsInstalled(voice)) return;
        if (MessageBox.Show(this, $"Xóa model {voice.DisplayName}?", "Xóa giọng", MessageBoxButton.YesNo, MessageBoxImage.Question) != MessageBoxResult.Yes) return;
        StopPlayback(true);
        tts.Remove(voice);
        UpdateVoiceStatus();
        StatusText.Text = "Đã xóa model";
    }

    private void VoiceCombo_SelectionChanged(object sender, SelectionChangedEventArgs e) => UpdateVoiceStatus();

    private void UpdateVoiceStatus()
    {
        if (VoiceCombo.SelectedItem is not VoiceDefinition voice) return;
        bool installed = tts.IsInstalled(voice);
        VoiceSourceText.Text = $"Nguồn: {voice.Source}\nTrạng thái: {(installed ? "Đã tải • dùng offline" : "Chưa tải")}";
        DownloadVoiceButton.Content = installed ? "Đã tải" : "Tải giọng";
        DownloadVoiceButton.IsEnabled = !installed;
        RemoveVoiceButton.IsEnabled = installed;
    }

    private async void Play_Click(object sender, RoutedEventArgs e)
    {
        if (ChapterList.SelectedIndex < 0)
        {
            MessageBox.Show(this, "Hãy chọn một chương để bắt đầu đọc.");
            return;
        }

        await StartChapterPlaybackAsync(ChapterList.SelectedIndex);
    }

    private async void PrevChapter_Click(object sender, RoutedEventArgs e)
    {
        if (book == null || book.Chapters.Count == 0) return;
        int target = Math.Max(0, ChapterList.SelectedIndex - 1);
        await StartChapterPlaybackAsync(target);
    }

    private async void NextChapter_Click(object sender, RoutedEventArgs e)
    {
        if (book == null || book.Chapters.Count == 0) return;
        int current = Math.Max(0, ChapterList.SelectedIndex);
        int target = Math.Min(book.Chapters.Count - 1, current + 1);
        await StartChapterPlaybackAsync(target);
    }

    private async Task StartChapterPlaybackAsync(int chapterIndex)
    {
        if (book == null || chapterIndex < 0 || chapterIndex >= book.Chapters.Count)
            return;

        if (VoiceCombo.SelectedItem is not VoiceDefinition voice)
        {
            MessageBox.Show(this, "Hãy chọn giọng đọc.");
            return;
        }

        if (!tts.IsInstalled(voice))
        {
            MessageBox.Show(this, "Giọng này chưa tải. Bấm “Tải giọng” trước.");
            return;
        }

        StopPlayback(false);
        userStopRequested = false;
        playbackCts = new CancellationTokenSource();
        int generation = playbackGeneration;
        var token = playbackCts.Token;

        ChapterList.SelectedIndex = chapterIndex;
        ChapterList.ScrollIntoView(book.Chapters[chapterIndex]);
        var chapter = book.Chapters[chapterIndex];

        string wav = Path.Combine(Path.GetTempPath(), $"vietvoice-preview-{Guid.NewGuid():N}.wav");

        try
        {
            Busy($"Đang chuẩn bị {chapter.Title}...", true);
            await tts.SynthesizeToWaveAsync(
                voice,
                chapter.Text,
                (float)SpeedSlider.Value,
                wav,
                token);

            if (token.IsCancellationRequested || generation != playbackGeneration)
            {
                TryDelete(wav);
                return;
            }

            var localReader = new AudioFileReader(wav);
            var localOutput = new WaveOutEvent();
            localOutput.Init(localReader);

            waveReader = localReader;
            waveOut = localOutput;
            playbackTempWav = wav;

            localOutput.PlaybackStopped += (_, args) =>
            {
                Dispatcher.InvokeAsync(async () =>
                {
                    if (generation != playbackGeneration)
                    {
                        try { localOutput.Dispose(); } catch { }
                        try { localReader.Dispose(); } catch { }
                        TryDelete(wav);
                        return;
                    }

                    try { localOutput.Dispose(); } catch { }
                    try { localReader.Dispose(); } catch { }

                    if (ReferenceEquals(waveOut, localOutput)) waveOut = null;
                    if (ReferenceEquals(waveReader, localReader)) waveReader = null;
                    if (string.Equals(playbackTempWav, wav, StringComparison.OrdinalIgnoreCase))
                        playbackTempWav = null;

                    TryDelete(wav);

                    if (args.Exception != null)
                    {
                        StatusText.Text = "Lỗi phát âm thanh: " + args.Exception.Message;
                        return;
                    }

                    if (userStopRequested)
                    {
                        StatusText.Text = "Đã dừng";
                        return;
                    }

                    bool autoNext = AutoNextCheckBox.IsChecked == true;
                    int next = chapterIndex + 1;

                    if (autoNext && book != null && next < book.Chapters.Count)
                    {
                        StatusText.Text = $"Hết {chapter.Title} — đang chuyển sang chương kế...";
                        await StartChapterPlaybackAsync(next);
                    }
                    else if (book != null && next >= book.Chapters.Count)
                    {
                        StatusText.Text = "Đã đọc hết sách";
                    }
                    else
                    {
                        StatusText.Text = "Đã đọc xong " + chapter.Title;
                    }
                });
            };

            localOutput.Play();
            StatusText.Text = $"Đang đọc {chapterIndex + 1}/{book.Chapters.Count}: {chapter.Title}";
        }
        catch (OperationCanceledException)
        {
            TryDelete(wav);
        }
        catch (Exception ex)
        {
            TryDelete(wav);
            if (generation == playbackGeneration)
                MessageBox.Show(this, ex.Message, "Lỗi TTS", MessageBoxButton.OK, MessageBoxImage.Error);
        }
        finally
        {
            if (generation == playbackGeneration)
                Busy(null, false);
        }
    }

    private void Stop_Click(object sender, RoutedEventArgs e)
    {
        StopPlayback(true);
        StatusText.Text = "Đã dừng";
    }

    private void StopPlayback(bool userInitiated)
    {
        userStopRequested = userInitiated;
        playbackGeneration++;

        try { playbackCts?.Cancel(); } catch { }
        playbackCts?.Dispose();
        playbackCts = null;

        var output = waveOut;
        var reader = waveReader;
        var temp = playbackTempWav;

        waveOut = null;
        waveReader = null;
        playbackTempWav = null;

        try { output?.Stop(); } catch { }
        try { output?.Dispose(); } catch { }
        try { reader?.Dispose(); } catch { }

        if (!string.IsNullOrWhiteSpace(temp))
            TryDelete(temp);
    }

    private static void TryDelete(string path)
    {
        try
        {
            if (File.Exists(path)) File.Delete(path);
        }
        catch
        {
        }
    }

    private async void Export_Click(object sender, RoutedEventArgs e)
    {
        if (book == null || VoiceCombo.SelectedItem is not VoiceDefinition voice)
        {
            MessageBox.Show(this, "Hãy mở EPUB và chọn giọng trước.");
            return;
        }
        if (!tts.IsInstalled(voice))
        {
            MessageBox.Show(this, "Hãy tải giọng trước khi xuất MP3.");
            return;
        }

        var scope = MessageBox.Show(this, "Xuất toàn bộ sách?\n\nYes = toàn bộ sách\nNo = chương đang chọn\nCancel = hủy", "Xuất MP3", MessageBoxButton.YesNoCancel, MessageBoxImage.Question);
        if (scope == MessageBoxResult.Cancel) return;
        bool all = scope == MessageBoxResult.Yes;
        bool merge = false;
        if (all)
        {
            var mode = MessageBox.Show(this, "Gộp toàn bộ sách thành 1 file MP3?\n\nYes = 1 file\nNo = mỗi chương 1 file", "Kiểu xuất", MessageBoxButton.YesNoCancel, MessageBoxImage.Question);
            if (mode == MessageBoxResult.Cancel) return;
            merge = mode == MessageBoxResult.Yes;
        }

        using var folderDlg = new Forms.FolderBrowserDialog { Description = "Chọn thư mục lưu MP3", UseDescriptionForTitle = true };
        if (folderDlg.ShowDialog() != Forms.DialogResult.OK) return;

        operationCts?.Cancel();
        operationCts = new CancellationTokenSource();
        var report = new Progress<(double, string)>(x => { Progress.Value = x.Item1; StatusText.Text = x.Item2; });
        try
        {
            Busy("Đang xuất MP3...", true);
            if (all && merge)
                await exporter.ExportWholeBookAsync(book, voice, (float)SpeedSlider.Value, folderDlg.SelectedPath, report, operationCts.Token);
            else
            {
                IEnumerable<BookChapter> chapters = all ? book.Chapters : new[] { (BookChapter)ChapterList.SelectedItem! };
                await exporter.ExportChaptersAsync(book, chapters, voice, (float)SpeedSlider.Value, folderDlg.SelectedPath, report, operationCts.Token);
            }
            MessageBox.Show(this, "Xuất MP3 hoàn tất.", "VietVoice Reader", MessageBoxButton.OK, MessageBoxImage.Information);
        }
        catch (OperationCanceledException) { StatusText.Text = "Đã hủy xuất MP3"; }
        catch (Exception ex)
        {
            MessageBox.Show(this, ex.Message, "Lỗi xuất MP3", MessageBoxButton.OK, MessageBoxImage.Error);
        }
        finally { Busy(null, false); }
    }

    private bool TryGetReadContext(out BookChapter chapter, out VoiceDefinition voice)
    {
        chapter = null!;
        voice = null!;
        if (ChapterList.SelectedItem is not BookChapter c || VoiceCombo.SelectedItem is not VoiceDefinition v)
        {
            MessageBox.Show(this, "Hãy mở sách và chọn một chương/giọng đọc.");
            return false;
        }
        if (!tts.IsInstalled(v))
        {
            MessageBox.Show(this, "Giọng này chưa tải. Bấm “Tải giọng” trước.");
            return false;
        }
        chapter = c;
        voice = v;
        return true;
    }

    private void SpeedSlider_ValueChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (SpeedText != null) SpeedText.Text = $"{e.NewValue:0.00}x";
    }

    private void FontCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (FontCombo.SelectedItem is FontFamily family)
        {
            currentSettings.FontFamily = family.Source;
            RefreshCurrentChapterAndSave();
        }
    }

    private void FontSizeSlider_ValueChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (FontSizeText != null) FontSizeText.Text = $"{e.NewValue:0}";
        currentSettings.FontSize = e.NewValue;
        RefreshCurrentChapterAndSave();
    }

    private void LineHeightSlider_ValueChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (LineHeightText != null) LineHeightText.Text = $"{e.NewValue:0}";
        currentSettings.LineHeight = e.NewValue;
        RefreshCurrentChapterAndSave();
    }

    private void ThemeCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (ThemeCombo.SelectedItem is ComboBoxItem item)
            currentSettings.Theme = item.Content?.ToString() ?? "Sáng";
        ApplyTheme();
        SaveCurrentSettings();
    }

    private void ViewModeCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (ViewModeCombo.SelectedItem is not ComboBoxItem item) return;
        currentSettings.ViewMode = item.Content?.ToString() ?? "Cuộn";
        Reader.ViewingMode = currentSettings.ViewMode switch
        {
            "1 trang" => FlowDocumentReaderViewingMode.Page,
            "2 trang" => FlowDocumentReaderViewingMode.TwoPage,
            _ => FlowDocumentReaderViewingMode.Scroll
        };
        SaveCurrentSettings();
    }

    private void ImportFont_Click(object sender, RoutedEventArgs e)
    {
        var dlg = new OpenFileDialog { Filter = "Font (*.ttf;*.otf)|*.ttf;*.otf" };
        if (dlg.ShowDialog() != true) return;
        try
        {
            var families = Fonts.GetFontFamilies(new Uri(dlg.FileName, UriKind.Absolute)).ToList();
            if (families.Count == 0) throw new InvalidDataException("Không đọc được font này.");
            var existing = (FontCombo.ItemsSource as IEnumerable<FontFamily>)?.ToList() ?? new List<FontFamily>();
            existing.AddRange(families.Where(x => existing.All(e => e.Source != x.Source)));
            FontCombo.ItemsSource = existing.OrderBy(x => x.Source).ToList();
            FontCombo.SelectedItem = families[0];
            StatusText.Text = "Đã nạp font: " + families[0].Source;
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, ex.Message, "Không nạp được font", MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    private void LoadBookSettings()
    {
        if (book == null) return;
        currentSettings = settingsStore.Get(book.SourcePath);
        FontSizeSlider.Value = currentSettings.FontSize;
        LineHeightSlider.Value = currentSettings.LineHeight;
        var fonts = (FontCombo.ItemsSource as IEnumerable<FontFamily>)?.ToList() ?? new();
        var selected = fonts.FirstOrDefault(f => f.Source.Equals(currentSettings.FontFamily, StringComparison.OrdinalIgnoreCase));
        if (selected != null) FontCombo.SelectedItem = selected;
        SelectComboByText(ThemeCombo, currentSettings.Theme);
        SelectComboByText(ViewModeCombo, currentSettings.ViewMode);
        if (ChapterList.SelectedItem is BookChapter chapter) ShowChapter(chapter);
    }

    private static void SelectComboByText(ComboBox combo, string text)
    {
        foreach (var item in combo.Items.OfType<ComboBoxItem>())
            if (string.Equals(item.Content?.ToString(), text, StringComparison.OrdinalIgnoreCase))
            {
                combo.SelectedItem = item;
                return;
            }
    }

    private void RefreshCurrentChapterAndSave()
    {
        if (ChapterList?.SelectedItem is BookChapter chapter) ShowChapter(chapter);
        SaveCurrentSettings();
    }

    private void SaveCurrentSettings()
    {
        if (book != null) settingsStore.Save(book.SourcePath, currentSettings);
    }

    private void ApplyTheme()
    {
        if (Reader == null) return;
        var (bg, fg) = currentSettings.Theme switch
        {
            "Tối" => (Color.FromRgb(30, 30, 32), Color.FromRgb(232, 232, 235)),
            "Sepia" => (Color.FromRgb(244, 236, 216), Color.FromRgb(69, 55, 38)),
            _ => (Colors.White, Color.FromRgb(32, 32, 34))
        };
        Reader.Background = new SolidColorBrush(bg);
        if (Reader.Document != null)
        {
            Reader.Document.Foreground = new SolidColorBrush(fg);
            Reader.Document.Background = new SolidColorBrush(bg);
        }
    }

    private void Busy(string? message, bool busy)
    {
        OpenBookButton.IsEnabled = !busy;
        PlayButton.IsEnabled = !busy;
        ExportButton.IsEnabled = !busy;
        DownloadVoiceButton.IsEnabled = !busy && VoiceCombo.SelectedItem is VoiceDefinition v && !tts.IsInstalled(v);
        if (!string.IsNullOrWhiteSpace(message)) StatusText.Text = message;
        if (!busy) Progress.Value = 0;
    }

    protected override void OnClosed(EventArgs e)
    {
        operationCts?.Cancel();
        StopPlayback(true);
        tts.Dispose();
        base.OnClosed(e);
    }
}
