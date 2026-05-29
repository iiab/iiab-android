/*
 * ============================================================================
 * Name        : DeployFragment.java
 * Author      : IIAB Project
 * Copyright   : Copyright (c) 2026 IIAB Project
 * Description : Installation / deployment view (Refactored with SAF & Regions)
 * ============================================================================
 */
package org.iiab.controller;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
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

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.RemoteInput;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;
import com.google.android.material.snackbar.Snackbar;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

public class DeployFragment extends Fragment {

    // =========================================================================================
    // REGION 1: VARIABLES & STATE
    // =========================================================================================
    private static final String TAG = "IIAB-DeployFragment";

    // UI Variables
    private View ledInternet, ledDevMode, ledDcpr, ledPpk;
    private TextView txtDcpr, txtPpk, btnRefreshModules;
    private LinearLayout rolesContainer, discrepancyWarning;
    private Button btnLaunchInstall, btnFastInstall, btnFastDelete, btnAdvancedReset;
    private Button btnAdvancedBackup, btnAdvancedRestore, btnAdvancedForceStop;

    // Backup Menu UI
    private TextView txtSelectBackupTitle, txtBackupStatus;
    private LinearLayout containerBackupList;
    private String selectedBackupFile = null;

    // Advanced Monitoring UI
    private TextView txtAdvMonitoringTitle;
    private LinearLayout containerAdvMonitoring;
    private Button btnAdbAction;
    private View ledAdbStatus;
    private TextView txtAdbLedLabel;
    private com.github.mikephil.charting.charts.LineChart cpuChart;

    // Planner UI
    private Button btnTierBasic, btnTierStandard, btnTierFull;
    private TextView txtLegendIiab, txtLegendMaps, txtLegendKiwix, txtLegendFree;
    private CheckBox chkCompanionData;
    private MultiResourceGaugeView storageGauge;
    private android.widget.ImageButton btnKiwixSettings;

    // SAF & Backup Controls
    private Button btnImportBackup;
    private boolean isBackupInProgress = false;
    private ActivityResultLauncher<String[]> importBackupLauncher;
    private ActivityResultLauncher<String> exportBackupLauncher;

    // State Variables
    private final List<CheckBox> newInstallCheckboxes = new ArrayList<>();
    private File sharedStateDir;
    private JSONObject lastKnownState = new JSONObject();
    private List<String> installationQueue = new ArrayList<>();
    private boolean isBatchInstalling = false;
    private boolean isStorageSafe = false;
    private String overrideKiwixLang = null;
    private String overrideKiwixVariant = null;
    private InstallationPlanner.Tier selectedTier = null;

    // Native Engine Variables
    private static Aria2Manager aria2Manager;
    private static boolean isDownloadingRootfs = false;
    private PRootEngine prootEngine;

    // Background Handlers
    private final Handler liveStatusHandler = new Handler(Looper.getMainLooper());
    private Runnable liveStatusRunnable;

    // ADB Variables
    private boolean isConnectedToAdb = false, isScanning = false, isAttemptingFastConnect = false;
    private android.net.nsd.NsdManager nsdManager;
    private String discoveredHostIp = "127.0.0.1";
    private int discoveredConnectPort = -1, discoveredPairingPort = -1;
    private android.net.nsd.NsdManager.DiscoveryListener connectDiscoveryListener, pairingDiscoveryListener;
    private android.net.wifi.WifiManager.MulticastLock multicastLock;

    private static final String CHANNEL_ID = "adb_pairing_channel";
    private static final String SERVICE_TYPE_CONNECT = "_adb-tls-connect._tcp.";
    private static final String SERVICE_TYPE_PAIRING = "_adb-tls-pairing._tcp.";

