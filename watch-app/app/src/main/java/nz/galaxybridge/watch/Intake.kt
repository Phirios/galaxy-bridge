package nz.galaxybridge.watch

import android.content.Context

/**
 * Water, caffeine and menstrual-flow logging from the watch (quick-log tile and health screen).
 * These are entered by the user, so they're always queued for Apple Health (no sync toggle);
 * the iPhone writes them as dietaryWater / dietaryCaffeine / menstrual flow.
 */
object Intake {
    fun logWater(c: Context, ml: Int = HealthDepth.get(c, "cupMl")) = log(c, SampleKind.WATER, ml)

    fun logCaffeine(c: Context, mg: Int = HealthDepth.get(c, "coffeeMg")) = log(c, SampleKind.CAFFEINE, mg)

    /** [level]: 1 unspecified, 2 light, 3 medium, 4 heavy, 5 none (MenstrualFlow in GBLink.swift). */
    fun logFlow(c: Context, level: Int): Boolean {
        if (level !in 1..5) return false
        val now = System.currentTimeMillis()
        return enqueue(c, HealthSample(SampleKind.MENSTRUAL_FLOW, now, now, level.toDouble()))
    }

    private fun log(c: Context, kind: SampleKind, amount: Int): Boolean {
        val now = System.currentTimeMillis()
        val ok = enqueue(c, HealthSample(kind, now, now, amount.toDouble()))
        if (ok) Goals.addIntake(c, kind, amount)
        HealthDepthTiles.refresh(c)
        return ok
    }

    private fun enqueue(c: Context, s: HealthSample): Boolean {
        if (!s.isPlausible()) return false
        SampleQueue(c.filesDir).add(listOf(s))
        LinkService.samplesQueued()
        return true
    }
}
