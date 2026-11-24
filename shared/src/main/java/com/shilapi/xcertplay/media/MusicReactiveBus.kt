package com.shilapi.xcertplay.media

import android.os.SystemClock
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * 从音乐 PCM 里提取的轻量律动信号，供取景窗“射线”主题使用。
 *
 * 只在音乐通道的 PCM 写入路径上采样几百个点：算 RMS 和一个一阶低通的低频包络，
 * 不做 FFT、不分配内存。导航播报、通话音等其他通道不参与，避免它们把射线带飞。
 *
 * UI 侧用 [levelAt] / [pulseAt] 读取；数据超过 1.5 秒没更新就当作静音，
 * 这样暂停或断开后辉光会自然收掉，而不是冻在最后一帧。
 */
object MusicReactiveBus {

    private const val SPECTRUM_BANDS = 16
    private const val SPECTRUM_WINDOW = 512

    /** 当前音乐响度，已经归一化到 0..1。 */
    @Volatile
    var level: Float = 0f
        private set

    /** 当前低频（底鼓/贝斯）包络，0..1。 */
    @Volatile
    var bass: Float = 0f
        private set

    /** 累计节拍数；每次检测到一下低频重音加一。 */
    @Volatile
    var beatCount: Long = 0L
        private set

    /** 16 段频谱，0..1；只由音乐 PCM 更新。 */
    private val spectrum = FloatArray(SPECTRUM_BANDS)
    private val spectrumLastAt = LongArray(SPECTRUM_BANDS)

    fun spectrumSize(): Int = SPECTRUM_BANDS

    fun bandAt(index: Int, now: Long = SystemClock.elapsedRealtime()): Float {
        if (index !in 0 until SPECTRUM_BANDS || !activeAt(now)) return 0f
        return spectrum[index]
    }

    /** 最后一次收到音乐 PCM 的时刻（elapsedRealtime）。 */
    @Volatile
    var lastPcmAt: Long = 0L
        private set

    /** CarPlay 报告的播放状态；用于暂停时立刻停掉射线，而不是等 PCM 超时。 */
    @Volatile
    private var playing = false
    /** 播放状态是否已经由 CarPlay 的 NowPlaying 回调报告过。 */
    @Volatile
    private var playbackKnown = false
    /** 这次会话是否真正播放过；用来区分“初始 false”和“用户暂停”。 */
    @Volatile
    private var everPlayed = false

    /** 上一帧的低频包络，用来识别“突然起来”的鼓点，而不是持续的低频。 */
    private var previousBass = 0f
    @Volatile
    private var lastBeatAt = 0L

    /**
     * 分析一帧音乐 PCM。只读前 [channels] 个声道中的左声道，按 [length] 均匀抽样。
     *
     * 由 [AndroidMediaSink] 的音乐写入路径调用；不能阻塞，也不能分配数组。
     */
    fun analyzePcm(data: ByteArray, offset: Int, length: Int, channels: Int) {
        if (channels <= 0 || length < 2 * channels) return
        val frameBytes = 2 * channels
        val frames = length / frameBytes
        // 每帧最多看 600 个点：20ms 的包够用时就是逐帧，长缓冲会自动降采样。
        val step = maxOf(1, frames / 600)
        var frame = 0
        var sum = 0.0
        var lowSum = 0.0
        var low = 0f
        var count = 0
        while (frame < frames) {
            val index = offset + frame * frameBytes
            if (index + 1 >= data.size) break
            val sample = ((data[index + 1].toInt() shl 8) or (data[index].toInt() and 0xFF)).toShort().toFloat() / 32768f
            sum += (sample * sample).toDouble()
            // 一阶低通约 500-600 Hz，跟着底鼓和贝斯走，不去追人声齿音。
            low += (sample - low) * 0.08f
            lowSum += (low * low).toDouble()
            count++
            frame += step
        }
        if (count == 0) return
        val now = SystemClock.elapsedRealtime()
        val rms = sqrt(sum / count).toFloat()
        val bassNow = sqrt(lowSum / count).toFloat()
        level = (rms * 3.2f).coerceIn(0f, 1f)
        bass = (bassNow * 6.0f).coerceIn(0f, 1f)
        // 用“低频上升量”而不是“低频绝对值”判断鼓点：持续贝斯不会每帧都触发，
        // 只有一下明显的起音才加一拍。280ms 的硬间隔把最快节奏压在 ~3.6 Hz，
        // 避免把连续低频误判成高速鼓点（那会让射线看起来一直在冲刺）。
        val onset = bass - previousBass
        previousBass = bass
        if (bass > 0.10f && onset > 0.08f && now - lastBeatAt >= 280L) {
            lastBeatAt = now
            beatCount++
        }
        updateSpectrum(data, offset, length, channels, frames, now)
        lastPcmAt = now
    }

