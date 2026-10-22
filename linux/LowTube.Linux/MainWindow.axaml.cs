using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Interactivity;
using Avalonia.Controls.Primitives;
using Avalonia.Threading;
using Avalonia.Styling;
using System.Diagnostics;
using LowTube.Linux.Services;
using LowTube.Linux.ViewModels;

namespace LowTube.Linux;

public partial class MainWindow : Window
{
    private readonly YouTubeCatalog catalog = new();
    private readonly YtDlpDownloader downloader = new();
    private readonly MpvPlayer player = new();
    private readonly MainViewModel viewModel = new();
    private readonly SettingsStore settingsStore = new();
    private readonly LocalLibrary library = new();
    private readonly DispatcherTimer playbackTimer = new() { Interval = TimeSpan.FromSeconds(1) };
    private LinuxSettings settings;
    private CancellationTokenSource? searchCancellation;
    private bool seeking;
    private MediaItem? currentItem;
    private bool playbackObserved;
    private DateTime lastResumeSave = DateTime.MinValue;

    public MainWindow()
    {
        InitializeComponent();
        settings = settingsStore.Load();
        UiText.SetLanguage(settings.Language);
        ApplyLanguage();
        PageTitleText.Text = UiText.T("Discover");
        IntroText.Text = UiText.T("Search YouTube to find something to watch.");
        ApplyTheme();
        player.ApplySettings(settings);
        VolumeSlider.Value = settings.Volume;
        DataContext = viewModel;
        playbackTimer.Tick += PlaybackTimer_OnTick;
        playbackTimer.Start();
        StatusText.Text = catalog.IsAvailable
            ? (player.IsAvailable ? UiText.T("Ready") + " · yt-dlp and mpv found" : UiText.T("Ready") + " · " + UiText.T("Install mpv to play videos"))
            : UiText.T("Install yt-dlp to search YouTube");
        SearchBox.KeyDown += async (_, e) => { if (e.Key == Key.Enter) await SearchAsync(); };
        Closed += async (_, _) =>
        {
            playbackTimer.Stop();
            searchCancellation?.Cancel();
            await player.DisposeAsync();
        };
    }

    private async void Search_OnClick(object? sender, RoutedEventArgs e) => await SearchAsync();

    private async void Settings_OnClick(object? sender, RoutedEventArgs e)
    {
        LinuxSettings previous = settings.Copy();
        var dialog = new SettingsWindow(settingsStore, settings, updated =>
        {
            settings = updated;
            UiText.SetLanguage(settings.Language);
            ApplyLanguage();
            ApplyTheme();
            player.ApplySettings(settings);
            VolumeSlider.Value = settings.Volume;
            StatusText.Text = player.IsRunning && NeedsPlayerRestart(previous, updated)
                ? UiText.T("Saved. Restart LowTube to apply playback quality, scaling and equalizer changes.")
                : UiText.T("Settings saved");
        });
        await dialog.ShowDialog(this);
    }

    private static bool NeedsPlayerRestart(LinuxSettings oldValue, LinuxSettings newValue) =>
        oldValue.MaxHeight != newValue.MaxHeight || oldValue.H264 != newValue.H264 || oldValue.Fps30 != newValue.Fps30 ||
        oldValue.BufferSeconds != newValue.BufferSeconds || oldValue.VideoScale != newValue.VideoScale ||
        oldValue.EqualizerEnabled != newValue.EqualizerEnabled || oldValue.EqualizerPreset != newValue.EqualizerPreset;

    private void ApplyLanguage()
    {
        SearchBox.Watermark = UiText.T("Search YouTube or paste a video, playlist, or channel link");
        SearchButton.Content = UiText.T("Search");
        SettingsButton.Content = UiText.T("Settings");
        LibraryLabel.Text = UiText.T("LIBRARY");
        SearchNavButton.Content = UiText.T("Search");
        SubscriptionsButton.Content = UiText.T("Subscriptions");
        SavedButton.Content = UiText.T("Saved");
        PlaylistsButton.Content = UiText.T("Playlists");
        HistoryButton.Content = UiText.T("History");
        QueueButton.Content = UiText.T("Queue");
        PlayQueueButton.Content = UiText.T("Play queue");
        ClearQueueButton.Content = UiText.T("Clear queue");
        DownloadsButton.Content = UiText.T("Downloads");
        PortStatusText.Text = UiText.T("Linux edition preview");
        PauseButton.Content = UiText.T("Pause / resume");
        StopButton.Content = UiText.T("Stop");
        VolumeLabel.Text = UiText.T("Volume");
        viewModel.RefreshLanguage();
    }

