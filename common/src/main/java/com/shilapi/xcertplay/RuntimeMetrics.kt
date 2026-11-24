package com.shilapi.xcertplay

import android.app.ActivityManager
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Debug
import android.os.Process
import java.io.File
import kotlin.math.roundToInt

/**
 * Best-effort device load sampler for the diagnostics overlay.
 *
 * Everything here is optional: a vendor head unit may hide `/proc`, cpufreq, thermal or GPU sysfs
 * files, so a missing value is reported as null and the UI prints "--" instead of failing the
 * whole panel. The sample runs once per second only while the debug overlay is enabled.
 */
internal class RuntimeMetrics(context: Context) {
    data class Snapshot(
        val cpuPercent: Int?,
        val appCpuPercent: Int?,
        val mainCpuPercent: Int?,
        val videoCpuPercent: Int?,
        val touchCpuPercent: Int?,
        val cpuFreqMhz: Int?,
        val maxTempC: Int?,
        val cpuTempC: Int?,
        val gpuTempC: Int?,
        val gpuPercent: Int?,
        val gpuFreqMhz: Int?,
        val wifiRssiDbm: Int?,
        val wifiLinkMbps: Int?,
        val wifiInterface: String?,
        val netRxMbps: Double?,
        val netTxMbps: Double?,
        val freeMemoryMb: Long?,
        val pssMb: Long?,
    )

    private data class CpuTicks(val total: Long, val idle: Long)
    private data class ThreadTicks(val main: Long?, val video: Long?, val touch: Long?)
    private data class Thermal(val maxC: Int?, val cpuC: Int?, val gpuC: Int?)
    private data class NetCounters(val interfaceName: String, val rx: Long, val tx: Long)

    private val appContext = context.applicationContext
    private val activityManager =
        appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    private val wifiManager =
        appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    // Process.myTid() is the Linux TID. Thread.id is a JVM-level id on some ART builds and made
    // /proc/self/task/<id>/stat miss the main thread (the first build always printed main=--%).
    private val mainThreadId = Process.myTid()
    private val memoryInfo = ActivityManager.MemoryInfo()
    private val debugMemoryInfo = Debug.MemoryInfo()

    private var lastCpu: CpuTicks? = null
    private var lastProcessTicks: Long? = null
    private var lastThreadTicks: ThreadTicks? = null
    private var lastNet: NetCounters? = null
    private var lastNetAtNs = 0L

    /** Drops the previous delta sample; the first sample after a resume reports no percentages. */
    fun reset() {
        lastCpu = null
        lastProcessTicks = null
        lastThreadTicks = null
        lastNet = null
        lastNetAtNs = 0L
    }

    fun sample(nowNs: Long = System.nanoTime()): Snapshot {
        val cpuNow = readCpuTicks()
        val processNow = readStatTicks(File("/proc/self/stat"))
        val threadsNow = readThreadTicks()
        val previousCpu = lastCpu
        val totalDelta = if (cpuNow != null && previousCpu != null) {
            (cpuNow.total - previousCpu.total).coerceAtLeast(0L)
        } else {
            0L
        }
        val cpuPercent = cpuUsage(cpuNow)
        val appCpuPercent = threadUsage(processNow, lastProcessTicks, totalDelta)
        val mainCpuPercent = threadUsage(threadsNow?.main, lastThreadTicks?.main, totalDelta)
        val videoCpuPercent = threadUsage(threadsNow?.video, lastThreadTicks?.video, totalDelta)
        val touchCpuPercent = threadUsage(threadsNow?.touch, lastThreadTicks?.touch, totalDelta)
        val netNow = readNetCounters()
        val netRxMbps = netRate(netNow?.rx, lastNet?.rx, nowNs)
        val netTxMbps = netRate(netNow?.tx, lastNet?.tx, nowNs)

        lastCpu = cpuNow
        lastProcessTicks = processNow
        lastThreadTicks = threadsNow
        lastNet = netNow
        lastNetAtNs = nowNs

        val thermal = readThermal()
        val freeMb = readFreeMemoryMb()
        val pssMb = readPssMb()
        return Snapshot(
            cpuPercent = cpuPercent,
            appCpuPercent = appCpuPercent,
            mainCpuPercent = mainCpuPercent,
            videoCpuPercent = videoCpuPercent,
            touchCpuPercent = touchCpuPercent,
            cpuFreqMhz = readCpuFreqMhz(),
            maxTempC = thermal.maxC,
            cpuTempC = thermal.cpuC,
            gpuTempC = thermal.gpuC,
            gpuPercent = readGpuPercent(),
            gpuFreqMhz = readGpuFreqMhz(),
            wifiRssiDbm = readWifiRssi(),
            wifiLinkMbps = readWifiLinkMbps(),
            wifiInterface = netNow?.interfaceName,
            netRxMbps = netRxMbps,
            netTxMbps = netTxMbps,
            freeMemoryMb = freeMb,
            pssMb = pssMb,
        )
    }

