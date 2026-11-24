package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Color
import com.shilapi.xcertplay.host.R

/**
 * 取景窗主题。
 *
 * 设置页的下拉列表、保存键和说明文字都从这张表生成。新增一套主题只需要：
 *  1. 在这里加一个枚举项；
 *  2. 给它一份 [Palette]；
 *  3. 加两条字符串（名称 / 说明）；
 *  4. 如果背景不是默认波浪，再指定 [Decor] 并实现对应的背景层（如 [PortholeRayView]）。
 * 设置页不用再改。
 *
 * 说明文字是"每个主题一份"而不是固定一句：设置页在选中项变化时重新读取
 * [descriptionRes]，所以以后十几套主题各自的说明会跟着切换。
 *
 * 存的是 [id] 而不是枚举序号：以后主题增删、排序变化都不会让用户的选择错位到
 * 另一套主题；未知 id 统一回落到默认主题。
 */
internal enum class PortholeTheme(
    val id: String,
    val labelRes: Int,
    val descriptionRes: Int,
    val palette: Palette,
    /** 日夜主题在夜间使用这套色；其它主题为 null。 */
    val nightPalette: Palette? = null,
    /** 这个主题的背景由哪一层画：默认是两条波浪，射线主题是粒子射线 + 呼吸灯。 */
    val decor: Decor = Decor.WAVE,
) {
    DEFAULT(
        id = "default",
        labelRes = R.string.porthole_theme_default,
        descriptionRes = R.string.porthole_theme_default_desc,
        palette = Palette(
            // 取景窗视频层背后的颜色：视频还没到时看到的就是它。
            backdrop = Color.BLACK,
            cardBackground = Color.argb(235, 15, 20, 24),
            cardBorder = Color.argb(60, 255, 255, 255),
            textPrimary = Color.rgb(232, 236, 239),
            textMuted = Color.rgb(138, 147, 155),
            textDim = Color.rgb(91, 100, 107),
            late = Color.rgb(226, 75, 74),
            grid = Color.argb(18, 255, 255, 255),
            divider = Color.argb(42, 255, 255, 255),
        ),
    ),

    /**
     * 射线主题（概念图 2026-11-09）。
     *
     * 背景从面板中心向外发射彩色射线，颜色以当前歌曲封面为主、随 PCM 节奏重新抽色；
     * 左侧速度/挡位和右侧转速下面有跟着节拍呼吸的底色。默认主题的两条波浪不参与。
     * 速度/挡位/转速的数字本身仍是车机 MCU 画的，这里只画它们下面的底色/辉光。
     */
    RAY(
        id = "ray",
        labelRes = R.string.porthole_theme_ray,
        descriptionRes = R.string.porthole_theme_ray_desc,
        palette = Palette(
            backdrop = Color.rgb(3, 5, 12),
            cardBackground = Color.argb(228, 8, 12, 22),
            cardBorder = Color.argb(105, 130, 220, 255),
            textPrimary = Color.rgb(245, 248, 255),
            textMuted = Color.rgb(176, 190, 210),
            textDim = Color.rgb(110, 124, 145),
            late = Color.rgb(255, 80, 90),
            grid = Color.argb(30, 255, 255, 255),
            divider = Color.argb(55, 255, 255, 255),
            accentHueStart = 175f,
            accentHueSpan = 90f,
            accentSaturation = 0.7f,
            accentValue = 1f,
            waveEnabled = false,
        ),
        decor = Decor.RAY,
    ),

    /**
     * 日夜主题：跟随车灯在白天/夜晚两套画面之间切换。
     *
     * 背景由 [PortholeDayNightView] 画：白天太阳在右上角、暖色阳光向左下铺开；夜晚
     * 同位置换成月亮，边上布星，偶尔有流星划过。右侧卡片和取景窗边框也跟着换色，
     * 所以这里给两套 [Palette]。
     */
    DAY_NIGHT(
        id = "daynight",
        labelRes = R.string.porthole_theme_day_night,
        descriptionRes = R.string.porthole_theme_day_night_desc,
        palette = Palette(
            // 白天视频还没到时，取景窗里的底色偏深蓝，和天空/地图都能接住。
            backdrop = Color.rgb(31, 66, 92),
            cardBackground = Color.argb(236, 255, 250, 239),
            cardBorder = Color.argb(190, 255, 221, 166),
            textPrimary = Color.rgb(27, 41, 56),
            textMuted = Color.rgb(82, 98, 111),
            textDim = Color.rgb(122, 137, 148),
            late = Color.rgb(190, 58, 52),
            grid = Color.argb(26, 27, 41, 56),
            divider = Color.argb(38, 27, 41, 56),
            accentHueStart = 33f,
            accentHueSpan = 0f,
            accentSaturation = 0.62f,
            accentValue = 0.64f,
            waveEnabled = false,
        ),
        nightPalette = Palette(
            backdrop = Color.rgb(3, 5, 12),
            cardBackground = Color.argb(224, 8, 15, 28),
            cardBorder = Color.argb(120, 155, 195, 245),
            textPrimary = Color.rgb(243, 247, 255),
            textMuted = Color.rgb(180, 192, 210),
            textDim = Color.rgb(111, 127, 148),
            late = Color.rgb(255, 107, 107),
            grid = Color.argb(26, 255, 255, 255),
            divider = Color.argb(45, 255, 255, 255),
            accentHueStart = 198f,
            accentHueSpan = 0f,
            accentSaturation = 0.70f,
            accentValue = 1f,
            waveEnabled = false,
        ),
        decor = Decor.DAY_NIGHT,
    ),

    /**
     * 数学之美：每首歌从 Fourier / 超公式 / 混沌吸引子 / 环面纽结里随机选一种，
     * 颜色由封面主色（以及第二个较明显的色相）生成。背景层是 [PortholeMathView]。
     */
    MATH(
        id = "math",
        labelRes = R.string.porthole_theme_math,
        descriptionRes = R.string.porthole_theme_math_desc,
        palette = Palette(
            backdrop = Color.rgb(5, 10, 16),
            cardBackground = Color.argb(236, 6, 13, 21),
            cardBorder = Color.argb(150, 104, 225, 205),
            textPrimary = Color.rgb(241, 250, 248),
            textMuted = Color.rgb(168, 190, 190),
            textDim = Color.rgb(105, 129, 132),
            late = Color.rgb(255, 106, 112),
            grid = Color.argb(28, 255, 255, 255),
            divider = Color.argb(48, 255, 255, 255),
            accentHueStart = 176f,
            accentHueSpan = 0f,
            accentSaturation = 0.64f,
            accentValue = 1f,
            waveEnabled = false,
        ),
        decor = Decor.MATH,
    ),

    /**
     * 火焰双擎：左侧 0–220 km/h 车速环，右侧 0–5500 r/min 转速环。
     * 圆弧按实时车速/转速填充；PC bench 可用 BENCH_VEHICLE 覆盖测试值。
     */
    DUAL_RING(
        id = "dual_ring",
        labelRes = R.string.porthole_theme_dual_ring,
        descriptionRes = R.string.porthole_theme_dual_ring_desc,
        palette = Palette(
            backdrop = Color.rgb(5, 2, 1),
            cardBackground = Color.argb(232, 13, 7, 3),
            cardBorder = Color.argb(140, 255, 145, 55),
            textPrimary = Color.rgb(255, 247, 235),
            textMuted = Color.rgb(201, 175, 145),
            textDim = Color.rgb(137, 111, 86),
            late = Color.rgb(255, 68, 52),
            grid = Color.argb(24, 255, 170, 90),
            divider = Color.argb(55, 255, 175, 100),
            accentHueStart = 28f,
            accentHueSpan = 0f,
            accentSaturation = 0.86f,
            accentValue = 1f,
            waveEnabled = false,
        ),
        decor = Decor.DUAL_RING,
    ),

    /**
     * 导航双擎：不是普通取景窗背景，而是取景窗位置上的全屏导航 HUD。
     *
     * 取景窗小画面、ETA、歌曲卡全部隐藏，111 导航流铺满仪表；左右两个大圆环压在
     * MCU 的车速/转速读数上。三指位置机仍保持在取景窗(2)，因此左滑没有下一站，
     * 右滑回到仅导航(1)。
     */
    NAV_TWIN(
        id = "nav_twin",
        labelRes = R.string.porthole_theme_nav_twin,
        descriptionRes = R.string.porthole_theme_nav_twin_desc,
        palette = Palette(
            backdrop = Color.rgb(2, 7, 12),
            cardBackground = Color.argb(232, 8, 15, 22),
            cardBorder = Color.argb(130, 145, 210, 235),
            textPrimary = Color.rgb(241, 249, 255),
            textMuted = Color.rgb(166, 190, 205),
            textDim = Color.rgb(98, 122, 138),
            late = Color.rgb(255, 76, 78),
            grid = Color.argb(24, 145, 210, 235),
            divider = Color.argb(45, 210, 235, 245),
            accentHueStart = 195f,
            accentHueSpan = 0f,
            accentSaturation = 0.55f,
            accentValue = 1f,
            waveEnabled = false,
        ),
        decor = Decor.NAV_TWIN,
    ),

    /**
     * Runtime custom theme loaded from Android/data/<package>/files/themes/<id>/theme.json.
     * The actual id is stored separately in preferences; this enum value only selects the
     * custom renderer. It is not shown as a normal fixed entry in the picker.
     */
    CUSTOM(
        id = "__custom__",
        labelRes = R.string.porthole_theme_custom,
        descriptionRes = R.string.porthole_theme_custom_desc,
        palette = Palette(
            backdrop = Color.TRANSPARENT,
            cardBackground = Color.argb(220, 10, 17, 24),
            cardBorder = Color.argb(120, 140, 210, 235),
            textPrimary = Color.rgb(241, 249, 255),
            textMuted = Color.rgb(166, 190, 205),
            textDim = Color.rgb(98, 122, 138),
            late = Color.rgb(255, 76, 78),
            grid = Color.argb(24, 145, 210, 235),
            divider = Color.argb(45, 210, 235, 245),
            accentHueStart = 195f,
            accentHueSpan = 0f,
            accentSaturation = 0.55f,
            accentValue = 1f,
            waveEnabled = false,
        ),
        decor = Decor.CUSTOM,
    );

    enum class Decor { WAVE, RAY, DAY_NIGHT, MATH, DUAL_RING, NAV_TWIN, CUSTOM }

    /**
     * 一套主题的画布颜色。全部是 ARGB，取景窗按 1920x720 设计稿直接使用。
     *
     * 律动色不是固定值：默认主题和旧版一样，按歌名在青绿到紫色之间取色，
     * 所以这里给的是色相范围而不是一个"强调色"。不想跟歌名变的主题把
     * [accentHueSpan] 设为 0，只改 [accentHueStart] 即可。
     */
    data class Palette(
        /** 取景窗视频层背后的纯色。 */
        val backdrop: Int,
        val cardBackground: Int,
        val cardBorder: Int,
        val textPrimary: Int,
        val textMuted: Int,
        val textDim: Int,
        val late: Int,
        val grid: Int,
        val divider: Int,
        /** 律动色相起点，默认青绿 170°。 */
        val accentHueStart: Float = 170f,
        /** 律动色相跨度；0 表示所有歌曲用同一个色相。 */
        val accentHueSpan: Float = 110f,
        val accentSaturation: Float = 0.42f,
        val accentValue: Float = 0.88f,
        /** 是否绘制取景窗背景波浪。 */
        val waveEnabled: Boolean = true,
    )

    /** 日夜主题按车灯状态取对应的一套颜色；其它主题永远返回 [palette]。 */
    fun paletteFor(night: Boolean): Palette =
        if (night) nightPalette ?: palette else palette

    companion object {
        fun fromId(id: String?): PortholeTheme =
            entries.firstOrNull { it.id == id } ?: DEFAULT

        /** 按指定 id 解析；切换主题时用它锁定目标，避免中途再读偏好。 */
        fun loadById(context: Context, id: String?): PortholeTheme {
            entries.firstOrNull { it.id == id }?.let { return it }
            return if (CustomThemeRepository.exists(context, id)) CUSTOM else DEFAULT
        }

        /** 当前选择；设置里存的是 id。 */
        fun load(context: Context): PortholeTheme =
            loadById(context, AirPlayPersistence.loadClusterPortholeTheme(context))

        /** 取景窗左右滑动切换：step=+1 下一个，-1 上一个，循环。 */
        fun next(current: PortholeTheme, step: Int): PortholeTheme {
            val values = entries
            val index = values.indexOf(current).coerceAtLeast(0)
            return values[((index + step) % values.size + values.size) % values.size]
        }
    }
}
