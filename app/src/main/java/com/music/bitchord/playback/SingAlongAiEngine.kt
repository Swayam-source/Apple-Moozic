package com.music.bitchord.playback

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.music.bitchord.data.Http
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * On-device ONNX Runtime AI engine for UVR_MDXNET_KARA.onnx (Ultimate Vocal Remover Karaoke MDX-Net).
 * Downloads the 29.7 MB ONNX model once into internal storage, initializes an NNAPI-accelerated
 * OrtSession, and runs background neural spectrogram inference on rolling stereo audio blocks to
 * drive real-time vocal stem suppression masks.
 */
object SingAlongAiEngine {
    private const val MODEL_FILE_NAME = "UVR_MDXNET_KARA.onnx"
    private val MODEL_URLS = listOf(
        "https://huggingface.co/Tha456/uvr5-models/resolve/main/UVR_MDXNET_KARA.onnx",
        "https://huggingface.co/Acapellas/uvr5-models/resolve/main/UVR_MDXNET_KARA.onnx",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null

    private val downloading = AtomicBoolean(false)
    private val inferring = AtomicBoolean(false)

    @Volatile var modelReady: Boolean = false
        private set

    // 4-band AI vocal presence masks (Low-Mid 160-450Hz, Core 450-1400Hz, Formant 1400-4200Hz, Air >4200Hz)
    @Volatile var aiMaskBand1: Float = 1.0f
    @Volatile var aiMaskBand2: Float = 1.0f
    @Volatile var aiMaskBand3: Float = 1.0f
    @Volatile var aiMaskBand4: Float = 0.65f

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var modelDimF: Int = 2048
    private var modelDimT: Int = 256

    fun init(context: Context) {
        appContext = context.applicationContext
        val file = modelFile(context)
        if (file != null && file.exists() && file.length() > 10_000_000L) {
            scope.launch { loadSession(file) }
        }
    }

    private fun modelFile(context: Context? = appContext): File? {
        val ctx = context ?: return null
        val dir = File(ctx.filesDir, "ai_models").apply { mkdirs() }
        return File(dir, MODEL_FILE_NAME)
    }

    private fun toast(msg: String) {
        val ctx = appContext ?: return
        mainHandler.post { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
    }

    fun ensureReady() {
        if (modelReady || downloading.get()) return
        val file = modelFile() ?: return
        if (file.exists() && file.length() > 10_000_000L) {
            scope.launch { loadSession(file) }
            return
        }
        if (!downloading.compareAndSet(false, true)) return
        scope.launch {
            try {
                toast("Sing-Along AI: Downloading UVR neural model (29 MB)…")
                val tmp = File(file.parentFile, "$MODEL_FILE_NAME.tmp")
                var success = false
                for (url in MODEL_URLS) {
                    runCatching {
                        val req = Request.Builder().url(url).build()
                        Http.client.newCall(req).execute().use { res ->
                            if (!res.isSuccessful) return@use
                            val body = res.body ?: return@use
                            body.byteStream().use { input ->
                                FileOutputStream(tmp).use { output ->
                                    val buf = ByteArray(65_536)
                                    while (true) {
                                        val r = input.read(buf)
                                        if (r <= 0) break
                                        output.write(buf, 0, r)
                                    }
                                    output.flush()
                                }
                            }
                            if (tmp.length() > 10_000_000L) {
                                if (file.exists()) file.delete()
                                tmp.renameTo(file)
                                success = true
                            }
                        }
                    }
                    if (success) break
                }
                if (success) {
                    loadSession(file)
                    if (modelReady) toast("Sing-Along AI: UVR Neural Engine Active")
                }
            } finally {
                downloading.set(false)
            }
        }
    }

    @Synchronized
    private fun loadSession(file: File) {
        if (modelReady) return
        runCatching {
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(4)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                runCatching { addNnapi() }
            }
            val session = env.createSession(file.absolutePath, opts)
            val info = session.inputInfo.values.firstOrNull()?.info as? TensorInfo
            val shape = info?.shape
            if (shape != null && shape.size == 4) {
                if (shape[2] > 0) modelDimF = shape[2].toInt()
                if (shape[3] > 0) modelDimT = shape[3].toInt()
            }
            ortEnv = env
            ortSession = session
            modelReady = true
        }
    }

    /**
     * Submits a snapshot of recent stereo audio to the ONNX UVR_MDXNET_KARA neural network
     * on a background thread. The network outputs the predicted Instrumental spectrogram,
     * from which we compute the 4-band vocal-to-instrumental suppression ratios.
     */
    fun submitAudioWindow(leftWindow: FloatArray, rightWindow: FloatArray, sampleRate: Int) {
        if (!AppSettings.vocalReductionEnabled.value) return
        if (!modelReady) {
            ensureReady()
            return
        }
        if (!inferring.compareAndSet(false, true)) return
        val lCopy = leftWindow.clone()
        val rCopy = rightWindow.clone()
        scope.launch {
            try {
                runNeuralMaskInference(lCopy, rCopy, sampleRate)
            } finally {
                inferring.set(false)
            }
        }
    }

    private fun runNeuralMaskInference(left: FloatArray, right: FloatArray, sampleRate: Int) {
        val session = ortSession ?: return
        val env = ortEnv ?: return
        val dimF = modelDimF
        val dimT = modelDimT
        val inputName = session.inputNames.firstOrNull() ?: return

        // Build [1, 4, dimF, dimT] STFT tensor via 4096-point Hann FFT
        val fftSize = 4096
        val hop = 512
        val tensorSize = 4 * dimF * dimT
        val tensorData = FloatBuffer.allocate(tensorSize)

        val framesToAnalyze = minOf(dimT, 16)
        val nSamples = left.size
        val window = FloatArray(fftSize) { i -> (0.5 * (1.0 - cos(2.0 * PI * i / fftSize))).toFloat() }
        val cosT = FloatArray(fftSize / 2) { i -> cos(-2.0 * PI * i / fftSize).toFloat() }
        val sinT = FloatArray(fftSize / 2) { i -> sin(-2.0 * PI * i / fftSize).toFloat() }
        val bitRev = IntArray(fftSize).apply {
            for (i in 0 until fftSize) {
                var rev = 0; var v = i
                for (b in 0 until 12) { rev = (rev shl 1) or (v and 1); v = v ushr 1 }
                this[i] = rev
            }
        }

        val reL = FloatArray(fftSize)
        val imL = FloatArray(fftSize)
        val reR = FloatArray(fftSize)
        val imR = FloatArray(fftSize)
        val inBandEnergy = FloatArray(4)

        val ch0Offset = 0
        val ch1Offset = dimF * dimT
        val ch2Offset = 2 * dimF * dimT
        val ch3Offset = 3 * dimF * dimT
        val rawArr = tensorData.array()

        for (t in 0 until framesToAnalyze) {
            val baseIdx = ((t * hop) % maxOf(1, nSamples - fftSize)).coerceAtLeast(0)
            for (i in 0 until fftSize) {
                val idx = (baseIdx + i) % nSamples
                val w = window[i]
                reL[i] = left[idx] * w; imL[i] = 0f
                reR[i] = right[idx] * w; imR[i] = 0f
            }
            fftInPlace(reL, imL, fftSize, bitRev, cosT, sinT)
            fftInPlace(reR, imR, fftSize, bitRev, cosT, sinT)

            val binHz = sampleRate.toFloat() / fftSize
            val maxBin = minOf(dimF, fftSize / 2)
            for (f in 0 until maxBin) {
                val rl = reL[f]; val il = imL[f]
                val rr = reR[f]; val ir = imR[f]
                val base = f * dimT + t
                rawArr[ch0Offset + base] = rl
                rawArr[ch1Offset + base] = il
                rawArr[ch2Offset + base] = rr
                rawArr[ch3Offset + base] = ir

                val midRe = 0.5f * (rl + rr)
                val midIm = 0.5f * (il + ir)
                val pMid = midRe * midRe + midIm * midIm
                val hz = f * binHz
                val b = when {
                    hz < 160f -> -1
                    hz < 450f -> 0
                    hz < 1400f -> 1
                    hz < 4200f -> 2
                    else -> 3
                }
                if (b >= 0) inBandEnergy[b] += pMid
            }
        }

        val shape = longArrayOf(1L, 4L, dimF.toLong(), dimT.toLong())
        OnnxTensor.createTensor(env, tensorData, shape).use { inputTensor ->
            session.run(mapOf(inputName to inputTensor)).use { result ->
                val outTensor = result.firstOrNull()?.value as? OnnxTensor ?: return
                val outBuf = outTensor.floatBuffer ?: return
                val outBandEnergy = FloatArray(4)
                val binHz = sampleRate.toFloat() / fftSize
                val maxBin = minOf(dimF, fftSize / 2)

                for (t in 0 until framesToAnalyze) {
                    for (f in 0 until maxBin) {
                        val base = f * dimT + t
                        val rl = outBuf.get(ch0Offset + base)
                        val il = outBuf.get(ch1Offset + base)
                        val rr = outBuf.get(ch2Offset + base)
                        val ir = outBuf.get(ch3Offset + base)
                        val midRe = 0.5f * (rl + rr)
                        val midIm = 0.5f * (il + ir)
                        val pInstMid = midRe * midRe + midIm * midIm
                        val hz = f * binHz
                        val b = when {
                            hz < 160f -> -1
                            hz < 450f -> 0
                            hz < 1400f -> 1
                            hz < 4200f -> 2
                            else -> 3
                        }
                        if (b >= 0) outBandEnergy[b] += pInstMid
                    }
                }

                // UVR_MDXNET_KARA outputs the Instrumental stem: vocal fraction = 1 - sqrt(E_inst / E_mix)
                val m1 = (1f - sqrt(outBandEnergy[0] / (inBandEnergy[0] + 1e-7f))).coerceIn(0.65f, 1.0f)
                val m2 = (1f - sqrt(outBandEnergy[1] / (inBandEnergy[1] + 1e-7f))).coerceIn(0.85f, 1.0f)
                val m3 = (1f - sqrt(outBandEnergy[2] / (inBandEnergy[2] + 1e-7f))).coerceIn(0.85f, 1.0f)
                val m4 = (1f - sqrt(outBandEnergy[3] / (inBandEnergy[3] + 1e-7f))).coerceIn(0.40f, 0.90f)

                aiMaskBand1 = aiMaskBand1 * 0.35f + m1 * 0.65f
                aiMaskBand2 = aiMaskBand2 * 0.35f + m2 * 0.65f
                aiMaskBand3 = aiMaskBand3 * 0.35f + m3 * 0.65f
                aiMaskBand4 = aiMaskBand4 * 0.35f + m4 * 0.65f
            }
        }
    }

    private fun fftInPlace(
        re: FloatArray,
        im: FloatArray,
        n: Int,
        bitRev: IntArray,
        cosT: FloatArray,
        sinT: FloatArray,
    ) {
        for (i in 0 until n) {
            val j = bitRev[i]
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val half = len ushr 1
            val step = n / len
            var i = 0
            while (i < n) {
                var k = 0
                for (j in 0 until half) {
                    val wr = cosT[k]; val wi = sinT[k]
                    val u = i + j; val v = u + half
                    val vr = re[v] * wr - im[v] * wi
                    val vi = re[v] * wi + im[v] * wr
                    re[v] = re[u] - vr; im[v] = im[u] - vi
                    re[u] += vr; im[u] += vi
                    k += step
                }
                i += len
            }
            len = len shl 1
        }
    }
}
