package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * 取景窗“数学之美”主题的背景层。
 *
 * 每首歌从四种公式族里挑一种：
 *  - FOURIER：多阶 Lissajous / Fourier 参数曲线；
 *  - BLOOM：Superformula 花瓣 + Maurer 玫瑰弦网；
 *  - CHAOS：Clifford / De Jong 混沌吸引子点云；
 *  - KNOT：三股环面纽结 + Harmonograph。
 *
 * 公式参数由歌名哈希决定，所以同一首歌稳定、换歌才会换图形；主色和辅助色从封面
 * 24x24 缩略图的色相直方图取，不持有大图。复杂图形先画到一张透明 Bitmap，再逐帧
 * 叠加呼吸/漂移，避免在 60fps 里反复算十万个点。
 */
internal class PortholeMathView(context: Context) : View(context) {

    private enum class Motif { FOURIER, BLOOM, CHAOS, KNOT }

    private data class MotifPalette(val primary: Int, val secondary: Int, val tertiary: Int)

    private var running = false
    private var startedAt = 0L
    private var metrics = PanelMetrics(1920, 720)
    private var theme: PortholeTheme? = null

    private var songKey = ""
    private var coverHue: Float? = null
    private var coverHue2: Float? = null
    private var motif = Motif.FOURIER
    private var motifSeed = 0x51A7L

    // Only the chaotic point cloud is cached; the three line-based families are cheap enough
    // to redraw every 33 ms, which is what makes the formulas visibly move.
    private var chaosA = -1.4f
    private var chaosB = 1.6f
    private var chaosC = 1.0f
    private var chaosD = 0.7f
    private var chaosX = 0.1f
    private var chaosY = 0.1f
    private var chaosLiveX = 0.1f
    private var chaosLiveY = 0.1f

    private var primary = Color.rgb(90, 242, 207)
    private var secondary = Color.rgb(255, 197, 107)
    private var tertiary = Color.rgb(109, 157, 255)
    private var background = Color.rgb(5, 14, 20)
    private var gridColor = Color.argb(30, 90, 242, 207)
    private var dimColor = Color.argb(100, 143, 181, 178)

