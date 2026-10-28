package com.drasite.mercedesobd

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Color
import android.text.TextUtils
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.CheckedTextView
import android.widget.LinearLayout
import android.widget.ListView
import java.io.File
import java.text.DateFormat
import java.util.Date

internal class LogsDialog(
    private val activity: Activity,
    private val files: List<File>,
    private val export: (List<String>) -> Unit,
    private val share: (List<String>) -> Unit,
    private val delete: (List<String>) -> Unit,
) {
    private val panel = DialogPanel(activity, tr("Registros", "Logs"), tr("Selecciona archivos CSV o JSONL", "Select CSV or JSONL files"))
    private val selected = BooleanArray(files.size)
    private val list = ListView(activity).apply { choiceMode = ListView.CHOICE_MODE_MULTIPLE }
    private val all = CheckBox(activity).apply { text = tr("Todos", "All"); textSize = 14f }
    private val count = panel.text("", 13f, DialogPanel.MUTED)
    private val exportButton = panel.icon(R.drawable.ic_export, tr("Exportar", "Export"), DialogPanel.CYAN) { selectedAction(export) }
    private val shareButton = panel.icon(R.drawable.ic_share, tr("Compartir", "Share"), DialogPanel.CYAN) { selectedAction(share) }
    private val deleteButton = panel.icon(R.drawable.ic_delete, tr("Borrar", "Delete"), DialogPanel.RED) { confirmDelete() }
    private var updating = false
    private var confirmation: AlertDialog? = null

    init {
        if (files.isEmpty()) {
            panel.content.addView(panel.text(tr("Todavía no hay registros", "No logs yet"), 17f, DialogPanel.MUTED).apply {
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-1, -1))
        } else {
            val dates = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            val labels = files.map { "${it.extension.uppercase()} · ${dates.format(Date(it.lastModified()))} · ${Formatter.formatFileSize(activity, it.length())}\n${it.name}" }
            list.adapter = object : ArrayAdapter<String>(activity, android.R.layout.simple_list_item_multiple_choice, labels) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                    (super.getView(position, convertView, parent) as CheckedTextView).apply {
                        textSize = 14f
                        setTextColor(Color.WHITE)
                        setPadding(panel.dp(4), panel.dp(10), panel.dp(8), panel.dp(10))
                        maxLines = 3
                        ellipsize = TextUtils.TruncateAt.END
                    }
            }
            list.setOnItemClickListener { _, _, position, _ ->
                selected[position] = list.isItemChecked(position)
                updateSelection()
            }
            panel.content.addView(list, LinearLayout.LayoutParams(-1, -1))
        }
        panel.actions.addView(all)
        panel.actions.addView(count, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = panel.dp(8) })
        panel.actions.addView(exportButton)
        panel.actions.addView(shareButton)
        panel.actions.addView(deleteButton)
        all.setOnCheckedChangeListener { _, checked ->
            if (!updating) {
                selected.indices.forEach { selected[it] = checked; list.setItemChecked(it, checked) }
                updateSelection()
            }
        }
        panel.dialog.setOnDismissListener { confirmation?.dismiss() }
        updateSelection()
    }

    fun show(): Dialog = panel.show()

    private fun names() = files.filterIndexed { i, _ -> selected[i] }.map { it.name }

    private fun updateSelection() {
        val number = selected.count { it }
        count.text = "$number / ${files.size}"
        updating = true
        all.isChecked = files.isNotEmpty() && number == files.size
        all.isEnabled = files.isNotEmpty()
        updating = false
        listOf(exportButton, shareButton, deleteButton).forEach { it.available(number > 0) }
    }

    private fun selectedAction(action: (List<String>) -> Unit) {
        val names = names()
        if (names.isEmpty()) return
        panel.dialog.dismiss()
        action(names)
    }

    private fun confirmDelete() {
        val names = names()
        if (names.isEmpty()) return
        confirmation = AlertDialog.Builder(activity)
            .setTitle(tr("¿Borrar ${names.size} archivo(s)?", "Delete ${names.size} file(s)?"))
            .setMessage(names.joinToString("\n"))
            .setPositiveButton(tr("Borrar", "Delete")) { _, _ ->
                panel.dialog.dismiss()
                delete(names)
            }
            .setNegativeButton(tr("Cancelar", "Cancel"), null).show()
    }
}
