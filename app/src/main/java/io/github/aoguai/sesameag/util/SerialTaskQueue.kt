package io.github.aoguai.sesameag.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicReference

/** 完整处理一次提交后才放行后继；挂起和取消等待都不会打乱提交顺序。 */
class SerialTaskQueue {
    private val tail = AtomicReference<CompletableDeferred<Unit>?>(null)

    suspend fun <T> submit(block: suspend () -> T): T {
        val completion = CompletableDeferred<Unit>()
        val previous = tail.getAndSet(completion)
        try {
            previous?.await()
            currentCoroutineContext().ensureActive()
            return block()
        } finally {
            // 等待者取消时，其后继仍须等原前驱退出，不能提前开始。
            fun finish() {
                tail.compareAndSet(completion, null)
                completion.complete(Unit)
            }
            if (previous == null || previous.isCompleted) finish()
            else previous.invokeOnCompletion { finish() }
        }
    }

    /** 供现有同步入口提交使用；队列内部直接调用私有实现，避免再次入队。 */
    fun <T> execute(block: () -> T): T = runBlocking { submit { block() } }
}
