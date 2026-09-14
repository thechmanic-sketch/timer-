package com.freshfocus.timer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/**
 * Fixed daily alarms: 5:30, 9:00, 12:00, 15:00, 18:00, 21:00 (device-local time).
 * Uses setAlarmClock so alarms fire precisely and are treated like a real alarm
 * clock (shown to the user, generally bypasses Do Not Disturb).
 */
object AlarmScheduler {

    private val ALARM_TIMES = listOf(
        5 to 30,   // 5:30am
        9 to 0,    // 9am
        12 to 0,   // 12pm
        15 to 0,   // 3pm
        18 to 0,   // 6pm
        21 to 0    // 9pm
    )

    fun scheduleAllDailyAlarms(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        ALARM_TIMES.forEachIndexed { index, (hour, minute) ->
            scheduleOne(context, alarmManager, requestCode = index, hour = hour, minute = minute)
        }
    }

    private fun scheduleOne(
        context: Context,
        alarmManager: AlarmManager,
        requestCode: Int,
        hour: Int,
        minute: Int
    ) {
        val triggerTime = nextOccurrence(hour, minute)

        // FLAG_IMMUTABLE only exists from API 23 onward.
        val immutableFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }

        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("hour", hour)
            putExtra("minute", minute)
            putExtra("requestCode", requestCode)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val showIntent = PendingIntent.getActivity(
                context, requestCode, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag
            )
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(triggerTime, showIntent),
                pendingIntent
            )
        } else {
            // No setAlarmClock before API 21; setExact (API 19+) is the closest equivalent.
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
        }
    }

    private fun nextOccurrence(hour: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (next.timeInMillis <= now.timeInMillis) {
            next.add(Calendar.DAY_OF_YEAR, 1)
        }
        return next.timeInMillis
    }
}
