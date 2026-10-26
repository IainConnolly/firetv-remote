package com.firetvremote

import android.content.Context
import android.content.SharedPreferences
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * One remote button mapping.
 *
 * A button is recognised in up to two ways, because Fire TV remotes differ:
 *  - [keyCode]/[scanCode]: the key event the button sends, when it reaches us.
 *  - [triggerPackage]: the app the button normally opens, for remotes where the
 *    system launches the app directly and we only see that app appear.
 */
data class Rule(
    val id: String = UUID.randomUUID().toString(),
    val keyCode: Int = KeyEvent.KEYCODE_UNKNOWN,
    val scanCode: Int = 0,
    val triggerPackage: String? = null,
    val targetPackage: String? = null,
) {
    val hasKey: Boolean
        get() = keyCode != KeyEvent.KEYCODE_UNKNOWN || scanCode != 0

    fun matches(event: KeyEvent): Boolean = when {
        keyCode != KeyEvent.KEYCODE_UNKNOWN -> event.keyCode == keyCode
        scanCode != 0 -> event.keyCode == KeyEvent.KEYCODE_UNKNOWN && event.scanCode == scanCode
        else -> false
    }

    fun keyDescription(): String = when {
        keyCode != KeyEvent.KEYCODE_UNKNOWN -> KeyEvent.keyCodeToString(keyCode)
        scanCode != 0 -> "Unnamed key (scan code $scanCode)"
        else -> "Not detected"
    }

    fun describe(context: Context): String {
        val button = when {
            triggerPackage != null -> "${Apps.label(context, triggerPackage)} button"
            hasKey -> keyDescription()
            else -> "New mapping"
        }
        val target = targetPackage?.let { Apps.label(context, it) } ?: "(no app chosen)"
        return "$button  →  $target"
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("keyCode", keyCode)
        .put("scanCode", scanCode)
        .put("trigger", triggerPackage ?: JSONObject.NULL)
        .put("target", targetPackage ?: JSONObject.NULL)

    companion object {
        fun fromJson(json: JSONObject) = Rule(
            id = json.getString("id"),
            keyCode = json.optInt("keyCode", KeyEvent.KEYCODE_UNKNOWN),
            scanCode = json.optInt("scanCode", 0),
            triggerPackage = json.optString("trigger").takeUnless { json.isNull("trigger") || it.isEmpty() },
            targetPackage = json.optString("target").takeUnless { json.isNull("target") || it.isEmpty() },
        )
    }
}

object RuleStore {
    private const val PREFS = "rules"
    private const val KEY_RULES = "rules"

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): List<Rule> {
        val raw = prefs(context).getString(KEY_RULES, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { Rule.fromJson(array.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun get(context: Context, id: String): Rule? = load(context).firstOrNull { it.id == id }

    fun put(context: Context, rule: Rule) {
        val rules = load(context).toMutableList()
        val index = rules.indexOfFirst { it.id == rule.id }
        if (index >= 0) rules[index] = rule else rules.add(rule)
        save(context, rules)
    }

    fun update(context: Context, id: String, change: (Rule) -> Rule) {
        get(context, id)?.let { put(context, change(it)) }
    }

    fun delete(context: Context, id: String) {
        save(context, load(context).filterNot { it.id == id })
    }

    private fun save(context: Context, rules: List<Rule>) {
        val array = JSONArray()
        rules.forEach { array.put(it.toJson()) }
        prefs(context).edit().putString(KEY_RULES, array.toString()).apply()
    }
}
