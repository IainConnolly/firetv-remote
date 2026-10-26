package com.iainconnolly.firetvremote

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/** Switches the service back on when Fire OS has turned it off, given WRITE_SECURE_SETTINGS (granted once over ADB). */
object ServiceKeeper {
    private const val PERMISSION = "android.permission.WRITE_SECURE_SETTINGS"

    fun canSelfEnable(context: Context): Boolean =
        context.checkCallingOrSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun grantCommand(context: Context) = "adb shell pm grant ${context.packageName} $PERMISSION"

    fun ensureEnabled(context: Context) {
        if (RemapService.isEnabled(context) || !canSelfEnable(context)) return
        if (writeEnabled(context, true)) DiagLog.add("Switched the service back on")
    }

    /**
     * The service can be switched on in settings yet not running, e.g. after the
     * app was force-stopped. Switching it off and on again makes the system bind it.
     */
    fun restartIfStuck(context: Context) {
        if (!RemapService.isEnabled(context) || RemapService.instance != null || !canSelfEnable(context)) return
        if (!writeEnabled(context, false)) return
        Handler(Looper.getMainLooper()).postDelayed({
            if (writeEnabled(context, true)) DiagLog.add("Restarted the service, which was on but not running")
        }, RESTART_GAP_MS)
    }

    private fun writeEnabled(context: Context, on: Boolean): Boolean = try {
        val resolver = context.contentResolver
        val me = RemapService.componentName(context)
        val others = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').filter { it.isNotBlank() && ComponentName.unflattenFromString(it) != me }
        val services = if (on) others + me.flattenToString() else others
        Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, services.joinToString(":"))
        if (on) Settings.Secure.putString(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, "1")
        true
    } catch (e: SecurityException) {
        DiagLog.add("Could not change the service setting: ${e.message}")
        false
    }

    private const val RESTART_GAP_MS = 1_000L
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> ServiceKeeper.ensureEnabled(context)
        }
    }
}
