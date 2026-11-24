package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import com.shilapi.xcertplay.media.MusicReactiveBus
import java.util.Random
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * 取景窗“射线”主题的背景层。
 *
 * 画在全屏面板的最底层，所以：
 *  - 左侧速度/挡位、右侧转速下面的呼吸底色在车机 MCU 数字**下面**，不会盖住真实读数；
 *  - 中间的地图视频和右侧到达卡在它上面，射线只从四周露出来；
 *  - PC 模拟副屏的 [BenchMcuView] 也压在它上面，bench 里挡位/转速仍然清楚。
 *
 * 颜色来源：歌曲封面的主色相 + 歌曲名哈希兜底；每一下低音重音（PCM 包络）会重新
 * 抽一批射线颜色并发射一组新的光段。默认主题的两条波浪不在这里画。
 *
 * 只按 1920x720 设计稿坐标绘制，再由 [PanelMetrics] 整体缩放；不乘屏幕密度。
 */
internal class PortholeRayView(context: Context) : View(context) {

    private data class Beam(
        var angle: Float,
        var phase: Float,
        var speed: Float,
        var length: Float,
        var width: Float,
        var hue: Float,
        var color: Int,
        /** 只在刚发射的一小段时间里加速，之后回到慢速漂移。 */
        var launchBoost: Float = 0f,
    )

    private var running = false
    private var lastFrameAt = 0L
    private var lastBeatCount = -1L
    private var lastBurstAt = 0L
    private var songKey = ""
    private var coverHue: Float? = null
    private var baseHue = 185f
    private var theme: PortholeTheme? = null
    private var metrics = PanelMetrics(1920, 720)

    private val random = Random(0x5EED11)
    private val beams = Array(BEAM_COUNT) { Beam(0f, 0f, 0f, 0f, 0f, 0f, Color.WHITE) }

    private val beamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val flashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    private var bgShader: LinearGradient? = null
    private var horizonGlow: RadialGradient? = null
    private var speedGlow: RadialGradient? = null
    private var gearGlow: RadialGradient? = null
    private var tachGlow: RadialGradient? = null
    private var gridColor = Color.WHITE
    private var burstColor = Color.WHITE

    init {
        beams.forEach { resetBeam(it, launch = false) }
        rebuildBackground()
        rebuildGlows()
    }

    fun setMetrics(next: PanelMetrics) {
        if (next == metrics) return
        metrics = next
        invalidate()
    }

    /** 主题切换时重新取底色；射线本身不依赖主题，只依赖封面和音乐。 */
    fun applyTheme(next: PortholeTheme) {
        if (theme == next) return
        theme = next
        rebuildBackground()
        rebuildGlows()
        invalidate()
    }

    fun setSongKey(key: String) {
        if (key == songKey) return
        songKey = key
        // 有封面时以封面为准；没有封面才用歌名哈希兜底，避免封面晚到时颜色先跳一下。
        if (coverHue == null) {
            baseHue = hueForKey(key)
            refreshPalette()
        }
    }

    /**
     * 新封面。只在这里采样一次主色相，不持有 Bitmap，避免封面被媒体层回收后还留着引用。
     */
    fun setArtwork(artwork: Bitmap?) {
        val hue = artworkHue(artwork)
        if (hue == coverHue) return
        coverHue = hue
        baseHue = hue ?: hueForKey(songKey)
        refreshPalette()
    }

    fun start() {
        if (running) return
        running = true
        lastFrameAt = 0L
        // 进入取景窗时不要补放一次“假重音”：只跟随之后真正检测到的鼓点。
        lastBeatCount = MusicReactiveBus.beatCount
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

    override fun onDraw(canvas: Canvas) {
        if (!running) return
        super.onDraw(canvas)
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastFrameAt == 0L) 0f else ((now - lastFrameAt).coerceAtMost(50L)) / 1000f
        lastFrameAt = now

        // 音乐暂停 / 没歌时完全不动：不推进相位、不呼吸、不闪。只留一张静态底色，
        // 每 450ms 轻量重绘一次等音乐回来，避免白烧 60fps。
        val active = MusicReactiveBus.activeAt(now)
        val level = if (active) MusicReactiveBus.levelAt(now) else 0f
        val pulse = if (active) MusicReactiveBus.pulseAt(now) else 0f
        if (active) {
            val beats = MusicReactiveBus.beatCount
            if (beats != lastBeatCount) {
                lastBeatCount = beats
                onBeat(now)
            }
        } else {
            lastBeatCount = MusicReactiveBus.beatCount
            // 暂停瞬间收掉上一拍还在扩散的闪光/冲击波，避免“停了还动一下”。
            lastBurstAt = 0L
        }

        val save = canvas.save()
        canvas.translate(0f, metrics.offsetY)
        canvas.scale(metrics.scale, metrics.scale)
        drawBackground(canvas)
        drawGrid(canvas)
        if (active) updateBeams(dt, level, pulse)
        drawBeams(canvas, pulse)
        drawBurst(canvas, now)
        drawGlows(canvas, active, level, pulse)
        canvas.restoreToCount(save)
        // Decorative rays do not need 60 fps while the instrument shares the UI thread with the
        // main CarPlay video. 20 fps is enough for the glow and leaves the main picture ahead.
        if (active) postInvalidateDelayed(50L) else postInvalidateDelayed(IDLE_REFRESH_MS)
    }

