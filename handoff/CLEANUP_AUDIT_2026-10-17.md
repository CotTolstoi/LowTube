# Аудит очищення LowTube — 2026-10-17

**Оновлення: користувач погодив очищення; виконано 256 видалень (206,82 МіБ). Поточний результат — CLEANUP_DONE_2026-10-17.md та CLEANUP_RESULT_2026-10-17.json. Нижче збережено початкову оцінку. QuickJS-NG залишено через обов’язкову startup-перевірку MainForm.**

Корінь перевірки: `C:\Users\sv176\Desktop\LowTube\LowTube`. На момент первинного аудиту нічого не видалялося. Вимірювання до створення цього звіту: **602 файли, 673,128,314 байтів (641,95 МіБ)**.

МіБ = 1 048 576 байтів. Підсумки — сума довжин файлів; фактично звільнене місце NTFS може відрізнятися через розмір кластерів/стиснення. Нові файли цього аудиту в суму не включені.

## Підсумок

| Група | Файлів | Байти | МіБ |
|---|---:|---:|---:|
| A1: Рекомендоване очищення: кеш установлення | 6 | 179,233,060 | 170,93 |
| A2: Рекомендоване очищення: зібрані тестові EXE | 12 | 292,864 | 0,28 |
| A3: Рекомендоване очищення: тимчасові перевірки | 18 | 225,081 | 0,21 |
| B: Необов’язково: попередні архіви патчів | 4 | 1,690,271 | 1,61 |
| C1: Необов’язково: окремий плеєр FFplay | 1 | 19,156,992 | 18,27 |
| C2: Необов’язково: довідка FFmpeg | 35 | 11,915,383 | 11,36 |
| C3: Необов’язково: C-заголовки FFmpeg | 146 | 1,358,223 | 1,30 |
| C4: Необов’язково: import/static-бібліотеки FFmpeg | 28 | 1,763,943 | 1,68 |
| C5: Необов’язково: довідка mpv | 2 | 1,233,746 | 1,18 |

**Рекомендоване очищення A1–A3: 36 файлів, 179,751,005 байтів = 171,42 МіБ.**
Разом із попередніми патчами B: 40 файлів, 181,441,276 байтів = 173,04 МіБ.
Якщо додатково відмовитися від усіх необов’язкових компонентів C1–C5: 252 файли, 216,869,563 байтів = **206,82 МіБ**. Це розширене очищення, а не лише тимчасове сміття.

Кеш download-cache не потрібний для поточного запуску. Setup.ps1: Fetch перевіряє/використовує ці пакети повторно; після очищення для встановлення може знадобитися нове завантаження. Копія QuickJS у кеші побайтово збігається з tools/qjs.exe (перевірено SHA-256). Інших побайтових дублікатів серед файлів від 1 КіБ не знайдено. Сам факт дублювання бібліотеки/ліцензії не означав би, що її можна видалити.

У .checks-temp є 5 одноразових скриптів пакування та StreamProbe.cs. Якщо їх планують використовувати як інструменти розробки, їх можна залишити: разом це лише 12 776 байтів (приблизно 12,5 КіБ); економія A1–A3 тоді зменшиться на цю величину.

## Залишити

- LowTube.exe, LowTube.Benchmark.exe, LowTube.Hybrid.exe, LowTube.Hybrid.Compare.exe та .config: поточний запуск і окремі експерименти.
- src/, player/, tests/, benchmark/, hybrid/, скрипти збірки/запуску та .vscode/. Тести й історичні функції не вважаються сміттям.
- tools/mpv/mpv.exe, tools/mpv/d3dcompiler_43.dll, tools/ffmpeg/ffmpeg.exe, tools/ffmpeg/ffprobe.exe та всі runtime DLL.
- tools/yt-dlp/ цілком, включно з _internal/ і _internal/base_library.zip: це залежності onedir-дистрибутива, а не кеш.
- tools/js/node22/ та tools/qjs.exe: підтримувані JS runtime; ліцензійні файли залишити.
- handoff/reports/: 3 історичні звіти, разом 12 184 452 байти (11,62 МіБ). Найбільший — 8AApd803TUE_20261014_010927_735.txt (11,25 МіБ). Вони містять докази попередніх вимірювань, тому не включені в очищення.
- Усі історичні SHA256/SOURCE/TRANSFER/PATCH-маніфести, ліцензії, docs/legacy/ і setup-diagnostics.txt: контекст, походження компонентів та діагностика.
- LowTube_0.11.6_patch_2026-10-17.zip: актуальний кумулятивний патч. Старі архіви B — лише необов’язкові кандидати.
- tools/mpv/installer/, register/unregister/updater, ffmpeg/presets/: допоміжні інструменти/можливості, малий обсяг.