    private fun cpuUsage(now: CpuTicks?): Int? {
        val previous = lastCpu ?: return null
        if (now == null) return null
        val total = now.total - previous.total
        val idle = now.idle - previous.idle
        if (total <= 0L) return null
        return (((total - idle).coerceAtLeast(0L) * 100.0) / total).roundToInt().coerceIn(0, 100)
    }

    private fun threadUsage(now: Long?, previous: Long?, totalDelta: Long): Int? {
        if (previous == null || now == null || totalDelta <= 0L) return null
        val delta = now - previous
        if (delta < 0L) return null
        return ((delta * 100.0) / totalDelta).roundToInt().coerceIn(0, 100)
    }

    private fun readCpuTicks(): CpuTicks? = runCatching {
        val line = File("/proc/stat").useLines { lines ->
            lines.firstOrNull { it.startsWith("cpu ") }
        } ?: return@runCatching null
        val values = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        if (values.size < 4) return@runCatching null
        val total = values.sum()
        val idle = values[3] + (values.getOrNull(4) ?: 0L)
        CpuTicks(total, idle)
    }.getOrNull()

    private fun readStatTicks(file: File): Long? = runCatching {
        if (!file.isFile) return@runCatching null
        val text = file.readText()
        val left = text.indexOf('(')
        val right = text.lastIndexOf(')')
        if (left < 0 || right <= left) return@runCatching null
        val fields = text.substring(right + 1).trim().split(Regex("\\s+"))
        if (fields.size < 13) return@runCatching null
        (fields[11].toLongOrNull() ?: return@runCatching null) +
            (fields[12].toLongOrNull() ?: return@runCatching null)
    }.getOrNull()

    private fun readThreadTicks(): ThreadTicks? {
        val main = readStatTicks(File("/proc/self/task/$mainThreadId/stat"))
        var video = 0L
        var touch = 0L
        var seenVideo = false
        var seenTouch = false
        val tasks = runCatching { File("/proc/self/task").listFiles() }.getOrNull() ?: return null
        for (task in tasks) {
            if (!task.isDirectory) continue
            val text = runCatching { File(task, "stat").readText() }.getOrNull() ?: continue
            val left = text.indexOf('(')
            val right = text.lastIndexOf(')')
            if (left < 0 || right <= left) continue
            val comm = text.substring(left + 1, right)
            val fields = text.substring(right + 1).trim().split(Regex("\\s+"))
            if (fields.size < 13) continue
            val ticks = (fields[11].toLongOrNull() ?: continue) +
                (fields[12].toLongOrNull() ?: continue)
            when {
                comm == "carplay-video" || comm.startsWith("carplay-video") -> {
                    video += ticks
                    seenVideo = true
                }
                comm == "carplay-touch" -> {
                    touch += ticks
                    seenTouch = true
                }
            }
        }
        return ThreadTicks(
            main = main,
            video = if (seenVideo) video else null,
            touch = if (seenTouch) touch else null,
        )
    }

    private fun readCpuFreqMhz(): Int? {
        var highest = 0
        val cores = Runtime.getRuntime().availableProcessors().coerceIn(1, 32)
        for (core in 0 until cores) {
            for (name in listOf("scaling_cur_freq", "cpuinfo_cur_freq")) {
                val khz = runCatching {
                    File("/sys/devices/system/cpu/cpu$core/cpufreq/$name").readText().trim().toLong()
                }.getOrNull() ?: continue
                if (khz > 0) highest = maxOf(highest, (khz / 1000).toInt())
            }
        }
        return highest.takeIf { it > 0 }
    }

    private fun readThermal(): Thermal {
        var maxC: Int? = null
        var cpuC: Int? = null
        var gpuC: Int? = null
        val zones = runCatching { File("/sys/class/thermal").listFiles() }.getOrNull() ?: return Thermal(null, null, null)
        for (zone in zones) {
            if (!zone.isDirectory) continue
            val type = runCatching { File(zone, "type").readText().trim().lowercase() }.getOrNull() ?: ""
            val raw = runCatching { File(zone, "temp").readText().trim().toLong() }.getOrNull() ?: continue
            val celsius = (if (raw > 1000L) raw / 1000 else raw).toInt()
            if (celsius !in -40..150) continue
            maxC = maxC?.let { maxOf(it, celsius) } ?: celsius
            if (type.contains("cpu") || type.contains("soc") || type.contains("cluster")) {
                cpuC = cpuC?.let { maxOf(it, celsius) } ?: celsius
            }
            if (type.contains("gpu") || type.contains("mali")) {
                gpuC = gpuC?.let { maxOf(it, celsius) } ?: celsius
            }
        }
        return Thermal(maxC, cpuC, gpuC)
    }

