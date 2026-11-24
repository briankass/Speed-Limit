package com.speedalert.overlay

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.location.Location
import android.location.LocationManager
import android.provider.Settings
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.concurrent.Executors
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
    private val testExecutor = Executors.newSingleThreadExecutor()
    private var showingSource = Prefs.Source.OSM

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

        setupDataSource()
        findViewById<MaterialButton>(R.id.fixHereButton).setOnClickListener { showFixDialog() }
        refreshCorrections()

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

    // ---------- Speed limit data source ----------

    private fun setupDataSource() {
        val group = findViewById<MaterialButtonToggleGroup>(R.id.sourceGroup)
        val ids = mapOf(
            Prefs.Source.OSM to R.id.srcOsm,
            Prefs.Source.HERE to R.id.srcHere,
            Prefs.Source.TOMTOM to R.id.srcTomTom,
        )
        val keyInput = findViewById<TextInputEditText>(R.id.keyInput)
        val keyResult = findViewById<TextView>(R.id.keyResult)

        group.check(ids.getValue(prefs.source))
        showSource(prefs.source)
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val src = ids.entries.first { it.value == checkedId }.key
            prefs.source = src
            showSource(src)
            if (OverlayService.isRunning) {
                Toast.makeText(this, "Restart the overlay to switch data source", Toast.LENGTH_SHORT).show()
            }
        }

        keyInput.doAfterTextChanged { text ->
            when (showingSource) {
                Prefs.Source.HERE -> prefs.hereKey = text?.toString() ?: ""
                Prefs.Source.TOMTOM -> prefs.tomtomKey = text?.toString() ?: ""
                Prefs.Source.OSM -> {}
            }
            keyResult.text = ""
        }

        findViewById<MaterialSwitch>(R.id.fallbackSwitch).apply {
            isChecked = prefs.osmFallback
            setOnCheckedChangeListener { _, checked -> prefs.osmFallback = checked }
        }

        findViewById<MaterialButton>(R.id.testKeyButton).setOnClickListener { testKey() }
    }

    private fun showSource(src: Prefs.Source) {
        showingSource = Prefs.Source.OSM // don't save while swapping the text below
        val section = findViewById<View>(R.id.keySection)
        val keyLayout = findViewById<TextInputLayout>(R.id.keyLayout)
        val keyInput = findViewById<TextInputEditText>(R.id.keyInput)
        val help = findViewById<TextView>(R.id.keyHelp)
        findViewById<TextView>(R.id.keyResult).text = ""
        when (src) {
            Prefs.Source.OSM -> section.visibility = View.GONE
            Prefs.Source.HERE -> {
                section.visibility = View.VISIBLE
                keyLayout.hint = "HERE API key"
                keyInput.setText(prefs.hereKey)
                help.text = "Free key: sign up at https://platform.here.com, create an app, " +
                    "then create an API key. Paste it above. Uses the HERE Routing API."
            }
            Prefs.Source.TOMTOM -> {
                section.visibility = View.VISIBLE
                keyLayout.hint = "TomTom API key"
                keyInput.setText(prefs.tomtomKey)
                help.text = "Free key: sign up at https://developer.tomtom.com and copy the " +
                    "API key from your dashboard. Paste it above. Free plan: 2,500 lookups/day."
            }
        }
        showingSource = src
    }

    @Suppress("MissingPermission")
    private fun lastKnownLocation(): Location? {
        if (!hasLocationPermission()) return null
        val lm = getSystemService(LocationManager::class.java) ?: return null
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    /** Looks up the limit near you (or a test road in New York) to check the key works. */
    private fun testKey() {
        val src = prefs.source
        val key = prefs.activeKey()
        val result = findViewById<TextView>(R.id.keyResult)
        if (key == null) {
            result.text = "Paste a key first."
            return
        }
        val loc = lastKnownLocation()
        val lat = loc?.latitude ?: 40.7614
        val lon = loc?.longitude ?: -73.9776
        val where = if (loc != null) "near you" else "at the test road"
        result.text = "Testing\u2026"
        testExecutor.execute {
            val r = runCatching {
                val res = if (src == Prefs.Source.HERE) {
                    // A short hop ~150 m north-east so HERE has a route to measure
                    OnlineLimits.here(key, lat, lon, lat + 0.001, lon + 0.001)
                } else {
                    OnlineLimits.tomtom(key, lat, lon, null)
                }
                res.limit
            }
            handler.post {
                if (isFinishing || isDestroyed) return@post
                result.text = r.fold(
                    onSuccess = { mph ->
                        if (mph != null) "\u2705 Key works. Limit $where: $mph mph"
                        else "\u2705 Key works (no limit found $where)"
                    },
                    onFailure = { "\u274C ${it.message ?: "Couldn't connect"}" }
                )
            }
        }
    }

    // ---------- Personal corrections ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun showFixDialog() {
        val loc = OverlayService.liveLocation ?: lastKnownLocation()
        if (loc == null) {
            Toast.makeText(this, "Start the overlay first so the app knows where you are.", Toast.LENGTH_LONG).show()
            return
        }
        val liveRoad = if (OverlayService.isRunning) OverlayService.liveRoad else null
        val liveLimit = if (OverlayService.isRunning) OverlayService.liveLimit else null
        val liveLabel = if (OverlayService.isRunning) OverlayService.liveLabel else null

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
        }
        box.addView(TextView(this).apply {
            text = if (liveLimit != null) "Currently showing $liveLimit mph ($liveLabel)." else
                "Road name (check it matches what the app detected):"
            textSize = 13f
        })
        val roadInput = EditText(this).apply {
            hint = "Street name, e.g. Oak St"
            setText(liveRoad ?: "")
            isSingleLine = true
        }
        box.addView(roadInput)

        box.addView(TextView(this).apply {
            text = "Correct speed limit (mph):"
            textSize = 13f
            setPadding(0, dp(10), 0, 0)
        })
        val values = (5..85 step 5).toList()
        val picker = NumberPicker(this).apply {
            minValue = 0
            maxValue = values.size - 1
            displayedValues = values.map { it.toString() }.toTypedArray()
            wrapSelectorWheel = false
            value = values.indexOf(25)
        }
        box.addView(picker)

        val radii = listOf(300 to "Just this stretch (~300 m / 1,000 ft)", 1000 to "Nearby (~1 km / 0.6 mi)", 5000 to "This whole street around here (~5 km / 3 mi)")
        val group = RadioGroup(this)
        radii.forEachIndexed { i, (_, label) ->
            group.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = label
                textSize = 13f
                if (i == 1) isChecked = true
            })
        }
        box.addView(group)

        AlertDialog.Builder(this)
            .setTitle("Fix the speed limit here")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val road = roadInput.text.toString().trim()
                if (road.isEmpty()) {
                    Toast.makeText(this, "Enter the street name.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val idx = (0 until group.childCount).firstOrNull {
                    (group.getChildAt(it) as RadioButton).isChecked
                } ?: 1
                Corrections(this).add(
                    Correction(road, loc.latitude, loc.longitude, radii[idx].first, values[picker.value])
                )
                refreshCorrections()
                Toast.makeText(this, "Saved: $road is ${values[picker.value]} mph here", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshCorrections() {
        val container = findViewById<LinearLayout>(R.id.correctionsList)
        container.removeAllViews()
        val store = Corrections(this)
        for (c in store.load()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, 0)
            }
            val area = when {
                c.radiusM <= 300 -> "this stretch"
                c.radiusM <= 1000 -> "nearby"
                else -> "whole street"
            }
            row.addView(TextView(this).apply {
                text = "${c.road}: ${c.mph} mph ($area)"
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(this).apply {
                text = "Delete"
                textSize = 14f
                setTextColor(android.graphics.Color.parseColor("#D32F2F"))
                setPadding(dp(12), dp(8), dp(4), dp(8))
                setOnClickListener {
                    store.remove(c)
                    refreshCorrections()
                }
            })
            container.addView(row)
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
        testExecutor.shutdownNow()
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
