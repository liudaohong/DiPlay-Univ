package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import com.shilapi.xcertplay.daynight.CarLampMonitor
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 取景窗“日夜”主题的背景层。
 *
 * 只画天空、太阳/月亮、星星和流星；地图视频、右侧到达卡、车机 MCU 的速度/挡位/转速
 * 都在它上面。白天太阳在右上角（地图和到达卡之间的空位），阳光向左下铺开；夜晚同一
 * 位置换成月亮，周围布星，流星在天空里随机划过。
 *
 * 车灯状态来自 [CarLampMonitor.lastNight]：大灯开=夜晚，关=白天。切换时两套画面做
 * 700ms 交叉淡化，不会硬切。读不到车灯信号时保持当前画面，等下一帧有效值。
 */
internal class PortholeDayNightView(context: Context) : View(context) {

    private data class Cloud(
        val x0: Float,
        val y: Float,
        val rx: Float,
        val ry: Float,
        val alpha: Int,
        val drift: Float,
    )

    private data class Mote(
        val x0: Float,
        val y: Float,
        val radius: Float,
        val phase: Float,
        val speed: Float,
        val alpha: Float,
    )

    private data class Star(
        val x: Float,
        val y: Float,
        val radius: Float,
        val baseAlpha: Float,
        val phase: Float,
        val speed: Float,
        val cross: Boolean,
    )

    private data class Crater(val x: Float, val y: Float, val radius: Float, val alpha: Int)

    private class Meteor {
        var startAt = -1f
        var duration = 1f
        var x0 = 0f
        var y0 = 0f
        var dx = 0f
        var dy = 0f
        var speed = 0f
        var length = 0f
        var width = 0f
        var alpha = 0f
        val path = Path()
    }

    private var running = false
    private var startedAt = 0L
    private var metrics = PanelMetrics(1920, 720)
    private var theme: PortholeTheme? = null

    /** 仅供本地预览测试用；正常路径读 [CarLampMonitor.lastNight]。 */
    @Volatile
    private var nightOverride: Boolean? = null

    /** 预览用：让 Robolectric 直接画白天或夜晚，不依赖车机属性。 */
    internal fun setNightOverrideForPreview(night: Boolean?) {
        nightOverride = night
        if (running) {
            targetInitialized = false
            invalidate()
        }
    }

    private var targetNight = false
    private var targetInitialized = false
    private var mix = 0f
    private var transitionStart = 0L
    private var transitionFrom = 0f
    private var transitionTo = 0f

    /** 车灯状态变化时通知宿主刷新右侧卡片/边框（它们不随本视图逐帧重绘）。 */
    var onNightChanged: ((Boolean) -> Unit)? = null

    private val random = Random(0x0DA7)
    private val meteorRandom = Random(0x51A7)

    private val clouds = Array(6) { index ->
        Cloud(
            x0 = 130f + index * 265f + random.nextFloat() * 90f,
            y = 78f + random.nextFloat() * 300f,
            rx = 150f + random.nextFloat() * 180f,
            ry = 24f + random.nextFloat() * 34f,
            alpha = 18 + random.nextInt(30),
            drift = 1.4f + random.nextFloat() * 2.4f,
        )
    }

    private val motes = Array(38) {
        Mote(
            x0 = random.nextFloat() * PANEL_W,
            y = 72f + random.nextFloat() * 560f,
            radius = 0.7f + random.nextFloat() * 1.5f,
            phase = random.nextFloat() * TWO_PI,
            speed = 3f + random.nextFloat() * 9f,
            alpha = 0.08f + random.nextFloat() * 0.20f,
        )
    }

    private val stars = Array(190) {
        val bright = random.nextFloat()
        Star(
            x = random.nextFloat() * PANEL_W,
            y = random.nextFloat() * 560f,
            radius = if (bright > 0.9f) 1.4f + random.nextFloat() * 1.4f else 0.5f + random.nextFloat() * 1.1f,
            baseAlpha = 0.22f + random.nextFloat() * 0.72f,
            phase = random.nextFloat() * TWO_PI,
            speed = 0.5f + random.nextFloat() * 1.8f,
            cross = random.nextFloat() < 0.055f,
        )
    }