    /**
     * 16 段 Goertzel 频谱：只取左声道连续 512 帧，不做整段缓存。
     * 对数频率 55 Hz..8 kHz；每段独立衰减，避免柱子瞬间塌掉。
     */
    private fun updateSpectrum(
        data: ByteArray,
        offset: Int,
        length: Int,
        channels: Int,
        frames: Int,
        now: Long,
    ) {
        val count = minOf(frames, SPECTRUM_WINDOW)
        if (count < 32) return
        val frameBytes = 2 * channels
        for (band in 0 until SPECTRUM_BANDS) {
            val freq = 55.0 * Math.pow(8000.0 / 55.0, band / (SPECTRUM_BANDS - 1.0))
            val omega = 2.0 * Math.PI * freq / 44100.0
            val coeff = 2.0 * Math.cos(omega)
            var q1 = 0.0
            var q2 = 0.0
            var index = offset
            for (n in 0 until count) {
                val sample = (((data[index + 1].toInt() shl 8) or
                    (data[index].toInt() and 0xFF)).toShort().toFloat() / 32768f)
                val q0 = coeff * q1 - q2 + sample
                q2 = q1
                q1 = q0
                index += frameBytes
            }
            val power = q1 * q1 + q2 * q2 - coeff * q1 * q2
            val magnitude = if (power > 0.0) Math.sqrt(power) / count else 0.0
            val target = (magnitude * 14.0).toFloat().coerceIn(0f, 1f)
            // 快攻慢放：新峰值立即上去，旧值按 0.72 衰减。
            spectrumLastAt[band] = now
            spectrum[band] = maxOf(target, spectrum[band] * 0.72f)
        }
    }

    /** CarPlay 播放/暂停；暂停时立即让射线停下来。 */
    fun setPlaying(value: Boolean) {        if (value) everPlayed = true
        playing = value
        playbackKnown = true
    }

    /** 音乐是否真的在走：CarPlay 报告在播，且 1.5 秒内还有 PCM。 */
    fun activeAt(now: Long = SystemClock.elapsedRealtime()): Boolean {
        if (lastPcmAt == 0L || now - lastPcmAt > 1_500L) return false
        if (!playbackKnown) return true
        if (playing) return true
        // 初始 NowPlaying 可能先报 false、随后 PCM 已经在流；还没有真正播放过时
        // 以 PCM 为准，避免首次播放没有回调就把射线冻住。
        return !everPlayed
    }

    /** 静音/停止时调用，避免 UI 继续拿旧包络。 */
    fun reset() {
        level = 0f
        bass = 0f
        previousBass = 0f
        playing = false
        playbackKnown = false
        everPlayed = false
        lastBeatAt = 0L
        lastPcmAt = 0L
        for (i in spectrum.indices) {
            spectrum[i] = 0f
            spectrumLastAt[i] = 0L
        }
    }

    /** 响度；暂停或超过 1.5 秒没有新 PCM 就返回 0。 */
    fun levelAt(now: Long = SystemClock.elapsedRealtime()): Float =
        if (!activeAt(now)) 0f else level

    /**
     * 节拍脉冲，0..1。每次重音从 1 开始，按指数衰减；暂停时立即归零。
     */
    fun pulseAt(now: Long = SystemClock.elapsedRealtime()): Float {
        if (!activeAt(now) || lastBeatAt == 0L) return 0f
        val age = (now - lastBeatAt).coerceAtLeast(0L)
        // 170ms 的衰减让每一下重音之间有明显的暗-亮轮廓；260ms 时连续鼓点会糊成常亮。
        return exp(-age / 170f).toFloat().coerceIn(0f, 1f)
    }
}