    private void ApplyTheme()
    {
        Avalonia.Application.Current!.RequestedThemeVariant = settings.Theme switch
        {
            "dark" => ThemeVariant.Dark,
            "system" => ThemeVariant.Default,
            _ => ThemeVariant.Light
        };
    }

    private async void Saved_OnClick(object? sender, RoutedEventArgs e)
    {
        viewModel.Replace(library.Saved);
        PageTitleText.Text = UiText.T("Saved");
        IntroText.Text = UiText.T("Saved videos");
        StatusText.Text = library.Saved.Count + " · " + UiText.T("Saved");
        await Task.CompletedTask;
    }

    private async void History_OnClick(object? sender, RoutedEventArgs e)
    {
        viewModel.Replace(library.History);
        PageTitleText.Text = UiText.T("History");
        IntroText.Text = UiText.T("Recently played");
        StatusText.Text = library.History.Count + " item(s) in history";
        await Task.CompletedTask;
    }

    private async void Queue_OnClick(object? sender, RoutedEventArgs e)
    {
        viewModel.Replace(library.Queue);
        PageTitleText.Text = UiText.T("Queue");
        IntroText.Text = library.Queue.Count + " · " + UiText.T("Queued videos");
        StatusText.Text = library.Queue.Count == 0 ? UiText.T("Queue is empty") : UiText.T("Choose a video or press Play queue");
        await Task.CompletedTask;
    }

    private async void PlayQueue_OnClick(object? sender, RoutedEventArgs e)
    {
        MediaItem? next = library.TakeNextQueued();
        if (next is null) { StatusText.Text = UiText.T("Queue is empty"); return; }
        await PlayAsync(next);
    }

    private void QueueItem_OnClick(object? sender, RoutedEventArgs e)
    {
        if (sender is not Button { DataContext: MediaItem item }) return;
        try { library.Enqueue(item); StatusText.Text = UiText.T("Added to queue") + " · " + item.Title; }
        catch (Exception ex) { StatusText.Text = UiText.T(ex.Message); }
    }

    private void ClearQueue_OnClick(object? sender, RoutedEventArgs e)
    {
        library.ClearQueue();
        if (PageTitleText.Text == UiText.T("Queue")) Queue_OnClick(sender, e);
        else StatusText.Text = UiText.T("Queue cleared");
    }

    private async void Subscriptions_OnClick(object? sender, RoutedEventArgs e)
    {
        var dialog = new SubscriptionWindow(library, RefreshSubscriptionsAsync);
        await dialog.ShowDialog(this);
    }

    private async void Playlists_OnClick(object? sender, RoutedEventArgs e)
    {
        var dialog = new PlaylistWindow(library, items =>
        {
            viewModel.Replace(items);
            PageTitleText.Text = UiText.T("Local playlist");
            IntroText.Text = items.Count + " · " + UiText.T("Double-click or choose Play");
        });
        await dialog.ShowDialog(this);
    }

