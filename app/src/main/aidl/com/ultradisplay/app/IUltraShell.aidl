package com.ultradisplay.app;

import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.os.ParcelFileDescriptor;

/** Runs inside a Shizuku user-service process with shell (adb) identity. */
interface IUltraShell {
    /** Called by Shizuku when the service should exit. Transaction code is fixed by Shizuku. */
    void destroy() = 16777114;

    int createDisplay(in Surface surface, int width, int height, int dpi) = 1;
    void releaseDisplay() = 2;
    oneway void injectTouch(in MotionEvent event, int displayId) = 3;
    oneway void injectKey(int keyCode, int displayId) = 4;
    String exec(String command) = 5;
    int uid() = 6;
    /** Capture the whole audio output (REMOTE_SUBMIX); the phone's own speaker goes silent while active. */
    ParcelFileDescriptor startAudio(int sampleRate) = 7;
    void stopAudio() = 8;
    oneway void injectKeyEvent(in KeyEvent event, int displayId) = 9;
}
