package ua.at200.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;
import android.webkit.DownloadListener;
import android.webkit.GeolocationPermissions;
import android.webkit.HttpAuthHandler;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.SslErrorHandler;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebViewDatabase;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;
import android.widget.AdapterView;
import android.widget.Button;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Легкий браузер для Toshiba AT200 (Android 4.0.3, API 15).
 * Використовує лише API рівня 15 і нижче, без AppCompat та сторонніх бібліотек.
 */
public class MainActivity extends Activity {

    private static final String HOME_URL = "about:home";
    private static final int REQ_FILE = 101;
    private static final int MAX_TABS = 8;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private static final String DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/60.0.3112.116 Safari/537.36";

    private static final String[] ENGINE_NAMES = {"Google", "DuckDuckGo", "Bing", "Yandex"};
    private static final String[] ENGINE_URLS = {
            "https://www.google.com/search?q=",
            "https://duckduckgo.com/html/?q=",
            "https://www.bing.com/search?q=",
            "https://yandex.ua/search/?text="};
    private static final String[] ENGINE_PARAM = {"q", "q", "q", "text"};

    private static final String[] ZOOM_NAMES = {"Дрібний (80%)", "Звичайний (100%)", "Великий (130%)", "Дуже великий (160%)"};
    private static final int[] ZOOM_VALUES = {80, 100, 130, 160};

    private static class Tab {
        WebView web;
        String url = "";
        String title = "";
        String pending;
        String errorUrl;
        boolean desktop;
        boolean loading;
        int progress;
        Tab opener;
        LinearLayout header;
        TextView titleView;
    }

    private Store store;
    private final List<Tab> tabs = new ArrayList<Tab>();
    private Tab cur;
    private final Handler handler = new Handler();
    private float density;

    private FrameLayout root;
    private LinearLayout main;
    private HorizontalScrollView tabScroll;
    private LinearLayout tabStrip;
    private FrameLayout content;
    private EditText urlBox;
    private IconView btnBack, btnFwd, btnReload, btnHome, btnStar, btnMenu, lockIcon;
    private ProgressBar progressBar;
    private LinearLayout findBar;
    private EditText findBox;
    private TextView findCount;

    private View customView;
    private WebChromeClient.CustomViewCallback customCb;
    private ValueCallback<Uri> uploadMsg;
    private Bitmap videoPoster;

    private final Set<String> sslAllowed = new HashSet<String>();
    private final Map<String, List<SslErrorHandler>> sslPending = new HashMap<String, List<SslErrorHandler>>();

    // =====================================================================
    // Життєвий цикл
    // =====================================================================

