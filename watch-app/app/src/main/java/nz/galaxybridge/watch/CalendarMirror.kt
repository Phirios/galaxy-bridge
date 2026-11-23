package nz.galaxybridge.watch

import android.Manifest
import android.accounts.Account
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.util.Log
import java.util.TimeZone

/**
 * Mirrors the iPhone's upcoming events into the watch's own calendar storage, as a local
 * "iPhone" calendar, so Samsung's Calendar app, its tile and watch-face complications show them
 * natively. The calendar is owned by this app (local account), replaced wholesale on each update
 * from the phone, and never synced anywhere else.
 */
object CalendarMirror {
    private const val TAG = "CalendarMirror"
    private const val ACCOUNT = "iPhone"
    private val account = Account(ACCOUNT, CalendarContract.ACCOUNT_TYPE_LOCAL)

    private fun asSync(uri: Uri): Uri = uri.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, account.name)
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, account.type)
        .build()

    private fun allowed(c: Context) =
        c.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            c.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private fun calendarId(c: Context): Long? {
        val cr = c.contentResolver
        cr.query(Calendars.CONTENT_URI, arrayOf(Calendars._ID),
            "${Calendars.ACCOUNT_NAME}=? AND ${Calendars.ACCOUNT_TYPE}=?", arrayOf(account.name, account.type), null)
            ?.use { if (it.moveToFirst()) return it.getLong(0) }
        val v = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, account.name)
            put(Calendars.ACCOUNT_TYPE, account.type)
            put(Calendars.NAME, "iPhone")
            put(Calendars.CALENDAR_DISPLAY_NAME, "iPhone")
            put(Calendars.CALENDAR_COLOR, 0xFF4DA3FF.toInt())
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_READ)
            put(Calendars.OWNER_ACCOUNT, account.name)
            put(Calendars.VISIBLE, 1)
            put(Calendars.SYNC_EVENTS, 1)
            put(Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
        }
        return cr.insert(asSync(Calendars.CONTENT_URI), v)?.lastPathSegment?.toLongOrNull()
    }

    /** Replaces the mirrored calendar's events with [events]. Safe to call often. */
    fun update(c: Context, events: List<PhoneMessage.Event>) {
        if (!allowed(c)) return
        runCatching {
            val id = calendarId(c) ?: return
            val cr = c.contentResolver
            cr.delete(asSync(Events.CONTENT_URI), "${Events.CALENDAR_ID}=?", arrayOf(id.toString()))
            for (e in events) {
                val v = ContentValues().apply {
                    put(Events.CALENDAR_ID, id)
                    put(Events.TITLE, e.title.ifEmpty { "Event" })
                    put(Events.ALL_DAY, if (e.allDay) 1 else 0)
                    // All-day events are stored in UTC midnight-to-midnight, per CalendarContract.
                    put(Events.EVENT_TIMEZONE, if (e.allDay) "UTC" else TimeZone.getDefault().id)
                    put(Events.DTSTART, e.start)
                    put(Events.DTEND, maxOf(e.end, e.start + 60_000))
                    put(Events.ACCESS_LEVEL, Events.ACCESS_DEFAULT)
                }
                cr.insert(asSync(Events.CONTENT_URI), v)
            }
            Log.i(TAG, "mirrored ${events.size} events")
        }.onFailure { Log.w(TAG, "mirror failed", it) }
    }
}
