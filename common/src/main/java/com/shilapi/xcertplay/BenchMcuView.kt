package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import com.shilapi.xcertplay.daynight.CarDataMonitor
import java.util.Locale

/**
 * 测试用的 MCU 背景（只在 bench 模式出现）。
 *
 * 真车上 挡位/速度/总里程/转速/指示灯 是 MCU 画的，永远压在应用之上；PC 上的模拟副屏没有
 * 这一层，所以取景窗视图看起来会缺一半信息。这个 View 按实车截图量出来的坐标把那一层画出来，
 * 加在容器最底部，于是 scrcpy 窗口里看到的画面和车机上一致。
 *
 * 坐标全部是 1920x720 的面板像素（不是 dp），与 PortholeOverlayView 同一套坐标系。
 */
internal class BenchMcuView(context: Context) : View(context) {

    private val gear = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(232, 236, 239)
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        textSize = 130f
        textAlign = Paint.Align.CENTER
    }
    private val drive = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(232, 236, 239)
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        textSize = 62f
        textAlign = Paint.Align.CENTER
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(185, 193, 199)
        textSize = 30f
    }
    private val mode = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(154, 163, 170)
        textSize = 30f
        textAlign = Paint.Align.CENTER
    }
    private val tach = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(232, 236, 239)
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        textSize = 128f
        textAlign = Paint.Align.CENTER
    }
    private val tachUnit = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(138, 147, 155)
        textSize = 30f
        textAlign = Paint.Align.CENTER
    }
    private val speed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(232, 236, 239)
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        textSize = 130f
        textAlign = Paint.Align.CENTER
    }
    private val speedUnit = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(138, 147, 155)
        textSize = 30f
        textAlign = Paint.Align.CENTER
    }
    private val iconStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val iconFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private var metrics = PanelMetrics(1920, 720)
    private var dataMonitorActive = false

    fun setMetrics(next: PanelMetrics) {
        if (next == metrics) return
        metrics = next
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateDataMonitor()
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        setDataMonitorActive(false)
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateDataMonitor()
    }

    private fun updateDataMonitor() {
        setDataMonitorActive(isAttachedToWindow && visibility == View.VISIBLE)
    }

    private fun setDataMonitorActive(active: Boolean) {
        if (dataMonitorActive == active) return
        dataMonitorActive = active
        if (active) CarDataMonitor.start(context) else CarDataMonitor.stop()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val save = canvas.save()
        canvas.translate(0f, metrics.offsetY)
        canvas.scale(metrics.scale, metrics.scale)

        val data = CarDataMonitor.snapshot
        val rpm = CarDataMonitor.displayedRpm()
        val odometer = data.odometerKm
        val speedKmh = CarDataMonitor.displayedSpeedKmh()

        // 左列坐标按真车 MCU 截图：运动/有车速时，大车速占据上方 P 的位置，
        // 挡位 D 落到下方；静止无车速时才是 P + Total。PC 模拟必须和真车一致，
        // 否则双环会“对着 P 或对着 110”而不是对着真车的大车速。
        val hasSpeed = speedKmh.isFinite()
        if (hasSpeed) {
            canvas.drawText(
                String.format(Locale.US, "%.0f", speedKmh.coerceAtLeast(0f)),
                255f,
                330f,
                speed,
            )
            canvas.drawText("km/h", 255f, 390f, speedUnit)
            canvas.drawText("D", 255f, 522f, drive)
        } else {
            canvas.drawText("P", 255f, 330f, gear)
            canvas.drawText(
                if (odometer.isFinite()) String.format(Locale.US, "Total  %.0f km", odometer)
                else "Total  43071 km",
                134f,
                510f,
                label,
            )
        }
        canvas.drawText("NORMAL", 260f, 590f, mode)

        // 右列：转速 + 单位；坐标按真车截图中心 1660
        canvas.drawText(
            if (rpm.isFinite()) String.format(Locale.US, "%.1f", rpm / 1000f) else "0.0",
            1660f,
            330f,
            tach,
        )
        canvas.drawText("x1000r/min", 1660f, 390f, tachUnit)
        // 顶部指示灯
        drawTelltales(canvas)
        canvas.restoreToCount(save)
        if (isAttachedToWindow) postInvalidateDelayed(150L)
    }

    private fun drawTelltales(canvas: Canvas) {
        // 手刹：红圈 P
        iconStroke.color = Color.rgb(226, 75, 74)
        canvas.drawCircle(500f, 66f, 22f, iconStroke)
        iconFill.color = Color.rgb(226, 75, 74)
        canvas.drawText("P", 490f, 76f, iconFill.also { it.textSize = 22f })

        // 车道辅助：绿色车形
        iconStroke.color = Color.rgb(93, 202, 165)
        canvas.drawRect(560f, 46f, 606f, 86f, iconStroke)
        canvas.drawLine(583f, 46f, 583f, 86f, iconStroke)

        // 远光：绿色灯组
        iconStroke.color = Color.rgb(93, 202, 165)
        canvas.drawCircle(650f, 62f, 10f, iconStroke)
        for (i in 0..2) {
            val x = 674f + i * 12f
            canvas.drawLine(x, 50f, x, 76f, iconStroke)
        }

        // 双闪：红色三角
        iconStroke.color = Color.rgb(226, 75, 74)
        val path = android.graphics.Path().apply {
            moveTo(760f, 42f)
            lineTo(786f, 84f)
            lineTo(734f, 84f)
            close()
        }
        canvas.drawPath(path, iconStroke)
    }
}
