package com.lowtube.android.downloads

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.lowtube.android.MainActivity
import com.lowtube.android.R

/** Shared notification construction so service and locale acceptance use the same payload code. */
internal object DownloadNotificationFactory {
    const val CHANNEL_ID = "lowtube_downloads"

    fun build(
        context: Context,
        title: String,
        downloaded: Long,
        total: Long,
        active: Boolean,
        id: Long = 0,
    ): Notification {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_DOWNLOADS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.downloads))
            .setContentText(title)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(active)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

        if (total > 0) builder.setProgress(100, ((downloaded * 100L) / total).toInt().coerceIn(0, 100), false)
        else if (active) builder.setProgress(0, 0, true)

        if (active && id > 0) {
            val cancel = PendingIntent.getService(
                context,
                0,
                Intent(context, DownloadService::class.java)
                    .setAction(DownloadService.ACTION_CANCEL)
                    .setData(Uri.fromParts("lowtube-download", id.toString(), null))
                    .putExtra(DownloadService.EXTRA_ID, id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, context.getString(R.string.cancel_download), cancel)
        }
        return builder.build()
    }

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, context.getString(R.string.downloads), NotificationManager.IMPORTANCE_LOW),
                )
        }
    }
}