Каталоги bin/, obj/, __pycache__, .setup-* і tools.previous-* не знайдено. Порожніх каталогів і посилань на зовнішні каталоги під час первинного обходу не знайдено. Дані %LOCALAPPDATA%\LowTube та профілі браузерів не перевірялися.

## Повний список кандидатів

Усі шляхи нижче відносні до наведеного кореня. Групи не перетинаються; жоден файл не врахований двічі.

### A1. Рекомендоване очищення: кеш установлення

Копії завантажених пакетів; робочі інструменти збережено в tools.

| Файл | Байти | МіБ |
|---|---:|---:|
| `download-cache/20b2f500c9d94fc417ea770d37c4f27e4c26a707e54052973a0591cb663169c7-ffmpeg-master-latest-win64-gpl-shared.zip` | 88,848,730 | 84,73 |
| `download-cache/256feca8e274e5da655e2a284fabafd9f554365eb164862089dacd4e8276d282-7zr.exe` | 602,624 | 0,57 |
| `download-cache/2aeabf0092c3262d6b2609824418f7dd7ed1f1df939f73b2b15645230cac0d77-qjs-windows-x86_64.exe` | 2,151,363 | 2,05 |
| `download-cache/2b0ff57b049cda1bbcea2240eec20467018713c1efe1f7360c2681859b90ed71-node-v22.23.3-win-x64.zip` | 35,574,076 | 33,93 |
| `download-cache/30b4c14aafab6082becff7881e41b76df46dc43ea7633479410a91e29da492bf-yt-dlp_win.zip` | 18,087,041 | 17,25 |
| `download-cache/f9becefe40e2a29d2ce5f3ab231ff094f9cf3e4038ff014cfe4a0c09ad90e79e-mpv-x86_64-20261015-git-492bae0768.7z` | 33,969,226 | 32,40 |
| **Разом (6 файлів)** | **179,233,060** | **170,93** |

### A2. Рекомендоване очищення: зібрані тестові EXE

Не потрібні для запуску; штатні тести збирає Run-Checks.ps1, StreamProbe — одноразовий діагностичний helper.

| Файл | Байти | МіБ |
|---|---:|---:|
| `Test.BenchPlanTests.exe` | 11,776 | 0,01 |
| `Test.ContinuityTests.exe` | 16,384 | 0,02 |
| `Test.CoreTests.exe` | 73,216 | 0,07 |
| `Test.FeatureTests.exe` | 19,456 | 0,02 |
| `Test.GoogleTests.exe` | 11,776 | 0,01 |
| `Test.InnerTubeReliabilityTests.exe` | 27,648 | 0,03 |
| `Test.InnerTubeTests.exe` | 41,984 | 0,04 |
| `Test.PlaybackSelectionTests.exe` | 7,680 | 0,01 |
| `Test.StreamOpenTests.exe` | 15,360 | 0,01 |
| `Test.StreamProbe.exe` | 9,728 | 0,01 |
| `Test.WindowsPlayerStopTests.exe` | 37,888 | 0,04 |
| `Test.WindowsStreamOpenTests.exe` | 19,968 | 0,02 |
| **Разом (12 файлів)** | **292,864** | **0,28** |

### A3. Рекомендоване очищення: тимчасові перевірки

Логи, синтетичні тестові JSON, одноразові probe/packaging-скрипти. Не частина робочого застосунку; останні результати описані в handoff.

| Файл | Байти | МіБ |
|---|---:|---:|
| `.checks-temp/StreamProbe.cs` | 2,530 | 0,00 |
| `.checks-temp/checks-full.log` | 22,712 | 0,02 |
| `.checks-temp/checks.log` | 16,261 | 0,02 |
| `.checks-temp/lowtube-features-f1b3f15dea1149099015dd4c24b5e90d/cancel.json.tmp` | 389 | 0,00 |
| `.checks-temp/lowtube-rate-dfd7bc24c2fc4bed90f6f7b1e98fbeab/jobs.json` | 954 | 0,00 |
| `.checks-temp/osc-close-2026-10-17-final.log` | 28,512 | 0,03 |
| `.checks-temp/osc-close-2026-10-17.log` | 28,947 | 0,03 |
| `.checks-temp/package_0_11_3.py` | 2,280 | 0,00 |
| `.checks-temp/package_0_11_4.py` | 1,942 | 0,00 |
| `.checks-temp/package_0_11_5.py` | 2,060 | 0,00 |
| `.checks-temp/package_0_11_6.py` | 1,909 | 0,00 |
| `.checks-temp/package_patch.py` | 2,055 | 0,00 |
| `.checks-temp/playback-stop-final.log` | 27,907 | 0,03 |
| `.checks-temp/playback-stop.log` | 28,530 | 0,03 |
| `.checks-temp/public.log` | 3,393 | 0,00 |
| `.checks-temp/stage1-checks.log` | 24,946 | 0,02 |
| `.checks-temp/stage1-public.log` | 3,454 | 0,00 |
| `.checks-temp/stream-checks.log` | 26,300 | 0,03 |
| **Разом (18 файлів)** | **225,081** | **0,21** |

