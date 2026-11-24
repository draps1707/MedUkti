package com.medukti.keyboard

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Cached Silero VAD detector. Shares one compiled OrtSession across keyboard opens
 * (loaded from a cached file path to avoid 600KB+ Java heap copies on every mic tap).
 */
class SileroVadDetector private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession
) {
    private val xBuf: FloatBuffer = ByteBuffer.allocateDirect(512 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val hBuf: FloatBuffer = ByteBuffer.allocateDirect(2 * 1 * 64 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val cBuf: FloatBuffer = ByteBuffer.allocateDirect(2 * 1 * 64 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var hData = FloatArray(2 * 1 * 64)
    private var cData = FloatArray(2 * 1 * 64)

    fun reset() {
        hData.fill(0f)
        cData.fill(0f)
    }

    fun predict(frame512: FloatArray): Float {
        if (frame512.size != 512) return 0f
        var xTensor: OnnxTensor? = null
        var hTensor: OnnxTensor? = null
        var cTensor: OnnxTensor? = null
        var res: OrtSession.Result? = null
        return try {
            xBuf.clear(); xBuf.put(frame512); xBuf.rewind()
            hBuf.clear(); hBuf.put(hData); hBuf.rewind()
            cBuf.clear(); cBuf.put(cData); cBuf.rewind()

            xTensor = OnnxTensor.createTensor(env, xBuf, longArrayOf(1L, 512L))
            hTensor = OnnxTensor.createTensor(env, hBuf, longArrayOf(2L, 1L, 64L))
            cTensor = OnnxTensor.createTensor(env, cBuf, longArrayOf(2L, 1L, 64L))
            res = session.run(mapOf("x" to xTensor, "h" to hTensor, "c" to cTensor))

            @Suppress("UNCHECKED_CAST")
            val probArr = res[0].value as Array<FloatArray>
            val prob = probArr[0][0]

            @Suppress("UNCHECKED_CAST")
            val newH = res[1].value as Array<Array<FloatArray>>
            @Suppress("UNCHECKED_CAST")
            val newC = res[2].value as Array<Array<FloatArray>>
            var idx = 0
            for (i in 0 until 2) {
                for (k in 0 until 64) {
                    hData[idx] = newH[i][0][k]
                    cData[idx] = newC[i][0][k]
                    idx++
                }
            }
            prob
        } catch (_: Exception) {
            0f
        } finally {
            try { res?.close() } catch (_: Exception) {}
            try { xTensor?.close() } catch (_: Exception) {}
            try { hTensor?.close() } catch (_: Exception) {}
            try { cTensor?.close() } catch (_: Exception) {}
        }
    }

    fun release() {
        // Shared OrtSession stays cached in memory for zero-lag keyboard switches.
    }

    companion object {
        private val lock = Any()
        @Volatile private var sharedSession: OrtSession? = null

        fun ensureAssetFile(ctx: Context, assetName: String): File {
            val dir = File(ctx.filesDir, "onnx_cache").apply { mkdirs() }
            val out = File(dir, assetName)
            if (!out.exists() || out.length() == 0L) {
                ctx.assets.open(assetName).use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
            }
            return out
        }

        fun createOrNull(ctx: Context): SileroVadDetector? {
            return try {
                val env = OrtEnvironment.getEnvironment()
                val sess = sharedSession ?: synchronized(lock) {
                    sharedSession ?: run {
                        val file = ensureAssetFile(ctx.applicationContext, "silero_vad.onnx")
                        val opts = OrtSession.SessionOptions().apply {
                            setIntraOpNumThreads(1)
                            setInterOpNumThreads(1)
                        }
                        env.createSession(file.absolutePath, opts).also { sharedSession = it }
                    }
                }
                SileroVadDetector(env, sess)
            } catch (_: Exception) {
                null
            }
        }
    }
}
