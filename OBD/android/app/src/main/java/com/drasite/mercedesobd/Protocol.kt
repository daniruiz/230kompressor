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
    // All twelve readings stay in the group; no slot redefinition is needed per cycle.
    // 1143 is raw MAF ADC; 1107 returns processed ECU MAF and load together.
    val ids = intArrayOf(0x04, 0x0D, 0x2B, 0x2D, 0x1B, 0x23, 0x01, 0x43, 0x0B, 0x0A, 0x1F, 0x07)
    const val TICK_US = 8.0 / 3
    const val KNOCK_ALERT_THRESHOLD_DEG = 3.0

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
        if (found.size > 1) throw DiagnosticException(tr("Respuestas múltiples", "Multiple responses"))
        frames
            .firstOrNull { it.length == 6 && it.startsWith("7F%02X".format(service)) }
            ?.let { throw NegativeResponse(service, it.substring(4).toInt(16)) }
        throw DiagnosticException(tr("Respuesta inválida o incompleta: ${reply.take(160)}", "Invalid or incomplete response: ${reply.take(160)}"))
    }

    fun pairs(
        b: ByteArray,
        at: Long,
        ecuProfile: EcuProfile?,
    ): Sample {
        if (b.size != 24) throw DiagnosticException(tr("Se esperaban doce pares", "Expected twelve pairs"))
        val signals = ids.mapIndexed { i, id -> id to b.copyOfRange(i * 2, i * 2 + 2) }.toMap()
        fun pair(id: Int): ByteArray = signals[id]
            ?: throw DiagnosticException(tr("Falta la lectura 11%02X", "Missing reading 11%02X").format(id))
        fun value(id: Int) = word(pair(id), 0)
        val mafWord = value(0x43)
        return Sample(
            rpm = value(0x04),
            injection = value(0x0D),
            corrections = (pair(0x2B) + pair(0x2D)).map { it.u() }.toIntArray(),
            stft = value(0x1B),
            ltft = value(0x23),
            coolant = pair(0x01)[0].u(),
            iat = pair(0x01)[1].u(),
            maf = mafWord and 0x03FF,
            mafAdcWord = mafWord,
            mafGramsPerSecond = MafCalibration.gramsPerSecond(mafWord),
            mafCalibrationStatus = if (ecuProfile != null) "stock_curve_assumed"
                else "stock_curve_assumed_unverified_software",
            mafEcuRaw = pair(0x07)[0].u(),
            load = pair(0x07)[1].u(),
            advance = pair(0x0B)[0].u(),
            throttle = pair(0x0A)[0].u(),
            oxygen = value(0x1F),
            diagnosticIds = ids.toList(),
            pairs = b.hex(),
            at = at,
        )
    }

    fun trim(raw: Int) = ((16384.0 + (raw shr 1)) / 32768 - 1) * 100

    fun oxygen(raw: Int) = ((raw - 3512).coerceIn(0, 0x3FBE) shr 6) / 200.0

    fun advance(raw: Int) = raw * .75 - 66

    // F74C = min(255, floor(processed flow / 40)); one flow unit is 0.02777 g/s.
    // This quantized diagnostic value follows ECU load selection/filtering.
    fun ecuMaf(raw: Int?): Double? = raw?.let { it * 11108 / 10000.0 }

    fun adapterVoltage(reply: String): Pair<Double?, String> {
        val lines = reply.replace('>', ' ').replace('\r', '\n').lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.replace(Regex("\\s+"), "").equals("ATRV", true) }
            .toList()
        if (lines == listOf("?")) return null to "unsupported"
        if (lines.size != 1) return null to "invalid_response"
        val match = Regex("([0-9]+(?:\\.[0-9]+)?)\\s*V", RegexOption.IGNORE_CASE)
            .matchEntire(lines.single())
        val value = match?.groupValues?.get(1)?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it in 0.0..30.0 }
        return value to if (value == null) "invalid_response" else "ok"
    }

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
    val mafAdcWord: Int? = null,
    val mafGramsPerSecond: Double? = null,
    val mafCalibrationStatus: String = "unverified_software",
    val mafEcuRaw: Int? = null,
    val advance: Int = 60,
    val throttle: Int = 0,
    val oxygen: Int = 0,
    val diagnosticIds: List<Int> = emptyList(),
    val pairs: String = "",
    val at: Long = monoMs(),
    val acquisitionMs: Double = 0.0,
    val cycleMs: Long? = null,
    val adapterVoltage: Double? = null,
    val adapterVoltageAgeMs: Long? = null,
    val adapterVoltageStatus: String = "pending",
) {
    // ATRV measures the adapter supply, not the ECU's F734 byte for dead time.
    val injectionMetrics = Injection.metrics(injection, rpm, null)

    val injectionMs
        get() = injectionMetrics.totalMs

    val duty
        get() = injectionMetrics.duty

    val retard
        get() = (corrections.maxOrNull() ?: 0) * .75

    val highRetard
        get() = retard > Decoder.KNOCK_ALERT_THRESHOLD_DEG
}

