package com.shilapi.xcertplay

import android.app.Presentation
import com.shilapi.xcertplay.hud.ClusterSong
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.WindowManager
import android.graphics.Point
import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewOutlineProvider
import android.view.TextureView
import android.widget.FrameLayout
import android.widget.TextView
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.daynight.CarDataMonitor
import com.shilapi.xcertplay.daynight.CarLampMonitor
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.BydLaneGuidance
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import com.shilapi.xcertplay.media.MusicReactiveBus
import com.shilapi.xcertplay.media.ScreenStreamGeometry
import java.util.Locale

/**
 * Shows CarPlay's instrument-cluster stream on a BYD cluster projection display.
 *
 * BYD exposes the cluster's projection area as public presentation displays owned by
 * com.byd.containerservice; the stock map (com.byd.launchermap) draws there the same way. The
 * cluster only shows this display while its projection mode is on, which DiPlay cannot switch.
 */
internal class ClusterMapPresentation(
    context: Context,
    display: Display,
    private val theme: DiLink51ClusterLayout.Theme = DiLink51ClusterLayout.theme(context),
    private val onMirrorSurface: (Surface?) -> Unit,
    private val onNavSurface: (Surface?) -> Unit,
    /**
     * The instruction-card stream surface (FLY_CARD only). Bound to SCREEN_TYPE_ALT_CARD by the
     * host; null on every other mode, where the card view is never created.
     */
    private val onCardSurface: (Surface?) -> Unit = {},
    /**
     * 取景窗对 111 的绑定：非 null 时把导航流（111）的输出面切到取景窗，null 表示
     * 取景窗拆除（由 [detachPortholeSurface] 先把 111 交还全屏导航视图，再回调这里）。
     */
    private val onPortholeSurface: ((Surface?) -> Unit)? = null,
    private val onCardDebug: (String) -> Unit = {},
    private val navOnlyCards: Boolean = false,
    /**
     * The "pure Apple cluster" branch (2026-10-24): the 111 stream is requested with the
     * instruments URL (maps:/car/instrumentcluster), shown FULL SCREEN like the nav-only mode,
     * and nothing of ours is drawn on top - no guidance cards, no song card. The mode is also
     * pinned to navigation: mirror requests (three-finger swipe, auto switch) are ignored.
     * Selected via the "cluster picture source" setting (Content.FLY_CARD).
     */
    private val flyCard: Boolean = false,
    /** Only MAP_WITH_CUSTOM_CARD owns the locally drawn cards. */
    private val customCards: Boolean = true,
) : Presentation(context, display) {
    private var waitingLabel: TextView? = null
    /**
     * The turn/instruction card. Nav-only mode only (A1): the whole-screen mirror must stay the
     * raw mirror picture, so this view does not exist while the dashboard shows it.
     */
    private var turnCardView: ClusterTurnCardView? = null
    /**
     * The arrival (ETA) card. Shown in BOTH modes - nav-only and whole-screen mirror (A2): the
     * driver wants the arrival estimate next to CarPlay's own picture as well.
     */
    private var etaCardView: ClusterEtaCardView? = null
    /** Song now playing, top centre. Title/artist only - the protocol carries no lyrics. */
    private var songView: ClusterSongView? = null
    private var genericTextureView: TextureView? = null
    private var navTextureView: TextureView? = null
    private var cardTextureView: TextureView? = null
    /** Clips the full decoded buffer to the phone-reported crop rectangle. */
    private var cardContainer: FrameLayout? = null
    /** Root the card window is attached to while it exists (FLY_CARD only). */
    private var rootView: FrameLayout? = null
    private var debugFpsView: TextView? = null
    /** Full-panel blocking notice shown while the theme is being replaced. */
    private var themeSwitchOverlay: TextView? = null
    /** The instruction-card stream is up right now (the phone is sending frames). */
    private var cardStreamActive = false
    /** Current decoded picture size of the instruction-card stream, in its own pixels. */
    private var cardVideoWidth = CarPlayClusterDisplay.CARD_WIDTH_PIXELS
    private var cardVideoHeight = CarPlayClusterDisplay.CARD_HEIGHT_PIXELS
    /** Full decoded buffer; the window shows only [cardCropWidth] x [cardCropHeight]. */
    private var cardStreamWidth = CarPlayClusterDisplay.CARD_WIDTH_PIXELS
    private var cardStreamHeight = CarPlayClusterDisplay.CARD_HEIGHT_PIXELS
    private var cardCropLeft = 0
    private var cardCropTop = 0
    private var cardCropWidth = CarPlayClusterDisplay.CARD_WIDTH_PIXELS
    private var cardCropHeight = CarPlayClusterDisplay.CARD_HEIGHT_PIXELS
    /** Panel coordinates the card window was last laid out at, i.e. AFTER the clamp (FLY_CARD). */
    private var cardWindowLeft = CARD_LEFT_PX
    private var cardWindowTop = CARD_TOP_PX
    /** The 0x0-panel warning is emitted once per window, not on every per-second refresh. */
    private var cardWindowSizeWarned = false
    private val projectionSize: Point = sizeOf(display)
    var mirrorOutputSurface: Surface? = null
        private set
    var navOutputSurface: Surface? = null
        private set
    var cardOutputSurface: Surface? = null
        private set

    // Nav-only card group state. When navOnlyCards is true (Chang'an generic path) the group
    // belongs to the navigation stream: the turn card exists ONLY in nav-only mode, while the
    // ETA card and the song card are shown in whole-screen mirror mode too (driver requirement
    // 2026-10-24). DiLink 5.1 keeps the upstream behavior (turn card follows the "dashboard
    // shows" content setting, no ETA card).
    private var mirrorMode = false
    /**
     * 测试版：取景窗视图。
     *
     * 111 流不再铺满仪表，而是按 1:1 只显示一块矩形（横向以车标为中心、底边压 safe area 下沿），
     * 其余区域留给卡片和信息栏。用 TextureView 的变换实现裁剪，不缩放，所以不会糊。
     */
    private var portholeMode = false
    /** 取景窗视图的叠加层（信息栏 + 字幕）；只在取景窗模式下挂载并显示。 */
    private var portholeOverlay: PortholeOverlayView? = null
    /** 取景窗模式的背景波浪：加在容器最底层，所以始终在内容之下。 */
    private var portholeWave: PortholeWaveView? = null
    private var portholeDayNight: PortholeDayNightView? = null
    private var portholeMath: PortholeMathView? = null
    private var portholeDualRing: PortholeDualRingView? = null
    /** 导航双擎：取景窗位置上的全屏导航 + 双环 HUD。 */
    private var portholeNavTwin: PortholeNavTwinView? = null
    private var navTwinActive = false
    private var customThemeHost: CustomThemeHost? = null
    private var customThemeActive = false
    /** The custom theme's own arrival card (declared `eta` slot), default-rail look. */
    private var themeEtaCard: ThemeEtaCardView? = null
    /** The custom theme's own song card (declared `song` slot), default-rail look. */
    private var themeSongCard: ThemeSongCardView? = null
    /** Last porthole info pushed by the host; seeds the theme cards when they are created. */
    private var lastPortholeInfo: PortholeOverlayView.Info? = null
    /**
     * Live slots declared by the active custom theme (`nav` / `card112` / `eta` / `song`).
     *
     * 主题拥有整块面板：内置视图只在该槽位被声明时保留，没声明的一律移除；否则每秒一次的
     * setEtaGuidance()/setSong() 会把它们重新显示在主题上（2026-11-10 反馈）。
     */
    private var customLiveSlots: Set<String> = emptySet()
    /** 取景窗“射线”主题的背景层；与波浪二选一，不能同时显示。 */
    private var portholeRay: PortholeRayView? = null
    /** bench 模式下的 MCU 背景（挡位/里程/转速/指示灯），压在最底层。 */
    private var benchMcu: BenchMcuView? = null
    /**
     * The PC simulator's secondary display is an Android overlay display. Unlike the car
     * panel it has no hardware MCU layer, so speed/rpm/gear must always be drawn by the
     * benchmark MCU view on that display — even when bench mode itself was left off.
     */
    private val simulatedOverlayDisplay: Boolean by lazy {
        val name = getDisplay()?.name ?: return@lazy false
        name.contains("叠加", ignoreCase = true) ||
            name.contains("overlay", ignoreCase = true)
    }
    /**
     * 画布固定为 1920x720。
     *
     * 我们只对这一个尺寸负责：设备给的副屏若是别的分辨率（例如平板 ROM 只有 1920x1080），
     * 那是设备设置问题，不去适配——否则同一套布局在两种尺寸下会有两种行为，反而查不清。
     */
    private val metrics = PanelMetrics(1920, 720)
    /**
     * 取景窗专用窗口（750x460）。
     *
     * 不能把全屏的 navTextureView 缩成小块：它的 buffer 是面板尺寸，缩完再平移等于整幅
     * 画面压缩，取不到"某一小块"。这里另建一个 TextureView，把 111 的 Surface 接到它上面，
     * 再用变换只截取 111 里 (785,150)-(1535,590) 那一块。
     */
    private var portholeTextureView: TextureView? = null
    /** 取景窗外框：只负责圆角裁切（TextureView 自己不能画 outline）。 */
    private var portholeContainer: FrameLayout? = null
    private var portholeSurface: Surface? = null
    /** 射线主题的取色兜底：封面还没到时先用歌名。 */
    private var lastPortholeSongKey = ""
    /** 111 当前是否被取景窗占着（用于异常销毁时把 111 还回全屏视图）。 */
    private var portholeOwnsNav = false
    /** 取景窗模式下的 112 卡片缩放：卡片流本身是 480x200，缩到 0.83 才不会压到取景窗。 */
    private var cardWindowScale = 1f
    /** 面板尺寸，取景窗的流按它协商（车机固定 1920x720）。 */
    private var panelSize: Point = Point(1920, 720)
    /** Custom-theme nav viewport: x, y, width, height, scale. Null keeps the built-in crop. */
    private var customNavRect: FloatArray? = null
    /** Last mode requested by the host, kept even while the TextureViews are still being created. */
    private var desiredMirror = AirPlayPersistence.loadClusterMirrorMain(context)
    private var lastTurnGuidance: ClusterTurnGuidance? = null
    private var lastLanes: BydLaneGuidance? = null
    private var lastJunction: Bitmap? = null
    private var lastEtaGuidance: ClusterTurnGuidance? = null

    // Per-frame cost killers: card show/hide switches and the fronted-card set are cached.
    // applyCardsVisibility runs on every guidance tick (per second); reading SharedPreferences
    // or calling bringToFront() there would requestLayout/re-read the disk every frame.
    // Load them at construction: on a cold start the first guidance tick can arrive before any
    // settings change, and the old defaults (true) showed cards the driver had switched off
    // until a reconnect re-read them.
    //
    // Only the ETA card is left here: the turn card has its own switch below, the song card is
    // unconditional, and the road-name card was removed from the product (A3).
    private var cardVisibleCache: Map<String, Boolean> = mapOf(
        "eta" to AirPlayPersistence.loadClusterCardVisible(context, "eta"),
    )
    private var turnCardVisibleCache = AirPlayPersistence.loadClusterTurnCardVisible(context)
    private var frontedCards: List<View> = emptyList()
    /** The window's root, where guidance cards are added and removed as the mode changes. */
    private var cardsRoot: FrameLayout? = null

    /**
     * Re-applies the saved dashboard target rectangle to the generic TextureView without
     * destroying/recreating the window or its decoder surface. Called by the host when the
     * "dashboard picture adjust" panel closes; a no-op on the DiLink 5.1 layout path.
     *
     * Order of operations (driver requirement, 10-08):
     * 1. CROP the whole-mirror source first: the left rail (CarPlay dock, ~14%) is removed
     *    from the SOURCE coordinates, so the rail is gone from the picture entirely.
     * 2. Then APPLY the dashboard picture adjust rect: the cropped picture is scaled
     *    (aspect-preserving) to FIT inside the target rect, centred on it. Fit - not fill -
     *    so every slider move shrinks/grows the picture around its centre instead of sliding
     *    it or cropping its edges (field report 10-08 18:2x: fill made the height slider read
     *    as "the picture is dragged upward"). The leftover margin shows the backdrop.
     */
    fun refreshCrop() {
        val textureView = genericTextureView ?: return
        val baseW = projectionSize.x
        val baseH = projectionSize.y
        // The auto navigation switch changes the live mode without writing the saved setting;
        // use this window's actual mode, not the persisted one, or crop adjustments are ignored
        // while the dashboard is showing the mirror.
        if (!mirrorMode) {
            // Nav-only stream: full-screen, untouched. No target frame and no left-rail crop
            // - the driver wants the raw navigation stream edge to edge.
            textureView.setTransform(Matrix())
            textureView.invalidate()
            Log.i(TAG, "nav-only stream: full screen, no crop on ${baseW}x${baseH}")
            return
        }
        // Step 1 - source crop: remove the left rail from the source picture (fixed pixels,
        // set once by the driver; the old percent-based crop default cropped too much).
        val cropLeftPx = AirPlayPersistence.loadClusterMirrorLeftCropPx(context)
        val availW = baseW - cropLeftPx
        // Step 2 - dashboard picture adjust target rect (percent of the dashboard panel). The values
        // may be negative or above 100: the driver uses them to push the picture off an edge or
        // enlarge it past the panel, so no clamping to 0..100 happens here - only a floor of one
        // pixel keeps the matrix invertible.
        val xPct = AirPlayPersistence.loadClusterCropLeftPercent(context)
        val yPct = AirPlayPersistence.loadClusterCropTopPercent(context)
        val wPct = AirPlayPersistence.loadClusterCropWidthPercent(context)
        val hPct = AirPlayPersistence.loadClusterCropHeightPercent(context)
        val w = (wPct * baseW / 100).coerceAtLeast(1)
        val h = (hPct * baseH / 100).coerceAtLeast(1)
        val x = xPct * baseW / 100
        val y = yPct * baseH / 100
        // Pipeline (10-08 field requirements): FIRST crop the left rail out of the SOURCE
        // picture, THEN fit the remaining picture into the target rect. Fit - not cover - so
        // every adjustment to the frame is immediately visible: making the frame smaller
        // visibly shrinks the dashboard picture.
        //
        // A single TextureView matrix cannot express both cleanly: the source crop is a shift
        // of the whole buffer by -cropLeftPx*scale, while centering the cropped picture in the
        // rect would otherwise let the left rail leak back into the left margin. Two steps:
        //   1. Matrix maps source (cropLeftPx,0) to the picture's top-left (picLeft,picTop),
        //      which pushes the rail out of the visible area entirely.
        //   2. Clip the TextureView's left edge so any rail pixels that would leak into the
        //      left margin (fit margins) are never drawn - the margin shows the black backdrop
        //      instead, symmetric with the right margin.
        // FIT (contain), not cover: scale is the MINIMUM of the two axes so the cropped
        // picture always fits inside the target rect, aspect-preserving and centred. Cover
        // (maxOf) kept the scale pinned to the wider axis and only translated the frame when
        // the height slider moved, which read as "the whole picture is dragged upward"
        // (field report 10-08 18:2x). With fit, shrinking the frame shrinks the picture
        // symmetrically around its centre; the leftover margin shows the backdrop.
        val scale = minOf(w.toFloat() / availW, h.toFloat() / baseH)
        val drawnW = availW * scale
        val drawnH = baseH * scale
        val picLeft = x + (w - drawnW) / 2f
        val picTop = y + (h - drawnH) / 2f
        val offsetX = picLeft - cropLeftPx * scale
        val offsetY = picTop
        textureView.setTransform(Matrix().apply {
            setScale(scale, scale)
            postTranslate(offsetX, offsetY)
        })
        // Only the left edge needs clipping (the rail). When picLeft <= 0 the buffer's left
        // edge already maps to a source x >= cropLeftPx, so nothing can leak. Fall back to
        // baseW/baseH when the view is not laid out yet (width == 0 would clip everything).
        val clipLeft = if (picLeft > 0f) picLeft.toInt() else 0
        val clipRight = if (textureView.width > 0) textureView.width else baseW
        val clipBottom = if (textureView.height > 0) textureView.height else baseH
        textureView.setClipBounds(Rect(clipLeft, 0, clipRight, clipBottom))
        // The head-unit TextureView keeps rendering the LAST decoded frame when the stream is
        // idle; a new transform alone is not re-applied until the next frame arrives (the
        // driver sees "no change until the console moves"). Force a redraw of the current
        // buffer with the new matrix now, both this frame and on the next vsync.
        textureView.invalidate()
        textureView.postInvalidateOnAnimation()
        Log.i(
            TAG,
            "mirror rect x=$x y=$y w=$w h=$h fit=$scale drawn=${drawnW}x${drawnH} " +
                "placed=($offsetX,$offsetY) leftCrop=${cropLeftPx}px clipLeft=$clipLeft on ${baseW}x${baseH}",
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Start transparent on the generic path.
        //
        // incall lays an ext-screen window out as its small preview box at the right of the panel
        // until the projection channel is open, and our window is created several seconds before
        // the open sequence. The activity calls setMapVisible(false) right after show(), but the
        // window is already on screen for the frames in between - that is the driver's "冷启动时
        // 仪表右边中间自动投一个小方块". Being transparent from the moment the window exists means
        // the box is never seen; setMapVisible(true) only runs once the channel reports open.
        if (!DiLink51ClusterLayout.supported()) {
            window?.attributes = window?.attributes?.apply { alpha = 0f }
        }
        val size = projectionSize
        panelSize = size
        Log.i(TAG, "cluster panel: ${size.x}x${size.y}")
        val adaptive = DiLink51ClusterLayout.supported()
        val plan = if (adaptive) DiLink51ClusterLayout.plan(size.x, size.y, theme) else null
        val dark = DiLink51ClusterLayout.dark(context, theme)
        val backdrop = if (dark) Color.rgb(15, 22, 30) else Color.rgb(207, 218, 229)
        val root = FrameLayout(context).apply {
            setBackgroundColor(
                when {
                    plan == null -> Color.BLACK
                    plan.fullMap -> backdrop
                    else -> Color.TRANSPARENT
                },
            )
        }
        rootView = root
        debugFpsView = null
        if (plan != null) {
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        val videoParams = if (plan == null) FrameLayout.LayoutParams(-1, -1) else
            FrameLayout.LayoutParams(plan.width, plan.height, Gravity.TOP or Gravity.LEFT).apply {
                leftMargin = plan.left; topMargin = plan.top
            }
        if (plan != null) {
            val textureView = TextureView(context).apply { isOpaque = true }
            fun transform() {
                textureView.setTransform(Matrix().apply {
                    setScale(DiLink51ClusterLayout.STREAM_WIDTH.toFloat() / plan.width,
                        DiLink51ClusterLayout.STREAM_HEIGHT.toFloat() / plan.height)
                    postTranslate(-plan.sourceLeft.toFloat(), -plan.sourceTop.toFloat())
                })
            }
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                    texture.setDefaultBufferSize(DiLink51ClusterLayout.STREAM_WIDTH, DiLink51ClusterLayout.STREAM_HEIGHT)
                    transform()
                    mirrorOutputSurface = Surface(texture).also(onMirrorSurface)
                }
                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = transform()
                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                    onMirrorSurface(null)
                    mirrorOutputSurface?.release()
                    mirrorOutputSurface = null
                    return true
                }
            }
            root.addView(textureView, videoParams)
            if (plan.fullMap) root.addView(InstrumentContrastView(context, plan, backdrop), FrameLayout.LayoutParams(-1, -1))
            Log.i(TAG, "layout=$theme viewport=$plan source=${plan.sourceLeft},${plan.sourceTop} dark=$dark")
        } else {
            // Generic path (Changan Feiyu OS and other OEMs). TWO TextureViews are created so a
            // mode switch never has to rebuild the window. The 111 navigation Surface stays bound
            // from first projection, which is what stops the head-unit from announcing
            // "全屏导航已开启" every time the driver swipes to nav-only (incall announces on 111
            // stream (re-)activation events; the OEM box keeps the stream resident for the same
            // reason). The 110 mirror Surface is kept too, but its decoder is bound only while the
            // mirror is actually shown (CarPlayHostActivity.updateClusterMirrorDecoder), because
            // the duplicate full-panel decode is expensive while the view is GONE. A TextureView
            // lets the driver crop/zoom via the "dashboard picture adjust" sliders; the transform
            // maps the saved crop window onto the whole panel, so smaller width/height zooms in and
            // left/top moves the visible window. Only the mirror view carries the crop - the nav
            // view is full-screen untouched.
            val mirrorTextureView = TextureView(context).apply { isOpaque = true }
            genericTextureView = mirrorTextureView
            mirrorTextureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                    Log.i(TAG, "cluster mirror surface created")
                    texture.setDefaultBufferSize(size.x, size.y)
                    refreshCrop()
                    mirrorOutputSurface = Surface(texture).also(onMirrorSurface)
                }

                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = refreshCrop()

                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                    Log.i(TAG, "cluster mirror surface destroyed")
                    mirrorOutputSurface?.release()
                    mirrorOutputSurface = null
                    onMirrorSurface(null)
                    return true
                }
            }
            root.addView(mirrorTextureView, videoParams)
            // Second TextureView for stream 111. Kept invisible until nav-only mode is selected;
            // its Surface is bound to sink.setSurface(SCREEN_TYPE_ALT, ...) as soon as it exists so
            // the 111 stream stays resident in incall (no re-activation announcement on switches).
            // The pure-Apple branch (flyCard) uses the SAME full-screen view - it differs only in
            // the requested URL and in never drawing anything of ours on top (see setMode).
            val navView = TextureView(context).apply { isOpaque = true }
            navTextureView = navView
            navView.visibility = View.GONE
            navView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                    Log.i(TAG, "cluster nav surface created buffer=${size.x}x${size.y}")
                    // buffer 按画布尺寸设置（否则 111 会被解码成别的比例）。取景窗的
                    // 裁切不在这里做：它另有一块同样大小的 buffer，只改 TextureView 的
                    // 视图变换（见 portholeTransform）。
                    texture.setDefaultBufferSize(size.x, size.y)
                    navOutputSurface = Surface(texture).also(onNavSurface)
                    // The TextureViews are created asynchronously, so a setMode() issued while
                    // they were still null would have been lost (e.g. cold-start auto-project to
                    // nav-only would show the mirror view). Re-assert the last requested mode;
                    // never the saved setting, which the navigation auto-switch bypasses.
                    setMode(desiredMirror)
                    applyPortholeLayout()
                }

                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                    Log.i(TAG, "cluster nav stream size: ${width}x${height}")
                    if (portholeMode) applyPortholeLayout()
                }

                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                    Log.i(TAG, "cluster nav surface destroyed")
                    navOutputSurface?.release()
                    navOutputSurface = null
                    onNavSurface(null)
                    return true
                }
            }
            root.addView(navView, videoParams)
            // The instruction-card window (FLY_CARD) is NOT built here.
            //
            // It exists only while the phone is actually sending that stream AND there is
            // guidance to show: createCardWindow() builds a TextureView sized to the stream's own
            // picture and drops it at the saved position (default: the panel's upper-left
            // corner), destroyCardWindow() removes it again. Nothing of ours is ever drawn around
            // it - no background, no mask, no stroke - so the dashboard shows exactly the frames
            // the phone sends, and nothing at all when it sends none. See setCardStreamActive().
        }
        waitingLabel = TextView(context).apply {
            text = context.getString(R.string.cluster_waiting_for_map)
            setTextColor(if (plan != null && !dark) Color.DKGRAY else Color.WHITE)
            textSize = 26f
            gravity = Gravity.CENTER
        }
        root.addView(waitingLabel, FrameLayout.LayoutParams(videoParams))
        // The guidance cards are NOT created here.
        //
        // They are created by applyCardsVisibility() from the saved switches, BEFORE the stream is
        // shown: the turn card only in nav-only mode, the ETA card in both modes (A1/A2). Creating
        // them here would let a card flash over CarPlay's own picture before it arrives.
        cardsRoot = root
        // TEMPORARY EXCLUSION TEST (driver's request, 2026-10-24).
        //
        // Every self-drawn card is switched off so a screenshot can only show what the phone
        // itself sends. If the glass turn box in the middle of the dashboard survives this build,
        // it is Apple's own instrument-cluster view; if it disappears, it was one of ours.
        // Set to false to restore the cards.
        if (!CARDS_DISABLED) {
            // Added last so it draws above the guidance cards.
            //
            // The song card - like the ETA card - is shown in EVERY mode (A4): it is created once
            // here and lives until the window dies. Nothing in the mode or guidance paths ever
            // hides or destroys it, and ClusterSongView decides its own visibility purely from
            // whether a song is playing.
            songView = ClusterSongView(context).apply { visibility = View.GONE }
            root.addView(songView, FrameLayout.LayoutParams(-1, -1))
        }
        setContentView(root)
    }

    /**
     * Creates the guidance cards the driver's switches ask for.
     *
     * Two different rules live here (driver's requirements, 2026-10-24):
     * - the TURN card belongs to the navigation stream only: it is never created while the
     *   dashboard shows the whole-screen mirror (A1);
     * - the ETA card is shown in both modes, mirror included (A2).
     * Everything here is idempotent: an existing card is left alone, a missing one is created
     * with the guidance data already known, and the music card is never touched because it
     * belongs to every mode.
     */
    private fun materializeGuidanceCards() {
        if (CARDS_DISABLED) return
        val root = cardsRoot ?: return
        // Turn card: nav-only stream only. iOS's own instruction box is the whole picture of the
        // mirror stream, and a second card on top of it was exactly the "glass box" confusion.
        // FLY_CARD never draws this card: Apple's instruction-card WINDOW carries the turns there.
        if (customCards && !mirrorMode && turnCardView == null && turnCardVisibleCache) {
            turnCardView = ClusterTurnCardView(context, onCardDebug).apply { visibility = View.GONE }
            root.addView(turnCardView, FrameLayout.LayoutParams(-1, -1))
            // A rebuilt card starts at its own defaults, so restore the driver's saved geometry
            // before it can draw.
            turnCardView?.setLayout(
                AirPlayPersistence.loadClusterTurnCardX(context),
                AirPlayPersistence.loadClusterTurnCardY(context),
                AirPlayPersistence.loadClusterCardScalePercent(context, "turn"),
            )
            turnCardView?.setBackgroundAlpha(AirPlayPersistence.loadClusterCardAlpha(context, "turn"))
            turnCardView?.setGuidance(lastTurnGuidance)
            turnCardView?.setLanes(lastLanes)
            turnCardView?.setJunctionImage(lastJunction)
        }
        // ETA card: both modes (A2). The host feeds setEtaGuidance() in either mode.
        if (etaCardView == null && (cardVisibleCache["eta"] ?: true)) {
            etaCardView = ClusterEtaCardView(context).apply { visibility = View.GONE }
            root.addView(etaCardView, FrameLayout.LayoutParams(-1, -1))
            etaCardView?.setBackgroundAlpha(AirPlayPersistence.loadClusterCardAlpha(context, "eta"))
            etaCardView?.setGuidance(lastEtaGuidance)
        }
        // The cards are inserted after the video surfaces, so they must be raised again.
        listOfNotNull(turnCardView, etaCardView).forEach { it.bringToFront() }
        // The music card was added after the guidance cards in onCreate; keep it on top.
        songView?.bringToFront()
        // 112 卡片永远压在这些自绘卡片/歌曲卡之上。
        cardContainer?.bringToFront()
        Log.i(
            TAG,
            "guidance cards created turn=${turnCardView != null} eta=${etaCardView != null} " +
                "(mode=${if (mirrorMode) "mirror" else "nav-only"})",
        )
    }

    /**
     * Removes the turn card from the window.
     *
     * Destroyed rather than hidden: a hidden view can still be brought back by a stray
     * setGuidance(), and the driver's complaint was exactly that a card stayed on screen after
     * the dashboard left nav-only mode. ONLY the turn card goes away with the navigation stream:
     * the ETA card and the music card are shown in both modes, so they are deliberately left
     * untouched here (A2/A4).
     */
    private fun destroyTurnCard() {
        val root = cardsRoot ?: return
        val view = turnCardView ?: return
        root.removeView(view)
        turnCardView = null
        // Force the next visibility pass to re-front the remaining cards.
        frontedCards = emptyList()
        Log.i(TAG, "turn card destroyed")
    }

    /**
     * Removes the built-in arrival card from the window.
     *
     * Hidden is not enough: [ClusterEtaCardView.setGuidance] sets the view VISIBLE whenever the
     * guidance carries arrival/remaining data, and the host feeds it once per second, so a
     * "hidden" arrival card came straight back on top of the custom theme (field report
     * 2026-11-10). Same rule the turn card already follows: the view must not exist.
     */
    private fun destroyEtaCard() {
        val view = etaCardView ?: return
        cardsRoot?.removeView(view)
        etaCardView = null
        frontedCards = frontedCards.filter { it !== view }
        Log.i(TAG, "eta card destroyed")
    }

    /**
     * Selects which resident stream is displayed on the dashboard: whole-screen mirror (110) or
     * navigation stream only (111). Both decoder surfaces stay bound to the sink - only the view
     * visibility changes, so the incall service never sees a 111 (re-)activation and does not
     * announce "全屏导航已开启". On the DiLink 5.1 layout (single TextureView) the mode is a no-op.
     */
    /** 测试版：切换 111 的"取景窗"布局。返回 true 表示已应用。 */
    /**
     * 打开/关闭 MCU 背景（仅 bench）。
     *
     * PC 的模拟副屏没有车机那层 MCU，取景窗视图会缺了挡位/转速；这里按实车截图把那一层画出来，
     * 加在容器最底部，于是 scrcpy 窗口里的画面和车机上一致。真机（bench 关闭）不会创建它。
     */
    fun setBenchMcuVisible(visible: Boolean) {
        if (!visible) {
            benchMcu?.let { runCatching { rootView?.removeView(it) } }
            benchMcu = null
            return
        }
        benchMcu?.let {
            it.visibility = View.VISIBLE
            it.translationZ = BENCH_MCU_Z
            it.bringToFront()
            return
        }
        val root = rootView ?: return
        BenchMcuView(context).also {
            it.setMetrics(metrics)
            // PC bench has no real MCU overlay above the panel, so this View simulates it.
            // It must be above every app layer (map, porthole, cards, themes), exactly like the
            // car's hardware MCU digits; a high Z keeps it on top even when later views call
            // bringToFront() (the instruction card uses Z=100).
            it.translationZ = BENCH_MCU_Z
            root.addView(it, 0, FrameLayout.LayoutParams(-1, -1))
            it.bringToFront()
            benchMcu = it
        }
    }

    /** 背景波浪层：只在取景窗模式下存在并运行。 */
    private fun ensurePortholeWave(): PortholeWaveView? {
        portholeWave?.let { return it }
        val root = rootView ?: return null
        val wave = PortholeWaveView(context)
        wave.setMetrics(metrics)
        wave.applyTheme(PortholeTheme.load(context))
        // index 0：压在所有内容之下，包括两块视频层。
        root.addView(wave, 0, FrameLayout.LayoutParams(-1, -1))
        portholeWave = wave
        return wave
    }

    /** 日夜主题的背景层；和波浪/射线一样压在最底层，由车灯状态决定白天或夜晚。 */
    private fun ensurePortholeDayNight(theme: PortholeTheme): PortholeDayNightView? {
        portholeDayNight?.let { it.applyTheme(theme); return it }
        val root = rootView ?: return null
        val view = PortholeDayNightView(context)
        view.setMetrics(metrics)
        view.applyTheme(theme)
        view.onNightChanged = { portholeOverlay?.invalidate() }
        view.visibility = View.GONE
        root.addView(view, 0, FrameLayout.LayoutParams(-1, -1))
        portholeDayNight = view
        return view
    }

    /** 数学之美背景层；公式图形画好缓存，逐帧只叠加呼吸/微光。 */
    private fun ensurePortholeMath(theme: PortholeTheme): PortholeMathView? {
        portholeMath?.let {
            it.applyTheme(theme)
            it.publishAccent()
            return it
        }
        val root = rootView ?: return null
        val view = PortholeMathView(context)
        view.setMetrics(metrics)
        view.applyTheme(theme)
        view.setSongKey(lastPortholeSongKey)
        view.setArtwork(CarPlayMediaKeys.currentArtwork())
        view.onAccent = { primary, secondary ->
            portholeOverlay?.setCoverAccent(primary, secondary)
        }
        view.visibility = View.GONE
        root.addView(view, 0, FrameLayout.LayoutParams(-1, -1))
        portholeMath = view
        view.publishAccent()
        return view
    }

    /** 火焰双擎背景层；只画火墙和双环，车速/转速/挡位/状态由 MCU 层显示。 */
    private fun ensurePortholeDualRing(theme: PortholeTheme): PortholeDualRingView? {
        portholeDualRing?.let { it.applyTheme(theme); return it }
        val root = rootView ?: return null
        val view = PortholeDualRingView(context)
        view.setMetrics(metrics)
        view.applyTheme(theme)
        view.visibility = View.GONE
        root.addView(view, 0, FrameLayout.LayoutParams(-1, -1))
        portholeDualRing = view
        return view
    }

    /** 射线主题的背景层；与波浪一样压在最底层，让车机 MCU 的读数压在它上面。 */
    /**
     * 导航双擎：透明双环层，压在 111 全屏导航之上、MCU 之下。
     *
     * 这一层不参与取景窗裁切，不做视频 Surface；它只负责把两个环画在 MCU 读数周围。
     */
    private fun ensurePortholeNavTwin(theme: PortholeTheme): PortholeNavTwinView? {
        portholeNavTwin?.let { it.applyTheme(theme); return it }
        val root = rootView ?: return null
        val view = PortholeNavTwinView(context)
        view.setMetrics(metrics)
        view.applyTheme(theme)
        view.visibility = View.GONE
        // Above the map and the song card; below the instruction card (z=100) and the PC MCU.
        view.translationZ = 50f
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        portholeNavTwin = view
        return view
    }

    /**
     * 进入导航双擎：隐藏取景窗小地图/卡片，111 交回全屏视图，环层打开。
     *
     * 位置机仍停在取景窗(2)，不是仅导航(1)。这样三指左滑到边界没有下一站，右滑才回 1。
     */
    private fun enterNavTwin() {
        if (navTwinActive) {
            portholeNavTwin?.visibility = View.VISIBLE
            if (portholeMode) portholeNavTwin?.start()
            return
        }
        navTwinActive = true

        portholeWave?.stop()
        portholeWave?.visibility = View.GONE
        portholeRay?.stop()
        portholeRay?.visibility = View.GONE
        portholeDayNight?.stop()
        portholeDayNight?.visibility = View.GONE
        portholeMath?.stop()
        portholeMath?.visibility = View.GONE
        portholeDualRing?.stop()
        portholeDualRing?.visibility = View.GONE

        portholeOverlay?.visibility = View.GONE
        turnCardView?.visibility = View.GONE
        etaCardView?.visibility = View.GONE
        songView?.setSuppressed(true)

        // Release the porthole's private 111 surface and give the full-screen view the stream
        // back. No reconnect is involved; only the sink's target TextureView changes.
        detachPortholeSurface()
        navTextureView?.visibility = View.VISIBLE
        setBenchMcuVisible(BenchCluster.enabled || simulatedOverlayDisplay)

        ensurePortholeNavTwin(PortholeTheme.load(context))?.let {
            it.visibility = View.VISIBLE
            it.start()
        }
        // Apple 完整导航时，112 卡片流就是手机自己的导航卡；它必须和 111 一起出现。
        // 非 Apple 完整地图没有 cardContainer，这里自然是空操作。
        applyCardWindowVisibility()
        Log.i(TAG, "nav twin: full nav + rings, position stays porthole(2)")
    }

    /**
     * 退出导航双擎。reattach=true 是两指切回普通取景窗主题；false 是三指离开位置 2，
     * 此时交给正常的模式切换，不再把取景窗小地图接回来。
     */
    private fun exitNavTwin(reattach: Boolean) {
        if (!navTwinActive) return
        navTwinActive = false
        portholeNavTwin?.stop()
        portholeNavTwin?.visibility = View.GONE
        songView?.setSuppressed(false)

        if (reattach && portholeMode) {
            portholeOverlay?.visibility = View.VISIBLE
            navTextureView?.visibility = View.GONE
            attachPortholeSurface()
            applyPortholeLayout()
        } else {
            navTextureView?.visibility = if (desiredMirror) View.GONE else View.VISIBLE
        }
        applyCardWindowVisibility()
        Log.i(TAG, "nav twin: exit reattach=$reattach porthole=$portholeMode")
    }

    /**
     * One custom-theme data frame. The host samples it once per tick and shares the same instance
     * with every text binding, gauge and visibleIf, so they cannot disagree inside one frame.
     */
    private fun themeData(): ThemeData {
        val info = lastPortholeInfo
        val nowMs = System.currentTimeMillis()
        val minutes = ThemeData.localMinutes(nowMs)
        val snapshot = CarDataMonitor.snapshot
        return ThemeData(
            speedKmh = CarDataMonitor.displayedSpeedKmh(),
            rpm = CarDataMonitor.displayedRpm(),
            odometerKm = snapshot.odometerKm,
            rangeKm = snapshot.rangeKm,
            night = CarLampMonitor.effectiveNight == true,
            appleFullMap = flyCard,
            clockMinutes = minutes,
            clockText = ThemeData.clockText(minutes),
            // A song-only Info is not navigation: after an explicit route end the host keeps
            // publishing the song lines, so navActive must look at the ETA fields themselves.
            navActive = info != null && (
                info.remainingKm.isNotBlank() ||
                    info.remainingMin.isNotBlank() ||
                    info.arrivalClock.isNotBlank()
                ),
            navRemainingKm = info?.remainingKm.orEmpty(),
            navRemainingMin = info?.remainingMin.orEmpty(),
            navArrival = info?.arrivalClock.orEmpty(),
            navDelta = info?.arrivalDeltaText.orEmpty(),
            navSubtitle = info?.subtitle.orEmpty(),
            songTitle = info?.songTitle.orEmpty(),
            songArtist = info?.songArtist.orEmpty(),
            gear = snapshot.gear,
            gearRaw = snapshot.gearRaw,
            gearText = snapshot.gearText,
            turnLeft = snapshot.turnLeft,
            turnRight = snapshot.turnRight,
            hazard = snapshot.hazard,
            turnLeftBlink = snapshot.turnLeftBlink,
            turnRightBlink = snapshot.turnRightBlink,
            audioLevel = MusicReactiveBus.levelAt(),
            audioBass = MusicReactiveBus.bass,
            beatPulse = MusicReactiveBus.pulseAt(),
        )
    }

    /**
     * Runtime custom theme V1. Layers are separate sibling Views, so a custom image can be
     * above the 112 card and the next one below the ETA.
     */
    private fun ensureCustomThemeHost(spec: CustomThemeSpec): CustomThemeHost {
        customThemeHost?.detach()
        customNavRect = null
        // 自定义主题之间切换时，旧主题可能把取景窗放到左上角/改过 scale。先把
        // porthole 容器恢复成内置布局，再由新主题的 nav dst 覆盖；否则新主题
        // 没有 nav 或 nav 解析失败时会继承上一套主题的窗口位置。
        etaCardView?.clearCustomPosition()
        songView?.clearCustomPosition()
        if (portholeMode) applyPortholeLayout(forceBuiltIn = true)
        ensureThemeLiveCards()
        val host = CustomThemeHost(
            context = context,
            root = rootView ?: error("no root"),
            spec = spec,
            liveViews = mapOf(
                "nav" to portholeContainer,
                "card112" to cardContainer,
                "eta" to themeEtaCard,
                "song" to themeSongCard,
            ),
            appleFullMap = { flyCard },
            data = { themeData() },
            onNavRect = { x, y, w, h, scale -> applyCustomNavRect(x, y, w, h, scale) },
            onLayerOrderChanged = {
                if (BenchCluster.enabled || simulatedOverlayDisplay) setBenchMcuVisible(true)
            },
        )
        customThemeHost = host
        host.attach()
        return host
    }

    /**
     * Creates the two custom-theme info cards the spec declares.
     *
     * A custom theme gets its own ETA/song cards, drawn in the default porthole arrival-card
     * style, instead of the built-in nav-only cards. They are plain sibling views like every
     * other theme layer, so z-order, `visibleIf` and keyframes all apply.
     */
    private fun ensureThemeLiveCards() {
        val root = rootView ?: return
        if ("eta" in customLiveSlots) {
            if (themeEtaCard == null) {
                themeEtaCard = ThemeEtaCardView(context).also {
                    it.setInfo(lastPortholeInfo)
                    root.addView(it, FrameLayout.LayoutParams(-1, -1))
                }
            }
        } else {
            destroyThemeEtaCard()
        }
        if ("song" in customLiveSlots) {
            if (themeSongCard == null) {
                themeSongCard = ThemeSongCardView(context).also {
                    it.setSong(
                        lastPortholeInfo?.songTitle.orEmpty(),
                        lastPortholeInfo?.songArtist.orEmpty(),
                    )
                    root.addView(it, FrameLayout.LayoutParams(-1, -1))
                }
            }
        } else {
            destroyThemeSongCard()
        }
    }

    private fun destroyThemeEtaCard() {
        themeEtaCard?.let { runCatching { rootView?.removeView(it) } }
        themeEtaCard = null
    }

    private fun destroyThemeSongCard() {
        themeSongCard?.let { runCatching { rootView?.removeView(it) } }
        themeSongCard = null
    }

    private fun detachCustomThemeHost() {
        customThemeHost?.detach()
        customThemeHost = null
        customThemeActive = false
        customLiveSlots = emptySet()
        // 主题里声明了 song 时内置歌曲卡被压掉，退出主题要恢复。
        songView?.setSuppressed(false)
        customNavRect = null
        etaCardView?.clearCustomPosition()
        songView?.clearCustomPosition()
        destroyThemeEtaCard()
        destroyThemeSongCard()
        if (portholeMode) {
            restoreBuiltInPortholeLayout()
        } else {
            // 正在离开取景窗：容器马上会隐藏，只把主题留下的位移/缩放还原，
            // 免得下次进入取景窗时继承上一套主题的变换。
            applyPortholeLayout(forceBuiltIn = true)
        }
    }

    /** The custom live layer moved/scaled the porthole views; put the built-in layout back. */
    private fun restoreBuiltInPortholeLayout() {
        Log.i(TAG, "restore built-in porthole layout porthole=$portholeMode")
        cardWindowScale = PORTHOLE_CARD_SCALE
        applyCardWindowScale()
        // Recreate the porthole window. A custom live layer may have moved/scaled
        // the old container; rebuilding guarantees the built-in transform is pristine.
        detachPortholeSurface()
        navTextureView?.visibility = View.GONE
        attachPortholeSurface()
        applyPortholeLayout()
        refreshCardWindowPosition()
        applyCardWindowVisibility()
    }

    private fun ensurePortholeRay(theme: PortholeTheme): PortholeRayView? {
        portholeRay?.let { it.applyTheme(theme); return it }
        val root = rootView ?: return null
        val ray = PortholeRayView(context)
        ray.setMetrics(metrics)
        ray.applyTheme(theme)
        ray.setSongKey(lastPortholeSongKey)
        ray.setArtwork(CarPlayMediaKeys.currentArtwork())
        ray.visibility = View.GONE
        root.addView(ray, 0, FrameLayout.LayoutParams(-1, -1))
        portholeRay = ray
        return ray
    }

    /**
     * Full-panel black notice used while a theme switch is in progress. It is deliberately
     * kept for the whole clear+load operation so no half-old/half-new frame is visible.
     */
    fun showThemeSwitchOverlay(text: String) {
        val root = rootView ?: return
        var overlay = themeSwitchOverlay
        if (overlay == null) {
            overlay = TextView(context).apply {
                setBackgroundColor(Color.BLACK)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f)
                typeface = Typeface.DEFAULT_BOLD
                isClickable = false
                isFocusable = false
                translationZ = 2500f
            }
            root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
            themeSwitchOverlay = overlay
        }
        overlay.text = text
        overlay.visibility = View.VISIBLE
        overlay.bringToFront()
    }

    fun hideThemeSwitchOverlay() {
        themeSwitchOverlay?.let { runCatching { rootView?.removeView(it) } }
        themeSwitchOverlay = null
    }

    /**
     * Clear the currently displayed theme before the next one is loaded. This removes all
     * custom sibling views, restores the built-in porthole window and hides the old
     * decorative layers. The black switch notice stays on top during the operation.
     */
    fun clearPortholeThemeForSwitch() {
        detachCustomThemeHost()
        portholeWave?.stop()
        portholeWave?.visibility = View.GONE
        portholeRay?.stop()
        portholeRay?.visibility = View.GONE
        portholeDayNight?.stop()
        portholeDayNight?.visibility = View.GONE
        portholeMath?.stop()
        portholeMath?.visibility = View.GONE
        portholeDualRing?.stop()
        portholeDualRing?.visibility = View.GONE
        portholeNavTwin?.stop()
        portholeNavTwin?.visibility = View.GONE
        portholeOverlay?.visibility = View.GONE
        turnCardView?.visibility = View.GONE
    }

    /**
     * 主题切换立即生效：叠加层颜色、背景层、视频层背后的底色一起换。
     * 不重建窗口、不重连，正在显示的取景窗原地换肤。
     */
    fun applyPortholeTheme(targetId: String? = null) {
        val rawId = targetId ?: AirPlayPersistence.loadClusterPortholeTheme(context)
        val theme = PortholeTheme.loadById(context, rawId)
        if (theme.decor == PortholeTheme.Decor.CUSTOM) {
            if (!portholeMode) {
                detachCustomThemeHost()
                return
            }
            val spec = CustomThemeRepository.loadSpec(context, rawId)
            if (spec == null) {
                Log.w(TAG, "custom theme missing/invalid: $rawId; falling back to default")
                AirPlayPersistence.saveClusterPortholeTheme(context, PortholeTheme.DEFAULT.id)
                detachCustomThemeHost()
                return
            }
            if (navTwinActive) exitNavTwin(reattach = true)
            portholeWave?.stop(); portholeWave?.visibility = View.GONE
            portholeRay?.stop(); portholeRay?.visibility = View.GONE
            portholeDayNight?.stop(); portholeDayNight?.visibility = View.GONE
            portholeMath?.stop(); portholeMath?.visibility = View.GONE
            portholeDualRing?.stop(); portholeDualRing?.visibility = View.GONE
            portholeNavTwin?.stop(); portholeNavTwin?.visibility = View.GONE
            portholeOverlay?.visibility = View.GONE
            turnCardView?.visibility = View.GONE
            customThemeActive = true
            // 自定义主题自己画整块面板：只有 theme.json 声明过的 live 槽位才保留视图。
            // 内置到达卡/歌曲卡在自定义主题里一律不使用，改为按取景窗默认到达卡样式
            // 单独绘制的 themeEtaCard / themeSongCard（见 ensureThemeLiveCards）。
            customLiveSlots = spec.layers.filter { it.type == "live" }.map { it.slot }.toSet()
            destroyEtaCard()
            songView?.setSuppressed(true)
            portholeContainer?.setBackgroundColor(Color.TRANSPARENT)
            ensureCustomThemeHost(spec)
            return
        }
        if (customThemeActive) {
            detachCustomThemeHost()
            portholeOverlay?.visibility = if (portholeMode) View.VISIBLE else View.GONE
            portholeContainer?.visibility = if (portholeMode) View.VISIBLE else View.GONE
            portholeContainer?.setBackgroundColor(theme.palette.backdrop)
            applyCardWindowVisibility()
            songView?.visibility = View.VISIBLE
        }
        if (!portholeMode) {
            // A theme switch while the dashboard is in nav/mirror must not recreate the
            // porthole-only decorative layers just to leave them GONE. The saved theme is
            // applied again when the porthole is entered.
            return
        }
        restoreBuiltInPortholeLayout()
        if (theme.decor == PortholeTheme.Decor.NAV_TWIN) {
            if (portholeMode) {
                enterNavTwin()
            } else {
                // The theme can be picked while the dashboard is on another position; only
                // position 2 owns the full-screen HUD. Do not leave a stale overlay behind.
                exitNavTwin(reattach = false)
            }
            return
        }
        if (navTwinActive) exitNavTwin(reattach = portholeMode)
        val accent = portholeOverlay?.accentTargetColor()
        portholeOverlay?.applyTheme(theme)
        portholeContainer?.setBackgroundColor(theme.palette.backdrop)
        // The dual-ring theme deliberately draws no speed/rpm/gear/status text: those live in
        // the MCU layer. Keep the PC MCU simulation (and the real hardware MCU) above it.
        benchMcu?.visibility = View.VISIBLE
        when (theme.decor) {
            PortholeTheme.Decor.RAY -> {
                // 射线主题没有默认主题那两条波浪；切换时立刻停掉并藏起来。
                portholeWave?.stop()
                portholeWave?.visibility = View.GONE
                portholeDayNight?.stop()
                portholeDayNight?.visibility = View.GONE
                portholeMath?.stop()
                portholeMath?.visibility = View.GONE
                portholeDualRing?.stop()
                portholeDualRing?.visibility = View.GONE
                val ray = ensurePortholeRay(theme)
                ray?.setSongKey(lastPortholeSongKey)
                ray?.visibility = if (portholeMode) View.VISIBLE else View.GONE
                if (portholeMode) ray?.start() else ray?.stop()
            }
            PortholeTheme.Decor.DAY_NIGHT -> {
                portholeWave?.stop()
                portholeWave?.visibility = View.GONE
                portholeRay?.stop()
                portholeRay?.visibility = View.GONE
                portholeMath?.stop()
                portholeMath?.visibility = View.GONE
                portholeDualRing?.stop()
                portholeDualRing?.visibility = View.GONE
                val view = ensurePortholeDayNight(theme)
                view?.visibility = if (portholeMode) View.VISIBLE else View.GONE
                if (portholeMode) view?.start() else view?.stop()
            }
            PortholeTheme.Decor.MATH -> {
                portholeWave?.stop()
                portholeWave?.visibility = View.GONE
                portholeRay?.stop()
                portholeRay?.visibility = View.GONE
                portholeDayNight?.stop()
                portholeDayNight?.visibility = View.GONE
                portholeDualRing?.stop()
                portholeDualRing?.visibility = View.GONE
                val view = ensurePortholeMath(theme)
                view?.setSongKey(lastPortholeSongKey)
                view?.setArtwork(CarPlayMediaKeys.currentArtwork())
                view?.visibility = if (portholeMode) View.VISIBLE else View.GONE
                if (portholeMode) view?.start() else view?.stop()
            }
            PortholeTheme.Decor.DUAL_RING -> {
                portholeWave?.stop()
                portholeWave?.visibility = View.GONE
                portholeRay?.stop()
                portholeRay?.visibility = View.GONE
                portholeDayNight?.stop()
                portholeDayNight?.visibility = View.GONE
                portholeMath?.stop()
                portholeMath?.visibility = View.GONE
                val view = ensurePortholeDualRing(theme)
                view?.applyTheme(theme)
                view?.visibility = if (portholeMode) View.VISIBLE else View.GONE
                if (portholeMode) view?.start() else view?.stop()
            }
            else -> {
                portholeRay?.stop()
                portholeRay?.visibility = View.GONE
                portholeDayNight?.stop()
                portholeDayNight?.visibility = View.GONE
                portholeMath?.stop()
                portholeMath?.visibility = View.GONE
                portholeDualRing?.stop()
                portholeDualRing?.visibility = View.GONE
                val wave = ensurePortholeWave()
                wave?.applyTheme(theme)
                // 换主题时叠加层的律动色会立刻重算；波浪层用目标色跟上，否则要等下一次 1s tick。
                accent?.let { wave?.accentColor = it }
                wave?.visibility = if (portholeMode) View.VISIBLE else View.GONE
                if (portholeMode) wave?.start() else wave?.stop()
            }
        }
    }

    fun setPorthole(on: Boolean): Boolean {
        val navTwinTheme = PortholeTheme.load(context).decor == PortholeTheme.Decor.NAV_TWIN
        portholeMode = on

        // 导航双擎不是取景窗小地图：位置机仍在 2，但 111 走全屏视图，环层单独叠在上面。
        if (on && navTwinTheme) {
            cardWindowScale = PORTHOLE_CARD_SCALE
            applyCardWindowScale()
            turnCardView?.visibility = View.GONE
            etaCardView?.visibility = View.GONE
            portholeOverlay?.visibility = View.GONE
            songView?.setSuppressed(true)
            setBenchMcuVisible(BenchCluster.enabled || simulatedOverlayDisplay)
            applyPortholeTheme()
            navTextureView?.visibility = View.VISIBLE
            ensurePortholeNavTwin(PortholeTheme.load(context))?.let {
                it.visibility = View.VISIBLE
                it.start()
            }
            applyCardWindowVisibility()
            return true
        }

        if (!on) {
            if (navTwinActive) exitNavTwin(reattach = false)
            // 离开取景窗位置时必须把运行时主题一起拆掉：自定义图层和 live 槽位都挂在
            // 根视图上，不拆的话它们会继续压在整机镜像/仅导航的 111 画面上
            // （实测 2026-11-10：右滑回仅导航后主题仪表还留着）。
            if (customThemeActive) detachCustomThemeHost()
            cardWindowScale = 1f
            applyCardWindowScale()
            portholeOverlay?.visibility = View.GONE
            portholeWave?.stop()
            portholeWave?.visibility = View.GONE
            portholeRay?.stop()
            portholeRay?.visibility = View.GONE
            portholeDayNight?.stop()
            portholeDayNight?.visibility = View.GONE
            portholeMath?.stop()
            portholeMath?.visibility = View.GONE
            portholeDualRing?.stop()
            portholeDualRing?.visibility = View.GONE
            detachPortholeSurface()
            // Leaving the porthole must not leave its decorative layers, overlay, animators or
            // cached backdrop Bitmap resident for the rest of the session. They are recreated by
            // applyPortholeTheme() on the next entry.
            releasePortholeDecor()
            return true
        }

        if (navTwinActive) exitNavTwin(reattach = true)
        // 112 卡片流是 480x200 的原尺寸，直接摆在 0,0 会盖住取景窗左上角；取景窗模式下
        // 统一缩到 0.83（=398x166），右边缘正好落在取景窗的 x=400 之前。
        cardWindowScale = PORTHOLE_CARD_SCALE
        applyCardWindowScale()
        applyPortholeLayout()

        // 取景窗视图下不画我们自己的转向卡/ETA 卡：左上角那张卡只来自手机的 112 流，
        // 手机不发就什么都不显示（见 createCardWindow）。
        turnCardView?.visibility = View.GONE
        etaCardView?.visibility = View.GONE
        portholeOverlay?.visibility = View.VISIBLE
        // MCU 背景在进入取景窗时才确保存在：滑动可能第一次就命中这里，
        // 此时 applyClusterMode 的创建分支不会执行。
        setBenchMcuVisible(BenchCluster.enabled || simulatedOverlayDisplay)
        // Attach first: a custom live nav layer needs the porthole container to exist.
        navTextureView?.visibility = View.GONE
        attachPortholeSurface()
        applyPortholeLayout()
        applyPortholeTheme()
        return true
    }

    /** 创建取景窗专属 TextureView，并把 111 的输出面切到它上面。 */
    private fun attachPortholeSurface() {
        val root = rootView ?: return
        if (portholeTextureView == null) {
            val view = TextureView(context).apply { isOpaque = true }
            view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                    // 取景窗和全屏导航视图用同样大的 buffer：解码器把整幅 111 画进来，
                    // 再由视图变换只露出设计稿要的那一块（1:1）。绝不能把 buffer 设成
                    // 取景窗大小——解码器会先把整幅画面缩进取景窗，后面的裁切就全错了。
                    texture.setDefaultBufferSize(panelSize.x, panelSize.y)
                    Log.i(TAG, "porthole surface created buffer=${panelSize.x}x${panelSize.y}")
                    portholeSurface = Surface(texture)
                    portholeOwnsNav = true
                    onPortholeSurface?.invoke(portholeSurface)
                    applyPortholeLayout()
                }

                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                    Log.i(TAG, "porthole surface size: ${width}x${height}")
                    applyPortholeLayout()
                }

                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                    Log.i(TAG, "porthole surface destroyed")
                    portholeSurface?.release()
                    portholeSurface = null
                    // 正常拆除时 detachPortholeSurface 已经把 111 交还全屏视图；如果取景窗
                    // 还是被异常销毁（系统回收 Surface），这里必须自己把 111 还回去，
                    // 否则解码器会一直画到一块已经释放的面上。
                    if (portholeOwnsNav) {
                        portholeOwnsNav = false
                        val nav = navOutputSurface
                        if (nav != null) onNavSurface(nav) else onPortholeSurface?.invoke(null)
                    }
                    return true
                }
            }
            // 圆角外框：和右边 ETA 卡片同一种做法——容器 clipToOutline，内部的
            // TextureView 铺满，圆角之外露出背景（黑底/波浪），视频四角就不会是直角。
            val container = FrameLayout(context).apply {
                // 视频层隐藏时透出的就是这块纯色圆角底；没有它，背景会露出波浪/车机层。
                // 默认主题是纯黑，别的主题可以换成自己的底色。
                setBackgroundColor(PortholeTheme.load(context).palette.backdrop)
                clipChildren = true
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, outline: Outline) {
                        outline.setRoundRect(0, 0, v.width, v.height, PORTHOLE_CORNER_RADIUS_PX)
                    }
                }
            }
            container.addView(view, FrameLayout.LayoutParams(-1, -1))
            root.addView(container, FrameLayout.LayoutParams(1, 1))
            portholeContainer = container
            portholeTextureView = view
            // 取景窗容器是后加的，会盖住更早的 112 导航卡；把卡片重新提到最上层。
            cardContainer?.bringToFront()
        }
    }

    /** 拆掉取景窗，把 111 交还全屏导航视图。 */
    private fun detachPortholeSurface() {
        val view = portholeTextureView ?: return
        // 先把 111 绑回全屏视图，再拆取景窗。反过来会让解码器短暂指向一个即将销毁的
        // Surface；重绑之后才到达的销毁回调还可能把新绑定清掉，切回仅导航就黑了。
        portholeOwnsNav = false
        val nav = navOutputSurface
        if (nav != null) onNavSurface(nav) else onPortholeSurface?.invoke(null)
        portholeSurface?.release()
        portholeSurface = null
        runCatching { rootView?.removeView(portholeContainer ?: view) }
        portholeContainer = null
        portholeTextureView = null
        // setMode 在 portholeMode 为 true 时把全屏视图藏起来了，这里按当前模式恢复。
        navTextureView?.visibility = if (desiredMirror) View.GONE else View.VISIBLE
        Log.i(TAG, "porthole detached: 111 back to nav view (mirror=$desiredMirror)")
    }

    /**
     * Removes the porthole-only decorative views instead of leaving them GONE in the root until
     * the Presentation dies. `PortholeDualRingView` keeps a full-panel ARGB backdrop Bitmap, the
     * math view caches a motif Bitmap, and the overlay can own a running ValueAnimator; all of
     * those are pure waste while the dashboard is in nav/mirror mode. The next
     * applyPortholeTheme() recreates whatever the selected theme needs.
     */
    private fun releasePortholeDecor() {
        val root = rootView
        fun drop(view: View?) {
            if (view == null) return
            runCatching { root?.removeView(view) }
        }
        portholeWave?.stop()
        drop(portholeWave)
        portholeWave = null
        portholeRay?.stop()
        drop(portholeRay)
        portholeRay = null
        portholeDayNight?.stop()
        drop(portholeDayNight)
        portholeDayNight = null
        portholeMath?.stop()
        drop(portholeMath)
        portholeMath = null
        portholeDualRing?.release()
        drop(portholeDualRing)
        portholeDualRing = null
        portholeNavTwin?.stop()
        drop(portholeNavTwin)
        portholeNavTwin = null
        portholeOverlay?.release()
        drop(portholeOverlay)
        portholeOverlay = null
        navTwinActive = false
    }

    /**
     * Custom-theme nav viewport. The porthole TextureView is a real window; its width/height
     * can be changed (the default 750x440 crop is kept only for the native porthole size).
     * A different size means the whole 111 frame fills the requested rectangle, which is what
     * a full-screen map theme needs: dst [0,0,1920,720].
     */
    private fun applyCustomNavRect(x: Float, y: Float, w: Float, h: Float, scale: Float) {
        customNavRect = floatArrayOf(x, y, w, h, scale)
        Log.i(TAG, "custom nav rect=$x,$y ${w}x$h scale=$scale")
        applyCustomNavLayout()
    }

    private fun applyCustomNavLayout() {
        val values = customNavRect ?: return
        val container = portholeContainer ?: return
        val view = portholeTextureView ?: return
        val width = (if (values[2] > 1f) values[2] else PORTHOLE_W.toFloat()).toInt().coerceAtLeast(1)
        val height = (if (values[3] > 1f) values[3] else PORTHOLE_H.toFloat()).toInt().coerceAtLeast(1)
        val params = container.layoutParams as? FrameLayout.LayoutParams
            ?: FrameLayout.LayoutParams(width, height)
        params.width = width
        params.height = height
        params.gravity = Gravity.TOP or Gravity.LEFT
        params.leftMargin = values[0].toInt()
        params.topMargin = values[1].toInt()
        container.layoutParams = params
        container.pivotX = 0f
        container.pivotY = 0f
        container.scaleX = values[4].coerceIn(0.05f, 5f)
        container.scaleY = values[4].coerceIn(0.05f, 5f)
        val nativeCrop = Math.abs(width - PORTHOLE_W) <= 1 && Math.abs(height - PORTHOLE_H) <= 1
        view.setTransform(if (nativeCrop) portholeTransform(PORTHOLE_Y, width, height) else Matrix())
    }

    /** 把取景窗（圆角容器 + 111 视频）摆到设计稿矩形上，并用变换做裁剪。 */
    private fun applyPortholeLayout(forceBuiltIn: Boolean = false) {
        val view = portholeTextureView
        val container = portholeContainer
        if (view == null || container == null) {
            Log.i(TAG, "layout: no porthole view yet")
            return
        }
        if (!forceBuiltIn && customThemeActive && customNavRect != null) {
            applyCustomNavLayout()
            return
        }
        if (forceBuiltIn) {
            // A previous custom theme may have moved/scaled the same container. The
            // built-in layout below rewrites width/height/margins, but transform values
            // must be cleared explicitly as well.
            container.translationX = 0f
            container.translationY = 0f
            container.pivotX = 0f
            container.pivotY = 0f
            container.scaleX = 1f
            container.scaleY = 1f
        }
        val params = container.layoutParams as? FrameLayout.LayoutParams
        if (params == null) {
            Log.i(TAG, "layout: params null cls=${container.layoutParams?.javaClass?.simpleName}")
            return
        }
        if (portholeMode) {
            // 窗口高度始终 460、顶边始终 150。字幕出现时不再把取景窗收成 388/222：
            // 那会让整块画面往下跳（司机反馈“播报时取景窗往下移动”）。字幕条现在
            // 画在取景窗底部之上，是覆盖层，不改变视频的裁切矩形。
            val designHeight = PortholeOverlayView.PORTHOLE_H
            // 顶边固定 150，底边 590（比安全区 610 留 20px）；字幕不再改变这个矩形。
            val designTop = PORTHOLE_Y
            val r = metrics.scaledRect(PORTHOLE_X, designTop, PORTHOLE_W, designHeight)
            // 窗口尺寸严格等于设计稿：设备画布更大也不放大，多出来的地方由背景/MCU 占住。
            params.width = r[2]
            params.height = r[3]
            params.gravity = Gravity.TOP or Gravity.LEFT
            params.leftMargin = r[0]
            params.topMargin = r[1]
            container.layoutParams = params
            view.setTransform(portholeTransform(designTop, r[2], r[3]))
            portholeOverlay?.setMetrics(metrics)
            portholeWave?.setMetrics(metrics)
            portholeDayNight?.setMetrics(metrics)
            portholeMath?.setMetrics(metrics)
            portholeDualRing?.setMetrics(metrics)
            benchMcu?.setMetrics(metrics)
            val withSub = portholeOverlay?.isSubtitleVisible() == true
            Log.i(
                TAG,
                "cluster porthole: rect=${r[0]},${r[1]} ${r[2]}x${r[3]} " +
                    "crop=$PORTHOLE_CROP_X,$designTop ${PORTHOLE_W}x$designHeight " +
                    "panel=${panelSize.x}x${panelSize.y} subtitle=$withSub",
            )
        } else {
            params.width = 1
            params.height = 1
            params.leftMargin = 0
            params.topMargin = 0
            container.layoutParams = params
        }
    }

    /**
     * 取景窗的视图变换：从整幅 111 里裁出设计稿 (PORTHOLE_CROP_X, designTop) 起、
     * [PORTHOLE_W] x [designH] 的一块，1:1 铺满取景窗。
     *
     * TextureView 默认先把整幅解码 buffer 拉伸到视图大小，所以矩阵是在“已铺满视图”
     * 的坐标系上工作的：
     *
     *   scale     = 面板像素 / 取景窗像素   → 把视图内容放大回面板的 1:1 比例
     *   translate = -设计稿裁切原点          → 把要的那块移到视图左上角
     *
     * 旧实现按“流像素 / 设计稿尺寸”缩放再平移，等于先在面板比例上又缩了一次，
     * 750 宽的取景窗只剩 390 宽的画面还被平移出可视区——这就是一直全黑的原因。
     */
    private fun portholeTransform(designTop: Int, viewW: Int, viewH: Int): Matrix {
        val matrix = Matrix()
        if (viewW <= 0 || viewH <= 0 || panelSize.x <= 0 || panelSize.y <= 0) return matrix
        matrix.setScale(
            panelSize.x / viewW.toFloat(),
            panelSize.y / viewH.toFloat(),
        )
        matrix.postTranslate(
            -metrics.x(PORTHOLE_CROP_X.toFloat()),
            -metrics.y(designTop.toFloat()),
        )
        return matrix
    }

    /** 歌曲帧到达时立即刷新取景窗的两行歌词/歌名，不等 ETA tick。 */
    fun setPortholeSong(title: String, artist: String, songKey: String) {
        lastPortholeSongKey = songKey
        portholeRay?.setSongKey(songKey)
        portholeMath?.setSongKey(songKey)
        themeSongCard?.setSong(title, artist)
        if (!portholeMode) return
        val view = portholeOverlay ?: return
        view.setSong(title, artist, songKey)
        // 背景波浪不跟着 800ms 渐变走，歌曲帧一到就直接用目标色。
        portholeWave?.let { wave ->
            val target = view.accentTargetColor()
            if (wave.accentColor != target) wave.accentColor = target
        }
    }

    /**
     * 封面对射线主题着色：只把 Bitmap 交给取色视图，视图不长期持有它。
     * 取景窗还没建立时不用缓存，建立时自己会取当前封面。
     */
    fun setPortholeArtwork(artwork: Bitmap?) {
        portholeRay?.setArtwork(artwork)
        portholeMath?.setArtwork(artwork)
    }

    /** 取景窗模式下的信息栏数据；null 表示没有可用数据（内置信息栏隐藏，主题卡显示占位）。 */
    fun updatePorthole(info: PortholeOverlayView.Info?) {
        lastPortholeInfo = info
        // Do not recreate the overlay after leaving the porthole. Its update path used to add the
        // view back (GONE) on the next song/route tick, which defeated releasePortholeDecor().
        if (!portholeMode) return
        // 自定义主题的到达卡/歌曲卡与内置信息栏吃同一份数据，样式和数值因此不会走样。
        themeEtaCard?.setInfo(info)
        themeSongCard?.setSong(info?.songTitle.orEmpty(), info?.songArtist.orEmpty())
        if (info == null) {
            portholeOverlay?.visibility = View.GONE
            return
        }
        val view = portholeOverlay ?: run {
            val root = rootView ?: return
            PortholeOverlayView(context).apply {
                visibility = View.GONE
                setMetrics(metrics)
                applyTheme(PortholeTheme.load(context))
                onSubtitleRowChanged = { applyPortholeLayout() }
            }.also {
                root.addView(it, FrameLayout.LayoutParams(-1, -1))
                it.bringToFront()
                portholeOverlay = it
                // 信息栏/字幕是后加的覆盖层，112 卡片仍必须压在它上面。
                cardContainer?.bringToFront()
            }
        }
        // 自定义主题不显示内置信息栏/到达卡：applyPortholeTheme() 只在切换时藏一次，
        // 这里每秒一次的刷新曾把它又打开，于是“默认到达信息卡没移除”（2026-11-10 反馈）。
        view.visibility = if (portholeMode && !navTwinActive && !customThemeActive) {
            View.VISIBLE
        } else {
            View.GONE
        }
        view.update(info)
        lastPortholeSongKey = info.songKey
        portholeRay?.setSongKey(info.songKey)
        portholeMath?.setSongKey(info.songKey)
        // 信息栏可能是后创建的；把已经取到的封面主色补给它一次。
        portholeMath?.publishAccent()
        // 用目标色而不是当前动画中间色，否则每秒 tick 正好在渐变开始时会把波浪写回旧色。
        portholeWave?.let { wave ->
            val target = view.accentTargetColor()
            if (wave.accentColor != target) wave.accentColor = target
        }
    }

    fun setMode(mirror: Boolean) {
        // Every content mode switches the same two views. The mirror decoder itself is bound or
        // released by the host according to this mode; FLY_CARD is no exception any more (field
        // 2026-10-25: the driver could not reach the whole-screen mirror at all, by three-finger
        // swipe or by reconnecting, because this used to pin mirrorMode to false). Its
        // instruction-card window is simply hidden while the mirror is up.
        // Remember the request before checking the views: on a cold start the host can ask for
        // nav-only while the TextureViews are still null, and the surface callback below must
        // re-apply this mode rather than the saved setting (which the auto-switch intentionally
        // does not update).
        desiredMirror = mirror
        mirrorMode = mirror
        // The cards follow the mode, not the TextureViews: both surfaces are created
        // asynchronously and either may report first, so card lifecycle must not be behind that
        // check. This is what guarantees the decision is made before the stream is shown.
        if (navOnlyCards) {
            // One pass decides everything: the turn card follows the mode, the ETA card is
            // created in both modes (applyCardsVisibility -> materializeGuidanceCards).
            applyCardsVisibility()
        }
        applyCardWindowVisibility()
        val nav = navTextureView ?: return
        val mirrorView = genericTextureView ?: return
        mirrorView.visibility = if (mirror) View.VISIBLE else View.GONE
        // 取景窗模式下 111 由取景窗那块面承接，全屏视图必须保持隐藏；否则它会在
        // 取景窗周围露出最后一帧（甚至整屏旧画面）。取景窗拆除时再由 detach 恢复。
        nav.visibility = if (mirror || (portholeMode && !navTwinActive)) View.GONE else View.VISIBLE
        Log.i(
            TAG,
            "cluster display mode: ${if (mirror) "mirror(110)" else "nav-only(111)"} " +
                "porthole=$portholeMode",
        )
    }

    /** Hides the placeholder once the phone streams the cluster screen. */
    fun setStreamActive(active: Boolean) {
        waitingLabel?.visibility = if (active) View.GONE else View.VISIBLE
    }

    /**
     * 诊断：把导航流（111）当前这一帧存成 PNG。
     *
     * 日志只有帧率和码率，给不出画面内容——而"仪表上那行提示到底是不是 111 画的"只能看画面
     * 才知道，前面的码率推断已经错过三次。缩到一半再存，一张约 200-400 KB，失败返回 null。
     */
    fun captureNavFrame(directory: java.io.File): java.io.File? {
        val view = navTextureView ?: return null
        if (!view.isAvailable) return null
        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0) return null
        val bitmap = runCatching { view.getBitmap(width / 2, height / 2) }.getOrNull() ?: return null
        val file = java.io.File(directory, "nav-frame-${System.currentTimeMillis()}.png")
        val saved = runCatching {
            java.io.FileOutputStream(file).use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            file
        }.getOrNull()
        bitmap.recycle()
        return saved?.takeIf { it.length() > 0 }
    }

    /**
     * The instruction-card stream came up or went away (FLY_CARD only).
     *
     * The card window is not a fixed part of the dashboard: it exists while the phone is really
     * sending that stream and there is guidance to show, and is destroyed otherwise. That is what
     * keeps an idle dashboard free of a black slab - no stream (or no guidance), no window.
     */
    fun setCardStreamActive(active: Boolean) {
        if (!flyCard) return
        if (cardStreamActive == active) return
        cardStreamActive = active
        updateCardWindow()
    }

    /** Creates or destroys the card window from the stream's state and the guidance data. */
    private fun updateCardWindow() {
        if (!flyCard) return
        val wanted = cardStreamActive && lastTurnGuidance != null
        if (wanted == (cardTextureView != null)) return
        if (wanted) createCardWindow() else destroyCardWindow()
    }

    /**
     * 把 112 卡片视频的深蓝底压成近黑，同时保住白色箭头/文字。
     *
     * 矩阵是“增益 1.5 + 偏移 -60”：卡片底 (17,25,43) → (0,0,4)（近黑），白色文字
     * 255 → 255（钳位），浅灰文字 200 → 240。只作用在卡片这一层，不影响地图/信息栏。
     */
    private fun applyInstructionCardTint(view: TextureView) {
        val matrix = android.graphics.ColorMatrix(
            floatArrayOf(
                1.5f, 0f, 0f, 0f, -60f,
                0f, 1.5f, 0f, 0f, -60f,
                0f, 0f, 1.5f, 0f, -60f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        val paint = Paint().apply {
            colorFilter = android.graphics.ColorMatrixColorFilter(matrix)
        }
        view.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
    }

    /** 取景窗模式下把 112 卡片缩到 0.83，避免它压住取景窗左上角。 */
    private fun applyCardWindowScale() {
        cardContainer?.apply {
            pivotX = 0f
            pivotY = 0f
            scaleX = cardWindowScale
            scaleY = cardWindowScale
        }
    }

    /**
     * Builds the window from the stream's own picture: no background, no rounded mask, no stroke,
     * at the driver's saved position (default: the panel's upper-left corner, see
     * cardWindowParams()). Frames arrive straight from the decoder surface, so one stream pixel is
     * one panel pixel.
     */
    private fun createCardWindow() {
        val root = rootView ?: return
        if (cardTextureView != null) return
        val container = FrameLayout(context)
        container.clipChildren = true
        container.clipToOutline = true
        // 除 bringToFront() 外再给卡片一个很高的 Z：即使自绘卡片/信息栏之后又把自己
        // bringToFront()，ViewGroup 按 Z 排序时 112 卡片仍在最上面（translationZ 无阴影）。
        container.translationZ = CARD_WINDOW_Z
        val cardView = TextureView(context)
        // 112 是 iPhone 自己画的卡片，底色是它视频里的深蓝（不是我们绘制的）。用一层
        // 对比度矩阵把深蓝压到黑、同时保住白色箭头/文字（0.83 缩放后仍然清晰）。
        applyInstructionCardTint(cardView)
        cardView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                Log.i(TAG, "cluster card surface created")
                texture.setDefaultBufferSize(cardStreamWidth, cardStreamHeight)
                cardOutputSurface = Surface(texture).also(onCardSurface)
            }

            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                Log.i(TAG, "cluster card surface destroyed")
                cardOutputSurface?.release()
                cardOutputSurface = null
                onCardSurface(null)
                return true
            }
        }
        cardTextureView = cardView
        cardContainer = container
        container.pivotX = 0f
        container.pivotY = 0f
        applyCardWindowScale()
        // The decoded buffer is larger than the card's real content. Lay the full buffer out with
        // a negative offset inside a container of the crop's size; the container clips the rest.
        container.addView(
            cardView,
            FrameLayout.LayoutParams(cardStreamWidth, cardStreamHeight).apply {
                leftMargin = -cardCropLeft
                topMargin = -cardCropTop
            },
        )
        container.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, CARD_CORNER_RADIUS_PX)
            }
        }
        root.addView(container, cardWindowParams())
        // 112 导航卡必须在最上层，用户把它拖到任何位置都不能被取景窗/信息栏/歌曲卡盖住。
        container.bringToFront()
        applyCardWindowVisibility()
        if (customThemeActive) {
            // The custom spec may have been built before the 112 surface arrived; rebuild its
            // live view map so the card can take its layer order/position.
            applyPortholeTheme()
        }
        Log.i(
            TAG,
            "cluster card window created buffer=${cardStreamWidth}x$cardStreamHeight " +
                "crop=${cardCropLeft},${cardCropTop} ${cardCropWidth}x$cardCropHeight " +
                "at $cardWindowLeft,$cardWindowTop",
        )
    }

    /**
     * The instruction-card window belongs to the navigation picture only.
     *
     * While the whole-screen mirror is up it is HIDDEN, not destroyed: keeping the view and its
     * surface avoids re-negotiating the card stream and rebuilding its decoder when the driver
     * swipes back (field 2026-10-25: the mirror must be reachable from this mode at all).
     */
    private fun applyCardWindowVisibility() {
        if (!flyCard) return
        if (customThemeActive) return
        if (navTwinActive) {
            // Apple 完整导航的 112 卡片是手机画面的一部分，导航双擎不能把它吞掉。
            val visible = !mirrorMode
            cardContainer?.visibility = if (visible) View.VISIBLE else View.GONE
            if (visible) cardContainer?.bringToFront()
            return
        }
        val visible = !mirrorMode
        cardContainer?.visibility = if (visible) View.VISIBLE else View.GONE
        // 导航卡层级固定在最上：每次重新显示都再提一次，避免被后加的取景窗/信息栏压住。
        if (visible) cardContainer?.bringToFront()
    }

    /** Drops the window and its surface; the host releases the decoder with it. */
    private fun destroyCardWindow() {
        val container = cardContainer ?: return
        cardContainer = null
        cardTextureView = null
        (container.parent as? android.view.ViewGroup)?.removeView(container)
        Log.i(TAG, "cluster card window destroyed")
    }

    /**
     * The phone-reported decoded buffer and crop rectangle.
     *
     * The buffer is not resized; only the crop rectangle is shown, and the card window is sized to
     * that crop. Fires from the codec's output format (AndroidMediaSink.onScreenStreamOutputSize).
     */
    fun setCardVideoGeometry(geometry: ScreenStreamGeometry) {
        if (geometry.streamWidth <= 0 || geometry.streamHeight <= 0) return
        if (geometry.cropWidth <= 0 || geometry.cropHeight <= 0) return
        if (geometry.streamWidth == cardStreamWidth &&
            geometry.streamHeight == cardStreamHeight &&
            geometry.cropLeft == cardCropLeft &&
            geometry.cropTop == cardCropTop &&
            geometry.cropWidth == cardCropWidth &&
            geometry.cropHeight == cardCropHeight
        ) {
            return
        }
        cardStreamWidth = geometry.streamWidth
        cardStreamHeight = geometry.streamHeight
        cardCropLeft = geometry.cropLeft
        cardCropTop = geometry.cropTop
        cardCropWidth = geometry.cropWidth
        cardCropHeight = geometry.cropHeight
        cardVideoWidth = cardCropWidth
        cardVideoHeight = cardCropHeight
        val texture = cardTextureView
        if (texture != null) {
            texture.surfaceTexture?.setDefaultBufferSize(cardStreamWidth, cardStreamHeight)
            texture.layoutParams = FrameLayout.LayoutParams(cardStreamWidth, cardStreamHeight).apply {
                leftMargin = -cardCropLeft
                topMargin = -cardCropTop
            }
        }
        cardContainer?.layoutParams = cardWindowParams()
        Log.i(
            TAG,
            "cluster card buffer=${cardStreamWidth}x$cardStreamHeight " +
                "crop=${cardCropLeft},${cardCropTop} ${cardCropWidth}x$cardCropHeight " +
                "window at $cardWindowLeft,$cardWindowTop",
        )
    }

    /** Live card window frame on the panel: left, top, width, height. Null when not created. */
    fun cardWindowRect(): IntArray? {
        val view = cardContainer ?: return null
        return intArrayOf(view.left, view.top, view.width, view.height)
    }

    /** Panel size this presentation was built for, for diagnostics. */
    fun panelSize(): IntArray = intArrayOf(projectionSize.x, projectionSize.y)

    /**
     * Re-applies the saved card-window position to the live window (settings change, B4).
     *
     * The window only exists while the phone streams the card AND there is guidance, so a
     * position change usually arrives with no view at all: the saved coordinates then simply take
     * effect the next time createCardWindow() runs (B7). The clamp runs either way, so an
     * impossible coordinate is corrected in the saved value as soon as it is read.
     */
    fun refreshCardWindowPosition() {
        if (!flyCard) return
        // Built BEFORE the null check on purpose: cardWindowParams() also clamps and silently
        // corrects an out-of-bounds saved value, and that has to keep working while no window
        // exists (a coordinate saved before the first stream must not stay impossible).
        val params = cardWindowParams()
        val view = cardContainer ?: return
        val applied = view.layoutParams as? FrameLayout.LayoutParams
        // The host reaches this from applyClusterTurnOverlay(), which the guidance/ETA tick drives
        // once per second. Assigning layoutParams requests a layout pass of the whole window, and
        // this codebase treats per-second relayout as a bug (see the fronted-card cache in
        // applyCardsVisibility and ClusterEtaCardView's first-show-only bringToFront), so an
        // unchanged position costs nothing here.
        //
        // The comparison is against what the VIEW carries (leftMargin/topMargin/width/height),
        // not against a second copy of those values in fields: one source of truth cannot drift
        // out of sync with the view after a window is created or destroyed. A window that
        // createCardWindow() just added already carries the right params and is skipped too; the
        // first real move is what requests the layout.
        if (applied != null &&
            applied.width == params.width &&
            applied.height == params.height &&
            applied.leftMargin == params.leftMargin &&
            applied.topMargin == params.topMargin
        ) {
            return
        }
        view.layoutParams = params
        Log.i(
            TAG,
            "cluster card window moved to $cardWindowLeft,$cardWindowTop ${params.width}x${params.height}",
        )
    }

    /**
     * The card window's layout: exactly the stream's own picture size, at the driver's saved
     * position.
     *
     * The position is clamped so the window can never leave the dashboard. The driver types plain
     * pixel coordinates relative to the panel's upper-left corner (0..1920 / 0..720) and can
     * easily push the card past an edge, which would leave half of it outside the cluster with no
     * way back. A corrected value is persisted SILENTLY: notifying the settings listener from
     * inside a layout pass would cascade into another apply/refresh round (see
     * AirPlayPersistence.saveClusterCardXSilent).
     */
    private fun cardWindowParams(): FrameLayout.LayoutParams {
        // A panel that has not reported its size yet (getRealSize can hand back 0 while the
        // display is being brought up) is NOT a 0x0 dashboard: clamping against it would collapse
        // every coordinate to 0 and the silent write-back below would permanently overwrite the
        // driver's saved position. Fall back to the panel origin and write NOTHING.
        if (projectionSize.x <= 0 || projectionSize.y <= 0) {
            // Once per window, not once per tick: this path is reached from the per-second
            // overlay refresh as well, and a repeated warning would bury the log.
            if (!cardWindowSizeWarned) {
                cardWindowSizeWarned = true
                Log.w(
                    TAG,
                    "cluster card window: panel size ${projectionSize.x}x${projectionSize.y}, " +
                        "keeping saved position",
                )
            }
            cardWindowLeft = CARD_LEFT_PX
            cardWindowTop = CARD_TOP_PX
            return FrameLayout.LayoutParams(cardVideoWidth, cardVideoHeight, Gravity.TOP or Gravity.LEFT).apply {
                leftMargin = CARD_LEFT_PX
                topMargin = CARD_TOP_PX
            }
        }
        val maxLeft = (projectionSize.x - cardVideoWidth).coerceAtLeast(0)
        val maxTop = (projectionSize.y - cardVideoHeight).coerceAtLeast(0)
        val savedLeft = AirPlayPersistence.loadClusterCardX(context, CARD_WINDOW_CARD_ID)
        val savedTop = AirPlayPersistence.loadClusterCardY(context, CARD_WINDOW_CARD_ID)
        val left = savedLeft.coerceIn(0, maxLeft)
        val top = savedTop.coerceIn(0, maxTop)
        if (left != savedLeft) AirPlayPersistence.saveClusterCardXSilent(context, CARD_WINDOW_CARD_ID, left)
        if (top != savedTop) AirPlayPersistence.saveClusterCardYSilent(context, CARD_WINDOW_CARD_ID, top)
        if (left != cardWindowLeft || top != cardWindowTop) {
            Log.i(
                TAG,
                "cluster card window placed x=$savedLeft->$left y=$savedTop->$top " +
                    "limit=$maxLeft,$maxTop on ${projectionSize.x}x${projectionSize.y}",
            )
        }
        cardWindowLeft = left
        cardWindowTop = top
        return FrameLayout.LayoutParams(cardVideoWidth, cardVideoHeight, Gravity.TOP or Gravity.LEFT).apply {
            leftMargin = left
            topMargin = top
        }
    }

    /** Current display mode (true = whole-screen mirror, false = nav-only), for diagnostics. */
    fun isMirrorMode(): Boolean = mirrorMode

    /**
     * Which guidance cards exist right now, for the dashboard diagnostic log.
     *
     * "exists" matters more than "has data": a card that was never created, or one destroyed when
     * the mode changed, cannot be the glass box the driver sees - and telling those two apart is
     * what decides whether a complaint is about our drawing or about Apple's own view.
     */
    fun cardsSummary(): String =
        "turnCard=${turnCardView != null} etaCard=${etaCardView != null} " +
            "songCard=${songView != null} cardWindow=${cardTextureView != null}"

    /**
     * Window alpha hides the pixels without destroying the TextureView/decoder surface.
     *
     * Do NOT also set the decor view to GONE. Tried on 2026-10-25: after showing the window again
     * the TextureView was re-laid out at the wrong size, the instruction-card picture filled the
     * whole dashboard and the main screen showed our dashboard text. Alpha is the field-proven
     * hand-back and the occasional "car UI only in a corner" case is on the head unit's side.
     */
    fun setMapVisible(visible: Boolean) {
        window?.let { window ->
            val alpha = if (visible) 1f else 0f
            if (window.attributes.alpha != alpha) window.attributes = window.attributes.apply { this.alpha = alpha }
        }
    }

    /**
     * Debug-only FPS badge on the dashboard. Null removes it; the host calls this once per second
     * only while "显示调试信息" is enabled. Black background and white monospace text make it
     * readable over both the map and the mirror without matching any CarPlay content.
     */
    fun setDebugFps(fps: Double?) {
        val root = rootView ?: return
        var view = debugFpsView
        if (view?.parent !== root) {
            view = null
            debugFpsView = null
        }
        if (fps == null) {
            view?.visibility = View.GONE
            return
        }
        val target = view ?: TextView(context).apply {
            setBackgroundColor(Color.BLACK)
            setTextColor(Color.WHITE)
            textSize = 30f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            includeFontPadding = false
            elevation = 24f * context.resources.displayMetrics.density
            translationZ = 1000f
            val padX = (14f * context.resources.displayMetrics.density).toInt()
            val padY = (8f * context.resources.displayMetrics.density).toInt()
            setPadding(padX, padY, padX, padY)
        }.also { created ->
            val density = context.resources.displayMetrics.density
            root.addView(
                created,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.END,
                ).apply {
                    topMargin = (16f * density).toInt()
                    rightMargin = (16f * density).toInt()
                },
            )
            debugFpsView = created
        }
        val text = if (fps.isFinite() && fps >= 0) {
            String.format(Locale.US, "FPS %.1f", fps)
        } else {
            "FPS --"
        }
        if (target.text.toString() != text) target.text = text
        target.visibility = View.VISIBLE
        target.bringToFront()
    }


    /**
     * The window's current frame, in pixels. incall can lay an ext-screen window out as its small
     * preview box at the right of the panel instead of over the whole panel, and the driver's rule
     * is that a small box must never be visible - a blank dashboard is acceptable, a square is not.
     */
    fun windowFrame(): Point {
        val decor = window?.decorView ?: return Point(0, 0)
        return Point(decor.width, decor.height)
    }

    fun setTurnCardOverlay(xPx: Int, yPx: Int, scalePercent: Int) {
        turnCardView?.setLayout(xPx, yPx, scalePercent)
    }

    /**
     * Live refresh of every nav card after a settings change: the turn card re-applies
     * its layout, the absolute-position cards just invalidate and re-read the new
     * (x, y) from persistence in onDraw. Called from the overlay-settings listener so a
     * saved position change is visible on the dashboard immediately.
     */
    fun refreshCardOverlays() {
        turnCardView?.setLayout(
            AirPlayPersistence.loadClusterTurnCardX(context),
            AirPlayPersistence.loadClusterTurnCardY(context),
            AirPlayPersistence.loadClusterCardScalePercent(context, "turn"),
        )
        turnCardView?.setBackgroundAlpha(AirPlayPersistence.loadClusterCardAlpha(context, "turn"))
        // Drop each card's cached position so the next draw reloads the saved coordinates.
        etaCardView?.notifyPositionChanged()
        etaCardView?.setBackgroundAlpha(AirPlayPersistence.loadClusterCardAlpha(context, "eta"))
        // Deliberately outside the nav-only group: the mirror mode shows these as well.
        songView?.notifyPositionChanged()
        // Reload the show/hide switches, then a saved switch change re-applies visibility right away.
        reloadCardVisibilityCache()
        if (navOnlyCards) applyCardsVisibility()
    }

    /** The song now playing. Null clears the card. */
    fun setSong(song: ClusterSong?) {
        songView?.setSong(song)
    }

    /** Album art for the current item; the lyrics card lays it out beside or above the text. */
    fun setSongArtwork(artwork: android.graphics.Bitmap?) {
        songView?.setArtwork(artwork)
    }

    /** A song-card setting changed; the view drops its caches on the next draw. */
    fun notifySongSettingsChanged() {
        songView?.notifyPositionChanged()
    }

    fun setTurnCardGuidance(guidance: ClusterTurnGuidance?) {
        lastTurnGuidance = guidance
        turnCardView?.setGuidance(guidance)
        if (navOnlyCards) applyCardsVisibility()
        // The card window lives only while there is guidance to draw (FLY_CARD).
        updateCardWindow()
    }

    /**
     * Lane guidance from iAP2 0x5204, drawn under our own turn card (MAP_WITH_CUSTOM_CARD).
     * null hides the strip; the FLY_CARD window and the combined stream are untouched.
     */
    fun setLaneGuidance(lanes: BydLaneGuidance?) {
        lastLanes = lanes
        turnCardView?.setLanes(lanes)
    }

    /** Junction view for the self-drawn card; null removes that row and shrinks the card. */
    fun setTurnCardJunction(bitmap: Bitmap?) {
        lastJunction = bitmap
        turnCardView?.setJunctionImage(bitmap)
    }

    fun setEtaGuidance(guidance: ClusterTurnGuidance?) {
        lastEtaGuidance = guidance
        // 自定义主题的到达卡是按默认到达卡样式单独绘制的 ThemeEtaCardView，数据走
        // updatePorthole()；内置到达卡在主题里一律不参与，直接整张移除。
        if (customThemeActive) {
            destroyEtaCard()
            return
        }
        etaCardView?.setGuidance(guidance)
        if (navOnlyCards) applyCardsVisibility()
    }

    /**
     * Nav-only card group visibility, per mode.
     *
     * Whole-screen mirror: the TURN card must not exist (A1) - the mirror picture is Apple's own,
     * with its own instruction box - but the ETA card stays (A2). This used to destroy the whole
     * guidance group, which is why the arrival card vanished the moment the driver mirrored the
     * phone.
     *
     * Nav-only: the driver's switches alone decide which cards exist. DiLink 5.1
     * (navOnlyCards=false) never reaches this branch.
     *
     * Each view's setGuidance() is the authority on its own visibility (turn needs guidance,
     * ETA needs arrival/remaining), so here we only create/destroy by mode and switch, and raise
     * the visible cards above the video layer. Never force a card VISIBLE here without the same
     * data check - that would show an empty glass block.
     */
    private fun applyCardsVisibility() {
        if (!navOnlyCards) return
        // FLY_CARD is no longer exempt: it shows the ETA and song cards too (2026-10-25). Its
        // turn card is simply never created (see materializeGuidanceCards), so the turn branch
        // below is a no-op there.
        // 整机镜像与取景窗都不显示自绘卡：镜像时我们的卡会压在手机画面上，
        // 取景窗里左上角那张卡只认手机的 112 流，不许我们自己再画一张。
        if (mirrorMode || portholeMode) {
            destroyTurnCard()
            // 取景窗里左上角那张卡只认手机的 112 流；自定义主题则用自己绘制的到达卡，
            // 内置到达卡在两种情况下都不该存在。
            destroyEtaCard()
            return
        }
        // Not the navigation stream: only the turn card goes away, everything else stays.
        if (!turnCardVisibleCache) destroyTurnCard()
        if (cardVisibleCache["eta"] == false) {
            etaCardView?.let {
                cardsRoot?.removeView(it)
                frontedCards = emptyList()
            }
            etaCardView = null
        }
        materializeGuidanceCards()
        // bringToFront() triggers a layout pass, so only re-front when the visible set
        // actually changed — guidance ticks every second and must not requestLayout each time.
        val visibleNow = listOfNotNull(turnCardView, etaCardView)
            .filter { it.visibility == View.VISIBLE }
        if (visibleNow != frontedCards) {
            frontedCards = visibleNow
            visibleNow.forEach { it.bringToFront() }
            // 112 卡片仍要压在这些自绘卡片之上。
            cardContainer?.bringToFront()
        }
    }

    /** Reload the cached show/hide switches after a settings change. */
    private fun reloadCardVisibilityCache() {
        cardVisibleCache = mapOf(
            "eta" to AirPlayPersistence.loadClusterCardVisible(context, "eta"),
        )
        turnCardVisibleCache = AirPlayPersistence.loadClusterTurnCardVisible(context)
    }

    companion object {
        /** 取景窗在面板上的落点（左侧）。 */
        const val PORTHOLE_X = 400
        /**
         * 111 流里的裁切起点，**独立于落点**。
         *
         * 窗口可以摆在面板左边，但它显示的仍然是流里 (785,150) 起的那一块——裁的是画面还是
         * 同一块，换位置只挪窗口，不换内容。785 = 车标 x(1160) − 窗宽/2。
         */
        const val PORTHOLE_CROP_X = 785
        const val PORTHOLE_Y = 150
        const val PORTHOLE_W = 750
        /** 与 [PortholeOverlayView.PORTHOLE_H] 一致：底边 590，距安全区下沿 610 留 20px。 */
        const val PORTHOLE_H = 440
        /** 取景窗圆角，和右侧 ETA 卡片的视觉半径一致（面板像素）。 */
        const val PORTHOLE_CORNER_RADIUS_PX = 30f
        /** 取景窗模式下 112 卡片的缩放：480x200 → 398x166，右边缘落在取景窗 x=400 之前。 */
        const val PORTHOLE_CARD_SCALE = 0.83f
        const val TAG = "DiPlay-Cluster"

        // —— Instruction-card window (FLY_CARD), in panel pixels ——
        // Sized to the stream's own picture, so the card arrives and is shown 1:1. No background,
        // mask or stroke of ours: an earlier build drew a translucent panel of a guessed size and
        // stretched the picture into it, which is what looked like a large black slab. The card
        // display is advertised with a card-shaped canvas (CarPlayClusterDisplay.CARD_*), so the
        // picture itself is compact.
        //
        // The POSITION is the driver's to set (settings: "Apple 完整导航卡位置", B1-B5); these two
        // constants are only the panel-origin default, kept as the initial value of the live
        // coordinates before the first layout resolves them from persistence.
        const val CARD_LEFT_PX = 0
        const val CARD_TOP_PX = 0
        /** Rounded corners of the card window, in panel pixels (≈24 % of the card height). */
        const val CARD_CORNER_RADIUS_PX = 48f
        /** 112 导航卡的 Z 层级：保证它永远在取景窗/信息栏/自绘卡片/歌曲卡之上。 */
        const val CARD_WINDOW_Z = 100f
        /** Above cards/themes; the car's real MCU layer is above every app pixel. */
        const val BENCH_MCU_Z = 3000f
        /**
         * The card window's key in the shared per-card position store (AirPlayPersistence).
         *
         * "apple" = Apple's OWN full-navigation card, which the phone paints into the stream, as
         * opposed to the cards we draw ourselves ("turn", "eta", "song"). It only has a position -
         * no size (the stream drives it) and no show switch (there is nothing to disable when the
         * phone is sending that stream).
         */
        const val CARD_WINDOW_CARD_ID = "apple"

        /**
         * Builds a dashboard with no self-drawn card at all.
         *
         * Used once, on 2026-10-24, to settle where a dashboard element comes from: with this true
         * the log shows cards=turnCard=false etaCard=false songCard=false, and the glass turn box
         * in the middle was still on screen - so it is Apple's, from its own instrument-cluster
         * view. Kept as a switch because the same question will come up again; false is the
         * normal build.
         */
        const val CARDS_DISABLED = false

        /** A verified 5.1 profile chooses its layer explicitly; other firmware keeps PR #5 behavior. */
        fun findDisplay(context: Context, theme: DiLink51ClusterLayout.Theme = DiLink51ClusterLayout.theme(context)): Display? {
            val displayManager = context.getSystemService(DisplayManager::class.java) ?: return null
            if (!DiLink51ClusterLayout.supported()) {
                // Generic path (Changan Feiyu OS, etc.): accept any non-default display, exactly like
                // AMap Companion's findClusterDisplay(). Prefer the presentation category first.
                displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
                    .firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }?.let { return it }
                displayManager.displays
                    .firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }?.let { return it }
                return null
            }
            val displays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            val name = DiLink51ClusterLayout.displayName(
                displays.map { it.name }, android.os.Build.FINGERPRINT, theme,
            ) ?: return null
            return displays.firstOrNull { it.name == name }?.takeIf {
                val size = sizeOf(it)
                DiLink51ClusterLayout.plan(size.x, size.y, theme) != null
            }
        }

        fun describeDisplays(context: Context): String =
            context.getSystemService(DisplayManager::class.java)?.displays
                ?.joinToString { "${it.displayId}:${it.name}" }.orEmpty()

        fun sizeOf(display: Display): Point = Point().also {
            @Suppress("DEPRECATION")
            display.getRealSize(it)
        }
    }
}

