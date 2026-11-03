package io.github.aoguai.sesameag.task.antMember

import io.github.aoguai.sesameag.hook.ApplicationHookConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async

internal fun AntMember.scheduleBeanWorkflows(
    scope: CoroutineScope,
    deferredTasks: MutableList<Deferred<Unit>>
) {
    if (beanSignIn?.value != true && beanExchangeRight?.value != true && beanDrawPrize?.value != true) return
    deferredTasks.add(scope.async(Dispatchers.IO) {
        if (beanSignIn?.value == true) beanSignIn()
        if (ApplicationHookConstants.isOffline()) return@async
        if (beanExchangeRight?.value == true) beanExchangeRight()
        if (ApplicationHookConstants.isOffline()) return@async
        if (beanDrawPrize?.value == true) beanDrawPrize()
    })
}
