package nz.galaxybridge.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HrvTest {
    @Test fun parsesPairedIbiSlots() {
        // [HR, rri, status, n=3, ibi, st, ibi, st, ibi, st]; the bad-status beat is dropped.
        val ints = listOf(62, 0, 1, 3, 950, 0, 980, 1, 1000, 0)
        assertEquals(listOf(950, 1000), Hrv.ibisFrom(ints))
    }

    @Test fun noIbiWhenCountIsZero() {
        assertTrue(Hrv.ibisFrom(listOf(70, 0, 1, 0)).isEmpty())
        assertTrue(Hrv.ibisFrom(listOf(70)).isEmpty())
    }

    @Test fun computesSdnnAndRmssd() {
        val beats = (0 until 60).map { if (it % 2 == 0) 1000 else 1040 }
        val r = Hrv.compute(beats)
        assertNotNull(r)
        assertEquals(20.0, r!!.sdnn, 0.01)
        assertEquals(40.0, r.rmssd, 0.01)
        assertEquals(60, r.beats)
    }

    @Test fun rejectsTooFewBeatsAndArtefacts() {
        assertNull(Hrv.compute(List(10) { 1000 }))
        // A missed beat (2000 ms) is filtered rather than inflating HRV.
        val steady = List(46) { if (it % 2 == 0) 1000 else 1020 }
        val r = Hrv.compute(steady.take(40) + 2000 + steady.drop(40))
        assertNotNull(r)
        assertEquals(10.0, r!!.sdnn, 0.01)
        assertEquals(46, r.beats)
    }

    @Test fun stressScaleIsBounded() {
        assertEquals(0, Hrv.stressScore(150.0))
        assertEquals(100, Hrv.stressScore(5.0))
        assertTrue(Hrv.stressScore(30.0) in 40..70)
    }
}
