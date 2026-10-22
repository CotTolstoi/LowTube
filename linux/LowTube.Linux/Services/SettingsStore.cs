using System.Text.Json;

namespace LowTube.Linux.Services;

public sealed class SettingsStore
{
    private static readonly JsonSerializerOptions JsonOptions = new() { WriteIndented = true, PropertyNameCaseInsensitive = true };
    public string FilePath { get; }

    public SettingsStore()
    {
        string? configuredRoot = Environment.GetEnvironmentVariable("XDG_CONFIG_HOME");
        string root = !string.IsNullOrWhiteSpace(configuredRoot) && Path.IsPathRooted(configuredRoot)
            ? configuredRoot
            : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".config");
        FilePath = Path.Combine(root, "lowtube", "settings.json");
    }

    public LinuxSettings Load()
    {
        try
        {
            var info = new FileInfo(FilePath);
            if (!info.Exists || info.Length > 1024 * 1024) return new LinuxSettings();
            var value = JsonSerializer.Deserialize<LinuxSettings>(File.ReadAllText(FilePath), JsonOptions) ?? new LinuxSettings();
            value.Normalize();
            return value;
        }
        catch (IOException) { return new LinuxSettings(); }
        catch (UnauthorizedAccessException) { return new LinuxSettings(); }
        catch (JsonException) { return new LinuxSettings(); }
    }

    public void Save(LinuxSettings settings)
    {
        settings.Normalize();
        string? directory = Path.GetDirectoryName(FilePath);
        if (directory is null) throw new IOException("Could not determine the settings directory.");
        Directory.CreateDirectory(directory);
        string temporary = FilePath + ".tmp";
        File.WriteAllText(temporary, JsonSerializer.Serialize(settings, JsonOptions));
        if (OperatingSystem.IsLinux()) File.SetUnixFileMode(temporary, UnixFileMode.UserRead | UnixFileMode.UserWrite);
        File.Move(temporary, FilePath, overwrite: true);
    }
}