data class ExchangeTiming(val totalStart: Long = monoMs(), val hostGapWaitMs: Long = 0)

interface Wire : Closeable {
    fun exchange(
        command: String,
        timeoutMs: Long = 6000,
        timing: ExchangeTiming = ExchangeTiming(),
    ): String
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

    var ecuProfile: EcuProfile? = null
        private set
    var ecuSoftware: String? = null
        private set
    var ecuIdentificationStatus = "pending"
        private set

    private var gap = 60L
    private var lastBus: Long? = null
    private var p3 = false
    private var p3Ms: Long? = null
    private var p4 = false
    private var hostGapTuned = false
    private var hostGapProbing = false
    private var elmTimingProbeDisabled = false
    private var elmTimingProbeCompleted = false
    private var fallbacks = 0
    private var adapterVoltagePolling = false
    private var nextAdapterVoltageAt = 0L
    private var adapterVoltage: Double? = null
    private var adapterVoltageAt: Long? = null
    private var adapterVoltageStatus = "pending"

    fun command(cmd: String, timeout: Long = 6000): String {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        val totalStart = now()
        var hostGapWait = 0L
        val bus = !(cmd.startsWith("AT") || cmd.startsWith("ST"))
        if (bus)
            lastBus?.let {
                val remain = gap - (now() - it)
                if (remain > 0) {
                    val waitStart = now()
                    pause(remain)
                    hostGapWait = now() - waitStart
                }
            }
        val reply = wire.exchange(cmd, timeout, ExchangeTiming(totalStart, hostGapWait))
        if (bus) lastBus = now()
        return reply
    }

    fun at(cmd: String, optional: Boolean = false): Boolean {
        val reply = command(cmd)
        val ok = reply.uppercase(Locale.ROOT).split(Regex("\\s+")).contains("OK")
        if (!ok && !optional) throw DiagnosticException(tr("$cmd rechazado: ${reply.take(120)}", "$cmd rejected: ${reply.take(120)}"))
        return ok
    }

    fun enableAdapterVoltagePolling() {
        // Resume after timing optimisation without repeating an unsupported
        // command or discarding the schedule set by the startup reading.
        adapterVoltagePolling = adapterVoltageStatus != "unsupported"
    }

    private fun pollAdapterVoltage() {
        if (!adapterVoltagePolling || now() < nextAdapterVoltageAt) return
        readAdapterVoltage()
    }

    private fun readAdapterVoltage() {
        val start = now()
        nextAdapterVoltageAt = start + 5000
        // A local AT command does not reset lastBus. The next ECU request waits
        // only for any bus gap left after ATRV, on this same acquisition thread.
        val reply = try {
            command("ATRV", 1000)
        } catch (e: LinkTimeout) {
            adapterVoltageStatus = "timeout"
            log.event("adapter_voltage", "adapter_voltage_V" to null,
                "status" to adapterVoltageStatus, "elapsed_ms" to now() - start)
            // Without a prompt the stream is unsynchronized; do not send a group.
            throw e
        }
        val end = now()
        val (value, status) = Decoder.adapterVoltage(reply)
        adapterVoltageStatus = status
        if (value != null) {
            adapterVoltage = value
            adapterVoltageAt = end
        }
        if (status == "unsupported") adapterVoltagePolling = false
        log.event("adapter_voltage", "adapter_voltage_V" to value,
            "status" to status, "elapsed_ms" to end - start)
    }

