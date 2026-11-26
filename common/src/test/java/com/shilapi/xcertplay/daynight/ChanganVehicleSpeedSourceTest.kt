package com.shilapi.xcertplay.daynight

import com.shilapi.xcertplay.transport.PascdEncoder
import com.shilapi.xcertplay.transport.VehicleGear
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE)
class ChanganVehicleSpeedSourceTest {

    @Test
    fun `canonicalGear and gearText preserve M1-M6 and PRND mapping`() {
        val expected = listOf(
            0 to (0 to ""),
            -1 to (0 to ""),
            1 to (5 to "M1"),
            2 to (6 to "M2"),
            3 to (7 to "M3"),
            4 to (8 to "M4"),
            5 to (9 to "M5"),
            6 to (10 to "M6"),
            7 to (1 to "P"),
            8 to (2 to "R"),
            9 to (3 to "N"),
            10 to (4 to "D"),
            11 to (5 to ""),
        )
        for ((raw, pair) in expected) {
            assertEquals("canonicalGear($raw)", pair.first, CarLampSource.canonicalGear(raw))
            assertEquals("gearText($raw)", pair.second, CarLampSource.gearText(raw))
        }
    }

    @Test
    fun `mapGear handles PRND and manual gears M1-M6 uniformly`() {
        assertNull(ChanganVehicleSpeedSource.mapGear(0))
        assertEquals(VehicleGear.PARK, ChanganVehicleSpeedSource.mapGear(1))
        assertEquals(VehicleGear.REVERSE, ChanganVehicleSpeedSource.mapGear(2))
        assertEquals(VehicleGear.NEUTRAL, ChanganVehicleSpeedSource.mapGear(3))
        for (gear in 4..10) {
            assertEquals("gear=$gear", VehicleGear.DRIVE, ChanganVehicleSpeedSource.mapGear(gear))
        }
        assertNull(ChanganVehicleSpeedSource.mapGear(13))
    }

    @Test
    fun `throttles 100ms samples to 200ms and encodes valid PASCD NMEA`() {
        val harness = Harness()
        harness.source.start()
        assertEquals(1, harness.starts)

        harness.emit(1_000L, speedKmh = 36f, canonicalGear = 6, gearRaw = 2, gearText = "M2")
        harness.emit(1_100L, speedKmh = 54f, canonicalGear = 6, gearRaw = 2, gearText = "M2") // dropped (<200ms)
        harness.emit(1_220L, speedKmh = 72f, canonicalGear = 10, gearRaw = 6, gearText = "M6")

        val reading = harness.source.drain()
        assertNotNull(reading)
        reading!!
        assertEquals(VehicleGear.DRIVE, reading.gear)
        assertEquals(2, reading.samples.size)
        assertEquals(1_000L, reading.samples[0].elapsedMillis)
        assertEquals(10.0, reading.samples[0].metersPerSecond, 1e-4)
        assertEquals(1_220L, reading.samples[1].elapsedMillis)
        assertEquals(20.0, reading.samples[1].metersPerSecond, 1e-4)

        val nmea = PascdEncoder.encode(reading)
        assertNotNull(nmea)
        assertTrue("Expected PASCD prefix in $nmea", nmea!!.startsWith("\$PASCD,1.000,C,D,0,2,0.00,10.000,0.22,20.000*"))
        assertNull(harness.source.drain())
    }

    @Test
    fun `filters disconnected invalid speeds and handles unknown gear fallback`() {
        val harness = Harness()
        harness.source.start()

        // Disconnected or invalid speed samples must be ignored.
        harness.emit(500L, speedKmh = 40f, canonicalGear = 4, connected = false)
        harness.emit(750L, speedKmh = Float.NaN, canonicalGear = 4)
        harness.emit(1_000L, speedKmh = 350f, canonicalGear = 4)
        harness.emit(1_250L, speedKmh = -5f, canonicalGear = 4)
        assertNull(harness.source.drain())

        // Stationary with unknown gear -> do not guess a gear.
        harness.emit(2_000L, speedKmh = 0f, canonicalGear = 0)
        assertNull(harness.source.drain())

        // Moving >= 1 km/h with unknown gear -> temporarily assume DRIVE without overwriting future gear updates.
        harness.emit(3_000L, speedKmh = 18f, canonicalGear = 0)
        val movingFallback = harness.source.drain()
        assertNotNull(movingFallback)
        assertEquals(VehicleGear.DRIVE, movingFallback!!.gear)

        // Subsequent real gear arrives (REVERSE) -> must reflect REVERSE cleanly.
        harness.emit(4_000L, speedKmh = 5f, canonicalGear = 2, gearRaw = 8, gearText = "R")
        val reverseReading = harness.source.drain()
        assertNotNull(reverseReading)
        assertEquals(VehicleGear.REVERSE, reverseReading!!.gear)
    }

    @Test
    fun `start and stop are idempotent and clear buffered samples`() {
        val harness = Harness()
        harness.source.start()
        harness.source.start()
        assertEquals(1, harness.starts)
        assertEquals(1, harness.listeners.size)

        harness.emit(1_000L, speedKmh = 20f, canonicalGear = 4)
        harness.source.stop()
        harness.source.stop()
        assertEquals(1, harness.stops)
        assertTrue(harness.listeners.isEmpty())
        assertNull(harness.source.drain())
    }

    private class Harness {
        var starts = 0
        var stops = 0
        val listeners = linkedSetOf<(Long, CarDataMonitor.State) -> Unit>()

        val source = ChanganVehicleSpeedSource(
            startMonitor = { starts++ },
            stopMonitor = { stops++ },
            registerListener = { listeners += it },
            unregisterListener = { listeners -= it },
        )

        fun emit(
            elapsedMs: Long,
            speedKmh: Float,
            canonicalGear: Int,
            gearRaw: Int = 0,
            gearText: String = "",
            connected: Boolean = true,
        ) {
            val state = CarDataMonitor.State(
                speedRaw = speedKmh,
                gear = canonicalGear,
                gearRaw = gearRaw,
                gearText = gearText,
                connected = connected,
            )
            for (listener in listeners.toList()) {
                listener(elapsedMs, state)
            }
        }
    }
}
