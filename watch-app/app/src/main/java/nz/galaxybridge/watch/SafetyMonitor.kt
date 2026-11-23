package nz.galaxybridge.watch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

/**
 * The safety engine: fall detection, user-triggered SOS, the no-movement check-in and heart-rate
 * alerts. Every automatic call goes through [trigger] → a visible countdown (at least 15 s) that
 * can be cancelled on the watch → a call over HFP to the user's chosen contact. Never a public
 * emergency number (see [Emergency]); with no allowed contact nothing is dialled.
 */
object SafetyMonitor {
    private const val TAG = "Safety"
    private const val CHANNEL = "safety"
    private const val NOTIFY_ID = 7_301
    private const val HR_NOTIFY_ID = 7_302
    const val ACTION_CANCEL = "nz.galaxybridge.watch.SAFETY_CANCEL"

    enum class Kind(val wire: String, val title: String) {
        SOS("sos", "Emergency SOS"), FALL("fall", "Looks like you fell"), CHECK_IN("checkin", "Are you OK?")
    }
    enum class Stage { COUNTDOWN, CALLING, NO_CONTACT, NOT_CONNECTED }

    data class Alert(val kind: Kind, val stage: Stage, val endsAt: Long, val contact: String, val location: Location? = null)

    /** The alert on screen, or null. [SafetyActivity] draws it. */
    val active = MutableStateFlow<Alert?>(null)
    /** Last HR alert shown on the watch: (bpm, "high"/"low"). */
    val lastHrAlert = MutableStateFlow<Pair<Int, String>?>(null)

    private val main = Handler(Looper.getMainLooper())
    private var fall: FallSensor? = null
    private var hr: HrMonitor? = null
    private var onBody = true
    private var offBodyListener: SensorEventListener? = null
    private var ringtone: Ringtone? = null
    private var appCtx: Context? = null

