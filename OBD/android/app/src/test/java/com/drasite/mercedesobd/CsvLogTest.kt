package com.drasite.mercedesobd

import org.junit.Assert.*
import org.junit.Test

class CsvLogTest {
    @Test fun bothFamiliesExportTheirCanonicalLogKeysWithoutTranslation() {
        assertEquals(239, CsvLog.fields.size)
        assertEquals(CsvLog.fields.size, CsvLog.fields.toSet().size)
        assertTrue(CsvLog.fields.none { it.contains("_est") })
        assertTrue(CsvLog.fields.none { it in listOf("stft_a_pct", "ltft_a_pct",
            "lambda_correction_pct", "ltft_applied_pct") })
        assertEquals(listOf("stft_pct", "ltft_pct"), CsvLog.fields.subList(14, 16))
        for (family in DemoEcu.entries) {
            val demo = DemoSamples.sample(family, 2.0, 2000)
            val sample = if (family == DemoEcu.ME21) demo.copy(mafEcuRaw = 100) else demo
            val v = sample.values
            val source = linkedMapOf<String, Any?>(
                "ecu_family" to sample.ecuFamily, "rpm" to v.rpm, "load_raw" to sample.load,
                "throttle_pct" to v.throttlePercent, "advance_deg" to v.advanceDegrees,
                "coolant_C" to v.coolantC, "iat_C" to v.iatC, "oxygen_voltage_V" to v.oxygenVolts,
                "maf_g_s" to v.mafGramsPerSecond, "maf_ecu_g_s" to v.ecuMafGramsPerSecond,
                "injection_ms" to v.injectionMs, "injector_duty_pct" to v.dutyPercent,
                "stft_pct" to v.stftPercent, "ltft_pct" to v.ltftPercent,
                "adapter_voltage_V" to sample.adapterVoltage,
            )
            sample.injectionMetrics?.let { source.putAll(it.logValues()) }
            if (family == DemoEcu.SIM4LE) source.putAll(Sim4leLog.snapshotFields(sample))
            v.retardDegrees.forEachIndexed { i, value -> source["knock_channel_${i + 1}_deg"] = value }
            val before = source.toMap()
            val cells = CsvLog.sampleRow(source, 1250).trimEnd('\r', '\n').split(',')
            fun cell(name: String) = cells[CsvLog.fields.indexOf(name)]
            assertEquals(CsvLog.fields.size, cells.size)
            assertEquals("1.250", cell("Time"))
            for (key in listOf("rpm", "throttle_pct", "advance_deg", "coolant_C", "iat_C",
                    "oxygen_voltage_V", "maf_g_s", "maf_ecu_g_s", "injection_ms",
                    "injector_duty_pct", "stft_pct", "ltft_pct") +
                    (1..4).map { "knock_channel_${it}_deg" }) {
                assertEquals(source[key].toString(), cell(key))
                assertTrue(cell(key).isNotEmpty())
            }
            assertEquals(v.stftPercent.toString(), cell("stft_pct"))
            assertEquals(v.ltftPercent.toString(), cell("ltft_pct"))
            assertEquals(before, source)
            if (family == DemoEcu.ME21) {
                assertEquals(sample.adapterVoltage.toString(), cell("adapter_voltage_V"))
                assertEquals("", cell("ecu_voltage_V"))
                assertEquals(sample.load.toString(), cell("load_raw"))
            } else {
                assertEquals("", cell("adapter_voltage_V"))
                assertEquals(v.ecuVoltageV.toString(), cell("ecu_voltage_V"))
                assertEquals(sample.ecuFields["load_raw16"].toString(), cell("load_raw"))
                assertEquals(sample.ecuFields["scaled_load_raw"].toString(), cell("scaled_load_raw"))
            }
        }
    }

    @Test fun sharedTrimsKeepZerosNegativeNullAndMissingValues() {
        for (family in listOf("ME2.1", "SIM4LE")) {
            for ((short, long) in listOf(0.0 to -6.25, -12.5 to 0.0, null to null)) {
                val source = linkedMapOf<String, Any?>("ecu_family" to family,
                    "stft_pct" to short, "ltft_pct" to long)
                val before = source.toMap()
                val cells = CsvLog.sampleRow(source, 0).trimEnd('\r', '\n').split(',')
                assertEquals(short?.toString().orEmpty(), cells[CsvLog.fields.indexOf("stft_pct")])
                assertEquals(long?.toString().orEmpty(), cells[CsvLog.fields.indexOf("ltft_pct")])
                assertEquals(before, source)
                source.remove("stft_pct")
                source.remove("ltft_pct")
                val missing = CsvLog.sampleRow(source, 0).trimEnd('\r', '\n').split(',')
                assertEquals("", missing[CsvLog.fields.indexOf("stft_pct")])
                assertEquals("", missing[CsvLog.fields.indexOf("ltft_pct")])
            }
        }
    }

    @Test fun injectionAndAdaptationProducersUseCanonicalJsonKeys() {
        val injection = Injection.metrics(3000, 3000, 137)
        val values = injection.logValues()
        assertTrue(values.keys.none { it.contains("_est") })
        assertEquals(injection.totalMs, values["injection_ms"])
        assertEquals(injection.duty, values["injector_duty_pct"])
        assertEquals(injection.deadtimeMs, values["injector_deadtime_ms"])
        assertEquals(injection.beforeDeadtimeMs, values["injection_before_deadtime_ms"])
        for (family in DemoEcu.entries) {
            val corrections = DemoSamples.corrections(family)
            val fields = corrections.logValues().toMap()
            assertTrue(fields.keys.none { it.contains("_est") })
            assertTrue(fields.containsKey("additive_ms"))
            assertEquals(corrections.values.additiveMs, fields["additive_ms"])
        }
    }
}
