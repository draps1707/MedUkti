package com.medukti.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Hardware-synced 120 Hz animation view for MedUkti's Mic pill.
 * - LISTENING: 120 Hz spring-smoothed 4-bar voice-reactive equalizer.
 * - POLISHING: Snappy 120 Hz bouncing-dot -> expanding round-cap ring -> contracting dot loop.
 */
class MicAnimView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), Choreographer.FrameCallback {

    enum class Mode { IDLE, LISTENING, POLISHING }

    private var mode = Mode.IDLE
    private var running = false
    private var lastFrameNanos = 0L
    private var elapsedSec = 0f

    @Volatile private var targetLevel = 0.08f
    @Volatile private var speechActive = false

    private val barHeights = FloatArray(4) { 0.25f }
    private val arcRect = RectF()

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FDE68A")
        style = Paint.Style.FILL
    }

    fun setMode(newMode: Mode) {
        if (mode == newMode) return
        mode = newMode
        elapsedSec = 0f
        if (mode == Mode.IDLE) {
            stopLoop()
        } else {
            startLoop()
        }
        invalidate()
    }

    fun updateAudioLevel(rms: Float, isSpeech: Boolean) {
        targetLevel = (rms / 0.055f).coerceIn(0.08f, 1.0f)
        speechActive = isSpeech
    }

    private fun startLoop() {
        if (running || !isAttachedToWindow) return
        running = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun stopLoop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (mode != Mode.IDLE) startLoop()
    }

    override fun onDetachedFromWindow() {
        stopLoop()
        super.onDetachedFromWindow()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running || mode == Mode.IDLE) return
        val dt = if (lastFrameNanos == 0L) 0.0083f
        else ((frameTimeNanos - lastFrameNanos) / 1_000_000_000.0f).coerceIn(0.001f, 0.05f)
        lastFrameNanos = frameTimeNanos
        elapsedSec += dt

        if (mode == Mode.LISTENING) {
            val lvl = targetLevel
            val active = speechActive
            val smooth = 1.0f - exp(-24.0f * dt)
            val w = elapsedSec * 9.5f
            for (i in 0 until 4) {
                val wave = 0.5f + 0.5f * sin(w - i * 1.15f)
                val target = if (active) {
                    (0.28f + lvl * (0.35f + 0.37f * wave)).coerceIn(0.22f, 1.0f)
                } else {
                    0.20f + 0.08f * wave
                }
                barHeights[i] += (target - barHeights[i]) * smooth
            }
        }
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun easeInOut(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    private fun bounceOut(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return when {
            x < 1f / 2.75f -> 7.5625f * x * x
            x < 2f / 2.75f -> { val k = x - 1.5f / 2.75f; 7.5625f * k * k + 0.75f }
            x < 2.5f / 2.75f -> { val k = x - 2.25f / 2.75f; 7.5625f * k * k + 0.9375f }
            else -> { val k = x - 2.625f / 2.75f; 7.5625f * k * k + 0.984375f }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        when (mode) {
            Mode.IDLE -> Unit
            Mode.LISTENING -> {
                val density = resources.displayMetrics.density
                val strokeW = 4.2f * density
                barPaint.strokeWidth = strokeW
                barPaint.color = if (speechActive) Color.WHITE else Color.parseColor("#D1FAE5")
                val gap = 4.6f * density
                val totalW = strokeW * 4f + gap * 3f
                val startX = (w - totalW) * 0.5f + strokeW * 0.5f
                val maxH = (h - strokeW * 1.6f).coerceAtLeast(4f * density)
                val cy = h * 0.5f
                for (i in 0 until 4) {
                    val half = (maxH * barHeights[i]) * 0.5f
                    val x = startX + i * (strokeW + gap)
                    canvas.drawLine(x, cy - half, x, cy + half, barPaint)
                }
            }
            Mode.POLISHING -> {
                val density = resources.displayMetrics.density
                val strokeW = 2.8f * density
                ringPaint.strokeWidth = strokeW
                ringPaint.color = Color.WHITE

                val size = minOf(w, h) - strokeW * 2.2f
                val cx = w * 0.5f
                val cy = h * 0.5f
                val r = size * 0.5f
                arcRect.set(cx - r, cy - r, cx + r, cy + r)

                // Snappy 0.92s cycle: dot bounce -> expand arc -> full ring -> contract to dot
                val cycle = 0.92f
                val p = (elapsedSec % cycle) / cycle

                when {
                    p < 0.16f -> {
                        val b = bounceOut(p / 0.16f)
                        val y = cy - r * 0.35f * (1f - b) + r * b
                        canvas.drawCircle(cx, y, strokeW * 0.65f, dotPaint)
                    }
                    p < 0.54f -> {
                        val t = easeInOut((p - 0.16f) / 0.38f)
                        val startAngle = 90f + 180f * t
                        val sweep = (2f + 358f * t).coerceAtMost(360f)
                        canvas.drawArc(arcRect, startAngle, sweep, false, ringPaint)
                    }
                    p < 0.88f -> {
                        val t = easeInOut((p - 0.54f) / 0.34f)
                        val startAngle = 270f + 360f * t
                        val sweep = (360f * (1f - t)).coerceAtLeast(2f)
                        canvas.drawArc(arcRect, startAngle, sweep, false, ringPaint)
                    }
                    else -> {
                        val b = bounceOut((p - 0.88f) / 0.12f)
                        val y = (cy - r) + (2f * r) * b
                        canvas.drawCircle(cx, y, strokeW * 0.65f, dotPaint)
                    }
                }
            }
        }
    }
}
