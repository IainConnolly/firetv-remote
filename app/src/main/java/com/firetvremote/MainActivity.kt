package com.firetvremote

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : Activity() {

    private var rules: List<Rule> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.setup_button).setOnClickListener { confirmAutomaticSetup() }
        findViewById<Button>(R.id.enable_button).setOnClickListener { openAccessibilitySettings() }
        findViewById<Button>(R.id.add_button).setOnClickListener {
            // Starts blank: the viewer detects the button and chooses the app.
            val rule = Rule()
            RuleStore.put(this, rule)
            openRule(rule.id)
        }
        findViewById<Button>(R.id.diagnostics_button).setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }
        findViewById<ListView>(R.id.rules).setOnItemClickListener { _, _, position, _ ->
            openRule(rules[position].id)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        // A service that is on but still not running after a moment is stuck; restart it and look again.
        if (RemapService.isEnabled(this) && RemapService.instance == null) {
            val status = findViewById<View>(R.id.status)
            status.postDelayed({ ServiceKeeper.restartIfStuck(this) }, STUCK_AFTER_MS)
            status.postDelayed({ if (!isFinishing) refresh() }, STUCK_AFTER_MS + RECHECK_AFTER_MS)
        }
    }

    private fun refresh() {
        ServiceKeeper.ensureEnabled(this)
        findViewById<TextView>(R.id.status).text = statusText() + "\n\n" + keepOnText()
        findViewById<Button>(R.id.setup_button).visibility =
            if (ServiceKeeper.canSelfEnable(this)) View.GONE else View.VISIBLE
        rules = RuleStore.load(this)
        findViewById<ListView>(R.id.rules).adapter =
            ArrayAdapter(this, android.R.layout.simple_list_item_1, rules.map { it.describe(this) })
        findViewById<TextView>(R.id.empty).visibility = if (rules.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun statusText(): String {
        val enabled = RemapService.isEnabled(this)
        return when {
            enabled && RemapService.instance != null ->
                "✔ The remap service is on."
            enabled ->
                "The remap service is switched on and starting. If this doesn't change, turn it off and on again in Accessibility settings."
            else ->
                "✘ The remap service is off. Choose Set up automatically, or turn on \"${getString(R.string.service_label)}\" in Accessibility settings if it's listed there."
        }
    }

    private fun keepOnText(): String =
        if (ServiceKeeper.canSelfEnable(this)) {
            "✔ Set up: the service switches itself back on after a restart. You can turn ADB debugging off again."
        } else {
            "Set up automatically lets the app switch its service on, and back on after every restart."
        }

    private fun confirmAutomaticSetup() {
        AlertDialog.Builder(this)
            .setTitle("Set up automatically")
            .setMessage(
                "1. Go to Settings → My Fire TV → Developer options and turn on ADB debugging.\n" +
                    "   (No Developer options? Settings → My Fire TV → About, then press Select on the device name 7 times.)\n\n" +
                    "2. Come back here and choose Continue.\n\n" +
                    "3. When \"Allow USB debugging?\" appears, tick \"Always allow from this computer\" and choose OK."
            )
            .setPositiveButton("Continue") { _, _ -> runAutomaticSetup() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runAutomaticSetup() {
        val progress = AlertDialog.Builder(this)
            .setTitle("Setting up…")
            .setMessage("If \"Allow USB debugging?\" appears, tick \"Always allow from this computer\" and choose OK.")
            .setCancelable(false)
            .show()
        Thread {
            val result = AdbSetup.run(applicationContext)
            runOnUiThread {
                progress.dismiss()
                if (isFinishing) return@runOnUiThread
                val message = when (result) {
                    AdbSetup.Result.Success ->
                        "All done. The remap service is on and will switch itself back on after a restart.\n\n" +
                            "You can turn ADB debugging off again now."
                    AdbSetup.Result.AdbOff ->
                        "Couldn't connect. Check that ADB debugging is on in Settings → My Fire TV → Developer options, then try again."
                    is AdbSetup.Result.Failed ->
                        "Setup didn't finish: ${result.message}\n\n" +
                            "If you chose Deny on the prompt, try again and choose OK."
                }
                val dialog = AlertDialog.Builder(this).setMessage(message).setPositiveButton("OK", null)
                if (result != AdbSetup.Result.Success) {
                    dialog.setNeutralButton("Other ways") { _, _ -> showOtherWays() }
                }
                dialog.show()
                refresh()
            }
        }.start()
    }

    /** Setup routes for devices where the automatic one doesn't work. */
    private fun showOtherWays() {
        val address = deviceAddress() ?: "<this device's IP address>"
        val grant = ServiceKeeper.grantCommand(this)
        AlertDialog.Builder(this)
            .setTitle("Other ways to set up")
            .setMessage(
                "A. From a computer, or an Android phone with an ADB app (for example Remote ADB Shell), " +
                    "on the same Wi-Fi, with ADB debugging still on:\n" +
                    "   adb connect $address:5555\n" +
                    "   $grant\n" +
                    "Then open Remote Remap again.\n\n" +
                    "B. If \"${getString(R.string.service_label)}\" is listed in Accessibility settings, turn it on there. " +
                    "Without option A, some devices switch it off again after a restart."
            )
            .setPositiveButton("OK", null)
            .setNeutralButton("Accessibility settings") { _, _ -> openAccessibilitySettings() }
            .show()
    }

    private fun deviceAddress(): String? = try {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    } catch (e: Exception) {
        null
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "This device has no Accessibility settings screen; use the ADB command.", Toast.LENGTH_LONG).show()
        }
    }

    private fun openRule(id: String) {
        startActivity(Intent(this, RuleEditActivity::class.java).putExtra(RuleEditActivity.EXTRA_RULE_ID, id))
    }

    companion object {
        private const val STUCK_AFTER_MS = 2_000L
        private const val RECHECK_AFTER_MS = 2_500L
    }
}
