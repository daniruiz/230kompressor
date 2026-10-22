package com.drasite.mercedesobd

import java.io.Closeable
import java.util.Locale

fun monoMs(): Long = System.nanoTime() / 1_000_000

fun Byte.u(): Int = toInt() and 255

fun ByteArray.hex(): String = joinToString("") { "%02X".format(Locale.ROOT, it.u()) }

fun unhex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

open class DiagnosticException(message: String) : Exception(message)

class LinkTimeout(message: String) : DiagnosticException(message)

class NegativeResponse(val service: Int, val code: Int) :
    DiagnosticException("ECU: 7F %02X %02X".format(service, code))

object Decoder {
    val ids = intArrayOf(0x04, 0x0D, 0x2B, 0x2D, 0x1B, 0x23, 0x01, 0x07, 0x0B, 0x0A, 0x1F, 0x10)
    const val TICK_US = 8.0 / 3
    const val VOLT_SCALE = 3.0 / 32
    const val MAF_SCALE = 1.1108

    fun word(b: ByteArray, i: Int) = b[i].u() or (b[i + 1].u() shl 8)

    fun positive(reply: String, service: Int, prefix: String, length: Int): ByteArray {
        val frames =
            reply
                .replace('>', ' ')
                .replace('\r', '\n')
                .lines()
                .map { it.replace(Regex("\\s+"), "").uppercase(Locale.ROOT) }
                .filter { it.isNotEmpty() && it.length % 2 == 0 && it.matches(Regex("[0-9A-F]+")) }
        val found = frames.filter { it.startsWith(prefix) }
        if (found.size == 1 && found[0].length == length * 2) return unhex(found[0])
        if (found.size > 1) throw DiagnosticException("Respuestas múltiples")
        frames
            .firstOrNull { it.length == 6 && it.startsWith("7F%02X".format(service)) }
            ?.let { throw NegativeResponse(service, it.substring(4).toInt(16)) }
        throw DiagnosticException("Respuesta inválida o incompleta: ${reply.take(160)}")
    }

    fun pairs(b: ByteArray): Sample {
        if (b.size != 24) throw DiagnosticException("Se esperaban doce pares")
        return Sample(
            rpm = word(b, 0),
            injection = word(b, 2),
            corrections = intArrayOf(b[4].u(), b[5].u(), b[6].u(), b[7].u()),
            stft = word(b, 8),
            ltft = word(b, 10),
            coolant = b[12].u(),
            iat = b[13].u(),
            maf = b[14].u(),
            load = b[15].u(),
            advance = b[16].u(),
            throttle = b[18].u(),
            oxygen = word(b, 20),
            voltage = b[22].u(),
            pairs = b.hex(),
        )
    }

    fun grouped(reply: String) = pairs(positive(reply, 0x2A, "6A01", 26).copyOfRange(2, 26))

    fun trim(raw: Int) = ((16384.0 + (raw shr 1)) / 32768 - 1) * 100

    fun oxygen(raw: Int) = ((raw - 3512).coerceIn(0, 0x3FBE) shr 6) / 200.0

    fun advance(raw: Int) = raw * .75 - 66

    fun definition(slot: Int, id: Int): String {
        require(slot in 1..12 && id in 1..255)
        return "2C01D2%02X11%02X".format(slot, id)
    }
}

data class Sample(
    val rpm: Int,
    val injection: Int,
    val load: Int,
    val corrections: IntArray,
    val stft: Int = 32768,
    val ltft: Int = 32768,
    val coolant: Int = 130,
    val iat: Int = 78,
    val maf: Int = 0,
    val advance: Int = 60,
    val throttle: Int = 0,
    val oxygen: Int = 0,
    val voltage: Int = 149,
    val pairs: String = "",
    val at: Long = monoMs(),
    val acquisitionMs: Double = 0.0,
    val cycleMs: Long? = null,
) {
    val injectionMetrics = Injection.metrics(injection, rpm, voltage)

    val injectionMs
        get() = injectionMetrics.totalMs

    val duty
        get() = injectionMetrics.duty

    val retard
        get() = (corrections.maxOrNull() ?: 0) * .75
}

interface Wire : Closeable {
    fun exchange(command: String, timeoutMs: Long = 6000): String
}

interface Events {
    fun event(type: String, vararg values: Pair<String, Any?>)
}

