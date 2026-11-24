package com.shilapi.xcertplay.daynight

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log

/**
 * 车灯 → CarPlay 日夜模式的轮询器。
 *
 * 数据源只有车机属性 0x11400E10（1=大灯开，0=关，-1=读不到）。该属性在本机只在
 * 进程 "com.wt.n2048" 里可读（参考 lampstatus 测试 APK 把整个应用放在该进程），所以本类
 * 绑定同 APK 的 [LampReaderService]（android:process="com.wt.n2048"）通过 AIDL 取值。
 * Settings.System "car_mode" 在本机语义相反（1=灯全关，0=有灯亮）；大灯属性读不到时用它兜底。
 *
 * 行为：
 *  - 1000 ms 读一次；
 *  - 只在昼夜结果变化时回调，不每秒覆盖；
 *  - 启动后第一次读到有效状态就立刻同步；
 *  - 读不到（lamp=-1）时保持当前主题，不改。
 *
 * 线程：所有回调和日志都在主线程。进程级单例，Activity 重建不会重建绑定。
 */
object CarLampMonitor {
    private const val POLL_MS = 1000L
    private const val REBIND_MS = 5000L

    data class State(
        val lamp: Int,
        val carMode: Int,
        val night: Boolean?,
        val source: String,
        val probeBound: Boolean,
        val propertyConnected: Boolean,
        val manager: String,
        val managerName: String,
        val createCarMethod: String,
        val failures: Int,
        val error: String,
    )

    /** Callbacks to the controller must stay on the main thread; the binder polls must not. */
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pollThread: HandlerThread? = null
    private var pollHandler: Handler? = null

    private var appContext: Context? = null
    private var logger: ((String) -> Unit)? = null
    private var listener: ((Boolean) -> Unit)? = null

    private var running = false
    private var lastState: State? = null
    private var lastRawLamp: Int? = null
    private var lastRawCarMode: Int? = null

    @Volatile private var reader: ILampReader? = null
    @Volatile private var binding = false
    @Volatile private var bindRequested = false
    @Volatile private var probeBound = false
    private var lastBindAt = 0L

    /** 最近一次真正应用的昼夜；null 表示还没读到有效状态。 */
    @Volatile
    var lastNight: Boolean? = null
        private set

    /**
     * Bench-only headlamp override. It affects the 日夜 porthole theme only; it deliberately
     * does not change CarPlay's own appearance, so a PC bench can preview both palettes without
     * touching the phone's day/night mode.
     */
    @Volatile
    private var benchNight: Boolean? = null

    /** 供取景窗主题读取：bench 临时值优先，其次才是真实车灯。 */
    val effectiveNight: Boolean?
        get() = benchNight ?: lastNight

