package com.firetvremote

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView

class AppPickerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_picker)
        findViewById<TextView>(R.id.picker_title).text = intent.getStringExtra(EXTRA_TITLE)

        val entries = Apps.launchable(this)

        val list = findViewById<ListView>(R.id.apps)
        list.adapter = AppAdapter(entries)
        list.setOnItemClickListener { _, _, position, _ ->
            setResult(RESULT_OK, Intent().putExtra(EXTRA_PACKAGE, entries[position].packageName))
            finish()
        }
    }

    private inner class AppAdapter(private val entries: List<AppEntry>) : BaseAdapter() {
        override fun getCount() = entries.size
        override fun getItem(position: Int) = entries[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_app, parent, false)
            val entry = entries[position]
            view.findViewById<ImageView>(R.id.app_icon).setImageDrawable(entry.icon)
            view.findViewById<TextView>(R.id.app_label).text = entry.label
            return view
        }
    }

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_PACKAGE = "package"
    }
}
