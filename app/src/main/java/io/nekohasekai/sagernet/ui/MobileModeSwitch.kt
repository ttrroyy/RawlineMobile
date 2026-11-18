package app.rawline.mobile.ui

import app.rawline.mobile.bg.BaseService

internal class MobileModeSwitch(pending: String? = null) {
    var pendingMode: String? = pending
        private set

    fun request(current: String, target: String) { pendingMode = target.takeUnless { it == current } }
    fun cancel() { pendingMode = null }
    fun takeAfterStop(state: BaseService.State): String? {
        if (state != BaseService.State.Stopped) return null
        return pendingMode.also { pendingMode = null }
    }
}
