# Known Limitations

## 1. Session-0 conflicts

**Issue:** Only one app can control audio session 0 at a time. If Wavelet, Poweramp EQ, or another EQ app takes control, Equalizer314 loses its effect.

**Current mitigation:** Watchdog with exponential backoff (15s..60s) automatically reclaims control. A reclaim cooldown prevents tug-of-war with competing apps. However, reclaim is not always instantaneous — brief audio dropouts may occur.

**This is a DynamicsProcessing API limitation** — the same issue affects Wavelet, Poweramp EQ, and RootlessJamesDSP. Tracked in Android issue tracker as a long-standing audio framework quirk.

## 2. Spectrum doesn't fade on pause (phone speaker)

**Issue:** When playing through the phone's built-in speaker and pausing, the spectrum analyzer stays visible instead of fading out.

**Cause:** The Android Visualizer API on session 0 captures the DAC output, which remains active on the phone speaker even when audio is paused. The signal level stays above -60 dBFS silence threshold.

**Works correctly on:** Bluetooth, wired headphones, USB audio.

**Workaround:** Toggle the spectrum visualizer off/on using the button on the graph.

## 3. 127-band limit

**Issue:** DynamicsProcessing API caps at 127 bands/channel. This is a hardware API constraint.

**Current usage:** All 127 bands are allocated. Additional processing (MBC crossover, limiter) shares the same band pool through pre-EQ/post-EQ stages.

## 4. Hidden API (EnvironmentalReverb)

**Issue:** EnvironmentalReverb requires reflective access to `AudioEffect(UUID, UUID, int, int)` constructor on Android 14+.

**Risk:** May fail on future Android versions with `InaccessibleObjectException`. Currently used as a fallback — the reverb feature degrades gracefully if reflection fails.

**Planned fix:** Migrate to public `EnvironmentalReverb.createWithSession()` API where available.

## 5. Spotify / Chrome — blocked audio capture

**Issue:** Some apps (Spotify, Google Chrome, SoundCloud) block internal audio capture, preventing session-based audio processing.

**Affects:** Session-based routing mode (not system-wide mode). System-wide mode (session 0) works with all apps.

**Workaround:** Switch to system-wide mode in Settings → Audio Routing. For Spotify specifically, a ReVanced patch removing the screen capture restriction may help.

## 6. PlaybackListenerService — NotificationListenerService requirement

**Issue:** Session detection for non-broadcasting apps requires `NotificationListenerService` permission (manually granted by the user in system settings).

**Impact:** Apps that don't broadcast audio playback intents won't appear in the Channel Input list until the user grants notification access.
