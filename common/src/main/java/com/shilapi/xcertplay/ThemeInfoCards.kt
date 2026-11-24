package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

/**
 * A live card drawn into a panel box (the layer's `dst`), instead of being a native-size view
 * scaled by [CustomThemeHost].
 *
 * The custom-theme `eta` / `song` slots used to reuse the built-in [ClusterEtaCardView] and
 * [ClusterSongView]. Those are designed for the nav-only dashboard and look nothing like the
 * porthole's arrival strip, so a theme that placed a card there got a foreign style. These two
 * cards reproduce the DEFAULT porthole arrival-card style instead (see [ThemeCardStyle]).
 */
internal interface CustomBoxLiveView {
    /**
     * Places the card. `w`/`h` are the layer's dst size; `scale` is the layer's extra scale and
     * scales the whole card around the box's top-left corner, like every other live slot.
     */
    fun setBox(x: Float, y: Float, w: Float, h: Float, scale: Float)
}

/**
 * Shared look of the two custom-theme info cards.
 *
 * Values are copied from [PortholeOverlayView]'s info rail, i.e. the DEFAULT porthole theme's
 * "到达信息卡": a dark glass panel (15/20/24 at 92 %), hairline white border, white primary text,
 * grey muted labels, teal accent for an early arrival and red for a late one. The Theme Maker's
 * preview uses the same numbers, so a theme looks identical in the editor and in the car.
 *
 * Both cards are authored on a fixed [BASE_W] x [BASE_H] canvas and then scaled to the layer's
 * dst box. That makes the theme's width/height the real card size (the editor's box is what the
 * driver gets) and keeps the preview honest at any aspect ratio.
 */
internal object ThemeCardStyle {
    const val BASE_W = 430f
    const val BASE_H = 165f
    const val RADIUS = 18f

    /** argb(235, 15, 20, 24) — the rail's card background. */
    val FILL: Int = Color.argb(235, 15, 20, 24)

    /** argb(60, 255, 255, 255) — the rail's hairline border. */
    val BORDER: Int = Color.argb(60, 255, 255, 255)

    val TEXT_PRIMARY: Int = Color.rgb(232, 236, 239)
    val TEXT_MUTED: Int = Color.rgb(138, 147, 155)
    val ACCENT: Int = Color.rgb(93, 202, 165)
    val LATE: Int = Color.rgb(226, 75, 74)

    fun textPaint(color: Int, size: Float, medium: Boolean): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            typeface = Typeface.create(
                if (medium) "sans-serif-medium" else "sans-serif",
                Typeface.NORMAL,
            )
            isSubpixelText = true
        }

    /** Ellipsizes [text] to [maxWidth]; returns the text unchanged when it already fits. */
    fun fitText(text: String, paint: Paint, maxWidth: Float): String {
        if (text.isEmpty() || paint.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        val ellipsisWidth = paint.measureText(ellipsis)
        var end = text.length
        while (end > 0 && paint.measureText(text, 0, end) + ellipsisWidth > maxWidth) {
            end--
        }
        return text.substring(0, end).trimEnd() + ellipsis
    }
}

/** Base class: owns the box, the panel background and the fixed design canvas. */
internal abstract class ThemeCardView(context: Context) : View(context), CustomBoxLiveView {
    private val box = RectF()
    private var extraScale = 1f
    private val panel = RectF(0f, 0f, ThemeCardStyle.BASE_W, ThemeCardStyle.BASE_H)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ThemeCardStyle.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ThemeCardStyle.BORDER
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = false
        isFocusable = false
    }

    override fun setBox(x: Float, y: Float, w: Float, h: Float, scale: Float) {
        // A theme layer without dst keeps the card's native design size at the panel origin,
        // instead of stretching it over the whole 1920x720 panel.
        val width = if (w > 1f) w else ThemeCardStyle.BASE_W
        val height = if (h > 1f) h else ThemeCardStyle.BASE_H
        box.set(x, y, x + width, y + height)
        extraScale = scale.coerceIn(0.05f, 5f)
        // Keyframe scale/rotation are authored around the card's middle (same as the editor).
        pivotX = box.centerX()
        pivotY = box.centerY()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (box.isEmpty) return
        val save = canvas.save()
        canvas.translate(box.left, box.top)
        canvas.scale(
            (box.width() / ThemeCardStyle.BASE_W) * extraScale,
            (box.height() / ThemeCardStyle.BASE_H) * extraScale,
        )
        canvas.drawRoundRect(panel, ThemeCardStyle.RADIUS, ThemeCardStyle.RADIUS, fillPaint)
        canvas.drawRoundRect(panel, ThemeCardStyle.RADIUS, ThemeCardStyle.RADIUS, borderPaint)
        drawContent(canvas)
        canvas.restoreToCount(save)
    }

    protected abstract fun drawContent(canvas: Canvas)
}