class Elm(
    private val wire: Wire,
    private val log: Events,
    var fast: Boolean = true,
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
    private val now: () -> Long = ::monoMs,
) {
    var profile = "ELM compatible"
        private set

    var stn = false
        private set

    private var gap = 60L
    private var lastBus: Long? = null
    private var p3 = false
    private var p4 = false
    private var fallbacks = 0

    fun command(cmd: String, timeout: Long = 6000): String {
        val bus = !(cmd.startsWith("AT") || cmd.startsWith("ST"))
        if (bus)
            lastBus?.let {
                val remain = gap - (now() - it)
                if (remain > 0) pause(remain)
            }
        val reply = wire.exchange(cmd, timeout)
        if (bus) lastBus = now()
        return reply
    }

    fun at(cmd: String, optional: Boolean = false): Boolean {
        val reply = command(cmd)
        val ok = reply.uppercase(Locale.ROOT).split(Regex("\\s+")).contains("OK")
        if (!ok && !optional) throw DiagnosticException("$cmd rechazado: ${reply.take(120)}")
        return ok
    }

    fun rollback(reason: String): Boolean {
        if (!p3 && !p4) return false
        if (p4) {
            at("STIP4 5")
            p4 = false
        }
        gap = 60
        p3 = false
        fallbacks++
        profile = "STN compatible"
        log.event("transport_fallback", "reason" to reason)
        return true
    }

    fun query(payload: String, optimized: Boolean = true): String {
        fun request() = payload + if (fast && optimized) " 1" else ""
        var reply = command(request())
        if (reply.trim() == "?" && fast && optimized) {
            fast = false
            reply = command(payload)
        }
        if (
            reply.contains("NO DATA", true) &&
                (payload.startsWith("22") || payload.startsWith("2A"))
        ) {
            log.event("recovery", "payload" to payload)
            rollback("NO DATA")
            reply = command(request())
        }
        return reply
    }

    fun setup(notice: (String) -> Unit) {
        at("ATE0")
        val identity = command("ATI").trim()
        notice("Adaptador: $identity")
        val firmware = command("STI", 2000).trim()
        stn = Regex("\\bSTN\\d+\\b|OBDLINK", RegexOption.IGNORE_CASE).containsMatchIn(firmware)
        val hardware = if (stn) command("STDI", 2000).trim() else null
        log.event(
            "adapter_details",
            "identity" to identity,
            "firmware" to firmware,
            "hardware" to hardware,
        )
        at("ATPC")
        notice("Cerrando sesión anterior · 6 s")
        pause(6000)
        for (cmd in
            listOf(
                "ATD",
                "ATE0",
                "ATL0",
                "ATS0",
                "ATH0",
                "ATM0",
                "ATSP4",
                "ATIIA01",
                "ATSH8101F1",
                "ATSW00",
                "ATAT0",
                "ATST80",
            )) at(cmd)
        var kwDisabled = false
        for (cmd in listOf("ATKW0", "AT KW0", "AT KW 0")) if (at(cmd, true)) {
            kwDisabled = true
            break
        }
        log.event("keyword_check", "disabled_acknowledged" to kwDisabled)
        at("ATAL", true)
        notice("Inicializando Mercedes · dirección 01")
        val init = command("ATSI", 20000)
        val keywords = command("ATKW").trim()
        command("ATDP")
        if (listOf("ERROR", "UNABLE", "BUS BUSY").any { init.contains(it, true) })
            throw DiagnosticException("Inicialización fallida: $init")
        verifyLink()
        log.event(
            "ecu_link_verified",
            "keywords" to keywords,
            "keyword_disable_acknowledged" to kwDisabled,
        )
    }

    fun verifyLink() {
        Decoder.positive(query("221104", false), 0x22, "621104", 5)
    }

    fun defineGroup(): Sample {
        Decoder.ids.forEachIndexed { i, id ->
            Decoder.positive(query(Decoder.definition(i + 1, id), false), 0x2C, "6C01D2", 3)
        }
        return Decoder.grouped(query("2A0101", false))
    }

    fun acquire(group: Boolean): Sample {
        val start = now()
        val sample =
            if (group) Decoder.grouped(query("2A0101"))
            else {
                val pairs =
                    Decoder.ids
                        .flatMap { id ->
                            Decoder.positive(
                                    query("2211%02X".format(id)),
                                    0x22,
                                    "6211%02X".format(id),
                                    5,
                                )
                                .copyOfRange(3, 5)
                                .toList()
                        }
                        .toByteArray()
                Decoder.pairs(pairs)
            }
        val end = now()
        return sample.copy(at = end, acquisitionMs = (end - start).toDouble())
    }

    fun storedTrim(address: Int): Int {
        require(address == 0x900C || address == 0x9014)
        val suffix = "00%04X".format(address)
        val frame = Decoder.positive(query("23$suffix"), 0x23, "63$suffix", 6)
        return Decoder.word(frame, 4)
    }

    fun optimize(group: Boolean, publish: (Sample) -> Unit, notice: (String) -> Unit) {
        if (!stn || !fast) return
        profile = "STN compatible"
        if (at("STPTRQ 60", true)) {
            p3 = true
            gap = 0
            profile = "STN P3 adaptador"
        }
        if (!group) return
        notice("OBDLink · midiendo tiempos")
        try {
            val base = ArrayList<Double>()
            val trial = ArrayList<Double>()
            repeat(4) {
                val s = acquire(true)
                publish(s)
                base.add(s.acquisitionMs)
                if (fallbacks > 0 || !fast) return
            }
            if (!at("STIP4 0", true)) return
            p4 = true
            repeat(6) {
                val s = acquire(true)
                publish(s)
                trial.add(s.acquisitionMs)
                if (fallbacks > 0 || !fast) {
                    rollback("Atajo rechazado")
                    return
                }
            }
            fun median(xs: List<Double>): Double {
                val v = xs.sorted()
                return if (v.size % 2 == 1) v[v.size / 2]
                else (v[v.size / 2 - 1] + v[v.size / 2]) / 2
            }
            val b = median(base.drop(1))
            val t = median(trial.drop(1))
            val accept = t < b * .97
            if (accept) profile = "STN rápido P4=0"
            else {
                at("STIP4 5")
                p4 = false
            }
            log.event(
                "transport_benchmark",
                "baseline_ms" to base,
                "trial_ms" to trial,
                "p4_zero_accepted" to accept,
            )
        } catch (e: LinkTimeout) {
            throw e
        } catch (e: DiagnosticException) {
            rollback(e.message ?: "Error prueba")
        }
    }
}
