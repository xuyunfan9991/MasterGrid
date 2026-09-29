package com.xyf.mastergrid

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.Switch
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** 设置页：无障碍开关引导、宫格滤镜的排序和显示/隐藏。 */
class MainActivity : Activity() {
    private lateinit var store: FilterStore
    private lateinit var status: TextView
    private lateinit var empty: TextView
    private val adapter = FilterAdapter()
    private val touchHelper = ItemTouchHelper(DragCallback())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = FilterStore(this)
        status = findViewById(R.id.status)
        empty = findViewById(R.id.empty)

        findViewById<Button>(R.id.btnA11y).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnSortStyle).setOnClickListener {
            store.sortByStyle()
            adapter.load()
        }
        findViewById<Switch>(R.id.swReturn).apply {
            isChecked = store.returnAfterSelect
            setOnCheckedChangeListener { _, checked -> store.returnAfterSelect = checked }
        }
        findViewById<RecyclerView>(R.id.list).also {
            it.layoutManager = LinearLayoutManager(this)
            it.adapter = adapter
            touchHelper.attachToRecyclerView(it)
        }
    }

    override fun onResume() {
        super.onResume()
        val on = serviceEnabled()
        status.text = if (on) "✅ 无障碍服务已开启" else "⚠️ 无障碍服务未开启：请在无障碍设置 → 已下载的应用 里打开「大师滤镜宫格」"
        adapter.load()
        empty.visibility = if (adapter.itemCount == 0) View.VISIBLE else View.GONE
    }

    private fun serviceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val me = ComponentName(this, MasterGridService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val check: CheckBox = v.findViewById(R.id.check)
        val name: TextView = v.findViewById(R.id.name)
        val sub: TextView = v.findViewById(R.id.sub)
        val handle: View = v.findViewById(R.id.handle)
    }

    private inner class FilterAdapter : RecyclerView.Adapter<Holder>() {
        val items = mutableListOf<String>()

        @SuppressLint("NotifyDataSetChanged")
        fun load() {
            items.clear()
            items += store.customOrder
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_filter, parent, false))

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(h: Holder, position: Int) {
            val name = items[position]
            val (title, base) = FilterStore.split(name)
            h.name.text = title
            h.sub.text = base ?: ""
            h.sub.visibility = if (base == null) View.GONE else View.VISIBLE
            h.check.setOnCheckedChangeListener(null)
            h.check.isChecked = name !in store.hidden
            h.check.setOnCheckedChangeListener { _, checked ->
                store.hidden = if (checked) store.hidden - name else store.hidden + name
            }
            h.handle.setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) touchHelper.startDrag(h)
                false
            }
        }
    }

    private inner class DragCallback : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
        override fun isLongPressDragEnabled() = true

        override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
            val a = from.bindingAdapterPosition
            val b = to.bindingAdapterPosition
            adapter.items.add(b, adapter.items.removeAt(a))
            adapter.notifyItemMoved(a, b)
            return true
        }

        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(rv, vh)
            store.customOrder = adapter.items.toList()
        }
    }
}
