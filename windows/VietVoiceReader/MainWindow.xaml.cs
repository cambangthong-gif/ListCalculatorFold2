using Microsoft.Win32;
using NAudio.Wave;
using System.Text;
using System.Text.RegularExpressions;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Documents;
using System.Windows.Input;
using System.Windows.Media;
using Forms = System.Windows.Forms;
using VietVoiceReader.Models;
using VietVoiceReader.Services;

namespace VietVoiceReader;

public partial class MainWindow : Window
{
    private sealed class OpenBookTab
    {
        public required BookDocument Book { get; init; }
        public required FlowDocumentReader Reader { get; init; }
        public required TabItem Tab { get; init; }
        public required BookDisplaySettings Settings { get; set; }
        public int ChapterIndex { get; set; }
        public int LastSentenceIndex { get; set; }
        public double ScrollRatio { get; set; }
        public double LastSavedScrollRatio { get; set; } = -1;
        public ScrollViewer? AttachedScroll { get; set; }
        public List<Run> SentenceRuns { get; } = new();
        public Run? HighlightedRun { get; set; }
    }

    private sealed record PlaybackUnit(
        int ChapterIndex,
        int SegmentIndex,
        int SegmentCount,
        string Text);

    private readonly TtsService tts = new();
    private readonly Mp3Exporter exporter;
    private readonly SettingsStore settingsStore = new();
    private readonly LibraryStore libraryStore = new();
    private readonly BookmarkStore bookmarkStore = new();

    private readonly Dictionary<string, OpenBookTab> openBooks =
        new(StringComparer.OrdinalIgnoreCase);

    private OpenBookTab? activeTab;
    private BookDocument? book;
    private BookDisplaySettings currentSettings = new();

    private CancellationTokenSource? operationCts;
    private CancellationTokenSource? playbackCts;
    private WaveOutEvent? waveOut;
    private AudioFileReader? waveReader;
    private string? playbackTempWav;

    private int playbackSessionId;
    private int playCommandGate;
    private bool userStopRequested;
    private bool isPlaybackActive;
    private string? activePlaybackBookPath;

    private bool suppressChapterSelection;
    private bool suppressSettingsEvents;
    private bool isFocusMode;

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

        var fonts = Fonts.SystemFontFamilies
            .OrderBy(f => f.Source, StringComparer.CurrentCultureIgnoreCase)
            .ToList();

