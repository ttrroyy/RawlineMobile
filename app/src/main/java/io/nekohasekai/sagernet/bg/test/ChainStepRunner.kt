package app.rawline.mobile.bg.test

import android.os.Parcelable
import app.rawline.mobile.R
import app.rawline.mobile.aidl.IChainStepCallback
import app.rawline.mobile.bg.CoreRuntime
import app.rawline.mobile.bg.carrier.CarrierPool
import app.rawline.mobile.bg.proto.CoreConfig
import app.rawline.mobile.bg.proto.CoreConfigs
import app.rawline.mobile.bg.proto.applyTo
import app.rawline.mobile.database.DataStore
import app.rawline.mobile.database.ProxyEntity
import app.rawline.mobile.ktx.Logs
import app.rawline.mobile.ktx.app
import app.rawline.mobile.ktx.completeWith
import app.rawline.mobile.ktx.readableMessage
import app.rawline.mobile.outbound.config.PortLease
import app.rawline.core.mobile.Mobile
import app.rawline.core.mobile.TestRequest
import app.rawline.core.mobile.URLTestHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.parcelize.Parcelize
import java.util.concurrent.ConcurrentHashMap

/** ChainStepResult (TestRunner.h): one probe's verdict, [latency] as stored (ms, -1 failed, -2 Connect OK). */
@Parcelize
class ChainStepResult(val hop: Int, val alone: Boolean, val latency: Int, val error: String) : Parcelable

/** ChainStepReport (TestRunner.h): a hop equal to the hop count is the group's landing proxy. */
@Parcelize
class ChainStepReport(
    val error: String = "",
    val invalidHops: IntArray = IntArray(0),
    val hasLanding: Boolean = false,
    val aborted: Boolean = false,
    val results: List<ChainStepResult> = emptyList(),
) : Parcelable

/**
 * TestRunner::runChainStepTest in :bg: URL-tests the chain editor's unsaved hops ([CoreConfigs.buildChainStepTest])
 * with the URL test settings, stores nothing and reports once. It holds the engine's session lock for the whole run,
 * so it is refused while a session runs and sessions wait for it.
 */
internal object ChainStepRunner {

    fun start(hops: List<Long>, groupId: Long, callback: IChainStepCallback): Boolean {
        val run = Run(hops, groupId)
        var report = ChainStepReport(aborted = true)
        return TestEngine.startExclusive(finished = {
            try {
                callback.onDone(report)
            } catch (e: Exception) {
                Logs.w(e)
            }
        }) {
            try {
                report = run.execute()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logs.w(e)
                report = ChainStepReport(error = e.readableMessage)
            }
        }
    }

    private class Outcome(val latency: Int, val error: String)

    private class Run(private val hops: List<Long>, private val groupId: Long) {

        suspend fun execute(): ChainStepReport {
            // The probe box binds the bridge ports late; leased until the test is over.
            val ports = PortLease()
            try {
                val step = runInterruptible(Dispatchers.IO) { CoreConfigs.buildChainStepTest(hops, groupId, ports) }
                val invalidHops = step.invalidHops.sorted().toIntArray()
                val build = step.build
                if (step.error.isNotEmpty() || build == null || build.outboundTags.isEmpty()) {
                    return ChainStepReport(step.error, invalidHops, step.hasLanding)
                }
                val request = TestRequest().apply {
                    CoreConfig.from(build).applyTo(this)
                    url = DataStore.testUrl
                    timeoutMs = DataStore.urlTestTimeoutMs
                    maxConcurrency = DataStore.testConcurrent
                    step.vpnEndpointTags.forEach { addVPNEndpointTag(it) }
                }
                val outcomes = ConcurrentHashMap<String, Outcome>()
                val tunnelsUp: MutableSet<String> = ConcurrentHashMap.newKeySet()
                try {
                    TestTags.holding(build.outboundTags) {
                        withPreparedTransports(build.carrierPlans, { plan -> CarrierPool.acquire(plan, interactive = true) }) {
                            suspendCancellableCoroutine<Unit> { continuation ->
                                if (!continuation.isActive) return@suspendCancellableCoroutine
                                Mobile.startURLTest(
                                    null, CoreRuntime.platform, request,
                                    Handler(outcomes, tunnelsUp) { continuation.completeWith(Result.success(Unit)) },
                                )
                                if (continuation.isCancelled) Mobile.stopTests()
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logs.w(e)
                    val error = e.message?.takeIf { it.isNotBlank() } ?: app.getString(R.string.chain_step_no_answer)
                    return ChainStepReport(error, invalidHops, step.hasLanding)
                }

                var aborted = false
                val results = ArrayList<ChainStepResult>()
                for ((tag, outcome) in outcomes) {
                    val probe = step.tagToProbe[tag] ?: continue
                    if (outcome.error.isNotEmpty() && ProxyEntity.isTestAborted(outcome.error)) {
                        aborted = true
                        continue
                    }
                    results.add(
                        when {
                            outcome.error.isEmpty() -> ChainStepResult(probe.hop, probe.alone, outcome.latency, "")
                            tag in tunnelsUp -> ChainStepResult(probe.hop, probe.alone, ProxyEntity.LATENCY_CONNECT_ONLY, "")
                            else -> ChainStepResult(probe.hop, probe.alone, -1, outcome.error)
                        }
                    )
                }
                return ChainStepReport("", invalidHops, step.hasLanding, aborted, results)
            } finally {
                ports.close()
            }
        }
    }

    /** Collects the results and which OpenVPN / OpenConnect tunnels came up; [done] once the core is through. */
    private class Handler(
        private val outcomes: MutableMap<String, Outcome>,
        private val tunnelsUp: MutableSet<String>,
        private val done: () -> Unit,
    ) : URLTestHandler {
        override fun onResult(tag: String?, latencyMs: Int, error: String?) = guarded {
            if (tag != null) outcomes[tag] = Outcome(latencyMs, error.orEmpty())
        }

        override fun onVPNStatus(tag: String?, connected: Boolean, state: String?, err: String?) = guarded {
            if (tag != null && connected) tunnelsUp.add(tag)
        }

        override fun onDone() = guarded(done)
    }
}
