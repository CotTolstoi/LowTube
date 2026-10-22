using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Interactivity;
using Avalonia.Controls.Primitives;
using LowTube.Linux.Services;
using LowTube.Linux.ViewModels;

namespace LowTube.Linux;

public partial class MainWindow : Window
{
    private readonly YouTubeCatalog catalog = new();
    private readonly MpvPlayer player = new();
    private readonly MainViewModel viewModel = new();
    private readonly SettingsStore settingsStore = new();
    private LinuxSettings settings;
    private CancellationTokenSource? searchCancellation;

    public MainWindow()
    {
        InitializeComponent();
        settings = settingsStore.Load();
        player.ApplySettings(settings);
        VolumeSlider.Value = settings.Volume;
        DataContext = viewModel;
        StatusText.Text = catalog.IsAvailable
            ? (player.IsAvailable ? "Ready · yt-dlp and mpv found" : "Ready to search · install mpv to play videos")
            : "Install yt-dlp to search YouTube";
        SearchBox.KeyDown += async (_, e) => { if (e.Key == Key.Enter) await SearchAsync(); };
        Closed += async (_, _) =>
        {
            searchCancellation?.Cancel();
            await player.DisposeAsync();
        };
    }

    private async void Search_OnClick(object? sender, RoutedEventArgs e) => await SearchAsync();

    private async void Settings_OnClick(object? sender, RoutedEventArgs e)
    {
        var dialog = new SettingsWindow(settingsStore, settings, updated =>
        {
            settings = updated;
            player.ApplySettings(settings);
            VolumeSlider.Value = settings.Volume;
        });
        await dialog.ShowDialog(this);
    }

    private async void Pause_OnClick(object? sender, RoutedEventArgs e)
    {
        try { await player.TogglePauseAsync(); StatusText.Text = "Playback command sent"; }
        catch (Exception ex) { StatusText.Text = ex.Message; }
    }

    private async void Stop_OnClick(object? sender, RoutedEventArgs e)
    {
        try { await player.StopAsync(); StatusText.Text = "Playback stopped"; }
        catch (Exception ex) { StatusText.Text = ex.Message; }
    }

    private async void Volume_OnChanged(object? sender, RangeBaseValueChangedEventArgs e)
    {
        settings.Volume = (int)Math.Round(e.NewValue);
        try { if (player.IsRunning) await player.SetVolumeAsync(settings.Volume); }
        catch (Exception ex) { StatusText.Text = ex.Message; }
    }

    private async Task SearchAsync()
    {
        string query = SearchBox.Text?.Trim() ?? "";
        if (query.Length == 0) return;
        searchCancellation?.Cancel();
        var currentCancellation = new CancellationTokenSource(TimeSpan.FromSeconds(90));
        searchCancellation = currentCancellation;
        SearchButton.IsEnabled = false;
        StatusText.Text = "Searching…";
        try
        {
            var results = await catalog.SearchAsync(query, currentCancellation.Token);
            viewModel.Replace(results);
            IntroText.Text = $"{results.Count} result{(results.Count == 1 ? "" : "s")} · double-click or choose Play";
            StatusText.Text = results.Count == 0 ? "No results found" : "Search complete";
        }
        catch (OperationCanceledException)
        {
            StatusText.Text = "Search cancelled or timed out";
        }
        catch (Exception ex)
        {
            StatusText.Text = ex.Message;
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

    private async void PlayItem_OnClick(object? sender, RoutedEventArgs e)
    {
        if (sender is Button { DataContext: MediaItem item }) await PlayAsync(item);
    }

    private async Task PlaySelectedAsync()
    {
        if (ResultsList.SelectedItem is MediaItem item) await PlayAsync(item);
    }

    private async Task PlayAsync(MediaItem item)
    {
        try
        {
            StatusText.Text = "Opening in mpv…";
            await player.PlayAsync(item.Url);
            StatusText.Text = "Video opened in mpv";
        }
        catch (Exception ex)
        {
            StatusText.Text = ex.Message;
        }
    }
}