    fun rollback(reason: String): Boolean {
        val elmGapActive = hostGapTuned || hostGapProbing
        if (!p3 && !p4 && !elmGapActive) return false
        // Volatile adapter settings only; no ECU calibration or timing writes.
        if (p3 && (p3Ms ?: 60L) < 60L) {
            at("STPTRQ 60")
            p3Ms = 60
        }
        if (p4) {
            at("STIP4 5")
            p4 = false
        }
        gap = 60
        p3 = false
        hostGapTuned = false
        hostGapProbing = false
        if (elmGapActive) elmTimingProbeDisabled = true
        fallbacks++
        profile = if (stn) "STN compatible" else "ELM compatible"
        log.event(
            "transport_fallback",
            "reason" to reason,
            "host_gap_ms" to 60,
            "p4_ms" to if (stn) 5 else null,
            "p3_ms" to p3Ms,
            "elm_timing_probe_disabled" to elmTimingProbeDisabled,
        )
        return true
    }

    fun query(payload: String, optimized: Boolean = true): String {
        // The shorter STN gap is validated only for the fixed polling group.
        if (p3 && (p3Ms ?: 60L) < 60L && payload != "2A0101")
            rollback(tr("Consulta fuera del grupo", "Request outside the group"))
        fun request() = payload + if (fast && optimized) " 1" else ""
        var reply = command(request())
        if (reply.trim() == "?" && fast && optimized) {
            fast = false
            if (p3 || p4 || hostGapTuned || hostGapProbing)
                rollback(tr("Sufijo de una respuesta rechazado", "Single-response suffix rejected"))
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
        notice(tr("Adaptador: $identity", "Adapter: $identity"))
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
        // Let a previous ECU session expire before starting another slow init.
        // ATRV is local to the adapter: use the last second of this idle window
        // for the first voltage reading without extending the six-second wait.
        val idleDeadline = now() + 6000
        notice(tr("Cerrando sesión anterior · 6 s", "Closing previous session · 6 s"))
        pause(5000)
        readAdapterVoltage()
        val idleRemaining = idleDeadline - now()
        if (idleRemaining > 0) pause(idleRemaining)
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
        // Keep one more idle retry for init/read failures with the prompt intact.
        for (attempt in 0..1) {
            if (attempt > 0) {
                at("ATPC")
                lastBus = null
                notice(tr("Reintentando · cerrando sesión anterior · 6 s",
                    "Retrying · closing previous session · 6 s"))
                pause(6000)
            }
            notice(tr("Inicializando Mercedes · dirección 01", "Starting Mercedes diagnostics · address 01"))
            try {
                val init = command("ATSI", 20000)
                if (listOf("ERROR", "UNABLE", "BUS BUSY").any { init.contains(it, true) })
                    throw DiagnosticException(tr("Inicialización fallida: $init", "Initialization failed: $init"))
                val keywords = command("ATKW").trim()
                verifyLink()
                log.event(
                    "ecu_link_verified",
                    "keywords" to keywords,
                    "keyword_disable_acknowledged" to kwDisabled,
                    "attempts" to attempt + 1,
                    "startup_wait_ms" to (attempt + 1) * 6000,
                )
                return
            } catch (e: LinkTimeout) {
                // Do not send another command after losing adapter framing.
                throw e
            } catch (e: NegativeResponse) {
                // The ECU is already communicating; waiting cannot fix rejection.
                throw e
            } catch (e: DiagnosticException) {
                if (attempt > 0) throw e
                log.event("connection_retry", "reason" to e.toString(), "wait_ms" to 6000)
            }
        }
    }

    fun verifyLink() {
        readSingleFrame("221104") { Decoder.positive(it, 0x22, "621104", 5) }
    }

    fun readFaults(): List<FaultCode> {
        rollback("DTC")
        // No single-response suffix: A1 returns one frame per fault and a terminator.
        val faults = FaultCodes.decode(command("A1", 30000))
        log.event("dtc_read", "codes" to faults.map { it.code }, "records" to faults.map { it.raw })
        return faults
    }

    fun clearFaults() {
        rollback("DTC")
        val rpm = Decoder.word(Decoder.positive(query("221104", false), 0x22, "621104", 5), 3)
        if (rpm != 0) throw DiagnosticException(tr("Para borrar averías, apaga el motor y deja el contacto puesto", "To clear faults, stop the engine and leave the ignition on"))
        // SW404 03AB02: bare service 14, positive response 54. Never retry a clear.
        Decoder.positive(query("14", false), 0x14, "54", 1)
        log.event("dtc_clear", "acknowledged" to true)
    }

    /** Known single-frame reads can skip the adapter's final response timeout.
     * Validate the full positive reply and fall back for incompatible clones.
     * A lost prompt or a negative ECU response is never retried here.
     * Only read services belong here, never writes or multi-frame DTC reads.
     */
    private fun <T> readSingleFrame(payload: String, decode: (String) -> T): T {
        try {
            return decode(query(payload))
        } catch (e: LinkTimeout) {
            throw e
        } catch (e: NegativeResponse) {
            throw e
        } catch (e: DiagnosticException) {
            if (!fast) throw e
            rollback(e.message ?: e.toString())
            fast = false
            log.event("single_response_disabled", "payload" to payload, "reason" to e.toString())
            return decode(query(payload, false))
        }
    }

    private fun decodePairs(bytes: ByteArray): Sample = Decoder.pairs(bytes, now(), ecuProfile)

    private fun decodeGroup(reply: String): Sample =
        decodePairs(Decoder.positive(reply, 0x2A, "6A01", 26).copyOfRange(2, 26))

    private fun withAdapterVoltage(sample: Sample): Sample = sample.copy(
        adapterVoltage = adapterVoltage,
        adapterVoltageAgeMs = adapterVoltageAt?.let { (sample.at - it).coerceAtLeast(0) },
        adapterVoltageStatus = adapterVoltageStatus,
    )

    fun defineGroup(): Sample {
        Decoder.ids.forEachIndexed { i, id ->
            // Send each definition explicitly; only read services may retry NO DATA.
            Decoder.positive(query(Decoder.definition(i + 1, id)), 0x2C, "6C01D2", 3)
        }
        // One frame contains this group's twelve pairs; validate its full length.
        return withAdapterVoltage(readSingleFrame("2A0101") { decodeGroup(it) })
    }

    fun acquire(group: Boolean): Sample {
        val start = now()
        pollAdapterVoltage()
        val sample =
            if (group) decodeGroup(query("2A0101"))
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
                decodePairs(pairs)
            }
        val end = now()
        return withAdapterVoltage(sample.copy(
            at = end,
            acquisitionMs = (end - start).toDouble(),
        ))
    }

