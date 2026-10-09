package com.sightline.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.BridgeActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * Replaces the generated MainActivity so the custom plugin is registered, and
 * requests the CAMERA + notifications permissions up front. Requesting CAMERA
 * natively (rather than relying on the WebView's getUserMedia to prompt) is what
 * makes the camera reliably start on Samsung tablets like the Tab S10 Lite.
 */
public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(BackgroundMonitorPlugin.class);
        super.onCreate(savedInstanceState);
        requestStartupPermissions();
    }

    private void requestStartupPermissions() {
        List<String> need = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.CAMERA);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!need.isEmpty())
            ActivityCompat.requestPermissions(this, need.toArray(new String[0]), 101);
    }
}
