# AGENTS.md — Equalizer314 контекст для LLM-агентов

```
Версия:    0.1.0-alpha (code 100)
Форк:      ZDarow/Equalizer314
Upstream:  bearinmindcat/Equalizer314
Лицензия:  GPL-3.0
Автономия: ✅ — этот файл даёт все ответы. Не спрашивай LLM-пользователя.
```

---

## 1. Контекст и Архитектура

### 1.1 Стек

| Слой | Технология |
|---|---|
| Язык | Kotlin 100% (Java 0 строк) |
| SDK | `minSdk=28` (Android 9), `compileSdk=35`, `targetSdk=35` |
| Сборка | Gradle 8.x + Kotlin DSL + Version Catalog (`gradle/libs.versions.toml`) |
| UI | Android Views (XML), кастомные Canvas View, НЕ Jetpack Compose |
| БД | Room (KSP), 3 сущности: `PresetEntity`, `DeviceBindingEntity`, `SeenDeviceEntity` |
| DI | Нет фреймворка (ручной singleton через `companion object`) |
| Архитектура | ViewModel + StateFlow, god-классы в Activity |
| Линтер | Detekt, baseline в `app/detekt-baseline.xml` (~400 suppressed) |
| Покрытие | Kover (line ~4%, метод ~10%), 160 unit-тестов, 7 instrumented |
| CI/CD | GitHub Actions (7 jobs), Gradle cache (read-only на PR) |
| Документация | Dokka (HTML), KDoc ~76 комментариев |

### 1.2 Структура директорий

```
app/src/main/java/com/bearinmind/equalizer314/
├── MainActivity.kt          # God-класс (~3760 LOC) — EQ, MBC, лимитер, реверб, навигация, импорт
├── MbcActivity.kt           # (~1820 LOC) — мультибенд компрессор
├── LimiterActivity.kt       # (~1100 LOC) — лимитер + waveform
├── TargetCurveActivity.kt   # AutoEQ — загрузка target кривых
├── AutoEqActivity.kt        # AutoEQ — импорт Squiglink/Wavelet/APO
├── ... (ещё ~15 Activity)
│
├── audio/                   # Аудиообработка (22 файла)
│   ├── EqService.kt         # Foreground service (~1070 LOC) — ядро
│   ├── DynamicsProcessingManager.kt  # DSP pipeline + Android API
│   ├── VisualizerHelper.kt  # FFT-спектр анализатор
│   ├── ChannelMath.kt       # L/R канальная математика
│   ├── SessionEffectManager.kt  # AudioSession management
│   ├── RouteSwitchCoordinator.kt  # Audio routing
│   └── ...
│
├── dsp/                     # Цифровая обработка сигналов (6 файлов)
│   ├── ParametricEqualizer.kt  # Параметрический эквалайзер (127 полос)
│   ├── BiquadFilter.kt     # Биквадратный фильтр (RBJ)
│   ├── EqSerializer.kt     # Сериализация пресетов JSON
│   ├── FFT.kt              # Быстрое преобразование Фурье
│   ├── SpectrumAnalyzer.kt # Спектр-анализатор (FFT + нормализация)
│   └── ParametricToDpConverter.kt  # Конвертер EQ → DynamicsProcessing
│
├── state/                   # Состояние приложения (5 файлов)
│   ├── EqViewModel.kt      # ViewModel с StateFlow
│   ├── EqStateManager.kt   # Менеджер состояния
│   ├── EqPreferencesManager.kt  # SharedPreferences + Room sync
│   ├── PresetManager.kt    # CRUD пресетов
│   └── UndoRedoManager.kt  # Undo/Redo для EQ
│
├── ui/                      # UI-компоненты (18 файлов)
│   ├── EqGraphView.kt      # Кастомный View (~1700 LOC) — основная EQ кривая
│   ├── ReverbVisualizerView.kt  # (~1414 LOC) — визуализатор реверберации
│   ├── FilterRole.kt       # Enum + 5 filter-type функций (вынесено из MainActivity)
│   ├── UIExtensions.kt     # hzToSlider, sliderToHz, formatHzValue, blendColor
│   ├── DialogFactory.kt    # Фабрика диалогов
│   ├── SimpleEqController.kt  # Simple EQ контроллер (7 полос)
│   ├── GraphicEqController.kt # Graphic EQ (31 полоса)
│   ├── TableEqController.kt   # Table EQ
│   └── ...
│
├── data/                    # Слой данных (10 файлов)
│   ├── EqDatabase.kt       # Room БД (v1)
│   ├── PresetDao.kt        # Room DAO для пресетов
│   ├── DeviceBindingDao.kt # Room DAO для привязок устройств
│   ├── SeenDeviceDao.kt    # Room DAO для виденных устройств
│   ├── PresetEntity.kt     # Entity пресета
│   ├── PresetConverter.kt  # Legacy SharedPreferences → Room конвертер
│   ├── EqMigrationHelper.kt # Миграция SP → Room
│   └── PresetRepository.kt # Репозиторий пресетов
│
├── autoeq/                  # AutoEQ импорт (6 файлов)
│   ├── AutoEqParser.kt     # Парсинг Squiglink/Wavelet
│   ├── EqFitter.kt         # Подбор EQ-фильтров под target
│   ├── FreqResponseParser.kt  # Парсинг CSV частотных характеристик
│   ├── ApoConverter.kt     # EqualizerAPO конвертер
│   └── AutoEqModels.kt     # Data-классы AutoEQ
│
├── data/                    # Уже описан выше
├── autoeq/                  # Уже описан выше
└── ... (остальные файлы в корне: EqualizerApp.kt, BackupManager.kt, EqUiMode.kt)
```

