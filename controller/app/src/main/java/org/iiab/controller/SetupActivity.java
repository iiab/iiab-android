/*
 * ============================================================================
 * Name        : SetupActivity.java
 * Author      : IIAB Project
 * Copyright   : Copyright (c) 2026 IIAB Project
 * Description : Setup permission table helper
 * ============================================================================
 */
package org.iiab.controller;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.snackbar.Snackbar;

public class SetupActivity extends AppCompatActivity {

    private static final String TERMUX_PERMISSION = "com.termux.permission.RUN_COMMAND";

    private SwitchCompat switchNotif, switchTermux, switchStorage, switchVpn, switchBattery;
    private Button btnContinue;
    private Button btnManageAll;
    private Button btnTermuxOverlay;
    private Button btnTermuxStorage;
    private Button btnManageTermux;

    private ActivityResultLauncher<String> requestPermissionLauncher;
    private ActivityResultLauncher<Intent> storageLauncher;
    private ActivityResultLauncher<Intent> vpnLauncher;
    private ActivityResultLauncher<Intent> batteryLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup);

        TextView welcomeText = findViewById(R.id.setup_welcome_text);
        welcomeText.setText(getString(R.string.setup_welcome, getString(R.string.app_name)));

        switchNotif = findViewById(R.id.switch_perm_notifications);
        switchTermux = findViewById(R.id.switch_perm_termux);
        switchStorage = findViewById(R.id.switch_perm_storage);
        switchVpn = findViewById(R.id.switch_perm_vpn);
        switchBattery = findViewById(R.id.switch_perm_battery);
        btnContinue = findViewById(R.id.btn_setup_continue);
        btnManageAll = findViewById(R.id.btn_manage_all);
        btnTermuxOverlay = findViewById(R.id.btn_termux_overlay);
        btnTermuxStorage = findViewById(R.id.btn_termux_storage);
        btnManageTermux = findViewById(R.id.btn_manage_termux);

        // Hide Notification switch if Android < 13
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            switchNotif.setVisibility(android.view.View.GONE);
        }

        setupLaunchers();
        setupListeners();
        checkAllPermissions();

        btnContinue.setOnClickListener(v -> {
            // Tell bash that permissions are handled
            writeTermuxPermissionFlags();
            // Save flag so we don't show this screen again
            SharedPreferences prefs = getSharedPreferences(getString(R.string.pref_file_internal), Context.MODE_PRIVATE);
            prefs.edit().putBoolean(getString(R.string.pref_key_setup_complete), true).apply();

            finish();
        });
    }

    private void setupLaunchers() {
        requestPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> checkAllPermissions()
        );

        storageLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> checkAllPermissions()
        );

        vpnLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> checkAllPermissions()
        );

        batteryLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> checkAllPermissions()
        );
    }

    private void setupListeners() {
        switchNotif.setOnClickListener(v -> {
            if (hasNotifPermission()) {
                handleRevokeAttempt(v);
                return;
            }
            if (switchNotif.isChecked()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
                }
            }
            switchNotif.setChecked(false); // Force visual state back until system confirms
        });

        switchTermux.setOnClickListener(v -> {
            if (hasTermuxPermission()) {
                handleRevokeAttempt(v);
                return;
            }
            if (switchTermux.isChecked()) {
                requestPermissionLauncher.launch(TERMUX_PERMISSION);
            }
            switchTermux.setChecked(false);
        });

        switchStorage.setOnClickListener(v -> {
            if (hasStoragePermission()) {
                handleRevokeAttempt(v);
                return;
            }
            if (switchStorage.isChecked()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                        intent.addCategory("android.intent.category.DEFAULT");
                        intent.setData(Uri.parse(String.format("package:%s", getApplicationContext().getPackageName())));
                        storageLauncher.launch(intent);
                    } catch (Exception e) {
                        Intent intent = new Intent();
                        intent.setAction(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                        storageLauncher.launch(intent);
                    }
                } else {
                    requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
                }
            }
            switchStorage.setChecked(false); // Force visual state back until system confirms
        });

        switchVpn.setOnClickListener(v -> {
            if (hasVpnPermission()) {
                handleRevokeAttempt(v);
                return;
            }
            if (switchVpn.isChecked()) {
                Intent intent = VpnService.prepare(this);
                if (intent != null) {
                    vpnLauncher.launch(intent);
                } else {
                    checkAllPermissions(); // Already granted
                }
            }
            switchVpn.setChecked(false);
        });

        switchBattery.setOnClickListener(v -> {
            if (hasBatteryPermission()) {
                handleRevokeAttempt(v);
                return;
            }
            if (switchBattery.isChecked()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    batteryLauncher.launch(intent);
                }
            }
            switchBattery.setChecked(false);
        });
        // Direct access to all the Controller permissions
        btnManageAll.setOnClickListener(v -> openAppSettings());
        // Direct access to Termux Overlay permissions
        btnTermuxOverlay.setOnClickListener(v -> {
            try {
                SharedPreferences internalPrefs = getSharedPreferences(getString(R.string.pref_file_internal), Context.MODE_PRIVATE);
                internalPrefs.edit().putBoolean("termux_tapped_overlay", true).apply();

                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                intent.setData(Uri.parse("package:com.termux"));
                startActivity(intent);
            } catch (Exception e) {
                Snackbar.make(v, R.string.termux_not_installed_error, Snackbar.LENGTH_LONG).show();
            }
        });

        // Direct access to Termux settings to grant Files/Storage permission
        btnTermuxStorage.setOnClickListener(v -> {
            try {
                SharedPreferences internalPrefs = getSharedPreferences(getString(R.string.pref_file_internal), Context.MODE_PRIVATE);
                internalPrefs.edit().putBoolean("termux_tapped_storage", true).apply();

                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.parse("package:com.termux"));
                startActivity(intent);
                // Toast.makeText(this, "Please go to Permissions and allow Storage/Files", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Snackbar.make(v, R.string.termux_not_installed_error, Snackbar.LENGTH_LONG).show();
            }
        });

        // Direct access to Controller settings (Reuses the method from Phase 1)
        btnManageTermux.setOnClickListener(v -> {
            try {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.parse("package:com.termux"));
                startActivity(intent);
            } catch (Exception e) {
                Snackbar.make(v, R.string.termux_not_installed, Snackbar.LENGTH_LONG).show();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkAllPermissions(); // Refresh state if user returns from settings
    }

    /**
     * It displays visual feedback (shake) and a message when the user
     * tries to turn off a permission.
     */
    private void handleRevokeAttempt(View switchView) {
        // Force the switch to stay checked visually
        ((SwitchCompat) switchView).setChecked(true);

        // Animate the switch (Shake)
        Animation shake = AnimationUtils.loadAnimation(this, R.anim.shake);
        switchView.startAnimation(shake);

        // Show Snackbar with action to go to Settings
        Snackbar.make(findViewById(android.R.id.content), R.string.revoke_permission_warning, Snackbar.LENGTH_LONG)
                .setAction(R.string.settings_label, v -> openAppSettings()).show();
    }

    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private void checkAllPermissions() {
        boolean notif = hasNotifPermission();
        boolean termux = hasTermuxPermission();
        boolean storage = hasStoragePermission();
        boolean vpn = hasVpnPermission();
        boolean battery = hasBatteryPermission();

        switchNotif.setChecked(notif);
        switchTermux.setChecked(termux);
        switchStorage.setChecked(storage);
        switchVpn.setChecked(vpn);
        switchBattery.setChecked(battery);

        boolean allGranted = termux && storage && vpn && battery;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            allGranted = allGranted && notif;
        }

        btnContinue.setEnabled(allGranted);
        btnContinue.setBackgroundTintList(ContextCompat.getColorStateList(this,
                allGranted ? R.color.btn_explore_ready : R.color.btn_explore_disabled));
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

    private boolean hasVpnPermission() {
        return VpnService.prepare(this) == null;
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

    private void writeTermuxPermissionFlags() {
        java.io.File stateDir = new java.io.File(android.os.Environment.getExternalStorageDirectory(), ".iiab_state");
        if (!stateDir.exists()) {
            stateDir.mkdirs();
        }
        try {
            new java.io.File(stateDir, "flag_perm_battery").createNewFile();
            new java.io.File(stateDir, "flag_perm_overlay").createNewFile();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
