package app.rawline.mobile.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import app.rawline.mobile.Action
import app.rawline.mobile.R
import app.rawline.mobile.database.DataStore
import app.rawline.mobile.database.GroupRepo
import app.rawline.mobile.database.ProfileManager
import app.rawline.mobile.database.ProxyEntity
import app.rawline.mobile.database.ProxyGroup
import app.rawline.mobile.database.SagerDatabase
import app.rawline.mobile.ktx.runOnMainDispatcher
import app.rawline.mobile.widget.applyInsetMargin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SwitchActivity : ThemedActivity(R.layout.layout_current_connections) {
    private lateinit var list: ListView
    private lateinit var groupButton: TextView
    private lateinit var empty: TextView
    private var groups = emptyList<ProxyGroup>()
    private var profiles = emptyList<ProxyEntity>()
    private var groupIndex = 0
    private var query = ""
    private var load: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.setTitle(R.string.action_switch)
        toolbar.setNavigationIcon(R.drawable.baseline_arrow_back_24)
        toolbar.navigationIcon?.setTint(MobilePalette.text)
        toolbar.setNavigationOnClickListener { finish() }

        val oldList = findViewById<View>(R.id.current_connection_list)
        val frame = oldList.parent as ViewGroup
        frame.removeView(oldList)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        frame.addView(page, 0, ViewGroup.LayoutParams(-1, -1))
        page.applyInsetMargin(bottom = true, horizontal = true, ime = true)
        empty = findViewById(R.id.current_connection_message)
        empty.setText(R.string.profiles_empty)
        empty.setTextColor(MobilePalette.muted)
        empty.visibility = View.GONE

        groupButton = TextView(this).apply {
            textSize = 16f
            setTextColor(MobilePalette.text)
            background = MobilePalette.rowShape(context)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener {
                if (groups.size > 1) MobileSelector.show(
                    this@SwitchActivity, getString(R.string.menu_group),
                    groups.map { it.displayName() }, groupIndex, ::showGroup,
                )
            }
        }
        page.addView(groupButton, LinearLayout.LayoutParams(-1, -2))
        val search = SearchView(this).apply {
            isIconifiedByDefault = false
            queryHint = getString(R.string.search_apps)
            clearFocus()
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(text: String?) = false
                override fun onQueryTextChange(text: String?): Boolean {
                    query = text.orEmpty()
                    render()
                    return true
                }
            })
        }
        page.addView(search, LinearLayout.LayoutParams(-1, -2))
        list = ListView(MobilePalette.scrollContext(this)).apply {
            divider = null
            MobilePalette.showScrollIndicator(this)
        }
        page.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        list.emptyView = empty
        lifecycleScope.launch {
            val (loaded, activeGroup) = withContext(Dispatchers.IO) {
                GroupRepo.all() to ProfileManager.getProfile(DataStore.selectedProxy)?.groupId
            }
            groups = loaded
            val index = loaded.indexOfFirst { it.id == activeGroup }.coerceAtLeast(0)
            showGroup(index)
        }
    }

    private fun showGroup(index: Int) {
        val group = groups.getOrNull(index) ?: return
        groupIndex = index
        groupButton.text = group.displayName()
        load?.cancel()
        load = lifecycleScope.launch {
            profiles = withContext(Dispatchers.IO) { SagerDatabase.proxyDao.getByGroup(group.id) }
            render()
        }
    }

    private fun render() {
        if (!::list.isInitialized) return
        val shown = profiles.filter { it.displayName().contains(query.trim(), ignoreCase = true) }
        list.adapter = object : android.widget.BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(position: Int) = shown[position]
            override fun getItemId(position: Int) = getItem(position).id
            override fun getView(position: Int, old: View?, parent: ViewGroup): View {
                val row = (old as? ProfileRow) ?: ProfileRow(parent.context)
                val profile = getItem(position)
                val name = profile.displayName()
                val flag = MobileCountryFlags.first(name)
                row.flag.text = flag?.value ?: "◎"
                row.title.text = flag?.let { name.removeRange(it.range).trim() } ?: name
                row.summary.text = listOf(profile.displayProtocol(), profile.latency.takeIf { it > 0 }?.let { "$it ms" })
                    .filterNotNull().joinToString("  ·  ")
                row.isActivated = profile.id == DataStore.selectedProxy
                row.alpha = if (row.isActivated) 1f else .84f
                return row
            }
        }
        list.setOnItemClickListener { _, _, index, _ -> returnProfile(shown[index].id) }
    }

    private fun returnProfile(profileId: Long) {
        val old = DataStore.selectedProxy
        DataStore.selectedProxy = profileId
        runOnMainDispatcher {
            ProfileManager.postUpdate(old, true)
            ProfileManager.postUpdate(profileId, true)
        }
        sendBroadcast(Intent(Action.SWITCH_PROFILE).setPackage(packageName).putExtra(Action.EXTRA_PROFILE_ID, profileId))
        finish()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private inner class ProfileRow(context: Context) : LinearLayout(context) {
        val flag = TextView(context)
        val title = TextView(context)
        val summary = TextView(context)
        init {
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = MobilePalette.rowShape(context)
            minimumHeight = dp(54)
            flag.gravity = android.view.Gravity.CENTER
            flag.textSize = 19f
            flag.background = MobilePalette.rowShape(context)
            addView(flag, LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(10) })
            val labels = LinearLayout(context).apply { orientation = VERTICAL }
            title.setTextColor(MobilePalette.text)
            title.textSize = 15f
            title.maxLines = 2
            title.ellipsize = TextUtils.TruncateAt.END
            summary.setTextColor(MobilePalette.muted)
            summary.textSize = 12f
            summary.maxLines = 1
            summary.ellipsize = TextUtils.TruncateAt.END
            labels.addView(title)
            labels.addView(summary)
            addView(labels, LayoutParams(0, -2, 1f))
        }
    }
}
