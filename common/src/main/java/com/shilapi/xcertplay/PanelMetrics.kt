package com.shilapi.xcertplay

import android.graphics.Matrix

/**
 * 设计稿坐标（1920x720）到实际画布的映射。
 *
 * 车机面板是 1920x720，模拟副屏却常常是 1920x1080（ROM 只给固定预设）。所有取景窗/信息栏/
 * MCU 的坐标都按设计稿写死，绘制前统一过这里换算，就不必维护两套常量。
 *
 * 规则：**等比缩放 + 底部对齐**。宽度按画布宽/1920，高度同样比例，
 * 剩出来的高度贴在顶边（顶部留白）——底部是饰板位置，内容必须贴住它。
 */
internal data class PanelMetrics(val width: Int, val height: Int) {

    /** 设计稿宽度。 */
    private val designWidth = 1920f
    private val designHeight = 720f

    /** 1560x585 之类的画布按宽度算比例；1024x600 这类极端值也不会把内容挤扁。 */
    val scale: Float get() = if (width <= 0) 1f else width / designWidth

    /** 设计稿的 720 高换算到画布上的高度。 */
    val designHeightPx: Float get() = designHeight * scale

    /**
     * 设计坐标原点在画布上的 y 偏移。
     *
     * 画布比设计稿高（1080 vs 720）时，多出来的高度放在顶部，这样 y=610 的底边仍然贴在
     * 画布拉伸后的同一相对位置之下，底部不会因为居中而空出一条。
     */
    val offsetY: Float get() = (height - designHeightPx).coerceAtLeast(0f)

    fun x(designX: Float): Float = designX * scale

    fun y(designY: Float): Float = offsetY + designY * scale

    fun w(designW: Float): Float = designW * scale

    fun h(designH: Float): Float = designH * scale

    /** 给 TextureView 用的视图变换：把流坐标映射到实际画布坐标。 */
    fun textureMatrix(cropX: Int, cropY: Int): Matrix = Matrix().apply {
        setScale(scale, scale)
        postTranslate(-cropX * scale, -cropY * scale + offsetY)
    }

    fun scaledRect(designX: Int, designY: Int, designW: Int, designH: Int): IntArray =
        intArrayOf(
            (designX * scale).toInt(),
            (offsetY + designY * scale).toInt(),
            (designW * scale).toInt(),
            (designH * scale).toInt(),
        )
}
