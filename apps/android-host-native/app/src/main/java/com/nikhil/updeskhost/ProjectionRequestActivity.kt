package com.nikhil.updeskhost

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * A transparent, no-UI activity whose only job is to obtain a MediaProjection
 * token — the one thing that legally requires an Activity + the system consent
 * dialog. [HostService] launches it from the background (allowed because we hold
 * "Display over other apps"); [InputAccessibilityService] auto-taps "Start now"
 * on the dialog, and we hand the resulting token back to the service and finish.
 * The user sees, at most, a brief flash of the dialog.
 */
class ProjectionRequestActivity : AppCompatActivity() {

    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        HostService.deliverProjection(
            this,
            result.resultCode == Activity.RESULT_OK,
            if (result.resultCode == Activity.RESULT_OK) result.data else null
        )
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Re-arm the auto-confirm right before showing the dialog (in case the
        // service's arm window elapsed while the activity was starting).
        InputAccessibilityService.armProjectionAutoConfirm()
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            launcher.launch(mpm.createScreenCaptureIntent())
        } catch (t: Throwable) {
            HostService.deliverProjection(this, false, null)
            finish()
        }
    }
}
