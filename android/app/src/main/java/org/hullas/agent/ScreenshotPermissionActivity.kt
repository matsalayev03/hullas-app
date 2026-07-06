package org.hullas.agent

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

class ScreenshotPermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mgr.createScreenCaptureIntent(), REQ_CODE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CODE && data != null) {
            handleResult(this, resultCode, data)
        }
        finish()
    }

    companion object {
        private const val REQ_CODE = 1001

        fun createIntent(ctx: Context): Intent =
            Intent(ctx, ScreenshotPermissionActivity::class.java)

        fun handleResult(ctx: Context, resultCode: Int, data: Intent) {
            ProjectionHelper.savePermission(ctx, resultCode, data)
        }
    }
}