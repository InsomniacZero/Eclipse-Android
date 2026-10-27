package com.eclipse.ai.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.eclipse.ai.service.GatewayService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            GatewayService.startService(context)
        }
    }
}
