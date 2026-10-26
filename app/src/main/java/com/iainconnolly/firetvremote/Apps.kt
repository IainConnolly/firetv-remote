package com.iainconnolly.firetvremote

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

data class AppEntry(val packageName: String, val label: String, val icon: Drawable?)

object Apps {
    fun isInstalled(context: Context, packageName: String): Boolean = try {
        context.packageManager.getApplicationInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun label(context: Context, packageName: String): String {
        val pm = context.packageManager
        return try {
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    fun launchIntent(context: Context, packageName: String): Intent? {
        val pm = context.packageManager
        return pm.getLeanbackLaunchIntentForPackage(packageName)
            ?: pm.getLaunchIntentForPackage(packageName)
    }

    fun launch(context: Context, packageName: String): Boolean {
        val intent = launchIntent(context, packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun homePackage(context: Context): String? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return context.packageManager
            .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
    }

    /** Every app that can be opened from a launcher, TV apps included. */
    fun launchable(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val packages = linkedSetOf<String>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            pm.queryIntentActivities(query, 0).forEach { packages.add(it.activityInfo.packageName) }
        }
        packages.remove(context.packageName)
        return packages
            .map { pkg ->
                val icon = try { pm.getApplicationIcon(pkg) } catch (e: Exception) { null }
                AppEntry(pkg, label(context, pkg), icon)
            }
            .sortedBy { it.label.lowercase() }
    }
}
