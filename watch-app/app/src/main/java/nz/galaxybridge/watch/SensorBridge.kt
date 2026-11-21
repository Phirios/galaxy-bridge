package nz.galaxybridge.watch

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import com.samsung.android.service.health.tracking.data.ServiceDataPoint
import java.util.Collections

/**
 * Reads the Samsung-only health sensors (SpO2, skin temperature, ECG, body composition) that
 * Health Services doesn't expose, by binding directly to Samsung Health's exported tracking
 * service and speaking its Binder protocol.
 *
 * Why this works (reverse-engineered from HealthPlatform 1.7.00.05): the service
 * `com.samsung.android.service.health.tracking.requesthandler.WearTrackerService` is exported with
 * the intent action `...wear.sts.ACTION_SERVICE` and no permission guard; its per-sensor check
 * only needs android.permission.BODY_SENSORS. A second online policy check is skipped when Samsung
 * Health's developer mode is on (toggled once from the exported settings screen). The bound binder
 * implements `IClientEventListener`; we call createHealthTracker(1)/setEventListener(2)/
 * unsetEventListener(3), and the service calls back our `IServiceEventListener` stub with
 * onDataUpdated(1, List<ServiceDataPoint>) / onThrowException(2) / onFlushCompleted(3).
 *
 * This is our own Binder client against the service's wire contract — no Samsung SDK, no bundled
 * proprietary code. Every path is guarded; failure degrades to "no reading", never a crash.
 */
object SensorBridge {
    private const val TAG = "SensorBridge"

    private const val SERVICE_PKG = "com.samsung.android.service.health"
    private const val ACTION = "com.samsung.android.service.health.wear.sts.ACTION_SERVICE"
    private const val CLIENT_IFACE = "com.samsung.android.service.health.tracking.IClientEventListener"
    private const val SERVICE_IFACE = "com.samsung.android.service.health.tracking.IServiceEventListener"

    // IClientEventListener transaction codes (service binder).
    private const val TX_CREATE = 1
    private const val TX_SET_LISTENER = 2
    private const val TX_UNSET_LISTENER = 3

    // Tracker-type strings the service expects (on-demand variants share the base string).
    private fun trackerType(kind: SampleKind): String? = when (kind) {
        SampleKind.SPO2 -> "SPO2"
        SampleKind.SKIN_TEMP -> "SKIN_TEMPERATURE"
        SampleKind.HEART_RATE -> "HEART_RATE"
        else -> null
    }

    private const val TYPE_ECG = "ECG"
    private const val TYPE_BIA = "BIA"

    /** True when Samsung Health's exported tracking service is present. */
    fun isAvailable(ctx: Context): Boolean =
        ctx.packageManager.resolveService(Intent(ACTION).setPackage(SERVICE_PKG), 0) != null

