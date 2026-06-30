# F-Droid submission materials for Equalizer314

Этот каталог содержит материалы, связанные с публикацией Equalizer314 через F-Droid.

**Примечание:** Форк [ZDarow/Equalizer314](https://github.com/ZDarow/Equalizer314) использует собственную подпись APK (ключ форка). Для публикации на F-Droid требуется **новый MR** в `fdroiddata` с обновлёнными ключами. Нижеперечисленные документы наследуют опыт первоначальной публикации от upstream-проекта.

- **`com.bearinmind.equalizer314.yml`** — черновик рецепта для fork-версии (ZDarow).
- **`SUBMISSION_JOURNAL.md`** — хронология первоначальной отправки от upstream `bearinmindcat`. Полезна для понимания типичных проблем F-Droid CI.
- **`RELEASE_PROCESS.md`** — пошаговый план вырезания нового релиза (адаптирован от upstream).
- **`RECIPE_FIELD_REFERENCE.md`** — что означает каждое поле YAML, соглашения о расположении, форматирование `fdroid rewritemeta`.
- **`REPRODUCIBLE_BUILDS.md`** — почему важны воспроизводимые сборки, проблема CRLF/LF и как `.gitattributes` её решает.

## Текущее состояние форка

- **Upstream MR:** https://gitlab.com/fdroid/fdroiddata/-/merge_requests/36655 (от bearinmindcat)
- **Форк — требуется новый MR** с ключом ZDarow
- **Версия:** 0.1.0-alpha-2 (`versionCode 100`) — измерение АЧХ, русская локализация, 184 теста
- **Подпись:** ключи форка (см. `com.bearinmind.equalizer314.yml`)

## Историческое состояние (upstream)

- **Tracked version:** 0.0.3-beta (`versionCode 3`)
- **Source commit pinned:** `43caa38b1fb6307c05a5d983a3aafb58387d77cf`
- **Signing cert SHA-256:** `7a8368d18ad64294f9aadf4b736adcd15cb0cb88c6b9dc2e0bd5f1e461b83e52`

## Recipe field convention

Order matters only for readability (F-Droid parses any order), but follow the accepted-recipe convention seen in apps like `androdns.android.leetdreams.ch.androdns.yml`:

1. `Categories`, `License`, `AuthorName`, `SourceCode`, `IssueTracker`
2. `AutoName`
3. `RepoType`, `Repo`, **`Binaries`** (folded onto next line — `rewritemeta` requires a trailing space after the colon and the URL on an indented line)
4. `Builds:` list
5. **`AllowedAPKSigningKeys`** (after `Builds:`, lowercase hex, no colons)
6. `AutoUpdateMode`, `UpdateCheckMode`, `CurrentVersion`, `CurrentVersionCode`
