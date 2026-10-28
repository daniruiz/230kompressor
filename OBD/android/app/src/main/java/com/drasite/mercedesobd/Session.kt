package com.drasite.mercedesobd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ArrayBlockingQueue
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin

class Session(private val root: File, val model: Model, private val alert: () -> Unit) {
    @Volatile private var thread: Thread? = null
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var stopped = false
    @Volatile private var diagnosticsReady = false
    private data class FaultRequest(val clear: Boolean, val done: (Result<List<FaultCode>>) -> Unit)
    private val faultRequests = ArrayBlockingQueue<FaultRequest>(1)
    val running: Boolean
        get() = thread?.isAlive == true

    @Synchronized
    fun start(demo: Boolean, address: String?, adapterTuning: Boolean = true): Boolean {
        if (running) return false
        model.clear()
        stopped = false
        diagnosticsReady = false
        thread =
            Thread({ run(demo, address, adapterTuning) }, "MercedesOBD acquisition").also {
                it.start()
            }
        return true
    }

    fun stop() {
        stopped = true
        thread?.interrupt()
        try {
            socket?.close()
        } catch (_: Exception) {}
    }

    @Synchronized
    fun faults(clear: Boolean, done: (Result<List<FaultCode>>) -> Unit): Boolean =
        running && !stopped && diagnosticsReady && faultRequests.offer(FaultRequest(clear, done))

    private fun serviceFaults(elm: Elm?, journal: Journal, demoFaults: MutableList<FaultCode>) {
        val request = faultRequests.poll() ?: return
        try {
            if (elm == null) {
                if (request.clear) demoFaults.clear()
                journal.event("dtc_demo", "clear" to request.clear)
                request.done(Result.success(demoFaults.toList()))
            } else {
                val result = if (request.clear) { elm.clearFaults(); emptyList() } else elm.readFaults()
                request.done(Result.success(result))
            }
        } catch (e: Exception) {
            request.done(Result.failure(e))
            if (e is LinkTimeout || e is InterruptedException) throw e
        }
    }