### B. Необов’язково: попередні архіви патчів

Історичні версії, не побайтові дублікати. Усі їхні шляхи присутні в останньому кумулятивному патчі 0.11.6; втрачається можливість взяти ці старі версії саме з архівів.

| Файл | Байти | МіБ |
|---|---:|---:|
| `LowTube_0.11.3_patch_2026-10-15.zip` | 399,111 | 0,38 |
| `LowTube_0.11.4_patch_2026-10-15.zip` | 438,522 | 0,42 |
| `LowTube_0.11.5_patch_2026-10-15.zip` | 467,910 | 0,45 |
| `LowTube_InnerTube_patch_2026-10-15.zip` | 384,728 | 0,37 |
| **Разом (4 файлів)** | **1,690,271** | **1,61** |

### C1. Необов’язково: окремий плеєр FFplay

У коді/скриптах LowTube викликів ffplay не знайдено. FFmpeg/FFprobe та всі DLL залишити.

| Файл | Байти | МіБ |
|---|---:|---:|
| `tools/ffmpeg/ffplay.exe` | 19,156,992 | 18,27 |
| **Разом (1 файлів)** | **19,156,992** | **18,27** |

### C2. Необов’язково: довідка FFmpeg

Локальна HTML-документація; запуск LowTube її не використовує. LICENSE.txt залишити.

| Файл | Байти | МіБ |
|---|---:|---:|
| `tools/ffmpeg/doc/bootstrap.min.css` | 109,483 | 0,10 |
| `tools/ffmpeg/doc/community.html` | 13,505 | 0,01 |
| `tools/ffmpeg/doc/default.css` | 2,494 | 0,00 |
| `tools/ffmpeg/doc/developer.html` | 65,770 | 0,06 |
| `tools/ffmpeg/doc/drawvg-reference.html` | 147,341 | 0,14 |
| `tools/ffmpeg/doc/faq.html` | 63,702 | 0,06 |
| `tools/ffmpeg/doc/fate.html` | 18,768 | 0,02 |
| `tools/ffmpeg/doc/ffmpeg-all.html` | 3,170,258 | 3,02 |
| `tools/ffmpeg/doc/ffmpeg-bitstream-filters.html` | 73,014 | 0,07 |
| `tools/ffmpeg/doc/ffmpeg-codecs.html` | 362,715 | 0,35 |
| `tools/ffmpeg/doc/ffmpeg-devices.html` | 114,015 | 0,11 |
| `tools/ffmpeg/doc/ffmpeg-filters.html` | 1,723,294 | 1,64 |
| `tools/ffmpeg/doc/ffmpeg-formats.html` | 346,962 | 0,33 |
| `tools/ffmpeg/doc/ffmpeg-protocols.html` | 134,892 | 0,13 |
| `tools/ffmpeg/doc/ffmpeg-resampler.html` | 13,818 | 0,01 |
| `tools/ffmpeg/doc/ffmpeg-scaler.html` | 12,981 | 0,01 |
| `tools/ffmpeg/doc/ffmpeg-utils.html` | 55,775 | 0,05 |
| `tools/ffmpeg/doc/ffmpeg.html` | 222,447 | 0,21 |
| `tools/ffmpeg/doc/ffplay-all.html` | 2,446,539 | 2,33 |
| `tools/ffmpeg/doc/ffplay.html` | 43,262 | 0,04 |
| `tools/ffmpeg/doc/ffprobe-all.html` | 2,462,431 | 2,35 |
| `tools/ffmpeg/doc/ffprobe.html` | 59,074 | 0,06 |
| `tools/ffmpeg/doc/general.html` | 133,600 | 0,13 |
| `tools/ffmpeg/doc/git-howto.html` | 27,737 | 0,03 |
| `tools/ffmpeg/doc/libavcodec.html` | 3,147 | 0,00 |
| `tools/ffmpeg/doc/libavdevice.html` | 3,026 | 0,00 |
| `tools/ffmpeg/doc/libavfilter.html` | 3,092 | 0,00 |
| `tools/ffmpeg/doc/libavformat.html` | 3,122 | 0,00 |
| `tools/ffmpeg/doc/libavutil.html` | 3,621 | 0,00 |
| `tools/ffmpeg/doc/libswresample.html` | 4,005 | 0,00 |
| `tools/ffmpeg/doc/libswscale.html` | 3,621 | 0,00 |
| `tools/ffmpeg/doc/mailing-list-faq.html` | 31,209 | 0,03 |
| `tools/ffmpeg/doc/nut.html` | 11,279 | 0,01 |
| `tools/ffmpeg/doc/platform.html` | 18,861 | 0,02 |
| `tools/ffmpeg/doc/style.min.css` | 6,523 | 0,01 |
| **Разом (35 файлів)** | **11,915,383** | **11,36** |

