package dev.encounter.foxhollow;

import android.app.Application;
import android.content.Context;
import android.util.Log;

/**
 * FoxhollowApplication - Application class for global initialization
 */
public class FoxhollowApplication extends Application {
    private static final String TAG = "FoxhollowApplication";
    private static Context applicationContext;

    @Override
    public void onCreate() {
        super.onCreate();
        applicationContext = this;
        
        // Initialize any global state here
        Log.i(TAG, "Foxhollow Application starting");
        
        // Ensure native libraries are loaded early
        // (Also done in FoxhollowActivity static block, but good to have here too)
    }

    public static Context getContext() {
        return applicationContext;
    }
}