    private async Task RefreshSubscriptionsAsync()
    {
        if (library.Subscriptions.Count == 0)
        {
            viewModel.Replace(Array.Empty<MediaItem>());
            PageTitleText.Text = UiText.T("Subscriptions");
            IntroText.Text = UiText.T("Subscriptions");
            StatusText.Text = UiText.T("Add a public channel URL first");
            return;
        }
        SearchButton.IsEnabled = false;
        StatusText.Text = UiText.T("Refreshing public channel feeds…");
        var all = new List<MediaItem>();
        using var cancellation = new CancellationTokenSource(TimeSpan.FromMinutes(3));
        try
        {
            foreach (string channel in library.Subscriptions)
            {
                var result = await catalog.SearchAsync(channel, cancellation.Token);
                all.AddRange(result);
            }
            var unique = all.GroupBy(x => x.Url, StringComparer.OrdinalIgnoreCase).Select(x => x.First()).ToList();
            viewModel.Replace(unique);
            PageTitleText.Text = UiText.T("Subscriptions");
            IntroText.Text = UiText.T("Subscriptions");
            StatusText.Text = unique.Count + " public video(s) from " + library.Subscriptions.Count + " channel(s)";
        }
        finally { SearchButton.IsEnabled = true; }
    }

    private void Downloads_OnClick(object? sender, RoutedEventArgs e)
    {
        try
        {
            Directory.CreateDirectory(settings.DownloadFolder);
            var info = new ProcessStartInfo("xdg-open") { UseShellExecute = false };
            info.ArgumentList.Add(settings.DownloadFolder);
            Process.Start(info);
        }
        catch (Exception ex) { StatusText.Text = UiText.T("Could not open download folder: ") + ex.Message; }
    }

    private void SaveItem_OnClick(object? sender, RoutedEventArgs e)
    {
        if (sender is not Button { DataContext: MediaItem item }) return;
        try
        {
            bool added = library.ToggleSaved(item);
            StatusText.Text = added ? UiText.T("Saved to your library") : UiText.T("Removed from your library");
        }
        catch (Exception ex) { StatusText.Text = ex.Message; }
    }

    private async void Pause_OnClick(object? sender, RoutedEventArgs e)
    {
        try { await player.TogglePauseAsync(); StatusText.Text = UiText.T("Playback command sent"); }
        catch (Exception ex) { StatusText.Text = ex.Message; }
    }

    private async void Stop_OnClick(object? sender, RoutedEventArgs e)
    {
        try { await player.StopAsync(); currentItem = null; playbackObserved = false; StatusText.Text = UiText.T("Playback stopped"); }
        catch (Exception ex) { StatusText.Text = ex.Message; }
    }

    private async void Volume_OnChanged(object? sender, RangeBaseValueChangedEventArgs e)
    {
        settings.Volume = (int)Math.Round(e.NewValue);
        try { if (player.IsRunning) await player.SetVolumeAsync(settings.Volume); }
        catch (Exception ex) { StatusText.Text = ex.Message; }
    }

    private void Seek_OnPointerPressed(object? sender, PointerPressedEventArgs e) => seeking = true;

    private async void Seek_OnPointerReleased(object? sender, PointerReleasedEventArgs e)
    {
        seeking = false;
        try { await player.SeekAsync(SeekSlider.Value); }
        catch (Exception ex) { StatusText.Text = ex.Message; }
    }

    private async void PlaybackTimer_OnTick(object? sender, EventArgs e)
    {
        if (!player.IsRunning) return;
        try
        {
            bool idle = await player.IsIdleAsync();
            if (!idle) playbackObserved = true;
            if (idle && playbackObserved && currentItem is not null)
            {
                playbackObserved = false;
                MediaItem? next = settings.AutoNext ? library.TakeNextQueued() : null;
                currentItem = null;
                if (next is not null) { await PlayAsync(next); return; }
            }
            if (seeking) return;
            var state = await player.GetPlaybackPositionAsync();
            if (!state.Duration.HasValue || !state.Position.HasValue) return;
            SeekSlider.Maximum = Math.Max(1, state.Duration.Value);
            SeekSlider.Value = Math.Clamp(state.Position.Value, 0, SeekSlider.Maximum);
            TimeText.Text = FormatTime(state.Position.Value) + " / " + FormatTime(state.Duration.Value);
            if (currentItem is not null && DateTime.UtcNow - lastResumeSave >= TimeSpan.FromSeconds(10))
            {
                library.SaveResumePosition(currentItem, state.Position.Value, state.Duration.Value);
                lastResumeSave = DateTime.UtcNow;
            }
        }
        catch (InvalidOperationException) { }
        catch (IOException) { }
    }

