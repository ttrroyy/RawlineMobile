package app.rawline.mobile.ui.profile

import androidx.preference.PreferenceFragmentCompat
import app.rawline.mobile.R
import app.rawline.mobile.outbound.types.Juicity

class JuicitySettingsActivity : BindingSettingsActivity<Juicity>() {

    override fun createEntity() = Juicity()
    override val preferencesResource = R.xml.juicity_preferences

    init {
        pbm.text("name")
        pbm.text("server")
        pbm.int("serverPort")
        pbm.text("uuid")
        pbm.text("password")
        pbm.text("pinned_certchain_sha256")
        TlsBlock.bind(pbm)
    }

    override fun Juicity.serialize() {
        pbm.fromCacheAll(this)
        pinned_certchain_sha256 = pinned_certchain_sha256.trim()
    }

    override fun PreferenceFragmentCompat.onPreferencesCreated() {
        portInput("serverPort")
        passwordSummary("password")
        TlsBlock.setup(this, mustTls = true)
    }

}
