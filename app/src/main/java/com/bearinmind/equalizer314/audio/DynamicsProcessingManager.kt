package com.bearinmind.equalizer314.audio

import android.media.audiofx.DynamicsProcessing
import android.os.Build
import android.util.Log
import com.bearinmind.equalizer314.BuildConfig
import com.bearinmind.equalizer314.dsp.ParametricEqualizer
import com.bearinmind.equalizer314.dsp.ParametricToDpConverter

/**
 * System-wide EQ using Android's DynamicsProcessing API. Configuration
 * follows the patterns reverse-engineered from Wavelet
 * (com.pittvandewitt.wavelet) and Poweramp Equalizer
 * (com.maxmpz.equalizer):
 *
 *   • 127 bands at Wavelet's exact frequency table (matches AutoEQ
 *     GraphicEQ.txt format byte-for-byte).
 *   • `setPreferredFrameDuration(10 ms)` — short FFT window for clean
 *     transient handling.
 *   • Stage order on creation: limiter → MBC dummy → pre-EQ → enable.
 *   • Atomic per-channel `setPreEqByChannelIndex(ch, Eq)` batch update
 *     (2 binder calls per EQ change vs the legacy 256).
 *   • Preamp + per-channel offset routed through DP's input-gain stage
 *     via `setInputGainbyChannel`, leaving band gains as pure EQ shape.
 *   • `dp.hasControl()` guard on every band write.
 *   • MBC stage always allocated with at least 1 dummy band so the
 *     stage exists even when MBC is user-disabled (Wavelet pattern).
 *
 * Requires API 28+.
 */
class DynamicsProcessingManager {

    companion object {
        private const val TAG = "DynamicsProcessingMgr"
    }

    private var dynamicsProcessing: DynamicsProcessing? = null
    private var currentBandCount = 0
    private var lastEq: com.bearinmind.equalizer314.dsp.ParametricEqualizer? = null
    // Optional right-channel EQ for per-channel mode. When null, lastEq is
    // applied to both channels (original shared behavior).
    private var lastRightEq: com.bearinmind.equalizer314.dsp.ParametricEqualizer? = null
    private var lastReclaimTime = 0L
    private val reclaimCooldownMs = 2000L  // Don't reclaim more than once every 2 seconds
    /** User-configured band count (128..1024); used instead of hardcoded 127. */
    var requestedBandCount: Int = 127
        set(value) { field = value.coerceIn(128, 1024) }

    // @Volatile: read off the main thread by EqService's watchdog and by
    // EqService.isDpRunning mirrors, written from start()/stop().
    @Volatile
    var isActive = false
        private set

    // Preamp
    var preampGainDb: Float = 0f

    // Auto-gain
    var autoGainEnabled: Boolean = false
    var lastAutoGainOffset: Float = 0f
        private set

    // MBC
    var mbcEnabled: Boolean = false
    var mbcBandCount: Int = 3

    // Limiter — defaults match Wavelet's `a6/z.java:105` baseline
    // (1 ms attack, 60 ms release, 10:1 ratio, −2 dB threshold, 0 dB
    // post-gain). EqStateManager will overwrite these from user prefs
    // before start(); these values are the in-class fallback for the
    // very-first call before sync.
    var limiterEnabled: Boolean = true
    var limiterAttackMs: Float = 1f
    var limiterReleaseMs: Float = 60f
    var limiterRatio: Float = 10f
    var limiterThresholdDb: Float = -2f
    var limiterPostGainDb: Float = 0f

    // Channel Side Options — balance + per-channel preamp.
    // Routed through DP's input-gain stage, NOT baked into band gains.
    var channelBalancePercent: Int = 0     // -100..100, 0 = center
    var leftChannelGainDb: Float = 0f      // -12..12
    var rightChannelGainDb: Float = 0f     // -12..12

