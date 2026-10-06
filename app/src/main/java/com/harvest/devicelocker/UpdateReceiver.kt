package com.harvest.devicelocker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
        when (status) {
            PackageInstaller.STATUS_SUCCESS ->
                Log.i("DeviceLocker", "self-update installed")
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Shouldn't happen under Device Owner, but handle it if this
                // ever runs without DO privileges.
                val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirmIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                confirmIntent?.let { context.startActivity(it) }
            }
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w("DeviceLocker", "self-update failed: status=$status message=$message")
            }
        }
    }
}
