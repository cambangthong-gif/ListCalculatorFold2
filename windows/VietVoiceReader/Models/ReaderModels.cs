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
}
