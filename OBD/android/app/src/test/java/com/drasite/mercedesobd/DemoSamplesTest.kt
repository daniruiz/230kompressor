package com.drasite.mercedesobd

import org.junit.Assert.*
import org.junit.Test

class DemoSamplesTest {
    @Test fun demoDefaultsToMe21AndAcceptsBothFamilyNames() {
        assertEquals(DemoEcu.ME21, DemoEcu.fromName(null))
        assertEquals(DemoEcu.ME21, DemoEcu.fromName("ME2.1"))
        assertEquals(DemoEcu.ME21, DemoEcu.fromName("me21"))
        assertEquals(DemoEcu.SIM4LE, DemoEcu.fromName("sim4le"))
        for (ecu in DemoEcu.entries) assertEquals(ecu, DemoEcu.fromName(ecu.name))
    }

    @Test fun me21KeepsRawReadingsAndUsesAdapterVoltage() {
        val sample = DemoSamples.sample(DemoEcu.ME21, 2.0, 42, 125.0)
        assertEquals("ME2.1", sample.ecuFamily)
        assertNull(sample.measurements)
        assertNotNull(sample.injectionMetrics)
        assertEquals(Decoder.ids.toList(), sample.diagnosticIds)
        assertEquals("demo", sample.mafCalibrationStatus)
        assertTrue(sample.mafGramsPerSecond!! > 0.0)
        assertEquals("ok", sample.adapterVoltageStatus)
        assertEquals(0L, sample.adapterVoltageAgeMs)
        assertTrue(sample.adapterVoltage!! in 13.5..14.1)
        assertNull(sample.values.ecuVoltageV)
        assertNull(sample.values.mapKpa)
        assertEquals(42L, sample.at)
        assertEquals(125.0, sample.acquisitionMs, 0.0)
    }

    @Test fun sim4leExercisesSnapshotFieldsWithoutMe21RawReadings() {
        val sample = DemoSamples.sample(DemoEcu.SIM4LE, 2.0, 42, 125.0)
        val decoded = Sim4leDecoder.decode(byteArrayOf(0x61, 0x01) + sample.ecuRaw)
        assertEquals(decoded.values, sample.values)
        assertEquals("SIM4LE", sample.ecuFamily)
        assertEquals(178, sample.ecuRaw.size)
        assertTrue(sample.diagnosticIds.isEmpty())
        assertTrue(sample.corrections.isEmpty())
        assertEquals(0, sample.injection)
        assertNull(sample.injectionMetrics)
        assertNull(sample.adapterVoltage)
        val values = sample.values
        assertEquals("raw", values.loadUnit)
        assertEquals(65535.0, values.loadMaximum, 0.0)
        assertTrue(values.load!! > 255)
        assertTrue(values.mapKpa!! > 0.0)
        assertTrue(values.mafGramsPerSecond!! > 0.0)
        assertTrue(values.ecuVoltageV!! in 13.5..14.1)
        assertEquals(values.injectionRaw!! * .004, values.injectionMs!!, 0.0)
        assertEquals(values.injectionMs!! * values.rpm!! / 1200, values.dutyPercent!!, 0.0)
        assertEquals("base_4us_nominal_720deg", values.injectionBasis)
        assertEquals(42L, sample.at)
        assertEquals(125.0, sample.acquisitionMs, 0.0)
    }

    @Test fun bothDemosHoldLeanNeutralAndRichOxygenReadings() {
        val expected = listOf(.18 to false, .425 to false, .43 to null, .435 to null,
            .44 to null, .445 to true, .82 to true)
        for (ecu in DemoEcu.entries) for ((index, reading) in expected.withIndex()) {
            for (offset in listOf(0.0, 1.875)) {
                val values = DemoSamples.sample(ecu, index * 2.0 + offset, 100).values
                assertEquals(reading.first, values.oxygenVolts!!, 0.0)
                assertEquals(reading.second, values.oxygenRich)
            }
        }
    }

    @Test fun bothDemosDriveChartsAndKnockAlerts() {
        for (ecu in DemoEcu.entries) {
            val model = Model()
            model.clear()
            model.mode("DEMO · SIN COCHE", ecu.family)
            model.setEcu(null, null, "demo")
            assertFalse(model.accept(DemoSamples.sample(ecu, 1.875, 1875)).second)
            val (sample, raised) = model.accept(DemoSamples.sample(ecu, 2.0, 2000))
            assertTrue(raised)
            assertTrue(sample.values.highRetard)
            assertEquals(125L, sample.cycleMs)
            val display = model.snapshot()
            assertEquals(listOf(1, 2), display.lastAlertChannels)
            assertEquals(1, display.count)
            assertEquals(2, display.rpm.size)
            assertEquals(sample.values.load!!.toDouble(), display.load.last(), 0.0)
            assertEquals(sample.values.dutyPercent!!, display.duty.last(), 0.0)
            assertEquals(6.0, display.knock.last(), 0.0)
            assertEquals(ecu.family, display.profile)
            assertEquals("demo", display.ecuIdentificationStatus)
            assertFalse(model.accept(DemoSamples.sample(ecu, 2.125, 2125)).second)
        }
    }

    @Test fun bothDemosCoverNormalAndHotTemperaturesAndChangingMetrics() {
        for (ecu in DemoEcu.entries) {
            val samples = (0..480).map { DemoSamples.sample(ecu, it / 8.0, it * 125L) }
            val values = samples.map { it.values }
            assertTrue(values.any { it.coolantC!! > TemperatureAlerts.MOTOR_LIMIT })
            assertTrue(values.any { it.coolantC!! < TemperatureAlerts.MOTOR_LIMIT })
            assertTrue(values.any { it.iatC!! > TemperatureAlerts.INTAKE_LIMIT })
            assertTrue(values.any { it.iatC!! < TemperatureAlerts.INTAKE_LIMIT })
            assertTrue(values.map { it.rpm }.distinct().size > 10)
            assertTrue(values.map { it.load }.distinct().size > 10)
            assertTrue(values.map { it.mafGramsPerSecond }.distinct().size > 10)
            assertTrue(values.all { it.dutyPercent!! >= 0.0 && it.throttlePercent!! in 0.0..100.0 })
            val alerts = TemperatureAlerts()
            assertTrue(samples.count { alerts.accept(it) } >= 2)
        }
    }
}