    private fun drawBackground(canvas: Canvas) {
        val shader = bgShader
        if (shader == null) {
            canvas.drawColor(Color.BLACK)
        } else {
            bgPaint.shader = shader
            canvas.drawRect(0f, 0f, PANEL_W, PANEL_H, bgPaint)
        }
        horizonGlow?.let { glow ->
            glowPaint.shader = glow
            glowPaint.alpha = 255
            canvas.drawCircle(FOCUS_X, FOCUS_Y, 760f, glowPaint)
        }
    }

    private fun drawGrid(canvas: Canvas) {
        gridPaint.color = gridColor
        // 从灭点向下的透视网格。射线的中心也在这里，网格只当很淡的底纹。
        gridPaint.alpha = 34
        for (i in 0..10) {
            val x = i * PANEL_W / 10f
            canvas.drawLine(FOCUS_X, FOCUS_Y, x, PANEL_H, gridPaint)
        }
        gridPaint.alpha = 16
        for (i in 0..3) {
            val y = 470f + i * 72f
            canvas.drawLine(0f, y, PANEL_W, y, gridPaint)
        }
    }

    private fun updateBeams(dt: Float, level: Float, pulse: Float) {
        if (dt <= 0f) return
        // 安静段慢，响的时候才快；鼓点只做一个短促的加速，不再一直 2.8 倍冲刺。
        val motion = 0.35f + 0.65f * level.coerceIn(0f, 1f)
        val boost = motion * (1f + pulse * 1.2f)
        val launchDecay = (1f - 3.5f * dt).coerceIn(0f, 1f)
        for (beam in beams) {
            beam.phase += dt * beam.speed * boost * (1f + beam.launchBoost)
            beam.launchBoost *= launchDecay
            // 自然走完一圈只换一条慢速射线；只有节拍才给“发射”加速。
            if (beam.phase > 1.2f) resetBeam(beam, launch = false)
        }
    }

    private fun drawBeams(canvas: Canvas, pulse: Float) {
        val boost = 1f + pulse * 0.7f
        for (beam in beams) {
            val r0 = beam.phase * MAX_RADIUS
            val r1 = (beam.phase + beam.length) * MAX_RADIUS
            val span = r1 - r0
            if (span <= 1f) continue
            // 一条很淡的连续底线，让光段看起来像一条穿过全屏的射线，而不是悬空虚线。
            drawBeamSegment(canvas, beam, r0, r1, (13f * boost).toInt(), beam.width * 0.55f)
            drawBeamSegment(canvas, beam, r0, r0 + span * 0.45f, (22f * boost).toInt(), beam.width * 0.7f)
            drawBeamSegment(canvas, beam, r0 + span * 0.45f, r0 + span * 0.8f, (70f * boost).toInt(), beam.width)
            // 先铺一条宽而淡的光晕，再叠窄的高光头：不做 BlurMaskFilter，车机 GPU 更稳。
            drawBeamSegment(canvas, beam, r0 + span * 0.68f, r1, (26f * boost).toInt(), beam.width * 5f)
            drawBeamSegment(
                canvas,
                beam,
                r0 + span * 0.8f,
                r1,
                (185f * boost).toInt().coerceAtMost(255),
                beam.width * 1.5f,
            )
            // 亮头让“发射”方向一眼可读；线段本身有圆角，这一个点负责高光。
            val ca = cos(beam.angle)
            val sa = sin(beam.angle)
            headPaint.color = beam.color
            headPaint.alpha = (150f * boost).toInt().coerceAtMost(255)
            canvas.drawCircle(FOCUS_X + ca * r1, FOCUS_Y + sa * r1, beam.width * 1.1f, headPaint)
        }
    }

