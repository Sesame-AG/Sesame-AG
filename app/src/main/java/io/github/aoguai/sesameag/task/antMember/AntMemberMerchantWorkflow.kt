package io.github.aoguai.sesameag.task.antMember

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async

internal fun AntMember.scheduleMerchantWorkflows(
    scope: CoroutineScope,
    deferredTasks: MutableList<Deferred<Unit>>
) {
    if (merchantSign?.value != true &&
        merchantKmdk?.value != true &&
        merchantMoreTask?.value != true
    ) {
        return
    }
    deferredTasks.add(scope.async(Dispatchers.IO) { runMerchantWorkflow() })
}
