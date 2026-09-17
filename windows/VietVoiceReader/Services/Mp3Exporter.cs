using NAudio.Wave;
using VietVoiceReader.Models;

namespace VietVoiceReader.Services;

public sealed class Mp3Exporter
{
    private readonly TtsService tts;

    public Mp3Exporter(TtsService tts) => this.tts = tts;

    public async Task ExportChaptersAsync(BookDocument book, IEnumerable<BookChapter> chapters, VoiceDefinition voice, float speed, string outputFolder, IProgress<(double, string)>? progress, CancellationToken ct)
    {
        Directory.CreateDirectory(outputFolder);
        var list = chapters.ToList();
        for (int i = 0; i < list.Count; i++)
        {
            ct.ThrowIfCancellationRequested();
            var chapter = list[i];
            progress?.Report(((double)i / Math.Max(1, list.Count), $"Đang xuất {chapter.Title}"));
            var safe = SafeName($"{i + 1:00} - {chapter.Title}");
            var wav = Path.Combine(Path.GetTempPath(), $"vietvoice-{Guid.NewGuid():N}.wav");
            var mp3 = Path.Combine(outputFolder, safe + ".mp3");
            try
            {
                await tts.SynthesizeToWaveAsync(voice, chapter.Text, speed, wav, ct);
                EncodeMp3(wav, mp3);
            }
            finally
            {
                if (File.Exists(wav)) File.Delete(wav);
            }
        }
        progress?.Report((1, "Xuất MP3 hoàn tất"));
    }

    public async Task ExportWholeBookAsync(BookDocument book, VoiceDefinition voice, float speed, string outputFolder, IProgress<(double, string)>? progress, CancellationToken ct)
    {
        Directory.CreateDirectory(outputFolder);
        var tempWavs = new List<string>();
        var combined = Path.Combine(Path.GetTempPath(), $"vietvoice-book-{Guid.NewGuid():N}.wav");
        try
        {
            for (int i = 0; i < book.Chapters.Count; i++)
            {
                ct.ThrowIfCancellationRequested();
                var chapter = book.Chapters[i];
                progress?.Report(((double)i / Math.Max(1, book.Chapters.Count + 1), $"Đang tổng hợp {chapter.Title}"));
                var wav = Path.Combine(Path.GetTempPath(), $"vietvoice-{Guid.NewGuid():N}.wav");
                tempWavs.Add(wav);
                await tts.SynthesizeToWaveAsync(voice, chapter.Text, speed, wav, ct);
            }

            progress?.Report((0.9, "Đang ghép các chương..."));
            CombineWav(tempWavs, combined);
            var mp3 = Path.Combine(outputFolder, SafeName(book.Title) + ".mp3");
            EncodeMp3(combined, mp3);
            progress?.Report((1, "Đã xuất toàn bộ sách: " + mp3));
        }
        finally
        {
            foreach (var f in tempWavs) if (File.Exists(f)) File.Delete(f);
            if (File.Exists(combined)) File.Delete(combined);
        }
    }

    private static void EncodeMp3(string wavPath, string mp3Path)
    {
        using var reader = new WaveFileReader(wavPath);
        MediaFoundationEncoder.EncodeToMp3(reader, mp3Path, 128000);
    }

    private static void CombineWav(IReadOnlyList<string> files, string output)
    {
        if (files.Count == 0) throw new InvalidOperationException("Không có âm thanh để ghép.");
        using var first = new WaveFileReader(files[0]);
        var format = first.WaveFormat;
        using var writer = new WaveFileWriter(output, format);
        CopyReader(first, writer);
        for (int i = 1; i < files.Count; i++)
        {
            using var reader = new WaveFileReader(files[i]);
            if (!reader.WaveFormat.Equals(format))
                throw new InvalidDataException("Các chương có định dạng WAV khác nhau.");
            CopyReader(reader, writer);
        }
    }

    private static void CopyReader(WaveFileReader reader, WaveFileWriter writer)
    {
        var buffer = new byte[1024 * 128];
        int read;
        while ((read = reader.Read(buffer, 0, buffer.Length)) > 0)
            writer.Write(buffer, 0, read);
    }

    private static string SafeName(string name)
    {
        foreach (var c in Path.GetInvalidFileNameChars()) name = name.Replace(c, '_');
        name = name.Trim();
        return string.IsNullOrWhiteSpace(name) ? "audiobook" : name;
    }
}
