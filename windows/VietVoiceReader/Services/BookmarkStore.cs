using System.Text.Json;
using VietVoiceReader.Models;

namespace VietVoiceReader.Services;

public sealed class BookmarkStore
{
    private readonly string path;
    private readonly List<BookmarkItem> items = new();

    public BookmarkStore()
    {
        var dir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "VietVoiceReader");

        Directory.CreateDirectory(dir);
        path = Path.Combine(dir, "bookmarks.json");
        Load();
    }

    public IReadOnlyList<BookmarkItem> ForBook(string sourcePath) =>
        items
            .Where(x => string.Equals(
                Normalize(x.SourcePath),
                Normalize(sourcePath),
                StringComparison.OrdinalIgnoreCase))
            .OrderBy(x => x.ChapterIndex)
            .ThenBy(x => x.SentenceIndex)
            .ThenBy(x => x.ScrollRatio)
            .ToList();

    public BookmarkItem Add(
        BookDocument book,
        int chapterIndex,
        int sentenceIndex,
        double scrollRatio)
    {
        chapterIndex = Math.Clamp(
            chapterIndex,
            0,
            Math.Max(0, book.Chapters.Count - 1));

        var chapterTitle =
            book.Chapters.Count == 0
                ? string.Empty
                : book.Chapters[chapterIndex].Title;

        var existing = items.FirstOrDefault(x =>
            string.Equals(
                Normalize(x.SourcePath),
                Normalize(book.SourcePath),
                StringComparison.OrdinalIgnoreCase)
            && x.ChapterIndex == chapterIndex
            && Math.Abs(x.ScrollRatio - scrollRatio) < 0.03
            && Math.Abs(x.SentenceIndex - sentenceIndex) <= 1);

        if (existing != null)
            return existing;

        var item = new BookmarkItem
        {
            SourcePath = book.SourcePath,
            BookTitle = book.Title,
            ChapterIndex = chapterIndex,
            ChapterTitle = chapterTitle,
            SentenceIndex = Math.Max(0, sentenceIndex),
            ScrollRatio = Math.Clamp(scrollRatio, 0, 1),
            CreatedUtc = DateTime.UtcNow
        };

        items.Add(item);
        Save();
        return item;
    }

    public void Remove(string id)
    {
        items.RemoveAll(x =>
            string.Equals(
                x.Id,
                id,
                StringComparison.OrdinalIgnoreCase));

        Save();
    }

    private static string Normalize(string path)
    {
        try { return Path.GetFullPath(path); }
        catch { return path; }
    }

    private void Load()
    {
        try
        {
            if (!File.Exists(path))
                return;

            var data =
                JsonSerializer.Deserialize<List<BookmarkItem>>(
                    File.ReadAllText(path));

            if (data != null)
                items.AddRange(data);
        }
        catch
        {
        }
    }

    private void Save()
    {
        try
        {
            File.WriteAllText(
                path,
                JsonSerializer.Serialize(
                    items,
                    new JsonSerializerOptions
                    {
                        WriteIndented = true
                    }));
        }
        catch
        {
        }
    }
}
