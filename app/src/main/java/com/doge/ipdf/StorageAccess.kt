package com.doge.ipdf

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * "All files access" is what lets iPDF write a PDF back to the folder it came from when the app
 * was started through ACTION_VIEW and therefore only received a read-only URI grant.
 *
 * Android 11+ exposes this as a special app access that has to be switched on in system settings;
 * Android 10 (the minimum we support) still uses the legacy write permission instead.
 */
object StorageAccess {
    /** True when iPDF may write documents directly on shared storage. */
    fun isGranted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        }

    /** Whether granting happens on a settings page (Android 11+) or a permission dialog. */
    val usesSettingsPage: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** The system page where the user can switch on "allow management of all files". */
    fun settingsIntent(context: Context): Intent {
        val specific = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
        return if (context.packageManager.resolveActivity(specific, 0) != null) {
            specific
        } else {
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        }
    }
}
