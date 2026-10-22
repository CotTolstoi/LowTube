using System.Reflection;
using System.Runtime.Versioning;
using System.Text.Json;
using LowTube.Linux.Services;

[assembly: SupportedOSPlatform("linux")]

int passed = 0;
void Check(bool value, string name)
{
    if (!value) throw new InvalidOperationException("FAIL: " + name);
    passed++;
    Console.WriteLine("PASS: " + name);
}

string root = Path.Combine(Path.GetTempPath(), "lowtube-linux-tests-" + Guid.NewGuid().ToString("N"));
string bin = Path.Combine(root, "bin");
Directory.CreateDirectory(bin);
Environment.SetEnvironmentVariable("XDG_CONFIG_HOME", Path.Combine(root, "config"));
Environment.SetEnvironmentVariable("XDG_DATA_HOME", Path.Combine(root, "data"));
string argLog = Path.Combine(root, "yt-dlp-args.txt");
Environment.SetEnvironmentVariable("LOWTUBE_TEST_ARGS", argLog);
string oldPath = Environment.GetEnvironmentVariable("PATH") ?? "";
Environment.SetEnvironmentVariable("PATH", bin + Path.PathSeparator + oldPath);
try
{
    var item = new MediaItem("abcdefghijk", "Demo", "Channel", "5:00", "https://www.youtube.com/watch?v=abcdefghijk", "Video");
    var library = new LocalLibrary();
    library.Enqueue(item);
    library.AddToHistory(item);
    library.SaveResumePosition(item, 125, 300);
    library.CreatePlaylist("Favorites", new[] { item });
    var loaded = new LocalLibrary();
    Check(loaded.Queue.Count == 1 && loaded.History.Count == 1, "queue and history persist");
    Check(loaded.GetResumePosition(item.Url) == 125 && loaded.Playlists["Favorites"].Count == 1, "resume point and playlist persist");
    loaded.SaveResumePosition(item, 280, 300);
    Check(loaded.GetResumePosition(item.Url) == 0, "completed playback clears resume point");

    string export = loaded.ExportJson();
    var imported = new LocalLibrary();
    imported.CreatePlaylist("Favorites", new[] { item with { Id = "lmnopqrstuv", Title = "Other", Url = "https://www.youtube.com/watch?v=lmnopqrstuv" } });
    imported.ImportJson(export);
    Check(imported.Playlists["Favorites"].Count == 2 && imported.Queue.Count == 1, "versioned library export merges playlist items");
    try { imported.ImportJson("{\"version\":1,\"history\":[{\"id\":\"bad\",\"title\":\"Bad\",\"channel\":\"\",\"duration\":\"\",\"url\":\"https://evil.example/\",\"kind\":\"Video\"}]}"); throw new Exception("Unsafe import accepted"); }
    catch (InvalidDataException) { Check(imported.Queue.Count == 1, "invalid import is rejected without changing library"); }

    Check(MpvPlayer.BuildEqualizerFilter(new LinuxSettings { EqualizerEnabled = true, EqualizerPreset = "bass" }).Contains("equalizer=f=31"), "equalizer preset builds a filter graph");
    Check(MpvPlayer.BuildEqualizerFilter(new LinuxSettings()).Length == 0, "equalizer bypass emits no filter");
    Check(YtDlpDownloader.BuildFormat(new LinuxSettings()).Contains("[height<=720]"), "video downloads honor maximum height");
    Check(YtDlpDownloader.BuildAudioFormat() == "bestaudio/best", "audio download selects the audio stream");

    string ytScript = "#!/bin/sh\nprintf '%s\\n' \"$@\" >> \"$LOWTUBE_TEST_ARGS\"\ncase \" $* \" in *\" --dump-single-json \"*) printf '%s' '{\"entries\":[{\"id\":\"abcdefghijk\",\"title\":\"Demo\",\"url\":\"abcdefghijk\"}]}' ;; *) printf '%s\\n' '[download] 12.5% of 10MiB' '[download] 100% of 10MiB' ;; esac\n";
    string ytPath = Path.Combine(bin, "yt-dlp");
    File.WriteAllText(ytPath, ytScript);
    File.SetUnixFileMode(ytPath, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
    var catalog = new YouTubeCatalog();
    var results = await catalog.SearchAsync("safe ; touch " + Path.Combine(root, "injected"), CancellationToken.None);
    Check(results.Count == 1 && results[0].Id == item.Id, "catalog search parses yt-dlp results");
    Check(!File.Exists(Path.Combine(root, "injected")), "search text is passed without shell execution");
    var progress = new List<DownloadProgress>();
    await new YtDlpDownloader().DownloadAsync(item, new LinuxSettings { DownloadFolder = Path.Combine(root, "downloads") }, new InlineProgress<DownloadProgress>(progress.Add), CancellationToken.None, audioOnly: true);
    string captured = File.ReadAllText(argLog);
    Check(captured.Contains("bestaudio/best") && progress.Any(x => x.Percent == 12.5), "audio downloader passes format and reports progress");

    var uiType = typeof(SettingsStore).Assembly.GetType("LowTube.Linux.UiText")!;
    var translate = uiType.GetMethod("T", BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Static)!;
    Check((string)translate.Invoke(null, new object[] { "Queue" })! == "Queue", "localization catalog initializes without duplicate entries");

    if (FindOnPath("python3") is string python)
    {
        await TestPlayerAsync(python, bin, root, Check);
    }
    Console.WriteLine($"All {passed} Linux smoke checks passed.");
}
finally
{
    try { Directory.Delete(root, recursive: true); } catch (IOException) { }
}

static async Task TestPlayerAsync(string python, string bin, string root, Action<bool, string> check)
{
    string server = Path.Combine(root, "mpv-server.py");
    string serverBody = """
import json, os, socket, sys
path = next(x.split('=',1)[1] for x in sys.argv[1:] if x.startswith('--input-ipc-server='))
log = os.environ['LOWTUBE_TEST_MPV_LOG']
idle = True
s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
s.bind(path); s.listen()
while True:
    conn, _ = s.accept()
    with conn:
        data = conn.makefile('rwb')
        line = data.readline()
        if not line: continue
        request = json.loads(line)
        command = request['command']
        name = command[0]
        if name == 'loadfile': idle = False
        if name == 'stop': idle = True
        value = True if command[1:] == ['idle-active'] and idle else False if command[1:] == ['idle-active'] else 45 if command[1:] == ['time-pos'] else 100 if command[1:] == ['duration'] else None
        with open(log, 'a') as f: f.write(json.dumps(command)+'\n')
        data.write((json.dumps({'request_id':request['request_id'],'error':'success','data':value})+'\n').encode()); data.flush()
    if name == 'quit': break
""";
    File.WriteAllText(server, serverBody);
    string launcher = Path.Combine(bin, "mpv");
    File.WriteAllText(launcher, "#!/bin/sh\nexec \"" + python + "\" \"" + server + "\" \"$@\"\n");
    File.SetUnixFileMode(launcher, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
    string log = Path.Combine(root, "mpv-commands.jsonl");
    Environment.SetEnvironmentVariable("LOWTUBE_TEST_MPV_LOG", log);
    await using (var player = new MpvPlayer())
    {
        player.ApplySettings(new LinuxSettings());
        await player.PlayAsync("https://www.youtube.com/watch?v=abcdefghijk");
        check(!await player.IsIdleAsync(), "mpv idle state updates after loading media");
        var state = await player.GetPlaybackPositionAsync();
        check(state.Position == 45 && state.Duration == 100, "mpv timeline properties are read over IPC");
        await player.TogglePauseAsync();
        await player.SeekAsync(12);
        await player.StopAsync();
        check(await player.IsIdleAsync(), "mpv stop returns to idle state");
    }
    check(File.ReadAllText(Path.Combine(root, "mpv-commands.jsonl")).Contains("quit"), "mpv receives a graceful exit command");
}

static string? FindOnPath(string name)
{
    foreach (string directory in (Environment.GetEnvironmentVariable("PATH") ?? "").Split(Path.PathSeparator))
    {
        string path = Path.Combine(directory, name);
        if (File.Exists(path)) return path;
    }
    return null;
}

sealed class InlineProgress<T>(Action<T> report) : IProgress<T>
{
    public void Report(T value) => report(value);
}
