/*
 ============================================================================
 Name        : MainActivity.java
 Contributors: IIAB Project
 Copyright (c) 2026 IIAB Project
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

import android.content.Intent;
import android.content.Context;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.PowerManager;
import android.net.wifi.WifiManager;

import androidx.core.content.ContextCompat;

import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import androidx.viewpager2.widget.ViewPager2;

import android.view.MotionEvent;

import java.io.BufferedReader;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.Proxy;
import java.net.InetSocketAddress;

public class MainActivity extends AppCompatActivity implements View.OnClickListener {
    private static final String TAG = "IIAB-MainActivity";
    public Preferences prefs;
    private ImageButton themeToggle;
    private ImageButton btnSettings;
    private android.widget.ImageView headerIcon;

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
    public PRootEngine serverEngine;
    private float currentTerminalFontSize = 32f;

    // Load native C++ engine
    static {
        System.loadLibrary("termux");
    }

    /**
     * Dummy method to satisfy legacy fragments.
     * Since we are now a monolithic app with an embedded PRoot environment,
     * the host is always "installed".
     */
    public boolean isTermuxInstalled() {
        return true;
    }

    // New variables for the ninja terminal
    private com.termux.view.TerminalView terminalView;
    private com.termux.terminal.TerminalSession terminalSession;
    private com.google.android.material.bottomsheet.BottomSheetBehavior<View> bottomSheetBehavior;
    private boolean isTerminalLocked = true;
    // Multi-session architecture
    private List<com.termux.terminal.TerminalSession> terminalSessionsList = new ArrayList<>();
    private android.widget.ArrayAdapter<com.termux.terminal.TerminalSession> sessionsAdapter;

    public void invalidateModuleStateTrust() {
        getSharedPreferences("iiab_queue_prefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("is_module_state_trusted", false)
                .apply();
    }

    public boolean isModuleStateTrusted() {
        return getSharedPreferences("iiab_queue_prefs", Context.MODE_PRIVATE)
                .getBoolean("is_module_state_trusted", true);
    }

    private final BroadcastReceiver logReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (IIABWatchdog.ACTION_LOG_MESSAGE.equals(action)) {
                String message = intent.getStringExtra(IIABWatchdog.EXTRA_MESSAGE);
                addToLog(message);
                if (usageFragment != null) usageFragment.updateLogSizeUI();
            } else if (WatchdogService.ACTION_STATE_STARTED.equals(action)) {
                // Keep the visual sync pulse alive if the UI reloads while protected
                if (usageFragment != null) usageFragment.startFusionPulse();
            } else if (WatchdogService.ACTION_STATE_STOPPED.equals(action)) {
                // Service is down! Give it a visual margin, then stop the exit pulse.
                new Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    if (usageFragment != null) usageFragment.finalizeExitPulse();
                }, 1500);
            }
        }
    };
    // Listens for commands originating from the 'iiab' bash script in the host terminal
    private final BroadcastReceiver cliReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;

            switch (action) {
                case "org.iiab.ACTION_BAKE_IMAGE":
                    // Delegated to bash, we do nothing here
                    break;
                case "org.iiab.ACTION_BACKUP_ROOTFS":
                    addToLog(getString(R.string.log_cli_backup_triggered));
                    // triggerBackupProcess();
                    break;
                case "org.iiab.ACTION_RESTORE_ROOTFS":
                    addToLog(getString(R.string.log_cli_restore_triggered));
                    // triggerRestoreProcess();
                    break;
                case "org.iiab.ACTION_PREPARE_ROOTFS":
                    // The terminal requested a clean boot environment
                    File rootfsDir = new File(getFilesDir(), "rootfs/installed-rootfs/iiab");
                    createFakeSysData(rootfsDir);
                    break;
//                case "org.iiab.ACTION_UNLOCK_SDCARD":
//                    File prootTmp = new File(getFilesDir(), "proot_tmp");
//
//                    runOnUiThread(() -> {
//                        // 1. Ocultar físicamente la ventana de la terminal (BottomSheet)
//                        // Esto mata CUALQUIER intento nativo de Termux de robar el foco o el teclado.
//                        View bottomSheet = findViewById(R.id.terminal_bottom_sheet);
//                        if (bottomSheet != null && bottomSheetBehavior != null) {
//                            bottomSheetBehavior.setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_HIDDEN);
//                        }
//
//                        // 2. Por si acaso, quitar cualquier foco residual a nivel Java
//                        if (terminalView != null) {
//                            terminalView.setFocusable(false);
//                            terminalView.setFocusableInTouchMode(false);
//                            terminalView.clearFocus();
//                            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
//                            if (imm != null) imm.hideSoftInputFromWindow(terminalView.getWindowToken(), 0);
//                        }
//
//                        // Opcional: confirmación visual en Java
//                        Toast.makeText(MainActivity.this, "Biometric Requested by Shell", Toast.LENGTH_SHORT).show();
//
//                        // 3. Lanzar la huella (con la terminal ya fuera del camino, Android tendrá la pantalla limpia)
//                        BiometricHelper.prompt(MainActivity.this,
//                                getString(R.string.terminal_auth_title),
//                                getString(R.string.terminal_auth_subtitle),
//                                new BiometricHelper.AuthCallback() {
//                                    @Override
//                                    public void onSuccess() {
//                                        try {
//                                            new File(prootTmp, ".auth_success").createNewFile();
//                                            addToLog(getString(R.string.log_cli_sdcard_granted));
//                                        } catch (Exception ignored) {
//                                        } finally {
//                                            restoreTerminalView();
//                                        }
//                                    }
//
//                                    @Override
//                                    public void onFailed() {
//                                        try {
//                                            new File(prootTmp, ".auth_failed").createNewFile();
//                                            addToLog(getString(R.string.log_cli_sdcard_denied));
//                                        } catch (Exception ignored) {
//                                        } finally {
//                                            restoreTerminalView();
//                                        }
//                                    }
//
//                                    // Método auxiliar para regresar todo a la normalidad
//                                    private void restoreTerminalView() {
//                                        // A. Restaurar el comportamiento de foco
//                                        if (terminalView != null) {
//                                            terminalView.setFocusable(true);
//                                            terminalView.setFocusableInTouchMode(true);
//                                        }
//
//                                        // B. Volver a abrir el BottomSheet al 100% de la pantalla
//                                        if (bottomSheet != null && bottomSheetBehavior != null) {
//                                            // Fuerza la visibilidad por si acaso
//                                            if (bottomSheet.getVisibility() != View.VISIBLE) {
//                                                bottomSheet.setVisibility(View.VISIBLE);
//                                            }
//                                            bottomSheet.bringToFront();
//                                            bottomSheetBehavior.setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
//
//                                            // C. Devolver el foco a la terminal una vez que ya esté abierta
//                                            new Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
//                                                if (terminalView != null) terminalView.requestFocus();
//                                            }, 300); // Darle tiempo a la animación de expansión
//                                        }
//                                    }
//                                });
//                    });
//                    break;
            }
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Intercept launch and redirect to Setup Wizard if first time
        SharedPreferences internalPrefs = getSharedPreferences(getString(R.string.pref_file_internal), Context.MODE_PRIVATE);
        if (!internalPrefs.getBoolean(getString(R.string.pref_key_setup_complete), false)) {
            try {
                startActivity(new Intent(this, SetupActivity.class));
                finish();
                return; // We stop the execution of MainActivity right here
            } catch (android.content.ActivityNotFoundException e) {
                android.util.Log.w(TAG, "SetupActivity not found. Skipping initial setup.");
                internalPrefs.edit().putBoolean(getString(R.string.pref_key_setup_complete), true).apply();
            }
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
                case 3:
                    tab.setText(R.string.tab_share);
                    break;
            }
        }).attach();

        // --- TUTORIAL TAB DETECTOR  ---
        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                if (position == 3) { // El índice 3 es la pestaña "Share"
                    showShareTutorialIfNeeded();
                }
            }
        });

        // --- START: EASTER EGG & OTA LOGIC ---
        versionFooter = findViewById(R.id.version_text);
        setVersionFooter();

        // Configure hidden bottom sheet
        View bottomSheet = findViewById(R.id.terminal_bottom_sheet);
        terminalView = findViewById(R.id.terminal_view);
        bottomSheetBehavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(bottomSheet);
        bottomSheetBehavior.setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_HIDDEN);

        // MULTI-SESSION DRAWER SETUP
        // =========================================================
        androidx.drawerlayout.widget.DrawerLayout drawerLayout = findViewById(R.id.drawer_layout);
        android.widget.ListView drawerList = findViewById(R.id.left_drawer_list);
        android.widget.Button newSessionBtn = findViewById(R.id.new_session_button);

        // Custom Adapter to display session names (e.g. "Session 1", "Session 2")
        sessionsAdapter = new android.widget.ArrayAdapter<com.termux.terminal.TerminalSession>(
                this,
                android.R.layout.simple_list_item_1,
                terminalSessionsList
        ) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView text = view.findViewById(android.R.id.text1);
                text.setTextColor(android.graphics.Color.WHITE);
                text.setText("[" + (position + 1) + "]");
                return view;
            }
        };

        if (drawerList != null) {
            drawerList.setAdapter(sessionsAdapter);

            // Switch session when clicked
            drawerList.setOnItemClickListener((parent, view, position, id) -> {
                terminalSession = terminalSessionsList.get(position);
                if (terminalView != null) {
                    terminalView.attachSession(terminalSession);

                    // Termux style indicator
                    Toast toast = Toast.makeText(MainActivity.this, "[" + (position + 1) + "]", Toast.LENGTH_SHORT);
                    toast.setGravity(android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL, 0, 150);
                    toast.show();
                }
                if (drawerLayout != null) {
                    drawerLayout.closeDrawer(androidx.core.view.GravityCompat.START);
                }
            });
        }

        if (newSessionBtn != null) {
            newSessionBtn.setOnClickListener(btn -> {
                addNewTerminalSession(); // Spawn new shell
                if (terminalView != null) {
                    terminalView.attachSession(terminalSession); // Switch to the new one immediately

                    // Termux style indicator for the newly created session
                    int newSessionNumber = terminalSessionsList.size();
                    Toast toast = Toast.makeText(MainActivity.this, "[" + newSessionNumber + "]", Toast.LENGTH_SHORT);
                    toast.setGravity(android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL, 0, 150);
                    toast.show();
                }
                if (drawerLayout != null) {
                    drawerLayout.closeDrawer(androidx.core.view.GravityCompat.START);
                }
            });
        }

        // =========================================================
        // TERMINAL LOCK/UNLOCK LOGIC
        // =========================================================
        View terminalDragHandle = findViewById(R.id.terminal_drag_handle_area);
        if (terminalDragHandle != null) {
            terminalDragHandle.setOnLongClickListener(v -> {
                // Vibrate to provide tactile feedback
                vibrateDevice();

                // Toggle the lock state
                isTerminalLocked = !isTerminalLocked;

                // Lock means it CANNOT be dragged (scroll is safe). Unlock means it CAN be dragged.
                bottomSheetBehavior.setDraggable(!isTerminalLocked);

                // Notify the user
                int msgResId = isTerminalLocked ? R.string.terminal_locked : R.string.terminal_unlocked;
                Toast.makeText(MainActivity.this, msgResId, Toast.LENGTH_SHORT).show();

                return true; // Event consumed, prevents normal click processing
            });
        }

        // 3-second Ninja trigger & OTA Updater
        versionFooter.setOnTouchListener(new View.OnTouchListener() {
            private final Handler handler = new Handler(android.os.Looper.getMainLooper());
            private final Runnable longPressRunnable = () -> {
                try {
                    // 3 seconds reached! Expand terminal.
                    vibrateDevice();

                    // --- TERMINAL RESET NUKE ---
                    if (terminalSessionsList != null) {
                        for (com.termux.terminal.TerminalSession s : terminalSessionsList) {
                            s.finishIfRunning();
                        }
                        terminalSessionsList.clear();
                    }
                    terminalSession = null;

                    // Spawn the first session
                    addNewTerminalSession();

                    // 1. Force the view to be VISIBLE before expanding
                    View targetSheet = findViewById(R.id.terminal_bottom_sheet);
                    if (targetSheet.getVisibility() != View.VISIBLE) {
                        targetSheet.setVisibility(View.VISIBLE);
                    }

                    // 2. Bring view to front
                    targetSheet.bringToFront();
                    targetSheet.requestLayout();

                    // 3. Change to EXPANDED (100% screen)
                    bottomSheetBehavior.setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
                    // --- APPLY LOCK BY DEFAULT ---
                    isTerminalLocked = true;
                    bottomSheetBehavior.setDraggable(false);

                    terminalView.requestFocus();

                } catch (Throwable t) {
                    // CATCH ABSOLUTELY EVERYTHING (Even native JNI crashes)
                    new androidx.appcompat.app.AlertDialog.Builder(MainActivity.this)
                            .setTitle(R.string.terminal_crash_title)
                            .setMessage(android.util.Log.getStackTraceString(t))
                            .setPositiveButton("OK", null)
                            .show();
                }
            };
            private long touchStartTime;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        touchStartTime = System.currentTimeMillis();
                        handler.postDelayed(longPressRunnable, 3000);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        handler.removeCallbacks(longPressRunnable);

                        // --- THE UPDATER (OTA LOGIC) ---
                        // If released early and it was a quick tap (<500ms), trigger normal OTA update
                        if (System.currentTimeMillis() - touchStartTime < 500) {
                            long currentTime = System.currentTimeMillis();
                            if (currentTime - lastUpdateCheckTime < 10000) {
                                Toast.makeText(MainActivity.this, R.string.ota_toast_cooldown, Toast.LENGTH_SHORT).show();
                            } else {
                                lastUpdateCheckTime = currentTime;
                                checkForUpdates(true);
                            }
                        }
                        return true;
                }
                return false;
            }
        });

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
                        if (entry.getKey().equals(Manifest.permission.POST_NOTIFICATIONS)) {
                            addToLog(getString(entry.getValue() ? R.string.notif_perm_granted : R.string.notif_perm_denied));
                        }
                    }
                    prepareVpn();
                }
        );

        themeToggle = findViewById(R.id.theme_toggle);
        btnSettings = findViewById(R.id.btn_settings);
        headerIcon = findViewById(R.id.header_icon);
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

        // ADDED CAMERA PERMISSION REQUEST
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA);
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
        BatteryUtils.checkAndPromptOptimizations(MainActivity.this, batteryOptLauncher);
    }

    public void startLogSizeUpdates() {
        sizeUpdateHandler.removeCallbacks(sizeUpdateRunnable);
        sizeUpdateHandler.post(sizeUpdateRunnable);
    }

    public void stopLogSizeUpdates() {
        sizeUpdateHandler.removeCallbacks(sizeUpdateRunnable);
    }

    private void connectVpn() {
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

        // Check battery status whenever returning to the app
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Log.d(TAG, "onResume: Battery still optimized, showing warning");
                showBatterySnackbar();
            }
        }
        updateConnectivityStatus(); // Force instant UI refresh when returning to app

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

        IntentFilter cliFilter = new IntentFilter();
        cliFilter.addAction("org.iiab.ACTION_BAKE_IMAGE");
        cliFilter.addAction("org.iiab.ACTION_BACKUP_ROOTFS");
        cliFilter.addAction("org.iiab.ACTION_RESTORE_ROOTFS");
        cliFilter.addAction("org.iiab.ACTION_PREPARE_ROOTFS");
