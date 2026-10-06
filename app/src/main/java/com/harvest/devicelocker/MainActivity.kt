package com.harvest.devicelocker

import android.app.ActivityManager
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName

    private lateinit var titleText: TextView
    private lateinit var statusText: TextView
    private lateinit var idText: TextView
    private lateinit var lockedGroup: LinearLayout
    private lateinit var unlockedGroup: LinearLayout
    private lateinit var pinInput: EditText
    private lateinit var unlockButton: Button
    private lateinit var relockButton: Button

    private var pollJob: Job? = null
    private var updateJob: Job? = null
    private var titleTapCount = 0
    private var locked = true

    companion object {
        private const val PREFS = "device_locker_prefs"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_PIN = "admin_pin"
        private const val KEY_LOCKED = "locked"
        private const val POLL_INTERVAL_MS = 10_000L
        private const val UPDATE_CHECK_INTERVAL_MS = 300_000L // 5 min
        private const val DEFAULT_PIN = "1234"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_DEVICE_ID)) {
            prefs.edit().putString(KEY_DEVICE_ID, UUID.randomUUID().toString().take(8)).apply()
        }
        if (!prefs.contains(KEY_PIN)) {
            prefs.edit().putString(KEY_PIN, DEFAULT_PIN).apply()
        }
        if (!prefs.contains(KEY_SERVER_URL)) {
            // Edit this default once, or change it later via the hidden admin menu
            // (tap the title 7 times, then enter the current PIN).
            prefs.edit().putString(KEY_SERVER_URL, "http://192.168.1.100:3000").apply()
        }
        locked = prefs.getBoolean(KEY_LOCKED, true)

        dpm = getSystemService(DevicePolicyManager::class.java)
        adminComponent = ComponentName(this, AdminReceiver::class.java)

        titleText = findViewById(R.id.titleText)
        statusText = findViewById(R.id.statusText)
        idText = findViewById(R.id.idText)
        lockedGroup = findViewById(R.id.lockedGroup)
        unlockedGroup = findViewById(R.id.unlockedGroup)
        pinInput = findViewById(R.id.pinInput)
        unlockButton = findViewById(R.id.unlockButton)
        relockButton = findViewById(R.id.relockButton)

        idText.text = "device: ${prefs.getString(KEY_DEVICE_ID, "")}"

        titleText.setOnClickListener {
            titleTapCount++
            if (titleTapCount >= 7) {
                titleTapCount = 0
                promptAdminSettings()
            }
        }

        unlockButton.setOnClickListener {
            val entered = pinInput.text.toString()
            if (entered == prefs.getString(KEY_PIN, DEFAULT_PIN)) {
                pinInput.text.clear()
                setLocked(false)
            } else {
                Toast.makeText(this, "Wrong PIN", Toast.LENGTH_SHORT).show()
            }
        }

        relockButton.setOnClickListener {
            setLocked(true)
        }

        if (isDeviceOwner()) {
            dpm.setLockTaskPackages(adminComponent, arrayOf(packageName))
        }

        applyLockState(animate = false)
        startPolling()
        if (isDeviceOwner()) startUpdateChecks()
    }

    override fun onDestroy() {
        super.onDestroy()
        pollJob?.cancel()
        updateJob?.cancel()
    }

    override fun onBackPressed() {
        if (locked) return // swallow back while locked
        super.onBackPressed()
    }

    private fun isDeviceOwner(): Boolean = dpm.isDeviceOwnerApp(packageName)

    private fun setLocked(value: Boolean) {
        locked = value
        prefs.edit().putBoolean(KEY_LOCKED, value).apply()
        applyLockState(animate = true)
    }

    private fun applyLockState(animate: Boolean) {
        if (locked) {
            titleText.text = "DEVICE LOCKED"
            lockedGroup.visibility = android.view.View.VISIBLE
            unlockedGroup.visibility = android.view.View.GONE
            if (isDeviceOwner()) {
                val lockTaskState = getSystemService(ActivityManager::class.java).lockTaskModeState
                if (lockTaskState == ActivityManager.LOCK_TASK_MODE_NONE) {
                    startLockTask()
                }
            }
        } else {
            titleText.text = "DEVICE UNLOCKED"
            lockedGroup.visibility = android.view.View.GONE
            unlockedGroup.visibility = android.view.View.VISIBLE
            if (isDeviceOwner()) {
                val lockTaskState = getSystemService(ActivityManager::class.java).lockTaskModeState
                if (lockTaskState != ActivityManager.LOCK_TASK_MODE_NONE) {
                    stopLockTask()
                }
            }
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (true) {
                pollServerOnce()
                kotlinx.coroutines.delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun pollServerOnce() {
        val base = prefs.getString(KEY_SERVER_URL, "") ?: ""
        val deviceId = prefs.getString(KEY_DEVICE_ID, "") ?: ""
        if (base.isBlank()) return
        val result = withContext(Dispatchers.IO) {
            try {
                val url = URL("$base/status/$deviceId")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.requestMethod = "GET"
                val code = conn.responseCode
                if (code == 200) {
                    val body = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                    val json = JSONObject(body)
                    json.getBoolean("locked")
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }
        if (result == null) {
            statusText.text = "Offline - use admin PIN"
        } else {
            statusText.text = "Synced with server"
            if (result != locked) {
                setLocked(result)
            }
        }
    }

    private fun startUpdateChecks() {
        updateJob?.cancel()
        updateJob = lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(UPDATE_CHECK_INTERVAL_MS)
                val base = prefs.getString(KEY_SERVER_URL, "") ?: ""
                if (base.isNotBlank()) {
                    UpdateManager.checkAndInstall(this@MainActivity, base)
                }
            }
        }
    }

    private fun promptAdminSettings() {
        val checkInput = EditText(this)
        checkInput.hint = "Current PIN"
        checkInput.inputType = android.text.InputType.TYPE_CLASS_NUMBER or
            android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD

        AlertDialog.Builder(this)
            .setTitle("Admin PIN required")
            .setView(checkInput)
            .setPositiveButton("OK") { _, _ ->
                if (checkInput.text.toString() == prefs.getString(KEY_PIN, DEFAULT_PIN)) {
                    showAdminSettingsDialog()
                } else {
                    Toast.makeText(this, "Wrong PIN", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAdminSettingsDialog() {
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(40, 20, 40, 20)

        val serverInput = EditText(this)
        serverInput.hint = "Server URL (e.g. http://192.168.1.100:3000)"
        serverInput.setText(prefs.getString(KEY_SERVER_URL, ""))
        layout.addView(serverInput)

        val pinInputNew = EditText(this)
        pinInputNew.hint = "New admin PIN"
        pinInputNew.inputType = android.text.InputType.TYPE_CLASS_NUMBER or
            android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        layout.addView(pinInputNew)

        AlertDialog.Builder(this)
            .setTitle("Admin settings  -  device: ${prefs.getString(KEY_DEVICE_ID, "")}")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val newServer = serverInput.text.toString().trim().trimEnd('/')
                if (newServer.isNotBlank()) {
                    prefs.edit().putString(KEY_SERVER_URL, newServer).apply()
                }
                val newPin = pinInputNew.text.toString().trim()
                if (newPin.isNotBlank()) {
                    prefs.edit().putString(KEY_PIN, newPin).apply()
                }
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
