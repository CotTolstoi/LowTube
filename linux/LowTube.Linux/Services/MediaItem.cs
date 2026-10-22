namespace LowTube.Linux.Services;

public sealed record MediaItem(string Id, string Title, string Channel, string Duration, string Url, string Kind)
{
    [System.Text.Json.Serialization.JsonIgnore]
    public string PlayLabel => LowTube.Linux.UiText.T("Play");
    [System.Text.Json.Serialization.JsonIgnore]
    public string QueueLabel => LowTube.Linux.UiText.T("Queue");
    [System.Text.Json.Serialization.JsonIgnore]
    public string VideoDownloadLabel => LowTube.Linux.UiText.T("Video");
    [System.Text.Json.Serialization.JsonIgnore]
    public string AudioDownloadLabel => LowTube.Linux.UiText.T("Audio");
    [System.Text.Json.Serialization.JsonIgnore]
    public string SaveLabel => LowTube.Linux.UiText.T("Save");
    [System.Text.Json.Serialization.JsonIgnore]
    public string Metadata => string.Join(" · ", new[] { Channel, Duration, Kind }.Where(x => !string.IsNullOrWhiteSpace(x)));
}
