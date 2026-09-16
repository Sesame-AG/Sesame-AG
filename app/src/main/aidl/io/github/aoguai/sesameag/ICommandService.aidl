package io.github.aoguai.sesameag;

import io.github.aoguai.sesameag.ICallback;
import io.github.aoguai.sesameag.IStatusListener;

interface ICommandService {
    void executeCommand(String command, ICallback callback);
    void registerListener(IStatusListener listener);
    void unregisterListener(IStatusListener listener);
    boolean isExecutionAllowed(String userId);
    boolean scheduleWakeup(String scheduleId, long triggerAtMs, String precisionPolicy, long toleranceMs);
    void recoverScheduledTask(String scheduleId);
}

