using System.Text.Json;

namespace LowTube.Linux.Services;

public sealed class LocalLibrary
{
    private static readonly JsonSerializerOptions JsonOptions = new() { WriteIndented = true, PropertyNameCaseInsensitive = true, MaxDepth = 32 };
    private readonly string filePath;

    public List<MediaItem> History { get; private set; } = new();
    public List<MediaItem> Saved { get; private set; } = new();
    public List<string> Subscriptions { get; private set; } = new();
    public List<MediaItem> Queue { get; private set; } = new();
    public Dictionary<string, List<MediaItem>> Playlists { get; private set; } = new(StringComparer.OrdinalIgnoreCase);
    private Dictionary<string, double> resumePositions = new(StringComparer.Ordinal);

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

    public void Enqueue(MediaItem item)
    {
        if (!IsSafeItem(item) || !string.Equals(item.Kind, "Video", StringComparison.Ordinal)) throw new ArgumentException("Only standard YouTube videos can be queued.");
        if (Queue.Count >= 500) throw new InvalidOperationException("The queue is full.");
        Queue.Add(item);
        Save();
    }

    public MediaItem? TakeNextQueued()
    {
        if (Queue.Count == 0) return null;
        MediaItem item = Queue[0];
        Queue.RemoveAt(0);
        Save();
        return item;
    }

    public void ClearQueue()
    {
        Queue.Clear();
        Save();
    }

    public bool RemoveQueued(string url)
    {
        int index = Queue.FindIndex(x => string.Equals(x.Url, url, StringComparison.Ordinal));
        if (index < 0) return false;
        Queue.RemoveAt(index);
        Save();
        return true;
    }

    public double GetResumePosition(string url) => resumePositions.TryGetValue(url, out double value) ? value : 0;

    public void SaveResumePosition(MediaItem item, double position, double? duration)
    {
        if (!IsSafeItem(item) || !double.IsFinite(position) || position < 0) return;
        if (duration.HasValue && (position >= duration.Value - 30 || position < 5)) resumePositions.Remove(item.Url);
        else resumePositions[item.Url] = Math.Min(position, 30 * 24 * 60 * 60);
        if (resumePositions.Count > 500)
            foreach (string key in resumePositions.Keys.Take(resumePositions.Count - 500).ToArray()) resumePositions.Remove(key);
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

    public void CreatePlaylist(string name, IEnumerable<MediaItem> items)
    {
        name = (name ?? "").Trim();
        if (name.Length is < 1 or > 100) throw new ArgumentException("Use a playlist name of 1–100 characters.");
        if (!Playlists.ContainsKey(name) && Playlists.Count >= 200) throw new InvalidOperationException("Up to 200 local playlists are supported.");
        var snapshot = items.Where(x => IsSafeItem(x) && string.Equals(x.Kind, "Video", StringComparison.Ordinal)).DistinctBy(x => x.Url, StringComparer.Ordinal).Take(501).ToList();
        if (snapshot.Count == 0 || snapshot.Count > 500) throw new ArgumentException("A playlist must contain 1–500 valid videos.");
        Playlists[name] = snapshot;
        Save();
    }

    public void RemovePlaylist(string name)
    {
        if (Playlists.Remove(name)) Save();
    }

    public void ExportTo(string path)
    {
        File.WriteAllText(path, ExportJson());
    }

    public string ExportJson()
    {
        string json = JsonSerializer.Serialize(ToData(), JsonOptions);
        if (json.Length > 4 * 1024 * 1024) throw new InvalidOperationException("The library export exceeds 4 MiB.");
        return json;
    }

    public void ImportFrom(string path)
    {
        var info = new FileInfo(path);
        if (!info.Exists || info.Length is < 1 or > 4 * 1024 * 1024) throw new InvalidDataException("The library file is empty or exceeds 4 MiB.");
        ImportJson(File.ReadAllText(path));
    }

    public void ImportJson(string json)
    {
        if (string.IsNullOrWhiteSpace(json) || json.Length > 4 * 1024 * 1024) throw new InvalidDataException("The library file is empty or exceeds 4 MiB.");
        LibraryData imported;
        try { imported = JsonSerializer.Deserialize<LibraryData>(json, JsonOptions) ?? throw new InvalidDataException("The library file is empty."); }
        catch (JsonException ex) { throw new InvalidDataException("The library file is not valid LowTube JSON.", ex); }
        if (imported.History is null || imported.Saved is null || imported.Queue is null || imported.Subscriptions is null || imported.Playlists is null || imported.ResumePositions is null ||
            imported.Version != 1 || imported.History.Count > 500 || imported.Saved.Count > 200 || imported.Queue.Count > 500 ||
            imported.Subscriptions.Count > 100 || imported.Playlists.Count > 200 || imported.ResumePositions.Count > 500)
            throw new InvalidDataException("The library format version or item limits are invalid.");
        if (imported.History.Any(x => !IsSafeItem(x)) || imported.Saved.Any(x => !IsSafeItem(x)) || imported.Queue.Any(x => !IsSafeItem(x)) ||
            imported.Subscriptions.Any(x => !IsSafeChannel(x)) || imported.ResumePositions.Any(x => x is null || !YouTubeCatalog.IsSafeYoutubeUrl(x.Url) || !double.IsFinite(x.Seconds) || x.Seconds < 5) ||
            imported.Playlists.Any(x => x is null || x.Name is null || x.Items is null || x.Name.Length is < 1 or > 100 || x.Items.Count is < 1 or > 500 || x.Items.Any(item => !IsSafeItem(item))))
            throw new InvalidDataException("The library contains an invalid URL or item.");

        var mergedHistory = Merge(imported.History, History, 500);
        var mergedSaved = Merge(imported.Saved, Saved, 200);
        var mergedQueue = Merge(Queue, imported.Queue, 500);
        var mergedSubscriptions = imported.Subscriptions.Concat(Subscriptions).Distinct(StringComparer.OrdinalIgnoreCase).Take(100).ToList();
        var mergedPlaylists = new Dictionary<string, List<MediaItem>>(Playlists, StringComparer.OrdinalIgnoreCase);
        foreach (var playlist in imported.Playlists)
            mergedPlaylists[playlist.Name] = mergedPlaylists.TryGetValue(playlist.Name, out var existing)
                ? playlist.Items.Concat(existing).DistinctBy(x => x.Url, StringComparer.Ordinal).Take(500).ToList()
                : playlist.Items;
        var mergedResume = new Dictionary<string, double>(resumePositions, StringComparer.Ordinal);
        foreach (var entry in imported.ResumePositions) mergedResume[entry.Url] = entry.Seconds;
        if (mergedPlaylists.Count > 200 || mergedResume.Count > 500) throw new InvalidDataException("The merged library exceeds its item limits.");
        var data = new LibraryData { Version = 1, History = mergedHistory, Saved = mergedSaved, Queue = mergedQueue,
            Subscriptions = mergedSubscriptions, Playlists = mergedPlaylists.Select(x => new LocalPlaylist(x.Key, x.Value)).ToList(),
            ResumePositions = mergedResume.Select(x => new ResumeEntry(x.Key, x.Value)).ToList() };
        WriteData(data);
        History = mergedHistory;
        Saved = mergedSaved;
        Queue = mergedQueue;
        Subscriptions = mergedSubscriptions;
        Playlists = mergedPlaylists;
        resumePositions = mergedResume;
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
            Queue = (data?.Queue ?? new()).Where(IsSafeItem).Take(500).ToList();
            Playlists = (data?.Playlists ?? new()).Where(x => x is not null && x.Name is { Length: > 0 and <= 100 } && x.Items is not null)
                .GroupBy(x => x.Name, StringComparer.OrdinalIgnoreCase).Take(200)
                .ToDictionary(x => x.Key, x => x.First().Items.Where(IsSafeItem).Take(500).ToList(), StringComparer.OrdinalIgnoreCase);
            resumePositions = (data?.ResumePositions ?? new())
                .Where(x => YouTubeCatalog.IsSafeYoutubeUrl(x.Url) && double.IsFinite(x.Seconds) && x.Seconds >= 5)
                .Take(500).GroupBy(x => x.Url, StringComparer.Ordinal).ToDictionary(x => x.Key, x => x.First().Seconds, StringComparer.Ordinal);
        }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
        catch (JsonException) { }
    }

