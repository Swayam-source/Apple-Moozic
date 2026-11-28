package com.music.bitchord.playback

import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.music.bitchord.data.settings.AppSettings
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

@UnstableApi
class SingAlongProcessorChain(
    private val delegate: AudioProcessorChain,
) : AudioProcessorChain {
    private val singAlong = SingAlongAudioProcessor()
    private var cached: Array<AudioProcessor>? = null

    override fun getAudioProcessors(): Array<AudioProcessor> {
        val existing = cached
        if (existing != null) return existing
        val base = delegate.audioProcessors
        val combined = Array<AudioProcessor>(base.size + 1) { i ->
            if (i == 0) singAlong else base[i - 1]
        }
        cached = combined
        return combined
    }

    override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters =
        delegate.applyPlaybackParameters(playbackParameters)

    override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean =
        delegate.applySkipSilenceEnabled(skipSilenceEnabled)

    override fun getMediaDuration(playoutDuration: Long): Long =
        delegate.getMediaDuration(playoutDuration)

    override fun getSkippedOutputFrameCount(): Long =
        delegate.skippedOutputFrameCount
}

/**
 * 4096-point High-Resolution Harmonic-Formant & Stereo Vocal Reverb Separator (~11.7 Hz bin resolution).
 *
 * - Isolates narrowband center vocal harmonics from broadband center instruments via local spectral
 *   prominence + 3-bin morphological MAX dilation (capturing the full Hann main lobe without diluting peaks).
 * - Cancels stereo vocal chorus/doubles and 180ms vocal reverb tails in the Side channel on active vocal bins
 *   while keeping stereo instruments (|Side| > 0.62 |Mid|), sub-215 Hz bass/kick, and broadband drum hits at 100%.
 */
@UnstableApi
class SingAlongAudioProcessor : BaseAudioProcessor() {
    companion object {
        private const val FFT_SIZE = 4096
        private const val HALF_BINS = 2049
        private const val HOP_SIZE = 1024
        private const val RING_SIZE = 16384
        private const val RING_MASK = RING_SIZE - 1
        private const val OLA_NORM = 1.5f
    }

    private val window = FloatArray(FFT_SIZE) { i ->
        (0.5 * (1.0 - cos(2.0 * PI * i / FFT_SIZE))).toFloat()
    }
    private val bitRev = IntArray(FFT_SIZE).apply {
        val bits = 12 // log2(4096)
        for (i in 0 until FFT_SIZE) {
            var rev = 0
            var v = i
            for (b in 0 until bits) {
                rev = (rev shl 1) or (v and 1)
                v = v ushr 1
            }
            this[i] = rev
        }
    }
    private val cosTable = FloatArray(FFT_SIZE / 2) { i -> cos(-2.0 * PI * i / FFT_SIZE).toFloat() }
    private val sinTable = FloatArray(FFT_SIZE / 2) { i -> sin(-2.0 * PI * i / FFT_SIZE).toFloat() }

    private val inRingL = FloatArray(RING_SIZE)
    private val inRingR = FloatArray(RING_SIZE)
    private val olaRingL = FloatArray(RING_SIZE)
    private val olaRingR = FloatArray(RING_SIZE)
    private val outQueueL = FloatArray(RING_SIZE)
    private val outQueueR = FloatArray(RING_SIZE)

    private var inWritePos = 0
    private var olaWritePos = 0
    private var outWritePos = 0
    private var outReadPos = 0
    private var hopCounter = 0

    private val fftRe = FloatArray(FFT_SIZE)
    private val fftIm = FloatArray(FFT_SIZE)

    private val midRe = FloatArray(HALF_BINS)
    private val midIm = FloatArray(HALF_BINS)
    private val sideRe = FloatArray(HALF_BINS)
    private val sideIm = FloatArray(HALF_BINS)
    private val midMag = FloatArray(HALF_BINS)
    private val sideMag = FloatArray(HALF_BINS)
    private val prevMidMag = FloatArray(HALF_BINS)