### C3. Необов’язково: C-заголовки FFmpeg

SDK-заголовки; Build-All.ps1 збирає C# і не компілює нативний код FFmpeg.

| Файл | Байти | МіБ |
|---|---:|---:|
| `tools/ffmpeg/include/libavcodec/ac3_parser.h` | 1,207 | 0,00 |
| `tools/ffmpeg/include/libavcodec/adts_parser.h` | 1,354 | 0,00 |
| `tools/ffmpeg/include/libavcodec/avcodec.h` | 110,821 | 0,11 |
| `tools/ffmpeg/include/libavcodec/avdct.h` | 2,767 | 0,00 |
| `tools/ffmpeg/include/libavcodec/bsf.h` | 25,839 | 0,02 |
| `tools/ffmpeg/include/libavcodec/codec.h` | 11,922 | 0,01 |
| `tools/ffmpeg/include/libavcodec/codec_desc.h` | 4,264 | 0,00 |
| `tools/ffmpeg/include/libavcodec/codec_id.h` | 18,734 | 0,02 |
| `tools/ffmpeg/include/libavcodec/codec_par.h` | 8,400 | 0,01 |
| `tools/ffmpeg/include/libavcodec/d3d11va.h` | 2,610 | 0,00 |
| `tools/ffmpeg/include/libavcodec/defs.h` | 13,981 | 0,01 |
| `tools/ffmpeg/include/libavcodec/dirac.h` | 4,126 | 0,00 |
| `tools/ffmpeg/include/libavcodec/dv_profile.h` | 3,694 | 0,00 |
| `tools/ffmpeg/include/libavcodec/dxva2.h` | 2,128 | 0,00 |
| `tools/ffmpeg/include/libavcodec/exif.h` | 8,430 | 0,01 |
| `tools/ffmpeg/include/libavcodec/jni.h` | 2,263 | 0,00 |
| `tools/ffmpeg/include/libavcodec/mediacodec.h` | 3,742 | 0,00 |
| `tools/ffmpeg/include/libavcodec/packet.h` | 33,047 | 0,03 |
| `tools/ffmpeg/include/libavcodec/qsv.h` | 3,844 | 0,00 |
| `tools/ffmpeg/include/libavcodec/smpte_436m.h` | 11,580 | 0,01 |
| `tools/ffmpeg/include/libavcodec/vdpau.h` | 4,555 | 0,00 |
| `tools/ffmpeg/include/libavcodec/version.h` | 1,619 | 0,00 |
| `tools/ffmpeg/include/libavcodec/version_major.h` | 1,696 | 0,00 |
| `tools/ffmpeg/include/libavcodec/videotoolbox.h` | 2,445 | 0,00 |
| `tools/ffmpeg/include/libavcodec/vorbis_parser.h` | 2,285 | 0,00 |
| `tools/ffmpeg/include/libavdevice/avdevice.h` | 13,506 | 0,01 |
| `tools/ffmpeg/include/libavdevice/version.h` | 1,624 | 0,00 |
| `tools/ffmpeg/include/libavdevice/version_major.h` | 1,200 | 0,00 |
| `tools/ffmpeg/include/libavfilter/avfilter.h` | 45,981 | 0,04 |
| `tools/ffmpeg/include/libavfilter/buffersink.h` | 7,102 | 0,01 |
| `tools/ffmpeg/include/libavfilter/buffersrc.h` | 7,334 | 0,01 |
| `tools/ffmpeg/include/libavfilter/version.h` | 1,653 | 0,00 |
| `tools/ffmpeg/include/libavfilter/version_major.h` | 1,223 | 0,00 |
| `tools/ffmpeg/include/libavformat/avformat.h` | 127,549 | 0,12 |
| `tools/ffmpeg/include/libavformat/avio.h` | 31,178 | 0,03 |
| `tools/ffmpeg/include/libavformat/version.h` | 1,652 | 0,00 |
| `tools/ffmpeg/include/libavformat/version_major.h` | 2,024 | 0,00 |
| `tools/ffmpeg/include/libavutil/adler32.h` | 1,696 | 0,00 |
| `tools/ffmpeg/include/libavutil/aes.h` | 1,912 | 0,00 |
| `tools/ffmpeg/include/libavutil/aes_ctr.h` | 2,443 | 0,00 |
| `tools/ffmpeg/include/libavutil/ambient_viewing_environment.h` | 2,585 | 0,00 |
| `tools/ffmpeg/include/libavutil/attributes.h` | 6,672 | 0,01 |
| `tools/ffmpeg/include/libavutil/audio_fifo.h` | 5,966 | 0,01 |
| `tools/ffmpeg/include/libavutil/avassert.h` | 4,015 | 0,00 |
| `tools/ffmpeg/include/libavutil/avconfig.h` | 180 | 0,00 |
| `tools/ffmpeg/include/libavutil/avstring.h` | 14,962 | 0,01 |
| `tools/ffmpeg/include/libavutil/avutil.h` | 8,702 | 0,01 |
| `tools/ffmpeg/include/libavutil/base64.h` | 2,285 | 0,00 |
| `tools/ffmpeg/include/libavutil/blowfish.h` | 2,394 | 0,00 |
| `tools/ffmpeg/include/libavutil/bprint.h` | 8,818 | 0,01 |
| `tools/ffmpeg/include/libavutil/bswap.h` | 2,788 | 0,00 |
| `tools/ffmpeg/include/libavutil/buffer.h` | 11,998 | 0,01 |
| `tools/ffmpeg/include/libavutil/camellia.h` | 2,139 | 0,00 |
| `tools/ffmpeg/include/libavutil/cast5.h` | 2,561 | 0,00 |
| `tools/ffmpeg/include/libavutil/channel_layout.h` | 35,181 | 0,03 |
| `tools/ffmpeg/include/libavutil/common.h` | 17,359 | 0,02 |
| `tools/ffmpeg/include/libavutil/container_fifo.h` | 4,855 | 0,00 |
| `tools/ffmpeg/include/libavutil/cpu.h` | 6,899 | 0,01 |
| `tools/ffmpeg/include/libavutil/crc.h` | 3,259 | 0,00 |
| `tools/ffmpeg/include/libavutil/csp.h` | 7,304 | 0,01 |
| `tools/ffmpeg/include/libavutil/des.h` | 2,514 | 0,00 |
| `tools/ffmpeg/include/libavutil/detection_bbox.h` | 3,524 | 0,00 |
| `tools/ffmpeg/include/libavutil/dict.h` | 9,521 | 0,01 |
| `tools/ffmpeg/include/libavutil/display.h` | 3,472 | 0,00 |
| `tools/ffmpeg/include/libavutil/dovi_meta.h` | 12,730 | 0,01 |
| `tools/ffmpeg/include/libavutil/downmix_info.h` | 5,373 | 0,01 |
| `tools/ffmpeg/include/libavutil/encryption_info.h` | 7,056 | 0,01 |
| `tools/ffmpeg/include/libavutil/error.h` | 5,555 | 0,01 |
| `tools/ffmpeg/include/libavutil/eval.h` | 6,600 | 0,01 |
| `tools/ffmpeg/include/libavutil/executor.h` | 1,924 | 0,00 |
| `tools/ffmpeg/include/libavutil/ffversion.h` | 208 | 0,00 |
| `tools/ffmpeg/include/libavutil/fifo.h` | 8,487 | 0,01 |
| `tools/ffmpeg/include/libavutil/file.h` | 3,416 | 0,00 |
| `tools/ffmpeg/include/libavutil/film_grain_params.h` | 9,190 | 0,01 |
| `tools/ffmpeg/include/libavutil/frame.h` | 44,367 | 0,04 |
| `tools/ffmpeg/include/libavutil/gain_map.h` | 4,771 | 0,00 |
| `tools/ffmpeg/include/libavutil/hash.h` | 8,457 | 0,01 |
| `tools/ffmpeg/include/libavutil/hdr_dynamic_metadata.h` | 18,538 | 0,02 |
| `tools/ffmpeg/include/libavutil/hdr_dynamic_vivid_metadata.h` | 8,427 | 0,01 |
| `tools/ffmpeg/include/libavutil/hmac.h` | 2,865 | 0,00 |
| `tools/ffmpeg/include/libavutil/hwcontext.h` | 24,170 | 0,02 |
| `tools/ffmpeg/include/libavutil/hwcontext_amf.h` | 2,292 | 0,00 |
| `tools/ffmpeg/include/libavutil/hwcontext_cuda.h` | 4,025 | 0,00 |
| `tools/ffmpeg/include/libavutil/hwcontext_d3d11va.h` | 7,653 | 0,01 |
| `tools/ffmpeg/include/libavutil/hwcontext_d3d12va.h` | 6,201 | 0,01 |
| `tools/ffmpeg/include/libavutil/hwcontext_drm.h` | 4,673 | 0,00 |
| `tools/ffmpeg/include/libavutil/hwcontext_dxva2.h` | 2,411 | 0,00 |
| `tools/ffmpeg/include/libavutil/hwcontext_mediacodec.h` | 1,988 | 0,00 |
| `tools/ffmpeg/include/libavutil/hwcontext_oh.h` | 1,098 | 0,00 |
| `tools/ffmpeg/include/libavutil/hwcontext_opencl.h` | 3,098 | 0,00 |
| `tools/ffmpeg/include/libavutil/hwcontext_qsv.h` | 2,515 | 0,00 |
| `tools/ffmpeg/include/libavutil/hwcontext_vaapi.h` | 3,784 | 0,00 |
| `tools/ffmpeg/include/libavutil/hwcontext_vdpau.h` | 1,360 | 0,00 |
| `tools/ffmpeg/include/libavutil/hwcontext_videotoolbox.h` | 4,745 | 0,00 |
| `tools/ffmpeg/include/libavutil/hwcontext_vulkan.h` | 11,870 | 0,01 |
| `tools/ffmpeg/include/libavutil/iamf.h` | 21,603 | 0,02 |
| `tools/ffmpeg/include/libavutil/imgutils.h` | 16,326 | 0,02 |
| `tools/ffmpeg/include/libavutil/intfloat.h` | 1,726 | 0,00 |
| `tools/ffmpeg/include/libavutil/intreadwrite.h` | 19,426 | 0,02 |
| `tools/ffmpeg/include/libavutil/lfg.h` | 2,542 | 0,00 |
| `tools/ffmpeg/include/libavutil/log.h` | 14,088 | 0,01 |
| `tools/ffmpeg/include/libavutil/lzo.h` | 2,048 | 0,00 |
| `tools/ffmpeg/include/libavutil/macros.h` | 2,304 | 0,00 |
| `tools/ffmpeg/include/libavutil/mastering_display_metadata.h` | 4,284 | 0,00 |
| `tools/ffmpeg/include/libavutil/mathematics.h` | 9,565 | 0,01 |
| `tools/ffmpeg/include/libavutil/md5.h` | 2,092 | 0,00 |
| `tools/ffmpeg/include/libavutil/mem.h` | 20,521 | 0,02 |
| `tools/ffmpeg/include/libavutil/motion_vector.h` | 1,770 | 0,00 |
| `tools/ffmpeg/include/libavutil/murmur3.h` | 3,507 | 0,00 |
| `tools/ffmpeg/include/libavutil/opt.h` | 46,402 | 0,04 |
| `tools/ffmpeg/include/libavutil/parseutils.h` | 7,888 | 0,01 |
| `tools/ffmpeg/include/libavutil/pixdesc.h` | 16,432 | 0,02 |
| `tools/ffmpeg/include/libavutil/pixelutils.h` | 2,051 | 0,00 |
| `tools/ffmpeg/include/libavutil/pixfmt.h` | 48,517 | 0,05 |
| `tools/ffmpeg/include/libavutil/random_seed.h` | 1,889 | 0,00 |
| `tools/ffmpeg/include/libavutil/rational.h` | 6,287 | 0,01 |
| `tools/ffmpeg/include/libavutil/raw_color_params.h` | 5,621 | 0,01 |
| `tools/ffmpeg/include/libavutil/rc4.h` | 2,004 | 0,00 |
| `tools/ffmpeg/include/libavutil/refstruct.h` | 12,172 | 0,01 |
| `tools/ffmpeg/include/libavutil/replaygain.h` | 1,607 | 0,00 |
| `tools/ffmpeg/include/libavutil/ripemd.h` | 2,158 | 0,00 |
| `tools/ffmpeg/include/libavutil/samplefmt.h` | 10,562 | 0,01 |
| `tools/ffmpeg/include/libavutil/sha.h` | 2,368 | 0,00 |
| `tools/ffmpeg/include/libavutil/sha512.h` | 2,413 | 0,00 |
| `tools/ffmpeg/include/libavutil/spherical.h` | 8,607 | 0,01 |
| `tools/ffmpeg/include/libavutil/stereo3d.h` | 7,446 | 0,01 |
| `tools/ffmpeg/include/libavutil/tdrdi.h` | 5,280 | 0,01 |
| `tools/ffmpeg/include/libavutil/tea.h` | 2,035 | 0,00 |
| `tools/ffmpeg/include/libavutil/threadmessage.h` | 3,910 | 0,00 |
| `tools/ffmpeg/include/libavutil/time.h` | 1,800 | 0,00 |
| `tools/ffmpeg/include/libavutil/timecode.h` | 7,843 | 0,01 |
| `tools/ffmpeg/include/libavutil/timestamp.h` | 2,726 | 0,00 |
| `tools/ffmpeg/include/libavutil/tree.h` | 5,408 | 0,01 |
| `tools/ffmpeg/include/libavutil/twofish.h` | 2,245 | 0,00 |
| `tools/ffmpeg/include/libavutil/tx.h` | 7,141 | 0,01 |
| `tools/ffmpeg/include/libavutil/uuid.h` | 4,895 | 0,00 |
| `tools/ffmpeg/include/libavutil/version.h` | 4,195 | 0,00 |
| `tools/ffmpeg/include/libavutil/video_enc_params.h` | 5,991 | 0,01 |
| `tools/ffmpeg/include/libavutil/video_hint.h` | 3,585 | 0,00 |
| `tools/ffmpeg/include/libavutil/xtea.h` | 2,834 | 0,00 |
| `tools/ffmpeg/include/libswresample/swresample.h` | 22,476 | 0,02 |
| `tools/ffmpeg/include/libswresample/version.h` | 1,707 | 0,00 |
| `tools/ffmpeg/include/libswresample/version_major.h` | 1,015 | 0,00 |
| `tools/ffmpeg/include/libswscale/swscale.h` | 28,916 | 0,03 |
| `tools/ffmpeg/include/libswscale/version.h` | 1,589 | 0,00 |
| `tools/ffmpeg/include/libswscale/version_major.h` | 1,176 | 0,00 |
| **Разом (146 файлів)** | **1,358,223** | **1,30** |