### 1.3 Ключевые сущности

```
EqDatabase (Room v1)
 ├── preset_dao → PresetEntity(name, bandsJson, preamp, isChannelSideEq, leftBandsJson, rightBandsJson)
 ├── device_binding_dao → DeviceBindingEntity(deviceKey PK, label, presetName)
 └── seen_device_dao → SeenDeviceEntity(deviceKey PK, label)

EqService (Foreground Service)
 ├── DynamicsProcessingManager (ядро DSP, 127 полос)
 ├── SessionEffectManager (AudioSession отслеживание)
 └── RouteSwitchCoordinator (маршрутизация звука)

MainActivity (God-класс)
 ├── EqViewModel (состояние через StateFlow)
 ├── EqStateManager (синхронизация между ViewModel и Service)
 ├── PresetManager (CRUD пресетов)
 ├── UndoRedoManager (отмена/повтор)
 └── VisualizerHelper (FFT-спектр)
```

### 1.4 Связи между модулями

```
MainActivity
  ├── EqViewModel
  │    ├── EqStateManager
  │    │    ├── EqPreferencesManager → EqDatabase
  │    │    └── EqService (через bindService)
  │    └── PresetManager
  │         └── PresetRepository → EqDatabase
  │
  ├── EqGraphView (кастомный Canvas View)
  ├── SimpleEqController, GraphicEqController, TableEqController
  ├── DialogFactory (диалоги)
  └── BroadcastReceivers (eqStopped, eqStarted, statusRefresh)

EqService
  ├── SessionEffectManager → AudioManager
  ├── DynamicsProcessingManager → android.media.audiofx.DynamicsProcessing
  │    └── ParametricToDpConverter
  │         └── ParametricEqualizer
  │              └── BiquadFilter (x127)
  ├── VisualizerHelper → android.media.audiofx.Visualizer
  │    └── FFT → SpectrumAnalyzer
  └── RouteSwitchCoordinator → AudioRoutingMonitor
```

### 1.5 Точки входа

1. **`EqualizerApp.onCreate()`** — инициализация Room, однократная миграция SharedPreferences → Room
2. **`MainActivity.onCreate()`** — привязка к EqService, инициализация EQ, загрузка пресетов
3. **`EqService.onCreate()`** — создание DynamicsProcessing, регистрация AudioSessionReceiver
4. **`BootCompletedReceiver.onReceive()`** — автозапуск после перезагрузки

