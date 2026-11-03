package io.github.aoguai.sesameag.task.antMember

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async

internal fun AntMember.scheduleGoldTicketWorkflows(
    scope: CoroutineScope,
    deferredTasks: MutableList<Deferred<Unit>>
) {
    if (enableGoldTicket?.value != true && enableGoldTicketConsume?.value != true) {
        return
    }
    deferredTasks.add(
        scope.async(Dispatchers.IO) {
            doGoldTicketTask(
                enableGoldTicket?.value == true,
                enableGoldTicketConsume?.value == true
            )
        }
    )
}
