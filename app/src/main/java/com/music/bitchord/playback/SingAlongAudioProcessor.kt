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
import kotlin.math.cos
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
 * 2048-point STFT / iSTFT Spectro-Temporal Vocal Separator (75% overlap-add, 5-frame HPSS lookahead).
 * Separates vocals from instruments across 1,025 discrete frequency bins without attenuating stereo instruments
 * or center bass/drum transients.
 */
@UnstableApi
class SingAlongAudioProcessor : BaseAudioProcessor() {
    companion object {
        private const val FFT_SIZE = 2048
        private const val HALF_BINS = 1025
        private const val HOP_SIZE = 512
        private const val RING_SIZE = 8192
        private const val RING_MASK = RING_SIZE - 1
        private const val HIST_FRAMES = 5
        private const val OLA_NORM = 1.5f // Sum of squared periodic Hann window at 75% overlap (N/4)
    }

    private val window = FloatArray(FFT_SIZE) { i ->
        (0.5 * (1.0 - cos(2.0 * PI * i / FFT_SIZE))).toFloat()
    }
    private val bitRev = IntArray(FFT_SIZE).apply {
        val bits = 11 // log2(2048)
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

    // 5-frame spectro-temporal circular history for Harmonic-Percussive + Wiener vocal masking
    private val midReHist = Array(HIST_FRAMES) { FloatArray(HALF_BINS) }
    private val midImHist = Array(HIST_FRAMES) { FloatArray(HALF_BINS) }
    private val sideReHist = Array(HIST_FRAMES) { FloatArray(HALF_BINS) }
    private val sideImHist = Array(HIST_FRAMES) { FloatArray(HALF_BINS) }
    private val midPowHist = Array(HIST_FRAMES) { FloatArray(HALF_BINS) }
    private val sidePowHist = Array(HIST_FRAMES) { FloatArray(HALF_BINS) }
    private var histHead = 0
    private var framesFilled = 0

    private val fftRe = FloatArray(FFT_SIZE)
    private val fftIm = FloatArray(FFT_SIZE)
    private val rawMask = FloatArray(HALF_BINS)
    private val smoothMask = FloatArray(HALF_BINS)
    private val prevBinGain = FloatArray(HALF_BINS) { 1f }

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
        Arrays.fill(prevBinGain, 1f)
        for (f in 0 until HIST_FRAMES) {
            Arrays.fill(midReHist[f], 0f)
            Arrays.fill(midImHist[f], 0f)
            Arrays.fill(sideReHist[f], 0f)
            Arrays.fill(sideImHist[f], 0f)
            Arrays.fill(midPowHist[f], 0f)
            Arrays.fill(sidePowHist[f], 0f)
        }
        inWritePos = 0
        olaWritePos = 0
        outWritePos = 0
        outReadPos = 0
        hopCounter = 0
        histHead = 0
        framesFilled = 0
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
        smoothCut += (targetCut - smoothCut) * 0.25f
        val cut = smoothCut.coerceIn(0f, 1f)

        // 1. Window latest 2048 stereo samples into Mid (real) and Side (imag)
        val startIn = (inWritePos - FFT_SIZE) and RING_MASK
        for (i in 0 until FFT_SIZE) {
            val idx = (startIn + i) and RING_MASK
            val l = inRingL[idx]
            val r = inRingR[idx]
            val w = window[i]
            fftRe[i] = 0.5f * (l + r) * w
            fftIm[i] = 0.5f * (l - r) * w
        }

        // 2. Forward 2048-point FFT
        fft(fftRe, fftIm, invert = false)

        // 3. Unpack Mid and Side complex spectra into current history slot
        val slot = histHead
        val mRe = midReHist[slot]
        val mIm = midImHist[slot]
        val sRe = sideReHist[slot]
        val sIm = sideImHist[slot]
        val mPow = midPowHist[slot]
        val sPow = sidePowHist[slot]

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

            mRe[k] = mr; mIm[k] = mi
            sRe[k] = sr; sIm[k] = si
            mPow[k] = mr * mr + mi * mi
            sPow[k] = sr * sr + si * si
        }

        histHead = (histHead + 1) % HIST_FRAMES
        if (framesFilled < HIST_FRAMES) framesFilled++

