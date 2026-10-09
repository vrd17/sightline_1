package com.sightline.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * BackgroundMonitor — bridges the WebView to the native camera monitor.
 * JS: Capacitor.Plugins.BackgroundMonitor
 *
 *   start({threshold, approachHold, safeBlink, blinkHold, refCm, notif})
 *                      — begin the foreground service in STANDBY (call while app visible)
 *   activate()         — app going to background → open camera & monitor
 *   standby()          — app returning → close camera (WebView resumes)
 *   getSummary()       — { breaches:[{ts,type}], samples:[{ts,cm,rate}], started, ended }
 *   stop()             — end the service
 *   isRunning()        — { running: boolean }
 *   requestBatteryExemption() — opens the "ignore battery optimizations" prompt (Samsung etc.)
 */
@CapacitorPlugin(name = "BackgroundMonitor")
public class BackgroundMonitorPlugin extends Plugin {

    private Intent svc() { return new Intent(getContext(), BackgroundMonitorService.class); }

    @PluginMethod
    public void start(PluginCall call) {
        try {
            Intent i = svc();
            i.putExtra("threshold", call.getDouble("threshold", 35.0));
            i.putExtra("approachHold", call.getDouble("approachHold", 5.0));
            i.putExtra("safeBlink", call.getDouble("safeBlink", 12.0));
            i.putExtra("blinkHold", call.getDouble("blinkHold", 15.0));
            i.putExtra("refCm", call.getDouble("refCm", 0.0));
            i.putExtra("calK", call.getDouble("calK", 0.0));
            i.putExtra("notif", call.getBoolean("notif", true));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) getContext().startForegroundService(i);
            else getContext().startService(i);
            call.resolve();
        } catch (Exception e) { call.reject("bg-start-failed: " + e.getMessage()); }
    }

    /** activate({...same config as start}) — called as the app is backgrounded.
     *  Updates the running service in place; restarting it from the background
     *  would be refused on Android 12+. */
    @PluginMethod
    public void activate(PluginCall call) {
        BackgroundMonitorService s = BackgroundMonitorService.INSTANCE;
        if (s != null) {
            if (call.hasOption("threshold")) {
                s.configure(call.getDouble("threshold", 35.0), call.getDouble("approachHold", 5.0),
                        call.getDouble("safeBlink", 12.0), call.getDouble("blinkHold", 15.0),
                        call.getDouble("refCm", 0.0), call.getDouble("calK", 0.0),
                        call.getBoolean("notif", true));
            }
            s.activate();
            call.resolve();
        } else {
            // No running service = no armed session; never start one from the background.
            call.reject("bg-not-running");
        }
    }

    @PluginMethod
    public void standby(PluginCall call) {
        BackgroundMonitorService s = BackgroundMonitorService.INSTANCE;
        if (s != null) s.standby();
        call.resolve();
    }

    @PluginMethod
    public void getSummary(PluginCall call) {
        BackgroundMonitorService s = BackgroundMonitorService.INSTANCE;
        JSObject ret = new JSObject();
        ret.put("summary", s != null ? s.getSummaryJson() : "{}");
        call.resolve(ret);
    }

    @PluginMethod
    public void stop(PluginCall call) {
        try {
            // Stop directly: startService() can be refused when the session ends
            // while the app is in the background (e.g. the session timer).
            BackgroundMonitorService s = BackgroundMonitorService.INSTANCE;
            if (s != null) s.stopNow();
            getContext().stopService(svc());
            call.resolve();
        } catch (Exception e) { call.reject("bg-stop-failed: " + e.getMessage()); }
    }

    @PluginMethod
    public void isRunning(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("running", BackgroundMonitorService.RUNNING);
        call.resolve(ret);
    }

    @PluginMethod
    public void requestBatteryExemption(PluginCall call) {
        try {
            String pkg = getContext().getPackageName();
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve();
        } catch (Exception e) { call.reject("battery-exemption-failed: " + e.getMessage()); }
    }

    /** { granted } — "Display over other apps", needed to show alerts on top of a fullscreen video. */
    @PluginMethod
    public void canDrawOverlays(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("granted", AlertOverlay.canDraw(getContext()));
        call.resolve(ret);
    }

    /** Opens the system "Display over other apps" page for Sightline. */
    @PluginMethod
    public void requestOverlayPermission(PluginCall call) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !AlertOverlay.canDraw(getContext())) {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getContext().getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(i);
            }
            call.resolve();
        } catch (Exception e) { call.reject("overlay-permission-failed: " + e.getMessage()); }
    }
}
