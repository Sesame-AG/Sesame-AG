package io.github.aoguai.sesameag;

import android.os.Bundle;

// 主进程仅返回已确认且仍与宿主登录身份一致的会话。
interface ICaptureSession {
    Bundle getCurrentSession();
}
