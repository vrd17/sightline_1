package com.sightline.app;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

/**
 * Replaces the generated MainActivity so the custom Foreground plugin is registered.
 * (The patch script copies this into the generated android/ project.)
 */
public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(ForegroundPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