    /** Opens Samsung Health's developer-mode screen (exported) so the user can enable the flag. */
    fun openDeveloperModeSettings(ctx: Context): Boolean = runCatching {
        ctx.startActivity(Intent().apply {
            setClassName(SERVICE_PKG, "$SERVICE_PKG.wear.settings.home.SettingsMainActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }); true
    }.getOrElse { Log.w(TAG, "dev-mode settings unavailable", it); false }

    // --- Public captures (same API the rest of the app already calls) ---

    /**
     * Reads scalar sensors (SpO2, skin temperature), enqueues samples and flushes to the iPhone.
     * [onSamples] (optional) receives the readings collected, so a UI can display the values.
     */
    fun measure(ctx: Context, kinds: Set<SampleKind>, durationMs: Long = 35_000,
                onSamples: ((List<HealthSample>) -> Unit)? = null, done: (Boolean) -> Unit) {
        val results = Collections.synchronizedList(ArrayList<HealthSample>())
        val specs = kinds.mapNotNull { k -> trackerType(k)?.let { k to it } }
        if (specs.isEmpty()) return done(false)
        Session(ctx, specs, null, durationMs,
            onData = { kind, point ->
                scalar(kind, point)?.let { v -> results.add(HealthSample(kind, point.timeStamp, point.timeStamp, v)) }
            },
            onDone = { _ ->
                val snapshot = synchronized(results) { ArrayList(results) }
                if (snapshot.isNotEmpty()) {
                    SampleQueue(ctx.filesDir).add(snapshot.filter { it.isPlausible() && WatchSettings.syncs(ctx, it.kind) })
                    LinkService.samplesQueued()
                }
                runCatching { onSamples?.invoke(snapshot) }
                done(snapshot.isNotEmpty())
            }).start()
    }

    /** Records an ECG strip; reports the sample rate and a relative waveform (display only). */
    fun captureEcg(ctx: Context, durationMs: Long = 30_000, done: (hz: Int, microvolts: ShortArray) -> Unit) {
        // Each ECG sample is in serviceValue[0], a single ServiceValue carrying BOTH fields:
        //   integerValue = raw ADC counts (relative to a baseline), floatValue = calibrated mV.
        // The mV field is only populated when the watch firmware reports parsed data (EcgTracker
        // in the decompiled HealthPlatform); otherwise it's a constant sentinel. The old fixed
        // ×1000 scale read that field directly and overflowed to a clamped flat line. ECG here is
        // display-only (HealthKit bars third-party ECG), so we keep both candidate series and
        // auto-range whichever carries a real waveform — only the shape matters, not the units.
        val mv = Collections.synchronizedList(ArrayList<Float>(16_000))
        val adc = Collections.synchronizedList(ArrayList<Int>(16_000))
        Session(ctx, listOf(SampleKind.HEART_RATE to TYPE_ECG), null, durationMs,
            onData = { _, point ->
                point.getServiceValue().getOrNull(0)?.let { sv ->
                    mv.add(sv.floatValue)
                    adc.add(sv.integerValue)
                }
            },
            onDone = { _ ->
                val mvSnap = synchronized(mv) { mv.toList() }
                val adcSnap = synchronized(adc) { adc.toList() }
                val scaled = autoRangeEcg(mvSnap, adcSnap)
                val step = if (scaled.size > 7500) 2 else 1
                val out = ShortArray(scaled.size / step) { scaled[it * step] }
                done(if (step == 2) 250 else 500, out)
            }).start()
    }

    /**
     * Converts the collected ECG samples into a relative Int16 waveform that fills the range
     * without clamping. Prefers the calibrated-mV series when it is a real, in-range signal
     * (a sentinel/garbage field is near-constant or physiologically absurd), else the raw ADC
     * series; removes the DC baseline and scales by the peak absolute value. Display only.
     */
    internal fun autoRangeEcg(mv: List<Float>, adc: List<Int>): ShortArray {
        val mvD = mv.map { it.toDouble() }
        // Real ECG millivolts vary and stay within a few mV; reject a constant or absurd field.
        val mvUsable = mvD.isNotEmpty() && mvD.all { it.isFinite() && kotlin.math.abs(it) <= 100.0 } &&
            stdDev(mvD) > 1e-4
        val raw = if (mvUsable) mvD else adc.map { it.toDouble() }
        if (raw.isEmpty()) return ShortArray(0)
        val mean = raw.average()
        var maxAbs = 0.0
        for (v in raw) maxAbs = kotlin.math.max(maxAbs, kotlin.math.abs(v - mean))
        val scale = if (maxAbs > 0.0) 32000.0 / maxAbs else 0.0
        return ShortArray(raw.size) { ((raw[it] - mean) * scale).toInt().coerceIn(-32768, 32767).toShort() }
    }

    private fun stdDev(xs: List<Double>): Double {
        if (xs.size < 2) return 0.0
        val mean = xs.average()
        return kotlin.math.sqrt(xs.sumOf { (it - mean) * (it - mean) } / xs.size)
    }

    /** Reads a body-composition (BIA) measurement; reports the named values. */
    fun captureBia(ctx: Context, durationMs: Long = 20_000, done: (Map<String, Double>) -> Unit) {
        val latest = HashMap<String, Double>()
        // BIA needs a user profile (height cm, weight kg, gender, age). The phone sends the real
        // one (from HealthKit or manual entry) via "setBiaProfile"; until then a neutral default.
        val profile = loadProfile(ctx)
        Session(ctx, listOf(SampleKind.HEART_RATE to TYPE_BIA), profile, durationMs,
            onData = { _, point ->
                val v = point.getServiceValue()
                // BiaSet order: [1]=BODY_FAT_RATIO,[3]=TOTAL_BODY_WATER,[5]=SKELETAL_MUSCLE_MASS,
                // [6]=BASAL_METABOLIC_RATE,[8]=FAT_FREE_MASS.
                synchronized(latest) {
                    v.getOrNull(1)?.let { latest["fatPct"] = it.floatValue.toDouble() }
                    v.getOrNull(8)?.let { latest["leanKg"] = it.floatValue.toDouble() }
                    v.getOrNull(3)?.let { latest["waterPct"] = it.floatValue.toDouble() }
                    v.getOrNull(5)?.let { latest["muscleKg"] = it.floatValue.toDouble() }
                    v.getOrNull(6)?.let { latest["bmr"] = it.floatValue.toDouble() }
                }
            },
            onDone = { _ -> done(synchronized(latest) { HashMap(latest) }) }).start()
    }

    /** Extracts the single scalar value for a SpO2 / skin-temp / HR data point. */
    private fun scalar(kind: SampleKind, point: ServiceDataPoint): Double? {
        val v = point.getServiceValue()
        return when (kind) {
            SampleKind.SPO2 -> v.getOrNull(1)?.integerValue?.toDouble()          // SpO2Set[1]=SPO2
            SampleKind.SKIN_TEMP -> v.getOrNull(0)?.floatValue?.toDouble()        // SkinTempSet[0]=OBJECT_TEMPERATURE
            SampleKind.HEART_RATE -> v.getOrNull(0)?.integerValue?.toDouble()     // HeartRateSet[0]=HEART_RATE
            else -> null
        }
    }

    private data class BiaProfile(val height: Float, val weight: Float, val gender: Int, val age: Int)

    private const val PROFILE_PREFS = "biaProfile"

    /**
     * Stores the user's body profile sent from the phone, so a BIA reading uses real values.
     * [gender] is 1 = male, 0 = female (Samsung's convention). Values are range-checked here.
     */
    fun setProfile(ctx: Context, height: Float, weight: Float, gender: Int, age: Int): Boolean {
        if (!(height in 50f..260f && weight in 20f..400f && gender in 0..1 && age in 1..130)) return false
        ctx.getSharedPreferences(PROFILE_PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("height", height).putFloat("weight", weight)
            .putInt("gender", gender).putInt("age", age).apply()
        return true
    }

    private fun loadProfile(ctx: Context): BiaProfile {
        val p = ctx.getSharedPreferences(PROFILE_PREFS, Context.MODE_PRIVATE)
        // Neutral defaults only if the phone hasn't sent a profile yet.
        return BiaProfile(
            height = p.getFloat("height", 170f),
            weight = p.getFloat("weight", 70f),
            gender = p.getInt("gender", 1),
            age = p.getInt("age", 30),
        )
    }

    /**
     * One bind-connect-subscribe-collect-unbind cycle. Registers a per-tracker-type callback
     * (each ServiceDataPoint arrives on its own tracker's listener, so the kind is unambiguous),
     * collects for [durationMs], then unsubscribes and disconnects. Fully guarded.
     */
    private class Session(
        private val ctx: Context,
        private val specs: List<Pair<SampleKind, String>>,
        private val biaProfile: BiaProfile?,
        private val durationMs: Long,
        private val onData: (SampleKind, ServiceDataPoint) -> Unit,
        private val onDone: (ok: Boolean) -> Unit,
    ) : ServiceConnection {
        private var binder: IBinder? = null
        private val registered = ArrayList<Pair<String, IBinder>>()
        private val appId = ctx.packageName
        private val main = Handler(ctx.mainLooper)
        @Volatile private var finished = false

        fun start() {
            if (!isAvailable(ctx)) { Log.w(TAG, "tracking service not present"); return onDone(false) }
            val ok = runCatching {
                ctx.bindService(Intent(ACTION).setPackage(SERVICE_PKG), this, Context.BIND_AUTO_CREATE)
            }.getOrDefault(false)
            if (!ok) { Log.w(TAG, "bindService failed"); onDone(false) }
            else main.postDelayed({ finish(true) }, durationMs + 2_000) // safety net
        }

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service
            try {
                for ((kind, type) in specs) {
                    createHealthTracker(type, if (type == TYPE_BIA) biaProfile else null)
                    val cb = callbackFor(kind)
                    setEventListener(type, cb)
                    registered.add(type to cb)
                }
            } catch (t: Throwable) { Log.w(TAG, "subscribe failed", t); finish(false); return }
            main.postDelayed({ finish(true) }, durationMs)
        }

        override fun onServiceDisconnected(name: ComponentName?) { binder = null }
        override fun onNullBinding(name: ComponentName?) { finish(false) }

        private fun finish(ok: Boolean) {
            if (finished) return
            finished = true
            runCatching {
                for ((type, _) in registered) unsetEventListener(type)
                ctx.unbindService(this)
            }
            onDone(ok)
        }

        // --- IClientEventListener calls on the bound binder ---

        private inline fun call(code: Int, write: (Parcel) -> Unit) {
            val b = binder ?: return
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try { data.writeInterfaceToken(CLIENT_IFACE); write(data); b.transact(code, data, reply, 0); reply.readException() }
            finally { reply.recycle(); data.recycle() }
        }

        private fun createHealthTracker(type: String, profile: BiaProfile?) = call(TX_CREATE) { p ->
            p.writeString(appId)
            p.writeString(type)
            if (profile == null) p.writeInt(0) else {        // AIDL writeTypedObject: 0=null, 1=present
                p.writeInt(1)
                p.writeFloat(profile.height); p.writeFloat(profile.weight)
                p.writeInt(profile.gender); p.writeInt(profile.age)
            }
            p.writeString("")                                 // exercise type (none)
        }

        private fun setEventListener(type: String, cb: IBinder) = call(TX_SET_LISTENER) { p ->
            p.writeString(appId); p.writeString(type); p.writeStrongBinder(cb)
        }

        private fun unsetEventListener(type: String) = call(TX_UNSET_LISTENER) { p ->
            p.writeString(appId); p.writeString(type)
        }

        /** An IServiceEventListener stub bound to one tracker kind; the service calls it back. */
        private fun callbackFor(kind: SampleKind): IBinder = object : android.os.Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code in 1..0x00FFFFFF) data.enforceInterface(SERVICE_IFACE)
                return when (code) {
                    INTERFACE_TRANSACTION -> { reply?.writeString(SERVICE_IFACE); true }
                    1 -> { // onDataUpdated(List<ServiceDataPoint>)
                        val points = data.createTypedArrayList(ServiceDataPoint.CREATOR).orEmpty()
                        for (pt in points) runCatching { onData(kind, pt) }
                        reply?.writeNoException(); true
                    }
                    2 -> { val e = data.readInt(); Log.w(TAG, "service error $e for $kind"); reply?.writeNoException(); true }
                    3 -> { reply?.writeNoException(); true } // onFlushCompleted
                    else -> super.onTransact(code, data, reply, flags)
                }
            }
        }
    }
}
