package com.freshfocus.timer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val hour = intent.getIntExtra("hour", 0)
        val minute = intent.getIntExtra("minute", 0)

        val ringIntent = Intent(context, AlarmRingActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION
            )
            putExtra("hour", hour)
            putExtra("minute", minute)
        }
        context.startActivity(ringIntent)

        // Re-schedule this specific slot for tomorrow.
        AlarmScheduler.scheduleAllDailyAlarms(context)
    }
}
