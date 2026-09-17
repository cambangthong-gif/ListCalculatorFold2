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
    private BookDocument? book;
    private BookDisplaySettings currentSettings = new();
    private CancellationTokenSource? operationCts;
    private WaveOutEvent? waveOut;
    private AudioFileReader? waveReader;

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
    }

    private async void OpenBook_Click(object sender, RoutedEventArgs e)
    {
        var dlg = new OpenFileDialog { Filter = "EPUB (*.epub)|*.epub", Multiselect = false };
        if (dlg.ShowDialog() != true) return;
        try
        {
            Busy("Đang mở EPUB...", true);
            book = await EpubService.LoadAsync(dlg.FileName);
            ChapterList.ItemsSource = book.Chapters;
            ChapterList.SelectedIndex = 0;
            BookTitleText.Text = string.IsNullOrWhiteSpace(book.Author) ? book.Title : $"{book.Title} — {book.Author}";
            LoadBookSettings();
            StatusText.Text = $"Đã mở {book.Title} • {book.Chapters.Count} chương";
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, ex.Message, "Không mở được EPUB", MessageBoxButton.OK, MessageBoxImage.Error);
            StatusText.Text = "Mở EPUB thất bại";
        }
        finally { Busy(null, false); }
    }

    private void ChapterList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (ChapterList.SelectedItem is BookChapter chapter)
            ShowChapter(chapter);
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
        StopAudio();
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
        if (!TryGetReadContext(out var chapter, out var voice)) return;
        operationCts?.Cancel();
        operationCts = new CancellationTokenSource();
        var wav = Path.Combine(Path.GetTempPath(), $"vietvoice-preview-{Guid.NewGuid():N}.wav");
        try
        {
            StopAudio();
            Busy("Đang tổng hợp giọng đọc...", true);
            await tts.SynthesizeToWaveAsync(voice, chapter.Text, (float)SpeedSlider.Value, wav, operationCts.Token);
            waveReader = new AudioFileReader(wav);
            waveOut = new WaveOutEvent();
            waveOut.Init(waveReader);
            waveOut.PlaybackStopped += (_, _) => Dispatcher.Invoke(() =>
            {
                var path = waveReader?.FileName;
                StopAudio();
                if (!string.IsNullOrWhiteSpace(path) && File.Exists(path))
                {
                    try { File.Delete(path); } catch { }
                }
            });
            waveOut.Play();
            StatusText.Text = "Đang đọc: " + chapter.Title;
        }
        catch (OperationCanceledException) { if (File.Exists(wav)) File.Delete(wav); }
        catch (Exception ex)
        {
            if (File.Exists(wav)) File.Delete(wav);
            MessageBox.Show(this, ex.Message, "Lỗi TTS", MessageBoxButton.OK, MessageBoxImage.Error);
        }
        finally { Busy(null, false); }
    }

    private void Stop_Click(object sender, RoutedEventArgs e)
    {
        operationCts?.Cancel();
        StopAudio();
        StatusText.Text = "Đã dừng";
    }

    private void StopAudio()
    {
        try { waveOut?.Stop(); } catch { }
        waveOut?.Dispose();
        waveReader?.Dispose();
        waveOut = null;
        waveReader = null;
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
        StopAudio();
        tts.Dispose();
        base.OnClosed(e);
    }
}
