/*
 * ============================================================================
 * Name        : DashboardManager.java
 * Author      : IIAB Project
 * Copyright   : Copyright (c) 2026 IIAB Project
 * Description : Initial dasboard status helper
 * ============================================================================
 */
package org.iiab.controller;

import android.app.Activity;
import android.content.Intent;
import android.provider.Settings;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

public class DashboardManager {

    private final Activity activity;
    private final LinearLayout dashboardContainer;

    private final View dashWifi, dashHotspot, dashTunnel;
    private final View ledWifi, ledHotspot, ledTunnel;
    private final View standaloneEspwButton;
    private final View standaloneEspwDescription;

    // Memory variables to avoid freezing the screen
    private boolean lastTunnelState = false;
    private boolean lastDegradedState = false;
    private boolean isFirstRun = true;

    public interface DashboardActionCallback {
        void onToggleEspwRequested();
    }

    public DashboardManager(Activity activity, View rootView, DashboardActionCallback callback) {
        this.activity = activity;

        // Bind all the views
        dashboardContainer = (LinearLayout) rootView.findViewById(R.id.dashboard_container);
        dashWifi = rootView.findViewById(R.id.dash_wifi);
        dashHotspot = rootView.findViewById(R.id.dash_hotspot);
        dashTunnel = rootView.findViewById(R.id.dash_tunnel);

        ledWifi = rootView.findViewById(R.id.led_wifi);
        ledHotspot = rootView.findViewById(R.id.led_hotspot);
        ledTunnel = rootView.findViewById(R.id.led_tunnel);

        standaloneEspwButton = rootView.findViewById(R.id.control);
        standaloneEspwDescription = rootView.findViewById(R.id.control_description);

        setupListeners(callback);
    }

    private void setupListeners(DashboardActionCallback callback) {
        // Single tap opens Settings directly
        dashWifi.setOnClickListener(v -> activity.startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)));

        dashHotspot.setOnClickListener(v -> {
            try {
                Intent intent = new Intent(Intent.ACTION_MAIN);
                intent.setClassName("com.android.settings", "com.android.settings.TetherSettings");
                activity.startActivity(intent);
            } catch (Exception e) {
                activity.startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
            }
        });

        // The Tunnel/ESPW toggle logic
        View.OnClickListener toggleEspw = v -> callback.onToggleEspwRequested();
        standaloneEspwButton.setOnClickListener(toggleEspw);
        dashTunnel.setOnClickListener(toggleEspw);
    }

    // Updates the LED graphics based on actual OS connectivity states
    public void updateConnectivityLeds(boolean isWifiOn, boolean isHotspotOn) {
        ledWifi.setBackgroundResource(isWifiOn ? R.drawable.led_on_green : R.drawable.led_off);
        ledHotspot.setBackgroundResource(isHotspotOn ? R.drawable.led_on_green : R.drawable.led_off);
    }

    // The Magic Morphing Animation!
    public void setTunnelState(boolean isTunnelActive, boolean isDegraded) {
        // ANTI-FREEZE SHIELD!
        // If the state is exactly the same as 3 seconds ago, abort to avoid blocking the UI
        if (!isFirstRun && lastTunnelState == isTunnelActive && lastDegradedState == isDegraded) {
            return;
        }
        isFirstRun = false;
        lastTunnelState = isTunnelActive;
        lastDegradedState = isDegraded;

        // Tells Android to smoothly animate any layout changes we make next
        TransitionManager.beginDelayedTransition((ViewGroup) dashboardContainer.getParent(), new AutoTransition().setDuration(300));

        if (isTunnelActive) {
            // Morph into 33% / 33% / 33% Dashboard mode
            standaloneEspwButton.setVisibility(View.GONE);
            standaloneEspwDescription.setVisibility(View.GONE);
            dashTunnel.setVisibility(View.VISIBLE);
            ledTunnel.setBackgroundResource(isDegraded ? R.drawable.led_on_orange : R.drawable.led_on_green);

            // Force recalculate
            dashboardContainer.setWeightSum(3f);
        } else {
            // Morph back into 50% / 50% mode
            dashTunnel.setVisibility(View.GONE);
            // TODO: [RESTORE] Uncomment to show ESPW button again
//            standaloneEspwButton.setVisibility(View.VISIBLE);
//            standaloneEspwDescription.setVisibility(View.VISIBLE);
            // The LED turns off implicitly since the whole dash_tunnel hides, but we can enforce it:
            ledTunnel.setBackgroundResource(R.drawable.led_off);
            // Force recalculate
            dashboardContainer.setWeightSum(2f);
        }
        // Force recalculate
        dashboardContainer.requestLayout();
    }
}