//        cliFilter.addAction("org.iiab.ACTION_UNLOCK_SDCARD");

        // cliReceiver MUST be exported to receive commands from the system's 'am' binary
        ContextCompat.registerReceiver(this, cliReceiver, cliFilter, ContextCompat.RECEIVER_EXPORTED);
        ContextCompat.registerReceiver(this, logReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            unregisterReceiver(logReceiver);
        } catch (Exception e) {
        }
        try {
            unregisterReceiver(cliReceiver);
        } catch (Exception e) {
        }
        stopLogSizeUpdates();
    }

    @Override
    public void onClick(View view) {
        // Delegated
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

    private void createFakeSysData(File rootfsDir) {
        try {
            File procDir = new File(rootfsDir, "proc");
            if (!procDir.exists()) procDir.mkdirs();

            // Calculate REAL device boot time and uptime using Android's native clocks
            long uptimeMillis = android.os.SystemClock.elapsedRealtime();
            long bootTimeSeconds = (System.currentTimeMillis() - uptimeMillis) / 1000;
            double uptimeSeconds = uptimeMillis / 1000.0;

            // 1. Fake Uptime (Real Android uptime frozen at the moment of launch)
            File uptimeFile = new File(procDir, ".uptime");
            if (uptimeFile.exists()) uptimeFile.delete(); // Always refresh
            java.io.FileOutputStream fosUp = new java.io.FileOutputStream(uptimeFile);
            fosUp.write(String.format(java.util.Locale.US, "%.2f %.2f\n", uptimeSeconds, uptimeSeconds).getBytes());
            fosUp.close();

            // 2. Fake Version (Kernel Info)
            File versionFile = new File(procDir, ".version");
            if (!versionFile.exists()) {
                java.io.FileOutputStream fosVer = new java.io.FileOutputStream(versionFile);
                fosVer.write("Linux version 6.17.0-PRoot-IIAB (builder@iiab) (Android NDK) #1 SMP PREEMPT Thu Apr 30 20:00:00 UTC 2026\n".getBytes());
                fosVer.close();
            }

            // 3. Fake Stat (Real btime injected dynamically)
            File statFile = new File(procDir, ".stat");
            if (statFile.exists()) statFile.delete(); // Always refresh
            java.io.FileOutputStream fosStat = new java.io.FileOutputStream(statFile);
            String statContent = "cpu  1000 0 1000 10000 0 0 0 0 0 0\n" +
                    "btime " + bootTimeSeconds + "\n";
            fosStat.write(statContent.getBytes());
            fosStat.close();

            // 4. Fake LoadAvg
            File loadavgFile = new File(procDir, ".loadavg");
            if (!loadavgFile.exists()) {
                java.io.FileOutputStream fosLoad = new java.io.FileOutputStream(loadavgFile);
                fosLoad.write("0.00 0.00 0.00 1/1 1\n".getBytes());
                fosLoad.close();
            }

        } catch (Exception e) {
            android.util.Log.e(TAG, "Failed to create dynamic fake sysdata", e);
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

        File rootfsDir = new File(getFilesDir(), "rootfs/installed-rootfs/iiab");

        if (!isServerAlive) {
            addToLog(getString(R.string.log_server_booting_native));
            // kernel & uptime
            createFakeSysData(rootfsDir);

            if (serverEngine != null) {
                serverEngine.killProcess();
            }
            serverEngine = new PRootEngine();

            // THE DOCKER TRICK: We start bash as login (-lc), start pdsm, and block the process with tail
            // so that PROoot never closes until we kill it.
            String startCmd = "/usr/bin/env PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin bash -lc '/usr/local/bin/pdsm start && tail -f /dev/null'";

            serverEngine.executeInContainer(this, rootfsDir.getAbsolutePath(), startCmd, new PRootEngine.OutputListener() {
                @Override
                public void onOutputLine(String line) {
                    runOnUiThread(() -> addToLog("[Server] " + line));
                }

                @Override
                public void onProcessExit(int exitCode) {
                    runOnUiThread(() -> addToLog(getString(R.string.log_server_engine_shutdown, exitCode)));
                }

                @Override
                public void onError(String error) {
                    runOnUiThread(() -> addToLog(getString(R.string.log_server_error, error)));
                }
            });
            // --- Watchdog injection / foreground service --- //
            prefs.setWatchdogEnable(true);
            enableSystemProtection();
            addToLog(getString(R.string.watchdog_started));
            if (usageFragment != null) usageFragment.startFusionPulse();

            // Fallback for Oppo/Xiaomi: Notify user if server fails to start
            new Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (targetServerState != null && !isServerAlive) {
                    Snackbar.make(v, R.string.termux_stuck_warning, Snackbar.LENGTH_LONG).show();
                }
            }, getResources().getInteger(R.integer.server_snackbar_delay_ms));

        } else {
            addToLog(getString(R.string.log_server_stopping_gracefully));

            PRootEngine stopEngine = new PRootEngine();

            stopEngine.executeInContainer(this, rootfsDir.getAbsolutePath(), "/usr/bin/env PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin bash -lc '/usr/local/bin/pdsm stop'", new PRootEngine.OutputListener() {
                @Override
                public void onOutputLine(String line) {
                    runOnUiThread(() -> addToLog("[PDSM Stop] " + line));
                }

                @Override
                public void onProcessExit(int exitCode) {
                    runOnUiThread(() -> {
                        // 1. Kill the engine instance
                        if (serverEngine != null) {
                            serverEngine.killProcess();
                            serverEngine = null;
                        }

                        // 2. Zombie Cleanup (Replicating bash pkill script)
                        new Thread(() -> {
                            try {
                                // Force kill any orphaned PRoot children
                                Runtime.getRuntime().exec(new String[]{"sh", "-c", "killall -9 proot 2>/dev/null"});
                            } catch (Exception ignored) {
                            }
                        }).start();

                        // 3. We turned off the Android Watchdog.
                        if (prefs.getWatchdogEnable()) {
                            prefs.setWatchdogEnable(false);
                            disableSystemProtection();
                            addToLog(getString(R.string.watchdog_stopped));
                            if (usageFragment != null) usageFragment.startExitPulse();
                        }
                    });
                }

                @Override
                public void onError(String error) {
                    runOnUiThread(() -> addToLog(getString(R.string.log_server_stop_error, error)));
                }
            });
        }
    }

    private void toggleService(boolean stop) {
        prefs.setEnable(!stop);
        savePrefs();
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

    public void startTermuxEnvironmentVisible(String actionFlag) {
        android.util.Log.d(TAG, "Legacy Headless command ignored: " + actionFlag);
    }

    // --- TERMUX HEADLESS BRIDGE ---
    public void executeTermuxCommandHeadless(String actionFlag) {
        android.util.Log.d(TAG, "Legacy Headless command ignored: " + actionFlag);
    }

    private void updateConnectivityStatus() {
        boolean isWifiOn = false;
        boolean isHotspotOn = false;
        WifiManager wifiManager = null; // Declare it outside so that it is accessible throughout the function

        try {
            wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            isWifiOn = wifiManager != null && wifiManager.isWifiEnabled();
        } catch (SecurityException e) {
            android.util.Log.w(TAG, "ACCESS_WIFI_STATE permission denied, ignoring Wi-Fi state");
        }

        try {
            // 1. Try standard reflection (Works on older Androids)
            if (wifiManager != null) {
                java.lang.reflect.Method method = wifiManager.getClass().getDeclaredMethod("isWifiApEnabled");
                method.setAccessible(true);
                isHotspotOn = (Boolean) method.invoke(wifiManager);
            }
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
                // Silently ignore
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
        if (usageFragment != null && usageFragment.isAdded()) {
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

    // --- PERMISSION CHECKERS FOR UI OPACITY ---

    private void updateHeaderIconsOpacity() {
        // Verify only the 4 native permissions required by our new monolithic architecture
        boolean hasAllPerms = hasNotifPermission() && hasBatteryPermission() && hasStoragePermission();

        float targetAlpha = hasAllPerms ? 1.0f : 0.4f;

        if (btnSettings != null) btnSettings.setAlpha(targetAlpha);
        if (headerIcon != null) headerIcon.setAlpha(targetAlpha);
    }

    private boolean hasNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private boolean hasBatteryPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        }
        return true;
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

                    org.json.JSONObject json = new org.json.JSONObject(response.toString());

                    // We read the versionCodeBase (Ex: 50)
                    int serverVersionCodeBase = json.getInt("versionCodeBase");
                    String serverVersionName = json.getString("versionName");
                    String changelog = json.getString("changelog");

                    // We get our local version and convert it to the base by dividing by 10
                    int currentVersionCode = 0;
                    try {
                        currentVersionCode = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
                    } catch (PackageManager.NameNotFoundException e) {
                        Log.e(TAG, "OTA: Could not get local version code", e);
                    }
                    int localVersionCodeBase = currentVersionCode / 10;

                    Log.d(TAG, "OTA: Server Base=" + serverVersionCodeBase + " | Local Base=" + localVersionCodeBase + " (Raw Local: " + currentVersionCode + ")");

                    // We compare the base versions
                    if (serverVersionCodeBase > localVersionCodeBase) {
                        // DETECT ARCHITECTURE TO DOWNLOAD THE CORRECT APK
                        String deviceArch = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "unknown";
                        String apkKey = "apk_universal"; // Fallback por defecto

                        if (deviceArch.contains("arm64") || deviceArch.contains("aarch64")) {
                            apkKey = "apk_arm64_v8a";
                        } else if (deviceArch.contains("armeabi") || deviceArch.contains("armv7")) {
                            apkKey = "apk_armeabi_v7a";
                        }

                        // If for some reason the JSON does not have that architecture, we use the universal
                        String apkName = json.optString(apkKey, json.optString("apk_universal"));

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
        // 1. We extract the actual file name from the end of the URL
        String apkName = android.net.Uri.parse(downloadUrl).getLastPathSegment();
        if (apkName == null || !apkName.endsWith(".apk")) {
            apkName = "iiab_update.apk"; // Fallback just in case
        }

        // 2. We save the name in memory so that it is not forgotten if the app closes
        getSharedPreferences(getString(R.string.pref_file_internal), Context.MODE_PRIVATE)
                .edit().putString("ota_apk_name", apkName).apply();

        // 3. We delete previous failed downloads with THIS same name
        java.io.File oldApk = new java.io.File(
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                apkName
        );
        if (oldApk.exists()) {
            oldApk.delete();
        }

        android.app.DownloadManager.Request request = new android.app.DownloadManager.Request(android.net.Uri.parse(downloadUrl));

        request.setTitle(getString(R.string.download_title));
        request.setDescription(getString(R.string.download_description));
        request.setMimeType("application/vnd.android.package-archive");
        request.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);

        // 4. We tell DownloadManager to use the dynamic name
        request.setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, apkName);

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
        String apkName = getSharedPreferences(getString(R.string.pref_file_internal), Context.MODE_PRIVATE)
                .getString("ota_apk_name", "iiab_update.apk");

        java.io.File apkFile = new java.io.File(
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                apkName
        );

        if (apkFile.exists()) {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            android.net.Uri apkUri = androidx.core.content.FileProvider.getUriForFile(
                    this,
                    getPackageName() + ".provider",
                    apkFile
            );

            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);

            List<android.content.pm.ResolveInfo> resInfoList = getPackageManager().queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
            for (android.content.pm.ResolveInfo resolveInfo : resInfoList) {
                String packageName = resolveInfo.activityInfo.packageName;
                grantUriPermission(packageName, apkUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }

            try {
                startActivity(intent);
            } catch (Exception e) {
                Log.e(TAG, "OTA: Error launching installer", e);
                Toast.makeText(this, R.string.ota_error_launching_installer, Toast.LENGTH_LONG).show();
            }
        } else {
            Log.e(TAG, "OTA: Downloaded APK file not found at " + apkFile.getAbsolutePath());
        }
    }

    private void addNewTerminalSession() {
        com.termux.terminal.TerminalSessionClient client = new com.termux.terminal.TerminalSessionClient() {
            @Override
            public void onTextChanged(com.termux.terminal.TerminalSession session) {
                runOnUiThread(() -> terminalView.invalidate());
            }

            @Override
            public void onTitleChanged(com.termux.terminal.TerminalSession session) {
            }

            // --- THE CLEAN SHUTDOWN (When Bash dies and tells us) ---
            @Override
            public void onSessionFinished(com.termux.terminal.TerminalSession session) {
                runOnUiThread(() -> {
                    // 1. Remove the dead session from our list and update the UI Drawer
                    if (terminalSessionsList != null) {
                        terminalSessionsList.remove(session);
                        if (sessionsAdapter != null) sessionsAdapter.notifyDataSetChanged();
                    }

                    // 2. Are we looking at the session that just died?
                    if (terminalSession == session) {
                        if (terminalSessionsList != null && !terminalSessionsList.isEmpty()) {
                            // Fallback: Jump to the last available active session
                            terminalSession = terminalSessionsList.get(terminalSessionsList.size() - 1);
                            if (terminalView != null) terminalView.attachSession(terminalSession);
                        } else {
                            // No sessions left! Close the terminal panel entirely.
                            terminalSession = null;
                            View bottomSheet = findViewById(R.id.terminal_bottom_sheet);
                            if (bottomSheet != null) {
                                com.google.android.material.bottomsheet.BottomSheetBehavior<?> behavior =
                                        com.google.android.material.bottomsheet.BottomSheetBehavior.from(bottomSheet);
                                behavior.setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_HIDDEN);
                            }
                        }
                    }
                });
            }

            @Override
            public void onCopyTextToClipboard(com.termux.terminal.TerminalSession session, String text) {
                android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                android.content.ClipData clip = android.content.ClipData.newPlainText("terminal", text);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(clip);
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, R.string.terminal_copied_toast, Toast.LENGTH_SHORT).show());
                }
            }

            @Override
            public void onPasteTextFromClipboard(com.termux.terminal.TerminalSession session) {
                android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null && clipboard.hasPrimaryClip() && clipboard.getPrimaryClip().getItemCount() > 0) {
                    CharSequence text = clipboard.getPrimaryClip().getItemAt(0).getText();
                    if (text != null && terminalSession != null) {
                        terminalSession.write(text.toString());
                    }
                }
            }

            @Override
            public void onBell(com.termux.terminal.TerminalSession session) {
            }

            @Override
            public void onColorsChanged(com.termux.terminal.TerminalSession session) {
            }

            @Override
            public void onTerminalCursorStateChange(boolean state) {
            }

            @Override
            public Integer getTerminalCursorStyle() {
                return 0;
            }

            @Override
            public void setTerminalShellPid(com.termux.terminal.TerminalSession session, int pid) {
            }

            @Override
            public void logError(String tag, String message) {
                Log.e(tag, message);
            }

            @Override
            public void logWarn(String tag, String message) {
                Log.w(tag, message);
            }

            @Override
            public void logInfo(String tag, String message) {
                Log.i(tag, message);
            }

            @Override
            public void logDebug(String tag, String message) {
                Log.d(tag, message);
            }

            @Override
            public void logVerbose(String tag, String message) {
                Log.v(tag, message);
            }

            @Override
            public void logStackTraceWithMessage(String tag, String message, Exception e) {
                Log.e(tag, message, e);
            }

            @Override
            public void logStackTrace(String tag, Exception e) {
                Log.e(tag, "Stack trace", e);
            }
        };

        try {
            // --- DUAL SYSTEM ARCHITECTURE: THE HOST SHELL ---
            // Instead of diving directly into a fragile PRoot guest environment,
            // we drop the expert user into the native Android Host Shell.
            // From here, they can debug, clear directories, or manually trigger PRoot.

            String hostShell = "/system/bin/sh";
            File workingDirectory = getFilesDir(); // Start in the app's secure root

            try {
                File hostBinDir = new File(getFilesDir(), "usr/bin");
                if (!hostBinDir.exists()) hostBinDir.mkdirs();

                // =========================================================
                // 0.5 EXTRACT BUNDLED CA-CERTIFICATES (The Browser Model)
                // =========================================================
                File caCertFile = new File(getFilesDir(), "cacert.pem");
                if (!caCertFile.exists()) {
                    try {
                        java.io.InputStream in = getAssets().open("cacert.pem");
                        java.io.FileOutputStream out = new java.io.FileOutputStream(caCertFile);
                        byte[] buffer = new byte[4096];
                        int bytesRead;
                        while ((bytesRead = in.read(buffer)) != -1) {
                            out.write(buffer, 0, bytesRead);
                        }
                        out.close();
                        in.close();
                        Log.i(TAG, "cacert.pem extracted successfully.");
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to extract bundled cacert.pem", e);
                    }
                }

                // =========================================================
                // LINK NINJA BINARIES (Expose .so files as normal commands)
                // =========================================================
                String nativeDir = getApplicationInfo().nativeLibraryDir;
                String[] ninjaBinaries = {"aria2c", "tar", "xz", "gzip", "rsync", "proot", "nano", "less"};

                for (String bin : ninjaBinaries) {
                    File soFile = new File(nativeDir, "lib" + bin + ".so");
                    File binFile = new File(hostBinDir, bin);

                    if (soFile.exists()) {
                        // We always delete the old link to avoid "Dangling Symlinks"
                        // caused by APK updates that change the path hash.
                        binFile.delete();

                        try {
                            android.system.Os.symlink(soFile.getAbsolutePath(), binFile.getAbsolutePath());
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to symlink " + bin, e);
                        }
                    } else {
                        // Shout out if the binary is not on disk!
                        Log.e(TAG, "CRITICAL: Native library missing! " + soFile.getAbsolutePath());
                        runOnUiThread(() -> addToLog(getString(R.string.log_critical_binary_missing, bin)));
                    }
                }

                // -------------------------------------------------------------
                // GENERATE ORCHESTRATOR CLI 'iiab' (Monolithic Tool)
                // -------------------------------------------------------------
                File rootfsDir = new File(getFilesDir(), "rootfs/installed-rootfs/iiab");
                File libproot = new File(getApplicationInfo().nativeLibraryDir, "libproot.so");
                File prootLoader = new File(getApplicationInfo().nativeLibraryDir, "libproot-loader.so");
                File prootLoader32 = new File(getApplicationInfo().nativeLibraryDir, "libproot-loader32.so");
                File tmpDir = new File(getFilesDir(), "proot_tmp");
                if (!tmpDir.exists()) tmpDir.mkdirs();

                File iiabCliScript = new File(hostBinDir, "iiab");
                StringBuilder cliStr = new StringBuilder();
                cliStr.append("#!/system/bin/sh\n\n");

                // Global script variables
                File backupsDir = new File(getFilesDir(), "rootfs/backups");
                cliStr.append("ROOTFS_DIR=\"").append(rootfsDir.getAbsolutePath()).append("\"\n");
                cliStr.append("BACKUPS_DIR=\"").append(backupsDir.getAbsolutePath()).append("\"\n");
                cliStr.append("export PROOT_TMP_DIR=\"").append(tmpDir.getAbsolutePath()).append("\"\n");
                cliStr.append("export PROOT_LOADER=\"").append(prootLoader.getAbsolutePath()).append("\"\n");
                cliStr.append("export PROOT_LOADER_32=\"").append(prootLoader32.getAbsolutePath()).append("\"\n\n");

                // Initialize Mount Flags
                cliStr.append("MOUNT_SDCARD=false\n");
                cliStr.append("MOUNT_BACKUPS=false\n\n");

                // Reusable login function (Purified with env PATH, explicit fake sysdata, and dynamic mounts)
                cliStr.append("do_login() {\n");
                cliStr.append("    echo -e '\\033[32mPreparing IIAB Debian Environment...\\033[0m'\n");

//                // --- SECURITY CHECK FOR SDCARD ---
//                cliStr.append("    if [ \"$MOUNT_SDCARD\" = true ]; then\n");
//                cliStr.append("        echo -e '\\033[33m[Security] Requesting biometric unlock for SD Card access...\\033[0m'\n");
//                cliStr.append("        # Clean previous flags\n");
//                cliStr.append("        rm -f \"$PROOT_TMP_DIR/.auth_success\" \"$PROOT_TMP_DIR/.auth_failed\"\n");
//                cliStr.append("        # Trigger UI Authentication\n");
//                cliStr.append("        am broadcast --user 0 -a org.iiab.ACTION_UNLOCK_SDCARD -p org.iiab.controller >/dev/null 2>&1\n");
//                cliStr.append("        \n");
//                cliStr.append("        # Wait for Java to write the result flag (Timeout after 30s)\n");
//                cliStr.append("        WAIT_TIME=0\n");
//                cliStr.append("        while [ ! -f \"$PROOT_TMP_DIR/.auth_success\" ] && [ ! -f \"$PROOT_TMP_DIR/.auth_failed\" ]; do\n");
//                cliStr.append("            sleep 1\n");
//                cliStr.append("            WAIT_TIME=$((WAIT_TIME + 1))\n");
//                cliStr.append("            if [ $WAIT_TIME -ge 30 ]; then\n");
//                cliStr.append("                echo -e '\\033[31m[Error] Authentication timed out.\\033[0m'\n");
//                cliStr.append("                exit 1\n");
//                cliStr.append("            fi\n");
//                cliStr.append("        done\n");
//                cliStr.append("        \n");
//                cliStr.append("        if [ -f \"$PROOT_TMP_DIR/.auth_failed\" ]; then\n");
//                cliStr.append("            echo -e '\\033[31m[Error] Authentication failed or cancelled. Access denied.\\033[0m'\n");
//                cliStr.append("            exit 1\n");
//                cliStr.append("        fi\n");
//                cliStr.append("        echo -e '\\033[32m[Success] SD Card access granted.\\033[0m'\n");
//                cliStr.append("    fi\n\n");

                // 1. Calculate native Android btime & uptime directly in Bash
                cliStr.append("    up_sec=$(awk '{print $1}' /proc/uptime 2>/dev/null || echo 1000)\n");
                cliStr.append("    now_sec=$(date +%s 2>/dev/null || echo 1716000000)\n");
                cliStr.append("    btime=$(awk -v up=\"$up_sec\" -v now=\"$now_sec\" 'BEGIN {printf \"%d\", now - up}')\n");

                // 2. Inject fresh data in-sync to bypass Android proc restrictions cleanly
                cliStr.append("    mkdir -p \"$ROOTFS_DIR/proc\" 2>/dev/null\n");
                cliStr.append("    echo \"$up_sec $up_sec\" > \"$ROOTFS_DIR/proc/.uptime\"\n");
                cliStr.append("    echo \"cpu  1000 0 1000 10000 0 0 0 0 0 0\" > \"$ROOTFS_DIR/proc/.stat\"\n");
                cliStr.append("    echo \"btime $btime\" >> \"$ROOTFS_DIR/proc/.stat\"\n");
                cliStr.append("    echo \"Linux version 6.17.0-PRoot-IIAB (builder@iiab) (Android NDK) #1 SMP PREEMPT Thu Apr 30 20:00:00 UTC 2026\" > \"$ROOTFS_DIR/proc/.version\"\n");
                cliStr.append("    echo \"0.00 0.00 0.00 1/1 1\" > \"$ROOTFS_DIR/proc/.loadavg\"\n");

                // 3. Build the PRoot Command dynamically based on flags
                cliStr.append("    PROOT_CMD=\"").append(libproot.getAbsolutePath()).append(" --sysvipc -0 --link2symlink -k 6.1.0 -r \\\"$ROOTFS_DIR\\\"\"\n");
                cliStr.append("    PROOT_CMD=\"$PROOT_CMD -b /dev -b /proc -b /sys\"\n");
                cliStr.append("    PROOT_CMD=\"$PROOT_CMD -b \\\"$ROOTFS_DIR/proc/.stat:/proc/stat\\\"\"\n");
                cliStr.append("    PROOT_CMD=\"$PROOT_CMD -b \\\"$ROOTFS_DIR/proc/.uptime:/proc/uptime\\\"\"\n");
                cliStr.append("    PROOT_CMD=\"$PROOT_CMD -b \\\"$ROOTFS_DIR/proc/.version:/proc/version\\\"\"\n");
                cliStr.append("    PROOT_CMD=\"$PROOT_CMD -b \\\"$ROOTFS_DIR/proc/.loadavg:/proc/loadavg\\\"\"\n");
                cliStr.append("    PROOT_CMD=\"$PROOT_CMD -b \\\"$PROOT_TMP_DIR\\\":/tmp\"\n");
                cliStr.append("    PROOT_CMD=\"$PROOT_CMD -b \\\"$PROOT_TMP_DIR\\\":/dev/shm\"\n");

                // Conditionally append risky mounts
                cliStr.append("    if [ \"$MOUNT_SDCARD\" = true ]; then\n");
                cliStr.append("        PROOT_CMD=\"$PROOT_CMD -b /storage/emulated/0:/sdcard\"\n");
                cliStr.append("    fi\n");
                cliStr.append("    if [ \"$MOUNT_BACKUPS\" = true ]; then\n");
                cliStr.append("        PROOT_CMD=\"$PROOT_CMD -b \\\"$BACKUPS_DIR:/backups\\\"\"\n");
                cliStr.append("    fi\n");

                cliStr.append("    PROOT_CMD=\"$PROOT_CMD -w /root /usr/bin/env PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin TERM=xterm-256color /bin/bash -l -i\"\n");

                // Execute!
                cliStr.append("    echo -e '\\033[32mEntering Jail...\\033[0m'\n");
                cliStr.append("    eval \"$PROOT_CMD\"\n");
                cliStr.append("}\n\n");

                // CLI arguments handling (Now loops to parse multiple flags)
                cliStr.append("ACTION=\"login\"\n");
                cliStr.append("while [ $# -gt 0 ]; do\n");
                cliStr.append("  case \"$1\" in\n");
                cliStr.append("    --reset)\n");
                cliStr.append("      ACTION=\"reset\"\n");
                cliStr.append("      shift\n");
                cliStr.append("      ;;\n");
                cliStr.append("    --backup-rootfs)\n");
                cliStr.append("      ACTION=\"backup\"\n");
                cliStr.append("      shift\n");
                cliStr.append("      ;;\n");
                cliStr.append("    --restore-rootfs)\n");
                cliStr.append("      ACTION=\"restore\"\n");
                cliStr.append("      shift\n");
                cliStr.append("      ;;\n");
                cliStr.append("    -l|--login)\n");
                cliStr.append("      ACTION=\"login\"\n");
                cliStr.append("      shift\n");
                cliStr.append("      ;;\n");
                cliStr.append("    --mount-sdcard)\n");
                cliStr.append("      MOUNT_SDCARD=true\n");
                cliStr.append("      shift\n");
                cliStr.append("      ;;\n");
                cliStr.append("    --mount-backups)\n");
                cliStr.append("      MOUNT_BACKUPS=true\n");
                cliStr.append("      shift\n");
                cliStr.append("      ;;\n");
                cliStr.append("    -h|--help)\n");
                cliStr.append("      echo 'IIAB Controller CLI'\n");
                cliStr.append("      echo 'Usage: iiab [COMMAND] [OPTIONS]'\n");
                cliStr.append("      echo 'Commands:'\n");
                cliStr.append("      echo '  -l, --login        Enter the IIAB Debian Environment (Default)'\n");
                cliStr.append("      echo '  --reset            Wipe system and reinstall Debian base'\n");
                cliStr.append("      echo '  --backup-rootfs    Trigger a system backup'\n");
                cliStr.append("      echo '  --restore-rootfs   Trigger a system restore'\n");
                cliStr.append("      echo 'Options for login:'\n");
                cliStr.append("      echo '  --mount-backups    Mount the app backups directory at /backups'\n");
                cliStr.append("      echo '  --mount-sdcard     Mount the Android SD Card at /sdcard (Requires Biometrics)'\n");
                cliStr.append("      exit 0\n");
                cliStr.append("      ;;\n");
                cliStr.append("    *)\n");
                cliStr.append("      # Unknown flag\n");
                cliStr.append("      shift\n");
                cliStr.append("      ;;\n");
                cliStr.append("  esac\n");
                cliStr.append("done\n\n");

                // Execute based on ACTION
                cliStr.append("if [ \"$ACTION\" = \"reset\" ]; then\n");
                cliStr.append("    echo -e '\\033[31m[WARNING] This will DESTROY the current IIAB Debian installation!\\033[0m'\n");
                cliStr.append("    echo -n 'Are you sure you want to reset the system? [y/N]: '\n");
                cliStr.append("    read ans\n");
                cliStr.append("    if [ \"$ans\" != \"y\" ] && [ \"$ans\" != \"Y\" ]; then echo 'Aborted.'; exit 0; fi\n\n");
                cliStr.append("    DL_DIR=\"/storage/emulated/0/Download\"\n");
                cliStr.append("    ARCH=$(uname -m)\n");
                cliStr.append("    if [ \"$ARCH\" = \"aarch64\" ]; then TERMUX_ARCH=\"aarch64\"; else TERMUX_ARCH=\"arm\"; fi\n");
                cliStr.append("    TARBALL=\"debian-trixie-${TERMUX_ARCH}-pd-v4.29.0.tar.xz\"\n");
                cliStr.append("    URL=\"https://iiab.switnet.org/android/rootfs/proot-distro-v4.29.0/${TARBALL}\"\n");
                cliStr.append("    CA_CERT=\"").append(caCertFile.getAbsolutePath()).append("\"\n\n");
                cliStr.append("    echo -e '\\n\\033[36m[1/4] Wiping current environment...\\033[0m'\n");
                cliStr.append("    rm -rf \"$ROOTFS_DIR\" 2>/dev/null || true\n");
                cliStr.append("    mkdir -p \"$ROOTFS_DIR\"\n\n");
                cliStr.append("    echo -e '\\033[36m[2/4] Downloading clean Debian base...\\033[0m'\n");
                cliStr.append("    if [ ! -f \"$DL_DIR/$TARBALL\" ]; then\n");
                cliStr.append("        aria2c --ca-certificate=\"$CA_CERT\" --dir=\"$DL_DIR\" --out=\"$TARBALL\" \"$URL\" || { echo -e '\\033[31mDownload failed!\\033[0m'; exit 1; }\n");
                cliStr.append("    else\n");
                cliStr.append("        echo 'Base tarball found in Downloads. Skipping download.'\n");
                cliStr.append("    fi\n\n");
                cliStr.append("    echo -e '\\033[36m[3/4] Extracting Debian (This may take a minute)...\\033[0m'\n");
                cliStr.append("    tar --exclude='*/dev/*' --strip-components=1 -xJf \"$DL_DIR/$TARBALL\" -C \"$ROOTFS_DIR\" || true\n\n");
                cliStr.append("    echo -e '\\033[36m[4/4] Bootstrapping IIAB environment via PRoot...\\033[0m'\n");
                cliStr.append("    rm -f \"$ROOTFS_DIR/etc/resolv.conf\" 2>/dev/null || true\n");
                cliStr.append("    echo 'nameserver 1.1.1.1' > \"$ROOTFS_DIR/etc/resolv.conf\"\n");
                cliStr.append("    echo 'nameserver 8.8.8.8' >> \"$ROOTFS_DIR/etc/resolv.conf\"\n");
                cliStr.append("    echo '127.0.0.1 localhost' > \"$ROOTFS_DIR/etc/hosts\"\n");
                cliStr.append("    ").append(libproot.getAbsolutePath()).append(" --sysvipc -0 --link2symlink -k 6.1.0 -r \"$ROOTFS_DIR\" \\\n");
                cliStr.append("      -b /dev -b /proc -b /sys -b /storage/emulated/0:/sdcard \\\n");
                cliStr.append("      -b \"$PROOT_TMP_DIR\":/tmp \\\n");
                cliStr.append("      -b \"$PROOT_TMP_DIR\":/dev/shm \\\n");
                cliStr.append("      -w /root /bin/bash -c '" +
                        "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin && " +
                        "export DEBIAN_FRONTEND=noninteractive && " +
                        "apt-get update && apt-get install -y curl ca-certificates nano sudo && " +
                        "curl -fsSL https://raw.githubusercontent.com/iiab/iiab-android/main/iiab-android -o /usr/local/sbin/iiab-android && " +
                        "chmod +x /usr/local/sbin/iiab-android && " +
                        "apt-get clean && apt-get autoremove -y && " +
                        "rm -rf /var/lib/apt/lists/* /tmp/* /root/.cache'\n\n");
                cliStr.append("    echo -e '\\n\\033[32m[SUCCESS] System is clean and ready!\\033[0m'\n");

                cliStr.append("elif [ \"$ACTION\" = \"backup\" ]; then\n");
                cliStr.append("    echo -e '\\033[33m[iiab]\\033[0m Triggering backup in UI...'\n");
                cliStr.append("    am broadcast -a org.iiab.ACTION_BACKUP_ROOTFS -p org.iiab.controller >/dev/null 2>&1\n");
                cliStr.append("elif [ \"$ACTION\" = \"restore\" ]; then\n");
                cliStr.append("    echo -e '\\033[33m[iiab]\\033[0m Triggering restore in UI...'\n");
                cliStr.append("    am broadcast -a org.iiab.ACTION_RESTORE_ROOTFS -p org.iiab.controller >/dev/null 2>&1\n");
                cliStr.append("else\n");
                cliStr.append("    if [ ! -f \"$ROOTFS_DIR/usr/bin/env\" ]; then\n");
                cliStr.append("        echo -e \"\\033[1;31m[ERROR]\\033[0m IIAB-Debian is not installed or rootfs is missing!\"\n");
                cliStr.append("        exit 1\n");
                cliStr.append("    fi\n");
                cliStr.append("    do_login\n");
                cliStr.append("fi\n");

                java.io.FileOutputStream fosCli = new java.io.FileOutputStream(iiabCliScript);
                fosCli.write(cliStr.toString().getBytes());
                fosCli.close();
                iiabCliScript.setExecutable(true);

            } catch (Exception e) {
                Log.e(TAG, "Failed to create host scripts", e);
            }
            // =========================================================
            // GENERATE MOTD (.profile)
            // =========================================================
            File profileFile = new File(workingDirectory, ".profile");
            try {
                java.io.FileOutputStream fosProfile = new java.io.FileOutputStream(profileFile);
                StringBuilder profile = new StringBuilder();

                // 1. Header (ASCII Art)
                profile.append("clear\n");
                profile.append("echo -e \"\\033[1;36m\"\n");
                profile.append("echo \"  ___ ___   _   ___             _    \"\n");
                profile.append("echo \" |_ _|_ _| /_\\ | _ )  ___ ___  /_\\   \"\n");
                profile.append("echo \"  | | | | / _ \\| _ \\ |___/ _ \\/ _ \\  \"\n");
                profile.append("echo \" |___|___/_/ \\_\\___/     \\___/_/ \\_\\ \"\n");
                profile.append("echo -e \"\\033[0m\"\n");
                profile.append("echo -e \"\\033[1;32m  C O N T R O L L E R   T E R M I N A L\\033[0m\\n\"\n");

                // 2. The Mission / Welcome
                profile.append("echo -e \"Welcome to the native \\033[1;36mIIAB on Android Host Shell\\033[0m.\"\n");
                profile.append("echo \"\"\n");
                profile.append("echo -e \"\\033[1mInternet-in-a-Box (IIAB) on Android\\033[0m will allow\"\n");
                profile.append("echo \"millions of people worldwide to build their own\"\n");
                profile.append("echo \"family libraries, inside their own phones!\"\n");
                profile.append("echo \"\"\n");
                profile.append("echo \"This terminal helps you build and transform\"\n");
                profile.append("echo \"your Android device as an Offline Learning\"\n");
                profile.append("echo \"Environment (Internet-in-a-Box).\"\n");
                profile.append("echo \"\"\n");

                // 3. Context & Warnings
                profile.append("echo -e \"\\033[1;33mNOTE:\\033[0m You are currently OUTSIDE the IIAB-Debian\"\n");
                profile.append("echo \"environment.\"\n");
                profile.append("echo \"Package managers (apt/pkg) are NOT available here.\"\n");
                profile.append("echo \"To install packages and manage the server, login\"\n");
                profile.append("echo \"to IIAB-Debian using the command below:\"\n");
                profile.append("echo \"\"\n");

                // 4. Helpful Commands
                profile.append("echo -e \"  \\033[1;32miiab --login\\033[0m  Login to IIAB-Debian PRoot (Default)\"\n");
                profile.append("echo -e \"  \\033[1;32miiab --help\\033[0m   Show orchestrator commands\"\n");
                profile.append("echo \"\"\n");

                // 5. Links and Resources
                profile.append("echo \"Online resources:\"\n");
                profile.append("echo -e \"\\033[1;33m*\\033[0m 🔗: \\033[1mhttps://internet-in-a-box.org\\033[0m\"\n");
                profile.append("echo -e \"\\033[1;33m*\\033[0m 📖: \\033[1mhttps://github.com/iiab/iiab-android\\033[0m\"\n");
                profile.append("echo -e \"\\033[1;33m*\\033[0m 🐛: \\033[1mhttps://github.com/iiab/iiab-android/issues\\033[0m\"\n");
                profile.append("echo \"\"\n");

                // 6. Custom Prompt (PS1)
                profile.append("export PS1=\"\\033[1;36m[Host]\\033[0m:~\\$ \"\n");

                fosProfile.write(profile.toString().getBytes());
                fosProfile.close();
            } catch (Exception e) {
                Log.e(TAG, "Failed to create .profile MOTD", e);
            }

            // =========================================================
            // GENERATE MKSHRC (To defeat Android's default prompt override)
            // =========================================================
            File mkshrcFile = new File(workingDirectory, ".mkshrc");
            try {
                java.io.FileOutputStream fosMkshrc = new java.io.FileOutputStream(mkshrcFile);
                StringBuilder mkshrc = new StringBuilder();
                // 1. Load system defaults first (crucial to keep backspace and history working)
                mkshrc.append("[ -f /system/etc/mkshrc ] && . /system/etc/mkshrc\n");
                // 2. Crush the system prompt with our custom one
                mkshrc.append("export PS1=\"~$ \"\n");
                fosMkshrc.write(mkshrc.toString().getBytes());
                fosMkshrc.close();
            } catch (Exception e) {
                Log.e(TAG, "Failed to create .mkshrc", e);
            }

            // Minimal, bulletproof environment variables
            String[] env = new String[]{
                    "TERM=xterm-256color",
                    "HOME=" + workingDirectory.getAbsolutePath(),
                    "ENV=" + mkshrcFile.getAbsolutePath(),
                    // Include the system bins and our W^X fake prefix bins
                    "PATH=/sbin:/system/sbin:/system/bin:/system/xbin:" + workingDirectory.getAbsolutePath() + "/usr/bin"
            };


            // Launch the native Android shell!
            terminalSession = new com.termux.terminal.TerminalSession(
                    hostShell,
                    workingDirectory.getAbsolutePath(),
                    new String[]{"-l"}, // Login shell flag
                    env,
                    5000, // <--- Careful to increase, all lines (per session) are stored in RAM
                    client
            );
            terminalView.setTextSize((int) currentTerminalFontSize);
            // Add to our multi-session list
            terminalSessionsList.add(terminalSession);
            if (sessionsAdapter != null) {
                runOnUiThread(() -> sessionsAdapter.notifyDataSetChanged());
            }

            // --- VIEW CLIENT (Touches, Zoom & Keyboard) ---
            terminalView.setTerminalViewClient(new com.termux.view.TerminalViewClient() {
                @Override
                public float onScale(float scale) {
                    currentTerminalFontSize *= scale;

                    if (currentTerminalFontSize < 10f) currentTerminalFontSize = 10f;
                    if (currentTerminalFontSize > 80f) currentTerminalFontSize = 80f;

                    terminalView.setTextSize((int) currentTerminalFontSize);
                    return 1.0f;
                }

                // --- THE ESCAPE TAP (Crucial for Debugging Limbo Sessions) ---
                @Override
                public void onSingleTapUp(android.view.MotionEvent e) {
                    // If the terminal process is dead (e.g., has crashed or shown "[Process completed]"),
                    // a single tap on the black screen hides the panel and nullifies the session.
                    // If the CURRENT terminal process is dead, close the panel and kill ALL sessions.
                    if (terminalSession != null && !terminalSession.isRunning()) {
                        runOnUiThread(() -> {
                            View bottomSheet = findViewById(R.id.terminal_bottom_sheet);
                            if (bottomSheet != null) {
                                com.google.android.material.bottomsheet.BottomSheetBehavior<?> behavior =
                                        com.google.android.material.bottomsheet.BottomSheetBehavior.from(bottomSheet);
                                behavior.setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_HIDDEN);
                            }

                            // The Guillotine: Kill all active sessions securely
                            if (terminalSessionsList != null) {
                                for (com.termux.terminal.TerminalSession s : terminalSessionsList) {
                                    s.finishIfRunning();
                                }
                                terminalSessionsList.clear();
                            }
                            terminalSession = null;
                            if (sessionsAdapter != null) sessionsAdapter.notifyDataSetChanged();
                        });
                        return; // Event consumed.
                    }

                    // --- NORMAL BEHAVIOR FOR ALIVE SESSIONS (Focus and Keyboard) ---
                    terminalView.setFocusable(true);
                    terminalView.setFocusableInTouchMode(true);
                    terminalView.requestFocus();

                    terminalView.post(() -> {
                        android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                        if (imm != null) imm.showSoftInput(terminalView, 0);
                    });
                }

                @Override
                public boolean onLongPress(android.view.MotionEvent e) {
                    return false;
                }

                @Override
                public boolean shouldBackButtonBeMappedToEscape() {
                    return false;
                }

                @Override
                public boolean shouldEnforceCharBasedInput() {
                    return false;
                }

                @Override
                public boolean shouldUseCtrlSpaceWorkaround() {
                    return false;
                }

                @Override
                public boolean isTerminalViewSelected() {
                    return true;
                }

                @Override
                public void copyModeChanged(boolean copyMode) {
                }

                // --- THE ESCAPE HATCH: Listen for hardware ENTER key on dead sessions ---
                @Override
                public boolean onKeyDown(int keyCode, android.view.KeyEvent e, com.termux.terminal.TerminalSession session) {
                    // Return false to let the terminal emulator handle keys internally.
                    // This allows onSessionFinished to trigger if keys are processed.
                    return false;
                }

                @Override
                public boolean onKeyUp(int keyCode, android.view.KeyEvent e) {
                    return false;
                }

                @Override
                public boolean readControlKey() {
                    com.termux.shared.termux.extrakeys.ExtraKeysView extraKeysView = findViewById(R.id.extra_keys_view);
                    if (extraKeysView != null) {
                        // Polling the CTRL state natively from the view!
                        Boolean state = extraKeysView.readSpecialButton(com.termux.shared.termux.extrakeys.SpecialButton.CTRL, true);
                        return state != null && state;
                    }
                    return false;
                }

                @Override
                public boolean readAltKey() {
                    com.termux.shared.termux.extrakeys.ExtraKeysView extraKeysView = findViewById(R.id.extra_keys_view);
                    if (extraKeysView != null) {
                        // Polling the ALT state natively from the view!
                        Boolean state = extraKeysView.readSpecialButton(com.termux.shared.termux.extrakeys.SpecialButton.ALT, true);
                        return state != null && state;
                    }
                    return false;
                }

                @Override
                public boolean readShiftKey() {
                    return false;
                }

                @Override
                public boolean readFnKey() {
                    return false;
                }

                @Override
                public boolean onCodePoint(int codePoint, boolean ctrlDown, com.termux.terminal.TerminalSession session) {
                    return false;
                }

                @Override
                public void onEmulatorSet() {
                }

                @Override
                public void logError(String tag, String message) {
                }

                @Override
                public void logWarn(String tag, String message) {
                }

                @Override
                public void logInfo(String tag, String message) {
                }

                @Override
                public void logDebug(String tag, String message) {
                }

                @Override
                public void logVerbose(String tag, String message) {
                }

                @Override
                public void logStackTraceWithMessage(String tag, String message, Exception e) {
                }

                @Override
                public void logStackTrace(String tag, Exception e) {
                }
            });
            // We wait for the view to be drawn before attaching the session to ensure the terminal is ready.
            terminalView.post(() -> {
                if (terminalSession != null) {
                    terminalView.attachSession(terminalSession);

                    // =========================================================
                    // IIAB NATIVE KEYBOARD INTEGRATION
                    // =========================================================
                    try {
                        com.termux.shared.termux.extrakeys.ExtraKeysView extraKeysView =
                                findViewById(R.id.extra_keys_view);

                        if (extraKeysView != null) {
                            extraKeysView.loadIIABDefaultKeys();

                            // Listen for normal keys (ESC, TAB, UP, etc)
                            extraKeysView.setExtraKeysViewClient(new com.termux.shared.termux.extrakeys.ExtraKeysView.IExtraKeysView() {
                                @Override
                                public void onExtraKeyButtonClick(View view, com.termux.shared.termux.extrakeys.ExtraKeyButton buttonInfo, com.google.android.material.button.MaterialButton button) {
                                    if (terminalSession != null) {
                                        String key = buttonInfo.getKey();
                                        switch (key) {
                                            case "ESC":
                                                terminalSession.write("\033");
                                                break;
                                            case "TAB":
                                                terminalSession.write("\t");
                                                break;
                                            case "UP":
                                                terminalSession.write("\033[A");
                                                break;
                                            case "DOWN":
                                                terminalSession.write("\033[B");
                                                break;
                                            case "RIGHT":
                                                terminalSession.write("\033[C");
                                                break;
                                            case "LEFT":
                                                terminalSession.write("\033[D");
                                                break;
                                            // --- NEW ORIGINAL TERMUX KEYS ---
                                            case "HOME":
                                                terminalSession.write("\033[1~");
                                                break;
                                            case "END":
                                                terminalSession.write("\033[4~");
                                                break;
                                            case "PGUP":
                                                terminalSession.write("\033[5~");
                                                break;
                                            case "PGDN":
                                                terminalSession.write("\033[6~");
                                                break;
                                            default:
                                                terminalSession.write(key);
                                                break;
                                        }
                                    }
                                }

                                @Override
                                public boolean performExtraKeyButtonHapticFeedback(View view, com.termux.shared.termux.extrakeys.ExtraKeyButton buttonInfo, com.google.android.material.button.MaterialButton button) {
                                    return false; // Let Termux handle the vibration natively
                                }
                            });
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to initialize Native ExtraKeys", e);
                    }
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "Failed to start Terminal Session", e);
        }
    }

    private void vibrateDevice() {
        android.os.Vibrator v = (android.os.Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null && v.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(android.os.VibrationEffect.createOneShot(50, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(50);
            }
        }
    }

    // WATCHDOG PROTECTION UTILS
    public void enableSystemProtection() {
        Intent intent = new Intent(this, WatchdogService.class);
        intent.setAction(WatchdogService.ACTION_START);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    public void disableSystemProtection() {
        Intent intent = new Intent(this, WatchdogService.class);
        intent.setAction(WatchdogService.ACTION_STOP);
        startService(intent);
    }

    // TUTORIAL OVERLAY LOGIC
    private void showShareTutorialIfNeeded() {
        SharedPreferences internalPrefs = getSharedPreferences(getString(R.string.pref_file_internal), Context.MODE_PRIVATE);
        boolean hideTutorial = internalPrefs.getBoolean("hide_share_tutorial", false);

        if (hideTutorial) return;

        android.view.ViewGroup root = findViewById(android.R.id.content);
        if (root.findViewById(R.id.tutorial_root) != null) return;

        View tutorialView = getLayoutInflater().inflate(R.layout.overlay_tutorial_share, root, false);
        root.addView(tutorialView);

        // --- Clone QR BUTTON ---
        ImageButton realBtn = findViewById(R.id.btn_share_qr);
        android.widget.ImageView fakeBtn = tutorialView.findViewById(R.id.fake_share_btn);
        View bubbleContainer = tutorialView.findViewById(R.id.bubble_light_container);

        if (realBtn != null && fakeBtn != null) {
            // We copy the icon you are currently using
            fakeBtn.setImageDrawable(realBtn.getDrawable());

            // We wait for the screen to finish drawing to obtain precise coordinates
            tutorialView.post(() -> {
                // 1. Obtain real coordinates by mitigating the Offset of the Status Bar
                int[] rootLoc = new int[2];
                tutorialView.getLocationInWindow(rootLoc);

                int[] btnLoc = new int[2];
                realBtn.getLocationInWindow(btnLoc);

                float exactX = btnLoc[0] - rootLoc[0];
                float exactY = btnLoc[1] - rootLoc[1];

                // 2. Position the Clone Button
                fakeBtn.setX(exactX);
                fakeBtn.setY(exactY);
                fakeBtn.getLayoutParams().width = realBtn.getWidth();
                fakeBtn.getLayoutParams().height = realBtn.getHeight();
                fakeBtn.requestLayout();

                // 3. Align the needle EXACTLY to the center of the cloned button
                View needle = tutorialView.findViewById(R.id.pointer_needle);
                float needleX = exactX + (realBtn.getWidth() / 2f) - (needle.getWidth() / 2f);
                float needleY = exactY + realBtn.getHeight() - (needle.getHeight() / 2f);
                needle.setX(needleX);
                needle.setY(needleY);

                // 4. Position the blue bubble to align with the needle
                bubbleContainer.setY(needleY + (needle.getHeight() / 2f) - 6);
            });
        }

        // Entry animation
        tutorialView.animate().alpha(1f).setDuration(400).start();

        android.widget.Button btnGotIt = tutorialView.findViewById(R.id.btn_got_it);
        android.widget.CheckBox chkDontShow = tutorialView.findViewById(R.id.chk_dont_show_again);

        btnGotIt.setOnClickListener(v -> {
            if (chkDontShow.isChecked()) {
                internalPrefs.edit().putBoolean("hide_share_tutorial", true).apply();
            }

            tutorialView.animate()
                    .alpha(0f)
                    .setDuration(300)
                    .withEndAction(() -> root.removeView(tutorialView))
                    .start();
        });
    }
}