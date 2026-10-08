#!/usr/bin/env python3
"""Compile and check the production launch-command builder using cached Kotlin jars.
Android constants are stubbed; device recovery is checked by check_wakeup.py.
"""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java/io/github/aoguai/sesameag/hook/keepalive'
CACHE = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle')) / 'caches/modules-2/files-2.1'

scheduler = (SRC / 'SystemWakeScheduler.kt').read_text()
command = 'val command = listOf(' + scheduler.split('val command = listOf(', 1)[1].split('return try {', 1)[0]
code = """package io.github.aoguai.sesameag.hook.keepalive
object Intent { const val ACTION_MAIN = "android.intent.action.MAIN"; const val FLAG_ACTIVITY_NEW_TASK = 268435456 }
data class Component(val value: String) { fun flattenToString() = value }
const val EXTRA_SCHEDULE_ID = "schedule_id"
const val EXTRA_PERSISTENT_ALARM_LAUNCH = "persistent_alarm_launch"
const val EXTRA_CONFIRMATION_AT = "persistent_confirmation_at"
fun launchCommand(component: Component, schedule: PersistentSchedule, userId: Int): Any {
""" + command + """
return command.joinToString(" ")
}
fun main() {
 val result = launchCommand(Component("com.eg.android.AlipayGphone/.AlipayLogin"), PersistentSchedule(id="abc-123"), 0)
 check(result is String && result.split(" ").first() == "am") { "Shizuku receives literal quotes in executable/arguments" }
 check(!result.contains("'"))
 check(runCatching { launchCommand(Component("pkg/.Main"), PersistentSchedule(id="bad;id"), 0) }.getOrNull() == false) { "Unsafe schedule id accepted" }
 println("PASS: Shizuku argv and unsafe-input rejection")
}
"""
compilers = sorted(CACHE.glob('org.jetbrains.kotlin/kotlin-compiler-embeddable/*/*/*.jar'))
assert compilers, 'Build the project once to populate the Gradle Kotlin compiler cache'
compiler = compilers[-1]
jars = [compiler]
for group, artifact in [('org.jetbrains.kotlin','kotlin-stdlib'), ('org.jetbrains.kotlin','kotlin-script-runtime'),
                        ('org.jetbrains.kotlin','kotlin-reflect'), ('org.jetbrains.intellij.deps','trove4j'),
                        ('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm'), ('org.jetbrains','annotations')]:
    matches = sorted(CACHE.glob(f'{group}/{artifact}/*/*/*.jar'))
    if matches:
        jars.append(matches[-1])
classpath = os.pathsep.join(map(str, jars))
java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else shutil.which('java')
assert java, 'Set JAVA_HOME to JDK 17 or put java on PATH'
with tempfile.TemporaryDirectory() as directory:
    source = Path(directory) / 'Check.kt'
    source.write_text(code)
    output = Path(directory) / 'classes'
    subprocess.run([java, '-cp', classpath, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-classpath', classpath, '-d', str(output),
                    str(SRC / 'PersistentSchedule.kt'), str(source)], check=True)
    subprocess.run([java, '-cp', str(output)+os.pathsep+classpath,
                    'io.github.aoguai.sesameag.hook.keepalive.CheckKt'], check=True)
