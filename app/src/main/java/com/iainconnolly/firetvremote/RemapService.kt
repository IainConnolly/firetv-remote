package com.iainconnolly.firetvremote

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Catches remote buttons and opens the chosen app instead, by the key the button
 * sends or, when the system opens the button's app itself, by that app coming to
 * the front without Select having just been pressed (see [Rule]).
 */
class RemapService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var rules: List<Rule> = emptyList()
    private var homePackage: String? = null
    private var foregroundPackage: String? = null
    private var lastSelectAt = 0L
    private val ruleKeyPressedAt = HashMap<String, Long>()

    private var learningRuleId: String? = null
    private val finishLearningRunnable = Runnable { finishLearning() }

    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> reloadRules() }

    override fun onServiceConnected() {
        instance = this
        homePackage = Apps.homePackage(this)
        RuleStore.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener)
        reloadRules()
        DiagLog.add("Service connected (home app: $homePackage)")
    }

    override fun onDestroy() {
        RuleStore.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        handler.removeCallbacksAndMessages(null)
        if (instance === this) instance = null
        DiagLog.add("Service stopped")
        super.onDestroy()
    }

    override fun onInterrupt() {}

    private fun reloadRules() {
        rules = RuleStore.load(this)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        if (down && event.repeatCount == 0) {
            DiagLog.add("Key ${KeyEvent.keyCodeToString(event.keyCode)} (code ${event.keyCode}, scan ${event.scanCode})")
        }

        learningRuleId?.let { ruleId ->
            if (event.keyCode in NAVIGATION_KEYS) return false
            if (down && event.repeatCount == 0) {
                RuleStore.update(this, ruleId) { it.copy(keyCode = event.keyCode, scanCode = event.scanCode) }
                DiagLog.add("Learned key ${KeyEvent.keyCodeToString(event.keyCode)}")
                // Keep listening briefly in case the system also opens an app for this button.
                handler.removeCallbacks(finishLearningRunnable)
                handler.postDelayed(finishLearningRunnable, LEARN_SETTLE_MS)
            }
            return true
        }

        if (down && event.keyCode in SELECT_KEYS) lastSelectAt = SystemClock.uptimeMillis()

        val rule = rules.firstOrNull { it.targetPackage != null && it.matches(event) } ?: return false
        if (down && event.repeatCount == 0) {
            ruleKeyPressedAt[rule.id] = SystemClock.uptimeMillis()
            redirect(rule, "button press")
        }
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == foregroundPackage || isOverlayPackage(pkg)) return
        foregroundPackage = pkg
        DiagLog.add("App in front: $pkg")

        val learning = learningRuleId
        if (learning != null) {
            if (pkg != packageName && pkg != homePackage) {
                RuleStore.update(this, learning) { it.copy(triggerPackage = pkg) }
                DiagLog.add("Learned that the button opens $pkg")
                finishLearning()
            }
            return
        }

        if (pkg == packageName) return
        val now = SystemClock.uptimeMillis()
        for (rule in rules) {
            if (rule.triggerPackage != pkg || rule.targetPackage == null) continue
            val buttonJustPressed = ruleKeyPressedAt[rule.id]?.let { now - it < BUTTON_FOLLOWUP_MS } == true
            when {
                buttonJustPressed -> redirect(rule, "button press, $pkg opened anyway")
                now - lastSelectAt < SELECT_WINDOW_MS ->
                    DiagLog.add("Not redirecting: $pkg was opened with Select")
                else -> redirect(rule, "$pkg opened without Select")
            }
            return
        }
    }

    private fun redirect(rule: Rule, reason: String) {
        val target = rule.targetPackage ?: return
        DiagLog.add("Opening $target ($reason)")
        // Go home first so Back from the target app doesn't land in the app we replaced.
        performGlobalAction(GLOBAL_ACTION_HOME)
        handler.postDelayed({
            if (!Apps.launch(this, target)) DiagLog.add("Could not open $target (is it installed?)")
        }, LAUNCH_DELAY_MS)
    }

    private fun isOverlayPackage(pkg: String): Boolean {
        if (pkg == "android" || pkg == "com.android.systemui") return true
        val ime = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        return ime != null && ime.startsWith("$pkg/")
    }

    fun startLearning(ruleId: String) {
        learningRuleId = ruleId
        handler.removeCallbacks(finishLearningRunnable)
        handler.postDelayed(finishLearningRunnable, LEARN_TIMEOUT_MS)
        DiagLog.add("Waiting for a button press…")
    }

    fun cancelLearning() {
        learningRuleId = null
        handler.removeCallbacks(finishLearningRunnable)
    }

    private fun finishLearning() {
        val ruleId = learningRuleId ?: return
        cancelLearning()
        if (foregroundPackage != packageName) {
            // The button opened another app; bring the settings screen back.
            startActivity(
                Intent(this, RuleEditActivity::class.java)
                    .putExtra(RuleEditActivity.EXTRA_RULE_ID, ruleId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        }
        learnListener?.invoke()
    }

    companion object {
        private const val LEARN_TIMEOUT_MS = 15_000L
        private const val LEARN_SETTLE_MS = 2_000L
        private const val BUTTON_FOLLOWUP_MS = 4_000L
        private const val SELECT_WINDOW_MS = 3_000L
        private const val LAUNCH_DELAY_MS = 300L

        private val SELECT_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_SELECT,
        )

        private val NAVIGATION_KEYS = SELECT_KEYS + setOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_HOME,
        )

        @Volatile
        var instance: RemapService? = null
            private set

        /** Called on the main thread when a learning session ends. */
        var learnListener: (() -> Unit)? = null

        fun componentName(context: Context) = ComponentName(context, RemapService::class.java)

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val me = componentName(context)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
