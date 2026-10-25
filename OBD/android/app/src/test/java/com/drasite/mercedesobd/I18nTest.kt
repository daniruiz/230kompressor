package com.drasite.mercedesobd

import java.util.Locale
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class I18nTest {
    private val originalProvider = I18n.localeProvider

    @After fun restoreLocale() { I18n.localeProvider = originalProvider }

    @Test fun selectsSpanishForAllRegionsAndEnglishOtherwise() {
        for (tag in listOf("es", "es-ES", "es-MX", "es-AR")) {
            I18n.localeProvider = { Locale.forLanguageTag(tag) }
            assertEquals("Conectar", tr("Conectar", "Connect"))
        }
        for (tag in listOf("en-US", "en-GB", "de-DE", "fr-FR", "und")) {
            I18n.localeProvider = { Locale.forLanguageTag(tag) }
            assertEquals("Connect", tr("Conectar", "Connect"))
        }
    }

    @Test fun readsCurrentConfigurationAfterLanguageChanges() {
        var current = Locale.forLanguageTag("es-MX")
        I18n.localeProvider = { current }
        val model = Model()
        assertEquals("Selecciona Demo o un adaptador emparejado", model.snapshot().status)
        current = Locale.UK
        model.clear()
        assertEquals("Preparing session", model.snapshot().status)
        model.stop()
        assertEquals("Session stopped · logs saved", model.snapshot().status)
        // The internal state remains stable for logging; only its label is translated.
        assertEquals("PARADO", model.snapshot().mode)
        assertEquals("STOPPED", I18n.mode(model.snapshot().mode))
    }

    @Test fun errorsTranslateWithoutChangingRequestsOrMeasurements() {
        val values = ArrayList<Map<String, Any?>>()
        for (tag in listOf("en-US", "es-ES")) {
            I18n.localeProvider = { Locale.forLanguageTag(tag) }
            assertEquals("2C01D2011104", Decoder.definition(1, 4))
            val sample = Decoder.pairs(ByteArray(24) { 1 })
            values.add(sample.injectionMetrics.logValues())
            try {
                Decoder.pairs(byteArrayOf())
                fail("Expected an invalid payload error")
            } catch (e: DiagnosticException) {
                assertEquals(if (tag == "es-ES") "Se esperaban doce pares" else "Expected twelve pairs", e.message)
            }
        }
        assertEquals(values[0], values[1])
    }
}
