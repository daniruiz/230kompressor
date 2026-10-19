package com.drasite.mercedesobd

/** Pixel geometry shared by the chart grid and its overlaid footer action button. */
internal class DashboardLayout(width: Float, height: Float, menuSize: Float) {
    val scaleX = width / WIDTH
    val footerHeight = maxOf(height * FOOTER_HEIGHT / HEIGHT, menuSize).coerceAtMost(height - 1f)
    val contentHeight = height - footerHeight
    val contentScaleY = contentHeight / CONTENT_HEIGHT
    val footerTextWidth = ((width - menuSize) / scaleX - 16f).coerceAtLeast(0f)

    companion object {
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
