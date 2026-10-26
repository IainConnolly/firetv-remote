package com.firetvremote

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

class RuleEditActivity : Activity() {

    private lateinit var ruleId: String
    private var learningFrom: Rule? = null
    private var learnMessage: String? = null

    // The screen follows the stored mapping itself rather than waiting to be told:
    // when the button's app never properly takes the front (some apps open through
    // an invisible trampoline), this screen is never paused and resumed.
    private val ruleListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ruleId = intent.getStringExtra(EXTRA_RULE_ID) ?: return finish()
        setContentView(R.layout.activity_rule_edit)
        RuleStore.prefs(this).registerOnSharedPreferenceChangeListener(ruleListener)

        findViewById<Button>(R.id.detect_button).setOnClickListener { startLearning() }
        findViewById<Button>(R.id.target_button).setOnClickListener {
            val intent = Intent(this, AppPickerActivity::class.java)
                .putExtra(AppPickerActivity.EXTRA_TITLE, "Which app should the button open instead?")
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_TARGET)
        }
        findViewById<Button>(R.id.test_button).setOnClickListener {
            val target = RuleStore.get(this, ruleId)?.targetPackage
            if (target == null || !Apps.launch(this, target)) {
                Toast.makeText(this, "Choose an app first.", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.delete_button).setOnClickListener {
            RuleStore.delete(this, ruleId)
            finish()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_RULE_ID)?.let { ruleId = it }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        RuleStore.prefs(this).unregisterOnSharedPreferenceChangeListener(ruleListener)
        if (learningFrom != null) {
            DiagLog.add("Edit screen closed while detecting; detection cancelled")
            RemapService.instance?.cancelLearning()
            RemapService.learnListener = null
            keepOldButtonIfNoneLearned()
        }
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        // Leaving a mapping that was never set up shouldn't leave an empty entry behind.
        // Done here, not in onDestroy, because the main screen draws its list before we are destroyed.
        if (isFinishing && learningFrom == null) {
            RuleStore.get(this, ruleId)
                ?.takeIf { !it.hasKey && it.triggerPackage == null && it.targetPackage == null }
                ?.let { RuleStore.delete(this, ruleId) }
        }
    }

    private fun startLearning() {
        val service = RemapService.instance
        if (service == null) {
            Toast.makeText(this, "Turn on the remap service first (see the main screen).", Toast.LENGTH_LONG).show()
            return
        }
        // Start from a clean slate so the new button fully replaces the old one.
        val before = RuleStore.get(this, ruleId)
        RuleStore.update(this, ruleId) { it.copy(keyCode = KeyEvent.KEYCODE_UNKNOWN, scanCode = 0, triggerPackage = null) }
        learningFrom = before
        learnMessage = "Press the button on your remote now (for example Netflix). Waiting 15 seconds…"
        RemapService.learnListener = { onLearningFinished() }
        service.startLearning(ruleId)
        refresh()
    }

    /** The service gave up or stopped; if the mapping hasn't gained a button, nothing was detected. */
    private fun onLearningFinished() {
        RemapService.learnListener = null
        if (learningFrom == null) return refresh()
        learnMessage = if (keepOldButtonIfNoneLearned()) {
            "No button detected. Choose Detect button and try again."
        } else {
            "Got it."
        }
        refresh()
    }

    /** Puts back the button the mapping had before Detect if nothing new was learned; true if so. */
    private fun keepOldButtonIfNoneLearned(): Boolean {
        val before = learningFrom ?: return false
        learningFrom = null
        val now = RuleStore.get(this, ruleId) ?: return false
        if (now.hasKey || now.triggerPackage != null) return false
        RuleStore.put(this, now.copy(keyCode = before.keyCode, scanCode = before.scanCode, triggerPackage = before.triggerPackage))
        return true
    }

    private fun refresh() {
        val rule = RuleStore.get(this, ruleId) ?: return finish()
        if (learningFrom != null && (rule.hasKey || rule.triggerPackage != null)) {
            learningFrom = null
            learnMessage = "Got it."
        }
        findViewById<TextView>(R.id.summary).text = rule.describe(this)

        val trigger = rule.triggerPackage?.let { Apps.label(this, it) } ?: "not set"
        var buttonInfo = "Key from remote: ${rule.keyDescription()}\nApp it normally opens: $trigger"
        if (!rule.hasKey && rule.triggerPackage == null) {
            buttonInfo += "\nChoose Detect button, then press the button on your remote."
        }
        findViewById<TextView>(R.id.button_info).text = buttonInfo

        findViewById<TextView>(R.id.target_info).text =
            rule.targetPackage?.let { "Opens: ${Apps.label(this, it)}" } ?: "No app chosen yet."

        findViewById<TextView>(R.id.learn_status).apply {
            text = learnMessage
            visibility = if (learnMessage == null) View.GONE else View.VISIBLE
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_TARGET || resultCode != RESULT_OK) return
        val pkg = data?.getStringExtra(AppPickerActivity.EXTRA_PACKAGE) ?: return
        if (pkg == RuleStore.get(this, ruleId)?.triggerPackage) {
            Toast.makeText(this, "That's the app this button already opens; choose a different one.", Toast.LENGTH_LONG).show()
            return
        }
        RuleStore.update(this, ruleId) { it.copy(targetPackage = pkg) }
        refresh()
    }

    companion object {
        const val EXTRA_RULE_ID = "rule_id"
        private const val REQUEST_TARGET = 1
    }
}
