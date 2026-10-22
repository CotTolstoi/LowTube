using System.Diagnostics;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace LowTube.Linux.Services;

public sealed class YouTubeCatalog
{
    private static readonly Regex VideoIdPattern = new("^[A-Za-z0-9_-]{11}$", RegexOptions.Compiled | RegexOptions.CultureInvariant);

    public bool IsAvailable => FindExecutable("yt-dlp") is not null;

    public async Task<IReadOnlyList<MediaItem>> SearchAsync(string query, CancellationToken cancellationToken)
    {
        string target = BuildTarget(query);
        string executable = FindExecutable("yt-dlp") ?? throw new InvalidOperationException("yt-dlp is not installed or is missing from PATH.");
        var info = new ProcessStartInfo(executable)
        {
            UseShellExecute = false,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            CreateNoWindow = true
        };
        foreach (string arg in new[] { "--ignore-config", "--no-warnings", "--no-progress", "--flat-playlist", "--dump-single-json", "--skip-download", "--playlist-end", "25", target })
            info.ArgumentList.Add(arg);

        using var process = new Process { StartInfo = info, EnableRaisingEvents = true };
        if (!process.Start()) throw new InvalidOperationException("Could not start yt-dlp.");
        using var registration = cancellationToken.Register(() => Kill(process));
        Task<string> stdoutTask = ReadBoundedAsync(process.StandardOutput, 16 * 1024 * 1024, process, cancellationToken);
        Task<string> stderrTask = ReadBoundedAsync(process.StandardError, 1024 * 1024, process, cancellationToken);
        string stdout;
        string stderr;
        try
        {
            await Task.WhenAll(stdoutTask, stderrTask, process.WaitForExitAsync(cancellationToken));
            stdout = await stdoutTask;
            stderr = await stderrTask;
        }
        catch (OperationCanceledException)
        {
            Kill(process);
            try { await process.WaitForExitAsync(); } catch (InvalidOperationException) { }
            throw;
        }
        catch
        {
            Kill(process);
            try { await process.WaitForExitAsync(); } catch (InvalidOperationException) { }
            throw;
        }
        if (process.ExitCode != 0)
        {
            string detail = ScrubError(stderr);
            throw new InvalidOperationException(string.IsNullOrWhiteSpace(detail) ? "yt-dlp search failed." : detail);
        }

        return ParseResults(stdout);
    }

    public static string BuildTarget(string query)
    {
        string value = query.Trim();
        if (value.Length == 0) throw new ArgumentException("Enter a search phrase or YouTube link.", nameof(query));
        if (value.Length > 2048) throw new ArgumentException("Search text is too long.", nameof(query));
        if (VideoIdPattern.IsMatch(value)) return "https://www.youtube.com/watch?v=" + value;
        if (Uri.TryCreate(value, UriKind.Absolute, out var uri))
        {
            string host = uri.IdnHost.ToLowerInvariant();
            if ((uri.Scheme != Uri.UriSchemeHttps && uri.Scheme != Uri.UriSchemeHttp) || !string.IsNullOrEmpty(uri.UserInfo) || !uri.IsDefaultPort ||
                (host != "youtube.com" && !host.EndsWith(".youtube.com", StringComparison.Ordinal) && host != "youtu.be"))
                throw new ArgumentException("Only standard YouTube links are supported.", nameof(query));
            return new UriBuilder(uri) { Scheme = Uri.UriSchemeHttps, Port = -1, Fragment = "" }.Uri.AbsoluteUri;
        }
        return "ytsearch25:" + value;
    }

    public static IReadOnlyList<MediaItem> ParseResults(string json)
    {
        using var document = JsonDocument.Parse(json, new JsonDocumentOptions { MaxDepth = 100 });
        JsonElement root = document.RootElement;
        JsonElement entries;
        if (root.ValueKind == JsonValueKind.Object && root.TryGetProperty("entries", out var value) && value.ValueKind == JsonValueKind.Array)
            entries = value;
        else if (root.ValueKind == JsonValueKind.Object)
        {
            var single = ParseEntry(root);
            return single is null ? Array.Empty<MediaItem>() : new[] { single };
        }
        else
            return Array.Empty<MediaItem>();
        var results = new List<MediaItem>();
        foreach (var entry in entries.EnumerateArray())
        {
            var item = ParseEntry(entry);
            if (item is not null) results.Add(item);
        }
        return results;
    }

