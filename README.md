<h1><img width="100" src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="Equalizer314" align="absmiddle"> Equalizer314</h1>

<p align="center">
  <a href="https://github.com/ZDarow/Equalizer314/actions/workflows/ci.yml"><img src="https://github.com/ZDarow/Equalizer314/actions/workflows/ci.yml/badge.svg" alt="CI Status"></a>
  <a href="https://github.com/ZDarow/Equalizer314/releases/tag/v0.1.0-alpha-2"><img src="https://img.shields.io/github/v/release/ZDarow/Equalizer314?include_prereleases&label=release" alt="Release"></a>
  <a href="https://github.com/ZDarow/Equalizer314/blob/main/LICENSE"><img src="https://img.shields.io/github/license/ZDarow/Equalizer314" alt="License"></a>
  <a href="https://github.com/ZDarow/Equalizer314/releases/latest"><img src="https://img.shields.io/github/downloads/ZDarow/Equalizer314/total?label=downloads" alt="Downloads"></a>
  <a href="https://github.com/ZDarow/Equalizer314/commits/main"><img src="https://img.shields.io/github/last-commit/ZDarow/Equalizer314" alt="Last commit"></a>
</p>

<p align="center">
  <a href="https://github.com/ZDarow/Equalizer314/releases/latest"><img src="https://raw.githubusercontent.com/NeoApplications/Neo-Backup/034b226cea5c1b30eb4f6a6f313e4dadcbb0ece4/badge_github.png" alt="Get it on GitHub" height="70"></a>
  <a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/{%22id%22:%22com.bearinmind.equalizer314%22,%22url%22:%22https://github.com/ZDarow/Equalizer314%22,%22author%22:%22ZDarow%22,%22name%22:%22Equalizer314%22}"><img src="https://raw.githubusercontent.com/ImranR98/Obtainium/b1c8ac6f2ab08497189721a788a5763e28ff64cd/assets/graphics/badge_obtainium.png" alt="Get it on Obtainium" height="70"></a>
</p>

**Equalizer314** — system-wide параметрический эквалайзер для Android с открытым исходным кодом. Использует `android.media.audiofx.DynamicsProcessing` (127 полос) и `Visualizer` API для аудио-визуальной обратной связи. Форк [bearinmindcat/Equalizer314](https://github.com/bearinmindcat/Equalizer314) с существенными архитектурными улучшениями.

## Возможности

- **4 режима EQ**: Parametric, Graphic (31 полоса), Table, Simple (7 полос)
- **127 полос** DSP через `DynamicsProcessing` API
- **Channel Side EQ** — независимая настройка левого/правого канала
- **Multi-band компрессор** (MBC, 1–6 полос) с soft-knee, GR trace, crossover
- **Лимитер** с waveform и LUFS-измерениями
- **Environmental Reverb** — 10 параметров через Android API
- **AutoEQ** — импорт Squiglink/Wavelet/APO, подбор фильтров под target-кривую
- **Привязка пресетов** к устройствам (Bluetooth MAC) и приложениям (audio session)
- **Undo/Redo** для всех режимов EQ
- **Спектроанализатор** (FFT) с различными режимами отображения
- **Импорт/экспорт APO**, конвертация Wavelet/Poweramp EQ
- **Backup/restore** — полный экспорт/импорт всех настроек
- **Foreground service** — постоянная обработка после перезагрузки

## Скриншоты

<p align="center">
  <img width="19%" alt="Screenshot 1" src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.jpg" />
  <img width="19%" alt="Screenshot 2" src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.jpg" />
  <img width="19%" alt="Screenshot 3" src="fastlane/metadata/android/en-US/images/phoneScreenshots/3.jpg" />
  <img width="19%" alt="Screenshot 4" src="fastlane/metadata/android/en-US/images/phoneScreenshots/4.jpg" />
  <img width="19%" alt="Screenshot 5" src="fastlane/metadata/android/en-US/images/phoneScreenshots/5.jpg" />
</p>

<p align="center">
  <img width="24%" alt="Screenshot 6" src="fastlane/metadata/android/en-US/images/phoneScreenshots/6.jpg" />
  <img width="24%" alt="Screenshot 7" src="fastlane/metadata/android/en-US/images/phoneScreenshots/7.jpg" />
  <img width="24%" alt="Screenshot 8" src="fastlane/metadata/android/en-US/images/phoneScreenshots/8.jpg" />
  <img width="24%" alt="Screenshot 9" src="fastlane/metadata/android/en-US/images/phoneScreenshots/9.jpg" />
</p>

## Сборка

```bash
git clone https://github.com/ZDarow/Equalizer314.git
cd Equalizer314
./gradlew assembleDebug
```

**Требования:** JDK 17, Android SDK 35 (compileSdk), SDK 28 (minSdk).

## Архитектура

| Компонент | Технология |
|---|---|
| Язык | 100% Kotlin |
| UI | Android Views + кастомные Canvas View (`EqGraphView`, `ReverbVisualizerView`) |
| Аудиоядро | `DynamicsProcessing` + `EnvironmentalReverb` (API 28+) |
| Состояние | `EqViewModel` (StateFlow) + `EqStateManager` |
| Хранилище | Room (KSP) — SharedPreferences мигрированы |
| Сборка | Gradle 8.x + Kotlin DSL + Version Catalog |
| DI | Ручной (singleton через companion object) |
| Стат. анализ | Detekt + baseline |
| Покрытие | Kover (~4% line, 160 unit-тестов) |
| CI/CD | GitHub Actions (7 jobs) |

Подробнее см. [CHANGELOG.md](CHANGELOG.md) и [AGENTS.md](AGENTS.md).

## Известные проблемы

- **Session-0 конфликты**: только одно приложение может контролировать глобальную аудиосессию. При конфликте с Wavelet/Poweramp EQ автоперехват не всегда срабатывает. Встроен watchdog с экспоненциальным backoff.
- **Спектр не затухает на паузе (динамик)**: аппаратное ограничение Visualizer API — DAC телефона не замолкает полностью при паузе. На Bluetooth/проводных наушниках работает корректно.
- **Ограничение 127 полос**: DynamicsProcessing API не позволяет больше. Текущая реализация использует максимум.
- **Hidden API (EnvironmentalReverb)**: для расширенных функций реверберации используется рефлексия — может не работать на Android 14+.
- **Spotify/Chrome**: приложения, блокирующие внутренний захват аудио, не обрабатываются (аналогично Wavelet).
- **PlaybackListenerService**: для определения сессий приложений, не транслирующих broadcast-интенты, требуется ручное разрешение `NotificationListenerService`.

## Лицензия

Equalizer314 распространяется под **GNU General Public License v3.0**. См. [LICENSE](LICENSE).

## Благодарности

- [Audio EQ Cookbook](https://www.w3.org/TR/audio-eq-cookbook/) — биквадратные фильтры
- [Matched Second Order Digital Filters](https://www.vicanek.de/articles/BiquadFits.pdf) — формулы Vicanek
- [AutoEq](https://github.com/jaakkopasanen/AutoEq) — target-кривые и подбор фильтров
- [Digital Dynamic Range Compressor Design](https://www.eecs.qmul.ac.uk/~josh/documents/2012/GiannoulisMassbergReiss-dynamicrangecompression-JAES2012.pdf) — компрессор с soft-knee
- [Linkwitz–Riley crossover](https://en.wikipedia.org/wiki/Linkwitz%E2%80%93Riley_filter) — кроссовер MBC
- [RootlessJamesDSP](https://github.com/timschneeb/RootlessJamesDSP) — референс для system-wide EQ
- [JamesDSP](https://github.com/james34602/JamesDSPManager) — DSP-движок (вдохновение для архитектуры)