        FontCombo.ItemsSource = fonts;
        FontCombo.DisplayMemberPath = "Source";
        FontCombo.SelectedItem =
            fonts.FirstOrDefault(
                f => f.Source.Equals("Segoe UI", StringComparison.OrdinalIgnoreCase))
            ?? fonts.FirstOrDefault();

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
            await OpenBookPathAsync(
                recent.SourcePath,
                resumeProgress: true,
                switchToContents: false,
                selectTab: true);
    }

    // ---------------------------------------------------------------------
    // Open / drag-drop / tabs
    // ---------------------------------------------------------------------

    private async void OpenBook_Click(object sender, RoutedEventArgs e)
    {
        var dlg = new OpenFileDialog
        {
            Filter = "EPUB (*.epub)|*.epub",
            Multiselect = true
        };

        if (dlg.ShowDialog() != true) return;

        foreach (var file in dlg.FileNames)
        {
            await OpenBookPathAsync(
                file,
                resumeProgress: true,
                switchToContents: true,
                selectTab: true);
        }
    }

    private void Window_DragOver(object sender, DragEventArgs e)
    {
        if (e.Data.GetDataPresent(DataFormats.FileDrop)
            && e.Data.GetData(DataFormats.FileDrop) is string[] files
            && files.Any(IsEpub))
        {
            e.Effects = DragDropEffects.Copy;
        }
        else
        {
            e.Effects = DragDropEffects.None;
        }

        e.Handled = true;
    }

    private async void Window_Drop(object sender, DragEventArgs e)
    {
        if (!e.Data.GetDataPresent(DataFormats.FileDrop)
            || e.Data.GetData(DataFormats.FileDrop) is not string[] files)
            return;

        var epubs = files
            .Where(IsEpub)
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToList();

        if (epubs.Count == 0) return;

        foreach (var file in epubs)
        {
            await OpenBookPathAsync(
                file,
                resumeProgress: true,
                switchToContents: true,
                selectTab: true);
        }
    }

    private static bool IsEpub(string path) =>
        File.Exists(path)
        && string.Equals(
            Path.GetExtension(path),
            ".epub",
            StringComparison.OrdinalIgnoreCase);

    private async Task OpenBookPathAsync(
        string filePath,
        bool resumeProgress,
        bool switchToContents,
        bool selectTab)
    {
        filePath = SafeFullPath(filePath);

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

        if (openBooks.TryGetValue(filePath, out var alreadyOpen))
        {
            if (selectTab)
                BookTabs.SelectedItem = alreadyOpen.Tab;

            if (switchToContents)
                LeftTabs.SelectedIndex = 1;

            return;
        }

        var previous = libraryStore.Find(filePath);

        try
        {
            Busy("Đang mở EPUB...", true);

            var loadedBook = await EpubService.LoadAsync(filePath);
            var settings = settingsStore.Get(filePath);

            int resumeIndex = 0;
            if (resumeProgress && previous != null)
            {
                resumeIndex = Math.Clamp(
                    previous.LastChapterIndex,
                    0,
                    Math.Max(0, loadedBook.Chapters.Count - 1));
            }

            var reader = new FlowDocumentReader
            {
                ViewingMode = ToViewingMode(settings.ViewMode),
                IsPrintEnabled = false,
                IsFindEnabled = true,
                Margin = new Thickness(8),
                Background = Brushes.Transparent,
                MinZoom = 50,
                MaxZoom = 200,
                ZoomIncrement = 10,
                Zoom = Math.Clamp(settings.ZoomPercent, 50, 200)
            };

            var tabItem = new TabItem();

            var state = new OpenBookTab
            {
                Book = loadedBook,
                Reader = reader,
                Tab = tabItem,
                Settings = settings,
                ChapterIndex = resumeIndex,
                LastSentenceIndex =
                    resumeProgress && previous != null
                        ? Math.Max(0, previous.LastSentenceIndex)
                        : 0,
                ScrollRatio =
                    resumeProgress && previous != null
                        ? Math.Clamp(previous.LastScrollRatio, 0, 1)
                        : 0
            };

            reader.PreviewMouseWheel += (_, e) => Reader_PreviewMouseWheel(state, e);
            reader.PreviewKeyDown += (_, e) => Reader_PreviewKeyDown(state, e);
            reader.PreviewMouseLeftButtonUp += (_, e) => Reader_PreviewMouseLeftButtonUp(state, e);

            tabItem.Tag = state;
            tabItem.Content = reader;
            tabItem.Header = CreateTabHeader(state);

            openBooks[filePath] = state;
            BookTabs.Items.Add(tabItem);

            libraryStore.UpsertBook(
                loadedBook,
                resumeIndex,
                state.LastSentenceIndex,
                state.ScrollRatio);
            RefreshLibrary(filePath);

            if (selectTab)
                BookTabs.SelectedItem = tabItem;

            if (switchToContents)
                LeftTabs.SelectedIndex = 1;

            EmptyHint.Visibility = Visibility.Collapsed;

            StatusText.Text = previous != null && resumeProgress
                ? $"Đọc tiếp: {loadedBook.Title} • chương {resumeIndex + 1}/{loadedBook.Chapters.Count}"
                : $"Đã mở {loadedBook.Title} • {loadedBook.Chapters.Count} chương";
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

    private FrameworkElement CreateTabHeader(OpenBookTab state)
    {
        var panel = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            VerticalAlignment = VerticalAlignment.Center
        };

        var title = new TextBlock
        {
            Text = ShortTitle(state.Book.Title, 30),
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(4, 0, 5, 0)
        };

        var close = new Button
        {
            Content = "×",
            MinWidth = 24,
            Width = 24,
            Height = 24,
            Padding = new Thickness(0),
            Margin = new Thickness(2, 0, 0, 0),
            ToolTip = "Đóng tab"
        };

        close.Click += (_, e) =>
        {
            e.Handled = true;
            CloseBookTab(state);
        };

        panel.Children.Add(title);
        panel.Children.Add(close);
        return panel;
    }

    private void CloseBookTab(OpenBookTab state)
    {
        if (isPlaybackActive
            && string.Equals(
                activePlaybackBookPath,
                state.Book.SourcePath,
                StringComparison.OrdinalIgnoreCase))
        {
            StopPlayback(true);
        }

        openBooks.Remove(SafeFullPath(state.Book.SourcePath));
        BookTabs.Items.Remove(state.Tab);

        if (BookTabs.Items.Count == 0)
        {
            activeTab = null;
            book = null;
            ChapterList.ItemsSource = null;
            BookTitleText.Text = "Kéo EPUB vào đây để mở";
            EmptyHint.Visibility = Visibility.Visible;
            StatusText.Text = "Sẵn sàng";
        }
    }

    private void BookTabs_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (BookTabs.SelectedItem is not TabItem tab
            || tab.Tag is not OpenBookTab state)
            return;

        if (isPlaybackActive
            && !string.Equals(
                activePlaybackBookPath,
                state.Book.SourcePath,
                StringComparison.OrdinalIgnoreCase))
        {
            StopPlayback(true);
        }

        activeTab = state;
        book = state.Book;
        currentSettings = state.Settings;

        BookTitleText.Text = string.IsNullOrWhiteSpace(book.Author)
            ? book.Title
            : $"{book.Title} — {book.Author}";

        suppressChapterSelection = true;
        try
        {
            ChapterList.ItemsSource = book.Chapters;
            ChapterList.SelectedIndex = Math.Clamp(
                state.ChapterIndex,
                0,
                Math.Max(0, book.Chapters.Count - 1));

            if (ChapterList.SelectedItem != null)
                ChapterList.ScrollIntoView(ChapterList.SelectedItem);
        }
        finally
        {
            suppressChapterSelection = false;
        }

        LoadSettingsIntoControls(state);
        ShowCurrentChapter(state);
        libraryStore.UpdateProgress(
            book,
            state.ChapterIndex,
            state.LastSentenceIndex,
            state.ScrollRatio);
        RefreshLibrary(book.SourcePath);
        RefreshBookmarks();
        UpdateBookProgressUi(state);
    }

    // ---------------------------------------------------------------------
    // Library
    // ---------------------------------------------------------------------

    private void RefreshLibrary(string? selectPath = null)
    {
        if (LibraryList == null) return;

        var recent = libraryStore.GetRecent();
        LibraryList.ItemsSource = recent;

        if (!string.IsNullOrWhiteSpace(selectPath))
        {
            var selected = recent.FirstOrDefault(
                x => string.Equals(
                    SafeFullPath(x.SourcePath),
                    SafeFullPath(selectPath),
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
            switchToContents: true,
            selectTab: true);
    }

    private async void LibraryList_MouseDoubleClick(
        object sender,
        MouseButtonEventArgs e)
    {
        if (LibraryList.SelectedItem is not BookLibraryItem item)
            return;

        await OpenBookPathAsync(
            item.SourcePath,
            resumeProgress: true,
            switchToContents: true,
            selectTab: true);
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

    // ---------------------------------------------------------------------
    // Chapters / display
    // ---------------------------------------------------------------------

    private void ChapterList_SelectionChanged(
        object sender,
        SelectionChangedEventArgs e)
    {
        if (suppressChapterSelection || activeTab == null)
            return;

        if (ChapterList.SelectedIndex < 0
            || ChapterList.SelectedIndex >= activeTab.Book.Chapters.Count)
            return;

        activeTab.ChapterIndex = ChapterList.SelectedIndex;
        ShowCurrentChapter(activeTab);

        libraryStore.UpdateProgress(
            activeTab.Book,
            activeTab.ChapterIndex);

        RefreshLibrary(activeTab.Book.SourcePath);
    }

    private void ShowCurrentChapter(OpenBookTab state)
    {
        if (state.Book.Chapters.Count == 0)
            return;

        state.ChapterIndex = Math.Clamp(
            state.ChapterIndex,
            0,
            state.Book.Chapters.Count - 1);

        ShowChapter(
            state,
            state.Book.Chapters[state.ChapterIndex]);
    }

    private void ShowChapter(
        OpenBookTab state,
        BookChapter chapter)
    {
        var settings = state.Settings;

        var doc = new FlowDocument
        {
            PagePadding = new Thickness(42, 34, 42, 56),
            FontFamily = new FontFamily(settings.FontFamily),
            FontSize = settings.FontSize,
            LineHeight = settings.LineHeight,
            TextAlignment = TextAlignment.Justify,
            ColumnWidth = double.PositiveInfinity
        };

        state.SentenceRuns.Clear();
        state.HighlightedRun = null;

        var blocks = Regex.Split(
            chapter.Text ?? string.Empty,
            @"\r?\n\s*\r?\n")
            .Where(x => !string.IsNullOrWhiteSpace(x))
            .ToList();

        foreach (var block in blocks)
        {
            var paragraph = new Paragraph
            {
                Margin = new Thickness(
                    0,
                    0,
                    0,
                    settings.FontSize * 0.8),
                TextAlignment = TextAlignment.Justify
            };

            var sentences = SplitParagraphForFastTts(block);

            for (int i = 0; i < sentences.Count; i++)
            {
                var run = new Run(sentences[i]);
                state.SentenceRuns.Add(run);
                paragraph.Inlines.Add(run);

                if (i < sentences.Count - 1)
                    paragraph.Inlines.Add(new Run(" "));
            }

            doc.Blocks.Add(paragraph);
        }

        state.Reader.Document = doc;
        state.Reader.Zoom = Math.Clamp(settings.ZoomPercent, 50, 200);
        ApplyThemeToReader(state);

        Dispatcher.BeginInvoke(() =>
        {
            var scroll = FindScrollableViewer(state.Reader);
            if (scroll == null)
                return;

            HookReaderScroll(state, scroll);

            if (state.ScrollRatio > 0
                && scroll.ScrollableHeight > 0)
            {
                scroll.ScrollToVerticalOffset(
                    scroll.ScrollableHeight
                    * Math.Clamp(state.ScrollRatio, 0, 1));
            }
            else if (state.LastSentenceIndex > 0
                     && state.LastSentenceIndex < state.SentenceRuns.Count)
            {
                try
                {
                    state.SentenceRuns[state.LastSentenceIndex].BringIntoView();
                }
                catch
                {
                    scroll.ScrollToTop();
                }
            }
            else
            {
                scroll.ScrollToTop();
            }

            UpdateBookProgressUi(state);
        });
    }

    // ---------------------------------------------------------------------
    // Voice download
    // ---------------------------------------------------------------------

    private async void DownloadVoice_Click(object sender, RoutedEventArgs e)
    {
        if (VoiceCombo.SelectedItem is not VoiceDefinition voice)
            return;

        if (tts.IsInstalled(voice))
        {
            MessageBox.Show(
                this,
                "Giọng này đã được tải.",
                "VietVoice Reader");
            return;
        }

        operationCts?.Cancel();
        operationCts = new CancellationTokenSource();

        try
        {
            Busy("Đang tải giọng...", true);
            await tts.InstallAsync(
                voice,
                operationCts.Token);

            UpdateVoiceStatus();
        }
        catch (OperationCanceledException)
        {
            StatusText.Text = "Đã hủy tải";
        }
        catch (Exception ex)
        {
            MessageBox.Show(
                this,
                ex.Message,
                "Lỗi tải giọng",
                MessageBoxButton.OK,
                MessageBoxImage.Error);

            StatusText.Text = "Tải giọng thất bại";
        }
        finally
        {
            Busy(null, false);
        }
    }

    private void RemoveVoice_Click(object sender, RoutedEventArgs e)
    {
        if (VoiceCombo.SelectedItem is not VoiceDefinition voice
            || !tts.IsInstalled(voice))
            return;

        if (MessageBox.Show(
                this,
                $"Xóa model {voice.DisplayName}?",
                "Xóa giọng",
                MessageBoxButton.YesNo,
                MessageBoxImage.Question)
            != MessageBoxResult.Yes)
            return;

        StopPlayback(true);
        tts.Remove(voice);
        UpdateVoiceStatus();
        StatusText.Text = "Đã xóa model";
    }

    private void VoiceCombo_SelectionChanged(
        object sender,
        SelectionChangedEventArgs e) =>
        UpdateVoiceStatus();

    private void UpdateVoiceStatus()
    {
        if (VoiceCombo.SelectedItem is not VoiceDefinition voice)
            return;

        bool installed = tts.IsInstalled(voice);

        VoiceSourceText.Text =
            $"Nguồn: {voice.Source}\n"
            + $"Trạng thái: {(installed ? "Đã tải • dùng offline" : "Chưa tải")}";

        DownloadVoiceButton.Content =
            installed ? "Đã tải" : "Tải giọng";

        DownloadVoiceButton.IsEnabled =
            !installed;

        RemoveVoiceButton.IsEnabled =
            installed;
    }

    // ---------------------------------------------------------------------
    // Playback: ONE session, ONE output, small chunks, prefetch next chunk
    // ---------------------------------------------------------------------

    private async void Play_Click(object sender, RoutedEventArgs e)
    {
        // Hard guard: even if WPF raises the command twice, only one can enter.
        if (Interlocked.CompareExchange(
                ref playCommandGate,
                1,
                0) != 0)
            return;

        try
        {
            if (isPlaybackActive)
                return;

            if (activeTab == null)
            {
                MessageBox.Show(
                    this,
                    "Hãy mở sách và chọn một chương.");
                return;
            }

            await StartReadingSessionAsync(
                activeTab,
                activeTab.ChapterIndex,
                forceRestart: false);
        }
        finally
        {
            Interlocked.Exchange(
                ref playCommandGate,
                0);
        }
    }

    private async void PrevChapter_Click(
        object sender,
        RoutedEventArgs e)
    {
        if (activeTab == null)
            return;

        int target = Math.Max(
            0,
            activeTab.ChapterIndex - 1);

        await StartReadingSessionAsync(
            activeTab,
            target,
            forceRestart: true);
    }

    private async void NextChapter_Click(
        object sender,
        RoutedEventArgs e)
    {
        if (activeTab == null)
            return;

        int target = Math.Min(
            activeTab.Book.Chapters.Count - 1,
            activeTab.ChapterIndex + 1);

        await StartReadingSessionAsync(
            activeTab,
            target,
            forceRestart: true);
    }

    private async Task StartReadingSessionAsync(
        OpenBookTab state,
        int startChapterIndex,
        bool forceRestart)
    {
        if (state.Book.Chapters.Count == 0)
            return;

        if (VoiceCombo.SelectedItem is not VoiceDefinition voice)
        {
            MessageBox.Show(
                this,
                "Hãy chọn giọng đọc.");
            return;
        }

        if (!tts.IsInstalled(voice))
        {
            MessageBox.Show(
                this,
                "Giọng này chưa tải. Bấm “Tải giọng” trước.");
            return;
        }

        if (isPlaybackActive && !forceRestart)
            return;

        StopPlayback(false);

        userStopRequested = false;
        isPlaybackActive = true;
        activePlaybackBookPath = state.Book.SourcePath;

        playbackCts = new CancellationTokenSource();
        var token = playbackCts.Token;

        int session = ++playbackSessionId;

        PlayButton.IsEnabled = false;
        MiniPlayButton.IsEnabled = false;
        PrevChapterButton.IsEnabled = true;
        NextChapterButton.IsEnabled = true;

        try
        {
            bool autoNext = AutoNextCheckBox.IsChecked == true;

            var units = BuildPlaybackUnits(
                state.Book,
                startChapterIndex,
                autoNext);

            if (units.Count == 0)
                return;

            int currentUnitIndex = 0;

            if (startChapterIndex == state.ChapterIndex
                && state.LastSentenceIndex > 0)
            {
                int resumeUnit =
                    units.FindIndex(x =>
                        x.ChapterIndex == startChapterIndex
                        && x.SegmentIndex >= state.LastSentenceIndex);

                if (resumeUnit >= 0)
                    currentUnitIndex = resumeUnit;
            }

            string? currentWav =
                await SynthesizeUnitAsync(
                    units[currentUnitIndex],
                    voice,
                    session,
                    token);

            while (currentWav != null
                   && currentUnitIndex < units.Count
                   && session == playbackSessionId
                   && !token.IsCancellationRequested)
            {
                var currentUnit = units[currentUnitIndex];

                await Dispatcher.InvokeAsync(() =>
                {
                    if (BookTabs.SelectedItem != state.Tab)
                        BookTabs.SelectedItem = state.Tab;

                    SetActiveChapterFromPlayback(
                        state,
                        currentUnit.ChapterIndex);

                    HighlightPlaybackSentence(
                        state,
                        currentUnit.SegmentIndex);

                    StatusText.Text =
                        $"Đang đọc {currentUnit.ChapterIndex + 1}/{state.Book.Chapters.Count}"
                        + $" • đoạn {currentUnit.SegmentIndex + 1}/{currentUnit.SegmentCount}"
                        + $" • {state.Book.Chapters[currentUnit.ChapterIndex].Title}";
                });

                Task<string?>? prefetchTask = null;

                if (currentUnitIndex + 1 < units.Count)
                {
                    var nextUnit = units[currentUnitIndex + 1];
                    prefetchTask =
                        SynthesizeUnitAsync(
                            nextUnit,
                            voice,
                            session,
                            token);
                }

                await PlayWaveAsync(
                    currentWav,
                    session,
                    token);

                if (session != playbackSessionId
                    || token.IsCancellationRequested)
                    break;

                currentUnitIndex++;

                if (currentUnitIndex >= units.Count)
                    break;

                currentWav = prefetchTask == null
                    ? null
                    : await prefetchTask;
            }

            if (session == playbackSessionId
                && !token.IsCancellationRequested
                && !userStopRequested)
            {
                StatusText.Text =
                    autoNext
                        ? "Đã đọc hết phần đã chọn / hết sách"
                        : "Đã đọc xong chương";
            }
        }
        catch (OperationCanceledException)
        {
        }
        catch (Exception ex)
        {
            if (session == playbackSessionId)
            {
                MessageBox.Show(
                    this,
                    ex.Message,
                    "Lỗi TTS",
                    MessageBoxButton.OK,
                    MessageBoxImage.Error);
            }
        }
        finally
        {
            if (session == playbackSessionId)
            {
                isPlaybackActive = false;
                activePlaybackBookPath = null;
                PlayButton.IsEnabled = true;
                MiniPlayButton.IsEnabled = true;
            }
        }
    }

    private async Task<string?> SynthesizeUnitAsync(
        PlaybackUnit unit,
        VoiceDefinition voice,
        int session,
        CancellationToken token)
    {
        if (session != playbackSessionId
            || token.IsCancellationRequested)
            return null;

        string wav = Path.Combine(
            Path.GetTempPath(),
            $"vietvoice-segment-{session}-{unit.ChapterIndex}-{unit.SegmentIndex}-{Guid.NewGuid():N}.wav");

        try
        {
            if (unit.SegmentIndex == 0)
            {
                StatusText.Text =
                    $"Đang chuẩn bị chương {unit.ChapterIndex + 1}...";
            }

            await tts.SynthesizeToWaveAsync(
                voice,
                unit.Text,
                (float)SpeedSlider.Value,
                wav,
                token);

            if (session != playbackSessionId
                || token.IsCancellationRequested)
            {
                TryDelete(wav);
                return null;
            }

            return wav;
        }
        catch
        {
            TryDelete(wav);
            throw;
        }
    }

    private async Task PlayWaveAsync(
        string wav,
        int session,
        CancellationToken token)
    {
        if (session != playbackSessionId)
        {
            TryDelete(wav);
            return;
        }

        // Absolute singleton: dispose any stale output before creating a new one.
        DisposeCurrentOutput();

        var localReader = new AudioFileReader(wav);
        var localOutput = new WaveOutEvent();

        localOutput.Init(localReader);

        waveReader = localReader;
        waveOut = localOutput;
        playbackTempWav = wav;

        var completion =
            new TaskCompletionSource(
                TaskCreationOptions.RunContinuationsAsynchronously);

        int signaled = 0;

        localOutput.PlaybackStopped += (_, args) =>
        {
            if (Interlocked.Exchange(
                    ref signaled,
                    1) != 0)
                return;

            if (args.Exception != null)
                completion.TrySetException(args.Exception);
            else
                completion.TrySetResult();
        };

        using var registration =
            token.Register(() =>
            {
                completion.TrySetCanceled(token);
                try { localOutput.Stop(); } catch { }
            });

        try
        {
            if (session != playbackSessionId
                || token.IsCancellationRequested)
                return;

            localOutput.Play();
            await completion.Task;
        }
        finally
        {
            try { localOutput.Stop(); } catch { }
            try { localOutput.Dispose(); } catch { }
            try { localReader.Dispose(); } catch { }

            if (ReferenceEquals(waveOut, localOutput))
                waveOut = null;

            if (ReferenceEquals(waveReader, localReader))
                waveReader = null;

            if (string.Equals(
                    playbackTempWav,
                    wav,
                    StringComparison.OrdinalIgnoreCase))
            {
                playbackTempWav = null;
            }

            TryDelete(wav);
        }
    }

    private static List<PlaybackUnit> BuildPlaybackUnits(
        BookDocument sourceBook,
        int startChapterIndex,
        bool autoNext)
    {
        var result = new List<PlaybackUnit>();

        int firstChapter = Math.Clamp(
            startChapterIndex,
            0,
            Math.Max(0, sourceBook.Chapters.Count - 1));

        int lastChapter =
            autoNext
                ? sourceBook.Chapters.Count - 1
                : firstChapter;

        for (int chapterIndex = firstChapter;
             chapterIndex <= lastChapter;
             chapterIndex++)
        {
            var segments = SplitForFastTts(
                sourceBook.Chapters[chapterIndex].Text);

            for (int i = 0; i < segments.Count; i++)
            {
                result.Add(
                    new PlaybackUnit(
                        chapterIndex,
                        i,
                        segments.Count,
                        segments[i]));
            }
        }

        return result;
    }

    private static List<string> SplitForFastTts(
        string text,
        int maxChars = 360)
    {
        var result = new List<string>();

        foreach (var paragraph in Regex.Split(
                     text ?? string.Empty,
                     @"\r?\n\s*\r?\n"))
        {
            if (string.IsNullOrWhiteSpace(paragraph))
                continue;

            result.AddRange(
                SplitParagraphForFastTts(
                    paragraph,
                    maxChars));
        }

        return result;
    }

    private static List<string> SplitParagraphForFastTts(
        string text,
        int maxChars = 360)
    {
        text = Regex.Replace(
            text ?? string.Empty,
            @"\s+",
            " ")
            .Trim();

        if (text.Length == 0)
            return new List<string>();

        var sentences = Regex.Split(
            text,
            @"(?<=[\.!\?…;:])\s+");

        var result = new List<string>();

        foreach (var sentence in sentences)
        {
            var s = sentence.Trim();

            if (s.Length == 0)
                continue;

            if (s.Length <= maxChars)
            {
                result.Add(s);
                continue;
            }

            var words = s.Split(
                ' ',
                StringSplitOptions.RemoveEmptyEntries);

            var buffer = new StringBuilder();

            foreach (var word in words)
            {
                if (buffer.Length > 0
                    && buffer.Length + 1 + word.Length > maxChars)
                {
                    result.Add(buffer.ToString());
                    buffer.Clear();
                }

                if (buffer.Length > 0)
                    buffer.Append(' ');

                buffer.Append(word);
            }

            if (buffer.Length > 0)
                result.Add(buffer.ToString());
        }

        return result;
    }

    private void SetActiveChapterFromPlayback(
        OpenBookTab state,
        int chapterIndex)
    {
        int nextChapter = Math.Clamp(
            chapterIndex,
            0,
            state.Book.Chapters.Count - 1);

        bool chapterChanged =
            state.ChapterIndex != nextChapter;

        state.ChapterIndex =
            nextChapter;

        if (chapterChanged)
        {
            state.LastSentenceIndex = 0;
            state.ScrollRatio = 0;
        }

        if (ReferenceEquals(activeTab, state))
        {
            suppressChapterSelection = true;
            try
            {
                ChapterList.SelectedIndex =
                    state.ChapterIndex;

                if (ChapterList.SelectedItem != null)
                    ChapterList.ScrollIntoView(
                        ChapterList.SelectedItem);
            }
            finally
            {
                suppressChapterSelection = false;
            }

            if (chapterChanged)
                ShowCurrentChapter(state);
        }

        libraryStore.UpdateProgress(
            state.Book,
            state.ChapterIndex,
            state.LastSentenceIndex,
            state.ScrollRatio);

        RefreshLibrary(
            state.Book.SourcePath);
        UpdateBookProgressUi(state);
    }

    private void Stop_Click(
        object sender,
        RoutedEventArgs e)
    {
        StopPlayback(true);
        StatusText.Text = "Đã dừng";
    }

    private void StopPlayback(bool userInitiated)
    {
        userStopRequested = userInitiated;
        isPlaybackActive = false;
        activePlaybackBookPath = null;

        // Invalidate EVERY old callback/task immediately.
        playbackSessionId++;

        try { playbackCts?.Cancel(); } catch { }
        playbackCts?.Dispose();
        playbackCts = null;

        DisposeCurrentOutput();

        PlayButton.IsEnabled = true;
        MiniPlayButton.IsEnabled = true;
    }

    private void DisposeCurrentOutput()
    {
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
            if (File.Exists(path))
                File.Delete(path);
        }
        catch
        {
        }
    }

    // ---------------------------------------------------------------------
    // MP3
    // ---------------------------------------------------------------------

    private async void Export_Click(
        object sender,
        RoutedEventArgs e)
    {
        if (activeTab == null
            || VoiceCombo.SelectedItem is not VoiceDefinition voice)
        {
            MessageBox.Show(
                this,
                "Hãy mở EPUB và chọn giọng trước.");
            return;
        }

        var exportBook = activeTab.Book;

        if (!tts.IsInstalled(voice))
        {
            MessageBox.Show(
                this,
                "Hãy tải giọng trước khi xuất MP3.");
            return;
        }

        var scope = MessageBox.Show(
            this,
            "Xuất toàn bộ sách?\n\n"
            + "Yes = toàn bộ sách\n"
            + "No = chương đang chọn\n"
            + "Cancel = hủy",
            "Xuất MP3",
            MessageBoxButton.YesNoCancel,
            MessageBoxImage.Question);

        if (scope == MessageBoxResult.Cancel)
            return;

        bool all =
            scope == MessageBoxResult.Yes;

        bool merge = false;

        if (all)
        {
            var mode = MessageBox.Show(
                this,
                "Gộp toàn bộ sách thành 1 file MP3?\n\n"
                + "Yes = 1 file\n"
                + "No = mỗi chương 1 file",
                "Kiểu xuất",
                MessageBoxButton.YesNoCancel,
                MessageBoxImage.Question);

            if (mode == MessageBoxResult.Cancel)
                return;

            merge =
                mode == MessageBoxResult.Yes;
        }

        using var folderDlg =
            new Forms.FolderBrowserDialog
            {
                Description = "Chọn thư mục lưu MP3",
                UseDescriptionForTitle = true
            };

        if (folderDlg.ShowDialog()
            != Forms.DialogResult.OK)
            return;

        operationCts?.Cancel();
        operationCts =
            new CancellationTokenSource();

        var report =
            new Progress<(double, string)>(
                x =>
                {
                    Progress.Value = x.Item1;
                    StatusText.Text = x.Item2;
                });

        try
        {
            StopPlayback(true);
            Busy("Đang xuất MP3...", true);

            if (all && merge)
            {
                await exporter.ExportWholeBookAsync(
                    exportBook,
                    voice,
                    (float)SpeedSlider.Value,
                    folderDlg.SelectedPath,
                    report,
                    operationCts.Token);
            }
            else
            {
                IEnumerable<BookChapter> chapters =
                    all
                        ? exportBook.Chapters
                        : new[]
                        {
                            exportBook.Chapters[
                                Math.Clamp(
                                    activeTab.ChapterIndex,
                                    0,
                                    exportBook.Chapters.Count - 1)]
                        };

                await exporter.ExportChaptersAsync(
                    exportBook,
                    chapters,
                    voice,
                    (float)SpeedSlider.Value,
                    folderDlg.SelectedPath,
                    report,
                    operationCts.Token);
            }

            MessageBox.Show(
                this,
                "Xuất MP3 hoàn tất.",
                "VietVoice Reader",
                MessageBoxButton.OK,
                MessageBoxImage.Information);
        }
        catch (OperationCanceledException)
        {
            StatusText.Text =
                "Đã hủy xuất MP3";
        }
        catch (Exception ex)
        {
            MessageBox.Show(
                this,
                ex.Message,
                "Lỗi xuất MP3",
                MessageBoxButton.OK,
                MessageBoxImage.Error);
        }
        finally
        {
            Busy(null, false);
        }
    }

    // ---------------------------------------------------------------------
    // Display settings
    // ---------------------------------------------------------------------

    private void ReaderZoomSlider_ValueChanged(
        object sender,
        RoutedPropertyChangedEventArgs<double> e)
    {
        if (ReaderZoomText != null)
            ReaderZoomText.Text = $"{e.NewValue:0}%";

        if (suppressSettingsEvents
            || activeTab == null)
            return;

        activeTab.Settings.ZoomPercent =
            Math.Clamp(e.NewValue, 50, 200);

        activeTab.Reader.Zoom =
            activeTab.Settings.ZoomPercent;

        currentSettings =
            activeTab.Settings;

        SaveCurrentSettings();
    }

    private void GoToPage_Click(
        object sender,
        RoutedEventArgs e) =>
        GoToTypedPage();

    private void PageNumberBox_KeyDown(
        object sender,
        KeyEventArgs e)
    {
        if (e.Key != Key.Enter)
            return;

        e.Handled = true;
        GoToTypedPage();
    }

    private void GoToTypedPage()
    {
        if (activeTab == null)
            return;

        if (!int.TryParse(
                PageNumberBox.Text,
                out int page)
            || page < 1)
        {
            StatusText.Text =
                "Nhập số trang từ 1 trở lên.";
            return;
        }

        activeTab.Settings.ViewMode =
            "1 trang";

        activeTab.Reader.ViewingMode =
            FlowDocumentReaderViewingMode.Page;

        suppressSettingsEvents = true;
        try
        {
            SelectComboByText(
                ViewModeCombo,
                "1 trang");
        }
        finally
        {
            suppressSettingsEvents = false;
        }

        SaveCurrentSettings();

        activeTab.Reader.Focus();

        if (NavigationCommands.GoToPage.CanExecute(
                page,
                activeTab.Reader))
        {
            NavigationCommands.GoToPage.Execute(
                page,
                activeTab.Reader);

            StatusText.Text =
                $"Đã chuyển đến trang {page}.";
        }
        else
        {
            StatusText.Text =
                $"Không thể chuyển đến trang {page}.";
        }
    }

    private void FocusMode_Click(
        object sender,
        RoutedEventArgs e)
    {
        isFocusMode = !isFocusMode;

        LeftPane.Visibility =
            isFocusMode
                ? Visibility.Collapsed
                : Visibility.Visible;

        RightPane.Visibility =
            isFocusMode
                ? Visibility.Collapsed
                : Visibility.Visible;

        LeftColumn.Width =
            isFocusMode
                ? new GridLength(0)
                : new GridLength(320);

        RightColumn.Width =
            isFocusMode
                ? new GridLength(0)
                : new GridLength(310);

        FocusModeButton.Content =
            isFocusMode
                ? "Thoát tập trung"
                : "Tập trung";
    }

    private void Reader_PreviewMouseWheel(
        OpenBookTab state,
        MouseWheelEventArgs e)
    {
        if ((Keyboard.Modifiers
             & ModifierKeys.Control)
            == ModifierKeys.Control)
        {
            double next =
                Math.Clamp(
                    state.Settings.ZoomPercent
                    + (e.Delta > 0 ? 10 : -10),
                    50,
                    200);

            state.Settings.ZoomPercent = next;
            state.Reader.Zoom = next;
            settingsStore.Save(
                state.Book.SourcePath,
                state.Settings);

            if (ReferenceEquals(
                    activeTab,
                    state))
            {
                suppressSettingsEvents = true;
                try
                {
                    ReaderZoomSlider.Value =
                        next;

                    ReaderZoomText.Text =
                        $"{next:0}%";
                }
                finally
                {
                    suppressSettingsEvents = false;
                }
            }

            e.Handled = true;
            return;
        }

        if (e.Delta >= 0
            || isPlaybackActive
            || state.Settings.ViewMode != "Cuộn")
            return;

        var scroll =
            FindScrollableViewer(
                state.Reader);

        if (scroll == null)
            return;

        bool atBottom =
            scroll.ScrollableHeight <= 0
            || scroll.VerticalOffset
               >= scroll.ScrollableHeight - 2;

        if (!atBottom)
            return;

        if (state.ChapterIndex
            >= state.Book.Chapters.Count - 1)
            return;

        e.Handled = true;

        Dispatcher.BeginInvoke(() =>
            MoveToChapterFromReading(
                state,
                state.ChapterIndex + 1));
    }

    private void Reader_PreviewKeyDown(
        OpenBookTab state,
        KeyEventArgs e)
    {
        if (isPlaybackActive
            || state.Settings.ViewMode != "Cuộn")
            return;

        if (e.Key != Key.PageDown
            && e.Key != Key.Down
            && e.Key != Key.Space)
            return;

        var scroll =
            FindScrollableViewer(
                state.Reader);

        if (scroll == null)
            return;

        if (scroll.VerticalOffset
            < scroll.ScrollableHeight - 2)
            return;

        if (state.ChapterIndex
            >= state.Book.Chapters.Count - 1)
            return;

        e.Handled = true;

        MoveToChapterFromReading(
            state,
            state.ChapterIndex + 1);
    }

    private void MoveToChapterFromReading(
        OpenBookTab state,
        int chapterIndex,
        bool toEnd = false)
    {
        chapterIndex =
            Math.Clamp(
                chapterIndex,
                0,
                state.Book.Chapters.Count - 1);

        state.ChapterIndex =
            chapterIndex;
        state.LastSentenceIndex = 0;
        state.ScrollRatio =
            toEnd ? 1 : 0;

        if (BookTabs.SelectedItem
            != state.Tab)
        {
            BookTabs.SelectedItem =
                state.Tab;
        }

        if (ReferenceEquals(
                activeTab,
                state))
        {
            suppressChapterSelection = true;
            try
            {
                ChapterList.SelectedIndex =
                    chapterIndex;

                ChapterList.ScrollIntoView(
                    state.Book.Chapters[
                        chapterIndex]);
            }
            finally
            {
                suppressChapterSelection =
                    false;
            }

            ShowCurrentChapter(state);

            if (toEnd)
            {
                Dispatcher.BeginInvoke(() =>
                {
                    var scroll =
                        FindScrollableViewer(
                            state.Reader);

                    scroll?.ScrollToEnd();
                });
            }
        }

        libraryStore.UpdateProgress(
            state.Book,
            chapterIndex,
            state.LastSentenceIndex,
            state.ScrollRatio);

        RefreshLibrary(
            state.Book.SourcePath);
        UpdateBookProgressUi(state);

        StatusText.Text =
            $"Chương {chapterIndex + 1}/{state.Book.Chapters.Count}: "
            + state.Book.Chapters[
                chapterIndex].Title;
    }

    private void HighlightPlaybackSentence(
        OpenBookTab state,
        int sentenceIndex)
    {
        if (state.HighlightedRun != null)
        {
            state.HighlightedRun.Background =
                null;

            state.HighlightedRun.FontWeight =
                FontWeights.Normal;
        }

        if (sentenceIndex < 0
            || sentenceIndex
               >= state.SentenceRuns.Count)
        {
            state.HighlightedRun = null;
            return;
        }

        var run =
            state.SentenceRuns[
                sentenceIndex];

        var color =
            state.Settings.Theme == "Tối"
                ? Color.FromArgb(
                    150,
                    110,
                    92,
                    32)
                : Color.FromArgb(
                    160,
                    255,
                    226,
                    116);

        run.Background =
            new SolidColorBrush(color);

        run.FontWeight =
            FontWeights.SemiBold;

        state.HighlightedRun = run;

        Dispatcher.BeginInvoke(() =>
        {
            try
            {
                run.BringIntoView();
            }
            catch
            {
            }
        });
    }

    private void HookReaderScroll(
        OpenBookTab state,
        ScrollViewer scroll)
    {
        if (ReferenceEquals(
                state.AttachedScroll,
                scroll))
            return;

        state.AttachedScroll = scroll;

        scroll.ScrollChanged += (_, _) =>
        {
            if (!ReferenceEquals(
                    state.AttachedScroll,
                    scroll))
                return;

            ReaderScrollChanged(
                state,
                scroll);
        };
    }

    private void ReaderScrollChanged(
        OpenBookTab state,
        ScrollViewer scroll)
    {
        if (scroll.ScrollableHeight <= 0)
            state.ScrollRatio = 0;
        else
            state.ScrollRatio =
                Math.Clamp(
                    scroll.VerticalOffset
                    / scroll.ScrollableHeight,
                    0,
                    1);

        if (Math.Abs(
                state.ScrollRatio
                - state.LastSavedScrollRatio)
            >= 0.02)
        {
            state.LastSavedScrollRatio =
                state.ScrollRatio;

            libraryStore.UpdateProgress(
                state.Book,
                state.ChapterIndex,
                state.LastSentenceIndex,
                state.ScrollRatio,
                touchLastOpened: false);
        }

        if (ReferenceEquals(
                activeTab,
                state))
        {
            UpdateBookProgressUi(state);
        }
    }

    private double GetCurrentScrollRatio(
        OpenBookTab state)
    {
        var scroll =
            FindScrollableViewer(
                state.Reader);

        if (scroll == null
            || scroll.ScrollableHeight <= 0)
            return state.ScrollRatio;

        return Math.Clamp(
            scroll.VerticalOffset
            / scroll.ScrollableHeight,
            0,
            1);
    }

    private void UpdateBookProgressUi(
        OpenBookTab state)
    {
        if (!ReferenceEquals(
                activeTab,
                state)
            || state.Book.Chapters.Count <= 0)
            return;

        double within =
            Math.Clamp(
                state.ScrollRatio,
                0,
                1);

        double progress =
            100.0
            * (state.ChapterIndex + within)
            / state.Book.Chapters.Count;

        if (state.ChapterIndex
                == state.Book.Chapters.Count - 1
            && within >= 0.995)
        {
            progress = 100;
        }

        BookProgressSlider.Value =
            Math.Clamp(progress, 0, 100);

        BookProgressText.Text =
            $"{progress:0.0}% • {state.ChapterIndex + 1}/{state.Book.Chapters.Count}";
    }

    private void BookProgressSlider_MouseLeftButtonUp(
        object sender,
        MouseButtonEventArgs e)
    {
        if (activeTab == null
            || activeTab.Book.Chapters.Count == 0)
            return;

        double normalized =
            Math.Clamp(
                BookProgressSlider.Value / 100.0,
                0,
                0.999999);

        double exactChapter =
            normalized
            * activeTab.Book.Chapters.Count;

        int chapterIndex =
            Math.Clamp(
                (int)Math.Floor(exactChapter),
                0,
                activeTab.Book.Chapters.Count - 1);

        double ratio =
            Math.Clamp(
                exactChapter - chapterIndex,
                0,
                1);

        activeTab.LastSentenceIndex = 0;
        activeTab.ScrollRatio = ratio;

        MoveToChapterFromReading(
            activeTab,
            chapterIndex);

        Dispatcher.BeginInvoke(() =>
        {
            var scroll =
                FindScrollableViewer(
                    activeTab.Reader);

            if (scroll != null
                && scroll.ScrollableHeight > 0)
            {
                scroll.ScrollToVerticalOffset(
                    scroll.ScrollableHeight
                    * ratio);
            }
        });
    }

    private void Reader_PreviewMouseLeftButtonUp(
        OpenBookTab state,
        MouseButtonEventArgs e)
    {
        if (isPlaybackActive)
            return;

        var point =
            e.GetPosition(
                state.Reader);

        double width =
            Math.Max(
                1,
                state.Reader.ActualWidth);

        bool left =
            point.X <= width * 0.16;

        bool right =
            point.X >= width * 0.84;

        if (!left && !right)
            return;

        if (state.Settings.ViewMode == "Cuộn")
        {
            var scroll =
                FindScrollableViewer(
                    state.Reader);

            if (scroll == null)
                return;

            if (left)
            {
                if (scroll.VerticalOffset > 2)
                {
                    scroll.PageUp();
                }
                else if (state.ChapterIndex > 0)
                {
                    MoveToChapterFromReading(
                        state,
                        state.ChapterIndex - 1,
                        toEnd: true);
                }
            }
            else
            {
                bool atBottom =
                    scroll.ScrollableHeight <= 0
                    || scroll.VerticalOffset
                       >= scroll.ScrollableHeight - 2;

                if (!atBottom)
                {
                    scroll.PageDown();
                }
                else if (state.ChapterIndex
                         < state.Book.Chapters.Count - 1)
                {
                    MoveToChapterFromReading(
                        state,
                        state.ChapterIndex + 1);
                }
            }

            e.Handled = true;
            return;
        }

        var command =
            left
                ? NavigationCommands.PreviousPage
                : NavigationCommands.NextPage;

        if (command.CanExecute(
                null,
                state.Reader))
        {
            command.Execute(
                null,
                state.Reader);

            e.Handled = true;
            return;
        }

        if (left
            && state.ChapterIndex > 0)
        {
            MoveToChapterFromReading(
                state,
                state.ChapterIndex - 1,
                toEnd: true);

            e.Handled = true;
        }
        else if (right
                 && state.ChapterIndex
                    < state.Book.Chapters.Count - 1)
        {
            MoveToChapterFromReading(
                state,
                state.ChapterIndex + 1);

            e.Handled = true;
        }
    }

    private static ScrollViewer? FindScrollableViewer(
        DependencyObject root)
    {
        ScrollViewer? best = null;
        double bestHeight = -1;

        foreach (var scroll
                 in FindVisualChildren<ScrollViewer>(
                     root))
        {
            if (scroll.ScrollableHeight
                > bestHeight)
            {
                best = scroll;
                bestHeight =
                    scroll.ScrollableHeight;
            }
        }

        return best;
    }

    private static IEnumerable<T> FindVisualChildren<T>(
        DependencyObject root)
        where T : DependencyObject
    {
        if (root == null)
            yield break;

        int count =
            VisualTreeHelper.GetChildrenCount(
                root);

        for (int i = 0; i < count; i++)
        {
            var child =
                VisualTreeHelper.GetChild(
                    root,
                    i);

            if (child is T typed)
                yield return typed;

            foreach (var nested
                     in FindVisualChildren<T>(
                         child))
            {
                yield return nested;
            }
        }
    }

    private void SpeedSlider_ValueChanged(
        object sender,
        RoutedPropertyChangedEventArgs<double> e)
    {
        if (SpeedText != null)
            SpeedText.Text =
                $"{e.NewValue:0.00}x";
    }

    private void FontCombo_SelectionChanged(
        object sender,
        SelectionChangedEventArgs e)
    {
        if (suppressSettingsEvents
            || activeTab == null)
            return;

        if (FontCombo.SelectedItem
            is FontFamily family)
        {
            activeTab.Settings.FontFamily =
                family.Source;

            currentSettings =
                activeTab.Settings;

            RefreshCurrentChapterAndSave();
        }
    }

    private void FontSizeSlider_ValueChanged(
        object sender,
        RoutedPropertyChangedEventArgs<double> e)
    {
        if (FontSizeText != null)
            FontSizeText.Text =
                $"{e.NewValue:0}";

        if (suppressSettingsEvents
            || activeTab == null)
            return;

        activeTab.Settings.FontSize =
            e.NewValue;

        currentSettings =
            activeTab.Settings;

        RefreshCurrentChapterAndSave();
    }

    private void LineHeightSlider_ValueChanged(
        object sender,
        RoutedPropertyChangedEventArgs<double> e)
    {
        if (LineHeightText != null)
            LineHeightText.Text =
                $"{e.NewValue:0}";

        if (suppressSettingsEvents
            || activeTab == null)
            return;

        activeTab.Settings.LineHeight =
            e.NewValue;

        currentSettings =
            activeTab.Settings;

        RefreshCurrentChapterAndSave();
    }

    private void ThemeCombo_SelectionChanged(
        object sender,
        SelectionChangedEventArgs e)
    {
        if (suppressSettingsEvents
            || activeTab == null)
            return;

        if (ThemeCombo.SelectedItem
            is ComboBoxItem item)
        {
            activeTab.Settings.Theme =
                item.Content?.ToString()
                ?? "Sáng";
        }

        currentSettings =
            activeTab.Settings;

        ApplyThemeToReader(activeTab);
        SaveCurrentSettings();
    }

    private void ViewModeCombo_SelectionChanged(
        object sender,
        SelectionChangedEventArgs e)
    {
        if (suppressSettingsEvents
            || activeTab == null
            || ViewModeCombo.SelectedItem
                is not ComboBoxItem item)
            return;

        activeTab.Settings.ViewMode =
            item.Content?.ToString()
            ?? "Cuộn";

        activeTab.Reader.ViewingMode =
            ToViewingMode(
                activeTab.Settings.ViewMode);

        currentSettings =
            activeTab.Settings;

        SaveCurrentSettings();
    }

    private void ImportFont_Click(
        object sender,
        RoutedEventArgs e)
    {
        var dlg =
            new OpenFileDialog
            {
                Filter =
                    "Font (*.ttf;*.otf)|*.ttf;*.otf"
            };

        if (dlg.ShowDialog() != true)
            return;

        try
        {
            var families =
                Fonts.GetFontFamilies(
                    new Uri(
                        dlg.FileName,
                        UriKind.Absolute))
                .ToList();

            if (families.Count == 0)
                throw new InvalidDataException(
                    "Không đọc được font này.");

            var existing =
                (FontCombo.ItemsSource
                    as IEnumerable<FontFamily>)
                ?.ToList()
                ?? new List<FontFamily>();

            existing.AddRange(
                families.Where(
                    x => existing.All(
                        e => e.Source != x.Source)));

            FontCombo.ItemsSource =
                existing
                    .OrderBy(x => x.Source)
                    .ToList();

            FontCombo.SelectedItem =
                families[0];

            StatusText.Text =
                "Đã nạp font: "
                + families[0].Source;
        }
        catch (Exception ex)
        {
            MessageBox.Show(
                this,
                ex.Message,
                "Không nạp được font",
                MessageBoxButton.OK,
                MessageBoxImage.Error);
        }
    }

    private void LoadSettingsIntoControls(
        OpenBookTab state)
    {
        suppressSettingsEvents = true;

        try
        {
            currentSettings =
                state.Settings;

            FontSizeSlider.Value =
                state.Settings.FontSize;

            LineHeightSlider.Value =
                state.Settings.LineHeight;

            ReaderZoomSlider.Value =
                Math.Clamp(
                    state.Settings.ZoomPercent,
                    50,
                    200);

            ReaderZoomText.Text =
                $"{ReaderZoomSlider.Value:0}%";

            state.Reader.Zoom =
                ReaderZoomSlider.Value;

            var fonts =
                (FontCombo.ItemsSource
                    as IEnumerable<FontFamily>)
                ?.ToList()
                ?? new();

            var selected =
                fonts.FirstOrDefault(
                    f => f.Source.Equals(
                        state.Settings.FontFamily,
                        StringComparison.OrdinalIgnoreCase));

            if (selected != null)
                FontCombo.SelectedItem =
                    selected;

            SelectComboByText(
                ThemeCombo,
                state.Settings.Theme);

            SelectComboByText(
                ViewModeCombo,
                state.Settings.ViewMode);

            state.Reader.ViewingMode =
                ToViewingMode(
                    state.Settings.ViewMode);
        }
        finally
        {
            suppressSettingsEvents = false;
        }
    }

    private static void SelectComboByText(
        ComboBox combo,
        string text)
    {
        foreach (var item
                 in combo.Items
                     .OfType<ComboBoxItem>())
        {
            if (string.Equals(
                    item.Content?.ToString(),
                    text,
                    StringComparison.OrdinalIgnoreCase))
            {
                combo.SelectedItem = item;
                return;
            }
        }
    }

    private void RefreshCurrentChapterAndSave()
    {
        if (activeTab == null)
            return;

        ShowCurrentChapter(activeTab);
        SaveCurrentSettings();
    }

    private void SaveCurrentSettings()
    {
        if (activeTab != null)
        {
            settingsStore.Save(
                activeTab.Book.SourcePath,
                activeTab.Settings);
        }
    }

    private static void ApplyThemeToReader(
        OpenBookTab state)
    {
        var (bg, fg) =
            state.Settings.Theme switch
            {
                "Tối" =>
                    (
                        Color.FromRgb(30, 30, 32),
                        Color.FromRgb(232, 232, 235)
                    ),
                "Sepia" =>
                    (
                        Color.FromRgb(244, 236, 216),
                        Color.FromRgb(69, 55, 38)
                    ),
                _ =>
                    (
                        Colors.White,
                        Color.FromRgb(32, 32, 34)
                    )
            };

        state.Reader.Background =
            new SolidColorBrush(bg);

        if (state.Reader.Document != null)
        {
            state.Reader.Document.Foreground =
                new SolidColorBrush(fg);

            state.Reader.Document.Background =
                new SolidColorBrush(bg);
        }
    }

    private static FlowDocumentReaderViewingMode ToViewingMode(
        string mode) =>
        mode switch
        {
            "1 trang" =>
                FlowDocumentReaderViewingMode.Page,
            "2 trang" =>
                FlowDocumentReaderViewingMode.TwoPage,
            _ =>
                FlowDocumentReaderViewingMode.Scroll
        };

    // ---------------------------------------------------------------------
    // UI helpers
    // ---------------------------------------------------------------------

    private void Busy(
        string? message,
        bool busy)
    {
        OpenBookButton.IsEnabled =
            !busy;

        ExportButton.IsEnabled =
            !busy;

        DownloadVoiceButton.IsEnabled =
            !busy
            && VoiceCombo.SelectedItem
                is VoiceDefinition v
            && !tts.IsInstalled(v);

        if (!isPlaybackActive)
            PlayButton.IsEnabled =
                !busy;

        if (!string.IsNullOrWhiteSpace(message))
            StatusText.Text = message;

        if (!busy)
            Progress.Value = 0;
    }

    private static string SafeFullPath(
        string path)
    {
        try
        {
            return Path.GetFullPath(path);
        }
        catch
        {
            return path;
        }
    }

    private static string ShortTitle(
        string title,
        int max)
    {
        if (string.IsNullOrWhiteSpace(title))
            return "EPUB";

        title = title.Trim();

        return title.Length <= max
            ? title
            : title[..Math.Max(1, max - 1)]
              + "…";
    }

    protected override void OnClosed(
        EventArgs e)
    {
        operationCts?.Cancel();
        StopPlayback(true);
        tts.Dispose();
        base.OnClosed(e);
    }
}