    private fun drawBeamSegment(
        canvas: Canvas,
        beam: Beam,
        from: Float,
        to: Float,
        alpha: Int,
        width: Float,
    ) {
        if (to <= from || alpha <= 0) return
        val ca = cos(beam.angle)
        val sa = sin(beam.angle)
        beamPaint.color = beam.color
        beamPaint.alpha = alpha.coerceIn(0, 255)
        beamPaint.strokeWidth = width
        canvas.drawLine(
            FOCUS_X + ca * from,
            FOCUS_Y + sa * from,
            FOCUS_X + ca * to,
            FOCUS_Y + sa * to,
            beamPaint,
        )
    }

    private fun drawBurst(canvas: Canvas, now: Long) {
        val age = now - lastBurstAt
        if (lastBurstAt == 0L || age !in 0L..650L) return
        val progress = age / 650f
        // 重音时整块底色轻微提亮，再放一道很淡的冲击波；中心大部分会被地图盖住。
        flashPaint.color = burstColor
        flashPaint.alpha = ((1f - progress) * 22f).toInt().coerceIn(0, 255)
        canvas.drawRect(0f, 0f, PANEL_W, PANEL_H, flashPaint)
        val radius = 160f + progress * 820f
        beamPaint.color = burstColor
        beamPaint.strokeWidth = 2f
        beamPaint.alpha = ((1f - progress) * 55f).toInt().coerceIn(0, 255)
        canvas.drawCircle(FOCUS_X, FOCUS_Y, radius, beamPaint)
    }

    private fun drawGlows(canvas: Canvas, active: Boolean, level: Float, pulse: Float) {
        // 只在有音乐时呼吸；没有音乐时留一层很淡的静态底色，不再自己 sin 摆动。
        val breath = if (active) (0.15f + 0.70f * pulse + 0.25f * level).coerceIn(0f, 1f) else 0f
        drawGlow(canvas, speedGlow, SPEED_X, SPEED_Y, SPEED_R, breath)
        drawGlow(canvas, gearGlow, GEAR_X, GEAR_Y, GEAR_R, (breath * 0.9f).coerceIn(0f, 1f))
        drawGlow(canvas, tachGlow, TACH_X, TACH_Y, TACH_R, breath)
    }

    private fun drawGlow(
        canvas: Canvas,
        shader: RadialGradient?,
        x: Float,
        y: Float,
        radius: Float,
        intensity: Float,
    ) {
        if (shader == null) return
        glowPaint.shader = shader
        glowPaint.alpha = (30f + 190f * intensity).toInt().coerceIn(0, 220)
        canvas.drawCircle(x, y, radius, glowPaint)
    }

    private fun onBeat(now: Long) {
        lastBurstAt = now
        // 色相只轻轻漂一点，避免重音时整块背景跳色；每次只发射一小批光段，
        // 其它射线继续慢慢走，节奏才会像“音乐在推”而不是全屏乱闪。
        baseHue = normalizeHue(baseHue + random.nextFloat() * 6f - 3f)
        for (beam in beams) {
            if (random.nextFloat() < 0.16f) {
                resetBeam(beam, launch = true)
            } else if (random.nextFloat() < 0.35f) {
                beam.hue = pickHue()
                beam.color = colorOf(beam.hue)
            }
        }
        rebuildBackground()
        rebuildGlows()
    }

    private fun refreshPalette() {
        for (beam in beams) {
            beam.hue = pickHue()
            beam.color = colorOf(beam.hue)
        }
        rebuildBackground()
        rebuildGlows()
        invalidate()
    }

    private fun resetBeam(beam: Beam, launch: Boolean) {
        beam.angle = random.nextFloat() * TWO_PI
        beam.phase = if (launch) 0f else random.nextFloat()
        // 底线速度压慢：即使没有鼓点也只是缓慢漂移，不会自己高速绕圈。
        beam.speed = 0.06f + random.nextFloat() * 0.10f
        beam.length = 0.2f + random.nextFloat() * 0.38f
        beam.width = 1.2f + random.nextFloat() * 2.8f
        beam.launchBoost = if (launch) 2.4f else 0f
        beam.hue = pickHue()
        beam.color = colorOf(beam.hue)
    }

    private fun pickHue(): Float {
        val offset = HUE_OFFSETS[random.nextInt(HUE_OFFSETS.size)]
        return normalizeHue(baseHue + offset + random.nextFloat() * 28f - 14f)
    }

    private fun colorOf(hue: Float): Int =
        Color.HSVToColor(floatArrayOf(normalizeHue(hue), 0.92f, 1f))

