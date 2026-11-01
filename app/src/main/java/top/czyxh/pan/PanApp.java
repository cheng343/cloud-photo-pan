package top.czyxh.pan;

import android.app.Application;
import com.google.android.material.color.DynamicColors;

/**
 * Application entry point with Google Material You (Monet) dynamic color theming enabled globally.
 */
public class PanApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // Enables Android 12+ wallpaper Monet dynamic coloring across all Activities and Dialogs
        DynamicColors.applyToActivitiesIfAvailable(this);
    }
}
