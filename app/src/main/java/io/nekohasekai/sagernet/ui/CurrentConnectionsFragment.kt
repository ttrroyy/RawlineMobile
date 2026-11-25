package app.rawline.mobile.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.TextUtils
import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.rawline.mobile.R
import app.rawline.mobile.database.DataStore
import app.rawline.mobile.database.ProfileManager
import app.rawline.mobile.widget.applyInsetMargin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class CurrentConnectionsFragment : ToolbarFragment(R.layout.layout_current_connections) {
    private lateinit var list: RecyclerView
    private lateinit var message: TextView
    private val processes = mutableMapOf<String, String>()
    private val expanded = mutableSetOf<String>()
    private var subscription: Job? = null
    private var activeRows = emptyList<ActiveConnection>()
    private var feedProfile = 0L
    private var profileName = ""
    private val adapter = ConnectionAdapter { key ->
        if (!expanded.add(key)) expanded.remove(key)
        render()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar?.setTitle(R.string.home_connections)
        list = view.findViewById(R.id.current_connection_list)
        list.applyInsetMargin(bottom = true, horizontal = true)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        list.itemAnimator = null
        MobilePalette.showScrollIndicator(list)
        message = view.findViewById(R.id.current_connection_message)
        message.setTextColor(MobilePalette.muted)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                onServiceStateChanged()
                try {
                    kotlinx.coroutines.awaitCancellation()
                } finally {
                    subscription?.cancel()
                }
            }
        }
    }

    fun onServiceStateChanged() {
        if (view == null || !viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        if (!DataStore.serviceState.connected || !DataStore.apiDashboardEnabled) {
            subscription?.cancel()
            subscription = null
            activeRows = emptyList()
            adapter.submitList(emptyList())
            showStatus(if (DataStore.serviceState.connected) R.string.connections_unavailable else R.string.connections_stopped)
            return
        }
        val profile = DataStore.currentProfile
        if (subscription?.isActive == true && profile == feedProfile) return
        subscription?.cancel()
        feedProfile = profile
        showStatus(R.string.connecting)
        subscription = viewLifecycleOwner.lifecycleScope.launch {
            profileName = withContext(Dispatchers.IO) { ProfileManager.getProfile(profile)?.displayName().orEmpty() }
            ConnectionFeed.subscribe(DataStore.coreBoxApiPort, DataStore.coreBoxApiSecret)
                .conflate()
                .retryWhen { cause, _ ->
                    if (cause !is IOException) return@retryWhen false
                    showStatus(R.string.connections_unavailable)
                    delay(1500)
                    true
                }
                .collect {
                    activeRows = it
                    render()
                    delay(400)
                }
        }
    }

    private fun showStatus(text: Int) {
        message.setText(text)
        message.visibility = View.VISIBLE
        list.visibility = View.GONE
    }

    private fun render() {
        if (activeRows.isEmpty()) {
            showStatus(R.string.connections_empty)
            return
        }
        message.visibility = View.GONE
        list.visibility = View.VISIBLE
        val groups = activeRows.groupBy { it.process }
        val rows = groups.entries.sortedBy { displayProcess(it.key) }.flatMap { (process, connections) ->
            val open = process in expanded
            val title = "${if (open) "▾" else "▸"}  ${displayProcess(process)} (${connections.size})"
            val route = connections.map { displayRoute(it.route) }.distinct().singleOrNull().orEmpty()
            val traffic = traffic(connections.sumOf { it.upload }, connections.sumOf { it.download })
            listOf(Line("group:$process", title, listOf(route, traffic).filter(String::isNotBlank).joinToString("  ·  "), process)) +
                if (open) connections.map {
                    Line(it.id, it.destination.ifEmpty { "—" }, listOf(it.protocol, displayRoute(it.route), traffic(it.upload, it.download))
                        .filter(String::isNotBlank).joinToString("  ·  "))
                } else emptyList()
        }
        adapter.submitList(rows)
    }

    private fun displayRoute(route: String): String =
        route.split(" → ").joinToString(" → ") {
            if (it == "proxy" && profileName.isNotBlank()) profileName else it
        }

    private fun displayProcess(process: String): String {
        if (process.isBlank()) return getString(R.string.connections_unknown_app)
        return processes.getOrPut(process) {
            val pm = requireContext().packageManager
            try { pm.getApplicationLabel(pm.getApplicationInfo(process, 0)).toString() }
            catch (_: PackageManager.NameNotFoundException) { process }
        }
    }

    private fun traffic(upload: Long, download: Long) =
        "↑ ${Formatter.formatShortFileSize(requireContext(), upload)}  ↓ ${Formatter.formatShortFileSize(requireContext(), download)}"

    private data class Line(val id: String, val title: String, val summary: String, val group: String? = null)

    private class ConnectionAdapter(private val toggle: (String) -> Unit) : ListAdapter<Line, Row>(
        object : DiffUtil.ItemCallback<Line>() {
            override fun areItemsTheSame(oldItem: Line, newItem: Line) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: Line, newItem: Line) = oldItem == newItem
        },
    ) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Row(parent.context)
        override fun onBindViewHolder(holder: Row, position: Int) {
            val row = getItem(position)
            holder.title.text = row.title
            holder.summary.text = row.summary
            holder.itemView.setOnClickListener(row.group?.let { group -> View.OnClickListener { toggle(group) } })
            holder.itemView.isClickable = row.group != null
            holder.itemView.isFocusable = row.group != null
        }
    }

    private class Row(context: Context) : RecyclerView.ViewHolder(LinearLayout(context)) {
        val title = TextView(context)
        val summary = TextView(context)
        init {
            val row = itemView as LinearLayout
            fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
            row.orientation = LinearLayout.VERTICAL
            row.layoutParams = RecyclerView.LayoutParams(-1, -2).apply { setMargins(dp(16), 0, dp(16), dp(6)) }
            row.background = MobilePalette.rowShape(context)
            row.setPadding(dp(14), dp(9), dp(14), dp(9))
            title.setTextColor(MobilePalette.text)
            title.textSize = 15f
            title.setTypeface(null, android.graphics.Typeface.BOLD)
            title.maxLines = 1
            title.ellipsize = TextUtils.TruncateAt.END
            summary.setTextColor(MobilePalette.muted)
            summary.textSize = 12f
            summary.maxLines = 2
            summary.ellipsize = TextUtils.TruncateAt.END
            row.addView(title)
            row.addView(summary)
        }
    }
}
