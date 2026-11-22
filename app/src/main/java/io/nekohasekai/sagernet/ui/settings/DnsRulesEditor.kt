package app.rawline.mobile.ui.settings

import android.content.Context
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.rawline.mobile.R
import app.rawline.mobile.database.DataStore
import app.rawline.mobile.outbound.config.DnsRule
import app.rawline.mobile.outbound.config.DnsRules
import app.rawline.mobile.ui.MobileAlertDialogBuilder
import app.rawline.mobile.ui.MobilePalette
import app.rawline.mobile.ui.MobileSheetDialog
import app.rawline.mobile.ui.ServerUi

/** The DNS rules of the settings: which sites resolve through which server, through the proxy or directly. */
internal object DnsRulesEditor {
    fun summary(context: Context): String {
        val rules = DnsRules.decode(DataStore.dnsRules)
        return if (rules.isEmpty()) context.getString(R.string.dns_rules_summary_empty)
        else context.getString(R.string.dns_rules_summary, rules.size, rules.count { it.enabled })
    }

    fun show(context: Context, pickRuleSets: (List<String>, (List<String>) -> Unit) -> Unit, changed: () -> Unit) {
        val ui = ServerUi(context)
        val dialog = MobileSheetDialog(context)
        val body = ui.column(16)
        fun render() {
            body.removeAllViews()
            body.addView(ui.text(context.getString(R.string.dns_rules_title), 19f, bold = true))
            body.addView(ui.text(context.getString(R.string.dns_rules_note), 14f, MobilePalette.muted).apply { setPadding(0, ui.dp(6), 0, ui.dp(8)) })
            val rules = DnsRules.decode(DataStore.dnsRules)
            rules.forEachIndexed { index, rule ->
                val shown = rule.sites.take(2).joinToString(", ")
                val title = if (rule.sites.size > 2) context.getString(R.string.dns_rules_more, shown, rule.sites.size - 2) else shown
                val subtitle = listOfNotNull(rule.server, context.getString(if (rule.direct) R.string.dns_rules_direct else R.string.dns_rules_via_proxy),
                    context.getString(R.string.dns_rules_off).takeIf { !rule.enabled }).joinToString(" · ")
                body.addView(ui.tile(title, subtitle, R.drawable.ic_action_dns) { edit(context, index, pickRuleSets) { render(); changed() } },
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
            }
            body.addView(ui.tile(context.getString(R.string.dns_rules_add), null, R.drawable.ic_mobile_add) { edit(context, null, pickRuleSets) { render(); changed() } },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        }
        render()
        dialog.setContentView(ScrollView(MobilePalette.scrollContext(context)).apply { addView(body) })
        dialog.show()
    }

    private fun edit(context: Context, index: Int?, pickRuleSets: (List<String>, (List<String>) -> Unit) -> Unit, saved: () -> Unit) {
        val ui = ServerUi(context)
        val rules = DnsRules.decode(DataStore.dnsRules).toMutableList()
        val rule = index?.let(rules::getOrNull) ?: DnsRule()
        val dialog = MobileSheetDialog(context)
        val body = ui.column(16)
        body.addView(ui.text(context.getString(if (index == null) R.string.dns_rules_new else R.string.dns_rules_rule), 19f, bold = true))
        val sites = ui.field(body, context.getString(R.string.dns_rules_sites), rule.sites.joinToString("\n"), multiline = true)
        body.addView(ui.text(context.getString(R.string.dns_rules_sites_help), 13f, MobilePalette.muted).apply { setPadding(ui.dp(4), ui.dp(4), 0, 0) })
        body.addView(ui.tile(context.getString(R.string.rule_set_picker_title), null, R.drawable.ic_action_dns) {
            pickRuleSets(DnsRules.parse(DnsRules.lines(sites.text.toString())).ruleSets) { selected ->
                val original = DnsRules.lines(sites.text.toString())
                sites.setText(DnsRules.withRuleSets(original, selected).joinToString("\n"))
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        val server = ui.field(body, context.getString(R.string.dns_rules_server), rule.server)
        val direct = ui.switchRow(body, context.getString(R.string.dns_rules_direct_switch), rule.direct)
        val enabled = ui.switchRow(body, context.getString(R.string.dns_rules_enabled), rule.enabled)
        val error = TextView(context).apply { setTextColor(android.graphics.Color.rgb(232, 120, 112)); textSize = 14f; setPadding(ui.dp(4), ui.dp(8), 0, 0) }
        body.addView(error)
        fun store(list: List<DnsRule>) { DataStore.dnsRules = DnsRules.encode(list); dialog.dismiss(); saved() }
        body.addView(ui.button(context.getString(R.string.save), primary = true) {
            val next = DnsRule(enabled.isChecked, DnsRules.lines(sites.text.toString()), server.text.toString().trim(), direct.isChecked)
            val problem = next.copy(enabled = true).problem()
            if (problem.isNotEmpty()) { error.text = problem; return@button }
            if (index == null || index !in rules.indices) rules += next else rules[index] = next
            store(rules)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(12) })
        if (index != null && index in rules.indices) body.addView(ui.button(context.getString(R.string.delete), danger = true) {
            MobileAlertDialogBuilder(context).setMessage(R.string.dns_rules_delete_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.delete) { _, _ -> rules.removeAt(index); store(rules) }
                .show()
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        dialog.setContentView(ScrollView(MobilePalette.scrollContext(context)).apply { addView(body) })
        dialog.show()
    }
}
