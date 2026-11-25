# Кадры из видео (FrameGrabber)

Android-приложение: кадры из видео по одному или пачкой, GIF/клип/лист кадров, распознавание текста.
Сборка: GitHub Actions → Releases → `FrameGrabber.apk`.

## Структура кода (`app/src/main/java/com/dave/framegrabber/`)

| Папка | Что там |
|---|---|
| `MainActivity.kt` | Точка входа: окно, открытие видео (браузер / другие приложения / «Поделиться»), жизненный цикл |
| `core/` | Работа с видео: `FrameEngine` (чтение кадров), `FrameCapture` (снять и сохранить кадр, «самый резкий рядом»), `FrameRender` (масштаб, кодирование), `ImageMetrics` (резкость, похожесть), `TimeUtil` |
| `data/` | Данные и настройки: `Prefs`, `Naming` (шаблон имён), `Output` (куда сохранять), `Recent` (недавние видео), `CaptureStore` (снятые кадры), `AppTheme` |
| `export/` | `GifEncoder`, `ContactSheet` (лист кадров), `Exporters` (GIF и MP4-клип) |
| `batch/` | Пачка кадров в фоне: `BatchJob`/`BatchState` + `BatchService` (уведомление с прогрессом) |
| `ocr/` | Распознавание текста (Tesseract) |
| `ui/` | Общие анимации (`Anim.kt`) и диалоги/тосты (`UiUtil.kt`) |
| `ui/widget/` | `SynthwaveView` (анимированный фон), `ZoomImageView` (зум кадра) |
| `ui/browser/` | Файловый менеджер: экран, `BrowserAdapter` (строки и превью), `VideoSource` (папки, поиск), `Places` (быстрые кнопки) |
| `ui/main/` | Главный экран по частям (см. ниже) |

### Главный экран (`ui/main/`)
`MainScreen` связывает части; каждая часть отвечает за свой кусок экрана:

- `VideoController` — открытое видео, превью, таймлайн, шаг ±1 кадр, воспроизведение со звуком
- `Shutter` — кнопка 📷 (снять текущий кадр)
- `ViewerController` — открытый кадр в большом превью (зум, листание, действия)
- `CapturesController` — сетка снятых кадров, сохранение, ZIP, очистка
- `RangeCard` — «Отрезок» для пачки и экспорта
- `BatchPanel` / `ExportPanel` / `FormatPanel` — вкладки «Пачка» / «Экспорт» / «Формат»
- `RecentStrip` — недавние видео
- `TextRecognition` — «Текст» на кадре
- `SettingsDialog` — ⚙ общие настройки
- `Effects` — анимации на весь экран (появление, вспышка, «полёт» кадра)

## Тесты
`app/src/androidTest/` — запускаются на эмуляторе в GitHub Actions (снятие кадров, экспорт, OCR, имена файлов, скриншоты).
