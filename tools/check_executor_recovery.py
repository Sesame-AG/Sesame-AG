#!/usr/bin/env python3
"""Run the production workflow recovery code with a failing/recovering Binder stub.

Android/Binder are stubbed; delays are shortened. This does not replace USB checks.
"""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
CACHE = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle')) / 'caches/modules-2/files-2.1'
source = (ROOT / 'app/src/main/java/io/github/aoguai/sesameag/hook/ApplicationHook.kt').read_text()
recovery = source.split('        private fun ensureRootAccessForWorkflow(', 1)[1].split('        private fun ensureLegalAcceptanceForWorkflow()', 1)[0]
code = r'''
import kotlinx.coroutines.*
import kotlinx.coroutines.delay as realDelay

val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
fun execute(block: suspend CoroutineScope.() -> Unit) = scope.launch(block = block)
suspend fun delay(ms: Long) { realDelay(10) }
fun record(tag: String, message: String) {}
fun printStackTrace(tag: String, message: String, error: Throwable) { throw error }
fun updateRunningStatus(message: String) {}
object Log { fun w(tag: String, message: String) {} ; fun record(tag: String, message: String) {}; fun printStackTrace(tag: String, message: String, error: Throwable) {} }
object Config { var accepted = true; fun isLoaded() = true; fun isLegalAcceptedForCurrentVersion() = accepted }
object AccountSlotRegistry { fun shortHash(id: String) = id; fun isExecutableUser(id: String?) = true }
object RuntimeIdentityGuard { fun isTrustedForExecution() = true }
object CommandUtil {
    sealed class ServiceStatus {
        data object Loading: ServiceStatus()
        data object Inactive: ServiceStatus()
        data class Error(val msg: String): ServiceStatus()
        data class Active(val type: String): ServiceStatus()
    }
    var attempts = 0
    var connected = false
    var alwaysFail = false
    var authorized = true
    suspend fun awaitServiceStatus(context: Any): ServiceStatus {
        attempts++
        if (alwaysFail || attempts == 1) return ServiceStatus.Error("bind_false")
        connected = true
        return ServiceStatus.Active("Shizuku")
    }
}
object WorkflowRootGuard {
    fun hasGrantedRoot() = true
    fun isExecutionAllowed() = Config.accepted && CommandUtil.connected && CommandUtil.authorized
    suspend fun hasRoot(forceRefresh: Boolean, reason: String) = true
}
object AccountSessionCoordinator {
    var epoch = 1L
    var refreshed = 0
    fun currentSessionEpoch() = epoch
    fun refreshWorkflowState(context: Any?, reason: String) { refreshed++ }
}
object ApplicationHookConstants {
    var offline = false
    fun isOffline() = offline
    fun clearPendingTriggers(reason: String) {}
    fun submitEntry(reason: String, block: () -> Unit) { block() }
}
object ApplicationHookCore { var dispatches = 0; fun dispatchIfNeeded() { dispatches++ } }
enum class PersistentReconcileMode { FIRE_ALARM_DUE }
object UnifiedScheduler {
    fun reconcilePersistentSchedules(context: Any, mode: PersistentReconcileMode) {}
}
object Hook {
    const val TAG = "test"
    var init = false
    var pendingInit = false
    var pendingInitReason: String? = null
    var rootCheckInProgress = false
    var appContext: Any? = Any()
    var service: Any? = Any()
    var currentUid: String? = "test-account"
    var initialized = 0
    fun initHandler(reason: String): Boolean {
        check(Config.accepted && CommandUtil.connected)
        initialized++
        init = true
        pendingInit = false
        return true
    }
    fun start() = ensureRootAccessForWorkflow("onResume")
    private fun ensureRootAccessForWorkflow(''' + recovery + r'''
}
suspend fun settle() {
    withTimeout(1500) { while (Hook.rootCheckInProgress) realDelay(5) }
}
fun reset() {
    Hook.init = false; Hook.initialized = 0; Hook.pendingInit = false
    Hook.currentUid = "test-account"
    Config.accepted = true
    CommandUtil.attempts = 0; CommandUtil.connected = false; CommandUtil.alwaysFail = false
    CommandUtil.authorized = true
    ApplicationHookConstants.offline = false
    ApplicationHookCore.dispatches = 0
}
fun main() = runBlocking {
    reset()
    Hook.start(); Hook.start(); settle()
    check(Hook.initialized == 1) { "Service recovered but initialization stayed blocked until a config reload" }
    check(Config.accepted) { "Recovery changed legal acceptance" }
    reset(); Hook.init = true
    Hook.start(); settle()
    check(Hook.initialized == 0) { "Reconnecting an initialized workflow restarted its tasks" }
    check(ApplicationHookCore.dispatches == 1) { "Queued execution was not resumed" }
    reset(); CommandUtil.authorized = false
    Hook.start(); settle()
    check(Hook.initialized == 0) { "Service permission denial was bypassed" }
    reset(); CommandUtil.alwaysFail = true
    Hook.start(); realDelay(2); Config.accepted = false; settle()
    check(Hook.initialized == 0) { "Recovery ignored withdrawn consent" }
    reset(); CommandUtil.alwaysFail = true
    Hook.start(); realDelay(2); AccountSessionCoordinator.epoch++; settle()
    check(Hook.initialized == 0) { "Recovery crossed account session boundaries" }
    reset(); ApplicationHookConstants.offline = true
    Hook.start(); settle()
    check(Hook.initialized == 0) { "Recovery cleared an offline safety pause" }
    checkRpcGate()
    scope.cancel()
    println("PASS: delayed connection, no duplicate init, consent/session/offline guards")
}
'''
rpc_source = (ROOT / 'app/src/main/java/io/github/aoguai/sesameag/hook/RequestManager.kt').read_text()
rpc_method = rpc_source.split('    private inline fun executeRpcOnce(', 1)[1].split('    /**\n     * 处理失败逻辑', 1)[0]
code += r'''
class AccountSessionIdentity
class RpcBridge
class JSONObject(val body: String)
sealed class RpcRequestOutcome {
    data class Failure(val reason: String): RpcRequestOutcome()
    data class Stopped(val body: String): RpcRequestOutcome()
    data class Success(val body: String): RpcRequestOutcome()
}
object RpcDailyCircuit { fun isCurrent(identity: AccountSessionIdentity) = true; fun isStopResponse(body: JSONObject) = false }
object RpcOfflineRisk { fun isHardBlocked(body: JSONObject) = false }
object RpcFallbackJsonFactory { fun build(reason: String, method: String?) = reason }
object ModuleStatusReporter { fun requestUpdate(reason: String) {} }
object RequestTest {
    val TAG = "rpc-test"
    val rpcBridgeNullCount = java.util.concurrent.atomic.AtomicInteger(0)
    val errorCount = java.util.concurrent.atomic.AtomicInteger(0)
    val rpcBridgeNullLogLimiter = Limiter()
    class Limiter { fun shouldLog() = false }
    fun tryBlockByOffline(method: String?): RpcRequestOutcome.Failure? = null
    fun getRpcBridge() = RpcBridge()
    fun handleFailure(method: String, reason: String) { errorCount.incrementAndGet() }
    fun request(block: (RpcBridge) -> String?) = executeRpcOnce("test.rpc", AccountSessionIdentity(), block)
    private inline fun executeRpcOnce(''' + rpc_method + r'''
}
fun checkRpcGate() {
    reset()
    repeat(12) { RequestTest.request { null } }
    check(RequestTest.errorCount.get() == 0) { "Local service outage counted as remote RPC failures" }
    CommandUtil.connected = true
    RequestTest.request { CommandUtil.connected = false; null }
    check(RequestTest.errorCount.get() == 0) { "Mid-request service loss counted as remote RPC failure" }
    CommandUtil.connected = true
    RequestTest.request { null }
    check(RequestTest.errorCount.get() == 1) { "A real empty RPC response was not counted" }
    check(RequestTest.request { "ok" } is RpcRequestOutcome.Success)
    check(RequestTest.errorCount.get() == 0)
}
'''
jars = []
for group, artifact in [('org.jetbrains.kotlin','kotlin-compiler-embeddable'),
                        ('org.jetbrains.kotlin','kotlin-stdlib'), ('org.jetbrains.kotlin','kotlin-script-runtime'),
                        ('org.jetbrains.kotlin','kotlin-reflect'), ('org.jetbrains.intellij.deps','trove4j'),
                        ('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm'), ('org.jetbrains','annotations')]:
    matches = sorted(CACHE.glob(f'{group}/{artifact}/*/*/*.jar'))
    if matches:
        jars.append(matches[-1])
classpath = os.pathsep.join(map(str, jars))
java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else shutil.which('java')
assert java and jars, 'Build once with JDK 17 to populate the Kotlin compiler cache'
with tempfile.TemporaryDirectory() as directory:
    path = Path(directory)
    (path / 'Check.kt').write_text(code)
    subprocess.run([java, '-cp', classpath, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-classpath', classpath, '-d', str(path / 'classes'),
                    str(path / 'Check.kt')], check=True)
    subprocess.run([java, '-cp', str(path / 'classes') + os.pathsep + classpath, 'CheckKt'], check=True)