    private val craters = arrayOf(
        Crater(-18f, -12f, 17f, 30),
        Crater(13f, 8f, 22f, 26),
        Crater(-3f, 24f, 11f, 24),
        Crater(24f, -19f, 10f, 22),
        Crater(-27f, 18f, 8f, 18),
    )

    private val meteors = Array(METEOR_COUNT) { Meteor() }

    private val rayAngles = FloatArray(26) { it * (360f / 26f) + 3f }
    private val rayLengths = FloatArray(26) { 400f + random.nextFloat() * 560f }
    private val rayWidths = FloatArray(26) { 2f + random.nextFloat() * 9f }
    private val rayAlphas = IntArray(26) { 18 + random.nextInt(40) }

    private val daySkyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            0f, 0f, 0f, PANEL_H,
            intArrayOf(
                Color.rgb(44, 116, 173),
                Color.rgb(93, 168, 214),
                Color.rgb(167, 210, 225),
                Color.rgb(241, 216, 178),
                Color.rgb(246, 201, 142),
            ),
            floatArrayOf(0f, 0.34f, 0.66f, 0.86f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    private val dayHorizonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            0f, 390f, 0f, PANEL_H,
            intArrayOf(Color.TRANSPARENT, Color.argb(30, 255, 195, 125), Color.argb(62, 255, 174, 95)),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    private val dayTopVignette = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            0f, 0f, 0f, 170f,
            Color.argb(62, 8, 20, 34), Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
    }