    private fun readMemoryWord(address: Int): Int {
        // Service 23 reads one LE word from an even, logical 16-bit address;
        // there is no length byte. Only known ROM cells and verified corrections use it.
        require(address in 0..0xFFFE && (address and 1) == 0) { tr("Dirección de palabra inválida", "Invalid word address") }
        val suffix = "00%04X".format(address)
        val frame = Decoder.positive(query("23$suffix"), 0x23, "63$suffix", 6)
        return Decoder.word(frame, 4)
    }

    /** Run once at connection, before grouping and timing optimisation.
     * Both the full ROM reference and its location must match. Never infer a RAM
     * layout from plausible trim values; a lost prompt still ends the session.
     */
    fun identifyEcu(notice: (String) -> Unit): EcuProfile? {
        ecuProfile = null
        ecuSoftware = null
        ecuIdentificationStatus = "unverified"
        notice(tr("Identificando software ECU · leyendo referencia en ROM", "Identifying ECU software · reading ROM reference"))
        val cache = HashMap<Int, Int>()
        fun word(address: Int) = cache.getOrPut(address) { readMemoryWord(address) }
        var reason = tr("No se encontró una referencia de software conocida", "No known software reference found")
        for (address in EcuProfiles.all.map { it.softwareAddress }.distinct()) {
            try {
                if (word(address) != 0x3131) continue // ASCII "11", little-endian.
                val raw = ByteArray(14)
                for (i in 0 until 7) {
                    val value = word(address + i * 2)
                    raw[i * 2] = value.toByte()
                    raw[i * 2 + 1] = (value shr 8).toByte()
                }
                val software = raw.copyOf(13).toString(Charsets.US_ASCII)
                if (!Regex("114\\.[0-9]{6}\\.[0-9]{2}").matches(software)) continue
                ecuSoftware = software
                val selected = EcuProfiles.bySoftware[software]
                if (selected == null || selected.softwareAddress != address) {
                    ecuIdentificationStatus = "unknown_software"
                    reason = tr("Referencia ROM sin perfil de direcciones validado", "ROM reference has no verified address profile")
                    break
                }
                ecuProfile = selected
                ecuIdentificationStatus = "identified_rom"
                log.event(
                    "ecu_identification",
                    "software" to selected.software,
                    "status" to ecuIdentificationStatus,
                    "method" to "ROM",
                    "software_address" to addressHex(address),
                    "ltft_low_address" to addressHex(selected.ltftLowAddress),
                    "ltft_high_address" to addressHex(selected.ltftHighAddress),
                    "stored_corrections_available" to true,
                    "maf_conversion_available" to true,
                    "maf_calibration_source" to MafCalibration.SOURCE,
                    "maf_curve_sha256" to MafCalibration.SHA256,
                    "additive_address" to addressHex(selected.additiveAddress),
                    "corrections_read_policy" to "on_demand",
                )
                notice(tr("ECU ${selected.software} · perfil de correcciones seleccionado", "ECU ${selected.software} · correction profile selected"))
                return selected
            } catch (e: LinkTimeout) {
                throw e
            } catch (e: DiagnosticException) {
                ecuIdentificationStatus = "read_error"
                reason = e.message ?: e.toString()
                log.event(
                    "ecu_identification_probe_error",
                    "stage" to "ROM",
                    "address" to addressHex(address),
                    "error" to reason,
                )
                if (e is NegativeResponse && e.code in listOf(0x11, 0x12, 0x33)) break
            }
        }
        log.event(
            "ecu_identification",
            "software" to ecuSoftware,
            "status" to ecuIdentificationStatus,
            "reason" to reason,
            "method" to "ROM",
            "stored_corrections_available" to false,
            "maf_conversion_available" to true,
            "maf_calibration_status" to "stock_curve_assumed_unverified_software",
            "maf_calibration_source" to MafCalibration.SOURCE,
            "maf_curve_sha256" to MafCalibration.SHA256,
        )
        notice(tr("ECU sin perfil verificado · lectura de correcciones no disponible", "ECU profile unverified · correction readings unavailable"))
        return null
    }