    @Override
    protected void onCreate(android.os.Bundle b) {
        super.onCreate(b);
        density = getResources().getDisplayMetrics().density;
        store = new Store(this);
        CookieSyncManager.createInstance(this);
        CookieManager.getInstance().setAcceptCookie(true);

        buildUi();
        setContentView(root);

        restoreTabs();
        if (b == null) handleIntent(getIntent());
        if (tabs.isEmpty()) newTab(store.home(), true);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent i) {
        if (i == null) return;
        if (Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null) {
            String u = i.getDataString();
            Tab t = newTab(u, true);
            if (t == null && cur != null) load(cur, u);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        CookieSyncManager.getInstance().startSync();
        if (cur != null && cur.web != null) {
            cur.web.onResume();
            cur.web.resumeTimers();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (customView != null) hideCustomView();
        saveTabs();
        CookieSyncManager.getInstance().sync();
        CookieSyncManager.getInstance().stopSync();
        if (cur != null && cur.web != null) {
            cur.web.onPause();
            cur.web.pauseTimers();
        }
    }

    @Override
    protected void onDestroy() {
        for (Tab t : new ArrayList<Tab>(tabs)) destroyWeb(t);
        super.onDestroy();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        freeBackgroundTabs();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_RUNNING_LOW) freeBackgroundTabs();
    }

    private void freeBackgroundTabs() {
        for (Tab t : tabs) {
            if (t != cur && t.web != null) {
                t.pending = t.url;
                destroyWeb(t);
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            hideCustomView();
            return;
        }
        if (findBar.getVisibility() == View.VISIBLE) {
            closeFind();
            return;
        }
        if (cur != null && cur.web != null && cur.web.canGoBack()) {
            cur.web.goBack();
            return;
        }
        if (cur != null && (tabs.size() > 1) && cur.opener != null) {
            closeTab(cur);
            return;
        }
        finish();
    }

    // =====================================================================
    // Інтерфейс
    // =====================================================================

    private int dp(int v) {
        return (int) (v * density + 0.5f);
    }

    private IconView icon(int type, View.OnClickListener l) {
        IconView v = new IconView(this, type);
        v.setOnClickListener(l);
        return v;
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);

        main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        root.addView(main, new FrameLayout.LayoutParams(MATCH, MATCH));

        // --- смуга вкладок ---
        tabScroll = new HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabScroll.setBackgroundColor(0xFFD5D9DE);
        tabStrip = new LinearLayout(this);
        tabStrip.setOrientation(LinearLayout.HORIZONTAL);
        tabScroll.addView(tabStrip, new FrameLayout.LayoutParams(WRAP, MATCH));
        main.addView(tabScroll, new LinearLayout.LayoutParams(MATCH, dp(36)));

        // --- панель інструментів ---
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFFF4F5F6);
        bar.setPadding(dp(4), dp(2), dp(4), dp(2));

        btnBack = icon(IconView.BACK, new View.OnClickListener() {
            public void onClick(View v) {
                if (cur != null && cur.web != null && cur.web.canGoBack()) cur.web.goBack();
            }
        });
        btnFwd = icon(IconView.FORWARD, new View.OnClickListener() {
            public void onClick(View v) {
                if (cur != null && cur.web != null && cur.web.canGoForward()) cur.web.goForward();
            }
        });
        btnReload = icon(IconView.RELOAD, new View.OnClickListener() {
            public void onClick(View v) {
                reloadOrStop();
            }
        });
        btnHome = icon(IconView.HOME, new View.OnClickListener() {
            public void onClick(View v) {
                if (cur != null) load(cur, store.home());
            }
        });
        bar.addView(btnBack, new LinearLayout.LayoutParams(dp(44), dp(44)));
        bar.addView(btnFwd, new LinearLayout.LayoutParams(dp(44), dp(44)));
        bar.addView(btnReload, new LinearLayout.LayoutParams(dp(44), dp(44)));
        bar.addView(btnHome, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable pb = new GradientDrawable();
        pb.setColor(Color.WHITE);
        pb.setCornerRadius(dp(20));
        pb.setStroke(dp(1), 0xFFC8CCD0);
        pill.setBackgroundDrawable(pb);

        lockIcon = new IconView(this, IconView.LOCK);
        lockIcon.setClickable(false);
        lockIcon.setFocusable(false);
        LinearLayout.LayoutParams llock = new LinearLayout.LayoutParams(dp(32), dp(32));
        llock.leftMargin = dp(6);
        pill.addView(lockIcon, llock);

        urlBox = new EditText(this);
        urlBox.setBackgroundDrawable(null);
        urlBox.setSingleLine(true);
        urlBox.setTextSize(15);
        urlBox.setHint("Адреса або пошуковий запит");
        urlBox.setSelectAllOnFocus(true);
        urlBox.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlBox.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        urlBox.setPadding(dp(4), 0, dp(4), 0);
        urlBox.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int actionId, KeyEvent e) {
                boolean enter = e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER
                        && e.getAction() == KeyEvent.ACTION_DOWN;
                if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                    go();
                    return true;
                }
                return false;
            }
        });
        pill.addView(urlBox, new LinearLayout.LayoutParams(0, MATCH, 1f));

        btnStar = icon(IconView.STAR, new View.OnClickListener() {
            public void onClick(View v) {
                toggleBookmark();
            }
        });
        pill.addView(btnStar, new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout.LayoutParams lpill = new LinearLayout.LayoutParams(0, dp(40), 1f);
        lpill.leftMargin = dp(6);
        lpill.rightMargin = dp(4);
        bar.addView(pill, lpill);

        btnMenu = icon(IconView.MENU, new View.OnClickListener() {
            public void onClick(View v) {
                showMenu();
            }
        });
        bar.addView(btnMenu, new LinearLayout.LayoutParams(dp(44), dp(44)));
        main.addView(bar, new LinearLayout.LayoutParams(MATCH, dp(48)));

        // --- індикатор завантаження ---
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setVisibility(View.GONE);
        main.addView(progressBar, new LinearLayout.LayoutParams(MATCH, dp(3)));

        // --- вміст ---
        content = new FrameLayout(this);
        content.setFocusableInTouchMode(true);
        main.addView(content, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        // --- пошук на сторінці ---
        findBar = new LinearLayout(this);
        findBar.setOrientation(LinearLayout.HORIZONTAL);
        findBar.setGravity(Gravity.CENTER_VERTICAL);
        findBar.setBackgroundColor(0xFFEAECEE);
        findBar.setPadding(dp(8), dp(2), dp(4), dp(2));
        findBar.setVisibility(View.GONE);

        findBox = new EditText(this);
        findBox.setSingleLine(true);
        findBox.setHint("Знайти на сторінці");
        findBox.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        findBox.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void afterTextChanged(Editable s) {
                doFind(s.toString());
            }
        });
        findBox.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int actionId, KeyEvent e) {
                if (cur != null && cur.web != null) cur.web.findNext(true);
                return true;
            }
        });
        findBar.addView(findBox, new LinearLayout.LayoutParams(0, WRAP, 1f));

        findCount = new TextView(this);
        findCount.setTextSize(13);
        findCount.setPadding(dp(8), 0, dp(8), 0);
        findBar.addView(findCount, new LinearLayout.LayoutParams(WRAP, WRAP));

        findBar.addView(icon(IconView.UP, new View.OnClickListener() {
            public void onClick(View v) {
                if (cur != null && cur.web != null) cur.web.findNext(false);
            }
        }), new LinearLayout.LayoutParams(dp(44), dp(44)));
        findBar.addView(icon(IconView.DOWN, new View.OnClickListener() {
            public void onClick(View v) {
                if (cur != null && cur.web != null) cur.web.findNext(true);
            }
        }), new LinearLayout.LayoutParams(dp(44), dp(44)));
        findBar.addView(icon(IconView.CLOSE, new View.OnClickListener() {
            public void onClick(View v) {
                closeFind();
            }
        }), new LinearLayout.LayoutParams(dp(44), dp(44)));
        main.addView(findBar, new LinearLayout.LayoutParams(MATCH, WRAP));
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(urlBox.getWindowToken(), 0);
        imm.hideSoftInputFromWindow(findBox.getWindowToken(), 0);
        if (cur != null && cur.web != null) cur.web.requestFocus();
        else content.requestFocus();
    }

    // =====================================================================
    // Вкладки
    // =====================================================================

    private String tabTitle(Tab t) {
        if (t.title != null && t.title.length() > 0) return t.title;
        String u = displayUrl(t.url);
        return u.length() > 0 ? u : "Нова вкладка";
    }

    private Tab newTab(String url, boolean select) {
        if (tabs.size() >= MAX_TABS) {
            toast("Забагато вкладок (максимум " + MAX_TABS + ")");
            return null;
        }
        Tab t = new Tab();
        t.pending = url;
        if (url != null) t.url = url;
        tabs.add(t);
        if (select) switchTo(t);
        else refreshStrip();
        return t;
    }

    private void switchTo(Tab t) {
        if (t == null) return;
        closeFind();
        if (cur != null && cur != t && cur.web != null) {
            content.removeView(cur.web);
            cur.web.onPause();
        }
        cur = t;
        ensureWeb(t);
        if (t.web.getParent() == null) {
            content.addView(t.web, new FrameLayout.LayoutParams(MATCH, MATCH));
        }
        t.web.onResume();
        refreshStrip();
        updateUi(true);
    }

    private void closeTab(Tab t) {
        int idx = tabs.indexOf(t);
        if (idx < 0) return;
        tabs.remove(t);
        destroyWeb(t);
        if (tabs.isEmpty()) {
            cur = null;
            newTab(store.home(), true);
            return;
        }
        if (cur == t) {
            Tab next = (t.opener != null && tabs.contains(t.opener))
                    ? t.opener : tabs.get(Math.min(idx, tabs.size() - 1));
            switchTo(next);
        } else {
            refreshStrip();
        }
    }

    private void destroyWeb(Tab t) {
        WebView w = t.web;
        if (w == null) return;
        t.web = null;
        try {
            content.removeView(w);
            w.stopLoading();
            w.destroy();
        } catch (Exception ignored) {
        }
    }

    private void refreshStrip() {
        tabStrip.removeAllViews();
        for (final Tab t : tabs) {
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(10), 0, dp(2), 0);

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(t == cur ? 0xFFF4F5F6 : 0xFFC3C8CE);
            float r = dp(8);
            bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
            item.setBackgroundDrawable(bg);

            TextView tv = new TextView(this);
            tv.setSingleLine(true);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            tv.setTextSize(13);
            tv.setTextColor(t == cur ? 0xFF111111 : 0xFF444A50);
            tv.setMinWidth(dp(80));
            tv.setMaxWidth(dp(170));
            tv.setText(tabTitle(t));
            t.titleView = tv;
            item.addView(tv, new LinearLayout.LayoutParams(WRAP, WRAP));

            IconView x = icon(IconView.CLOSE, new View.OnClickListener() {
                public void onClick(View v) {
                    closeTab(t);
                }
            });
            item.addView(x, new LinearLayout.LayoutParams(dp(30), dp(30)));

            item.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (t != cur) switchTo(t);
                }
            });
            t.header = item;

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, MATCH);
            lp.topMargin = dp(4);
            lp.leftMargin = dp(2);
            tabStrip.addView(item, lp);
        }
        IconView plus = icon(IconView.PLUS, new View.OnClickListener() {
            public void onClick(View v) {
                newTab(store.home(), true);
            }
        });
        tabStrip.addView(plus, new LinearLayout.LayoutParams(dp(36), dp(36)));

        tabScroll.post(new Runnable() {
            public void run() {
                if (cur != null && cur.header != null) {
                    tabScroll.smoothScrollTo(Math.max(0, cur.header.getLeft() - dp(24)), 0);
                }
            }
        });
    }

    private void updateUi(boolean forceUrl) {
        Tab t = cur;
        if (t == null) return;
        WebView w = t.web;
        if (forceUrl || !urlBox.hasFocus()) urlBox.setText(displayUrl(t.url));
        btnBack.setActive(w != null && w.canGoBack());
        btnFwd.setActive(w != null && w.canGoForward());
        btnReload.setType(t.loading ? IconView.STOP : IconView.RELOAD);
        boolean https = t.url != null && t.url.startsWith("https://");
        boolean web = isWebUrl(t.url);
        lockIcon.setIconColor(https ? 0xFF2E9E4F : 0xFF8A9096);
        lockIcon.setVisibility(web ? View.VISIBLE : View.INVISIBLE);
        btnStar.setActive(web);
        btnStar.setFilled(web && store.isBookmarked(t.url));
        progressBar.setProgress(t.progress);
        progressBar.setVisibility(t.loading ? View.VISIBLE : View.GONE);
        if (t.titleView != null) t.titleView.setText(tabTitle(t));
    }

    // =====================================================================
    // Навігація
    // =====================================================================

    private static boolean isWebUrl(String u) {
        return u != null && (u.startsWith("http://") || u.startsWith("https://"));
    }

    private static String displayUrl(String u) {
        if (u == null || u.startsWith("about:") || u.startsWith("data:")) return "";
        return u;
    }

    private void go() {
        if (cur == null) return;
        String text = urlBox.getText().toString();
        load(cur, text);
    }

    private void load(Tab t, String input) {
        ensureWeb(t);
        t.errorUrl = null;
        String url = resolve(input);
        hideKeyboard();
        if (HOME_URL.equals(url)) {
            loadHome(t);
        } else {
            t.url = url;
            t.web.loadUrl(url);
        }
        if (t == cur) updateUi(true);
    }

    private String resolve(String input) {
        String s = input == null ? "" : input.trim();
        if (s.length() == 0) return HOME_URL;
        if (s.startsWith("about:") || s.startsWith("javascript:")) return s;
        if (s.matches("^[a-zA-Z][a-zA-Z0-9+.\\-]*://.*")) return s;
        boolean hasSpace = s.indexOf(' ') >= 0;
        boolean looksLikeHost = s.indexOf('.') > 0 || s.startsWith("localhost");
        if (!hasSpace && looksLikeHost) return "http://" + s;
        try {
            return ENGINE_URLS[store.engine()] + URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return ENGINE_URLS[0] + s;
        }
    }

    private void reloadOrStop() {
        if (cur == null || cur.web == null) return;
        if (cur.loading) {
            cur.web.stopLoading();
            cur.loading = false;
            cur.progress = 0;
            updateUi(false);
        } else if (cur.url == null || cur.url.startsWith("about:") || cur.url.length() == 0) {
            loadHome(cur);
        } else {
            cur.web.reload();
        }
    }

    private void toggleBookmark() {
        if (cur == null || !isWebUrl(cur.url)) return;
        if (store.isBookmarked(cur.url)) {
            store.removeBookmark(cur.url);
            toast("Видалено із закладок");
        } else {
            store.addBookmark(cur.title == null ? "" : cur.title, cur.url);
            toast("Додано до закладок");
        }
        updateUi(false);
    }

    private void ensureWeb(Tab t) {
        if (t.web != null) return;
        t.web = createWebView(t);
        String p = t.pending;
        t.pending = null;
        if (p != null) load(t, p);
    }

    // =====================================================================
    // WebView
    // =====================================================================

    private WebView createWebView(final Tab tab) {
        WebView w = new WebView(this);
        w.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        applySettings(w, tab);
        w.setWebViewClient(new Client(tab));
        w.setWebChromeClient(new Chrome(tab));
        w.setDownloadListener(new DownloadListener() {
            public void onDownloadStart(String url, String ua, String cd, String mime, long len) {
                startDownload(url, ua, cd, mime);
            }
        });
        w.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                final WebView wv = (WebView) v;
                WebView.HitTestResult r = wv.getHitTestResult();
                if (r == null) return false;
                final String extra = r.getExtra();
                int type = r.getType();
                if (extra == null) return false;
                if (type == WebView.HitTestResult.SRC_ANCHOR_TYPE) {
                    showLinkMenu(extra, null);
                    return true;
                }
                if (type == WebView.HitTestResult.IMAGE_TYPE) {
                    showLinkMenu(null, extra);
                    return true;
                }
                if (type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                    Handler hh = new Handler() {
                        @Override
                        public void handleMessage(Message msg) {
                            String href = msg.getData().getString("url");
                            showLinkMenu(href, extra);
                        }
                    };
                    wv.requestFocusNodeHref(hh.obtainMessage());
                    return true;
                }
                return false;
            }
        });
        return w;
    }

    private void applySettings(WebView w, Tab tab) {
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(store.js());
        s.setLoadsImagesAutomatically(store.images());
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAppCacheEnabled(true);
        s.setAppCachePath(getCacheDir().getAbsolutePath() + "/appcache");
        s.setAppCacheMaxSize(10 * 1024 * 1024);
        s.setGeolocationEnabled(true);
        s.setGeolocationDatabasePath(getFilesDir().getPath());
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setSupportZoom(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportMultipleWindows(true);
        s.setAllowFileAccess(false);
        s.setSaveFormData(true);
        s.setTextZoom(store.textZoom());
        s.setDefaultTextEncodingName("UTF-8");
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(tab.desktop ? DESKTOP_UA : null);
    }

    private void loadHome(Tab t) {
        ensureWeb(t);
        t.url = HOME_URL;
        t.title = "";
        t.web.loadDataWithBaseURL(HOME_URL, homeHtml(), "text/html", "UTF-8", HOME_URL);
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private String homeHtml() {
        int e = store.engine();
        if (e < 0 || e >= ENGINE_URLS.length) e = 0;
        String full = ENGINE_URLS[e];
        String action = full.substring(0, full.indexOf('?'));
        StringBuilder sb = new StringBuilder();
        sb.append("<html><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .append("<title>Нова вкладка</title><style>")
                .append("body{margin:0;padding:24px;background:#f4f6f8;font-family:sans-serif;color:#222}")
                .append("h1{font-size:28px;font-weight:300;text-align:center;margin:30px 0 20px}")
                .append("form{max-width:640px;margin:0 auto 30px;display:block;white-space:nowrap}")
                .append("input[type=text]{width:75%;box-sizing:border-box;font-size:18px;padding:10px 14px;")
                .append("border:1px solid #bbb;border-radius:22px}")
                .append("button{font-size:16px;padding:10px 16px;margin-left:6px;border:0;border-radius:22px;")
                .append("background:#2179d9;color:#fff}")
                .append(".g{max-width:900px;margin:0 auto;text-align:center}")
                .append(".g a{display:inline-block;width:150px;margin:8px;padding:14px 8px;background:#fff;")
                .append("border-radius:10px;text-decoration:none;color:#222;font-size:14px;overflow:hidden;")
                .append("text-overflow:ellipsis;white-space:nowrap;vertical-align:top}")
                .append(".g b{display:block;width:44px;height:44px;line-height:44px;margin:0 auto 8px;")
                .append("border-radius:22px;background:#2179d9;color:#fff;font-size:22px}")
                .append("p{text-align:center;color:#777}")
                .append("</style></head><body>")
                .append("<h1>Браузер</h1>")
                .append("<form action=\"").append(esc(action)).append("\" method=\"get\">")
                .append("<input type=\"text\" name=\"").append(ENGINE_PARAM[e]).append("\" placeholder=\"Пошук у ")
                .append(ENGINE_NAMES[e]).append("\"><button type=\"submit\">Знайти</button></form>");
        List<Store.Entry> bm = store.bookmarks();
        if (bm.isEmpty()) {
            sb.append("<p>Закладок поки немає. Відкрийте сайт і натисніть зірочку в адресному рядку.</p>");
        } else {
            sb.append("<div class=\"g\">");
            int n = Math.min(bm.size(), 16);
            for (int i = 0; i < n; i++) {
                Store.Entry en = bm.get(i);
                String t = en.title.length() > 0 ? en.title : en.url;
                String letter = t.substring(0, 1).toUpperCase();
                sb.append("<a href=\"").append(esc(en.url)).append("\"><b>").append(esc(letter))
                        .append("</b>").append(esc(t)).append("</a>");
            }
            sb.append("</div>");
        }
        sb.append("</body></html>");
        return sb.toString();
    }

    private String errorHtml(String failingUrl, int code, String desc) {
        String msg;
        switch (code) {
            case WebViewClient.ERROR_HOST_LOOKUP:
                msg = "Не вдалося знайти сервер. Перевірте адресу та підключення до інтернету.";
                break;
            case WebViewClient.ERROR_CONNECT:
            case WebViewClient.ERROR_IO:
                msg = "Не вдалося з'єднатися із сайтом. Перевірте інтернет.";
                break;
            case WebViewClient.ERROR_TIMEOUT:
                msg = "Сайт занадто довго не відповідає.";
                break;
            case WebViewClient.ERROR_FAILED_SSL_HANDSHAKE:
                msg = "Не вдалося встановити захищене з'єднання. Android 4.0 не підтримує сучасні версії "
                        + "шифрування (TLS 1.2), яких вимагають деякі сайти. Спробуйте адресу з http:// "
                        + "або інший сайт.";
                break;
            default:
                msg = desc == null ? "Сторінку не вдалося завантажити." : desc;
        }
        return "<html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\">"
                + "<title>Помилка</title></head><body style=\"font-family:sans-serif;padding:30px;color:#333\">"
                + "<h2>Сторінка недоступна</h2><p>" + esc(msg) + "</p>"
                + "<p style=\"color:#888;word-break:break-all\">" + esc(failingUrl) + "</p>"
                + "<p><a href=\"" + esc(failingUrl) + "\" style=\"font-size:20px\">Спробувати ще раз</a></p>"
                + "</body></html>";
    }

    private boolean handleExternal(WebView v, String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        if (lower.startsWith("http:") || lower.startsWith("https:") || lower.startsWith("about:")
                || lower.startsWith("javascript:") || lower.startsWith("data:")) {
            return false;
        }
        if (lower.startsWith("file:")) {
            toast("Локальні файли заблоковано");
            return true;
        }
        try {
            Intent i;
            if (lower.startsWith("intent:")) {
                i = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                i.addCategory(Intent.CATEGORY_BROWSABLE);
                i.setComponent(null);
            } else {
                i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            }
            startActivity(i);
        } catch (Exception e) {
            toast("Немає програми для відкриття цього посилання");
        }
        return true;
    }

    private class Client extends WebViewClient {
        private final Tab tab;

        Client(Tab t) {
            tab = t;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            if (url != null && url.equals(tab.errorUrl)) tab.errorUrl = null;
            return handleExternal(view, url);
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            tab.url = url == null ? "" : url;
            tab.loading = true;
            tab.progress = 5;
            if (tab == cur) updateUi(false);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            tab.loading = false;
            tab.progress = 100;
            if (url != null) tab.url = url;
            String t = view.getTitle();
            if (t != null && t.length() > 0 && !t.equals(url)) tab.title = t;
            boolean isError = tab.errorUrl != null && tab.errorUrl.equals(url);
            if (!isError && isWebUrl(url)) store.addHistory(tab.title == null ? "" : tab.title, url);
            CookieSyncManager.getInstance().sync();
            if (tab == cur) updateUi(false);
            else if (tab.titleView != null) tab.titleView.setText(tabTitle(tab));
        }

        @Override
        public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
            if (failingUrl == null || errorCode == ERROR_UNSUPPORTED_SCHEME) return;
            tab.errorUrl = failingUrl;
            view.loadDataWithBaseURL(failingUrl, errorHtml(failingUrl, errorCode, description),
                    "text/html", "UTF-8", failingUrl);
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler h, SslError error) {
            final String host = hostOf(error.getUrl());
            if (sslAllowed.contains(host)) {
                h.proceed();
                return;
            }
            List<SslErrorHandler> list = sslPending.get(host);
            if (list != null) {
                list.add(h);
                return;
            }
            list = new ArrayList<SslErrorHandler>();
            list.add(h);
            sslPending.put(host, list);

            String why;
            switch (error.getPrimaryError()) {
                case SslError.SSL_EXPIRED:
                    why = "Термін дії сертифіката минув (або на планшеті неправильна дата).";
                    break;
                case SslError.SSL_IDMISMATCH:
                    why = "Сертифікат виданий для іншого сайту.";
                    break;
                case SslError.SSL_NOTYETVALID:
                    why = "Сертифікат ще не діє (перевірте дату на планшеті).";
                    break;
                default:
                    why = "Сертифікат не визнано довіреним. На Android 4.0 застарілий список "
                            + "центрів сертифікації, тому таке трапляється і на справжніх сайтах.";
            }
            if (isFinishing()) {
                resolveSsl(host, false);
                return;
            }
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle("Проблема із сертифікатом")
                    .setMessage(host + "\n\n" + why + "\n\nПродовжити на власний ризик? "
                            + "Не вводьте паролі та дані карток на сайтах, яким не довіряєте.")
                    .setPositiveButton("Продовжити", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            resolveSsl(host, true);
                        }
                    })
                    .setNegativeButton("Назад", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            resolveSsl(host, false);
                        }
                    })
                    .setOnCancelListener(new DialogInterface.OnCancelListener() {
                        public void onCancel(DialogInterface d) {
                            resolveSsl(host, false);
                        }
                    })
                    .show();
        }

        @Override
        public void onReceivedHttpAuthRequest(WebView view, final HttpAuthHandler h,
                                              final String host, final String realm) {
            String[] saved = view.getHttpAuthUsernamePassword(host, realm);
            if (h.useHttpAuthUsernamePassword() && saved != null && saved.length == 2) {
                h.proceed(saved[0], saved[1]);
                return;
            }
            final WebView wv = view;
            LinearLayout box = new LinearLayout(MainActivity.this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(20), dp(10), dp(20), dp(10));
            final EditText user = new EditText(MainActivity.this);
            user.setHint("Користувач");
            user.setSingleLine(true);
            final EditText pass = new EditText(MainActivity.this);
            pass.setHint("Пароль");
            pass.setSingleLine(true);
            pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            box.addView(user, new LinearLayout.LayoutParams(MATCH, WRAP));
            box.addView(pass, new LinearLayout.LayoutParams(MATCH, WRAP));
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle("Вхід на " + host)
                    .setMessage(realm)
                    .setView(box)
                    .setPositiveButton("Увійти", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            String u = user.getText().toString();
                            String p = pass.getText().toString();
                            wv.setHttpAuthUsernamePassword(host, realm, u, p);
                            h.proceed(u, p);
                        }
                    })
                    .setNegativeButton("Скасувати", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            h.cancel();
                        }
                    })
                    .setOnCancelListener(new DialogInterface.OnCancelListener() {
                        public void onCancel(DialogInterface d) {
                            h.cancel();
                        }
                    })
                    .show();
        }

        @Override
        public void onFormResubmission(WebView view, final Message dontResend, final Message resend) {
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle("Повторне надсилання")
                    .setMessage("Сторінка показана після відправлення форми. Надіслати дані ще раз?")
                    .setPositiveButton("Надіслати", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            resend.sendToTarget();
                        }
                    })
                    .setNegativeButton("Скасувати", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            dontResend.sendToTarget();
                        }
                    })
                    .setOnCancelListener(new DialogInterface.OnCancelListener() {
                        public void onCancel(DialogInterface d) {
                            dontResend.sendToTarget();
                        }
                    })
                    .show();
        }
    }

    private static String hostOf(String url) {
        try {
            String h = Uri.parse(url).getHost();
            return h == null ? "" : h;
        } catch (Exception e) {
            return "";
        }
    }

    private void resolveSsl(String host, boolean ok) {
        List<SslErrorHandler> list = sslPending.remove(host);
        if (ok) sslAllowed.add(host);
        if (list == null) return;
        for (SslErrorHandler h : list) {
            try {
                if (ok) h.proceed();
                else h.cancel();
            } catch (Exception ignored) {
            }
        }
    }

    private class Chrome extends WebChromeClient {
        private final Tab tab;

        Chrome(Tab t) {
            tab = t;
        }

        @Override
        public void onProgressChanged(WebView view, int p) {
            tab.progress = p;
            if (tab == cur) {
                progressBar.setProgress(p);
                if (p >= 100) {
                    tab.loading = false;
                    updateUi(false);
                } else if (!tab.loading) {
                    tab.loading = true;
                    updateUi(false);
                }
            }
        }

        @Override
        public void onReceivedTitle(WebView view, String title) {
            if (title != null && title.length() > 0) tab.title = title;
            if (tab.titleView != null) tab.titleView.setText(tabTitle(tab));
        }

        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean userGesture, Message resultMsg) {
            Tab nt = newTab(null, true);
            if (nt == null) return false;
            nt.opener = tab;
            WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(nt.web);
            resultMsg.sendToTarget();
            return true;
        }

        @Override
        public void onCloseWindow(final WebView window) {
            handler.post(new Runnable() {
                public void run() {
                    for (Tab t : new ArrayList<Tab>(tabs)) {
                        if (t.web == window) {
                            closeTab(t);
                            return;
                        }
                    }
                }
            });
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback cb) {
            showCustomView(view, cb);
        }

        @Override
        public void onHideCustomView() {
            hideCustomView();
        }

        @Override
        public Bitmap getDefaultVideoPoster() {
            if (videoPoster == null) videoPoster = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            return videoPoster;
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(final String origin,
                                                       final GeolocationPermissions.Callback cb) {
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle("Місцезнаходження")
                    .setMessage(hostOf(origin) + " хоче дізнатися ваше місцезнаходження.")
                    .setPositiveButton("Дозволити", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            cb.invoke(origin, true, false);
                        }
                    })
                    .setNegativeButton("Заборонити", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            cb.invoke(origin, false, false);
                        }
                    })
                    .setOnCancelListener(new DialogInterface.OnCancelListener() {
                        public void onCancel(DialogInterface d) {
                            cb.invoke(origin, false, false);
                        }
                    })
                    .show();
        }

        @Override
        public boolean onJsAlert(WebView view, String url, String message, final JsResult result) {
            if (isFinishing()) {
                result.cancel();
                return true;
            }
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle(hostOf(url))
                    .setMessage(message)
                    .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            result.confirm();
                        }
                    })
                    .setOnCancelListener(new DialogInterface.OnCancelListener() {
                        public void onCancel(DialogInterface d) {
                            result.cancel();
                        }
                    })
                    .show();
            return true;
        }

        @Override
        public boolean onJsConfirm(WebView view, String url, String message, final JsResult result) {
            if (isFinishing()) {
                result.cancel();
                return true;
            }
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle(hostOf(url))
                    .setMessage(message)
                    .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            result.confirm();
                        }
                    })
                    .setNegativeButton("Скасувати", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            result.cancel();
                        }
                    })
                    .setOnCancelListener(new DialogInterface.OnCancelListener() {
                        public void onCancel(DialogInterface d) {
                            result.cancel();
                        }
                    })
                    .show();
            return true;
        }

        @Override
        public boolean onJsPrompt(WebView view, String url, String message, String defaultValue,
                                  final JsPromptResult result) {
            if (isFinishing()) {
                result.cancel();
                return true;
            }
            final EditText input = new EditText(MainActivity.this);
            input.setSingleLine(true);
            if (defaultValue != null) input.setText(defaultValue);
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle(hostOf(url))
                    .setMessage(message)
                    .setView(input)
                    .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            result.confirm(input.getText().toString());
                        }
                    })
                    .setNegativeButton("Скасувати", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            result.cancel();
                        }
                    })
                    .setOnCancelListener(new DialogInterface.OnCancelListener() {
                        public void onCancel(DialogInterface d) {
                            result.cancel();
                        }
                    })
                    .show();
            return true;
        }

        // Вибір файлу для завантаження на сайт. Ці методи прихованого API викликаються
        // WebView на Android 3.0 - 4.3 (різні сигнатури в різних версіях).
        public void openFileChooser(ValueCallback<Uri> cb) {
            openChooser(cb, null);
        }

        public void openFileChooser(ValueCallback<Uri> cb, String acceptType) {
            openChooser(cb, acceptType);
        }

        public void openFileChooser(ValueCallback<Uri> cb, String acceptType, String capture) {
            openChooser(cb, acceptType);
        }
    }

    // =====================================================================
    // Повноекранне відео
    // =====================================================================

    private class VideoEnd implements MediaPlayer.OnCompletionListener, MediaPlayer.OnErrorListener {
        public void onCompletion(MediaPlayer mp) {
            hideCustomView();
        }

        public boolean onError(MediaPlayer mp, int what, int extra) {
            hideCustomView();
            return true;
        }
    }

    private void showCustomView(View v, WebChromeClient.CustomViewCallback cb) {
        if (customView != null) {
            cb.onCustomViewHidden();
            return;
        }
        customView = v;
        customCb = cb;
        v.setBackgroundColor(Color.BLACK);
        main.setVisibility(View.GONE);
        root.addView(v, new FrameLayout.LayoutParams(MATCH, MATCH));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LOW_PROFILE);
        if (v instanceof FrameLayout) {
            View child = ((FrameLayout) v).getFocusedChild();
            if (child instanceof VideoView) {
                VideoEnd end = new VideoEnd();
                ((VideoView) child).setOnCompletionListener(end);
                ((VideoView) child).setOnErrorListener(end);
            }
        }
    }

    private void hideCustomView() {
        if (customView == null) return;
        View v = customView;
        WebChromeClient.CustomViewCallback cb = customCb;
        customView = null;
        customCb = null;
        root.removeView(v);
        main.setVisibility(View.VISIBLE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        if (cb != null) {
            try {
                cb.onCustomViewHidden();
            } catch (Exception ignored) {
            }
        }
    }

    // =====================================================================
    // Файли: вибір для сайту та завантаження
    // =====================================================================

    private void openChooser(ValueCallback<Uri> cb, String accept) {
        if (uploadMsg != null) uploadMsg.onReceiveValue(null);
        uploadMsg = cb;
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        String type = "*/*";
        if (accept != null && accept.length() > 0 && accept.indexOf(',') < 0) type = accept;
        i.setType(type);
        try {
            startActivityForResult(Intent.createChooser(i, "Виберіть файл"), REQ_FILE);
        } catch (Exception e) {
            uploadMsg = null;
            cb.onReceiveValue(null);
            toast("Немає програми для вибору файлів");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_FILE) {
            if (uploadMsg != null) {
                Uri r = (data == null || res != RESULT_OK) ? null : data.getData();
                uploadMsg.onReceiveValue(r);
                uploadMsg = null;
            }
            return;
        }
        super.onActivityResult(req, res, data);
    }

    private void startDownload(String url, String ua, String cd, String mime) {
        try {
            if (!isWebUrl(url)) {
                toast("Цей тип посилання не можна завантажити");
                return;
            }
            String name = URLUtil.guessFileName(url, cd, mime);
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).mkdirs();
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            if (mime != null && mime.length() > 0) r.setMimeType(mime);
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null) r.addRequestHeader("Cookie", cookies);
            if (ua != null) r.addRequestHeader("User-Agent", ua);
            if (cur != null && isWebUrl(cur.url)) r.addRequestHeader("Referer", cur.url);
            r.setTitle(name);
            r.setDescription(url);
            r.allowScanningByMediaScanner();
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(r);
            toast("Завантаження: " + name);
        } catch (Exception e) {
            toast("Не вдалося почати завантаження");
        }
    }

    // =====================================================================
    // Меню, діалоги
    // =====================================================================

    private void showMenu() {
        if (cur == null) return;
        PopupMenu pm = new PopupMenu(this, btnMenu);
        Menu m = pm.getMenu();
        m.add(0, 1, 0, "Нова вкладка");
        m.add(0, 2, 1, "Закладки");
        m.add(0, 3, 2, "Історія");
        m.add(0, 4, 3, "Знайти на сторінці");
        m.add(0, 5, 4, "Версія для ПК: " + (cur.desktop ? "увімк." : "вимк."));
        m.add(0, 6, 5, "Завантаження");
        m.add(0, 7, 6, "Поділитися посиланням");
        m.add(0, 8, 7, "Налаштування");
        m.add(0, 9, 8, "Вийти");
        pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
                    case 1:
                        newTab(store.home(), true);
                        break;
                    case 2:
                        showEntries(true);
                        break;
                    case 3:
                        showEntries(false);
                        break;
                    case 4:
                        openFind();
                        break;
                    case 5:
                        toggleDesktop();
                        break;
                    case 6:
                        try {
                            startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS));
                        } catch (Exception e) {
                            toast("Не вдалося відкрити список завантажень");
                        }
                        break;
                    case 7:
                        shareText(cur.url);
                        break;
                    case 8:
                        showSettings();
                        break;
                    case 9:
                        finish();
                        break;
                    default:
                        break;
                }
                return true;
            }
        });
        pm.show();
    }

    private void toggleDesktop() {
        if (cur == null || cur.web == null) return;
        cur.desktop = !cur.desktop;
        applySettings(cur.web, cur);
        toast(cur.desktop ? "Версія для ПК увімкнена" : "Мобільна версія");
        if (isWebUrl(cur.url)) cur.web.reload();
    }

    private void shareText(String text) {
        if (!isWebUrl(text)) {
            toast("Немає чим ділитися");
            return;
        }
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(i, "Поділитися"));
    }

    private void showLinkMenu(final String link, final String image) {
        final List<String> labels = new ArrayList<String>();
        final List<Integer> codes = new ArrayList<Integer>();
        if (link != null) {
            labels.add("Відкрити в новій вкладці");
            codes.add(1);
            labels.add("Копіювати посилання");
            codes.add(2);
            labels.add("Поділитися посиланням");
            codes.add(3);
        }
        if (image != null) {
            labels.add("Зберегти зображення");
            codes.add(4);
            labels.add("Відкрити зображення в новій вкладці");
            codes.add(5);
        }
        if (labels.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle(link != null ? link : image)
                .setItems(labels.toArray(new String[labels.size()]), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        switch (codes.get(which)) {
                            case 1:
                                newTab(link, false);
                                toast("Відкрито у фоновій вкладці");
                                break;
                            case 2:
                                copyText(link);
                                break;
                            case 3:
                                shareText(link);
                                break;
                            case 4:
                                startDownload(image, cur != null && cur.web != null
                                        ? cur.web.getSettings().getUserAgentString() : null, null, null);
                                break;
                            case 5:
                                newTab(image, true);
                                break;
                            default:
                                break;
                        }
                    }
                })
                .show();
    }

    private void copyText(String s) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("url", s));
        toast("Скопійовано");
    }

    private void showEntries(final boolean bookmarks) {
        final List<Store.Entry> list = new ArrayList<Store.Entry>(
                bookmarks ? store.bookmarks() : store.history());
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle(bookmarks ? "Закладки" : "Історія");
        if (list.isEmpty()) {
            b.setMessage("Порожньо");
            b.setPositiveButton("Закрити", null);
            b.show();
            return;
        }
        final ListView lv = new ListView(this);
        final ArrayAdapter<Store.Entry> adapter = new ArrayAdapter<Store.Entry>(
                this, android.R.layout.simple_list_item_2, android.R.id.text1, list) {
            @Override
            public View getView(int pos, View convert, ViewGroup parent) {
                View v = super.getView(pos, convert, parent);
                Store.Entry e = getItem(pos);
                ((TextView) v.findViewById(android.R.id.text1)).setText(e.title);
                TextView t2 = (TextView) v.findViewById(android.R.id.text2);
                t2.setText(e.url);
                t2.setSingleLine(true);
                t2.setEllipsize(TextUtils.TruncateAt.END);
                return v;
            }
        };
        lv.setAdapter(adapter);
        b.setView(lv);
        b.setPositiveButton("Закрити", null);
        if (!bookmarks) {
            b.setNeutralButton("Очистити", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    store.clearHistory();
                    toast("Історію очищено");
                }
            });
        }
        final AlertDialog dlg = b.create();
        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                dlg.dismiss();
                if (cur != null) load(cur, list.get(pos).url);
            }
        });
        lv.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> p, View v, final int pos, long id) {
                final Store.Entry e = list.get(pos);
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(e.title)
                        .setItems(new String[]{"Відкрити в новій вкладці", "Видалити"},
                                new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d, int which) {
                                        if (which == 0) {
                                            dlg.dismiss();
                                            newTab(e.url, true);
                                        } else {
                                            if (bookmarks) store.removeBookmark(e.url);
                                            else store.removeHistory(e.url);
                                            adapter.remove(e);
                                            updateUi(false);
                                        }
                                    }
                                })
                        .show();
                return true;
            }
        });
        dlg.show();
    }

    // ---------- пошук на сторінці ----------

    private void openFind() {
        findBar.setVisibility(View.VISIBLE);
        findBox.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        imm.showSoftInput(findBox, 0);
    }

    private void closeFind() {
        if (findBar == null || findBar.getVisibility() != View.VISIBLE) return;
        findBar.setVisibility(View.GONE);
        findBox.setText("");
        findCount.setText("");
        if (cur != null && cur.web != null) cur.web.clearMatches();
        hideKeyboard();
    }

    private void doFind(String text) {
        if (cur == null || cur.web == null) return;
        if (text.length() == 0) {
            cur.web.clearMatches();
            findCount.setText("");
            return;
        }
        int n = cur.web.findAll(text);
        try {
            WebView.class.getMethod("setFindIsUp", Boolean.TYPE).invoke(cur.web, Boolean.TRUE);
        } catch (Exception ignored) {
        }
        findCount.setText(n > 0 ? "Знайдено: " + n : "Нічого");
    }

    // ---------- налаштування ----------

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(14);
        t.setPadding(0, dp(12), 0, dp(2));
        return t;
    }

    private void showSettings() {
        ScrollView sv = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));
        sv.addView(box, new FrameLayout.LayoutParams(MATCH, WRAP));

        final CheckBox cbJs = new CheckBox(this);
        cbJs.setText("JavaScript (потрібен більшості сайтів)");
        cbJs.setChecked(store.js());
        box.addView(cbJs);

        final CheckBox cbImg = new CheckBox(this);
        cbImg.setText("Завантажувати зображення");
        cbImg.setChecked(store.images());
        box.addView(cbImg);

        box.addView(label("Пошукова система"));
        final Spinner spEngine = new Spinner(this);
        ArrayAdapter<String> ea = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, ENGINE_NAMES);
        ea.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spEngine.setAdapter(ea);
        spEngine.setSelection(Math.max(0, Math.min(store.engine(), ENGINE_NAMES.length - 1)));
        box.addView(spEngine);

        box.addView(label("Розмір тексту"));
        final Spinner spZoom = new Spinner(this);
        ArrayAdapter<String> za = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, ZOOM_NAMES);
        za.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spZoom.setAdapter(za);
        int zi = 1;
        for (int i = 0; i < ZOOM_VALUES.length; i++) {
            if (ZOOM_VALUES[i] == store.textZoom()) zi = i;
        }
        spZoom.setSelection(zi);
        box.addView(spZoom);

        box.addView(label("Домашня сторінка"));
        final EditText etHome = new EditText(this);
        etHome.setSingleLine(true);
        etHome.setText(store.home());
        etHome.setHint("about:home або адреса сайту");
        etHome.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        box.addView(etHome);

        Button bCache = new Button(this);
        bCache.setText("Очистити кеш, cookie та збережені дані форм");
        bCache.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                clearBrowsingData();
            }
        });
        box.addView(bCache);

        Button bHist = new Button(this);
        bHist.setText("Очистити історію");
        bHist.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                store.clearHistory();
                toast("Історію очищено");
            }
        });
        box.addView(bHist);

        new AlertDialog.Builder(this)
                .setTitle("Налаштування")
                .setView(sv)
                .setPositiveButton("Зберегти", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        boolean changed = cbJs.isChecked() != store.js() || cbImg.isChecked() != store.images();
                        store.setJs(cbJs.isChecked());
                        store.setImages(cbImg.isChecked());
                        store.setEngine(spEngine.getSelectedItemPosition());
                        store.setTextZoom(ZOOM_VALUES[spZoom.getSelectedItemPosition()]);
                        String h = etHome.getText().toString().trim();
                        store.setHome(h.length() == 0 ? HOME_URL : (h.startsWith("about:") ? h : resolve(h)));
                        for (Tab t : tabs) {
                            if (t.web != null) applySettings(t.web, t);
                        }
                        if (changed && cur != null && cur.web != null && isWebUrl(cur.url)) cur.web.reload();
                        toast("Збережено");
                    }
                })
                .setNegativeButton("Скасувати", null)
                .show();
    }

    private void clearBrowsingData() {
        try {
            if (cur != null && cur.web != null) cur.web.clearCache(true);
            CookieManager.getInstance().removeAllCookie();
            CookieSyncManager.getInstance().sync();
            WebViewDatabase db = WebViewDatabase.getInstance(this);
            db.clearFormData();
            db.clearHttpAuthUsernamePassword();
            WebStorage.getInstance().deleteAllData();
            sslAllowed.clear();
            toast("Дані очищено");
        } catch (Exception e) {
            toast("Не вдалося очистити дані");
        }
    }

    // =====================================================================
    // Збереження вкладок
    // =====================================================================

    private void saveTabs() {
        try {
            JSONArray a = new JSONArray();
            for (Tab t : tabs) {
                String u = (t.web == null && t.pending != null) ? t.pending : t.url;
                if (u == null || u.startsWith("about:") || u.startsWith("data:") || u.length() == 0) u = HOME_URL;
                JSONObject o = new JSONObject();
                o.put("u", u);
                o.put("t", t.title == null ? "" : t.title);
                a.put(o);
            }
            store.putString("tabs", a.toString());
            store.putInt("cur", Math.max(0, tabs.indexOf(cur)));
        } catch (Exception ignored) {
        }
    }

    private void restoreTabs() {
        String s = store.getString("tabs", null);
        if (s == null) return;
        try {
            JSONArray a = new JSONArray(s);
            for (int i = 0; i < a.length() && tabs.size() < MAX_TABS; i++) {
                JSONObject o = a.getJSONObject(i);
                Tab t = new Tab();
                t.url = o.optString("u", HOME_URL);
                t.pending = t.url;
                t.title = o.optString("t", "");
                tabs.add(t);
            }
        } catch (Exception e) {
            tabs.clear();
        }
        if (!tabs.isEmpty()) {
            int ci = store.getInt("cur", 0);
            if (ci < 0 || ci >= tabs.size()) ci = 0;
            switchTo(tabs.get(ci));
        }
    }
}