    private static string FormatTime(double value)
    {
        var time = TimeSpan.FromSeconds(Math.Max(0, value));
        return time.ToString(value >= 3600 ? @"h\:mm\:ss" : @"m\:ss");
    }

    private async Task SearchAsync()
    {
        string query = SearchBox.Text?.Trim() ?? "";
        if (query.Length == 0) return;
        searchCancellation?.Cancel();
        var currentCancellation = new CancellationTokenSource(TimeSpan.FromSeconds(90));
        searchCancellation = currentCancellation;
        SearchButton.IsEnabled = false;
        StatusText.Text = UiText.T("Searching…");
        try
        {
            var results = await catalog.SearchAsync(query, currentCancellation.Token);
            viewModel.Replace(results);
            PageTitleText.Text = UiText.T("Search");
            IntroText.Text = $"{results.Count} · {UiText.T("Search results")} · {UiText.T("Double-click or choose Play")}";
            StatusText.Text = results.Count == 0 ? UiText.T("No results found") : UiText.T("Search complete");
        }
        catch (OperationCanceledException)
        {
            StatusText.Text = UiText.T("Search cancelled or timed out");
        }
        catch (Exception ex)
        {
            StatusText.Text = UiText.T(ex.Message);
        }
        finally
        {
            if (ReferenceEquals(searchCancellation, currentCancellation))
            {
                searchCancellation = null;
                SearchButton.IsEnabled = true;
            }
            currentCancellation.Dispose();
        }
    }

    private async void Results_OnDoubleTapped(object? sender, TappedEventArgs e) => await PlaySelectedAsync();

    private async void DownloadItem_OnClick(object? sender, RoutedEventArgs e)
    {
        if (sender is not Button { DataContext: MediaItem item }) return;
        await DownloadAsync(item, audioOnly: false);
    }

    private async void DownloadAudioItem_OnClick(object? sender, RoutedEventArgs e)
    {
        if (sender is not Button { DataContext: MediaItem item }) return;
        await DownloadAsync(item, audioOnly: true);
    }

    private async Task DownloadAsync(MediaItem item, bool audioOnly)
    {
        try
        {
            StatusText.Text = UiText.T("Starting download…");
            var progress = new Progress<DownloadProgress>(state => StatusText.Text = UiText.T(state.Message));
            await downloader.DownloadAsync(item, settings, progress, CancellationToken.None, audioOnly);
            StatusText.Text = UiText.T("Download complete") + " · " + settings.DownloadFolder;
        }
        catch (Exception ex) { StatusText.Text = UiText.T(ex.Message); }
    }

    private async void PlayItem_OnClick(object? sender, RoutedEventArgs e)
    {
        if (sender is Button { DataContext: MediaItem item })
        {
            if (PageTitleText.Text == UiText.T("Queue")) library.RemoveQueued(item.Url);
            await PlayAsync(item);
        }
    }

    private async Task PlaySelectedAsync()
    {
        if (ResultsList.SelectedItem is MediaItem item) await PlayAsync(item);
    }

    private async Task PlayAsync(MediaItem item)
    {
        currentItem = null;
        playbackObserved = false;
        try
        {
            StatusText.Text = UiText.T("Opening in mpv…");
            await player.PlayAsync(item.Url);
            currentItem = item;
            playbackObserved = false;
            library.AddToHistory(item);
            double resume = library.GetResumePosition(item.Url);
            if (resume >= 5)
            {
                for (int attempt = 0; attempt < 30; attempt++)
                {
                    if (await player.IsIdleAsync()) { await Task.Delay(150); continue; }
                    var state = await player.GetPlaybackPositionAsync();
                    if (state.Duration.HasValue)
                    {
                        await player.SeekAsync(Math.Min(resume, Math.Max(0, state.Duration.Value - 5)));
                        break;
                    }
                    await Task.Delay(150);
                }
            }
            lastResumeSave = DateTime.UtcNow;
            StatusText.Text = UiText.T("Video opened in mpv");
        }
        catch (Exception ex)
        {
            StatusText.Text = UiText.T(ex.Message);
        }
    }
}
