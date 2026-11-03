package io.github.aoguai.sesameag;

import android.app.PendingIntent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import io.github.aoguai.sesameag.IStoreTransaction;
import io.github.aoguai.sesameag.IStoreListener;
import io.github.aoguai.sesameag.ICaptureSession;
import io.github.aoguai.sesameag.ICallback;
import io.github.aoguai.sesameag.IStatusListener;

interface ICommandService {
    IStoreTransaction beginStoreTransaction(IBinder client);
    boolean isStoreReady();
    void registerStoreListener(IStoreListener listener);
    void unregisterStoreListener(IStoreListener listener);
    ParcelFileDescriptor openLogStream(String channel, String processName);
    void executeCommand(String command, ICallback callback);
    void registerListener(IStatusListener listener);
    void unregisterListener(IStatusListener listener);
    boolean isExecutionAllowed(String userId);
    PendingIntent getPersistentScheduleAlarmIntent(int lane);
    boolean verifyRuntimeIdentity(int expectedModuleUid, String expectedModuleSourceDir);
    void setCaptureSessionProvider(ICaptureSession provider);
    Bundle getCaptureSession();
}

