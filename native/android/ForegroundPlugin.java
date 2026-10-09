package com.sightline.app;

import android.content.Intent;
import android.os.Build;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Foreground — exposes start/stop of the Sightline foreground service to the
 * WebView. Accessible in JS as Capacitor.Plugins.Foreground.
 *
 *   Foreground.start({ title, text })  -> keeps the session alive in background
 *   Foreground.stop()                  -> removes the persistent notification
 */
@CapacitorPlugin(name = "Foreground")
public class ForegroundPlugin extends Plugin {

    @PluginMethod
    public void start(PluginCall call) {
        try {
            Intent i = new Intent(getContext(), SightlineForegroundService.class);
            i.putExtra("title", call.getString("title", "Sightline is guarding your eyes"));
            i.putExtra("text", call.getString("text", "Monitoring screen distance & blink rate"));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getContext().startForegroundService(i);
            } else {
                getContext().startService(i);
            }
            call.resolve();
        } catch (Exception e) {
            call.reject("foreground-start-failed: " + e.getMessage());
        }
    }

    @PluginMethod
    public void stop(PluginCall call) {
        try {
            getContext().stopService(new Intent(getContext(), SightlineForegroundService.class));
            call.resolve();
        } catch (Exception e) {
            call.reject("foreground-stop-failed: " + e.getMessage());
        }
    }
}
