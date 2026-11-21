package app.rawline.mobile.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** Restore the default launcher entry on upgrades from a theme that enabled the old alias. */
object LauncherIcon {
    private const val DARK = "app.rawline.mobile.LauncherDark"
    private const val LIGHT = "app.rawline.mobile.LauncherLight"

    fun sync(context: Context) {
        val manager = context.packageManager
        runCatching {
            for (alias in listOf(DARK, LIGHT)) {
                val component = ComponentName(context, alias)
                if (manager.getComponentEnabledSetting(component) != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
                    manager.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
            }
        }
    }
}
