package nz.galaxybridge.watch

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

/**
 * The rest of Apple Media Service's remote commands (AMS spec, RemoteCommandID). Whether each one
 * does anything depends on the iPhone app that is playing: Music and Podcasts honour like/skip-15,
 * many third-party players ignore them. AMS has no "seek to" command, so scrubbing goes over our
 * own link to the iPhone app instead (see [seek]).
 */
object AmsExtra {
    const val CMD_REPEAT = 7
    const val CMD_SHUFFLE = 8
    const val CMD_SKIP_FORWARD = 9
    const val CMD_SKIP_BACKWARD = 10
    const val CMD_LIKE = 11
    const val CMD_DISLIKE = 12
    const val CMD_BOOKMARK = 13

    /**
     * Asks the iPhone app to move the system music player to [sec]. iOS only lets an app seek the
     * Apple Music player (MPMusicPlayerController.systemMusicPlayer); other players can't be
     * scrubbed from outside. Returns false if the phone isn't connected.
     */
    fun seek(sec: Double): Boolean =
        sec.isFinite() && sec >= 0 && LinkService.sendFeature("mediaSeek", JSONObject().put("sec", sec))
}

/**
 * Presentation clicker: while on, the watch presents itself as a small Bluetooth keyboard with only
 * the keys a slideshow needs (arrows, page up/down, B for blank, Esc). Works with Keynote on the
 * iPhone or a computer the watch is paired with.
 *
 * Android allows one HID app per device, so this temporarily takes over from the camera shutter
 * ([HidShutter]) and gives it back when stopped. While a Bluetooth keyboard is connected iOS hides
 * its on-screen keyboard; stopping the clicker disconnects it again.
 */
@SuppressLint("MissingPermission")
object Presenter {
    private const val TAG = "Presenter"
    private const val REPORT_ID = 2

    const val KEY_RIGHT = 0x4F
    const val KEY_LEFT = 0x50
    const val KEY_PAGE_DOWN = 0x4E
    const val KEY_PAGE_UP = 0x4B
    const val KEY_B = 0x05
    const val KEY_ESC = 0x29

    private val DESCRIPTOR = byteArrayOf(
        0x05, 0x01,                        // Usage Page (Generic Desktop)
        0x09, 0x06,                        // Usage (Keyboard)
        0xA1.toByte(), 0x01,               // Collection (Application)
        0x85.toByte(), REPORT_ID.toByte(), //   Report ID (2)
        0x05, 0x07,                        //   Usage Page (Keyboard/Keypad)
        0x19, 0xE0.toByte(),               //   Usage Minimum (Left Control)
        0x29, 0xE7.toByte(),               //   Usage Maximum (Right GUI)
        0x15, 0x00,                        //   Logical Minimum (0)
        0x25, 0x01,                        //   Logical Maximum (1)
        0x75, 0x01,                        //   Report Size (1)
        0x95.toByte(), 0x08,               //   Report Count (8)
        0x81.toByte(), 0x02,               //   Input (Data, Variable, Absolute) modifiers
        0x95.toByte(), 0x01,               //   Report Count (1)
        0x75, 0x08,                        //   Report Size (8)
        0x81.toByte(), 0x01,               //   Input (Constant) reserved
        0x95.toByte(), 0x06,               //   Report Count (6)
        0x75, 0x08,                        //   Report Size (8)
        0x15, 0x00,                        //   Logical Minimum (0)
        0x25, 0x65,                        //   Logical Maximum (101)
        0x05, 0x07,                        //   Usage Page (Keyboard/Keypad)
        0x19, 0x00,                        //   Usage Minimum (0)
        0x29, 0x65,                        //   Usage Maximum (101)
        0x81.toByte(), 0x00,               //   Input (Data, Array)
        0xC0.toByte(),                     // End Collection
    )

    data class Target(val address: String, val name: String)

    enum class State { OFF, STARTING, WAITING, CONNECTED }

    val state = MutableStateFlow(State.OFF)
    val hostName = MutableStateFlow("")

    private val main = Handler(Looper.getMainLooper())
    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private var wanted: String? = null

    /** The iPhone and any paired computers/tablets the clicker can drive. */
    fun targets(c: Context): List<Target> {
        val adapter = c.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        val trusted = c.getSharedPreferences("link", Context.MODE_PRIVATE).getString("trustedPhone", null)
        return runCatching { adapter.bondedDevices }.getOrDefault(emptySet())
            .filter { it.type != BluetoothDevice.DEVICE_TYPE_LE || it.address == trusted }
            .filter {
                val major = it.bluetoothClass?.majorDeviceClass
                it.address == trusted || major == BluetoothClass.Device.Major.COMPUTER ||
                    major == BluetoothClass.Device.Major.PHONE
            }
            .map { Target(it.address, it.name?.take(30) ?: "Device") }
            .sortedByDescending { it.name.contains("iPhone", ignoreCase = true) }
    }

    fun start(c: Context, target: Target?) {
        if (state.value != State.OFF) stop(c)
        wanted = target?.address ?: targets(c).firstOrNull()?.address
        state.value = State.STARTING
        // Release the camera-shutter HID app first; only one may be registered.
        LinkService.current()?.hid?.stop()
        val adapter = c.getSystemService(BluetoothManager::class.java)?.adapter ?: run { state.value = State.OFF; return }
        adapter.getProfileProxy(c.applicationContext, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                val device = proxy as BluetoothHidDevice
                hid = device
                val sdp = BluetoothHidDeviceAppSdpSettings("Wear OS Bridge Clicker", "Presentation remote", "Wear OS Bridge",
                    BluetoothHidDevice.SUBCLASS1_KEYBOARD, DESCRIPTOR)
                if (!device.registerApp(sdp, null, null, c.mainExecutor, callback)) {
                    Log.w(TAG, "registerApp refused")
                    stop(c)
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                hid = null
                host = null
                if (state.value != State.OFF) state.value = State.OFF
            }
        }, BluetoothProfile.HID_DEVICE)
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            if (!registered) return
            state.value = State.WAITING
            connect()
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, st: Int) {
            if (st == BluetoothProfile.STATE_CONNECTED) {
                host = device
                hostName.value = device.name?.take(30) ?: "Device"
                state.value = State.CONNECTED
            } else if (device == host && st == BluetoothProfile.STATE_DISCONNECTED) {
                host = null
                if (state.value != State.OFF) state.value = State.WAITING
            }
        }
    }

    fun connect() {
        val device = hid ?: return
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        val target = runCatching { adapter.bondedDevices.firstOrNull { it.address == wanted } }.getOrNull() ?: return
        if (!device.connect(target)) Log.w(TAG, "connect refused")
    }

    /** Taps one key. Returns false when no host is connected. */
    fun press(key: Int): Boolean {
        val device = hid ?: return false
        val target = host ?: run { connect(); return false }
        val down = byteArrayOf(0, 0, key.toByte(), 0, 0, 0, 0, 0)
        if (!device.sendReport(target, REPORT_ID, down)) return false
        main.postDelayed({ runCatching { device.sendReport(target, REPORT_ID, ByteArray(8)) } }, 50)
        return true
    }

    fun stop(c: Context) {
        if (state.value == State.OFF && hid == null) return
        runCatching { host?.let { hid?.disconnect(it) } }
        runCatching { hid?.unregisterApp() }
        hid = null
        host = null
        state.value = State.OFF
        hostName.value = ""
        // Give the shutter back.
        main.postDelayed({ LinkService.current()?.hid?.start() }, 400)
    }
}
