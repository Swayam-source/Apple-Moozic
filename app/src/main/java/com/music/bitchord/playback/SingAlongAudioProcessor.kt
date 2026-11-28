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
import kotlin.math.exp

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

@UnstableApi
class SingAlongAudioProcessor : BaseAudioProcessor() {
    // 4th-order (2-pole cascaded) Linkwitz-Riley low-pass at 140 Hz for mono bass/kick preservation
    private var bassLp1 = 0f
    private var bassLp2 = 0f
    // High-pass crossover at 7200 Hz to preserve center hi-hats/air
    private var airLp = 0f
    // Stereo side-channel vocal formant band (280 Hz - 3600 Hz) to suppress stereo vocal reverb/doubles
    private var sideLpLow = 0f
    private var sideLpHigh = 0f
    private var vocalEnv = 0f

    private var aBass = 0.018f
    private var aAir = 0.60f
    private var aSideLow = 0.036f
    private var aSideHigh = 0.37f
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
        val sr = inputAudioFormat.sampleRate.toFloat().coerceAtLeast(8000f)
        aBass = (1f - exp((-2.0 * PI * 140.0 / sr).toFloat())).coerceIn(0.001f, 0.99f)
        aAir = (1f - exp((-2.0 * PI * 7200.0 / sr).toFloat())).coerceIn(0.01f, 0.99f)
        aSideLow = (1f - exp((-2.0 * PI * 280.0 / sr).toFloat())).coerceIn(0.001f, 0.99f)
        aSideHigh = (1f - exp((-2.0 * PI * 3600.0 / sr).toFloat())).coerceIn(0.01f, 0.99f)
        return inputAudioFormat
    }

    override fun onFlush() {
        bassLp1 = 0f
        bassLp2 = 0f
        airLp = 0f
        sideLpLow = 0f
        sideLpHigh = 0f
        vocalEnv = 0f
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun processFrame(l: Float, r: Float, targetCut: Float): Long {
        smoothCut += (targetCut - smoothCut) * 0.0025f
        val mid = 0.5f * (l + r)
        val side = 0.5f * (l - r)

        // 1. Preserve < 140 Hz mono kick & sub-bass
        bassLp1 += aBass * (mid - bassLp1)
        bassLp2 += aBass * (bassLp1 - bassLp2)
        val midBass = bassLp2

        // 2. Preserve > 7200 Hz center air/cymbals
        airLp += aAir * (mid - airLp)
        val midAir = mid - airLp

        // 3. Cancel 140 Hz - 7200 Hz center lead vocal with quadratic depth curve
        val midVocal = airLp - midBass
        val rem = (1f - smoothCut).coerceIn(0f, 1f)
        val vocalGain = rem * rem
        val newMid = midBass + midAir * (1f - 0.35f * smoothCut) + vocalGain * midVocal

        // 4. Suppress stereo vocal chorus/reverb in the 280 Hz - 3600 Hz side band
        sideLpLow += aSideLow * (side - sideLpLow)
        sideLpHigh += aSideHigh * (side - sideLpHigh)
        val sideVocalBand = sideLpHigh - sideLpLow

        val instDom = (abs(midVocal) / (abs(midVocal) + abs(side) * 0.8f + 0.0001f)).coerceIn(0f, 1f)
        val envRate = if (instDom > vocalEnv) 0.02f else 0.001f
        vocalEnv += envRate * (instDom - vocalEnv)

        val sideDuck = (smoothCut * 0.72f * vocalEnv).coerceIn(0f, 0.78f)
        val cleanedSide = side - sideDuck * sideVocalBand

        // 5. Apply instrumental makeup gain & soft clip protection
        val makeup = 1f + 0.26f * smoothCut
        val outL = ((newMid + cleanedSide * makeup)).coerceIn(-1f, 1f)
        val outR = ((newMid - cleanedSide * makeup)).coerceIn(-1f, 1f)
        return (outL.toRawBits().toLong() shl 32) or (outR.toRawBits().toLong() and 0xFFFFFFFFL)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val enabled = AppSettings.vocalReductionEnabled.value
        val targetCut = if (enabled) AppSettings.vocalReductionLevel.value.coerceIn(0f, 1f) else 0f

        if (!enabled && smoothCut < 0.0005f) {
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
                    val packed = processFrame(l, r, targetCut)
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
                    val packed = processFrame(l, r, targetCut)
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
                    val packed = processFrame(lInt / 8388608f, rInt / 8388608f, targetCut)
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
                    val packed = processFrame(l, r, targetCut)
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
