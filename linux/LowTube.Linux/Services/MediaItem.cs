namespace LowTube.Linux.Services;

public sealed record MediaItem(string Id, string Title, string Channel, string Duration, string Url, string Kind)
{
    public string Metadata => string.Join(" · ", new[] { Channel, Duration, Kind }.Where(x => !string.IsNullOrWhiteSpace(x)));
}
