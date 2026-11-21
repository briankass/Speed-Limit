package com.speedalert.overlay

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch

/** Settings screen + Start/Stop button. */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var statusText: TextView
    private lateinit var startStopButton: MaterialButton
    private var testAlerts: AlertManager? = null
    private var waitingForOverlayPermission = false
    private val handler = Handler(Looper.getMainLooper())

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasLocationPermission()) {
            tryStart()
        } else {
            Toast.makeText(this, "Location permission is needed to read your speed.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        statusText = findViewById(R.id.statusText)
        startStopButton = findViewById(R.id.startStopButton)
        startStopButton.setOnClickListener {
            if (OverlayService.isRunning) stopOverlay() else tryStart()
        }

        // Tolerance: +0 / +5 / +10 / +15
        val group = findViewById<MaterialButtonToggleGroup>(R.id.toleranceGroup)
        val ids = mapOf(0 to R.id.tol0, 5 to R.id.tol5, 10 to R.id.tol10, 15 to R.id.tol15)
        group.check(ids[prefs.tolerance] ?: R.id.tol10)
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                ids.entries.firstOrNull { it.value == checkedId }?.let { prefs.tolerance = it.key }
            }
        }

        bindCheck(R.id.changeBeep, prefs.changeBeep) { prefs.changeBeep = it }
        bindCheck(R.id.changeVoice, prefs.changeVoice) { prefs.changeVoice = it }
        bindCheck(R.id.changeVibrate, prefs.changeVibrate) { prefs.changeVibrate = it }
        bindCheck(R.id.speedBeep, prefs.speedBeep) { prefs.speedBeep = it }
        bindCheck(R.id.speedVoice, prefs.speedVoice) { prefs.speedVoice = it }
        bindCheck(R.id.speedVibrate, prefs.speedVibrate) { prefs.speedVibrate = it }

        findViewById<MaterialSwitch>(R.id.repeatSwitch).apply {
            isChecked = prefs.repeatWarning
            setOnCheckedChangeListener { _, checked -> prefs.repeatWarning = checked }
        }

        testAlerts = AlertManager(this)
        findViewById<MaterialButton>(R.id.testButton).setOnClickListener {
            val a = testAlerts ?: return@setOnClickListener
            a.limitChanged(35, 45, prefs.changeBeep, prefs.changeVoice, prefs.changeVibrate)
            handler.postDelayed({
                a.speeding(35, prefs.tolerance + 2, prefs.speedBeep, prefs.speedVoice, prefs.speedVibrate)
            }, 3_000)
        }
    }

    private fun bindCheck(id: Int, value: Boolean, save: (Boolean) -> Unit) {
        findViewById<CheckBox>(id).apply {
            isChecked = value
            setOnCheckedChangeListener { _, checked -> save(checked) }
        }
    }

    override fun onResume() {
        super.onResume()
        if (waitingForOverlayPermission) {
            waitingForOverlayPermission = false
            if (Settings.canDrawOverlays(this)) tryStart()
        }
        handler.postDelayed({ refreshStatus() }, 300)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        testAlerts?.shutdown()
        testAlerts = null
        super.onDestroy()
    }

    private fun refreshStatus() {
        if (OverlayService.isRunning) {
            statusText.text = "Running — open Google Maps and drive safely"
            startStopButton.text = "Stop overlay"
        } else {
            statusText.text = "Stopped"
            startStopButton.text = "Start overlay"
        }
    }

    private fun hasLocationPermission() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    /** Walks through each permission, then starts the service. */
    private fun tryStart() {
        if (!hasLocationPermission()) {
            val perms = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
            if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
            permissionLauncher.launch(perms.toTypedArray())
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("Allow display over other apps")
                .setMessage("To show the speed bubble on top of Google Maps, turn on \"Allow display over other apps\" for Speed Alert on the next screen, then come back.")
                .setPositiveButton("Open settings") { _, _ ->
                    waitingForOverlayPermission = true
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    )
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java))
        handler.postDelayed({ refreshStatus() }, 500)
    }

    private fun stopOverlay() {
        stopService(Intent(this, OverlayService::class.java))
        handler.postDelayed({ refreshStatus() }, 300)
    }
}
