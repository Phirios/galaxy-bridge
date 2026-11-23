package nz.galaxybridge.watch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Offline music and podcasts: audio files pushed from the iPhone over the link, kept in the app's
 * private storage and played on the watch (to Bluetooth earbuds, or the speaker if Wear OS allows).
 *
 * Transfer: {"t":"musicPush","id","part","parts","name","title","artist","bytes","data"} with 32 KB
 * of raw audio per part. Parts are written straight to disk; the watch acks every [ACK_EVERY]
 * parts ("musicAck") so the phone never queues a whole song in memory.
 */
object MusicLibrary {
    private const val TAG = "MusicLibrary"
    const val MAX_BYTES = 80L * 1024 * 1024
    private const val ACK_EVERY = 8
    private val EXT = setOf("mp3", "m4a", "aac", "mp4", "ogg", "opus", "flac", "wav")

    data class Track(val id: String, val file: String, val title: String, val artist: String, val bytes: Long, val ms: Long)

    val tracks = MutableStateFlow<List<Track>>(emptyList())
    val incoming = MutableStateFlow<Pair<String, Float>?>(null)

    private var curId = ""
    private var curParts = 0
    private var nextPart = 0
    private var curBytes = 0L
    private var out: FileOutputStream? = null

    private fun dir(c: Context) = File(c.filesDir, "music").apply { mkdirs() }
    private fun index(c: Context) = File(dir(c), "index.json")

    fun load(c: Context): List<Track> {
        if (tracks.value.isEmpty()) {
            val arr = runCatching { JSONArray(index(c).readText()) }.getOrNull() ?: return emptyList()
            tracks.value = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Track(o.optString("id"), o.optString("file"), o.optString("title"), o.optString("artist"),
                    o.optLong("bytes"), o.optLong("ms")).takeIf { File(dir(c), it.file).exists() }
            }
        }
        return tracks.value
    }

    private fun save(c: Context, list: List<Track>) {
        tracks.value = list
        index(c).writeText(JSONArray(list.map {
            JSONObject().put("id", it.id).put("file", it.file).put("title", it.title).put("artist", it.artist)
                .put("bytes", it.bytes).put("ms", it.ms)
        }).toString())
    }

    fun fileOf(c: Context, t: Track) = File(dir(c), t.file)

    fun receivePart(s: LinkService, j: JSONObject) {
        val id = WaveD.id(j.optString("id")) ?: return
        val part = j.optInt("part", -1)
        val parts = j.optInt("parts", -1)
        if (parts !in 1..4000 || part !in 0 until parts) return
        if (part == 0) {
            abort(s)
            val total = j.optLong("bytes", -1)
            if (total !in 1..MAX_BYTES) return fail(s, id, "That file is too big for the watch")
            if (dir(s).usableSpace < total + 100L * 1024 * 1024) return fail(s, id, "Not enough free space on the watch")
            curId = id; curParts = parts; nextPart = 0; curBytes = 0
            out = FileOutputStream(File(dir(s), ".incoming"))
        }
        if (id != curId || parts != curParts || part != nextPart) return
        val bytes = runCatching { Base64.decode(j.optString("data"), Base64.DEFAULT) }.getOrNull()
            ?: return fail(s, id, "Transfer corrupted")
        curBytes += bytes.size
        if (curBytes > MAX_BYTES) return fail(s, id, "That file is too big for the watch")
        runCatching { out!!.write(bytes) }.onFailure { return fail(s, id, "Couldn't write to the watch's storage") }
        nextPart++
        incoming.value = (j.optString("title").take(40)) to (nextPart.toFloat() / parts)
        if (nextPart % ACK_EVERY == 0 || nextPart == parts) {
            s.send(WatchMessages.feature("musicAck", JSONObject().put("id", id).put("part", nextPart - 1)))
        }
        if (nextPart < parts) return
        runCatching { out?.close() }
        out = null
        finish(s, id, j)
    }

    private fun finish(s: LinkService, id: String, j: JSONObject) {
        val tmp = File(dir(s), ".incoming")
        val ext = j.optString("name").substringAfterLast('.', "").lowercase().takeIf { it in EXT } ?: "m4a"
        // Only keep files Android can actually read as audio.
        val ms = runCatching {
            MediaMetadataRetriever().use { r ->
                r.setDataSource(tmp.absolutePath)
                if (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) != "yes") null
                else r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            }
        }.getOrNull()
        if (ms == null) { tmp.delete(); reset(); return fail(s, id, "Not an audio file the watch can play") }
        val file = "$id.$ext"
        if (!tmp.renameTo(File(dir(s), file))) { tmp.delete(); reset(); return fail(s, id, "Couldn't save") }
        val title = WaveD.text(j.optString("title"), 80).ifEmpty { WaveD.text(j.optString("name"), 80).substringBeforeLast('.') }
        val track = Track(id, file, title.ifEmpty { "Track" }, WaveD.text(j.optString("artist"), 60), curBytes, ms)
        save(s, load(s).filterNot { it.id == id } + track)
        reset()
        Features.result(s, "musicPush", true, "${track.title} is on your watch")
        sendLibrary(s)
    }

    private fun fail(s: LinkService, id: String, why: String) {
        abort(s)
        Features.result(s, "musicPush", false, why)
        s.send(WatchMessages.feature("musicAck", JSONObject().put("id", id).put("part", -1)))
    }

    private fun abort(c: Context) {
        runCatching { out?.close() }
        out = null
        File(dir(c), ".incoming").delete()
        reset()
    }

    private fun reset() { curId = ""; curParts = 0; nextPart = 0; curBytes = 0; incoming.value = null }

    fun sendLibrary(s: LinkService) {
        val items = JSONArray(load(s).map {
            JSONObject().put("id", it.id).put("title", it.title).put("artist", it.artist).put("bytes", it.bytes).put("ms", it.ms)
        })
        s.send(WatchMessages.feature("musicLib", JSONObject().put("items", items)
            .put("freeMB", dir(s).usableSpace / (1024 * 1024))))
    }

    fun delete(c: Context, rawId: String) {
        val id = WaveD.id(rawId) ?: return
        val t = load(c).firstOrNull { it.id == id } ?: return
        if (MusicPlayer.state.value.trackId == id) MusicPlayer.stop(c)
        fileOf(c, t).delete()
        save(c, load(c).filterNot { it.id == id })
        (c as? LinkService)?.let { sendLibrary(it) }
    }
}

