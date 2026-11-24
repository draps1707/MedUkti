package com.medukti.keyboard

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer

class SileroVadDetector private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession
) {
    private var hData = FloatArray(2 * 1 * 64)
    private var cData = FloatArray(2 * 1 * 64)

    fun reset() {
        hData = FloatArray(2 * 1 * 64)
        cData = FloatArray(2 * 1 * 64)
    }

    fun predict(frame512: FloatArray): Float {
        if (frame512.size != 512) return 0f
        var xTensor: OnnxTensor? = null
        var hTensor: OnnxTensor? = null
        var cTensor: OnnxTensor? = null
        var res: OrtSession.Result? = null
        return try {
            xTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(frame512), longArrayOf(1L, 512L))
            hTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(hData), longArrayOf(2L, 1L, 64L))
            cTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(cData), longArrayOf(2L, 1L, 64L))
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
        try { session.close() } catch (_: Exception) {}
    }

    companion object {
        fun createOrNull(ctx: Context): SileroVadDetector? {
            return try {
                val modelBytes = ctx.assets.open("silero_vad.onnx").use { it.readBytes() }
                val env = OrtEnvironment.getEnvironment()
                val opts = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(1)
                    setInterOpNumThreads(1)
                }
                val session = env.createSession(modelBytes, opts)
                SileroVadDetector(env, session)
            } catch (_: Exception) {
                null
            }
        }
    }
}