    @SuppressLint("MissingPermission")
    private fun run(demo: Boolean, address: String?, adapterTuning: Boolean) {
        var journal: Journal? = null
        var wire: BluetoothWire? = null
        try {
            val j = Journal(root, demo, adapterTuning)
            journal = j
            val temperatureAlerts = TemperatureAlerts()
            val demoFaults = mutableListOf(FaultCode("P0300", 0, "DEMO"))
            fun publish(s: Sample) {
                val (sample, raised) = model.accept(s)
                j.sample(sample, model.snapshot(0), raised)
                val temperatureRaised = temperatureAlerts.accept(sample)
                if (raised || temperatureRaised) alert()
            }
            if (demo) {
                diagnosticsReady = true
                model.mode("DEMO · SIN COCHE")
                model.setEcu(null, null, "demo")
                val start = monoMs()
                var last = start
                var nextTrim = 0L
                while (!stopped) {
                    serviceFaults(null, j, demoFaults)
                    val now = monoMs()
                    val t = (now - start) / 1000.0
                    val phase = t % 8
                    val load = (60 + 175 * (.5 + .5 * sin(t / 2.2))).toInt()
                    val rpm = (900 + 5200 * (.5 + .5 * sin(t / 3 - 1.5))).toInt()
                    val c = if (phase >= 2) maxOf(0, 8 - ((phase - 2) * 2).toInt()) else 0
                    val voltage = round((14 + .2 * sin(t / 5)) / Decoder.VOLT_SCALE).toInt().coerceIn(0, 255)
                    val injection = requireNotNull(Injection.deadtimeTicks(voltage)) +
                        Injection.calibration.minimumTicks + round(7500 * (.5 + .5 * sin(t / 3 - 1))).toInt()
                    if (now >= nextTrim) {
                        model.trims(
                            (32768 + 1000 * sin(t / 12)).toInt(),
                            (32768 + 2400 * cos(t / 17)).toInt(),
                            now,
                        )
                        nextTrim = now + 30000
                    }
                    publish(
                        Sample(
                            rpm,
                            injection,
                            load,
                            intArrayOf(c, maxOf(0, c - 2), 0, 0),
                            stft = (32768 + 2500 * sin(t)).toInt(),
                            ltft = 34000,
                            coolant = (140 + 18 * sin(t / 4 - 1.5)).toInt(),
                            iat = (98 + 20 * sin(t / 4 - 2.5)).toInt(),
                            maf = minOf(255, load * rpm / 100000),
                            advance = ((-15 + 20 * sin(t / 4) + 66) / .75).toInt(),
                            throttle = (255 * (.5 + .5 * sin(t / 3))).toInt(),
                            oxygen = (0x2456 * (1 + .7 * sin(t * 4))).toInt(),
                            voltage = voltage,
                            at = now,
                            acquisitionMs = (now - last).toDouble(),
                        )
                    )
                    last = now
                    Thread.sleep(125)
                }
                return
            }
            model.notice(tr("Conectando Bluetooth SPP…", "Connecting Bluetooth SPP…"))
            val adapter =
                BluetoothAdapter.getDefaultAdapter()
                    ?: throw DiagnosticException(tr("Bluetooth no disponible", "Bluetooth unavailable"))
            if (!adapter.isEnabled) throw DiagnosticException(tr("Activa Bluetooth en Ajustes", "Enable Bluetooth in Settings"))
            val device = adapter.getRemoteDevice(requireNotNull(address))
            val sock =
                device.createRfcommSocketToServiceRecord(
                    UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
                )
            socket = sock
            if (stopped) throw InterruptedException()
            val watchdog = Executors.newSingleThreadScheduledExecutor()
            try {
                val timeout =
                    watchdog.schedule(
                        {
                            try {
                                sock.close()
                            } catch (_: Exception) {}
                        },
                        15,
                        TimeUnit.SECONDS,
                    )
                try {
                    sock.connect()
                } finally {
                    timeout.cancel(false)
                }
            } finally {
                watchdog.shutdownNow()
            }
            val w = BluetoothWire(sock, j)
            wire = w
            j.event(
                "adapter_connection",
                "transport" to "bluetooth_spp",
                "bluetooth_name" to device.name,
                "bluetooth_address" to device.address,
            )
            val elm = Elm(w, j)
            elm.setup(model::notice)
            val ecuProfile = elm.identifyEcu(model::notice)
            model.setEcu(ecuProfile, elm.ecuSoftware, elm.ecuIdentificationStatus)
            var group = true
            try {
                publish(elm.defineGroup())
            } catch (e: LinkTimeout) {
                throw e
            } catch (e: DiagnosticException) {
                j.event("fallback", "reason" to e.toString())
                model.notice(tr("Grupo rechazado; lecturas individuales", "Group rejected; individual reads"))
                group = false
            }
            if (adapterTuning) elm.optimize(group, ::publish, model::notice)
            diagnosticsReady = true
            var consecutive = 0
            var nextTrim = 0L
            var pendingLow: Pair<Int, Long>? = null
            var trimDisabled = ecuProfile == null
            while (!stopped) {
                serviceFaults(elm, j, demoFaults)
                try {
                    model.mode(if (group) "AGRUPADO" else "INDIVIDUAL", elm.profile)
                    publish(elm.acquire(group))
                    consecutive = 0
                    if (!trimDisabled && ecuProfile != null && monoMs() >= nextTrim) {
                        try {
                            val low = pendingLow
                            if (low == null) pendingLow = elm.storedTrim(ecuProfile.ltftLowAddress) to monoMs()
                            else {
                                val high = elm.storedTrim(ecuProfile.ltftHighAddress)
                                model.trims(low.first, high, low.second)
                                j.event(
                                    "ltft_stored",
                                    "low_raw" to low.first,
                                    "high_raw" to high,
                                    "software" to ecuProfile.software,
                                    "low_address" to addressHex(ecuProfile.ltftLowAddress),
                                    "high_address" to addressHex(ecuProfile.ltftHighAddress),
                                    "low_pct" to Decoder.trim(low.first),
                                    "high_pct" to Decoder.trim(high),
                                    "pair_span_s" to (monoMs() - low.second) / 1000.0,
                                )
                                pendingLow = null
                                nextTrim = monoMs() + 30000
                            }
                        } catch (e: LinkTimeout) {
                            throw e
                        } catch (e: DiagnosticException) {
                            trimDisabled =
                                e is NegativeResponse && e.code in listOf(0x11, 0x12, 0x31, 0x33)
                            model.trimError(if (trimDisabled) "no disponible" else "error")
                            j.event(
                                "ltft_stored_error",
                                "error" to e.toString(),
                                "disabled" to trimDisabled,
                            )
                            elm.rollback("LTFT: ${e.message}")
                            pendingLow = null
                            nextTrim = monoMs() + 30000
                        }
                    }
                } catch (e: LinkTimeout) {
                    throw e
                } catch (e: DiagnosticException) {
                    consecutive++
                    model.reject(e.message ?: tr("Respuesta inválida", "Invalid response"))
                    if (elm.rollback(e.message ?: "Error")) continue
                    if (group && consecutive >= 3) {
                        elm.verifyLink()
                        group = false
                        consecutive = 0
                        j.event("fallback", "reason" to tr("3 errores de grupo; RPM individual válida", "3 errors de grupo; RPM individual válida"))
                    } else if (consecutive >= 5)
                        throw DiagnosticException(tr("Cinco ciclos sin datos válidos", "Five cycles without valid data"))
                }
            }
        } catch (e: Exception) {
            if (!stopped && e !is InterruptedException) {
                model.reject(e.message ?: e.toString())
                model.mode("ERROR")
                try {
                    journal?.event("fatal", "error" to e.toString())
                } catch (_: Exception) {}
            }
        } finally {
            diagnosticsReady = false
            while (true) {
                val request = faultRequests.poll() ?: break
                request.done(Result.failure(DiagnosticException(tr("Sesión finalizada", "Session ended"))))
            }
            try {
                wire?.close()
            } catch (_: Exception) {}
            try {
                socket?.close()
            } catch (_: Exception) {}
            socket = null
            try {
                journal?.close()
            } catch (e: Exception) {
                model.reject(tr("Error cerrando registros: ${e.message}", "Error closing logs: ${e.message}"))
            }
            if (stopped) model.stop()
        }
    }
}
