package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.TypedValue
import android.view.View
import com.shilapi.xcertplay.daynight.CarLampMonitor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 取景窗视图的叠加层：左侧信息栏 + 底部播报字幕。
 *
 * 面板是 1920x720，绘制坐标直接用面板坐标，由 [ClusterMapPresentation] 铺满整个面板。
 * 数据全部来自 iAP2（0x5201 剩余时间/距离、0x5202 路名）与播报窗口，没有新增数据源。
 *
 * 字幕条画在取景窗底部之上，是覆盖层，不改变取景窗的裁切矩形；字幕出现/消失时
 * 宿主通过 [onSubtitleRowChanged] 只重画叠加层，取景窗不再上下移动。
 */
internal class PortholeOverlayView(context: Context) : View(context) {

    /** 一帧要画的数据。全部可为空：没有数据就不画那一块。 */
    data class Info(
        val arrivalClock: String = "",
        val arrivalDeltaText: String = "",
        val deltaIsLate: Boolean = true,
        val remainingKm: String = "",
        val remainingMin: String = "",
        /** 剩余分钟的历史，用来画趋势；越靠后越新。 */
        val history: FloatArray = FloatArray(0),
        val subtitle: String = "",
        /** 歌名（稳定、不含歌词），用来取律动色，避免歌词每变一行颜色就变。 */
        val songKey: String = "",
        /** 第一行：歌词（QQ 音乐放在 title；没有歌词时是歌手兜底）。 */
        val songTitle: String = "",
        /** 第二行：稳定歌名（QQ 音乐歌词帧里放在 artist）。 */
        val songArtist: String = "",
    )

