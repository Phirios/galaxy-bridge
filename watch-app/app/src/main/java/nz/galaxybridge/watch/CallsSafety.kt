package nz.galaxybridge.watch

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Calls, messaging and safety (Wave B): favourites, call history, quick replies, fall detection,
 * SOS, heart-rate alerts, no-movement check-in and loud "find". Phone messages arrive here from
 * [Features.handle]; the Swift side is WearLink/CallsSafety.swift and must stay in step.
 *
 * Hard rule: automatic calls only ever go to the personal contact the user picked on the iPhone,
 * after a visible countdown that can be cancelled, and never to a public emergency number.
 */
object CallsSafety {
    private const val TAG = "CallsSafety"
    private const val PREFS = "callsSafety"

    data class Config(
        val name: String = "",
        val number: String = "",
        val fall: Boolean = false,
        val sos: Boolean = false,
        val countdown: Int = 30,
        val hr: Boolean = false,
        val hrHigh: Int = 150,
        val hrLow: Int = 40,
        val checkIn: Boolean = false,
        val checkInHours: Int = 3,
    ) {
        val hasContact get() = Emergency.isAllowedForAutoCall(number)
        val contactLabel get() = name.ifEmpty { number }

        fun toJson(): JSONObject = JSONObject().put("name", name).put("number", number).put("fall", fall).put("sos", sos)
            .put("countdown", countdown).put("hr", hr).put("hrHigh", hrHigh).put("hrLow", hrLow)
            .put("checkIn", checkIn).put("checkInHours", checkInHours)

        companion object {
            /** Validates a config from the phone. Auto-call features need an allowed contact. */
            fun from(j: JSONObject): Config {
                val number = j.optString("number").trim().take(32)
                val allowed = Emergency.isAllowedForAutoCall(number)
                return Config(
                    name = Features.clean(j.optString("name")).trim().take(60),
                    number = if (allowed) number else "",
                    fall = allowed && j.optBoolean("fall", false),
                    sos = allowed && j.optBoolean("sos", false),
                    countdown = j.optInt("countdown", 30).coerceIn(MIN_COUNTDOWN, 60),
                    hr = j.optBoolean("hr", false),
                    hrHigh = j.optInt("hrHigh", 150).coerceIn(100, 220),
                    hrLow = j.optInt("hrLow", 40).coerceIn(30, 70),
                    checkIn = allowed && j.optBoolean("checkIn", false),
                    checkInHours = j.optInt("checkInHours", 3).coerceIn(1, 12),
                )
            }
        }
    }

    /** Never less than this, whatever the phone sends. */
    const val MIN_COUNTDOWN = 15

    data class Favourite(val name: String, val number: String)

    val DEFAULT_REPLIES = listOf("On my way", "Running late", "Can't talk now", "Call you back soon", "Yes", "No", "Thanks!")

    val config = MutableStateFlow(Config())
    val favourites = MutableStateFlow<List<Favourite>>(emptyList())
    val quickReplies = MutableStateFlow(DEFAULT_REPLIES)

    private val PHONE = Regex("^[0-9+(][0-9*#+ ().-]{1,31}$")
    fun isDialable(number: String) = PHONE.matches(number.trim())

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Called from LinkService.onCreate. */
    fun start(s: LinkService) {
        load(s)
        CallLog.start(s)
        SafetyMonitor.apply(s, config.value)
    }

    /** Called from LinkService.onDestroy. */
    fun stop(s: LinkService) {
        CallLog.stop(s)
        SafetyMonitor.stop(s)
        FindLoud.stop(s)
    }

    fun load(c: Context) {
        val p = prefs(c)
        p.getString("config", null)?.let { runCatching { config.value = Config.from(JSONObject(it)) } }
        p.getString("favourites", null)?.let { runCatching { favourites.value = parseFavourites(JSONArray(it)) } }
        p.getString("replies", null)?.let { runCatching { quickReplies.value = parseReplies(JSONArray(it)).ifEmpty { DEFAULT_REPLIES } } }
    }

    fun handle(s: LinkService, type: String, j: JSONObject) {
        runCatching {
            when (type) {
                "callFavourites" -> {
                    val list = parseFavourites(j.optJSONArray("items") ?: JSONArray())
                    favourites.value = list
                    prefs(s).edit().putString("favourites", JSONArray(list.map { JSONObject().put("n", it.name).put("p", it.number) }).toString()).apply()
                    CallsTile.refresh(s)
                    Features.result(s, "callFavourites", true, "${list.size} favourites on your watch")
                }
                "callLogGet" -> CallLog.sendTo(s)
                "safetyConfig" -> {
                    val c = Config.from(j)
                    config.value = c
                    prefs(s).edit().putString("config", c.toJson().toString()).apply()
                    SafetyMonitor.apply(s, c)
                    sendState(s)
                }
                "safetyGet" -> sendState(s)
                "findLoud" -> if (j.optBoolean("on", false)) FindLoud.start(s) else FindLoud.stop(s)
                "quickReplies" -> {
                    val list = parseReplies(j.optJSONArray("items") ?: JSONArray()).ifEmpty { DEFAULT_REPLIES }
                    quickReplies.value = list
                    prefs(s).edit().putString("replies", JSONArray(list).toString()).apply()
                }
            }
        }.onFailure { Log.w(TAG, "$type failed", it) }
    }

    /** What the watch is actually doing, so the iPhone shows the truth (e.g. no accelerometer). */
    fun sendState(s: LinkService) {
        val c = config.value
        s.send(WatchMessages.feature("safetyState", JSONObject()
            .put("hasContact", c.hasContact)
            .put("fall", c.fall).put("sos", c.sos).put("hr", c.hr).put("checkIn", c.checkIn)
            .put("countdown", c.countdown)
            .put("fallRunning", SafetyMonitor.fallRunning())
            .put("hrSensor", SafetyMonitor.hasHeartRateSensor(s))
            .put("favourites", favourites.value.size)))
    }

    private fun parseFavourites(a: JSONArray): List<Favourite> = (0 until minOf(a.length(), 12)).mapNotNull { i ->
        val o = a.optJSONObject(i) ?: return@mapNotNull null
        val number = o.optString("p").trim().take(32)
        if (!isDialable(number)) return@mapNotNull null
        Favourite(Features.clean(o.optString("n")).trim().take(60).ifEmpty { number }, number)
    }

    private fun parseReplies(a: JSONArray): List<String> = (0 until minOf(a.length(), 8))
        .map { Features.clean(a.optString(it)).trim().take(60) }.filter { it.isNotEmpty() }
}

/** Public emergency numbers are never auto-dialled. Mirrors EmergencyNumbers in WearLink. */
object Emergency {
    val BLOCKED = setOf(
        "000", "08", "100", "101", "102", "103", "108", "110", "111", "112", "113", "117", "118", "119",
        "122", "123", "999", "911", "990", "991", "995", "997", "998", "106", "190", "191", "192", "193", "197",
    )
    private val SHAPE = Regex("^[0-9+(][0-9 ().-]*$")

    fun isPublicEmergency(number: String): Boolean {
        val d = number.filter { it.isDigit() }
        return d.length < 5 || d in BLOCKED
    }

    fun isAllowedForAutoCall(number: String): Boolean {
        val t = number.trim()
        return t.length <= 32 && SHAPE.matches(t) && !isPublicEmergency(t)
    }
}
