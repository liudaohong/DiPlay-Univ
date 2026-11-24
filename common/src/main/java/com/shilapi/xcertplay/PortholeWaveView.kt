package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.sin

/**
 * 取景窗视图的背景波浪。
 *
 * 画在**所有内容之下**（加进容器的最底层），所以在黑色背景上表现为"内容背后流动的两条波"，
 * 遇到取景窗（不透明的视频层）自然被挡住——这正好是设计里想要的"断开"效果。
 *
 * 波的颜色跟随 [PortholeOverlayView] 的律动色，幅度在这里是常量；真机若要跟 PCM 包络，
 * 只需把 amplitude 换成一个每秒更新的音量值。
 */
internal class PortholeWaveView(context: Context) : View(context) {

    var accentColor: Int = Color.rgb(139, 127, 232)
        set(value) {
            field = value
            invalidate()
        }

    private var running = false
    /** 有些主题不要波浪；由 [applyTheme] 控制，默认保持旧版一直显示。 */
    private var waveEnabled = PortholeTheme.DEFAULT.palette.waveEnabled
    private var startedAt = 0L
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.4f
    }
    private val path = Path()

    fun start() {
        if (running) return
        running = true
        startedAt = SystemClock.elapsedRealtime()
        postInvalidateOnAnimation()
    }

    fun stop() {
        running = false
        invalidate()
    }

    /** 主题切换后立即生效：不需要重建波浪层，也不打断正在播放的音乐。 */
    fun applyTheme(theme: PortholeTheme) {
        val enabled = theme.palette.waveEnabled
        if (waveEnabled == enabled) return
        waveEnabled = enabled
        invalidate()
    }

    private var metrics = PanelMetrics(1920, 720)

    fun setMetrics(next: PanelMetrics) {
        if (next == metrics) return
        metrics = next
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!running || !waveEnabled) return
        val save = canvas.save()
        canvas.translate(0f, metrics.offsetY)
        canvas.scale(metrics.scale, metrics.scale)
        val t = (SystemClock.elapsedRealtime() - startedAt) / 1000f
        drawWave(canvas, t, 40f, 0.55f, -150f)
        drawWave(canvas, t, 26f, 0.35f, 120f)
        canvas.restoreToCount(save)
        // Same main-screen priority as the other porthole decorations: 20 fps.
        postInvalidateDelayed(50L)
    }

    private fun drawWave(canvas: Canvas, t: Float, amplitude: Float, alpha: Float, speed: Float) {
        paint.color = accentColor
        paint.alpha = (alpha * 150).toInt().coerceIn(0, 255)
        path.reset()
        var x = 0f
        var first = true
        while (x <= width) {
            val y = CENTER_Y + amplitude * sin((x / PERIOD) * 2f * PI.toFloat() + t * speed / PERIOD)
            if (first) {
                path.moveTo(x, y)
                first = false
            } else {
                path.lineTo(x, y)
            }
            x += 12f
        }
        canvas.drawPath(path, paint)
    }

    private companion object {
        /** 面板中心稍靠下（720 的中点是 360）。 */
        const val CENTER_Y = 420f
        const val PERIOD = 520f
    }
}
