using Avalonia;
using Avalonia.Controls;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Interactivity;
using LowTube.Linux.Services;

namespace LowTube.Linux;

public sealed class SettingsWindow : Window
{
    private readonly SettingsStore store;
    private readonly LinuxSettings settings;
    private readonly Action<LinuxSettings> saved;
    private readonly ComboBox quality;
    private readonly CheckBox h264;
    private readonly CheckBox fps30;
    private readonly NumericUpDown buffer;
    private readonly TextBlock error;

    public SettingsWindow(SettingsStore store, LinuxSettings settings, Action<LinuxSettings> saved)
    {
        this.store = store;
        this.settings = settings;
        this.saved = saved;
        Title = "LowTube settings";
        Width = 500;
        SizeToContent = SizeToContent.Height;
        WindowStartupLocation = WindowStartupLocation.CenterOwner;

        quality = new ComboBox { ItemsSource = new[] { "144p", "240p", "360p", "480p", "720p", "1080p", "1440p", "2160p" }, SelectedItem = settings.MaxHeight + "p" };
        h264 = new CheckBox { Content = "Prefer H.264 video", IsChecked = settings.H264 };
        fps30 = new CheckBox { Content = "Limit video to 30 FPS", IsChecked = settings.Fps30 };
        buffer = new NumericUpDown { Minimum = 5, Maximum = 3600, Increment = 5, Value = settings.BufferSeconds };
        error = new TextBlock { Foreground = Brushes.IndianRed, TextWrapping = TextWrapping.Wrap };

        var save = new Button { Content = "Save settings", MinWidth = 120 };
        var cancel = new Button { Content = "Cancel", MinWidth = 100 };
        save.Click += Save_OnClick;
        cancel.Click += (_, _) => Close();

        var content = new StackPanel { Margin = new Thickness(24), Spacing = 14 };
        content.Children.Add(new TextBlock { Text = "Playback", FontSize = 22, FontWeight = FontWeight.SemiBold });
        content.Children.Add(new TextBlock { Text = "Maximum video height" });
        content.Children.Add(quality);
        content.Children.Add(h264);
        content.Children.Add(fps30);
        content.Children.Add(new TextBlock { Text = "mpv cache duration (seconds)" });
        content.Children.Add(buffer);
        content.Children.Add(new TextBlock { Text = "Settings are stored in your XDG configuration directory." , Foreground = Brushes.Gray });
        content.Children.Add(error);
        var actions = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right, Spacing = 8 };
        actions.Children.Add(cancel);
        actions.Children.Add(save);
        content.Children.Add(actions);
        Content = content;
    }

    private void Save_OnClick(object? sender, RoutedEventArgs e)
    {
        if (!int.TryParse((quality.SelectedItem as string)?.TrimEnd('p'), out int height))
        {
            error.Text = "Choose a valid video height.";
            return;
        }
        settings.MaxHeight = height;
        settings.H264 = h264.IsChecked == true;
        settings.Fps30 = fps30.IsChecked == true;
        settings.BufferSeconds = decimal.ToInt32(buffer.Value ?? 60);
        try
        {
            store.Save(settings);
            saved(settings);
            Close();
        }
        catch (Exception ex) { error.Text = "Could not save settings: " + ex.Message; }
    }
}
