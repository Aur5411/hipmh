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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONArray;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "HipMain";

    private static final String MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 12; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /**
     * 只保留真正的广告投放网络。
     * 注意：不要放 cloudflareinsights / analytics.google / hotjar / scorecardresearch /
     * quantserve / facebook.net / googletagmanager —— 这些是站点的分析或 GTM 容器本身，
     * 拦掉会让页面脚本报错、行为异常（主页「广告屏蔽有问题」就是这么来的）。
     * GTM 容器自身放行，改由 cleanJs 在运行时隐藏它注入的广告 DOM。
     */
    private static final String[] AD_HOSTS = {
            "doubleclick.net", "googlesyndication", "googleadservices",
            "adsbygoogle", "pagead2.googlesyndication", "adservice.google",
            "ad-maven", "adnxs", "popads", "popcash", "propellerads",
            "exoclick", "juicyads", "trafficjunky", "adsterra", "hilltopads",
            "clickadu", "appnexus", "pubmatic", "rubiconproject",
            "taboola", "outbrain", "criteo.", "mgid.com", "revcontent",
            "securepubads", "amazon-adsystem", "casalemedia", "openx.net",
            "smartadserver", "bidswitch", "adform.net", "360yield",
            "adyoulike", "tynt.com", "yandex.ru/ads", "adpushup",
            // 站点自建点击劫持广告（#nav-redirect-config 的 urls 里轮询跳转的目标）
            "1wm.top", "hai8g.com",
            // ★ v1.0.10：GTM 是新的广告注入源
            //   首页含 <iframe src="https://www.googletagmanager.com/ns.html?id=GTM-KWM3FNGT">
            //   + dataLayer 脚本异步加载 https://www.googletagmanager.com/gtm.js?id=GTM-KWM3FNGT
            //   GTM 容器会在运行时注入广告 iframe/弹窗，这就是「广告又出现了」。
            //   只拦这些子资源域名，站点自身页面不受影响。
            "googletagmanager.com", "googletagservices.com",
            "google-analytics.com", "analytics.google.com"
    };

    /**
     * 站点自己塞的「免费图库」外链推广卡（g-mh / 18gallery），点了直接无反应。
     * 这两个域名同时也在点击劫持的 urls 列表里，一并按推广处理。
     */
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

    /** ★ v1.4：阅读页图片预加载线程池（并发抓图进 ResCache 磁盘缓存） */
    private final ExecutorService preloadPool = Executors.newFixedThreadPool(8);
    /** 已提交预加载的 URL 去重集合，避免重复提交 / 跨章节重抓 */
    private final Set<String> preloadSeen = ConcurrentHashMap.newKeySet();

    /** ★ v1.5：隐藏后台 WebView，用于「预抓下一章」—— 加载 reader.hipmh.top 的下一章页面，
     *  复用站点自身 JS 解密 + 现有 readerJs 把下一章图片收集并预抓进共享 ResCache。 */
    private WebView bgWeb;
    /** 当前已安排后台预抓的下一章 hid（去重，避免重复 load） */
    private String bgHid = "";

    /** assets/t2s.js 里的繁→简字符映射表（首次读取后缓存） */
    private static volatile String t2sMapJs = null;
    /** 映射表只需注入一次；后续跳转只补注入转换逻辑 */
    private boolean t2sInjected = false;

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
        // 注意：图片线路不要缓存成字段，否则用户在设置里改了线路、
        // 阅读页仍会用旧值（readerJs 里每章都实时读 prefs.imgLine()）

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
                injectHist();
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
                // 主文档：先扼杀站点的点击劫持广告（#nav-redirect-config），
                // 它的内联脚本会在捕获阶段把漫画链接的点击换成广告跳转。
                if (prefs.adBlock() && req.isForMainFrame() && isDocument(u)) {
                    WebResourceResponse r = stripRedirectHijack(u);
                    if (r != null) return r;
                }
                return interceptRes(req);
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
            // ① 广告域名：直接吃掉，既不跳转也不交给系统浏览器。
            //    站点 #nav-redirect-config 的点击劫持就是 location.href 到这些地址，
            //    以前这里会走 openExternal() 把广告甩进浏览器，所以「广告又显示了」。
            if (isAd(u)) return true;
            // ② 「免费图库」推广卡：点了什么都不做
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
        // 站点自身域名（含图片 CDN）永不拦，避免误伤
        if (isInternal(u)) return false;
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

    /** 是否是主文档（HTML 页面）请求 */
    private boolean isDocument(String u) {
        String low = u.toLowerCase(Locale.US);
        int q = low.indexOf('?');
        String p = q > 0 ? low.substring(0, q) : low;
        int h = p.indexOf('#');
        if (h > 0) p = p.substring(0, h);
        return p.endsWith("/") || p.endsWith(".html") || p.endsWith(".htm")
                || p.endsWith(".php") || !p.substring(p.lastIndexOf('/') + 1).contains(".");
    }

    /**
     * 摘掉站点主文档里的点击劫持广告配置。
     *
     * 站点在页面里放了：
     *   <div id="nav-redirect-config" class="hidden"
     *        data-config='{"selector":"#reading-btn,.chapter-link,...",
     *                      "urls":["https://1wm.top/t1",...,"https://hai8g.com/4/9545797"],
     *                      "strategy":"round_robin",...}'>
     * 紧跟一段内联脚本，在**捕获阶段**监听 click：命中 selector 就
     * preventDefault + stopPropagation，然后 window.open(真实章节) 并
     * location.href = 轮询到的广告地址 —— 用户表现就是「点漫画被带去广告站」。
     *
     * 与其在它之后抢事件（时序不保证），不如直接把这个配置块和它的脚本从
     * HTML 里删掉，脚本自然 return（`if(!f)return;`），劫持彻底不存在。
     */
    private WebResourceResponse stripRedirectHijack(String u) {
        try {
            java.net.HttpURLConnection c =
                    (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
            c.setRequestProperty("User-Agent", uaCache);
            c.setRequestProperty("Accept", "text/html,*/*");
            if (refCache != null && !refCache.isEmpty()) {
                c.setRequestProperty("Referer", refCache);
            }
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            int code = c.getResponseCode();
            if (code < 200 || code >= 400) {
                c.disconnect();
                return null;
            }
            String ctype = c.getContentType();
            if (ctype == null || !ctype.toLowerCase(Locale.US).contains("html")) {
                c.disconnect();
                return null;   // 不是 HTML，交回 WebView 正常处理
            }
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
            in.close();
            c.disconnect();

            // 站点是 UTF-8；即使原始是 gzip，HttpURLConnection 也已解压
            String html = bos.toString("UTF-8");
            String cleaned = stripNode(html, "nav-redirect-config");
            // ★ v1.0.10：再摘掉 GTM（新广告源）。GTM 的 dataLayer 内联脚本会异步
            //   加载 gtm.js 并在运行时注入广告 iframe/弹窗；noscript iframe 也会请求。
            //   整段删掉后 GTM 容器不再存在，广告自然不再注入。
            cleaned = stripGtm(cleaned);
            // ★ v1.0.11：把两个「我的書架」锚点直接从 HTML 里删掉。
            //   之前只靠 cleanJs 在客户端 hide()，而站点是 Astro 客户端路由：
            //   软导航会重新渲染 nav（新的书架 <a>），而 cleanJs 开头有
            //   if(window.__hipClean)return 的一次性守卫 —— 之后再也不处理，
            //   所以「书架一直在」。改到服务端删除后，DOM 里根本不存在该书架元素，
            //   与客户端路由/水合无关，彻底断根。
            cleaned = stripShelfAnchors(cleaned);
            // ★ v1.2：主页那两个「免费高清图库」推广卡（g-mh.com / 18gallery.com）。
            //   站点把它们写成静态 HTML，且**两个卡的包裹容器 class 不一致**：
            //     卡 A：<div class="container px-2"><div class="flex flex-row gap-2 w-full flex-1"><a href="https://g-mh.com/" ...>
            //     卡 B：<div class><div class="flex flex-row gap-2 w-full flex-1"><a href="https://18gallery.com/" ...>
            //   卡 B 的外层是**空 class**，所以之前靠 `div:has(> div > a[href*=...])`
            //   这类依赖 class 结构的 CSS 选择器会漏掉它。这里改为按 href 精确删整个
            //   `<a>...</a>`（连同卡内图片与文字），再顺手收掉只剩它的空包裹 div。
            cleaned = stripPromoCards(cleaned);
            // ★ v1.3：把历史页/侧边栏底部的「登入以同步您的資料」+ 登入按钮整块删掉。
            //   它由 Sidebar.astro 的 JS 控制：未登录时执行
            //     t.classList.remove('hidden')   // t = #navbar-sidebar-user-not-authed
            //   而该 JS 是 **async**（await isAuthenticated()），会在我们客户端 hide()
            //   之后才落地，等于每次都把 hidden 摘掉 → 之前 hide 不住。
            //   现在直接从 HTML 里把这整块删掉：JS 里是 `t && t.classList.remove(...)`，
            //   元素为 null 时安全跳过，不会报错。
            cleaned = stripSigninPrompt(cleaned);
            if (cleaned == null || cleaned.equals(html)) return null;   // 没命中，别多此一举

            return new WebResourceResponse("text/html", "utf-8",
                    new ByteArrayInputStream(cleaned.getBytes("UTF-8")));
        } catch (Throwable e) {
            return null;   // 一律回退给 WebView 原生加载，不能因拦截失败而白屏
        }
    }

    /**
     * 删除所有引用 GTM / Google 分析的片段：
     * - 内联 dataLayer 启动脚本（含 googletagmanager.com/gtm.js 的那段 <script>…</script>）
     * - <iframe src=".../ns.html?id=GTM-...">（noscript 回退）
     * - <script src="...googletagmanager.com/..."> 外链
     * 用逐段定位 + 标签配对，避免正则跨段误伤。
     */
    private static String stripGtm(String html) {
        if (html == null || html.isEmpty()) return html;
        String out = html;
        try {
            // 1) 内联脚本块里出现 googletagmanager / gtmscript / dataLayer 的，整块删
            int guard = 0;
            while (guard++ < 40) {
                int s = out.indexOf("<script");
                int hitStart = -1;
                int scan = 0;
                while (s >= 0) {
                    int e = out.indexOf("</script>", s);
                    if (e < 0) break;
                    String body = out.substring(s, e + 9);
                    if (body.contains("googletagmanager.com")
                            || body.contains("gtm.js")
                            || body.contains("dataLayer")
                            || body.contains("GTM-")) {
                        hitStart = s;
                        break;
                    }
                    scan = e + 9;
                    s = out.indexOf("<script", scan);
                }
                if (hitStart < 0) break;
                int he = out.indexOf("</script>", hitStart);
                if (he < 0) break;
                out = out.substring(0, hitStart) + out.substring(he + 9);
            }
            // 2) GTM noscript iframe（站点是：<noscript> <iframe src=...ns.html?id=GTM-...> </noscript>）
            //    ★ v1.0.11：noscript 里的 iframe 在「脚本开启」的浏览器里本不加载，
            //    但 WebView 仍会把它解析进 DOM 并可能发出请求；更关键的是外层
            //    <noscript> 会原样保留造成 GTM 容器残留。这里连 <noscript> 一起删。
            guard = 0;
            while (guard++ < 40) {
                int i = out.indexOf("<iframe");
                int hit = -1;
                int scan = 0;
                while (i >= 0) {
                    int e = out.indexOf('>', i);
                    if (e < 0) break;
                    String tag = out.substring(i, e + 1);
                    if (tag.contains("googletagmanager.com")
                            || tag.contains("google-analytics.com")) {
                        hit = i;
                        break;
                    }
                    scan = e + 1;
                    i = out.indexOf("<iframe", scan);
                }
                if (hit < 0) break;
                int e2 = out.indexOf('>', hit);
                if (e2 < 0) break;
                // 若存在 </iframe> 也一并删
                int ce = out.indexOf("</iframe>", e2);
                int stop = (ce >= 0 && ce - e2 < 600) ? ce + 9 : e2 + 1;
                // 若整个 iframe 被 <noscript>…</noscript> 包着，则连外层 noscript 一起删
                int ns = out.lastIndexOf("<noscript", hit);
                if (ns >= 0 && hit - ns < 200) {
                    int nsEnd = out.indexOf("</noscript>", stop);
                    if (nsEnd >= 0 && nsEnd - stop < 400) {
                        hit = ns;
                        stop = nsEnd + 11;
                    }
                }
                out = out.substring(0, hit) + out.substring(stop);
            }
        } catch (Throwable ignore) {
            return html;
        }
        return out;
    }

    /**
     * 删除所有「我的書架 / 我的书架」锚点（含其内部 svg/span 内容）。
     *
     * 站点把书架写成两处静态 HTML（都在 aria-label 里）：
     *   1) nav 第一行右侧：<a href="https://m.xipmh.com/dashboard?lang=zh"
     *                        aria-label="我的書架" class="text-foreground ... p-1">
     *   2) #navbar-sidebar 底部：同 href，class 里带 flex-1 justify-center
     * 两处都是服务端渲染好的静态节点，与 Astro 水合无关 —— 直接在 HTML 层删掉最干净，
     * 也就不必再依赖客户端 JS（后者会被 Astro 软导航绕过）。
     *
     * <p>用标签配对扫描而非正则整体替换：<a> 内部含多层 &lt;svg&gt;/&lt;path&gt;，
     * 且属性里可能出现 '&gt;'，正则容易截断错误。
     */
    private static String stripShelfAnchors(String html) {
        if (html == null || html.isEmpty()) return html;
        try {
            String out = html;
            guard:
            for (int round = 0; round < 12; round++) {
                // 找下一个 aria-label 命中「书架」的 <a 起点
                int at = -1;
                for (String lbl : new String[]{"我的書架", "我的书架", "書架", "书架"}) {
                    String key = "aria-label=\"" + lbl + "\"";
                    int k = out.indexOf(key);
                    while (k >= 0) {
                        int lt = out.lastIndexOf("<a", k);
                        // 必须同一个标签内（中间不能再出现 '>'）
                        if (lt >= 0) {
                            int gt = out.indexOf('>', lt);
                            if (gt >= 0 && gt > k) {
                                if (at < 0 || lt < at) at = lt;
                                break;
                            }
                        }
                        k = out.indexOf(key, k + 1);
                    }
                    if (at >= 0) break;
                }
                if (at < 0) break guard;

                // 从 <a 开始做配对扫描找 </a>
                int i = at;
                int depth = 0;
                int end = -1;
                while (i < out.length()) {
                    int lt = out.indexOf('<', i);
                    if (lt < 0) break;
                    if (out.startsWith("<!--", lt)) {
                        int ce = out.indexOf("-->", lt);
                        i = ce > 0 ? ce + 3 : out.length();
                        continue;
                    }
                    if (out.startsWith("</a", lt)) {
                        int gt = out.indexOf('>', lt);
                        if (gt < 0) break;
                        depth--;
                        if (depth <= 0) {
                            end = gt + 1;
                            break;
                        }
                        i = gt + 1;
                        continue;
                    }
                    if (out.startsWith("<a", lt)) {
                        int gt = out.indexOf('>', lt);
                        if (gt < 0) break;
                        String seg = out.substring(lt, gt + 1);
                        if (seg.endsWith("/>")) {
                            i = gt + 1;
                            continue;
                        }
                        depth++;
                        i = gt + 1;
                        continue;
                    }
                    i = lt + 1;
                }
                if (end < 0) break guard;
                out = out.substring(0, at) + out.substring(end);
            }
            return out;
        } catch (Throwable ignore) {
            return html;
        }
    }
    /**
     * 删除站点塞的「免费高清图库」推广卡（g-mh.com / 18gallery.com）。
     *
     * <p>两个卡的结构（注意外层 class 不一致，这是之前漏删的原因）：
     * <pre>
     * 卡 A 在 &lt;main&gt; 之前：
     *   &lt;div class="container px-2"&gt;
     *     &lt;div class="flex flex-row gap-2 w-full flex-1"&gt;
     *       &lt;a href="https://g-mh.com/" target="_blank" rel="noopener noreferrer"
     *          class="flex bg-muted/80 w-full rounded-md flex-row justify-between items-center px-3 h-12 my-2 ..."&gt;
     *         &lt;div&gt;&lt;div class="nav-card-image-wrap"&gt;
     *           &lt;img src="https://s3-nl-01.mangabuddy.in/g-mh-900.webp" alt="G-MH"&gt;
     *         &lt;/div&gt;&lt;span&gt; 免费高清图库&lt;/span&gt;&lt;/div&gt; …
     *       &lt;/a&gt;
     *     &lt;/div&gt;
     *   &lt;/div&gt;
     *
     * 卡 B 在 banner 之后，外层是**空 class**：
     *   &lt;div class&gt;&lt;div class="flex flex-row gap-2 w-full flex-1"&gt;
     *     &lt;a href="https://18gallery.com/" …&gt;…&lt;/a&gt;
     *   &lt;/div&gt;&lt;/div&gt;
     * </pre>
     *
     * <p>做法：按 <b>href</b>（而非 class 结构）定位 &lt;a&gt;，用标签配对扫描删掉
     * 整个 &lt;a&gt;…&lt;/a&gt;（含图片与文字）；若其父容器在删完后不再含任何
     * &lt;a&gt;/&lt;img&gt;/文字，则连父容器一起收掉，避免留下空白占位。
     */
    private static String stripPromoCards(String html) {
        if (html == null || html.isEmpty()) return html;
        try {
            String out = html;
            for (int round = 0; round < 8; round++) {
                // 找下一个 href 指向推广站点的 <a 起点
                int at = -1;
                for (String host : PROMO_HOSTS) {
                    int k = out.indexOf(host);
                    while (k >= 0) {
                        int lt = out.lastIndexOf("<a", k);
                        if (lt >= 0) {
                            int gt = out.indexOf('>', lt);
                            // 命中必须在同一个 <a ...> 标签内
                            if (gt >= 0 && gt > k) {
                                if (at < 0 || lt < at) at = lt;
                                break;
                            }
                        }
                        k = out.indexOf(host, k + 1);
                    }
                }
                if (at < 0) break;

                // 配对扫描找对应的 </a>
                int i = at;
                int depth = 0;
                int end = -1;
                while (i < out.length()) {
                    int lt = out.indexOf('<', i);
                    if (lt < 0) break;
                    if (out.startsWith("<!--", lt)) {
                        int ce = out.indexOf("-->", lt);
                        i = ce > 0 ? ce + 3 : out.length();
                        continue;
                    }
                    if (out.startsWith("</a", lt)) {
                        int gt = out.indexOf('>', lt);
                        if (gt < 0) break;
                        depth--;
                        if (depth <= 0) {
                            end = gt + 1;
                            break;
                        }
                        i = gt + 1;
                        continue;
                    }
                    if (out.startsWith("<a", lt)) {
                        int gt = out.indexOf('>', lt);
                        if (gt < 0) break;
                        if (out.substring(lt, gt + 1).endsWith("/>")) {
                            i = gt + 1;
                            continue;
                        }
                        depth++;
                        i = gt + 1;
                        continue;
                    }
                    i = lt + 1;
                }
                if (end < 0) break;
                out = out.substring(0, at) + out.substring(end);
            }
            return cleanupEmptyPromoWrappers(out);
        } catch (Throwable ignore) {
            return html;
        }
    }

    /**
     * 删掉侧边栏／历史页底部的「登入以同步您的資料 + 登入按钮」整块。
     *
     * <pre>
     * &lt;div id="navbar-sidebar-user-not-authed" class="hidden"&gt;
     *   &lt;button type="button" class="w-full px-4 py-2.5 … bg-primary …"
     *           data-navbar-sidebar-signin&gt; 登入 &lt;/button&gt;
     * &lt;/div&gt;
     * </pre>
     *
     * <p><b>为什么必须在服务端删：</b>Sidebar.astro 的脚本未登录时会执行
     * {@code t.classList.remove('hidden')}，而且它是 <b>async</b> 的
     * （{@code await isAuthenticated()} 之后才落地），总会晚于客户端的 {@code hide()}，
     * 所以客户端怎么藏都会被它重新揭开。
     *
     * <p><b>删除是否安全：</b>该脚本对这两个元素一律写成
     * {@code t && t.classList.xxx(...)} / {@code e && …} 的空值保护形式，
     * 取不到时静默跳过，因此直接从 HTML 删掉不会引起 JS 报错。
     * 已登录用户对应的是 {@code #navbar-sidebar-user-authed}（登出/个人中心），
     * 这里**不动**它，避免影响正常登录状态。
     */
    private static String stripSigninPrompt(String html) {
        if (html == null || html.isEmpty()) return html;
        try {
            String out = html;
            // 1) 主目标：整个未登录容器（含里面的登入按钮）
            for (int i = 0; i < 4; i++) {
                String next = stripNode(out, "navbar-sidebar-user-not-authed");
                if (next == null || next.equals(out)) break;
                out = next;
            }
            // 2) 兜底：单独存在的 data-navbar-sidebar-signin 按钮
            //    （万一站点改了容器 id，也要能删掉）
            for (int i = 0; i < 8; i++) {
                String next = stripElementByAttr(out, "data-navbar-sidebar-signin");
                if (next == null || next.equals(out)) break;
                out = next;
            }
            return out;
        } catch (Throwable ignore) {
            return html;
        }
    }

    /**
     * 删除「标签内带有 marker 属性」的整个元素（含配对结束标签）。
     * marker 例如 {@code data-navbar-sidebar-signin}。
     * 同样用标签配对扫描（注释感知），不用正则。
     */
    private static String stripElementByAttr(String html, String marker) {
        if (html == null || html.isEmpty()) return html;
        try {
            int at = html.indexOf(marker);
            if (at < 0) return html;
            int start = html.lastIndexOf('<', at);
            if (start < 0) return html;
            String tag = html.substring(start + 1).trim();
            int sp = 0;
            while (sp < tag.length() && !Character.isWhitespace(tag.charAt(sp))
                    && tag.charAt(sp) != '>' && tag.charAt(sp) != '/') sp++;
            String name = tag.substring(0, sp);
            if (name.isEmpty()) return html;

            int i = start;
            int depth = 0;
            int end = -1;
            while (i < html.length()) {
                int lt = html.indexOf('<', i);
                if (lt < 0) break;
                if (html.startsWith("<!--", lt)) {
                    int ce = html.indexOf("-->", lt + 4);
                    i = ce < 0 ? html.length() : ce + 3;
                    continue;
                }
                int gt = html.indexOf('>', lt);
                if (gt < 0) break;
                String inner = html.substring(lt + 1, gt).trim();
                boolean closing = inner.startsWith("/");
                String body = closing ? inner.substring(1).trim() : inner;
                int e2 = 0;
                while (e2 < body.length() && !Character.isWhitespace(body.charAt(e2))
                        && body.charAt(e2) != '>' && body.charAt(e2) != '/') e2++;
                String n2 = body.substring(0, e2);
                if (n2.equalsIgnoreCase(name)) {
                    if (closing) {
                        depth--;
                        if (depth <= 0) {
                            end = gt + 1;
                            break;
                        }
                    } else if (!html.substring(lt, gt + 1).endsWith("/>")) {
                        depth++;
                    }
                }
                i = gt + 1;
            }
            if (end < 0) return html;
            return html.substring(0, start) + html.substring(end);
        } catch (Throwable ignore) {
            return html;
        }
    }

    /**
     * 推广卡被删掉后，把遗留的空包裹容器（如 {@code <div class="container px-2">
     * <div class="flex flex-row gap-2 w-full flex-1"></div></div>}）一并清掉，
     * 否则页面顶部会留一条空白。
     *
     * <p>判据：容器内去掉空白与注释后**不含任何标签与可见文字**。
     */
    private static String cleanupEmptyPromoWrappers(String html) {
        if (html == null || html.isEmpty()) return html;
        try {
            String out = html;
            // 反复扫描：删掉一层空壳后，外层可能也变空了，所以需要多轮
            for (int round = 0; round < 24; round++) {
                int[] hit = findFirstEmptyWrapper(out);
                if (hit == null) break;
                out = out.substring(0, hit[0]) + out.substring(hit[1]);
            }
            return out;
        } catch (Throwable ignore) {
            return html;
        }
    }

    /**
     * 找出第一个「空壳 div」的 [start, end) 区间，找不到返回 null。
     *
     * <p>「空壳」判据（三条同时满足）：
     * <ol>
     *   <li>该 div 是推广卡的已知包裹形态：
     *       <ul>
     *         <li>{@code class="flex flex-row gap-2 w-full flex-1"}（内外两层都长这样）</li>
     *         <li>{@code class="container px-2"}（卡 A 的最外层）</li>
     *         <li><b>空 class</b>：{@code <div class>}（卡 B 的最外层，之前漏删的元凶）</li>
     *       </ul>
     *   </li>
     *   <li>去掉注释与空白后，div 内部没有任何内容（无子标签、无可见文字）；</li>
     *   <li>其闭合标签配平正确。</li>
     * </ol>
     * 只对这三种推广卡专用形态生效，避免误伤页面上其它正常占位容器。
     */
    private static int[] findFirstEmptyWrapper(String html) {
        // 三种推广卡包裹特征；startAt 从早到晚扫描，优先删最内层（内层删完外层才可能变空）
        int bestStart = -1;
        int bestEnd = -1;

        // ① flex flex-row gap-2 w-full flex-1
        int[] a = scanEmptyDiv(html, "class=\"flex flex-row gap-2 w-full flex-1\"");
        if (a != null) { bestStart = a[0]; bestEnd = a[1]; }

        // ② container px-2（仅当其中已无内容）
        int[] b = scanEmptyDiv(html, "class=\"container px-2\"");
        if (b != null && (bestStart < 0 || b[0] < bestStart)) { bestStart = b[0]; bestEnd = b[1]; }

        // ③ 空 class：<div class> 或 <div class="">
        int[] c = scanEmptyDiv(html, "class>");
        if (c == null) c = scanEmptyDiv(html, "class=\"\"");
        if (c != null && (bestStart < 0 || c[0] < bestStart)) { bestStart = c[0]; bestEnd = c[1]; }

        if (bestStart < 0) return null;
        return new int[]{bestStart, bestEnd};
    }

    /**
     * 在 html 中查找 needle 指向的 div，若它是空壳则返回 [start, end)。
     * 用 <div / </div> 配对扫描（含注释过滤），不用正则。
     */
    private static int[] scanEmptyDiv(String html, String needle) {
        int k = html.indexOf(needle);
        while (k >= 0) {
            // needle 必须是本标签 "class" 属性的一部分，回退到本标签 '<'
            int lt = html.lastIndexOf('<', k);
            if (lt >= 0) {
                // 必须是 div 标签
                if (html.startsWith("<div", lt)) {
                    int gt = html.indexOf('>', k);
                    if (gt >= 0) {
                        int depth = 0;
                        int i = lt;
                        int end = -1;
                        while (i < html.length()) {
                            int l2 = html.indexOf("<div", i);
                            int c2 = html.indexOf("</div>", i);
                            if (c2 < 0) break;
                            if (l2 >= 0 && l2 < c2) {
                                depth++;
                                i = l2 + 4;
                            } else {
                                depth--;
                                i = c2 + 6;
                                if (depth <= 0) {
                                    end = i;
                                    break;
                                }
                            }
                        }
                        if (end > gt) {
                            String inner = html.substring(gt + 1, end - 6);
                            if (isBlankMarkup(inner)) return new int[]{lt, end};
                        }
                    }
                }
            }
            k = html.indexOf(needle, k + 1);
        }
        return null;
    }

    /** 去掉 <script>/<style>/注释与所有空白后是否为空 */
    private static boolean isBlankMarkup(String s) {
        if (s == null || s.isEmpty()) return true;
        String t = s.replaceAll("(?s)<script\\b[^>]*>.*?</script>", "")
                .replaceAll("(?s)<style\\b[^>]*>.*?</style>", "")
                .replaceAll("(?s)<!--.*?-->", "")
                .replaceAll("\\s+", "")
                .replace("&nbsp;", "");
        return t.isEmpty();
    }

    /**
     * 删除 id=nodeId 的整个元素（含配对标签），并一并删掉紧随其后引用它的内联 <script>。
     * 用标签配对扫描而非正则整体替换，避免属性里含 '>' 时截断错误。
     */
    private static String stripNode(String html, String nodeId) {
        try {
            String key = "id=\"" + nodeId + "\"";
            int at = html.indexOf(key);
            if (at < 0) return html;
            // 回退到该标签起点
            int start = html.lastIndexOf('<', at);
            if (start < 0) return html;
            // 向前扫描配对结束（同类标签可能嵌套）
            String tag = html.substring(start + 1).trim();
            int sp = 0;
            while (sp < tag.length() && !Character.isWhitespace(tag.charAt(sp))
                    && tag.charAt(sp) != '>' && tag.charAt(sp) != '/') sp++;
            String name = tag.substring(0, sp);
            if (name.isEmpty()) return html;

            int i = start;
            int depth = 0;
            int end = -1;
            while (i < html.length()) {
                int lt = html.indexOf('<', i);
                if (lt < 0) break;
                if (html.startsWith("<!--", lt)) {
                    int ce = html.indexOf("-->", lt + 4);
                    i = ce < 0 ? html.length() : ce + 3;
                    continue;
                }
                int gt = html.indexOf('>', lt);
                if (gt < 0) break;
                String inner = html.substring(lt + 1, gt).trim();
                boolean closing = inner.startsWith("/");
                String body = closing ? inner.substring(1).trim() : inner;
                int e2 = 0;
                while (e2 < body.length() && !Character.isWhitespace(body.charAt(e2))
                        && body.charAt(e2) != '>' && body.charAt(e2) != '/') e2++;
                String n2 = body.substring(0, e2);
                if (n2.equalsIgnoreCase(name)) {
                    if (closing) {
                        depth--;
                        if (depth <= 0) { end = gt + 1; break; }
                    } else {
                        depth++;
                    }
                }
                i = gt + 1;
            }
            if (end < 0) return html;

            StringBuilder out = new StringBuilder(html.length());
            out.append(html, 0, start);
            out.append(html, end, html.length());
            return out.toString();
        } catch (Throwable e) {
            return html;
        }
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
        // 阅读记录 / 搜索 是我们要保留的功能页，别当成登录页拦掉
        if (p.equals("/history") || p.startsWith("/history/")
                || p.equals("/search") || p.startsWith("/search/")) return false;
        for (String x : LOGIN_PATHS) {
            if (p.equals(x) || p.startsWith(x + "/") || p.startsWith(x + "?")) return true;
        }
        return false;
    }

    /** 阅读记录页底部那个「登入」按钮：用站点自带属性精确定位并隐藏 */
    private static final String HIST_JS =
            // ★ v1.0.11：原来只按「文字 + href」猜，而且被 __hipHistJs 一次性守卫挡住，
            //   Astro 软导航后就再也不执行了。现在：
            //   1) 主要用站点自带的精确属性 data-navbar-sidebar-signin / -user-not-authed；
            //   2) 去掉一次性守卫，每次都重新扫描（用节流避免频繁重排）；
            //   3) 兼带把登录后的 data-navbar-sidebar-signout（登出）也隐藏。
            //   站点结构（/history）：
            //     <div id="navbar-sidebar-user-not-authed" class="hidden">
            //       <button data-navbar-sidebar-signin>登入</button></div>
            "(function(){function hide(e){try{e.style.setProperty('display','none','important');}catch(x){}}"
                    + "var LB=/^登入$|^登录$|^登錄$|^登出$|^註冊$|^注册$|^Sign in$|^Log in$|^Login$|^Register$|^Sign out$|^Logout$/;"
                    + "function hide(){try{var p=location.pathname||'';if(p.indexOf('/history')<0)return;"
                    + "var ex=document.querySelectorAll('[data-navbar-sidebar-signin],[data-navbar-sidebar-signout],#navbar-sidebar-user-not-authed,#navbar-sidebar-user-authed');"
                    + "for(var q=0;q<ex.length;q++){hide(ex[q]);}"
                    + "var as=document.querySelectorAll('a,button');for(var i=0;i<as.length;i++){var a=as[i];"
                    + "if(a.getAttribute&&(a.getAttribute('data-hip')||a.getAttribute('data-hipfav')))continue;var t=(a.textContent||'').trim();"
                    + "if(!t||t.length>10)continue;if(!LB.test(t))continue;var h=a.getAttribute('href')||'';if(h.indexOf('/works/')>=0)continue;"
                    + "if(h&&!/login|register|signin|signup|dashboard|user\\/profile/i.test(h))continue;hide(a);}"
                    + "var nz=document.getElementById('navbar-sidebar-user-not-authed');if(nz){var cs=nz.children;var vis=0;"
                    + "for(var k=0;k<cs.length;k++){if(getComputedStyle(cs[k]).display!=='none')vis++;}if(vis===0)hide(nz);}"
                    + "}catch(e){}}hide();setInterval(hide,1500);"
                    + "['astro:after-swap','astro:page-load','popstate','hashchange'].forEach(function(ev){try{document.addEventListener(ev,function(){setTimeout(hide,60);setTimeout(hide,400);},false);}catch(x){}});"
                    + "try{new MutationObserver(function(){setTimeout(hide,80);}).observe(document.documentElement,{childList:true,subtree:true});}catch(e){}"
                    + "})();";

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
     * 3) 登录注册入口 + 右上角/侧栏的「我的書架」；同时把右上角「搜索」「记录」入口恢复出来。
     *
     * 注意：本 App 不做书架功能，遇到的「我的書架 / 個人中心 / 登出」一律隐藏，
     * 绝不再把已隐藏的书架重新显示回来。
     */
    private static String cleanJs(boolean blockAd) {
        StringBuilder sb = new StringBuilder();
        // ★ v1.0.11：去掉「if(window.__hipClean)return」的一次性守卫。
        //   站点是 Astro 客户端路由（页面里注册了 astro:after-swap 监听）：
        //   软导航会替换整棵 body（新的 nav，含新的「我的書架」<a>），
        //   但 __hipClean 已经是 1 → 整个 cleanJs 直接 return，新 DOM 永远不被清理，
        //   「书架一直在、广告又出现」就是这么来的。
        //   现在改为：每次调用都重新建 <style>（先删旧的）+ 立即 scan 一次，
        //   并额外挂 astro:after-swap / popstate / hashchange 在软导航后补一次清理。
        sb.append("(function(){function hide(e){try{e.style.setProperty('display','none','important');}catch(x){}}var AD=__AD__;");
        // 旧的 style 节点若还在，先摘掉，避免重复叠加
        sb.append("try{var old=document.getElementById('__hipCss');if(old&&old.parentNode)old.parentNode.removeChild(old);}catch(x){}var css='';if(AD){css+='ins.adsbygoogle,[id^=google_ads],[id^=div-gpt-ad],';");
        sb.append("css+='[class*=adsbygoogle],[class*=google-ad],[class*=ad-slot],[id*=ad-slot],';css+='[class*=adsense],.adsbox,[class*=advertisement],[class*=advertising],';css+='iframe[src*=doubleclick],iframe[src*=googlesyndication],';");
        sb.append("css+='iframe[src*=adservice],iframe[src*=adsbygoogle]{display:none!important;}';}");
        // ---- 书架 / 登录入口隐藏（CSS 规则，Astro 水合替换节点后依然生效）----
        // 站点右上角图标（手机 UA）：搜尋 <a href="/search" aria-label="搜尋">
        //                          → 我的書架 <a href="https://m.xipmh.com/dashboard?lang=zh">
        //                          → 更多 <button aria-label="更多">
        // 另有 #navbar-sidebar 底部第二个「我的書架」（同为 dashboard 外链）。
        // 这里同时用「中文 aria-label」+「不依赖中文的 href/site 结构」双保险。
        sb.append("css+='a[href*=\"g-mh.com\"],a[href*=\"18gallery.com\"],';");
        sb.append("css+='a[href*=\"xipmh.com\"],a[href*=\"dashboard\"],a[href*=\"shelf\"],';");
        sb.append("css+='a[aria-label=\"個人中心\"],a[aria-label=\"个人中心\"],a[aria-label=\"登出\"],a[aria-label=\"退出\"],';");
        sb.append("css+='a[aria-label=\"我的書架\"],a[aria-label=\"我的书架\"],a[aria-label=\"書架\"],a[aria-label=\"书架\"],';");
        sb.append("css+='a[href$=\"/login\"],a[href$=\"/register\"],a[href$=\"/signin\"],a[href$=\"/signup\"]';");
        sb.append("css+='{display:none!important;}';");
        // 图库推广卡的【祖先容器】也要一起收掉，否则只隐藏 <a> 会留下空白广告位
        sb.append("css+='div:has(> div > a[href*=\"g-mh.com\"]),div:has(> div > a[href*=\"18gallery.com\"]){display:none!important;}';");
        sb.append("var st=document.createElement('style');st.id='__hipCss';st.textContent=css;document.head.appendChild(st);");
        sb.append("var LOGINP=['/login','/register','/signin','/signup','/user/profile','/account','/dashboard'];function isLoginHref(h){if(!h)return 0;h=String(h).toLowerCase();var i=h.indexOf('?');if(i>0)h=h.substring(0,i);");
        sb.append("if(h.indexOf('/works/')>=0)return 0;var s=h.indexOf('//');if(s>=0){var e=h.indexOf('/',s+2);h=e>=0?h.substring(e):'';}if(h==='/history'||h.indexOf('/history/')===0)return 0;if(h==='/search'||h.indexOf('/search/')===0)return 0;");
        sb.append("for(var i2=0;i2<LOGINP.length;i2++){var x=LOGINP[i2];if(h===x||h.indexOf(x+'/')===0)return 1;}return 0;}var LB=/^我的書架$|^我的书架$|^個人中心$|^个人中心$|^登出$|^退出$|^登入$|^登录$|^登錄$|^註冊$|^注册$/;var GK=/图库|圖庫|免费高清|免費高清/;");
        sb.append("window.__hipClass=function(el){try{if(!el||!el.getAttribute)return 0;var h=el.getAttribute('href')||'';if(h.indexOf('/works/')>=0)return 3;if(/g-mh\\.com|18gallery\\.com/i.test(h))return 2;var t=(el.textContent||'').trim();");
        sb.append("if(t&&t.length<=24&&GK.test(t))return 2;var im=el.querySelector?el.querySelector('img'):null;if(im){var al2=im.getAttribute('alt')||'';var sr=im.getAttribute('src')||'';if(/^G-MH$|^18GAL$/.test(al2))return 2;");
        sb.append("if(/g-mh-900|18gallery-1/i.test(sr))return 2;}if(isLoginHref(h))return 1;var ar=el.getAttribute('aria-label')||'';if(ar&&LB.test(ar.trim()))return 1;if(t&&t.length<=8&&LB.test(t))return 1;");
        // ---- 自建「搜索 / 记录」按钮 ----
        // 关键：不能再 host.appendChild(box) 到 <nav> 末尾。
        // 站点 <nav class="fixed z-[100]"> 的末尾是「第二行：横向滚动标签栏」，
        // 追加到末尾会让 box 落在标签栏之后，视觉错位且盖住第一行右侧的
        // 原生搜索/书架/汉堡图标，导致「右上角搜索点击没反应」。
        // 现在改为：优先插到【nav 第一行右侧图标容器内、原生搜索图标之前】，
        // 并显式给 box 加 pointer-events:auto 但不覆盖兄弟；找不到才退回 nav。
        sb.append("}catch(e){}return 0;};function mk(id,txt,href){var b=document.createElement('a');b.id=id;b.href=href;b.setAttribute('data-hip','1');b.style.cssText=");
        sb.append("'margin-left:8px;padding:3px 9px;border-radius:12px;background:rgba(120,130,150,.18);'");
        sb.append("+'color:inherit;font-size:12px;text-decoration:none;white-space:nowrap;display:inline-flex;align-items:center;position:relative;z-index:1;';");
        sb.append("b.textContent=txt;b.addEventListener('click',function(e){e.preventDefault();e.stopPropagation();");
        sb.append("try{location.href=href;}catch(x){}},false);return b;}");
        // 找到「nav 第一行右侧图标容器」——即原生搜索图标的 parentNode
        sb.append("function barHost(){try{var a=document.querySelector('nav a[aria-label=\"搜尋\"],nav a[aria-label=\"搜索\"]');");
        sb.append("if(a&&a.parentNode)return a.parentNode;var n=document.querySelector('nav header,nav');if(!n)return null;");
        // 退回：nav 内第一个 display:flex / justify-end 的容器
        sb.append("var ds=n.querySelectorAll('div');for(var i=0;i<ds.length;i++){var c=ds[i].className||'';");
        sb.append("if(typeof c==='string'&&c.indexOf('justify-end')>=0)return ds[i];}return n;}catch(e){return null;}}");
        sb.append("function bar(){try{if(document.getElementById('__hipSearch'))return;var host=barHost();if(!host)return;");
        sb.append("var box=document.createElement('span');box.id='__hipBox';box.setAttribute('data-hip','1');");
        sb.append("box.style.cssText='display:inline-flex;align-items:center;flex:0 0 auto;pointer-events:auto;';");
        sb.append("box.appendChild(mk('__hipSearch','搜索','https://m.hipmh.com/search'));");
        sb.append("box.appendChild(mk('__hipHist','记录','https://m.hipmh.com/history'));");
        // 插到原生搜索图标之前，保证不与右侧图标重叠、也不会挤到第二行标签栏
        sb.append("var sa=host.querySelector('a[aria-label=\"搜尋\"],a[aria-label=\"搜索\"]');");
        sb.append("if(sa&&sa.parentNode===host){host.insertBefore(box,sa);}else{host.appendChild(box);}");
        sb.append("}catch(e){}}");
        sb.append("var last=0;function scan(){try{var now=Date.now();");
        sb.append("if(now-last<800)return;last=now;bar();");
        //   右上角 <a href="https://m.xipmh.com/dashboard?lang=zh"> 与
        //   侧栏底部同款 <a> —— 一并隐藏，并把它所在的「空的图标位/按钮行」也收掉，
        //   否则会出现「按钮没了但位置留白」或第二个书架漏网。
        sb.append("var sh=document.querySelectorAll('a[href*=\"xipmh.com\"],a[href*=\"dashboard\"],a[href*=\"shelf\"]');");
        sb.append("for(var k=0;k<sh.length;k++){var e2=sh[k];if(e2.getAttribute('data-hip'))continue;hide(e2);e2.__hipShown=1;");
        // 若其父容器只剩这个链接（没有其它可点击子元素），把父容器一起隐藏，去掉留白
        sb.append("var pc=e2.parentElement;if(pc&&pc.querySelectorAll&&pc.querySelectorAll('a,button').length<=1){");
        sb.append("if(!pc.getAttribute('data-hip'))hide(pc);}}");
        sb.append("var as=document.querySelectorAll('a,button');for(var i=0;i<as.length;i++){var el=as[i];if(el.getAttribute('data-hip'))continue;");
        sb.append("var c=window.__hipClass(el);if(c===1){hide(el);continue;}if(c===3||c===0){if(el.__hipShown){el.style.removeProperty('display');el.__hipShown=0;}continue;}if(c===2){hide(el);el.__hipShown=1;var pa=el.parentElement;");
        sb.append("if(pa&&(pa.textContent||'').trim().length<=40){hide(pa);pa.__hipShown=1;}continue;}if(AD){var h2=el.getAttribute('href')||'';if(h2&&/doubleclick|googlesyndication|adsbygoogle|popads|adnxs/.test(h2)){hide(el);");
        sb.append("var pb=el.parentElement;if(pb)hide(pb);}}}if(AD){var ifs=document.querySelectorAll('iframe');for(var j=0;j<ifs.length;j++){var s=ifs[j].src||'';if(/doubleclick|googlesyndication|adservice|adsbygoogle|adnxs|criteo|taboola|popads|googletagmanager|google-analytics|googleadservices/.test(s)){hide(ifs[j]);");
        sb.append("var pp=ifs[j].parentElement;if(pp)hide(pp);}}");
        // GTM 是新的广告注入源：<iframe src=".../ns.html?id=GTM-..."> + dataLayer 脚本加载 gtm.js
        // 这里把 GTM 注入出来的广告容器一并收掉（按 id/class 特征）。
        sb.append("var gt=document.querySelectorAll('[id^=GTM],[id*=gtm-],[class*=gtm-],ins.adsbygoogle,div[id^=aswift],iframe[id^=aswift]');");
        sb.append("for(var q=0;q<gt.length;q++){hide(gt[q]);}}");
        sb.append("}catch(e){}}scan();try{new MutationObserver(function(m){for(var i=0;i<m.length;i++){var n=m[i];if(n.type==='childList'&&n.addedNodes&&n.addedNodes.length){scan();");
        // ★ v1.0.11：Astro 客户端路由（astro:after-swap）会替换整棵 body，
        //   此时 scan() 受 800ms 节流可能直接被跳过，新的书架/广告就不会被清理。
        //   这里在软导航事件后强制重置节流并立即重扫，保证「每次都清」。
        sb.append("return;}}}).observe(document.documentElement,{childList:true,subtree:true});}catch(e){}"
                + "function force(){try{last=0;}catch(e){}scan();}"
                + "['astro:after-swap','astro:page-load','popstate','hashchange'].forEach(function(ev){"
                + "try{document.addEventListener(ev,function(){setTimeout(force,60);setTimeout(force,400);setTimeout(force,1200);},false);}catch(x){}});"
                + "setInterval(scan,4000);})();");
        return sb.toString();
    }

    /** 登录注册入口 + 图库推广卡：点了什么都不发生 */
    private void injectGuard() {
        web.evaluateJavascript(GUARD_JS, null);
    }

    /** 阅读记录页：隐藏底部的「登入」按钮 */
    private void injectHist() {
        web.evaluateJavascript(HIST_JS, null);
    }

    private static final String GUARD_JS =
            "(function(){if(window.__hipGuard)return;window.__hipGuard=1;function blocked(a){try{if(!a||!a.getAttribute)return 0;if(a.getAttribute('data-hip')||a.getAttribute('data-hipfav'))return 0;"
                    + "var c=window.__hipClass?window.__hipClass(a):0;return (c===1||c===2)?1:0;}catch(e){}return 0;}function kill(e){try{var el=e.target;"
                    + "if(!el)return;var hit=0;if(el.nodeType===1)hit=blocked(el);if(!hit&&el.closest)hit=blocked(el.closest('a,button'));if(hit){e.preventDefault();"
                    + "e.stopPropagation();}}catch(x){}}document.addEventListener('click',kill,false);})();";

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

    /**
     * 繁体转简体：站点本身只有繁体，这里把页面文本转成简体并默认开启。
     * 映射表约 58KB，MPA 每次跳转都重传会明显拖慢体验 —— 所以只在首次注入 map，
     * 之后仅补注入转换逻辑（新页面 window 已重置，逻辑必须重注入）。
     */
    private void injectT2s() {
        if (!t2sInjected) {
            String map = t2sMap();
            if (map == null || map.isEmpty()) return;
            t2sInjected = true;
            web.evaluateJavascript(map + T2S_JS, null);
        } else {
            web.evaluateJavascript(T2S_JS, null);
        }
    }

    private static final String T2S_JS =
            "(function(){"
                    // ★ v1.0.11：去掉 __hipT2s 一次性守卫 → Astro 软导航后新渲染的
                    //   繁体中文字也能立即转成简体（原来的守卫会让第二次起完全失效）。
                    //   MutationObserver 也需要重新挂；用 __hipT2sObs 防止重复注册观察器。
                    + "var M=window.__hipT2SMap;if(!M)return;"
                    + "var SKIP={SCRIPT:1,STYLE:1,NOSCRIPT:1,TEXTAREA:1,CODE:1,PRE:1};"
                    + "function cv(s){return s.replace(/[\\u4e00-\\u9fff]/g,function(c){"
                    + "var v=M[c];return v!==undefined?v:c;});}"
                    + "function cvText(n){try{var x=n.nodeValue;if(!x)return;"
                    + "var y=cv(x);if(y!==x)n.nodeValue=y;}catch(e){}}"
                    + "function skip(n){var p=n.parentNode;if(!p||p.nodeType!==1)return 0;"
                    + "return SKIP[p.nodeName]?1:0;}"
                    + "function conv(root){try{"
                    + "if(!root)return;"
                    + "if(root.nodeType===3){if(!skip(root))cvText(root);return;}"
                    + "if(root.nodeType!==1)return;"
                    + "if(SKIP[root.nodeName])return;"
                    + "var w=document.createTreeWalker(root,NodeFilter.SHOW_TEXT,null,false);"
                    + "var batch=[],n;while((n=w.nextNode())){if(!skip(n))batch.push(n);}"
                    + "for(var i=0;i<batch.length;i++)cvText(batch[i]);"
                    + "}catch(e){}}"
                    + "var pend=[],timer=0;"
                    + "function flush(){timer=0;try{"
                    + "var list=pend;pend=[];"
                    + "for(var i=0;i<list.length;i++){if(list[i].isConnected!==false)conv(list[i]);}"
                    + "}catch(e){}}"
                    + "function queue(n){try{pend.push(n);if(pend.length>60){flush();return;}"
                    + "if(!timer)timer=setTimeout(flush,250);}catch(e){}}"
                    + "conv(document.body);"
                    + "if(!window.__hipT2sObs){window.__hipT2sObs=1;try{new MutationObserver(function(m){"
                    + "for(var i=0;i<m.length;i++){var an=m[i].addedNodes;"
                    + "if(!an)continue;"
                    + "for(var j=0;j<an.length;j++){var n=an[j];"
                    + "if(n.nodeType===1||n.nodeType===3)queue(n);}}}"
                    + ").observe(document.body,{childList:true,subtree:true});}catch(e){}}"
                    // Astro 软导航换 body 后 main/body 是同一节点，Observer 仍然有效；
                    // 但整棵重渲染的文本需要再整体转一次，这里补一次全量转换。
                    + "['astro:after-swap','astro:page-load'].forEach(function(ev){"
                    + "try{document.addEventListener(ev,function(){setTimeout(function(){conv(document.body);},80);"
                    + "setTimeout(function(){conv(document.body);},500);},false);}catch(x){}});"
                    + "})();";

    /** 资源/缓存拦截（主 WebView 与隐藏后台 WebView 共用）：
     *  广告/推广直接返回空；可缓存资源命中 ResCache 直接回放，未命中则下载并落盘。 */
    private WebResourceResponse interceptRes(WebResourceRequest req) {
        String u = req.getUrl() != null ? req.getUrl().toString() : "";
        if (u.isEmpty()) return null;
        if (prefs.adBlock() && isAd(u)) return empty();
        if (isPromoImg(u)) return empty();
        if (ResCache.isCacheable(u) && (prefs.cacheAssets() || prefs.preloadChapter())) {
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

    private void injectReader(String u) {
        injectReaderInto(web, u, true);
    }

    /** 往指定 WebView 注入阅读页预加载脚本。
     *  allowNext=true（主 WebView）：额外抓取「下一章 hid」触发后台预抓；
     *  allowNext=false（隐藏后台 WebView）：只收集+预抓本章图片，不级联。 */
    private void injectReaderInto(WebView w, String u, boolean allowNext) {
        if (u == null || !(u.contains("/chapter/") || u.contains("/chapter/go"))) return;
        w.evaluateJavascript(readerJs(allowNext), null);
    }

    private String readerJs(boolean allowNext) {
        // ★ v1.5：allowNext=true（主 WebView）时，阅读页 JS 顺带请求站点章节 API 拿 next_hid，
        //   交给原生去隐藏后台 WebView 预抓下一章图片（翻章零等待）。
        String nextBlock = allowNext ? (
                "try{var __re=document.querySelector('[data-api-base-url]');"
                + "var __ab=__re?__re.getAttribute('data-api-base-url'):'';"
                + "var __ah=__re?__re.getAttribute('data-api-hid'):'';"
                + "if(__ab&&__ah&&window.HipApp&&HipApp.preloadNext){"
                + "fetch(__ab+'/v2/chapter?hid='+encodeURIComponent(__ah))"
                + ".then(function(r){return r.json();})"
                + ".then(function(j){try{var __d=j&&j.data?j.data:null;"
                + "var __nh=__d?__d.next_hid:null;"
                + "if(__nh&&window.HipApp&&HipApp.preloadNext)HipApp.preloadNext(__nh);}"
                + "catch(e){}}).catch(function(e){});}"
                + "}catch(e){}"
        ) : "";
        return "(function(){"
                + "try{localStorage.setItem('chapterApiLine','" + prefs.imgLine() + "');}catch(e){}"
                // ★ v1.2：去掉 __hipReady 一次性守卫 → Astro 软导航后阅读页也能重新挂
                //   （返回按钮 + 滚动锁 + 沉浸点击）。用 __hipReadyObs 防重复注册观察器。
                + "if(!window.__hipReaderCss){window.__hipReaderCss=1;var st=document.createElement('style');"
                + "st.id='__hipReaderCss';"
                + "st.textContent='img{max-width:100%!important;height:auto!important;display:block;}'"
                + "+'html,body{overflow-x:hidden!important;max-width:100%!important;}'"
                + "+'ins.adsbygoogle,iframe[src*=ads],.adsbox,[id^=google_ads],'"
                + "+'[id^=div-gpt-ad],[class*=adsbygoogle]{display:none!important;}'"
                + "+'#d-chapters-modal,#d-modal-chapters-list{overscroll-behavior:contain;}'"
                // 「返回书籍」悬浮按钮（右上角圆形，避开顶部系统栏）
                + "+'#__hipBack{position:fixed;top:calc(14px + env(safe-area-inset-top,0px));right:12px;'"
                + "+'z-index:2147483000;width:38px;height:38px;border-radius:50%;display:flex;'"
                + "+'align-items:center;justify-content:center;background:rgba(24,26,32,.62);'"
                + "+'color:#fff;border:0;padding:0;cursor:pointer;backdrop-filter:blur(6px);'"
                + "+'-webkit-backdrop-filter:blur(6px);box-shadow:0 2px 8px rgba(0,0,0,.28);';"
                + "document.head.appendChild(st);}"
                // 章节目录弹窗 / 设置抽屉打开时锁背景滚动，防止滚动穿透
                + "function __hipVis(id){try{var e=document.getElementById(id);"
                + "if(!e)return 0;if(e.classList.contains('hidden'))return 0;"
                + "var s=window.getComputedStyle?getComputedStyle(e):null;"
                + "if(s&&(s.display==='none'||s.visibility==='hidden'||parseFloat(s.opacity||'1')<0.05))return 0;"
                + "return 1;}catch(x){return 0;}}"
                + "function __hipMenuOpen(){return __hipVis('drawerOverlay')||__hipVis('d-chapters-modal')"
                + "||__hipVis('d-modal-chapters-list')||__hipVis('readerMenu')||__hipVis('navOverlay');}"
                + "function __hipLock(){var mo=__hipMenuOpen();"
                + "document.body.style.overflow=mo?'hidden':'';}"
                + "function __hipSync(){__hipLock();}"
                + "__hipSync();"
                + "if(!window.__hipReadyObs){window.__hipReadyObs=1;"
                + "try{new MutationObserver(__hipSync).observe(document.documentElement,"
                + "{attributes:true,subtree:true,attributeFilter:['class','style']});}catch(e){}}"
                // ★ v1.2：「返回书籍」按钮 —— 点击后走原生 onBackPressed，
                //   由它按「hid → m:<作品id> → /works/<b64>」回到书籍详情页。
                + "function __hipBackBtn(){try{if(!location.pathname||location.pathname.indexOf('/chapter/')<0){"
                + "var eb=document.getElementById('__hipBack');if(eb&&eb.parentNode)eb.parentNode.removeChild(eb);return;}"
                + "if(document.getElementById('__hipBack'))return;"
                + "var b=document.createElement('button');b.id='__hipBack';b.setAttribute('aria-label','返回书籍');"
                + "b.innerHTML='<svg width=\"20\" height=\"20\" viewBox=\"0 0 24 24\" fill=\"none\" "
                + "stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" "
                + "stroke-linejoin=\"round\"><path d=\"M15 18l-6-6 6-6\"></path></svg>';"
                + "b.addEventListener('click',function(e){e.preventDefault();e.stopPropagation();"
                + "try{if(window.HipApp&&HipApp.backToBook)HipApp.backToBook();else history.back();}"
                + "catch(x){try{history.back();}catch(y){}}},true);"
                + "document.body.appendChild(b);}catch(e){}}"
                + "__hipBackBtn();setTimeout(__hipBackBtn,600);setTimeout(__hipBackBtn,1800);"
                + "['astro:after-swap','astro:page-load','popstate','hashchange'].forEach(function(ev){"
                + "try{document.addEventListener(ev,function(){setTimeout(__hipBackBtn,80);"
                + "setTimeout(__hipBackBtn,500);},false);}catch(x){}});"
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
// ★ v1.4：阅读页图片预加载 —— 收集图片 URL 交给原生线程池并发抓进 ResCache，
//   翻页时 WebView 直接从磁盘缓存取、零网络等待（懒加载图也按 data-src 取真地址）
+ "function __hipImgUrl(el){var c=el.getAttribute('data-src')||el.getAttribute('data-original')||"
+ "el.getAttribute('data-lazy-src')||el.getAttribute('data-lazy')||el.getAttribute('data-url')||"
+ "el.currentSrc||el.src||'';if(!c||c.indexOf('data:')===0||c.indexOf('blob:')===0)return '';"
+ "try{return new URL(c,location.href).href;}catch(e){return c;}}"
+ "function __hipCollectImgs(){if(!location.pathname||location.pathname.indexOf('/chapter/')<0)return '[]';"
+ "var r=document.getElementById('chapcontent');"
+ "if(!r){var f=document.querySelector('.chapter-image,.chapter-img-container,.reader-content');r=f||document.body;}"
+ "var ns=r.querySelectorAll('img');var out=[];for(var i=0;i<ns.length;i++){"
+ "var u=__hipImgUrl(ns[i]);if(u)out.push(u);}return JSON.stringify(out);}"
+ "function __hipPreload(){try{if(window.HipApp&&HipApp.preloadImages)"
+ "HipApp.preloadImages(__hipCollectImgs());}catch(e){}}"
+ "__hipPreload();setTimeout(__hipPreload,800);setTimeout(__hipPreload,2000);setTimeout(__hipPreload,4000);"
+ "var __hipPlT=0;try{var po=new MutationObserver(function(){var n=Date.now();"
+ "if(n-__hipPlT<600)return;__hipPlT=n;__hipPreload();});"
+ "var ph=document.getElementById('chapcontent')||document.querySelector('.chapter-image,.chapter-img-container')||document.body;"
+ "po.observe(ph,{childList:true,subtree:true});}catch(e){}"
+ "['astro:after-swap','astro:page-load','popstate','hashchange'].forEach(function(ev){"
+ "try{document.addEventListener(ev,function(){setTimeout(__hipPreload,200);"
+ "setTimeout(__hipPreload,1200);},false);}catch(x){}});"
+ nextBlock
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
                "浏览记录", "设置",
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
                                try {
                                    Intent li = new Intent(MainActivity.this, ListActivity.class);
                                    li.putExtra("tab", "hist");
                                    startActivity(li);
                                } catch (Throwable ignore) {
                                }
                                break;
                            case 12:
                                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
                                break;
                            case 13:
                                openExternal(web.getUrl());
                                break;
                            case 14:
                                clearCache();
                                break;
                            case 15:
                                finish();
                                break;
                            default:
                                break;
                        }
                    }
                })
                .show();
    }

    /**
     * 从章节地址还原作品详情地址：https://m.hipmh.com/works/xxxx/chapter/12 -> .../works/xxxx
     */
    private static String workUrl(String u) {
        try {
            int i = u.indexOf("/works/");
            if (i < 0) return u;
            int j = i + 7;
            int k = u.indexOf('/', j);
            if (k < 0) {
                int q = u.indexOf('?', j);
                return q < 0 ? u : u.substring(0, q);
            }
            return u.substring(0, k);
        } catch (Throwable e) {
            return u;
        }
    }

    /** 章节标题通常形如「作品名 - 第12话」，收藏时只留作品名 */
    private static String workTitle(String t) {
        if (t == null) return null;
        int i = t.indexOf(" - ");
        if (i > 0) return t.substring(0, i).trim();
        int j = t.indexOf("–");
        if (j > 0) return t.substring(0, j).trim();
        return t;
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
        // ★ v1.2：阅读器返回逻辑重做。
        //   用户要求：在阅读器里按返回 = 退出阅读器、回到【书籍详情页】，
        //   而不是依赖 WebView 历史（历史里可能是上一章/下一章），
        //   更不能因为 canGoBack()==false 就落到「再按一次退出软件」。
        //
        //   站点 URL 规律（已实测确认）：
        //     阅读器 /chapter/go?hid=<urlsafe-b64("m:<作品id>-c:<章节id>")>&m=<作品id>&ct=…
        //     详情页 /works/<urlsafe-b64("m:<作品id>")>
        //   验证：hid=bToxNTAzMS1jOjEzOTc1-… → 解出 m:15031 →
        //        /works/bToxNTAzMQ 打开 title=「我独自升级 : 诸神黄昏」✓
        if (readingMode) {
            String book = bookUrlFromCurrent();
            if (book != null) {
                // 已经在该书的详情页（防止来回打转）
                String cur = web.getUrl();
                if (cur == null || !book.equals(cur)) {
                    web.loadUrl(book);
                    return;
                }
            }
            // 拿不到作品 id：至少保证「退出阅读器」，不要退出软件
            if (web.canGoBack()) {
                web.goBack();
                return;
            }
            // 实在没有可回退的历史 → 回首页
            web.loadUrl(Prefs.HOME);
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

    /**
     * 从当前（或历史里的）阅读器 URL 推导所属书籍详情页 URL。
     *
     * <p>站点规则：
     * <ul>
     *   <li>阅读器 {@code /chapter/go?hid=<b64url("m:<作品id>-c:<章节id>…")>&m=<作品id>}</li>
     *   <li>详情页 {@code /works/<b64url("m:<作品id>")>}</li>
     * </ul>
     * 优先读 {@code m=} 查询参数（最稳），回退解 {@code hid} 的 base64 取 {@code m:<id>}。
     *
     * @return 详情页绝对 URL；解析失败返回 {@code null}
     */
    private String bookUrlFromCurrent() {
        try {
            String cur = web.getUrl();
            if (cur == null) return null;
            int id = workIdOf(cur);
            if (id <= 0 && web.canGoBack()) {
                // 当前可能已被重定向掉，退回上一条历史再试
                id = workIdOf(lastNonReaderUrl());
            }
            if (id <= 0) return null;
            String b64 = android.util.Base64.encodeToString(
                    ("m:" + id).getBytes("UTF-8"),
                    android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING
                            | android.util.Base64.NO_WRAP);
            return "https://m.hipmh.com/works/" + b64;
        } catch (Throwable e) {
            return null;
        }
    }

    /** 从 URL 里提取作品 id（`m=` 参数优先，其次解 `hid` 的 base64）。 */
    private static int workIdOf(String u) {
        if (u == null) return -1;
        try {
            // 1) ?m=15031
            int q = u.indexOf("m=");
            while (q >= 0) {
                int s = q + 2;
                int e = s;
                while (e < u.length() && Character.isDigit(u.charAt(e))) e++;
                if (e > s && (q == 0 || !Character.isLetterOrDigit(u.charAt(q - 1)))) {
                    return Integer.parseInt(u.substring(s, e));
                }
                q = u.indexOf("m=", q + 2);
            }
            // 2) hid=<b64>，解出 m:<id>
            int h = u.indexOf("hid=");
            if (h >= 0) {
                int s = h + 4;
                int e = s;
                while (e < u.length() && u.charAt(e) != '&' && u.charAt(e) != '#') e++;
                String b64 = u.substring(s, e);
                String dec = decodeB64Url(b64);
                if (dec != null) {
                    int mi = dec.indexOf("m:");
                    if (mi >= 0) {
                        int p = mi + 2;
                        int z = p;
                        while (z < dec.length() && Character.isDigit(dec.charAt(z))) z++;
                        if (z > p) return Integer.parseInt(dec.substring(p, z));
                    }
                }
            }
        } catch (Throwable ignore) {
        }
        return -1;
    }

    /** base64url 解码（自动补 padding、容忍尾部杂物）。 */
    private static String decodeB64Url(String s) {
        try {
            if (s == null || s.isEmpty()) return null;
            // 站点把 hid 写成 "<b64>-<b64>"，只取第一段
            int dash = s.indexOf('-');
            String head = dash > 0 ? s.substring(0, dash) : s;
            String t = head.replace('-', '+').replace('_', '/');
            int pad = (4 - t.length() % 4) % 4;
            for (int i = 0; i < pad; i++) t = t + "=";
            byte[] raw = android.util.Base64.decode(t, android.util.Base64.DEFAULT);
            return new String(raw, "UTF-8");
        } catch (Throwable e) {
            return null;
        }
    }

    /** 在 WebView 历史里找最近一条「非阅读器」的 URL。 */
    private String lastNonReaderUrl() {
        try {
            android.webkit.WebBackForwardList l = web.copyBackForwardList();
            if (l == null) return null;
            int idx = l.getCurrentIndex();
            for (int i = idx - 1; i >= 0 && i >= idx - 5; i--) {
                android.webkit.WebHistoryItem it = l.getItemAtIndex(i);
                if (it == null) continue;
                String u = it.getUrl();
                if (u != null && !u.contains("/chapter/")) return u;
            }
        } catch (Throwable ignore) {
        }
        return null;
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
            if (bgWeb != null) {
                bgWeb.stopLoading();
                bgWeb.setWebViewClient(null);
                bgWeb.setWebChromeClient(null);
                bgWeb.destroy();
                bgWeb = null;
            }
        } catch (Throwable ignore) {
        }
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

    /** 懒创建隐藏后台 WebView（与主 WebView 共享 JsBridge / ResCache / UA）。
     *  它永不被加入视图层级，仅用于后台加载下一章并把图片预抓进共享 ResCache。 */
    private void ensureBgWeb() {
        if (bgWeb != null) return;
        try {
            bgWeb = new WebView(this);
            WebSettings s = bgWeb.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setDatabaseEnabled(true);
            s.setLoadWithOverviewMode(true);
            s.setUseWideViewPort(true);
            s.setCacheMode(WebSettings.LOAD_DEFAULT);
            s.setUserAgentString(uaCache);
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
            bgWeb.addJavascriptInterface(new JsBridge(), "HipApp");
            bgWeb.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView v, String u) {
                    // 只收集+预抓本章图片，不级联预抓（allowNext=false）
                    injectReaderInto(v, u, false);
                }

                @Override
                public WebResourceResponse shouldInterceptRequest(WebView v,
                                                                 WebResourceRequest req) {
                    return interceptRes(req);
                }
            });
        } catch (Throwable ignore) {
        }
    }

    /** ★ v1.5：主阅读页 JS 拿到下一章 hid 后回调 —— 后台预抓下一章图片。
     *  翻章时主 WebView 命中共享 ResCache 磁盘缓存，零网络等待。 */
    private void preloadNextChapter(String nextHid) {
        if (!prefs.preloadChapter()) return;
        if (nextHid == null || nextHid.isEmpty()) return;
        if (nextHid.equals(bgHid)) return;                 // 已安排/正在预抓同一章
        // 不要把「当前正在读的章」误当成下一章（极端情况）
        if (readingMode && web.getUrl() != null && web.getUrl().contains(nextHid)) return;
        bgHid = nextHid;
        ensureBgWeb();
        if (bgWeb != null) {
            bgWeb.loadUrl("https://reader.hipmh.top/chapter/" + nextHid);
        }
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

        /** 点右上角「记录」-> 打开本地浏览历史 */
        @JavascriptInterface
        public void openHistList() {
            ui.post(() -> {
                try {
                    Intent i = new Intent(MainActivity.this, ListActivity.class);
                    i.putExtra("tab", "hist");
                    startActivity(i);
                } catch (Throwable ignore) {
                }
            });
        }

        /**
         * ★ v1.2：阅读页内的「返回书籍」按钮走这里 —— 复用与系统返回键同一套逻辑，
         * 回到所属书籍详情页（而不是退出软件、也不是上一章）。
         */
        @JavascriptInterface
        public void backToBook() {
            ui.post(() -> {
                try {
                    String book = bookUrlFromCurrent();
                    if (book != null) {
                        String cur = web.getUrl();
                        if (cur == null || !book.equals(cur)) {
                            web.loadUrl(book);
                            return;
                        }
                    }
                    if (web.canGoBack()) web.goBack();
                    else web.loadUrl(Prefs.HOME);
                } catch (Throwable ignore) {
                }
            });
        }

        /**
         * ★ v1.4：阅读页 JS 收集到的图片 URL 列表传过来（JSON 数组）。
         * 原生用 8 线程池并发预抓进 ResCache，翻页时直接从磁盘取、零网络等待。
         * 已缓存 / 已提交 / 非图片 / 广告域名一律跳过，避免重复与浪费。
         */
        @JavascriptInterface
        public void preloadImages(String json) {
            if (!prefs.preloadChapter()) return;
            if (json == null || json.isEmpty()) return;
            try {
                JSONArray arr = new JSONArray(json);
                final int n = arr.length();
                for (int i = 0; i < n; i++) {
                    String u = arr.optString(i);
                    if (u == null || u.isEmpty()) continue;
                    if (!u.startsWith("http")) continue;
                    if (MainActivity.this.isAd(u) || MainActivity.this.isPromoImg(u)) continue;
                    if (!ResCache.isCacheable(u)) continue;
                    if (ResCache.get(u) != null) continue;   // 已缓存，跳过
                    if (!preloadSeen.add(u)) continue;       // 已提交，跳过
                    final String fu = u;
                    preloadPool.execute(() -> {
                        try {
                            ResCache.fetch(fu, MainActivity.this.uaCache, MainActivity.this.refCache);
                        } catch (Throwable ignore) {
                        }
                    });
                }
            } catch (Throwable ignore) {
            }
        }

        /**
         * ★ v1.5：阅读页 JS 从章节 API 拿到下一章 hid 后回调，
         * 由原生启动隐藏后台 WebView 预抓下一章图片。
         */
        @JavascriptInterface
        public void preloadNext(String nextHid) {
            ui.post(() -> MainActivity.this.preloadNextChapter(nextHid));
        }
    }
}
