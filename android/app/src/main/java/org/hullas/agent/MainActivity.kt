package org.hullas.agent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private var uiJob: Job? = null

    private val perms = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        toast("Ruxsatlar yangilandi")
        refreshUi()
    }

    private val screenLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            ScreenshotPermissionActivity.handleResult(this, result.resultCode, result.data!!)
            toast("Ekran ruxsati saqlandi")
        }
        refreshUi()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        detailText = findViewById(R.id.detailText)

        findViewById<Button>(R.id.btnStart).setOnClickListener { startMonitoring() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { stopMonitoring() }
        findViewById<Button>(R.id.btnPerms).setOnClickListener { permLauncher.launch(perms) }
        findViewById<Button>(R.id.btnScreen).setOnClickListener {
            screenLauncher.launch(ScreenshotPermissionActivity.createIntent(this))
        }
        findViewById<Button>(R.id.btnBattery).setOnClickListener { openBatterySettings() }

        refreshUi()
        if (Prefs.isMonitoring(this)) {
            HullasService.start(this)
        }
    }

    override fun onResume() {
        super.onResume()
        AppLifecycle.mainActivity = this
        startUiRefresh()
    }

    override fun onPause() {
        if (AppLifecycle.mainActivity == this) AppLifecycle.mainActivity = null
        uiJob?.cancel()
        super.onPause()
    }

    private fun startMonitoring() {
        if (!hasBasicPerms()) {
            toast("Avval ruxsatlarni bering")
            permLauncher.launch(perms)
            return
        }
        Prefs.setMonitoring(this, true)
        HullasService.start(this)
        toast("Monitoring boshlandi")
        refreshUi()
    }

    private fun stopMonitoring() {
        Prefs.setMonitoring(this, false)
        stopService(Intent(this, HullasService::class.java))
        toast("To'xtatildi")
        refreshUi()
    }

    private fun openBatterySettings() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                })
            } else {
                toast("Batareya allaqachon cheklanmagan")
            }
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun hasBasicPerms(): Boolean =
        perms.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun startUiRefresh() {
        uiJob?.cancel()
        uiJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                refreshUi()
                delay(3000)
            }
        }
    }

    private fun refreshUi() {
        val running = Prefs.isMonitoring(this) && HullasService.isRunning
        statusText.text = if (running) "Holat: Ishlayapti" else "Holat: To'xtatilgan"

        val lines = buildList {
            add("Server: ${Prefs.serverUrl(this@MainActivity)}")
            add("Ruxsatlar: ${if (hasBasicPerms()) "OK" else "YO'Q"}")
            add("Ekran: ${if (ProjectionHelper.hasSavedPermission(this@MainActivity)) "OK" else "YO'Q"}")
            if (running) {
                add("Oxirgi heartbeat: ${Prefs.lastHeartbeat(this@MainActivity)}")
            }
            add("")
            add("Botdan: /device /screenshot /live_start /live_stop /cam_back /location")
        }
        detailText.text = lines.joinToString("\n")
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}