    private final BroadcastReceiver adbUiUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if ("org.iiab.controller.ADB_PAIRING_SUCCESSFUL".equals(action)) {
                requireContext().getSharedPreferences("iiab_adb_prefs", Context.MODE_PRIVATE)
                        .edit().putBoolean("pairing_just_succeeded", false).apply();

                android.util.Log.i(TAG, "Broadcast received: Pairing successful! Re-scanning in 2.5s...");
                if (isAdded()) {
                    btnAdbAction.setText(getString(R.string.adb_status_securing));
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        startAdbPairingFlow();
                    }, 2500);
                }
            } else if ("org.iiab.controller.ADB_PAIRING_FAILED".equals(action)) {
                android.util.Log.w(TAG, "Broadcast received: Pairing failed.");
                if (isAdded()) resetScanState();

            } else if ("org.iiab.controller.ADB_PAIRING_SENT".equals(action)) {
                btnAdbAction.setText(getString(R.string.adb_status_connected));
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

                requireContext().getSharedPreferences("iiab_adb_prefs", Context.MODE_PRIVATE)
                        .edit()
                        .putString("child_process_value", cpValue)
                        .putString("ppk_value", rawPpkValue)
                        .apply();

                String ppkDisplay = ("null".equals(rawPpkValue) || "unknown".equals(rawPpkValue)) ? getString(R.string.adb_ppk_default) : rawPpkValue;

                ledDcpr.setBackgroundTintList(null);
                ledPpk.setBackgroundTintList(null);
                ledPpk.setBackgroundResource(R.drawable.led_off);
                ledDcpr.setBackgroundResource(R.drawable.led_off);

                if (android.os.Build.VERSION.SDK_INT >= 34) {
                    txtPpk.setText(android.text.Html.fromHtml(getString(R.string.adb_ppk_limit_not_required, ppkDisplay), android.text.Html.FROM_HTML_MODE_COMPACT));
                    if ("256".equals(rawPpkValue) || "512".equals(rawPpkValue) || "1024".equals(rawPpkValue)) {
                        ledPpk.setBackgroundResource(R.drawable.led_on_green);
                    } else if ("error".equals(rawPpkValue) || rawPpkValue == null || rawPpkValue.isEmpty()) {
                        ledPpk.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#FFC107")));
                    } else {
                        ledPpk.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#2196F3")));
                    }

                    if ("0".equals(cpValue) || "false".equals(cpValue)) {
                        ledDcpr.setBackgroundResource(R.drawable.led_on_green);
                        txtDcpr.setText(android.text.Html.fromHtml(getString(R.string.adb_cp_disabled_ok), android.text.Html.FROM_HTML_MODE_COMPACT));
                    } else if ("1".equals(cpValue) || "true".equals(cpValue) || "null".equals(cpValue) || cpValue == null) {
                        ledDcpr.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#F44336")));
                        txtDcpr.setText(android.text.Html.fromHtml(getString(R.string.adb_cp_enabled_limiting), android.text.Html.FROM_HTML_MODE_COMPACT));
                    } else {
                        txtDcpr.setText(android.text.Html.fromHtml(getString(R.string.adb_cp_unknown), android.text.Html.FROM_HTML_MODE_COMPACT));
                    }

                } else if (android.os.Build.VERSION.SDK_INT >= 31) {
                    txtDcpr.setText(android.text.Html.fromHtml(getString(R.string.adb_cp_not_required), android.text.Html.FROM_HTML_MODE_COMPACT));
                    txtPpk.setText(android.text.Html.fromHtml(getString(R.string.adb_ppk_limit_active, ppkDisplay), android.text.Html.FROM_HTML_MODE_COMPACT));

                    if ("256".equals(rawPpkValue) || "512".equals(rawPpkValue) || "1024".equals(rawPpkValue)) {
                        ledPpk.setBackgroundResource(R.drawable.led_on_green);
                    } else if ("error".equals(rawPpkValue) || rawPpkValue == null || rawPpkValue.isEmpty()) {
                        ledPpk.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#F44336")));
                    } else {
                        ledPpk.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#FFC107")));
                    }
                }
            }
        }
    };


    // =========================================================================================
    // REGION 2: ANDROID LIFECYCLE
    // =========================================================================================

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_deploy, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // UI Binding
        ledInternet = view.findViewById(R.id.led_install_internet);
        ledDevMode = view.findViewById(R.id.led_install_dev_mode);
        ledDcpr = view.findViewById(R.id.led_install_dcpr);
        ledPpk = view.findViewById(R.id.led_install_ppk);
        txtDcpr = view.findViewById(R.id.txt_install_dcpr);
        txtPpk = view.findViewById(R.id.txt_install_ppk);
        btnAdbAction = view.findViewById(R.id.btn_adb_action);
        ledAdbStatus = view.findViewById(R.id.led_adb_status);
        txtAdbLedLabel = view.findViewById(R.id.txt_adb_led_label);
        cpuChart = view.findViewById(R.id.cpu_chart);
        btnKiwixSettings = view.findViewById(R.id.btn_kiwix_settings);
        rolesContainer = view.findViewById(R.id.install_roles_container);
        discrepancyWarning = view.findViewById(R.id.install_discrepancy_warning);
        btnLaunchInstall = view.findViewById(R.id.btn_launch_install);
        btnFastInstall = view.findViewById(R.id.btn_fast_install);
        btnFastDelete = view.findViewById(R.id.btn_fast_delete);
        btnAdvancedReset = view.findViewById(R.id.btn_advanced_reset);
        btnAdvancedBackup = view.findViewById(R.id.btn_advanced_backup);
        btnAdvancedRestore = view.findViewById(R.id.btn_advanced_restore);
        btnAdvancedForceStop = view.findViewById(R.id.btn_advanced_force_stop);
        txtSelectBackupTitle = view.findViewById(R.id.txt_select_backup_title);
        containerBackupList = view.findViewById(R.id.container_backup_list);
        txtBackupStatus = view.findViewById(R.id.txt_backup_status);
        btnRefreshModules = view.findViewById(R.id.btn_refresh_modules);
        btnTierBasic = view.findViewById(R.id.btn_tier_basic);
        btnTierStandard = view.findViewById(R.id.btn_tier_standard);
        btnTierFull = view.findViewById(R.id.btn_tier_full);
        chkCompanionData = view.findViewById(R.id.chk_companion_data);
        storageGauge = view.findViewById(R.id.storage_projection_gauge);
        txtLegendIiab = view.findViewById(R.id.txt_legend_iiab);
        txtLegendMaps = view.findViewById(R.id.txt_legend_maps);
        txtLegendKiwix = view.findViewById(R.id.txt_legend_kiwix);
        txtLegendFree = view.findViewById(R.id.txt_legend_free);

        // SAF Binding
        btnImportBackup = view.findViewById(R.id.btn_import_backup);

        nsdManager = (android.net.nsd.NsdManager) requireContext().getSystemService(Context.NSD_SERVICE);
        sharedStateDir = new File(Environment.getExternalStorageDirectory(), ".iiab_state");

        // Initialization Logic
        setupSafLaunchers();
        setupAdbNetworking();
        setupAdvancedMonitoringMenu(view);
        setupCpuChart();
        setupAdbListeners();
        setupPlannerListeners();
        setupAllCollapsibleMenus();
        createModulesGrid();

        // Initial States
        btnLaunchInstall.setEnabled(false);
        btnLaunchInstall.setAlpha(0.5f);
        btnKiwixSettings.setColorFilter(Color.parseColor("#555555"));
        btnFastInstall.setAlpha(0.4f);

        // Handlers
        liveStatusRunnable = () -> {
            new Thread(() -> {
                boolean isAlive = pingUrl("http://localhost:8085/home");
                checkInternetAccess();
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).isServerAlive = isAlive;
                }
                if (isAdded() && getActivity() != null) {
                    getActivity().runOnUiThread(this::updateDynamicButtons);
                }
            }).start();
            liveStatusHandler.postDelayed(liveStatusRunnable, 3000);
        };

        requestFreshLocalVarsSilently();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (discrepancyWarning != null) discrepancyWarning.setVisibility(View.GONE);

        registerAdbReceiver();
        checkAndHandleSyncFragmentFocus();
        restoreQueueFromPrefs();

        if (isBatchInstalling) {
            new Handler(Looper.getMainLooper()).postDelayed(this::processNextInQueue, 500);
        }

        if (lastKnownState.length() > 0) {
            verifyInstallationState(lastKnownState);
        } else {
            loadLocalVarsFallback();
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

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (getActivity() != null && getActivity().isChangingConfigurations()) return;
        if (aria2Manager != null && isDownloadingRootfs) {
            aria2Manager.stopDownload();
            isDownloadingRootfs = false;
        }
    }


    // =========================================================================================
    // REGION 3: UI & MENU CONTROLLERS
    // =========================================================================================

    private void setupAllCollapsibleMenus() {
        if (getView() == null) return;
        TextView txtModuleMgmtTitle = getView().findViewById(R.id.txt_module_mgmt_title);
        LinearLayout containerModuleMgmt = getView().findViewById(R.id.container_module_mgmt);
        setupSingleMenu(txtModuleMgmtTitle, containerModuleMgmt, R.string.install_header_roles);

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
            titleView.setText(getString(isCollapsed ? R.string.label_separator_down : R.string.label_separator_up, baseText));
        });
    }

    private void setupAdvancedMonitoringMenu(View view) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            view.findViewById(R.id.section_adv_monitoring).setVisibility(View.GONE);
            View adbLedsContainer = view.findViewById(R.id.container_adb_leds);
            if (adbLedsContainer != null) adbLedsContainer.setVisibility(View.GONE);
        } else {
            txtAdvMonitoringTitle = view.findViewById(R.id.txt_adv_monitoring_title);
            containerAdvMonitoring = view.findViewById(R.id.container_adv_monitoring);
            setupSingleMenu(txtAdvMonitoringTitle, containerAdvMonitoring, R.string.install_adv_monitoring_title);
        }
    }

    private void focusAdvancedMonitoring() {
        if (containerAdvMonitoring != null && txtAdvMonitoringTitle != null) {
            if (containerAdvMonitoring.getVisibility() == View.GONE)
                txtAdvMonitoringTitle.performClick();
            android.animation.ArgbEvaluator evaluator = new android.animation.ArgbEvaluator();
            android.animation.ObjectAnimator animator = android.animation.ObjectAnimator.ofObject(
                    txtAdvMonitoringTitle, "textColor", evaluator,
                    Color.RED, ContextCompat.getColor(requireContext(), R.color.dash_text_primary)
            );
            animator.setDuration(400);
            animator.setRepeatCount(5);
            animator.setRepeatMode(android.animation.ValueAnimator.REVERSE);
            animator.start();
        }
    }

    private void checkAndHandleSyncFragmentFocus() {
        android.content.SharedPreferences adbPrefs = requireContext().getSharedPreferences("iiab_adb_prefs", Context.MODE_PRIVATE);
        if (adbPrefs.getBoolean("pairing_just_succeeded", false)) {
            adbPrefs.edit().putBoolean("pairing_just_succeeded", false).apply();
            btnAdbAction.setText("Securing connection...");
            new Handler(Looper.getMainLooper()).postDelayed(() -> startAdbPairingFlow(true), 2500);
        }
        if (adbPrefs.getBoolean("focus_adb", false)) {
            adbPrefs.edit().putBoolean("focus_adb", false).apply();
            new Handler(Looper.getMainLooper()).postDelayed(this::focusAdvancedMonitoring, 600);
        }
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
            rowLayout.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            rowLayout.setBaselineAligned(false);
            rowLayout.setWeightSum(numCols);
            rowLayout.setPadding(0, 0, 0, 16);

            for (int col = 0; col < numCols; col++) {
                int index = (row * numCols) + col;
                LinearLayout cell = new LinearLayout(requireContext());
                LinearLayout.LayoutParams cellParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);

                int margin = 10;
                if (col == 0) cellParams.setMargins(0, 0, margin, 0);
                else if (col == 1) cellParams.setMargins(margin / 2, 0, margin / 2, 0);
                else cellParams.setMargins(margin, 0, 0, 0);

                cell.setLayoutParams(cellParams);

                if (index < activeModules.size()) {
                    ModuleRegistry.IiabModule currentMod = activeModules.get(index);

                    cell.setOrientation(LinearLayout.HORIZONTAL);
                    cell.setBackgroundResource(R.drawable.rounded_button);
                    cell.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.dash_module_bg)));
                    cell.setPadding(16, 28, 16, 28);
                    cell.setGravity(android.view.Gravity.CENTER);

                    int boxSizePx = (int) (24 * getResources().getDisplayMetrics().density);
                    android.widget.FrameLayout indicatorContainer = new android.widget.FrameLayout(requireContext());
                    LinearLayout.LayoutParams indParams = new LinearLayout.LayoutParams(boxSizePx, boxSizePx);
                    indicatorContainer.setLayoutParams(indParams);

                    View led = new View(requireContext());
                    android.widget.FrameLayout.LayoutParams ledParams = new android.widget.FrameLayout.LayoutParams(ledSizePx, ledSizePx, android.view.Gravity.CENTER);
                    led.setLayoutParams(ledParams);
                    led.setBackgroundResource(R.drawable.led_off);

                    CheckBox checkBox = new CheckBox(requireContext());
                    checkBox.setScaleX(0.85f);
                    checkBox.setScaleY(0.85f);
                    checkBox.setPadding(0, 0, 0, 0);
                    android.widget.FrameLayout.LayoutParams cbParams = new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER);
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
                    LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
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

    private void updateDynamicButtons() {
        MainActivity mainAct = (MainActivity) getActivity();
        if (mainAct == null || !isAdded()) return;

        boolean isServerRunning = mainAct.isServerAlive;
        final File iiabRootDir = new File(requireContext().getFilesDir(), "rootfs");
        final File debianRootfs = new File(iiabRootDir, "installed-rootfs/iiab");
        final File backupsDir = new File(iiabRootDir, "backups");
        if (!backupsDir.exists()) backupsDir.mkdirs();

        boolean isProotInstalled = new File(debianRootfs, "etc/os-release").exists() || new File(debianRootfs, "usr/bin/bash").exists();
        refreshDashboardLeds(mainAct);

        // Animated Banner
        View bannerWarning = getView().findViewById(R.id.banner_server_warning);
        if (bannerWarning != null) {
            boolean isBannerVisible = bannerWarning.getVisibility() == View.VISIBLE;
            if (isServerRunning && !isBannerVisible) {
                android.transition.TransitionManager.beginDelayedTransition((ViewGroup) getView(), new android.transition.AutoTransition().setDuration(300));
                bannerWarning.setVisibility(View.VISIBLE);
            } else if (!isServerRunning && isBannerVisible) {
                android.transition.TransitionManager.beginDelayedTransition((ViewGroup) getView(), new android.transition.AutoTransition().setDuration(300));
                bannerWarning.setVisibility(View.GONE);
            }
        }

        // Refresh Button
        if (btnRefreshModules != null) {
            btnRefreshModules.setEnabled(true);
            if (isServerRunning || !isProotInstalled) {
                btnRefreshModules.setTextColor(Color.parseColor("#9E9E9E"));
                btnRefreshModules.setAlpha(0.6f);
                btnRefreshModules.setOnClickListener(v -> {
                    if (!isProotInstalled)
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

        // Basic Enablers
        btnFastInstall.setEnabled(true);
        btnFastDelete.setEnabled(true);
        if (btnAdvancedReset != null) btnAdvancedReset.setEnabled(true);
        if (btnAdvancedBackup != null) btnAdvancedBackup.setEnabled(true);
        if (btnAdvancedRestore != null) btnAdvancedRestore.setEnabled(true);
        if (txtSelectBackupTitle != null) txtSelectBackupTitle.setEnabled(true);

        if (btnAdvancedForceStop != null) {
            btnAdvancedForceStop.setEnabled(true);
            btnAdvancedForceStop.setAlpha(1.0f);
            btnAdvancedForceStop.setOnClickListener(v -> openTermuxAppInfo());
        }

        if (isServerRunning) {
            // CASE A: Server Running (Lock UI)
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
            if (txtSelectBackupTitle != null) txtSelectBackupTitle.setOnClickListener(serverHot);

        } else {
            // CASE B: Server Offline (Enable UI)
            if (selectedTier == null || !isStorageSafe) btnFastInstall.setAlpha(0.4f);
            else btnFastInstall.setAlpha(1.0f);

            btnFastDelete.setAlpha(1.0f);
            if (btnAdvancedBackup != null) btnAdvancedBackup.setAlpha(1.0f);
            if (btnAdvancedReset != null) btnAdvancedReset.setAlpha(1.0f);
            if (txtSelectBackupTitle != null) txtSelectBackupTitle.setAlpha(1.0f);
            refreshRestoreButtonLogic();

            if (!isDownloadingRootfs) {
                btnFastInstall.setEnabled(true);
                btnFastInstall.setTextSize(14f);
                if (isProotInstalled) btnFastInstall.setText(R.string.install_btn_reinstall);
                else btnFastInstall.setText(R.string.install_btn_install);
            } else {
                btnFastInstall.setEnabled(true);
                btnFastInstall.setTextSize(12f);
                btnFastInstall.setAlpha(0.8f);
            }

            // Bind native buttons
            bindInstallButtonLogic(mainAct, debianRootfs, iiabRootDir);
            bindDeleteButtonLogic(mainAct, debianRootfs);
            bindBackupButtonLogic(mainAct, backupsDir, iiabRootDir);
            bindResetButtonLogic(mainAct, debianRootfs);
            bindBackupMenuLogic(backupsDir);
        }
    }


    // =========================================================================================
    // REGION 4: INSTALLATION PLANNER
    // =========================================================================================

    private void setupPlannerListeners() {
        btnTierBasic.setAlpha(0.5f);
        btnTierBasic.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#008000")));
        btnTierStandard.setAlpha(0.5f);
        btnTierStandard.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#333333")));
        btnTierFull.setAlpha(0.5f);
        btnTierFull.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#333333")));

        View.OnClickListener tierClickListener = v -> {
            btnTierBasic.setAlpha(1.0f);
            btnTierStandard.setAlpha(1.0f);
            btnTierFull.setAlpha(1.0f);
            btnTierBasic.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#333333")));
            btnTierStandard.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#333333")));
            btnTierFull.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#333333")));
            v.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#008000")));

            if (v.getId() == R.id.btn_tier_basic) selectedTier = InstallationPlanner.Tier.BASIC;
            else if (v.getId() == R.id.btn_tier_standard)
                selectedTier = InstallationPlanner.Tier.STANDARD;
            else if (v.getId() == R.id.btn_tier_full) selectedTier = InstallationPlanner.Tier.FULL;

            overrideKiwixVariant = null;
            recalculateProjection();
        };

        btnTierBasic.setOnClickListener(tierClickListener);
        btnTierStandard.setOnClickListener(tierClickListener);
        btnTierFull.setOnClickListener(tierClickListener);

        chkCompanionData.setOnCheckedChangeListener((buttonView, isChecked) -> {
            btnKiwixSettings.setColorFilter(ContextCompat.getColor(requireContext(), isChecked ? R.color.colorAccent : R.color.dash_text_secondary));
            recalculateProjection();
        });

        btnKiwixSettings.setOnClickListener(v -> showKiwixSettingsDialog());
        recalculateProjection();
    }

    private void recalculateProjection() {
        android.content.SharedPreferences prefs = requireContext().getSharedPreferences(getString(R.string.pref_file_internal), Context.MODE_PRIVATE);
        String targetLang = (overrideKiwixLang != null) ? overrideKiwixLang : prefs.getString("selected_lang_minimal", "en");
        InstallationPlanner.Tier evalTier = (selectedTier != null) ? selectedTier : InstallationPlanner.Tier.BASIC;

        InstallationPlanner.calculateProjectedSize(requireContext(), evalTier, chkCompanionData.isChecked(), targetLang, overrideKiwixVariant, new InstallationPlanner.PlanResultListener() {
            @Override
            public void onCalculated(InstallationPlanner.StorageProjection projection) {
                if (!isAdded()) return;

                File path = android.os.Environment.getDataDirectory();
                double freeSpaceGb = path.getFreeSpace() / (1024.0 * 1024.0 * 1024.0);
                double totalSpaceGb = path.getTotalSpace() / (1024.0 * 1024.0 * 1024.0);
                double usedSpaceGb = totalSpaceGb - freeSpaceGb;

                double pOs = (selectedTier == null) ? 0.0 : projection.osSize;
                double pMaps = (selectedTier == null) ? 0.0 : projection.mapsSize;
                // --- DETECT ARCHITECTURE ---
                String arch = getTermuxArch();
                boolean is64Bit = arch != null && arch.contains("64");

                // --- FORCE KIWIX TO ZERO IN 32-BITS (change if kiwix gets support for 32bits somehow) ---
                double pKiwix = (selectedTier == null || !is64Bit) ? 0.0 : projection.kiwixSize;
                double pTotal = pOs + pMaps + pKiwix;

                isStorageSafe = pTotal <= (freeSpaceGb - 5.0);

                if (txtLegendIiab != null)
                    txtLegendIiab.setText(String.format(java.util.Locale.US, "%.1fG", pOs));
                if (txtLegendMaps != null)
                    txtLegendMaps.setText(String.format(java.util.Locale.US, "%.1fG", pMaps));
                if (txtLegendKiwix != null)
                    txtLegendKiwix.setText(String.format(java.util.Locale.US, "%.1fG", pKiwix));

                TextView lblWiki = getView().findViewById(R.id.txt_legend_kiwix).getRootView().findViewWithTag("label_kiwix");
                if (lblWiki == null && txtLegendKiwix != null) {
                    ViewGroup parent = (ViewGroup) txtLegendKiwix.getParent();
                    lblWiki = (TextView) parent.getChildAt(1);
                }
                // --- HIDE UI OF KIWIX IF IT IS 32-BITS ---
                if (!is64Bit) {
                    // We force "N/A" in the size text
                    if (txtLegendKiwix != null) {
                        txtLegendKiwix.setText(getString(R.string.install_msg_backup_na)); // Use the "N/A" string you already have
                        txtLegendKiwix.setTextColor(ContextCompat.getColor(requireContext(), R.color.dash_text_secondary));
                    }

                    if (lblWiki != null) {
                        lblWiki.setText(getString(R.string.install_legend_wiki_plain));
                        // We apply gray
                        lblWiki.setTextColor(ContextCompat.getColor(requireContext(), R.color.dash_text_secondary));
                        // (Optional) We can cross it out to make it clear that it is disabled
                        lblWiki.setPaintFlags(lblWiki.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
                    }

                    // We hide the gear so it cannot interact
                    if (btnKiwixSettings != null) btnKiwixSettings.setVisibility(View.GONE);
                } else if (lblWiki != null) {
                    // We clean the strikethrough (in case the view is recycled)
                    lblWiki.setPaintFlags(lblWiki.getPaintFlags() & (~android.graphics.Paint.STRIKE_THRU_TEXT_FLAG));

                    // Normal logic for 64-bit
                    if (chkCompanionData.isChecked()) {
                        lblWiki.setText(getString(R.string.install_legend_wiki_lang, projection.resolvedLang.toUpperCase()));
                        lblWiki.setTextColor(Color.parseColor("#2196F3"));
                    } else {
                        lblWiki.setText(getString(R.string.install_legend_wiki_plain));
                        lblWiki.setTextColor(ContextCompat.getColor(requireContext(), R.color.dash_text_secondary));
                    }
                }

                if (txtLegendFree != null) {
                    if (isStorageSafe) {
                        txtLegendFree.setText(String.format(java.util.Locale.US, "%.1fG", (freeSpaceGb - pTotal)));
                        txtLegendFree.setTextColor(ContextCompat.getColor(requireContext(), R.color.dash_text_inverted));
                    } else {
                        txtLegendFree.setText("OVERLOAD");
                        txtLegendFree.setTextColor(Color.parseColor("#F44336"));
                    }
                }

                if (storageGauge != null) {
                    List<MultiResourceGaugeView.Segment> segments = new ArrayList<>();
                    float otherUsedPct = (totalSpaceGb > 0) ? (float) (usedSpaceGb / totalSpaceGb) * 100f : 0f;
                    float osPct = (totalSpaceGb > 0) ? (float) (pOs / totalSpaceGb) * 100f : 0f;
                    float mapsPct = (totalSpaceGb > 0) ? (float) (pMaps / totalSpaceGb) * 100f : 0f;
                    float kiwixPct = (totalSpaceGb > 0) ? (float) (pKiwix / totalSpaceGb) * 100f : 0f;
                    float totalDrawn = 0f;

                    if (otherUsedPct > 0) {
                        float draw = Math.min(otherUsedPct, 100f - totalDrawn);
                        segments.add(new MultiResourceGaugeView.Segment(draw, Color.WHITE));
                        totalDrawn += draw;
                    }
                    if (osPct > 0 && totalDrawn < 100f) {
                        float draw = Math.min(osPct, 100f - totalDrawn);
                        segments.add(new MultiResourceGaugeView.Segment(draw, Color.parseColor("#00FFFF")));
                        totalDrawn += draw;
                    }
                    if (mapsPct > 0 && totalDrawn < 100f) {
                        float draw = Math.min(mapsPct, 100f - totalDrawn);
                        segments.add(new MultiResourceGaugeView.Segment(draw, Color.parseColor("#FF9800")));
                        totalDrawn += draw;
                    }
                    if (kiwixPct > 0 && totalDrawn < 100f) {
                        float draw = Math.min(kiwixPct, 100f - totalDrawn);
                        segments.add(new MultiResourceGaugeView.Segment(draw, Color.parseColor("#008000")));
                    }

                    int centerColor = (selectedTier == null || isStorageSafe) ? ContextCompat.getColor(requireContext(), R.color.dash_text_inverted) : Color.parseColor("#F44336");
                    storageGauge.updateData(segments, String.format(java.util.Locale.US, "%.1fG", pTotal), centerColor, "Projected", "Storage");
                }

                if (getActivity() != null)
                    getActivity().runOnUiThread(() -> updateDynamicButtons());
            }

            @Override
            public void onError(String error) {
                if (isAdded() && txtLegendFree != null) txtLegendFree.setText("Error");
            }
        });
    }

    private void showKiwixSettingsDialog() {
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(requireContext()).create();
        View view = getLayoutInflater().inflate(R.layout.dialog_install_planner_settings, null);
        dialog.setView(view);

        android.widget.Spinner spinnerLang = view.findViewById(R.id.spinner_kiwix_lang);
        Button btnWipe = view.findViewById(R.id.btn_wipe_cache);
        Button btnSelect = view.findViewById(R.id.btn_select_variant);
        android.widget.RadioGroup rgVariants = view.findViewById(R.id.rg_kiwix_variants);

        btnWipe.setOnClickListener(v -> {
            InstallationPlanner.wipeCache(requireContext());
            rgVariants.removeAllViews();
            spinnerLang.setAdapter(null);
            overrideKiwixVariant = null;
            Snackbar.make(getView(), R.string.kiwix_cache_wiped, Snackbar.LENGTH_SHORT).show();
        });

        InstallationPlanner.getOrFetchCatalog(requireContext(), new InstallationPlanner.CacheListener() {
            @Override
            public void onReady(JSONObject catalog) {
                if (!isAdded()) return;

                List<String> langKeys = new ArrayList<>();
                java.util.Iterator<String> keys = catalog.keys();
                while (keys.hasNext()) langKeys.add(keys.next());
                java.util.Collections.sort(langKeys);

                List<String> displayNames = new ArrayList<>();
                int selectedIndex = 0;
                android.content.SharedPreferences prefs = requireContext().getSharedPreferences(getString(R.string.pref_file_internal), Context.MODE_PRIVATE);
                String currentTarget = (overrideKiwixLang != null) ? overrideKiwixLang : prefs.getString("selected_lang_minimal", "en");

                for (int i = 0; i < langKeys.size(); i++) {
                    String code = langKeys.get(i);
                    java.util.Locale loc = new java.util.Locale(code);
                    String name = loc.getDisplayLanguage(loc);
                    displayNames.add(name.substring(0, 1).toUpperCase() + name.substring(1) + " / " + loc.getDisplayLanguage(java.util.Locale.US));
                    if (code.equals(currentTarget)) selectedIndex = i;
                }

                android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_dropdown_item, displayNames);
                spinnerLang.setAdapter(adapter);
                spinnerLang.setSelection(selectedIndex);

                spinnerLang.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(android.widget.AdapterView<?> parent, View v, int position, long id) {
                        String selectedCode = langKeys.get(position);
                        rgVariants.removeAllViews();

                        JSONObject variants = catalog.optJSONObject(selectedCode);
                        if (variants != null) {
                            java.util.Iterator<String> vKeys = variants.keys();
                            while (vKeys.hasNext()) {
                                String vk = vKeys.next();
                                JSONObject vData = variants.optJSONObject(vk);
                                double size = (vData != null) ? vData.optDouble("size", 0.0) : 0.0;

                                android.widget.RadioButton rb = new android.widget.RadioButton(requireContext());
                                rb.setId(View.generateViewId());
                                rb.setText(String.format(java.util.Locale.US, "%-22s %5.1f GB", vk, size));
                                rb.setTextColor(ContextCompat.getColor(requireContext(), R.color.dash_text_primary));
                                rb.setTypeface(Typeface.MONOSPACE);
                                rb.setTag(vk);
                                rgVariants.addView(rb);

                                if (vk.equals(overrideKiwixVariant)) rb.setChecked(true);
                            }
                        }
                    }

                    @Override
                    public void onNothingSelected(android.widget.AdapterView<?> parent) {
                    }
                });

                btnSelect.setOnClickListener(v -> {
                    int checkedId = rgVariants.getCheckedRadioButtonId();
                    if (checkedId != -1) {
                        android.widget.RadioButton rb = rgVariants.findViewById(checkedId);
                        overrideKiwixVariant = (String) rb.getTag();
                        overrideKiwixLang = langKeys.get(spinnerLang.getSelectedItemPosition());
                        recalculateProjection();
                        dialog.dismiss();
                    } else {
                        Snackbar.make(getView(), R.string.kiwix_select_variant_error, Snackbar.LENGTH_SHORT).show();
                    }
                });
            }

            @Override
            public void onError(String error) {
                if (isAdded())
                    Snackbar.make(getView(), getString(R.string.kiwix_catalog_error, error), Snackbar.LENGTH_LONG).show();
            }
        });
        dialog.show();
    }


    // =========================================================================================
    // REGION 5: NATIVE PIPELINES
    // =========================================================================================

    private void bindInstallButtonLogic(MainActivity mainAct, File debianRootfs, File iiabRootDir) {
        btnFastInstall.setOnClickListener(v -> {
            if (selectedTier == null) {
                Snackbar.make(v, R.string.install_error_no_tier, Snackbar.LENGTH_LONG).show();
                return;
            }
            if (!isStorageSafe) {
                Snackbar.make(v, R.string.install_error_no_storage, Snackbar.LENGTH_LONG).show();
                return;
            }
            if (isDownloadingRootfs) {
                new android.app.AlertDialog.Builder(requireContext())
                        .setTitle(getString(R.string.install_btn_cancel_title))
                        .setMessage(getString(R.string.install_btn_cancel_msg))
                        .setPositiveButton(getString(R.string.install_btn_cancel_confirm), (dialog, which) -> {
                            if (aria2Manager != null) aria2Manager.stopDownload();
                            disableSystemProtection();
                            isDownloadingRootfs = false;
                            btnFastInstall.setText(R.string.install_btn_install);
                            btnFastInstall.setAlpha(1.0f);
                            Snackbar.make(getView(), R.string.install_msg_cancelled, Snackbar.LENGTH_SHORT).show();
                        })
                        .setNegativeButton(getString(R.string.cancel), null)
                        .show();
                return;
            }

            Runnable executeDownload = () -> {
                enableSystemProtection();
                mainAct.invalidateModuleStateTrust();
                isDownloadingRootfs = true;
                btnFastInstall.setAlpha(0.8f);
                btnFastInstall.setTextSize(12f);

                if (aria2Manager == null) aria2Manager = new Aria2Manager();

                String arch = getTermuxArch();
                String archSuffix = (arch.contains("arm") && !arch.contains("64")) ? "armeabi-v7a" : "arm64-v8a";
                InstallationPlanner.Tier safeTier = (selectedTier != null) ? selectedTier : InstallationPlanner.Tier.BASIC;
                String tierString = safeTier.name().toLowerCase(java.util.Locale.US);
                String directUrl = "https://iiab.switnet.org/android/rootfs/latest_" + tierString + "_" + archSuffix + ".meta4";

                aria2Manager.startDownload(requireContext(), directUrl, new Aria2Manager.DownloadListener() {
                    @Override
                    public void onProgress(int percentage, String speed, String eta) {
                        if (isAdded() && getActivity() != null) {
                            getActivity().runOnUiThread(() -> btnFastInstall.setText(getString(R.string.install_status_os_download, percentage, speed)));
                        }
                    }

                    @Override
                    public void onComplete(String downloadPath) {
                        if (!isAdded() || getActivity() == null) return;
                        mainAct.runOnUiThread(() -> btnFastInstall.setText(getString(R.string.install_status_extracting)));

                        File downloadDir = new File(downloadPath);
                        File[] archives = downloadDir.listFiles((dir, name) -> name.endsWith(".tar.xz") || name.endsWith(".tar.gz"));

                        if (archives == null || archives.length == 0) {
                            abortInstallation(getString(R.string.install_error_no_archive));
                            return;
                        }

                        File downloadedArchive = archives[0];
                        TarExtractor tarExtractor = new TarExtractor();

                        tarExtractor.startExtraction(requireContext(), downloadedArchive.getAbsolutePath(), iiabRootDir.getAbsolutePath(), new TarExtractor.ExtractionListener() {
                            @Override
                            public void onComplete(String destDir) {
                                downloadedArchive.delete();
                                File prootTmp = new File(requireContext().getCacheDir(), "proot_tmp");
                                if (!prootTmp.exists()) prootTmp.mkdirs();
                                File binDir = new File(requireContext().getFilesDir(), "usr/bin");
                                if (binDir.exists()) {
                                    try {
                                        Runtime.getRuntime().exec(new String[]{"chmod", "-R", "755", binDir.getAbsolutePath()}).waitFor();
                                    } catch (Exception ignored) {
                                    }
                                }

                                if (chkCompanionData.isChecked()) {
                                    editLocalVarsForMaps(debianRootfs, safeTier);
                                    android.content.SharedPreferences prefs = requireContext().getSharedPreferences(getString(R.string.pref_file_internal), Context.MODE_PRIVATE);
                                    String targetLang = (overrideKiwixLang != null) ? overrideKiwixLang : prefs.getString("selected_lang_minimal", "en");

                                    InstallationPlanner.calculateProjectedSize(requireContext(), safeTier, true, targetLang, overrideKiwixVariant, new InstallationPlanner.PlanResultListener() {
                                        @Override
                                        public void onCalculated(InstallationPlanner.StorageProjection projection) {
                                            if (projection.resolvedFilename != null)
                                                downloadAndIndexKiwix(projection.resolvedFilename, debianRootfs);
                                            else runMapsAnsible(debianRootfs);
                                        }

                                        @Override
                                        public void onError(String error) {
                                            runMapsAnsible(debianRootfs);
                                        }
                                    });
                                } else {
                                    finishInstallationSuccess();
                                }
                            }

                            @Override
                            public void onError(String error) {
                                abortInstallation(getString(R.string.install_error_extraction, error));
                            }
                        });
                    }

                    @Override
                    public void onError(String error) {
                        abortInstallation(getString(R.string.install_error_download, error));
                    }
                });
            };

            if (debianRootfs.exists() && debianRootfs.isDirectory()) {
                new android.app.AlertDialog.Builder(requireContext())
                        .setTitle(R.string.install_btn_reinstall)
                        .setMessage(R.string.install_dialog_wipe_msg)
                        .setPositiveButton(R.string.install_btn_yes, (dialog, which) -> {
                            btnFastInstall.setText(R.string.install_status_wiping_old);
                            btnFastInstall.setEnabled(false);
                            new Thread(() -> {
                                try {
                                    Runtime.getRuntime().exec(new String[]{"rm", "-rf", debianRootfs.getAbsolutePath()}).waitFor();
                                } catch (Exception ignored) {
                                }
                                mainAct.runOnUiThread(executeDownload);
                            }).start();
                        })
                        .setNegativeButton(R.string.install_btn_no, null)
                        .show();
            } else {
                executeDownload.run();
            }
        });
    }

    private void bindDeleteButtonLogic(MainActivity mainAct, File debianRootfs) {
        btnFastDelete.setOnClickListener(v -> {
            new android.app.AlertDialog.Builder(requireContext())
                    .setTitle(R.string.install_dialog_delete_title)
                    .setMessage(R.string.install_dialog_delete_msg)
                    .setPositiveButton(R.string.install_btn_delete_confirm, (dialog, which) -> {
                        mainAct.invalidateModuleStateTrust();
                        btnFastDelete.setEnabled(false);
                        Snackbar.make(getView(), R.string.install_status_deleting, Snackbar.LENGTH_SHORT).show();
                        new Thread(() -> {
                            enableSystemProtection();
                            try {
                                Process p = Runtime.getRuntime().exec(new String[]{"rm", "-rf", debianRootfs.getAbsolutePath()});
                                p.waitFor();
                                mainAct.runOnUiThread(this::updateDynamicButtons);
                            } catch (Exception e) {
                                mainAct.runOnUiThread(() -> Snackbar.make(getView(), getString(R.string.install_error_delete, e.getMessage()), Snackbar.LENGTH_LONG).show());
                            } finally {
                                disableSystemProtection();
                            }
                        }).start();
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        });
    }

    private void bindResetButtonLogic(MainActivity mainAct, File debianRootfs) {
        if (btnAdvancedReset == null) return;
        btnAdvancedReset.setOnClickListener(v -> {

            // 1. Safe Abortion Phase
            if (isDownloadingRootfs) {
                new android.app.AlertDialog.Builder(requireContext())
                        .setTitle(getString(R.string.install_btn_cancel_title))
                        .setMessage(getString(R.string.install_btn_cancel_msg))
                        .setPositiveButton(getString(R.string.install_btn_cancel_confirm), (dialog, which) -> {
                            if (aria2Manager != null) aria2Manager.stopDownload();
                            disableSystemProtection();
                            isDownloadingRootfs = false;

                            // We clean the remains of the failed download if they exist
                            String arch = getTermuxArch();
                            String archSuffix = (arch.contains("arm") && !arch.contains("64")) ? "arm" : "aarch64";
                            String tarball = "debian-trixie-" + archSuffix + "-pd-v4.29.0.tar.xz";
                            File partialDownload = new File("/storage/emulated/0/Download", tarball);
                            if (partialDownload.exists()) partialDownload.delete();
                            File ariaState = new File("/storage/emulated/0/Download", tarball + ".aria2");
                            if (ariaState.exists()) ariaState.delete();

                            btnAdvancedReset.setText(getString(R.string.install_btn_reset));
                            btnAdvancedReset.setEnabled(true);
                            updateDynamicButtons();
                            Snackbar.make(getView(), R.string.install_msg_cancelled, Snackbar.LENGTH_SHORT).show();
                        })
                        .setNegativeButton(R.string.cancel, null)
                        .show();
                return;
            }

            // 2. IF THE SYSTEM IS ALREADY DOING SOMETHING ELSE OR HAS ALREADY STARTED EXTRACTING
            if (btnAdvancedReset.getText().toString().contains("Extract") || btnAdvancedReset.getText().toString().contains("Bootstrap")) {
                Snackbar.make(getView(), "System extraction in progress. Please do not interrupt.", Snackbar.LENGTH_SHORT).show();
                return;
            }

            // 3. NORMAL STATE: RESET START
            new android.app.AlertDialog.Builder(requireContext())
                    .setTitle(R.string.install_dialog_reset_title)
                    .setMessage(R.string.install_dialog_reset_msg)
                    .setPositiveButton(R.string.install_dialog_reset_confirm, (dialog, which) -> {
                        // IMMEDIATE LOCK AGAINST DOUBLE PRESS
                        if (isDownloadingRootfs) return;
                        isDownloadingRootfs = true;

                        mainAct.invalidateModuleStateTrust();
                        final String originalText = getString(R.string.install_btn_reset);

                        // We enable the button but change the text to serve as "Cancel"
                        btnAdvancedReset.setEnabled(true);

                        new Thread(() -> {
                            enableSystemProtection();
                            try {
                                mainAct.runOnUiThread(() -> {
                                    btnAdvancedReset.setText(getString(R.string.install_status_wiping_old));
                                    Snackbar.make(getView(), R.string.install_status_starting_vanilla, Snackbar.LENGTH_SHORT).show();
                                });

                                // 1. WIPE
                                Runtime.getRuntime().exec(new String[]{"rm", "-rf", debianRootfs.getAbsolutePath()}).waitFor();
                                debianRootfs.mkdirs();

                                // 2. DOWNLOAD
                                mainAct.runOnUiThread(() -> btnAdvancedReset.setText(getString(R.string.install_status_downloading_debian)));
                                if (aria2Manager == null) aria2Manager = new Aria2Manager();

                                String arch = getTermuxArch();
                                String archSuffix = (arch.contains("arm") && !arch.contains("64")) ? "arm" : "aarch64";
                                String tarball = "debian-trixie-" + archSuffix + "-pd-v4.29.0.tar.xz";
                                String url = "https://iiab.switnet.org/android/rootfs/proot-distro-v4.29.0/" + tarball;

                                aria2Manager.startDownload(requireContext(), url, new Aria2Manager.DownloadListener() {
                                    @Override
                                    public void onProgress(int percentage, String speed, String eta) {
                                        if (isAdded() && getActivity() != null) {
                                            getActivity().runOnUiThread(() -> {
                                                // We keep it visible that you can cancel
                                                btnAdvancedReset.setText(getString(R.string.install_status_debian_download, percentage, speed) + " (Tap to Cancel)");
                                            });
                                        }
                                    }

                                    @Override
                                    public void onComplete(String downloadPath) {
                                        new Thread(() -> {
                                            try {
                                                // 3. EXTRACT
                                                isDownloadingRootfs = false;
                                                mainAct.runOnUiThread(() -> {
                                                    btnAdvancedReset.setText(getString(R.string.install_status_extracting_base));
                                                    btnAdvancedReset.setEnabled(false); // <--- SAFE DOOR CLOSURE
                                                });

                                                File downloadedArchive = new File(downloadPath, tarball);
                                                File staticTar = new File(requireContext().getApplicationInfo().nativeLibraryDir, "libtar.so");
                                                File staticXz = new File(requireContext().getApplicationInfo().nativeLibraryDir, "libxz.so");
                                                String tarBin = staticTar.exists() ? staticTar.getAbsolutePath() : "tar";
                                                String xzBin = staticXz.exists() ? staticXz.getAbsolutePath() : "xz";

                                                // Pipe xz directly into tar to bypass Android's limited PATH
                                                String extractCmd = xzBin + " -d -c " + downloadedArchive.getAbsolutePath() + " | " + tarBin + " --exclude='*/dev/*' --strip-components=1 -xf - -C " + debianRootfs.getAbsolutePath();

                                                Process pExt = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", extractCmd});
                                                BufferedReader errReader = new BufferedReader(new java.io.InputStreamReader(pExt.getErrorStream()));
                                                StringBuilder errMsg = new StringBuilder();
                                                String errLine;
                                                while ((errLine = errReader.readLine()) != null) {
                                                    errMsg.append(errLine).append("\n");
                                                    android.util.Log.e(TAG, "[TAR Extractor] " + errLine);
                                                }

                                                int exitCode = pExt.waitFor();
                                                if (exitCode != 0) {
                                                    throw new Exception("Extraction failed (Code " + exitCode + "):\n" + errMsg.toString());
                                                }

                                                downloadedArchive.delete();

                                                // 4. BOOTSTRAP IIAB
                                                mainAct.runOnUiThread(() -> btnAdvancedReset.setText(getString(R.string.install_status_bootstrapping)));

                                                File resolvConf = new File(debianRootfs, "etc/resolv.conf");
                                                if (resolvConf.exists()) resolvConf.delete();
                                                java.io.FileOutputStream fos = new java.io.FileOutputStream(resolvConf);
                                                fos.write("nameserver 1.1.1.1\nnameserver 8.8.8.8\n".getBytes());
                                                fos.close();

                                                File hostsFile = new File(debianRootfs, "etc/hosts");
                                                java.io.FileOutputStream fosH = new java.io.FileOutputStream(hostsFile);
                                                fosH.write("127.0.0.1 localhost\n".getBytes());
                                                fosH.close();

                                                if (prootEngine == null)
                                                    prootEngine = new PRootEngine();
                                                String bootstrapCmd = "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin && " +
                                                        "export DEBIAN_FRONTEND=noninteractive && " +
                                                        "apt-get update && apt-get install -y curl ca-certificates nano sudo && " +
                                                        "curl -fsSL https://raw.githubusercontent.com/iiab/iiab-android/main/iiab-android -o /usr/local/sbin/iiab-android && " +
                                                        "chmod +x /usr/local/sbin/iiab-android && " +
                                                        "apt-get clean && apt-get autoremove -y && rm -rf /var/lib/apt/lists/* /tmp/* /root/.cache";

                                                prootEngine.executeInContainer(requireContext(), debianRootfs.getAbsolutePath(), "/bin/bash -c '" + bootstrapCmd + "'", new PRootEngine.OutputListener() {
                                                    @Override
                                                    public void onOutputLine(String line) {
                                                        mainAct.runOnUiThread(() -> mainAct.addToLog("[Bootstrap] " + line));
                                                    }

                                                    @Override
                                                    public void onProcessExit(int exitCode) {
                                                        mainAct.runOnUiThread(() -> {
                                                            disableSystemProtection();
                                                            btnAdvancedReset.setText(originalText);
                                                            btnAdvancedReset.setEnabled(true);
                                                            updateDynamicButtons();
                                                            Snackbar.make(getView(), R.string.install_success_vanilla, Snackbar.LENGTH_LONG).show();
                                                        });
                                                    }

                                                    @Override
                                                    public void onError(String error) {
                                                        mainAct.runOnUiThread(() -> {
                                                            disableSystemProtection();
                                                            btnAdvancedReset.setText(originalText);
                                                            btnAdvancedReset.setEnabled(true);
                                                            updateDynamicButtons();
                                                            Snackbar.make(getView(), getString(R.string.install_error_bootstrap, error), Snackbar.LENGTH_LONG).show();
                                                        });
                                                    }
                                                });

                                            } catch (Exception e) {
                                                mainAct.runOnUiThread(() -> {
                                                    isDownloadingRootfs = false;
                                                    disableSystemProtection();
                                                    btnAdvancedReset.setText(originalText);
                                                    btnAdvancedReset.setEnabled(true);
                                                    updateDynamicButtons();
                                                    Snackbar.make(getView(), getString(R.string.install_error_extract_bootstrap, e.getMessage()), Snackbar.LENGTH_LONG).show();
                                                });
                                            }
                                        }).start();
                                    }

                                    @Override
                                    public void onError(String error) {
                                        mainAct.runOnUiThread(() -> {
                                            isDownloadingRootfs = false;
                                            disableSystemProtection();
                                            btnAdvancedReset.setText(originalText);
                                            btnAdvancedReset.setEnabled(true);
                                            updateDynamicButtons();
                                            Snackbar.make(getView(), getString(R.string.install_error_download, error), Snackbar.LENGTH_LONG).show();
                                        });
                                    }
                                });

                            } catch (Exception e) {
                                mainAct.runOnUiThread(() -> {
                                    isDownloadingRootfs = false;
                                    disableSystemProtection();
                                    btnAdvancedReset.setText(originalText);
                                    btnAdvancedReset.setEnabled(true);
                                    updateDynamicButtons();
                                    Snackbar.make(getView(), getString(R.string.install_error_reset, e.getMessage()), Snackbar.LENGTH_LONG).show();
                                });
                            }
                        }).start();
                    })
                    .setNegativeButton(R.string.install_dialog_reset_cancel, null)
                    .show();
        });
    }

    private void processNextInQueue() {
        if (installationQueue.isEmpty()) {
            isBatchInstalling = false;
            saveQueueToPrefs();
            btnLaunchInstall.setEnabled(false);
            btnLaunchInstall.setText(getString(R.string.install_btn_launch));
            fetchLocalVarsFromPRoot();
            if (getView() != null)
                Snackbar.make(getView(), R.string.install_msg_finished, Snackbar.LENGTH_LONG).show();
            return;
        }

        String nextModule = installationQueue.remove(0);
        saveQueueToPrefs();
        btnLaunchInstall.setEnabled(false);
        btnLaunchInstall.setText(getString(R.string.install_status_installing_module, nextModule));

        File rootfsDir = new File(requireContext().getFilesDir(), "rootfs/installed-rootfs/iiab");
        if (prootEngine == null) prootEngine = new PRootEngine();

        String installCmd = "sed -i -E '/^[[:space:]]*" + nextModule + "_(install|enabled)[[:space:]]*:/d' /etc/iiab/local_vars.yml && " +
                "echo '" + nextModule + "_install: True' >> /etc/iiab/local_vars.yml && " +
                "echo '" + nextModule + "_enabled: True' >> /etc/iiab/local_vars.yml && " +
                "cd /opt/iiab/iiab && ./runrole " + nextModule;

        prootEngine.executeInContainer(requireContext(), rootfsDir.getAbsolutePath(), installCmd, new PRootEngine.OutputListener() {
            @Override
            public void onOutputLine(String line) {
                if (getActivity() instanceof MainActivity)
                    ((MainActivity) getActivity()).runOnUiThread(() -> ((MainActivity) getActivity()).addToLog("[Ansible] " + line));
            }

            @Override
            public void onProcessExit(int exitCode) {
                if (getActivity() != null) getActivity().runOnUiThread(() -> processNextInQueue());
            }

            @Override
            public void onError(String error) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        isBatchInstalling = false;
                        updateDynamicButtons();
                        if (getView() != null)
                            Snackbar.make(getView(), getString(R.string.install_error_bootstrap, error), Snackbar.LENGTH_LONG).show();
                    });
                }
            }
        });
    }

    private void editLocalVarsForMaps(File debianRootfs, InstallationPlanner.Tier tier) {
        File yamlFile = new File(debianRootfs, "etc/iiab/local_vars.yml");
        if (!yamlFile.exists()) return;
        try {
            StringBuilder content = new StringBuilder();
            BufferedReader reader = new BufferedReader(new FileReader(yamlFile));
            String line;
            while ((line = reader.readLine()) != null) content.append(line).append("\n");
            reader.close();

            String text = content.toString();
            // Always make sure the installation and module are active
            text = text.replaceAll("(?m)^maps_install:\\s*.*", "maps_install: True");
            text = text.replaceAll("(?m)^maps_enabled:\\s*.*", "maps_enabled: True");

            if (tier == InstallationPlanner.Tier.BASIC) {
                // BASIC TIER ~0.2GB
                // Note: The current base image already has these default values.
                // We leave them commented for the future in case the base image changes.
                /*
                text = text.replaceAll("(?m)^maps_vector_quality:\\s*.*", "maps_vector_quality: nat-z8");
                text = text.replaceAll("(?m)^maps_satellite_zoom:\\s*.*", "maps_satellite_zoom: 7");
                text = text.replaceAll("(?m)^maps_terrain_zoom:\\s*.*", "maps_terrain_zoom: none");
                */
            } else if (tier == InstallationPlanner.Tier.STANDARD) {
                // STANDARD TIER ~11GB
                text = text.replaceAll("(?m)^maps_vector_quality:\\s*.*", "maps_vector_quality: osm-z11");
                text = text.replaceAll("(?m)^maps_satellite_zoom:\\s*.*", "maps_satellite_zoom: 9");
                text = text.replaceAll("(?m)^maps_terrain_zoom:\\s*.*", "maps_terrain_zoom: 7");
            } else if (tier == InstallationPlanner.Tier.FULL) {
                // FULL TIER ~16GB
                text = text.replaceAll("(?m)^maps_vector_quality:\\s*.*", "maps_vector_quality: osm-z11");
                text = text.replaceAll("(?m)^maps_satellite_zoom:\\s*.*", "maps_satellite_zoom: 9");
                text = text.replaceAll("(?m)^maps_terrain_zoom:\\s*.*", "maps_terrain_zoom: 8");
            }

            java.io.FileWriter writer = new java.io.FileWriter(yamlFile);
            writer.write(text);
            writer.close();
        } catch (Exception ignored) {
        }
    }

    private void downloadAndIndexKiwix(String zimFilename, File debianRootfs) {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> btnFastInstall.setText(R.string.install_status_preparing_kiwix));

        String zimUrl = "https://download.kiwix.org/zim/wikipedia/" + zimFilename;
        File libraryDir = new File(debianRootfs, "library/zims/content");
        if (!libraryDir.exists()) libraryDir.mkdirs();

        if (aria2Manager == null) aria2Manager = new Aria2Manager();
        aria2Manager.startDownload(requireContext(), zimUrl, new Aria2Manager.DownloadListener() {
            @Override
            public void onProgress(int percentage, String speed, String eta) {
                if (isAdded() && getActivity() != null)
                    getActivity().runOnUiThread(() -> btnFastInstall.setText(getString(R.string.install_status_zim_download, percentage, speed)));
            }

            @Override
            public void onComplete(String downloadPath) {
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> btnFastInstall.setText(R.string.install_status_indexing_zim));
                File downloadedZim = new File(downloadPath, zimFilename);
                if (downloadedZim.exists())
                    downloadedZim.renameTo(new File(libraryDir, zimFilename));

                if (prootEngine == null) prootEngine = new PRootEngine();
                prootEngine.executeInContainer(requireContext(), debianRootfs.getAbsolutePath(), "iiab-make-kiwix-lib", new PRootEngine.OutputListener() {
                    @Override
                    public void onOutputLine(String line) {
                        if (getActivity() instanceof MainActivity)
                            ((MainActivity) getActivity()).runOnUiThread(() -> ((MainActivity) getActivity()).addToLog("[Kiwix] " + line));
                    }

                    @Override
                    public void onProcessExit(int exitCode) {
                        runMapsAnsible(debianRootfs);
                    }

                    @Override
                    public void onError(String error) {
                        runMapsAnsible(debianRootfs);
                    }
                });
            }

            @Override
            public void onError(String error) {
                runMapsAnsible(debianRootfs);
            }
        });
    }

    private void runMapsAnsible(File debianRootfs) {
        if (getActivity() == null) return;

        InstallationPlanner.Tier safeTier = (selectedTier != null) ? selectedTier : InstallationPlanner.Tier.BASIC;

        if (safeTier == InstallationPlanner.Tier.BASIC) {
            // BYPASS PARA BASIC
            getActivity().runOnUiThread(() -> btnFastInstall.setText(R.string.install_status_maps_provisioned));
            new Handler(Looper.getMainLooper()).postDelayed(this::finishInstallationSuccess, 1500);
            return;
        }

        getActivity().runOnUiThread(() -> btnFastInstall.setText(R.string.install_status_maps_configuring));

        if (prootEngine == null) prootEngine = new PRootEngine();
        String installCmd = "cd /opt/iiab/iiab && ./runrole --reinstall maps";

        prootEngine.executeInContainer(requireContext(), debianRootfs.getAbsolutePath(), installCmd, new PRootEngine.OutputListener() {
            @Override
            public void onOutputLine(String line) {
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).runOnUiThread(() -> ((MainActivity) getActivity()).addToLog("[Ansible] " + line));
                }
            }

            @Override
            public void onProcessExit(int exitCode) {
                finishInstallationSuccess();
            }

            @Override
            public void onError(String error) {
                finishInstallationSuccess();
            }
        });
    }

    private void finishInstallationSuccess() {
        disableSystemProtection();
        isDownloadingRootfs = false;
        if (isAdded() && getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                btnFastInstall.setText(R.string.install_btn_reinstall);
                btnFastInstall.setAlpha(1.0f);
                updateDynamicButtons();
                requestFreshLocalVarsSilently();
                if (getView() != null)
                    Snackbar.make(getView(), R.string.install_success_deployment, Snackbar.LENGTH_LONG).show();
            });
        }
    }

    private void abortInstallation(String message) {
        disableSystemProtection();
        isDownloadingRootfs = false;
        if (isAdded() && getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                btnFastInstall.setText(R.string.install_btn_install);
                btnFastInstall.setAlpha(1.0f);
                updateDynamicButtons();
                if (getView() != null)
                    Snackbar.make(getView(), message, Snackbar.LENGTH_LONG).show();
            });
        }
    }

    private void fetchLocalVarsFromPRoot() {
        File rootfsDir = new File(requireContext().getFilesDir(), "rootfs/installed-rootfs/iiab");
        File localVarsFile = new File(rootfsDir, "etc/iiab/local_vars.yml");

        if (!rootfsDir.exists() || !rootfsDir.isDirectory() || !localVarsFile.exists()) {
            lastKnownState = new JSONObject();
            verifyInstallationState(lastKnownState);
            return;
        }

        new Thread(() -> {
            try {
                StringBuilder yamlOutput = new StringBuilder();
                BufferedReader br = new BufferedReader(new FileReader(localVarsFile));
                String line;
                while ((line = br.readLine()) != null) {
                    yamlOutput.append(line).append("\n");
                }
                br.close();

                JSONObject freshVars = parseYamlToJson(yamlOutput.toString());
                lastKnownState = freshVars;

                if (getActivity() instanceof MainActivity) {
                    getActivity().getSharedPreferences("iiab_queue_prefs", Context.MODE_PRIVATE)
                            .edit().putBoolean("is_module_state_trusted", true).apply();
                }

                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> verifyInstallationState(freshVars));
                }
            } catch (Exception e) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> verifyInstallationState(lastKnownState));
                }
            }
        }).start();
    }

    private JSONObject parseYamlToJson(String yaml) {
        JSONObject json = new JSONObject();
        String[] lines = yaml.split("\n");
        for (String line : lines) {
            if (line.contains(":") && !line.trim().startsWith("#")) {
                String[] parts = line.split(":", 2);
                String key = parts[0].trim();
                String val = parts[1].trim().toLowerCase();

                if (key.endsWith("_install") || key.endsWith("_enabled")) {
                    try {
                        boolean isTrue = val.equals("true") || val.equals("yes") || val.equals("1");
                        json.put(key, isTrue);
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return json;
    }

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

                    MainActivity mainAct = (MainActivity) getActivity();
                    boolean isRunning = mainAct != null && mainAct.isServerAlive;
                    boolean isTrusted = mainAct != null && mainAct.isModuleStateTrusted();

                    boolean isConfirmedInstalled;
                    boolean isDiscrepancy;

                    if (isRunning) {
                        isConfirmedInstalled = yamlState && pingState;
                        isDiscrepancy = yamlState != pingState;
                    } else {
                        isConfirmedInstalled = yamlState;
                        isDiscrepancy = yamlState && !isTrusted;
                    }

                    final boolean finalConfirmed = isConfirmedInstalled;
                    final boolean finalDiscrepancyFlag = isDiscrepancy;
                    final boolean finalIsRunning = isRunning;

                    getActivity().runOnUiThread(() -> {
                        card.setOnClickListener(null);
                        checkBox.setOnCheckedChangeListener(null);

                        if (finalConfirmed && !finalDiscrepancyFlag) {
                            checkBox.setVisibility(View.GONE);
                            led.setVisibility(View.VISIBLE);
                            led.setBackgroundTintList(null);

                            if (finalIsRunning) {
                                led.setBackgroundResource(R.drawable.led_on_green);
                                card.setOnClickListener(v -> Snackbar.make(v, R.string.install_msg_confirmed, Snackbar.LENGTH_LONG).show());
                            } else {
                                led.setBackgroundResource(R.drawable.led_on_green);
                                led.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#9C27B0")));
                                card.setOnClickListener(v -> Snackbar.make(v, R.string.install_msg_offline_trusted, Snackbar.LENGTH_LONG).show());
                            }
                        } else if (finalDiscrepancyFlag) {
                            checkBox.setVisibility(View.GONE);
                            led.setVisibility(View.VISIBLE);
                            led.setBackgroundResource(R.drawable.led_off);
                            led.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#FFC107")));
                            card.setOnClickListener(v -> Snackbar.make(v, R.string.install_warning_discrepancy_msg, Snackbar.LENGTH_LONG).show());
                        } else {
                            led.setVisibility(View.GONE);
                            checkBox.setVisibility(View.VISIBLE);
                            checkBox.setChecked(false);

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

                            if (!newInstallCheckboxes.contains(checkBox))
                                newInstallCheckboxes.add(checkBox);
                            checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> evaluateLaunchButton());
                        }
                    });

                    if (finalDiscrepancyFlag) discrepancyFound = true;
                }
            }

            final boolean finalDiscrepancy = discrepancyFound;
            getActivity().runOnUiThread(() -> {
                if (discrepancyWarning != null)
                    discrepancyWarning.setVisibility(finalDiscrepancy ? View.VISIBLE : View.GONE);
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

    // =========================================================================================
    // REGION 6: BACKUP & RESTORE SAF
    // =========================================================================================

    private void setupSafLaunchers() {
        importBackupLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri != null) importBackupSafely(uri);
        });

        exportBackupLauncher = registerForActivityResult(new ActivityResultContracts.CreateDocument("application/gzip"), uri -> {
            if (uri != null && selectedBackupFile != null)
                exportBackupSafely(uri, selectedBackupFile);
        });
    }

    private void bindBackupButtonLogic(MainActivity mainAct, File backupsDir, File iiabRootDir) {
        if (btnAdvancedBackup == null) return;
        btnAdvancedBackup.setOnClickListener(v -> {
            if (isBackupInProgress) {
                new android.app.AlertDialog.Builder(requireContext())
                        .setTitle(getString(R.string.install_msg_backup_in_progress_title))
                        .setMessage(getString(R.string.install_msg_backup_in_progress_body))
                        .setPositiveButton(getString(R.string.install_btn_force_stop_process), (dialog, which) -> {
                            isBackupInProgress = false;
                            btnAdvancedBackup.setText(getString(R.string.install_btn_backup));
                            Snackbar.make(getView(), getString(R.string.install_msg_backup_aborted), Snackbar.LENGTH_SHORT).show();
                        })
                        .setNegativeButton(getString(R.string.install_btn_let_finish), null)
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .show();
                return;
            }

            isBackupInProgress = true;
            btnAdvancedBackup.setText(getString(R.string.install_msg_compressing));
            Snackbar.make(v, getString(R.string.install_msg_creating_backup), Snackbar.LENGTH_LONG).show();

            new Thread(() -> {
                enableSystemProtection();
                try {
                    // Format: iiab-oa_rootfs_$year.$day_of_year_3_digits_$id_$arch.tar.gz
                    java.util.Calendar calendar = java.util.Calendar.getInstance();
                    int year = calendar.get(java.util.Calendar.YEAR);
                    int dayOfYear = calendar.get(java.util.Calendar.DAY_OF_YEAR);
                    String arch = getTermuxArch();

                    // --- AUTO-INCREMENTAL ID LOGIC ---
                    android.content.SharedPreferences prefs = requireContext().getSharedPreferences(getString(R.string.pref_file_internal), Context.MODE_PRIVATE);

                    // We check if we continue on the same day. If it is a new day, we reset the ID to 1
                    int lastSavedDay = prefs.getInt("backup_last_day", -1);
                    int currentId;

                    if (lastSavedDay == dayOfYear) {
                        // Same day, we increase the ID
                        currentId = prefs.getInt("backup_daily_id", 0) + 1;
                    } else {
                        // New day, we start from 1
                        currentId = 1;
                        prefs.edit().putInt("backup_last_day", dayOfYear).apply();
                    }

                    // We save the new ID in preferences for next time
                    prefs.edit().putInt("backup_daily_id", currentId).apply();

                    // We construct the final name with the ID
                    String fileName = String.format(java.util.Locale.US, "iiab-oa_%04d.%03d_%d_%s.tar.gz", year, dayOfYear, currentId, arch);
                    File backupFile = new File(backupsDir, fileName);

                    File staticTar = new File(requireContext().getApplicationInfo().nativeLibraryDir, "libtar.so");
                    File staticGzip = new File(requireContext().getApplicationInfo().nativeLibraryDir, "libgzip.so");
                    String tarBin = staticTar.exists() ? staticTar.getAbsolutePath() : "tar";
                    String gzipBin = staticGzip.exists() ? staticGzip.getAbsolutePath() : "gzip";

                    String cmd = tarBin + " -cf - -C " + iiabRootDir.getAbsolutePath() + " installed-rootfs | " + gzipBin + " > " + backupFile.getAbsolutePath();
                    Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", cmd});
                    int exitCode = p.waitFor();

                    mainAct.runOnUiThread(() -> {
                        if (isBackupInProgress) {
                            if (exitCode == 0) {
                                Snackbar.make(getView(), getString(R.string.install_msg_backup_complete, backupFile.getName()), Snackbar.LENGTH_LONG).show();
                                selectedBackupFile = backupFile.getName();
                            } else {
                                Snackbar.make(getView(), getString(R.string.install_msg_backup_failed, exitCode), Snackbar.LENGTH_LONG).show();
                                if (backupFile.exists()) backupFile.delete();

                                // If it fails, we revert the ID so as not to waste numbers
                                prefs.edit().putInt("backup_daily_id", currentId - 1).apply();
                            }
                        } else {
                            if (backupFile.exists()) backupFile.delete();
                            prefs.edit().putInt("backup_daily_id", currentId - 1).apply();
                        }
                        isBackupInProgress = false;
                        btnAdvancedBackup.setText(getString(R.string.install_btn_backup));
                        updateDynamicButtons();
                        disableSystemProtection();
                    });
                } catch (Exception e) {
                    mainAct.runOnUiThread(() -> {
                        isBackupInProgress = false;
                        btnAdvancedBackup.setText(getString(R.string.install_btn_backup));
                        Snackbar.make(getView(), getString(R.string.install_msg_backup_error, e.getMessage()), Snackbar.LENGTH_LONG).show();
                        updateDynamicButtons();
                        disableSystemProtection();
                    });
                }
            }).start();
        });

        if (btnImportBackup != null) {
            // 1. We load the native icon
            android.graphics.drawable.Drawable importIcon = ContextCompat.getDrawable(requireContext(), android.R.drawable.stat_sys_download);
            if (importIcon != null) {
                importIcon.setTint(Color.parseColor("#66BB6A"));
                btnImportBackup.setCompoundDrawablesWithIntrinsicBounds(importIcon, null, null, null);
                btnImportBackup.setCompoundDrawablePadding(24);

                // 2. We center the content internally
                btnImportBackup.setGravity(android.view.Gravity.CENTER);
                btnImportBackup.setPadding(0, 0, 0, 0);

                // 3. We change the width to wrap_content and center the button in its container
                if (btnImportBackup.getLayoutParams() instanceof LinearLayout.LayoutParams) {
                    LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) btnImportBackup.getLayoutParams();
                    params.width = ViewGroup.LayoutParams.WRAP_CONTENT;
                    params.gravity = android.view.Gravity.CENTER_HORIZONTAL;
                    btnImportBackup.setLayoutParams(params);
                }
            }

            btnImportBackup.setOnClickListener(v -> {
                importBackupLauncher.launch(new String[]{"application/gzip", "application/x-gzip", "*/*"});
            });
        }
    }

    private void bindBackupMenuLogic(File backupsDir) {
        if (txtSelectBackupTitle == null) return;
        txtSelectBackupTitle.setOnClickListener(v -> {
            boolean isCollapsed = containerBackupList.getVisibility() == View.GONE;
            if (isCollapsed) {
                containerBackupList.setVisibility(View.VISIBLE);
                txtSelectBackupTitle.setText(getString(R.string.install_adv_select_backup_open));
                containerBackupList.removeAllViews();
                selectedBackupFile = null;

                File[] backups = backupsDir.listFiles((dir, name) -> name.endsWith(".tar.gz") || name.endsWith(".tar.xz"));
                if (backups == null || backups.length == 0) {
                    TextView noBackups = new TextView(requireContext());
                    noBackups.setText(getString(R.string.install_msg_no_backups));
                    noBackups.setTextColor(Color.parseColor("#FF5555"));
                    containerBackupList.addView(noBackups);
                } else {
                    java.util.Arrays.sort(backups, (f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));

                    LinearLayout listContainer = new LinearLayout(requireContext());
                    listContainer.setOrientation(LinearLayout.VERTICAL);

                    List<android.widget.RadioButton> radioButtons = new ArrayList<>();
                    int iconPadding = (int) (12 * getResources().getDisplayMetrics().density);

                    // Variable to alternate colors (Zebra Effect)
                    boolean isEvenRow = true;

                    for (File b : backups) {
                        String filename = b.getName();
                        String size = String.format(java.util.Locale.US, "%.2f MB", b.length() / (1024.0 * 1024.0));
                        String date = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(new java.util.Date(b.lastModified()));

                        // MAIN ROW
                        LinearLayout row = new LinearLayout(requireContext());
                        row.setOrientation(LinearLayout.HORIZONTAL);
                        row.setGravity(android.view.Gravity.CENTER_VERTICAL);

                        // Apply subtle alternating background color
                        if (isEvenRow) {
                            row.setBackgroundColor(Color.parseColor("#11ffffff")); // Slightly lighter
                        } else {
                            row.setBackgroundColor(Color.TRANSPARENT); // Normal dark
                        }
                        isEvenRow = !isEvenRow; // Alternar para la siguiente fila

                        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                        rowParams.setMargins(0, 0, 0, 8); // Separation between cards
                        row.setLayoutParams(rowParams);
                        row.setPadding(8, 8, 8, 8);

                        // RADIO BUTTON AND TEXT
                        android.widget.RadioButton rb = new android.widget.RadioButton(requireContext());
                        rb.setText(getString(R.string.install_msg_backup_details, filename, size, date));
                        rb.setTextColor(ContextCompat.getColor(requireContext(), R.color.dash_text_primary));
                        rb.setPadding(0, 8, 0, 8);
                        rb.setTag(filename);

                        LinearLayout.LayoutParams rbParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                        rb.setLayoutParams(rbParams);
                        radioButtons.add(rb);

                        // Selection logic (Applied to the ENTIRE row, not just the radio button)
                        View.OnClickListener selectRowListener = rowView -> {
                            for (android.widget.RadioButton other : radioButtons) {
                                other.setChecked(other == rb);
                            }
                            selectedBackupFile = rb.isChecked() ? filename : null;
                            refreshRestoreButtonLogic();
                        };

                        // We assign the click to both the RadioButton and the parent Layout
                        rb.setOnClickListener(selectRowListener);
                        row.setOnClickListener(selectRowListener);

                        // EXPORT BUTTON
                        android.widget.ImageButton btnExport = new android.widget.ImageButton(requireContext());
                        btnExport.setImageResource(android.R.drawable.stat_sys_upload);
                        btnExport.setBackgroundColor(Color.TRANSPARENT);
                        btnExport.setColorFilter(Color.parseColor("#66BB6A"));
                        btnExport.setPadding(iconPadding, iconPadding, iconPadding, iconPadding);

                        btnExport.setOnClickListener(btn -> {
                            selectedBackupFile = filename;
                            for (android.widget.RadioButton other : radioButtons) {
                                other.setChecked(other == rb);
                            }
                            refreshRestoreButtonLogic();
                            exportBackupLauncher.launch(selectedBackupFile);
                        });

                        // DELETE BUTTON
                        android.widget.ImageButton btnDelete = new android.widget.ImageButton(requireContext());
                        btnDelete.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
                        btnDelete.setBackgroundColor(Color.TRANSPARENT);
                        btnDelete.setColorFilter(Color.parseColor("#F44336"));
                        btnDelete.setPadding(iconPadding, iconPadding, iconPadding, iconPadding);

                        btnDelete.setOnClickListener(btn -> {
                            new android.app.AlertDialog.Builder(requireContext())
                                    .setTitle(R.string.install_dialog_delete_backup_title)
                                    .setMessage(getString(R.string.install_dialog_delete_backup_msg, filename))
                                    .setPositiveButton(R.string.install_btn_delete_confirm, (dialog, which) -> {
                                        File toDelete = new File(backupsDir, filename);
                                        if (toDelete.delete()) {
                                            if (filename.equals(selectedBackupFile)) selectedBackupFile = null;
                                            txtSelectBackupTitle.performClick();
                                            txtSelectBackupTitle.performClick();
                                            Snackbar.make(getView(), R.string.install_msg_backup_deleted, Snackbar.LENGTH_SHORT).show();
                                        }
                                    })
                                    .setNegativeButton(R.string.cancel, null)
                                    .show();
                        });

                        row.addView(rb);
                        row.addView(btnExport);
                        row.addView(btnDelete);

                        listContainer.addView(row);
                    }
                    containerBackupList.addView(listContainer);
                }
                refreshRestoreButtonLogic();
            } else {
                containerBackupList.setVisibility(View.GONE);
                txtSelectBackupTitle.setText(getString(R.string.install_adv_select_backup));
            }
        });
    }

    private void refreshRestoreButtonLogic() {
        MainActivity mainAct = (MainActivity) getActivity();
        if (mainAct == null || btnAdvancedRestore == null) return;

        if (mainAct.isServerAlive) {
            btnAdvancedRestore.setAlpha(0.5f);
            btnAdvancedRestore.setOnClickListener(v -> Snackbar.make(v, R.string.install_msg_server_running_lock, Snackbar.LENGTH_LONG).show());
            return;
        }

        if (selectedBackupFile == null) {
            btnAdvancedRestore.setAlpha(0.5f);
            btnAdvancedRestore.setOnClickListener(v -> Snackbar.make(v, R.string.install_msg_select_backup_first, Snackbar.LENGTH_LONG).show());
        } else {
            btnAdvancedRestore.setAlpha(1.0f);
            btnAdvancedRestore.setOnClickListener(v -> {
                Snackbar.make(v, getString(R.string.install_msg_restore_starting, selectedBackupFile), Snackbar.LENGTH_SHORT).show();
                mainAct.invalidateModuleStateTrust();

                File backupFile = new File(new File(requireContext().getFilesDir(), "rootfs/backups"), selectedBackupFile);
                if (!backupFile.exists()) {
                    Snackbar.make(v, R.string.install_error_backup_missing, Snackbar.LENGTH_SHORT).show();
                    return;
                }

                btnAdvancedRestore.setEnabled(false);
                btnAdvancedRestore.setText(getString(R.string.install_status_restoring));
                File iiabRootDir = new File(requireContext().getFilesDir(), "rootfs");
                TarExtractor tarExtractor = new TarExtractor();

                enableSystemProtection();
                tarExtractor.startExtraction(requireContext(), backupFile.getAbsolutePath(), iiabRootDir.getAbsolutePath(), new TarExtractor.ExtractionListener() {
                    @Override
                    public void onComplete(String destDir) {
                        mainAct.runOnUiThread(() -> {
                            disableSystemProtection();
                            btnAdvancedRestore.setEnabled(true);
                            btnAdvancedRestore.setText(getString(R.string.install_btn_restore));
                            Snackbar.make(getView(), R.string.install_success_restore, Snackbar.LENGTH_LONG).show();
                            updateDynamicButtons();
                        });
                    }

                    @Override
                    public void onError(String error) {
                        mainAct.runOnUiThread(() -> {
                            disableSystemProtection();
                            btnAdvancedRestore.setEnabled(true);
                            btnAdvancedRestore.setText(getString(R.string.install_btn_restore));
                            Snackbar.make(getView(), getString(R.string.install_msg_restore_failed) + " " + error, Snackbar.LENGTH_LONG).show();
                            updateDynamicButtons();
                        });
                    }
                });
            });
        }
    }

    private void importBackupSafely(Uri sourceUri) {
        btnImportBackup.setEnabled(false);
        btnImportBackup.setText(getString(R.string.install_msg_importing));
        Snackbar.make(getView(), getString(R.string.install_msg_importing), Snackbar.LENGTH_LONG).show();

        new Thread(() -> {
            enableSystemProtection();
            try {
                File backupsDir = new File(requireContext().getFilesDir(), "rootfs/backups");
                if (!backupsDir.exists()) backupsDir.mkdirs();

                String fileName = "imported_backup_" + System.currentTimeMillis() + ".tar.gz";
                File destFile = new File(backupsDir, fileName);

                InputStream is = requireContext().getContentResolver().openInputStream(sourceUri);
                OutputStream os = new java.io.FileOutputStream(destFile);
                byte[] buffer = new byte[8192];
                int length;
                while ((length = is.read(buffer)) > 0) {
                    os.write(buffer, 0, length);
                }
                os.flush();
                os.close();
                is.close();

                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        btnImportBackup.setEnabled(true);
                        btnImportBackup.setText(getString(R.string.install_btn_import_backup));
                        selectedBackupFile = fileName;
                        updateDynamicButtons();
                        Snackbar.make(getView(), getString(R.string.install_msg_import_success), Snackbar.LENGTH_LONG).show();
                    });
                }
            } catch (Exception e) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        btnImportBackup.setEnabled(true);
                        btnImportBackup.setText(getString(R.string.install_btn_import_backup));
                        Snackbar.make(getView(), getString(R.string.install_msg_import_failed, e.getMessage()), Snackbar.LENGTH_LONG).show();
                    });
                }
            } finally {
                disableSystemProtection();
            }
        }).start();
    }

    private void exportBackupSafely(Uri destUri, String backupFileName) {
        Snackbar.make(getView(), getString(R.string.install_msg_exporting, backupFileName), Snackbar.LENGTH_LONG).show();

        new Thread(() -> {
            enableSystemProtection();
            try {
                File sourceFile = new File(new File(requireContext().getFilesDir(), "rootfs/backups"), backupFileName);
                InputStream is = new java.io.FileInputStream(sourceFile);
                OutputStream os = requireContext().getContentResolver().openOutputStream(destUri);
                byte[] buffer = new byte[8192];
                int length;
                while ((length = is.read(buffer)) > 0) {
                    os.write(buffer, 0, length);
                }
                os.flush();
                os.close();
                is.close();

                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        Snackbar.make(getView(), getString(R.string.install_msg_export_success), Snackbar.LENGTH_LONG).show();
                    });
                }
            } catch (Exception e) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        Snackbar.make(getView(), getString(R.string.install_msg_export_failed, e.getMessage()), Snackbar.LENGTH_LONG).show();
                    });
                }
            } finally {
                disableSystemProtection();
            }
        }).start();
    }


    // =========================================================================================
    // REGION 7: ADB & SYSTEM RESTRICTIONS
    // =========================================================================================

    private void setupAdbNetworking() {
        android.net.wifi.WifiManager wifi = (android.net.wifi.WifiManager) requireContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            multicastLock = wifi.createMulticastLock("iiab_adb_multicast_lock");
            multicastLock.setReferenceCounted(true);
        }
    }

    private void setupAdbListeners() {
        LinearLayout containerDcpr = getView().findViewById(R.id.container_led_dcpr);
        containerDcpr.setOnClickListener(v -> {
            if (isConnectedToAdb && android.os.Build.VERSION.SDK_INT >= 34) {
                IIABAdbManager adbManager = IIABAdbManager.getInstance(requireContext());
                adbManager.executeCommand("settings put global settings_enable_monitor_phantom_procs 0");
                Snackbar.make(v, R.string.adb_snack_disabling_cp, Snackbar.LENGTH_SHORT).show();
                new Handler(Looper.getMainLooper()).postDelayed(() -> adbManager.checkSystemRestrictions(requireContext()), 1000);
            }
        });

        LinearLayout containerPpk = getView().findViewById(R.id.container_led_ppk);
        containerPpk.setOnClickListener(v -> {
            if (isConnectedToAdb && android.os.Build.VERSION.SDK_INT >= 31) {
                IIABAdbManager adbManager = IIABAdbManager.getInstance(requireContext());
                adbManager.executeCommand("device_config put activity_manager max_phantom_processes 256");
                Snackbar.make(v, R.string.adb_snack_setting_ppk, Snackbar.LENGTH_SHORT).show();
                new Handler(Looper.getMainLooper()).postDelayed(() -> adbManager.checkSystemRestrictions(requireContext()), 1000);
            }
        });

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
    }

    private void registerAdbReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction("org.iiab.controller.ADB_PAIRING_SUCCESSFUL");
        filter.addAction("org.iiab.controller.ADB_PAIRING_FAILED");
        filter.addAction("org.iiab.controller.ADB_PAIRING_SENT");
        filter.addAction("org.iiab.controller.ADB_CPU_UPDATE");
        filter.addAction("org.iiab.controller.ADB_RESTRICTIONS_UPDATE");
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(adbUiUpdateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            requireContext().registerReceiver(adbUiUpdateReceiver, filter);
        }
    }

    private void updateUiState(boolean isConnected) {
        btnAdbAction.setEnabled(true);
        if (isConnected) {
            ledAdbStatus.setBackgroundResource(R.drawable.led_on_green);
            txtAdbLedLabel.setText(getString(R.string.adb_status_connected));
            txtAdbLedLabel.setTextColor(Color.parseColor("#4CAF50"));
            btnAdbAction.setText(getString(R.string.adb_btn_disconnect));
            btnAdbAction.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#F44336")));

            txtDcpr.setText(android.text.Html.fromHtml(getString(R.string.adb_ui_checking_cp), android.text.Html.FROM_HTML_MODE_COMPACT));
            txtPpk.setText(android.text.Html.fromHtml(getString(R.string.adb_ui_checking_ppk), android.text.Html.FROM_HTML_MODE_COMPACT));
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

            txtDcpr.setText(getString(R.string.adb_ui_unknown_cp));
            txtPpk.setText(getString(R.string.adb_ui_unknown_ppk));
            ledDcpr.setBackgroundResource(R.drawable.led_off);
            ledDcpr.setBackgroundTintList(null);
            ledPpk.setBackgroundResource(R.drawable.led_off);
            ledPpk.setBackgroundTintList(null);
        }
    }

    private void startAdbPairingFlow() {
        startAdbPairingFlow(false);
    }

    private void startAdbPairingFlow(boolean isSilentScan) {
        isScanning = true;
        isAttemptingFastConnect = false;
        btnAdbAction.setText(getString(R.string.adb_btn_scanning));
        btnAdbAction.setEnabled(false);
        discoveredConnectPort = -1;
        discoveredPairingPort = -1;

        if (multicastLock != null && !multicastLock.isHeld()) multicastLock.acquire();

        connectDiscoveryListener = createDiscoveryListener(SERVICE_TYPE_CONNECT);
        pairingDiscoveryListener = createDiscoveryListener(SERVICE_TYPE_PAIRING);

        try {
            nsdManager.discoverServices(SERVICE_TYPE_CONNECT, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, connectDiscoveryListener);
            nsdManager.discoverServices(SERVICE_TYPE_PAIRING, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, pairingDiscoveryListener);
        } catch (Exception e) {
            resetScanState();
            return;
        }

        if (!isSilentScan) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (isScanning && !isConnectedToAdb) openDeveloperOptions();
            }, 4000);
        }

        new Handler(Looper.getMainLooper()).postDelayed(this::checkIfScanTimedOut, 90000);
    }

    private int getDynamicAdbPort(int fallbackPort) {
        try {
            Process process = Runtime.getRuntime().exec("getprop service.adb.tls.port");
            BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(process.getInputStream()));
            String portStr = reader.readLine();
            reader.close();
            if (portStr != null && !portStr.trim().isEmpty())
                return Integer.parseInt(portStr.trim());
        } catch (Exception ignored) {
        }
        return fallbackPort;
    }

    private void attemptFastConnection(String hostIp, int port) {
        if (isAttemptingFastConnect) return;
        isAttemptingFastConnect = true;

        Context appContext = requireContext().getApplicationContext();
        new Thread(() -> {
            boolean connected = false;
            IIABAdbManager adbManager = IIABAdbManager.getInstance(appContext);

            for (int i = 0; i < 6; i++) {
                try {
                    adbManager.connect(hostIp, port);
                    connected = true;
                    break;
                } catch (Exception e) {
                    try {
                        adbManager.disconnect();
                        Thread.sleep(600);
                    } catch (Exception ignored) {
                    }
                }
            }

            if (connected) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    stopDiscovery();
                    isConnectedToAdb = true;
                    if (btnAdbAction != null)
                        btnAdbAction.setText(getString(R.string.adb_status_connected));
                    updateUiState(true);
                });
                adbManager.startCpuMonitor(appContext);
                adbManager.checkSystemRestrictions(appContext);
            } else {
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
                String hostIp = serviceInfo.getHost().getHostAddress();
                String myIp = getLocalWifiIp();

                if (hostIp != null && !hostIp.equals(myIp) && !hostIp.equals("127.0.0.1")) return;

                new Handler(Looper.getMainLooper()).post(() -> {
                    discoveredHostIp = hostIp;
                    if (type.contains("connect")) {
                        discoveredConnectPort = port;
                        attemptFastConnection(hostIp, port);
                    } else if (type.contains("pairing")) {
                        discoveredPairingPort = port;
                    }

                    if (discoveredConnectPort != -1 && discoveredPairingPort != -1 && !isConnectedToAdb) {
                        stopDiscovery();
                        showPairingNotification(discoveredHostIp, discoveredConnectPort, discoveredPairingPort);
                        resetScanState();
                    }
                });
            }
        });
    }

    private void showPairingNotification(String hostIp, int connectPort, int pairingPort) {
        RemoteInput remoteInput = new RemoteInput.Builder(AdbPairingReceiver.KEY_PIN_REPLY).setLabel(getString(R.string.adb_notif_input_hint)).build();
        Intent replyIntent = new Intent(requireContext(), AdbPairingReceiver.class);
        replyIntent.putExtra("hostIp", hostIp);
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
        } finally {
            if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
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
        leftAxis.setTextColor(ContextCompat.getColor(requireContext(), R.color.dash_text_secondary));
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


    // =========================================================================================
    // REGION 8: UTILITIES
    // =========================================================================================

    private void enableSystemProtection() {
        // SAFE CHECK
        if (!isAdded() || getContext() == null) return;

        try {
            Intent intent = new Intent(requireContext(), WatchdogService.class);
            intent.setAction(WatchdogService.ACTION_START);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                requireContext().startForegroundService(intent);
            } else {
                requireContext().startService(intent);
            }
        } catch (Exception ignored) {
        }
    }

    private void disableSystemProtection() {
        // SAFE CHECK
        if (!isAdded() || getContext() == null) return;

        try {
            Intent intent = new Intent(requireContext(), WatchdogService.class);
//            intent.setAction(WatchdogService.ACTION_START);
            intent.setAction(WatchdogService.ACTION_STOP);
            requireContext().startService(intent);
        } catch (Exception ignored) {
        }
    }

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

    private String getLocalWifiIp() {
        android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) requireContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            int ip = wm.getConnectionInfo().getIpAddress();
            if (ip != 0)
                return String.format(java.util.Locale.US, "%d.%d.%d.%d", (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));
        }
        return "127.0.0.1";
    }

    private String getTermuxArch() {
        try {
            android.content.pm.ApplicationInfo info = requireContext().getApplicationInfo();
            String nativeLibDir = info.nativeLibraryDir;
            if (nativeLibDir != null) {
                if (nativeLibDir.endsWith("arm64") || nativeLibDir.contains("arm64-v8a"))
                    return "arm64-v8a";
                if (nativeLibDir.endsWith("arm") || nativeLibDir.contains("armeabi-v7a"))
                    return "armeabi-v7a";
                if (nativeLibDir.endsWith("x86_64") || nativeLibDir.contains("x86_64"))
                    return "x86_64";
                if (nativeLibDir.endsWith("x86") || nativeLibDir.contains("x86")) return "x86";
            }
        } catch (Exception ignored) {
        }
        if (android.os.Build.SUPPORTED_ABIS.length > 0) return android.os.Build.SUPPORTED_ABIS[0];
        return "unknown";
    }

    public void openTermuxAppInfo() {
        try {
            android.content.Intent intent = new android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            android.net.Uri uri = android.net.Uri.fromParts("package", requireContext().getPackageName(), null);
            intent.setData(uri);
            startActivity(intent);
        } catch (Exception ignored) {
        }
    }

    private void checkInternetAccess() {
        new Thread(() -> {
            boolean hasInternet = false;
            try {
                URL url = new URL("https://clients3.google.com/generate_204");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setUseCaches(false);
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(2000);
                conn.setRequestMethod("HEAD");
                hasInternet = (conn.getResponseCode() == 204 || conn.getResponseCode() == 200);
            } catch (Exception e) {
                hasInternet = false;
            }
            final boolean isOnline = hasInternet;
            if (isAdded() && getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (ledInternet != null) {
                        if (isOnline) {
                            ledInternet.setBackgroundResource(R.drawable.led_on_green);
                            ledInternet.setBackgroundTintList(null);
                        } else {
                            ledInternet.setBackgroundResource(R.drawable.led_off);
                            ledInternet.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#F44336")));
                        }
                    }
                });
            }
        }).start();
    }

    private void loadLocalVarsFallback() {
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
            } catch (Exception ignored) {
            }
        }
    }

    private void refreshDashboardLeds(MainActivity mainAct) {
        if (mainAct == null) return;
        boolean isDevModeOn = false;
        try {
            isDevModeOn = android.provider.Settings.Global.getInt(
                    requireContext().getContentResolver(),
                    android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0;
        } catch (Exception ignored) {
        }
        if (ledDevMode != null)
            ledDevMode.setBackgroundResource(isDevModeOn ? R.drawable.led_on_green : R.drawable.led_off);
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

    private void requestFreshLocalVars() {
        fetchLocalVarsFromPRoot();
    }

    private void requestFreshLocalVarsSilently() {
        fetchLocalVarsFromPRoot();
    }
}