namespace LowTube.Linux.Services;

public sealed class LinuxSettings
{
    public string Language { get; set; } = "en";
    public string Theme { get; set; } = "light";
    public int PageSize { get; set; } = 25;
    public int MaxHeight { get; set; } = 720;
    public bool H264 { get; set; } = true;
    public bool Fps30 { get; set; } = true;
    public int BufferSeconds { get; set; } = 60;
    public bool AutoNext { get; set; } = true;
    public int Volume { get; set; } = 100;
    public string VideoScale { get; set; } = "bilinear";
    public string DownloadFolder { get; set; } = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads", "LowTube");

    public void Normalize()
    {
        if (Language is not ("en" or "uk" or "ru")) Language = "en";
        if (Theme is not ("light" or "dark" or "system")) Theme = "light";
        if (PageSize is < 1 or > 250) PageSize = 25;
        if (MaxHeight is not (144 or 240 or 360 or 480 or 720 or 1080 or 1440 or 2160)) MaxHeight = 720;
        if (BufferSeconds is < 5 or > 3600) BufferSeconds = 60;
        if (Volume is < 0 or > 130) Volume = 100;
        if (VideoScale is not ("bilinear" or "bicubic" or "lanczos" or "ewa_lanczos")) VideoScale = "bilinear";
        if (string.IsNullOrWhiteSpace(DownloadFolder)) DownloadFolder = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads", "LowTube");
    }
}
