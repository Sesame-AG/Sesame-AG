package io.github.aoguai.sesameag;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;

// A session owns the module-side storage lock until close or client death.
interface IStoreTransaction {
    Bundle stat(int collection, String owner, String name);
    String[] list(int collection, String owner, String name);
    boolean ensureDirectory(int collection, String owner, String name);
    ParcelFileDescriptor read(int collection, String owner, String name);
    void write(int collection, String owner, String name, in ParcelFileDescriptor source);
    void append(int collection, String owner, String name, in ParcelFileDescriptor source);
    void truncate(int collection, String owner, String name, long length);
    boolean remove(int collection, String owner, String name);
    void close();
}
