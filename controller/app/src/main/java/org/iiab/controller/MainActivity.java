/*
 ============================================================================
 Name        : MainActivity.java
 Author      : hev <r@hev.cc>
 Contributors: IIAB Project
 Copyright   : Copyright (c) 2025 hev
 Copyright (c) 2026 IIAB Project
 Copyright   : Copyright (c) 2023 xyz
 Description : Main Activity
 ============================================================================
 */

package org.iiab.controller;

import android.Manifest;
import android.os.Bundle;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.app.AlertDialog;

import android.content.Intent;
import android.content.Context;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.res.ColorStateList;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.graphics.Color;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.net.VpnService;
import android.net.Uri;
import android.text.method.ScrollingMovementMethod;
import android.os.Build;
import android.os.Handler;
import android.os.PowerManager;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.provider.Settings;
import android.net.wifi.WifiManager;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import androidx.viewpager2.widget.ViewPager2;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.text.SimpleDateFormat;
import java.util.concurrent.Executor;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.Proxy;
import java.net.InetSocketAddress;

public class MainActivity extends AppCompatActivity implements View.OnClickListener {
    private static final String TAG = "IIAB-MainActivity";
    private static final String TERMUX_PERMISSION = "com.termux.permission.RUN_COMMAND";
    public Preferences prefs;
    private ImageButton themeToggle;
    private ImageButton btnSettings;
    private android.widget.ImageView headerIcon;
    private TextView badgeTermux;
    private TextView badgeController;
    private long updateDownloadId = -1;
    private long lastUpdateCheckTime = 0;

    // Tabs UI
    private TabLayout tabLayout;
    private ViewPager2 viewPager;
    private TextView versionFooter;
    public boolean isServerAlive = false;
    public boolean isNegotiating = false;
    public DashboardFragment.SystemState currentSystemState = DashboardFragment.SystemState.NONE;
    public boolean isProxyDegraded = false;
    public Boolean targetServerState = null;
    public String serverTransitionText = "";
    public UsageFragment usageFragment;

    public void setUsageFragment(UsageFragment fragment) {
        this.usageFragment = fragment;
    }

    private final Handler timeoutHandler = new Handler(android.os.Looper.getMainLooper());
    private Runnable timeoutRunnable;
    private boolean isWifiActive = false;
    private boolean isHotspotActive = false;
    private String currentTargetUrl = null;
    private long pulseStartTime = 0;

    private ActivityResultLauncher<Intent> vpnPermissionLauncher;
    private ActivityResultLauncher<String[]> requestPermissionsLauncher;
    private ActivityResultLauncher<Intent> batteryOptLauncher;

    public boolean isReadingLogs = false;
    private final Handler sizeUpdateHandler = new Handler();
    private Runnable sizeUpdateRunnable;

    // Variables for adaptive localhost server check
    private final Handler serverCheckHandler = new Handler(android.os.Looper.getMainLooper());
    private Runnable serverCheckRunnable;
    private static final int CHECK_INTERVAL_MS = 3000;

