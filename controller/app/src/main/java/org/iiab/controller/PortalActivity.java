/*
 * ============================================================================
 * Name        : PortalActivity.java
 * Author      : IIAB Project
 * Copyright   : Copyright (c) 2026 IIAB Project
 * Description : Webview portal activity
 * ============================================================================
 */
package org.iiab.controller;

import android.os.Bundle;
import android.util.Log;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;

import java.util.concurrent.Executor;

import android.graphics.Bitmap;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

public class PortalActivity extends AppCompatActivity {
    private static final String TAG = "IIAB-Portal";
    private WebView webView;
    private boolean isPageLoading = false;
    private android.webkit.ValueCallback<android.net.Uri[]> filePathCallback;
    private final static int FILECHOOSER_RESULTCODE = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_portal);

        // 1. Basic WebView configuration
        webView = findViewById(R.id.myWebView);

        LinearLayout bottomNav = findViewById(R.id.bottomNav);
        Button btnHandle = findViewById(R.id.btnHandle); // The new handle
        Button btnHideNav = findViewById(R.id.btnHideNav); // Button to close

        Button btnBack = findViewById(R.id.btnBack);
        Button btnHome = findViewById(R.id.btnHome);
        Button btnReload = findViewById(R.id.btnReload);
        Button btnExit = findViewById(R.id.btnExit);
        Button btnForward = findViewById(R.id.btnForward);

        // --- PREPARE HIDDEN BAR ---
        // Wait for Android to draw the screen to determine bar height
        // and hide it exactly below the bottom edge.
        bottomNav.post(() -> {
            bottomNav.setTranslationY(bottomNav.getHeight()); // Move outside the screen
            bottomNav.setVisibility(View.VISIBLE); // Remove invisibility
        });

        // --- AUTO-HIDE TIMER ---
        Handler hideHandler = new Handler(Looper.getMainLooper());

        // This is the hiding action packaged for later use
        Runnable hideRunnable = () -> {
            bottomNav.animate().translationY(bottomNav.getHeight()).setDuration(250);
            btnHandle.setVisibility(View.VISIBLE);
            btnHandle.animate().alpha(1f).setDuration(150);
        };

        // --- Restart timer ---
        Runnable resetTimer = () -> {
            hideHandler.removeCallbacks(hideRunnable);
            hideHandler.postDelayed(hideRunnable, 5000); // Restarts new 5 sec
        };

        // --- HANDLE LOGIC (Show Bar) ---
        btnHandle.setOnClickListener(v -> {
            // 1. Animate entry
            btnHandle.animate().alpha(0f).setDuration(150).withEndAction(() -> btnHandle.setVisibility(View.GONE));
            bottomNav.animate().translationY(0).setDuration(250);

            // 2. Starts countdown
            resetTimer.run();
        });
        // Button actions
        btnBack.setOnClickListener(v -> {
            if (webView.canGoBack()) webView.goBack();
            resetTimer.run();
        });

        btnForward.setOnClickListener(v -> {
            if (webView.canGoForward()) webView.goForward();
            resetTimer.run();
        });

        Preferences prefs = new Preferences(this);
        boolean isVpnActive = prefs.getEnable();

        String rawUrl = getIntent().getStringExtra("TARGET_URL");

        // If for some strange reason the URL arrives empty, we use the security fallback
        if (rawUrl == null || rawUrl.isEmpty()) {
            rawUrl = "http://localhost:8085/home";
        }

        // We are giving the URL secure global reach for all lambdas from now on
        final String finalTargetUrl = rawUrl;

        btnHome.setOnClickListener(v -> {
            webView.loadUrl(finalTargetUrl);
            resetTimer.run();
        });

        // Dual logic: Forced reload or Stop
        btnReload.setOnClickListener(v -> {
            if (isPageLoading) {
                webView.stopLoading();
            } else {
                // Disable cache temporarily
                webView.getSettings().setCacheMode(android.webkit.WebSettings.LOAD_NO_CACHE);
                // Force download from scratch
                webView.clearCache(true);
                webView.reload();
            }
            resetTimer.run();
        });

        // --- NEW: DETECT LOADING TO CHANGE BUTTON TO 'X' ---
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                String url = request.getUrl().toString();
                String host = request.getUrl().getHost();

                // Internal server link (Box)
                if (host != null && (host.equals("box") || host.equals("127.0.0.1") || host.equals("localhost"))) {
                    return false; // Remains in our app and travels through the proxy
                }

                // External link (Real Internet)
                try {
                    // Tell Android to find the correct app to open this (Chrome, YouTube, etc.)
                    Intent intent = new Intent(Intent.ACTION_VIEW, request.getUrl());
                    startActivity(intent);
                } catch (Exception e) {
                    Log.e(TAG, "No app installed to open: " + url);
                }

                return true; // return true means: "WebView, I'll handle it, you ignore this click"
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                isPageLoading = true;
                btnReload.setText("✕"); // Change to Stop
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                isPageLoading = false;
                btnReload.setText("↻"); // Back to Reload

                // Restore cache for normal browsing speed
                view.getSettings().setCacheMode(android.webkit.WebSettings.LOAD_DEFAULT);
            }

            @Override
            public void onReceivedError(WebView view, android.webkit.WebResourceRequest request, android.webkit.WebResourceError error) {
                super.onReceivedError(view, request, error);

                if (request.isForMainFrame()) {
                    String customErrorHtml = "<html><body style='background-color:#1A1A1A;color:#FFFFFF;text-align:center;padding-top:50px;font-family:sans-serif;'>"
                            + "<h2>⚠️ Connection Failed</h2>"
                            + "<p>Unable to reach the secure environment.</p>"
                            + "<p style='color:#888;font-size:12px;'>Error: " + error.getDescription() + "</p>"
                            + "</body></html>";
                    view.loadData(customErrorHtml, "text/html", "UTF-8");
                    isPageLoading = false;
                    btnReload.setText("↻");
                }
            }
        });

        // --- MANUALLY CLOSE BAR LOGIC ---
        btnHideNav.setOnClickListener(v -> {
            hideHandler.removeCallbacks(hideRunnable); // Cancel the timer so it doesn't conflict
            hideRunnable.run(); // Execute hiding action immediately
        });

        // <-- EXIT ACTION -->
        btnExit.setOnClickListener(v -> finish());
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, android.webkit.ValueCallback<android.net.Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (PortalActivity.this.filePathCallback != null) {
                    PortalActivity.this.filePathCallback.onReceiveValue(null);
                }
                PortalActivity.this.filePathCallback = filePathCallback;

                Intent intent = fileChooserParams.createIntent();
                try {
                    startActivityForResult(intent, FILECHOOSER_RESULTCODE);
                } catch (android.content.ActivityNotFoundException e) {
                    PortalActivity.this.filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        // Port and Mirror logic
        int tempPort = prefs.getSocksPort();
        if (tempPort <= 0) tempPort = 1080;

        // We restored the secure variable for the port
        final int finalProxyPort = tempPort;

        // 4. Proxy block (ONLY IF VPN IS ACTIVE)
        if (isVpnActive) {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                ProxyConfig proxyConfig = new ProxyConfig.Builder()
                        .addProxyRule("socks5://127.0.0.1:" + finalProxyPort)
                        .build();

                Executor executor = ContextCompat.getMainExecutor(this);

                ProxyController.getInstance().setProxyOverride(proxyConfig, executor, () -> {
                    Log.d(TAG, "Proxy configured on port: " + finalProxyPort);
                    // Load HTML only when proxy is ready
                    webView.loadUrl(finalTargetUrl);
                });
            } else {
                // Fallback for older devices
                Log.w(TAG, "Proxy Override not supported");
                webView.loadUrl(finalTargetUrl);
            }
        } else {
            // VPN is OFF. Do NOT use proxy. Just load localhost directly.
            webView.loadUrl(finalTargetUrl);
        }
    }

    // Cleanup (Important to not leave the proxy active)
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            ProxyController.getInstance().clearProxyOverride(Runnable::run, () -> {
                Log.d(TAG, "WebView proxy released");
            });
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILECHOOSER_RESULTCODE) {
            if (filePathCallback == null) return;

            android.net.Uri[] results = null;

            if (resultCode == RESULT_OK && data != null) {
                String dataString = data.getDataString();
                if (dataString != null) {
                    results = new android.net.Uri[]{android.net.Uri.parse(dataString)};
                } else if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    results = new android.net.Uri[count];
                    for (int i = 0; i < count; i++) {
                        results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                }
            }

            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }
}
