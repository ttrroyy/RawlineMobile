package app.rawline.mobile.bg

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import app.rawline.mobile.aidl.ICoreService
import app.rawline.mobile.ktx.app
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

// Main-process client of the :bg CoreService; each call binds for its own duration.
object CoreServiceClient {

    private class Connection : ServiceConnection {
        val service = CompletableDeferred<ICoreService>()

        override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
            service.complete(ICoreService.Stub.asInterface(binder))
        }

        override fun onServiceDisconnected(name: ComponentName?) = died()

        override fun onBindingDied(name: ComponentName?) = died()

        override fun onNullBinding(name: ComponentName?) {
            service.completeExceptionally(IllegalStateException("core service returned no binder"))
        }

        private fun died() {
            service.completeExceptionally(IllegalStateException("core service died"))
        }
    }

    private suspend fun <T> withService(block: suspend (ICoreService) -> T): T {
        repeat(3) { attempt ->
            val connection = Connection()
            try {
                if (app.bindService(Intent(app, CoreService::class.java), connection, Context.BIND_AUTO_CREATE)) {
                    val service = withTimeout(15_000) { connection.service.await() }
                    // Retry only a rejected binding, never an RPC that may already have imported data.
                    return block(service)
                }
                if (attempt == 2) error("cannot bind core service")
            } finally {
                runCatching { app.unbindService(connection) }
            }
            delay(250L * (attempt + 1))
        }
        error("cannot bind core service")
    }

    /** Binds for the duration of [block]; for one-shot calls such as `groupAction` or `warpRegister`. */
    suspend fun <T> call(block: suspend (ICoreService) -> T): T = withService { service -> block(service) }

    /**
     * Keeps a binding until the returned function is called; [onConnected] runs on every (re)connection,
     * [onDisconnected] when the :bg process dies. For long-lived listeners such as test sessions and subscription callbacks.
     */
    fun bindPersistent(onConnected: (ICoreService) -> Unit, onDisconnected: () -> Unit = {}): () -> Unit {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder) =
                onConnected(ICoreService.Stub.asInterface(binder))

            override fun onServiceDisconnected(name: ComponentName?) = onDisconnected()
            override fun onBindingDied(name: ComponentName?) = onDisconnected()
            override fun onNullBinding(name: ComponentName?) = onDisconnected()
        }
        try {
            check(app.bindService(Intent(app, CoreService::class.java), connection, Context.BIND_AUTO_CREATE)) { "cannot bind core service" }
        } catch (e: Exception) {
            runCatching { app.unbindService(connection) }
            throw e
        }
        return { runCatching { app.unbindService(connection) }; Unit }
    }

}
