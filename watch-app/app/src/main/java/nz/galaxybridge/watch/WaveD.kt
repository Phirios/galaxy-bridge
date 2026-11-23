package nz.galaxybridge.watch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.json.JSONObject

/**
 * Entry point for the media, customisation and productivity features: offline music, the
 * presentation clicker, face scheduling, QR passes, smart-home shortcuts, world clock,
 * translate, checklists and tile status. Keeps the shared files (Features.kt, LinkService.kt)
 * down to one dispatch branch and two lifecycle calls.
 */
object WaveD {
    private const val TAG = "WaveD"

    /** Phone → watch message types handled here. Must match GBFeature.toWatch in GBLink.swift. */
    val FROM_PHONE = setOf(
        "musicPush", "musicList", "musicDelete", "faceSchedule", "getFaceSchedule",
        "qrPass", "qrPassDelete", "qrPassList", "homeScenes", "homeSceneResult",
        "toolsConfig", "translateResult", "checklist", "getTileState",
    )

    private var scope: CoroutineScope? = null

    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Alarms can be held back in doze; catch up on a missed face switch when the user looks.
            FaceSchedule.catchUp(context)
        }
    }

    fun handle(s: LinkService, type: String, j: JSONObject) {
        runCatching {
            when (type) {
                "musicPush" -> MusicLibrary.receivePart(s, j)
                "musicList" -> MusicLibrary.sendLibrary(s)
                "musicDelete" -> MusicLibrary.delete(s, j.optString("id"))
                "faceSchedule" -> FaceSchedule.receive(s, j)
                "getFaceSchedule" -> FaceSchedule.sendState(s)
                "qrPass" -> QrPasses.receive(s, j)
                "qrPassDelete" -> QrPasses.delete(s, j.optString("id"))
                "qrPassList" -> QrPasses.sendList(s)
                "homeScenes" -> HomeScenes.receive(s, j)
                "homeSceneResult" -> HomeScenes.result(s, j)
                "toolsConfig" -> ToolsConfig.receive(s, j)
                "translateResult" -> Translate.result(j)
                "checklist" -> Checklist.receive(s, j)
                "getTileState" -> TileState.send(s)
            }
        }.onFailure { Log.w(TAG, "feature $type failed", it) }
    }

    /** Called from LinkService.onCreate. */
    fun onServiceCreate(s: LinkService) {
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = sc
        FaceSchedule.reschedule(s)
        FaceSchedule.catchUp(s)
        Workouts.live.onEach { FaceSchedule.onWorkout(s, it != null) }.launchIn(sc)
        runCatching { s.registerReceiver(screenOn, IntentFilter(Intent.ACTION_SCREEN_ON)) }
    }

    /** Called from LinkService.onDestroy. */
    fun onServiceDestroy(s: LinkService) {
        runCatching { s.unregisterReceiver(screenOn) }
        Presenter.stop(s)
        scope?.cancel()
        scope = null
    }

    // --- shared validation helpers ---

    private val ID = Regex("^[A-Za-z0-9_-]{1,64}$")
    fun id(raw: String?): String? = raw?.takeIf { ID.matches(it) }

    fun text(raw: String?, max: Int): String = Features.clean(raw ?: "").trim().take(max)
}
