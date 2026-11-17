package com.drasite.mercedesobd

import org.junit.Assert.*
import org.junit.Test

class Sim4leLogTest {
    @Test fun appendsAllRawBytesWithoutRenamingExistingColumns() {
        val old = listOf("Time", "utc", "rpm", "load_raw", "throttle_pct", "maf_g_s_est", "maf_ecu_g_s_est",
            "injection_ms_est", "injector_duty_est_pct", "advance_deg") +
            (1..4).map { "knock_channel_${it}_deg_est" } + listOf("stft_a_pct", "ltft_a_pct",
                "coolant_C", "iat_C", "oxygen_voltage_V", "adapter_voltage_V")
        assertEquals(old, CsvLog.fields.take(old.size))
        assertEquals(CsvLog.fields.size, CsvLog.fields.toSet().size)
        assertEquals(178, CsvLog.fields.count { it.startsWith("sim4le_2101_byte_") })
        val data = ByteArray(178) { ((it * 7) and 255).toByte() }
        val sample = Sim4leDecoder.decode(byteArrayOf(0x61, 1) + data, at = 100, acquisitionMs = 220.0)
        val values = Sim4leLog.snapshotFields(sample)
        for (i in data.indices) assertEquals(data[i].u(), values["sim4le_2101_byte_${i.toString().padStart(3, '0')}"])
        assertEquals(data.hex(), values["sim4le_2101_raw"])
        val load16 = data[13].u() or (data[14].u() shl 8)
        assertEquals(data[12].u(), sample.values.load)
        assertEquals(data[12].u(), values["scaled_load_raw"])
        assertEquals(load16, values["load_raw16"])
        assertEquals(load16, values["load_raw"])
        assertEquals(load16, values["load_value"])
        val row = CsvLog.sampleRow(values, 1250).trimEnd('\r', '\n').split(',')
        assertEquals("1.250", row.first())
        assertEquals("", row[CsvLog.fields.indexOf("stft_a_pct")])
        assertEquals("", row[CsvLog.fields.indexOf("ltft_a_pct")])
        assertEquals("SIM4LE", row[CsvLog.fields.indexOf("ecu_family")])
        assertEquals(load16.toString(), row[CsvLog.fields.indexOf("load_raw")])
        assertEquals(data[12].u().toString(), row[CsvLog.fields.indexOf("scaled_load_raw")])
        assertEquals(data[177].u().toString(), row.last())
    }

    @Test fun keepsInternalTermsDistinctFromObdTrimsAndElectricalDuty() {
        val sample = Sim4leDecoder.decode(byteArrayOf(0x61, 1) + ByteArray(178))
        val values = Sim4leLog.snapshotFields(sample)
        assertNull(values["stft_a_pct"])
        assertNull(values["ltft_a_pct"])
        assertNull(values["duty_over_100_est"])
        assertNull(values["adapter_voltage_source"])
        assertEquals(0.0, values["lambda_correction_pct"])
        assertEquals(0.0, values["ltft_applied_pct"])
        assertEquals("SIM4LE 2101 processed ECU airflow", values["maf_source"])
        assertEquals("base_4us_nominal_720deg", values["injection_breakdown_status"])
        assertFalse(values.containsKey("F746_raw"))
    }
}