/**
 * The `eta` slot: 预计到达 + clock + delta + 剩余/还需, i.e. the arrival part of the default
 * porthole's info rail. Always draws something: with no route data the rail shows "--:--" too,
 * and a theme that declared the slot must show the card (it is a layout element, not a status).
 */
internal class ThemeEtaCardView(context: Context) : ThemeCardView(context) {
    private var info: PortholeOverlayView.Info? = null

    private val labelPaint = ThemeCardStyle.textPaint(ThemeCardStyle.TEXT_MUTED, 19f, false)
    private val clockPaint = ThemeCardStyle.textPaint(ThemeCardStyle.TEXT_PRIMARY, 52f, true)
    private val deltaPaint = ThemeCardStyle.textPaint(ThemeCardStyle.ACCENT, 32f, true)
    private val subLabelPaint = ThemeCardStyle.textPaint(ThemeCardStyle.TEXT_MUTED, 18f, false)
    private val subValuePaint = ThemeCardStyle.textPaint(ThemeCardStyle.TEXT_PRIMARY, 18f, false)

    fun setInfo(next: PortholeOverlayView.Info?) {
        info = next
        invalidate()
    }

    override fun drawContent(canvas: Canvas) {
        val pad = 22f
        val next = info
        val clock = next?.arrivalClock?.takeIf { it.isNotBlank() } ?: "--:--"
        val delta = next?.arrivalDeltaText.orEmpty().trim()
        val km = next?.remainingKm?.takeIf { it.isNotBlank() } ?: "--"
        val minutes = next?.remainingMin?.takeIf { it.isNotBlank() } ?: "--"

        canvas.drawText("预计到达", pad, 40f, labelPaint)
        canvas.drawText(clock, pad, 104f, clockPaint)

        if (delta.isNotEmpty()) {
            val right = ThemeCardStyle.BASE_W - pad
            val room = right - (pad + clockPaint.measureText(clock) + 12f)
            var size = 32f
            deltaPaint.textSize = size
            while (deltaPaint.measureText(delta) > room && size > 18f) {
                size -= 1f
                deltaPaint.textSize = size
            }
            deltaPaint.color = if (next?.deltaIsLate == true) ThemeCardStyle.LATE else ThemeCardStyle.ACCENT
            val shown = ThemeCardStyle.fitText(delta, deltaPaint, room)
            canvas.drawText(shown, right - deltaPaint.measureText(shown), 104f, deltaPaint)
        }

        var x = pad
        canvas.drawText("剩余", x, 143f, subLabelPaint)
        x += subLabelPaint.measureText("剩余") + 8f
        canvas.drawText(km, x, 143f, subValuePaint)
        x += subValuePaint.measureText(km) + 22f
        canvas.drawText("还需", x, 143f, subLabelPaint)
        x += subLabelPaint.measureText("还需") + 8f
        canvas.drawText(minutes, x, 143f, subValuePaint)
    }
}

/**
 * The `song` slot: the two text lines of the default porthole's arrival card (the lyric/first
 * line in white, the stable song name in grey below it) plus a thin accent bar. Text only —
 * the rail carries no album art, so neither does this card.
 */
internal class ThemeSongCardView(context: Context) : ThemeCardView(context) {
    private var line1 = ""
    private var line2 = ""
    private val barRect = RectF(0f, 30f, 6f, ThemeCardStyle.BASE_H - 30f)

    private val titlePaint = ThemeCardStyle.textPaint(ThemeCardStyle.TEXT_PRIMARY, 30f, true)
    private val namePaint = ThemeCardStyle.textPaint(ThemeCardStyle.TEXT_MUTED, 19f, false)
    private val emptyPaint = ThemeCardStyle.textPaint(ThemeCardStyle.TEXT_MUTED, 24f, false)
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ThemeCardStyle.ACCENT
        alpha = 200
    }

    fun setSong(title: String, artist: String) {
        val next1 = title.trim()
        val next2 = artist.trim()
        if (next1 == line1 && next2 == line2) return
        line1 = next1
        line2 = next2
        invalidate()
    }

    override fun drawContent(canvas: Canvas) {
        val pad = 24f
        canvas.drawRoundRect(barRect, 3f, 3f, barPaint)

        if (line1.isEmpty() && line2.isEmpty()) {
            canvas.drawText("暂无歌曲", pad, 82f, emptyPaint)
            canvas.drawText("CarPlay 未发送歌曲信息", pad, 122f, namePaint)
            return
        }
        val maxWidth = ThemeCardStyle.BASE_W - pad - 22f
        val first = ThemeCardStyle.fitText(line1.ifEmpty { line2 }, titlePaint, maxWidth)
        canvas.drawText(first, pad, 88f, titlePaint)
        if (line2.isNotEmpty() && line1.isNotEmpty()) {
            canvas.drawText(ThemeCardStyle.fitText(line2, namePaint, maxWidth), pad, 128f, namePaint)
        }
    }
}