### C4. Необов’язково: import/static-бібліотеки FFmpeg

Файли SDK для нативного компонування; поточній C#-збірці не потрібні. Це не runtime DLL.

| Файл | Байти | МіБ |
|---|---:|---:|
| `tools/ffmpeg/lib/avcodec-63.def` | 5,306 | 0,01 |
| `tools/ffmpeg/lib/avcodec.lib` | 160,438 | 0,15 |
| `tools/ffmpeg/lib/avdevice-63.def` | 453 | 0,00 |
| `tools/ffmpeg/lib/avdevice.lib` | 14,120 | 0,01 |
| `tools/ffmpeg/lib/avfilter-12.def` | 2,011 | 0,00 |
| `tools/ffmpeg/lib/avfilter.lib` | 58,058 | 0,06 |
| `tools/ffmpeg/lib/avformat-63.def` | 3,627 | 0,00 |
| `tools/ffmpeg/lib/avformat.lib` | 126,862 | 0,12 |
| `tools/ffmpeg/lib/avutil-61.def` | 15,859 | 0,02 |
| `tools/ffmpeg/lib/avutil.lib` | 517,070 | 0,49 |
| `tools/ffmpeg/lib/libavcodec.dll.a` | 138,150 | 0,13 |
| `tools/ffmpeg/lib/libavdevice.dll.a` | 11,416 | 0,01 |
| `tools/ffmpeg/lib/libavfilter.dll.a` | 48,252 | 0,05 |
| `tools/ffmpeg/lib/libavformat.dll.a` | 129,778 | 0,12 |
| `tools/ffmpeg/lib/libavutil.dll.a` | 432,318 | 0,41 |
| `tools/ffmpeg/lib/libswresample.dll.a` | 15,964 | 0,02 |
| `tools/ffmpeg/lib/libswscale.dll.a` | 27,362 | 0,03 |
| `tools/ffmpeg/lib/pkgconfig/libavcodec.pc` | 312 | 0,00 |
| `tools/ffmpeg/lib/pkgconfig/libavdevice.pc` | 423 | 0,00 |
| `tools/ffmpeg/lib/pkgconfig/libavfilter.pc` | 404 | 0,00 |
| `tools/ffmpeg/lib/pkgconfig/libavformat.pc` | 350 | 0,00 |
| `tools/ffmpeg/lib/pkgconfig/libavutil.pc` | 264 | 0,00 |
| `tools/ffmpeg/lib/pkgconfig/libswresample.pc` | 301 | 0,00 |
| `tools/ffmpeg/lib/pkgconfig/libswscale.pc` | 295 | 0,00 |
| `tools/ffmpeg/lib/swresample-7.def` | 475 | 0,00 |
| `tools/ffmpeg/lib/swresample.lib` | 19,876 | 0,02 |
| `tools/ffmpeg/lib/swscale-10.def` | 911 | 0,00 |
| `tools/ffmpeg/lib/swscale.lib` | 33,288 | 0,03 |
| **Разом (28 файлів)** | **1,763,943** | **1,68** |

