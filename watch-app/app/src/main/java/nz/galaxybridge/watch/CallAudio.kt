package nz.galaxybridge.watch

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log

/**
 * Call audio on the watch: connects the watch's hands-free (HFP client) link to the iPhone, so
 * calls answered on the watch can use its speaker and mic. Android only turns this on by itself
 * for an Android companion phone; for an iPhone the app has to ask on each connection.
 * The connect call is a system API reached by reflection and may be refused on some watches.
 */
object CallAudio {
    /**
     * True while the hands-free link to the iPhone is up. The watch's own Samsung Phone app then
     * rings for incoming calls and logs them, so our own call alert and history stay out of the way.
     */
    @SuppressLint("MissingPermission")
    fun hfpConnected(context: Context): Boolean = runCatching {
        context.getSystemService(BluetoothManager::class.java).adapter
            .getProfileConnectionState(HEADSET_CLIENT) == BluetoothProfile.STATE_CONNECTED
    }.getOrDefault(false)

    private const val TAG = "CallAudio"
    private const val HEADSET_CLIENT = 16

    /** Call-control actions driven from the phone app or the watch's own Phone UI. */
    enum class Action { DIAL, ANSWER, REJECT, HANGUP }

    @SuppressLint("MissingPermission")
    fun connect(context: Context, phone: BluetoothDevice) {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        val app = context.applicationContext
        adapter.getProfileProxy(app, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                val state = runCatching { proxy.getConnectionState(phone) }.getOrDefault(BluetoothProfile.STATE_DISCONNECTED)
                if (state == BluetoothProfile.STATE_DISCONNECTED) {
                    val r = runCatching {
                        proxy.javaClass.getMethod("connect", BluetoothDevice::class.java).invoke(proxy, phone) as Boolean
                    }
                    Log.i(TAG, "connect: ${r.getOrNull() ?: (r.exceptionOrNull()?.cause ?: r.exceptionOrNull())}")
                }
                adapter.closeProfileProxy(profile, proxy)
            }
            override fun onServiceDisconnected(profile: Int) {}
        }, HEADSET_CLIENT)
    }

    /**
     * Places, answers, rejects or hangs up a call on the bonded iPhone over HFP (Hands-Free),
     * with audio on the watch. BluetoothHeadsetClient's control methods are system/@hide, so they
     * are reached by reflection and vary across OS versions; each candidate signature is tried in
     * turn and failure is logged, never thrown.
     *
     * LIVE TEST NEEDS A CLASSIC-BT HFP BOND between the watch and the iPhone (the GBLink BLE bond
     * is not an HFP bond). That bond is NOT set up in this environment — do not bond anything.
     * This routes to HeadsetClientService, which is live on the watch; verify end-to-end only once
     * a real HFP bond exists.
     */
    @SuppressLint("MissingPermission")
    fun control(context: Context, action: Action, number: String = "") {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        val phone = LinkService.phoneDevice() ?: run { Log.w(TAG, "no bonded phone for $action"); return }
        val app = context.applicationContext
        adapter.getProfileProxy(app, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                runCatching { perform(proxy, phone, action, number) }
                    .onFailure { Log.w(TAG, "$action failed", it.cause ?: it) }
                adapter.closeProfileProxy(profile, proxy)
            }
            override fun onServiceDisconnected(profile: Int) {}
        }, HEADSET_CLIENT)
    }

    /** Invokes the first reflective signature that exists for [action]. */
    private fun perform(proxy: BluetoothProfile, phone: BluetoothDevice, action: Action, number: String) {
        val cls = proxy.javaClass
        fun tryCall(name: String, argTypes: Array<Class<*>>, args: Array<Any?>): Boolean = runCatching {
            cls.getMethod(name, *argTypes).invoke(proxy, *args); true
        }.getOrDefault(false)

        val ok = when (action) {
            // dial(BluetoothDevice, String): places a call to [number].
            Action.DIAL -> tryCall("dial", arrayOf(BluetoothDevice::class.java, String::class.java), arrayOf(phone, number))
            // acceptCall(BluetoothDevice, int flag): 0 = BluetoothHeadsetClient.CALL_ACCEPT_NONE.
            Action.ANSWER -> tryCall("acceptCall", arrayOf(BluetoothDevice::class.java, Int::class.javaPrimitiveType!!), arrayOf(phone, 0))
            Action.REJECT -> tryCall("rejectCall", arrayOf(BluetoothDevice::class.java), arrayOf(phone))
            // terminateCall signature changed across versions: (device, UUID) then (device, call).
            Action.HANGUP -> tryCall("terminateCall", arrayOf(BluetoothDevice::class.java, java.util.UUID::class.java), arrayOf(phone, null)) ||
                tryCall("terminateCall", arrayOf(BluetoothDevice::class.java, Class.forName("android.bluetooth.BluetoothHeadsetClientCall")), arrayOf(phone, null)) ||
                tryCall("rejectCall", arrayOf(BluetoothDevice::class.java), arrayOf(phone))
        }
        Log.i(TAG, "$action -> $ok")
    }
}
