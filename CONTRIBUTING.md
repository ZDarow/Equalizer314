# Вклад в Equalizer314

Спасибо за интерес к проекту! Equalizer314 — это Android-приложение для system-wide параметрического эквалайзера с открытым исходным кодом.

## Как начать

1. Клонируйте репозиторий:
   ```bash
   git clone https://github.com/ZDarow/Equalizer314.git
   cd Equalizer314
   ```

2. Откройте проект в Android Studio.

3. Соберите debug-APK:
   ```bash
   ./gradlew assembleDebug
   ```

## Требования к среде

- **Android Studio** Hedgehog (2023.1.1) или новее
- **JDK 17** (Temurin/OpenJDK 17)
- **Gradle 8.9** — используется Gradle Wrapper (`./gradlew`)
- **Kotlin** 2.1.0 + KSP 2.1.0-1.0.29
- **Android SDK** — API 35 (compileSdk, targetSdk), API 28 (minSdk)

Сборка и тесты запускаются через `./gradlew`, отдельная установка Gradle не требуется.

## Code style

Статический анализ выполняется с помощью **Detekt**:

- Конфигурация: `config/detekt/detekt.yml`
- Базлайн: `app/detekt-baseline.xml` (~400 suppressed)
- В CI Detekt настроен на **0 пропущенных ошибок**

Запуск анализа:
```bash
./gradlew detekt
```

Покрытие кода измеряется через **Kover**:
```bash
./gradlew koverXmlReport koverHtmlReport
```

Отчёты формируются в `app/build/reports/kover/`.

## Как запустить тесты

Unit-тесты (DSP-модули, Room DAO, парсеры):
```bash
./gradlew testDebugUnitTest
```

Android Lint:
```bash
./gradlew lint
```

Полная проверка перед отправкой PR:
```bash
./gradlew lint detekt testDebugUnitTest assembleDebug
```

## Процесс PR

1. Создайте ветку от `main`:
   ```bash
   git checkout main
   git checkout -b feature/your-feature-name
   ```

2. Ветки именуются по схеме:
   - `feature/...` — новая функциональность
   - `fix/...` — исправление ошибок
   - `refactor/...` — рефакторинг
   - `docs/...` — документация
   - `perf/...` — оптимизация производительности
   - `ci/...` — CI/CD

3. Commit message на русском, в повелительном наклонении, ≤72 символа:
   ```
   fix: исправлять потерю сессии при переключении Bluetooth
   feat: добавлять поддержку 31-полосного Graphic EQ
   refactor: выносить BroadcastReceiver из MainActivity
   ```

4. Откройте Pull Request в ветку `main`.

5. Убедитесь, что все CI-проверки проходят.

## CI pipeline

GitHub Actions (`ci.yml`) запускается на push в `main`/`develop` и на PR в `main`. Состоит из 7 джобов:

| Джоб | Команда | Артефакт |
|------|---------|----------|
| **Lint + Detekt** | `./gradlew lint detekt` | Detekt HTML report |
| **Unit Tests** | `./gradlew testDebugUnitTest` + kover | Test results, coverage |
| **Build Debug APK** | `./gradlew assembleDebug` | Debug APK |
| **Instrumented Tests** | `./gradlew connectedDebugAndroidTest` (API 28) | Test results |
| **Dokka Docs** | `./gradlew :app:dokkaHtml` | HTML documentation |
| **Dependency Check** | `./gradlew dependencyUpdates` | JSON report |
| **Build Release APK** | `./gradlew assembleRelease` (unsigned) | Unsigned Release APK |

Все джобы используют **JDK 17** (Temurin), **Gradle Wrapper** и **Gradle cache** (read-only на PR).

## Лицензия

Equalizer314 распространяется под **GNU General Public License v3.0**. Внося изменения, вы соглашаетесь, что ваш код будет опубликован под этой же лицензией. Полный текст — в файле [LICENSE](LICENSE).