### 1.6 Поток аудиообработки

```
AudioSource → AudioSession
  → SessionEffectManager (определяет аудиосессию)
  → DynamicsProcessing (127 полос):
       ├── PreEQ (MBC-кроссовер)
       ├── MBC (4 полосы, компрессор/экспандер)
       ├── PostEQ (основной EQ: Parametric/Graphic/Simple/Table)
       ├── Limiter + Gain Reduction
       └── Environmental Reverb
  → Visualizer (FFT → спектр + waveform)
  → AudioOutput
```

---

## 2. Строгие правила и ограничения

### 2.1 Код-стайл

- **Язык:** 100% Kotlin. Никакого Java.
- **Null safety:** Запрещён `!!`. Использовать `?:`, `.let`, `.runCatching`, `requireNotNull()`, `checkNotNull()`.
- **Wildcard imports:** Запрещены (`import foo.*`). Всегда конкретные импорты.
- **Исключения:** Запрещён сырой `catch (e: Exception)`. Использовать:
  - `runCatching { ... }.getOrNull() / getOrDefault(...)` для fallback-паттернов
  - `@Suppress("TooGenericExceptionCaught", "SwallowedException")` с комментарием для остальных
- **TODO/FIXME/HACK:** Ноль в продакшен-коде. Закрывать до коммита.
- **Документация:** KDoc (`/** */`) на всех public функциях, классах и сложных private.
- **Импорты:** Группировать: `android.*` → `androidx.*` → `kotlin.*` → проект. Внутри групп — по алфавиту.
- **Переменные:** `camelCase`. Константы — `SCREAMING_SNAKE_CASE`.

### 2.2 Запрещённые паттерны

| ❌ Запрещено | ✅ Вместо |
|---|---|
| `catch (e: Exception)` | `runCatching{}`, `@Suppress` |
| `import android.widget.*` | `import android.widget.TextView` etc. |
| `var wnd: DoubleArray?` с `wnd!![i]` | локальная `val w = wnd ?: error(...)` |
| `getSystemService(...)` без проверки SDK | Build.VERSION.SDK_INT guard |
| `Thread { ... }.start()` | `CoroutineScope(Dispatchers.IO).launch { ... }` |
| `object : AsyncTask<...>` | `coroutineScope.launch(Dispatchers.Default)` |
| `System.currentTimeMillis()` | `SystemClock.elapsedRealtime()` |
| Синглтон через `object` с публичным `instance` | `companion object` с `@Volatile` + `synchronized` |
| Jetpack Compose | Android Views (осознанное решение) |

### 2.3 Целевые ОС — специфика

**Android / LineageOS 21+ (AOSP-based):**
- Foreground service обязателен для длительного аудиопроцессинга (Android 14+ restriction)
- `NotificationChannel` создаётся до всех `NotificationCompat.Builder`
- `AudioAttributes.USAGE_MEDIA` для аудиосессий
- `MediaSessionManager` для отслеживания плееров
- `registerReceiver` требует флагов `RECEIVER_EXPORTED`/`RECEIVER_NOT_EXPORTED` (compileSdk 35)
- `DynamicsProcessing` имеет лимит 127 полос (используется полностью)
- Session-0 watchdog — не удалять, это защита от "тихого убийства" сессии системой

**Linux Mint (среда разработки):**
- `./gradlew` — основной инструмент
- JDK 17 (Temurin), установлен через `sdkman` или `apt`
- Android SDK в `~/Android/Sdk/` (указан в `local.properties`)
- `ANDROID_HOME` не обязателен при наличии `local.properties`

### 2.4 Git

- Ветки: `feature/*`, `fix/*`, `docs/*`, `chore/*`
- Сообщения коммитов: `<тип>: <русский текст>` (Conventional Commits)
- Длина заголовка ≤ 72 символа
- Перед коммитом: `git status`, `git diff --stat`, `git diff --check`
- **Запрещён `git push --force`**, `git commit --amend` после push
- **Запрещён `git add -A`** без просмотра diff

