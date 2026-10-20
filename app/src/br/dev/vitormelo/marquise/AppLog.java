package br.dev.vitormelo.marquise;

import android.util.Log;

/** The app's log (adb logcat -s Marquise). Never logs keys. */
final class AppLog {
    private AppLog() {}

    static void w(String message) {
        Log.i("Marquise", message);
    }
}