    //Prefix-sum buffer for O(1) local spectral floor & formant envelope calculation
    private val magPrefix = FloatArray(HALF_BINS + 1)
    private val rawVocalMask = FloatArray(HALF_BINS)
    private val dilatedMask = FloatArray(HALF_BINS)
    private val vocalReverbHold = FloatArray(HALF_BINS)
    private val prevMidGain = FloatArray(HALF_BINS) { 1f }
    private val prevSideGain = FloatArray(HALF_BINS) { 1f }

    private var binHz = 48000f / FFT_SIZE
    private var smoothCut = 0f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.channelCount != 2) return AudioProcessor.AudioFormat.NOT_SET
        val enc = inputAudioFormat.encoding
        if (enc != C.ENCODING_PCM_16BIT &&
            enc != C.ENCODING_PCM_FLOAT &&
            enc != C.ENCODING_PCM_24BIT &&
            enc != C.ENCODING_PCM_32BIT
        ) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        binHz = inputAudioFormat.sampleRate.toFloat().coerceAtLeast(8000f) / FFT_SIZE
        return inputAudioFormat
    }

    override fun onFlush() {
        Arrays.fill(inRingL, 0f)
        Arrays.fill(inRingR, 0f)
        Arrays.fill(olaRingL, 0f)
        Arrays.fill(olaRingR, 0f)
        Arrays.fill(outQueueL, 0f)
        Arrays.fill(outQueueR, 0f)
        Arrays.fill(prevMidMag, 0f)
        Arrays.fill(vocalReverbHold, 0f)
        Arrays.fill(prevMidGain, 1f)
        Arrays.fill(prevSideGain, 1f)
        inWritePos = 0
        olaWritePos = 0
        outWritePos = 0
        outReadPos = 0
        hopCounter = 0
    }

    private fun fft(re: FloatArray, im: FloatArray, invert: Boolean) {
        for (i in 0 until FFT_SIZE) {
            val j = bitRev[i]
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        val sign = if (invert) -1f else 1f
        while (len <= FFT_SIZE) {
            val half = len ushr 1
            val step = FFT_SIZE / len
            var i = 0
            while (i < FFT_SIZE) {
                var k = 0
                for (j in 0 until half) {
                    val wr = cosTable[k]
                    val wi = sinTable[k] * sign
                    val uIdx = i + j
                    val vIdx = uIdx + half
                    val vr = re[vIdx] * wr - im[vIdx] * wi
                    val vi = re[vIdx] * wi + im[vIdx] * wr
                    re[vIdx] = re[uIdx] - vr
                    im[vIdx] = im[uIdx] - vi
                    re[uIdx] += vr
                    im[uIdx] += vi
                    k += step
                }
                i += len
            }
            len = len shl 1
        }
        if (invert) {
            val invN = 1f / FFT_SIZE
            for (i in 0 until FFT_SIZE) {
                re[i] *= invN
                im[i] *= invN
            }
        }
    }

    private fun processStftHop(targetCut: Float) {
        smoothCut += (targetCut - smoothCut) * 0.35f
        val cut = smoothCut.coerceIn(0f, 1f)

        // 1. Window latest 4096 samples into Mid (real) and Side (imag)
        val startIn = (inWritePos - FFT_SIZE) and RING_MASK
        for (i in 0 until FFT_SIZE) {
            val idx = (startIn + i) and RING_MASK
            val l = inRingL[idx]
            val r = inRingR[idx]
            val w = window[i]
            fftRe[i] = 0.5f * (l + r) * w
            fftIm[i] = 0.5f * (l - r) * w
        }

        // 2. Forward 4096-point FFT
        fft(fftRe, fftIm, invert = false)

        // 3. Unpack Mid and Side spectra & build prefix sum of Mid magnitude
        magPrefix[0] = 0f
        var onsetBins = 0
        var checkedBins = 0
        for (k in 0 until HALF_BINS) {
            val mirror = if (k == 0 || k == HALF_BINS - 1) k else (FFT_SIZE - k)
            val zReK = fftRe[k]
            val zImK = fftIm[k]
            val zReM = fftRe[mirror]
            val zImM = fftIm[mirror]

            val mr = 0.5f * (zReK + zReM)
            val mi = 0.5f * (zImK - zImM)
            val sr = 0.5f * (zImK + zImM)
            val si = -0.5f * (zReK - zReM)

            midRe[k] = mr; midIm[k] = mi
            sideRe[k] = sr; sideIm[k] = si

            val mMag = sqrt(mr * mr + mi * mi)
            val sMag = sqrt(sr * sr + si * si)
            midMag[k] = mMag
            sideMag[k] = sMag
            magPrefix[k + 1] = magPrefix[k] + mMag

            // Global broadband drum transient check across 1400 Hz - 8500 Hz
            val freq = k * binHz
            if (freq in 1400f..8500f) {
                checkedBins++
                if (mMag > prevMidMag[k] * 2.35f + 1e-5f) {
                    onsetBins++
                }
            }
            prevMidMag[k] = mMag
        }

        // True drum hit spikes >36% of all high-frequency bins simultaneously; sung syllables never do
        val isBroadbandDrumHit = checkedBins > 0 && (onsetBins.toFloat() / checkedBins) > 0.36f

        // 4. Compute per-bin Vocal Harmonic + Formant + Center Coherence Mask
        for (k in 0 until HALF_BINS) {
            val freq = k * binHz
            // Hard-protect sub-205 Hz bass/kick fundamental & >9500 Hz air
            if (freq < 205f || freq > 9500f) {
                rawVocalMask[k] = 0f
                continue
            }

            val mMag = midMag[k]
            val sMag = sideMag[k]

            // Local 3-bin peak magnitude (handles Hann window main lobe straddling two bins)
            val localPeak = max(
                mMag,
                max(
                    if (k > 0) midMag[k - 1] else 0f,
                    if (k + 1 < HALF_BINS) midMag[k + 1] else 0f,
                ),
            )

            // Wide neighborhood average (±22 bins ≈ ±250 Hz) measures broadband instrumental floor
            val lo = max(0, k - 22)
            val hi = min(HALF_BINS - 1, k + 22)
            val broadFloor = (magPrefix[hi + 1] - magPrefix[lo]) / (hi - lo + 1).toFloat()

            // Harmonic peak prominence: vocal harmonics stand above the broadband floor
            val peakRatio = localPeak / (broadFloor + 1e-7f)
            val harmonicProminence = ((peakRatio - 0.85f) / 0.80f).coerceIn(0f, 1f)

            // Linear magnitude stereo panning ratio: |Side| / |Mid|
            // Center vocal has sRatio < 0.35; wide stereo instruments have sRatio > 0.50
            val sRatio = sMag / (localPeak + 1e-7f)
            val centerPanMask = ((0.68f - sRatio) / 0.43f).coerceIn(0f, 1f)

            // Vocal frequency band weight: full strength across 245 Hz - 5200 Hz, tapered edges
            val bandWeight = when {
                freq < 250f -> ((freq - 205f) / 45f).coerceIn(0f, 1f)
                freq <= 5400f -> 1.0f
                else -> ((9500f - freq) / 4100f).coerceIn(0f, 1f)
            }

            // In the core vocal formant zone (280 Hz - 4200 Hz), strongly centered energy is vocal
            val formantCoreBoost = if (freq in 280f..4200f && sRatio < 0.38f) {
                max(harmonicProminence, 0.88f)
            } else {
                harmonicProminence
            }

            val drumProtect = if (isBroadbandDrumHit && freq > 1200f) 0.45f else 1.0f
            rawVocalMask[k] = (centerPanMask * formantCoreBoost * bandWeight * drumProtect).coerceIn(0f, 1f)
        }

        // 5. 3-Bin Morphological MAX Dilation (NEVER a 5-bin mean!) so the entire 3-bin Hann main lobe
        //    of every vocal harmonic receives 100% of the peak vocal mask
        for (k in 0 until HALF_BINS) {
            var maxM = rawVocalMask[k]
            if (k > 0 && rawVocalMask[k - 1] > maxM) maxM = rawVocalMask[k - 1]
            if (k + 1 < HALF_BINS && rawVocalMask[k + 1] > maxM) maxM = rawVocalMask[k + 1]
            if (k > 1 && rawVocalMask[k - 2] * 0.85f > maxM) maxM = rawVocalMask[k - 2] * 0.85f
            if (k + 2 < HALF_BINS && rawVocalMask[k + 2] * 0.85f > maxM) maxM = rawVocalMask[k + 2] * 0.85f
            dilatedMask[k] = maxM
        }

        // 6. Apply deep vocal notch to Mid and stereo vocal double/reverb cancellation to Side
        for (k in 0 until HALF_BINS) {
            val freq = k * binHz
            val vMask = dilatedMask[k]

            // Update 180ms vocal reverb decay envelope per bin (decays by 0.86 per 21ms hop)
            vocalReverbHold[k] = max(vMask, vocalReverbHold[k] * 0.86f)

            // Deep Mid vocal cancellation: reaches strict 0.0f (-inf dB) at cut=1.0 whenever vMask >= 0.35
            val rawMidGain = if (cut > 0.94f && vMask >= 0.35f) {
                0f
            } else {
                val g = (1f - cut * sqrt(vMask)).coerceIn(0f, 1f)
                g * g * g
            }

            // Fast attack (instant vocal mute), smooth release
            val gMid = if (rawMidGain < prevMidGain[k]) {
                rawMidGain * 0.85f + prevMidGain[k] * 0.15f
            } else {
                rawMidGain * 0.45f + prevMidGain[k] * 0.55f
            }
            prevMidGain[k] = gMid

            // Cancel stereo vocal doubles/chorus & reverb tail in Side channel (220 Hz - 5200 Hz)
            // ONLY when Side is quieter than Mid (stereo vocal double/reverb), sparing loud stereo instruments
            val sRatio = sideMag[k] / (midMag[k] + 1e-7f)
            val revMask = vocalReverbHold[k]
            val rawSideGain = if (freq in 220f..5200f && revMask > 0.38f && sRatio < 0.72f) {
                val sideVocalStrength = ((revMask - 0.38f) / 0.62f).coerceIn(0f, 1f) *
                    ((0.72f - sRatio) / 0.50f).coerceIn(0.25f, 1f)
                (1f - cut * 0.92f * sideVocalStrength).coerceIn(0.06f, 1f)
            } else {
                1f
            }
            val gSide = rawSideGain * 0.70f + prevSideGain[k] * 0.30f
            prevSideGain[k] = gSide

            // Gentle compensation on confirmed pure-instrument bins so overall song volume never drops
            val instBoost = if (vMask < 0.18f && freq in 60f..7000f) (1f + 0.18f * cut) else 1f

            val mr = midRe[k] * gMid * instBoost
            val mi = midIm[k] * gMid * instBoost
            val sr = sideRe[k] * gSide * instBoost
            val si = sideIm[k] * gSide * instBoost

            val mirror = if (k == 0 || k == HALF_BINS - 1) k else (FFT_SIZE - k)
            fftRe[k] = mr - si
            fftIm[k] = mi + sr
            if (mirror != k) {
                fftRe[mirror] = mr + si
                fftIm[mirror] = -mi + sr
            }
        }

        // 7. Inverse 4096-point FFT & Hann Overlap-Add
        fft(fftRe, fftIm, invert = true)

        for (i in 0 until FFT_SIZE) {
            val outIdx = (olaWritePos + i) and RING_MASK
            val w = window[i] / OLA_NORM
            val midOut = fftRe[i] * w
            val sideOut = fftIm[i] * w
            olaRingL[outIdx] += midOut + sideOut
            olaRingR[outIdx] += midOut - sideOut
        }

        for (i in 0 until HOP_SIZE) {
            val outIdx = (olaWritePos + i) and RING_MASK
            outQueueL[outWritePos] = olaRingL[outIdx].coerceIn(-1f, 1f)
            outQueueR[outWritePos] = olaRingR[outIdx].coerceIn(-1f, 1f)
            olaRingL[outIdx] = 0f
            olaRingR[outIdx] = 0f
            outWritePos = (outWritePos + 1) and RING_MASK
        }
        olaWritePos = (olaWritePos + HOP_SIZE) and RING_MASK
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun processSamplePair(l: Float, r: Float, targetCut: Float): Long {
        inRingL[inWritePos] = l
        inRingR[inWritePos] = r
        inWritePos = (inWritePos + 1) and RING_MASK
        hopCounter++

        if (hopCounter >= HOP_SIZE) {
            hopCounter = 0
            processStftHop(targetCut)
        }

        val outL: Float
        val outR: Float
        if (outReadPos != outWritePos) {
            outL = outQueueL[outReadPos]
            outR = outQueueR[outReadPos]
            outReadPos = (outReadPos + 1) and RING_MASK
        } else {
            outL = l
            outR = r
        }
        return (outL.toRawBits().toLong() shl 32) or (outR.toRawBits().toLong() and 0xFFFFFFFFL)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val enabled = AppSettings.vocalReductionEnabled.value
        val targetCut = if (enabled) AppSettings.vocalReductionLevel.value.coerceIn(0f, 1f) else 0f

        if (!enabled && smoothCut < 0.001f) {
            val out = replaceOutputBuffer(remaining)
            out.put(inputBuffer)
            out.flip()
            return
        }

        inputBuffer.order(ByteOrder.nativeOrder())
        val out = replaceOutputBuffer(remaining)
        out.order(ByteOrder.nativeOrder())

        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> {
                while (inputBuffer.remaining() >= 4) {
                    val l = inputBuffer.short.toFloat() / 32768f
                    val r = inputBuffer.short.toFloat() / 32768f
                    val packed = processSamplePair(l, r, targetCut)
                    val outL = Float.fromBits((packed ushr 32).toInt())
                    val outR = Float.fromBits(packed.toInt())
                    out.putShort((outL * 32767f).toInt().coerceIn(-32768, 32767).toShort())
                    out.putShort((outR * 32767f).toInt().coerceIn(-32768, 32767).toShort())
                }
            }
            C.ENCODING_PCM_FLOAT -> {
                while (inputBuffer.remaining() >= 8) {
                    val l = inputBuffer.float
                    val r = inputBuffer.float
                    val packed = processSamplePair(l, r, targetCut)
                    out.putFloat(Float.fromBits((packed ushr 32).toInt()))
                    out.putFloat(Float.fromBits(packed.toInt()))
                }
            }
            C.ENCODING_PCM_24BIT -> {
                while (inputBuffer.remaining() >= 6) {
                    val lInt = (inputBuffer.get().toInt() and 0xFF) or
                        ((inputBuffer.get().toInt() and 0xFF) shl 8) or
                        (inputBuffer.get().toInt() shl 16)
                    val rInt = (inputBuffer.get().toInt() and 0xFF) or
                        ((inputBuffer.get().toInt() and 0xFF) shl 8) or
                        (inputBuffer.get().toInt() shl 16)
                    val packed = processSamplePair(lInt / 8388608f, rInt / 8388608f, targetCut)
                    val oL = (Float.fromBits((packed ushr 32).toInt()) * 8388607f).toInt().coerceIn(-8388608, 8388607)
                    val oR = (Float.fromBits(packed.toInt()) * 8388607f).toInt().coerceIn(-8388608, 8388607)
                    out.put(oL.toByte()); out.put((oL shr 8).toByte()); out.put((oL shr 16).toByte())
                    out.put(oR.toByte()); out.put((oR shr 8).toByte()); out.put((oR shr 16).toByte())
                }
            }
            C.ENCODING_PCM_32BIT -> {
                while (inputBuffer.remaining() >= 8) {
                    val l = inputBuffer.int.toFloat() / 2147483648f
                    val r = inputBuffer.int.toFloat() / 2147483648f
                    val packed = processSamplePair(l, r, targetCut)
                    val oL = (Float.fromBits((packed ushr 32).toInt()) * 2147483647f).toInt()
                    val oR = (Float.fromBits(packed.toInt()) * 2147483647f).toInt()
                    out.putInt(oL)
                    out.putInt(oR)
                }
            }
        }
        if (inputBuffer.hasRemaining()) {
            out.put(inputBuffer)
        }
        out.flip()
    }
}
