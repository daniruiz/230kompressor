package com.drasite.mercedesobd

/** ME2.1 service A1: E1 + code (BE) + status + internal ID + 53 snapshot bytes.
 * SW404: 03B37C..03B448; final E1 FF FF count is mandatory, including count zero.
 */
data class FaultCode(val code: String, val status: Int, val raw: String) {
    fun label() = "$code · " + tr("estado", "status") + " %02X".format(status)
}

object FaultCodes {
    fun decode(reply: String): List<FaultCode> {
        val frames = reply.replace('>', ' ').replace('\r', '\n').lines()
            .map { it.replace(Regex("\\s+"), "").uppercase(java.util.Locale.ROOT) }
            .filter { it.isNotEmpty() }
        val result = ArrayList<FaultCode>()
        var complete = false
        for (text in frames) {
            if (!text.matches(Regex("(?:[0-9A-F]{2})+")))
                throw DiagnosticException(tr("Respuesta de averías inválida: $text", "Invalid fault response: $text"))
            val f = unhex(text)
            if (f.size == 3 && f[0].u() == 0x7F && f[1].u() == 0xA1)
                throw NegativeResponse(0xA1, f[2].u())
            if (complete || f[0].u() != 0xE1)
                throw DiagnosticException(tr("Secuencia de averías inválida", "Invalid fault sequence"))
            if (f.size == 4 && f[1].u() == 255 && f[2].u() == 255) {
                if (f[3].u() != result.size)
                    throw DiagnosticException(tr("Lectura de averías incompleta", "Incomplete fault read"))
                complete = true
            } else {
                if (f.size != 58 || result.size >= 255)
                    throw DiagnosticException(tr("Registro de avería incompleto", "Incomplete fault record"))
                val first = f[1].u()
                val code = "%c%01X%01X%02X".format("PCBU"[first shr 6], (first shr 4) and 3, first and 15, f[2].u())
                result.add(FaultCode(code, f[3].u(), text))
            }
        }
        if (!complete) throw DiagnosticException(tr("Falta el final de la lista de averías", "Missing end of fault list"))
        return result
    }
}
