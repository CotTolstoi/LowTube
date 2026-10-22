using System.Text;
using Avalonia.Controls;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Platform.Storage;
using LowTube.Linux.Services;

namespace LowTube.Linux;

public sealed class PlaylistWindow : Window
{
    private readonly LocalLibrary library;
    private readonly Action<IReadOnlyList<MediaItem>> openPlaylist;
    private readonly ListBox playlists = new();
    private readonly TextBox name = new();
    private readonly ComboBox source;
    private readonly TextBlock status = new() { TextWrapping = TextWrapping.Wrap, Foreground = Brushes.IndianRed };

    public PlaylistWindow(LocalLibrary library, Action<IReadOnlyList<MediaItem>> openPlaylist)
    {
        this.library = library;
        this.openPlaylist = openPlaylist;
        Title = UiText.T("Local playlists");
        Width = 560;
        Height = 540;
        MinWidth = 420;
        MinHeight = 420;
        WindowStartupLocation = WindowStartupLocation.CenterOwner;

        source = new ComboBox { ItemsSource = new[] { UiText.T("Current queue"), UiText.T("Saved videos") }, SelectedIndex = 0 };
        var root = new Grid { Margin = new Avalonia.Thickness(18), RowDefinitions = new RowDefinitions("*,Auto,Auto,Auto,Auto,Auto") };
        playlists.ItemsSource = library.Playlists.Keys.OrderBy(x => x, StringComparer.CurrentCultureIgnoreCase).ToArray();
        root.Children.Add(playlists);

        var createRow = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto"), Margin = new Avalonia.Thickness(0, 12, 0, 6) };
        Grid.SetRow(createRow, 1);
        name.Watermark = UiText.T("Playlist name");
        createRow.Children.Add(name);
        var create = new Button { Content = UiText.T("Save as playlist"), Margin = new Avalonia.Thickness(8, 0, 0, 0) };
        Grid.SetColumn(create, 1);
        create.Click += (_, _) => CreatePlaylist();
        createRow.Children.Add(create);
        root.Children.Add(createRow);

        Grid.SetRow(source, 2);
        root.Children.Add(source);
        var actions = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, Margin = new Avalonia.Thickness(0, 10, 0, 0) };
        Grid.SetRow(actions, 3);
        var open = new Button { Content = UiText.T("Open selected") };
        var remove = new Button { Content = UiText.T("Remove selected") };
        open.Click += (_, _) => OpenSelected();
        remove.Click += (_, _) => RemoveSelected();
        actions.Children.Add(open);
        actions.Children.Add(remove);
        root.Children.Add(actions);

        var transfer = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, Margin = new Avalonia.Thickness(0, 8, 0, 0) };
        Grid.SetRow(transfer, 4);
        var import = new Button { Content = UiText.T("Import library…") };
        var export = new Button { Content = UiText.T("Export library…") };
        import.Click += async (_, _) => await ImportAsync();
        export.Click += async (_, _) => await ExportAsync();
        transfer.Children.Add(import);
        transfer.Children.Add(export);
        root.Children.Add(transfer);

        Grid.SetRow(status, 5);
        status.Margin = new Avalonia.Thickness(0, 10, 0, 0);
        root.Children.Add(status);
        Content = root;
    }

    private void CreatePlaylist()
    {
        try
        {
            IEnumerable<MediaItem> items = source.SelectedIndex == 1 ? library.Saved : library.Queue;
            library.CreatePlaylist(name.Text ?? "", items);
            name.Text = "";
            RefreshList();
            status.Text = UiText.T("Playlist saved");
        }
        catch (Exception ex) { status.Text = UiText.T(ex.Message); }
    }

    private void OpenSelected()
    {
        if (playlists.SelectedItem is not string selected || !library.Playlists.TryGetValue(selected, out var items)) return;
        openPlaylist(items.ToArray());
        Close();
    }

    private void RemoveSelected()
    {
        if (playlists.SelectedItem is not string selected) return;
        library.RemovePlaylist(selected);
        RefreshList();
        status.Text = UiText.T("Playlist removed");
    }

    private void RefreshList()
    {
        playlists.ItemsSource = library.Playlists.Keys.OrderBy(x => x, StringComparer.CurrentCultureIgnoreCase).ToArray();
        playlists.SelectedItem = null;
    }

    private async Task ExportAsync()
    {
        try
        {
            var file = await StorageProvider.SaveFilePickerAsync(new FilePickerSaveOptions
            {
                Title = UiText.T("Export library"),
                SuggestedFileName = "lowtube-library.json",
                FileTypeChoices = new[] { new FilePickerFileType("LowTube library") { Patterns = new[] { "*.json" } } }
            });
            if (file is null) return;
            await using var stream = await file.OpenWriteAsync();
            await using var writer = new StreamWriter(stream, new UTF8Encoding(false));
            await writer.WriteAsync(library.ExportJson());
            status.Text = UiText.T("Library exported");
        }
        catch (Exception ex) { status.Text = UiText.T(ex.Message); }
    }

    private async Task ImportAsync()
    {
        try
        {
            var files = await StorageProvider.OpenFilePickerAsync(new FilePickerOpenOptions
            {
                Title = UiText.T("Import library"),
                AllowMultiple = false,
                FileTypeFilter = new[] { new FilePickerFileType("LowTube library") { Patterns = new[] { "*.json" } } }
            });
            if (files.Count == 0) return;
            await using var stream = await files[0].OpenReadAsync();
            using var reader = new StreamReader(stream, Encoding.UTF8, detectEncodingFromByteOrderMarks: true);
            var text = new StringBuilder();
            char[] buffer = new char[8192];
            while (true)
            {
                int count = await reader.ReadAsync(buffer);
                if (count == 0) break;
                if (text.Length + count > 4 * 1024 * 1024) throw new InvalidDataException("The library file exceeds 4 MiB.");
                text.Append(buffer, 0, count);
            }
            library.ImportJson(text.ToString());
            RefreshList();
            status.Text = UiText.T("Library imported and merged");
        }
        catch (Exception ex) { status.Text = UiText.T(ex.Message); }
    }
}
