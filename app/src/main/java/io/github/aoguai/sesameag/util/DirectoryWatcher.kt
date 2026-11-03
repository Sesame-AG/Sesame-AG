package io.github.aoguai.sesameag.util

import android.os.FileObserver
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.File

object DirectoryWatcher {

    fun observeDirectoryChanges(directory: File): Flow<Unit> = callbackFlow {
        if (directory is io.github.aoguai.sesameag.storage.StoreFile) {
            val stop = io.github.aoguai.sesameag.storage.StoreAccess.observe { trySend(Unit) }
            awaitClose { stop() }
            return@callbackFlow
        }
        if (!directory.exists()) {
            directory.mkdirs()
        }

        val mask = FileObserver.CREATE or FileObserver.DELETE or FileObserver.MOVED_TO or FileObserver.MOVED_FROM

        val observer = object : FileObserver(directory, mask) {
            override fun onEvent(event: Int, path: String?) {
                if (path != null) trySend(Unit)
            }
        }

        observer.startWatching()

        awaitClose {
            observer.stopWatching()
        }
    }
}

