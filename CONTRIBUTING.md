# Вклад в Equalizer314

Спасибо за интерес к проекту! Equalizer314 — это Android-приложение для параметрического эквалайзера с открытым исходным кодом. Этот документ описывает процесс внесения изменений.

## Как начать

1. Клонируйте репозиторий:
   ```bash
   git clone https://github.com/bearinmindcat/Equalizer314.git
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
- **Gradle** — используется Gradle Wrapper (версия определяется `gradle-wrapper.properties`)
- **Android SDK** — API 35 (compileSdk), API 28 (minSdk)

Сборка и тесты запускаются через `./gradlew`, отдельная установка Gradle не требуется.

## Code style

Статический анализ выполняется с помощью **Detekt**:

- Конфигурация: `config/detekt/detekt.yml`
- Базлайн: `app/detekt-baseline.xml` (существующие предупреждения)
- В CI Detekt настроен на **0 пропущенных ошибок** — все новые нарушения должны быть исправлены до мержа

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

Unit-тесты (DSP-модули):
```bash
./gradlew testDebugUnitTest
```

Android Lint:
```bash
./gradlew lint
```

Полная проверка перед отправкой PR:
```bash
./gradlew lint detekt testDebugUnitTest
```

## Процесс PR

1. Создайте ветку от `develop`:
   ```bash
   git checkout develop
   git checkout -b feature/your-feature-name
   ```

2. Ветки именуются по схеме:
   - `feature/...` — новая функциональность
   - `fix/...` — исправление ошибок
   - `refactor/...` — рефакторинг
   - `docs/...` — документация

3. Коммиты должны следовать **Conventional Commits**:
   - `feat: ...` — новая функция
   - `fix: ...` — исправление
   - `refactor: ...` — рефакторинг
   - `docs: ...` — документация
   - `test: ...` — тесты
   - `ci: ...` — CI/CD
   - `chore: ...` — обслуживание

4. Откройте Pull Request в ветку `main`.

5. Убедитесь, что все CI-проверки проходят (см. ниже).

## CI pipeline

GitHub Actions (`ci.yml`) запускается на push в `main`/`develop` и на PR в `main`. Состоит из 5 джобов:

| Джоб | Команда | Артефакт |
|------|---------|----------|
| **Lint + Detekt** | `./gradlew lint detekt` | Detekt HTML report |
| **Unit Tests** | `./gradlew testDebugUnitTest` + `koverXmlReport koverHtmlReport` | Test results, coverage report |
| **Build Debug APK** | `./gradlew assembleDebug` | Debug APK |
| **Dependency Versions** | `./gradlew dependencyUpdates` | Dependency report (JSON) |
| **Build Release APK** | `./gradlew assembleRelease` | Unsigned Release APK |

Все джобы используют **JDK 17** и **Gradle Wrapper**.

## Лицензия

Equalizer314 распространяется под **GNU General Public License v3.0**. Внося изменения, вы соглашаетесь, что ваш код будет опубликован под этой же лицензией. Полный текст — в файле [LICENSE](LICENSE).
