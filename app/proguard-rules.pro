# ── Equalizer314 ProGuard / R8 Rules ──────────────────────────────
# This file is used by R8 (the default shrinker/optimizer in AGP 8+)
# when isMinifyEnabled = true in the release build type.
#
# R8 shrinks, optimises and obfuscates release builds. These rules
# tell R8 which classes and members must be kept as-is.

# ── Keep all classes in the app package ───────────────────────────
# This covers:
#   • All Activities, Services, BroadcastReceivers, ContentProviders
#     declared in AndroidManifest.xml
#   • Room @Entity, @Dao, @Database classes (data package)
#   • Application class (EqualizerApp)
#   • TileService (Eq314TileService)
#   • All UI components, state management, DSP code
-keep class com.bearinmind.equalizer314.** { *; }

# ── Kotlin Coroutines ─────────────────────────────────────────────
# R8 handles coroutines correctly out of the box since AGP 8.x.
# No additional rules needed.

# ── LeakCanary (debug only — not affected by release minify) ─────
# LeakCanary is debugImplementation and excluded from release builds.

# ── Room (KSP-generated _Impl classes) ────────────────────────────
# Room uses KSP to generate *_Impl classes at compile time. The
# blanket -keep rule above already covers them. If more specific
# rules were needed:
#   -keep class * extends androidx.room.RoomDatabase
#   -keep @androidx.room.Entity class *
#   -keep @androidx.room.Dao class *
