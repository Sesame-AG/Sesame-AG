package io.github.aoguai.sesameag.hook.rpc.intervallimit

class DefaultIntervalLimit(override val interval: Int?) : IntervalLimit {
    override var time: Long = 0
}
