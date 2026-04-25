/*
 * ============================================================================
 * Name        : DashboardFragment.java
 * Author      : IIAB Project
 * Copyright   : Copyright (c) 2026 IIAB Project
 * Description : Initial dasboard status activity
 * ============================================================================
 */
package org.iiab.controller;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class DashboardFragment extends Fragment {

    private TextView txtDeviceName;
    private TextView txtWifiIp, txtHotspotIp, txtUptime, txtBattery, badgeStatus, txtTermuxState;
    private ResourceGaugeView gaugeStorage, gaugeRam, gaugeSwap;
    private LinearLayout rootGauges, bottomRowGauges;
    private TextView txtTermuxArch, txtDebianArch;
    private LinearLayout archContainer;
    private String cachedTermuxArch = null;
    private String cachedDebianArch = null;
    private boolean isArchCalculated = false;
    private TextView modulesTitle;
    private View ledTermuxState;
    private LinearLayout modulesContainer;

    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private Runnable refreshRunnable;

    // --- MODULE CONFIGURATION TARGETING SCALABILITY ---
    private static class IiabModule {
        String endpoint;
        int nameResId;
        boolean requires64Bit;

        IiabModule(String endpoint, int nameResId, boolean requires64Bit) {
            this.endpoint = endpoint;
            this.nameResId = nameResId;
            this.requires64Bit = requires64Bit;
        }
    }

    // THE MASTER ROSTER: We can add, remove, or restrict modules here.
    private final java.util.List<IiabModule> MASTER_ROSTER = java.util.Arrays.asList(
            new IiabModule("books", R.string.dash_books, false),
            new IiabModule("code", R.string.dash_code, false),
            new IiabModule("kiwix", R.string.dash_kiwix, true),
            new IiabModule("kolibri", R.string.dash_kolibri, false),
            new IiabModule("maps", R.string.dash_maps, false),
            new IiabModule("matomo", R.string.dash_matomo, false),
            new IiabModule("dashboard", R.string.dash_system, false)
    );

    public enum SystemState {
        ONLINE, OFFLINE, DEBIAN_ONLY, INSTALLER, TERMUX_ONLY, NONE
    }

    private SystemState currentSystemState = SystemState.NONE;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_dashboard, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Bindings
        txtDeviceName = view.findViewById(R.id.dash_text_device_name);
        txtWifiIp = view.findViewById(R.id.dash_text_wifi_ip);
        txtHotspotIp = view.findViewById(R.id.dash_text_hotspot_ip);
        txtUptime = view.findViewById(R.id.dash_text_uptime);
        txtBattery = view.findViewById(R.id.dash_text_battery);
        badgeStatus = view.findViewById(R.id.dash_badge_status);

        gaugeStorage = view.findViewById(R.id.gauge_storage);
        gaugeRam = view.findViewById(R.id.gauge_ram);
        gaugeSwap = view.findViewById(R.id.gauge_swap);

        rootGauges = view.findViewById(R.id.dash_gauges_root);
        bottomRowGauges = view.findViewById(R.id.dash_gauges_bottom_row);

        // INDIVIDUAL ANIMATION (Only for gauges)
        View.OnClickListener animateIndividualClick = v -> {
            if (v instanceof ResourceGaugeView) {
                ((ResourceGaugeView) v).triggerAnimation();
            }
        };
        gaugeStorage.setOnClickListener(animateIndividualClick);
        gaugeRam.setOnClickListener(animateIndividualClick);
        gaugeSwap.setOnClickListener(animateIndividualClick);

        // GROUP ANIMATION (Only when touching the background/empty space)
        View.OnClickListener animateAllClick = v -> {
            if (gaugeStorage != null) gaugeStorage.triggerAnimation();
            if (gaugeRam != null) gaugeRam.triggerAnimation();
            if (gaugeSwap != null) gaugeSwap.triggerAnimation();
        };
        rootGauges.setOnClickListener(animateAllClick);
        bottomRowGauges.setOnClickListener(animateAllClick);
        adaptLayoutToOrientation(getResources().getConfiguration().orientation);

        ledTermuxState = view.findViewById(R.id.led_termux_state);
        txtTermuxState = view.findViewById(R.id.text_termux_state);
        txtTermuxArch = view.findViewById(R.id.dash_text_termux_arch);
        txtDebianArch = view.findViewById(R.id.dash_text_debian_arch);
        archContainer = view.findViewById(R.id.dash_arch_container);
        modulesContainer = view.findViewById(R.id.modules_container);
        modulesTitle = view.findViewById(R.id.dash_modules_title);

        modulesContainer.setVisibility(View.GONE);
        modulesTitle.setText(String.format(getString(R.string.label_separator_up), getString(R.string.dash_installed_modules)));

        // Listener to colapse/expande
        modulesTitle.setOnClickListener(v -> {
            boolean isGone = modulesContainer.getVisibility() == View.GONE;
            modulesContainer.setVisibility(isGone ? View.VISIBLE : View.GONE);
            modulesTitle.setText(String.format(getString(isGone ? R.string.label_separator_down : R.string.label_separator_up), getString(R.string.dash_installed_modules)));
        });

        // Generate module views dynamically
        createModuleViews();

        // Configure refresh timer (every 5 seconds)
        refreshRunnable = new Runnable() {
            @Override
            public void run() {
                updateSystemStats();
                checkServerAndModules();
                refreshHandler.postDelayed(this, 5000);
            }
        };
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshHandler.post(refreshRunnable);
        // TRIGGER ANIMATION WHEN ENTERING TAB
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (gaugeStorage != null) gaugeStorage.triggerAnimation();
            if (gaugeRam != null) gaugeRam.triggerAnimation();
            if (gaugeSwap != null) gaugeSwap.triggerAnimation();
        }, 100);
    }

    @Override
    public void onPause() {
        super.onPause();
        refreshHandler.removeCallbacks(refreshRunnable);
    }

    private void updateSystemStats() {
        txtDeviceName.setText(getDeviceName());

        // --- 0. CALCULATE SERVER UPTIME ---
        long uptimeMillis = android.os.SystemClock.elapsedRealtime();
        long minutes = (uptimeMillis / (1000 * 60)) % 60;
        long hours = (uptimeMillis / (1000 * 60 * 60)) % 24;
        long days = (uptimeMillis / (1000 * 60 * 60 * 24));

        // Format: "Uptime: 2d 14h 05m" (Omit days if 0)
        String timeStr = (days > 0) ?
                String.format(Locale.US, "%dd %02dh %02dm", days, hours, minutes) :
                String.format(Locale.US, "%02dh %02dm", hours, minutes);

        txtUptime.setText(timeStr);
        txtWifiIp.setText(getWifiIp());
        txtHotspotIp.setText(getHotspotIp());

        int batteryLevel = getBatteryPercentage();
        if (batteryLevel >= 0) {
            txtBattery.setText(batteryLevel + "%");
        } else {
            txtBattery.setText("--%");
        }

        // --- 1. GET REAL RAM AND SWAP FROM LINUX ---
        long memTotal = 0, memAvailable = 0, swapTotal = 0, swapFree = 0;
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("MemTotal:")) memTotal = parseMemLine(line);
                else if (line.startsWith("MemAvailable:")) memAvailable = parseMemLine(line);
                    // If phone is old and doesn't have "MemAvailable", use "MemFree"
                else if (memAvailable == 0 && line.startsWith("MemFree:"))
                    memAvailable = parseMemLine(line);
                else if (line.startsWith("SwapTotal:")) swapTotal = parseMemLine(line);
                else if (line.startsWith("SwapFree:")) swapFree = parseMemLine(line);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Convert the values from kB to GB (1 GB = 1048576 kB)
        double memTotalGb = memTotal / 1048576.0;
        double memUsedGb = (memTotal - memAvailable) / 1048576.0;
        int memProgress = memTotal > 0 ? (int) (((memTotal - memAvailable) * 100) / memTotal) : 0;

        double swapTotalGb = swapTotal / 1048576.0;
        double swapUsedGb = (swapTotal - swapFree) / 1048576.0;
        int swapProgress = swapTotal > 0 ? (int) (((swapTotal - swapFree) * 100) / swapTotal) : 0;

        File path = android.os.Environment.getDataDirectory();
        long totalSpace = path.getTotalSpace() / (1024 * 1024 * 1024); // To GB
        long freeSpace = path.getFreeSpace() / (1024 * 1024 * 1024);
        long usedSpace = totalSpace - freeSpace;

        // --- UPDATE GAUGE VIEWS (ANIMATED AND COLORED) ---
        int colorRam = ContextCompat.getColor(requireContext(), R.color.dash_bar_ram);
        int colorSwap = ContextCompat.getColor(requireContext(), R.color.dash_bar_swap);
        int colorStorage = ContextCompat.getColor(requireContext(), R.color.dash_bar_storage);

        // RAM Gauge
        String strRam = String.format(Locale.US, "%.2f GB / %.2f GB", memUsedGb, memTotalGb);
        if (gaugeRam != null)
            gaugeRam.updateData(memProgress, strRam, getString(R.string.dash_ram_memory), colorRam);

        // SWAP Gauge
        if (gaugeSwap != null) {
            if (swapTotal > 0) {
                String strSwap = String.format(Locale.US, "%.2f GB / %.2f GB", swapUsedGb, swapTotalGb);
                gaugeSwap.updateData(swapProgress, strSwap, getString(R.string.dash_swap_virtual), colorSwap);
            } else {
                gaugeSwap.updateData(0, "-- / --", getString(R.string.dash_swap_virtual), colorSwap);
            }
        }

        // STORAGE Gauge
        if (gaugeStorage != null) {
            String strStorage = usedSpace + " GB / " + totalSpace + " GB";
            int storageProgress = totalSpace > 0 ? (int) ((usedSpace * 100f) / totalSpace) : 0;
            gaugeStorage.updateData(storageProgress, strStorage, getString(R.string.dash_main_storage), colorStorage);
        }
    }

    private void createModuleViews() {
        modulesContainer.removeAllViews();

        // Determine if the installed Termux is 64-bit
        String arch = getTermuxArch();
        boolean is64Bit = arch != null && arch.contains("64");

        // Filter the Master Roster based on architecture support
        java.util.List<IiabModule> activeModules = new java.util.ArrayList<>();
        for (IiabModule module : MASTER_ROSTER) {
            if (module.requires64Bit && !is64Bit) {
                continue; // Skip this module entirely for 32-bit devices
            }
            activeModules.add(module);
        }

        // Build the UI grid dynamically using the filtered list
        int numCols = 3;
        int numRows = (int) Math.ceil((double) activeModules.size() / numCols);

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

                // Margins to prevent them from sticking together
                int margin = 8;
                if (col == 0) cellParams.setMargins(0, 0, margin, 0); // Left
                else if (col == 1) cellParams.setMargins(margin / 2, 0, margin / 2, 0); // Center
                else cellParams.setMargins(margin, 0, 0, 0); // Right

                cell.setLayoutParams(cellParams);

                if (index < activeModules.size()) {
                    IiabModule currentMod = activeModules.get(index); // Get the module object

                    cell.setOrientation(LinearLayout.HORIZONTAL);
                    cell.setBackgroundResource(R.drawable.rounded_button);
                    cell.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                            androidx.core.content.ContextCompat.getColor(requireContext(), R.color.dash_module_bg)));
                    cell.setPadding(16, 24, 16, 24);
                    cell.setGravity(android.view.Gravity.CENTER);

                    View led = new View(requireContext());
                    led.setLayoutParams(new LinearLayout.LayoutParams(20, 20));
                    led.setBackgroundResource(R.drawable.led_off);
                    led.setId(View.generateViewId());

                    TextView name = new TextView(requireContext());
                    LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    textParams.setMargins(12, 0, 0, 0);
                    name.setLayoutParams(textParams);

                    // Inject the data from the Master Roster
                    name.setText(getString(currentMod.nameResId));
                    name.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.dash_module_text));
                    name.setTextSize(11f);
                    name.setSingleLine(true);

                    cell.addView(led);
                    cell.addView(name);

                    // The background ping thread relies on this tag to check the URL!
                    cell.setTag(currentMod.endpoint);
                } else {
                    cell.setVisibility(View.INVISIBLE); // Fill empty spaces to keep grid aligned
                }

                rowLayout.addView(cell);
            }
            modulesContainer.addView(rowLayout);
        }
    }

    private void checkServerAndModules() {
        new Thread(() -> {
            // 1. Ping the network once
            boolean isMainServerAlive = pingUrl("http://localhost:8085/home");

            if (!isAdded() || getActivity() == null) return;

            // 2. Ask the State Machine for the definitive truth
            currentSystemState = evaluateSystemState(isMainServerAlive);

            // 3. Push the state to MainActivity
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).currentSystemState = currentSystemState;
                getActivity().runOnUiThread(() -> {
                    if (getActivity() instanceof MainActivity) {
                        ((MainActivity) getActivity()).updateUIColorsAndVisibility();
                    }
                });
            }

            // --- CHECKPOINT 2 ---
            if (!isAdded() || getActivity() == null) return;

            // 4. Update the UI on the main thread
            getActivity().runOnUiThread(() -> {
                if (archContainer != null) {
                    if (isArchCalculated && currentSystemState != SystemState.NONE) {
                        archContainer.setVisibility(View.VISIBLE);
                        txtTermuxArch.setText(cachedTermuxArch);
                        txtDebianArch.setText(cachedDebianArch);
                    } else {
                        archContainer.setVisibility(View.GONE);
                    }
                }

                // Configure the Top Traffic Light (Server Status)
                if (currentSystemState == SystemState.ONLINE) {
                    badgeStatus.setText(R.string.dash_online);
                    badgeStatus.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                            androidx.core.content.ContextCompat.getColor(requireContext(), R.color.dash_status_online)));
                } else {
                    badgeStatus.setText(R.string.dash_offline);
                    badgeStatus.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                            androidx.core.content.ContextCompat.getColor(requireContext(), R.color.dash_text_secondary)));
                }

                // Configure the Bottom LED and Suggestion Message
                switch (currentSystemState) {
                    case ONLINE:
                        ledTermuxState.setBackgroundResource(R.drawable.led_on_green);
                        txtTermuxState.setText(getString(R.string.dash_state_online));
                        txtTermuxState.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.dash_text_primary));
                        break;
                    case OFFLINE:
                        ledTermuxState.setBackgroundResource(R.drawable.led_off);
                        txtTermuxState.setText(getString(R.string.dash_state_offline));
                        txtTermuxState.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.dash_text_secondary));
                        break;
                    case DEBIAN_ONLY:
                        ledTermuxState.setBackgroundResource(R.drawable.led_off);
                        txtTermuxState.setText(getString(R.string.dash_state_debian_only));
                        txtTermuxState.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.dash_text_primary));
                        break;
                    case INSTALLER:
                        ledTermuxState.setBackgroundResource(R.drawable.led_off);
                        txtTermuxState.setText(getString(R.string.dash_state_installer));
                        txtTermuxState.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.dash_text_primary));
                        break;
                    case TERMUX_ONLY:
                        ledTermuxState.setBackgroundResource(R.drawable.led_off);
                        txtTermuxState.setText(getString(R.string.dash_state_termux_only));
                        txtTermuxState.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.dash_warning));
                        break;
                    case NONE:
                        ledTermuxState.setBackgroundResource(R.drawable.led_off);
                        txtTermuxState.setText(getString(R.string.dash_state_none));
                        txtTermuxState.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.dash_warning));
                        break;
                }
            });

            // 5. Scan individual modules (Only if the system is ONLINE)
            for (int r = 0; r < modulesContainer.getChildCount(); r++) {
                LinearLayout row = (LinearLayout) modulesContainer.getChildAt(r);

                for (int c = 0; c < row.getChildCount(); c++) {
                    LinearLayout card = (LinearLayout) row.getChildAt(c);
                    String endpoint = (String) card.getTag();
                    if (endpoint == null) continue;

                    View led = card.getChildAt(0);

                    // Module ON = (System is ONLINE) AND (URL responds)
                    boolean isModuleAlive = (currentSystemState == SystemState.ONLINE) && pingUrl("http://localhost:8085/" + endpoint);

                    if (!isAdded() || getActivity() == null) return;

                    getActivity().runOnUiThread(() -> {
                        led.setBackgroundResource(isModuleAlive ? R.drawable.led_on_green : R.drawable.led_off);
                    });
                }
            }
        }).start();
    }

    private boolean pingUrl(String urlStr) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setUseCaches(false);
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(1500);
            conn.setRequestMethod("GET");
            return (conn.getResponseCode() >= 200 && conn.getResponseCode() < 400);
        } catch (Exception e) {
            return false;
        }
    }

    // Extracts the numbers (in kB) from the lines of /proc/meminfo
    private long parseMemLine(String line) {
        try {
            String[] parts = line.split("\\s+");
            return Long.parseLong(parts[1]);
        } catch (Exception e) {
            return 0;
        }
    }

    // --- METHODS FOR OBTAINING IPs ---
    private String getWifiIp() {
        return getIpByInterface("wlan0");
    }

    private String getHotspotIp() {
        String[] hotspotInterfaces = {"ap0", "wlan1", "swlan0"};
        for (String iface : hotspotInterfaces) {
            String ip = getIpByInterface(iface);
            if (!ip.equals("--")) return ip;
        }
        return "--";
    }

    private String getIpByInterface(String interfaceName) {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                if (intf.getName().equalsIgnoreCase(interfaceName)) {
                    List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                    for (InetAddress addr : addrs) {
                        if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                            return addr.getHostAddress();
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "--";
    }

    private int getBatteryPercentage() {
        try {
            IntentFilter iFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryStatus = requireContext().registerReceiver(null, iFilter);
            if (batteryStatus != null) {
                int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                return (int) ((level / (float) scale) * 100);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return -1;
    }

    // --- METHODS FOR OBTAINING THE DEVICE NAME ---
    private String getDeviceName() {
        String manufacturer = android.os.Build.MANUFACTURER;
        String model = android.os.Build.MODEL;

        if (model.toLowerCase().startsWith(manufacturer.toLowerCase())) {
            return capitalize(model);
        } else {
            return capitalize(manufacturer) + " " + model;
        }
    }

    private String capitalize(String s) {
        if (s == null || s.length() == 0) return "";
        char first = s.charAt(0);
        if (Character.isUpperCase(first)) {
            return s;
        } else {
            return Character.toUpperCase(first) + s.substring(1);
        }
    }

    // --- MASTER STATE EVALUATOR ---
    private SystemState evaluateSystemState(boolean isNginxAlive) {

        // 1. Does Termux physically exist on the Android device?
        boolean isTermuxInstalled = false;
        long currentTermuxInstallTime = 0;

        try {
            android.content.pm.PackageInfo info = requireContext().getPackageManager().getPackageInfo("com.termux", 0);
            isTermuxInstalled = true;
            currentTermuxInstallTime = info.firstInstallTime; // This is our secure signature!
        } catch (PackageManager.NameNotFoundException e) {
            isTermuxInstalled = false;
        }

        File stateDir = new File(Environment.getExternalStorageDirectory(), ".iiab_state");
        android.content.SharedPreferences prefs = requireContext().getSharedPreferences("iiab_internal_prefs", Context.MODE_PRIVATE);
        long savedTermuxSignature = prefs.getLong("termux_install_signature", 0);

        // --- THE PURGE: Ghost Handling & Signature Verification ---
        // If Termux is NOT installed, OR if it IS installed but the signature doesn't match (meaning it was reinstalled)
        if (!isTermuxInstalled || (savedTermuxSignature != 0 && currentTermuxInstallTime != savedTermuxSignature)) {

            isArchCalculated = false;
            if (stateDir.exists()) {
                deleteRecursive(stateDir);
            }

            if (!isTermuxInstalled) {
                // Termux is completely gone. Reset our signature memory to 0.
                prefs.edit().putLong("termux_install_signature", 0).apply();
                return SystemState.NONE;
            } else {
                // Termux was REINSTALLED. Save the new signature so we don't purge it again.
                prefs.edit().putLong("termux_install_signature", currentTermuxInstallTime).apply();

                // IMPORTANT: Here we need to reset the variables that track if the user clicked
                // the "Display over other apps" and "Storage" menus.
                // prefs.edit().putBoolean("termux_tapped_storage", false).apply();
            }
        } else if (isTermuxInstalled && savedTermuxSignature == 0) {
            // First time we ever see Termux. Save its signature.
            prefs.edit().putLong("termux_install_signature", currentTermuxInstallTime).apply();
        }

        // --- THE TRIGGER ---
        if (!isArchCalculated && isTermuxInstalled) {
            cachedTermuxArch = getTermuxArch();
            cachedDebianArch = getDebianArch(cachedTermuxArch);
            isArchCalculated = true;
        }

        // 2. Does the Nginx server respond?
        if (isNginxAlive) {
            return SystemState.ONLINE;
        }

        // 3. Is IIAB fully compiled/restored and ready?
        File flagIiabReady = new File(stateDir, "flag_iiab_ready");
        if (flagIiabReady.exists()) {
            return SystemState.OFFLINE;
        }

        // 4. Is the base Debian OS installed, but NO IIAB yet?
        File flagSystem = new File(stateDir, "flag_system_installed");
        if (flagSystem.exists()) {
            return SystemState.DEBIAN_ONLY;
        }

        // 5. Is only the installer ready?
        File flagInstaller = new File(stateDir, "flag_installer_present");
        if (flagInstaller.exists()) {
            return SystemState.INSTALLER;
        }

        // 6. Only the raw base app is present.
        return SystemState.TERMUX_ONLY;
    }

    // Helper method to recursively delete the .iiab_state folder if Termux was uninstalled
    private void deleteRecursive(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            File[] children = fileOrDirectory.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        fileOrDirectory.delete();
    }

    // --- METHODS FOR OBTAINING ARCHITECTURES ---
    private String getTermuxArch() {
        try {
            android.content.pm.ApplicationInfo info = requireContext().getPackageManager().getApplicationInfo("com.termux", 0);
            String nativeLibDir = info.nativeLibraryDir;

            if (nativeLibDir != null) {
                if (nativeLibDir.endsWith("arm64")) return "arm64-v8a";
                if (nativeLibDir.endsWith("arm")) return "armeabi-v7a";
            }
        } catch (PackageManager.NameNotFoundException e) {
            return "N/A";
        }

        if (android.os.Build.SUPPORTED_ABIS.length > 0) {
            return android.os.Build.SUPPORTED_ABIS[0];
        }
        return "unknown";
    }

    private String getDebianArch(String androidArch) {
        if (androidArch == null || androidArch.equals("N/A")) return "N/A";
        String lower = androidArch.toLowerCase();

        if (lower.contains("arm64") || lower.contains("aarch64")) return "arm64";
        if (lower.contains("armeabi") || lower.contains("armv7")) return "armhf";

        return lower;
    }

    @Override
    public void onConfigurationChanged(@NonNull android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // When the user rotates the screen, we dynamically rearrange the layout
        adaptLayoutToOrientation(newConfig.orientation);
    }

    private void adaptLayoutToOrientation(int orientation) {
        if (rootGauges == null || bottomRowGauges == null) return;

        if (orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            // ==========================================
            // LANDSCAPE MODE (1 Horizontal Row)
            // ==========================================
            rootGauges.setOrientation(LinearLayout.HORIZONTAL);

            if (gaugeRam.getParent() == bottomRowGauges) bottomRowGauges.removeView(gaugeRam);
            if (gaugeSwap.getParent() == bottomRowGauges) bottomRowGauges.removeView(gaugeSwap);

            if (gaugeRam.getParent() == null) rootGauges.addView(gaugeRam);
            if (gaugeSwap.getParent() == null) rootGauges.addView(gaugeSwap);

            bottomRowGauges.setVisibility(View.GONE);

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dpToPx(180), 1f);
            params.gravity = android.view.Gravity.CENTER;

            gaugeStorage.setLayoutParams(params);
            gaugeRam.setLayoutParams(params);
            gaugeSwap.setLayoutParams(params);

        } else {
            // ==========================================
            // PORTRAIT MODE (Triangle: 1 Up, 2 Down)
            // ==========================================
            rootGauges.setOrientation(LinearLayout.VERTICAL);

            if (gaugeRam.getParent() == rootGauges) rootGauges.removeView(gaugeRam);
            if (gaugeSwap.getParent() == rootGauges) rootGauges.removeView(gaugeSwap);

            if (gaugeRam.getParent() == null) bottomRowGauges.addView(gaugeRam);
            if (gaugeSwap.getParent() == null) bottomRowGauges.addView(gaugeSwap);

            bottomRowGauges.setVisibility(View.VISIBLE);

            // Large, centered Storage gauge
            LinearLayout.LayoutParams storageParams = new LinearLayout.LayoutParams(dpToPx(225), dpToPx(225), 0);
            storageParams.gravity = android.view.Gravity.CENTER;
            storageParams.bottomMargin = dpToPx(4);
            gaugeStorage.setLayoutParams(storageParams);

            // Dynamic RAM and SWAP (50% width each) to prevent clipping
            LinearLayout.LayoutParams ramParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            ramParams.rightMargin = dpToPx(8);

            LinearLayout.LayoutParams swapParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            swapParams.leftMargin = dpToPx(8);

            gaugeRam.setLayoutParams(ramParams);
            gaugeSwap.setLayoutParams(swapParams);
        }
    }

    // Converter from DP to actual screen pixels
    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