    /** adb bench entry point: true=night, false=day, null=auto (follow the real lamp). */
    fun setBenchNight(value: Boolean?) {
        benchNight = value
        val label = value?.let { if (it) "night" else "day" } ?: "auto"
        log("Lamp: bench override=$label")
        Log.i("DiPlay-Bench", "lamp override=$label")
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (!running) {
                runCatching { appContext?.unbindService(this) }
                return
            }
            val r = service?.let { ILampReader.Stub.asInterface(it) }
            reader = r
            binding = false
            probeBound = r != null
            log("Lamp: probe connected process=com.wt.n2048")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            reader = null
            binding = false
            probeBound = false
            bindRequested = false
            if (running) log("Lamp: probe disconnected")
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            pollOnce()
            if (running) pollHandler?.postDelayed(this, POLL_MS)
        }
    }

    /** 幂等启动；重复调用只更新 logger/listener。 */
    fun start(
        context: Context,
        logger: (String) -> Unit,
        listener: (Boolean) -> Unit,
    ) {
        val app = context.applicationContext
        appContext = app
        this.logger = logger
        this.listener = listener
        if (running) return
        running = true
        lastState = null
        lastRawLamp = null
        lastRawCarMode = null
        // lastNight deliberately survives stop/start: a rebuilt Activity applies the last known
        // lamp state immediately instead of flashing the system theme for one second.
        log("Lamp: monitor start (poll=${POLL_MS}ms, probe=com.wt.n2048)")
        bindProbe(app, force = true)
        // Binding is cheap; the actual AIDL reads can block for seconds (getGearRaw appeared in
        // the main-thread UI stall), so poll them on a worker and publish the result to main.
        val thread = HandlerThread("car-lamp-monitor").apply { start() }
        pollThread = thread
        val next = Handler(thread.looper)
        pollHandler = next
        next.post(tick)
    }

    /** 停止轮询并解绑；车机属性连接随服务进程结束。 */
    fun stop() {
        if (!running) return
        running = false
        pollHandler?.removeCallbacks(tick)
        pollHandler = null
        pollThread?.quitSafely()
        pollThread = null
        log("Lamp: monitor stop")
        val ctx = appContext
        if (ctx != null && bindRequested) {
            runCatching { ctx.unbindService(connection) }
        }
        reader = null
        binding = false
        probeBound = false
        bindRequested = false
        listener = null
        logger = null
        lastState = null
    }

    private fun bindProbe(ctx: Context, force: Boolean) {
        if (reader != null || binding) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastBindAt < REBIND_MS) return
        lastBindAt = now
        binding = true
        val ok = try {
            ctx.bindService(
                Intent(ctx, LampReaderService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        } catch (e: Throwable) {
            log("Lamp: probe bind failed ${e.javaClass.simpleName}")
            false
        }
        if (ok) {
            bindRequested = true
            log("Lamp: probe bind requested")
        } else {
            binding = false
            log("Lamp: probe bind returned false")
        }
    }

    private fun pollOnce() {
        val ctx = appContext ?: return
        val r = reader
        if (r == null) {
            bindProbe(ctx, force = false)
            publish(
                State(
                    lamp = -1,
                    carMode = -1,
                    night = null,
                    source = "无信号",
                    probeBound = false,
                    propertyConnected = false,
                    manager = "",
                    managerName = "",
                    createCarMethod = "",
                    failures = 0,
                    error = "probe not bound",
                ),
            )
            return
        }

        val lamp: Int
        val carMode: Int
        val propertyConnected: Boolean
        val manager: String
        val managerName: String
        val createCarMethod: String
        val failures: Int
        val error: String
        try {
            lamp = r.getLamp()
            carMode = r.getCarMode()
            propertyConnected = r.isConnected()
            manager = r.getManagerClass() ?: ""
            managerName = r.getManagerName() ?: ""
            createCarMethod = r.getCreateCarMethod() ?: ""
            failures = r.getConnectFailures()
            error = r.getLastError() ?: ""
        } catch (e: RemoteException) {
            log("Lamp: probe read failed ${e.javaClass.simpleName}")
            reader = null
            binding = false
            probeBound = false
            bindRequested = false
            publish(
                State(
                    lamp = -1,
                    carMode = -1,
                    night = null,
                    source = "无信号",
                    probeBound = false,
                    propertyConnected = false,
                    manager = "",
                    managerName = "",
                    createCarMethod = "",
                    failures = 0,
                    error = "remote read failed: ${e.javaClass.simpleName}",
                ),
            )
            return
        }

        // Primary source is the headlamp property. This unit has no usable signal for stretches
        // (the report shows "no headlamp signal" while the car is in a garage with the lights on),
        // so fall back to car_mode, whose polarity on this unit is documented and inverted:
        // car_mode 0 = a lamp is on, 1 = all lamps off.
        val night = when {
            lamp == 1 -> true
            lamp == 0 -> false
            carMode == 0 -> true
            carMode == 1 -> false
            else -> null
        }
        val sourceLabel = when {
            lamp == 0 || lamp == 1 -> "车机属性"
            carMode == 0 || carMode == 1 -> "car_mode兜底"
            else -> "无信号"
        }
        publish(
            State(
                lamp = lamp,
                carMode = carMode,
                night = night,
                source = sourceLabel,
                probeBound = true,
                propertyConnected = propertyConnected,
                manager = manager,
                managerName = managerName,
                createCarMethod = createCarMethod,
                failures = failures,
                error = error,
            ),
        )
    }

    private fun publish(state: State) {
        mainHandler.post { publishOnMain(state) }
    }

    private fun publishOnMain(state: State) {
        if (!running) return
        val rawChanged = state.lamp != lastRawLamp || state.carMode != lastRawCarMode
        lastRawLamp = state.lamp
        lastRawCarMode = state.carMode
        val stateChanged = state != lastState
        if (stateChanged || rawChanged) {
            lastState = state
            val derived = state.night?.let { if (it) "dark" else "light" } ?: "unknown"
            log(
                "Lamp: state lamp=${state.lamp} car_mode=${state.carMode} night=$derived" +
                    " source=${state.source} probeBound=${state.probeBound}" +
                    " propertyConnected=${state.propertyConnected}" +
                    " manager=${state.manager.ifEmpty { "-" }} managerName=${state.managerName.ifEmpty { "-" }}" +
                    " createCar=${state.createCarMethod.ifEmpty { "-" }} failures=${state.failures}" +
                    " error=${state.error.ifEmpty { "-" }}",
            )
        }

        val night = state.night
        if (night == null) {
            if (stateChanged) log("Lamp: no headlamp signal, keeping current theme")
        } else {
            // One sample is deliberate: the driver expects the theme to follow the switch
            // immediately. The reported dark/light alternation was the driver toggling the lights
            // to test it, not a signal that needs filtering.
            val before = lastNight
            applyNight(night)
            if (before != lastNight) {
                log(
                    "Lamp: switch applied ${if (night) "dark" else "light"}" +
                        " (was ${before?.let { if (it) "dark" else "light" } ?: "unknown"})" +
                        " lamp=${state.lamp} car_mode=${state.carMode} source=${state.source}",
                )
            }
        }
    }

    private fun applyNight(night: Boolean) {
        if (night == lastNight) return
        lastNight = night
        log("Lamp: apply CarPlay ${if (night) "dark" else "light"}")
        listener?.invoke(night)
    }

    private fun log(message: String) {
        logger?.invoke(message)
    }
}
