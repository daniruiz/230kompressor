package com.drasite.mercedesobd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.sin

class Session(private val root: File, val model: Model, private val alert: () -> Unit) {
    @Volatile private var thread: Thread? = null
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var stopped = false
    val running: Boolean
        get() = thread?.isAlive == true

    @Synchronized
    fun start(demo: Boolean, address: String?, tuning: Boolean, conservative: Boolean): Boolean {
        if (running) return false
        model.clear()
        stopped = false
        thread =
            Thread({ run(demo, address, tuning, conservative) }, "MercedesOBD acquisition").also {
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

    @SuppressLint("MissingPermission")
    private fun run(demo: Boolean, address: String?, tuning: Boolean, conservative: Boolean) {
        var journal: Journal? = null
        var wire: BluetoothWire? = null
        try {
            val j = Journal(root, demo)
            journal = j
            val temperatureAlerts = TemperatureAlerts()
            fun publish(s: Sample) {
                val (sample, raised) = model.accept(s)
                j.sample(sample, model.snapshot(0), raised)
                val temperatureRaised = temperatureAlerts.accept(sample)
                if (raised || temperatureRaised) alert()
            }
            if (demo) {
                model.mode("DEMO · SIN COCHE")
                val start = monoMs()
                var last = start
                var nextTrim = 0L
                while (!stopped) {
                    val now = monoMs()
                    val t = (now - start) / 1000.0
                    val phase = t % 8
                    val load = (60 + 175 * (.5 + .5 * sin(t / 2.2))).toInt()
                    val rpm = (900 + 5200 * (.5 + .5 * sin(t / 3 - 1.5))).toInt()
                    val c = if (phase >= 2) maxOf(0, 8 - ((phase - 2) * 2).toInt()) else 0
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
                            1100 + load * 18,
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
                            voltage = ((14 + .2 * sin(t / 5)) / Decoder.VOLT_SCALE).toInt(),
                            at = now,
                            acquisitionMs = (now - last).toDouble(),
                        )
                    )
                    last = now
                    Thread.sleep(125)
                }
                return
            }
            model.notice("Conectando Bluetooth SPP…")
            val adapter =
                BluetoothAdapter.getDefaultAdapter()
                    ?: throw DiagnosticException("Bluetooth no disponible")
            if (!adapter.isEnabled) throw DiagnosticException("Activa Bluetooth en Ajustes")
            val sock =
                adapter
                    .getRemoteDevice(requireNotNull(address))
                    .createRfcommSocketToServiceRecord(
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
            val elm = Elm(w, j, !conservative)
            elm.setup(model::notice)
            var group = true
            try {
                publish(elm.defineGroup())
            } catch (e: LinkTimeout) {
                throw e
            } catch (e: DiagnosticException) {
                j.event("fallback", "reason" to e.toString())
                model.notice("Grupo rechazado; lecturas individuales")
                group = false
            }
            if (tuning) elm.optimize(group, ::publish, model::notice)
            var consecutive = 0
            var nextTrim = 0L
            var pendingLow: Pair<Int, Long>? = null
            var trimDisabled = false
            while (!stopped) {
                try {
                    model.mode(if (group) "AGRUPADO" else "INDIVIDUAL", elm.profile)
                    publish(elm.acquire(group))
                    consecutive = 0
                    if (!trimDisabled && monoMs() >= nextTrim) {
                        try {
                            val low = pendingLow
                            if (low == null) pendingLow = elm.storedTrim(0x900C) to monoMs()
                            else {
                                val high = elm.storedTrim(0x9014)
                                model.trims(low.first, high, low.second)
                                j.event(
                                    "ltft_stored",
                                    "low_raw" to low.first,
                                    "high_raw" to high,
                                    "low_pct" to Decoder.trim(low.first),
                                    "high_pct" to Decoder.trim(high),
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
                    model.reject(e.message ?: "Respuesta inválida")
                    if (elm.rollback(e.message ?: "Error")) continue
                    if (group && consecutive >= 3) {
                        elm.verifyLink()
                        group = false
                        consecutive = 0
                        j.event("fallback", "reason" to "3 errores de grupo; RPM individual válida")
                    } else if (consecutive >= 5)
                        throw DiagnosticException("Cinco ciclos sin datos válidos")
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
                model.reject("Error cerrando registros: ${e.message}")
            }
            if (stopped) model.stop()
        }
    }
}