/** Shading belongs to DiPlay's map only; no stock cluster window is changed or covered outside the side card. */
private class InstrumentContrastView(
    context: Context,
    private val plan: DiLink51ClusterLayout.Plan,
    private val background: Int,
) : View(context) {
    private val paint = Paint()
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = plan.left.toFloat(); val top = plan.top.toFloat()
        val right = left + plan.width; val bottom = top + plan.height
        fun fade(x0: Float, y0: Float, x1: Float, y1: Float, start: Int, end: Int,
                 l: Float, t: Float, r: Float, b: Float) {
            paint.shader = LinearGradient(x0, y0, x1, y1, start, end, Shader.TileMode.CLAMP)
            canvas.drawRect(l, t, r, b, paint)
        }
        val clear = background and 0x00ffffff
        val soft = (background and 0x00ffffff) or (210 shl 24)
        if (plan.fullMap) {
            fade(left, top, left, top + 64, background, clear, left, top, right, top + 64)
            fade(left, bottom - 48, left, bottom, clear, background, left, bottom - 48, right, bottom)
            // The trip/menu readouts occupy the outer panels; keep the route and marker in the clear center.
            fade(left, top, left + 460, top, soft, clear, left, top, left + 460, bottom)
            fade(right - 460, top, right, top, clear, soft, right - 460, top, right, bottom)
        }
        paint.shader = null
    }
}
