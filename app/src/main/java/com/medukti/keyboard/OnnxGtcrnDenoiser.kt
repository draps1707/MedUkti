package com.medukti.keyboard

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * On-device neural speech enhancer using gtcrn_simple.onnx (524 KB, 16 kHz, n_fft=512, hop=256).
 * Strips steady TV, fan, and ward background noise while preserving speech clarity.
 */
class OnnxGtcrnDenoiser private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession
) {
    private val nFft = 512
    private val hop = 256
    private val bins = 257
    private val win = FloatArray(nFft) { n ->
        sqrt(0.5f * (1.0f - cos((2.0 * PI * n / nFft).toFloat())))
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

    private fun fft512(re: FloatArray, im: FloatArray, inverse: Boolean) {
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
                    val wi = if (inverse) sinTable[idx] else -sinTable[idx]
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
        if (inverse) {
            val invN = 1.0f / nFft
            for (i in 0 until nFft) {
                re[i] *= invN
                im[i] *= invN
            }
        }
    }

    @Synchronized
    fun denoiseChunk(samples: FloatArray): FloatArray {
        if (samples.size < hop) return samples
        return try {
            val paddedLen = hop + samples.size + nFft
            val padded = FloatArray(paddedLen)
            System.arraycopy(samples, 0, padded, hop, samples.size)
            val out = FloatArray(paddedLen)

            var convCache = FloatArray(2 * 1 * 16 * 16 * 33)
            var traCache = FloatArray(2 * 3 * 1 * 1 * 16)
            var interCache = FloatArray(2 * 1 * 33 * 16)

            val re = FloatArray(nFft)
            val im = FloatArray(nFft)
            val mixIn = FloatArray(bins * 2)

            val numFrames = (paddedLen - nFft) / hop + 1
            for (f in 0 until numFrames) {
                val start = f * hop
                for (i in 0 until nFft) {
                    re[i] = padded[start + i] * win[i]
                    im[i] = 0f
                }
                fft512(re, im, inverse = false)
                for (k in 0 until bins) {
                    mixIn[k * 2] = re[k]
                    mixIn[k * 2 + 1] = im[k]
                }

                var mixT: OnnxTensor? = null
                var convT: OnnxTensor? = null
                var traT: OnnxTensor? = null
                var interT: OnnxTensor? = null
                var res: OrtSession.Result? = null
                try {
                    mixT = OnnxTensor.createTensor(env, FloatBuffer.wrap(mixIn), longArrayOf(1L, 257L, 1L, 2L))
                    convT = OnnxTensor.createTensor(env, FloatBuffer.wrap(convCache), longArrayOf(2L, 1L, 16L, 16L, 33L))
                    traT = OnnxTensor.createTensor(env, FloatBuffer.wrap(traCache), longArrayOf(2L, 3L, 1L, 1L, 16L))
                    interT = OnnxTensor.createTensor(env, FloatBuffer.wrap(interCache), longArrayOf(2L, 1L, 33L, 16L))

                    res = session.run(
                        mapOf(
                            "mix" to mixT,
                            "conv_cache" to convT,
                            "tra_cache" to traT,
                            "inter_cache" to interT
                        )
                    )

                    val enhBuf = (res[0] as OnnxTensor).floatBuffer
                    enhBuf.rewind()
                    for (k in 0 until bins) {
                        re[k] = enhBuf.get()
                        im[k] = enhBuf.get()
                    }
                    im[0] = 0f
                    im[bins - 1] = 0f
                    for (k in bins until nFft) {
                        re[k] = re[nFft - k]
                        im[k] = -im[nFft - k]
                    }

                    val convBuf = (res[1] as OnnxTensor).floatBuffer
                    convBuf.rewind()
                    convBuf.get(convCache)

                    val traBuf = (res[2] as OnnxTensor).floatBuffer
                    traBuf.rewind()
                    traBuf.get(traCache)

                    val interBuf = (res[3] as OnnxTensor).floatBuffer
                    interBuf.rewind()
                    interBuf.get(interCache)

                    fft512(re, im, inverse = true)
                    for (i in 0 until nFft) {
                        out[start + i] += re[i] * win[i]
                    }
                } finally {
                    try { res?.close() } catch (_: Exception) {}
                    try { mixT?.close() } catch (_: Exception) {}
                    try { convT?.close() } catch (_: Exception) {}
                    try { traT?.close() } catch (_: Exception) {}
                    try { interT?.close() } catch (_: Exception) {}
                }
            }

            val result = FloatArray(samples.size)
            System.arraycopy(out, hop, result, 0, samples.size)
            for (v in result) {
                if (v.isNaN() || v.isInfinite()) return samples
            }
            result
        } catch (_: Exception) {
            samples
        }
    }

    fun release() {
        try { session.close() } catch (_: Exception) {}
    }

    companion object {
        fun createOrNull(ctx: Context): OnnxGtcrnDenoiser? {
            return try {
                val modelBytes = ctx.assets.open("gtcrn_simple.onnx").use { it.readBytes() }
                val env = OrtEnvironment.getEnvironment()
                val opts = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(2)
                    setInterOpNumThreads(1)
                }
                val session = env.createSession(modelBytes, opts)
                OnnxGtcrnDenoiser(env, session)
            } catch (_: Exception) {
                null
            }
        }
    }
}
