package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import com.shilapi.xcertplay.daynight.CarDataMonitor
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * 火焰双擎 · 熔核：背景炭纤维/六边形/裂纹 + 随车速升高的火墙，
 * 左侧 0-220 km/h 车速环，右侧 0-5500 r/min 转速环。
 *
 * 这里只画主题自己的背景、火焰和环。车速、转速、挡位、NORMAL 以及顶部状态灯
 * 全部由 MCU 层显示，Benched MCU（PC）或车机硬件 MCU 会压在它上面。
 */
internal class PortholeDualRingView(context: Context) : View(context) {

    private var metrics = PanelMetrics(1920, 720)
    private var running = false
    private var shownSpeed = 0f
    private var shownRpm = 0f
    private var lastFrameNanos = 0L
    private var timeSeconds = 0f
    private var backdrop: Bitmap? = null

    private val random = Random(73)
    private val tongues = ArrayList<Tongue>(40)
    private val embers = ArrayList<Ember>(140)

    private val platePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(210, 2, 5, 8)
    }
    private val plateEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 17f
        color = Color.argb(24, 255, 238, 214)
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val cometPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val flameBandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val flamePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val flameCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(32, 255, 235, 160)
    }
    private val bottomGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        shader = RadialGradient(
            960f, 740f, 980f,
            intArrayOf(Color.argb(66, 255, 66, 12), Color.argb(20, 255, 40, 8), Color.TRANSPARENT),
            floatArrayOf(0f, .5f, 1f),
            Shader.TileMode.CLAMP,
        )
    }
    private val emberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // Centres match the real MCU readouts, not the old generic side columns:
    // speed/P is at (255, 300), tach is at (1660, 300) on the 1920x720 panel.
    private val leftBounds = RectF(LEFT_CX - 185f, LEFT_CY - 185f, LEFT_CX + 185f, LEFT_CY + 185f)
    private val rightBounds = RectF(RIGHT_CX - 180f, RIGHT_CY - 180f, RIGHT_CX + 180f, RIGHT_CY + 180f)
    private val arcBounds = RectF()
    private val flamePath = Path()
    private val tickPath = Path()

    init {
        buildTongues()
        buildEmbers()
    }

    fun setMetrics(next: PanelMetrics) {
        if (next == metrics) return
        metrics = next
        invalidate()
    }

    fun applyTheme(theme: PortholeTheme) {
        if (theme.decor != PortholeTheme.Decor.DUAL_RING) return
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

    /** Drops the full-panel ARGB backdrop when the porthole view is removed. */
    fun release() {
        stop()
        backdrop?.recycle()
        backdrop = null
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
        val dt = if (lastFrameNanos == 0L) 0f
        else ((now - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, .08f)
        lastFrameNanos = now
        timeSeconds += dt

        ensureBackdrop()
        backdrop?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        canvas.drawRect(0f, 0f, PANEL_W, PANEL_H, bottomGlowPaint)

        val targetSpeed = CarDataMonitor.displayedSpeedKmh()
            .let { if (it.isFinite()) it.coerceIn(0f, 220f) else 0f }
        val targetRpm = CarDataMonitor.displayedRpm()
            .let { if (it.isFinite()) it.coerceIn(0f, 5500f) else 0f }
        shownSpeed = approach(shownSpeed, targetSpeed)
        shownRpm = approach(shownRpm, targetRpm)

        drawFlames(canvas, shownSpeed)
        drawEmbers(canvas, dt, shownSpeed)
        drawSpeedGauge(canvas, shownSpeed)
        drawRpmGauge(canvas, shownRpm)

        canvas.restoreToCount(save)
        // The cluster is capped at 30 fps and the main CarPlay picture must stay smooth. 20 fps
        // for the decorative rings removes a third of the full-panel UI draws on the same thread.
        if (running) postInvalidateDelayed(50L)
    }

    private fun approach(current: Float, target: Float): Float {
        val delta = target - current
        if (abs(delta) < .08f) return target
        return current + delta * .18f
    }

    /**
     * Fire height includes the bottom 110 px that is effectively hidden in the PC window.
     * A normal 150 km/h run must put the flame top above the speed digits, while 80 km/h
     * still shows a clear fire wall:
     *   80 km/h -> ~495 px, 150 km/h -> ~636 px, 220 km/h -> 700 px.
     */
    private fun flameHeight(speed: Float): Float {
        val ratio = (speed / 220f).coerceIn(0f, 1f)
        return (150f + 600f * ratio.toDouble().pow(.55).toFloat()).coerceAtMost(700f)
    }

    private fun buildTongues() {
        val layerCounts = intArrayOf(11, 14, 11)
        val layerWidths = floatArrayOf(165f, 112f, 62f)
        val layerScales = floatArrayOf(1f, .82f, .58f)
        for (layer in 0..2) {
            repeat(layerCounts[layer]) { i ->
                val baseX = (i + .5f) / layerCounts[layer] * 1920f
                tongues += Tongue(
                    x = baseX + (random.nextFloat() - .5f) * 110f,
                    width = layerWidths[layer] * (.52f + random.nextFloat() * .66f),
                    heightScale = layerScales[layer] * (.50f + random.nextFloat() * .65f),
                    phase = random.nextFloat() * (PI * 2).toFloat(),
                    lean = (random.nextFloat() - .5f) * layerWidths[layer] * 1.1f,
                    layer = layer,
                )
            }
        }
    }

    private fun buildEmbers() {
        repeat(140) {
            embers += Ember(
                x = random.nextFloat() * 1920f,
                y = 720f - random.nextFloat() * 260f,
                radius = .5f + random.nextFloat() * 2.2f,
                rise = 10f + random.nextFloat() * 34f,
                drift = (random.nextFloat() - .5f) * 9f,
                phase = random.nextFloat() * (PI * 2).toFloat(),
                hot = random.nextFloat() > .55f,
            )
        }
    }

    private fun drawFlames(canvas: Canvas, speed: Float) {
        val height = flameHeight(speed)
        val intensity = (speed / 220f).coerceIn(0f, 1f)
        val t = timeSeconds

        // Soft fire base.
        flamePath.reset()
        flamePath.moveTo(0f, 720f)
        var x = 0f
        while (x <= 1920f) {
            val top = 700f - sin((x * .012f + t * .7f).toDouble()).toFloat() * height * .05f
            if (x == 0f) flamePath.lineTo(0f, top)
            else flamePath.quadTo(x - 32f, top - height * .03f, x, top)
            x += 64f
        }
        flamePath.lineTo(1920f, 720f)
        flamePath.close()
        flameBandPaint.shader = LinearGradient(
            0f, 720f, 0f, 720f - height * .62f,
            intArrayOf(
                Color.argb(195, 255, 45, 10),
                Color.argb(110, 255, 112, 22),
                Color.argb(0, 255, 180, 60),
            ),
            floatArrayOf(0f, .56f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawPath(flamePath, flameBandPaint)

        for (tongue in tongues) {
            val h = height * tongue.heightScale
            val flick = sin((t * 1.35f + tongue.phase).toDouble()).toFloat()
            val tipX = tongue.x + tongue.lean + flick * tongue.width * .12f * (.45f + intensity)
            val tipY = 726f - h + flick * h * .025f
            val midY = tipY + h * (.34f + .06f * sin((t + tongue.phase).toDouble()).toFloat())
            val w = tongue.width
            flamePath.reset()
            flamePath.moveTo(tongue.x - w, 742f)
            flamePath.cubicTo(
                tongue.x - w * .92f, 700f - h * .08f,
                tongue.x - w * .64f, midY + h * .18f,
                tongue.x - w * .22f + tongue.lean * .18f, midY,
            )
            flamePath.cubicTo(
                tongue.x - w * .08f + tongue.lean * .30f, midY - h * .13f,
                tipX - w * .13f, tipY + h * .10f,
                tipX, tipY,
            )
            flamePath.cubicTo(
                tipX + w * .16f, tipY + h * .12f,
                tongue.x + w * .16f + tongue.lean * .16f, midY - h * .10f,
                tongue.x + w * .30f, midY,
            )
            flamePath.cubicTo(
                tongue.x + w * .68f, midY + h * .16f,
                tongue.x + w * .92f, 700f - h * .06f,
                tongue.x + w, 742f,
            )
            flamePath.close()
            flamePaint.color = when (tongue.layer) {
                0 -> Color.argb(48, 255, 44, 12)
                1 -> Color.argb(58, 255, 97, 22)
                else -> Color.argb(64, 255, 162, 44)
            }
            canvas.drawPath(flamePath, flamePaint)
            if (tongue.layer == 2 && intensity > .08f) {
                // A hot inner tongue, faint enough not to turn the fire into solid cones.
                flamePath.reset()
                flamePath.moveTo(tongue.x - w * .15f, 741f)
                flamePath.cubicTo(
                    tongue.x - w * .24f, midY + h * .18f,
                    tipX - w * .08f, tipY + h * .10f,
                    tipX, tipY + h * .02f,
                )
                flamePath.cubicTo(
                    tipX + w * .08f, tipY + h * .16f,
                    tongue.x + w * .10f, midY + h * .24f,
                    tongue.x + w * .13f, 741f,
                )
                flamePath.close()
                canvas.drawPath(flamePath, flameCorePaint)
            }
        }
    }

    private fun drawEmbers(canvas: Canvas, dt: Float, speed: Float) {
        val intensity = (speed / 220f).coerceIn(0f, 1f)
        val visible = (42f + intensity * 84f).toInt().coerceAtMost(embers.size)
        val maxRise = 720f - flameHeight(speed) - 70f
        val rise = 24f + speed * .22f
        for (i in 0 until visible) {
            val e = embers[i]
            e.y -= (e.rise + rise) * dt
            e.x += sin((timeSeconds * 1.1f + e.phase).toDouble()).toFloat() * e.drift * dt * 4f
            if (e.y < maxRise || e.y < -10f) {
                e.y = 730f + random.nextFloat() * 60f
                e.x = random.nextFloat() * 1920f
            }
            emberPaint.color = if (e.hot) {
                Color.argb((120 + intensity * 110).toInt(), 255, 214, 130)
            } else {
                Color.argb((80 + intensity * 90).toInt(), 255, 92 + (intensity * 70).toInt(), 22)
            }
            canvas.drawCircle(e.x, e.y, e.radius, emberPaint)
        }
    }

    private fun drawSpeedGauge(canvas: Canvas, speed: Float) {
        val ratio = (speed / 220f).coerceIn(0f, 1f)
        val cx = LEFT_CX
        val cy = LEFT_CY
        drawPlate(canvas, cx, cy, AMBER, 151f)

        canvas.drawArc(leftBounds, LEFT_START, LEFT_SWEEP, false, trackPaint)
        drawOuterSegments(
            canvas, cx, cy, 188f, LEFT_START, LEFT_SWEEP, ratio,
            AMBER, AMBER_HOT, redlineStart = 1f,
        )
        drawTicks(canvas, cx, cy, 181f, LEFT_START, LEFT_SWEEP, ratio, AMBER_HOT, 1f)

        val fillSweep = LEFT_SWEEP * ratio
        if (fillSweep > .2f) {
            arcBounds.set(cx - 164f, cy - 164f, cx + 164f, cy + 164f)
            arcPaint.color = AMBER
            arcPaint.strokeWidth = 15f
            arcPaint.setShadowLayer(12f, 0f, 0f, Color.argb(190, 255, 145, 35))
            canvas.drawArc(arcBounds, LEFT_START, fillSweep, false, arcPaint)
            arcPaint.clearShadowLayer()
            arcPaint.color = AMBER_HOT
            arcPaint.strokeWidth = 3.5f
            canvas.drawArc(arcBounds, LEFT_START, fillSweep, false, arcPaint)
            drawComet(canvas, cx, cy, 164f, LEFT_START + fillSweep, AMBER_HOT, AMBER)
        }
    }

    private fun drawRpmGauge(canvas: Canvas, rpm: Float) {
        val ratio = (rpm / 5500f).coerceIn(0f, 1f)
        val redline = 4500f / 5500f
        val cx = RIGHT_CX
        val cy = RIGHT_CY
        drawPlate(canvas, cx, cy, RED, 149f)

        canvas.drawArc(rightBounds, RIGHT_START, RIGHT_SWEEP, false, trackPaint)
        drawOuterSegments(
            canvas, cx, cy, 183f, RIGHT_START, RIGHT_SWEEP, ratio,
            AMBER, RED, redlineStart = redline,
        )
        drawTicks(canvas, cx, cy, 176f, RIGHT_START, RIGHT_SWEEP, ratio, RED, redline)

        // Redline is always visible.
        arcBounds.set(cx - 176f, cy - 176f, cx + 176f, cy + 176f)
        arcPaint.color = RED
        arcPaint.strokeWidth = 4f
        arcPaint.setShadowLayer(8f, 0f, 0f, Color.argb(185, 255, 28, 40))
        canvas.drawArc(arcBounds, RIGHT_START + RIGHT_SWEEP * redline, RIGHT_SWEEP * (1f - redline), false, arcPaint)
        arcPaint.clearShadowLayer()

        arcBounds.set(cx - 159f, cy - 159f, cx + 159f, cy + 159f)
        val safeRatio = ratio.coerceAtMost(redline)
        if (safeRatio > .005f) {
            arcPaint.color = AMBER
            arcPaint.strokeWidth = 15f
            arcPaint.setShadowLayer(12f, 0f, 0f, Color.argb(180, 255, 150, 35))
            canvas.drawArc(arcBounds, RIGHT_START, RIGHT_SWEEP * safeRatio, false, arcPaint)
            arcPaint.clearShadowLayer()
            arcPaint.color = AMBER_HOT
            arcPaint.strokeWidth = 3.5f
            canvas.drawArc(arcBounds, RIGHT_START, RIGHT_SWEEP * safeRatio, false, arcPaint)
        }
        if (ratio > redline) {
            arcPaint.color = RED
            arcPaint.strokeWidth = 15f
            arcPaint.setShadowLayer(12f, 0f, 0f, Color.argb(200, 255, 30, 38))
            canvas.drawArc(
                arcBounds,
                RIGHT_START + RIGHT_SWEEP * redline,
                RIGHT_SWEEP * (ratio - redline),
                false,
                arcPaint,
            )
            arcPaint.clearShadowLayer()
            arcPaint.color = Color.rgb(255, 210, 150)
            arcPaint.strokeWidth = 3.5f
            canvas.drawArc(
                arcBounds,
                RIGHT_START + RIGHT_SWEEP * redline,
                RIGHT_SWEEP * (ratio - redline),
                false,
                arcPaint,
            )
        }
        if (ratio > .005f) {
            drawComet(
                canvas, cx, cy, 159f, RIGHT_START + RIGHT_SWEEP * ratio,
                if (ratio > redline) Color.rgb(255, 215, 155) else AMBER_HOT,
                if (ratio > redline) RED else AMBER,
            )
        }
    }

    private fun drawPlate(canvas: Canvas, cx: Float, cy: Float, color: Int, radius: Float) {
        canvas.drawCircle(cx, cy, radius, platePaint)
        plateEdgePaint.color = Color.argb(35, Color.red(color), Color.green(color), Color.blue(color))
        canvas.drawCircle(cx, cy, radius, plateEdgePaint)
    }

    private fun drawOuterSegments(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        outerRadius: Float,
        start: Float,
        sweep: Float,
        ratio: Float,
        baseColor: Int,
        hotColor: Int,
        redlineStart: Float,
    ) {
        val segments = 32
        for (i in 0 until segments) {
            val f0 = i / segments.toFloat() + .004f
            val f1 = (i + 1) / segments.toFloat() - .004f
            val active = (i + 1) / segments.toFloat() <= ratio + .002f
            val hot = f1 > redlineStart
            val r = outerRadius + if (i % 3 == 0) 4f else 0f
            arcBounds.set(cx - r, cy - r, cx + r, cy + r)
            arcPaint.color = when {
                active && hot -> hotColor
                active -> baseColor
                hot -> Color.argb(35, 255, 55, 55)
                else -> Color.argb(16, 255, 238, 214)
            }
            arcPaint.strokeWidth = if (active && i % 3 == 0) 11f else 8f
            canvas.drawArc(arcBounds, start + sweep * f0, sweep * (f1 - f0), false, arcPaint)
        }
    }

    private fun drawTicks(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        start: Float,
        sweep: Float,
        ratio: Float,
        hotColor: Int,
        redlineStart: Float,
    ) {
        val count = 16
        for (i in 0..count) {
            val f = i / count.toFloat()
            val major = i % 2 == 0
            val active = f <= ratio + .002f
            val hot = f > redlineStart
            val angle = Math.toRadians((start + sweep * f).toDouble())
            val inner = radius - if (major) 20f else 12f
            val outer = radius
            tickPaint.color = when {
                hot -> Color.argb(210, 255, 52, 58)
                active -> Color.argb(205, 255, 222, 170)
                else -> Color.argb(48, 255, 226, 190)
            }
            tickPaint.strokeWidth = if (major) 2.6f else 1.3f
            val cosA = cos(angle).toFloat()
            val sinA = sin(angle).toFloat()
            canvas.drawLine(
                cx + cosA * inner, cy + sinA * inner,
                cx + cosA * outer, cy + sinA * outer,
                tickPaint,
            )
            if (major && active) {
                val spread = .018f
                tickPath.reset()
                val sideA = Math.toRadians((start + sweep * f - spread).toDouble())
                val sideB = Math.toRadians((start + sweep * f + spread).toDouble())
                tickPath.moveTo(cx + cos(sideA).toFloat() * (radius - 28f), cy + sin(sideA).toFloat() * (radius - 28f))
                tickPath.lineTo(cx + cosA * outer, cy + sinA * outer)
                tickPath.lineTo(cx + cos(sideB).toFloat() * (radius - 28f), cy + sin(sideB).toFloat() * (radius - 28f))
                tickPaint.color = if (hot) RED else hotColor
                tickPaint.strokeWidth = 1.5f
                canvas.drawPath(tickPath, tickPaint)
            }
        }
    }

    private fun drawComet(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        angle: Float,
        coreColor: Int,
        edgeColor: Int,
    ) {
        val rad = Math.toRadians(angle.toDouble())
        val px = cx + cos(rad).toFloat() * radius
        val py = cy + sin(rad).toFloat() * radius
        val nx = cos(rad + PI / 2).toFloat()
        val ny = sin(rad + PI / 2).toFloat()
        val tx = cos(rad).toFloat()
        val ty = sin(rad).toFloat()
        flamePath.reset()
        flamePath.moveTo(px + tx * 10f, py + ty * 10f)
        flamePath.lineTo(px + nx * 11f, py + ny * 11f)
        flamePath.lineTo(px - tx * 7f, py - ty * 7f)
        flamePath.lineTo(px - nx * 11f, py - ny * 11f)
        flamePath.close()
        cometPaint.color = coreColor
        cometPaint.setShadowLayer(10f, 0f, 0f, edgeColor)
        canvas.drawPath(flamePath, cometPaint)
        cometPaint.clearShadowLayer()
    }

    private fun ensureBackdrop() {
        if (backdrop != null) return
        val bitmap = Bitmap.createBitmap(PANEL_W.toInt(), PANEL_H.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        p.shader = LinearGradient(
            0f, 0f, PANEL_W, PANEL_H,
            intArrayOf(Color.rgb(13, 6, 3), Color.rgb(5, 2, 1), Color.rgb(2, 1, 1)),
            floatArrayOf(0f, .56f, 1f),
            Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, PANEL_W, PANEL_H, p)
        p.shader = null

        // Carbon fibre diagonals.
        p.color = Color.argb(20, 255, 176, 96)
        p.strokeWidth = 1f
        var i = -40
        while (i < 100) {
            val x = i * 22f
            c.drawLine(x, 0f, x + 720f, 720f, p)
            i++
        }
        p.color = Color.argb(10, 255, 230, 180)
        i = -40
        while (i < 100) {
            val x = i * 22f
            c.drawLine(x, 720f, x + 720f, 0f, p)
            i++
        }

        // Hex armour grid.
        val hex = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.argb(25, 255, 157, 74)
            strokeWidth = .7f
        }
        val hexPath = Path()
        var row = 0
        var y = -20f
        while (y < 760f) {
            var col = 0
            var x = -20f
            while (x < 1960f) {
                hexPath.reset()
                for (k in 0..5) {
                    val a = Math.toRadians(60.0 * k - 30.0)
                    val hx = x + cos(a).toFloat() * 27f
                    val hy = y + sin(a).toFloat() * 27f
                    if (k == 0) hexPath.moveTo(hx, hy) else hexPath.lineTo(hx, hy)
                }
                hexPath.close()
                c.drawPath(hexPath, hex)
                x += 58f
                col++
            }
            y += 50f
            row++
        }

        // Warm halos.
        val halo = Paint(Paint.ANTI_ALIAS_FLAG)
        halo.shader = RadialGradient(
            LEFT_CX, LEFT_CY, 315f,
            intArrayOf(Color.argb(46, 255, 150, 34), Color.TRANSPARENT),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawCircle(LEFT_CX, LEFT_CY, 315f, halo)
        halo.shader = RadialGradient(
            RIGHT_CX, RIGHT_CY, 315f,
            intArrayOf(Color.argb(54, 255, 54, 26), Color.TRANSPARENT),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawCircle(RIGHT_CX, RIGHT_CY, 315f, halo)

        // Fissures in the armour.
        val crack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        crack.setShadowLayer(5f, 0f, 0f, Color.argb(160, 255, 80, 10))
        drawCrack(c, crack, Color.argb(150, 255, 105, 20), 2.4f,
            0f, 155f, 58f, 190f, 96f, 172f, 145f, 211f, 194f, 190f, 238f, 216f)
        drawCrack(c, crack, Color.argb(100, 255, 205, 120), 1f,
            96f, 172f, 118f, 138f, 155f, 128f)
        drawCrack(c, crack, Color.argb(150, 255, 70, 20), 2.4f,
            0f, 612f, 74f, 560f, 128f, 585f, 194f, 522f, 248f, 548f, 312f, 506f)
        drawCrack(c, crack, Color.argb(150, 255, 105, 20), 2.4f,
            1920f, 142f, 1848f, 190f, 1806f, 166f, 1742f, 218f, 1688f, 198f, 1638f, 226f)
        drawCrack(c, crack, Color.argb(155, 255, 42, 24), 2.6f,
            1920f, 626f, 1838f, 569f, 1772f, 594f, 1702f, 532f, 1644f, 558f, 1588f, 520f)
        drawCrack(c, crack, Color.argb(90, 255, 170, 70), 1f,
            560f, 720f, 604f, 668f, 650f, 690f, 704f, 644f, 760f, 666f)
        crack.clearShadowLayer()

        backdrop = bitmap
    }

    private fun drawCrack(
        c: Canvas,
        paint: Paint,
        color: Int,
        width: Float,
        vararg points: Float,
    ) {
        val path = Path()
        var index = 0
        while (index + 1 < points.size) {
            val x = points[index]
            val y = points[index + 1]
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            index += 2
        }
        paint.color = color
        paint.strokeWidth = width
        c.drawPath(path, paint)
    }

    private data class Tongue(
        val x: Float,
        val width: Float,
        val heightScale: Float,
        val phase: Float,
        val lean: Float,
        val layer: Int,
    )

    private class Ember(
        var x: Float,
        var y: Float,
        val radius: Float,
        val rise: Float,
        val drift: Float,
        val phase: Float,
        val hot: Boolean,
    )

    private companion object {
        const val PANEL_W = 1920f
        const val PANEL_H = 720f
        val AMBER: Int = Color.rgb(255, 177, 46)
        val AMBER_HOT: Int = Color.rgb(255, 238, 180)
        val RED: Int = Color.rgb(255, 42, 46)
        const val LEFT_CX = 255f
        const val LEFT_CY = 300f
        const val RIGHT_CX = 1660f
        const val RIGHT_CY = 300f
        const val LEFT_START = 140f
        const val LEFT_SWEEP = 260f
        const val RIGHT_START = 40f
        const val RIGHT_SWEEP = -260f
    }
}
