using System.Net.Http;
using SharpCompress.Archives;
using SharpCompress.Common;
using SherpaOnnx;
using VietVoiceReader.Models;

namespace VietVoiceReader.Services;

public sealed class TtsService : IDisposable
{
    private readonly HttpClient http = new(new HttpClientHandler { AllowAutoRedirect = true });
    private readonly string modelsRoot;
    private OfflineTts? cachedTts;
    private string? cachedVoiceId;

    public event Action<double, string>? ProgressChanged;

    public TtsService()
    {
        modelsRoot = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "VietVoiceReader", "Models");
        Directory.CreateDirectory(modelsRoot);
        http.Timeout = TimeSpan.FromMinutes(30);
        http.DefaultRequestHeaders.UserAgent.ParseAdd("VietVoiceReader/1.0");
    }

    public bool IsInstalled(VoiceDefinition voice)
    {
        var folder = VoiceFolder(voice);
        return Directory.Exists(folder)
            && Directory.EnumerateFiles(folder, "*.onnx", SearchOption.AllDirectories).Any()
            && Directory.EnumerateFiles(folder, "tokens.txt", SearchOption.AllDirectories).Any();
    }

    public async Task InstallAsync(VoiceDefinition voice, CancellationToken cancellationToken)
    {
        var folder = VoiceFolder(voice);
        Directory.CreateDirectory(folder);

        if (!string.IsNullOrWhiteSpace(voice.ArchiveUrl))
        {
            var archivePath = Path.Combine(folder, "voice.tar.bz2");
            await DownloadAsync(voice.ArchiveUrl!, archivePath, cancellationToken);
            ProgressChanged?.Invoke(0.93, "Đang giải nén model...");
            await Task.Run(() =>
            {
                ArchiveFactory.WriteToDirectory(
                    archivePath,
                    folder,
                    new ExtractionOptions { ExtractFullPath = true, Overwrite = true });
            }, cancellationToken);
            File.Delete(archivePath);
        }
        else
        {
            if (string.IsNullOrWhiteSpace(voice.ModelUrl) || string.IsNullOrWhiteSpace(voice.TokensUrl))
                throw new InvalidOperationException("Giọng này chưa có nguồn model hợp lệ.");
            await DownloadAsync(voice.ModelUrl!, Path.Combine(folder, voice.ModelFileName), cancellationToken, 0.0, 0.9);
            await DownloadAsync(voice.TokensUrl!, Path.Combine(folder, "tokens.txt"), cancellationToken, 0.9, 0.99);
        }

        if (!IsInstalled(voice))
            throw new InvalidDataException("Tải xong nhưng không tìm thấy model .onnx hoặc tokens.txt.");

        ProgressChanged?.Invoke(1, "Đã cài giọng " + voice.DisplayName);
    }

    private async Task DownloadAsync(string url, string target, CancellationToken ct, double baseProgress = 0, double span = 0.92)
    {
        using var response = await http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, ct);
        response.EnsureSuccessStatusCode();
        var length = response.Content.Headers.ContentLength;
        await using var input = await response.Content.ReadAsStreamAsync(ct);
        await using var output = new FileStream(target, FileMode.Create, FileAccess.Write, FileShare.None, 1024 * 128, true);
        var buffer = new byte[1024 * 128];
        long total = 0;
        while (true)
        {
            int read = await input.ReadAsync(buffer, ct);
            if (read <= 0) break;
            await output.WriteAsync(buffer.AsMemory(0, read), ct);
            total += read;
            if (length is > 0)
                ProgressChanged?.Invoke(baseProgress + span * total / length.Value, $"Đang tải {total / 1024 / 1024} MB / {length.Value / 1024 / 1024} MB");
        }
    }

    public async Task<string> SynthesizeToWaveAsync(VoiceDefinition voice, string text, float speed, string outputPath, CancellationToken ct)
    {
        if (!IsInstalled(voice))
            throw new InvalidOperationException("Hãy tải giọng trước khi đọc.");
        if (string.IsNullOrWhiteSpace(text))
            throw new InvalidOperationException("Không có nội dung để đọc.");

        return await Task.Run(() =>
        {
            ct.ThrowIfCancellationRequested();
            var tts = GetOrCreateTts(voice);
            var gen = new OfflineTtsGenerationConfig { Sid = 0, Speed = speed, SilenceScale = 0.2f };
            var audio = tts.GenerateWithConfig(text, gen, null);
            if (!audio.SaveToWaveFile(outputPath))
                throw new IOException("Không thể ghi WAV từ TTS.");
            return outputPath;
        }, ct);
    }

    private OfflineTts GetOrCreateTts(VoiceDefinition voice)
    {
        if (cachedTts != null && cachedVoiceId == voice.Id)
            return cachedTts;

        cachedTts?.Dispose();
        cachedTts = null;
        cachedVoiceId = null;

        var folder = VoiceFolder(voice);
        var model = Directory.EnumerateFiles(folder, "*.onnx", SearchOption.AllDirectories).First();
        var tokens = Directory.EnumerateFiles(folder, "tokens.txt", SearchOption.AllDirectories).First();
        var espeak = Directory.EnumerateDirectories(folder, "espeak-ng-data", SearchOption.AllDirectories).FirstOrDefault() ?? string.Empty;

        var config = new OfflineTtsConfig();
        config.Model.Vits.Model = model;
        config.Model.Vits.Tokens = tokens;
        config.Model.Vits.DataDir = espeak;
        config.Model.NumThreads = Math.Clamp(Environment.ProcessorCount / 2, 1, 4);
        config.Model.Debug = 0;
        config.Model.Provider = "cpu";
        config.MaxNumSentences = 1;

        cachedTts = new OfflineTts(config);
        cachedVoiceId = voice.Id;
        return cachedTts;
    }

    public void Remove(VoiceDefinition voice)
    {
        if (cachedVoiceId == voice.Id)
        {
            cachedTts?.Dispose();
            cachedTts = null;
            cachedVoiceId = null;
        }
        var folder = VoiceFolder(voice);
        if (Directory.Exists(folder)) Directory.Delete(folder, true);
    }

    private string VoiceFolder(VoiceDefinition voice) => Path.Combine(modelsRoot, voice.Id);

    public void Dispose()
    {
        cachedTts?.Dispose();
        http.Dispose();
    }
}
