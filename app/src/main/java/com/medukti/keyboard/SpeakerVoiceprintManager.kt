package com.medukti.keyboard

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * On-device 2-voice speaker-voiceprint verifier using wespeaker_en_voxceleb_CAM++.onnx (512-dim).
 * Computes 80-dim Kaldi Mel Fbank + utterance CMVN and cosine similarity against enrolled voices.
 */
class SpeakerVoiceprintManager private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession
) {
    private val sr = 16000
    private val nFft = 512
    private val frameLen = 400
    private val frameShift = 160
    private val nMels = 80
    private val bins = nFft / 2 + 1 // 257

    private val poveyWin = FloatArray(frameLen) { i ->
        (0.5 - 0.5 * cos(2.0 * PI * i / (frameLen - 1))).pow(0.85).toFloat()
    }

    private val cosTable = FloatArray(nFft / 2) { k -> cos((2.0 * PI * k / nFft).toFloat()) }
    private val sinTable = FloatArray(nFft / 2) { k -> sin((2.0 * PI * k / nFft).toFloat()) }
    private val bitRev = IntArray(nFft).apply {
        var j = 0
        for (i in 1 until nFft) {
            var bit = nFft shr 1
            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            this[i] = j
        }
    }

    private val melBank = Array(nMels) { FloatArray(bins) }.apply {
        fun hzToMel(hz: Double) = 1127.0 * ln(1.0 + hz / 700.0)
        fun melToHz(mel: Double) = 700.0 * (exp(mel / 1127.0) - 1.0)
        val lowMel = hzToMel(20.0)
        val highMel = hzToMel(7600.0)
        val hzPts = DoubleArray(nMels + 2) { m ->
            val mel = lowMel + (highMel - lowMel) * m / (nMels + 1)
            melToHz(mel)
        }
        val fftFreqs = DoubleArray(bins) { k -> (sr / 2.0) * k / (bins - 1) }
        for (m in 1..nMels) {
            val fL = hzPts[m - 1]
            val fC = hzPts[m]
            val fR = hzPts[m + 1]
            for (k in 0 until bins) {
                val up = (fftFreqs[k] - fL) / (fC - fL + 1e-12)
                val down = (fR - fftFreqs[k]) / (fR - fC + 1e-12)
                this[m - 1][k] = max(0.0, min(up, down)).toFloat()
            }
        }
    }

    private fun rfftPower(re: FloatArray, im: FloatArray) {
        for (i in 0 until nFft) {
            val j = bitRev[i]
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= nFft) {
            val half = len shr 1
            val step = nFft / len
            for (i in 0 until nFft step len) {
                var idx = 0
                for (j in 0 until half) {
                    val wr = cosTable[idx]
                    val wi = -sinTable[idx]
                    val uRe = re[i + j]
                    val uIm = im[i + j]
                    val vRe = re[i + j + half] * wr - im[i + j + half] * wi
                    val vIm = re[i + j + half] * wi + im[i + j + half] * wr
                    re[i + j] = uRe + vRe
                    im[i + j] = uIm + vIm
                    re[i + j + half] = uRe - vRe
                    im[i + j + half] = uIm - vIm
                    idx += step
                }
            }
            len = len shl 1
        }
    }

    fun extractEmbedding(samplesFloat: FloatArray): FloatArray? {
        if (samplesFloat.size < frameLen + frameShift * 10) return null
        return try {
            val numFrames = (samplesFloat.size - frameLen) / frameShift + 1
            val feats = FloatArray(numFrames * nMels)
            val colMean = FloatArray(nMels)
            val re = FloatArray(nFft)
            val im = FloatArray(nFft)
            val frame = FloatArray(frameLen)

            for (f in 0 until numFrames) {
                val s = f * frameShift
                var mean = 0f
                for (i in 0 until frameLen) {
                    // Scale [-1, 1] to 16-bit PCM range because wespeaker CAM++ has normalize_samples=0
                    val v = samplesFloat[s + i] * 32768.0f
                    frame[i] = v
                    mean += v
                }
                mean /= frameLen
                for (i in 0 until frameLen) frame[i] -= mean
                for (i in frameLen - 1 downTo 1) {
                    frame[i] -= 0.97f * frame[i - 1]
                }
                frame[0] -= 0.97f * frame[0]

                for (i in 0 until frameLen) {
                    re[i] = frame[i] * poveyWin[i]
                    im[i] = 0f
                }
                for (i in frameLen until nFft) {
                    re[i] = 0f
                    im[i] = 0f
                }
                rfftPower(re, im)

                val rowOffset = f * nMels
                for (m in 0 until nMels) {
                    val bank = melBank[m]
                    var energy = 0f
                    for (k in 0 until bins) {
                        val p = re[k] * re[k] + im[k] * im[k]
                        energy += bank[k] * p
                    }
                    val logE = ln(max(energy, 1e-10f))
                    feats[rowOffset + m] = logE
                    colMean[m] += logE
                }
            }

            for (m in 0 until nMels) colMean[m] /= numFrames
            for (f in 0 until numFrames) {
                val rowOffset = f * nMels
                for (m in 0 until nMels) {
                    feats[rowOffset + m] -= colMean[m]
                }
            }

            var inTensor: OnnxTensor? = null
            var res: OrtSession.Result? = null
            try {
                inTensor = OnnxTensor.createTensor(
                    env,
                    FloatBuffer.wrap(feats),
                    longArrayOf(1L, numFrames.toLong(), nMels.toLong())
                )
                res = session.run(mapOf("feats" to inTensor))
                @Suppress("UNCHECKED_CAST")
                val embArr = res[0].value as Array<FloatArray>
                val raw = embArr[0]
                var normSq = 0.0
                for (v in raw) normSq += v.toDouble() * v.toDouble()
                val norm = sqrt(normSq).toFloat().coerceAtLeast(1e-12f)
                FloatArray(raw.size) { i -> raw[i] / norm }
            } finally {
                try { res?.close() } catch (_: Exception) {}
                try { inTensor?.close() } catch (_: Exception) {}
            }
        } catch (_: Exception) {
            null
        }
    }

    fun maxSimilarity(ctx: Context, samples: FloatArray): Float? {
        val v1 = Prefs.getVoiceprint(ctx, 1)
        val v2 = Prefs.getVoiceprint(ctx, 2)
        if (v1 == null && v2 == null) return null
        val emb = extractEmbedding(samples) ?: return null
        var best = -1f
        if (v1 != null && v1.size == emb.size) {
            best = max(best, cosine(emb, v1))
        }
        if (v2 != null && v2.size == emb.size) {
            best = max(best, cosine(emb, v2))
        }
        return if (best < -0.5f) null else best
    }

    fun release() {
        try { session.close() } catch (_: Exception) {}
    }

    companion object {
        fun cosine(a: FloatArray, b: FloatArray): Float {
            var dot = 0.0
            for (i in a.indices) dot += a[i].toDouble() * b[i].toDouble()
            return dot.toFloat()
        }

        fun hasAnyEnrolled(ctx: Context): Boolean =
            Prefs.getVoiceprint(ctx, 1) != null || Prefs.getVoiceprint(ctx, 2) != null

        fun createOrNull(ctx: Context): SpeakerVoiceprintManager? {
            return try {
                val modelBytes = ctx.assets.open("wespeaker_en_voxceleb_CAM++.onnx").use { it.readBytes() }
                val env = OrtEnvironment.getEnvironment()
                val opts = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(2)
                    setInterOpNumThreads(1)
                }
                val session = env.createSession(modelBytes, opts)
                SpeakerVoiceprintManager(env, session)
            } catch (_: Exception) {
                null
            }
        }
    }
}
