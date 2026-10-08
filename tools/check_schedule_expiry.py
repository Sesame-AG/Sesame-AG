#!/usr/bin/env python3
"""Run the production Kotlin planner/batch methods without an Android device.
Uses the Gradle-cached Kotlin compiler; stubs only storage, routing and AlarmManager.
"""
from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java/io/github/aoguai/sesameag/hook/keepalive'
CACHE = Path.home() / '.gradle/caches/modules-2/files-2.1'

def method(file, start, end):
    return (SRC / file).read_text().split(start, 1)[1].split(end, 1)[0]

planner = 'private fun selectPlan' + method('SystemWakeScheduler.kt', 'private fun selectPlan', 'private fun schedulePlanOnContext')
batch = 'fun fireDueSchedules' + method('PersistentScheduleRegistry.kt', 'fun fireDueSchedules', 'fun clearAll')
code = '''package io.github.aoguai.sesameag.hook.keepalive
class Context
object Log { fun record(vararg args: Any?) {} }
object SystemWakeScheduler {
 data class AlarmPlan(val primary: PersistentSchedule, val triggerAtMs: Long, val precisionPolicy: String)
 var armed: AlarmPlan? = null
 fun schedule(context: Context, schedule: PersistentSchedule, silent: Boolean = false): Boolean {
  armed = selectPlan(PersistentScheduleRegistry.list()); return true
 }
 fun cancelLaunchConfirmationTimeout(id: String) {}
 fun chosen(items: List<PersistentSchedule>) = selectPlan(items)?.primary?.id
''' + planner + '''
}
object ScheduledTaskRouter {
 val routed = mutableListOf<String>()
 fun fire(context: Context, schedule: PersistentSchedule, source: String) { routed.add(schedule.id) }
}
object PersistentScheduleRegistry {
 const val TAG = "test"
 var rows = mutableListOf<PersistentSchedule>()
 var freshReads = 0
 fun <T> withRegistryLock(block: () -> T): T { freshReads++; return block() }
 fun list() = rows.toList()
 fun loadMutable() = rows.toMutableList()
 fun save(items: List<PersistentSchedule>) { rows = items.toMutableList() }
''' + batch + '''
}
fun main() {
 val now = System.currentTimeMillis()
 val stale = PersistentSchedule(id="stale", kind=PersistentScheduleKind.MODULE_CHILD,
  triggerAtMs=now-600000, toleranceMs=120000)
 val next = PersistentSchedule(id="next", kind=PersistentScheduleKind.GLOBAL_WAKEUP, triggerAtMs=now+60000)
 check(SystemWakeScheduler.chosen(listOf(stale, next)) == "next") { "Expired child blocks the next alarm" }
 val registry = PersistentScheduleRegistry
 registry.rows = mutableListOf(stale, next)
 check(registry.fireDueSchedules(Context(), "test", now) == 0)
 check(registry.rows.first().state == PersistentScheduleState.EXPIRED) { "Expired row was not retired" }
 check(SystemWakeScheduler.armed?.primary?.id == "next") { "Empty batch did not rearm the next alarm" }
 check(ScheduledTaskRouter.routed.isEmpty()) { "Expired business task was dispatched" }
 check(registry.freshReads > 0) { "Batch used an unrefreshed cross-process snapshot" }
 val boundary = stale.copy(id="boundary", triggerAtMs=now-120000)
 registry.rows = mutableListOf(boundary, next)
 check(registry.fireDueSchedules(Context(), "test", now) == 1)
 check(ScheduledTaskRouter.routed == listOf("boundary")) { "Tolerance boundary was incorrectly expired" }
 registry.rows.clear()
 check(registry.fireDueSchedules(Context(), "test", now) == 0)
 check(SystemWakeScheduler.armed == null) { "Empty registry left a stale alarm" }
 println("PASS: expired child, empty batch rearm, fresh snapshot, tolerance boundary, empty registry")
}
'''
compiler = sorted(CACHE.glob('org.jetbrains.kotlin/kotlin-compiler-embeddable/*/*/*.jar'))[-1]
jars = [compiler]
for group, artifact in [('org.jetbrains.kotlin','kotlin-stdlib'), ('org.jetbrains.kotlin','kotlin-script-runtime'),
                        ('org.jetbrains.kotlin','kotlin-reflect'), ('org.jetbrains.intellij.deps','trove4j'),
                        ('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm'), ('org.jetbrains','annotations')]:
    matches = sorted(CACHE.glob(f'{group}/{artifact}/*/*/*.jar'))
    if matches:
        jars.append(matches[-1])
classpath = os.pathsep.join(map(str, jars))
java = str(Path(os.environ.get('JAVA_HOME', '/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home')) / 'bin/java')
with tempfile.TemporaryDirectory() as directory:
    source = Path(directory) / 'Check.kt'
    source.write_text(code)
    output = Path(directory) / 'classes'
    subprocess.run([java, '-cp', classpath, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-classpath', classpath, '-d', str(output),
                    str(SRC / 'PersistentSchedule.kt'), str(source)], check=True)
    subprocess.run([java, '-cp', str(output)+os.pathsep+classpath,
                    'io.github.aoguai.sesameag.hook.keepalive.CheckKt'], check=True)
