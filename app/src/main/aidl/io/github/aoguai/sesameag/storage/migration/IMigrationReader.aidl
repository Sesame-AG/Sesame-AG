package io.github.aoguai.sesameag.storage.migration;

import android.os.ParcelFileDescriptor;

interface IMigrationReader {
    ParcelFileDescriptor execute(String command) = 0;
    void destroy() = 16777114;
}
