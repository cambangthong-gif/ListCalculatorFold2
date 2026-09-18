using System.Text.Json;
using VietVoiceReader.Models;

namespace VietVoiceReader.Services;

public sealed class LibraryStore
{
    private readonly string path;
    private readonly Dictionary<string, BookLibraryItem> items =
        new(StringComparer.OrdinalIgnoreCase);

    public LibraryStore()
    {
        var dir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "VietVoiceReader");
        Directory.CreateDirectory(dir);
        path = Path.Combine(dir, "library.json");
        Load();
    }

    public IReadOnlyList<BookLibraryItem> GetRecent() =>
        items.Values
            .OrderByDescending(x => x.LastOpenedUtc)
            .ThenBy(x => x.Title, StringComparer.CurrentCultureIgnoreCase)
            .ToList();

    public BookLibraryItem? Find(string sourcePath)
    {
        if (string.IsNullOrWhiteSpace(sourcePath)) return null;
        items.TryGetValue(Normalize(sourcePath), out var item);
        return item;
    }

    public BookLibraryItem UpsertBook(
        BookDocument book,
        int chapterIndex,
        int sentenceIndex = 0,
        double scrollRatio = 0)
    {
        var key = Normalize(book.SourcePath);
        if (!items.TryGetValue(key, out var item))
        {
            item = new BookLibraryItem { SourcePath = book.SourcePath };
            items[key] = item;
        }

        item.SourcePath = book.SourcePath;
        item.Title = book.Title;
        item.Author = book.Author;
        item.ChapterCount = book.Chapters.Count;
        item.LastChapterIndex = ClampChapter(chapterIndex, item.ChapterCount);
        item.LastSentenceIndex = Math.Max(0, sentenceIndex);
        item.LastScrollRatio = Math.Clamp(scrollRatio, 0, 1);
        item.LastChapterTitle = item.ChapterCount > 0
            ? book.Chapters[item.LastChapterIndex].Title
            : string.Empty;
        item.LastOpenedUtc = DateTime.UtcNow;
        Save();
        return item;
    }

    public void UpdateProgress(
        BookDocument book,
        int chapterIndex,
        int sentenceIndex = 0,
        double scrollRatio = 0,
        bool touchLastOpened = true)
    {
        var key = Normalize(book.SourcePath);
        if (!items.TryGetValue(key, out var item))
        {
            UpsertBook(
                book,
                chapterIndex,
                sentenceIndex,
                scrollRatio);
            return;
        }

        item.Title = book.Title;
        item.Author = book.Author;
        item.ChapterCount = book.Chapters.Count;
        item.LastChapterIndex = ClampChapter(chapterIndex, item.ChapterCount);
        item.LastSentenceIndex = Math.Max(0, sentenceIndex);
        item.LastScrollRatio = Math.Clamp(scrollRatio, 0, 1);
        item.LastChapterTitle = item.ChapterCount > 0
            ? book.Chapters[item.LastChapterIndex].Title
            : string.Empty;

        if (touchLastOpened)
            item.LastOpenedUtc = DateTime.UtcNow;

        Save();
    }

    public void Remove(string sourcePath)
    {
        if (items.Remove(Normalize(sourcePath)))
            Save();
    }

    private static int ClampChapter(int index, int count)
    {
        if (count <= 0) return 0;
        return Math.Clamp(index, 0, count - 1);
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
            if (!File.Exists(path)) return;
            var data = JsonSerializer.Deserialize<List<BookLibraryItem>>(File.ReadAllText(path));
            if (data == null) return;

            foreach (var item in data)
            {
                if (string.IsNullOrWhiteSpace(item.SourcePath)) continue;
                items[Normalize(item.SourcePath)] = item;
            }
        }
        catch
        {
        }
    }

    private void Save()
    {
        try
        {
            var ordered = items.Values
                .OrderByDescending(x => x.LastOpenedUtc)
                .ToList();
            File.WriteAllText(
                path,
                JsonSerializer.Serialize(
                    ordered,
                    new JsonSerializerOptions { WriteIndented = true }));
        }
        catch
        {
        }
    }
}
