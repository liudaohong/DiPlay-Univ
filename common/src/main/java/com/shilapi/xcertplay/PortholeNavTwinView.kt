package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.SweepGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import com.shilapi.xcertplay.daynight.CarDataMonitor
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 导航双擎：全屏导航背景上的双环 HUD。
 *
 * 这一层只画圆环、刻度和进度，不画地图、不画卡片，也不画车速/转速/挡位/状态文字。
 * 数字由 MCU 层负责；这里只负责把左右两块环形仪表围在 MCU 读数的位置。
 *
 * 环心与实车 MCU 对齐：车速 (255,330)，转速 (1660,330)。
 * 左侧 0–220 km/h，右侧 0–5.5 x1000 r/min。
 */
internal class PortholeNavTwinView(context: Context) : View(context) {

    private var metrics = PanelMetrics(1920, 720)
    private var running = false
    private var shownSpeed = 0f
    private var shownRpm = 0f
    private var lastFrameNanos = 0L

    private val leftArc = RectF()
    private val rightArc = RectF()
    private val frameArc = RectF()

    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val lensPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 28f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textSize = 20f
    }

    init {
        // Transparent: the navigation stream underneath is the background.
        setBackgroundColor(Color.TRANSPARENT)
    }

    fun setMetrics(next: PanelMetrics) {
        if (next == metrics) return
        metrics = next
        invalidate()
    }

    fun applyTheme(theme: PortholeTheme) {
        if (theme.decor != PortholeTheme.Decor.NAV_TWIN) return
        invalidate()
    }

    fun start() {
        if (running) return
        running = true
        lastFrameNanos = 0L
        CarDataMonitor.start(context)
        postInvalidateOnAnimation()
    }

    fun stop() {
        if (!running) return
        running = false
        CarDataMonitor.stop()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE && running) stop()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val save = canvas.save()
        canvas.translate(0f, metrics.offsetY)
        canvas.scale(metrics.scale, metrics.scale)

        val now = SystemClock.elapsedRealtimeNanos()
        lastFrameNanos = now

        val targetSpeed = CarDataMonitor.displayedSpeedKmh()
            .let { if (it.isFinite()) it.coerceIn(0f, 220f) else 0f }
        val targetRpm = CarDataMonitor.displayedRpm()
            .let { if (it.isFinite()) it.coerceIn(0f, 5500f) else 0f }
        shownSpeed = approach(shownSpeed, targetSpeed)
        shownRpm = approach(shownRpm, targetRpm)

        drawGauge(
            canvas = canvas,
            cx = LEFT_CX,
            cy = CY,
            start = LEFT_START,
            sweep = LEFT_SWEEP,
            ratio = (shownSpeed / 220f).coerceIn(0f, 1f),
            rpm = false,
        )
        drawGauge(
            canvas = canvas,
            cx = RIGHT_CX,
            cy = CY,
            start = RIGHT_START,
            sweep = RIGHT_SWEEP,
            ratio = (shownRpm / 5500f).coerceIn(0f, 1f),
            rpm = true,
        )

        canvas.restoreToCount(save)
        // 20 fps: the cluster panel is capped at 30 fps and this view shares the UI thread with
        // the main CarPlay picture. Lowering the decorative refresh keeps the main screen ahead.
        if (running) postInvalidateDelayed(50L)
    }

    private fun approach(current: Float, target: Float): Float {
        val delta = target - current
        if (abs(delta) < .4f) return target
        return current + delta * .20f
    }

    private fun drawGauge(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        start: Float,
        sweep: Float,
        ratio: Float,
        rpm: Boolean,
    ) {
        val outer = if (rpm) RED else CHROME
        val active = if (rpm) AMBER else SPEED_ACTIVE
        val labelColor = if (rpm) Color.rgb(239, 176, 165) else Color.rgb(205, 224, 236)

        // Soft bottom fade, like the reference photo: no cut lines, no wedge. The complete
        // gauge is drawn into a layer, then an angular alpha mask fades 0..220 out toward the
        // bottom. Total / D / NORMAL therefore sit on clean map instead of a hard notch.
        val layerSave = canvas.saveLayer(0f, 0f, 1920f, 720f, null)

        // Previous smoked glass look: a complete dark disc behind the readout, not the
        // open-headphone shape from the first cut. The ring itself is small enough that D and
        // NORMAL sit outside it.
        // Outer metal bezel.
        strokePaint.shader = null
        strokePaint.strokeWidth = 12f
        strokePaint.color = if (rpm) Color.argb(220, 44, 8, 13) else Color.argb(220, 20, 28, 34)
        canvas.drawCircle(cx, cy, 208f, strokePaint)

        strokePaint.strokeWidth = 13f
        strokePaint.shader = if (rpm) {
            LinearGradient(
                cx - 230f, cy - 230f, cx + 230f, cy + 230f,
                intArrayOf(
                    Color.rgb(255, 105, 96), Color.rgb(210, 32, 42),
                    Color.rgb(70, 5, 12), Color.rgb(255, 72, 72),
                    Color.rgb(92, 7, 14), Color.rgb(255, 122, 104),
                ),
                floatArrayOf(0f, .18f, .36f, .56f, .78f, 1f),
                Shader.TileMode.CLAMP,
            )
        } else {
            LinearGradient(
                cx - 230f, cy - 230f, cx + 230f, cy + 230f,
                intArrayOf(
                    Color.WHITE, Color.rgb(168, 185, 196), Color.rgb(73, 87, 96),
                    Color.rgb(240, 249, 255), Color.rgb(103, 117, 126), Color.rgb(218, 231, 238),
                ),
                floatArrayOf(0f, .18f, .36f, .54f, .76f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawCircle(cx, cy, 202f, strokePaint)
        strokePaint.shader = null

        strokePaint.strokeWidth = 10f
        strokePaint.color = if (rpm) Color.argb(245, 14, 2, 5) else Color.argb(245, 5, 9, 13)
        canvas.drawCircle(cx, cy, 194f, strokePaint)
        strokePaint.strokeWidth = 1.5f
        strokePaint.color = if (rpm) Color.argb(80, 255, 205, 195) else Color.argb(85, 225, 246, 255)
        canvas.drawCircle(cx, cy, 206f, strokePaint)
        strokePaint.strokeWidth = 1f
        strokePaint.color = if (rpm) Color.argb(75, 255, 170, 160) else Color.argb(65, 220, 245, 255)
        canvas.drawCircle(cx, cy, 188f, strokePaint)

        // The readout disc is a vertical colour wash, not pure black: deep navy on the speed
        // side and deep oxblood on the rpm side, dense at the top and thinning toward the
        // opening at the bottom.
        // Very light centre wash only: it is not a filled circle anymore, just enough
        // colour to keep the white MCU digits readable over a pale map.
        lensPaint.shader = LinearGradient(
            0f, cy - 176f, 0f, cy + 176f,
            if (rpm) {
                intArrayOf(
                    Color.argb(55, 58, 16, 24), Color.argb(32, 30, 8, 13),
                    Color.argb(0, 18, 4, 8),
                )
            } else {
                intArrayOf(
                    Color.argb(55, 15, 46, 64), Color.argb(32, 6, 24, 34),
                    Color.argb(0, 3, 10, 15),
                )
            },
            floatArrayOf(0f, .60f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, 176f, lensPaint)
        lensPaint.shader = null

        // Inactive band.
        leftArc.set(cx - 150f, cy - 150f, cx + 150f, cy + 150f)
        bandPaint.color = if (rpm) Color.argb(30, 255, 210, 204) else Color.argb(25, 223, 244, 255)
        canvas.drawArc(leftArc, start, sweep, false, bandPaint)

        // Filled inner band. This is deliberately a thick fill, not a line.
        val fillSweep = sweep * ratio
        if (abs(fillSweep) > .3f) {
            bandPaint.shader = if (rpm) {
                LinearGradient(
                    cx - 150f, 0f, cx + 150f, 0f,
                    intArrayOf(Color.rgb(255, 214, 132), Color.rgb(255, 172, 76), Color.rgb(255, 132, 62)),
                    floatArrayOf(0f, .58f, 1f),
                    Shader.TileMode.CLAMP,
                )
            } else {
                LinearGradient(
                    cx - 150f, 0f, cx + 150f, 0f,
                    intArrayOf(Color.rgb(190, 248, 255), Color.WHITE, Color.rgb(255, 211, 145)),
                    floatArrayOf(0f, .56f, 1f),
                    Shader.TileMode.CLAMP,
                )
            }
            bandPaint.color = Color.WHITE
            canvas.drawArc(leftArc, start, fillSweep, false, bandPaint)
            bandPaint.shader = null
        }

        // No fixed redline fill: at idle the left side must stay dark, not read as a live
        // red band. The red rim and the 5.5 label still mark the top of the scale.

        // Cut the band into scales, matching the reference's filled inner ring.
        tickPaint.color = Color.rgb(2, 6, 10)
        tickPaint.strokeWidth = 1.3f
        val segments = 44
        for (i in 0..segments) {
            val a = Math.toRadians((start + sweep * (i / segments.toFloat())).toDouble())
            val c = cos(a).toFloat()
            val s = sin(a).toFloat()
            canvas.drawLine(
                cx + c * 136f, cy + s * 136f,
                cx + c * 164f, cy + s * 164f,
                tickPaint,
            )
        }

        // Outer ticks.
        tickPaint.setShadowLayer(2f, 0f, 0f, Color.argb(190, 0, 0, 0))
        for (i in 0..segments) {
            val f = i / segments.toFloat()
            val major = i % (if (rpm) 8 else 4) == 0
            val a = Math.toRadians((start + sweep * f).toDouble())
            val c = cos(a).toFloat()
            val s = sin(a).toFloat()
            tickPaint.color = when {
                rpm && f > .818f -> Color.rgb(255, 58, 66)
                else -> if (rpm) Color.rgb(255, 220, 205) else Color.rgb(225, 242, 250)
            }
            tickPaint.strokeWidth = if (major) 2.4f else 1.1f
            tickPaint.color = if (major) tickPaint.color else if (rpm) Color.rgb(145, 95, 92) else Color.rgb(145, 166, 178)
            canvas.drawLine(
                cx + c * (if (major) 190f else 194f), cy + s * (if (major) 190f else 194f),
                cx + c * 200f, cy + s * 200f,
                tickPaint,
            )
        }
        tickPaint.clearShadowLayer()

        // Static scale numbers. These are theme-owned labels, not live readouts.
        labelPaint.color = if (rpm) Color.rgb(255, 232, 224) else Color.rgb(244, 252, 255)
        labelPaint.alpha = 255
        labelPaint.setShadowLayer(3f, 0f, 0f, Color.argb(210, 0, 0, 0))
        val labels = if (rpm) {
            floatArrayOf(0f, 1f, 2f, 3f, 4f, 5.5f)
        } else {
            floatArrayOf(0f, 40f, 80f, 120f, 160f, 200f, 220f)
        }
        val maxValue = if (rpm) 5.5f else 220f
        for (value in labels) {
            val a = Math.toRadians((start + sweep * (value / maxValue)).toDouble())
            val x = cx + cos(a).toFloat() * 178f
            val y = cy + sin(a).toFloat() * 178f + 8f
            val text = if (rpm && value == 5.5f) "5.5" else if (rpm) value.toInt().toString() else value.toInt().toString()
            // Opaque numbers with a dark outline so they read on both map and ring.
            labelPaint.style = Paint.Style.STROKE
            labelPaint.strokeWidth = 4.5f
            labelPaint.color = Color.argb(235, 0, 0, 0)
            canvas.drawText(text, x, y, labelPaint)
            labelPaint.style = Paint.Style.FILL
            labelPaint.color = labelColor
            canvas.drawText(text, x, y, labelPaint)
        }

        // Thin highlight along the outside of the filled band.
        strokePaint.shader = null
        strokePaint.strokeWidth = 2.2f
        strokePaint.color = if (rpm) Color.argb(150, 255, 240, 220) else Color.argb(170, 245, 254, 255)
        if (abs(fillSweep) > .3f) {
            strokePaint.strokeWidth = 2.2f
            canvas.drawArc(leftArc, start, fillSweep, false, strokePaint)
        }

        // Active tip marker.
        if (abs(fillSweep) > .3f) {
            val a = Math.toRadians((start + fillSweep).toDouble())
            val x = cx + cos(a).toFloat() * 150f
            val y = cy + sin(a).toFloat() * 150f
            canvas.drawCircle(x, y, 3.5f, labelPaint.apply {
                color = if (rpm) Color.rgb(255, 228, 178) else Color.WHITE
            })
            labelPaint.alpha = 255
        }

        // Angular alpha mask: the bottom 0..max sector fades smoothly to transparent.
        // No hard radial cut, no wedge through the centre.
        val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        maskPaint.shader = SweepGradient(
            cx, cy,
            intArrayOf(
                Color.WHITE, Color.WHITE, Color.TRANSPARENT,
                Color.TRANSPARENT, Color.WHITE, Color.WHITE,
            ),
            floatArrayOf(0f, .111f, .167f, .333f, .389f, 1f),
        )
        maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        // Only the outer annulus fades. The centre lens (P/87/0.0) stays a full glass disc,
        // so no triangular bite is cut through the digits.
        val maskArea = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addCircle(cx, cy, 245f, Path.Direction.CW)
            addCircle(cx, cy, 142f, Path.Direction.CW)
        }
        val maskSave = canvas.save()
        canvas.clipPath(maskArea)
        canvas.drawRect(0f, 0f, 1920f, 720f, maskPaint)
        canvas.restoreToCount(maskSave)
        canvas.restoreToCount(layerSave)
    }

    companion object {
        // Matches the real MCU glyph centres on the 1920x720 panel.
        private const val LEFT_CX = 255f
        private const val RIGHT_CX = 1660f
        private const val CY = 285f
        private const val LEFT_START = 120f
        private const val LEFT_SWEEP = 300f
        private const val RIGHT_START = 60f
        private const val RIGHT_SWEEP = -300f

        private val CHROME = Color.WHITE
        private val RED = Color.rgb(255, 48, 58)
        private val AMBER = Color.rgb(255, 176, 76)
        private val SPEED_ACTIVE = Color.rgb(235, 251, 255)
    }
}
