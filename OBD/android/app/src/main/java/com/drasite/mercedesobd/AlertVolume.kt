package com.drasite.mercedesobd

/** Demo never reads or writes the user's media volume. Normal alerts borrow it. */
internal class AlertVolume(
    private val current: () -> Int,
    private val maximum: () -> Int,
    private val set: (Int) -> Unit,
) {
    private var previous: Int? = null
    fun begin(demo: Boolean) {
        if (demo || previous != null) return
        previous = current()
        set(maximum())
    }
    fun restore() {
        val saved = previous ?: return
        set(saved)
        previous = null
    }
}
