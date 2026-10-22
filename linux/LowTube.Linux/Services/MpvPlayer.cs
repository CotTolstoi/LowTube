using System.Diagnostics;
using System.Net.Sockets;
using System.Text.Json;

namespace LowTube.Linux.Services;

public sealed class MpvPlayer : IAsyncDisposable
{
    private readonly SemaphoreSlim gate = new(1, 1);
    private Process? process;
    private string? socketPath;
    private long requestId;
    private LinuxSettings settings = new();

    public bool IsAvailable => FindExecutable() is not null;
    public bool IsRunning => process is { HasExited: false };

    public void ApplySettings(LinuxSettings value)
    {
        settings = value;
        settings.Normalize();
    }

    public async Task PlayAsync(string url, CancellationToken cancellationToken = default)
    {
        await gate.WaitAsync(cancellationToken);
        try
        {
            if (!IsRunning) await StartAsync(cancellationToken);
            await SendCommandAsync(new object?[] { "loadfile", url, "replace" }, cancellationToken);
        }
        finally { gate.Release(); }
    }

    public Task TogglePauseAsync(CancellationToken cancellationToken = default) => SendSerializedAsync(new object?[] { "cycle", "pause" }, cancellationToken);
    public Task StopAsync(CancellationToken cancellationToken = default) => SendSerializedAsync(new object?[] { "stop" }, cancellationToken);
    public Task SeekAsync(double seconds, CancellationToken cancellationToken = default) => SendSerializedAsync(new object?[] { "seek", seconds, "absolute+keyframes" }, cancellationToken);
    public Task SetVolumeAsync(int volume, CancellationToken cancellationToken = default) => SendSerializedAsync(new object?[] { "set_property", "volume", Math.Clamp(volume, 0, 130) }, cancellationToken);

    public async Task<(double? Position, double? Duration)> GetPlaybackPositionAsync(CancellationToken cancellationToken = default)
    {
        if (!IsRunning) return (null, null);
        await gate.WaitAsync(cancellationToken);
        try
        {
            JsonElement? position = await SendCommandAsync(new object?[] { "get_property", "time-pos" }, cancellationToken);
            JsonElement? duration = await SendCommandAsync(new object?[] { "get_property", "duration" }, cancellationToken);
            return (Number(position), Number(duration));
        }
        finally { gate.Release(); }
    }

    private async Task SendSerializedAsync(object?[] command, CancellationToken cancellationToken)
    {
        await gate.WaitAsync(cancellationToken);
        try { await SendCommandAsync(command, cancellationToken); }
        finally { gate.Release(); }
    }

    private async Task StartAsync(CancellationToken cancellationToken)
    {
        string executable = FindExecutable() ?? throw new InvalidOperationException("mpv is not installed or is missing from PATH.");
        socketPath = Path.Combine(Path.GetTempPath(), "lowtube-" + Guid.NewGuid().ToString("N")[..12] + ".sock");
        var info = new ProcessStartInfo(executable) { UseShellExecute = false };
        string limits = "[height<=" + settings.MaxHeight + "]" + (settings.Fps30 ? "[fps<=?30]" : "") + (settings.H264 ? "[vcodec^=avc1]" : "");
        string quality = "bestvideo" + limits + "+bestaudio/best" + limits + "/best";
        foreach (string arg in new[] { "--idle=yes", "--force-window=yes", "--hwdec=auto-safe", "--ytdl=yes", "--ytdl-format=" + quality,
                     "--cache=yes", "--cache-secs=" + settings.BufferSeconds, "--volume=" + settings.Volume, "--input-ipc-server=" + socketPath })
            info.ArgumentList.Add(arg);

        process = Process.Start(info) ?? throw new InvalidOperationException("Could not start mpv.");
        process.EnableRaisingEvents = true;
        process.Exited += (_, _) => RemoveSocket();
        DateTime deadline = DateTime.UtcNow.AddSeconds(8);
        Exception? lastError = null;
        while (DateTime.UtcNow < deadline && !process.HasExited)
        {
            cancellationToken.ThrowIfCancellationRequested();
            try
            {
                if (File.Exists(socketPath) && OperatingSystem.IsLinux())
                    File.SetUnixFileMode(socketPath, UnixFileMode.UserRead | UnixFileMode.UserWrite);
                await SendCommandAsync(new object?[] { "get_property", "idle-active" }, cancellationToken);
                return;
            }
            catch (SocketException ex) { lastError = ex; }
            catch (IOException ex) { lastError = ex; }
            await Task.Delay(100, cancellationToken);
        }
        string reason = process.HasExited ? $"mpv exited with code {process.ExitCode}." : "mpv IPC socket did not become ready.";
        await DisposeProcessAsync();
        throw new InvalidOperationException(reason, lastError);
    }

