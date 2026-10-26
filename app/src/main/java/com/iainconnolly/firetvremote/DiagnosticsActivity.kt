package com.iainconnolly.firetvremote

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView

class DiagnosticsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostics)
        findViewById<Button>(R.id.refresh_button).setOnClickListener { refresh() }
        findViewById<Button>(R.id.clear_button).setOnClickListener {
            DiagLog.clear()
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val running = if (RemapService.instance != null) "running" else "NOT running"
        val lines = listOf("Service is $running.") + DiagLog.snapshot()
        findViewById<TextView>(R.id.log).text = lines.joinToString("\n")
        val scroll = findViewById<ScrollView>(R.id.log_scroll)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }
}