    private fun readGpuPercent(): Int? {
        val candidates = listOf(
            "/sys/class/kgsl/kgsl-3d0/gpubusy",
            "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
            "/sys/class/misc/mali0/device/utilisation",
            "/sys/kernel/ged/hal/gpu_utilization",
            "/sys/module/ged/parameters/gpu_loading",
        )
        for (path in candidates) {
            val text = runCatching { File(path).readText() }.getOrNull() ?: continue
            val values = Regex("-?\\d+(?:\\.\\d+)?").findAll(text)
                .mapNotNull { it.value.toDoubleOrNull() }.toList()
            if (values.isEmpty()) continue
            val percent = when {
                path.endsWith("gpubusy") && values.size >= 2 && values[1] > 0 ->
                    values[0] * 100.0 / values[1]
                values[0] in 0.0..100.0 -> values[0]
                else -> continue
            }
            return percent.roundToInt().coerceIn(0, 100)
        }
        return null
    }

    private fun readGpuFreqMhz(): Int? {
        val candidates = listOf(
            "/sys/class/kgsl/kgsl-3d0/gpuclk",
            "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",
            "/sys/class/misc/mali0/device/cur_freq",
            "/sys/kernel/ged/hal/current_freq",
        )
        for (path in candidates) {
            val raw = runCatching { File(path).readText().trim().toLong() }.getOrNull() ?: continue
            if (raw <= 0L) continue
            val mhz = when {
                raw > 100_000_000L -> raw / 1_000_000L
                raw > 100_000L -> raw / 1_000L
                else -> raw
            }
            if (mhz in 1L..4000L) return mhz.toInt()
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun readWifiRssi(): Int? {
        val fromWifi = runCatching { wifiManager?.connectionInfo?.rssi }.getOrNull()
        if (fromWifi != null && fromWifi in -127..-1) return fromWifi
        // Some P2P-only stacks leave WifiManager without a station connection; fall back to proc.
        val lines = runCatching { File("/proc/net/wireless").readLines() }.getOrNull() ?: return null
        for (line in lines.drop(1)) {
            val iface = line.substringBefore(':').trim()
            if (!iface.contains("p2p") && !iface.startsWith("wlan")) continue
            val fields = line.substringAfter(':').trim().split(Regex("\\s+"))
            val signal = fields.getOrNull(3)?.trimEnd('.')?.toIntOrNull()
            if (signal != null && signal in -127..-1) return signal
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun readWifiLinkMbps(): Int? {
        val fromWifi = runCatching { wifiManager?.connectionInfo?.linkSpeed }.getOrNull()
        if (fromWifi != null && fromWifi > 0) return fromWifi
        for (iface in NETWORK_INTERFACES) {
            val speed = runCatching { File("/sys/class/net/$iface/speed").readText().trim().toInt() }.getOrNull()
            if (speed != null && speed > 0) return speed
        }
        return null
    }

    private fun readNetCounters(): NetCounters? {
        for (iface in NETWORK_INTERFACES) {
            val rx = readLongFile("/sys/class/net/$iface/statistics/rx_bytes") ?: continue
            val tx = readLongFile("/sys/class/net/$iface/statistics/tx_bytes") ?: 0L
            return NetCounters(iface, rx, tx)
        }
        return null
    }

    private fun netRate(now: Long?, previous: Long?, nowNs: Long): Double? {
        if (now == null || previous == null || lastNetAtNs == 0L || now < previous) return null
        val elapsedNs = nowNs - lastNetAtNs
        if (elapsedNs <= 0L) return null
        return ((now - previous) * 8.0 * 1_000_000_000.0 / elapsedNs / 1_000_000.0)
    }

    private fun readFreeMemoryMb(): Long? = runCatching {
        val manager = activityManager ?: return@runCatching null
        manager.getMemoryInfo(memoryInfo)
        memoryInfo.availMem / (1024L * 1024L)
    }.getOrNull()

    private fun readPssMb(): Long? = runCatching {
        Debug.getMemoryInfo(debugMemoryInfo)
        (debugMemoryInfo.totalPss / 1024L).coerceAtLeast(0L)
    }.getOrNull()

    private fun readLongFile(path: String): Long? =
        runCatching { File(path).readText().trim().toLong() }.getOrNull()

    private companion object {
        val NETWORK_INTERFACES = listOf("p2p0", "p2p-wlan0-0", "wlan0", "wlan1")
    }
}