    fun readFuelCorrections(): FuelCorrections {
        val selected = ecuProfile
            ?: throw DiagnosticException(tr("Lectura de correcciones no disponible: software ECU sin verificar",
                "Correction readings unavailable: unverified ECU software"))
        return FuelCorrections(
            readMemoryWord(selected.ltftLowAddress),
            readMemoryWord(selected.ltftHighAddress),
            readMemoryWord(selected.additiveAddress),
        )
    }

    fun optimize(group: Boolean, publish: (Sample) -> Unit, notice: (String) -> Unit) {
        if (!fast) return
        if (!stn) {
            optimizeElm(group, publish, notice)
            return
        }
        if (fallbacks > 0) return
        profile = "STN compatible"
        if (at("STPTRQ 60", true)) {
            p3 = true
            p3Ms = 60
            gap = 0
            profile = "STN P3 adaptador"
        }
        log.event(
            "transport_profile",
            "profile" to profile,
            "host_gap_ms" to gap,
            "p3_ms" to p3Ms,
            "response_timeout_hex" to "80",
        )
        if (!group) return
        notice(tr("OBDLink · midiendo tiempos", "OBDLink · measuring timings"))
        try {
            val base = ArrayList<Double>()
            val trial = ArrayList<Double>()
            repeat(4) {
                val s = acquire(true)
                publish(s)
                base.add(s.acquisitionMs)
                if (fallbacks > 0 || !fast) return
            }
            if (at("STIP4 0", true)) {
                p4 = true
                repeat(6) {
                    val s = acquire(true)
                    publish(s)
                    trial.add(s.acquisitionMs)
                    if (fallbacks > 0 || !fast) {
                        rollback(tr("Atajo rechazado", "Shortcut rejected"))
                        return
                    }
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
                    "baseline_median_ms" to b,
                    "trial_median_ms" to t,
                    "p4_zero_accepted" to accept,
                    "profile" to profile,
                )
            }
            optimizeStnRequestGap(group, publish)
        } catch (e: LinkTimeout) {
            // No prompt: never send recovery commands into an unsynchronised stream.
            throw e
        } catch (e: DiagnosticException) {
            rollback(e.message ?: tr("Error prueba", "Trial error"))
            log.event("transport_benchmark_error", "error" to e.toString())
        }
        notice(I18n.profile(profile) + tr(" · lectura preparada", " · ready to read"))
    }