/** Playback state for the on-watch player UI. */
object MusicPlayer {
    data class State(val trackId: String = "", val title: String = "", val artist: String = "",
                     val playing: Boolean = false, val posMs: Long = 0, val durMs: Long = 0, val at: Long = 0)

    val state = MutableStateFlow(State())

    fun play(c: Context, id: String) = command(c, MusicService.ACTION_PLAY, id)
    fun toggle(c: Context) = command(c, MusicService.ACTION_TOGGLE)
    fun next(c: Context) = command(c, MusicService.ACTION_NEXT)
    fun previous(c: Context) = command(c, MusicService.ACTION_PREV)
    fun seek(c: Context, ms: Long) = command(c, MusicService.ACTION_SEEK, ms = ms)
    fun stop(c: Context) { if (state.value.trackId.isNotEmpty()) command(c, MusicService.ACTION_STOP) }

    private fun command(c: Context, action: String, id: String = "", ms: Long = 0) {
        runCatching {
            c.startForegroundService(Intent(c, MusicService::class.java).setAction(action)
                .putExtra("id", id).putExtra("ms", ms))
        }.onFailure { Log.w("MusicPlayer", "start", it) }
    }

    /** True when a Bluetooth headset/earbuds are the current media output. */
    fun hasHeadset(c: Context): Boolean {
        val am = c.getSystemService(AudioManager::class.java)
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
        }
    }

    /** Opens the watch's Bluetooth screen so the user can connect earbuds. */
    fun openBluetooth(c: Context): Boolean =
        Features.start(c, Intent("com.google.android.clockwork.settings.connectivity.bluetooth.ACTION_BLUETOOTH_SETTINGS")) ||
            Features.start(c, Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
}

