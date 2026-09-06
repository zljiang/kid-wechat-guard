package com.family.wxguard.guard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.family.wxguard.R
import com.family.wxguard.ui.MainActivity

object NotificationHelper {
    private const val CHANNEL_GUARD = "guard"
    private const val CHANNEL_EVENT = "event"
    private const val NOTIF_ID_GUARD = 1
    private const val NOTIF_ID_GRACE = 2

    private var context: Context? = null
    private var nm: NotificationManager? = null

    fun init(context: Context) {
        this.context = context.applicationContext
        nm = this.context?.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.createNotificationChannel(
            NotificationChannel(CHANNEL_GUARD, "守护常驻", NotificationManager.IMPORTANCE_MIN)
        )
        nm?.createNotificationChannel(
            NotificationChannel(CHANNEL_EVENT, "守护事件", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    fun guardNotification(): Notification {
        val ctx = context ?: return Notification()
        val pi = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(ctx, CHANNEL_GUARD)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("微信内守护运行中")
            .setContentText("守护微信内小程序 / 视频号时长")
            .setContentIntent(pi)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    fun showGraceReminder(title: String, body: String) {
        val ctx = context ?: return
        nm?.notify(
            NOTIF_ID_GRACE,
            NotificationCompat.Builder(ctx, CHANNEL_EVENT)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                .build()
        )
    }

    fun cancelGraceReminder() {
        nm?.cancel(NOTIF_ID_GRACE)
    }
}