    fun fallRunning() = fall?.running() == true
    fun hasHeartRateSensor(c: Context) = c.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_HEART_RATE) != null

    /** Starts or stops each monitor to match [c]. */
    fun apply(ctx: Context, c: CallsSafety.Config) {
        val app = ctx.applicationContext
        appCtx = app
        if (c.fall || c.checkIn) {
            val f = fall ?: FallSensor(app, { CallsSafety.config.value.fall && onBody }) {
                main.post { trigger(app, Kind.FALL) }
            }.also { fall = it }
            f.start()
            watchBody(app)
        } else {
            fall?.stop(); fall = null
            unwatchBody(app)
        }
        main.removeCallbacks(checkInTick)
        if (c.checkIn) main.postDelayed(checkInTick, CHECK_IN_POLL_MS)
        if (c.hr) (hr ?: HrMonitor(app).also { hr = it }).start() else { hr?.stop(); hr = null }
    }

    fun stop(ctx: Context) {
        fall?.stop(); fall = null
        hr?.stop(); hr = null
        unwatchBody(ctx.applicationContext)
        main.removeCallbacks(checkInTick)
    }

    // --- Countdown → call ---

    /** Starts an alert unless one is already running. Safe to call from any context. */
    fun trigger(ctx: Context, kind: Kind) {
        val c = ctx.applicationContext
        appCtx = c
        if (active.value != null) return
        val cfg = CallsSafety.config.value
        val enabled = when (kind) { Kind.SOS -> cfg.sos; Kind.FALL -> cfg.fall; Kind.CHECK_IN -> cfg.checkIn }
        if (!cfg.hasContact || !enabled) {
            active.value = Alert(kind, Stage.NO_CONTACT, 0, "")
            show(c)
            return
        }
        val seconds = cfg.countdown.coerceAtLeast(CallsSafety.MIN_COUNTDOWN)
        active.value = Alert(kind, Stage.COUNTDOWN, SystemClock.elapsedRealtime() + seconds * 1000L, cfg.contactLabel)
        Log.i(TAG, "alert ${kind.wire}: countdown ${seconds}s")
        alarm(c, true)
        show(c)
        notifyPhone(kind, "countdown")
        requestLocation(c)
        main.removeCallbacks(tick)
        main.postDelayed(tick, 1_000)
    }

    /** Cancels the countdown (or closes the alert screen after a call was placed). */
    fun cancel(ctx: Context) {
        val a = active.value ?: return
        main.removeCallbacks(tick)
        alarm(ctx.applicationContext, false)
        ctx.getSystemService(NotificationManager::class.java).cancel(NOTIFY_ID)
        active.value = null
        if (a.stage == Stage.COUNTDOWN) notifyPhone(a.kind, "cancelled")
    }

    /** Skips the rest of the countdown. */
    fun callNow(ctx: Context) {
        if (active.value?.stage == Stage.COUNTDOWN) placeCall(ctx.applicationContext)
    }

    private val tick = object : Runnable {
        override fun run() {
            val a = active.value ?: return
            if (a.stage != Stage.COUNTDOWN) return
            val app = appCtx ?: return
            if (SystemClock.elapsedRealtime() >= a.endsAt) placeCall(app) else main.postDelayed(this, 1_000)
        }
    }

    private fun placeCall(c: Context) {
        val a = active.value ?: return
        main.removeCallbacks(tick)
        alarm(c, false)
        val cfg = CallsSafety.config.value
        // Re-checked here as a last line of defence: never auto-dial a public emergency number.
        if (!Emergency.isAllowedForAutoCall(cfg.number)) { active.value = a.copy(stage = Stage.NO_CONTACT); return }
        val ok = CallLog.dial(c, cfg.number, cfg.name)
        active.value = a.copy(stage = if (ok) Stage.CALLING else Stage.NOT_CONNECTED)
        notifyPhone(a.kind, "calling")
        Log.i(TAG, "alert ${a.kind.wire}: dial sent=$ok")
    }

    private fun notifyPhone(kind: Kind, stage: String, loc: Location? = active.value?.location) {
        val body = JSONObject().put("kind", kind.wire).put("stage", stage)
        if (loc != null) body.put("lat", loc.latitude).put("lon", loc.longitude).put("acc", loc.accuracy.toDouble())
        LinkService.sendFeature("sosAlert", body)
    }

    /** Gets a fresh fix from the watch's GPS for the phone. While-in-use access: the alert is on screen. */
    private fun requestLocation(c: Context) {
        if (c.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val lm = c.getSystemService(LocationManager::class.java) ?: return
        fun deliver(loc: Location?) {
            val a = active.value ?: return
            if (loc == null) return
            active.value = a.copy(location = loc)
            notifyPhone(a.kind, "location", loc)
        }
        runCatching {
            val last = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused")
                .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
                .maxByOrNull { it.time }
            if (last != null && System.currentTimeMillis() - last.time < 10 * 60_000) deliver(last)
            val provider = if (lm.allProviders.contains("fused")) "fused" else LocationManager.GPS_PROVIDER
            lm.getCurrentLocation(provider, null, c.mainExecutor) { deliver(it) }
        }.onFailure { Log.w(TAG, "location failed", it) }
    }

    private fun show(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Safety alerts", NotificationManager.IMPORTANCE_HIGH))
        val screen = PendingIntent.getActivity(c, 3, Intent(c, SafetyActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getBroadcast(c, 4, Intent(ACTION_CANCEL).setClass(c, SafetyReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val a = active.value ?: return
        nm.notify(NOTIFY_ID, Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_iphone)
            .setContentTitle(a.kind.title)
            .setContentText(if (a.stage == Stage.COUNTDOWN) "Calling ${a.contact} soon. Tap to cancel." else "Open to see details")
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(a.stage == Stage.COUNTDOWN)
            .setFullScreenIntent(screen, true)
            .setContentIntent(screen)
            .addAction(Notification.Action.Builder(null, "Cancel", cancel).build())
            .build())
        // Also open the screen directly (the app holds "Display over other apps" on this watch).
        Features.start(c, Intent(c, SafetyActivity::class.java))
    }

    /** Strong vibration plus an alarm tone during the countdown, so the wearer notices. */
    private fun alarm(c: Context, on: Boolean) {
        val v = c.getSystemService(VibratorManager::class.java).defaultVibrator
        ringtone?.stop(); ringtone = null
        v.cancel()
        if (!on) return
        v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 300), 0))
        ringtone = RingtoneManager.getRingtone(c, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))?.apply {
            audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            isLooping = true
            runCatching { play() }
        }
    }

    // --- Off-body: don't flag falls or stillness while the watch is off the wrist ---

    private fun watchBody(c: Context) {
        if (offBodyListener != null) return
        val sm = c.getSystemService(SensorManager::class.java) ?: return
        val s = sm.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT) ?: return
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { onBody = e.values[0] != 0f }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (sm.registerListener(l, s, SensorManager.SENSOR_DELAY_NORMAL)) offBodyListener = l
    }

    private fun unwatchBody(c: Context) {
        offBodyListener?.let { c.getSystemService(SensorManager::class.java)?.unregisterListener(it) }
        offBodyListener = null
        onBody = true
    }

    // --- No-movement check-in ---

    private const val CHECK_IN_POLL_MS = 5 * 60_000L

    private val checkInTick = object : Runnable {
        override fun run() {
            val cfg = CallsSafety.config.value
            if (!cfg.checkIn) return
            main.postDelayed(this, CHECK_IN_POLL_MS)
            val c = appCtx ?: return
            val f = fall ?: return
            val charging = c.getSystemService(BatteryManager::class.java)?.isCharging == true
            val stillFor = SystemClock.elapsedRealtime() - f.lastMovementMs
            if (onBody && !charging && stillFor > cfg.checkInHours * 3_600_000L) {
                trigger(c, Kind.CHECK_IN)
                // Counts as a movement check so it doesn't fire again every poll.
                f.stop(); f.start()
            }
        }
    }

    // --- Heart-rate alerts ---

    internal fun onHeartRate(c: Context, bpm: Int, kind: String, limit: Int) {
        lastHrAlert.value = bpm to kind
        LinkService.sendFeature("hrAlert", JSONObject().put("bpm", bpm).put("kind", kind).put("limit", limit))
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Safety alerts", NotificationManager.IMPORTANCE_HIGH))
        nm.notify(HR_NOTIFY_ID, Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_iphone)
            .setContentTitle(if (kind == "high") "High heart rate" else "Low heart rate")
            .setContentText("$bpm bpm measured (your limit: $limit). Not a medical reading.")
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .build())
    }
}

