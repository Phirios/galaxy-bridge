package nz.galaxybridge.watch

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class CallsSafetyTest {
    @Test fun neverAutoCallsPublicEmergencyNumbers() {
        for (n in listOf("111", "911", "112", "000", "999", "1-1-1", "+112", "08", "1234", "")) {
            assertFalse(n, Emergency.isAllowedForAutoCall(n))
        }
        assertTrue(Emergency.isAllowedForAutoCall("+64 21 555 0100"))
        assertTrue(Emergency.isAllowedForAutoCall("(021) 555-0100"))
    }

    @Test fun configFromPhoneIsValidated() {
        val c = CallsSafety.Config.from(JSONObject().put("number", "111").put("fall", true).put("sos", true)
            .put("checkIn", true).put("hr", true).put("countdown", 2))
        assertEquals("", c.number)
        assertFalse(c.fall || c.sos || c.checkIn)
        assertTrue(c.hr)
        assertEquals(CallsSafety.MIN_COUNTDOWN, c.countdown)

        val ok = CallsSafety.Config.from(JSONObject().put("number", "+64215550100").put("fall", true).put("hrHigh", 999))
        assertTrue(ok.fall && ok.hasContact)
        assertEquals(220, ok.hrHigh)
        assertFalse("defaults off", ok.sos)
    }

    private fun run(h: FallHeuristic, samples: List<Double>, startMs: Long = 0): Boolean {
        var fell = false
        samples.forEachIndexed { i, g -> if (h.feed(startMs + i * 20L, g)) fell = true } // 50 Hz
        return fell
    }

    private fun still(seconds: Int) = List(seconds * 50) { i -> 1.0 + 0.01 * sin(i / 3.0) }

    @Test fun detectsFreeFallImpactThenStillness() {
        val samples = still(2) + List(15) { 0.2 } + listOf(1.5, 3.5, 2.0) + still(7)
        assertTrue(run(FallHeuristic(), samples))
    }

    @Test fun detectsHardImpactWithoutFreeFall() {
        assertTrue(run(FallHeuristic(), still(1) + listOf(4.5, 2.0) + still(7)))
    }

    @Test fun ignoresWalking() {
        val walk = List(30 * 50) { i -> 1.0 + 0.6 * sin(i * 2 * Math.PI / 25) }
        assertFalse(run(FallHeuristic(), walk))
    }

    @Test fun ignoresImpactFollowedByMovement() {
        // E.g. clapping or knocking the watch, then carrying on.
        val moving = List(8 * 50) { i -> 1.0 + 0.5 * sin(i * 2 * Math.PI / 20) }
        assertFalse(run(FallHeuristic(), still(1) + listOf(4.8) + moving))
    }

    @Test fun ignoresBriefDipWithoutImpact() {
        assertFalse(run(FallHeuristic(), still(1) + List(10) { 0.3 } + still(8)))
    }
}