    private val dayLeftVignette = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            0f, 0f, 520f, 0f,
            Color.argb(88, 8, 20, 34), Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
    }

    private val dayRightVignette = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            PANEL_W, 0f, PANEL_W - 440f, 0f,
            Color.argb(78, 8, 20, 34), Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
    }

    private val nightSkyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            0f, 0f, 0f, PANEL_H,
            intArrayOf(
                Color.rgb(3, 6, 20),
                Color.rgb(8, 21, 44),
                Color.rgb(18, 33, 60),
                Color.rgb(29, 39, 68),
                Color.rgb(43, 35, 57),
            ),
            floatArrayOf(0f, 0.36f, 0.68f, 0.88f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    private val nightHorizonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            0f, 400f, 0f, PANEL_H,
            intArrayOf(Color.TRANSPARENT, Color.argb(34, 88, 78, 150)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    private val nightLeftVignette = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            0f, 0f, 520f, 0f,
            Color.argb(116, 2, 5, 12), Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
    }

    private val nightRightVignette = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            PANEL_W, 0f, PANEL_W - 440f, 0f,
            Color.argb(96, 2, 5, 12), Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
    }

    private val nightTopVignette = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            0f, 0f, 0f, 170f,
            Color.argb(86, 2, 5, 12), Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
    }

    private val cloudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(Color.argb(255, 255, 246, 232), Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    private val sunGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            SUN_X, SUN_Y, 420f,
            intArrayOf(
                Color.argb(205, 255, 248, 214),
                Color.argb(118, 255, 226, 150),
                Color.argb(48, 255, 193, 100),
                Color.TRANSPARENT,
            ),
            floatArrayOf(0f, 0.14f, 0.38f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    private val sunDiskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            SUN_X - 12f, SUN_Y - 14f, SUN_R,
            intArrayOf(
                Color.rgb(255, 254, 248),
                Color.rgb(255, 250, 240),
                Color.rgb(255, 240, 180),
                Color.rgb(255, 209, 114),
                Color.rgb(255, 185, 71),
            ),
            floatArrayOf(0f, 0.42f, 0.66f, 0.88f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    private val sunRayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.rgb(255, 242, 198)
    }

    private val sunRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.4f
        color = Color.argb(225, 255, 250, 220)
    }

    private val sunCoronaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(70, 255, 225, 155)
    }

    private val sunShaftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            SUN_X, SUN_Y, -120f, 700f,
            intArrayOf(
                Color.argb(64, 255, 232, 175),
                Color.argb(32, 255, 211, 140),
                Color.TRANSPARENT,
            ),
            floatArrayOf(0f, 0.42f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    private val sunShaftPaths = arrayOf(
        Path().apply {
            moveTo(SUN_X, SUN_Y - 40f)
            lineTo(SUN_X - 280f, SUN_Y + 140f)
            lineTo(-160f, 760f)
            lineTo(160f, 760f)
            lineTo(SUN_X + 80f, SUN_Y + 120f)
            close()
        },
        Path().apply {
            moveTo(SUN_X - 40f, SUN_Y + 30f)
            lineTo(SUN_X - 600f, SUN_Y + 180f)
            lineTo(-260f, 560f)
            lineTo(20f, 480f)
            close()
        },
        Path().apply {
            moveTo(SUN_X + 20f, SUN_Y + 20f)
            lineTo(SUN_X - 900f, SUN_Y + 260f)
            lineTo(-200f, 260f)
            lineTo(180f, 190f)
            close()
        },
    )

    private val motePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 239, 196)
    }

    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(224, 236, 255)
    }

    private val starCrossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.7f
        color = Color.rgb(230, 240, 255)
    }

    private val moonGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            MOON_X, MOON_Y, 300f,
            intArrayOf(
                Color.argb(90, 205, 228, 255),
                Color.argb(42, 170, 205, 255),
                Color.argb(16, 120, 165, 235),
                Color.TRANSPARENT,
            ),
            floatArrayOf(0f, 0.22f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    private val moonDiskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            MOON_X - 14f, MOON_Y - 16f, MOON_R,
            intArrayOf(
                Color.rgb(255, 254, 248),
                Color.rgb(240, 242, 232),
                Color.rgb(214, 224, 226),
                Color.rgb(180, 195, 210),
            ),
            floatArrayOf(0f, 0.56f, 0.86f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    private val moonShadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            MOON_X - 45f, MOON_Y - 45f, MOON_X + 50f, MOON_Y + 50f,
            intArrayOf(Color.TRANSPARENT, Color.argb(78, 55, 79, 112)),
            floatArrayOf(0.42f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    private val moonRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = Color.argb(180, 245, 250, 255)
    }

    private val craterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(28, 115, 132, 153)
    }

    private val moonClip = Path().apply {
        addCircle(MOON_X, MOON_Y, MOON_R - 1f, Path.Direction.CW)
    }

    private val meteorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(220, 235, 255)
    }

    private val meteorCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.rgb(255, 255, 252)
    }

    private val meteorHeadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(255, 255, 252)
    }

    private val daySpeedScrim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            240f, 340f, 330f,
            intArrayOf(Color.argb(138, 14, 25, 38), Color.argb(60, 14, 25, 38), Color.TRANSPARENT),
            floatArrayOf(0f, 0.62f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    private val dayTachScrim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            1680f, 330f, 300f,
            intArrayOf(Color.argb(118, 14, 25, 38), Color.argb(40, 14, 25, 38), Color.TRANSPARENT),
            floatArrayOf(0f, 0.7f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    private val nightSpeedScrim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            240f, 340f, 330f,
            intArrayOf(Color.argb(102, 2, 6, 14), Color.argb(38, 2, 6, 14), Color.TRANSPARENT),
            floatArrayOf(0f, 0.65f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    private val nightTachScrim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            1680f, 330f, 300f,
            intArrayOf(Color.argb(86, 2, 6, 14), Color.argb(26, 2, 6, 14), Color.TRANSPARENT),
            floatArrayOf(0f, 0.7f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    init {
        resetMeteors()
    }

    fun applyTheme(next: PortholeTheme) {
        if (theme == next) return
        theme = next
        invalidate()
    }

    fun setMetrics(next: PanelMetrics) {
        if (next == metrics) return
        metrics = next
        invalidate()
    }

    fun start() {
        if (running) return
        running = true
        startedAt = SystemClock.elapsedRealtime()
        targetInitialized = false
        transitionStart = 0L
        mix = if (currentNight() == true) 1f else 0f
        resetMeteors()
        visibility = VISIBLE
        postInvalidateOnAnimation()
    }

    fun stop() {
        running = false
        invalidate()
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }

    private fun currentNight(): Boolean? = nightOverride ?: CarLampMonitor.effectiveNight

    private fun syncLamp(now: Long) {
        val night = currentNight() ?: return
        if (!targetInitialized) {
            targetInitialized = true
            targetNight = night
            onNightChanged?.invoke(night)
            val wanted = if (night) 1f else 0f
            if (wanted != mix) {
                transitionFrom = mix
                transitionTo = wanted
                transitionStart = now
            }
            return
        }
        if (night == targetNight) return
        targetNight = night
        onNightChanged?.invoke(night)
        transitionFrom = mix
        transitionTo = if (night) 1f else 0f
        transitionStart = now
    }

    private fun updateMix(now: Long) {
        if (transitionStart == 0L) return
        val p = ((now - transitionStart) / TRANSITION_MS).coerceIn(0f, 1f)
        // smoothstep：切换头尾都慢一点，不会像硬切。
        val s = p * p * (3f - 2f * p)
        mix = transitionFrom + (transitionTo - transitionFrom) * s
        if (p >= 1f) {
            mix = transitionTo
            transitionStart = 0L
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (!running) return
        super.onDraw(canvas)
        val now = SystemClock.elapsedRealtime()
        if (startedAt == 0L) startedAt = now
        syncLamp(now)
        updateMix(now)
        val t = (now - startedAt) / 1000f

        val save = canvas.save()
        canvas.translate(0f, metrics.offsetY)
        canvas.scale(metrics.scale, metrics.scale)
        when {
            mix <= 0.001f -> drawDay(canvas, t)
            mix >= 0.999f -> drawNight(canvas, t)
            else -> {
                drawDay(canvas, t)
                val layer = canvas.saveLayerAlpha(
                    0f, 0f, PANEL_W, PANEL_H,
                    (mix * 255f).toInt().coerceIn(0, 255),
                    Canvas.ALL_SAVE_FLAG,
                )
                drawNight(canvas, t)
                canvas.restoreToCount(layer)
            }
        }
        canvas.restoreToCount(save)
        postInvalidateOnAnimation()
    }

    private fun drawDay(canvas: Canvas, t: Float) {
        canvas.drawRect(0f, 0f, PANEL_W, PANEL_H, daySkyPaint)
        canvas.drawRect(0f, 330f, PANEL_W, 390f, dayHorizonPaint)
        drawClouds(canvas, t)
        canvas.drawRect(0f, 0f, 520f, PANEL_H, dayLeftVignette)
        canvas.drawRect(PANEL_W - 440f, 0f, PANEL_W, PANEL_H, dayRightVignette)
        canvas.drawRect(0f, 0f, PANEL_W, 170f, dayTopVignette)
        drawSun(canvas, t)
        drawSunShafts(canvas, t)
        drawMotes(canvas, t)
        canvas.drawRect(0f, 0f, 570f, PANEL_H, daySpeedScrim)
        canvas.drawRect(1390f, 30f, PANEL_W, PANEL_H, dayTachScrim)
    }

    private fun drawNight(canvas: Canvas, t: Float) {
        canvas.drawRect(0f, 0f, PANEL_W, PANEL_H, nightSkyPaint)
        canvas.drawRect(0f, 400f, PANEL_W, PANEL_H, nightHorizonPaint)
        canvas.drawRect(0f, 0f, 520f, PANEL_H, nightLeftVignette)
        canvas.drawRect(PANEL_W - 440f, 0f, PANEL_W, PANEL_H, nightRightVignette)
        canvas.drawRect(0f, 0f, PANEL_W, 170f, nightTopVignette)
        drawStars(canvas, t)
        drawMoon(canvas, t)
        drawMeteors(canvas, t)
        canvas.drawRect(0f, 0f, 570f, PANEL_H, nightSpeedScrim)
        canvas.drawRect(1390f, 30f, PANEL_W, PANEL_H, nightTachScrim)
    }

    private fun drawClouds(canvas: Canvas, t: Float) {
        for (cloud in clouds) {
            val span = PANEL_W + cloud.rx * 2f
            val x = ((cloud.x0 + t * cloud.drift) % span) - cloud.rx
            canvas.save()
            canvas.translate(x, cloud.y)
            canvas.scale(cloud.rx, cloud.ry)
            cloudPaint.alpha = cloud.alpha
            canvas.drawCircle(0f, 0f, 1f, cloudPaint)
            canvas.restore()
        }
    }

    private fun drawMotes(canvas: Canvas, t: Float) {
        for (mote in motes) {
            val x = (mote.x0 + t * mote.speed) % PANEL_W
            val y = mote.y + sin(t * 0.36f + mote.phase) * 8f
            val twinkle = 0.65f + 0.35f * sin(t * 0.9f + mote.phase)
            motePaint.alpha = (mote.alpha * twinkle * 255f).toInt().coerceIn(0, 255)
            canvas.drawCircle(x, y, mote.radius, motePaint)
        }
    }

    private fun drawSun(canvas: Canvas, t: Float) {
        sunGlowPaint.alpha = (225f + 18f * sin(t * 0.22f)).toInt().coerceIn(0, 255)
        canvas.drawCircle(SUN_X, SUN_Y, 420f, sunGlowPaint)

        canvas.save()
        canvas.translate(SUN_X, SUN_Y)
        canvas.rotate(t * 1.4f)
        for (index in 0 until RAY_COUNT) {
            canvas.save()
            canvas.rotate(rayAngles[index])
            sunRayPaint.strokeWidth = rayWidths[index]
            sunRayPaint.alpha = rayAlphas[index]
            canvas.drawLine(SUN_R + 8f, 0f, rayLengths[index], 0f, sunRayPaint)
            canvas.restore()
        }
        canvas.restore()

        sunCoronaPaint.strokeWidth = 2f
        sunCoronaPaint.alpha = 48
        canvas.drawCircle(SUN_X, SUN_Y, SUN_R + 30f, sunCoronaPaint)
        sunCoronaPaint.strokeWidth = 1.3f
        sunCoronaPaint.alpha = 28
        canvas.drawCircle(SUN_X, SUN_Y, SUN_R + 56f, sunCoronaPaint)

        canvas.drawCircle(SUN_X, SUN_Y, SUN_R, sunDiskPaint)
        canvas.drawCircle(SUN_X, SUN_Y, SUN_R + 1f, sunRimPaint)
    }

    private fun drawSunShafts(canvas: Canvas, t: Float) {
        sunShaftPaint.alpha = (150f + 28f * sin(t * 0.26f)).toInt().coerceIn(0, 255)
        for (path in sunShaftPaths) canvas.drawPath(path, sunShaftPaint)
    }

    private fun drawStars(canvas: Canvas, t: Float) {
        for (star in stars) {
            val twinkle = 0.72f + 0.28f * sin(t * star.speed + star.phase)
            val alpha = (star.baseAlpha * twinkle * 255f).toInt().coerceIn(0, 255)
            starPaint.alpha = alpha
            canvas.drawCircle(star.x, star.y, star.radius, starPaint)
            if (star.cross) {
                starCrossPaint.alpha = (alpha * 0.55f).toInt().coerceIn(0, 255)
                canvas.drawLine(star.x - 6f, star.y, star.x + 6f, star.y, starCrossPaint)
                canvas.drawLine(star.x, star.y - 6f, star.x, star.y + 6f, starCrossPaint)
            }
        }
    }

    private fun drawMoon(canvas: Canvas, t: Float) {
        moonGlowPaint.alpha = (205f + 22f * sin(t * 0.3f)).toInt().coerceIn(0, 255)
        canvas.drawCircle(MOON_X, MOON_Y, 300f, moonGlowPaint)
        canvas.drawCircle(MOON_X, MOON_Y, MOON_R * 1.06f, moonDiskPaint)
        canvas.drawCircle(MOON_X, MOON_Y, MOON_R, moonShadePaint)

        val save = canvas.save()
        canvas.clipPath(moonClip)
        for (crater in craters) {
            craterPaint.alpha = crater.alpha
            canvas.drawCircle(MOON_X + crater.x, MOON_Y + crater.y, crater.radius, craterPaint)
        }
        canvas.restoreToCount(save)
        canvas.drawCircle(MOON_X, MOON_Y, MOON_R + 0.5f, moonRimPaint)
    }

    private fun resetMeteors() {
        meteors.forEachIndexed { index, meteor ->
            spawnMeteor(meteor, 0.4f + index * 1.55f)
        }
    }

    private fun spawnMeteor(meteor: Meteor, at: Float) {
        val theta = (35f + meteorRandom.nextFloat() * 30f) * (PI.toFloat() / 180f)
        meteor.startAt = at
        meteor.duration = 1.0f + meteorRandom.nextFloat() * 0.8f
        meteor.x0 = 80f + meteorRandom.nextFloat() * 1760f
        meteor.y0 = -50f + meteorRandom.nextFloat() * 190f
        meteor.dx = -cos(theta)
        meteor.dy = sin(theta)
        meteor.speed = 190f + meteorRandom.nextFloat() * 170f
        meteor.length = 120f + meteorRandom.nextFloat() * 180f
        meteor.width = 1.4f + meteorRandom.nextFloat() * 1.8f
        meteor.alpha = 0.35f + meteorRandom.nextFloat() * 0.50f
    }

    private fun drawMeteors(canvas: Canvas, t: Float) {
        for (meteor in meteors) {
            if (meteor.startAt < 0f) spawnMeteor(meteor, t + 1f)
            if (t > meteor.startAt + meteor.duration + 1.2f) {
                spawnMeteor(meteor, t + 1.2f + meteorRandom.nextFloat() * 3.2f)
                continue
            }
            val elapsed = t - meteor.startAt
            if (elapsed < 0f || elapsed > meteor.duration) continue
            val fade = minOf(1f, elapsed / 0.12f, (meteor.duration - elapsed) / 0.18f).coerceIn(0f, 1f)
            if (fade <= 0f) continue

            val headX = meteor.x0 + meteor.dx * meteor.speed * elapsed
            val headY = meteor.y0 + meteor.dy * meteor.speed * elapsed
            val tailX = headX - meteor.dx * meteor.length
            val tailY = headY - meteor.dy * meteor.length
            val nx = -meteor.dy
            val ny = meteor.dx
            val half = meteor.width * 0.5f

            meteor.path.reset()
            meteor.path.moveTo(headX + nx * half, headY + ny * half)
            meteor.path.lineTo(tailX, tailY)
            meteor.path.lineTo(headX - nx * half, headY - ny * half)
            meteor.path.close()

            meteorPaint.alpha = (meteor.alpha * fade * 210f).toInt().coerceIn(0, 255)
            canvas.drawPath(meteor.path, meteorPaint)

            meteorCorePaint.strokeWidth = meteor.width * 0.42f
            meteorCorePaint.alpha = (meteor.alpha * fade * 240f).toInt().coerceIn(0, 255)
            canvas.drawLine(
                headX,
                headY,
                headX - meteor.dx * meteor.length * 0.34f,
                headY - meteor.dy * meteor.length * 0.34f,
                meteorCorePaint,
            )

            meteorHeadPaint.alpha = (fade * 255f).toInt().coerceIn(0, 255)
            canvas.drawCircle(headX, headY, meteor.width * 0.75f, meteorHeadPaint)
            meteorHeadPaint.alpha = (fade * 62f).toInt().coerceIn(0, 255)
            canvas.drawCircle(headX, headY, meteor.width * 2.5f, meteorHeadPaint)
        }
    }

    private companion object {
        const val PANEL_W = 1920f
        const val PANEL_H = 720f
        const val SUN_X = 1615f
        const val SUN_Y = 108f
        const val SUN_R = 62f
        const val MOON_X = 1600f
        const val MOON_Y = 108f
        const val MOON_R = 55f
        const val RAY_COUNT = 26
        const val METEOR_COUNT = 4
        const val TRANSITION_MS = 700f
        const val TWO_PI = (PI * 2.0).toFloat()
    }
}
