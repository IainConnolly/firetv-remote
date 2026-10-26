package com.firetvremote

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioManager
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

    // A trigger app opened with Select stays allowed until another app or Home
    // takes over, so returning to it from an overlay (screensaver, Alexa) is not
    // mistaken for the remote button opening it.
    private var allowedPackage: String? = null

    // The app this service last opened is left alone briefly, so a mapping whose
    // target is itself a trigger cannot bounce between apps.
    private var redirectedTo: String? = null
    private var redirectedFrom: String? = null
    private var redirectedAt = 0L

    private var learningRuleId: String? = null
    private var learnedKeyCode: Int? = null
    private var learnedApp = false
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
        if (learningRuleId != null) {
            cancelLearning()
            learnListener?.invoke()
        }
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
            // Only the first button is learned; others pass through untouched.
            learnedKeyCode?.let { return event.keyCode == it }
            if (down && event.repeatCount == 0) {
                learnedKeyCode = event.keyCode
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
            if (pkg != packageName && pkg != homePackage && Apps.launchIntent(this, pkg) != null) {
                learnedApp = true
                RuleStore.update(this, learning) { it.copy(triggerPackage = pkg) }
                DiagLog.add("Learned that the button opens $pkg")
                finishLearning()
            }
            return
        }

        if (pkg == packageName) return
        val now = SystemClock.uptimeMillis()
        if (pkg == redirectedTo && now - redirectedAt < REDIRECT_GRACE_MS) return
        if (pkg != allowedPackage && (pkg == homePackage || Apps.launchIntent(this, pkg) != null)) {
            allowedPackage = null
        }

        for (rule in rules) {
            if (rule.triggerPackage != pkg || rule.targetPackage == null) continue
            val buttonJustPressed = ruleKeyPressedAt[rule.id]?.let { now - it < BUTTON_FOLLOWUP_MS } == true
            when {
                buttonJustPressed -> redirect(rule, "button press, $pkg opened anyway", replaced = pkg)
                pkg == allowedPackage -> DiagLog.add("Not redirecting: back to $pkg, opened earlier with Select")
                now - lastSelectAt < SELECT_WINDOW_MS -> {
                    allowedPackage = pkg
                    DiagLog.add("Not redirecting: $pkg was opened with Select")
                }
                else -> redirect(rule, "$pkg opened without Select", replaced = pkg)
            }
            return
        }
    }

    /** [replaced] is the button's own app when the system opened it before we could step in. */
    private fun redirect(rule: Rule, reason: String, replaced: String? = null) {
        val target = rule.targetPackage ?: return
        val now = SystemClock.uptimeMillis()
        if (replaced != null && replaced == redirectedFrom && now - redirectedAt < REDIRECT_GRACE_MS) {
            // The app we just replaced popped up again (slow start, or reacting to the pause):
            // put the target back in front without another trip through Home.
            DiagLog.add("$replaced came back; bringing $target forward again")
            Apps.launch(this, target)
            return
        }
        DiagLog.add("Opening $target ($reason)")
        redirectedTo = target
        redirectedFrom = replaced
        redirectedAt = now
        // Go home first so Back from the target app doesn't land in the app we replaced.
        performGlobalAction(GLOBAL_ACTION_HOME)
        handler.postDelayed({
            if (!Apps.launch(this, target)) DiagLog.add("Could not open $target (is it installed?)")
        }, LAUNCH_DELAY_MS)
        replaced?.let { silence(it) }
    }

    /**
     * An app the button opened may carry on in the background, e.g. a music app that
     * starts playing. Apps can't force-stop each other, and Android won't close one that
     * is playing, so: pause whatever is playing while the target settles in, then close
     * the replaced app once it has nothing left running. Checked twice a second for a
     * while, because such apps start playing late and start again after a pause (Amazon
     * Music does both).
     */
    private fun silence(pkg: String) {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val activities = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        handler.removeCallbacksAndMessages(SILENCE_TOKEN)
        val start = SystemClock.uptimeMillis()
        for (delay in SILENCE_EVERY_MS..SILENCE_FOR_MS step SILENCE_EVERY_MS) {
            handler.postAtTime({
                if (foregroundPackage == pkg) return@postAtTime // the viewer went back to it
                if (audio.isMusicActive) {
                    audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
                    audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
                    DiagLog.add("Paused playback left behind by $pkg")
                }
                if (delay == SILENCE_FOR_MS) activities.killBackgroundProcesses(pkg)
            }, SILENCE_TOKEN, start + delay)
        }
    }

    private fun isOverlayPackage(pkg: String): Boolean {
        if (pkg == "android" || pkg == "com.android.systemui") return true
        val ime = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        return ime != null && ime.startsWith("$pkg/")
    }

    fun startLearning(ruleId: String) {
        learningRuleId = ruleId
        learnedKeyCode = null
        learnedApp = false
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
        if (learnedApp) {
            // The button opened another app; bring the settings screen back.
            startActivity(
                Intent(this, RuleEditActivity::class.java)
                    .putExtra(RuleEditActivity.EXTRA_RULE_ID, ruleId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        }
        DiagLog.add("Stopped waiting for a button")
        learnListener?.invoke()
    }

    companion object {
        private const val LEARN_TIMEOUT_MS = 15_000L
        private const val LEARN_SETTLE_MS = 2_000L
        private const val BUTTON_FOLLOWUP_MS = 4_000L
        private const val SELECT_WINDOW_MS = 3_000L
        private const val REDIRECT_GRACE_MS = 5_000L
        private const val LAUNCH_DELAY_MS = 300L
        private const val SILENCE_EVERY_MS = 500L
        private const val SILENCE_FOR_MS = 10_000L
        private val SILENCE_TOKEN = Any()

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

        /** On in both places Fire OS keeps it: the list of enabled services and the master switch. */
        fun isEnabled(context: Context): Boolean = isListed(context) &&
            Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1

        private fun isListed(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val me = componentName(context)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
