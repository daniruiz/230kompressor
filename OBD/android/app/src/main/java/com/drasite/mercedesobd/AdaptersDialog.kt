package com.drasite.mercedesobd

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckedTextView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import java.util.Locale

/** Classic Bluetooth discovery and bonding. Android owns the PIN/confirmation UI. */
@SuppressLint("MissingPermission")
internal class AdaptersDialog(
    private val activity: Activity,
    selectedAddress: String?,
    private val isSessionActive: () -> Boolean,
    private val requestPermissions: (Array<String>, () -> Unit) -> Unit,
    private val requestEnable: (() -> Unit) -> Unit,
    private val onSelected: (BluetoothDevice) -> Unit,
) {
    private val panel = DialogPanel(activity, tr("Adaptadores OBD", "OBD adapters"),
        tr("Bluetooth clásico · ELM327 / OBDLink", "Classic Bluetooth · ELM327 / OBDLink"))
    private val handler = Handler(Looper.getMainLooper())
    private val devices = LinkedHashMap<String, BluetoothDevice>()
    private val rows = ArrayList<BluetoothDevice>()
    private val labels = ArrayList<String>()
    private var selection = selectedAddress
    private var registered = false
    private var closed = false
    private var searching = false
    private var ownsDiscovery = false
    private var permissionPending = false
    private var deniedPermissions = emptyArray<String>()
    private var locationRequired = false
    private var pairingAddress: String? = null
    private var message: String? = null
    private var messageIsError = false
    private var sessionWasActive = isSessionActive()
    private val pairingTimeout = Runnable {
        if (live()) {
            val device = pairingAddress?.let { devices[it] }
            try {
                if (device?.bondState == BluetoothDevice.BOND_NONE) {
                    pairingAddress = null
                    showMessage(tr("El emparejamiento no se ha completado. Vuelve a intentarlo.", "Pairing did not complete. Try again."), true)
                }
            } catch (_: SecurityException) {
                pairingAddress = null
            }
            refresh()
        }
    }

    private val state = panel.text("", 14f, DialogPanel.MUTED)
    private val status = panel.text("", 13f, DialogPanel.MUTED)
    private val enable = panel.button(R.drawable.ic_bluetooth, tr("Activar Bluetooth", "Enable Bluetooth"), DialogPanel.CYAN) { enableOrSettings() }
    private val search = panel.button(R.drawable.ic_refresh, tr("Buscar", "Search"), DialogPanel.CYAN) { searchOrStop() }
    private val select = panel.button(R.drawable.ic_bluetooth, tr("Seleccionar", "Select"), DialogPanel.GREEN) { selectOrPair() }
    private val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
        isIndeterminate = true
        indeterminateTintList = ColorStateList.valueOf(DialogPanel.CYAN)
        visibility = View.GONE
    }
    private val empty = panel.text("", 15f, DialogPanel.MUTED).apply { gravity = Gravity.CENTER }
    private val list = ListView(activity).apply { choiceMode = ListView.CHOICE_MODE_SINGLE }
    private val listAdapter = object : ArrayAdapter<String>(activity, android.R.layout.simple_list_item_single_choice, labels) {
        override fun hasStableIds() = true

        override fun getItemId(position: Int): Long = rows.getOrNull(position)?.let(::deviceId) ?: -1L

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            (super.getView(position, convertView, parent) as CheckedTextView).apply {
                textSize = 14f
                setTextColor(Color.WHITE)
                setPadding(panel.dp(4), panel.dp(8), panel.dp(8), panel.dp(8))
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
            }
    }

    private val sessionWatcher = object : Runnable {
        override fun run() {
            if (!live()) return
            val active = isSessionActive()
            if (active != sessionWasActive) {
                sessionWasActive = active
                if (active && ownsDiscovery) cancelSearch()
                refresh()
            }
            handler.postDelayed(this, 500)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!live() || intent == null) return
            try {
                when (intent.action) {
                    BluetoothAdapter.ACTION_STATE_CHANGED -> {
                        showMessage(null)
                        if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) != BluetoothAdapter.STATE_ON) {
                            searching = false
                            ownsDiscovery = false
                            pairingAddress = null
                            devices.clear()
                        }
                        refresh()
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_STARTED -> if (ownsDiscovery) {
                        searching = true
                        refresh()
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> if (ownsDiscovery) {
                        searching = false
                        ownsDiscovery = false
                        showMessage(tr("Búsqueda finalizada. Selecciona tu adaptador.", "Search finished. Select your adapter."))
                        refresh()
                    }
                    BluetoothDevice.ACTION_FOUND -> if (ownsDiscovery && hasConnectPermission()) {
                        deviceFrom(intent)?.let { remember(it) }
                        refresh()
                    }
                    BluetoothDevice.ACTION_NAME_CHANGED -> if (hasConnectPermission()) {
                        deviceFrom(intent)?.let { if (devices.containsKey(it.address)) remember(it) }
                        refresh()
                    }
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> if (hasConnectPermission()) {
                        deviceFrom(intent)?.let { device ->
                            remember(device)
                            if (pairingAddress == device.address && device.bondState != BluetoothDevice.BOND_BONDING) {
                                pairingAddress = null
                                handler.removeCallbacks(pairingTimeout)
                                if (device.bondState == BluetoothDevice.BOND_BONDED)
                                    showMessage(tr("Emparejado. Pulsa Seleccionar para usarlo.", "Paired. Tap Select to use it."))
                                else showMessage(tr("Emparejamiento cancelado o fallido. Puedes volver a intentarlo.", "Pairing cancelled or failed. You can try again."), true)
                            }
                        }
                        refresh()
                    }
                }
            } catch (e: SecurityException) {
                showMessage(tr("Se necesita permiso para acceder a Bluetooth", "Bluetooth permission is required"), true)
                refresh()
            }
        }
    }

    init {
        val top = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(state, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(enable, LinearLayout.LayoutParams(-2, panel.dp(44)))
        panel.content.addView(top, LinearLayout.LayoutParams(-1, -2))
        panel.content.addView(status, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = panel.dp(6) })
        panel.content.addView(progress, LinearLayout.LayoutParams(-1, panel.dp(4)))
        list.adapter = listAdapter
        val frame = FrameLayout(activity).apply {
            addView(list, FrameLayout.LayoutParams(-1, -1))
            addView(empty, FrameLayout.LayoutParams(-1, -1))
        }
        list.emptyView = empty
        panel.content.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        panel.actions.addView(search, LinearLayout.LayoutParams(0, panel.dp(48), 1f).apply { marginEnd = panel.dp(8) })
        panel.actions.addView(select, LinearLayout.LayoutParams(0, panel.dp(48), 1f))
        list.setOnItemClickListener { _, _, _, id ->
            selection = rows.firstOrNull { deviceId(it) == id }?.address
            showMessage(null)
            refresh()
        }
        panel.dialog.setOnDismissListener {
            cancelSearch()
            closed = true
            handler.removeCallbacksAndMessages(null)
            if (registered) {
                try {
                    activity.unregisterReceiver(receiver)
                } catch (_: IllegalArgumentException) {
                    // The activity may already be releasing its registered receivers.
                }
                registered = false
            }
        }
    }

    fun show(): Dialog {
        val dialog = panel.show()
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_NAME_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        // Bluetooth broadcasts originate from a privileged system app, not always
        // the system UID. NOT_EXPORTED would miss some devices' discovery events.
        try {
            if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else activity.registerReceiver(receiver, filter)
            registered = true
        } catch (e: RuntimeException) {
            showMessage(tr("No se pudo preparar la búsqueda Bluetooth: ${e.message}", "Could not prepare Bluetooth discovery: ${e.message}"), true)
        }
        refresh()
        handler.post(sessionWatcher)
        // Run after the host has stored the dialog returned by show().
        handler.post { if (live() && !hasConnectPermission()) withPermissions(connectPermissions()) { refresh() } }
        return dialog
    }

    fun dismiss() = panel.dialog.dismiss()

    private fun live() = !closed && panel.dialog.isShowing && !activity.isFinishing && !activity.isDestroyed

    private fun adapter(): BluetoothAdapter? = activity.getSystemService(BluetoothManager::class.java)?.adapter

    private fun granted(permission: String) = activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun connectPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31)
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyArray()

    private fun scanPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31)
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasConnectPermission() = connectPermissions().all(::granted)

    private fun withPermissions(permissions: Array<String>, action: () -> Unit) {
        if (!live() || permissionPending) return
        val missing = permissions.filterNot(::granted).toTypedArray()
        if (missing.isEmpty()) {
            action()
            return
        }
        permissionPending = true
        refresh()
        requestPermissions(missing) {
            if (live()) {
                permissionPending = false
                deniedPermissions = missing.filterNot(::granted).toTypedArray()
                if (deniedPermissions.isEmpty()) {
                    showMessage(null)
                    action()
                } else showMessage(tr("Permiso denegado. Puedes concederlo desde Permisos.", "Permission denied. You can grant it from Permissions."), true)
                refresh()
            }
        }
    }

    private fun locationEnabled(): Boolean {
        val manager = activity.getSystemService(LocationManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= 28) manager.isLocationEnabled
        else manager.isProviderEnabled(LocationManager.GPS_PROVIDER) || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun showMessage(text: String?, error: Boolean = false) {
        message = text
        messageIsError = error
    }

    private fun openSettings(action: String, data: Uri? = null) {
        try {
            activity.startActivity(Intent(action, data))
        } catch (e: RuntimeException) {
            showMessage(tr("No se pudieron abrir los ajustes: ${e.message}", "Could not open settings: ${e.message}"), true)
            refresh()
        }
    }

    private fun enableOrSettings() {
        when {
            deniedPermissions.any { !granted(it) } -> openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}"))
            locationRequired -> openSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            else -> withPermissions(connectPermissions()) {
                if (adapter()?.isEnabled == false) requestEnable {
                    if (live()) {
                        // RESULT_OK can precede STATE_ON; the state broadcast finishes it.
                        showMessage(null)
                        refresh()
                    }
                } else refresh()
            }
        }
    }

    private fun remember(device: BluetoothDevice) {
        // This dashboard uses Bluetooth Classic SPP. BLE-only devices cannot connect.
        if (device.type != BluetoothDevice.DEVICE_TYPE_LE) devices[device.address] = device
    }

    private fun deviceId(device: BluetoothDevice): Long = device.address.replace(":", "").toLong(16)

    @Suppress("DEPRECATION")
    private fun deviceFrom(intent: Intent): BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33)
        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else intent.getParcelableExtra<Parcelable>(BluetoothDevice.EXTRA_DEVICE) as? BluetoothDevice

    fun refresh() {
        if (!live()) return
        try {
            val bluetooth = adapter()
            val permitted = hasConnectPermission()
            val bluetoothState = if (bluetooth != null && permitted) bluetooth.state else BluetoothAdapter.STATE_OFF
            val enabled = bluetoothState == BluetoothAdapter.STATE_ON
            val changingState = bluetoothState == BluetoothAdapter.STATE_TURNING_ON || bluetoothState == BluetoothAdapter.STATE_TURNING_OFF
            deniedPermissions = deniedPermissions.filterNot(::granted).toTypedArray()
            if (locationRequired && locationEnabled()) locationRequired = false
            if (enabled) bluetooth!!.bondedDevices.forEach(::remember)
            else devices.clear()
            val paired = pairingAddress?.let { devices[it] }
            // createBond() is asynchronous: it can return before BOND_BONDING.
            // Keep a pending request until a state broadcast or timeout reports failure.
            if (paired?.bondState == BluetoothDevice.BOND_BONDED) {
                pairingAddress = null
                handler.removeCallbacks(pairingTimeout)
                showMessage(tr("Emparejado. Pulsa Seleccionar para usarlo.", "Paired. Tap Select to use it."))
            }
            state.text = when {
                bluetooth == null -> tr("Bluetooth no disponible", "Bluetooth unavailable")
                !permitted -> tr("Acceso a Bluetooth pendiente", "Bluetooth access required")
                enabled -> tr("Bluetooth activado", "Bluetooth enabled")
                changingState -> tr("Cambiando estado de Bluetooth…", "Changing Bluetooth state…")
                else -> tr("Bluetooth desactivado", "Bluetooth disabled")
            }
            state.setTextColor(if (enabled) DialogPanel.GREEN else DialogPanel.MUTED)
            enable.visibility = if (bluetooth != null && (!enabled || deniedPermissions.isNotEmpty() || locationRequired)) View.VISIBLE else View.GONE
            enable.text = when {
                deniedPermissions.isNotEmpty() -> tr("Permisos", "Permissions")
                locationRequired -> tr("Activar ubicación", "Enable location")
                !permitted -> tr("Permitir Bluetooth", "Allow Bluetooth")
                else -> tr("Activar Bluetooth", "Enable Bluetooth")
            }
            enable.available(!permissionPending && !changingState)
            rows.clear()
            rows.addAll(devices.values.sortedWith(compareBy<BluetoothDevice> { it.bondState != BluetoothDevice.BOND_BONDED }
                .thenBy { (it.name ?: it.address).lowercase(Locale.ROOT) }))
            labels.clear()
            labels.addAll(rows.map { device ->
                val bond = when (device.bondState) {
                    BluetoothDevice.BOND_BONDED -> tr("Emparejado", "Paired")
                    BluetoothDevice.BOND_BONDING -> tr("Emparejando…", "Pairing…")
                    else -> tr("Encontrado · sin emparejar", "Found · not paired")
                }
                "${device.name ?: tr("Dispositivo Bluetooth", "Bluetooth device")}\n${device.address} · $bond"
            })
            listAdapter.notifyDataSetChanged()
            list.clearChoices()
            val index = rows.indexOfFirst { it.address == selection }
            if (index >= 0) list.setItemChecked(index, true)
            val selected = rows.getOrNull(index)
            val bonding = pairingAddress != null || selected?.bondState == BluetoothDevice.BOND_BONDING
            list.isEnabled = !bonding && !permissionPending
            empty.text = when {
                !permitted -> tr("Permite el acceso para ver los adaptadores", "Allow access to see adapters")
                !enabled -> tr("Activa Bluetooth para ver los adaptadores", "Enable Bluetooth to see adapters")
                searching -> tr("Buscando adaptadores cercanos…", "Searching for nearby adapters…")
                else -> tr("Pulsa Buscar y enciende tu adaptador OBD", "Tap Search and turn on your OBD adapter")
            }
            search.text = if (searching) tr("Detener búsqueda", "Stop search") else tr("Buscar", "Search")
            search.available(enabled && registered && !permissionPending && !bonding && !isSessionActive())
            select.text = when (selected?.bondState) {
                BluetoothDevice.BOND_NONE -> tr("Emparejar", "Pair")
                BluetoothDevice.BOND_BONDING -> tr("Emparejando…", "Pairing…")
                else -> tr("Seleccionar", "Select")
            }
            select.available(enabled && selected != null && !bonding && !permissionPending &&
                (selected.bondState == BluetoothDevice.BOND_BONDED || (registered && !isSessionActive())))
            progress.visibility = if (searching || bonding || changingState) View.VISIBLE else View.GONE
            status.text = when {
                message != null -> message
                bonding -> tr("Confirma el emparejamiento en el aviso de Android", "Confirm pairing in the Android prompt")
                searching -> tr("Buscando… ${rows.size} dispositivo(s)", "Searching… ${rows.size} device(s)")
                isSessionActive() -> tr("Detén la sesión OBD para buscar o emparejar", "Stop the OBD session to search or pair")
                else -> tr("Selecciona un dispositivo de la lista", "Select a device from the list")
            }
            status.setTextColor(if (messageIsError) DialogPanel.RED else DialogPanel.MUTED)
        } catch (e: SecurityException) {
            devices.clear()
            rows.clear()
            labels.clear()
            listAdapter.notifyDataSetChanged()
            search.available(false)
            select.available(false)
            enable.visibility = View.VISIBLE
            enable.text = tr("Permitir Bluetooth", "Allow Bluetooth")
            status.text = tr("Se necesita permiso para acceder a Bluetooth", "Bluetooth permission is required")
            status.setTextColor(DialogPanel.RED)
        }
    }

    private fun searchOrStop() {
        if (searching) {
            cancelSearch()
            showMessage(tr("Búsqueda detenida", "Search stopped"))
            refresh()
            return
        }
        if (isSessionActive()) return
        withPermissions(scanPermissions()) {
            if (isSessionActive()) return@withPermissions
            if (Build.VERSION.SDK_INT <= 30 && !locationEnabled()) {
                locationRequired = true
                showMessage(tr("Activa la ubicación para buscar en esta versión de Android", "Enable location to search on this Android version"))
                refresh()
                return@withPermissions
            }
            try {
                val bluetooth = adapter()
                if (bluetooth?.isEnabled != true) {
                    refresh()
                    return@withPermissions
                }
                devices.entries.removeAll { it.value.bondState != BluetoothDevice.BOND_BONDED }
                ownsDiscovery = bluetooth.isDiscovering || bluetooth.startDiscovery()
                searching = ownsDiscovery
                if (searching) showMessage(null)
                else showMessage(tr("No se pudo iniciar la búsqueda. Vuelve a intentarlo.", "Could not start discovery. Try again."), true)
            } catch (e: RuntimeException) {
                showMessage(tr("Error buscando adaptadores: ${e.message}", "Adapter search failed: ${e.message}"), true)
            }
            refresh()
        }
    }

    private fun cancelSearch(beforeConnection: Boolean = false) {
        val mayCancel = Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_SCAN)
        if (mayCancel && (ownsDiscovery || beforeConnection)) {
            try {
                adapter()?.cancelDiscovery()
            } catch (_: RuntimeException) {
                // Permission or the Bluetooth service may disappear during dismissal.
            }
        }
        ownsDiscovery = false
        searching = false
    }

    private fun selectOrPair() {
        withPermissions(connectPermissions()) {
            val device = selection?.let { devices[it] } ?: return@withPermissions
            try {
                if (adapter()?.isEnabled != true) {
                    refresh()
                    return@withPermissions
                }
                if (device.bondState == BluetoothDevice.BOND_BONDED) {
                    cancelSearch(beforeConnection = true)
                    dismiss()
                    onSelected(device)
                } else if (device.bondState == BluetoothDevice.BOND_NONE && !isSessionActive()) {
                    cancelSearch(beforeConnection = true)
                    pairingAddress = device.address
                    if (device.createBond()) {
                        handler.removeCallbacks(pairingTimeout)
                        handler.postDelayed(pairingTimeout, 60000)
                        showMessage(tr("Confirma el PIN o el emparejamiento en el aviso de Android", "Confirm the PIN or pairing in the Android prompt"))
                    } else {
                        pairingAddress = null
                        showMessage(tr("No se pudo iniciar el emparejamiento", "Could not start pairing"), true)
                    }
                    refresh()
                }
            } catch (e: RuntimeException) {
                pairingAddress = null
                showMessage(tr("No se pudo usar el adaptador: ${e.message}", "Could not use the adapter: ${e.message}"), true)
                refresh()
            }
        }
    }
}
