package com.music.bitchord.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.music.bitchord.data.settings.AppSettings
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.exp

@UnstableApi
class SingAlongAudioProcessor : BaseAudioProcessor() {
    private var lpLow = 0f
    private var lpHigh = 0f
    private var aLow = 0.03f
    private var aHigh = 0.45f
    private var smoothCut = 0f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.channelCount != 2) return AudioProcessor.AudioFormat.NOT_SET
        val enc = inputAudioFormat.encoding
        if (enc != C.ENCODING_PCM_16BIT && enc != C.ENCODING_PCM_FLOAT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        val sr = inputAudioFormat.sampleRate.toFloat().coerceAtLeast(8000f)
        aLow = (1f - exp((-2.0 * PI * 210.0 / sr).toFloat())).coerceIn(0.001f, 0.99f)
        aHigh = (1f - exp((-2.0 * PI * 5200.0 / sr).toFloat())).coerceIn(0.01f, 0.99f)
        return inputAudioFormat
    }

    override fun onFlush() {
        lpLow = 0f
        lpHigh = 0f
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val enabled = AppSettings.vocalReductionEnabled.value
        val targetCut = if (enabled) AppSettings.vocalReductionLevel.value.coerceIn(0f, 0.95f) else 0f

        if (!enabled && smoothCut < 0.001f) {
            val out = replaceOutputBuffer(remaining)
            out.put(inputBuffer)
            out.flip()
            return
        }

        val out = replaceOutputBuffer(remaining)
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
            while (inputBuffer.remaining() >= 4) {
                smoothCut += (targetCut - smoothCut) * 0.0015f
                val l = inputBuffer.short.toFloat() / 32768f
                val r = inputBuffer.short.toFloat() / 32768f
                val mid = 0.5f * (l + r)
                val side = 0.5f * (l - r) * (1f + 0.12f * smoothCut)
                lpLow += aLow * (mid - lpLow)
                lpHigh += aHigh * (mid - lpHigh)
                val vocalBand = lpHigh - lpLow
                val newMid = mid - smoothCut * vocalBand
                val outL = ((newMid + side) * 32768f).coerceIn(-32768f, 32767f).toInt().toShort()
                val outR = ((newMid - side) * 32768f).coerceIn(-32768f, 32767f).toInt().toShort()
                out.putShort(outL)
                out.putShort(outR)
            }
        } else {
            while (inputBuffer.remaining() >= 8) {
                smoothCut += (targetCut - smoothCut) * 0.0015f
                val l = inputBuffer.float
                val r = inputBuffer.float
                val mid = 0.5f * (l + r)
                val side = 0.5f * (l - r) * (1f + 0.12f * smoothCut)
                lpLow += aLow * (mid - lpLow)
                lpHigh += aHigh * (mid - lpHigh)
                val vocalBand = lpHigh - lpLow
                val newMid = mid - smoothCut * vocalBand
                out.putFloat((newMid + side).coerceIn(-1f, 1f))
                out.putFloat((newMid - side).coerceIn(-1f, 1f))
            }
        }
        out.flip()
    }
}