    private fun rebuildBackground() {
        val top = Color.HSVToColor(floatArrayOf(normalizeHue(baseHue), 0.72f, 0.16f))
        val mid = Color.HSVToColor(floatArrayOf(normalizeHue(baseHue + 35f), 0.78f, 0.055f))
        val bottom = theme?.palette?.backdrop ?: Color.BLACK
        bgShader = LinearGradient(
            0f,
            0f,
            0f,
            PANEL_H,
            intArrayOf(top, mid, bottom),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        val horizon = Color.HSVToColor(floatArrayOf(normalizeHue(baseHue), 0.85f, 1f))
        gridColor = horizon
        burstColor = horizon
        horizonGlow = RadialGradient(
            FOCUS_X,
            FOCUS_Y,
            760f,
            intArrayOf(
                Color.argb(58, Color.red(horizon), Color.green(horizon), Color.blue(horizon)),
                Color.TRANSPARENT,
            ),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    private fun rebuildGlows() {
        val color = Color.HSVToColor(floatArrayOf(normalizeHue(baseHue), 0.9f, 1f))
        speedGlow = radial(SPEED_X, SPEED_Y, SPEED_R, color)
        gearGlow = radial(GEAR_X, GEAR_Y, GEAR_R, color)
        tachGlow = radial(TACH_X, TACH_Y, TACH_R, color)
    }

    private fun radial(x: Float, y: Float, radius: Float, color: Int): RadialGradient =
        RadialGradient(
            x,
            y,
            radius,
            intArrayOf(color, Color.argb(70, Color.red(color), Color.green(color), Color.blue(color)), Color.TRANSPARENT),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )

    private fun hueForKey(key: String): Float {
        if (key.isBlank()) return 185f
        val bucket = (key.hashCode().toLong() and 0x7fffffffL) % 1000L
        return normalizeHue(170f + bucket / 1000f * 110f)
    }

    /**
     * 封面主色相：先缩到 24x24 再取，只有高饱和/不太暗的像素参与；按色相的圆周平均
     * 避免红-紫跨 0° 时算出中间值。没有可用颜色时返回 null，由歌名兜底。
     */
    private fun artworkHue(artwork: Bitmap?): Float? {
        if (artwork == null || artwork.isRecycled || artwork.width <= 0 || artwork.height <= 0) return null
        return runCatching {
            val sample = Bitmap.createScaledBitmap(artwork, 24, 24, false)
            val pixels = IntArray(24 * 24)
            try {
                sample.getPixels(pixels, 0, 24, 0, 0, 24, 24)
            } finally {
                if (sample !== artwork) sample.recycle()
            }
            val hsv = FloatArray(3)
            var sx = 0.0
            var sy = 0.0
            var weight = 0f
            for (pixel in pixels) {
                if (Color.alpha(pixel) < 128) continue
                Color.colorToHSV(pixel, hsv)
                if (hsv[1] < 0.18f || hsv[2] < 0.12f) continue
                val w = hsv[1] * hsv[2]
                val rad = hsv[0] * PI / 180.0
                sx += cos(rad) * w
                sy += sin(rad) * w
                weight += w
            }
            if (weight <= 0.001f) {
                null
            } else {
                normalizeHue((atan2(sy, sx) * 180.0 / PI).toFloat())
            }
        }.getOrNull()
    }

    private fun normalizeHue(value: Float): Float {
        val wrapped = value % 360f
        return if (wrapped < 0f) wrapped + 360f else wrapped
    }

    private companion object {
        const val PANEL_W = 1920f
        const val PANEL_H = 720f
        const val FOCUS_X = 960f
        const val FOCUS_Y = 360f
        const val MAX_RADIUS = 1_120f
        const val BEAM_COUNT = 44
        /** 没有音乐时的轮询间隔：只为了在音乐恢复时醒来，不参与动画。 */
        const val IDLE_REFRESH_MS = 450L
        val TWO_PI = (PI * 2.0).toFloat()

        // 左侧真实速度/挡位、右侧转速的大致中心（面板坐标）。数字本身由 MCU 画，
        // 这里只铺下面的呼吸底色，所以坐标不必精确到字宽。
        const val SPEED_X = 235f
        const val SPEED_Y = 300f
        const val SPEED_R = 300f
        const val GEAR_X = 235f
        const val GEAR_Y = 535f
        const val GEAR_R = 210f
        const val TACH_X = 1_665f
        const val TACH_Y = 300f
        const val TACH_R = 300f

        /** 以封面主色为基准的冷/暖混合偏移，避免所有射线一个颜色。 */
        val HUE_OFFSETS = floatArrayOf(-38f, -14f, 8f, 34f, 150f, 190f)
    }
}
