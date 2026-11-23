package nz.galaxybridge.watch

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Parcelable
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Call history on the watch, built from what the watch itself sees:
 *  - calls placed from the watch ([dial]),
 *  - HFP call-state broadcasts from the hands-free client (number, incoming/outgoing, answered or
 *    missed) — a hidden-API broadcast, so NEEDS LIVE TEST once an HFP bond exists,
 *  - iPhone incoming-call notifications over ANCS (caller name only).
 * Entries seen by more than one source within a short window are merged.
 */
object CallLog {
    private const val TAG = "CallLog"
    private const val MAX = 30
    private const val MERGE_MS = 45_000L

    // BluetoothHeadsetClient (system API) broadcast and the hidden BluetoothHeadsetClientCall states.
    private const val ACTION_CALL_CHANGED = "android.bluetooth.headsetclient.profile.action.AG_CALL_CHANGED"
    private const val EXTRA_CALL = "android.bluetooth.headsetclient.extra.CALL"
    private const val STATE_ACTIVE = 0
    private const val STATE_DIALING = 2
    private const val STATE_ALERTING = 3
    private const val STATE_INCOMING = 4
    private const val STATE_WAITING = 5
    private const val STATE_TERMINATED = 7

    const val IN = "in"
    const val OUT = "out"
    const val MISSED = "missed"

    data class Entry(val name: String, val number: String, val dir: String, val at: Long) {
        val label get() = name.ifEmpty { number.ifEmpty { "Unknown" } }
        fun toJson(): JSONObject = JSONObject().put("n", name).put("p", number).put("dir", dir).put("at", at)
    }

    val entries = MutableStateFlow<List<Entry>>(emptyList())

    private var receiver: BroadcastReceiver? = null
    private var scope: CoroutineScope? = null
    private var lastIncomingUid: Int? = null
    private data class HfpCall(val number: String, val incoming: Boolean, val answered: Boolean, val at: Long)
    private val hfpCalls = HashMap<Int, HfpCall>()

    private fun prefs(c: Context) = c.getSharedPreferences("callLog", Context.MODE_PRIVATE)

    fun load(c: Context): List<Entry> {
        if (entries.value.isEmpty()) {
            val raw = prefs(c).getString("items", null) ?: return emptyList()
            val a = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
            entries.value = (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                Entry(o.optString("n"), o.optString("p"), o.optString("dir", IN), o.optLong("at"))
            }
        }
        return entries.value
    }

