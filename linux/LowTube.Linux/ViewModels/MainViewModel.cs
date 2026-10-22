using System.Collections.ObjectModel;
using LowTube.Linux.Services;

namespace LowTube.Linux.ViewModels;

public sealed class MainViewModel
{
    public ObservableCollection<MediaItem> Items { get; } = new();

    public void Replace(IEnumerable<MediaItem> items)
    {
        Items.Clear();
        foreach (var item in items) Items.Add(item);
    }

    public void RefreshLanguage()
    {
        var current = Items.ToArray();
        Replace(current.Select(item => item with { }));
    }
}
