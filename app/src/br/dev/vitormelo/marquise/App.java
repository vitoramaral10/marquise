package br.dev.vitormelo.marquise;

import android.app.Application;

/** Runs in each of the app's processes: gives AppLog the folder where it keeps the last problems. */
public final class App extends Application {
    @Override public void onCreate() {
        super.onCreate();
        AppLog.init(this);
        Prefs.load(this);
    }
}