    private async Task<JsonElement?> SendCommandAsync(object?[] command, CancellationToken cancellationToken)
    {
        if (string.IsNullOrEmpty(socketPath)) throw new InvalidOperationException("mpv is not running.");
        using var socket = new Socket(AddressFamily.Unix, SocketType.Stream, ProtocolType.Unspecified);
        await socket.ConnectAsync(new UnixDomainSocketEndPoint(socketPath), cancellationToken);
        using var stream = new NetworkStream(socket, ownsSocket: false);
        using var writer = new StreamWriter(stream, leaveOpen: true) { AutoFlush = true };
        using var reader = new StreamReader(stream, leaveOpen: true);
        long id = Interlocked.Increment(ref requestId);
        string request = JsonSerializer.Serialize(new { command, request_id = id });
        await writer.WriteLineAsync(request.AsMemory(), cancellationToken);
        while (await reader.ReadLineAsync(cancellationToken) is { } line)
        {
            using var response = JsonDocument.Parse(line);
            JsonElement root = response.RootElement;
            if (!root.TryGetProperty("request_id", out var responseId) || responseId.GetInt64() != id) continue;
            string error = root.TryGetProperty("error", out var errorValue) ? errorValue.GetString() ?? "unknown error" : "unknown error";
            if (error != "success") throw new InvalidOperationException("mpv command failed: " + error);
            return root.TryGetProperty("data", out var data) ? data.Clone() : null;
        }
        throw new IOException("mpv closed the IPC connection before replying.");
    }

    private static double? Number(JsonElement? value)
    {
        if (!value.HasValue) return null;
        if (value.Value.ValueKind == JsonValueKind.Number && value.Value.TryGetDouble(out double number)) return number;
        return null;
    }

    private async Task DisposeProcessAsync()
    {
        Process? old = process;
        process = null;
        if (old is not null)
        {
            try { if (!old.HasExited) old.Kill(entireProcessTree: true); }
            catch (InvalidOperationException) { }
            catch (System.ComponentModel.Win32Exception) { }
            old.Dispose();
        }
        RemoveSocket();
        await Task.CompletedTask;
    }

    public async ValueTask DisposeAsync()
    {
        await gate.WaitAsync();
        try
        {
            if (IsRunning)
            {
                try { await SendCommandAsync(new object?[] { "quit" }, CancellationToken.None); }
                catch (Exception) { }
            }
            await DisposeProcessAsync();
        }
        finally { gate.Release(); }
    }

    private void RemoveSocket()
    {
        try { if (socketPath is not null && File.Exists(socketPath)) File.Delete(socketPath); }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
    }

    private static string? FindExecutable()
    {
        string? path = Environment.GetEnvironmentVariable("PATH");
        if (string.IsNullOrEmpty(path)) return null;
        foreach (string directory in path.Split(Path.PathSeparator, StringSplitOptions.RemoveEmptyEntries))
        {
            string candidate = Path.Combine(directory, "mpv");
            if (File.Exists(candidate)) return candidate;
        }
        return null;
    }
}