/** Foreground media-playback service: one MediaPlayer, a MediaSession and a media notification. */
class MusicService : Service() {
    companion object {
        const val ACTION_PLAY = "nz.galaxybridge.watch.music.PLAY"
        const val ACTION_TOGGLE = "nz.galaxybridge.watch.music.TOGGLE"
        const val ACTION_NEXT = "nz.galaxybridge.watch.music.NEXT"
        const val ACTION_PREV = "nz.galaxybridge.watch.music.PREV"
        const val ACTION_SEEK = "nz.galaxybridge.watch.music.SEEK"
        const val ACTION_STOP = "nz.galaxybridge.watch.music.STOP"
        private const val CHANNEL = "watchMusic"
        private const val NOTIFICATION_ID = 11
    }

    private var player: MediaPlayer? = null
    private var current: MusicLibrary.Track? = null
    private lateinit var session: MediaSession

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Music on watch", NotificationManager.IMPORTANCE_LOW))
        session = MediaSession(this, "WatchMusic").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = toggle(true)
                override fun onPause() = toggle(false)
                override fun onSkipToNext() = skip(1)
                override fun onSkipToPrevious() = skip(-1)
                override fun onSeekTo(pos: Long) { player?.seekTo(pos.toInt()); publish() }
                override fun onStop() = stopAll()
            })
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        when (intent?.action) {
            ACTION_PLAY -> MusicLibrary.load(this).firstOrNull { it.id == intent.getStringExtra("id") }?.let { start(it) }
            ACTION_TOGGLE -> if (player == null) MusicLibrary.load(this).firstOrNull()?.let { start(it) } else toggle(player?.isPlaying != true)
            ACTION_NEXT -> skip(1)
            ACTION_PREV -> if ((player?.currentPosition ?: 0) > 3000) { player?.seekTo(0); publish() } else skip(-1)
            ACTION_SEEK -> { player?.seekTo(intent.getLongExtra("ms", 0).toInt().coerceAtLeast(0)); publish() }
            ACTION_STOP -> stopAll()
        }
        if (player == null && intent?.action != ACTION_STOP) stopAll()
        return START_NOT_STICKY
    }

    private fun start(t: MusicLibrary.Track) {
        player?.release()
        current = t
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                setDataSource(MusicLibrary.fileOf(this@MusicService, t).absolutePath)
                setOnCompletionListener { skip(1) }
                prepare()
                start()
            }
        }.onFailure { Log.w("MusicService", "play", it) }.getOrNull()
        publish()
    }

    private fun toggle(play: Boolean) {
        val p = player ?: return
        if (play) p.start() else p.pause()
        publish()
    }

    private fun skip(by: Int) {
        val list = MusicLibrary.load(this)
        if (list.isEmpty()) return stopAll()
        val i = list.indexOfFirst { it.id == current?.id }
        val next = i + by
        if (next !in list.indices) { if (by > 0) { player?.pause(); player?.seekTo(0); publish() } else start(list.first()); return }
        start(list[next])
    }

    private fun stopAll() {
        player?.release()
        player = null
        current = null
        session.isActive = false
        MusicPlayer.state.value = MusicPlayer.State()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun publish() {
        val p = player
        val t = current
        val playing = runCatching { p?.isPlaying == true }.getOrDefault(false)
        val pos = runCatching { p?.currentPosition?.toLong() ?: 0L }.getOrDefault(0L)
        MusicPlayer.state.value = MusicPlayer.State(t?.id ?: "", t?.title ?: "", t?.artist ?: "", playing, pos,
            t?.ms ?: 0, System.currentTimeMillis())
        session.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, t?.title ?: "")
            .putString(MediaMetadata.METADATA_KEY_ARTIST, t?.artist ?: "")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, t?.ms ?: 0).build())
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO or
                PlaybackState.ACTION_STOP)
            .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, pos, if (playing) 1f else 0f)
            .build())
        session.isActive = t != null
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, ExtrasActivity::class.java).putExtra(ExtrasActivity.EXTRA_PAGE, ExtrasActivity.PAGE_MUSIC),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_music)
            .setContentTitle(current?.title ?: "Music on watch")
            .setContentText(current?.artist ?: "")
            .setContentIntent(open)
            .setOngoing(true)
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken))
            .build()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        session.release()
        super.onDestroy()
    }
}
