package nz.galaxybridge.watch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONObject
import java.util.Calendar

/**
 * Today's activity totals on the watch (for the goal rings) and the move / stand / hydration
 * reminders. Steps come from the hardware step counter (StepCounter.kt), energy from Health
 * Services passive data. "Active minutes" is an estimate: minutes with a walking cadence
 * (≥ [ACTIVE_CADENCE] steps/min) plus the active time of recorded workouts.
 *
 * Reminder checks run on AutoLog's 15-minute tick; they never fire during quiet hours, while the
 * sleep estimate says the wearer is asleep, or during a workout. All reminders are off by default.
 */
object Goals {
    private const val PREFS = "goalsDay"
    private const val ACTIVE_CADENCE = 60
    /** Steps within [MOVE_WINDOW_MS] that count as "moved" (for inactivity and the sleep estimate). */
    private const val MOVE_STEPS = 20
    private const val MOVE_WINDOW_MS = 5 * 60_000L
    private const val STAND_STEPS = 40

    private const val CHANNEL = "hdReminders"
    private const val NOTIFY_MOVE = 41
    private const val NOTIFY_STAND = 42
    private const val NOTIFY_WATER = 43
    const val ACTION_LOG_WATER = "nz.galaxybridge.watch.LOG_WATER"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun dayKey(ms: Long = System.currentTimeMillis()): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = ms }
        return cal.get(Calendar.YEAR) * 10_000 + (cal.get(Calendar.MONTH) + 1) * 100 + cal.get(Calendar.DAY_OF_MONTH)
    }

    private fun hourKey(ms: Long) = ms / 3_600_000L

    /** Prefs for today, reset at local midnight (move/remind timestamps survive the reset). */
    private fun day(c: Context): android.content.SharedPreferences {
        val p = prefs(c)
        val d = dayKey()
        if (p.getInt("day", 0) != d) {
            p.edit().putInt("day", d).putInt("steps", 0).putFloat("kcal", 0f).putInt("activeMin", 0)
                .putInt("water", 0).putInt("caffeine", 0).apply()
        }
        return p
    }

    data class Today(val steps: Int, val kcal: Double, val activeMin: Int, val waterMl: Int, val caffeineMg: Int)

    fun today(c: Context): Today {
        val p = day(c)
        return Today(p.getInt("steps", 0), p.getFloat("kcal", 0f).toDouble(), p.getInt("activeMin", 0),
            p.getInt("water", 0), p.getInt("caffeine", 0))
    }

    fun todayJson(c: Context): JSONObject {
        val t = today(c)
        return JSONObject().put("steps", t.steps).put("kcal", t.kcal).put("activeMin", t.activeMin)
            .put("water", t.waterMl).put("caffeine", t.caffeineMg)
    }

    /** Fractions of each goal, 0…1+ (steps, energy, active minutes). */
    fun progress(c: Context): Triple<Double, Double, Double> {
        val t = today(c)
        return Triple(t.steps / HealthDepth.get(c, "goalSteps").toDouble(),
            t.kcal / HealthDepth.get(c, "goalKcal").toDouble(),
            t.activeMin / HealthDepth.get(c, "goalActiveMin").toDouble())
    }

    /** Every hardware step-counter delta, whether or not step sync is on. */
    fun onSteps(c: Context, s: HealthSample) {
        if (s.kind != SampleKind.STEPS || s.value <= 0) return
        val p = day(c)
        val steps = s.value.toInt()
        val e = p.edit().putInt("steps", p.getInt("steps", 0) + steps)

        // Active minutes: short deltas are bucketed per minute; long (batched) deltas use their cadence.
        val span = s.endMs - s.startMs
        var active = p.getInt("activeMin", 0)
        if (span > 90_000L) {
            val minutes = (span / 60_000L).toInt().coerceAtLeast(1)
            if (steps / minutes >= ACTIVE_CADENCE) active += minutes.coerceAtMost(60)
        } else {
            // Count a minute once, as soon as its steps reach a walking cadence.
            val minute = s.endMs / 60_000L
            var inMinute = if (p.getLong("minKey", 0L) == minute) p.getInt("minSteps", 0) else 0
            val counted = p.getLong("minCounted", 0L) == minute
            inMinute += steps
            if (!counted && inMinute >= ACTIVE_CADENCE) { active++; e.putLong("minCounted", minute) }
            e.putLong("minKey", minute).putInt("minSteps", inMinute)
        }
        e.putInt("activeMin", active)

        // Stand: steps this hour.
        val hk = hourKey(s.endMs)
        if (p.getLong("hourKey", 0L) != hk) e.putLong("hourKey", hk).putInt("hourSteps", steps)
        else e.putInt("hourSteps", p.getInt("hourSteps", 0) + steps)

        // Movement window, for inactivity reminders and the sleep estimate.
        val winStart = p.getLong("winStart", 0L)
        var winSteps = p.getInt("winSteps", 0)
        if (s.endMs - winStart > MOVE_WINDOW_MS) { e.putLong("winStart", s.startMs); winSteps = 0 }
        winSteps += steps
        var moved = false
        if (winSteps >= MOVE_STEPS) {
            moved = true
            e.putLong("lastMove", s.endMs)
            winSteps = 0
            e.putLong("winStart", s.endMs)
        }
        e.putInt("winSteps", winSteps)
        e.apply()

        if (moved) {
            // Steps no longer reach SleepTracker through the passive listener (they come from the
            // hardware counter now), so tell it about real movement here; it closes sleep sessions.
            val sleep = SleepTracker.onData(c, listOf(HealthSample(SampleKind.STEPS, s.startMs, s.endMs, MOVE_STEPS.toDouble())))
                .filter { WatchSettings.syncs(c, it.kind) }
            if (sleep.isNotEmpty()) { SampleQueue(c.filesDir).add(sleep); LinkService.samplesQueued() }
        }
        if (steps >= 50) HealthDepthTiles.refresh(c)
    }

    /** Passive Health Services samples: energy for the ring. */
    fun onPassive(c: Context, samples: List<HealthSample>) {
        val kcal = samples.filter { it.kind == SampleKind.ACTIVE_ENERGY && it.endMs >= startOfDay() }.sumOf { it.value }
        if (kcal <= 0) return
        val p = day(c)
        p.edit().putFloat("kcal", (p.getFloat("kcal", 0f) + kcal).toFloat()).apply()
    }

    fun addWorkoutMinutes(c: Context, minutes: Int) {
        if (minutes <= 0) return
        val p = day(c)
        p.edit().putInt("activeMin", p.getInt("activeMin", 0) + minutes.coerceAtMost(600)).apply()
    }

    fun addIntake(c: Context, kind: SampleKind, amount: Int) {
        val p = day(c)
        when (kind) {
            SampleKind.WATER -> p.edit().putInt("water", p.getInt("water", 0) + amount)
                .putLong("lastWater", System.currentTimeMillis()).apply()
            SampleKind.CAFFEINE -> p.edit().putInt("caffeine", p.getInt("caffeine", 0) + amount).apply()
            else -> {}
        }
    }

    private fun startOfDay(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun anyReminder(c: Context) =
        HealthDepth.on(c, "remindInactive") || HealthDepth.on(c, "remindStand") || HealthDepth.on(c, "remindWater")

    private fun quiet(c: Context, now: Long): Boolean {
        val h = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
        val a = HealthDepth.get(c, "quietStart"); val b = HealthDepth.get(c, "quietEnd")
        return if (a == b) false else if (a < b) h in a until b else h >= a || h < b
    }

    /** Runs on each AutoLog tick. */
    fun checkReminders(c: Context, now: Long = System.currentTimeMillis()) {
        if (!anyReminder(c)) return
        if (quiet(c, now) || SleepTracker.isAsleep(c) || Workouts.live.value != null) return
        val p = day(c)
        val e = p.edit()
        if (p.getLong("lastMove", 0L) == 0L) e.putLong("lastMove", now)
        if (p.getLong("lastWater", 0L) == 0L) e.putLong("lastWater", now)
        e.apply()

        if (HealthDepth.on(c, "remindInactive")) {
            val gap = HealthDepth.get(c, "inactiveMin") * 60_000L
            val lastMove = p.getLong("lastMove", now)
            if (now - lastMove >= gap && now - p.getLong("lastMoveRemind", 0L) >= gap) {
                notify(c, NOTIFY_MOVE, "Time to move", "No steps for ${(now - lastMove) / 60_000} min — a short walk helps")
                p.edit().putLong("lastMoveRemind", now).apply()
            }
        }
        if (HealthDepth.on(c, "remindStand")) {
            val minute = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.MINUTE)
            val hk = hourKey(now)
            val hourSteps = if (p.getLong("hourKey", 0L) == hk) p.getInt("hourSteps", 0) else 0
            if (minute >= 40 && hourSteps < STAND_STEPS && p.getLong("standRemindHour", 0L) != hk) {
                notify(c, NOTIFY_STAND, "Stand up", "Stand and move for a minute this hour")
                p.edit().putLong("standRemindHour", hk).apply()
            }
        }
        if (HealthDepth.on(c, "remindWater")) {
            val gap = HealthDepth.get(c, "waterEveryMin") * 60_000L
            if (now - p.getLong("lastWater", now) >= gap && now - p.getLong("lastWaterRemind", 0L) >= gap) {
                notify(c, NOTIFY_WATER, "Drink some water", "Tap Log to add ${HealthDepth.get(c, "cupMl")} ml", water = true)
                p.edit().putLong("lastWaterRemind", now).apply()
            }
        }
    }

    private fun notify(c: Context, id: Int, title: String, text: String, water: Boolean = false) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Activity & water reminders", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(c, id, Intent(c, HealthDepthActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_health)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
        if (water) {
            val log = PendingIntent.getBroadcast(c, id, Intent(c, HealthDepthReceiver::class.java).setAction(ACTION_LOG_WATER),
                PendingIntent.FLAG_IMMUTABLE)
            b.addAction(Notification.Action.Builder(null, "Log", log).build())
        }
        runCatching { nm.notify(id, b.build()) }
    }

    fun cancelWaterReminder(c: Context) =
        runCatching { c.getSystemService(NotificationManager::class.java)?.cancel(NOTIFY_WATER) }
}

/** Notification actions (log water) and the AutoLog alarm tick. */
class HealthDepthReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = context.applicationContext
        when (intent.action) {
            Goals.ACTION_LOG_WATER -> { Intake.logWater(c); Goals.cancelWaterReminder(c) }
            AutoLog.ACTION_TICK -> AutoLog.onTick(c)
        }
    }
}
