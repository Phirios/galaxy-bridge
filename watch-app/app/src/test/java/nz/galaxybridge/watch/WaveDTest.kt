package nz.galaxybridge.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class WaveDTest {
    @Test fun calculatorPrecedenceAndBrackets() {
        assertEquals(7.0, Calc.eval("1+2×3")!!, 1e-9)
        assertEquals(9.0, Calc.eval("(1+2)×3")!!, 1e-9)
        assertEquals(-1.5, Calc.eval("−3÷2")!!, 1e-9)
        assertNull(Calc.eval("1+"))
        assertNull(Calc.eval("2÷0"))
        assertEquals("12", Calc.format(12.0))
        assertEquals("0.333333", Calc.format(1.0 / 3))
    }

    @Test fun conversions() {
        val c = Calc.CONVERSIONS.first { it.from == "°C" }
        assertEquals(212.0, c.convert(100.0), 1e-9)
        assertEquals(1.609344, Calc.CONVERSIONS.first { it.from == "mi" }.convert(1.0), 1e-9)
    }

    private fun at(h: Int, m: Int): Long = Calendar.getInstance().apply {
        set(2026, Calendar.MARCH, 10, h, m, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test fun faceScheduleSlots() {
        val f = FaceSchedule.Face("a.b", "a.b.S", "x")
        val slots = listOf(FaceSchedule.Slot(7, 0, f), FaceSchedule.Slot(22, 30, f.copy(name = "night")))
        // 08:00: the morning slot started today at 07:00; the next starts 22:30 today.
        assertEquals(at(7, 0), FaceSchedule.current(slots, at(8, 0))!!.first)
        assertEquals(at(22, 30), FaceSchedule.nextStart(slots, at(8, 0)))
        // 03:00: still last night's slot (yesterday 22:30); the next starts 07:00 today.
        val (start, slot) = FaceSchedule.current(slots, at(3, 0))!!
        assertEquals("night", slot.face.name)
        assertTrue(at(3, 0) - start == (4 * 60 + 30) * 60_000L)
        assertEquals(at(7, 0), FaceSchedule.nextStart(slots, at(3, 0)))
        assertNull(FaceSchedule.current(emptyList(), at(3, 0)))
    }
}
