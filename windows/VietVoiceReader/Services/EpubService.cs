using System.Net;
using System.Text.RegularExpressions;
using VersOne.Epub;
using VersOne.Epub.Options;
using VietVoiceReader.Models;

namespace VietVoiceReader.Services;

public static class EpubService
{
    public static async Task<BookDocument> LoadAsync(string filePath)
    {
        var options = new EpubReaderOptions();
        options.ContentReaderOptions.ContentFileMissing += (_, e) => e.SuppressException = true;
        EpubBook book = await EpubReader.ReadBookAsync(filePath, options);

        var titleMap = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        AddNavigation(book.Navigation, titleMap);

        var result = new BookDocument
        {
            SourcePath = filePath,
            Title = string.IsNullOrWhiteSpace(book.Title) ? Path.GetFileNameWithoutExtension(filePath) : book.Title,
            Author = book.Author ?? string.Empty
        };

        int index = 1;
        foreach (EpubLocalTextContentFile item in book.ReadingOrder)
        {
            var text = HtmlToText(item.Content);
            if (string.IsNullOrWhiteSpace(text))
                continue;

            var normalized = item.FilePath.Replace('\\', '/');
            var title = titleMap.TryGetValue(normalized, out var navTitle) && !string.IsNullOrWhiteSpace(navTitle)
                ? navTitle
                : GuessTitle(text, index);

            result.Chapters.Add(new BookChapter
            {
                Title = title,
                FilePath = item.FilePath,
                Text = text
            });
            index++;
        }

        if (result.Chapters.Count == 0)
            throw new InvalidDataException("EPUB không có chương văn bản có thể đọc.");

        return result;
    }

    private static void AddNavigation(IEnumerable<EpubNavigationItem> items, Dictionary<string, string> map)
    {
        foreach (var item in items)
        {
            if (item.Link?.ContentFilePath is string path && !string.IsNullOrWhiteSpace(path))
                map[path.Replace('\\', '/')] = item.Title ?? string.Empty;
            if (item.NestedItems is { Count: > 0 })
                AddNavigation(item.NestedItems, map);
        }
    }

    private static string GuessTitle(string text, int index)
    {
        var first = text.Split('\n', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries).FirstOrDefault();
        if (!string.IsNullOrWhiteSpace(first) && first.Length <= 90)
            return first;
        return $"Chương {index:00}";
    }

    private static string HtmlToText(string html)
    {
        if (string.IsNullOrWhiteSpace(html)) return string.Empty;
        var s = Regex.Replace(html, @"<script[\s\S]*?</script>|<style[\s\S]*?</style>", "", RegexOptions.IgnoreCase);
        s = Regex.Replace(s, @"<(br|/p|/div|/h[1-6]|/li|/tr)[^>]*>", "\n", RegexOptions.IgnoreCase);
        s = Regex.Replace(s, @"<[^>]+>", " ");
        s = WebUtility.HtmlDecode(s);
        s = s.Replace("\r", "");
        s = Regex.Replace(s, @"[ \t]+", " ");
        s = Regex.Replace(s, @"\n[ \t]+", "\n");
        s = Regex.Replace(s, @"\n{3,}", "\n\n");
        return s.Trim();
    }
}
