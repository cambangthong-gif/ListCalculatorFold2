using System.Text.Json;
using VietVoiceReader.Models;

namespace VietVoiceReader.Services;

public sealed class SettingsStore
{
    private readonly string path;
    private Dictionary<string, BookDisplaySettings> data = new(StringComparer.OrdinalIgnoreCase);

    public SettingsStore()
    {
        var dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "VietVoiceReader");
        Directory.CreateDirectory(dir);
        path = Path.Combine(dir, "reader-settings.json");
        Load();
    }

    public BookDisplaySettings Get(string bookPath)
    {
        if (data.TryGetValue(bookPath, out var value)) return value;
        value = new BookDisplaySettings();
        data[bookPath] = value;
        return value;
    }

    public void Save(string bookPath, BookDisplaySettings settings)
    {
        data[bookPath] = settings;
        try
        {
            File.WriteAllText(path, JsonSerializer.Serialize(data, new JsonSerializerOptions { WriteIndented = true }));
        }
        catch
        {
        }
    }

    private void Load()
    {
        try
        {
            if (!File.Exists(path)) return;
            data = JsonSerializer.Deserialize<Dictionary<string, BookDisplaySettings>>(File.ReadAllText(path))
                   ?? new Dictionary<string, BookDisplaySettings>(StringComparer.OrdinalIgnoreCase);
        }
        catch
        {
            data = new Dictionary<string, BookDisplaySettings>(StringComparer.OrdinalIgnoreCase);
        }
    }
}