    private final BroadcastReceiver logReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (IIABWatchdog.ACTION_LOG_MESSAGE.equals(action)) {
                String message = intent.getStringExtra(IIABWatchdog.EXTRA_MESSAGE);
                addToLog(message);
                if (usageFragment != null) usageFragment.updateLogSizeUI();
            } else if (WatchdogService.ACTION_STATE_STARTED.equals(action)) {
                long elapsed = System.currentTimeMillis() - pulseStartTime;
                long fullCycle = 1200;

                // Find out how many milliseconds are left to finish the current wave
                long remainder = elapsed % fullCycle;
                long timeToNextCycleEnd = fullCycle - remainder;

                // If the remaining time is too fast (< 1 second), add one more full cycle
                // so the user actually has time to see the system notification drop down gracefully.
                if (timeToNextCycleEnd < 1000) {
                    timeToNextCycleEnd += fullCycle;
                }

                // Wait exactly until the wave hits 1.0f alpha, then lock it!
                new Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    if (usageFragment != null) usageFragment.finalizeEntryPulse();
                }, timeToNextCycleEnd);
            } else if (WatchdogService.ACTION_STATE_STOPPED.equals(action)) {
                // Service is down! Give it a 1.5 second visual margin, then stop the exit pulse.
                new Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    if (usageFragment != null) usageFragment.finalizeExitPulse();
                }, 1500);
            }
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Intercept launch and redirect to Setup Wizard if first time
        SharedPreferences internalPrefs = getSharedPreferences(getString(R.string.pref_file_internal), Context.MODE_PRIVATE);
        if (!internalPrefs.getBoolean(getString(R.string.pref_key_setup_complete), false)) {
            startActivity(new Intent(this, SetupActivity.class));
            finish();
            return;
        }

        prefs = new Preferences(this);
        setContentView(R.layout.main);

        // --- START TABS & VIEWPAGER ---
        tabLayout = findViewById(R.id.tab_layout);
        viewPager = findViewById(R.id.view_pager);

        MainPagerAdapter pagerAdapter = new MainPagerAdapter(this);
        viewPager.setAdapter(pagerAdapter);

        new TabLayoutMediator(tabLayout, viewPager, (tab, position) -> {
            switch (position) {
                case 0:
                    tab.setText(R.string.tab_status);
                    break;
                case 1:
                    tab.setText(R.string.tab_usage);
                    break;
                case 2:
                    tab.setText(R.string.tab_deploy);
                    break;
            }
        }).attach();
        versionFooter = findViewById(R.id.version_text);
        setVersionFooter();
        // Check for version with 10s cooldown span
        versionFooter.setOnClickListener(v -> {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastUpdateCheckTime < 10000) {
                Toast.makeText(this, R.string.ota_toast_cooldown, Toast.LENGTH_SHORT).show();
                return;
            }
            lastUpdateCheckTime = currentTime;
            checkForUpdates(true);
        });

        viewPager.setCurrentItem(0, false);

        // 1. Initialize Result Launchers
        vpnPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && prefs.getEnable()) {
                        connectVpn();
                    }
                    BatteryUtils.checkAndPromptOptimizations(MainActivity.this, batteryOptLauncher);
                }
        );

        batteryOptLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    Log.d(TAG, "Returned from the battery settings screen");
                    BatteryUtils.checkAndPromptOptimizations(MainActivity.this, batteryOptLauncher);
                }
        );

        requestPermissionsLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> {
                    for (Map.Entry<String, Boolean> entry : result.entrySet()) {
                        if (entry.getKey().equals(TERMUX_PERMISSION)) {
                            addToLog(getString(entry.getValue() ? R.string.termux_perm_granted : R.string.termux_perm_denied));
                        } else if (entry.getKey().equals(Manifest.permission.POST_NOTIFICATIONS)) {
                            addToLog(getString(entry.getValue() ? R.string.notif_perm_granted : R.string.notif_perm_denied));
                        }
                    }
                    prepareVpn();
                }
        );

        themeToggle = findViewById(R.id.theme_toggle);
        btnSettings = findViewById(R.id.btn_settings);
        headerIcon = findViewById(R.id.header_icon);
        badgeTermux = findViewById(R.id.badge_termux);
        badgeController = findViewById(R.id.badge_controller);
        ImageButton btnShareQr = findViewById(R.id.btn_share_qr);

        // Listeners
        themeToggle.setOnClickListener(v -> toggleTheme());
        btnSettings.setOnClickListener(v -> startActivity(new Intent(MainActivity.this, SetupActivity.class)));

        // --- QR Share Button Logic ---
        btnShareQr.setOnClickListener(v -> {
            if (!isServerAlive) {
                // Rule 1: Server must be running
                Snackbar.make(findViewById(android.R.id.content), R.string.qr_error_no_server, Snackbar.LENGTH_LONG).show();
                return;
            }
            if (!isWifiActive && !isHotspotActive) {
                // Rule 2: At least one network must be active
                Snackbar.make(findViewById(android.R.id.content), R.string.qr_error_no_network, Snackbar.LENGTH_LONG).show();
                return;
            }

            // Launch the new QrActivity
            startActivity(new Intent(MainActivity.this, QrActivity.class));
        });

        applySavedTheme();
        updateUI();

        addToLog(getString(R.string.app_started));
        checkForUpdates(false);

        sizeUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                if (usageFragment != null && usageFragment.isAdded())
                    usageFragment.updateLogSizeUI();
                sizeUpdateHandler.postDelayed(this, 10000);
            }
        };

        serverCheckRunnable = new Runnable() {
            @Override
            public void run() {
                checkServerStatus();
                updateConnectivityStatus(); // Check Wi-Fi & Hotspot states
                serverCheckHandler.postDelayed(this, CHECK_INTERVAL_MS);
            }
        };
        serverCheckHandler.post(serverCheckRunnable);
    }

    private void showBatterySnackbar() {
        View rootView = findViewById(android.R.id.content);
        Snackbar.make(rootView, R.string.battery_opt_denied, Snackbar.LENGTH_INDEFINITE)
                .setAction(R.string.fix_action, v -> BatteryUtils.checkAndPromptOptimizations(MainActivity.this, batteryOptLauncher))
                .show();
    }

    private void initiatePermissionChain() {
        List<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
        if (ContextCompat.checkSelfPermission(this, TERMUX_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(TERMUX_PERMISSION);
        }

        if (!permissions.isEmpty()) {
            requestPermissionsLauncher.launch(permissions.toArray(new String[0]));
        } else {
            prepareVpn();
        }
    }

    private boolean pingUrl(String urlStr, boolean useProxy) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn;

            if (useProxy) {
                // We routed the request directly to the app's SOCKS proxy
                int socksPort = prefs.getSocksPort(); // generally 1080
                Proxy proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", socksPort));
                conn = (HttpURLConnection) url.openConnection(proxy);
            } else {
                // Normal request (for localhost)
                conn = (HttpURLConnection) url.openConnection();
            }

            conn.setUseCaches(false);
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(1500);
            conn.setRequestMethod("GET");
            return (conn.getResponseCode() >= 200 && conn.getResponseCode() < 400);
        } catch (Exception e) {
            return false;
        }
    }

    private void runNegotiationSequence() {
        isNegotiating = true;
        runOnUiThread(() -> {
            updateUIColorsAndVisibility(); // We forced an immediate visual update
        });

        new Thread(() -> {
            boolean boxAlive = false;

            // Attempt 1 (0 seconds)
            boxAlive = pingUrl("http://box/home", true);

            // Attempt 2 (At 2 seconds)
            if (!boxAlive) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ignored) {
                }
                boxAlive = pingUrl("http://box/home", true);
            }

            // Attempt 3 (At 3 seconds)
            if (!boxAlive) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {
                }
                boxAlive = pingUrl("http://box/home", true);
            }

            // We validate if localhost serves as a fallback.
            boolean localAlive = pingUrl("http://localhost:8085/home", false);

            // We evaluate the results
            isNegotiating = false;
            isServerAlive = boxAlive || localAlive;

            // If VPN is ON but box/proxy is dead, the tunnel is degraded (Orange).
            if (prefs.getEnable()) {
                isProxyDegraded = !boxAlive;
            } else {
                isProxyDegraded = false;
            }

            if (boxAlive) {
                currentTargetUrl = "http://box/home";
            } else if (localAlive) {
                currentTargetUrl = "http://localhost:8085/home";
            } else {
                currentTargetUrl = null;
            }

            runOnUiThread(this::updateUIColorsAndVisibility);
        }).start();
    }

    private void prepareVpn() {
        Intent intent = VpnService.prepare(MainActivity.this);
        if (intent != null) {
            vpnPermissionLauncher.launch(intent);
        } else {
            if (prefs.getEnable()) connectVpn();
            BatteryUtils.checkAndPromptOptimizations(MainActivity.this, batteryOptLauncher);
        }
    }

    public void startLogSizeUpdates() {
        sizeUpdateHandler.removeCallbacks(sizeUpdateRunnable);
        sizeUpdateHandler.post(sizeUpdateRunnable);
    }

    public void stopLogSizeUpdates() {
        sizeUpdateHandler.removeCallbacks(sizeUpdateRunnable);
    }

    private void connectVpn() {
        Intent intent = new Intent(this, TProxyService.class);
        startService(intent.setAction(TProxyService.ACTION_CONNECT));
        addToLog(getString(R.string.vpn_permission_granted));
    }

    @Override
    protected void onPause() {
        super.onPause();

        try {
            unregisterReceiver(downloadReceiver);
        } catch (IllegalArgumentException e) {
            // Ignore if it wasn't registered
        }

        stopLogSizeUpdates();
        serverCheckHandler.removeCallbacks(serverCheckRunnable);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Register download listener
        IntentFilter filter = new IntentFilter(android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(downloadReceiver, filter);
        }
        //  Check permissions status
        updateHeaderIconsOpacity();
        updatePermissionBadges();
        // Check battery status whenever returning to the app
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Log.d(TAG, "onResume: Battery still optimized, showing warning");
                showBatterySnackbar();
            }
        }
        updateConnectivityStatus(); // Force instant UI refresh when returning to app

        if (getIntent() != null && getIntent().getBooleanExtra(VpnRecoveryReceiver.EXTRA_RECOVERY, false)) {
            addToLog(getString(R.string.recovery_pulse_received));
            Intent vpnIntent = new Intent(this, TProxyService.class);
            startService(vpnIntent.setAction(TProxyService.ACTION_CONNECT));
            setIntent(null);
        }
        if (usageFragment != null && usageFragment.isLogVisible()) {
            startLogSizeUpdates();
        }
        serverCheckHandler.removeCallbacks(serverCheckRunnable);
        serverCheckHandler.post(serverCheckRunnable);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    private void toggleTheme() {
        SharedPreferences sharedPref = getPreferences(Context.MODE_PRIVATE);
        int currentMode = AppCompatDelegate.getDefaultNightMode();
        int nextMode = (currentMode == AppCompatDelegate.MODE_NIGHT_NO) ? AppCompatDelegate.MODE_NIGHT_YES :
                (currentMode == AppCompatDelegate.MODE_NIGHT_YES) ? AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM : AppCompatDelegate.MODE_NIGHT_NO;
        sharedPref.edit().putInt("ui_mode", nextMode).apply();
        AppCompatDelegate.setDefaultNightMode(nextMode);
        updateThemeToggleButton(nextMode);
    }

    private void applySavedTheme() {
        SharedPreferences sharedPref = getPreferences(Context.MODE_PRIVATE);
        int savedMode = sharedPref.getInt("ui_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        AppCompatDelegate.setDefaultNightMode(savedMode);
        updateThemeToggleButton(savedMode);
    }

    private void updateThemeToggleButton(int mode) {
        if (mode == AppCompatDelegate.MODE_NIGHT_NO)
            themeToggle.setImageResource(R.drawable.ic_theme_dark);
        else if (mode == AppCompatDelegate.MODE_NIGHT_YES)
            themeToggle.setImageResource(R.drawable.ic_theme_light);
        else themeToggle.setImageResource(R.drawable.ic_theme_system);
    }


    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter();
        filter.addAction(IIABWatchdog.ACTION_LOG_MESSAGE);
        filter.addAction(WatchdogService.ACTION_STATE_STARTED);
        filter.addAction(WatchdogService.ACTION_STATE_STOPPED);

        ContextCompat.registerReceiver(this, logReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            unregisterReceiver(logReceiver);
        } catch (Exception e) {
        }
        stopLogSizeUpdates();
    }

    @Override
    public void onClick(View view) {
        // Delegated
    }

    public void handleWatchdogClick() {
        setWatchdogState(!prefs.getWatchdogEnable());
    }

    private void setWatchdogState(boolean enable) {
        prefs.setWatchdogEnable(enable);
        Intent intent = new Intent(this, WatchdogService.class);

        if (enable) {
            forceTermuxToForeground();
            intent.setAction(WatchdogService.ACTION_START);
            addToLog(getString(R.string.watchdog_started));
            if (isServerAlive && usageFragment != null) usageFragment.startFusionPulse();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } else {
            addToLog(getString(R.string.watchdog_stopped));
            if (usageFragment != null) usageFragment.startExitPulse();
            stopService(intent);
        }

        updateUI();
        updateUIColorsAndVisibility();
    }

    public void handleControlClick() {
        if (!isServerAlive) {
            Snackbar.make(findViewById(android.R.id.content), R.string.qr_error_no_server, Snackbar.LENGTH_LONG).show();
            return;
        }
        if (prefs.getEnable()) {
            BiometricHelper.prompt(this,
                    getString(R.string.auth_required_title),
                    getString(R.string.auth_required_subtitle),
                    () -> {
                        addToLog(getString(R.string.auth_success_disconnect));
                        toggleService(true);
                    });
        } else {
            if (BiometricHelper.isDeviceSecure(this)) {
                addToLog(getString(R.string.user_initiated_conn));
                toggleService(false);
            } else {
                BiometricHelper.showEnrollmentDialog(this);
            }
        }
    }

    public void handleBrowseContentClick(View v) {
        if (!isServerAlive) {
            Snackbar.make(v, R.string.qr_error_no_server, Snackbar.LENGTH_LONG).show();
            return;
        }
        if (currentTargetUrl != null) {
            Intent intent = new Intent(this, PortalActivity.class);
            intent.putExtra("TARGET_URL", currentTargetUrl);
            startActivity(intent);
        }
    }

    public void handleServerLaunchClick(View v) {
        // Set a hard timeout as a safety net
        timeoutRunnable = () -> {
            if (targetServerState != null) {
                targetServerState = null; // Abort transition
                if (usageFragment != null) runOnUiThread(() -> usageFragment.stopBtnProgress());
                updateUIColorsAndVisibility();
                addToLog(getString(R.string.server_timeout_warning));
            }
        };
        timeoutHandler.postDelayed(timeoutRunnable, getResources().getInteger(R.integer.server_cool_off_duration_ms));

        // Execute the corresponding script command
        if (!isServerAlive) {
            startTermuxEnvironmentVisible("--start");

            // Fallback for Oppo/Xiaomi
            new Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (targetServerState != null && !isServerAlive) {
                    Snackbar.make(v, R.string.termux_stuck_warning, Snackbar.LENGTH_LONG).show();
                }
            }, getResources().getInteger(R.integer.server_snackbar_delay_ms));

        } else {
            startTermuxEnvironmentVisible("--stop");

            // Turn off Watchdog gracefully when stopping the server manually
            if (prefs.getWatchdogEnable()) {
                setWatchdogState(false);
            }
        }
    }

    private void toggleService(boolean stop) {
        prefs.setEnable(!stop);
        savePrefs();
        Intent intent = new Intent(this, TProxyService.class);
        startService(intent.setAction(stop ? TProxyService.ACTION_DISCONNECT : TProxyService.ACTION_CONNECT));
        addToLog(getString(stop ? R.string.vpn_stopping : R.string.vpn_starting));

        if (!stop) {
            runNegotiationSequence();
        } else {
            updateUIColorsAndVisibility();
        }
    }

    public void updateUI() {
        if (usageFragment != null) {
            usageFragment.updateUI();
        }
    }

    private void checkServerStatus() {
        if (isNegotiating) return;

        new Thread(() -> {
            boolean localAlive = pingUrl("http://localhost:8085/home", false);
            boolean vpnOn = prefs.getEnable();
            boolean boxAlive = false;

            if (vpnOn) {
                // The passive radar must also use the proxy to test the tunnel.
                boxAlive = pingUrl("http://box/home", true);
                isProxyDegraded = !boxAlive;
            } else {
                isProxyDegraded = false;
            }

            isServerAlive = localAlive || boxAlive;

            // STATE MACHINE: Has the target state been reached?
            if (targetServerState != null && isServerAlive == targetServerState) {
                targetServerState = null; // Transition complete!
                timeoutHandler.removeCallbacks(timeoutRunnable); // Cancel safety net
                if (usageFragment != null) runOnUiThread(() -> usageFragment.stopBtnProgress());
            }

            if (vpnOn && boxAlive) {
                currentTargetUrl = "http://box/home";
            } else if (localAlive) {
                currentTargetUrl = "http://localhost:8085/home";
            } else {
                currentTargetUrl = null;
            }

            runOnUiThread(this::updateUIColorsAndVisibility);
        }).start();
    }

    public void updateUIColorsAndVisibility() {
        if (usageFragment != null) {
            usageFragment.updateUIColorsAndVisibility();
        }
    }

    private void startTermuxEnvironmentVisible(String actionFlag) {
        Intent intent = new Intent();
        intent.setClassName("com.termux", "com.termux.app.RunCommandService");
        intent.setAction("com.termux.RUN_COMMAND");

        intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home");
        intent.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/env");
        intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{
                "INTENT_MODE=headless",
                "/data/data/com.termux/files/usr/bin/bash",
                "/data/data/com.termux/files/usr/bin/iiab-termux",
                actionFlag
        });

        intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", false);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            addToLog(getString(R.string.sent_to_termux, actionFlag));
        } catch (Exception e) {
            addToLog(getString(R.string.failed_termux_intent, e.getMessage()));
        }
    }

    private void updateConnectivityStatus() {
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        boolean isWifiOn = wifiManager != null && wifiManager.isWifiEnabled();
        boolean isHotspotOn = false;

        try {
            // 1. Try standard reflection (Works on older Androids)
            java.lang.reflect.Method method = wifiManager.getClass().getDeclaredMethod("isWifiApEnabled");
            method.setAccessible(true);
            isHotspotOn = (Boolean) method.invoke(wifiManager);
        } catch (Throwable e) {
            // 2. Fallback for Android 10+: Check physical network interfaces
            try {
                java.util.Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
                while (interfaces != null && interfaces.hasMoreElements()) {
                    java.net.NetworkInterface iface = interfaces.nextElement();
                    String name = iface.getName();
                    if ((name.startsWith("ap") || name.startsWith("swlan")) && iface.isUp()) {
                        isHotspotOn = true;
                        break;
                    }
                }
            } catch (Exception ex) {
            }
        }

        // Store states for the QR button logic
        this.isWifiActive = isWifiOn;
        this.isHotspotActive = isHotspotOn;

        if (usageFragment != null) {
            runOnUiThread(() -> usageFragment.updateConnectivityLeds(this.isWifiActive, this.isHotspotActive));
        }
    }

    public void savePrefs() {
        if (usageFragment != null) {
            usageFragment.savePrefsFromUI();
        }
    }

    public void addToLog(String message) {
        if (usageFragment != null) {
            usageFragment.addToLog(message);
        }
    }

    private void setVersionFooter() {
        try {
            PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            String version = pInfo.versionName;

            String footerText = getString(R.string.version_footer_format, version);

            versionFooter.setText(footerText);
        } catch (PackageManager.NameNotFoundException e) {
            versionFooter.setText(getString(R.string.version_footer_fallback));
        }
    }

    private void forceTermuxToForeground() {
        try {
            Intent intent = getPackageManager().getLaunchIntentForPackage("com.termux");
            if (intent != null) {
                // Bring existing activity to the foreground
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(intent);
                addToLog(getString(R.string.force_termux_foreground));
            }
        } catch (Exception e) {
            addToLog(getString(R.string.termux_invocation_error, e.getMessage()));
        }
    }

    // --- PERMISSION CHECKERS FOR UI OPACITY ---

    private void updateHeaderIconsOpacity() {
        boolean hasAllControllerPerms = hasNotifPermission() && hasTermuxPermission() && hasBatteryPermission() && hasStoragePermission();
        boolean hasTermuxStorage = hasTermuxStoragePermission();

        // If any vital permission is missing, dim the icons to 40% opacity (0.4f)
        boolean allPerfect = hasAllControllerPerms && hasTermuxStorage;
        float targetAlpha = allPerfect ? 1.0f : 0.4f;

        if (btnSettings != null) btnSettings.setAlpha(targetAlpha);
        if (headerIcon != null) headerIcon.setAlpha(targetAlpha);
    }

    private boolean hasNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private boolean hasTermuxPermission() {
        return ContextCompat.checkSelfPermission(this, TERMUX_PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasBatteryPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        }
        return true;
    }

    private boolean hasTermuxStoragePermission() {
        try {
            int result = getPackageManager().checkPermission(Manifest.permission.READ_EXTERNAL_STORAGE, "com.termux");
            if (result == PackageManager.PERMISSION_GRANTED) return true;

            // Fallback: If Android denies the package query, check if the directory actually exists
            File stateDir = new File(android.os.Environment.getExternalStorageDirectory(), ".iiab_state");
            return stateDir.exists();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        } else {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void checkForUpdates(boolean isManual) {
        if (isManual) {
            runOnUiThread(() -> Toast.makeText(this, R.string.ota_toast_checking, Toast.LENGTH_SHORT).show());
        }

        new Thread(() -> {
            try {
                // Check update JSON data
                Log.d(TAG, "OTA: Connecting to https://iiab.switnet.org/android/apk/update.json");
                URL url = new URL("https://iiab.switnet.org/android/apk/update.json");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(5000);
                conn.setRequestMethod("GET");

                int responseCode = conn.getResponseCode();
                Log.d(TAG, "OTA: HTTP response code: " + responseCode);

                if (responseCode == 200) {
                    BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();

                    Log.d(TAG, "OTA: Downloaded JSON: " + response.toString());

                    org.json.JSONObject json = new org.json.JSONObject(response.toString());
                    int serverVersionCode = json.getInt("versionCode");
                    String serverVersionName = json.getString("versionName");
                    String apkName = json.getString("apkName");
                    String changelog = json.getString("changelog");

                    // Get current version
                    int currentVersionCode = BuildConfig.VERSION_CODE;
                    Log.d(TAG, "OTA: Server Version=" + serverVersionCode + " | Local Version=" + currentVersionCode);

                    // Check against current version
                    if (serverVersionCode > currentVersionCode) {
                        String downloadUrl = "https://iiab.switnet.org/android/apk/" + apkName;
                        runOnUiThread(() -> showUpdateDialog(serverVersionName, changelog, downloadUrl));
                    } else if (isManual) {
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, R.string.ota_toast_latest, Toast.LENGTH_LONG).show());
                    }
                } else if (isManual) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, getString(R.string.ota_toast_error_server, responseCode), Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                Log.e(TAG, "OTA: Critical error checking for updates", e);
                if (isManual) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, R.string.ota_toast_error_network, Toast.LENGTH_SHORT).show());
                }
            }
        }).start();
    }

    private void showUpdateDialog(String versionName, String changelog, String downloadUrl) {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.update_dialog_title, versionName))
                .setMessage(getString(R.string.update_dialog_message, changelog))
                .setPositiveButton(R.string.update_dialog_positive, (dialog, which) -> {
                    startDownload(downloadUrl);
                })
                .setNegativeButton(R.string.update_dialog_negative, null)
                .setCancelable(false)
                .show();
    }

    private void startDownload(String downloadUrl) {
        // Remove old files preventing overlap
        java.io.File oldApk = new java.io.File(
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                "iiab_update.apk"
        );
        if (oldApk.exists()) {
            oldApk.delete();
        }

        android.app.DownloadManager.Request request = new android.app.DownloadManager.Request(android.net.Uri.parse(downloadUrl));

        request.setTitle(getString(R.string.download_title));
        request.setDescription(getString(R.string.download_description));

        request.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);

        request.setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, "iiab_update.apk");

        android.app.DownloadManager manager = (android.app.DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager != null) {
            updateDownloadId = manager.enqueue(request);
            android.widget.Toast.makeText(this, R.string.download_started_toast, android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private final android.content.BroadcastReceiver downloadReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            long id = intent.getLongExtra(android.app.DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            if (id == updateDownloadId) {
                installApk();
            }
        }
    };

    private void installApk() {
        java.io.File apkFile = new java.io.File(
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                "iiab_update.apk"
        );

        if (apkFile.exists()) {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            android.net.Uri apkUri = androidx.core.content.FileProvider.getUriForFile(
                    this,
                    BuildConfig.APPLICATION_ID + ".provider",
                    apkFile
            );

            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            startActivity(intent);
        }
    }

    // --- PERMISSION BADGES LOGIC ---
    private void updatePermissionBadges() {
        // 1. Calculate Controller missing permissions (Purple Badge)
        int missingController = 0;
        if (!hasNotifPermission()) missingController++;
        if (!hasTermuxPermission()) missingController++;
        if (!hasStoragePermission()) missingController++;
        if (!hasBatteryPermission()) missingController++;
        if (VpnService.prepare(this) != null) missingController++; // VPN Permission check

        if (badgeController != null) {
            if (missingController > 0) {
                badgeController.setVisibility(View.VISIBLE);
                badgeController.setText(String.valueOf(missingController));
            } else {
                badgeController.setVisibility(View.GONE);
            }
        }

        // --- THE SIGNATURE VERIFIER ---
        // Let's check if Termux was uninstalled or reinstalled by looking at its unique install timestamp
        boolean isTermuxInstalled = false;
        long currentTermuxInstallTime = 0;
        try {
            android.content.pm.PackageInfo info = getPackageManager().getPackageInfo("com.termux", 0);
            isTermuxInstalled = true;
            currentTermuxInstallTime = info.firstInstallTime; // The definitive truth
        } catch (PackageManager.NameNotFoundException e) {
            isTermuxInstalled = false;
        }

        SharedPreferences internalPrefs = getSharedPreferences(getString(R.string.pref_file_internal), Context.MODE_PRIVATE);
        long savedTermuxSignature = internalPrefs.getLong("termux_install_signature", 0);

        // If Termux is missing, OR if the signature changed (meaning it was reinstalled), reset our UI flags!
        if (!isTermuxInstalled || (savedTermuxSignature != 0 && currentTermuxInstallTime != savedTermuxSignature)) {
            internalPrefs.edit()
                    .putBoolean("termux_tapped_overlay", false)
                    .putBoolean("termux_tapped_storage", false)
                    .putLong("termux_install_signature", isTermuxInstalled ? currentTermuxInstallTime : 0)
                    .apply();
        } else if (isTermuxInstalled && savedTermuxSignature == 0) {
            // First time tracking an existing installation
            internalPrefs.edit().putLong("termux_install_signature", currentTermuxInstallTime).apply();
        }

        // 2. Calculate Termux missing permissions (Blue Badge)
        int missingTermux = 0;

        // We read the flags (they will automatically return false if they were just wiped by the logic above)
        if (!internalPrefs.getBoolean("termux_tapped_overlay", false)) missingTermux++;
        if (!internalPrefs.getBoolean("termux_tapped_storage", false)) missingTermux++;

        if (badgeTermux != null) {
            if (missingTermux > 0) {
                badgeTermux.setVisibility(View.VISIBLE);
                badgeTermux.setText(String.valueOf(missingTermux));
            } else {
                badgeTermux.setVisibility(View.GONE);
            }
        }
    }
}