### C5. Необов’язково: довідка mpv

Локальна документація; низькорівневі бібліотеки, installer та виконувані файли залишити.

| Файл | Байти | МіБ |
|---|---:|---:|
| `tools/mpv/doc/manual.pdf` | 1,043,370 | 1,00 |
| `tools/mpv/doc/mpbindings.png` | 190,376 | 0,18 |
| **Разом (2 файлів)** | **1,233,746** | **1,18** |

## Доповнення: JS runtime та експеримент гібридного декодування

Це початкова додаткова оцінка. Після погодження видалено лише чотири Deno/Classic launcher/README; QuickJS і hybrid збережено. Остаточний результат наведено у CLEANUP_DONE_2026-10-17.md. Попередні суми 171,42 / 206,82 МіБ залишаються для груп A–C.

### JavaScript

| Компонент | Байти | Оцінка |
|---|---:|---|
| tools/js/node22/ | 87 121 985 | 83,09 МіБ. Типовий runtime у JavaScriptRuntime.Profile; залишити для yt-dlp медіапотоків/завантажень. |
| tools/yt-dlp/_internal/yt_dlp_ejs/yt/solver/core.min.js та lib.min.js | 158 506 | Solver-код bundled yt-dlp; залишити. Це не сторонній старий експеримент LowTube. |
| JS_RUNTIME_SMOKE.js | 137 | Використовується START_NODE22/START_DENO/START_QUICKJS_* для перевірки runtime. Залишити. |
| tools/qjs.exe + START_QUICKJS_NG.cmd | 2 152 175 | 2,05 МіБ. Збережено: пізніша перевірка виявила обов’язкову залежність у startup-списку MainForm навіть для Node. |
| START_DENO.cmd, README_Deno.txt | 1 041 | Сам Deno не встановлений; це тільки launcher і пояснення. |
| START_QUICKJS_CLASSIC.cmd, README_QuickJS_Classic.txt | 1 101 | Сам QuickJS Classic не встановлений; це тільки launcher і пояснення. |
| RUNTIME_Deno/QuickJS_Classic_* маніфести | 1 474 | Історичні контрольні суми/джерела; зберегти відповідно до AGENTS.md. |

