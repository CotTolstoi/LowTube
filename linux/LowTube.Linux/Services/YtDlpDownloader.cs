using System.Diagnostics;
using System.Text.RegularExpressions;

namespace LowTube.Linux.Services;

public sealed record DownloadProgress(double? Percent, string Message);

public sealed class YtDlpDownloader
{
    private static readonly Regex PercentPattern = new(@"(?:^|\s)(\d{1,3}(?:\.\d+)?)%", RegexOptions.Compiled | RegexOptions.CultureInvariant);

    public async Task DownloadAsync(MediaItem item, LinuxSettings settings, IProgress<DownloadProgress>? progress, CancellationToken cancellationToken)
    {
        string executable = FindExecutable() ?? throw new InvalidOperationException("yt-dlp is not installed or is missing from PATH.");
        settings.Normalize();
        Directory.CreateDirectory(settings.DownloadFolder);
        string format = BuildFormat(settings);
        var info = new ProcessStartInfo(executable)
        {
            UseShellExecute = false,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            CreateNoWindow = true
        };
        foreach (string arg in new[] { "--ignore-config", "--no-warnings", "--no-color", "--newline", "--no-overwrites", "--format", format,
                     "--paths", "home:" + settings.DownloadFolder, "--output", "%(title).120B [%(id)s].%(ext)s", "--no-playlist", item.Url })
            info.ArgumentList.Add(arg);

        using var process = new Process { StartInfo = info };
        if (!process.Start()) throw new InvalidOperationException("Could not start yt-dlp.");
        using var registration = cancellationToken.Register(() => Kill(process));
        Task<string> stderrTask = process.StandardError.ReadToEndAsync(cancellationToken);
        try
        {
            while (await process.StandardOutput.ReadLineAsync(cancellationToken) is { } line)
            {
                var match = PercentPattern.Match(line);
                double? percent = match.Success && double.TryParse(match.Groups[1].Value, System.Globalization.NumberStyles.Float,
                    System.Globalization.CultureInfo.InvariantCulture, out double value) ? value : null;
                progress?.Report(new DownloadProgress(percent, percent.HasValue ? $"Downloading {percent.Value:0.#}%" : "Downloading…"));
            }
            await process.WaitForExitAsync(cancellationToken);
            string error = await stderrTask;
            if (process.ExitCode != 0)
                throw new InvalidOperationException(ScrubError(error));
            progress?.Report(new DownloadProgress(100, "Download complete"));
        }
        catch (OperationCanceledException)
        {
            Kill(process);
            try { await process.WaitForExitAsync(); } catch (InvalidOperationException) { }
            throw;
        }
        catch
        {
            if (!process.HasExited) Kill(process);
            try { await process.WaitForExitAsync(); } catch (InvalidOperationException) { }
            throw;
        }
    }

    public static string BuildFormat(LinuxSettings settings)
    {
        string filters = "[height<=" + settings.MaxHeight + "]" +
            (settings.Fps30 ? "[fps<=?30]" : "") +
            (settings.H264 ? "[vcodec^=avc1]" : "");
        return "bv" + filters + "+ba[ext=m4a]/bv" + filters + "+ba/b" + filters;
    }

    private static string? FindExecutable()
    {
        string? path = Environment.GetEnvironmentVariable("PATH");
        if (string.IsNullOrEmpty(path)) return null;
        foreach (string directory in path.Split(Path.PathSeparator, StringSplitOptions.RemoveEmptyEntries))
        {
            string candidate = Path.Combine(directory, "yt-dlp");
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

    private static string ScrubError(string value)
    {
        string clean = Regex.Replace(value ?? "", @"https?://[^\s]+", "[YouTube URL]").Trim();
        if (clean.Length == 0) return "yt-dlp download failed.";
        return clean.Length > 500 ? clean[..500] : clean;
    }
}
