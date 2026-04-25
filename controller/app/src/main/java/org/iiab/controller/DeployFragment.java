/*
 * ============================================================================
 * Name        : DeployFragment.java
 * Author      : IIAB Project
 * Copyright   : Copyright (c) 2026 IIAB Project
 * Description : Installation / deployment view
 * ============================================================================
 */
package org.iiab.controller;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;

import androidx.core.app.NotificationCompat;
import androidx.core.app.RemoteInput;

import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.snackbar.Snackbar;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class DeployFragment extends Fragment {

    // --- UI Variables ---
    private View ledTermux;
    private View ledDevMode;
    private View ledDcpr;
    private View ledPpk;
    private TextView txtDcpr;
    private TextView txtPpk;
    private LinearLayout rolesContainer;
    private LinearLayout discrepancyWarning;
    private Button btnLaunchInstall;
    private Button btnFastInstall;
    private Button btnFastDelete;
    private Button btnAdvancedReset;
    private Button btnAdvancedBackup;
    private Button btnAdvancedRestore;
    private Button btnAdvancedForceStop;

    // Restore backups menu
    private TextView txtSelectBackupTitle;
    private LinearLayout containerBackupList;
    private TextView txtBackupStatus;
    private String selectedBackupFile = null;

    // --- State Management ---
    private final List<CheckBox> newInstallCheckboxes = new ArrayList<>();
    private File sharedStateDir;
    private JSONObject lastKnownState = new JSONObject();
    private TextView btnRefreshModules;
    private static final String TAG = "IIAB-DeployFragment";
    private List<String> installationQueue = new ArrayList<>();
    private boolean isBatchInstalling = false;
    private final Handler liveStatusHandler = new Handler(Looper.getMainLooper());
    private Runnable liveStatusRunnable;
    // -- Advance monitoring --
    private Button btnAdbAction;
    private View ledAdbStatus;
    private TextView txtAdbLedLabel;
    private com.github.mikephil.charting.charts.LineChart cpuChart;

    private boolean isConnectedToAdb = false;
    private boolean isScanning = false;
    private boolean isAttemptingFastConnect = false;
    private android.net.nsd.NsdManager nsdManager;
    private int discoveredConnectPort = -1;
    private int discoveredPairingPort = -1;
    private android.net.nsd.NsdManager.DiscoveryListener connectDiscoveryListener;
    private android.net.nsd.NsdManager.DiscoveryListener pairingDiscoveryListener;

    private static final String CHANNEL_ID = "adb_pairing_channel";
    private static final String SERVICE_TYPE_CONNECT = "_adb-tls-connect._tcp.";
    private static final String SERVICE_TYPE_PAIRING = "_adb-tls-pairing._tcp.";

    private final BroadcastReceiver adbUiUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if ("org.iiab.controller.ADB_PAIRING_SENT".equals(action)) {
                btnAdbAction.setText("Connected!");
                isConnectedToAdb = true;
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (isAdded()) updateUiState(true);
                }, 500);
            } else if ("org.iiab.controller.ADB_CPU_UPDATE".equals(action)) {
                String cpuData = intent.getStringExtra("cpu_line");
                if (isAdded() && isConnectedToAdb && cpuData != null) {
                    float cpuVal = parseCpuUsage(cpuData);
                    if (cpuVal >= 0f) {
                        addCpuEntry(cpuVal);
                    }
                }
            } else if ("org.iiab.controller.ADB_RESTRICTIONS_UPDATE".equals(action)) {
                if (!isAdded()) return;

                String cpValue = intent.getStringExtra("child_process_value");
                String rawPpkValue = intent.getStringExtra("ppk_value");

                String ppkDisplay = ("null".equals(rawPpkValue) || "unknown".equals(rawPpkValue)) ? getString(R.string.adb_ppk_default) : rawPpkValue;

                // Reset tints and set base grey background
                ledDcpr.setBackgroundTintList(null);
                ledPpk.setBackgroundTintList(null);
                ledPpk.setBackgroundResource(R.drawable.led_off);
                ledDcpr.setBackgroundResource(R.drawable.led_off);

                if (android.os.Build.VERSION.SDK_INT >= 34) {
                    // ==========================================
                    // --- ANDROID 14+ LOGIC ---
                    // ==========================================
                    txtPpk.setText(android.text.Html.fromHtml(getString(R.string.adb_ppk_limit_not_required, ppkDisplay), android.text.Html.FROM_HTML_MODE_COMPACT));

                    // PPK Color Logic (A14+)
                    if ("256".equals(rawPpkValue) || "512".equals(rawPpkValue) || "1024".equals(rawPpkValue)) {
                        ledPpk.setBackgroundResource(R.drawable.led_on_green); // GREEN (Fixed manually)
                    } else if ("error".equals(rawPpkValue) || rawPpkValue == null || rawPpkValue.isEmpty()) {
                        ledPpk.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#FFC107"))); // YELLOW (Timeout/Error)
                    } else {
                        ledPpk.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#2196F3"))); // BLUE (Queried, default value, not critical here)
                    }

                    // Child Process Color Logic (A14+)
                    if ("0".equals(cpValue) || "false".equals(cpValue)) {
                        ledDcpr.setBackgroundResource(R.drawable.led_on_green);
                        txtDcpr.setText(android.text.Html.fromHtml(getString(R.string.adb_cp_disabled_ok), android.text.Html.FROM_HTML_MODE_COMPACT));
                    } else if ("1".equals(cpValue) || "true".equals(cpValue) || "null".equals(cpValue) || cpValue == null) {
                        ledDcpr.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#F44336"))); // RED (Limiting)
                        txtDcpr.setText(android.text.Html.fromHtml(getString(R.string.adb_cp_enabled_limiting), android.text.Html.FROM_HTML_MODE_COMPACT));
                    } else {
                        txtDcpr.setText(android.text.Html.fromHtml(getString(R.string.adb_cp_unknown), android.text.Html.FROM_HTML_MODE_COMPACT));
                    }

                } else if (android.os.Build.VERSION.SDK_INT >= 31) {
                    // ==========================================
                    // --- ANDROID 12 & 13 LOGIC ---
                    // ==========================================
                    txtDcpr.setText(android.text.Html.fromHtml(getString(R.string.adb_cp_not_required), android.text.Html.FROM_HTML_MODE_COMPACT));

                    txtPpk.setText(android.text.Html.fromHtml(getString(R.string.adb_ppk_limit_active, ppkDisplay), android.text.Html.FROM_HTML_MODE_COMPACT));

                    // PPK Color Logic (A12/13)
                    if ("256".equals(rawPpkValue) || "512".equals(rawPpkValue) || "1024".equals(rawPpkValue)) {
                        ledPpk.setBackgroundResource(R.drawable.led_on_green); // GREEN (Fixed manually)
                    } else if ("error".equals(rawPpkValue) || rawPpkValue == null || rawPpkValue.isEmpty()) {
                        ledPpk.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#F44336"))); // RED (Timeout/Error - Critical here)
                    } else {
                        ledPpk.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#FFC107"))); // YELLOW (Queried, default value, needs attention)
                    }
                }
            }
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_deploy, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // 1. SINGLE VIEW INITIALIZATION
        ledTermux = view.findViewById(R.id.led_install_termux);
        ledDevMode = view.findViewById(R.id.led_install_dev_mode);
        ledDcpr = view.findViewById(R.id.led_install_dcpr);
        ledPpk = view.findViewById(R.id.led_install_ppk);
        txtDcpr = view.findViewById(R.id.txt_install_dcpr);
        txtPpk = view.findViewById(R.id.txt_install_ppk);

        btnAdbAction = view.findViewById(R.id.btn_adb_action);
        ledAdbStatus = view.findViewById(R.id.led_adb_status);
        txtAdbLedLabel = view.findViewById(R.id.txt_adb_led_label);
        cpuChart = view.findViewById(R.id.cpu_chart);
        nsdManager = (android.net.nsd.NsdManager) requireContext().getSystemService(Context.NSD_SERVICE);

        // 2. VERSION LOGIC (Hide on Android 10-)
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            view.findViewById(R.id.section_adv_monitoring).setVisibility(View.GONE);
            view.findViewById(R.id.container_led_dcpr).setVisibility(View.GONE);
            view.findViewById(R.id.container_led_ppk).setVisibility(View.GONE);
        } else {
            TextView txtAdvMonitoringTitle = view.findViewById(R.id.txt_adv_monitoring_title);
            LinearLayout containerAdvMonitoring = view.findViewById(R.id.container_adv_monitoring);
            setupSingleMenu(txtAdvMonitoringTitle, containerAdvMonitoring, R.string.install_adv_monitoring_title);
        }

        // 3. ASSIGN TEXT LISTENERS
        LinearLayout containerDcpr = view.findViewById(R.id.container_led_dcpr);
        containerDcpr.setOnClickListener(v -> {
            if (isConnectedToAdb && android.os.Build.VERSION.SDK_INT >= 34) {
                IIABAdbManager adbManager = IIABAdbManager.getInstance(requireContext());
                adbManager.executeCommand("settings put global settings_enable_monitor_phantom_procs 0");
                com.google.android.material.snackbar.Snackbar.make(v, R.string.adb_snack_disabling_cp, com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show();
                new Handler(Looper.getMainLooper()).postDelayed(() -> adbManager.checkSystemRestrictions(requireContext()), 1000);
            }
        });

        // Listener to set PPK to 256 (Android 12+)
        LinearLayout containerPpk = view.findViewById(R.id.container_led_ppk);
        containerPpk.setOnClickListener(v -> {
            if (isConnectedToAdb && android.os.Build.VERSION.SDK_INT >= 31) {
                IIABAdbManager adbManager = IIABAdbManager.getInstance(requireContext());
                adbManager.executeCommand("device_config put activity_manager max_phantom_processes 256");
                com.google.android.material.snackbar.Snackbar.make(v, R.string.adb_snack_setting_ppk, com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show();
                new Handler(Looper.getMainLooper()).postDelayed(() -> adbManager.checkSystemRestrictions(requireContext()), 1000);
            }
        });

        // 4. INITIALIZE CHART AND ADB BUTTON
        setupCpuChart();

        btnAdbAction.setOnClickListener(v -> {
            if (isConnectedToAdb) {
                new Thread(() -> {
                    try {
                        IIABAdbManager.getInstance(requireContext()).disconnect();
                    } catch (Exception ignored) {
                    }
                }).start();
                isConnectedToAdb = false;
                updateUiState(false);
            } else if (!isScanning) {
                startAdbPairingFlow();
            }
        });

        rolesContainer = view.findViewById(R.id.install_roles_container);
        discrepancyWarning = view.findViewById(R.id.install_discrepancy_warning);
        btnLaunchInstall = view.findViewById(R.id.btn_launch_install);

        // Link fast install / delete buttons
        btnFastInstall = view.findViewById(R.id.btn_fast_install);
        btnFastDelete = view.findViewById(R.id.btn_fast_delete);

        btnAdvancedReset = view.findViewById(R.id.btn_advanced_reset);
        btnAdvancedBackup = view.findViewById(R.id.btn_advanced_backup);
        btnAdvancedRestore = view.findViewById(R.id.btn_advanced_restore);
        btnAdvancedForceStop = view.findViewById(R.id.btn_advanced_force_stop);
        txtSelectBackupTitle = view.findViewById(R.id.txt_select_backup_title);
        containerBackupList = view.findViewById(R.id.container_backup_list);
        txtBackupStatus = view.findViewById(R.id.txt_backup_status);

        sharedStateDir = new File(Environment.getExternalStorageDirectory(), ".iiab_state");
        btnRefreshModules = view.findViewById(R.id.btn_refresh_modules);
        // Initial Button State
        btnLaunchInstall.setEnabled(false);
        btnLaunchInstall.setAlpha(0.5f);

        setupAllCollapsibleMenus();
        createModulesGrid();
        requestFreshLocalVarsSilently();

        // Define the heartbeat (checks the server in the background)
        liveStatusRunnable = () -> {
            new Thread(() -> {
                boolean isAlive = pingUrl("http://localhost:8085/home");
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).isServerAlive = isAlive;
                }
                if (isAdded() && getActivity() != null) {
                    getActivity().runOnUiThread(this::updateDynamicButtons);
                }
            }).start();
            // Check every 3 seconds
            liveStatusHandler.postDelayed(liveStatusRunnable, 3000);
        };
    }

    @Override
    public void onResume() {
        super.onResume();
        // Hide warning initially
        if (discrepancyWarning != null) discrepancyWarning.setVisibility(View.GONE);

        IntentFilter filter = new IntentFilter();
        filter.addAction("org.iiab.controller.ADB_PAIRING_SENT");
        filter.addAction("org.iiab.controller.ADB_CPU_UPDATE");
        filter.addAction("org.iiab.controller.ADB_RESTRICTIONS_UPDATE");
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(adbUiUpdateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            requireContext().registerReceiver(adbUiUpdateReceiver, filter);
        }

        // Restore the memory queue
        restoreQueueFromPrefs();

        if (isBatchInstalling) {
            new Handler(Looper.getMainLooper()).postDelayed(this::processNextInQueue, 500);
        }

        if (lastKnownState.length() > 0) {
            verifyInstallationState(lastKnownState);
        } else {

            File jsonFile = new File(sharedStateDir, "local_vars.json");
            if (jsonFile.exists() && jsonFile.length() > 0) {
                try {
                    StringBuilder text = new StringBuilder();
                    BufferedReader br = new BufferedReader(new FileReader(jsonFile));
                    String line;
                    while ((line = br.readLine()) != null) {
                        text.append(line);
                    }
                    br.close();
                    lastKnownState = new JSONObject(text.toString());
                    verifyInstallationState(lastKnownState);
                } catch (Exception e) {
                    android.util.Log.e(TAG, "Failed to read existing JSON in onResume", e);
                }
            }
        }
        updateDynamicButtons();
        liveStatusHandler.post(liveStatusRunnable);
    }

    @Override
    public void onPause() {
        super.onPause();
        try {
            requireContext().unregisterReceiver(adbUiUpdateReceiver);
        } catch (Exception ignored) {
        }
        liveStatusHandler.removeCallbacks(liveStatusRunnable);
    }

    private void setupAllCollapsibleMenus() {
        if (getView() == null) return;
        // 2. Modules
        TextView txtModuleMgmtTitle = getView().findViewById(R.id.txt_module_mgmt_title);
        LinearLayout containerModuleMgmt = getView().findViewById(R.id.container_module_mgmt);
        setupSingleMenu(txtModuleMgmtTitle, containerModuleMgmt, R.string.install_header_roles);

        // 3. Maintenance
        TextView txtMaintenanceTitle = getView().findViewById(R.id.txt_maintenance_title);
        LinearLayout containerMaintenance = getView().findViewById(R.id.container_maintenance);
        setupSingleMenu(txtMaintenanceTitle, containerMaintenance, R.string.install_header_maintenance);
    }

    private void setupSingleMenu(TextView titleView, View container, int stringRes) {
        if (titleView == null || container == null) return;

        container.setVisibility(View.GONE);

        String baseText = getString(stringRes);

        titleView.setText(getString(R.string.label_separator_up, baseText));

        titleView.setOnClickListener(v -> {
            boolean isCollapsed = container.getVisibility() == View.GONE;
            container.setVisibility(isCollapsed ? View.VISIBLE : View.GONE);

            if (isCollapsed) {
                titleView.setText(getString(R.string.label_separator_down, baseText));
            } else {
                titleView.setText(getString(R.string.label_separator_up, baseText));
            }
        });
    }

    private void createModulesGrid() {
        if (rolesContainer == null || getContext() == null) return;
        rolesContainer.removeAllViews();
        newInstallCheckboxes.clear();

        boolean isServerRunning = false;
        if (getActivity() instanceof MainActivity) {
            isServerRunning = ((MainActivity) getActivity()).isServerAlive;
        }

        String termuxArch = getTermuxArch();
        boolean is64Bit = termuxArch != null && termuxArch.contains("64");

        List<ModuleRegistry.IiabModule> activeModules = new ArrayList<>();
        for (ModuleRegistry.IiabModule module : ModuleRegistry.MASTER_ROSTER) {
            if (module.requires64Bit && !is64Bit) continue;
            activeModules.add(module);
        }

        int numCols = 3;
        int numRows = (int) Math.ceil((double) activeModules.size() / numCols);

        int ledSizePx = (int) (12 * getResources().getDisplayMetrics().density);

        for (int row = 0; row < numRows; row++) {
            LinearLayout rowLayout = new LinearLayout(requireContext());
            rowLayout.setOrientation(LinearLayout.HORIZONTAL);
            rowLayout.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            rowLayout.setBaselineAligned(false);
            rowLayout.setWeightSum(numCols);
            rowLayout.setPadding(0, 0, 0, 16);

            for (int col = 0; col < numCols; col++) {
                int index = (row * numCols) + col;

                LinearLayout cell = new LinearLayout(requireContext());
                LinearLayout.LayoutParams cellParams = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);

                int margin = 10;
                if (col == 0) cellParams.setMargins(0, 0, margin, 0);
                else if (col == 1) cellParams.setMargins(margin / 2, 0, margin / 2, 0);
                else cellParams.setMargins(margin, 0, 0, 0);

                cell.setLayoutParams(cellParams);

                if (index < activeModules.size()) {
                    ModuleRegistry.IiabModule currentMod = activeModules.get(index);

                    cell.setOrientation(LinearLayout.HORIZONTAL);
                    cell.setBackgroundResource(R.drawable.rounded_button);
                    cell.setBackgroundTintList(ColorStateList.valueOf(
                            ContextCompat.getColor(requireContext(), R.color.dash_module_bg)));
                    cell.setPadding(16, 28, 16, 28);
                    cell.setGravity(android.view.Gravity.CENTER);

                    int boxSizePx = (int) (24 * getResources().getDisplayMetrics().density);
                    android.widget.FrameLayout indicatorContainer = new android.widget.FrameLayout(requireContext());
                    LinearLayout.LayoutParams indParams = new LinearLayout.LayoutParams(boxSizePx, boxSizePx);
                    indicatorContainer.setLayoutParams(indParams);

                    View led = new View(requireContext());
                    android.widget.FrameLayout.LayoutParams ledParams = new android.widget.FrameLayout.LayoutParams(
                            ledSizePx, ledSizePx, android.view.Gravity.CENTER);
                    led.setLayoutParams(ledParams);
                    led.setBackgroundResource(R.drawable.led_off);

                    CheckBox checkBox = new CheckBox(requireContext());
                    checkBox.setScaleX(0.85f);
                    checkBox.setScaleY(0.85f);
                    checkBox.setPadding(0, 0, 0, 0);
                    android.widget.FrameLayout.LayoutParams cbParams = new android.widget.FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER);
                    checkBox.setLayoutParams(cbParams);
                    checkBox.setVisibility(View.GONE);

                    if (isServerRunning) {
                        checkBox.setEnabled(false);
                        cell.setAlpha(0.6f);
                    } else {
                        checkBox.setEnabled(true);
                        cell.setAlpha(1.0f);
                    }

                    indicatorContainer.addView(led);
                    indicatorContainer.addView(checkBox);

                    TextView name = new TextView(requireContext());
                    name.setText(getString(currentMod.nameResId));
                    name.setTextColor(ContextCompat.getColor(requireContext(), R.color.dash_text_primary));
                    name.setTextSize(12f);
                    name.setSingleLine(true);

                    LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    textParams.setMargins(16, 0, 0, 0);
                    name.setLayoutParams(textParams);

                    cell.addView(indicatorContainer);
                    cell.addView(name);

                    cell.setTag(currentMod);
                } else {
                    cell.setVisibility(View.INVISIBLE);
                }

                rowLayout.addView(cell);
            }
            rolesContainer.addView(rowLayout);
        }
    }

    // --- LOGIC: REQUEST FRESH JSON ---
    private void requestFreshLocalVars() {
        File jsonFile = new File(sharedStateDir, "local_vars.json");

        if (jsonFile.exists() && jsonFile.length() > 0) {
            try {
                StringBuilder text = new StringBuilder();
                BufferedReader br = new BufferedReader(new FileReader(jsonFile));
                String line;
                while ((line = br.readLine()) != null) {
                    text.append(line);
                }
                br.close();
                lastKnownState = new JSONObject(text.toString());
            } catch (Exception e) {
                android.util.Log.e(TAG, "Failed to cache old JSON", e);
            }
        }

        if (jsonFile.exists()) {
            jsonFile.delete();
        }

        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).executeTermuxCommandHeadless("--json-vars");
        }

        pollForJsonFile(jsonFile, 10, 1000); // 10 attempts, 1000ms each = 10 seconds
    }

    private void requestFreshLocalVarsSilently() {
        File jsonFile = new File(sharedStateDir, "local_vars.json");
        if (jsonFile.exists() && jsonFile.length() > 0) {
            try {
                StringBuilder text = new StringBuilder();
                BufferedReader br = new BufferedReader(new FileReader(jsonFile));
                String line;
                while ((line = br.readLine()) != null) text.append(line);
                br.close();
                lastKnownState = new JSONObject(text.toString());
                verifyInstallationState(lastKnownState);
            } catch (Exception e) {
                android.util.Log.e(TAG, "Failed to read existing JSON silently", e);
            }
        }
    }

    private void pollForJsonFile(File jsonFile, int attemptsLeft, int delayMs) {
        if (attemptsLeft <= 0) {
            android.util.Log.w(TAG, "TIMEOUT: local_vars.json never appeared. Using cached state.");
            verifyInstallationState(lastKnownState);
            return;
        }

        if (jsonFile.exists() && jsonFile.length() > 0) {
            android.util.Log.d(TAG, "SUCCESS: Found fresh local_vars.json!");
            try {
                StringBuilder text = new StringBuilder();
                BufferedReader br = new BufferedReader(new FileReader(jsonFile));
                String line;
                while ((line = br.readLine()) != null) {
                    text.append(line);
                }
                br.close();

                JSONObject freshVars = new JSONObject(text.toString());
                lastKnownState = freshVars;
                if (getActivity() instanceof MainActivity) {
                    getActivity().getSharedPreferences("iiab_queue_prefs", Context.MODE_PRIVATE)
                            .edit()
                            .putBoolean("is_module_state_trusted", true)
                            .apply();
                }
                verifyInstallationState(freshVars);
                return;
            } catch (Exception e) {
                android.util.Log.e(TAG, "Failed to parse fresh JSON", e);
                verifyInstallationState(lastKnownState);
                return;
            }
        }

        // KEEP WAITING
        new Handler(android.os.Looper.getMainLooper()).postDelayed(() ->
                pollForJsonFile(jsonFile, attemptsLeft - 1, delayMs), delayMs);
    }

    // --- LOGIC: VERIFY AND COLORIZE ---
    private void verifyInstallationState(JSONObject jsonVars) {
        new Thread(() -> {
            if (!isAdded() || getActivity() == null || rolesContainer == null) return;

            boolean isMainServerAlive = pingUrl("http://localhost:8085/home");
            boolean discrepancyFound = false;

            for (int r = 0; r < rolesContainer.getChildCount(); r++) {
                LinearLayout row = (LinearLayout) rolesContainer.getChildAt(r);

                for (int c = 0; c < row.getChildCount(); c++) {
                    LinearLayout card = (LinearLayout) row.getChildAt(c);
                    ModuleRegistry.IiabModule module = (ModuleRegistry.IiabModule) card.getTag();
                    if (module == null) continue;

                    android.widget.FrameLayout indicatorContainer = (android.widget.FrameLayout) card.getChildAt(0);
                    View led = indicatorContainer.getChildAt(0);
                    CheckBox checkBox = (CheckBox) indicatorContainer.getChildAt(1);

                    boolean isInstallTrue = jsonVars.optBoolean(module.yamlBaseKey + "_install", false);
                    boolean isEnabledTrue = jsonVars.optBoolean(module.yamlBaseKey + "_enabled", false);
                    boolean yamlState = isInstallTrue || isEnabledTrue;

                    boolean pingState = isMainServerAlive && pingUrl("http://localhost:8085/" + module.endpoint);

                    // Get global state before entering UI thread
                    MainActivity mainAct = (MainActivity) getActivity();
                    boolean isRunning = mainAct != null && mainAct.isServerAlive;
                    boolean isTrusted = mainAct != null && mainAct.isModuleStateTrusted();

                    // LOGIC FIX: Separate rules for On/Off states
                    boolean isConfirmedInstalled;
                    boolean isDiscrepancy;

                    if (isRunning) {
                        // Server ON: YAML and Ping must match perfectly
                        isConfirmedInstalled = yamlState && pingState;
                        isDiscrepancy = yamlState != pingState;
                    } else {
                        // Server OFF: Pings will fail. Trust YAML and memory state.
                        isConfirmedInstalled = yamlState;
                        isDiscrepancy = yamlState && !isTrusted;
                    }

                    // Freeze variables to pass them to the UI thread
                    final boolean finalConfirmed = isConfirmedInstalled;
                    final boolean finalDiscrepancyFlag = isDiscrepancy;
                    final boolean finalIsRunning = isRunning;

                    getActivity().runOnUiThread(() -> {
                        card.setOnClickListener(null);
                        checkBox.setOnCheckedChangeListener(null);

                        if (finalConfirmed && !finalDiscrepancyFlag) {
                            // RULE 1: Installed and Trusted
                            checkBox.setVisibility(View.GONE);
                            led.setVisibility(View.VISIBLE);
                            led.setBackgroundTintList(null);

                            if (finalIsRunning) {
                                // Live absolute certainty (GREEN)
                                led.setBackgroundResource(R.drawable.led_on_green);
                                card.setOnClickListener(v -> Snackbar.make(v, R.string.install_msg_confirmed, Snackbar.LENGTH_LONG).show());
                            } else {
                                // Offline but trusted (PURPLE)
                                led.setBackgroundResource(R.drawable.led_on_green); // Use green as base shape...
                                led.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#9C27B0"))); // ... and tint it purple
                                card.setOnClickListener(v -> Snackbar.make(v, R.string.install_msg_offline_trusted, Snackbar.LENGTH_LONG).show());
                            }
                        } else if (finalDiscrepancyFlag) {
                            // RULE 2: Real discrepancy or untrusted memory (YELLOW/ORANGE)
                            checkBox.setVisibility(View.GONE);
                            led.setVisibility(View.VISIBLE);
                            led.setBackgroundResource(R.drawable.led_off);
                            led.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#FFC107")));

                            card.setOnClickListener(v -> Snackbar.make(v, R.string.install_warning_discrepancy_msg, Snackbar.LENGTH_LONG).show());
                        } else {
                            // RULE 3: Available to install (CHECKBOX)
                            led.setVisibility(View.GONE);
                            checkBox.setVisibility(View.VISIBLE);
                            checkBox.setChecked(false);

                            // Security lock if server is running
                            if (finalIsRunning) {
                                checkBox.setEnabled(false);
                                card.setAlpha(0.6f);
                                card.setOnClickListener(v -> Snackbar.make(v, R.string.install_msg_server_running_lock, Snackbar.LENGTH_LONG).show());
                            } else {
                                checkBox.setEnabled(true);
                                card.setAlpha(1.0f);
                                checkBox.setButtonTintList(ColorStateList.valueOf(Color.WHITE));
                                card.setOnClickListener(v -> checkBox.toggle());
                            }

                            if (!newInstallCheckboxes.contains(checkBox)) {
                                newInstallCheckboxes.add(checkBox);
                            }
                            checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> evaluateLaunchButton());
                        }
                    });

                    if (finalDiscrepancyFlag) {
                        discrepancyFound = true;
                    }
                }
            }

            final boolean finalDiscrepancy = discrepancyFound;
            getActivity().runOnUiThread(() -> {
                if (discrepancyWarning != null) {
                    discrepancyWarning.setVisibility(finalDiscrepancy ? View.VISIBLE : View.GONE);
                }
                evaluateLaunchButton();
            });

        }).start();
    }

    private void evaluateLaunchButton() {
        if (isBatchInstalling) return;

        boolean hasSelections = false;
        installationQueue.clear();

        for (CheckBox cb : newInstallCheckboxes) {
            if (cb.isChecked()) {
                hasSelections = true;
                ViewGroup indicatorContainer = (ViewGroup) cb.getParent();
                ViewGroup card = (ViewGroup) indicatorContainer.getParent();
                ModuleRegistry.IiabModule module = (ModuleRegistry.IiabModule) card.getTag();

                if (module != null) {
                    installationQueue.add(module.yamlBaseKey);
                }
            }
        }

        btnLaunchInstall.setEnabled(hasSelections);
        btnLaunchInstall.setAlpha(hasSelections ? 1.0f : 0.5f);
        btnLaunchInstall.setText(getString(R.string.install_btn_launch));

        if (hasSelections) {
            btnLaunchInstall.setOnClickListener(v -> {
                isBatchInstalling = true;
                saveQueueToPrefs();
                processNextInQueue();
            });
        } else {
            btnLaunchInstall.setOnClickListener(null);
        }
    }

    private boolean pingUrl(String urlStr) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setUseCaches(false);
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(1500);
            conn.setRequestMethod("HEAD");
            int responseCode = conn.getResponseCode();
            return (responseCode >= 200 && responseCode < 400);
        } catch (Exception e) {
            return false;
        }
    }

    private String getTermuxArch() {
        try {
            android.content.pm.ApplicationInfo info = requireContext().getPackageManager().getApplicationInfo("com.termux", 0);
            String nativeLibDir = info.nativeLibraryDir;

            if (nativeLibDir != null) {
                if (nativeLibDir.endsWith("arm64")) return "arm64-v8a";
                if (nativeLibDir.endsWith("arm")) return "armeabi-v7a";
                if (nativeLibDir.endsWith("x86_64")) return "x86_64";
                if (nativeLibDir.endsWith("x86")) return "x86";
            }
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return "N/A";
        }

        if (android.os.Build.SUPPORTED_ABIS.length > 0) {
            return android.os.Build.SUPPORTED_ABIS[0];
        }
        return "unknown";
    }

    // --- METHODS FOR PERSISTING THE INSTALLATION QUEUE ---
    private void saveQueueToPrefs() {
        if (getActivity() == null) return;
        android.content.SharedPreferences prefs = getActivity().getSharedPreferences("iiab_queue_prefs", android.content.Context.MODE_PRIVATE);
        String queueString = android.text.TextUtils.join(",", installationQueue);
        prefs.edit().putString("pending_modules", queueString).putBoolean("is_batch_installing", isBatchInstalling).apply();
    }

    private void restoreQueueFromPrefs() {
        if (getActivity() == null) return;
        android.content.SharedPreferences prefs = getActivity().getSharedPreferences("iiab_queue_prefs", android.content.Context.MODE_PRIVATE);
        isBatchInstalling = prefs.getBoolean("is_batch_installing", false);
        String queueString = prefs.getString("pending_modules", "");

        installationQueue.clear();
        if (!queueString.isEmpty()) {
            String[] modules = queueString.split(",");
            installationQueue.addAll(java.util.Arrays.asList(modules));
        }
    }

    // --- QUEUE DIRECTOR ---
    private void processNextInQueue() {
        if (installationQueue.isEmpty()) {
            // The queue is empty! We're finished.
            isBatchInstalling = false;
            saveQueueToPrefs();

            // We re-enable the UI and refresh the final JSON
            btnLaunchInstall.setEnabled(false);
            btnLaunchInstall.setText(getString(R.string.install_btn_launch));
            requestFreshLocalVars();

            if (getView() != null) {
                Snackbar.make(getView(), R.string.install_msg_finished, Snackbar.LENGTH_LONG).show();
            }
            return;
        }

        // We remove the FIRST element from the queue
        String nextModule = installationQueue.remove(0);
        saveQueueToPrefs();

        android.util.Log.d(TAG, "Queue: Sending module to Termux -> " + nextModule);

        // We've blocked the UI so the user knows what's going on.
        btnLaunchInstall.setEnabled(false);
        btnLaunchInstall.setText("Installing " + nextModule + "...");

        // We launch the Termux command
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).executeTermuxCommandHeadless("--install-module " + nextModule);
        }
    }

    /**
     * Master UI Controller for the Deployment Fragment.
     */
    private void updateDynamicButtons() {
        MainActivity mainAct = (MainActivity) getActivity();
        if (mainAct == null || !isAdded()) return;

        boolean isServerRunning = mainAct.isServerAlive;
        boolean isTermuxInst = mainAct.isTermuxInstalled();

        File stateDir = new File(Environment.getExternalStorageDirectory(), ".iiab_state");
        boolean isProotInstalled = new File(stateDir, "flag_system_installed").exists() ||
                new File(stateDir, "flag_iiab_ready").exists() ||
                new File(stateDir, "local_vars.json").exists();

        refreshDashboardLeds(mainAct);

        // LOGIC OF THE ANIMATED BANNER
        View bannerWarning = getView().findViewById(R.id.banner_server_warning);
        if (bannerWarning != null) {
            boolean isBannerVisible = bannerWarning.getVisibility() == View.VISIBLE;

            // Animate only if there is a real state change to prevent screen flashing.
            if (isServerRunning && !isBannerVisible) {
                android.transition.TransitionManager.beginDelayedTransition((ViewGroup) getView(), new android.transition.AutoTransition().setDuration(300));
                bannerWarning.setVisibility(View.VISIBLE);
            } else if (!isServerRunning && isBannerVisible) {
                android.transition.TransitionManager.beginDelayedTransition((ViewGroup) getView(), new android.transition.AutoTransition().setDuration(300));
                bannerWarning.setVisibility(View.GONE);
            }
        }

        // --- REFRESH BUTTON LOGIC ---
        if (btnRefreshModules != null) {
            btnRefreshModules.setEnabled(true);

            if (isServerRunning || !isTermuxInst || !isProotInstalled) {
                btnRefreshModules.setTextColor(Color.parseColor("#9E9E9E"));
                btnRefreshModules.setAlpha(0.6f);
                btnRefreshModules.setOnClickListener(v -> {
                    if (!isTermuxInst || !isProotInstalled)
                        Snackbar.make(v, R.string.install_msg_termux_missing, Snackbar.LENGTH_LONG).show();
                    else if (isServerRunning)
                        Snackbar.make(v, R.string.install_msg_server_running_lock, Snackbar.LENGTH_LONG).show();
                });
            } else {
                btnRefreshModules.setTextColor(Color.parseColor("#2196F3"));
                btnRefreshModules.setAlpha(1.0f);
                btnRefreshModules.setOnClickListener(v -> {
                    v.setAlpha(0.5f);
                    requestFreshLocalVars();
                    new Handler(Looper.getMainLooper()).postDelayed(() -> v.setAlpha(1.0f), 1000);
                });
            }
        }

        // --- ADVANCED SECTION LOGIC (2x2 Grid) ---
        btnFastInstall.setEnabled(true);
        btnFastDelete.setEnabled(true);
        if (btnAdvancedReset != null) btnAdvancedReset.setEnabled(true);
        if (btnAdvancedBackup != null) btnAdvancedBackup.setEnabled(true);
        if (btnAdvancedRestore != null) btnAdvancedRestore.setEnabled(true);
        if (txtSelectBackupTitle != null) txtSelectBackupTitle.setEnabled(true);

        // Force Stop is always active as an emergency exit
        if (btnAdvancedForceStop != null) {
            btnAdvancedForceStop.setEnabled(true);
            btnAdvancedForceStop.setAlpha(1.0f);
            btnAdvancedForceStop.setOnClickListener(v -> openTermuxAppInfo());
        }

        if (!isTermuxInst) {
            // CASE A: Termux missing
            float lockAlpha = 0.5f;
            btnFastInstall.setAlpha(lockAlpha);
            btnFastDelete.setAlpha(lockAlpha);
            if (btnAdvancedBackup != null) btnAdvancedBackup.setAlpha(lockAlpha);
            if (btnAdvancedRestore != null) btnAdvancedRestore.setAlpha(lockAlpha);
            if (btnAdvancedReset != null) btnAdvancedReset.setAlpha(lockAlpha);
            if (txtSelectBackupTitle != null) txtSelectBackupTitle.setAlpha(lockAlpha);

            btnFastInstall.setText(R.string.install_btn_install);

            View.OnClickListener noTermux = v -> Snackbar.make(v, R.string.install_msg_termux_missing, Snackbar.LENGTH_LONG).show();
            btnFastInstall.setOnClickListener(noTermux);
            btnFastDelete.setOnClickListener(noTermux);
            if (btnAdvancedBackup != null) btnAdvancedBackup.setOnClickListener(noTermux);
            if (btnAdvancedRestore != null) btnAdvancedRestore.setOnClickListener(noTermux);
            if (btnAdvancedReset != null) btnAdvancedReset.setOnClickListener(noTermux);
            if (txtSelectBackupTitle != null) txtSelectBackupTitle.setOnClickListener(noTermux);

        } else if (isServerRunning) {
            // CASE B: Server Running (Security lock)
            float lockAlpha = 0.5f;
            btnFastInstall.setAlpha(lockAlpha);
            btnFastDelete.setAlpha(lockAlpha);
            if (btnAdvancedBackup != null) btnAdvancedBackup.setAlpha(lockAlpha);
            if (btnAdvancedRestore != null) btnAdvancedRestore.setAlpha(lockAlpha);
            if (btnAdvancedReset != null) btnAdvancedReset.setAlpha(lockAlpha);
            if (txtSelectBackupTitle != null) txtSelectBackupTitle.setAlpha(lockAlpha);

            btnFastInstall.setText(R.string.install_btn_reinstall);

            View.OnClickListener serverHot = v -> Snackbar.make(v, R.string.install_msg_server_running_lock, Snackbar.LENGTH_LONG).show();
            btnFastInstall.setOnClickListener(serverHot);
            btnFastDelete.setOnClickListener(serverHot);
            if (btnAdvancedBackup != null) btnAdvancedBackup.setOnClickListener(serverHot);
            if (btnAdvancedRestore != null) btnAdvancedRestore.setOnClickListener(serverHot);
            if (btnAdvancedReset != null) btnAdvancedReset.setOnClickListener(serverHot);
            if (txtSelectBackupTitle != null)
                txtSelectBackupTitle.setOnClickListener(serverHot);

        } else {
            // CASE C: Clear Path (Server Offline)
            btnFastInstall.setAlpha(1.0f);
            btnFastDelete.setAlpha(1.0f);
            if (btnAdvancedBackup != null) btnAdvancedBackup.setAlpha(1.0f);
            if (btnAdvancedReset != null) btnAdvancedReset.setAlpha(1.0f);
            if (txtSelectBackupTitle != null) txtSelectBackupTitle.setAlpha(1.0f);
            refreshRestoreButtonLogic();

            // Restore button starts locked until a valid backup is selected
            if (btnAdvancedRestore != null) {
                btnAdvancedRestore.setAlpha(0.5f);
                btnAdvancedRestore.setOnClickListener(v -> {
                    Snackbar.make(v, "Please select a backup first.", Snackbar.LENGTH_LONG).show();
                });
            }

            if (isProotInstalled) {
                btnFastInstall.setText(R.string.install_btn_reinstall);
            } else {
                btnFastInstall.setText(R.string.install_btn_install);
            }

            // REAL SHARES
            btnFastInstall.setOnClickListener(v -> {
                mainAct.invalidateModuleStateTrust();
                Snackbar.make(v, "Starting fast installation (pull-rootfs)...", Snackbar.LENGTH_SHORT).show();
                mainAct.executeTermuxCommandHeadless("--pull-rootfs");
            });

            btnFastDelete.setOnClickListener(v -> {
                mainAct.invalidateModuleStateTrust();
                Snackbar.make(v, "Starting deletion (remove-rootfs)...", Snackbar.LENGTH_SHORT).show();
                mainAct.executeTermuxCommandHeadless("--remove-rootfs");
            });

            if (btnAdvancedBackup != null) {
                btnAdvancedBackup.setOnClickListener(v -> {
                    Snackbar.make(v, "Starting backup (backup-rootfs)...", Snackbar.LENGTH_SHORT).show();
                    mainAct.executeTermuxCommandHeadless("--backup-rootfs");
                });
            }

            if (btnAdvancedReset != null) {
                btnAdvancedReset.setOnClickListener(v -> {
                    new android.app.AlertDialog.Builder(requireContext())
                            .setTitle(R.string.install_dialog_reset_title)
                            .setMessage(R.string.install_dialog_reset_msg)
                            .setPositiveButton(R.string.install_dialog_reset_confirm, (dialog, which) -> {
                                Snackbar.make(v, "Starting reset (reset-iiab)...", Snackbar.LENGTH_SHORT).show();
                                mainAct.invalidateModuleStateTrust();
                                mainAct.executeTermuxCommandHeadless("--reset-iiab");
                            })
                            .setNegativeButton(R.string.install_dialog_reset_cancel, null)
                            .show();
                });
            }

            // --- BACKUP DROP-DOWN MENU LOGIC  ---
            if (txtSelectBackupTitle != null) {
                txtSelectBackupTitle.setOnClickListener(v -> {
                    boolean isCollapsed = containerBackupList.getVisibility() == View.GONE;

                    if (isCollapsed) {
                        // Display the menu visually
                        containerBackupList.setVisibility(View.VISIBLE);
                        txtSelectBackupTitle.setText(getString(R.string.install_adv_select_backup_open));
                        txtBackupStatus.setText(getString(R.string.install_msg_fetching_backups));
                        txtBackupStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.dash_text_secondary));

                        // Trigger the command to Termux to generate the list
                        mainAct.executeTermuxCommandHeadless("--list-backups");

                        // Start hunting for the JSON file (wait up to 5 seconds)
                        File backupsJsonFile = new File(stateDir, "backups_list.json");
                        if (backupsJsonFile.exists())
                            backupsJsonFile.delete(); // Clean up old queries
                        pollForBackupsJson(backupsJsonFile, 5, 1000);

                    } else {
                        // Close the menu
                        containerBackupList.setVisibility(View.GONE);
                        txtSelectBackupTitle.setText(getString(R.string.install_adv_select_backup));
                    }
                });
            }
        }
    }

    // --- AUXILIARY METHODS ---

    public void openTermuxAppInfo() {
        try {
            android.content.Intent intent = new android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            android.net.Uri uri = android.net.Uri.fromParts("package", "com.termux", null);
            intent.setData(uri);
            startActivity(intent);
        } catch (Exception e) {
            android.util.Log.e(TAG, "Error opening App Info", e);
        }
    }

    private void pollForBackupsJson(File jsonFile, int attemptsLeft, int delayMs) {
        if (attemptsLeft <= 0) {
            // Time ran out and Termux did not respond
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (txtBackupStatus != null) {
                        txtBackupStatus.setText(getString(R.string.install_msg_no_backups));
                        txtBackupStatus.setTextColor(Color.parseColor("#FF5555")); // Warning red
                    }
                    selectedBackupFile = null;
                    refreshRestoreButtonLogic(); // The master controller decides the button state
                });
            }
            return;
        }

        if (jsonFile.exists() && jsonFile.length() > 0) {
            try {
                StringBuilder text = new StringBuilder();
                BufferedReader br = new BufferedReader(new FileReader(jsonFile));
                String line;
                while ((line = br.readLine()) != null) text.append(line);
                br.close();

                JSONObject backupsData = new JSONObject(text.toString());
                org.json.JSONArray backupsArray = backupsData.optJSONArray("backups");
                String defaultNa = getString(R.string.install_msg_backup_na);

                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        containerBackupList.removeAllViews();

                        // CRITICAL: Ensure we start with no selection in memory
                        selectedBackupFile = null;

                        if (backupsArray == null || backupsArray.length() == 0) {
                            TextView noBackups = new TextView(requireContext());
                            noBackups.setText(getString(R.string.install_msg_no_backups));
                            noBackups.setTextColor(Color.parseColor("#FF5555"));
                            containerBackupList.addView(noBackups);
                        } else {
                            android.widget.RadioGroup radioGroup = new android.widget.RadioGroup(requireContext());
                            radioGroup.setOrientation(android.widget.RadioGroup.VERTICAL);

                            for (int i = 0; i < backupsArray.length(); i++) {
                                JSONObject backupObj = backupsArray.optJSONObject(i);
                                if (backupObj == null) continue;

                                String filename = backupObj.optString("filename", "");
                                String size = backupObj.optString("size", defaultNa);
                                String date = backupObj.optString("date", defaultNa);

                                android.widget.RadioButton rb = new android.widget.RadioButton(requireContext());
                                rb.setText(getString(R.string.install_msg_backup_details, filename, size, date));
                                rb.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.dash_text_primary));
                                rb.setPadding(0, 16, 0, 16);
                                rb.setTag(filename);

                                // Magic UX trick: Allow deselection
                                rb.setOnClickListener(v -> {
                                    // If tapped the already selected one, uncheck it
                                    if (filename.equals(selectedBackupFile)) {
                                        radioGroup.clearCheck();
                                        selectedBackupFile = null;
                                    } else {
                                        // If tapped a new one, update selection
                                        selectedBackupFile = filename;
                                    }
                                    // Refresh button (gray if null, green if selected)
                                    refreshRestoreButtonLogic();
                                });

                                radioGroup.addView(rb);
                            }

                            // Add the group to the container ONLY ONCE
                            containerBackupList.addView(radioGroup);
                        }

                        // Refresh general UI after building the list
                        refreshRestoreButtonLogic();
                    });
                }
                return;
            } catch (Exception e) {
                android.util.Log.e(TAG, "Error parsing backups JSON", e);
            }
        }

        // If it doesn't exist yet, we'll keep waiting.
        new Handler(android.os.Looper.getMainLooper()).postDelayed(() ->
                pollForBackupsJson(jsonFile, attemptsLeft - 1, delayMs), delayMs);
    }

    /**
     * Unified function to manage Restore button state.
     */
    private void refreshRestoreButtonLogic() {
        MainActivity mainAct = (MainActivity) getActivity();
        if (mainAct == null || btnAdvancedRestore == null) return;

        // Golden Rule: If server is running or Termux is missing, always gray.
        if (mainAct.isServerAlive || !mainAct.isTermuxInstalled()) {
            btnAdvancedRestore.setAlpha(0.5f);
            btnAdvancedRestore.setOnClickListener(v ->
                    Snackbar.make(v, R.string.install_msg_server_running_lock, Snackbar.LENGTH_LONG).show());
            return;
        }

        // If server is OFF, check if a backup is selected
        if (selectedBackupFile == null) {
            // No selection: Gray and prompt to select one
            btnAdvancedRestore.setAlpha(0.5f);
            btnAdvancedRestore.setOnClickListener(v ->
                    Snackbar.make(v, R.string.install_msg_select_backup_first, Snackbar.LENGTH_LONG).show());
        } else {
            // Selection exists: Turn button Green and assign action
            btnAdvancedRestore.setAlpha(1.0f);
            btnAdvancedRestore.setOnClickListener(v -> {
                String startingMsg = getString(R.string.install_msg_restore_starting, selectedBackupFile);
                Snackbar.make(v, startingMsg, Snackbar.LENGTH_SHORT).show();

                mainAct.invalidateModuleStateTrust();
                mainAct.executeTermuxCommandHeadless("--restore-rootfs " + selectedBackupFile);
            });
        }
    }

    private void refreshDashboardLeds(MainActivity mainAct) {
        if (mainAct == null || ledTermux == null) return;

        // 1. Termux LED: Verify if base app is installed
        boolean isTermuxInst = mainAct.isTermuxInstalled();
        ledTermux.setBackgroundResource(isTermuxInst ? R.drawable.led_on_green : R.drawable.led_off);

        // 2. Developer Mode LED: Native Android system read
        boolean isDevModeOn = false;
        try {
            isDevModeOn = android.provider.Settings.Global.getInt(
                    requireContext().getContentResolver(),
                    android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0;
        } catch (Exception e) {
            android.util.Log.e(TAG, "Could not check Developer Mode status", e);
        }
        ledDevMode.setBackgroundResource(isDevModeOn ? R.drawable.led_on_green : R.drawable.led_off);
    }

    // =========================================================================
    // ADB & CPU MONITORING METHODS
    // =========================================================================

    private void updateUiState(boolean isConnected) {
        if (isConnected) {
            ledAdbStatus.setBackgroundResource(R.drawable.led_on_green);
            txtAdbLedLabel.setText(getString(R.string.adb_status_connected));
            txtAdbLedLabel.setTextColor(Color.parseColor("#4CAF50"));
            btnAdbAction.setText(getString(R.string.adb_btn_disconnect));
            btnAdbAction.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#F44336")));

            // Texto en cursiva mientras consulta
            txtDcpr.setText(android.text.Html.fromHtml("Child Process<br><i>(Checking...)</i>", android.text.Html.FROM_HTML_MODE_COMPACT));
            txtPpk.setText(android.text.Html.fromHtml("PPK Limit<br><i>(Checking...)</i>", android.text.Html.FROM_HTML_MODE_COMPACT));
            ledDcpr.setBackgroundResource(R.drawable.led_off);
            ledDcpr.setBackgroundTintList(null);
            ledPpk.setBackgroundResource(R.drawable.led_off);
            ledPpk.setBackgroundTintList(null);

        } else {
            ledAdbStatus.setBackgroundResource(R.drawable.led_off);
            txtAdbLedLabel.setText(getString(R.string.adb_status_offline));
            txtAdbLedLabel.setTextColor(ContextCompat.getColor(requireContext(), R.color.dash_text_secondary));
            btnAdbAction.setText(getString(R.string.adb_btn_connect));
            btnAdbAction.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#2196F3")));

            txtDcpr.setText("Child Process\n(--)");
            txtPpk.setText("PPK Limit\n(--)");
            ledDcpr.setBackgroundResource(R.drawable.led_off);
            ledDcpr.setBackgroundTintList(null);
            ledPpk.setBackgroundResource(R.drawable.led_off);
            ledPpk.setBackgroundTintList(null);
        }
    }

    private void startAdbPairingFlow() {
        isScanning = true;
        isAttemptingFastConnect = false;
        btnAdbAction.setText(getString(R.string.adb_btn_scanning));
        btnAdbAction.setEnabled(false);

        discoveredConnectPort = -1;
        discoveredPairingPort = -1;

        connectDiscoveryListener = createDiscoveryListener(SERVICE_TYPE_CONNECT);
        pairingDiscoveryListener = createDiscoveryListener(SERVICE_TYPE_PAIRING);

        try {
            nsdManager.discoverServices(SERVICE_TYPE_CONNECT, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, connectDiscoveryListener);
            nsdManager.discoverServices(SERVICE_TYPE_PAIRING, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, pairingDiscoveryListener);
        } catch (Exception e) {
            android.util.Log.e(TAG, "Error starting NsdManager", e);
            resetScanState();
            return;
        }

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (isScanning && !isConnectedToAdb) {
                openDeveloperOptions();
            }
        }, 1500);

        new Handler(Looper.getMainLooper()).postDelayed(this::checkIfScanTimedOut, 90000);
    }

    private void attemptFastConnection(int port) {
        if (isAttemptingFastConnect) return;
        isAttemptingFastConnect = true;

        Context appContext = requireContext().getApplicationContext();
        new Thread(() -> {
            try {
                IIABAdbManager adbManager = IIABAdbManager.getInstance(appContext);
                adbManager.connect("127.0.0.1", port);

                new Handler(Looper.getMainLooper()).post(() -> {
                    stopDiscovery();
                    isConnectedToAdb = true;
                    btnAdbAction.setText(getString(R.string.adb_status_connected));
                    updateUiState(true);
                });

                adbManager.startCpuMonitor(appContext);
                adbManager.checkSystemRestrictions(appContext);
            } catch (Exception e) {
                isAttemptingFastConnect = false;
            }
        }).start();
    }

    private void openDeveloperOptions() {
        try {
            Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Snackbar.make(getView(), R.string.adb_snack_dev_options, Snackbar.LENGTH_LONG).show();
        }
    }

    private android.net.nsd.NsdManager.DiscoveryListener createDiscoveryListener(String serviceType) {
        return new android.net.nsd.NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String regType) {
            }

            @Override
            public void onServiceLost(android.net.nsd.NsdServiceInfo service) {
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                nsdManager.stopServiceDiscovery(this);
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                nsdManager.stopServiceDiscovery(this);
            }

            @Override
            public void onServiceFound(android.net.nsd.NsdServiceInfo service) {
                if (service.getServiceType().contains("_adb-tls")) resolveService(service);
            }
        };
    }

    private void resolveService(android.net.nsd.NsdServiceInfo serviceInfo) {
        nsdManager.resolveService(serviceInfo, new android.net.nsd.NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(android.net.nsd.NsdServiceInfo serviceInfo, int errorCode) {
            }

            @Override
            public void onServiceResolved(android.net.nsd.NsdServiceInfo serviceInfo) {
                int port = serviceInfo.getPort();
                String type = serviceInfo.getServiceType();

                new Handler(Looper.getMainLooper()).post(() -> {
                    if (type.contains("connect")) {
                        discoveredConnectPort = port;
                        attemptFastConnection(port);
                    } else if (type.contains("pairing")) {
                        discoveredPairingPort = port;
                    }

                    if (discoveredConnectPort != -1 && discoveredPairingPort != -1 && !isConnectedToAdb) {
                        stopDiscovery();
                        showPairingNotification(discoveredConnectPort, discoveredPairingPort);
                        resetScanState();
                    }
                });
            }
        });
    }

    private void showPairingNotification(int connectPort, int pairingPort) {
        RemoteInput remoteInput = new RemoteInput.Builder(AdbPairingReceiver.KEY_PIN_REPLY).setLabel(getString(R.string.adb_notif_input_hint)).build();
        Intent replyIntent = new Intent(requireContext(), AdbPairingReceiver.class);
        replyIntent.putExtra("connectPort", connectPort);
        replyIntent.putExtra("pairingPort", pairingPort);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0);
        PendingIntent replyPendingIntent = PendingIntent.getBroadcast(requireContext(), 0, replyIntent, flags);

        NotificationCompat.Action action = new NotificationCompat.Action.Builder(android.R.drawable.ic_menu_edit, getString(R.string.adb_notif_action_pin), replyPendingIntent).addRemoteInput(remoteInput).build();

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "ADB Pairing", NotificationManager.IMPORTANCE_HIGH);
            requireContext().getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(requireContext(), CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(getString(R.string.adb_notif_title))
                .setContentText(getString(R.string.adb_notif_desc))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .addAction(action)
                .setAutoCancel(true);

        NotificationManager nm = (NotificationManager) requireContext().getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(AdbPairingReceiver.NOTIFICATION_ID, builder.build());
    }

    private void stopDiscovery() {
        try {
            if (connectDiscoveryListener != null) {
                nsdManager.stopServiceDiscovery(connectDiscoveryListener);
                connectDiscoveryListener = null;
            }
            if (pairingDiscoveryListener != null) {
                nsdManager.stopServiceDiscovery(pairingDiscoveryListener);
                pairingDiscoveryListener = null;
            }
        } catch (Exception ignored) {
        }
    }

    private void checkIfScanTimedOut() {
        if (isScanning && (discoveredConnectPort == -1 || discoveredPairingPort == -1)) {
            Snackbar.make(getView(), R.string.adb_toast_scan_timeout, Snackbar.LENGTH_LONG).show();
            stopDiscovery();
            resetScanState();
        }
    }

    private void resetScanState() {
        isScanning = false;
        if (!isConnectedToAdb) {
            btnAdbAction.setEnabled(true);
            btnAdbAction.setText(getString(R.string.adb_btn_connect));
        }
    }

    private void setupCpuChart() {
        cpuChart.getDescription().setEnabled(false);
        cpuChart.setTouchEnabled(false);
        cpuChart.setDrawGridBackground(false);
        cpuChart.getLegend().setEnabled(false);

        XAxis xAxis = cpuChart.getXAxis();
        xAxis.setDrawLabels(false);
        xAxis.setDrawGridLines(true);
        xAxis.setGridColor(Color.parseColor("#333333"));
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);

        YAxis leftAxis = cpuChart.getAxisLeft();
        leftAxis.setTextColor(Color.LTGRAY);
        leftAxis.setAxisMaximum(100f);
        leftAxis.setAxisMinimum(0f);
        leftAxis.setDrawGridLines(true);
        leftAxis.setGridColor(Color.parseColor("#333333"));

        cpuChart.getAxisRight().setEnabled(false);
        cpuChart.setData(new LineData());
    }

    private void addCpuEntry(float cpuPercentage) {
        if (cpuChart == null || cpuChart.getData() == null) return;

        LineData data = cpuChart.getData();
        ILineDataSet set = data.getDataSetByIndex(0);

        if (set == null) {
            LineDataSet newSet = new LineDataSet(null, "CPU");
            newSet.setAxisDependency(YAxis.AxisDependency.LEFT);
            newSet.setColor(Color.parseColor("#4CAF50"));
            newSet.setLineWidth(2f);
            newSet.setDrawCircles(false);
            newSet.setDrawValues(false);
            newSet.setMode(LineDataSet.Mode.CUBIC_BEZIER);
            newSet.setDrawFilled(true);
            newSet.setFillColor(Color.parseColor("#4CAF50"));
            newSet.setFillAlpha(50);
            set = newSet;
            data.addDataSet(set);
        }

        data.addEntry(new Entry(set.getEntryCount(), cpuPercentage), 0);
        data.notifyDataChanged();
        cpuChart.notifyDataSetChanged();
        cpuChart.setVisibleXRangeMaximum(60);
        cpuChart.moveViewToX(data.getEntryCount());
    }

    private float parseCpuUsage(String cpuLine) {
        try {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("(\\d+)%cpu.*?(\\d+)%idle");
            java.util.regex.Matcher m = p.matcher(cpuLine.toLowerCase());
            if (m.find()) {
                float totalCpu = Float.parseFloat(m.group(1));
                float idleCpu = Float.parseFloat(m.group(2));
                if (totalCpu > 0) return ((totalCpu - idleCpu) / totalCpu) * 100f;
            }
        } catch (Exception ignored) {
        }
        return -1f;
    }
}
