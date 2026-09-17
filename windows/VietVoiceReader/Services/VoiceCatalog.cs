using VietVoiceReader.Models;

namespace VietVoiceReader.Services;

public static class VoiceCatalog
{
    private const string Nghitts = "https://huggingface.co/doof-ferb/nghitts-copy/resolve/main/sherpa-onnx/";
    private const string SharedTokens = Nghitts + "tokens.txt?download=true";

    public static IReadOnlyList<VoiceDefinition> Voices { get; } = new List<VoiceDefinition>
    {
        new()
        {
            Id = "vais1000",
            DisplayName = "VAIS1000 (Piper chính thức)",
            Source = "Sherpa-ONNX / Piper",
            ArchiveUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-vi_VN-vais1000-medium.tar.bz2"
        },
        Direct("ngocngan", "Ngọc Ngạn", "ngocngan3701.onnx"),
        Direct("ngochuyen", "Ngọc Huyền", "ngochuyen.onnx"),
        Direct("duyoryx", "Duy Oryx", "duyoryx3175.onnx"),
        Direct("banmai", "Ban Mai", "banmai.onnx"),
        Direct("lacphi", "Lạc Phi", "lacphi.onnx"),
        Direct("maiphuong", "Mai Phương", "maiphuong.onnx"),
        Direct("manhdung", "Mạnh Dũng", "manhdung.onnx"),
        Direct("minhkhang", "Minh Khang", "minhkhang.onnx"),
        Direct("minhthu", "Minh Thu", "minhthu.onnx"),
        Direct("phuongtrang", "Phương Trang", "phuongtrang.onnx"),
        Direct("taian", "Tài An", "taian2.onnx"),
        Direct("thanhphuong", "Thanh Phương", "thanhphuong2.onnx"),
        Direct("thientam", "Thiện Tâm", "thientam.onnx"),
        Direct("vietthao", "Việt Thảo", "vietthao3886.onnx")
    };

    private static VoiceDefinition Direct(string id, string name, string file) => new()
    {
        Id = id,
        DisplayName = name,
        Source = "NGHI-TTS / Sherpa-ONNX",
        ModelUrl = Nghitts + file + "?download=true",
        TokensUrl = SharedTokens,
        ModelFileName = file
    };
}
