package com.drasite.mercedesobd

import java.util.Locale

/** UI text only. Keep protocol commands and structured log values locale independent. */
object I18n {
    // Android binds this to its current resource configuration at application startup.
    // The JVM default also makes the diagnostic core usable without an Android context.
    var localeProvider: () -> Locale = { Locale.getDefault() }

    val locale: Locale get() = localeProvider()
    fun isSpanish(locale: Locale) = locale.language.equals("es", ignoreCase = true)
    fun text(spanish: String, english: String): String =
        if (isSpanish(locale)) spanish else english

    fun mode(value: String): String = when (value) {
        "PARADO" -> tr("PARADO", "STOPPED")
        "INICIANDO" -> tr("INICIANDO", "STARTING")
        "AGRUPADO" -> tr("AGRUPADO", "GROUPED")
        "DEMO · SIN COCHE" -> tr("DEMO · SIN COCHE", "DEMO · NO CAR")
        else -> value
    }

    fun profile(value: String): String = when (value) {
        "STN P3 adaptador" -> tr("STN P3 adaptador", "STN adapter P3")
        "STN rápido P4=0" -> tr("STN rápido P4=0", "STN fast P4=0")
        else -> value
    }
}

fun tr(spanish: String, english: String): String = I18n.text(spanish, english)