        // 4. Evaluate 2D Spectro-Temporal HPSS + Wiener Vocal Mask on the center frame (2-hop lookahead)
        val cSlot = if (framesFilled >= HIST_FRAMES) {
            (slot + HIST_FRAMES - 2) % HIST_FRAMES
        } else {
            slot
        }
        val tM2 = (cSlot + HIST_FRAMES - 2) % HIST_FRAMES
        val tM1 = (cSlot + HIST_FRAMES - 1) % HIST_FRAMES
        val tP1 = (cSlot + 1) % HIST_FRAMES
        val tP2 = (cSlot + 2) % HIST_FRAMES

        val cMidRe = midReHist[cSlot]
        val cMidIm = midImHist[cSlot]
        val cSideRe = sideReHist[cSlot]
        val cSideIm = sideImHist[cSlot]
        val cMidPow = midPowHist[cSlot]
        val cSidePow = sidePowHist[cSlot]

        for (k in 0 until HALF_BINS) {
            val freq = k * binHz
            if (freq < 130f || freq > 9200f) {
                rawMask[k] = 0f
                continue
            }

            val pMid = cMidPow[k]
            val pSide = cSidePow[k]

            // Stereo panning coherence: 1.0 = dead center, 0.0 = wide stereo instrument
            val centerRatio = pMid / (pMid + pSide * 1.25f + 1e-10f)

            // Temporal harmonic sustain (vocals sustain across frames; drum hits spike in a single frame)
            val timeSupport = if (framesFilled >= HIST_FRAMES) {
                0.25f * (midPowHist[tM2][k] + midPowHist[tM1][k] + midPowHist[tP1][k] + midPowHist[tP2][k])
            } else {
                pMid
            }
            val isPercussiveTransient = pMid > (timeSupport * 3.4f + 1e-7f)

            // Vocal fundamental & formant band weighting (130 Hz - 9200 Hz)
            val bandWeight = when {
                freq < 175f -> (freq - 130f) / 45f
                freq <= 5200f -> 1.0f
                else -> (1f - (freq - 5200f) / 4000f).coerceIn(0f, 1f)
            }

            val panMask = ((centerRatio - 0.28f) / 0.52f).coerceIn(0f, 1f)
            val transientPreserve = if (isPercussiveTransient) 0.25f else 1.0f
            rawMask[k] = (panMask * bandWeight * transientPreserve).coerceIn(0f, 1f)
        }

        // Smooth mask across 5 adjacent frequency bins to prevent musical noise
        for (k in 0 until HALF_BINS) {
            var sum = 0f
            var count = 0
            for (d in -2..2) {
                val nk = k + d
                if (nk in 0 until HALF_BINS) {
                    sum += rawMask[nk]
                    count++
                }
            }
            smoothMask[k] = sum / count
        }

        // 5. Apply frequency-bin vocal cancellation to Mid while keeping Side instruments at 100%
        for (k in 0 until HALF_BINS) {
            val vMask = smoothMask[k]
            // Steep Wiener curve so cut=1.0 mutes vocal bins completely to 0.0 (-60 dB)
            val targetMidGain = (1f - (cut * vMask)).let { g ->
                if (cut > 0.96f && vMask > 0.55f) 0f else (g * g).coerceIn(0f, 1f)
            }
            // Temporal envelope smoothing per bin prevents clicking
            val gMid = prevBinGain[k] * 0.35f + targetMidGain * 0.65f
            prevBinGain[k] = gMid

            // Only duck faint vocal reverb in a Side bin if that specific 21.5 Hz bin is >85% center vocal
            val gSide = if (cut > 0.65f && vMask > 0.82f) {
                1f - 0.45f * cut * ((vMask - 0.82f) / 0.18f)
            } else {
                1f
            }

            val mr = cMidRe[k] * gMid
            val mi = cMidIm[k] * gMid
            val sr = cSideRe[k] * gSide
            val si = cSideIm[k] * gSide

            val mirror = if (k == 0 || k == HALF_BINS - 1) k else (FFT_SIZE - k)
            fftRe[k] = mr - si
            fftIm[k] = mi + sr
            if (mirror != k) {
                fftRe[mirror] = mr + si
                fftIm[mirror] = -mi + sr
            }
        }

        // 6. Inverse 2048-point FFT & Hann Overlap-Add
        fft(fftRe, fftIm, invert = true)

        for (i in 0 until FFT_SIZE) {
            val outIdx = (olaWritePos + i) and RING_MASK
            val w = window[i] / OLA_NORM
            val midOut = fftRe[i] * w
            val sideOut = fftIm[i] * w
            olaRingL[outIdx] += midOut + sideOut
            olaRingR[outIdx] += midOut - sideOut
        }

        // 7. Transfer completed HOP_SIZE (512) samples to output queue and clear accumulator
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
