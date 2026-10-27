package com.eclipse.ai.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.eclipse.ai.EclipseApp
import com.eclipse.ai.MainActivity
import com.eclipse.ai.R
import java.io.File
import kotlin.concurrent.thread

class GatewayService : Service() {

    companion object {
        private const val TAG = "GatewayService"
        const val NOTIFICATION_ID = 5000

        const val ACTION_START = "com.eclipse.ai.service.ACTION_START"
        const val ACTION_STOP = "com.eclipse.ai.service.ACTION_STOP"
        const val ACTION_STATUS_QUERY = "com.eclipse.ai.service.ACTION_STATUS_QUERY"
        const val ACTION_STATUS_BROADCAST = "com.eclipse.ai.service.ACTION_STATUS_BROADCAST"
        const val EXTRA_IS_RUNNING = "extra_is_running"

        @Volatile
        var isRunning: Boolean = false
            private set

        fun startService(context: Context) {
            val intent = Intent(context, GatewayService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, GatewayService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private var workerThread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopGateway()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STATUS_QUERY -> {
                broadcastStatus()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                startInForeground()
                if (!isRunning) {
                    startGateway()
                }
            }
        }
        return START_STICKY
    }

    private fun startInForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startGateway() {
        if (isRunning) return
        isRunning = true
        broadcastStatus()

        workerThread = thread(name = "EclipseGatewayThread") {
            try {
                Log.i(TAG, "Starting Chaquopy Python platform for Gateway...")
                if (!Python.isStarted()) {
                    Python.start(AndroidPlatform(applicationContext))
                }
                val py = Python.getInstance()

                // Set required environment variables
                val osModule = py.getModule("os")
                val environ = osModule.get("environ")
                environ?.callAttr("__setitem__", "ECLIPSE_CHAQUOPY", "1")

                val dataDir = File(filesDir, "data").apply { mkdirs() }
                environ?.callAttr("__setitem__", "ECLIPSE_DATA_DIR", dataDir.absolutePath)

                Log.i(TAG, "Invoking eclipse.server.start_gateway(127.0.0.1, 5000)...")
                val serverModule = py.getModule("eclipse.server")
                serverModule.callAttr("start_gateway", "127.0.0.1", 5000)
            } catch (e: Exception) {
                Log.e(TAG, "Eclipse Gateway terminated with exception: ${e.message}", e)
            } finally {
                isRunning = false
                broadcastStatus()
                Log.i(TAG, "Eclipse Gateway background thread exited")
            }
        }
    }

    private fun stopGateway() {
        if (!isRunning) return
        Log.i(TAG, "Stopping Eclipse Gateway server...")
        try {
            if (Python.isStarted()) {
                val py = Python.getInstance()
                val serverModule = py.getModule("eclipse.server")
                serverModule.callAttr("stop_gateway")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during stop_gateway call: ${e.message}", e)
        }
        try {
            workerThread?.join(2000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        workerThread = null
        isRunning = false
        broadcastStatus()
    }

    private fun broadcastStatus() {
        val intent = Intent(ACTION_STATUS_BROADCAST).apply {
            putExtra(EXTRA_IS_RUNNING, isRunning)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val stopIntent = Intent(this, GatewayService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        return NotificationCompat.Builder(this, EclipseApp.CHANNEL_ID)
            .setContentTitle("Eclipse AI Gateway")
            .setContentText("Local server running on http://127.0.0.1:5000")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .addAction(R.drawable.ic_stop, "Stop Gateway", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        stopGateway()
        super.onDestroy()
    }
}