    // Background thread for the binder calls. Each EQ update issues one
    // setPreEqByChannelIndex transaction per channel; running them on the
    // UI thread blocks both rendering and (under contention) the audio
    // path during a drag. Recreated on each start(); quit on stop().
    private var workerThread: android.os.HandlerThread? = null
    private var workerHandler: android.os.Handler? = null
    @Volatile private var pendingApply: Runnable? = null
    @Volatile private var pendingLimiter: Runnable? = null

    fun start(eq: ParametricEqualizer) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Log.e(TAG, "DynamicsProcessing requires API 28+")
            return
        }

        stop() // Clean up any existing instance

        // (Re-)create the worker thread
        android.os.HandlerThread("EqDpWorker").apply {
            start()
            workerThread = this
            workerHandler = android.os.Handler(this.looper)
        }

        // Use user-configured band count (128..1024) from requestedBandCount.
        // Defaults to 127 for backward compat with Wavelet's frequency table.
        ParametricToDpConverter.setNumBands(requestedBandCount)
        val bandCount = ParametricToDpConverter.numBands
        val variant = DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION
        Log.d(TAG, "DP variant=FREQUENCY bands=$bandCount")

        // MBC stage: always allocate the stage with at least 1 band
        // (dummy disabled passthrough when MBC is user-disabled). Wavelet
        // does this regardless of MBC state — the stage existing seems
        // to be the expected DP usage pattern.
        val mbcStageBandCount = if (mbcEnabled) mbcBandCount else 1
        val configBuilder = DynamicsProcessing.Config.Builder(
            variant,
            2,                  // channel count (stereo)
            true,               // pre-EQ stage enabled
            bandCount,          // pre-EQ band count
            true,               // MBC stage allocated
            mbcStageBandCount,
            false,              // post-EQ disabled
            0,
            true                // limiter stage enabled
        )
        // Explicitly set FFT window length. DP's silent default is
        // typically ~32 ms, which smears bass periods and adds pre/post-
        // echo on transients. 10 ms = ~480-sample FFT @ 48 kHz, the
        // transient-friendly value Wavelet uses for short-frame mode.
        configBuilder.setPreferredFrameDuration(10f)
        val config = configBuilder.build()

        try {
            lastEq = eq
            dynamicsProcessing = DynamicsProcessing(Int.MAX_VALUE, 0, config).apply {
                // Stage population order matches Wavelet's a6/b0.smali:
                // limiter → MBC → pre-EQ → setEnabled. Setting enabled
                // last avoids DP processing audio with default bands
                // before our real values arrive.

                // Limiter for clipping protection
                val limiter = DynamicsProcessing.Limiter(
                    limiterEnabled, limiterEnabled, 0,
                    limiterAttackMs, limiterReleaseMs, limiterRatio,
                    limiterThresholdDb, limiterPostGainDb
                )
                setLimiterByChannelIndex(0, limiter)
                setLimiterByChannelIndex(1, limiter)
                Log.d(TAG, "Limiter config: enabled=$limiterEnabled thresh=$limiterThresholdDb ratio=$limiterRatio attack=$limiterAttackMs release=$limiterReleaseMs postGain=$limiterPostGainDb")

                // Dummy MBC band when user has MBC off — passthrough so
                // the audio is unchanged but the stage reports the
                // band slot DP allocated.
                if (!mbcEnabled) {
                    val dummyMbc = DynamicsProcessing.MbcBand(
                        false,        // enabled = false (passthrough)
                        20000f,       // cutoff well above audible
                        1f, 100f, 1f, 0f, 0f, -120f, 1f, 0f, 0f
                    )
                    setMbcBandByChannelIndex(0, 0, dummyMbc)
                    setMbcBandByChannelIndex(1, 0, dummyMbc)
                }

                // Apply parametric response, then enable. drainPendingApply
                // blocks until the band write lands so DP doesn't briefly
                // run with default bands.
                applyParametricResponse(this, eq)
                drainPendingApply()
                enabled = true

                // Detect when another app disables/overrides our DP and re-attach
                setEnableStatusListener(android.media.audiofx.AudioEffect.OnEnableStatusChangeListener { _, enabled ->
                    if (!enabled && isActive) {
                        reclaimSession()
                    }
                })

                // Detect control status changes (another app taking over session 0)
                setControlStatusListener(android.media.audiofx.AudioEffect.OnControlStatusChangeListener { _, controlGranted ->
                    if (!controlGranted && isActive) {
                        reclaimSession()
                    }
                })
            }
            currentBandCount = bandCount
            isActive = true
            Log.d(TAG, "DynamicsProcessing started with $bandCount bands")
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.e(TAG, "Failed to start DynamicsProcessing", e)
            dynamicsProcessing = null
            isActive = false
        }
    }

    /**
     * Block the calling thread until any pending [applyParametricResponse]
     * worker job has executed. Used in [start] so the band feed lands
     * BEFORE we toggle `enabled = true` — the Wavelet ordering. No-op
     * when no job is queued.
     */
    private fun drainPendingApply() {
        val job = pendingApply ?: return
        val handler = workerHandler ?: return
        // Remove from queue, run synchronously on the caller's thread.
        // Safe because the binder calls inside are thread-agnostic;
        // only ordering matters, not which thread issues them.
        handler.removeCallbacks(job)
        runCatching { job.run() }
    }

    private fun reclaimSession() {
        val now = System.currentTimeMillis()
        if (now - lastReclaimTime < reclaimCooldownMs) return  // Cooldown — don't fight endlessly
        lastReclaimTime = now
        Log.w(TAG, "DynamicsProcessing overridden by another app — reclaiming")
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (isActive && lastEq != null) {
                Log.d(TAG, "Reclaiming DynamicsProcessing")
                start(lastEq!!)
            }
        }, 100)
    }

    /** Force a clean recreate of the live DP on the *current* output
     *  device using the last-applied EQ. Used on output route changes
     *  (BT ↔ speaker etc.) to dodge OEM output-effect conflicts that
     *  otherwise leave the new route muted until DP is re-toggled —
     *  e.g. Pixel Adaptive Sound on Android 14. Equivalent to a manual
     *  power off/on but preserves the current bands. No-op (returns
     *  false) when DP isn't active or has no remembered EQ. Caller is
     *  responsible for re-applying MBC per-band params / bypass after,
     *  same as any other start(). */
    fun reattachActive(): Boolean {
        if (!isActive) return false
        val eq = lastEq ?: return false
        stop()
        start(eq)
        return isActive
    }

    /** True when the EQ is supposed to be live but our session-0 effect has
     *  silently lost control or been disabled by another app / the OEM
     *  audio policy (the "EQ goes flat after switching apps" case that the
     *  OnControl/OnEnable listeners often miss on aggressive ROMs). Safe to
     *  call from any thread: returns false when DP isn't active, and any
     *  native read on a torn-down handle is caught and treated as "lost"
     *  (so the caller does a clean reattach). */
    fun hasLostControl(): Boolean {
        if (!isActive) return false
        val dp = dynamicsProcessing ?: return false
        return try {
            !dp.hasControl() || !dp.enabled
        } catch (e: Throwable) {
            Log.w(TAG, "hasLostControl read threw — treating as lost", e)
            true
        }
    }

    /** Cooldown gate shared with [reclaimSession] (same lastReclaimTime /
     *  reclaimCooldownMs) so the watchdog and the listener path can't both
     *  fire a recreate inside the 2s window. Consumes the window when it
     *  returns true. */
    fun reclaimCooldownElapsed(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastReclaimTime < reclaimCooldownMs) return false
        lastReclaimTime = now
        return true
    }

    fun updateFromEqualizer(eq: ParametricEqualizer) {
        updateFromEqualizers(eq, eq)
    }

    /** Apply potentially-different EQs to the two channels. Pass the same
     *  instance for both in shared/BOTH mode. */
    fun updateFromEqualizers(leftEq: ParametricEqualizer, rightEq: ParametricEqualizer) {
        val dp = dynamicsProcessing ?: return

        // If band count changed, must recreate the DP instance
        if (ParametricToDpConverter.numBands != currentBandCount) {
            Log.d(TAG, "Band count changed ($currentBandCount -> ${ParametricToDpConverter.numBands}), recreating DP")
            lastRightEq = if (leftEq !== rightEq) rightEq else null
            start(leftEq)
            return
        }

        try {
            lastEq = leftEq
            lastRightEq = if (leftEq !== rightEq) rightEq else null
            applyParametricResponse(dp, leftEq, rightEq)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.e(TAG, "Failed to update DynamicsProcessing", e)
        }
    }

    private fun applyParametricResponse(dp: DynamicsProcessing, eq: ParametricEqualizer) {
        applyParametricResponse(dp, eq, eq)
    }

    private fun applyParametricResponse(
        dp: DynamicsProcessing,
        leftEq: ParametricEqualizer,
        rightEq: ParametricEqualizer,
    ) {
        // Cheap math (response sampling) on the caller's thread since it
        // touches the live ParametricEqualizer owned by the UI thread.
        // The expensive part — binder transactions into AudioFlinger —
        // is dispatched to the worker thread.
        //
        // Single conversion path for every UI mode: feature-aware
        // sampling places anchor cutoffs at every filter's centre
        // frequency + per-filter-type support points around each, then
        // fills remaining slots from Wavelet's 127-band table. The gain
        // at each cutoff is `eq.getFrequencyResponse(f)` — the same
        // biquad-summed value the on-screen graph draws. This way the
        // audio always agrees with the graph, regardless of whether the
        // user is editing in parametric / graphic / table / simple mode.
        val l = ParametricToDpConverter.convertFeatureAware(leftEq)
        val cutoffs = l.cutoffs
        val leftGains = l.gains
        val rightGains = if (leftEq === rightEq) leftGains.copyOf()
            else ParametricToDpConverter.convertFeatureAware(rightEq).gains

        // Auto-gain: bring the loudest band to ≤ 0 dB. Applied as a flat
        // shift to all bands so it preserves EQ shape.
        if (autoGainEnabled) {
            lastAutoGainOffset = ChannelMath.computeAutoGainOffset(leftGains, rightGains)
            if (lastAutoGainOffset != 0f) {
                for (i in leftGains.indices) leftGains[i] += lastAutoGainOffset
                for (i in rightGains.indices) rightGains[i] += lastAutoGainOffset
            }
        } else {
            lastAutoGainOffset = 0f
        }

        // Channel offsets and preamp are per-channel flat shifts —
        // they belong on DP's input-gain stage, NOT baked into band
        // gains. Wavelet's a6/b0.smali pattern: setInputGainbyChannel
        // (0, leftSum) and (1, rightSum). Keeps band gains as pure EQ
        // shape so DP's headroom logic doesn't compete with balance.
        val (leftOffsetDb, rightOffsetDb) = ChannelMath.computeChannelOffsets(
            channelBalancePercent, leftChannelGainDb, rightChannelGainDb,
        )

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "[DUMP] preamp=${"%.2f".format(preampGainDb)} dB, " +
                "autoGain=$autoGainEnabled (offset=${"%.2f".format(lastAutoGainOffset)} dB), " +
                "channelOffsets L=${"%.2f".format(leftOffsetDb)} R=${"%.2f".format(rightOffsetDb)} dB, " +
                "bands=${cutoffs.size}")
            val sb = StringBuilder("[DUMP] (cutoff Hz, L gain dB, R gain dB) per band:\n")
            for (i in cutoffs.indices) {
                sb.append("  [%3d] cutoff=%-9.1f L=%+6.2f R=%+6.2f\n"
                    .format(i, cutoffs[i], leftGains[i], rightGains[i]))
            }
            sb.toString().split('\n').forEach { line ->
                if (line.isNotEmpty()) Log.d(TAG, line)
            }
            Log.d(TAG, "[DUMP] Parametric source bands (left EQ):")
            for (i in 0 until leftEq.getBandCount()) {
                val b = leftEq.getBand(i) ?: continue
                Log.d(TAG, "  src[%2d] type=%-12s freq=%-8.1f Hz gain=%+6.2f dB Q=%.3f enabled=%s"
                    .format(i, b.filterType.name, b.frequency, b.gain, b.q, b.enabled))
            }
        }

        val n = ParametricToDpConverter.numBands
        val cutoffsSnap = cutoffs
        // Input gain composition: preamp + per-channel offset.
        // Auto-gain is already baked into band gains above (it's a
        // shape-preserving shift), so don't double-add it here.
        val leftInputGainDb = preampGainDb + leftOffsetDb
        val rightInputGainDb = preampGainDb + rightOffsetDb
        val job = Runnable {
            try {
                // Wavelet calls dp.hasControl() before applying any
                // settings (a6/n0.smali). If another app stole control
                // of session 0, all setters silently no-op without it.
                // Skip the apply — reclaimSession() will recreate when
                // control is regained.
                if (!dp.hasControl()) {
                    Log.w(TAG, "DP lost control — skipping band write")
                    return@Runnable
                }
                // Push preamp + per-channel offset via DP's input-gain
                // stage. Wavelet uses different values per channel
                // (a6/b0.smali:343,379).
                try {
                    dp.setInputGainbyChannel(0, leftInputGainDb)
                    dp.setInputGainbyChannel(1, rightInputGainDb)
                } catch (e: Throwable) {
                    Log.w(TAG, "setInputGainbyChannel failed", e)
                }
                // Atomic per-channel EQ swap. One Eq object per channel
                // → one binder transaction per channel. Audio engine
                // never observes partial state during the update.
                val leftEqObj = DynamicsProcessing.Eq(true, true, n)
                val rightEqObj = DynamicsProcessing.Eq(true, true, n)
                // Defensive guard: ensure all three arrays have the same length.
                // cutoffsSnap and leftGains come from convertFeatureAware() which
                // guarantees equal sizes, but rightGains is a copyOf or separate
                // conversion — verify to avoid ArrayIndexOutOfBounds.
                val minLen = minOf(cutoffsSnap.size, leftGains.size, rightGains.size)
                val actualN = minOf(n, minLen)
                for (i in 0 until actualN) {
                    leftEqObj.setBand(i, DynamicsProcessing.EqBand(true, cutoffsSnap[i], leftGains[i]))
                    rightEqObj.setBand(i, DynamicsProcessing.EqBand(true, cutoffsSnap[i], rightGains[i]))
                }
                dp.setPreEqByChannelIndex(0, leftEqObj)
                dp.setPreEqByChannelIndex(1, rightEqObj)
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Log.e(TAG, "DP band write failed", e)
            } finally {
                pendingApply = null
            }
        }
        val handler = workerHandler ?: return
        pendingApply?.let { handler.removeCallbacks(it) }
        pendingApply = job
        handler.post(job)
    }

    /** Re-apply the current EQ with fresh channel settings (balance, preamp). */
    fun updateChannelSettings() {
        val dp = dynamicsProcessing ?: return
        val eq = lastEq ?: return
        try {
            applyParametricResponse(dp, eq, lastRightEq ?: eq)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.e(TAG, "Failed to update channel settings", e)
        }
    }

    fun updateLimiter() {
        val dp = dynamicsProcessing ?: return
        try {
            val limiter = DynamicsProcessing.Limiter(
                limiterEnabled, limiterEnabled, 0,
                limiterAttackMs, limiterReleaseMs, limiterRatio,
                limiterThresholdDb, limiterPostGainDb
            )
            dp.setLimiterByChannelIndex(0, limiter)
            dp.setLimiterByChannelIndex(1, limiter)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.e(TAG, "Failed to update limiter", e)
        }
    }

    /**
     * Apply MBC band settings from MbcActivity's band data.
     * @param bands List of band parameters: cutoff, attack, release, ratio, threshold, knee, noiseGate, expander, preGain, postGain
     * @param crossovers Crossover frequencies (bands.size - 1)
     */
    fun applyMbcBands(
        bands: List<MbcBandParams>,
        crossovers: FloatArray
    ) {
        val dp = dynamicsProcessing ?: return
        if (!mbcEnabled) return

        try {
            for (i in bands.indices) {
                val b = bands[i]
                val cutoff = if (i < crossovers.size) crossovers[i] else 20000f
                val mbcBand = DynamicsProcessing.MbcBand(
                    b.enabled,
                    cutoff,
                    b.attackMs,
                    b.releaseMs,
                    b.ratio,
                    b.thresholdDb,
                    b.kneeDb,
                    b.noiseGateDb,
                    b.expanderRatio,
                    b.preGainDb,
                    b.postGainDb
                )
                dp.setMbcBandByChannelIndex(0, i, mbcBand)
                dp.setMbcBandByChannelIndex(1, i, mbcBand)
                Log.d(TAG, "MBC band $i: preGain=${b.preGainDb} postGain=${b.postGainDb} threshold=${b.thresholdDb} ratio=${b.ratio} cutoff=$cutoff")
            }

            // Readback
            val readback = dp.getMbcBandByChannelIndex(0, 0)
            Log.d(TAG, "MBC readback band 0: preGain=${readback.preGain} postGain=${readback.postGain} threshold=${readback.threshold}")
            Log.d(TAG, "DP enabled=${dp.enabled}, MBC stage enabled=${dp.getMbcByChannelIndex(0).isEnabled}, bandCount=${dp.getMbcByChannelIndex(0).bandCount}")
            Log.d(TAG, "Applied ${bands.size} MBC bands")
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.e(TAG, "Failed to apply MBC bands", e)
        }
    }

    /** Simple data class for MBC band parameters passed to applyMbcBands */
    data class MbcBandParams(
        val enabled: Boolean = true,
        val attackMs: Float = 1f,
        val releaseMs: Float = 100f,
        val ratio: Float = 2f,
        val thresholdDb: Float = 0f,
        val kneeDb: Float = 8f,
        val noiseGateDb: Float = -60f,
        val expanderRatio: Float = 1f,
        val preGainDb: Float = 0f,
        val postGainDb: Float = 0f
    )

    fun setEnabled(enabled: Boolean) {
        dynamicsProcessing?.enabled = enabled
    }

    /** Apply the current limiter fields to the live DP instance without
     *  rebuilding it. Dispatched to the worker thread so a slider drag
     *  doesn't stall the UI thread on a binder transaction. Coalesced with
     *  the band-write job so back-to-back slider ticks collapse to one
     *  write. Falls back silently when DP isn't running. */
    fun pushLimiterUpdate() {
        val dp = dynamicsProcessing ?: return
        val limiter = DynamicsProcessing.Limiter(
            limiterEnabled, limiterEnabled, 0,
            limiterAttackMs, limiterReleaseMs, limiterRatio,
            limiterThresholdDb, limiterPostGainDb
        )
        val job = Runnable {
            try {
                dp.setLimiterByChannelIndex(0, limiter)
                dp.setLimiterByChannelIndex(1, limiter)
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Log.e(TAG, "Limiter live-update failed", e)
            } finally {
                pendingLimiter = null
            }
        }
        val handler = workerHandler ?: return
        pendingLimiter?.let { handler.removeCallbacks(it) }
        pendingLimiter = job
        handler.post(job)
    }

    fun stop() {
        // Drain any queued band-write before tearing down the DP instance —
        // the runnable would otherwise run against a released native handle.
        workerHandler?.let { handler ->
            pendingApply?.let { handler.removeCallbacks(it) }
            pendingLimiter?.let { handler.removeCallbacks(it) }
        }
        pendingApply = null
        pendingLimiter = null
        try {
            dynamicsProcessing?.enabled = false
            dynamicsProcessing?.release()
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.e(TAG, "Error releasing DynamicsProcessing", e)
        }
        dynamicsProcessing = null
        currentBandCount = 0
        isActive = false
        workerThread?.quitSafely()
        workerThread = null
        workerHandler = null
        Log.d(TAG, "DynamicsProcessing stopped")
    }
}
