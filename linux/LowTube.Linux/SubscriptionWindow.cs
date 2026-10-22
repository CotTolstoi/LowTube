using Avalonia;
using Avalonia.Controls;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Interactivity;
using LowTube.Linux.Services;

namespace LowTube.Linux;

public sealed class SubscriptionWindow : Window
{
    private readonly LocalLibrary library;
    private readonly Func<Task> refresh;
    private readonly ListBox channels;
    private readonly TextBox channelUrl;
    private readonly TextBlock error;

    public SubscriptionWindow(LocalLibrary library, Func<Task> refresh)
    {
        this.library = library;
        this.refresh = refresh;
        Title = UiText.T("YouTube subscriptions");
        Width = 560;
        Height = 460;
        WindowStartupLocation = WindowStartupLocation.CenterOwner;

        channelUrl = new TextBox { Watermark = UiText.T("https://www.youtube.com/@channel") };
        channels = new ListBox { ItemsSource = library.Subscriptions };
        error = new TextBlock { Foreground = Brushes.IndianRed, TextWrapping = TextWrapping.Wrap };
        var add = new Button { Content = UiText.T("Add channel") };
        var remove = new Button { Content = UiText.T("Remove selected") };
        var refreshButton = new Button { Content = UiText.T("Refresh subscriptions") };
        var close = new Button { Content = UiText.T("Close") };
        add.Click += Add_OnClick;
        remove.Click += Remove_OnClick;
        refreshButton.Click += Refresh_OnClick;
        close.Click += (_, _) => Close();

        var content = new Grid { Margin = new Thickness(20), RowDefinitions = new RowDefinitions("Auto,Auto,*,Auto,Auto"), RowSpacing = 10 };
        content.Children.Add(new TextBlock { Text = UiText.T("Manage public channel feeds"), FontSize = 21, FontWeight = FontWeight.SemiBold });
        var input = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto"), ColumnSpacing = 8 };
        input.Children.Add(channelUrl);
        Grid.SetColumn(add, 1);
        input.Children.Add(add);
        Grid.SetRow(input, 1);
        content.Children.Add(input);
        Grid.SetRow(channels, 2);
        content.Children.Add(channels);
        Grid.SetRow(error, 3);
        content.Children.Add(error);
        var actions = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right, Spacing = 8 };
        actions.Children.Add(remove);
        actions.Children.Add(refreshButton);
        actions.Children.Add(close);
        Grid.SetRow(actions, 4);
        content.Children.Add(actions);
        Content = content;
    }

    private void Add_OnClick(object? sender, RoutedEventArgs e)
    {
        try
        {
            library.AddSubscription(channelUrl.Text ?? "");
            channels.ItemsSource = null;
            channels.ItemsSource = library.Subscriptions;
            channelUrl.Text = "";
            error.Text = "";
        }
        catch (Exception ex) { error.Text = ex.Message; }
    }

    private void Remove_OnClick(object? sender, RoutedEventArgs e)
    {
        if (channels.SelectedItem is not string value) return;
        library.RemoveSubscription(value);
        channels.ItemsSource = null;
        channels.ItemsSource = library.Subscriptions;
    }

    private async void Refresh_OnClick(object? sender, RoutedEventArgs e)
    {
        try { await refresh(); Close(); }
        catch (Exception ex) { error.Text = ex.Message; }
    }
}
