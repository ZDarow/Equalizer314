# ── Equalizer314 ProGuard / R8 Rules ──────────────────────────────
# This file is used by R8 (the default shrinker/optimizer in AGP 8+)
# when isMinifyEnabled = true in the release build type.
#
# R8 shrinks, optimises and obfuscates release builds. These rules
# tell R8 which classes and members must be kept as-is.

# ── Equalizer314 ProGuard / R8 Rules (точечные keep-правила) ─────
# ВАЖНО: blanket-правила заменены на точечные, чтобы R8 мог
# обфусцировать и оптимизировать внутренние классы. Если новый
# компонент ломается при minify — добавьте правило под конкретный
# паттерн, а не возвращайте blanket.

# ── Activities, Services, BroadcastReceivers, ContentProviders ──
# Все компоненты из AndroidManifest.xml.
-keep class * extends android.app.Activity
-keep class * extends android.app.Service
-keep class * extends android.content.BroadcastReceiver
-keep class * extends android.app.Application

# ── TileService ──────────────────────────────────────────────────
-keep class com.bearinmind.equalizer314.audio.Eq314TileService

# ── Room (KSP-generated _Impl) ───────────────────────────────────
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao class *
-keep class com.bearinmind.equalizer314.data.** { *; }
-keep class com.bearinmind.equalizer314.dsp.EqSerializer { *; }

# ── Kotlin Coroutines ─────────────────────────────────────────────
# R8 handles coroutines correctly out of the box since AGP 8.x.

# ── LeakCanary (debug only) ──────────────────────────────────────
# debugImplementation — не затрагивает release сборку.
