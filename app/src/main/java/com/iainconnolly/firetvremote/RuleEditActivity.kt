package com.iainconnolly.firetvremote

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

class RuleEditActivity : Activity() {

    private lateinit var ruleId: String
    private var learningFrom: Rule? = null
    private var learnMessage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ruleId = intent.getStringExtra(EXTRA_RULE_ID) ?: return finish()
        setContentView(R.layout.activity_rule_edit)

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
        if (learningFrom != null) {
            RemapService.instance?.cancelLearning()
            RemapService.learnListener = null
        }
        super.onDestroy()
    }

    private fun startLearning() {
        val service = RemapService.instance
        if (service == null) {
            Toast.makeText(this, "Turn on the remap service first (see the main screen).", Toast.LENGTH_LONG).show()
            return
        }
        learningFrom = RuleStore.get(this, ruleId)
        learnMessage = "Press the button on your remote now (for example Netflix). Waiting 15 seconds…"
        RemapService.learnListener = { onLearningFinished() }
        service.startLearning(ruleId)
        refresh()
    }

    private fun onLearningFinished() {
        val before = learningFrom
        learningFrom = null
        RemapService.learnListener = null
        val after = RuleStore.get(this, ruleId)
        learnMessage = if (after == before) {
            "No button detected. Choose Detect button and try again."
        } else {
            "Got it."
        }
        refresh()
    }

    private fun refresh() {
        val rule = RuleStore.get(this, ruleId) ?: return finish()
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
        RuleStore.update(this, ruleId) { it.copy(targetPackage = pkg) }
        refresh()
    }

    companion object {
        const val EXTRA_RULE_ID = "rule_id"
        private const val REQUEST_TARGET = 1
    }
}