    /** Match Python's STN trial: P3=25 must beat both 60 ms controls by >=3% and >=8 ms.
     * STPTRQ measures from the actual ECU response; hold P4 fixed throughout.
     * SW404 03B714/000CA8 reports P3min=0x2C*0.5=22 ms in its timing limits.
     * Thus 25 ms is a trial candidate, not a guarantee for every ECU.
     * Never change ECU timing parameters or retry a failed profile.
     * Reference: https://www.obdlink.com/frpm (STPTRQ and STIP4).
     */
    private fun optimizeStnRequestGap(group: Boolean, publish: (Sample) -> Unit) {
        if (!p3 || !fast || !group || fallbacks > 0) return
        val baseline = ArrayList<Double>()
        val trial = ArrayList<Double>()
        val control = ArrayList<Double>()
        val fallbackCount = fallbacks
        val outcome = linkedMapOf<String, Any?>(
            "accepted" to false,
            "failed" to false,
            "reason" to null,
        )

        fun collect(count: Int, values: MutableList<Double>) {
            repeat(count + 1) { index ->
                val sample = acquire(true)
                publish(sample)
                if (fallbacks != fallbackCount || !fast)
                    throw DiagnosticException(tr("Prueba cancelada tras recuperar la comunicación", "Trial cancelled after recovering communication"))
                if (!sample.acquisitionMs.isFinite() || sample.acquisitionMs <= 0)
                    throw DiagnosticException(tr("Duración de adquisición inválida durante la prueba", "Invalid acquisition duration during trial"))
                // Exclude the first request after each local adapter command.
                if (index > 0) values.add(sample.acquisitionMs)
            }
        }

        try {
            collect(3, baseline)
            if (!at("STPTRQ 25", true)) {
                outcome["reason"] = "STPTRQ 25 unsupported"
                return
            }
            p3Ms = 25
            collect(6, trial)
            at("STPTRQ 60")
            p3Ms = 60
            collect(3, control)
            val base = median(baseline)
            val last = median(control)
            val candidate = median(trial)
            val reference = minOf(base, last)
            val accept = reference - candidate >= 8.0 && candidate <= reference * .97
            if (accept) {
                at("STPTRQ 25")
                p3Ms = 25
                profile = "STN P3=25 ms" + if (p4) " · P4=0" else ""
            }
            outcome.putAll(mapOf(
                "accepted" to accept,
                "baseline_median_ms" to base,
                "control_median_ms" to last,
                "trial_median_ms" to candidate,
                "gain_ms" to reference - candidate,
                "gain_pct" to (reference - candidate) / reference * 100,
            ))
        } catch (e: InterruptedException) {
            outcome["failed"] = true
            outcome["reason"] = tr("Prueba interrumpida por el usuario", "Trial interrupted by user")
            throw e
        } catch (e: DiagnosticException) {
            // The caller restores timings only while the prompt is intact.
            outcome["failed"] = true
            outcome["reason"] = e.message
            throw e
        } finally {
            log.event(
                "stn_p3_benchmark",
                "baseline_ms" to baseline,
                "trial_ms" to trial,
                "control_ms" to control,
                *outcome.toList().toTypedArray(),
                "selected_p3_ms" to p3Ms,
                "p4_ms" to if (p4) 0 else 5,
                "profile" to profile,
                "timing_basis" to "host_acquisition_not_kline",
            )
        }
    }

