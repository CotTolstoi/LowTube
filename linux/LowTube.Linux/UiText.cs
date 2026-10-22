namespace LowTube.Linux;

internal static class UiText
{
    private static string language = "en";
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
        ["Linux edition preview"] = new[] { "Попередня версія для Linux", "Предварительная версия для Linux" },
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
        , ["Queue"] = new[] { "Черга", "Очередь" }
        , ["Play queue"] = new[] { "Відтворити чергу", "Воспроизвести очередь" }
        , ["Clear queue"] = new[] { "Очистити чергу", "Очистить очередь" }
        , ["Queue cleared"] = new[] { "Чергу очищено", "Очередь очищена" }
        , ["Queued videos"] = new[] { "Відео в черзі", "Видео в очереди" }
        , ["Queue is empty"] = new[] { "Черга порожня", "Очередь пуста" }
        , ["Choose a video or press Play queue"] = new[] { "Виберіть відео або натисніть «Відтворити чергу»", "Выберите видео или нажмите «Воспроизвести очередь»" }
        , ["Added to queue"] = new[] { "Додано до черги", "Добавлено в очередь" }
        , ["Only standard YouTube videos can be queued."] = new[] { "До черги можна додати лише звичайні відео YouTube.", "В очередь можно добавить только обычные видео YouTube." }
        , ["The queue is full."] = new[] { "Черга заповнена.", "Очередь заполнена." }
        , ["Theme"] = new[] { "Тема", "Тема" }
        , ["Video scaling"] = new[] { "Масштабування відео", "Масштабирование видео" }
        , ["Play next queued video automatically"] = new[] { "Автоматично відтворювати наступне відео з черги", "Автоматически воспроизводить следующее видео из очереди" }
        , ["Equalizer"] = new[] { "Еквалайзер", "Эквалайзер" }
        , ["Bass boost"] = new[] { "Підсилення басів", "Усиление басов" }
        , ["Voice"] = new[] { "Голос", "Голос" }
        , ["Treble boost"] = new[] { "Підсилення високих", "Усиление высоких" }
        , ["Rock"] = new[] { "Рок", "Рок" }
        , ["Classical"] = new[] { "Класика", "Классика" }
        , ["Playlists"] = new[] { "Плейлисти", "Плейлисты" }
        , ["Local playlist"] = new[] { "Локальний плейлист", "Локальный плейлист" }
        , ["Local playlists"] = new[] { "Локальні плейлисти", "Локальные плейлисты" }
        , ["Current queue"] = new[] { "Поточна черга", "Текущая очередь" }
        , ["Playlist name"] = new[] { "Назва плейлиста", "Название плейлиста" }
        , ["Save as playlist"] = new[] { "Зберегти як плейлист", "Сохранить как плейлист" }
        , ["Open selected"] = new[] { "Відкрити вибране", "Открыть выбранный" }
        , ["Import library…"] = new[] { "Імпортувати бібліотеку…", "Импортировать библиотеку…" }
        , ["Export library…"] = new[] { "Експортувати бібліотеку…", "Экспортировать библиотеку…" }
        , ["Export library"] = new[] { "Експорт бібліотеки", "Экспорт библиотеки" }
        , ["Import library"] = new[] { "Імпорт бібліотеки", "Импорт библиотеки" }
        , ["Playlist saved"] = new[] { "Плейлист збережено", "Плейлист сохранён" }
        , ["Playlist removed"] = new[] { "Плейлист вилучено", "Плейлист удалён" }
        , ["Library exported"] = new[] { "Бібліотеку експортовано", "Библиотека экспортирована" }
        , ["Library imported and merged"] = new[] { "Бібліотеку імпортовано та об’єднано", "Библиотека импортирована и объединена" }
        , ["Use a playlist name of 1–100 characters."] = new[] { "Назва плейлиста має містити від 1 до 100 символів.", "Название плейлиста должно содержать от 1 до 100 символов." }
        , ["Up to 200 local playlists are supported."] = new[] { "Підтримується до 200 локальних плейлистів.", "Поддерживается до 200 локальных плейлистов." }
        , ["A playlist must contain 1–500 valid videos."] = new[] { "Плейлист має містити від 1 до 500 правильних відео.", "Плейлист должен содержать от 1 до 500 корректных видео." }
        , ["The library contains an invalid URL or item."] = new[] { "Бібліотека містить неправильне посилання або запис.", "Библиотека содержит неверную ссылку или запись." }
        , ["The library format version or item limits are invalid."] = new[] { "Версія формату бібліотеки або кількість записів неприпустима.", "Версия формата библиотеки или количество записей недопустимы." }
        , ["Downloads"] = new[] { "Завантаження", "Загрузки" }
        , ["LowTube settings"] = new[] { "Налаштування LowTube", "Настройки LowTube" }
        , ["Ready"] = new[] { "Готово", "Готово" }
        , ["Stop"] = new[] { "Зупинити", "Остановить" }
        , ["Volume"] = new[] { "Гучність", "Громкость" }
        , ["Play"] = new[] { "Відтворити", "Воспроизвести" }
        , ["Video"] = new[] { "Відео", "Видео" }
        , ["Audio"] = new[] { "Аудіо", "Аудио" }
        , ["Save"] = new[] { "Зберегти", "Сохранить" }
        , ["System"] = new[] { "Системна", "Системная" }
        , ["Light"] = new[] { "Світла", "Светлая" }
        , ["Dark"] = new[] { "Темна", "Тёмная" }
        , ["Settings saved"] = new[] { "Налаштування збережено", "Настройки сохранены" }
        , ["Saved. Restart LowTube to apply playback quality, scaling and equalizer changes."] = new[] { "Збережено. Перезапустіть LowTube, щоб застосувати якість, масштабування та еквалайзер.", "Сохранено. Перезапустите LowTube, чтобы применить качество, масштабирование и эквалайзер." }
    };

    public static void SetLanguage(string value) => language = value is "uk" or "ru" ? value : "en";

    public static string T(string english)
    {
        if (LinuxCatalog.TryGetValue(english, out var values))
            return language == "ru" ? values[1] : language == "uk" ? values[0] : english;
        return english;
    }
}