Ні tools/js/deno/deno.exe, ні tools/js/quickjs-classic/qjs.exe у цьому проєкті немає. Тому великої економії від прибирання їхніх launcher-файлів не буде. JS_RUNTIMES_UK.txt описує також чинні профілі — це спільна документація, не кандидат на автоматичне видалення.

### Hybrid

Це окремий експеримент CPU + D3D11VA, який не викликається основним Player. Усі його файли в проєкті — **105 761 байт = 103,28 КіБ = 0,10 МіБ**. Лише два зібрані EXE — 53 248 байт (52 КіБ); Build-All.ps1 створить їх знову. Окремих великих відеофайлів чи кадрових кешів у hybrid/ не знайдено.

| Файл | Байти |
|---|---:|
| `HYBRID_BENCHMARK.cmd` | 54 |
| `HYBRID_EXPERIMENT.cmd` | 46 |
| `LowTube.Hybrid.Compare.exe` | 19,456 |
| `LowTube.Hybrid.exe` | 33,792 |
| `hybrid/BUILD.cmd` | 266 |
| `hybrid/BUILD_COMPARE.cmd` | 275 |
| `hybrid/Compare.cs` | 10,832 |
| `hybrid/Hybrid.cs` | 15,433 |
| `hybrid/README.uk.md` | 16,335 |
| `hybrid/metrics.lua` | 371 |
| `hybrid/test_compare.py` | 4,003 |
| `hybrid/test_frames.py` | 1,988 |
| `hybrid/test_queue.py` | 2,910 |

Звіти handoff/reports/comparison_20261014_193746_003.txt та hybrid_20261014_190141_392.txt займають разом 386 296 байтів (0,37 МіБ). Це результати вимірювань; зберегти. Великий звіт на 11,25 МіБ — окремий основний benchmark, а не сміттєвий кеш hybrid. Зовнішній %LOCALAPPDATA%\LowTube\HybridReports не аналізувався.

Рекомендація: залишити експеримент або обговорити винесення окремим архівом/проєктом. Просте видалення hybrid/ зламає Build-All.ps1, який зараз збирає чотири EXE. Повне вилучення — свідома зміна складу проєкту, а не очищення тимчасових файлів. Звичайне програмне й апаратне декодування mpv у src/Player.cs лишається окремою функцією основного клієнта.
