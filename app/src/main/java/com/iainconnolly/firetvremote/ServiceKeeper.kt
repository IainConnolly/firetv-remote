package com.iainconnolly.firetvremote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings

/** Switches the service back on when Fire OS has turned it off, given WRITE_SECURE_SETTINGS (granted once over ADB). */
object ServiceKeeper {
    private const val PERMISSION = "android.permission.WRITE_SECURE_SETTINGS"

    fun canSelfEnable(context: Context): Boolean =
        context.checkCallingOrSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun grantCommand(context: Context) = "adb shell pm grant ${context.packageName} $PERMISSION"

    fun ensureEnabled(context: Context) {
        if (RemapService.isEnabled(context) || !canSelfEnable(context)) return
        try {
            val resolver = context.contentResolver
            val current = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            val services = current.orEmpty().split(':').filter { it.isNotBlank() } +
                RemapService.componentName(context).flattenToString()
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, services.joinToString(":"))
            Settings.Secure.putString(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, "1")
            DiagLog.add("Switched the service back on")
        } catch (e: SecurityException) {
            DiagLog.add("Could not switch the service back on: ${e.message}")
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> ServiceKeeper.ensureEnabled(context)
        }
    }
}
