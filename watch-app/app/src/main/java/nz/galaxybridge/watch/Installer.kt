package nz.galaxybridge.watch

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Push-installs an APK that the iPhone sends over the link. The bytes arrive in ordered base64
 * parts (same chunking as photos/recordings); they're streamed to a staging file, written into a
 * PackageInstaller session and committed. The system then shows its own one-tap confirm screen on
 * the watch (STATUS_PENDING_USER_ACTION → we launch the returned confirm Intent).
 *
 * This needs REQUEST_INSTALL_PACKAGES, which is prot=signature|appop: enable it at setup with
 * `adb shell appops set nz.galaxybridge.watch REQUEST_INSTALL_PACKAGES allow`, or turn on
 * "Install unknown apps" for this app on the watch. Silent (unattended) install is walled.
 */
object Installer {
    private const val TAG = "Installer"
    private const val MAX_APK = 200 * 1024 * 1024
    private const val ACTION_RESULT = "nz.galaxybridge.watch.INSTALL_RESULT"
    private const val EXTRA_LABEL = "label"

    private var id = ""
    private var parts = 0
    private var received = 0
    private var total = 0L
    private var label = ""
    private var out: FileOutputStream? = null
    private var staging: File? = null

    @Volatile private var receiverRegistered = false

    /** {"id","part","parts","name","data"}: parts arrive in order over the ordered link. */
    fun receive(s: LinkService, j: JSONObject) {
        ensureReceiver(s.applicationContext)
        val idv = j.optString("id").take(64)
        val part = j.optInt("part", -1)
        val n = j.optInt("parts", -1)
        if (idv.isEmpty() || n !in 1..6000 || part !in 0 until n) return
        if (part == 0) {
            if (!canInstall(s)) return result(s, "", "blocked",
                "Allow installing apps for Wear OS Bridge on the watch (Install unknown apps)")
            begin(idv, n, Features.clean(j.optString("name")).take(60))
        }
        if (idv != id || n != parts || part != received) return reset()
        val bytes = runCatching { Base64.decode(j.optString("data"), Base64.DEFAULT) }.getOrNull() ?: return reset()
        total += bytes.size
        if (total > MAX_APK || out == null) return reset()
        runCatching { out!!.write(bytes) }.onFailure { Log.w(TAG, "write apk", it); return reset() }
        received++
        if (received < parts) return
        val apk = finishStaging() ?: return result(s, "", "failed", "Couldn't stage the APK")
        install(s, apk, label)
    }

    private fun begin(idv: String, n: Int, name: String) {
        reset()
        id = idv; parts = n; received = 0; total = 0; label = name
        staging = File(File(stagingDirRef, "install").apply { mkdirs() }, "push.apk")
        out = runCatching { FileOutputStream(staging) }.getOrNull()
    }

    private fun finishStaging(): File? {
        runCatching { out?.flush(); out?.close() }
        out = null
        return staging?.takeIf { it.exists() && it.length() > 0 }
    }

    private fun reset() {
        runCatching { out?.close() }
        out = null; id = ""; parts = 0; received = 0; total = 0; label = ""
    }

    /** Only the app's own staging dir; set once from the first LinkService context. */
    private lateinit var stagingDirRef: File
    private fun ensureReceiver(ctx: Context) {
        if (!::stagingDirRef.isInitialized) stagingDirRef = ctx.cacheDir
        if (receiverRegistered) return
        receiverRegistered = true
        runCatching {
            val filter = IntentFilter(ACTION_RESULT)
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(resultReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                ctx.registerReceiver(resultReceiver, filter)
            }
        }.onFailure { Log.w(TAG, "register result receiver", it) }
    }

    private fun canInstall(s: LinkService): Boolean =
        runCatching { s.packageManager.canRequestPackageInstalls() }.getOrDefault(false)

    private fun install(s: LinkService, apk: File, label: String) {
        runCatching {
            val pi = s.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            runCatching { params.setAppLabel(label.ifEmpty { "App" }) }
            val sid = pi.createSession(params)
            pi.openSession(sid).use { session ->
                session.openWrite("apk", 0, apk.length()).use { o ->
                    apk.inputStream().use { it.copyTo(o) }
                    session.fsync(o)
                }
                val intent = Intent(ACTION_RESULT).setPackage(s.packageName).putExtra(EXTRA_LABEL, label)
                val pending = PendingIntent.getBroadcast(s, sid, intent,
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                session.commit(pending.intentSender)
            }
        }.onFailure {
            Log.w(TAG, "install failed", it)
            result(s, "", "failed", "Install couldn't start")
        }
        apk.delete()
    }

    /** Receives the PackageInstaller result; launches the confirm screen, reports status. */
    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_RESULT) return
            val s = LinkService.current() ?: return
            val label = intent.getStringExtra(EXTRA_LABEL).orEmpty()
            val pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME).orEmpty()
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    val launched = confirm != null && Features.start(context, confirm)
                    result(s, pkg, if (launched) "confirm" else "failed",
                        if (launched) "Confirm the install on your watch" else "Couldn't show the install prompt")
                }
                PackageInstaller.STATUS_SUCCESS -> result(s, pkg, "ok",
                    "${label.ifEmpty { pkg }} installed on your watch")
                else -> {
                    val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                    result(s, pkg, "failed", msg.ifEmpty { "Install failed" })
                }
            }
        }
    }

    private fun result(s: Context, pkg: String, status: String, detail: String) {
        LinkService.sendFeature("install", JSONObject().put("pkg", pkg).put("status", status)
            .put("detail", detail.take(120)))
    }
}
