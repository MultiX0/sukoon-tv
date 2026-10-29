package dev.multix.tvhome.ui

import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import android.os.SystemClock
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * What the TV is playing, reduced to a few numbers for the lyrics visuals:
 * bass, vocal and high energy envelopes, a 96 band spectrum and a tempo
 * estimate. It reads the output mix through Visualizer(0), which needs
 * RECORD_AUDIO (granted over adb by the owner). Nothing is recorded or kept;
 * each FFT frame is reduced to these numbers and dropped.
 *
 * Without the permission, or while the output is silent, [live] is false and
 * callers fall back to motion from the song's seed and the lyric rhythm.
 */
object Beat {
    const val BANDS = 96

    @Volatile var bass = 0f
        private set
    @Volatile var vocal = 0f
        private set
    @Volatile var high = 0f
        private set
    /** Smoothed band levels, 0..1, low to high. Written on the main thread only. */
    val spectrum = FloatArray(BANDS)
    /** Beats per minute from bass onsets, 0 when unknown. */
    @Volatile var bpm = 0f
        private set

    @Volatile private var lastSignal = 0L
    val live: Boolean get() = SystemClock.uptimeMillis() - lastSignal < 3000

    private var viz: Visualizer? = null
    private var users = 0
    private var sampleRate = 48_000
    private var capture = 1024
    private var bandLo = IntArray(BANDS)
    private var bandHi = IntArray(BANDS)
    private val gains = floatArrayOf(4f, 4f, 4f)
    private val bandPeak = FloatArray(BANDS) { 4f }
    private val onsets = FloatArray(160)
    private var onsetPos = 0
    private var prevBass = 0f
    private var frames = 0

    fun acquire(ctx: Context) {
        users++
        if (users == 1) start(ctx)
    }

    fun release() {
        users = max(0, users - 1)
        if (users == 0) stop()
    }

    private fun start(ctx: Context) {
        if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        try {
            val v = Visualizer(0)
            val range = Visualizer.getCaptureSizeRange()
            capture = range[1].coerceAtMost(1024).coerceAtLeast(range[0])
            v.captureSize = capture
            sampleRate = (v.samplingRate / 1000).coerceAtLeast(8000)
            mapBands()
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(v: Visualizer?, w: ByteArray?, rate: Int) {}
                override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, rate: Int) {
                    if (fft != null) onFft(fft)
                }
            }, minOf(Visualizer.getMaxCaptureRate(), 20_000), false, true)
            v.enabled = true
            viz = v
        } catch (t: Throwable) {
            viz = null
        }
    }

    private fun bin(hz: Float): Int = (hz * capture / sampleRate).toInt().coerceIn(1, capture / 2 - 1)

    /** Log-spaced bands from 60 Hz to 12 kHz. */
    private fun mapBands() {
        val lo = ln(60.0)
        val hi = ln(12_000.0)
        for (b in 0 until BANDS) {
            val f0 = Math.exp(lo + (hi - lo) * b / BANDS).toFloat()
            val f1 = Math.exp(lo + (hi - lo) * (b + 1) / BANDS).toFloat()
            bandLo[b] = bin(f0)
            bandHi[b] = max(bandLo[b], bin(f1))
        }
    }

    private fun mag(fft: ByteArray, k: Int): Float {
        val re = fft[2 * k].toFloat()
        val im = fft[2 * k + 1].toFloat()
        return sqrt(re * re + im * im)
    }

    private fun range(fft: ByteArray, f0: Float, f1: Float): Float {
        val a = bin(f0)
        val b = max(a, bin(f1))
        var e = 0f
        for (k in a..b) e += mag(fft, k)
        return e / (b - a + 1)
    }

    private fun env(cur: Float, x: Float, attack: Float, release: Float) =
        cur + (x - cur) * (if (x > cur) attack else release)

    private fun onFft(fft: ByteArray) {
        val b = range(fft, 20f, 150f)
        val v = range(fft, 300f, 3000f)
        val h = range(fft, 6000f, 12_000f)
        if (b + v + h > 1.5f) lastSignal = SystemClock.uptimeMillis()
        // Automatic gain per group: the loudest recent frame maps to 1.
        gains[0] = max(gains[0] * 0.996f, max(b, 4f))
        gains[1] = max(gains[1] * 0.996f, max(v, 4f))
        gains[2] = max(gains[2] * 0.996f, max(h, 2f))
        val nb = (b / gains[0]).coerceIn(0f, 1f)
        bass = env(bass, nb, 0.6f, 0.12f)
        vocal = env(vocal, (v / gains[1]).coerceIn(0f, 1f), 0.35f, 0.08f)
        high = env(high, (h / gains[2]).coerceIn(0f, 1f), 0.5f, 0.2f)
        for (i in 0 until BANDS) {
            var e = 0f
            for (k in bandLo[i]..bandHi[i]) e += mag(fft, k)
            e /= (bandHi[i] - bandLo[i] + 1)
            bandPeak[i] = max(bandPeak[i] * 0.995f, max(e, 2f))
            spectrum[i] = env(spectrum[i], (e / bandPeak[i]).coerceIn(0f, 1f), 0.5f, 0.15f)
        }
        // Tempo: autocorrelate bass onsets over the last eight seconds (20 Hz).
        val onset = max(0f, nb - prevBass)
        prevBass = nb
        onsets[onsetPos] = onset
        onsetPos = (onsetPos + 1) % onsets.size
        if (++frames % 40 == 0) estimateTempo()
    }

    private fun estimateTempo() {
        var best = 0f
        var bestLag = 0
        val n = onsets.size
        for (lag in 7..20) {
            var s = 0f
            for (i in 0 until n - lag) s += onsets[(onsetPos + i) % n] * onsets[(onsetPos + i + lag) % n]
            if (s > best) {
                best = s
                bestLag = lag
            }
        }
        bpm = if (best > 0.05f && bestLag > 0) 60f / (bestLag * 0.05f) else 0f
    }

    private fun stop() {
        try {
            viz?.enabled = false
            viz?.release()
        } catch (t: Throwable) {
        }
        viz = null
        bass = 0f
        vocal = 0f
        high = 0f
        bpm = 0f
        spectrum.fill(0f)
    }
}