    private var motifBitmap: Bitmap? = null
    private val motifPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        strokeCap = Paint.Cap.ROUND
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val formulaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = dimColor
        typeface = Typeface.MONOSPACE
        textSize = 12f
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val speedScrim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            240f, 345f, 300f,
            intArrayOf(Color.argb(172, 2, 7, 12), Color.argb(50, 2, 7, 12), Color.TRANSPARENT),
            floatArrayOf(0f, 0.66f, 1f),
            Shader.TileMode.CLAMP,
        )
    }
    private val tachScrim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            1680f, 330f, 300f,
            intArrayOf(Color.argb(138, 2, 7, 12), Color.argb(34, 2, 7, 12), Color.TRANSPARENT),
            floatArrayOf(0f, 0.66f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    /** 宿主用它把封面主色同步给右侧卡片/取景窗边框。 */
    var onAccent: ((Int, Int) -> Unit)? = null

    val primaryColor: Int get() = primary
    val secondaryColor: Int get() = secondary

    init {
        refreshPalette(rebuild = false)
    }

    fun setMetrics(next: PanelMetrics) {
        if (next == metrics) return
        metrics = next
        invalidate()
    }

    fun applyTheme(next: PortholeTheme) {
        if (theme == next) return
        theme = next
        invalidate()
    }

    fun start() {
        if (running) return
        running = true
        startedAt = SystemClock.elapsedRealtime()
        visibility = VISIBLE
        if (motif == Motif.CHAOS && motifBitmap == null) rebuildMotif()
        postInvalidateOnAnimation()
    }

    fun stop() {
        running = false
        invalidate()
    }

    override fun onDetachedFromWindow() {
        running = false
        motifBitmap?.recycle()
        motifBitmap = null
        super.onDetachedFromWindow()
    }

    fun setSongKey(key: String) {
        if (key == songKey) return
        songKey = key
        val seed = if (key.isBlank()) 0x51A7L else key.hashCode().toLong()
        motif = Motif.values()[floorMod(seed, Motif.values().size)]
        motifSeed = seed
        if (coverHue == null) refreshPalette() else rebuildMotif()
        invalidate()
    }

    fun setArtwork(artwork: Bitmap?) {
        val hues = artworkHues(artwork)
        if (hues == null) {
            if (coverHue != null) {
                coverHue = null
                coverHue2 = null
                refreshPalette()
            }
            return
        }
        if (hues.first == coverHue && hues.second == coverHue2) return
        coverHue = hues.first
        coverHue2 = hues.second
        refreshPalette()
    }

    /** 在右侧卡片创建晚于本视图时，让宿主补一次当前色。 */
    fun publishAccent() {
        onAccent?.invoke(primary, secondary)
    }

    private fun refreshPalette(rebuild: Boolean = true) {
        val h1 = coverHue ?: hueForKey(songKey)
        // 没有封面时用青绿+鎏金的作者配色；有封面但只取到一个色相时用它的近互补色。
        var h2 = coverHue2 ?: if (coverHue == null) 42f else h1 + 140f
        if (angularDistance(h1, h2) < 34f) h2 = h1 + 140f
        primary = colorFor(h1, 0.62f, 0.98f)
        secondary = colorFor(h2, 0.60f, 0.98f)
        tertiary = colorFor(h1 + 60f, 0.45f, 0.92f)
        background = colorFor(h1, 0.55f, 0.045f)
        gridColor = withAlpha(primary, 48)
        dimColor = withAlpha(primary, 130)
        formulaPaint.color = withAlpha(primary, 96)
        publishAccent()
        if (rebuild) rebuildMotif()
    }

    private fun rebuildMotif() {
        val old = motifBitmap
        if (motif != Motif.CHAOS) {
            motifBitmap = null
            old?.recycle()
            invalidate()
            return
        }
        val random = Random(motifSeed xor 0x5EED51A7L)
        chaosA = -1.4f + (random.nextFloat() - 0.5f) * 0.08f
        chaosB = 1.6f + (random.nextFloat() - 0.5f) * 0.08f
        chaosC = 1.0f
        chaosD = 0.7f
        chaosX = 0.1f
        chaosY = 0.1f
        chaosLiveX = 0.1f
        chaosLiveY = 0.1f
        val bitmap = Bitmap.createBitmap(1920, 720, Bitmap.Config.ARGB_8888)
        drawChaos(Canvas(bitmap), random)
        motifBitmap = bitmap
        old?.recycle()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!running) return
        super.onDraw(canvas)
        if (motifBitmap == null) rebuildMotif()
        val now = SystemClock.elapsedRealtime()
        if (startedAt == 0L) startedAt = now
        val t = (now - startedAt) / 1000f

        val save = canvas.save()
        canvas.translate(0f, metrics.offsetY)
        canvas.scale(metrics.scale, metrics.scale)
        bgPaint.color = background
        canvas.drawRect(0f, 0f, PANEL_W, PANEL_H, bgPaint)
        drawGrid(canvas)
        drawFormula(canvas)

        val random = Random(motifSeed xor 0x5EED51A7L)
        when (motif) {
            Motif.FOURIER -> drawFourier(canvas, random, t)
            Motif.BLOOM -> drawBloom(canvas, random, t)
            Motif.CHAOS -> {
                if (motifBitmap == null) rebuildMotif()
                motifPaint.alpha = (242f + 8f * sin(t * 0.42f)).toInt().coerceIn(0, 255)
                motifBitmap?.let { canvas.drawBitmap(it, 0f, 0f, motifPaint) }
                drawChaosLive(canvas)
            }
            Motif.KNOT -> drawKnot(canvas, random, t)
        }
        drawTwinkles(canvas, t)
        canvas.drawRect(0f, 0f, 580f, PANEL_H, speedScrim)
        canvas.drawRect(1360f, 0f, PANEL_W, PANEL_H, tachScrim)
        canvas.restoreToCount(save)
        // The expensive formula field is a cached bitmap; 20fps is enough for the breathing
        // overlay and materially cheaper than repainting a 1920x720 layer at 30/60fps. This
        // leaves more UI-thread time for the main CarPlay video.
        postInvalidateDelayed(50L)
    }

    private fun drawGrid(canvas: Canvas) {
        gridPaint.color = gridColor
        gridPaint.strokeWidth = 1f
        for (x in 0..PANEL_W.toInt() step 32) {
            canvas.drawLine(x.toFloat(), 0f, x.toFloat(), PANEL_H, gridPaint)
        }
        for (y in 0..PANEL_H.toInt() step 32) {
            canvas.drawLine(0f, y.toFloat(), PANEL_W, y.toFloat(), gridPaint)
        }
        gridPaint.color = withAlpha(primary, 54)
        gridPaint.strokeWidth = 1.2f
        for (x in 0..PANEL_W.toInt() step 160) {
            canvas.drawLine(x.toFloat(), 0f, x.toFloat(), PANEL_H, gridPaint)
        }
        for (y in 0..PANEL_H.toInt() step 160) {
            canvas.drawLine(0f, y.toFloat(), PANEL_W, y.toFloat(), gridPaint)
        }
        gridPaint.color = withAlpha(primary, 90)
        canvas.drawLine(0f, 360f, PANEL_W, 360f, gridPaint)
        canvas.drawLine(960f, 0f, 960f, PANEL_H, gridPaint)

        formulaPaint.color = withAlpha(dimColor, 150)
        formulaPaint.textAlign = Paint.Align.CENTER
        for (x in 80..PANEL_W.toInt() step 160) {
            canvas.drawText(((x - 960) / 80).toString(), x.toFloat(), 378f, formulaPaint)
        }
        formulaPaint.textAlign = Paint.Align.LEFT
        for (y in 40..PANEL_H.toInt() step 80) {
            canvas.drawText(((360 - y) / 40).toString(), 8f, y.toFloat(), formulaPaint)
        }
    }

    private fun drawFormula(canvas: Canvas) {
        val lines = when (motif) {
            Motif.FOURIER -> arrayOf(
                "x(t) = Σ aₙ cos((2n+1)t + φₙ)",
                "y(t) = Σ bₙ sin((3n+2)t + ψₙ)",
                "n = 1 … 5    t ∈ [0, 2π]",
            )
            Motif.BLOOM -> arrayOf(
                "r(θ) = (|cos(mθ/4)/a|ⁿ² + |sin(mθ/4)/b|ⁿ³)^(−1/n₁)",
                "× (1 + ε sin(kθ + φ))",
                "m = 9    n₁ = 0.32    n₂ = n₃ = 0.36",
            )
            Motif.CHAOS -> arrayOf(
                "xₙ₊₁ = sin(a yₙ) + c cos(a xₙ)",
                "yₙ₊₁ = sin(b xₙ) + d cos(b yₙ)",
                "a = −1.4   b = 1.6   c = 1.0   d = 0.7",
            )
            Motif.KNOT -> arrayOf(
                "x = (R + r cos(qt)) cos(pt)",
                "y = (R + r cos(qt)) sin(pt)    z = r sin(qt)",
                "p = 3   q = 7   t ∈ [0, 2π]",
            )
        }
        formulaPaint.color = withAlpha(dimColor, 115)
        formulaPaint.textAlign = Paint.Align.LEFT
        formulaPaint.textSize = 12f
        lines.forEachIndexed { index, line ->
            canvas.drawText(line, 42f, 640f + index * 20f, formulaPaint)
        }
        formulaPaint.textAlign = Paint.Align.RIGHT
        formulaPaint.color = withAlpha(dimColor, 90)
        canvas.drawText("generative · parametric · cover-tinted", 1878f, 686f, formulaPaint)
    }

    private fun drawTwinkles(canvas: Canvas, t: Float) {
        val random = Random(motifSeed xor 0x77A1)
        val count = 22
        repeat(count) {
            val x = 30f + random.nextFloat() * 1820f
            val y = 30f + random.nextFloat() * 620f
            val phase = random.nextFloat() * PI.toFloat() * 2f
            val speed = 0.5f + random.nextFloat() * 1.8f
            val alpha = (0.15f + 0.35f * (0.5f + 0.5f * sin(t * speed + phase))).coerceIn(0f, 1f)
            fillPaint.color = withAlpha(if (it % 3 == 0) secondary else primary, (alpha * 255).toInt())
            canvas.drawCircle(x, y, 0.9f + (it % 4) * 0.35f, fillPaint)
        }
    }

    private fun drawLinePath(canvas: Canvas, path: Path, color: Int, width: Float, alpha: Float, glow: Float) {
        linePaint.color = color
        linePaint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
        linePaint.strokeWidth = width
        if (glow > 0f) linePaint.setShadowLayer(glow, 0f, 0f, color)
        canvas.drawPath(path, linePaint)
        linePaint.clearShadowLayer()
    }

    private fun drawFourier(canvas: Canvas, random: Random, t: Float) {
        // One clean 3:2 Lissajous. Earlier versions added multiple phase copies and high
        // harmonics; those read as a scribble. A single low-order figure stays legible while
        // the slow phase and a very slow rotation keep it alive.
        val phase = t * 0.06f
        val rotation = t * 0.015f
        val cosR = cos(rotation)
        val sinR = sin(rotation)
        val path = Path()
        for (i in 0..760) {
            val theta = i / 760f * PI.toFloat() * 2f
            val x = sin(3f * theta + phase) * 0.95f + 0.045f * sin(5f * theta + phase * 0.6f)
            val y = sin(2f * theta + phase * 0.42f) * 0.95f + 0.035f * cos(4f * theta + phase * 0.5f)
            val px = 200f + (x * cosR - y * sinR) * 170f
            val py = 355f + (x * sinR + y * cosR) * 190f
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawLinePath(canvas, path, primary, 2.0f, 0.92f, 10f)

        for (band in 0..1) {
            val path = Path()
            for (i in 0..760) {
                val u = i / 760f
                val x = -70f + u * 2060f
                val y = (if (band == 0) 58f else 662f) +
                    30f * sin(u * 22f * PI.toFloat() + 0.6f + t * 0.16f) +
                    8f * sin(u * 41f * PI.toFloat() + 1.1f + t * 0.23f)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawLinePath(canvas, path, if (band == 0) primary else secondary, 1.2f, 0.48f, 6f)
        }

        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        for (i in 0..2) {
            ringPaint.color = withAlpha(if (i % 2 == 0) primary else secondary, 38)
            val pulse = 2.0f * sin(t * 0.25f + i * 0.4f)
            canvas.drawCircle(200f, 355f, 58f + i * 44f + pulse, ringPaint)
        }
        fillPaint.color = secondary
        for (i in 0..7) {
            val angle = i * 0.88f + 0.4f + t * 0.18f
            val radius = 62f + (i % 4) * 38f
            fillPaint.alpha = 60 + (i % 3) * 40
            canvas.drawCircle(200f + cos(angle) * radius, 355f + sin(angle) * radius, 1.5f, fillPaint)
        }
    }

    private fun superRadius(theta: Float, m: Int, n1: Float, n2: Float, n3: Float, k: Int, phase: Float): Float {
        val t1 = abs(cos(m * theta / 4f)).toDouble().pow(n2.toDouble())
        val t2 = abs(sin(m * theta / 4f)).toDouble().pow(n3.toDouble())
        val base = (t1 + t2).pow((-1.0 / n1).toDouble()).toFloat()
        return (base * (1f + 0.13f * sin(k * theta + phase))).coerceIn(0.08f, 3.2f)
    }

    private fun drawBloom(canvas: Canvas, random: Random, t: Float) {
        val layers = arrayOf(
            floatArrayOf(9f, 0.32f, 0.36f, 0.36f, 7f, 0.4f, 168f, 0.78f),
            floatArrayOf(9f, 0.32f, 0.36f, 0.36f, 11f, 2.2f, 132f, 0.48f),
            floatArrayOf(7f, 0.40f, 0.42f, 0.42f, 5f, 3.4f, 96f, 0.38f),
        )
        val colors = intArrayOf(primary, secondary, tertiary)
        for ((index, layer) in layers.withIndex()) {
            val path = Path()
            for (i in 0..800) {
                val theta = i / 800f * PI.toFloat() * 2f
                val r = superRadius(
                    theta,
                    layer[0].toInt(),
                    layer[1],
                    layer[2],
                    layer[3],
                    layer[4].toInt(),
                    layer[5] + t * 0.20f,
                ) * 0.74f
                val angle = theta + if (index == 1) 2.1f else if (index == 2) 4.4f else 0f +
                    t * (0.10f + index * 0.015f)
                val x = 170f + cos(angle) * r * layer[6]
                val y = 365f + sin(angle) * r * layer[6] * 1.05f
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            if (index == 0) {
                fillPaint.color = withAlpha(primary, 30)
                canvas.drawPath(path, fillPaint)
            }
            drawLinePath(canvas, path, colors[index], if (index == 0) 2.1f else 1.2f, layer[7], if (index == 0) 15f else 6f)
        }

        val mesh = Path()
        for (i in 0..250) {
            val theta = i * 97f * PI.toFloat() / 180f + t * 0.08f
            val r = sin(5f * theta) * 136f
            val x = 220f + cos(theta) * r
            val y = 360f + sin(theta) * r
            if (i == 0) mesh.moveTo(x, y) else mesh.lineTo(x, y)
        }
        drawLinePath(canvas, mesh, secondary, 0.8f, 0.42f, 4f)

        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        for (i in 0..5) {
            ringPaint.color = withAlpha(if (i < 3) primary else tertiary, 40 - i * 4)
            canvas.drawOval(RectF(68f - i * 0f, 253f + i * 0f, 252f + i * 76f, 477f + i * 76f), ringPaint)
        }

        val small = Path()
        for (i in 0..700) {
            val theta = i / 700f * PI.toFloat() * 2f
            val r = superRadius(theta, 7, 0.4f, 0.42f, 0.42f, 5, 1.1f + t * 0.25f) * 0.55f
            val angle = theta + t * 0.22f
            val x = 1738f + cos(angle) * r * 54f
            val y = 168f + sin(angle) * r * 54f
            if (i == 0) small.moveTo(x, y) else small.lineTo(x, y)
        }
        small.close()
        drawLinePath(canvas, small, tertiary, 0.9f, 0.52f, 5f)

        for (band in 0..1) {
            val path = Path()
            for (i in 0..800) {
                val u = i / 800f
                val y = (if (band == 0) 54f else 666f) +
                    18f * sin(u * 13f * PI.toFloat() + 1.2f + t * 0.30f) +
                    5f * sin(u * 31f * PI.toFloat() + t * 0.45f)
                val x = -60f + u * 2040f
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawLinePath(canvas, path, if (band == 0) primary else secondary, 0.8f, 0.26f, 4f)
        }
    }

    private fun drawChaos(canvas: Canvas, random: Random) {
        val sets = arrayOf(
            floatArrayOf(chaosA, chaosB, chaosC, chaosD),
            floatArrayOf(1.4f + (random.nextFloat() - 0.5f) * 0.05f, -2.3f, 2.4f, -2.1f),
        )
        val attractors = arrayOf(
            floatArrayOf(215f, 352f, 90f, 115f, 2800f),
            floatArrayOf(1690f, 110f, 100f, 78f, 3000f),
        )
        for ((index, at) in attractors.withIndex()) {
            val p = sets[index]
            val a = p[0]
            val b = p[1]
            val c = p[2]
            val d = p[3]
            val count = at[4].toInt()
            val points = FloatArray(count * 2)
            var x = 0.1f
            var y = 0.1f
            for (i in 0 until count) {
                val nx = sin(a * y) + c * cos(a * x)
                val ny = sin(b * x) + d * cos(b * y)
                x = nx
                y = ny
                points[i * 2] = at[0] + x * at[2]
                points[i * 2 + 1] = at[1] + y * at[3]
            }
            pointPaint.color = if (index == 0) primary else secondary
            pointPaint.alpha = if (index == 0) 108 else 88
            pointPaint.strokeWidth = 1.7f
            canvas.drawPoints(points, pointPaint)
            pointPaint.color = tertiary
            pointPaint.alpha = 36
            pointPaint.strokeWidth = 1.3f
            canvas.drawPoints(points, pointPaint)
        }

        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        for (i in 0..4) {
            ringPaint.color = withAlpha(if (i < 3) primary else tertiary, 48 - i * 6)
            canvas.drawOval(RectF(20f + i * 0f, 210f + i * 0f, 320f + i * 38f, 510f + i * 30f), ringPaint)
        }

        for (band in 0..1) {
            val path = Path()
            for (i in 0..760) {
                val u = i / 760f
                val y = (if (band == 0) 52f else 668f) +
                    34f * sin(u * 25f * PI.toFloat() + 1.3f) +
                    9f * sin(u * 59f * PI.toFloat())
                val x = -70f + u * 2060f
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawLinePath(canvas, path, if (band == 0) tertiary else secondary, 1.0f, 0.38f, 6f)
        }
    }

    /**
     * A small live slice of the same Clifford attractor, advanced every frame. The full
     * 60k-point cloud stays cached, but these bright points visibly travel through the cloud,
     * so the theme is not a dead picture.
     */
    private fun drawChaosLive(canvas: Canvas) {
        val count = 1800
        val points = FloatArray(count * 2)
        var x = chaosLiveX
        var y = chaosLiveY
        for (i in 0 until count) {
            val nx = sin(chaosA * y) + chaosC * cos(chaosA * x)
            val ny = sin(chaosB * x) + chaosD * cos(chaosB * y)
            x = nx
            y = ny
            points[i * 2] = 215f + x * 90f
            points[i * 2 + 1] = 352f + y * 115f
        }
        chaosLiveX = x
        chaosLiveY = y

        // Never join the live samples into one path: consecutive Clifford points jump across
        // the whole attractor, and connecting them filled the entire left column with a solid
        // blob. Bright moving points over the sparse cached cloud keep the motion without the
        // "green noise block" look.
        pointPaint.color = secondary
        pointPaint.alpha = 225
        pointPaint.strokeWidth = 1.8f
        canvas.drawPoints(points, pointPaint)
        pointPaint.color = tertiary
        pointPaint.alpha = 105
        pointPaint.strokeWidth = 1.3f
        canvas.drawPoints(points, pointPaint)
    }

    private fun drawKnot(canvas: Canvas, random: Random, t: Float) {
        val p = 3
        val q = 7
        val r = 0.38f
        val yaw = 0.62f + t * 0.13f
        val pitch = -0.44f + 0.10f * sin(t * 0.35f)
        val colors = intArrayOf(primary, secondary, tertiary)
        for (strand in -1..1) {
            val points = ArrayList<FloatArray>(900)
            val rr = r + strand * 0.045f
            for (i in 0..900) {
                val u = i / 900f * PI.toFloat() * 2f
                val kx = (1f + rr * cos(q * u)) * cos(p * u)
                val ky = (1f + rr * cos(q * u)) * sin(p * u)
                val kz = rr * sin(q * u)
                val x1 = kx * cos(yaw) - ky * sin(yaw)
                val y1 = kx * sin(yaw) + ky * cos(yaw)
                val y2 = y1 * cos(pitch) - kz * sin(pitch)
                val z2 = y1 * sin(pitch) + kz * cos(pitch)
                points.add(floatArrayOf(195f + x1 * 150f, 360f + y2 * 200f, z2))
            }
            // Keep the parametric order. Sorting the points by z and then joining neighbours
            // would connect unrelated samples into a filled fan (the bug that made the first
            // trefoil render look like a solid blob).
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
            }
            for (i in 0 until points.size - 1) {
                val a = points[i]
                val b = points[i + 1]
                val depth = ((a[2] + b[2]) * 0.5f + 0.5f).coerceIn(0f, 1f)
                paint.color = if (strand == 0 && depth < 0.72f) primary else colors[strand + 1]
                paint.alpha = ((if (strand == 0) 0.08f else 0.04f) +
                    (if (strand == 0) 0.60f else 0.28f) * max(0f, depth) * 255f).toInt().coerceIn(0, 255)
                paint.strokeWidth = (if (strand == 0) 0.45f else 0.3f) +
                    (if (strand == 0) 1.15f else 0.6f) * depth
                if (strand == 0) paint.setShadowLayer(5f, 0f, 0f, primary)
                canvas.drawLine(a[0], a[1], b[0], b[1], paint)
            }
            paint.clearShadowLayer()
        }

        for (copy in 0..1) {
            val path = Path()
            for (i in 0..760) {
                val u = i / 760f
                val x = 600f + u * 1280f
                val y = (if (copy == 0) 66f else 112f) +
                    26f * sin(u * 14f * PI.toFloat() + copy * 1.8f + t * 0.28f) +
                    9f * sin(u * 31f * PI.toFloat() + 0.6f + t * 0.42f)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawLinePath(canvas, path, if (copy == 0) tertiary else secondary, 0.9f, if (copy == 0) 0.46f else 0.34f, 5f)
        }

        val small = Path()
        for (i in 0..700) {
            val t = i / 700f * PI.toFloat() * 2f
            val kx = (1f + 0.34f * cos(3f * t)) * cos(2f * t)
            val ky = (1f + 0.34f * cos(3f * t)) * sin(2f * t)
            val kz = 0.34f * sin(3f * t)
            val smallYaw = 0.3f + t * 0.18f
            val x1 = kx * cos(smallYaw) - ky * sin(smallYaw)
            val y1 = kx * sin(smallYaw) + ky * cos(smallYaw)
            val x = 1738f + x1 * 68f
            val y = 118f + (y1 * 0.7f - kz * 0.7f) * 68f
            if (i == 0) small.moveTo(x, y) else small.lineTo(x, y)
        }
        drawLinePath(canvas, small, secondary, 0.9f, 0.55f, 5f)

        val spiral = Path()
        for (i in 0..420) {
            val u = i / 420f * PI.toFloat() * 4.2f + t * 0.16f
            val rr = 5f * 1.055f.pow(i / 5f)
            val x = 86f + cos(u) * rr
            val y = 655f + sin(u) * rr
            if (i == 0) spiral.moveTo(x, y) else spiral.lineTo(x, y)
        }
        drawLinePath(canvas, spiral, secondary, 1f, 0.32f, 4f)
    }

    private fun hueForKey(key: String): Float {
        if (key.isBlank()) return 178f
        val bucket = (key.hashCode().toLong() and 0x7fffffffL) % 120L
        return 160f + bucket
    }

    /**
     * 24x24 缩略图的色相直方图：主色 + 第二个明显不同的色相。没有足够彩色像素时
     * 返回 null，由歌名哈希兜底。与射线主题同一套思路，但这里需要双色。
     */
    private fun artworkHues(artwork: Bitmap?): Pair<Float, Float?>? {
        if (artwork == null || artwork.isRecycled || artwork.width <= 0 || artwork.height <= 0) return null
        return runCatching {
            val sample = Bitmap.createScaledBitmap(artwork, 24, 24, false)
            val pixels = IntArray(24 * 24)
            try {
                sample.getPixels(pixels, 0, 24, 0, 0, 24, 24)
            } finally {
                if (sample !== artwork) sample.recycle()
            }
            val bins = FloatArray(24)
            val hsv = FloatArray(3)
            for (pixel in pixels) {
                if (Color.alpha(pixel) < 128) continue
                Color.colorToHSV(pixel, hsv)
                if (hsv[1] < 0.18f || hsv[2] < 0.12f) continue
                val bin = ((hsv[0] / 15f).toInt().coerceIn(0, 23))
                bins[bin] += hsv[1] * hsv[2]
            }
            val total = bins.sum()
            if (total < 0.8f) return@runCatching null
            var first = 0
            for (i in bins.indices) if (bins[i] > bins[first]) first = i
            var second = -1
            for (i in bins.indices) {
                if (bins[i] < bins[first] * 0.22f) continue
                val delta = min(abs(i - first), 24 - abs(i - first))
                if (delta < 3) continue
                if (second == -1 || bins[i] > bins[second]) second = i
            }
            val h1 = first * 15f + 7.5f
            val h2 = if (second >= 0) second * 15f + 7.5f else null
            Pair(h1, h2)
        }.getOrNull()
    }

    private fun angularDistance(a: Float, b: Float): Float {
        val d = abs(normalizeHue(a) - normalizeHue(b))
        return min(d, 360f - d)
    }

    private fun normalizeHue(h: Float): Float = ((h % 360f) + 360f) % 360f

    private fun colorFor(hue: Float, saturation: Float, value: Float): Int =
        Color.HSVToColor(floatArrayOf(normalizeHue(hue), saturation.coerceIn(0f, 1f), value.coerceIn(0f, 1f)))

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private fun floorMod(value: Long, size: Int): Int {
        val mod = (value % size).toInt()
        return if (mod < 0) mod + size else mod
    }

    private companion object {
        const val PANEL_W = 1920f
        const val PANEL_H = 720f
    }
}
