/*
 * ============================================================================
 * Name        : UsageFragment.java
 * Author      : IIAB Project
 * Copyright   : Copyright (c) 2026 IIAB Project
 * Description : Usage Fragment Activity
 * ============================================================================
 */
package org.iiab.controller;

import android.content.Context;
import android.content.Intent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.text.method.ScrollingMovementMethod;
import android.view.MotionEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.snackbar.Snackbar;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class UsageFragment extends Fragment implements View.OnClickListener {

    private MainActivity mainActivity;
    // INTERFACE VARS
    private EditText edittext_socks_addr, edittext_socks_udp_addr, edittext_socks_port, edittext_socks_user, edittext_socks_pass, edittext_dns_ipv4, edittext_dns_ipv6;
    private CheckBox checkbox_udp_in_tcp, checkbox_remote_dns, checkbox_global, checkbox_maintenance, checkbox_ipv4, checkbox_ipv6;
    private TextView textview_maintenance_warning, configLabel, advConfigLabel, logLabel, logWarning, logSizeText, connectionLog;
    private Button button_apps, button_save, button_control, button_browse_content, watchdogControl, btnClearLog, btnCopyLog;
    private LinearLayout logActions, configLayout, advancedConfig, deckContainer;
    private ProgressBar logProgress;
    private ProgressButton btnServerControl;

    private ObjectAnimator fusionAnimator;
    private DashboardManager dashboardManager;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (context instanceof MainActivity) {
            mainActivity = (MainActivity) context;
            mainActivity.setUsageFragment(this);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_usage, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // UI Bindings
        edittext_socks_addr = view.findViewById(R.id.socks_addr);
        edittext_socks_udp_addr = view.findViewById(R.id.socks_udp_addr);
        edittext_socks_port = view.findViewById(R.id.socks_port);
        edittext_socks_user = view.findViewById(R.id.socks_user);
        edittext_socks_pass = view.findViewById(R.id.socks_pass);
        edittext_dns_ipv4 = view.findViewById(R.id.dns_ipv4);
        edittext_dns_ipv6 = view.findViewById(R.id.dns_ipv6);
        checkbox_ipv4 = view.findViewById(R.id.ipv4);
        checkbox_ipv6 = view.findViewById(R.id.ipv6);
        checkbox_global = view.findViewById(R.id.global);
        checkbox_udp_in_tcp = view.findViewById(R.id.udp_in_tcp);
        checkbox_remote_dns = view.findViewById(R.id.remote_dns);
        checkbox_maintenance = view.findViewById(R.id.checkbox_maintenance);
        textview_maintenance_warning = view.findViewById(R.id.maintenance_warning);
        button_apps = view.findViewById(R.id.apps);
        button_save = view.findViewById(R.id.save);
        button_control = view.findViewById(R.id.control);
        button_browse_content = view.findViewById(R.id.btnBrowseContent);
        watchdogControl = view.findViewById(R.id.watchdog_control);

        logActions = view.findViewById(R.id.log_actions);
        btnClearLog = view.findViewById(R.id.btn_clear_log);
        btnCopyLog = view.findViewById(R.id.btn_copy_log);
        connectionLog = view.findViewById(R.id.connection_log);
        logProgress = view.findViewById(R.id.log_progress);
        logWarning = view.findViewById(R.id.log_warning_text);
        logSizeText = view.findViewById(R.id.log_size_text);
        configLayout = view.findViewById(R.id.config_layout);
        configLabel = view.findViewById(R.id.config_label);
        advancedConfig = view.findViewById(R.id.advanced_config);
        advConfigLabel = view.findViewById(R.id.adv_config_label);
        logLabel = view.findViewById(R.id.log_label);

        deckContainer = view.findViewById(R.id.deck_container);
        btnServerControl = view.findViewById(R.id.btn_server_control);

        dashboardManager = new DashboardManager(requireActivity(), view, () -> {
            mainActivity.handleControlClick();
        });

        // Listeners
        watchdogControl.setOnClickListener(v -> {
            if (mainActivity.currentSystemState == DashboardFragment.SystemState.NONE) {
                Snackbar.make(v, R.string.termux_not_installed_error, Snackbar.LENGTH_LONG).show();
                return;
            }
            mainActivity.handleWatchdogClick();
        });
        button_control.setOnClickListener(v -> mainActivity.handleControlClick());
        button_browse_content.setOnClickListener(v -> mainActivity.handleBrowseContentClick(v));
        btnClearLog.setOnClickListener(this);
        btnCopyLog.setOnClickListener(this);
        configLabel.setOnClickListener(v -> handleConfigToggle());
        advConfigLabel.setOnClickListener(v -> toggleVisibility(advancedConfig, advConfigLabel, getString(R.string.advanced_settings_label)));
        logLabel.setOnClickListener(v -> handleLogToggle());
        checkbox_udp_in_tcp.setOnClickListener(this);
        checkbox_remote_dns.setOnClickListener(this);
        checkbox_global.setOnClickListener(this);
        checkbox_maintenance.setOnClickListener(this);
        button_apps.setOnClickListener(this);
        button_save.setOnClickListener(this);

        btnServerControl.setOnClickListener(v -> {
            // --- Intercept based on State Machine ---
            DashboardFragment.SystemState state = mainActivity.currentSystemState;
            boolean isFullyInstalled = (state == DashboardFragment.SystemState.ONLINE || state == DashboardFragment.SystemState.OFFLINE);

            if (!isFullyInstalled) {
                Snackbar.make(v, R.string.server_not_installed_warning, 6000).show();
                return; // Stop execution here
            }
            // --------------------------------------------------

            if (mainActivity.targetServerState != null) return;

            mainActivity.serverTransitionText = !mainActivity.isServerAlive ? getString(R.string.server_booting) : getString(R.string.server_shutting_down);
            mainActivity.targetServerState = !mainActivity.isServerAlive;

            updateUIColorsAndVisibility();
            btnServerControl.startProgress();

            mainActivity.handleServerLaunchClick(v);
        });

        connectionLog.setMovementMethod(new ScrollingMovementMethod());
        connectionLog.setTextIsSelectable(true);
        connectionLog.setOnTouchListener((v, event) -> {
            v.getParent().requestDisallowInterceptTouchEvent(true);
            if ((event.getAction() & MotionEvent.ACTION_MASK) == MotionEvent.ACTION_UP) {
                v.getParent().requestDisallowInterceptTouchEvent(false);
            }
            return false;
        });

        updateUI();
    }

    @Override
    public void onClick(View v) {
        if (v == checkbox_global || v == checkbox_remote_dns || v == checkbox_maintenance) {
            mainActivity.savePrefs();
            updateUI();
        } else if (v == button_apps) {
            startActivity(new Intent(requireContext(), AppListActivity.class));
        } else if (v.getId() == R.id.save) {
            mainActivity.savePrefs();
            Toast.makeText(requireContext(), R.string.saved_toast, Toast.LENGTH_SHORT).show();
            addToLog(getString(R.string.settings_saved));
        } else if (v.getId() == R.id.btn_clear_log) {
            showResetLogConfirmation();
        } else if (v.getId() == R.id.btn_copy_log) {
            ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("IIAB Log", connectionLog.getText().toString());
            if (clipboard != null) {
                clipboard.setPrimaryClip(clip);
                Toast.makeText(requireContext(), R.string.log_copied_toast, Toast.LENGTH_SHORT).show();
            }
        }
    }

    public void updateUI() {
        if (button_control == null) return;

        boolean vpnActive = mainActivity.prefs.getEnable();
        boolean watchdogActive = mainActivity.prefs.getWatchdogEnable();

        if (dashboardManager != null)
            dashboardManager.setTunnelState(vpnActive, mainActivity.isProxyDegraded);

        if (vpnActive) {
            button_control.setText(R.string.control_disable);
            button_control.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.btn_vpn_on));
        } else {
            button_control.setText(R.string.control_enable);
            button_control.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.btn_vpn_off));
        }
        if (watchdogActive) {
            watchdogControl.setText(R.string.watchdog_disable);
        } else {
            watchdogControl.setText(R.string.watchdog_enable);
        }
        edittext_socks_addr.setText(mainActivity.prefs.getSocksAddress());
        edittext_socks_udp_addr.setText(mainActivity.prefs.getSocksUdpAddress());
        edittext_socks_port.setText(String.valueOf(mainActivity.prefs.getSocksPort()));
        edittext_socks_user.setText(mainActivity.prefs.getSocksUsername());
        edittext_socks_pass.setText(mainActivity.prefs.getSocksPassword());
        edittext_dns_ipv4.setText(mainActivity.prefs.getDnsIpv4());
        edittext_dns_ipv6.setText(mainActivity.prefs.getDnsIpv6());
        checkbox_ipv4.setChecked(mainActivity.prefs.getIpv4());
        checkbox_ipv6.setChecked(mainActivity.prefs.getIpv6());
        checkbox_global.setChecked(mainActivity.prefs.getGlobal());
        checkbox_udp_in_tcp.setChecked(mainActivity.prefs.getUdpInTcp());
        checkbox_remote_dns.setChecked(mainActivity.prefs.getRemoteDns());
        checkbox_maintenance.setChecked(mainActivity.prefs.getMaintenanceMode());
        boolean editable = !vpnActive;
        edittext_socks_addr.setEnabled(editable);
        edittext_socks_port.setEnabled(editable);
        button_save.setEnabled(editable);

        checkbox_maintenance.setEnabled(editable);
        if (textview_maintenance_warning != null) {
            textview_maintenance_warning.setVisibility(vpnActive ? View.VISIBLE : View.GONE);
        }
    }

    public void updateUIColorsAndVisibility() {
        if (!isAdded() || getContext() == null) {
            return;
        }
        if (button_control == null) return;

        boolean isVpnActive = mainActivity.prefs.getEnable();
        boolean isWatchdogOn = mainActivity.prefs.getWatchdogEnable();

        if (dashboardManager != null) {
            dashboardManager.setTunnelState(isVpnActive, mainActivity.isProxyDegraded);
        }

        // Main VPN Button
        if (!mainActivity.isServerAlive) {
            if (isVpnActive) {
                button_control.setText(R.string.control_disable);
                button_control.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.btn_vpn_on_dim));
            } else {
                button_control.setText(R.string.control_enable);
                button_control.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.btn_vpn_off_dim));
            }
        } else {
            button_control.setEnabled(true);
            if (isVpnActive) {
                button_control.setText(R.string.control_disable);
                button_control.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.btn_vpn_on));
            } else {
                button_control.setText(R.string.control_enable);
                button_control.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.btn_vpn_off));
            }
        }

        // Explore Button
        button_browse_content.setVisibility(View.VISIBLE);
        if (!mainActivity.isServerAlive) {
            button_browse_content.setEnabled(true);
            button_browse_content.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.btn_explore_disabled));
            button_browse_content.setAlpha(1.0f);
            button_browse_content.setTextColor(Color.parseColor("#888888"));
        } else if (mainActivity.isNegotiating) {
            button_browse_content.setEnabled(true);
            button_browse_content.setTextColor(Color.WHITE);
        } else {
            button_browse_content.setEnabled(true);
            button_browse_content.setTextColor(Color.WHITE);
            button_browse_content.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.btn_explore_ready));

            // TODO: [RESTORE] Uncomment original logic to tie Explore button to ESPW/VPN status
            // if (isVpnActive && !mainActivity.isProxyDegraded) {
            //     button_browse_content.setAlpha(1.0f);
            // } else {
            //     button_browse_content.setAlpha(0.6f);
            // }

            // TEMP BYPASS: Always fully opaque when server is running
            button_browse_content.setAlpha(1.0f);
        }

