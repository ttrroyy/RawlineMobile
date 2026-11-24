package app.rawline.mobile.ui

import android.content.Intent
import android.os.Bundle
import app.rawline.mobile.R
import app.rawline.mobile.database.ProxyEntity

class ProfileSelectActivity : ThemedActivity(R.layout.layout_empty),
    ConfigurationFragment.SelectCallback {

    companion object {
        const val EXTRA_SELECTED = "selected"
        const val EXTRA_PROFILE_ID = "id"
        const val EXTRA_ALLOW_AUTO_SELECTORS = "allowAutoSelectors"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState != null) return
        val selected = intent.getParcelableExtra<ProxyEntity>(EXTRA_SELECTED)

        supportFragmentManager.beginTransaction()
            .replace(
                R.id.fragment_holder,
                ConfigurationFragment.forSelection(
                    selected, R.string.select_profile,
                    hideAutoSelectors = !intent.getBooleanExtra(EXTRA_ALLOW_AUTO_SELECTORS, false),
                )
            )
            .commitAllowingStateLoss()
    }

    override fun returnProfile(profileId: Long) {
        setResult(RESULT_OK, Intent().apply {
            putExtra(EXTRA_PROFILE_ID, profileId)
        })
        finish()
    }

}