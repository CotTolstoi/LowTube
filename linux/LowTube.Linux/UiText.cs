using LowTube;

namespace LowTube.Linux;

internal static class UiText
{
    private static readonly HashSet<string> SharedCatalog = new(L.Keys, StringComparer.Ordinal);
    private static readonly Dictionary<string, string[]> LinuxCatalog = new(StringComparer.Ordinal)
    {
        ["Search"] = new[] { "Пошук", "Поиск" },
        ["Subscriptions"] = new[] { "Підписки", "Подписки" },
        ["Saved"] = new[] { "Збережене", "Сохранённое" },
        ["History"] = new[] { "Історія", "История" },
        ["Settings"] = new[] { "Налаштування", "Настройки" },
        ["Discover"] = new[] { "Огляд", "Обзор" },
        ["Search YouTube or paste a video, playlist, or channel link"] = new[] { "Шукайте на YouTube або вставте посилання на відео, плейлист чи канал", "Ищите на YouTube или вставьте ссылку на видео, плейлист или канал" },
        ["LIBRARY"] = new[] { "БІБЛІОТЕКА", "БИБЛИОТЕКА" },
        ["Pause / resume"] = new[] { "Пауза / продовжити", "Пауза / продолжить" },
        ["Linux port in progress"] = new[] { "Перенесення на Linux триває", "Перенос на Linux продолжается" },
        ["Search YouTube to find something to watch."] = new[] { "Знайдіть відео на YouTube.", "Найдите видео на YouTube." },
        ["Double-click or choose Play"] = new[] { "Двічі клацніть або натисніть «Відтворити»", "Дважды щёлкните или нажмите «Воспроизвести»" },
        ["Saved videos"] = new[] { "Збережені відео", "Сохранённые видео" },
        ["Recently played"] = new[] { "Нещодавно відтворені", "Недавно просмотренные" },
        ["Manage public channel feeds"] = new[] { "Керування публічними каналами", "Управление публичными каналами" },
        ["https://www.youtube.com/@channel"] = new[] { "https://www.youtube.com/@канал", "https://www.youtube.com/@канал" },
        ["Add channel"] = new[] { "Додати канал", "Добавить канал" },
        ["Remove selected"] = new[] { "Вилучити вибране", "Удалить выбранное" },
        ["Refresh subscriptions"] = new[] { "Оновити підписки", "Обновить подписки" },
        ["Playback"] = new[] { "Відтворення", "Воспроизведение" },
        ["Maximum video height"] = new[] { "Максимальна роздільність відео", "Максимальное разрешение видео" },
        ["Prefer H.264 video"] = new[] { "Надавати перевагу H.264", "Предпочитать H.264" },
        ["Limit video to 30 FPS"] = new[] { "Обмежити частоту до 30 кадрів/с", "Ограничить частоту до 30 кадров/с" },
        ["mpv cache duration (seconds)"] = new[] { "Тривалість кешу mpv (секунди)", "Длительность кэша mpv (секунды)" },
        ["Download folder (absolute path)"] = new[] { "Тека завантажень (повний шлях)", "Папка загрузок (полный путь)" },
        ["Settings are stored in your XDG configuration directory."] = new[] { "Налаштування зберігаються у каталозі конфігурації XDG.", "Настройки сохраняются в каталоге конфигурации XDG." },
        ["Could not save settings: "] = new[] { "Не вдалося зберегти налаштування: ", "Не удалось сохранить настройки: " },
        ["Install yt-dlp to search YouTube"] = new[] { "Встановіть yt-dlp для пошуку на YouTube", "Установите yt-dlp для поиска на YouTube" },
        ["Install mpv to play videos"] = new[] { "Встановіть mpv для відтворення відео", "Установите mpv для воспроизведения видео" },
        ["Search cancelled or timed out"] = new[] { "Пошук скасовано або перевищено час очікування", "Поиск отменён или превышено время ожидания" },
        ["No results found"] = new[] { "Нічого не знайдено", "Ничего не найдено" },
        ["Search complete"] = new[] { "Пошук завершено", "Поиск завершён" },
        ["Searching…"] = new[] { "Пошук…", "Поиск…" },
        ["Opening in mpv…"] = new[] { "Відкриваю в mpv…", "Открываю в mpv…" },
        ["Download complete"] = new[] { "Завантаження завершено", "Загрузка завершена" },
        ["Could not open download folder: "] = new[] { "Не вдалося відкрити теку завантажень: ", "Не удалось открыть папку загрузок: " },
        ["Enter a YouTube channel URL."] = new[] { "Введіть посилання на канал YouTube.", "Введите ссылку на канал YouTube." }
        , ["Language"] = new[] { "Мова", "Язык" }
        , ["Save settings"] = new[] { "Зберегти налаштування", "Сохранить настройки" }
        , ["Cancel"] = new[] { "Скасувати", "Отмена" }
        , ["Could not save settings: "] = new[] { "Не вдалося зберегти налаштування: ", "Не удалось сохранить настройки: " }
        , ["YouTube subscriptions"] = new[] { "Підписки YouTube", "Подписки YouTube" }
        , ["Close"] = new[] { "Закрити", "Закрыть" }
        , ["Starting download…"] = new[] { "Починаю завантаження…", "Начинаю загрузку…" }
        , ["Downloading"] = new[] { "Завантаження", "Загрузка" }
        , ["Downloading…"] = new[] { "Завантаження…", "Загрузка…" }
        , ["Video opened in mpv"] = new[] { "Відео відкрито в mpv", "Видео открыто в mpv" }
        , ["Playback command sent"] = new[] { "Команду плеєру надіслано", "Команда плееру отправлена" }
        , ["Playback stopped"] = new[] { "Відтворення зупинено", "Воспроизведение остановлено" }
        , ["Saved to your library"] = new[] { "Збережено до бібліотеки", "Сохранено в библиотеку" }
        , ["Removed from your library"] = new[] { "Вилучено з бібліотеки", "Удалено из библиотеки" }
        , ["Add a public channel URL first"] = new[] { "Спочатку додайте посилання на публічний канал", "Сначала добавьте ссылку на публичный канал" }
        , ["Refreshing public channel feeds…"] = new[] { "Оновлюю публічні канали…", "Обновляю публичные каналы…" }
        , ["Search results"] = new[] { "Результати пошуку", "Результаты поиска" }
        , ["Only standard YouTube links are supported."] = new[] { "Підтримуються лише звичайні посилання YouTube.", "Поддерживаются только обычные ссылки YouTube." }
        , ["Enter a search phrase or YouTube link."] = new[] { "Введіть пошуковий запит або посилання YouTube.", "Введите поисковый запрос или ссылку YouTube." }
        , ["yt-dlp is not installed or is missing from PATH."] = new[] { "yt-dlp не встановлено або його немає в PATH.", "yt-dlp не установлен или отсутствует в PATH." }
        , ["mpv is not installed or is missing from PATH."] = new[] { "mpv не встановлено або його немає в PATH.", "mpv не установлен или отсутствует в PATH." }
    };

    public static string T(string english)
    {
        if (LinuxCatalog.TryGetValue(english, out var values))
            return L.Language == "ru" ? values[1] : L.Language == "uk" ? values[0] : english;
        return SharedCatalog.Contains(english) ? L.T(english) : english;
    }
}
