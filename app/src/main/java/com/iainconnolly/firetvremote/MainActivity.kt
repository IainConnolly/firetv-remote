package com.iainconnolly.firetvremote

import android.app.Activity
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

class MainActivity : Activity() {

    private var rules: List<Rule> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

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
    }

    private fun refresh() {
        ServiceKeeper.ensureEnabled(this)
        findViewById<TextView>(R.id.status).text = statusText() + "\n\n" + keepOnText()
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
                "✘ The remap service is off. Open Accessibility settings and turn on \"${getString(R.string.service_label)}\".\n" +
                    "If it isn't listed there, run this from a computer with ADB:\n" +
                    "adb shell settings put secure enabled_accessibility_services " +
                    RemapService.componentName(this).flattenToShortString() + "\n" +
                    "adb shell settings put secure accessibility_enabled 1"
        }
    }

    private fun keepOnText(): String =
        if (ServiceKeeper.canSelfEnable(this)) {
            "✔ Keep-on is set up: the service switches itself back on after a restart."
        } else {
            "Keep-on isn't set up. If the service turns itself off after the Fire TV restarts, run this once from a computer with ADB:\n" +
                ServiceKeeper.grantCommand(this)
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
}
