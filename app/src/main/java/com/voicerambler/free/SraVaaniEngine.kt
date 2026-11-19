package com.voicerambler.free

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object SraVaaniEngine {

    private const val SAMPLE_RATE = 16000
    private const val N_FFT = 512
    private const val WIN_LEN = 400
    private const val HOP_LEN = 160
    private const val N_MELS = 128
    private const val BLANK_ID = 5000
    private const val VOCAB_SIZE = 5000
    private const val PRED_HIDDEN = 640
    private val DURATIONS = intArrayOf(0, 1, 2, 3, 4)

    private var env: OrtEnvironment? = null
    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null
    private var tokens: List<String> = emptyList()

    private val hannWindow: FloatArray by lazy {
        FloatArray(WIN_LEN) { i ->
            (0.5 - 0.5 * cos(2.0 * PI * i / (WIN_LEN - 1))).toFloat()
        }
    }

    private val melFilterbank: Array<FloatArray> by lazy { buildMelFilterbank() }

    private fun hzToMel(hz: Double): Double = 2595.0 * log10(1.0 + hz / 700.0)
    private fun melToHz(mel: Double): Double = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)

    private fun buildMelFilterbank(): Array<FloatArray> {
        val nFreqs = N_FFT / 2 + 1
        val melMin = hzToMel(0.0)
        val melMax = hzToMel(SAMPLE_RATE / 2.0)
        val melPoints = DoubleArray(N_MELS + 2) { i ->
            melMin + (melMax - melMin) * i / (N_MELS + 1)
        }
        val hzPoints = DoubleArray(N_MELS + 2) { i -> melToHz(melPoints[i]) }
        val bins = IntArray(N_MELS + 2) { i ->
            ((N_FFT + 1) * hzPoints[i] / SAMPLE_RATE).toInt().coerceIn(0, nFreqs - 1)
        }
        return Array(N_MELS) { m ->
            val row = FloatArray(nFreqs)
            val left = bins[m]
            val center = bins[m + 1]
            val right = bins[m + 2]
            if (center > left) {
                for (k in left until center) {
                    row[k] = ((k - left).toDouble() / (center - left)).toFloat()
                }
            }
            if (right > center) {
                for (k in center..right) {
                    row[k] = ((right - k).toDouble() / (right - center)).toFloat()
                }
            }
            val enorm = if (hzPoints[m + 2] > hzPoints[m]) {
                2.0 / (hzPoints[m + 2] - hzPoints[m])
            } else 1.0
            for (k in 0 until nFreqs) {
                row[k] = (row[k] * enorm).toFloat()
            }
            row
        }
    }

    private fun fft512(real: DoubleArray, imag: DoubleArray) {
        val n = N_FFT
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wlenR = cos(ang)
            val wlenI = sin(ang)
            var i = 0
            while (i < n) {
                var wR = 1.0
                var wI = 0.0
                for (k in 0 until len / 2) {
                    val uR = real[i + k]
                    val uI = imag[i + k]
                    val vR = real[i + k + len / 2] * wR - imag[i + k + len / 2] * wI
                    val vI = real[i + k + len / 2] * wI + imag[i + k + len / 2] * wR
                    real[i + k] = uR + vR
                    imag[i + k] = uI + vI
                    real[i + k + len / 2] = uR - vR
                    imag[i + k + len / 2] = uI - vI
                    val nextWR = wR * wlenR - wI * wlenI
                    wI = wR * wlenI + wI * wlenR
                    wR = nextWR
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun computeLogMel(samples: FloatArray): Pair<FloatArray, Int> {
        // Preemphasis 0.97
        val pre = FloatArray(samples.size)
        if (samples.isNotEmpty()) {
            pre[0] = samples[0]
            for (i in 1 until samples.size) {
                pre[i] = samples[i] - 0.97f * samples[i - 1]
            }
        }
        val pad = N_FFT / 2
        val padded = FloatArray(pre.size + 2 * pad)
        System.arraycopy(pre, 0, padded, pad, pre.size)

        val numFrames = max(1, (padded.size - N_FFT) / HOP_LEN + 1)
        val feats = FloatArray(N_MELS * numFrames)
        val nFreqs = N_FFT / 2 + 1
        val real = DoubleArray(N_FFT)
        val imag = DoubleArray(N_FFT)
        val powSpec = FloatArray(nFreqs)
        val winOffset = (N_FFT - WIN_LEN) / 2
        val eps = 5.9604645e-8f

        for (t in 0 until numFrames) {
            real.fill(0.0)
            imag.fill(0.0)
            val start = t * HOP_LEN
            for (i in 0 until WIN_LEN) {
                val idx = start + winOffset + i
                if (idx < padded.size) {
                    real[winOffset + i] = (padded[idx] * hannWindow[i]).toDouble()
                }
            }
            fft512(real, imag)
            for (k in 0 until nFreqs) {
                powSpec[k] = (real[k] * real[k] + imag[k] * imag[k]).toFloat()
            }
            for (m in 0 until N_MELS) {
                var sum = 0.0f
                val filt = melFilterbank[m]
                for (k in 0 until nFreqs) {
                    sum += filt[k] * powSpec[k]
                }
                feats[m * numFrames + t] = ln(max(sum, 0f) + eps)
            }
        }

        // Per-feature mean & std normalization across time
        for (m in 0 until N_MELS) {
            val base = m * numFrames
            var mean = 0.0
            for (t in 0 until numFrames) mean += feats[base + t]
            mean /= numFrames
            var varSum = 0.0
            for (t in 0 until numFrames) {
                val d = feats[base + t] - mean
                varSum += d * d
            }
            val std = sqrt(varSum / max(1, numFrames - 1)) + 1e-5
            for (t in 0 until numFrames) {
                feats[base + t] = ((feats[base + t] - mean) / std).toFloat()
            }
        }
        return feats to numFrames
    }

    @Synchronized
    fun preload(ctx: Context) {
        if (encoderSession != null && decoderSession != null) return
        val encFile = ModelDownloader.getFile(ctx, ModelDownloader.SRAVAANI_ENC)
        val decFile = ModelDownloader.getFile(ctx, ModelDownloader.SRAVAANI_DEC)
        if (!encFile.exists() || !decFile.exists()) {
            throw IllegalStateException("Download SraVaani 1.0 Odia Pack first")
        }
        if (tokens.isEmpty()) {
            tokens = ctx.assets.open("sravaani_tokens.txt").bufferedReader().readLines()
        }
        val ortEnv = env ?: OrtEnvironment.getEnvironment().also { env = it }
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
        }
        if (encoderSession == null) {
            encoderSession = ortEnv.createSession(encFile.absolutePath, opts)
        }
        if (decoderSession == null) {
            decoderSession = ortEnv.createSession(decFile.absolutePath, opts)
        }
    }

    @Synchronized
    fun release() {
        try { encoderSession?.close() } catch (_: Exception) {}
        try { decoderSession?.close() } catch (_: Exception) {}
        encoderSession = null
        decoderSession = null
    }

    fun transcribeSamples(ctx: Context, samples: FloatArray): String {
        preload(ctx)
        val ortEnv = env!!
        val enc = encoderSession!!
        val dec = decoderSession!!

        val (feats, numFrames) = computeLogMel(samples)
        val encInNames = enc.inputNames.toList()
        val featsName = encInNames.getOrElse(0) { "feats" }
        val lenName = encInNames.getOrElse(1) { "feature_lengths" }

        val featsTensor = OnnxTensor.createTensor(
            ortEnv,
            FloatBuffer.wrap(feats),
            longArrayOf(1L, N_MELS.toLong(), numFrames.toLong())
        )
        val lenTensor = OnnxTensor.createTensor(
            ortEnv,
            LongBuffer.wrap(longArrayOf(numFrames.toLong())),
            longArrayOf(1L)
        )

        var encData: FloatArray? = null
        var encLen = 0
        try {
            val encInputs = mapOf(featsName to featsTensor, lenName to lenTensor)
            enc.run(encInputs).use { res ->
                @Suppress("UNCHECKED_CAST")
                val outArr = res[0].value as Array<Array<FloatArray>>
                val lens = res[1].value as LongArray
                encLen = lens[0].toInt()
                val featDim = outArr[0].size // 1024
                val tTotal = outArr[0][0].size
                encData = FloatArray(featDim * tTotal)
                for (d in 0 until featDim) {
                    System.arraycopy(outArr[0][d], 0, encData!!, d * tTotal, tTotal)
                }
                encLen = min(encLen, tTotal)
            }
        } finally {
            featsTensor.close()
            lenTensor.close()
        }

        val encBuf = encData ?: return ""
        if (encLen <= 0) return ""
        val encTotalT = encBuf.size / 1024

        val decInNames = dec.inputNames.toList()
        val fName = decInNames.getOrElse(0) { "f" }
        val tgtName = decInNames.getOrElse(1) { "tgt" }
        val tlenName = decInNames.getOrElse(2) { "tlen" }
        val hName = decInNames.getOrElse(3) { "h_in" }
        val cName = decInNames.getOrElse(4) { "c_in" }

        var lastToken = BLANK_ID
        var hState = FloatArray(PRED_HIDDEN)
        var cState = FloatArray(PRED_HIDDEN)
        val outIds = ArrayList<Int>()

        var t = 0
        var syms = 0
        val frameBuf = FloatArray(1024)
        val tlenArr = intArrayOf(1)

        while (t < encLen) {
            for (d in 0 until 1024) {
                frameBuf[d] = encBuf[d * encTotalT + t]
            }
            val fTensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(frameBuf), longArrayOf(1L, 1024L, 1L))
            val tgtTensor = OnnxTensor.createTensor(ortEnv, IntBuffer.wrap(intArrayOf(lastToken)), longArrayOf(1L, 1L))
            val tlenTensor = OnnxTensor.createTensor(ortEnv, IntBuffer.wrap(tlenArr), longArrayOf(1L))
            val hTensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(hState), longArrayOf(1L, 1L, PRED_HIDDEN.toLong()))
            val cTensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(cState), longArrayOf(1L, 1L, PRED_HIDDEN.toLong()))

            try {
                val decInputs = mapOf(
                    fName to fTensor,
                    tgtName to tgtTensor,
                    tlenName to tlenTensor,
                    hName to hTensor,
                    cName to cTensor
                )
                dec.run(decInputs).use { dres ->
                    @Suppress("UNCHECKED_CAST")
                    val logits4d = dres[0].value as Array<Array<Array<FloatArray>>>
                    val logits = logits4d[0][0][0]

                    var bestTok = 0
                    var bestTokVal = Float.NEGATIVE_INFINITY
                    for (i in 0..VOCAB_SIZE) {
                        if (logits[i] > bestTokVal) {
                            bestTokVal = logits[i]
                            bestTok = i
                        }
                    }
                    var bestDurIdx = 0
                    var bestDurVal = Float.NEGATIVE_INFINITY
                    for (dIdx in DURATIONS.indices) {
                        val v = logits[VOCAB_SIZE + 1 + dIdx]
                        if (v > bestDurVal) {
                            bestDurVal = v
                            bestDurIdx = dIdx
                        }
                    }
                    val dur = DURATIONS[bestDurIdx]

                    if (bestTok != BLANK_ID) {
                        outIds.add(bestTok)
                        lastToken = bestTok
                        @Suppress("UNCHECKED_CAST")
                        val hOut = dres[2].value as Array<Array<FloatArray>>
                        @Suppress("UNCHECKED_CAST")
                        val cOut = dres[3].value as Array<Array<FloatArray>>
                        hState = hOut[0][0]
                        cState = cOut[0][0]
                        syms++
                        if (dur > 0) {
                            t += dur
                            syms = 0
                        } else if (syms >= 10) {
                            t += 1
                            syms = 0
                        }
                    } else {
                        t += max(1, dur)
                        syms = 0
                    }
                }
            } finally {
                fTensor.close()
                tgtTensor.close()
                tlenTensor.close()
                hTensor.close()
                cTensor.close()
            }
        }

        if (outIds.isEmpty()) return ""
        val sb = StringBuilder()
        for (id in outIds) {
            if (id in tokens.indices) {
                sb.append(tokens[id])
            }
        }
        return sb.toString().replace('\u2581', ' ').replace(Regex("\\s{2,}"), " ").trim()
    }
}
