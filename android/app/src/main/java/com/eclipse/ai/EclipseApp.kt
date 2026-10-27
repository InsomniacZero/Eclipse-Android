package com.eclipse.ai

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

class EclipseApp : Application() {

    companion object {
        const val CHANNEL_ID = "eclipse_gateway_channel"
        const val CHANNEL_NAME = "Eclipse Gateway Service"
    }

    override fun onCreate() {
        super.onCreate()
        initNotificationChannel()
        initChaquopy()
    }

    private fun initChaquopy() {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
    }

    private fun initNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background execution of the local Eclipse AI Gateway"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}