    /** Read-only trial, matching mercedes_dashboard.optimize_elm_transport.
     * Measure acquisition including host waits, discarding each phase's first cycle.
     * Both 60 ms controls must show >=3% and >=8 ms savings. This measures host
     * observations, not physical K-line P3 compliance, and never changes adapter PPs.
     */
    private fun optimizeElm(group: Boolean, publish: (Sample) -> Unit, notice: (String) -> Unit) {
        if (!group || elmTimingProbeDisabled || elmTimingProbeCompleted) return
        hostGapProbing = true
        hostGapTuned = false
        val fallbackCount = fallbacks
        val baseline = ArrayList<Double>()
        val trial20 = ArrayList<Double>()
        val trial0 = ArrayList<Double>()
        val control = ArrayList<Double>()
        val outcome = linkedMapOf<String, Any?>(
            "selected_host_gap_ms" to 60L,
            "gain_ms" to 0.0,
            "gain_pct" to 0.0,
            "failed" to false,
            "reason" to null,
        )
        notice(tr("ELM · comprobando espera del programa (60 / 20 / 0 ms)", "ELM · testing host wait (60 / 20 / 0 ms)"))

        fun collect(waitMs: Long, count: Int, values: MutableList<Double>) {
            gap = waitMs
            repeat(count + 1) { index ->
                val sample = acquire(true)
                publish(sample)
                if (elmTimingProbeDisabled || fallbacks != fallbackCount || !fast)
                    throw DiagnosticException(tr("Prueba cancelada tras recuperar la comunicación", "Trial cancelled after recovering communication"))
                if (!sample.acquisitionMs.isFinite() || sample.acquisitionMs <= 0)
                    throw DiagnosticException(tr("Duración de adquisición inválida durante la prueba", "Invalid acquisition duration during trial"))
                if (index > 0) values.add(sample.acquisitionMs)
            }
        }

        fun worthwhile(candidate: Double, reference: Double) =
            reference - candidate >= 8.0 && candidate <= reference * .97

        try {
            collect(60, 6, baseline)
            collect(20, 6, trial20)
            collect(0, 6, trial0)
            collect(60, 3, control)
            val base = median(baseline)
            val last = median(control)
            val reference = minOf(base, last)
            val ms20 = median(trial20)
            val ms0 = median(trial0)
            var selected = 60L
            var selectedMedian = reference
            if (worthwhile(ms20, reference)) {
                selected = 20
                selectedMedian = ms20
            }
            if (worthwhile(ms0, reference) && (selected == 60L || worthwhile(ms0, ms20))) {
                selected = 0
                selectedMedian = ms0
            }
            gap = selected
            hostGapTuned = selected < 60
            profile = if (hostGapTuned) "ELM host $selected ms" else "ELM compatible"
            outcome.putAll(mapOf(
                "baseline_median_ms" to base,
                "control_median_ms" to last,
                "reference_median_ms" to reference,
                "trial_20_median_ms" to ms20,
                "trial_0_median_ms" to ms0,
                "selected_host_gap_ms" to selected,
                "gain_ms" to reference - selectedMedian,
                "gain_pct" to (reference - selectedMedian) / reference * 100,
            ))
        } catch (e: LinkTimeout) {
            // No prompt: never send recovery commands into an unsynchronised stream.
            outcome["failed"] = true
            outcome["reason"] = e.message
            elmTimingProbeDisabled = true
            throw e
        } catch (e: InterruptedException) {
            outcome["failed"] = true
            outcome["reason"] = tr("Prueba interrumpida por el usuario", "Trial interrupted by user")
            throw e
        } catch (e: DiagnosticException) {
            rollback(e.message ?: tr("Error prueba", "Trial error"))
            outcome["failed"] = true
            outcome["reason"] = e.message
        } finally {
            hostGapProbing = false
            elmTimingProbeCompleted = true
            log.event(
                "elm_transport_benchmark",
                "baseline_ms" to baseline,
                "trial_20_ms" to trial20,
                "trial_0_ms" to trial0,
                "control_ms" to control,
                *outcome.toList().toTypedArray(),
                "profile" to profile,
                "timing_basis" to "host_acquisition_including_wait_not_kline",
                "disabled" to elmTimingProbeDisabled,
            )
        }
        notice(I18n.profile(profile) + tr(" · lectura preparada", " · ready to read"))
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        return if (sorted.size % 2 == 1) sorted[sorted.size / 2]
        else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
    }
}
