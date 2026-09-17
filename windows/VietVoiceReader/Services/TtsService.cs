using System.Net.Http;
using SharpCompress.Archives.Tar;
using SharpCompress.Common;
using SharpCompress.Compressors;
using SharpCompress.Compressors.BZip2;
using SherpaOnnx;
using VietVoiceReader.Models;

namespace VietVoiceReader.Services;

public sealed class TtsService : IDisposable
{
    private const string EspeakArchiveUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/espeak-ng-data.tar.bz2";

    private readonly HttpClient http = new(new HttpClientHandler { AllowAutoRedirect = true });
    private readonly string modelsRoot;
    private readonly string commonRoot;
    private OfflineTts? cachedTts;
    private string? cachedVoiceId;

    public event Action<double, string>? ProgressChanged;

    public TtsService()
    {
        var appRoot = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "VietVoiceReader");
        modelsRoot = Path.Combine(appRoot, "Models");
        commonRoot = Path.Combine(appRoot, "Common");
        Directory.CreateDirectory(modelsRoot);
        Directory.CreateDirectory(commonRoot);
        http.Timeout = TimeSpan.FromMinutes(30);
        http.DefaultRequestHeaders.UserAgent.ParseAdd("VietVoiceReader/1.0.1");
    }

    public bool IsInstalled(VoiceDefinition voice)
    {
        var folder = VoiceFolder(voice);
        bool filesOk = Directory.Exists(folder)
            && Directory.EnumerateFiles(folder, "*.onnx", SearchOption.AllDirectories).Any()
            && Directory.EnumerateFiles(folder, "tokens.txt", SearchOption.AllDirectories).Any();

        if (!filesOk) return false;
        if (!RequiresSharedEspeak(voice)) return true;

        return Directory.Exists(SharedEspeakPath)
            && Directory.EnumerateFiles(SharedEspeakPath, "*", SearchOption.AllDirectories).Any();
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
            await ExtractTarBz2Async(archivePath, folder, cancellationToken);
            File.Delete(archivePath);
        }
        else
        {
            if (string.IsNullOrWhiteSpace(voice.ModelUrl) || string.IsNullOrWhiteSpace(voice.TokensUrl))
                throw new InvalidOperationException("Giọng này chưa có nguồn model hợp lệ.");

            await DownloadAsync(voice.ModelUrl!, Path.Combine(folder, voice.ModelFileName), cancellationToken, 0.0, 0.78);
            await DownloadAsync(voice.TokensUrl!, Path.Combine(folder, "tokens.txt"), cancellationToken, 0.78, 0.04);
            await EnsureSharedEspeakAsync(cancellationToken);
        }

        if (!IsInstalled(voice))
            throw new InvalidDataException("Tải xong nhưng bộ model TTS chưa đầy đủ (model/tokens/espeak-ng-data).");

        ProgressChanged?.Invoke(1, "Đã cài giọng " + voice.DisplayName);
    }

    private async Task EnsureSharedEspeakAsync(CancellationToken cancellationToken)
    {
        if (Directory.Exists(SharedEspeakPath)
            && Directory.EnumerateFiles(SharedEspeakPath, "*", SearchOption.AllDirectories).Any())
            return;

        var archivePath = Path.Combine(commonRoot, "espeak-ng-data.tar.bz2");

        if (File.Exists(archivePath))
        {
            try
            {
                var info = new FileInfo(archivePath);
                if (info.Length < 1024)
                    File.Delete(archivePath);
            }
            catch
            {
            }
        }

        if (!File.Exists(archivePath))
        {
            ProgressChanged?.Invoke(0.83, "Đang tải dữ liệu phát âm tiếng Việt (chỉ tải một lần)...");
            await DownloadAsync(EspeakArchiveUrl, archivePath, cancellationToken, 0.83, 0.12);
        }

        ProgressChanged?.Invoke(0.96, "Đang giải nén dữ liệu phát âm...");
        await ExtractTarBz2Async(archivePath, commonRoot, cancellationToken);

        if (File.Exists(archivePath))
            File.Delete(archivePath);

        if (!Directory.Exists(SharedEspeakPath))
            throw new InvalidDataException("Không tìm thấy espeak-ng-data sau khi giải nén.");
    }

    private static async Task ExtractTarBz2Async(string archivePath, string destination, CancellationToken cancellationToken)
    {
        await Task.Run(() =>
        {
            cancellationToken.ThrowIfCancellationRequested();

            var tarPath = archivePath + ".tmp.tar";
            try
            {
                using (var input = new FileStream(archivePath, FileMode.Open, FileAccess.Read, FileShare.Read))
                using (var bzip = BZip2Stream.Create(input, CompressionMode.Decompress, decompressConcatenated: false))
                using (var tarOutput = new FileStream(tarPath, FileMode.Create, FileAccess.Write, FileShare.None))
                {
                    bzip.CopyTo(tarOutput);
                }

                cancellationToken.ThrowIfCancellationRequested();

                using var archive = TarArchive.OpenArchive(tarPath);
                foreach (var entry in archive.Entries.Where(e => !e.IsDirectory))
                {
                    cancellationToken.ThrowIfCancellationRequested();
                    entry.WriteToDirectory(
                        destination,
                        new ExtractionOptions
                        {
                            ExtractFullPath = true,
                            Overwrite = true
                        });
                }
            }
            catch (InvalidFormatException ex)
            {
                throw new InvalidDataException(
                    "Gói dữ liệu TTS tải về không đúng định dạng .tar.bz2. Hãy thử tải lại giọng.",
                    ex);
            }
            finally
            {
                if (File.Exists(tarPath))
                {
                    try { File.Delete(tarPath); } catch { }
                }
            }
        }, cancellationToken);
    }

    private async Task DownloadAsync(string url, string target, CancellationToken ct, double baseProgress = 0, double span = 0.92)
    {
        using var response = await http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, ct);
        response.EnsureSuccessStatusCode();

        var length = response.Content.Headers.ContentLength;
        var contentType = response.Content.Headers.ContentType?.MediaType ?? string.Empty;

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
                ProgressChanged?.Invoke(
                    baseProgress + span * total / length.Value,
                    $"Đang tải {total / 1024 / 1024} MB / {length.Value / 1024 / 1024} MB");
        }

        await output.FlushAsync(ct);

        if (total == 0)
            throw new InvalidDataException("Máy chủ trả về file rỗng.");

        if (target.EndsWith(".tar.bz2", StringComparison.OrdinalIgnoreCase)
            && (contentType.Contains("text/html", StringComparison.OrdinalIgnoreCase)
                || contentType.Contains("application/json", StringComparison.OrdinalIgnoreCase)))
        {
            try { File.Delete(target); } catch { }
            throw new InvalidDataException("Máy chủ trả về trang lỗi thay vì gói dữ liệu TTS.");
        }
    }

    public async Task<string> SynthesizeToWaveAsync(
        VoiceDefinition voice,
        string text,
        float speed,
        string outputPath,
        CancellationToken ct)
    {
        if (!IsInstalled(voice))
            throw new InvalidOperationException("Hãy tải đầy đủ giọng trước khi đọc.");

        if (string.IsNullOrWhiteSpace(text))
            throw new InvalidOperationException("Không có nội dung để đọc.");

        return await Task.Run(() =>
        {
            ct.ThrowIfCancellationRequested();

            var tts = GetOrCreateTts(voice);
            var gen = new OfflineTtsGenerationConfig
            {
                Sid = 0,
                Speed = speed,
                SilenceScale = 0.2f
            };

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

        var localEspeak = Directory
            .EnumerateDirectories(folder, "espeak-ng-data", SearchOption.AllDirectories)
            .FirstOrDefault();

        var espeak = localEspeak
            ?? (Directory.Exists(SharedEspeakPath) ? SharedEspeakPath : string.Empty);

        if (string.IsNullOrWhiteSpace(espeak))
            throw new InvalidDataException("Thiếu espeak-ng-data cho model TTS.");

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
        if (Directory.Exists(folder))
            Directory.Delete(folder, true);
    }

    private bool RequiresSharedEspeak(VoiceDefinition voice) =>
        string.IsNullOrWhiteSpace(voice.ArchiveUrl);

    private string SharedEspeakPath => Path.Combine(commonRoot, "espeak-ng-data");

    private string VoiceFolder(VoiceDefinition voice) =>
        Path.Combine(modelsRoot, voice.Id);

    public void Dispose()
    {
        cachedTts?.Dispose();
        http.Dispose();
    }
}
