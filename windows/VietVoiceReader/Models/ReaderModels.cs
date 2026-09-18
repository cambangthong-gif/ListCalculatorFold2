namespace VietVoiceReader.Models;

public sealed class BookChapter
{
    public string Title { get; set; } = "Chương";
    public string FilePath { get; set; } = string.Empty;
    public string Text { get; set; } = string.Empty;
    public override string ToString() => Title;
}

public sealed class BookDocument
{
    public string SourcePath { get; set; } = string.Empty;
    public string Title { get; set; } = "Sách EPUB";
    public string Author { get; set; } = string.Empty;
    public List<BookChapter> Chapters { get; set; } = new();
}

public sealed class VoiceDefinition
{
    public string Id { get; init; } = string.Empty;
    public string DisplayName { get; init; } = string.Empty;
    public string Source { get; init; } = string.Empty;
    public string? ArchiveUrl { get; init; }
    public string? ModelUrl { get; init; }
    public string? TokensUrl { get; init; }
    public string ModelFileName { get; init; } = "model.onnx";
    public override string ToString() => DisplayName;
}

public sealed class BookDisplaySettings
{
    public string FontFamily { get; set; } = "Segoe UI";
    public double FontSize { get; set; } = 20;
    public double LineHeight { get; set; } = 32;
    public string Theme { get; set; } = "Sáng";
    public string ViewMode { get; set; } = "Cuộn";
    public double ZoomPercent { get; set; } = 100;
}


public sealed class BookLibraryItem
{
    public string SourcePath { get; set; } = string.Empty;
    public string Title { get; set; } = "Sách EPUB";
    public string Author { get; set; } = string.Empty;
    public int LastChapterIndex { get; set; }
    public int LastSentenceIndex { get; set; }
    public double LastScrollRatio { get; set; }
    public string LastChapterTitle { get; set; } = string.Empty;
    public int ChapterCount { get; set; }
    public DateTime LastOpenedUtc { get; set; } = DateTime.UtcNow;

    public int ProgressPercent =>
        ChapterCount <= 0 ? 0 :
        (int)Math.Round(100.0 * Math.Clamp(LastChapterIndex + 1, 0, ChapterCount) / ChapterCount);

    public string ProgressText =>
        ChapterCount <= 0
            ? "Chưa có tiến độ"
            : $"Chương {Math.Clamp(LastChapterIndex + 1, 1, ChapterCount)}/{ChapterCount} • {ProgressPercent}%";

    public string LastReadText =>
        string.IsNullOrWhiteSpace(LastChapterTitle)
            ? ProgressText
            : $"{ProgressText} • {LastChapterTitle}";

    public string LastOpenedText =>
        LastOpenedUtc.ToLocalTime().ToString("dd/MM/yyyy HH:mm");
}


public sealed class BookmarkItem
{
    public string Id { get; set; } = Guid.NewGuid().ToString("N");
    public string SourcePath { get; set; } = string.Empty;
    public string BookTitle { get; set; } = string.Empty;
    public int ChapterIndex { get; set; }
    public string ChapterTitle { get; set; } = string.Empty;
    public int SentenceIndex { get; set; }
    public double ScrollRatio { get; set; }
    public DateTime CreatedUtc { get; set; } = DateTime.UtcNow;

    public string DisplayTitle =>
        string.IsNullOrWhiteSpace(ChapterTitle)
            ? $"Chương {ChapterIndex + 1}"
            : ChapterTitle;

    public string DisplayDetail =>
        $"Chương {ChapterIndex + 1} • {CreatedUtc.ToLocalTime():dd/MM/yyyy HH:mm}";
}

public sealed class BookSearchResult
{
    public int ChapterIndex { get; set; }
    public string ChapterTitle { get; set; } = string.Empty;
    public int CharacterIndex { get; set; }
    public string Snippet { get; set; } = string.Empty;

    public string DisplayTitle =>
        $"Chương {ChapterIndex + 1}: {ChapterTitle}";
}