    private static MediaItem? ParseEntry(JsonElement entry)
    {
        if (entry.ValueKind != JsonValueKind.Object) return null;
        string id = Text(entry, "id");
        string kind = Text(entry, "_type");
        string url = Text(entry, "webpage_url");
        if (url.Length == 0) url = Text(entry, "original_url");
        if (url.Length == 0 && id.Length > 0)
            url = kind.Contains("playlist", StringComparison.OrdinalIgnoreCase)
                ? "https://www.youtube.com/playlist?list=" + Uri.EscapeDataString(id)
                : VideoIdPattern.IsMatch(id) ? "https://www.youtube.com/watch?v=" + id : "";
        url = NormalizeYoutubeUrl(url);
        if (url.Length == 0) return null;
        double seconds = Number(entry, "duration");
        string duration = seconds > 0 ? TimeSpan.FromSeconds(seconds).ToString(seconds >= 3600 ? @"h\:mm\:ss" : @"m\:ss") : "";
        return new MediaItem(id, Text(entry, "title", "Untitled"), Text(entry, "channel", Text(entry, "uploader")), duration, url, kind.Contains("playlist", StringComparison.OrdinalIgnoreCase) ? "Playlist" : "Video");
    }

    private static string Text(JsonElement element, string key, string fallback = "") =>
        element.TryGetProperty(key, out var value) && value.ValueKind == JsonValueKind.String ? value.GetString() ?? fallback : fallback;

    private static double Number(JsonElement element, string key) =>
        element.TryGetProperty(key, out var value) && value.TryGetDouble(out double number) ? number : 0;

    private static string NormalizeYoutubeUrl(string value)
    {
        if (!Uri.TryCreate(value, UriKind.Absolute, out var uri)) return "";
        string host = uri.IdnHost.ToLowerInvariant();
        if ((uri.Scheme != Uri.UriSchemeHttps && uri.Scheme != Uri.UriSchemeHttp) || !string.IsNullOrEmpty(uri.UserInfo) || !uri.IsDefaultPort ||
            (host != "youtube.com" && !host.EndsWith(".youtube.com", StringComparison.Ordinal) && host != "youtu.be")) return "";
        return new UriBuilder(uri) { Scheme = Uri.UriSchemeHttps, Port = -1, Fragment = "" }.Uri.AbsoluteUri;
    }

    public static bool IsSafeYoutubeUrl(string value) => !string.IsNullOrEmpty(value) && NormalizeYoutubeUrl(value).Length > 0;

    private static string? FindExecutable(string name)
    {
        string? path = Environment.GetEnvironmentVariable("PATH");
        if (string.IsNullOrEmpty(path)) return null;
        foreach (string directory in path.Split(Path.PathSeparator, StringSplitOptions.RemoveEmptyEntries))
        {
            string candidate = Path.Combine(directory, name);
            if (File.Exists(candidate)) return candidate;
        }
        return null;
    }

    private static void Kill(Process process)
    {
        try { if (!process.HasExited) process.Kill(entireProcessTree: true); }
        catch (InvalidOperationException) { }
        catch (System.ComponentModel.Win32Exception) { }
    }

    private static async Task<string> ReadBoundedAsync(StreamReader reader, int limit, Process owner, CancellationToken cancellationToken)
    {
        var result = new StringBuilder();
        char[] buffer = new char[8192];
        while (true)
        {
            int read = await reader.ReadAsync(buffer.AsMemory(), cancellationToken);
            if (read == 0) return result.ToString();
            if (result.Length + read > limit)
            {
                Kill(owner);
                throw new InvalidOperationException("yt-dlp returned more data than LowTube allows.");
            }
            result.Append(buffer, 0, read);
        }
    }

    private static string ScrubError(string text)
    {
        string clean = Regex.Replace(text ?? "", @"https?://[^\s]+", "[YouTube URL]");
        return clean.Length > 500 ? clean[..500] : clean.Trim();
    }
}
