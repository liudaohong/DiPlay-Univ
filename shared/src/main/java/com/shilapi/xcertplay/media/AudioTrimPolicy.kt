package com.shilapi.xcertplay.media

/**
 * 连接方式决定音频时钟策略。
 *
 * 2026-11-22 用户实测：同一个 App、同一套设置，
 *
 * - **WiFi Direct**（车机做 GO，链路抖动大）：0.2.27 及以前经常卡顿、丢音、失真；
 *   0.2.28 引入的“真实水位 + 欠载反向修正”明显更稳，必须保留。
 * - **车载热点 / 现有 WiFi**（链路好）：同样的激进修正会持续小幅调整播放速率，
 *   听感是“忽快忽慢”，比旧版更频繁。
 *
 * 所以按连接方式分开：热点走 0.2.25 的旧算法（水位含未解码包、无欠载反向修正、
 * 默认 1000ms 缓冲），WiFi Direct 保持当前算法，一行不改。
 */
object AudioTrimPolicy {
    /**
     * true = 热点 / 现有 WiFi，走 0.2.25 的旧缓冲与时钟模型；
     * false = WiFi Direct，走 0.2.28 之后的模型。
     */
    @Volatile
    var legacyBufferModel: Boolean = false
        private set

    /** Called once the wireless backend is known, before any audio track is built. */
    fun update(isWifiDirect: Boolean) {
        legacyBufferModel = !isWifiDirect
    }
}
