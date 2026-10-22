using System.Text.Json;

namespace LowTube.Linux.Services;

public sealed class LocalLibrary
{
    private static readonly JsonSerializerOptions JsonOptions = new() { WriteIndented = true, PropertyNameCaseInsensitive = true, MaxDepth = 32 };
    private readonly string filePath;

    public List<MediaItem> History { get; private set; } = new();
    public List<MediaItem> Saved { get; private set; } = new();
    public List<string> Subscriptions { get; private set; } = new();

    public LocalLibrary()
    {
        string? dataHome = Environment.GetEnvironmentVariable("XDG_DATA_HOME");
        string root = !string.IsNullOrWhiteSpace(dataHome) && Path.IsPathRooted(dataHome)
            ? dataHome
            : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".local", "share");
        filePath = Path.Combine(root, "lowtube", "library.json");
        Load();
    }

    public void AddToHistory(MediaItem item)
    {
        History.RemoveAll(x => x.Url == item.Url);
        History.Insert(0, item);
        if (History.Count > 500) History.RemoveRange(500, History.Count - 500);
        Save();
    }

    public bool ToggleSaved(MediaItem item)
    {
        int index = Saved.FindIndex(x => x.Url == item.Url);
        if (index >= 0) Saved.RemoveAt(index);
        else Saved.Insert(0, item);
        if (Saved.Count > 200) Saved.RemoveRange(200, Saved.Count - 200);
        Save();
        return index < 0;
    }

    public void AddSubscription(string url)
    {
        string target = YouTubeCatalog.BuildTarget(url);
        if (!Uri.TryCreate(target, UriKind.Absolute, out var uri)) throw new ArgumentException("Enter a YouTube channel URL.");
        string path = uri.AbsolutePath;
        if (!(path.StartsWith("/@", StringComparison.Ordinal) || path.StartsWith("/channel/", StringComparison.Ordinal) ||
              path.StartsWith("/c/", StringComparison.Ordinal) || path.StartsWith("/user/", StringComparison.Ordinal)))
            throw new ArgumentException("Enter a YouTube channel URL.");
        if (!Subscriptions.Contains(target, StringComparer.OrdinalIgnoreCase)) Subscriptions.Add(target);
        if (Subscriptions.Count > 100) Subscriptions.RemoveRange(100, Subscriptions.Count - 100);
        Save();
    }

    public void RemoveSubscription(string url)
    {
        Subscriptions.RemoveAll(x => string.Equals(x, url, StringComparison.OrdinalIgnoreCase));
        Save();
    }

    private void Load()
    {
        try
        {
            var info = new FileInfo(filePath);
            if (!info.Exists || info.Length > 4 * 1024 * 1024) return;
            var data = JsonSerializer.Deserialize<LibraryData>(File.ReadAllText(filePath), JsonOptions);
            History = (data?.History ?? new()).Where(IsSafeItem).Take(500).ToList();
            Saved = (data?.Saved ?? new()).Where(IsSafeItem).Take(200).ToList();
            Subscriptions = (data?.Subscriptions ?? new()).Where(IsSafeChannel).Distinct(StringComparer.OrdinalIgnoreCase).Take(100).ToList();
        }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
        catch (JsonException) { }
    }

    private void Save()
    {
        string? directory = Path.GetDirectoryName(filePath);
        if (directory is null) throw new IOException("Could not determine the library directory.");
        Directory.CreateDirectory(directory);
        string temporary = filePath + ".tmp";
        File.WriteAllText(temporary, JsonSerializer.Serialize(new LibraryData(History, Saved, Subscriptions), JsonOptions));
        if (OperatingSystem.IsLinux()) File.SetUnixFileMode(temporary, UnixFileMode.UserRead | UnixFileMode.UserWrite);
        File.Move(temporary, filePath, overwrite: true);
    }

    private static bool IsSafeItem(MediaItem? item) => item is not null && (item.Title?.Length ?? 0) <= 2048 && YouTubeCatalog.IsSafeYoutubeUrl(item.Url);

    private static bool IsSafeChannel(string value)
    {
        try
        {
            string target = YouTubeCatalog.BuildTarget(value);
            return target == value && Uri.TryCreate(target, UriKind.Absolute, out var uri) &&
                (uri.AbsolutePath.StartsWith("/@", StringComparison.Ordinal) || uri.AbsolutePath.StartsWith("/channel/", StringComparison.Ordinal) ||
                 uri.AbsolutePath.StartsWith("/c/", StringComparison.Ordinal) || uri.AbsolutePath.StartsWith("/user/", StringComparison.Ordinal));
        }
        catch (ArgumentException) { return false; }
    }

    private sealed record LibraryData(List<MediaItem> History, List<MediaItem> Saved, List<string> Subscriptions);
}
