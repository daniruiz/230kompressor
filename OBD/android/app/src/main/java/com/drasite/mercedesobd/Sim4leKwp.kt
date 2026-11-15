package com.drasite.mercedesobd

import java.util.Locale

/** Reassemble wrapped ELM text, then validate complete addressed KWP frames. */
internal object Sim4leKwp {
    fun positive(reply: String, request: String, prefix: String, length: Int): ByteArray {
        val responses = positives(reply, request, prefix, length)
        if (responses.size != 1) throw DiagnosticException("SIM4LE: expected one complete engine response")
        return responses.single()
    }

    /** Validate every frame before returning any result; 03 may contain several 43 frames. */
    fun positives(reply: String, request: String, prefix: String, length: Int): List<ByteArray> {
        val upper = reply.uppercase(Locale.ROOT)
        val errors = listOf("BUFFER FULL", "DATA ERROR", "BUS ERROR", "BUS BUSY", "UNABLE TO CONNECT",
            "CAN ERROR", "RX ERROR", "STOPPED", "LV RESET", "<ERROR")
        errors.firstOrNull { upper.contains(it) }?.let { throw DiagnosticException(it) }
        val echo = request.replace(" ", "").uppercase(Locale.ROOT)
        val chunks = ArrayList<ByteArray>()
        for (source in upper.replace('>', '\n').replace('\r', '\n').lines()) {
            var line = source.trim()
            val compact = line.replace(Regex("\\s+"), "")
            if (compact.isEmpty() || (chunks.isEmpty() && (compact == echo || compact == echo + "1"))) continue
            line = line.replace(Regex("(?:SEARCHING|BUS INIT|BUSINIT)[ .:]*(?:OK)?"), "").trim()
            if (line.isEmpty() || line == "OK") continue
            if (line == "NO DATA" || line == "?" || line.contains("ERROR")) throw DiagnosticException(line)
            val hex = line.replace(Regex("\\s+"), "")
            if (hex.length % 2 != 0 || !hex.matches(Regex("[0-9A-F]+")))
                throw DiagnosticException("SIM4LE: non-hexadecimal response")
            chunks.add(unhex(hex))
        }
        val bytes = chunks.fold(byteArrayOf()) { all, part -> all + part }
        val positives = ArrayList<ByteArray>()
        val expectedPrefix = unhex(prefix)
        val service = unhex(request)[0].u()
        var pending = false
        var pos = 0
        while (pos < bytes.size) {
            if (bytes.size - pos < 4) throw DiagnosticException("SIM4LE: incomplete KWP header")
            val format = bytes[pos].u()
            if ((format and 0xC0) !in listOf(0x80, 0xC0))
                throw DiagnosticException("SIM4LE: addressed KWP header required")
            var size = format and 0x3F
            var head = 3
            if (size == 0) { size = bytes[pos + 3].u(); head = 4 }
            val end = pos + head + size + 1
            if (size == 0 || end > bytes.size) throw DiagnosticException("SIM4LE: truncated KWP frame")
            val checksum = (pos until end - 1).sumOf { bytes[it].u() } and 255
            if (checksum != bytes[end - 1].u()) throw DiagnosticException("SIM4LE: incorrect KWP checksum")
            if (bytes[pos + 1].u() != 0xF1) throw DiagnosticException("SIM4LE: wrong tester address")
            val source = bytes[pos + 2].u()
            val payload = bytes.copyOfRange(pos + head, end - 1)
            pos = end
            if (source != 0x01) throw DiagnosticException("SIM4LE: wrong ECU address")
            if (payload.size == 3 && payload[0].u() == 0x7F && payload[1].u() == service) {
                if (payload[2].u() == 0x78) { pending = true; continue }
                throw NegativeResponse(service, payload[2].u())
            }
            if (payload.size != length || !payload.take(expectedPrefix.size).toByteArray().contentEquals(expectedPrefix))
                throw DiagnosticException("SIM4LE: unexpected service or response length")
            positives.add(payload)
        }
        if (positives.isEmpty()) {
            if (pending) throw NegativeResponse(service, 0x78)
            throw DiagnosticException("SIM4LE: expected a complete engine response")
        }
        return positives
    }
}
