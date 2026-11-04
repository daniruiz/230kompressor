package com.drasite.mercedesobd

/** Pixel geometry shared by the chart grid and its overlaid footer controls. */
internal class DashboardLayout(
    width: Float,
    height: Float,
    menuSize: Float,
    linkWidth: Float = 0f,
    minimumFooterHeight: Float = 0f,
) {
    val scaleX = width / WIDTH
    val footerHeight = maxOf(menuSize, minimumFooterHeight)
        .coerceAtMost(height - 1f)
    val contentHeight = height - footerHeight
    val contentScaleY = contentHeight / CONTENT_HEIGHT
    val footerContentWidth = (width - menuSize - linkWidth).coerceAtLeast(0f)

    companion object {
        const val FOOTER_CONTROL_DP = 32
        const val INDICATOR_HEIGHT_DP = 8
        const val WIDTH = 1200f
        const val HEIGHT = 680f
        const val CONTENT_HEIGHT = 621f
        const val FOOTER_HEIGHT = HEIGHT - CONTENT_HEIGHT
    }
}

/** Undo the grid's non-uniform scaling without moving the text's anchor. */
internal class CanvasTextTransform(scaleX: Float, scaleY: Float, fontScale: Float) {
    val correctionX = fontScale / scaleX
    val correctionY = fontScale / scaleY

    fun availableWidth(logicalWidth: Float): Float = logicalWidth / correctionX
}