    fun start(s: LinkService) {
        load(s)
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { runCatching { onHfpCall(context, intent) } }
        }
        receiver = r
        // A system broadcast from the Bluetooth stack, so the receiver has to be exported.
        runCatching { ContextCompat.registerReceiver(s, r, IntentFilter(ACTION_CALL_CHANGED), ContextCompat.RECEIVER_EXPORTED) }
            .onFailure { Log.w(TAG, "HFP call receiver not registered", it) }
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = sc
        sc.launch {
            PhoneNotifier.incomingCall.collect { call ->
                if (call != null && call.uid != lastIncomingUid) {
                    lastIncomingUid = call.uid
                    record(s, Entry(call.caller.take(60), "", IN, System.currentTimeMillis()))
                }
            }
        }
    }

    fun stop(s: LinkService) {
        receiver?.let { runCatching { s.unregisterReceiver(it) } }
        receiver = null
        scope?.cancel()
        scope = null
    }

    /**
     * Places a call on the iPhone over HFP with the audio on the watch. Returns false when there's
     * no connected iPhone to send it to. Whether the HFP dial itself succeeds NEEDS LIVE TEST.
     */
    fun dial(c: Context, number: String, name: String = ""): Boolean {
        val n = number.trim()
        if (!CallsSafety.isDialable(n)) return false
        if (LinkService.phoneDevice() == null) return false
        CallAudio.control(c, CallAudio.Action.DIAL, n)
        record(c, Entry(name.ifEmpty { lookupName(c, n) }, n, OUT, System.currentTimeMillis()))
        return true
    }

    fun hangUp(c: Context) = CallAudio.control(c, CallAudio.Action.HANGUP)

    fun clear(c: Context) {
        entries.value = emptyList()
        prefs(c).edit().remove("items").apply()
        CallsTile.refresh(c)
    }

    fun sendTo(s: LinkService) {
        s.send(WatchMessages.feature("callLog", JSONObject().put("items", JSONArray(load(s).map { it.toJson() }))))
    }

    /** Adds an entry, merging with a recent one from another source for the same call. */
    @Synchronized
    fun record(c: Context, e: Entry) {
        val list = load(c).toMutableList()
        val i = list.indexOfFirst { o ->
            kotlin.math.abs(o.at - e.at) < MERGE_MS && (o.dir == e.dir || (o.dir == IN && e.dir == MISSED)) &&
                (o.number.isEmpty() || e.number.isEmpty() || o.number == e.number)
        }
        if (i >= 0) {
            val o = list[i]
            list[i] = o.copy(name = o.name.ifEmpty { e.name }, number = o.number.ifEmpty { e.number },
                dir = if (e.dir == MISSED) MISSED else o.dir)
        } else list.add(0, e)
        val trimmed = list.sortedByDescending { it.at }.take(MAX)
        entries.value = trimmed
        prefs(c).edit().putString("items", JSONArray(trimmed.map { it.toJson() }).toString()).apply()
        CallsTile.refresh(c)
    }

    private fun onHfpCall(c: Context, intent: Intent) {
        if (intent.action != ACTION_CALL_CHANGED) return
        @Suppress("DEPRECATION")
        val call: Parcelable = intent.getParcelableExtra(EXTRA_CALL) ?: return
        fun <T> get(name: String): T? = runCatching {
            @Suppress("UNCHECKED_CAST")
            call.javaClass.getMethod(name).invoke(call) as T
        }.getOrNull()
        val id = get<Int>("getId") ?: return
        val state = get<Int>("getState") ?: return
        val number = (get<String>("getNumber") ?: "").filter { it.isDigit() || it in "+*#" }.take(32)
        val prev = hfpCalls[id]
        val now = System.currentTimeMillis()
        when (state) {
            STATE_INCOMING, STATE_WAITING -> if (prev == null) hfpCalls[id] = HfpCall(number, true, false, now)
            STATE_DIALING, STATE_ALERTING -> if (prev == null) {
                hfpCalls[id] = HfpCall(number, false, false, now)
                // Placed from the iPhone or by Siri; one placed here was already logged by dial().
                record(c, Entry(lookupName(c, number), number, OUT, now))
            }
            STATE_ACTIVE -> prev?.let { hfpCalls[id] = it.copy(answered = true) }
            STATE_TERMINATED -> {
                val p = hfpCalls.remove(id) ?: return
                // Logged at the time it rang, so it merges with the ANCS entry for the same call.
                if (p.incoming) record(c, Entry(lookupName(c, p.number), p.number, if (p.answered) IN else MISSED, p.at))
            }
        }
    }

    /** The watch-side contact name (contacts copied from the iPhone), or "". */
    fun lookupName(c: Context, number: String): String {
        if (number.isEmpty() || c.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return ""
        return runCatching {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            c.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else ""
            } ?: ""
        }.getOrDefault("").take(60)
    }
}

/**
 * Siri over HFP: asks the iPhone (the HFP audio gateway) to start voice recognition (AT+BVRA=1),
 * with the audio on the watch. iOS answers with Siri; Siri composes and sends any reply. The
 * control call is a hidden BluetoothHeadsetClient method reached by reflection — NEEDS LIVE TEST.
 */
object Siri {
    private const val TAG = "Siri"
    private const val HEADSET_CLIENT = 16

    @SuppressLint("MissingPermission")
    fun start(context: Context, done: (Boolean) -> Unit = {}) {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return done(false)
        val phone = LinkService.phoneDevice() ?: return done(false)
        val ok = runCatching {
            adapter.getProfileProxy(context.applicationContext, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    val r = runCatching {
                        proxy.javaClass.getMethod("startVoiceRecognition", BluetoothDevice::class.java).invoke(proxy, phone) as? Boolean ?: false
                    }.onFailure { Log.w(TAG, "startVoiceRecognition failed", it.cause ?: it) }.getOrDefault(false)
                    adapter.closeProfileProxy(profile, proxy)
                    done(r)
                }
                override fun onServiceDisconnected(profile: Int) {}
            }, HEADSET_CLIENT)
        }.getOrDefault(false)
        if (!ok) done(false)
    }
}
