package com.hipmh.app;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "HipMain";

    private static final String MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 12; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private static final String[] AD_HOSTS = {
            "doubleclick.net", "googlesyndication", "googleadservices", "adservice",
            "adsbygoogle", "ad-maven", "adnxs", "popads", "popcash", "propellerads",
            "exoclick", "juicyads", "trafficjunky", "adsterra", "hilltopads",
            "clickadu", "adcolony", "appnexus", "pubmatic", "rubiconproject",
            "taboola", "outbrain", "criteo", "mgid.com", "revcontent",
            "googletagmanager", "googletagservices", "securepubads", "pagead2",
            "amazon-adsystem", "casalemedia", "openx.net", "smartadserver",
            "bidswitch", "adform.net", "360yield", "adyoulike", "tynt.com",
            "cloudflareinsights", "scorecardresearch", "quantserve", "hotjar",
            "facebook.net", "analytics.google", "googletag"
    };

    /** 站点自己塞的「免费图库」外链推广卡（g-mh / 18gallery），点了直接无反应 */
    private static final String[] PROMO_HOSTS = {
            "g-mh.com", "18gallery.com"
    };

    /** 登录注册相关路径，站内跳过去也直接拦掉 */
    private static final String[] LOGIN_PATHS = {
            "/login", "/register", "/signin", "/signup", "/user/profile", "/account", "/dashboard"
    };

    private static final String[] PROMO_IMGS = {
            "g-mh-900", "18gallery-1"
    };

    private WebView web;
    private SwipeRefreshLayout swipe;
    private ProgressBar progress;
    private LinearLayout errorView;
    private ImageButton btnTop;
    private ImageButton btnFab;
    private View root;

    private Prefs prefs;
    private Store store;
    private Handler ui;

    private boolean readingMode = false;
    private long lastBackMs = 0L;
    private boolean loadFailed = false;
    private String lastUa = "";
    private int lastZoom = -1;

    private volatile String uaCache = "";
    private volatile String refCache = "";
    private volatile String lineCache = "line1";

    /** assets/t2s.js 里的繁→简字符映射表（首次读取后缓存） */
    private static volatile String t2sMapJs = null;

    private ValueCallback<Uri[]> filePathCallback;
    private ActivityResultLauncher<String[]> filePicker;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = new Prefs(this);
        store = new Store(this);
        ui = new Handler(Looper.getMainLooper());

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);

        setContentView(R.layout.activity_main);

        root = findViewById(R.id.root);
        web = findViewById(R.id.web);
        swipe = findViewById(R.id.swipe);
        progress = findViewById(R.id.progress);
        errorView = findViewById(R.id.errorView);
        btnTop = findViewById(R.id.btnTop);
        btnFab = findViewById(R.id.btnFab);

        ResCache.init(this);
        ResCache.trim();

        filePicker = registerForActivityResult(
                new ActivityResultContracts.OpenMultipleDocuments(),
                result -> {
                    if (filePathCallback != null) {
                        filePathCallback.onReceiveValue(
                                result == null ? null : result.toArray(new Uri[0]));
                        filePathCallback = null;
                    }
                });

        setupInsets();
        setupWeb();
        setupActions();

        if (savedInstanceState == null) {
            web.loadUrl(Prefs.HOME);
        } else {
            web.restoreState(savedInstanceState);
        }

        applyImmersive(false);
        applyKeepScreen();
        lastUa = web.getSettings().getUserAgentString();
        lastZoom = prefs.textZoom();
    }

    private void setupInsets() {
        try {
            ViewCompat.setOnApplyWindowInsetsListener(root, (v, ins) -> {
                try {
                    Insets sb = ins.getInsets(WindowInsetsCompat.Type.systemBars());
                    applyFabMargin(btnTop, sb.bottom);
                    applyFabMargin(btnFab, sb.bottom);
                } catch (Throwable ignore) {
                }
                return ins;
            });
            ViewCompat.requestApplyInsets(root);
        } catch (Throwable ignore) {
        }
    }

    private void applyFabMargin(View v, int navH) {
        if (v == null) return;
        try {
            android.widget.FrameLayout.LayoutParams lp =
                    (android.widget.FrameLayout.LayoutParams) v.getLayoutParams();
            lp.bottomMargin = navH + dp(16);
            v.setLayoutParams(lp);
        } catch (Throwable ignore) {
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    private void setupWeb() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setLoadsImagesAutomatically(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setTextZoom(prefs.textZoom());
        s.setUserAgentString(prefs.desktopUa() ? DESKTOP_UA : MOBILE_UA);
        s.setMediaPlaybackRequiresUserGesture(false);
        try {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        } catch (Throwable ignore) {
        }
        try {
            s.setOffscreenPreRaster(true);
        } catch (Throwable ignore) {
        }
        try {
            s.setSafeBrowsingEnabled(false);
        } catch (Throwable ignore) {
        }
        try {
            s.setRenderPriority(WebSettings.RenderPriority.HIGH);
        } catch (Throwable ignore) {
        }
        try {
            s.setNeedInitialFocus(false);
        } catch (Throwable ignore) {
        }

        uaCache = s.getUserAgentString();
        lineCache = prefs.imgLine();

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        try {
            cm.setAcceptThirdPartyCookies(web, true);
        } catch (Throwable ignore) {
        }

        web.setBackgroundColor(getResources().getColor(R.color.bg));
        web.setHorizontalScrollBarEnabled(false);
        web.setVerticalScrollBarEnabled(true);

        web.addJavascriptInterface(new JsBridge(), "HipApp");

        web.setWebViewClient(new WebViewClient() {

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                String u = req.getUrl() != null ? req.getUrl().toString() : null;
                return handleUrl(u);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String u) {
                return handleUrl(u);
            }

            @Override
            public void onPageStarted(WebView v, String u, Bitmap f) {
                loadFailed = false;
                errorView.setVisibility(View.GONE);
                progress.setVisibility(View.VISIBLE);
                progress.setProgress(0);
                if (u != null) refCache = u;
            }

            @Override
            public void onPageFinished(WebView v, String u) {
                progress.setVisibility(View.GONE);
                swipe.setRefreshing(false);
                if (!loadFailed) errorView.setVisibility(View.GONE);
                applyMode(u);
                injectClean();
                injectGuard();
                injectT2s();
                injectReader(u);
                String t = v.getTitle();
                if (t != null && !t.isEmpty() && u != null && u.startsWith("http")) {
                    store.put("hist", t, u);
                }
            }

            @Override
            public void onReceivedError(WebView v, int code, String desc, String failingUrl) {
                if (Build.VERSION.SDK_INT < 23) showError(failingUrl);
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest req,
                                        android.webkit.WebResourceError err) {
                if (req != null && req.isForMainFrame()) {
                    showError(req.getUrl() != null ? req.getUrl().toString() : null);
                }
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
                String u = req.getUrl() != null ? req.getUrl().toString() : "";
                if (u.isEmpty()) return null;
                if (prefs.adBlock() && isAd(u)) return empty();
                if (isPromoImg(u)) return empty();
                if (prefs.cacheAssets() && ResCache.isCacheable(u)) {
                    byte[] d = ResCache.get(u);
                    if (d == null) d = ResCache.fetch(u, uaCache, refCache);
                    if (d != null && d.length > 0) {
                        try {
                            return new WebResourceResponse(ResCache.mime(u), "utf-8",
                                    new ByteArrayInputStream(d));
                        } catch (Throwable ignore) {
                        }
                    }
                }
                return null;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {

            @Override
            public void onProgressChanged(WebView v, int p) {
                progress.setProgress(p);
                if (p >= 100) {
                    ui.postDelayed(() -> progress.setVisibility(View.GONE), 300);
                }
            }

            @Override
            public void onReceivedTitle(WebView v, String t) {
                if (t != null && !t.isEmpty()) setTitle(t);
            }

            @Override
            public boolean onCreateWindow(WebView v, boolean dialog, boolean userGesture,
                                          Message msg) {
                try {
                    WebView tmp = new WebView(MainActivity.this);
                    tmp.setWebViewClient(new WebViewClient() {
                        @Override
                        public boolean shouldOverrideUrlLoading(WebView w, String u) {
                            if (u != null && !u.startsWith("about:")) web.loadUrl(u);
                            return true;
                        }

                        @Override
                        public boolean shouldOverrideUrlLoading(WebView w, WebResourceRequest r) {
                            String u = r.getUrl() != null ? r.getUrl().toString() : null;
                            if (u != null && !u.startsWith("about:")) web.loadUrl(u);
                            return true;
                        }
                    });
                    WebView.WebViewTransport tr = (WebView.WebViewTransport) msg.obj;
                    tr.setWebView(tmp);
                    msg.sendToTarget();
                    return true;
                } catch (Throwable e) {
                    return false;
                }
            }

            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb,
                                             FileChooserParams params) {
                filePathCallback = cb;
                try {
                    String[] types = params != null ? params.getAcceptTypes() : null;
                    if (types == null || types.length == 0
                            || types[0] == null || types[0].isEmpty()) {
                        types = new String[]{"*/*"};
                    }
                    filePicker.launch(types);
                    return true;
                } catch (Throwable e) {
                    filePathCallback = null;
                    return false;
                }
            }
        });

        web.setOnLongClickListener(v -> {
            try {
                WebView.HitTestResult r = web.getHitTestResult();
                if (r == null) return false;
                int t = r.getType();
                if (t == WebView.HitTestResult.IMAGE_TYPE
                        || t == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
                        || t == WebView.HitTestResult.SRC_ANCHOR_TYPE) {
                    String extra = r.getExtra();
                    if (extra != null && !extra.isEmpty()) {
                        showLongPressMenu(extra, t);
                        return true;
                    }
                }
            } catch (Throwable ignore) {
            }
            return false;
        });

        web.setWebContentsDebuggingEnabled(false);
    }

    private boolean handleUrl(String u) {
        if (u == null || u.isEmpty()) return true;
        String low = u.toLowerCase(Locale.US);
        if (low.startsWith("http://") || low.startsWith("https://")) {
            // 「免费图库」推广卡：点了什么都不做
            if (isPromo(u)) return true;
            if (isInternal(u)) {
                // 站内登录/注册页也直接吃掉，点了没反应
                if (isLoginPath(u)) return true;
                return false;
            }
            if (isLoginPath(u)) return true;
            openExternal(u);
            return true;
        }
        if (low.startsWith("intent:") || low.startsWith("market:")
                || low.startsWith("tel:") || low.startsWith("mailto:")
                || low.startsWith("weixin:") || low.startsWith("alipays:")) {
            openExternal(u);
            return true;
        }
        return false;
    }

    private boolean isInternal(String u) {
        try {
            String h = Uri.parse(u).getHost();
            if (h == null) return false;
            h = h.toLowerCase(Locale.US);
            return h.endsWith("hipmh.com")
                    || h.endsWith("hipmh.top")
                    || h.endsWith("s3file.top")
                    || h.endsWith("s3imgs.top")
                    || h.endsWith("mangabuddy.in");
        } catch (Throwable e) {
            return false;
        }
    }

    private void openExternal(String u) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(u));
            i.addCategory(Intent.CATEGORY_BROWSABLE);
            startActivity(i);
        } catch (Throwable e) {
            toast("无法打开链接");
        }
    }

    private boolean isAd(String u) {
        if (u == null || u.isEmpty()) return false;
        String low = u.toLowerCase(Locale.US);
        for (String h : AD_HOSTS) {
            if (low.contains(h)) return true;
        }
        return false;
    }

    private boolean isPromo(String u) {
        if (u == null) return false;
        String low = u.toLowerCase(Locale.US);
        for (String h : PROMO_HOSTS) {
            if (low.contains(h)) return true;
        }
        return false;
    }

    private boolean isLoginPath(String u) {
        if (u == null) return false;
        String low = u.toLowerCase(Locale.US);
        int q = low.indexOf('?');
        String p = q > 0 ? low.substring(0, q) : low;
        int s = p.indexOf("//");
        if (s >= 0) {
            int e = p.indexOf('/', s + 2);
            p = e >= 0 ? p.substring(e) : "";
        }
        for (String x : LOGIN_PATHS) {
            if (p.equals(x) || p.startsWith(x + "/") || p.startsWith(x + "?")) return true;
        }
        return false;
    }

    private boolean isPromoImg(String u) {
        if (u == null) return false;
        String low = u.toLowerCase(Locale.US);
        for (String s : PROMO_IMGS) {
            if (low.contains(s)) return true;
        }
        return false;
    }

    private WebResourceResponse empty() {
        try {
            return new WebResourceResponse("text/plain", "utf-8",
                    new ByteArrayInputStream(new byte[0]));
        } catch (Throwable e) {
            return null;
        }
    }

    private void showError(String u) {
        loadFailed = true;
        progress.setVisibility(View.GONE);
        swipe.setRefreshing(false);
        errorView.setVisibility(View.VISIBLE);
    }

    private void applyMode(String u) {
        boolean r = u != null && (u.contains("/chapter/") || u.contains("/chapter/go"));
        readingMode = r;
        if (r) {
            if (prefs.immersiveRead()) applyImmersive(true);
            swipe.setEnabled(false);
            btnTop.setVisibility(View.GONE);
            hideFab();
        } else {
            applyImmersive(false);
            swipe.setEnabled(true);
            showFab();
        }
        applyKeepScreen();
    }

    private void showFab() {
        if (btnFab == null) return;
        try {
            btnFab.setVisibility(View.VISIBLE);
            btnFab.setAlpha(1f);
        } catch (Throwable ignore) {
        }
    }

    private void hideFab() {
        if (btnFab == null) return;
        try {
            btnFab.setVisibility(View.GONE);
        } catch (Throwable ignore) {
        }
    }

    private void applyImmersive(boolean hide) {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                android.view.WindowInsetsController c = getWindow().getInsetsController();
                if (c != null) {
                    if (hide) {
                        c.hide(android.view.WindowInsets.Type.statusBars()
                                | android.view.WindowInsets.Type.navigationBars());
                    } else {
                        c.show(android.view.WindowInsets.Type.statusBars()
                                | android.view.WindowInsets.Type.navigationBars());
                    }
                    c.setSystemBarsBehavior(
                            android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                View v = getWindow().getDecorView();
                int f = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
                if (hide) {
                    f |= View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
                }
                v.setSystemUiVisibility(f);
            }
        } catch (Throwable ignore) {
        }
    }

    private void applyKeepScreen() {
        if (prefs.keepScreen()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void injectClean() {
        web.evaluateJavascript(cleanJs(prefs.adBlock()), null);
    }

    /**
     * 隐藏三类东西：
     * 1) 广告容器（GTM 运行时注入）
     * 2) 站点自己的「免费图库」外链推广卡：g-mh.com / 18gallery.com
     * 3) 登录注册入口：右上角「我的書架」按钮，未登录时渲染成「登入」，
     *    真实地址是 https://m.xipmh.com/dashboard?lang=zh（顶部 + 侧栏各一处）
     */
    private static String cleanJs(boolean blockAd) {
        StringBuilder sb = new StringBuilder();
        sb.append("(function(){");
        sb.append("if(window.__hipClean)return;window.__hipClean=1;");
        sb.append("function hide(e){try{e.style.setProperty('display','none','important');}catch(x){}}");

        sb.append("var css='';");
        if (blockAd) {
            sb.append("css+='ins.adsbygoogle,[id^=google_ads],[id^=div-gpt-ad],'");
            sb.append("+'[class*=adsbygoogle],[class*=google-ad],[class*=ad-slot],[id*=ad-slot],'");
            sb.append("+'[class*=adsense],.adsbox,[class*=advertisement],[class*=advertising],'");
            sb.append("+'iframe[src*=doubleclick],iframe[src*=googlesyndication],'");
            sb.append("+'iframe[src*=adservice],iframe[src*=adsbygoogle]{display:none!important;}';");
        }
        // 图库推广卡 + 登录入口（这两类在静态 HTML 里就有，CSS 直接命中，最稳）
        sb.append("css+='a[href*=\"g-mh.com\"],a[href*=\"18gallery.com\"],'");
        sb.append("+'a[href*=\"xipmh.com/dashboard\"],'");
        sb.append("+'a[aria-label=\"我的書架\"],a[aria-label=\"個人中心\"],a[aria-label=\"登出\"],'");
        sb.append("+'[data-navbar-sidebar-signin],[data-navbar-sidebar-signout],'");
        sb.append("+'a[href*=\"/login\"],a[href*=\"/register\"],a[href*=\"/signin\"],'"
                + "+'a[href*=\"/signup\"]{display:none!important;}';");
        sb.append("var st=document.createElement('style');st.textContent=css;");
        sb.append("document.head.appendChild(st);");

        sb.append("var LK=['我的書架','個人中心','登出','登入','登錄','登录','註冊','注册',"
                + "'Sign in','Log in','Login','Register'];");
        sb.append("var GK=['图库','圖庫','免费高清','免費高清'];");
        sb.append("function hitK(t,ks){for(var i=0;i<ks.length;i++){"
                + "if(t.indexOf(ks[i])>=0)return 1;}return 0;}");
        sb.append("window.__hipBad=function(el){try{");
        sb.append("var h=el.getAttribute?el.getAttribute('href')||'':'';");
        sb.append("if(h.indexOf('/works/')>=0)return 0;");
        sb.append("if(/g-mh\\.com|18gallery\\.com|xipmh\\.com\\/dashboard/i.test(h))return 1;");
        sb.append("if(/\\/login|\\/register|\\/signin|\\/signup|\\/user\\/profile/i.test(h))return 1;");
        sb.append("var al=el.getAttribute?el.getAttribute('aria-label')||'':'';");
        sb.append("if(al&&al.length<=16&&hitK(al,LK))return 1;");
        sb.append("var t=(el.textContent||'').trim();");
        sb.append("if(t&&t.length<=16&&hitK(t,LK))return 1;");
        sb.append("if(t&&t.length<=24&&hitK(t,GK))return 1;");
        sb.append("var im=el.querySelector?el.querySelector('img'):null;");
        sb.append("if(im){var a=im.getAttribute('alt')||'';var s=im.getAttribute('src')||'';");
        sb.append("if(/^G-MH$|^18GAL$/.test(a))return 1;");
        sb.append("if(/g-mh-900|18gallery-1/i.test(s))return 1;}");
        sb.append("}catch(e){}return 0;};");

        sb.append("function scan(){try{");
        sb.append("var as=document.querySelectorAll('a,button');");
        sb.append("for(var i=0;i<as.length;i++){var el=as[i];");
        sb.append("if(window.__hipBad(el)){hide(el);var pa=el.parentElement;");
        sb.append("if(pa&&(pa.textContent||'').trim().length<=40)hide(pa);continue;}");
        if (blockAd) {
            sb.append("var h2=el.getAttribute('href')||'';");
            sb.append("if(h2&&/doubleclick|googlesyndication|adsbygoogle|popads|adnxs/.test(h2)){"
                    + "hide(el);var pb=el.parentElement;if(pb)hide(pb);}");
        }
        sb.append("}");
        if (blockAd) {
            sb.append("var ifs=document.querySelectorAll('iframe');");
            sb.append("for(var j=0;j<ifs.length;j++){var s=ifs[j].src||'';");
            sb.append("if(/doubleclick|googlesyndication|adservice|adsbygoogle|adnxs|criteo|taboola|popads/"
                    + ".test(s)){hide(ifs[j]);var pp=ifs[j].parentElement;if(pp)hide(pp);}}");
        }
        sb.append("}catch(e){}}");
        sb.append("scan();");
        sb.append("try{new MutationObserver(scan).observe(document.documentElement,"
                + "{childList:true,subtree:true});}catch(e){}");
        sb.append("setInterval(scan,3000);");
        sb.append("})();");
        return sb.toString();
    }

    /** 登录注册入口 + 图库推广卡：点了什么都不发生 */
    private void injectGuard() {
        web.evaluateJavascript(GUARD_JS, null);
    }

    private static final String GUARD_JS =
            "(function(){"
                    + "if(window.__hipGuard)return;window.__hipGuard=1;"
                    + "var LP=['/login','/register','/signin','/signup','/user/profile',"
                    + "'/account','/dashboard'];"
                    + "var PH=['g-mh.com','18gallery.com','xipmh.com/dashboard'];"
                    + "function badUrl(u){if(!u)return 0;"
                    + "var l=String(u).toLowerCase();"
                    + "for(var i=0;i<PH.length;i++){if(l.indexOf(PH[i])>=0)return 1;}"
                    + "var p=l.indexOf('?')>0?l.substring(0,l.indexOf('?')):l;"
                    + "var s=p.indexOf('//');if(s>=0){var e=p.indexOf('/',s+2);p=e>=0?p.substring(e):'';}"
                    + "for(var j=0;j<LP.length;j++){if(p===LP[j]||p.indexOf(LP[j]+'/')===0)return 1;}"
                    + "return 0;}"
                    + "var KW=/我的書架|個人中心|登出|登入|登錄|登录|註冊|注册"
                    + "|Sign in|Log in|Login|Register/;"
                    + "function badEl(el){if(!el||!el.closest)return 0;"
                    + "if(window.__hipBad&&window.__hipBad(el))return 1;"
                    + "var a=el.closest('a[href]');"
                    + "if(a){var h=a.getAttribute('href')||'';"
                    + "if(h.indexOf('/works/')<0&&badUrl(h))return 1;"
                    + "var t=(a.textContent||'').trim();"
                    + "if(t&&t.length<=16&&KW.test(t))return 1;"
                    + "var al=a.getAttribute('aria-label')||'';"
                    + "if(al&&al.length<=16&&KW.test(al))return 1;}"
                    + "var b=el.closest('button');"
                    + "if(b){var tb=(b.textContent||'').trim();"
                    + "if(tb&&tb.length<=16&&KW.test(tb))return 1;}"
                    + "return 0;}"
                    + "function kill(e){try{if(badEl(e.target)){"
                    + "e.preventDefault();e.stopImmediatePropagation();e.stopPropagation();}}catch(x){}}"
                    + "var EV=['click','mousedown','mouseup','touchstart','touchend','pointerdown'];"
                    + "for(var k=0;k<EV.length;k++){"
                    + "document.addEventListener(EV[k],kill,true);}"
                    + "try{var of=window.open;window.open=function(u){"
                    + "if(badUrl(u))return null;return of.apply(window,arguments);};}catch(x){}"
                    + "})();";

    /** 从 assets 读取繁→简映射表（缓存） */
    private String t2sMap() {
        String m = t2sMapJs;
        if (m != null) return m;
        try {
            InputStream in = getAssets().open("t2s.js");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
            in.close();
            m = bos.toString("UTF-8");
        } catch (Throwable e) {
            m = "";
        }
        t2sMapJs = m;
        return m;
    }

    /** 繁体转简体：站点本身只有繁体，这里把页面文本转成简体并默认开启 */
    private void injectT2s() {
        String map = t2sMap();
        if (map == null || map.isEmpty()) return;
        web.evaluateJavascript(map + T2S_JS, null);
    }

    private static final String T2S_JS =
            "(function(){"
                    + "if(window.__hipT2s)return;window.__hipT2s=1;"
                    + "function cv(s){return s.replace(/[\\u4e00-\\u9fff]/g,function(c){"
                    + "var v=window.__hipT2SMap[c];return v!==undefined?v:c;});}"
                    + "function convNode(n){var p=n.parentNode;if(!p)return;"
                    + "var t=p.tagName;if(t==='SCRIPT'||t==='STYLE')return;"
                    + "if(n.nodeType===3){var x=n.nodeValue;if(x){var y=cv(x);if(y!==x)n.nodeValue=y;}}}"
                    + "function convAll(root){try{"
                    + "var w=document.createTreeWalker(root,NodeFilter.SHOW_TEXT,null,false);"
                    + "var n,a=[];while(n=w.nextNode()){a.push(n);}"
                    + "for(var i=0;i<a.length;i++)convNode(a[i]);}catch(e){}}"
                    + "convAll(document.body);"
                    + "try{new MutationObserver(function(m){for(var i=0;i<m.length;i++){"
                    + "var as=m[i].addedNodes;for(var j=0;j<as.length;j++){var n=as[j];"
                    + "if(n.nodeType===1)convAll(n);else if(n.nodeType===3)convNode(n);}}}"
                    + ").observe(document.documentElement,{childList:true,subtree:true});}catch(e){}"
                    + "})();";

    private void injectReader(String u) {
        if (u == null || !(u.contains("/chapter/") || u.contains("/chapter/go"))) return;
        web.evaluateJavascript(readerJs(), null);
    }

    private String readerJs() {
        return "(function(){"
                + "try{localStorage.setItem('chapterApiLine','" + prefs.imgLine() + "');}catch(e){}"
                + "if(window.__hipReady)return;window.__hipReady=1;"
                + "var st=document.createElement('style');"
                + "st.textContent='img{max-width:100%!important;height:auto!important;display:block;}'"
                + "+'html,body{overflow-x:hidden!important;max-width:100%!important;}'"
                + "+'ins.adsbygoogle,iframe[src*=ads],.adsbox,[id^=google_ads],'"
                + "+'[id^=div-gpt-ad],[class*=adsbygoogle]{display:none!important;}'"
                + "+'#d-chapters-modal,#d-modal-chapters-list{overscroll-behavior:contain;}';"
                + "document.head.appendChild(st);"
                // 章节目录弹窗 / 设置抽屉打开时锁背景滚动，防止滚动穿透
                + "function __hipLock(){var m=document.getElementById('d-chapters-modal');"
                + "var o=document.getElementById('drawerOverlay');"
                + "var mo=!!(m&&!m.classList.contains('hidden'));"
                + "var dr=!!(o&&!o.classList.contains('hidden'));"
                + "document.body.style.overflow=(mo||dr)?'hidden':'';}"
                // 设置抽屉开合联动原生悬浮按钮
                + "function __hipFab(){try{var o=document.getElementById('drawerOverlay');"
                + "var open=!!(o&&!o.classList.contains('hidden'));"
                + "if(window.HipApp&&window.HipApp.setFab)window.HipApp.setFab(open?1:0);"
                + "}catch(e){}}"
                + "function __hipSync(){__hipLock();__hipFab();}"
                + "__hipSync();"
                + "try{new MutationObserver(__hipSync).observe(document.documentElement,"
                + "{attributes:true,subtree:true,attributeFilter:['class','style']});}catch(e){}"
                + "window.__hipImgs=function(){var o=[];"
                + "var ns=document.querySelectorAll('.chapter-image img,.chapter-img-container img');"
                + "for(var i=0;i<ns.length;i++){var s=ns[i].currentSrc||ns[i].src;"
                + "if(s)o.push(s);}"
                + "return o;};"
                // 进入阅读页即沉浸：模拟一次点击，触发站内隐藏顶栏/底栏（setNavVisible(false)）
                + "setTimeout(function(){try{"
                + "var c=document.getElementById('chapcontent')||document.body;"
                + "c.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,view:window}));"
                + "}catch(e){}},400);"
                + "})();";
    }

    private void setupActions() {
        swipe.setOnRefreshListener(() -> {
            web.reload();
            ui.postDelayed(() -> swipe.setRefreshing(false), 2500);
        });

        btnFab.setOnClickListener(v -> showMenu());
        btnTop.setOnClickListener(v -> {
            web.scrollTo(0, 0);
            web.evaluateJavascript("window.scrollTo(0,0);", null);
        });

        findViewById(R.id.btnRetry).setOnClickListener(v -> {
            errorView.setVisibility(View.GONE);
            loadFailed = false;
            web.reload();
        });

        if (Build.VERSION.SDK_INT >= 23) {
            web.setOnScrollChangeListener(new View.OnScrollChangeListener() {
                @Override
                public void onScrollChange(View v, int x, int y, int ox, int oy) {
                    if (!readingMode) {
                        btnTop.setVisibility(y > dp(600) ? View.VISIBLE : View.GONE);
                    }
                }
            });
        }
    }

    private void showMenu() {
        final String[] items = {
                "首页", "发现漫画", "人气排行", "最新上架",
                "已完结", "连载中", "随机看看", "搜索",
                "后退", "前进", "刷新",
                "收藏本页", "历史与收藏", "设置",
                "用浏览器打开", "清除缓存", "退出"
        };
        new AlertDialog.Builder(this)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String base = Prefs.HOME;
                        switch (w) {
                            case 0:
                                web.loadUrl(base);
                                break;
                            case 1:
                                web.loadUrl(base + "explore");
                                break;
                            case 2:
                                web.loadUrl(base + "popularity");
                                break;
                            case 3:
                                web.loadUrl(base + "new-releases");
                                break;
                            case 4:
                                web.loadUrl(base + "completed");
                                break;
                            case 5:
                                web.loadUrl(base + "ongoing");
                                break;
                            case 6:
                                web.loadUrl(base + "random");
                                break;
                            case 7:
                                web.loadUrl(base + "search");
                                break;
                            case 8:
                                if (web.canGoBack()) web.goBack();
                                break;
                            case 9:
                                if (web.canGoForward()) web.goForward();
                                break;
                            case 10:
                                web.reload();
                                break;
                            case 11:
                                addFav();
                                break;
                            case 12:
                                startActivity(new Intent(MainActivity.this, ListActivity.class));
                                break;
                            case 13:
                                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
                                break;
                            case 14:
                                openExternal(web.getUrl());
                                break;
                            case 15:
                                clearCache();
                                break;
                            case 16:
                                finish();
                                break;
                            default:
                                break;
                        }
                    }
                })
                .show();
    }

    private void addFav() {
        String u = web.getUrl();
        String t = web.getTitle();
        if (u == null || u.isEmpty()) return;
        if (store.has("fav", u)) {
            store.remove("fav", u);
            toast("已取消收藏");
        } else {
            store.put("fav", t, u);
            toast("已收藏");
        }
    }

    private void clearCache() {
        web.clearCache(true);
        ResCache.clear();
        toast("缓存已清除");
    }

    private void showLongPressMenu(final String data, int type) {
        boolean isImg = type == WebView.HitTestResult.IMAGE_TYPE
                || type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE;
        String[] items;
        if (isImg) {
            items = new String[]{"全屏查看图片", "复制图片链接", "在新页面打开"};
        } else {
            items = new String[]{"复制链接", "在新页面打开", "分享链接"};
        }
        new AlertDialog.Builder(this)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        if (isImg) {
                            switch (w) {
                                case 0:
                                    ImageViewerActivity.open(MainActivity.this, data, web.getUrl());
                                    break;
                                case 1:
                                    copy(data);
                                    break;
                                case 2:
                                    web.loadUrl(data);
                                    break;
                                default:
                                    break;
                            }
                        } else {
                            switch (w) {
                                case 0:
                                    copy(data);
                                    break;
                                case 1:
                                    web.loadUrl(data);
                                    break;
                                case 2:
                                    share(data);
                                    break;
                                default:
                                    break;
                            }
                        }
                    }
                })
                .show();
    }

    private void copy(String t) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("link", t));
            toast(getString(R.string.copied));
        } catch (Throwable e) {
            toast("复制失败");
        }
    }

    private void share(String t) {
        try {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT, t);
            startActivity(Intent.createChooser(i, "分享"));
        } catch (Throwable e) {
            toast("分享失败");
        }
    }

    private void toast(String m) {
        try {
            Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignore) {
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (readingMode && prefs.volumePage() && e.getAction() == KeyEvent.ACTION_DOWN) {
            int k = e.getKeyCode();
            if (k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN) {
                int step = (int) (web.getHeight() * 0.85f);
                web.scrollBy(0, k == KeyEvent.KEYCODE_VOLUME_DOWN ? step : -step);
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public void onBackPressed() {
        if (errorView.getVisibility() == View.VISIBLE) {
            errorView.setVisibility(View.GONE);
            return;
        }
        if (web.canGoBack()) {
            web.goBack();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBackMs < 2000) {
            super.onBackPressed();
            return;
        }
        lastBackMs = now;
        toast(getString(R.string.exit_hint));
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        try {
            web.saveState(out);
        } catch (Throwable ignore) {
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            web.evaluateJavascript("try{document.activeElement.blur();}catch(e){}", null);
        } catch (Throwable ignore) {
        }
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        try {
            if (i != null) {
                String u = i.getStringExtra("url");
                if (u != null && !u.isEmpty()) web.loadUrl(u);
            }
        } catch (Throwable ignore) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyKeepScreen();
        applyImmersive(readingMode && prefs.immersiveRead());
        try {
            String ua = prefs.desktopUa() ? DESKTOP_UA : MOBILE_UA;
            if (!ua.equals(lastUa)) {
                lastUa = ua;
                uaCache = ua;
                web.getSettings().setUserAgentString(ua);
                web.reload();
            }
            int z = prefs.textZoom();
            if (z != lastZoom) {
                lastZoom = z;
                web.getSettings().setTextZoom(z);
                web.reload();
            }
        } catch (Throwable ignore) {
        }
    }

    @Override
    protected void onDestroy() {
        try {
            if (web != null) {
                web.stopLoading();
                web.setWebViewClient(null);
                web.setWebChromeClient(null);
                web.destroy();
            }
        } catch (Throwable ignore) {
        }
        super.onDestroy();
    }

    public final class JsBridge {

        @JavascriptInterface
        public void openImage(String url) {
            Log.d(TAG, "openImage " + url);
        }

        @JavascriptInterface
        public void toast(final String m) {
            ui.post(() -> MainActivity.this.toast(m));
        }

        /** 阅读器设置抽屉开合时联动：仅在阅读模式下才响应显示/隐藏悬浮按钮 */
        @JavascriptInterface
        public void setFab(final boolean show) {
            ui.post(() -> {
                if (readingMode) {
                    if (show) {
                        showFab();
                    } else {
                        hideFab();
                    }
                }
            });
        }
    }
}
