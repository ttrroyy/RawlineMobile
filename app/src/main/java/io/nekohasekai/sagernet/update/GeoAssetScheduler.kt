package app.rawline.mobile.update

import android.content.Context
import androidx.preference.PreferenceDataStore
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteCoroutineWorker
import androidx.work.multiprocess.RemoteListenableWorker
import androidx.work.multiprocess.RemoteWorkerService
import androidx.work.multiprocess.RemoteWorkManager
import app.rawline.mobile.bg.XrayGeoAssets
import app.rawline.mobile.database.DataStore
import app.rawline.mobile.database.SettingsRegistry
import app.rawline.mobile.database.preference.OnPreferenceDataStoreChangeListener
import app.rawline.mobile.ktx.Logs
import app.rawline.mobile.ktx.app
import app.rawline.mobile.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

object GeoAssetScheduler : OnPreferenceDataStoreChangeListener {
    private const val WORK_NAME = "RawlineGeoAssets"
    private val scheduling = Mutex()

    fun start() {
        DataStore.configurationStore.registerChangeListener(this)
        sync()
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        if (key == SettingsRegistry.XRAY_GEO_AUTO_UPDATE.key) sync()
    }

    private fun sync() {
        runOnDefaultDispatcher {
            scheduling.withLock {
                try {
                    val manager = RemoteWorkManager.getInstance(app)
                    if (DataStore.xrayGeoAutoUpdate) {
                        val request = PeriodicWorkRequestBuilder<RefreshTask>(24, TimeUnit.HOURS)
                            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                            .setInputData(Data.Builder()
                                .putString(RemoteListenableWorker.ARGUMENT_PACKAGE_NAME, app.packageName)
                                .putString(RemoteListenableWorker.ARGUMENT_CLASS_NAME, RemoteWorkerService::class.java.name)
                                .build())
                            .build()
                        manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request).get()
                    } else manager.cancelUniqueWork(WORK_NAME).get()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logs.w("GeoAssetScheduler: scheduling failed", e)
                }
            }
        }
    }

    internal fun updateDue(modifiedAt: Long, now: Long): Boolean =
        modifiedAt <= 0 || (now >= modifiedAt && now - modifiedAt >= TimeUnit.DAYS.toMillis(1))

    class RefreshTask(context: Context, params: WorkerParameters) : RemoteCoroutineWorker(context, params) {
        override suspend fun doRemoteWork(): Result {
            if (!DataStore.xrayGeoAutoUpdate) return Result.success()
            var failed = false
            for (name in XrayGeoAssets.FILES) {
                if (isStopped || !DataStore.xrayGeoAutoUpdate) return Result.success()
                if (!updateDue(XrayGeoAssets.file(name).lastModified(), System.currentTimeMillis())) continue
                try {
                    XrayGeoAssets.download(name, force = true, abort = { isStopped || !DataStore.xrayGeoAutoUpdate })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed = true
                    Logs.w("Automatic geodata update failed for $name", e)
                }
            }
            return if (failed) Result.retry() else Result.success()
        }
    }
}
