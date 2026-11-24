package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Movie
import android.os.SystemClock
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * One custom theme layer. The host keeps one full-screen transparent View per layer so the
 * layer can be ordered above/below the live navigation, 112 card, ETA and song views.
 */
internal class CustomThemeLayerView(
    context: Context,
    private val layer: CustomLayerSpec,
    private val animations: List<CustomAnimationSpec>,
) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val bitmap: Bitmap? by lazy {
        layer.src?.let { file ->
            runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
        }
    }
    /** Animated GIF source; movie is null for PNG/JPEG/BMP. */
    private val movie: Movie? by lazy {
        layer.src?.takeIf { it.name.endsWith(".gif", ignoreCase = true) }?.let { file ->
            runCatching { Movie.decodeFile(file.absolutePath) }.getOrNull()
        }
    }
    private val movieStartedAt = SystemClock.elapsedRealtime()
    private val swapBitmap: Bitmap? by lazy {
        layer.swapSrc?.let { file ->
            runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
        }
    }
    private var clockMs = 0L
    /** Latest frame handed down by [CustomThemeHost]; the same instance every layer sees. */
    private var frame = ThemeData()
    private var boundText: String? = null
    private var boundTextAt = Long.MIN_VALUE
    private var bindLastAtMs = Long.MIN_VALUE
    private var bindLevel = Float.NaN
    private var bindColor: Int? = null
    private var ruleAlpha = 1f
    private var ruleScale = 1f
    private var ruleText: String? = null
    private var ruleImageSrc: String? = null
    private var ruleImage: Bitmap? = null
    private var stateOffsetX = 0f
    private var stateOffsetY = 0f
    private var stateColor: Int? = null
    private var stateDst: RectF? = null
    private var sequenceOffsetX = 0f
    private var sequenceOffsetY = 0f
    private var fillLastAtMs = Long.MIN_VALUE
    private var fillLevel = Float.NaN
    private val dst = RectF()

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = false
        isFocusable = false
    }

    fun setData(data: ThemeData) {
        frame = data
    }

    fun setClock(ms: Long) {
        clockMs = ms
        invalidate()
    }

    /** Phase-4 rule flash multiplier; 1 = normal. */
    fun setRuleAlpha(value: Float) {
        ruleAlpha = value.coerceIn(0f, 1f)
    }

    /** Phase-4 pulse multiplier (setText/pulse actions). */
    fun setRuleScale(value: Float) {
        ruleScale = value.coerceIn(0.5f, 1.5f)
    }

    /** Phase-4 setText override; null restores the layer's own text/template. */
    fun setRuleText(value: String?) {
        ruleText = value
    }

    /** Phase-5 state offsets, added on top of keyframe x/y. */
    fun setStateOffset(x: Float, y: Float) {
        stateOffsetX = x
        stateOffsetY = y
    }

    /** Phase-5 state color override for text/shape/vector; null restores the layer color. */
    fun setStateColor(color: Int?) {
        stateColor = color
    }

    /** Phase-5 absolute dst override; null restores the layer dst. */
    fun setStateDst(rect: StateRect?) {
        stateDst = rect?.let { RectF(it.x, it.y, it.x + it.w, it.y + it.h) }
    }

    /** Phase-6 slide sequence offset, added on top of state/keyframe offsets. */
    fun setSequenceOffset(x: Float, y: Float) {
        sequenceOffsetX = x
        sequenceOffsetY = y
    }

    /** Phase-4 setImage override; relative image name inside the theme directory. */
    fun setRuleImage(src: String?) {        if (src == ruleImageSrc) return
        ruleImageSrc = src
        ruleImage = if (src == null) {
            null
        } else {
            val dir = layer.src?.parentFile ?: return
            runCatching { BitmapFactory.decodeFile(java.io.File(dir, src).absolutePath) }.getOrNull()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bind = layer.bindSpec
        val fraction = if (bind != null) bindFraction(bind) else null
        var baseAlpha = layer.alpha
        var baseScale = 1f
        var baseRotation = 0f
        var baseOffsetX = 0f
        var baseOffsetY = 0f
        bindColor = null
        if (bind != null && fraction != null) {
            when (bind.property) {
                "alpha" -> baseAlpha = ThemeBind.output(bind, fraction)
                "scale" -> baseScale = ThemeBind.output(bind, fraction)
                "rotation" -> baseRotation = ThemeBind.output(bind, fraction)
                "x" -> baseOffsetX = ThemeBind.output(bind, fraction)
                "y" -> baseOffsetY = ThemeBind.output(bind, fraction)
                "color" -> bindColor = ThemeBind.color(bind, fraction)
            }
        }
        // D2 precedence: bind replaces the base value, keyframes still multiply/add on top.
        val alpha = (baseAlpha * animated("alpha", 1f) * ruleAlpha).coerceIn(0f, 1f)
        if (alpha <= 0.001f) return

        val save = canvas.save()
        val rect = stateDst ?: layer.rect
        val x = rect.left + baseOffsetX + animated("x", 0f) + stateOffsetX + sequenceOffsetX
        val y = rect.top + baseOffsetY + animated("y", 0f) + stateOffsetY + sequenceOffsetY
        val scale = (baseScale * animated("scale", 1f) * ruleScale).coerceIn(0.01f, 20f)
        val rotation = baseRotation + animated("rotation", 0f)
        dst.set(x, y, x + rect.width(), y + rect.height())
        canvas.translate(dst.centerX(), dst.centerY())
        canvas.rotate(rotation)
        canvas.scale(scale, scale)
        canvas.translate(-dst.centerX(), -dst.centerY())
        paint.alpha = (alpha * 255f).toInt()

        when (layer.type) {
            "image" -> drawImage(canvas, dst, alpha)
            "spectrum" -> drawSpectrum(canvas, dst, alpha)
            "brush" -> drawBrush(canvas, dst, alpha)
            "shape" -> drawShape(canvas, dst, alpha)
            "text" -> drawTextLayer(canvas, alpha)
            "gauge" -> drawGauge(canvas, alpha)
            "vector" -> drawVector(canvas, alpha)
        }
        canvas.restoreToCount(save)
    }

    private fun drawImage(canvas: Canvas, dst: RectF, alpha: Float) {
        val animated = movie
        if (animated != null && !layer.coverArtwork) {
            drawMovie(canvas, dst, alpha, animated)
            return
        }
        val bmp = if (layer.coverArtwork) {
            CarPlayMediaKeys.currentArtwork()
        } else {
            ruleImage ?: if (swapMatches()) (swapBitmap ?: bitmap) else bitmap
        }
        if (bmp == null) return
        val src = RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        val target = RectF(dst)
        when (layer.fit) {
            "cover" -> {
                val scale = maxOf(dst.width() / src.width(), dst.height() / src.height())
                val w = src.width() * scale
                val h = src.height() * scale
                target.set(
                    dst.centerX() - w / 2f, dst.centerY() - h / 2f,
                    dst.centerX() + w / 2f, dst.centerY() + h / 2f,
                )
            }
            "contain" -> {
                val scale = minOf(dst.width() / src.width(), dst.height() / src.height())
                val w = src.width() * scale
                val h = src.height() * scale
                target.set(
                    dst.centerX() - w / 2f, dst.centerY() - h / 2f,
                    dst.centerX() + w / 2f, dst.centerY() + h / 2f,
                )
            }
        }
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.alpha = (alpha * 255f).toInt()
        canvas.drawBitmap(bmp, null, target, paint)
    }

    private fun drawMovie(canvas: Canvas, dst: RectF, alpha: Float, movie: Movie) {
        val mw = movie.width()
        val mh = movie.height()
        if (mw <= 0 || mh <= 0) return
        val duration = movie.duration().coerceAtLeast(1)
        movie.setTime(((SystemClock.elapsedRealtime() - movieStartedAt) % duration).toInt())
        val target = RectF(dst)
        when (layer.fit) {
            "cover" -> {
                val scale = maxOf(dst.width() / mw, dst.height() / mh)
                val w = mw * scale
                val h = mh * scale
                target.set(dst.centerX() - w / 2f, dst.centerY() - h / 2f,
                    dst.centerX() + w / 2f, dst.centerY() + h / 2f)
            }
            "contain" -> {
                val scale = minOf(dst.width() / mw, dst.height() / mh)
                val w = mw * scale
                val h = mh * scale
                target.set(dst.centerX() - w / 2f, dst.centerY() - h / 2f,
                    dst.centerX() + w / 2f, dst.centerY() + h / 2f)
            }
        }
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.alpha = (alpha * 255f).toInt()
        val save = canvas.save()
        canvas.translate(target.left, target.top)
        canvas.scale(target.width() / mw, target.height() / mh)
        movie.draw(canvas, 0f, 0f, paint)
        canvas.restoreToCount(save)
        postInvalidateOnAnimation()
    }

    private fun drawSpectrum(canvas: Canvas, dst: RectF, alpha: Float) {
        val n = layer.spectrumBands.coerceIn(4, 32)
        val gap = layer.spectrumGap.coerceIn(0f, 40f)
        val barW = ((dst.width() - gap * (n - 1)) / n).coerceAtLeast(1f)
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = layer.textColor
        paint.alpha = (alpha * 255f).toInt()
        val down = layer.spectrumDirection == "down"
        for (i in 0 until n) {
            val value = com.shilapi.xcertplay.media.MusicReactiveBus.bandAt(i)
            val h = (dst.height() * value).coerceAtLeast(1f)
            val left = dst.left + i * (barW + gap)
            val top = if (down) dst.bottom - h else dst.top
            canvas.drawRect(left, top, left + barW, top + h, paint)
        }
    }

    private fun drawBrush(canvas: Canvas, dst: RectF, alpha: Float) {
        val spec = layer.paint ?: return
        val colors = spec.colors.toIntArray()
        paint.style = Paint.Style.FILL
        paint.alpha = (alpha * 255f).toInt()
        paint.shader = when (spec.kind) {
            "radial" -> RadialGradient(
                spec.center[0], spec.center[1], spec.radius.coerceAtLeast(1f),
                colors, evenPositions(colors.size), Shader.TileMode.CLAMP,
            )
            "sweep" -> SweepGradient(
                spec.center[0], spec.center[1], colors, evenPositions(colors.size),
            )
            else -> LinearGradient(
                spec.from[0], spec.from[1], spec.to[0], spec.to[1],
                colors, evenPositions(colors.size), Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(dst, paint)
        paint.shader = null
    }

    private fun drawShape(canvas: Canvas, dst: RectF, alpha: Float) {
        paint.style = Paint.Style.FILL
        paint.color = withAlpha(stateColor ?: bindColor ?: layer.textColor, alpha)
        when (layer.shape) {
            "ellipse", "circle" -> canvas.drawOval(dst, paint)
            else -> canvas.drawRect(dst, paint)
        }
    }

    private fun drawTextLayer(canvas: Canvas, alpha: Float) {
        val text = textToDraw()
        if (text.isEmpty()) return
        textPaint.textSize = layer.textSize
        textPaint.color = withAlpha(stateColor ?: bindColor ?: layer.textColor, alpha)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.setShadowLayer(3f, 0f, 0f, Color.argb(180, 0, 0, 0))
        canvas.drawText(text, (stateDst?.left ?: layer.rect.left),
            (stateDst?.top ?: layer.rect.top) + layer.textSize, textPaint)
        textPaint.clearShadowLayer()
    }

    /**
     * Static text stays exactly as before. With a bind template the expansion is throttled so a
     * 150 ms data poll cannot make digits strobe at 30 fps.
     */
    private fun textToDraw(): String {
        ruleText?.let { return it }
        val template = layer.textTemplate
        if (template.isEmpty()) return layer.text
        val throttle = layer.textThrottleMs
        val cached = boundText
        if (cached != null && (throttle <= 0L || clockMs - boundTextAt < throttle)) return cached
        val value = ThemeTextFormat.format(template, frame, layer.textFallback)
        boundText = value
        boundTextAt = clockMs
        return value
    }

    /**
     * Smoothed 0..1 (or extrapolated) fraction for the layer's bind. `easeMs` turns the 150 ms
     * data poll into a continuous ramp; the first frame snaps so a theme never fades in from 0.
     */
    private fun bindFraction(bind: CustomBindSpec): Float? {
        val raw = when (bind.source) {
            "speed" -> frame.speedKmh
            "rpm" -> frame.rpm
            "odo" -> frame.odometerKm
            "range" -> frame.rangeKm
            "gear" -> frame.gear.toFloat()
            "gearRaw" -> frame.gearRaw.toFloat()
            "turnLeft" -> if (frame.turnLeft) 1f else 0f
            "turnRight" -> if (frame.turnRight) 1f else 0f
            "hazard" -> if (frame.hazard) 1f else 0f
            "turnLeftBlink" -> if (frame.turnLeftBlink) 1f else 0f
            "turnRightBlink" -> if (frame.turnRightBlink) 1f else 0f
            "audioLevel" -> frame.audioLevel
            "audioBass" -> frame.audioBass
            "beatPulse" -> frame.beatPulse
            else -> return null
        }
        val target = ThemeBind.fraction(bind, raw) ?: return null
        val now = clockMs
        val level = if (bindLastAtMs == Long.MIN_VALUE || bind.easeMs <= 0L) {
            target
        } else {
            val dt = ((now - bindLastAtMs).coerceAtLeast(0L)) / 1000f
            val k = (1f - exp(-dt * 1000f / bind.easeMs)).coerceIn(0f, 1f)
            val previous = if (bindLevel.isFinite()) bindLevel else target
            previous + (target - previous) * k
        }
        bindLevel = level
        bindLastAtMs = now
        return level
    }

    /** vector polygon: solid/gradient fill (optionally data-clipped), then the outline on top. */
    private fun drawVector(canvas: Canvas, alpha: Float) {
        val path = buildVectorPath() ?: return
        path.fillType = if (layer.fillRule == "evenodd") Path.FillType.EVEN_ODD else Path.FillType.WINDING
        paint.style = Paint.Style.FILL
        paint.color = stateColor ?: layer.textColor
        paint.alpha = (alpha * 255f).toInt()
        paint.shader = vectorShader()
        val bind = layer.fillBind
        if (bind == null) {
            canvas.drawPath(path, paint)
        } else {
            val f = vectorFillLevel(bind)
            if (f > 0.001f) {
                val save = canvas.save()
                canvas.clipPath(path)
                drawVectorFill(canvas, bind, f, alpha)
                canvas.restoreToCount(save)
            }
        }
        paint.shader = null
        val outline = layer.stroke
        if (outline != null && outline.width > 0f) {
            stroke.style = Paint.Style.STROKE
            stroke.strokeWidth = outline.width
            stroke.color = outline.color
            stroke.alpha = (alpha * 255f).toInt()
            stroke.strokeCap = when (outline.cap) {
                "butt" -> Paint.Cap.BUTT
                "square" -> Paint.Cap.SQUARE
                else -> Paint.Cap.ROUND
            }
            stroke.strokeJoin = when (outline.join) {
                "miter" -> Paint.Join.MITER
                "bevel" -> Paint.Join.BEVEL
                else -> Paint.Join.ROUND
            }
            stroke.shader = null
            canvas.drawPath(path, stroke)
        }
    }

    private fun buildVectorPath(): Path? {
        if (layer.points.size < 3) return null
        val rect = stateDst ?: layer.rect
        val width = rect.width()
        val height = rect.height()
        val path = Path()
        layer.points.forEachIndexed { index, point ->
            val x = rect.left + if (layer.pointsNormalized) point[0] * width else point[0]
            val y = rect.top + if (layer.pointsNormalized) point[1] * height else point[1]
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return path
    }

    /** Vector paint coordinates are dst-local, unlike brush layers which are panel-absolute. */
    private fun vectorShader(): Shader? {
        val spec = layer.paint ?: return null
        val colors = spec.colors.toIntArray()
        if (colors.isEmpty()) return null
        val rect = stateDst ?: layer.rect
        val dx = rect.left
        val dy = rect.top
        return when (spec.kind) {
            "radial" -> RadialGradient(
                spec.center[0] + dx, spec.center[1] + dy, spec.radius.coerceAtLeast(1f),
                colors, evenPositions(colors.size), Shader.TileMode.CLAMP,
            )
            "sweep" -> SweepGradient(
                spec.center[0] + dx, spec.center[1] + dy, colors, evenPositions(colors.size),
            )
            else -> LinearGradient(
                spec.from[0] + dx, spec.from[1] + dy, spec.to[0] + dx, spec.to[1] + dy,
                colors, evenPositions(colors.size), Shader.TileMode.CLAMP,
            )
        }
    }

    private fun drawVectorFill(canvas: Canvas, bind: CustomFillBindSpec, f: Float, alpha: Float) {
        val rect = stateDst ?: layer.rect
        val left = rect.left
        val top = rect.top
        val right = rect.right
        val bottom = rect.bottom
        val width = rect.width()
        val height = rect.height()
        paint.style = Paint.Style.FILL
        paint.color = stateColor ?: layer.textColor
        paint.alpha = (alpha * 255f).toInt()
        paint.shader = vectorShader()
        when (bind.direction) {
            "down" -> {
                val band = VectorFill.band(height, f, false)
                canvas.drawRect(left, top + band[0], right, top + band[1], paint)
            }
            "left" -> {
                val band = VectorFill.band(width, f, false)
                canvas.drawRect(left + band[0], top, left + band[1], bottom, paint)
            }
            "right" -> {
                val band = VectorFill.band(width, f, true)
                canvas.drawRect(left + band[0], top, left + band[1], bottom, paint)
            }
            "radial" -> {
                val cx = left + bind.center[0].coerceIn(0f, 1f) * width
                val cy = top + bind.center[1].coerceIn(0f, 1f) * height
                val radius = VectorFill.radius(maxOf(cx - left, right - cx), maxOf(cy - top, bottom - cy), f)
                canvas.drawCircle(cx, cy, radius, paint)
            }
            "sweep" -> {
                if (f >= 0.999f) {
                    canvas.drawRect(left, top, right, bottom, paint)
                } else {
                    val cx = left + bind.center[0].coerceIn(0f, 1f) * width
                    val cy = top + bind.center[1].coerceIn(0f, 1f) * height
                    val radius = VectorFill.radius(maxOf(cx - left, right - cx), maxOf(cy - top, bottom - cy), 1f)
                    val sector = Path()
                    sector.moveTo(cx, cy)
                    sector.arcTo(
                        RectF(cx - radius, cy - radius, cx + radius, cy + radius),
                        bind.start, VectorFill.sweepDegrees(f), false,
                    )
                    sector.close()
                    canvas.drawPath(sector, paint)
                }
            }
            else -> {
                val band = VectorFill.band(height, f, true)
                canvas.drawRect(left, top + band[0], right, top + band[1], paint)
            }
        }
        paint.shader = null
    }

    private fun vectorFillLevel(bind: CustomFillBindSpec): Float {
        val raw = if (bind.source == "rpm") frame.rpm else frame.speedKmh
        val target = VectorFill.fraction(raw, bind.min, bind.max)
        val now = clockMs
        val level = if (fillLastAtMs == Long.MIN_VALUE || bind.easeMs <= 0L) {
            target
        } else {
            val dt = ((now - fillLastAtMs).coerceAtLeast(0L)) / 1000f
            val k = (1f - exp(-dt * 1000f / bind.easeMs)).coerceIn(0f, 1f)
            val previous = if (fillLevel.isFinite()) fillLevel else target
            previous + (target - previous) * k
        }
        fillLevel = level
        fillLastAtMs = now
        return level
    }

    private fun drawGauge(canvas: Canvas, alpha: Float) {        val g = layer.gauge ?: return
        val raw = if (g.data == "rpm") frame.rpm else frame.speedKmh
        val value = if (raw.isFinite()) raw else 0f
        val ratio = ((value - g.min) / (g.max - g.min).coerceAtLeast(0.0001f)).coerceIn(0f, 1f)
        val cx = g.center[0]
        val cy = g.center[1]
        val r = g.radius.coerceAtLeast(20f)
        val bounds = RectF(cx - r, cy - r, cx + r, cy + r)
        val colors = g.colors.toIntArray()
        val shader = SweepGradient(cx, cy, colors, evenPositions(colors.size))

        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = g.thickness
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.color = withAlpha(g.trackColor, alpha)
        stroke.shader = null
        canvas.drawArc(bounds, g.start, g.sweep, false, stroke)

        if (abs(g.sweep * ratio) > .05f) {
            stroke.color = Color.WHITE
            stroke.alpha = (alpha * 255f).toInt()
            stroke.shader = shader
            canvas.drawArc(bounds, g.start, g.sweep * ratio, false, stroke)
            stroke.shader = null
        }

        if (g.ticks > 0) {
            stroke.strokeWidth = 2f
            stroke.color = withAlpha(Color.rgb(235, 246, 255), alpha)
            for (i in 0..g.ticks) {
                val f = i / g.ticks.toFloat()
                val a = Math.toRadians((g.start + g.sweep * f).toDouble())
                val c = cos(a).toFloat()
                val s = sin(a).toFloat()
                val outer = r + g.thickness / 2f + 7f
                val inner = outer - if (i % 4 == 0) 12f else 6f
                canvas.drawLine(cx + c * inner, cy + s * inner, cx + c * outer, cy + s * outer, stroke)
            }
        }

        if (g.labels.isNotEmpty()) {
            textPaint.textSize = g.labelSize
            textPaint.color = withAlpha(g.labelColor, alpha)
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.setShadowLayer(3f, 0f, 0f, Color.argb(210, 0, 0, 0))
            for (v in g.labels) {
                val f = ((v - g.min) / (g.max - g.min).coerceAtLeast(0.0001f)).coerceIn(0f, 1f)
                val a = Math.toRadians((g.start + g.sweep * f).toDouble())
                val px = cx + cos(a).toFloat() * (r - g.thickness / 2f - 22f)
                val py = cy + sin(a).toFloat() * (r - g.thickness / 2f - 22f) + g.labelSize / 3f
                canvas.drawText(if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString(), px, py, textPaint)
            }
            textPaint.clearShadowLayer()
        }
    }

    private fun withAlpha(color: Int, multiplier: Float): Int {
        val a = (Color.alpha(color) * multiplier).toInt().coerceIn(0, 255)
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun swapMatches(): Boolean {
        if (layer.swapImageId.isBlank() || layer.swapSrc == null) return false
        if (layer.swapMinSpeed == null && layer.swapMaxSpeed == null &&
            layer.swapMinRpm == null && layer.swapMaxRpm == null
        ) return false
        val speed = frame.speedKmh.finiteOrZero()
        val rpm = frame.rpm.finiteOrZero()
        if (layer.swapMinSpeed != null && speed < layer.swapMinSpeed) return false
        if (layer.swapMaxSpeed != null && speed > layer.swapMaxSpeed) return false
        if (layer.swapMinRpm != null && rpm < layer.swapMinRpm) return false
        if (layer.swapMaxRpm != null && rpm > layer.swapMaxRpm) return false
        return true
    }

    private fun animated(property: String, fallback: Float): Float {        val specs = animations.filter { it.target == layer.id && it.property == property }
        if (specs.isEmpty()) return fallback
        var result = fallback
        for (spec in specs) {
            val phase = if (spec.loop) {
                (clockMs % spec.durationMs) / spec.durationMs.toFloat()
            } else {
                (clockMs / spec.durationMs.toFloat()).coerceIn(0f, 1f)
            }
            result = valueAt(spec, phase, fallback)
        }
        return result
    }

    private fun valueAt(spec: CustomAnimationSpec, phase: Float, fallback: Float): Float {
        val keys = spec.keyframes
        if (keys.isEmpty()) return fallback
        if (phase <= keys.first().first) return keys.first().second
        if (phase >= keys.last().first) return keys.last().second
        for (i in 0 until keys.lastIndex) {
            val a = keys[i]
            val b = keys[i + 1]
            if (phase in a.first..b.first) {
                val span = (b.first - a.first).coerceAtLeast(0.0001f)
                val t = (phase - a.first) / span
                return a.second + (b.second - a.second) * t
            }
        }
        return keys.last().second
    }

    private fun evenPositions(count: Int): FloatArray =
        FloatArray(count) { if (count <= 1) 0f else it / (count - 1f) }

    companion object {
        private val DUMMY = Path()
    }
}
