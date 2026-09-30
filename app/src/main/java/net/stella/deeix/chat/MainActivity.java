package net.stella.deeix.chat;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public final class MainActivity extends Activity {
    private static final String PREFS = "deeix_chat_preferences";
    private static final String PREF_SERVER_URL = "server_url";
    private static final String PREF_SERVER_URLS = "server_urls";
    private static final String KEY_HANDLED_DEEP_LINK = "handled_deep_link";
    private static final int FILE_CHOOSER_REQUEST = 301;
    private static final int WEB_PERMISSION_REQUEST = 302;
    private static final int STORAGE_PERMISSION_REQUEST = 303;
    private static final int DARK_BLUE = Color.rgb(7, 12, 35);

    private FrameLayout root;
    private WebView webView;
    private ProgressBar progressBar;
    private TextView errorTitle;
    private TextView errorMessage;
    private Button errorRetry;
    private LinearLayout errorPanel;
    private int chromeColor = DARK_BLUE;
    private float touchStartX;
    private float touchStartY;
    private boolean edgeSwipeCandidate;
    private boolean appMenuGestureCandidate;
    private long appMenuGestureStart;
    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest pendingWebPermission;
    private PendingDownload pendingDownload;
    private String homeUrl;
    private String handledDeepLink;
    private String recoveryUrl;
    private SharedPreferences preferences;
    private OnBackInvokedCallback backInvokedCallback;

    private static final class PendingDownload {
        final String url;
        final String userAgent;
        final String contentDisposition;
        final String mimeType;

        PendingDownload(String url, String userAgent, String contentDisposition, String mimeType) {
            this.url = url;
            this.userAgent = userAgent;
            this.contentDisposition = contentDisposition;
            this.mimeType = mimeType;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        homeUrl = normalizeServerUrl(preferences.getString(PREF_SERVER_URL, BuildConfig.DEFAULT_HOME_URL));
        if (homeUrl == null) homeUrl = BuildConfig.DEFAULT_HOME_URL;
        configureWindow();
        createRoot();
        installWebView();
        installNativeControls();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backInvokedCallback = this::handleBack;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, backInvokedCallback);
        }

        // A deep link can arrive together with a restored instance state (e.g. the
        // process died while the activity was in the back stack). Only re-handle the
        // launching intent when it carries a link we have not consumed yet; otherwise
        // the restored WebView keeps the page the user was on.
        Uri intentData = getIntent() == null ? null : getIntent().getData();
        String intentDataString = intentData == null ? null : intentData.toString();
        String consumedLink = savedInstanceState == null
                ? null : savedInstanceState.getString(KEY_HANDLED_DEEP_LINK, null);
        boolean freshDeepLink = intentDataString != null && !intentDataString.equals(consumedLink);

        boolean restored = false;
        if (savedInstanceState != null) {
            try {
                restored = webView.restoreState(savedInstanceState) != null;
            } catch (RuntimeException ignored) {
                restored = false;
            }
        }
        if (freshDeepLink || !restored || webView.getUrl() == null) {
            loadInitialIntent();
            handledDeepLink = intentDataString;
        } else {
            handledDeepLink = consumedLink;
        }
    }

    private void configureWindow() {
        // No edge-to-edge overlay: the WebView lays out below the status bar so the
        // page's own (possibly fixed-position) header stays fully visible. The status
        // and navigation bars themselves are tinted with the page's theme-color, which
        // keeps the chrome color filling all the way to the screen edges.
        getWindow().setStatusBarColor(chromeColor);
        getWindow().setNavigationBarColor(chromeColor);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getWindow().setStatusBarContrastEnforced(false);
            getWindow().setNavigationBarContrastEnforced(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(true);
        }
    }

    private void createRoot() {
        root = new FrameLayout(this);
        root.setBackgroundColor(DARK_BLUE);
        // With setDecorFitsSystemWindows(true) the system insets the content view
        // below the status bar / above the navigation bar, so no manual padding
        // or overlay scrim is needed here.
        setContentView(root);
    }

    private void installWebView() {
        webView = new WebView(this);
        webView.setBackgroundColor(DARK_BLUE);
        root.addView(webView, 0, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        configureWebView();
    }

    private void installNativeControls() {
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        progressBar.setIndeterminate(false);
        progressBar.setProgressDrawable(new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.rgb(91, 87, 255), Color.rgb(105, 190, 255)}));
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(3), Gravity.TOP);
        root.addView(progressBar, progressParams);

        errorPanel = new LinearLayout(this);
        errorPanel.setOrientation(LinearLayout.VERTICAL);
        errorPanel.setGravity(Gravity.CENTER_HORIZONTAL);
        errorPanel.setPadding(dp(28), dp(24), dp(28), dp(24));
        errorPanel.setBackgroundColor(DARK_BLUE);
        errorTitle = new TextView(this);
        errorTitle.setTextColor(Color.WHITE);
        errorTitle.setTextSize(20);
        errorTitle.setGravity(Gravity.CENTER);
        errorMessage = new TextView(this);
        errorMessage.setTextColor(Color.rgb(205, 211, 229));
        errorMessage.setTextSize(15);
        errorMessage.setGravity(Gravity.CENTER);
        errorMessage.setPadding(0, dp(10), 0, dp(18));
        errorRetry = new Button(this);
        errorRetry.setText("重新加载");
        errorRetry.setOnClickListener(view -> retryPage());
        errorPanel.addView(errorTitle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        errorPanel.addView(errorMessage, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        errorPanel.addView(errorRetry, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams errorParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        errorParams.gravity = Gravity.CENTER;
        root.addView(errorPanel, errorParams);
        errorPanel.setVisibility(View.GONE);
    }

    /**
     * Reports the page's {@code <meta name="theme-color">} to native code and keeps
     * watching it, so the system bars follow the web theme even when the user
     * switches themes inside the page without a reload.
     */
    private static final String THEME_OBSERVER_JS =
            "(function(){"
                    + "function deeixReadTheme(){var m=document.querySelector('meta[name=\"theme-color\"]');"
                    + "return m?m.getAttribute('content'):null;}"
                    + "function deeixReportTheme(){try{DeeixTheme.onThemeColor(deeixReadTheme());}catch(e){}}"
                    + "if(window.__deeixThemeObserved)return;"
                    + "window.__deeixThemeObserved=true;"
                    + "deeixReportTheme();"
                    + "new MutationObserver(function(muts){"
                    + "for(var i=0;i<muts.length;i++){var mu=muts[i];"
                    + "if(mu.type==='attributes'){var t=mu.target;"
                    + "if(t&&t.getAttribute&&t.getAttribute('name')==='theme-color'){deeixReportTheme();return;}}"
                    + "else if(mu.type==='childList'){"
                    + "for(var j=0;j<mu.addedNodes.length;j++){var n=mu.addedNodes[j];"
                    + "if(n.nodeType!==1)continue;"
                    + "if(n.getAttribute('name')==='theme-color'){deeixReportTheme();return;}"
                    + "if(n.querySelector&&n.querySelector('meta[name=\"theme-color\"]')){deeixReportTheme();return;}"
                    + "}}}})"
                    + ".observe(document.documentElement,"
                    + "{subtree:true,childList:true,attributes:true,attributeFilter:['content']});"
                    + "})();";

    private final class ThemeBridge {
        @JavascriptInterface
        public void onThemeColor(String color) {
            // JavascriptInterface callbacks arrive on a background thread.
            runOnUiThread(() -> applyThemeColor(color));
        }
    }

    private void applyThemeColor(String spec) {
        int color = parseCssColor(spec, DARK_BLUE);
        if (color == chromeColor) return;
        chromeColor = color;
        getWindow().setStatusBarColor(color);
        getWindow().setNavigationBarColor(color);
        webView.setBackgroundColor(color);
        setLightStatusBar(isLightColor(color));
    }

    private static int parseCssColor(String spec, int fallback) {
        if (spec == null) return fallback;
        String s = spec.trim().toLowerCase(Locale.US);
        try {
            if (s.startsWith("#")) {
                String hex = s.substring(1);
                if (hex.length() == 3) {
                    hex = "" + hex.charAt(0) + hex.charAt(0)
                            + hex.charAt(1) + hex.charAt(1)
                            + hex.charAt(2) + hex.charAt(2);
                }
                if (hex.length() == 6) return 0xFF000000 | (int) Long.parseLong(hex, 16);
                if (hex.length() == 8) return (int) Long.parseLong(hex, 16);
            } else if (s.startsWith("rgb(") && s.endsWith(")")) {
                String[] parts = s.substring(4, s.length() - 1).split(",");
                if (parts.length >= 3) {
                    return Color.rgb(Integer.parseInt(parts[0].trim()),
                            Integer.parseInt(parts[1].trim()),
                            Integer.parseInt(parts[2].trim()));
                }
            }
        } catch (NumberFormatException ignored) {
        }
        return fallback;
    }

    private static boolean isLightColor(int color) {
        double r = Color.red(color) / 255.0;
        double g = Color.green(color) / 255.0;
        double b = Color.blue(color) / 255.0;
        return 0.2126 * r + 0.7152 * g + 0.0722 * b > 0.5;
    }

    private void setLightStatusBar(boolean light) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                int bars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(light ? bars : 0, bars);
            }
        } else {
            View decor = getWindow().getDecorView();
            int flags = decor.getSystemUiVisibility();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (light) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                else flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (light) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                else flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            decor.setSystemUiVisibility(flags);
        }
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);
        // Keep content:// access for user-selected uploads; file:// access remains disabled.
        settings.setAllowContentAccess(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
        }
        settings.setUserAgentString(settings.getUserAgentString() + " DeeixChat/" + BuildConfig.VERSION_NAME);
        // Lets the page report its theme-color so the native chrome can match it.
        webView.addJavascriptInterface(new ThemeBridge(), "DeeixTheme");
        // Do not expose remote debugging in a production APK.
        WebView.setWebContentsDebuggingEnabled(false);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleNavigation(request.getUrl());
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleNavigation(Uri.parse(url));
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                Uri page = Uri.parse(url);
                if (isWebUrl(page) && !isTrustedHost(page)) {
                    view.stopLoading();
                    openExternal(page);
                    return;
                }
                errorPanel.setVisibility(View.GONE);
                progressBar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                CookieManager.getInstance().flush();
                progressBar.setVisibility(View.GONE);
                view.evaluateJavascript(THEME_OBSERVER_JS, null);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request.isForMainFrame()) {
                    showError("无法连接 Deeix Chat", "请检查网络连接，或稍后重试。", true);
                }
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                String current = view.getUrl();
                replaceWebView();
                recoveryUrl = current == null ? homeUrl : current;
                showError("页面需要恢复", "网页进程已退出，可以重新加载当前页面。", true);
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                progressBar.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), FILE_CHOOSER_REQUEST);
                } catch (ActivityNotFoundException exception) {
                    fileCallback = null;
                    Toast.makeText(MainActivity.this, "未找到文件选择器", Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> requestWebPermission(request));
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin,
                                                            GeolocationPermissions.Callback callback) {
                callback.invoke(origin, false, false);
            }
        });

        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, length) -> {
            PendingDownload download = new PendingDownload(url, userAgent, contentDisposition, mimeType);
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                    && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                pendingDownload = download;
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        STORAGE_PERMISSION_REQUEST);
            } else {
                enqueueDownload(download);
            }
        });
    }

    private void loadInitialIntent() {
        Intent intent = getIntent();
        Uri data = intent == null ? null : intent.getData();
        if (data != null && "deeixchat".equalsIgnoreCase(data.getScheme())
                && "settings".equalsIgnoreCase(data.getHost())) {
            webView.loadUrl(homeUrl);
            showServerDialog(false);
            return;
        }
        if (data != null && isTrustedHost(data)) {
            webView.loadUrl(data.toString());
        } else {
            webView.loadUrl(homeUrl);
        }
    }

    private boolean handleNavigation(Uri uri) {
        if (uri == null) return true;
        String scheme = uri.getScheme();
        if (isWebUrl(uri)) {
            if (isTrustedHost(uri)) return false;
            openExternal(uri);
            return true;
        }
        Uri current = Uri.parse(webView.getUrl() == null ? "" : webView.getUrl());
        if (!isTrustedHost(current)) return true;
        if ("intent".equalsIgnoreCase(scheme)) {
            try {
                Intent intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME);
                if (intent.getComponent() != null) intent.setComponent(null);
                intent.addCategory(Intent.CATEGORY_BROWSABLE);
                startActivity(intent);
            } catch (Exception ignored) {
                Toast.makeText(this, "无法打开外部应用", Toast.LENGTH_SHORT).show();
            }
            return true;
        }
        if ("mailto".equalsIgnoreCase(scheme) || "tel".equalsIgnoreCase(scheme)) {
            openExternal(uri);
        }
        return true;
    }

    private boolean isWebUrl(Uri uri) {
        String scheme = uri == null ? null : uri.getScheme();
        return scheme == null || "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    private boolean isTrustedHost(Uri uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())) return false;
        String host = uri.getHost();
        Uri configured = Uri.parse(homeUrl == null ? BuildConfig.DEFAULT_HOME_URL : homeUrl);
        String configuredHost = configured.getHost();
        return host != null && configuredHost != null
                && configuredHost.equalsIgnoreCase(host)
                && effectivePort(configured) == effectivePort(uri);
    }

    private static int effectivePort(Uri uri) {
        int port = uri.getPort();
        if (port == -1) return -1;
        // Treat an explicit default port the same as an omitted one.
        if (port == 443 && "https".equalsIgnoreCase(uri.getScheme())) return -1;
        if (port == 80 && "http".equalsIgnoreCase(uri.getScheme())) return -1;
        return port;
    }

    private void enqueueDownload(PendingDownload download) {
        try {
            Uri uri = Uri.parse(download.url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException("Unsupported download scheme");
            }
            String fileName = android.webkit.URLUtil.guessFileName(
                    download.url, download.contentDisposition, download.mimeType);
            fileName = fileName.replaceAll("[\\\\/:*?\"<>|]", "_");
            String resolvedMime = download.mimeType;
            if (resolvedMime == null || resolvedMime.trim().isEmpty()) {
                resolvedMime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                        MimeTypeMap.getFileExtensionFromUrl(download.url));
            }
            DownloadManager.Request request = new DownloadManager.Request(uri);
            if (resolvedMime != null) request.setMimeType(resolvedMime);
            if (download.userAgent != null) request.addRequestHeader("User-Agent", download.userAgent);
            String cookies = CookieManager.getInstance().getCookie(download.url);
            if (cookies != null) request.addRequestHeader("Cookie", cookies);
            request.setTitle(fileName);
            request.setDescription("Deeix Chat 下载");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setAllowedOverMetered(true);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (manager == null) throw new IllegalStateException("DownloadManager unavailable");
            manager.enqueue(request);
            Toast.makeText(this, "已开始下载", Toast.LENGTH_SHORT).show();
        } catch (Exception exception) {
            Toast.makeText(this, "下载失败，已尝试用浏览器打开", Toast.LENGTH_SHORT).show();
            openExternal(Uri.parse(download.url));
        }
    }

    private void requestWebPermission(PermissionRequest request) {
        Uri origin = Uri.parse(request.getOrigin().toString());
        if (!isTrustedHost(origin)) {
            request.deny();
            return;
        }
        boolean camera = false;
        boolean microphone = false;
        for (String resource : request.getResources()) {
            if (!PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)
                    && !PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                request.deny();
                return;
            }
            camera |= PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource);
            microphone |= PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource);
        }
        if (request.getResources().length == 0) {
            request.deny();
            return;
        }
        if (pendingWebPermission != null) pendingWebPermission.deny();
        ArrayList<String> missing = new ArrayList<>();
        if (camera && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.CAMERA);
        }
        if (microphone && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.RECORD_AUDIO);
        }
        if (missing.isEmpty()) {
            request.grant(request.getResources());
        } else {
            pendingWebPermission = request;
            requestPermissions(missing.toArray(new String[0]), WEB_PERMISSION_REQUEST);
        }
    }

    private void showAppMenu(View anchor) {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(this, anchor);
        menu.getMenu().add("服务器地址").setOnMenuItemClickListener(item -> {
            showServerDialog(false);
            return true;
        });
        menu.getMenu().add("重新加载").setOnMenuItemClickListener(item -> {
            retryPage();
            return true;
        });
        menu.getMenu().add("清除网页缓存").setOnMenuItemClickListener(item -> {
            webView.clearCache(false);
            Toast.makeText(this, "网页缓存已清除", Toast.LENGTH_SHORT).show();
            return true;
        });
        menu.getMenu().add("在浏览器中打开").setOnMenuItemClickListener(item -> {
            openExternal(Uri.parse(webView.getUrl() == null ? homeUrl : webView.getUrl()));
            return true;
        });
        menu.show();
    }

    private void showServerDialog(boolean firstLaunch) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(24), dp(8), dp(24), 0);
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("https://example.com/");
        input.setText(homeUrl);
        layout.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        Set<String> saved = preferences.getStringSet(PREF_SERVER_URLS, new LinkedHashSet<>());
        if (!saved.isEmpty()) {
            TextView label = new TextView(this);
            label.setText("最近使用");
            label.setTextColor(Color.GRAY);
            label.setPadding(0, dp(14), 0, dp(4));
            layout.addView(label);
            for (String url : saved) {
                Button button = new Button(this);
                button.setText(url);
                button.setOnClickListener(view -> input.setText(url));
                layout.addView(button, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            }
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("服务器地址")
                .setMessage("必须是提供 Deeix Chat 页面兼容功能的 HTTPS 地址。")
                .setView(layout)
                .setNegativeButton(firstLaunch ? "使用默认地址" : "取消", (d, which) -> {
                    if (firstLaunch) webView.loadUrl(homeUrl);
                })
                .setPositiveButton("保存并打开", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String normalized = normalizeServerUrl(input.getText().toString());
            if (normalized == null) {
                input.setError("请输入有效的 HTTPS 地址");
                return;
            }
            homeUrl = normalized;
            LinkedHashSet<String> urls = new LinkedHashSet<>(preferences.getStringSet(
                    PREF_SERVER_URLS, new LinkedHashSet<>()));
            urls.remove(normalized);
            urls.add(normalized);
            while (urls.size() > 5) urls.remove(urls.iterator().next());
            preferences.edit().putString(PREF_SERVER_URL, normalized)
                    .putStringSet(PREF_SERVER_URLS, urls).apply();
            dialog.dismiss();
            replaceWebView();
            webView.loadUrl(homeUrl);
        }));
        dialog.show();
    }

    private String normalizeServerUrl(String value) {
        if (value == null) return null;
        String raw = value.trim();
        if (raw.isEmpty() || !raw.matches("(?i)^https://.+")) return null;
        Uri uri;
        try {
            uri = Uri.parse(raw);
        } catch (Exception exception) {
            return null;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null) return null;
        return raw.endsWith("/") ? raw : raw + "/";
    }

    private void showError(String title, String message, boolean showRetry) {
        errorTitle.setText(title);
        errorMessage.setText(message);
        errorRetry.setVisibility(showRetry ? View.VISIBLE : View.GONE);
        progressBar.setVisibility(View.GONE);
        errorPanel.setVisibility(View.VISIBLE);
    }

    private void retryPage() {
        errorPanel.setVisibility(View.GONE);
        progressBar.setVisibility(View.VISIBLE);
        if (recoveryUrl != null) {
            String target = recoveryUrl;
            recoveryUrl = null;
            webView.loadUrl(isTrustedHost(Uri.parse(target)) ? target : homeUrl);
        } else if (webView.getUrl() == null || webView.getUrl().isEmpty()) webView.loadUrl(homeUrl);
        else webView.reload();
    }

    private void replaceWebView() {
        if (webView != null) {
            root.removeView(webView);
            webView.stopLoading();
            webView.destroy();
        }
        installWebView();
    }

    private void openExternal(Uri uri) {
        if (uri == null) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException exception) {
            Toast.makeText(this, "没有可处理此链接的应用", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            touchStartX = event.getX();
            touchStartY = event.getY();
            edgeSwipeCandidate = touchStartX <= dp(72);
            appMenuGestureCandidate = false;
        } else if (event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN
                && event.getPointerCount() == 2) {
            appMenuGestureCandidate = true;
            appMenuGestureStart = android.os.SystemClock.uptimeMillis();
            edgeSwipeCandidate = false;
        } else if (event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN
                || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            edgeSwipeCandidate = false;
            appMenuGestureCandidate = false;
        } else if (event.getActionMasked() == MotionEvent.ACTION_POINTER_UP
                && appMenuGestureCandidate
                && android.os.SystemClock.uptimeMillis() - appMenuGestureStart < 450) {
            appMenuGestureCandidate = false;
            showAppMenu(root);
            return true;
        } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE
                && appMenuGestureCandidate && event.getPointerCount() == 2
                && (Math.abs(event.getX() - touchStartX) > dp(18)
                || Math.abs(event.getY() - touchStartY) > dp(18))) {
            appMenuGestureCandidate = false;
        } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            if (edgeSwipeCandidate
                    && event.getX() - touchStartX >= dp(88)
                    && Math.abs(event.getY() - touchStartY) <= dp(64)) {
                openMobileSidebar();
            }
            edgeSwipeCandidate = false;
        }
        return super.dispatchTouchEvent(event);
    }

    private void openMobileSidebar() {
        Uri current = Uri.parse(webView.getUrl() == null ? "" : webView.getUrl());
        if (!isTrustedHost(current)) return;
        webView.evaluateJavascript(
                "(function(){"
                        + "if(!window.matchMedia('(max-width: 767px)').matches)return;"
                        + "if(document.querySelector('[data-sidebar=\\\"sidebar\\\"][data-mobile=\\\"true\\\"][data-state=\\\"open\\\"]'))return;"
                        + "var button=document.querySelector('header button[aria-label=\\\"打开侧边栏\\\"],header button[aria-label=\\\"Open sidebar\\\"]');"
                        + "if(button){button.click();return;}"
                        + "window.dispatchEvent(new KeyboardEvent('keydown',{key:'b',ctrlKey:true,bubbles:true}));"
                        + "})();", null);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == WEB_PERMISSION_REQUEST && pendingWebPermission != null) {
            boolean granted = grantResults.length > 0;
            for (int result : grantResults) granted &= result == PackageManager.PERMISSION_GRANTED;
            if (granted) pendingWebPermission.grant(pendingWebPermission.getResources());
            else pendingWebPermission.deny();
            pendingWebPermission = null;
        } else if (requestCode == STORAGE_PERMISSION_REQUEST && pendingDownload != null) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            PendingDownload download = pendingDownload;
            pendingDownload = null;
            if (granted) enqueueDownload(download);
            else Toast.makeText(this, "没有存储权限，无法保存下载文件", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST && fileCallback != null) {
            Uri[] result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            fileCallback.onReceiveValue(result);
            fileCallback = null;
        }
    }

    @Override
    public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        Uri data = intent == null ? null : intent.getData();
        handledDeepLink = data == null ? null : data.toString();
        if (data != null && "deeixchat".equalsIgnoreCase(data.getScheme())
                && "settings".equalsIgnoreCase(data.getHost())) {
            showServerDialog(false);
        } else if (data != null && isTrustedHost(data)) {
            webView.loadUrl(data.toString());
        }
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_RUNNING_CRITICAL && webView != null) {
            webView.clearCache(false);
        }
    }

    @Override
    public void onBackPressed() {
        handleBack();
    }

    private void handleBack() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else finish();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putString(KEY_HANDLED_DEEP_LINK, handledDeepLink);
        try {
            if (webView != null) webView.saveState(outState);
        } catch (RuntimeException ignored) {
            // A very large WebView history should not prevent Activity state from saving.
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && backInvokedCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backInvokedCallback);
            backInvokedCallback = null;
        }
        if (fileCallback != null) {
            fileCallback.onReceiveValue(null);
            fileCallback = null;
        }
        if (pendingWebPermission != null) {
            pendingWebPermission.deny();
            pendingWebPermission = null;
        }
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