---

## 3. Операционные инструкции для агента

### 3.1 Установка и настройка

```bash
# Клонирование (если не склонирован)
git clone https://github.com/ZDarow/Equalizer314.git
cd Equalizer314

# SDK (если local.properties отсутствует)
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
echo "org.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8" >> gradle.properties
```

### 3.2 Сборка

```bash
# Debug APK
./gradlew assembleDebug

# Release APK (minify + ProGuard)
./gradlew assembleRelease

# Только линт
./gradlew lint

# Детект (статический анализ)
./gradlew detekt

# Kover (покрытие)
./gradlew koverXmlReport koverHtmlReport

# Dokka (документация)
./gradlew :app:dokkaHtml
```

### 3.3 Тестирование

```bash
# Unit-тесты
./gradlew testDebugUnitTest

# Конкретный класс
./gradlew testDebugUnitTest --tests "*FFTTest*"

# Инструментальные тесты (требуется эмулятор)
./gradlew connectedDebugAndroidTest

# AndroidTest компиляция (без запуска)
./gradlew compileDebugAndroidTestSources

# Полная верификация (обязательно перед коммитом)
./gradlew assembleDebug testDebugUnitTest compileDebugAndroidTestSources detekt
```

### 3.4 CI (локальный прогон)

```bash
# Симуляция CI pipeline
./gradlew lint detekt testDebugUnitTest assembleDebug assembleRelease
```

### 3.5 Быстрая диагностика

```bash
git status                          # состояние
git log --oneline --graph -10       # история
git diff --stat                     # изменения
git diff --check                    # пробелы
git diff                            # полный diff
git log --oneline upstream/main --not main  # неприменённые upstream коммиты
```

---

## 4. Бэклог задач (Roadmap)

### 🟡 Medium приоритет

| # | Задача | Файлы | Почему |
|---|---|---|---|
| M1 | **EqService lifecycle тест** | `audio/EqService.kt`, `app/src/test/...` | Foreground service — критический компонент без тестовой инфраструктуры |
| M2 | **MainActivity декомпозиция, Фаза 2** (BroadcastReceiver extraction) | `MainActivity.kt` (3762 LOC) | Бог-класс тормозит разработку |
| M3 | **EqGraphView декомпозиция** | `ui/EqGraphView.kt` (1700 LOC) | Сложный кастомный View с бизнес-логикой |
| M4 | **Room миграция v1→v2** (отказ от `fallbackToDestructiveMigration`) | `data/EqDatabase.kt`, `app/schemas/` | Текущая реализация сбрасывает БД при изменении схемы |
| M5 | **Dependabot** (`.github/dependabot.yml`) | `.github/dependabot.yml` | Автоматические обновления через `libs.versions.toml` |

### 🟢 Low приоритет

| # | Задача | Файлы | Почему |
|---|---|---|---|
| L1 | **OverridePendingTransition deprecation** | 15 файлов (~30 вхождений) | Deprecated в API 35 |
| L2 | **scaledDensity deprecation** | `MainActivity.kt`, `PresetCurveView.kt`, `SimpleEqController.kt` | Deprecated в API 35 |
| L3 | **Kover coverage improvement** | все тестовые файлы | Текущее покрытие ~4% |
| L4 | **Fastlane + русская локализация** | `fastlane/` | Только en-US |
| L5 | **CI badge в README** ✅ Уже сделано | `README.md` | Визуальный статус |

### ❌ Не планируется

| Задача | Причина |
|---|---|
| Jetpack Compose миграция | Архитектурное решение: Canvas Views для кастомной отрисовки EQ |
| DI фреймворк (Dagger/Hilt/Koin) | Маленький проект, ручной DI достаточен |
| Upstream tracking | Форк полностью независим (13 коммитов впереди, 0 позади) |
| Multi-module | Единый модуль `app` |

---

## 5. Технический долг и рекомендации

### 5.1 Известные узкие места