/** Not exported; only our own Cancel action reaches it. */
class SafetyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            SafetyMonitor.ACTION_CANCEL -> SafetyMonitor.cancel(context)
            FindLoud.ACTION_STOP -> FindLoud.stop(context)
        }
    }
}

/**
 * Samples the heart-rate sensor every [PERIOD_MS] for up to [WINDOW_MS] and alerts when the median
 * is outside the user's limits twice in a row (a minute apart), at most once per [COOLDOWN_MS].
 * Uses the standard Android heart-rate sensor (needs body-sensor permission, and background
 * body-sensor access while the screen is off): NEEDS LIVE TEST on this watch. Not a medical device.
 */
class HrMonitor(private val c: Context) : SensorEventListener {
    companion object {
        const val PERIOD_MS = 10 * 60_000L
        const val RECHECK_MS = 60_000L
        const val WINDOW_MS = 40_000L
        const val COOLDOWN_MS = 30 * 60_000L
    }

    private val sm = c.getSystemService(SensorManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val readings = ArrayList<Int>()
    private var sampling = false
    private var strikes = 0
    private var lastAlert = 0L
    private var running = false

    fun start() {
        if (running) return
        running = true
        main.postDelayed(sample, 30_000)
    }

    fun stop() {
        running = false
        main.removeCallbacks(sample); main.removeCallbacks(finish); main.removeCallbacks(retry)
        if (sampling) sm?.unregisterListener(this)
        sampling = false
    }

    private val sample: Runnable = Runnable {
        if (!running) return@Runnable
        val s = sm?.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        if (s == null || c.checkSelfPermission(android.Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED) {
            main.postDelayed(retry, PERIOD_MS); return@Runnable
        }
        readings.clear()
        sampling = sm.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL)
        main.postDelayed(finish, WINDOW_MS)
    }

    private val retry: Runnable = Runnable { sample.run() }

    private val finish: Runnable = Runnable {
        if (sampling) sm?.unregisterListener(this)
        sampling = false
        if (!running) return@Runnable
        val cfg = CallsSafety.config.value
        // Skip the first readings while the sensor settles.
        val values = readings.drop(5).sorted()
        val median = values.getOrNull(values.size / 2)
        val out = when {
            median == null -> null
            median > cfg.hrHigh -> "high" to cfg.hrHigh
            median < cfg.hrLow -> "low" to cfg.hrLow
            else -> null
        }
        if (out == null || median == null) { strikes = 0; main.postDelayed(sample, PERIOD_MS); return@Runnable }
        strikes++
        if (strikes < 2) { main.postDelayed(sample, RECHECK_MS); return@Runnable }
        strikes = 0
        val now = SystemClock.elapsedRealtime()
        if (lastAlert == 0L || now - lastAlert > COOLDOWN_MS) {
            lastAlert = now
            SafetyMonitor.onHeartRate(c, median, out.first, out.second)
        }
        main.postDelayed(sample, PERIOD_MS)
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (e.accuracy == SensorManager.SENSOR_STATUS_NO_CONTACT || e.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) return
        val bpm = e.values.firstOrNull()?.toInt() ?: return
        if (bpm in 25..250) readings.add(bpm)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

/**
 * Loud "find my watch": the alarm tone starts at about 40 % volume and climbs to full over ~30 s,
 * with vibration getting stronger, until stopped here or on the iPhone (or after 2 minutes). The
 * user's alarm volume is put back afterwards.
 */
object FindLoud {
    const val ACTION_STOP = "nz.galaxybridge.watch.FIND_LOUD_STOP"
    private const val NOTIFY_ID = 7_303
    private const val STEP_MS = 3_000L
    private const val MAX_MS = 120_000L

    val ringing = MutableStateFlow(false)
    private val main = Handler(Looper.getMainLooper())
    private var ringtone: Ringtone? = null
    private var savedVolume = -1
    private var step = 0
    private var appCtx: Context? = null

    fun start(ctx: Context) {
        val c = ctx.applicationContext
        appCtx = c
        if (ringing.value) return
        ringing.value = true
        val am = c.getSystemService(AudioManager::class.java)
        savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
        step = 0
        ringtone = RingtoneManager.getRingtone(c, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))?.apply {
            audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            isLooping = true
        }
        escalate.run()
        runCatching { ringtone?.play() }
        main.postDelayed(timeout, MAX_MS)
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("find", "Find my watch", NotificationManager.IMPORTANCE_HIGH))
        val screen = PendingIntent.getActivity(c, 5, Intent(c, SafetyActivity::class.java)
            .putExtra(SafetyActivity.EXTRA_MODE, SafetyActivity.MODE_FIND), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getBroadcast(c, 6, Intent(ACTION_STOP).setClass(c, SafetyReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(NOTIFY_ID, Notification.Builder(c, "find")
            .setSmallIcon(R.drawable.ic_stat_iphone)
            .setContentTitle("Find my watch")
            .setContentText("Getting louder. Tap to stop.")
            .setCategory(Notification.CATEGORY_ALARM)
            .setFullScreenIntent(screen, true)
            .setContentIntent(screen)
            .addAction(Notification.Action.Builder(null, "Found it", stop).build())
            .build())
    }

    fun stop(ctx: Context) {
        val c = ctx.applicationContext
        main.removeCallbacks(escalate); main.removeCallbacks(timeout)
        ringtone?.stop(); ringtone = null
        c.getSystemService(VibratorManager::class.java).defaultVibrator.cancel()
        if (savedVolume >= 0) runCatching { c.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0) }
        savedVolume = -1
        c.getSystemService(NotificationManager::class.java).cancel(NOTIFY_ID)
        if (ringing.value) LinkService.sendFeature("findLoud", JSONObject().put("on", false).put("side", "watch"))
        ringing.value = false
    }

    private val timeout = Runnable { appCtx?.let { stop(it) } }

    private val escalate = object : Runnable {
        override fun run() {
            val c = appCtx ?: return
            if (!ringing.value) return
            val am = c.getSystemService(AudioManager::class.java)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val frac = (0.4 + 0.6 * step / 10.0).coerceAtMost(1.0)
            runCatching { am.setStreamVolume(AudioManager.STREAM_ALARM, (max * frac).toInt().coerceAtLeast(1), 0) }
            val amp = (80 + 175 * step / 10).coerceAtMost(255)
            c.getSystemService(VibratorManager::class.java).defaultVibrator.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 500, 300), intArrayOf(0, amp, 0), 0))
            step++
            if (step <= 10) main.postDelayed(this, STEP_MS)
        }
    }
}
