package com.drasite.mercedesobd

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView

/** Shared native dialog shell. Typography inherits the system theme. */
internal class DialogPanel(val activity: Activity, title: String, description: String) {
    val dialog = Dialog(activity)
    val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    val actions = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
    val subtitle = text(description, 13f, MUTED)

    init {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        val header = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        val heading = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(title, 21f, Color.WHITE, true))
            addView(subtitle)
        }
        header.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(icon(R.drawable.ic_close, tr("Cerrar", "Close")) { dialog.dismiss() })
        root.addView(header, LinearLayout.LayoutParams(-1, -2))
        root.addView(divider(), LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(8) })
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            topMargin = dp(12); bottomMargin = dp(12)
        })
        root.addView(divider(), LinearLayout.LayoutParams(-1, dp(1)))
        root.addView(actions, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(4) })
        dialog.setContentView(root)
        dialog.setCanceledOnTouchOutside(true)
    }

    fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    fun text(value: String, size: Float = 15f, color: Int = Color.WHITE, bold: Boolean = false) =
        TextView(activity).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    fun background(color: Int, radius: Int = 12) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun divider() = View(activity).apply { setBackgroundColor(Color.rgb(40, 59, 81)) }

    fun icon(resource: Int, label: String, color: Int = MUTED, action: () -> Unit) = ImageButton(activity).apply {
        setImageResource(resource)
        imageTintList = ColorStateList.valueOf(color)
        contentDescription = label
        tooltipText = label
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        setPadding(dp(12), dp(12), dp(12), dp(12))
        val ripple = TypedValue()
        activity.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)
        setBackgroundResource(ripple.resourceId)
        setOnClickListener { action() }
    }

    fun button(resource: Int, label: String, color: Int, action: () -> Unit) = Button(activity).apply {
        text = label
        textSize = 14f
        isAllCaps = false
        setTextColor(color)
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(12), 0, dp(12), 0)
        background = this@DialogPanel.background(Color.rgb(24, 43, 62))
        val icon = activity.getDrawable(resource)?.mutate()?.apply { setTint(color) }
        setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null)
        compoundDrawablePadding = dp(8)
        setOnClickListener { action() }
    }

    fun show(): Dialog {
        dialog.show()
        val bounds = activity.window.decorView
        val metrics = activity.resources.displayMetrics
        val width = if (bounds.width > 0) bounds.width else metrics.widthPixels
        val height = if (bounds.height > 0) bounds.height else metrics.heightPixels
        dialog.window?.apply {
            setBackgroundDrawable(background(Color.rgb(12, 23, 38), 20))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(.65f)
            setLayout(minOf(dp(680), width - dp(32)), minOf(dp(480), height - dp(48)))
        }
        return dialog
    }

    companion object {
        val CYAN = Color.rgb(87, 216, 237)
        val MUTED = Color.rgb(154, 174, 200)
        val RED = Color.rgb(255, 112, 112)
        val GREEN = Color.rgb(69, 237, 203)
        val AMBER = Color.rgb(255, 210, 122)
    }
}

internal fun View.available(enabled: Boolean) {
    isEnabled = enabled
    alpha = if (enabled) 1f else .35f
}