    /**
     * 1 个"设计像素"= 1 个画布像素。
     *
     * 面板是 1920x720，这里的坐标全部按设计稿的面板像素写，不乘屏幕密度——乘了在 320dpi 的
     * 车机上字会大一倍（设计稿 26px 的字会画成 52px）。
     */
    private val density = 1f

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 15, 20, 24) }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.argb(60, 255, 255, 255)
    }
    private val textPrimary = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(232, 236, 239) }
    private val textMuted = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(138, 147, 155) }
    private val textDim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(91, 100, 107) }
    private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = Color.rgb(93, 202, 165)
    }
    private val latePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(226, 75, 74) }
    /** +N 分 / −N 分：跟 17:51 时钟同字号、同一基线，直接排在时间后面。 */
    private val deltaPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(18, 255, 255, 255) }
    /** 歌曲两行上方的细分隔线。 */
    private val songDivider = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(42, 255, 255, 255)
        strokeWidth = 1f * density
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.argb(150, 93, 202, 165)
    }
    /** 射线主题给取景窗视频加的一圈霓虹边框；默认主题不画。 */
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }
    private val frameRect = RectF()

    private var info = Info()
    /** 当前主题。默认值必须和下面这些 Paint 的初始色一致，否则第一帧会闪一下。 */
    private var theme: PortholeTheme = PortholeTheme.DEFAULT
    /** 当前实际使用的色板；日夜主题会按车灯状态在两套之间切。 */
    private var activePalette: PortholeTheme.Palette = PortholeTheme.DEFAULT.palette
    /** 日夜主题当前是否是夜晚；null 表示还没同步过。 */
    private var paletteNight: Boolean? = null
    /** 数学主题从封面取到的动态强调色；其它主题为 null。 */
    private var coverPrimary: Int? = null
    private var coverSecondary: Int? = null
    /** 仅供本地预览测试用；正常路径读 [CarLampMonitor.lastNight]。 */
    @Volatile
    private var nightOverride: Boolean? = null
    /** 律动强调色：来自封面/歌名映射到冷色区，换歌时 800ms 过渡。 */
    private var accentTarget: Int = Color.rgb(93, 202, 165)
    private var accentCurrent: Int = accentTarget
    private var accentAnimator: android.animation.ValueAnimator? = null
    private var subtitleVisible = false
    private val linePath = Path()
    private val cardRect = RectF()

    /** 字幕行的状态发生变化时通知宿主（用于取景窗伸缩）。 */
    var onSubtitleRowChanged: ((Boolean) -> Unit)? = null

    /** Releases the accent animator and callback when the porthole overlay is removed. */
    fun release() {
        accentAnimator?.cancel()
        accentAnimator = null
        onSubtitleRowChanged = null
        info = Info()
    }

    fun update(next: Info) {
        info = next
        applyAccentFor(next.songKey)
        val show = next.subtitle.isNotBlank()
        if (show != subtitleVisible) {
            subtitleVisible = show
            onSubtitleRowChanged?.invoke(show)
        }
        invalidate()
    }

    /** 只更新字幕（播报窗口来自 sink，刷新频率比数据高）。 */
    fun setSubtitle(text: String) {
        if (text == info.subtitle) return
        info = info.copy(subtitle = text)
        val show = text.isNotBlank()
        if (show != subtitleVisible) {
            subtitleVisible = show
            onSubtitleRowChanged?.invoke(show)
        }
        invalidate()
    }

    /** 歌曲帧到达时立即刷新两行，不等 1s 的 ETA tick。 */
    fun setSong(title: String, artist: String, songKey: String) {
        if (info.songTitle == title && info.songArtist == artist && info.songKey == songKey) return
        info = info.copy(songTitle = title, songArtist = artist, songKey = songKey)
        applyAccentFor(songKey)
        invalidate()
    }

    fun isSubtitleVisible(): Boolean = subtitleVisible

    /** 供背景波浪层同步使用。 */
    fun accentColor(): Int = accentCurrent

    /** 目标律动色：波浪层直接用它，避免 800ms 渐变期间先拿到旧颜色。 */
    fun accentTargetColor(): Int = accentTarget

    /**
     * 切换主题：只换这套主题定义的颜色，不重建 View、不打断正在播放的歌曲。
     * 设置页保存后由宿主调用；以后加主题不需要改这里的绘制代码。
     */
    fun applyTheme(next: PortholeTheme) {
        if (theme == next) return
        theme = next
        paletteNight = null
        val night = nightOverride ?: CarLampMonitor.effectiveNight ?: false
        applyPalette(next.paletteFor(night), night)
        // 换主题时直接落到新色，不播旧主题的过渡动画（否则会先看到一套半旧半新的色）。
        invalidate()
    }

    /** 预览测试：固定白天/夜晚，不依赖车机属性。 */
    internal fun setNightOverrideForPreview(night: Boolean?) {
        nightOverride = night
        paletteNight = null
        invalidate()
    }

    /**
     * 日夜主题：车灯一变，右侧卡片、文字和取景窗边框立刻换到对应色板。
     * 其它主题没有第二套色板，这里不做任何事。
     */
    private fun syncDayNight() {
        if (theme.decor != PortholeTheme.Decor.DAY_NIGHT) return
        val night = nightOverride ?: CarLampMonitor.effectiveNight ?: return
        if (night == paletteNight) return
        applyPalette(theme.paletteFor(night), night)
        invalidate()
    }

    private fun applyPalette(palette: PortholeTheme.Palette, night: Boolean) {
        activePalette = palette
        paletteNight = night
        bg.color = palette.cardBackground
        border.color = palette.cardBorder
        textPrimary.color = palette.textPrimary
        textMuted.color = palette.textMuted
        textDim.color = palette.textDim
        latePaint.color = palette.late
        grid.color = palette.grid
        songDivider.color = palette.divider
        accentAnimator?.cancel()
        accentCurrent = accentFor(info.songKey)
        accentTarget = accentCurrent
        accent.color = accentCurrent
        wavePaint.color = accentCurrent
        applyCoverAccentIfMath()
    }

    /** 数学之美：卡片边框/强调色跟随封面，不重建 View。 */
    fun setCoverAccent(primary: Int, secondary: Int) {
        if (coverPrimary == primary && coverSecondary == secondary) return
        coverPrimary = primary
        coverSecondary = secondary
        if (theme.decor == PortholeTheme.Decor.MATH) {
            applyCoverAccentIfMath()
            invalidate()
        }
    }

    private fun applyCoverAccentIfMath() {
        if (theme.decor != PortholeTheme.Decor.MATH) return
        val primary = coverPrimary ?: return
        val secondary = coverSecondary ?: primary
        border.color = withAlpha(primary, 190)
        accentAnimator?.cancel()
        accentCurrent = secondary
        accentTarget = secondary
        accent.color = secondary
        wavePaint.color = secondary
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    /**
     * 歌名/歌手 → 冷色区色相（170°–280°，青到紫）。
     *
     * 只用冷色是硬约束：红是"迟到"、琥珀是"缓行"，律动色一旦进入暖区，语义就废了。
     * 同一首歌永远是同一个颜色（哈希决定），换歌才换色。
     * 色相范围和饱和度由当前主题给出，默认值保持旧版行为。
     */
    private fun accentFor(songKey: String): Int {
        val palette = activePalette
        val hue = if (songKey.isBlank()) palette.accentHueStart else {
            val h = kotlin.math.abs(songKey.hashCode()) % 1000
            palette.accentHueStart + h / 1000f * palette.accentHueSpan
        }
        return Color.HSVToColor(
            floatArrayOf(
                ((hue % 360f) + 360f) % 360f,
                palette.accentSaturation.coerceIn(0f, 1f),
                palette.accentValue.coerceIn(0f, 1f),
            ),
        )
    }

    private fun applyAccentFor(songKey: String) {
        val target = accentFor(songKey)
        if (target == accentTarget) return
        accentTarget = target
        val from = accentCurrent
        accentAnimator?.cancel()
        accentAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            // 换歌时颜色尽快到位；旧 800ms 在 1s tick 的波浪采样下看起来像没更新。
            duration = 450L
            addUpdateListener { animator ->
                val f = animator.animatedValue as Float
                accentCurrent = blend(from, target, f)
                accent.color = accentCurrent
                wavePaint.color = accentCurrent
                invalidate()
            }
            start()
        }
    }

    private fun blend(from: Int, to: Int, f: Float): Int {
        val a = (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * f).toInt()
        val r = (Color.red(from) + (Color.red(to) - Color.red(from)) * f).toInt()
        val g = (Color.green(from) + (Color.green(to) - Color.green(from)) * f).toInt()
        val b = (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * f).toInt()
        return Color.argb(a, r, g, b)
    }

    /** 设计稿坐标 -> 实际画布。由宿主在布局变化时设置。 */
    private var metrics = PanelMetrics(1920, 720)

    fun setMetrics(next: PanelMetrics) {
        if (next == metrics) return
        metrics = next
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        syncDayNight()
        // 全部按 1920x720 设计稿画，最后整体缩放：画布是 1080 高时内容等比放大，不会挤在顶部。
        val save = canvas.save()
        canvas.translate(0f, metrics.offsetY)
        canvas.scale(metrics.scale, metrics.scale)
        drawRail(canvas)
        if (theme.decor == PortholeTheme.Decor.RAY || theme.decor == PortholeTheme.Decor.DAY_NIGHT) {
            drawFrame(canvas)
        }
        if (subtitleVisible) drawSubtitle(canvas)
        canvas.restoreToCount(save)
    }

    /** 射线/日夜主题的取景窗边框：外淡光晕 + 内窄线。 */
    private fun drawFrame(canvas: Canvas) {
        frameRect.set(PORTHOLE_LEFT, PORTHOLE_TOP, PORTHOLE_RIGHT, PORTHOLE_BOTTOM)
        framePaint.color = if (theme.decor == PortholeTheme.Decor.MATH && coverPrimary != null) {
            coverPrimary!!
        } else {
            activePalette.cardBorder
        }
        val dayNight = theme.decor == PortholeTheme.Decor.DAY_NIGHT
        framePaint.strokeWidth = (if (dayNight) 6f else 8f) * density
        framePaint.alpha = if (dayNight) 42 else 52
        canvas.drawRoundRect(frameRect, 20f, 20f, framePaint)
        framePaint.strokeWidth = 2.5f * density
        framePaint.alpha = if (dayNight) 178 else 215
        canvas.drawRoundRect(frameRect, 20f, 20f, framePaint)
    }

    private fun drawRail(canvas: Canvas) {
        val top = RAIL_TOP
        // 到达信息卡不随字幕变化：字幕是悬浮提示条，出现/消失只影响它自己，
        // 否则右侧卡片会在播报时缩短，和左侧取景窗高度不对称。
        val bottom = RAIL_BOTTOM
        cardRect.set(RAIL_LEFT, top, RAIL_RIGHT, bottom)
        val radius = 18f * density
        canvas.drawRoundRect(cardRect, radius, radius, bg)
        canvas.drawRoundRect(cardRect, radius, radius, border)

        val padLeft = RAIL_LEFT + 26f
        val padRight = RAIL_RIGHT - 26f
        var y = top + 46f

        // 预计到达
        textMuted.textSize = 20f * density
        canvas.drawText("预计到达", padLeft, y, textMuted)
        y += 62f
        textPrimary.textSize = 54f * density
        val clockText = info.arrivalClock.ifBlank { "--:--" }
        canvas.drawText(clockText, padLeft, y, textPrimary)

        if (info.arrivalDeltaText.isNotBlank()) {
            // 时间在左，变化量右对齐到卡片内边距；两者至少留 16px。
            // 先按 54px 排，剩余空间不够时逐像素缩小变化量（最小 30px），
            // 这样 +4 分、+58 分、+2 小时、+11 小时 都不会顶出右边距。
            val gap = 16f * density
            val clockRight = padLeft + textPrimary.measureText(clockText)
            val available = (padRight - clockRight - gap).coerceAtLeast(1f)
            var deltaSize = 54f
            var deltaText = info.arrivalDeltaText
            deltaPaint.textSize = deltaSize * density
            var deltaWidth = deltaPaint.measureText(deltaText)
            while (deltaWidth > available && deltaSize > 30f) {
                deltaSize -= 1f
                deltaPaint.textSize = deltaSize * density
                deltaWidth = deltaPaint.measureText(deltaText)
            }
            if (deltaWidth > available) {
                // 极长文案（例如 +11 小时 59 分）仍放不下时收尾省略，绝不越过右边距。
                var cut = deltaText
                while (cut.isNotEmpty() && deltaPaint.measureText("$cut…") > available) {
                    cut = cut.dropLast(1)
                }
                deltaText = if (cut.isEmpty()) "…" else "$cut…"
                deltaWidth = deltaPaint.measureText(deltaText)
            }
            deltaPaint.color = if (info.deltaIsLate) latePaint.color else accent.color
            canvas.drawText(deltaText, padRight - deltaWidth, y, deltaPaint)
        }

        // 趋势曲线
        val chartTop = y + 56f * density
        val chartBottom = chartTop + 70f * density
        if (info.history.size >= 2) {
            canvas.drawLine(padLeft, chartBottom, padRight, chartBottom, grid)
            linePath.reset()
            var lo = Float.MAX_VALUE
            var hi = -Float.MAX_VALUE
            info.history.forEach { v ->
                lo = min(lo, v)
                hi = max(hi, v)
            }
            val span = max(1f, hi - lo)
            val stepX = (padRight - padLeft) / (info.history.size - 1).toFloat()
            info.history.forEachIndexed { index, value ->
                val x = padLeft + stepX * index
                val ratio = (value - lo) / span
                val yy = chartBottom - ratio * (chartBottom - chartTop)
                if (index == 0) linePath.moveTo(x, yy) else linePath.lineTo(x, yy)
            }
            canvas.drawPath(linePath, accent)
        }

        // 剩余 / 还需
        var rowY = chartBottom + 48f * density
        textMuted.textSize = 23f * density
        textPrimary.textSize = 23f * density
        canvas.drawText("剩余", padLeft, rowY, textMuted)
        val km = info.remainingKm.ifBlank { "--" }
        canvas.drawText(km, padRight - textPrimary.measureText(km), rowY, textPrimary)
        rowY += 38f * density
        canvas.drawText("还需", padLeft, rowY, textMuted)
        val minText = info.remainingMin.ifBlank { "--" }
        canvas.drawText(minText, padRight - textPrimary.measureText(minText), rowY, textPrimary)

        // 第一行歌词，第二行稳定歌名；两行都单行截断。
        val lyricLine = info.songTitle.trim().replace('\n', ' ').replace('\r', ' ')
        val songNameLine = info.songArtist.trim().replace('\n', ' ').replace('\r', ' ')
        val firstLine = lyricLine.ifBlank { songNameLine }
        val secondLine = if (lyricLine.isNotEmpty()) songNameLine else ""
        if (firstLine.isNotEmpty()) {
            val dividerY = top + 351f * density
            canvas.drawLine(padLeft, dividerY, padRight, dividerY, songDivider)
            val maxWidth = padRight - padLeft
            var lineY = top + 388f * density
            textPrimary.textSize = 24f * density
            canvas.drawText(ellipsize(firstLine, textPrimary, maxWidth), padLeft, lineY, textPrimary)
            if (secondLine.isNotEmpty()) {
                lineY += 34f * density
                textMuted.textSize = 21f * density
                canvas.drawText(ellipsize(secondLine, textMuted, maxWidth), padLeft, lineY, textMuted)
            }
        }
    }

    /** 单行截断：超宽时逐字回退并补省略号，绝不越过 maxWidth。 */
    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (text.isEmpty() || paint.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        val ellipsisWidth = paint.measureText(ellipsis)
        var end = text.length
        while (end > 0 && paint.measureText(text, 0, end) + ellipsisWidth > maxWidth) {
            end--
        }
        return text.substring(0, end).trimEnd() + ellipsis
    }

    private fun drawSubtitle(canvas: Canvas) {
        val rect = RectF(SUB_LEFT, SUB_TOP, SUB_RIGHT, SUB_BOTTOM)
        val radius = 12f * density
        canvas.drawRoundRect(rect, radius, radius, bg)
        canvas.drawRoundRect(rect, radius, radius, border)

        // 三个"声波"竖条，固定形态（真机接 PCM 包络再让它动）
        val barLeft = SUB_LEFT + 26f
        val centerY = (SUB_TOP + SUB_BOTTOM) / 2f
        val heights = floatArrayOf(12f, 22f, 16f)
        heights.forEachIndexed { index, h ->
            val x = barLeft + index * 10f * density
            canvas.drawLine(
                x,
                centerY - h * density / 2f,
                x,
                centerY + h * density / 2f,
                wavePaint,
            )
        }

        textPrimary.textSize = 26f * density
        val textX = barLeft + 46f * density
        val maxWidth = SUB_RIGHT - textX - 24f
        // iAP2 的路名/指令原样显示；超过可用宽度时从尾部截断并补 "..."，
        // 不再像旧实现那样只丢字符、看不出被截断。
        var text = info.subtitle
        if (textPrimary.measureText(text) > maxWidth) {
            var cut = text
            while (cut.isNotEmpty() && textPrimary.measureText("$cut...") > maxWidth) {
                cut = cut.dropLast(1)
            }
            text = if (cut.isEmpty()) "..." else "$cut..."
        }
        val baseline = centerY - (textPrimary.descent() + textPrimary.ascent()) / 2f
        canvas.drawText(text, textX, baseline, textPrimary)
    }

    companion object {
        // 面板坐标（1920x720）。取景窗在左侧 (400,150)-(1150,590)，信息栏在它右边，
        // 这样"左上角导航卡 → 地图 → 信息栏"从左往右一眼扫完。底边统一留 20px，
        // 不再贴着 610 的安全区下沿（PC 窗口正好裁到 610，贴边会显得被切掉）。
        const val RAIL_LEFT = 1175f
        const val RAIL_RIGHT = 1535f
        const val RAIL_TOP = 150f
        const val RAIL_BOTTOM = 590f

        // 播报字幕：只覆盖取景窗宽度（400..1150）的一条悬浮提示，不压到右侧到达卡。
        const val SUB_LEFT = 400f
        const val SUB_RIGHT = 1150f
        const val SUB_TOP = 530f
        const val SUB_BOTTOM = 586f

        /** 取景窗高度（固定）：字幕只是覆盖在底部，不改变窗口高度，也不改变信息栏高度。 */
        const val PORTHOLE_H = 440

        /** 视频窗口的设计稿矩形，与 ClusterMapPresentation 的 PORTHOLE_* 一致。 */
        const val PORTHOLE_LEFT = 400f
        const val PORTHOLE_TOP = 150f
        const val PORTHOLE_RIGHT = 1150f
        const val PORTHOLE_BOTTOM = 590f
    }
}
