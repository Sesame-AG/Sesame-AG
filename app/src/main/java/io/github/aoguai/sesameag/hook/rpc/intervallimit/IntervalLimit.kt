package io.github.aoguai.sesameag.hook.rpc.intervallimit

interface IntervalLimit {
    val interval: Int? // 对应 Integer getInterval()
    var time: Long     // 对应 Long getTime() 和 void setTime(Long)
}