| # | Проблема | Серьёзность | Файл(ы) |
|---|---|---|---|
| D1 | **MainActivity — 3762 LOC** | 🟡 Medium | `MainActivity.kt` |
| D2 | **MbcActivity — 1822 LOC** | 🟡 Medium | `MbcActivity.kt` |
| D3 | **Detekt baseline — ~400 suppressed** | 🟡 Medium | `app/detekt-baseline.xml` |
| D4 | **Room fallbackToDestructiveMigration** | 🟡 Medium | `data/EqDatabase.kt:58` |
| D5 | **DSP pipeline без тестов** | 🟡 Medium | `audio/DynamicsProcessingManager.kt` |
| D6 | **`runCatching` с пустым телом** | 🟢 Low | ~8 мест (unregisterReceiver/unbindService) |
| D7 | **Захардкоженные sampleRate=44100** | 🟢 Low | `EqGraphView.kt:591`, `SimpleFFT.kt` |

### 5.2 «Подводные камни» (что НЕЛЬЗЯ делать)

1. **Не удалять Session-0 watchdog** (`EqStateManager.kt:499`). Это защита от Android-бага, когда система убивает аудиосессию 0. Без него EQ будет молча отключаться через 5-30 минут фоновой работы.

2. **Не менять архитектуру Canvas Views на Compose**. Кастомные `onDraw(canvas)` вызовы (`EqGraphView`, `ReverbVisualizerView`, `LimiterWaveformView`) критичны для производительности отрисовки EQ-кривых в реальном времени. Compose не даёт такого контроля.

3. **Не рефакторить BroadcastReceivers из MainActivity без глубокого понимания lifecycle**. Приёмники (`eqStoppedReceiver`, `eqStartedReceiver`, `statusRefreshReceiver`) завязаны на `this@MainActivity`, `stateManager`, `bindService`/`unbindService`. Вынос без полного тестового покрытия гарантированно сломает EQ.

4. **Не менять ProGuard/keep правила** без проверки на реальном устройстве. `-keep class com.bearinmind.equalizer314.** { *; }` — blanket keep, но он работает. Узкие правила ломают Room KSP-генерированные классы.

5. **Не увеличивать `targetSdk`** выше 35, пока не протестированы все новые ограничения Foreground Service (Android 14+).

6. **Не заменять `org.json.JSONObject`** на Gson/Moshi/kotlinx.serialization. `EqSerializer.kt`, `AutoEqParser.kt`, `EqPreferencesManager.kt` — все используют `org.json`. Это осознанный выбор (нулевые зависимости, прямой контроль сериализации).

7. **Не убирать `@Suppress("ClickableViewAccessibility")`** — это Accessibility API, который кастомные Canvas Views не поддерживают без полного переписывания `onTouchEvent` с `AccessibilityDelegate`.

### 5.3 Архитектурные советы

- **Новые Activity** → создавать в корне пакета `com.bearinmind.equalizer314`
- **Новые DSP-алгоритмы** → в `dsp/`, чистый Kotlin без Android-зависимостей
- **Новые UI-компоненты** → в `ui/`, extends `View` или кастомный canvas
- **Состояние** → через `EqViewModel.stateFlow`, избегать прямых вызовов `EqStateManager`
- **Room** → все запросы через `suspend` функции и Flow, никаких LiveData
- **Тесты** → DSP-алгоритмы тестируются unit-тестами (Robolectric не требуется), DAO — Robolectric, UI — Instrumented

### 5.4 Ключевые метрики для принятия решений

```yaml
prod_loc: 32842
test_loc: 1745
android_test_loc: 78
unit_tests: 160
instrumented_tests: 7
detekt_suppressed: ~400
kover_line_coverage: ~4%
largest_files:
  - MainActivity.kt: 3762 LOC
  - MbcActivity.kt: 1822 LOC
  - EqGraphView.kt: 1700 LOC
  - ReverbVisualizerView.kt: 1414 LOC
  - EqService.kt: 1070 LOC
```