// Server Control Logic
        DashboardFragment.SystemState state = mainActivity.currentSystemState;
        boolean isFullyInstalled = (state == DashboardFragment.SystemState.ONLINE || state == DashboardFragment.SystemState.OFFLINE);

        if (!isFullyInstalled) {
            // SYSTEM NOT READY: Gray out the button
            btnServerControl.setAlpha(0.6f);
            btnServerControl.setText(R.string.launch_server);
            btnServerControl.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.btn_explore_disabled));

            // Also gray out the watchdog since the server isn't installed
            deckContainer.setBackgroundColor(Color.TRANSPARENT);
            watchdogControl.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.btn_watchdog_off));

        } else if (mainActivity.targetServerState != null) {
            // TRANSITIONING STATE
            btnServerControl.setAlpha(0.6f);
            btnServerControl.setText(mainActivity.serverTransitionText);
            btnServerControl.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.btn_explore_disabled));
        } else {
            // SYSTEM READY: Normal behavior
            btnServerControl.setAlpha(1.0f);
            if (mainActivity.isServerAlive) {
                btnServerControl.setText(R.string.stop_server);
                if (isWatchdogOn) {
                    deckContainer.setBackgroundColor(Color.parseColor("#44FF9800"));
                    btnServerControl.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.btn_watchdog_on));
                    watchdogControl.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.btn_watchdog_on));
                } else {
                    if (fusionAnimator == null || !fusionAnimator.isRunning())
                        deckContainer.setBackgroundColor(Color.TRANSPARENT);
                    btnServerControl.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.btn_danger));
                    watchdogControl.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.btn_watchdog_off));
                }
            } else {
                deckContainer.setBackgroundColor(Color.TRANSPARENT);
                btnServerControl.setText(R.string.launch_server);
                btnServerControl.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.btn_success));
                watchdogControl.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), isWatchdogOn ? R.color.btn_watchdog_on : R.color.btn_watchdog_off));
            }
        }
    }

    public void stopBtnProgress() {
        btnServerControl.stopProgress();
    }

    public void startFusionPulse() {
        deckContainer.setBackgroundColor(Color.parseColor("#44FF9800"));
        if (fusionAnimator != null && fusionAnimator.isRunning()) fusionAnimator.cancel();
        fusionAnimator = ObjectAnimator.ofFloat(deckContainer, "alpha", 1f, 0.4f);
        fusionAnimator.setDuration(600);
        fusionAnimator.setRepeatCount(ObjectAnimator.INFINITE);
        fusionAnimator.setRepeatMode(ObjectAnimator.REVERSE);
        fusionAnimator.start();
    }

    public void startExitPulse() {
        if (fusionAnimator != null && fusionAnimator.isRunning()) fusionAnimator.cancel();
        fusionAnimator = ObjectAnimator.ofFloat(deckContainer, "alpha", deckContainer.getAlpha(), 0.3f);
        fusionAnimator.setDuration(800);
        fusionAnimator.setRepeatCount(ObjectAnimator.INFINITE);
        fusionAnimator.setRepeatMode(ObjectAnimator.REVERSE);
        fusionAnimator.start();
    }

    public void finalizeEntryPulse() {
        if (fusionAnimator != null) fusionAnimator.cancel();
        deckContainer.setAlpha(1f);
    }

    public void finalizeExitPulse() {
        if (fusionAnimator != null) fusionAnimator.cancel();
        deckContainer.animate().alpha(1f).setDuration(300).withEndAction(() -> deckContainer.setBackgroundColor(Color.TRANSPARENT)).start();
    }

    public void addToLog(String message) {
        requireActivity().runOnUiThread(() -> {
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
            String currentTime = sdf.format(new Date());
            String logEntry = "[" + currentTime + "] " + message + "\n";
            if (connectionLog != null) {
                connectionLog.append(logEntry);
                scrollToBottom();
            }
        });
    }

    private void scrollToBottom() {
        if (connectionLog != null && connectionLog.getLayout() != null) {
            int scroll = connectionLog.getLayout().getLineTop(connectionLog.getLineCount()) - connectionLog.getHeight();
            if (scroll > 0) connectionLog.scrollTo(0, scroll);
        }
    }

    public void updateLogSizeUI() {
        if (logSizeText == null) return;
        String sizeStr = LogManager.getFormattedSize(requireContext());
        logSizeText.setText(getString(R.string.log_size_format, sizeStr));
    }

    public void updateConnectivityLeds(boolean wifiOn, boolean hotspotOn) {
        if (dashboardManager != null) {
            dashboardManager.updateConnectivityLeds(wifiOn, hotspotOn);
        }
    }

    public boolean isLogVisible() {
        return connectionLog != null && connectionLog.getVisibility() == View.VISIBLE;
    }

    private void handleLogToggle() {
        boolean isOpening = connectionLog.getVisibility() == View.GONE;
        if (isOpening) {
            if (mainActivity.isReadingLogs) return;
            mainActivity.isReadingLogs = true;
            if (logProgress != null) logProgress.setVisibility(View.VISIBLE);

            LogManager.readLogsAsync(requireContext(), (logContent, isRapidGrowth) -> {
                if (connectionLog != null) {
                    connectionLog.setText(logContent);
                    scrollToBottom();
                }
                if (logProgress != null) logProgress.setVisibility(View.GONE);
                if (logWarning != null)
                    logWarning.setVisibility(isRapidGrowth ? View.VISIBLE : View.GONE);
                updateLogSizeUI();
                mainActivity.isReadingLogs = false;
            });
            mainActivity.startLogSizeUpdates();
        } else {
            mainActivity.stopLogSizeUpdates();
        }
        toggleVisibility(connectionLog, logLabel, getString(R.string.connection_log_label));
        logActions.setVisibility(connectionLog.getVisibility());
        if (logSizeText != null) logSizeText.setVisibility(connectionLog.getVisibility());
    }

    private void handleConfigToggle() {
        if (configLayout.getVisibility() == View.GONE) {
            if (BiometricHelper.isDeviceSecure(requireContext())) {
                BiometricHelper.prompt((androidx.appcompat.app.AppCompatActivity) requireActivity(),
                        getString(R.string.auth_required_title),
                        getString(R.string.auth_required_subtitle),
                        () -> toggleVisibility(configLayout, configLabel, getString(R.string.advanced_settings_label)));
            } else {
                BiometricHelper.showEnrollmentDialog(requireContext());
            }
        } else {
            toggleVisibility(configLayout, configLabel, getString(R.string.advanced_settings_label));
        }
    }

    private void toggleVisibility(View view, TextView label, String text) {
        boolean isGone = view.getVisibility() == View.GONE;
        view.setVisibility(isGone ? View.VISIBLE : View.GONE);
        label.setText(String.format(getString(isGone ? R.string.label_separator_down : R.string.label_separator_up), text));
    }

    private void showResetLogConfirmation() {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.log_reset_confirm_title)
                .setMessage(R.string.log_reset_confirm_msg)
                .setPositiveButton(R.string.reset_log, (dialog, which) -> {
                    LogManager.clearLogs(requireContext(), new LogManager.LogClearCallback() {
                        @Override
                        public void onSuccess() {
                            connectionLog.setText("");
                            addToLog(getString(R.string.log_reset_user));
                            if (logWarning != null) logWarning.setVisibility(View.GONE);
                            updateLogSizeUI();
                            Toast.makeText(requireContext(), R.string.log_cleared_toast, Toast.LENGTH_SHORT).show();
                        }

                        @Override
                        public void onError(String message) {
                            Toast.makeText(requireContext(), getString(R.string.failed_reset_log, message), Toast.LENGTH_SHORT).show();
                        }
                    });
                })
                .setNegativeButton(R.string.cancel, null).show();
    }

    public void savePrefsFromUI() {
        mainActivity.prefs.setSocksAddress("127.0.0.1");
        mainActivity.prefs.setSocksPort(1080);
        mainActivity.prefs.setSocksUdpAddress("");
        mainActivity.prefs.setSocksUsername("");
        mainActivity.prefs.setSocksPassword("");
        mainActivity.prefs.setIpv4(true);
        mainActivity.prefs.setIpv6(true);
        mainActivity.prefs.setUdpInTcp(false);
        mainActivity.prefs.setRemoteDns(true);
        mainActivity.prefs.setGlobal(true);

        mainActivity.prefs.setDnsIpv4(edittext_dns_ipv4.getText().toString());
        mainActivity.prefs.setDnsIpv6(edittext_dns_ipv6.getText().toString());
        mainActivity.prefs.setMaintenanceMode(checkbox_maintenance.isChecked());
    }
}