    private void Save()
    {
        WriteData(ToData());
    }

    private void WriteData(LibraryData data)
    {
        string? directory = Path.GetDirectoryName(filePath);
        if (directory is null) throw new IOException("Could not determine the library directory.");
        Directory.CreateDirectory(directory);
        string temporary = filePath + ".tmp";
        File.WriteAllText(temporary, JsonSerializer.Serialize(data, JsonOptions));
        if (OperatingSystem.IsLinux()) File.SetUnixFileMode(temporary, UnixFileMode.UserRead | UnixFileMode.UserWrite);
        File.Move(temporary, filePath, overwrite: true);
    }

    private static bool IsSafeItem(MediaItem? item) => item is not null &&
        (item.Id?.Length ?? 0) <= 256 && (item.Title?.Length ?? 0) <= 2048 && (item.Channel?.Length ?? 0) <= 512 &&
        (item.Duration?.Length ?? 0) <= 64 && (item.Kind?.Length ?? 0) <= 32 && YouTubeCatalog.IsSafeYoutubeUrl(item.Url);

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

    private LibraryData ToData() => new() { Version = 1, History = History, Saved = Saved, Subscriptions = Subscriptions, Queue = Queue,
        Playlists = Playlists.Select(x => new LocalPlaylist(x.Key, x.Value)).ToList(), ResumePositions = resumePositions.Select(x => new ResumeEntry(x.Key, x.Value)).ToList() };

    private static List<MediaItem> Merge(IEnumerable<MediaItem> first, IEnumerable<MediaItem> second, int limit) =>
        first.Concat(second).DistinctBy(x => x.Url, StringComparer.Ordinal).Take(limit).ToList();

    private sealed class LibraryData
    {
        public int Version { get; set; } = 1;
        public List<MediaItem> History { get; set; } = new();
        public List<MediaItem> Saved { get; set; } = new();
        public List<string> Subscriptions { get; set; } = new();
        public List<MediaItem> Queue { get; set; } = new();
        public List<LocalPlaylist> Playlists { get; set; } = new();
        public List<ResumeEntry> ResumePositions { get; set; } = new();
    }

    private sealed record ResumeEntry(string Url, double Seconds);
    private sealed record LocalPlaylist(string Name, List<MediaItem> Items);
}
