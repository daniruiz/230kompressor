package com.drasite.mercedesobd

import java.util.Locale

enum class EcuFamily { ME21, SIM4LE }

data class AuxiliaryReading(val raw: Int, val at: Long)

/** M10062/6577815126: fixed local-identifier 01, verified against the ROM pointer table.
 * Offsets start AFTER 61 01. Channel order follows D164..D167, not verified cylinder numbers.
 */
object Sim4le {
    const val FRAME_LENGTH = 180
    val auxiliaryPids = intArrayOf(0x04, 0x06, 0x07, 0x0E)

    /** ATH0 long KWP response: display wrapping is allowed, extra traffic is not.
     * Never discard BUFFER FULL or similar adapter errors around an apparently valid frame.
     */
    fun payload(reply: String, service: Int, prefix: String, length: Int): ByteArray {
        val lines = reply.trim().removeSuffix(">").trim().replace('\r', '\n').lines()
            .map { it.replace(Regex("\\s+"), "").uppercase(Locale.ROOT) }.filter { it.isNotEmpty() }
        if (lines.isEmpty() || lines.any { it.length % 2 != 0 || !it.matches(Regex("[0-9A-F]+")) })
            throw DiagnosticException(tr("Respuesta SIM4LE inválida o incompleta", "Invalid or incomplete SIM4LE response"))
        val joined = lines.joinToString("")
        if (joined.length == 6 && joined.startsWith("7F%02X".format(Locale.ROOT, service)))
            throw NegativeResponse(service, joined.substring(4).toInt(16))
        if (!joined.startsWith(prefix) || joined.length != length * 2)
            throw DiagnosticException(tr("Longitud o identificador SIM4LE incorrecto", "Incorrect SIM4LE response length or identifier"))
        return unhex(joined)
    }

    fun grouped(reply: String): Sample {
        val b = payload(reply, 0x21, "6101", FRAME_LENGTH).copyOfRange(2, FRAME_LENGTH)
        return Sample(
            rpm = Decoder.word(b, 8),
            injection = Decoder.word(b, 17),
            load = 0,
            corrections = intArrayOf(b[37].u(), b[39].u(), b[40].u(), b[38].u()),
            coolant = b[0].u(),
            iat = b[1].u(),
            maf = Decoder.word(b, 10),
            throttle = Decoder.word(b, 2),
            oxygen = Decoder.word(b, 74),
            pairs = b.hex(),
            family = EcuFamily.SIM4LE,
            mapKpa = minOf((Decoder.word(b, 6).toLong() * 0x10FA / 524288).toInt(), 255),
            speedKph = b[53].u(),
        )
    }
}
