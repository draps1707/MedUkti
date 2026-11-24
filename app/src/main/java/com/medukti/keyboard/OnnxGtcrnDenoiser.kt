package com.medukti.keyboard

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * On-device neural speech enhancer using gtcrn_simple.onnx (524 KB, 16 kHz, n_fft=512, hop=256).
 * Shares one cached OrtSession and reuses direct ByteBuffers to eliminate GC pauses.
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

    private val convCache = FloatArray(2 * 1 * 16 * 16 * 33)
    private val traCache = FloatArray(2 * 3 * 1 * 1 * 16)
    private val interCache = FloatArray(2 * 1 * 33 * 16)
    private val re = FloatArray(nFft)
    private val im = FloatArray(nFft)
    private val mixIn = FloatArray(bins * 2)

    private val mixBuf: FloatBuffer = ByteBuffer.allocateDirect(bins * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val convBuf: FloatBuffer = ByteBuffer.allocateDirect(convCache.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val traBuf: FloatBuffer = ByteBuffer.allocateDirect(traCache.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val interBuf: FloatBuffer = ByteBuffer.allocateDirect(interCache.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

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

            convCache.fill(0f)
            traCache.fill(0f)
            interCache.fill(0f)

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

                mixBuf.clear(); mixBuf.put(mixIn); mixBuf.rewind()
                convBuf.clear(); convBuf.put(convCache); convBuf.rewind()
                traBuf.clear(); traBuf.put(traCache); traBuf.rewind()
                interBuf.clear(); interBuf.put(interCache); interBuf.rewind()

                var mixT: OnnxTensor? = null
                var convT: OnnxTensor? = null
                var traT: OnnxTensor? = null
                var interT: OnnxTensor? = null
                var res: OrtSession.Result? = null
                try {
                    mixT = OnnxTensor.createTensor(env, mixBuf, longArrayOf(1L, 257L, 1L, 2L))
                    convT = OnnxTensor.createTensor(env, convBuf, longArrayOf(2L, 1L, 16L, 16L, 33L))
                    traT = OnnxTensor.createTensor(env, traBuf, longArrayOf(2L, 3L, 1L, 1L, 16L))
                    interT = OnnxTensor.createTensor(env, interBuf, longArrayOf(2L, 1L, 33L, 16L))

                    res = session.run(
                        mapOf(
                            "mix" to mixT,
                            "conv_cache" to convT,
                            "tra_cache" to traT,
                            "inter_cache" to interT
                        )
                    )

                    val enhOut = (res[0] as OnnxTensor).floatBuffer
                    enhOut.rewind()
                    for (k in 0 until bins) {
                        re[k] = enhOut.get()
                        im[k] = enhOut.get()
                    }
                    im[0] = 0f
                    im[bins - 1] = 0f
                    for (k in bins until nFft) {
                        re[k] = re[nFft - k]
                        im[k] = -im[nFft - k]
                    }

                    val convOut = (res[1] as OnnxTensor).floatBuffer
                    convOut.rewind()
                    convOut.get(convCache)

                    val traOut = (res[2] as OnnxTensor).floatBuffer
                    traOut.rewind()
                    traOut.get(traCache)

                    val interOut = (res[3] as OnnxTensor).floatBuffer
                    interOut.rewind()
                    interOut.get(interCache)

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
        // Shared OrtSession stays cached in memory for zero-lag keyboard switches.
    }

    companion object {
        private val lock = Any()
        @Volatile private var sharedSession: OrtSession? = null

        fun createOrNull(ctx: Context): OnnxGtcrnDenoiser? {
            return try {
                val env = OrtEnvironment.getEnvironment()
                val sess = sharedSession ?: synchronized(lock) {
                    sharedSession ?: run {
                        val file = SileroVadDetector.ensureAssetFile(ctx.applicationContext, "gtcrn_simple.onnx")
                        val opts = OrtSession.SessionOptions().apply {
                            setIntraOpNumThreads(2)
                            setInterOpNumThreads(1)
                        }
                        env.createSession(file.absolutePath, opts).also { sharedSession = it }
                    }
                }
                OnnxGtcrnDenoiser(env, sess)
            } catch (_: Exception) {
                null
            }
        }
    }
}
