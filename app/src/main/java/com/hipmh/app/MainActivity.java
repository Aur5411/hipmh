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
import java.util.List;
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
    /**
     * ★ v1.10.0：独立的「阅读器窗口」（第二个 WebView，悬浮在 {@link #web} 之上）。
     *
     * <p>用户要求：点进漫画开始阅读 = 「新开一个浏览器窗口」，之后的上一章 / 下一章
     * 都在这个窗口里进行，**返回 = 关闭窗口**。
     *
     * <p>这样做同时根治了 v1.9.3 的两个遗留问题：
     * <ol>
     *   <li>「返回详情页后，再返回又回到漫画阅读页」——
     *       同一个 WebView 时，{@code loadUrl(详情页)} 会往历史栈**追加一格**，
     *       再按返回就 {@code goBack()} 回到了历史里的阅读页。拆开后主 WebView 全程
     *       停在详情页原地不动，历史栈里根本没有阅读器。</li>
     *   <li>阅读器返回逻辑被迫用 {@code loadUrl} 硬跳、无法用历史回退。</li>
     * </ol>
     *
     * <p>生命周期：{@code enterReader()} 显示并加载 → 内部自由翻章 →
     * {@code exitReader()} 隐藏（并停止一切后台动作），主 WebView 原地露出。
     */
    private WebView rdWeb;
    private SwipeRefreshLayout swipe;
    private ProgressBar progress;
    private LinearLayout errorView;
    private ImageButton btnTop;
    private ImageButton btnFab;
    private View root;

    private Prefs prefs;
    private Store store;
    /** 最近一次页面标题（v1.7：不再写历史表，但仍用于收藏时兜底取标题） */
    private String lastTitle;
    /**
     * ★ v1.9：当前详情页解析出的封面图 URL（由 {@code COVER_JS} 回传）。
     * 收藏时写进 fav.cover，供书架列表显示封面。
     */
    private String lastCoverUrl;
    /** lastCoverUrl 对应的作品 key，避免串页 */
    private String lastCoverFor;
    /** ★ v1.9：站点 {@code [data-manga-title]} 给出的官方标题（比页面 <h1> 更干净） */
    private String lastCoverTitle;
    /** ★ v1.9.1：书架封面预热线程池（进页面时把封面灌进 ResCache） */
    private ExecutorService coverPool;
    private Handler ui;

    /**
     * ★ v1.10.0：阅读器窗口是否处于「打开」状态。
     * 与 {@link #readingMode} 的区别：{@code readingMode} 描述的是**当前正在显示的
     * 这个 WebView** 是不是阅读页（用于沉浸模式、音量翻页等）；
     * {@code readerOpen} 描述的是**阅读器窗口整体是否存在**（决定返回键该不该「关窗」）。
     * 拆开是因为主 WebView 现在永远不会进入 readingMode，两个语义不再重合。
     */
    private boolean readerOpen = false;
    /** ★ v1.10.0：阅读器窗口当前 URL（用于退出后仍能解析所属作品，以及调试） */
    private String readerUrl = null;
    /**
     * ★ v1.10.0：「我的书架」抽屉当前是否打开（由 {@code drawerJs} 的 tick 实时上报，
     * 见 {@link JsBridge#drawerState(boolean)}）。
     *
     * <p>返回键要靠它决定优先级：抽屉开着时，返回**先收起抽屉**，而不是退页面。
     * volatile：桥接方法在 WebView 线程被调用，返回键在主线程读。
     */
    private volatile boolean drawerOpen = false;
    /**
     * ★ v2.0.0：阅读器窗口最近一次上报的进度（防抖 + 复用）。
     * {@code reportProgressHid} 记录上报所属章节的 hid 片段 —— 同一章内
     * 反复触发（软导航 / MutationObserver 重跑）不再重复写库。
     */
    private String reportProgressHid = null;
    private boolean readingMode = false;
    private long lastBackMs = 0L;
    /**
     * ★ v1.9.4：手指是否正按在「我的书架」抽屉的条目上（由 JS 桥接实时上报）。
     *
     * <p>用来解决「长按书架里的书会弹两个窗」：抽屉条目的封面是 {@code <img>}，
     * 长按时 **WebView 原生长按**（{@code setOnLongClickListener}）会命中
     * {@code IMAGE_TYPE} 并弹出「全屏查看图片 / 复制图片链接 / 在新页面打开」，
     * 同时 JS 的 500ms 定时器又弹出我们自己的书架菜单 → 两个窗一起出现。
     * 有了这个标志，原生长按回调发现「用户按的是书架条目」时直接吞掉，
     * 全权交给 JS 的书架菜单处理。
     */
    private volatile boolean shelfTouchActive = false;
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
        rdWeb = findViewById(R.id.rdWeb);
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
        setupReaderWeb();
        setupActions();

        // ★ v1.6：从「浏览历史 / 我的书架」点进来时，MainActivity 有可能是被系统回收后
        //   重建的（此时 onNewIntent 不会走，onCreate 才走）。以前这里只认 savedInstanceState，
        //   结果 Intent 里的 url 被丢掉、直接回到首页 —— 表现就是「点了历史像没反应 / 空白页」。
        //   现在 onCreate 也读 url 额外参数，保证两种情况都能落到目标页。
        String startUrl = null;
        try {
            Intent si = getIntent();
            if (si != null) startUrl = si.getStringExtra("url");
        } catch (Throwable ignore) {
        }
        if (startUrl != null && startUrl.startsWith("http")) {
            web.loadUrl(startUrl);
        } else if (savedInstanceState == null) {
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
        applyCommonSettings(s);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);

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
                // ★ v1.10.0：不再需要记住「最近详情页」——
                //   阅读器已拆成独立窗口，主 WebView 全程停在详情页原地，
                //   「返回」= 关掉阅读器窗口，详情页自然露出，无需任何 URL 兜底。
                applyMode(u);
                injectClean();
                injectGuard();
                injectHist();
                injectT2s();
                injectReader(u);
                injectFav();
                injectCover();
                injectDetailProgress();
                injectDrawer();
                // ★ v1.7：不再记录浏览历史（功能已整体去掉）。
                //   标题仍要同步给原生，供收藏时兜底取用。
                String t = v.getTitle();
                if (t != null && !t.isEmpty()) lastTitle = t;
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
                // ★ v1.9.4：手指按在「我的书架」条目上时，长按菜单由 JS 全权负责
                //   （删除标记 / 取消收藏）。这里必须直接吞掉，否则会同时弹出
                //   WebView 原生的「全屏查看图片…」菜单 —— 用户看到两个弹窗。
                if (shelfTouchActive) return true;
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

    /**
     * ★ v1.10.0：主 WebView 与阅读器 WebView 的**公共** WebSettings。
     *
     * <p>抽出来是为了保证两个窗口行为完全一致（同 UA、同 textZoom、同缓存策略），
     * 否则用户在设置里改「电脑版 UA / 字号」时，只有主窗口生效，阅读器窗口会不同步
     * ——那正是「详情页正常、进阅读页字号变了」这类 Bug 的来源。
     *
     * <p>注意：{@code setSupportMultipleWindows} 与
     * {@code setJavaScriptCanOpenWindowsAutomatically} **不在这里**，
     * 只有主 WebView 需要（阅读器不需要开新窗口，见 {@link #setupWeb()}）。
     */
    @SuppressLint("SetJavaScriptEnabled")
    private void applyCommonSettings(WebSettings s) {
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
    }

    /**
     * ★ v1.10.0：初始化「阅读器窗口」{@link #rdWeb}。
     *
     * <p>它是**独立的一次浏览会话**，与主 WebView 共享 Cookie / 磁盘缓存，
     * 但拥有自己的历史栈 —— 这正是「返回即关窗」能成立的基础。
     *
     * <p>与主 WebView 的差别：
     * <ul>
     *   <li>不接管 {@code onCreateWindow}（阅读页不会开新窗口）</li>
     *   <li>不带 SwipeRefreshLayout（阅读页靠音量键/滚动翻动，下拉刷新会误触）</li>
     *   <li>注入脚本只跑阅读页相关的（readerJs + t2s + 清理），
     *       不跑书架/抽屉/封面采集那套（那些是主 WebView 的职责）</li>
     * </ul>
     */
    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    private void setupReaderWeb() {
        WebSettings s = rdWeb.getSettings();
        applyCommonSettings(s);
        // 阅读器不需要多窗口
        s.setSupportMultipleWindows(false);

        try {
            CookieManager.getInstance().setAcceptThirdPartyCookies(rdWeb, true);
        } catch (Throwable ignore) {
        }

        rdWeb.setBackgroundColor(getResources().getColor(R.color.bg));
        rdWeb.setHorizontalScrollBarEnabled(false);
        rdWeb.setVerticalScrollBarEnabled(true);
        // 阅读器是独立会话：同样需要 HipApp 桥（预加载 / 返回书籍 / 收藏 / 长按）
        rdWeb.addJavascriptInterface(new JsBridge(), "HipApp");

        rdWeb.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                String u = req.getUrl() != null ? req.getUrl().toString() : null;
                return handleReaderUrl(u);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String u) {
                return handleReaderUrl(u);
            }

            @Override
            public void onPageStarted(WebView v, String u, Bitmap f) {
                readerUrl = u;
            }

            @Override
            public void onPageFinished(WebView v, String u) {
                readerUrl = u;
                try {
                    progress.setVisibility(View.GONE);
                } catch (Throwable ignore) {
                }
                // 阅读器窗口里只注入阅读页需要的东西
                injectReaderWindow(u);
                String t = v.getTitle();
                if (t != null && !t.isEmpty()) lastTitle = t;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
                String u = req.getUrl() != null ? req.getUrl().toString() : "";
                if (u.isEmpty()) return null;
                if (prefs.adBlock() && isAd(u)) return empty();
                if (isPromoImg(u)) return empty();
                // 阅读器页也要拦广告脚本（GTM / 劫持配置不在阅读器域名上，但图库广告图在）
                return interceptRes(req);
            }
        });

        rdWeb.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int p) {
                try {
                    progress.setProgress(p);
                    if (p >= 100) {
                        ui.postDelayed(() -> progress.setVisibility(View.GONE), 300);
                    }
                } catch (Throwable ignore) {
                }
            }

            @Override
            public void onReceivedTitle(WebView v, String t) {
                if (t != null && !t.isEmpty()) setTitle(t);
            }
        });

        // 阅读器窗口同样支持长按菜单（图片另存 / 复制链接）
        rdWeb.setOnLongClickListener(v -> {
            try {
                WebView.HitTestResult r = rdWeb.getHitTestResult();
                if (r == null) return false;
                String extra = r.getExtra();
                if (extra == null || extra.isEmpty()) return false;
                int t = r.getType();
                if (t == WebView.HitTestResult.IMAGE_TYPE
                        || t == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                    showLongPressMenu(extra, t);
                    return true;
                }
            } catch (Throwable ignore) {
            }
            return false;
        });

        rdWeb.setWebContentsDebuggingEnabled(false);
    }

    /** 阅读器窗口的 URL 策略：站内放行、广告/推广吃掉、外链交给系统。 */
    private boolean handleReaderUrl(String u) {
        if (u == null || u.isEmpty()) return true;
        if (isAd(u)) return true;
        if (isPromo(u)) return true;
        String low = u.toLowerCase(Locale.US);
        if (low.startsWith("http://") || low.startsWith("https://")) {
            if (isInternal(u)) {
                if (isLoginPath(u)) return true;
                return false;   // 阅读器内部自由翻章
            }
            openExternal(u);
            return true;
        }
        return false;
    }

    // ==================== ★ v1.10.0：阅读器窗口 进入 / 退出 ====================

    /**
     * 这串 URL 是不是「真正的阅读器 / 章节页」？
     *
     * <p>覆盖站点三阶段结构里的后两段：
     * <ul>
     *   <li>中间跳转页 {@code m.hipmh.com/chapter/go?hid=...}</li>
     *   <li>真实阅读器 {@code reader.hipmh.top/chapter/<b64url>}</li>
     * </ul>
     * 注意 {@code /chapter/go} 必须也算 —— 它虽然只是中转，但最终一定会到阅读器，
     * 让它整个在阅读器窗口里跑完，主 WebView 才不会被污染。
     */
    private static boolean isReaderUrl(String u) {
        if (u == null) return false;
        return u.contains("/chapter/") || u.contains("/chapter/go");
    }

    /**
     * ★ v1.10.0：打开阅读器窗口并加载指定章节 URL。
     *
     * <p>这是「点进漫画开始阅读」的唯一入口 —— 相当于**新开一个浏览器窗口**。
     * 主 WebView 保持在详情页原地不动，因此：
     * <ul>
     *   <li>它的历史栈里不会混入任何阅读页 / 中间跳转页；</li>
     *   <li>关闭阅读器后，「再按一次返回」走的就是主 WebView 自己的历史
     *       （详情页 → 首页），问题「再返回又是阅读页」从根上消失。</li>
     * </ul>
     *
     * @param u 章节 URL（中间跳转页或真实阅读器页都可以）
     */
    private void enterReader(String u) {
        if (u == null || u.isEmpty()) return;
        try {
            readerOpen = true;
            readerUrl = u;
            // 切换期间先让主 WebView 的悬浮按钮让位，避免叠影
            hideFab();
            btnTop.setVisibility(View.GONE);
            applyImmersive(true);
            applyKeepScreen();

            rdWeb.setVisibility(View.VISIBLE);
            rdWeb.loadUrl(u);
            rdWeb.requestFocus();

            // 主 WebView 保持原样（不 load 任何东西）—— 这就是「窗外的世界没动」
            rbSync();
        } catch (Throwable ignore) {
        }
    }

    /**
     * ★ v1.10.0：关闭阅读器窗口。
     *
     * <p>做完三件事：
     * <ol>
     *   <li>把阅读器导航到空白页并隐藏 —— 不能只 setVisibility(GONE)，
     *       否则页面里的轮播/定时器仍在跑，白耗电；</li>
     *   <li>清掉 {@link #readerOpen} / {@link #readerUrl} 状态；</li>
     *   <li>把主 WebView 的 UI 状态（沉浸模式、悬浮按钮、下拉刷新）恢复回「非阅读」态。</li>
     * </ol>
     *
     * <p><b>全程不碰主 WebView 的 URL</b> —— 用户会看到详情页「原地」露出来，
     * 这正是「关窗」的视觉效果，而不是又一次跳转。
     */
    private void exitReader() {
        try {
            readerOpen = false;
            readerUrl = null;
            try {
                rdWeb.stopLoading();
            } catch (Throwable ignore) {
            }
            rdWeb.setVisibility(View.GONE);

            // 恢复主 WebView 的「非阅读」UI 态
            applyImmersive(false);
            swipe.setEnabled(true);
            showFab();
            applyKeepScreen();
            progress.setVisibility(View.GONE);

            // ★ v2.1.0：关窗时先让阅读窗补跑一次进度上报（__hipProg 幂等，
            //   已报过的章直接跳过；没报到的此刻同步读 DOM 补报），
            //   确保「最后一章」落库后再刷新详情页按钮。
            try {
                rdWeb.evaluateJavascript(
                        "try{window.__hipProg&&__hipProg();}catch(e){}", null);
            } catch (Throwable ignore) {
            }
            // ★ v2.0.0：关窗瞬间重跑详情页「继续阅读」改造 ——
            //   本轮读到的章节刚刚落库，主 WebView 还停在详情页原地，
            //   立即重算按钮文案，用户看到的「继续阅读」就是刚刚读完的那一章。
            try {
                injectDetailProgress();
            } catch (Throwable ignore) {
            }
        } catch (Throwable ignore) {
        }
    }

    /**
     * ★ v1.10.0：把「阅读器窗口是否打开」同步到主 WebView 的 UI 状态。
     *
     * <p>阅读器打开期间，主 WebView 仍然活着（停在详情页），但它在视觉上被 rdWeb 完全遮住，
     * 所以要把它的悬浮按钮 / 下拉刷新 / 进度条都收起来，避免：
     * <ul>
     *   <li>主 WebView 的浮动按钮「透」在阅读器上方（两个 WebView 同层叠加时的常见串味）；</li>
     *   <li>阅读器窗口里下拉时误触发主 WebView 的 SwipeRefreshLayout。</li>
     * </ul>
     */
    private void rbSync() {
        try {
            if (readerOpen) {
                swipe.setEnabled(false);
                btnTop.setVisibility(View.GONE);
                hideFab();
            } else {
                swipe.setEnabled(true);
                showFab();
            }
        } catch (Throwable ignore) {
        }
    }

    /**
     * 向阅读器窗口注入阅读页脚本（预加载图片、返回书籍按钮、繁转简、清理）。
     *
     * <p>只注入阅读页相关的那几支 —— 书架 / 抽屉 / 封面采集属于主 WebView 的职责，
     * 在阅读器里跑既没必要也会互相干扰（例如 injectDrawer 会去改导航栏，而阅读器没有导航栏）。
     */
    private void injectReaderWindow(String u) {
        try {
            if (!isReaderUrl(u)) return;
            injectReaderInto(rdWeb, u, true);
            injectT2sInto(rdWeb);
            injectCleanInto(rdWeb);
        } catch (Throwable ignore) {
        }
    }

    /**
     * ★ v1.10.0：请求收起「我的书架」抽屉。
     *
     * <p>复用站点**自己的**关闭逻辑：模拟点击 {@code button[data-navbar-sidebar-close]}
     * （实测该按钮在 {@code Sidebar.astro} 脚本里绑了 click → 关闭函数，
     * 关闭时站点会自己处理 aria-hidden / inert / translateX 与动画，
     * 并顺带把焦点还给「更多」按钮，比我们自己改样式干净得多）。
     *
     * <p>兜底：万一站点改版把那个按钮去掉了，就直接改 {@code #navbar-sidebar} 的
     * aria-hidden + 内联样式，保证「返回能收起抽屉」这个功能不失效。
     */
    private void closeDrawer() {
        if (web == null) return;
        String js = "(function(){try{"
                + "var c=document.querySelector('[data-navbar-sidebar-close]');"
                + "if(c&&typeof c.click==='function'){c.click();}"
                + "else{var s=document.getElementById('navbar-sidebar');"
                + "if(s){var e=s.querySelector('[data-navbar-sidebar-content]');"
                + "var o=s.querySelector('[data-navbar-sidebar-overlay]');"
                + "if(o)o.style.opacity='0';"
                + "s.style.opacity='0';"
                + "if(e)e.style.transform='translateX(100%)';"
                + "s.style.pointerEvents='none';"
                + "s.setAttribute('aria-hidden','true');"
                + "s.setAttribute('inert','');"
                + "document.body.style.overflow='';}}"
                + "}catch(e){}})();";
        try {
            web.evaluateJavascript(js, null);
        } catch (Throwable ignore) {
        }
        // 立刻翻转本地状态，避免连按两次返回时第二次又走「关抽屉」分支
        drawerOpen = false;
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
            // ★ v1.10.0：③ 「点进漫画开始阅读」= 新开阅读器窗口。
            //   在**主 WebView 发起导航之前**把它截胡，交给独立的 rdWeb 去加载，
            //   主 WebView 因此停在详情页原地不动、历史栈永远干净。
            //   —— 这是「返回详情页后，再返回又回到阅读页」的根治手段。
            //   注意顺序：必须在下面的 isInternal 放行**之前**判断，否则就 load 进主窗口了。
            if (isReaderUrl(u)) {
                enterReader(u);
                return true;
            }
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
            // ★ v1.8：作品详情页右上角站点原生的「爱心/收藏」按钮（点击要登录），
            //   我们已自带本地星标收藏，这个入口直接删掉（服务端删，避免首屏闪现）。
            cleaned = stripFavButtons(cleaned);
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
     * ★ v1.8：删除作品详情页右上角的站点原生「爱心/收藏」按钮。
     *
     * <p>站点结构（2026-09-21 抓取真实详情页）：
     * <pre>
     * 手机端：&lt;button type="button" id="mobile-favorite-btn"
     *              aria-label="喜歡" data-action="like" data-source-key="15031"&gt;…&lt;/button&gt;
     * 桌面端：&lt;div id="favorite-btn" title="收藏" data-source-key="15031"&gt;…&lt;/div&gt;
     * </pre>
     *
     * <p>这个入口点击后要求登录站点账号，而我们已经自带本地星标收藏，所以整体去掉。
     * 在<b>主文档 HTML 层</b>删除（而不是只靠客户端 CSS），避免首屏闪现，
     * 也避开 Astro 软导航后客户端清理不及时的问题。
     */
    private static String stripFavButtons(String html) {
        if (html == null || html.isEmpty()) return html;
        try {
            String out = html;
            for (int round = 0; round < 6; round++) {
                int at = -1;
                String open = null;
                String close = null;
                for (String idv : new String[]{"mobile-favorite-btn", "favorite-btn"}) {
                    int k = out.indexOf("id=\"" + idv + "\"");
                    if (k < 0) continue;
                    int lt = out.lastIndexOf("<button", k);
                    String tag = "button";
                    if (lt < 0 || out.indexOf('>', lt) < k) {
                        lt = out.lastIndexOf("<div", k);
                        tag = "div";
                    }
                    if (lt < 0) continue;
                    int gt = out.indexOf('>', lt);
                    if (gt < 0 || gt < k) continue;
                    if (at < 0 || lt < at) {
                        at = lt;
                        open = "<" + tag;
                        close = "</" + tag;
                    }
                }
                if (at < 0 || open == null) break;

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
                    if (out.startsWith(close, lt)) {
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
                    if (out.startsWith(open, lt)) {
                        int gt = out.indexOf('>', lt);
                        if (gt < 0) break;
                        if (!out.substring(lt, gt + 1).endsWith("/>")) depth++;
                        i = gt + 1;
                        continue;
                    }
                    i = lt + 1;
                }
                if (end < 0) break;
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
        // ★ v1.7：不再放行 /history —— 浏览记录功能已整体去掉，
        //   站点那个阅读记录页在本 App 内不再有入口（点了历史里的作品走的是 /works/，不受影响）。
        //   搜索页仍然保留。
        if (p.equals("/search") || p.startsWith("/search/")) return false;
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

    /**
     * ★ v1.10.0：根据**主 WebView** 当前 URL 切换「阅读态」UI。
     *
     * <p>注意：主 WebView 现在永远不会加载阅读页（阅读器被拆到独立的 rdWeb，
     * 见 {@link #enterReader(String)}），所以这里的 {@code r} 基本恒为 false。
     * 但它仍要保留 —— 万一某次导航绕过了 {@code shouldOverrideUrlLoading}
     * （例如站点自己 {@code location.replace} 到 /chapter/），也要能正确进入阅读态。
     *
     * <p><b>关键防护</b>：{@code readerOpen} 为 true 时**直接返回**，不能把
     * 沉浸模式 / 下拉刷新 / 悬浮按钮恢复回来 —— 否则主 WebView 后台加载完成一次，
     * 就会把阅读器窗口的沉浸态顶掉（表现为「阅读时代状态栏又冒出来」）。
     */
    private void applyMode(String u) {
        if (readerOpen) return;      // 阅读器窗口接管期间，主 WebView 不碰 UI 态
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
     * 3) 登录注册入口 + 右上角/侧栏的「我的書架」（那条是 xipmh.com 外链）。
     *    ★ v1.7：右上角不再自建任何按钮，改为把站点原生「更多」按钮劫持成书架入口。
     *
     * 注意：站点自带的「我的書架 / 個人中心 / 登出」一律隐藏（那是外链同步书架，不是本 App 的），
     * 本 App 的书架是本地收藏，入口是右上角那个「更多」图标（见 bindMore）。
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
        // ★ v1.8：作品详情页右上角的「爱心/收藏」图标（站点原生，点击要登录，我们已自带星标收藏）
        //   手机端：<button id="mobile-favorite-btn" aria-label="喜歡" data-action="like" data-source-key="..">
        //   桌面端：<div id="favorite-btn" title="收藏" class="hidden md:flex">
        //   两个都收掉，保证任何宽度下都不露出站点收藏入口。
        sb.append("css+='#mobile-favorite-btn,#favorite-btn,';");
        sb.append("css+='button[aria-label=\"喜歡\"],button[aria-label=\"喜欢\"],button[data-action=\"like\"],';");
        sb.append("css+='button[aria-label=\"收藏\"],button[aria-label=\"我的收藏\"],';");
        // ★ v1.7：清理旧版本注入的「搜索 / 记录 / 书架」文字按钮节点（升级安装后的残留）
        sb.append("css+='#__hipBox,#__hipShelf,#__hipSearch,';");
        sb.append("css+='a[href$=\"/login\"],a[href$=\"/register\"],a[href$=\"/signin\"],a[href$=\"/signup\"]';");
        sb.append("css+='{display:none!important;}';");
        // 图库推广卡的【祖先容器】也要一起收掉，否则只隐藏 <a> 会留下空白广告位
        sb.append("css+='div:has(> div > a[href*=\"g-mh.com\"]),div:has(> div > a[href*=\"18gallery.com\"]){display:none!important;}';");
        sb.append("var st=document.createElement('style');st.id='__hipCss';st.textContent=css;document.head.appendChild(st);");
        sb.append("var LOGINP=['/login','/register','/signin','/signup','/user/profile','/account','/dashboard'];function isLoginHref(h){if(!h)return 0;h=String(h).toLowerCase();var i=h.indexOf('?');if(i>0)h=h.substring(0,i);");
        sb.append("if(h.indexOf('/works/')>=0)return 0;var s=h.indexOf('//');if(s>=0){var e=h.indexOf('/',s+2);h=e>=0?h.substring(e):'';}if(h==='/history'||h.indexOf('/history/')===0)return 0;if(h==='/search'||h.indexOf('/search/')===0)return 0;");
        sb.append("for(var i2=0;i2<LOGINP.length;i2++){var x=LOGINP[i2];if(h===x||h.indexOf(x+'/')===0)return 1;}return 0;}var LB=/^我的書架$|^我的书架$|^個人中心$|^个人中心$|^登出$|^退出$|^登入$|^登录$|^登錄$|^註冊$|^注册$/;var GK=/图库|圖庫|免费高清|免費高清/;");
        sb.append("window.__hipClass=function(el){try{if(!el||!el.getAttribute)return 0;var h=el.getAttribute('href')||'';if(h.indexOf('/works/')>=0)return 3;if(/g-mh\\.com|18gallery\\.com/i.test(h))return 2;var t=(el.textContent||'').trim();");
        // ★ v1.8：详情页右上角站点原生的「爱心/收藏」按钮 —— 归为「要隐藏」（1），
        //   GUARD_JS 据此同时拦掉点击，避免弹站点登录。
        sb.append("var eid=el.id||'';if(eid==='mobile-favorite-btn'||eid==='favorite-btn')return 1;"
                + "if((el.getAttribute('data-action')||'')==='like')return 1;");
        sb.append("var arl=el.getAttribute('aria-label')||'';if(/^(喜歡|喜欢|收藏|我的收藏)$/.test(arl.trim()))return 1;");
        sb.append("if(t&&t.length<=24&&GK.test(t))return 2;var im=el.querySelector?el.querySelector('img'):null;if(im){var al2=im.getAttribute('alt')||'';var sr=im.getAttribute('src')||'';if(/^G-MH$|^18GAL$/.test(al2))return 2;");
        sb.append("if(/g-mh-900|18gallery-1/i.test(sr))return 2;}if(isLoginHref(h))return 1;var ar=el.getAttribute('aria-label')||'';if(ar&&LB.test(ar.trim()))return 1;if(t&&t.length<=8&&LB.test(t))return 1;");
        // 注意：__hipClass 到这一行为止，必须补上 "}catch(e){}return 0;};" 收尾，
        // 否则后面的语句会被算进 __hipClass 的函数体里（v1.7 曾因此导致语法错误）。
        sb.append("}catch(e){}return 0;};");
        // ---- ★ v1.8：不再劫持「更多」按钮 ----
        //   v1.7 试过把站点原生「更多」按钮的点击直接换成打开原生书架列表，但实测无效：
        //   捕获阶段抢事件与站点自身的开合时序互相干扰，抽屉照旧弹出来（用户反馈
        //   「跟以前一模一样，看不出变化」）。
        //   现在改为「原地改造抽屉内容」：保留站点的开合交互，只在抽屉里把
        //   「閱讀記錄」换成我们的本地书架 —— 见 DRAWER_JS。
        sb.append("var last=0;function scan(){try{var now=Date.now();");
        sb.append("if(now-last<800)return;last=now;");
        //   右上角 <a href="https://m.xipmh.com/dashboard?lang=zh"> 与
        //   侧栏底部同款 <a> —— 一并隐藏，并把它所在的「空的图标位/按钮行」也收掉，
        //   否则会出现「按钮没了但位置留白」或第二个书架漏网。
        sb.append("var sh=document.querySelectorAll('a[href*=\"xipmh.com\"],a[href*=\"dashboard\"],a[href*=\"shelf\"]');");
        sb.append("for(var k=0;k<sh.length;k++){var e2=sh[k];if(e2.getAttribute('data-hip'))continue;hide(e2);e2.__hipShown=1;");
        // 若其父容器只剩这个链接（没有其它可点击子元素），把父容器一起隐藏，去掉留白
        sb.append("var pc=e2.parentElement;if(pc&&pc.querySelectorAll&&pc.querySelectorAll('a,button').length<=1){");
        sb.append("if(!pc.getAttribute('data-hip'))hide(pc);}}");
        // ★ v1.8：详情页右上角站点原生的「爱心/收藏」按钮（点击要登录站点账号）。
        //   服务端 stripFavButtons 已从主文档里删掉它；但 Astro 软导航会用
        //   innerHTML 重新灌入详情页 HTML（绕过 shouldInterceptRequest），
        //   所以这里再补一层：既 hide 也直接从 DOM 摘掉。
        sb.append("var fb=document.querySelectorAll('#mobile-favorite-btn,#favorite-btn,"
                + "button[data-action=\"like\"],button[aria-label=\"喜歡\"],"
                + "button[aria-label=\"喜欢\"]');");
        sb.append("for(var f2=0;f2<fb.length;f2++){var fe=fb[f2];hide(fe);"
                + "try{if(fe.parentNode)fe.parentNode.removeChild(fe);}catch(x){}}");
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
                + "setInterval(scan,4000);"
                // ★ v1.7：旧版本的「搜索 / 书架」文字按钮若还在页面上（用户升级安装前的残留），
                //   连同其样式一起收掉。原生「更多」按钮已经接管为书架入口。
                + "try{var ob=document.getElementById('__hipBox');if(ob)hide(ob);"
                + "var osx=document.getElementById('__hipShelf');if(osx)hide(osx);"
                + "var ose=document.getElementById('__hipSearch');if(ose)hide(ose);}catch(e){}"
                + "})();");
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

    /** ★ v1.8：把站点右上角「更多」弹出的「閱讀記錄」抽屉改造成本地书架 */
    private void injectDrawer() {
        web.evaluateJavascript(DRAWER_JS, null);
        warmCovers();
    }

    /**
     * ★ v1.9.1：后台把书架里所有作品的封面预先抓进 {@link ResCache}。
     *
     * <p>抽屉和书架页里的 {@code <img src="cover...">} 由 WebView / ListActivity 自己发请求，
     * 若此时磁盘缓存是冷的，用户会先看到一片空框（「不显示封面」的观感来源之一）。
     * 这里在进入页面时就把封面灌进磁盘缓存，之后两边都是命中即显。
     *
     * <p>同一个 URL 的并发下载由 {@code ResCache} 的 inflight 去重合并，重复调用无副作用。
     */
    private void warmCovers() {
        try {
            final List<Store.Item> its = store.list("fav");
            if (its == null || its.isEmpty()) return;
            if (coverPool == null) {
                coverPool = Executors.newFixedThreadPool(3);
            }
            for (final Store.Item it : its) {
                final String cv = it.cover;
                if (cv == null || cv.isEmpty()) continue;
                if (ResCache.get(cv) != null) continue;
                try {
                    coverPool.execute(() -> {
                        try {
                            ResCache.fetch(cv, null, Prefs.HOME);
                        } catch (Throwable ignore) {
                        }
                    });
                } catch (Throwable ignore) {
                }
            }
        } catch (Throwable ignore) {
        }
    }

    /**
     * ★ v1.8：站点侧边抽屉 → 本地书架。
     *
     * <p>关键事实（2026-09-21 抓线上站点核对）：
     * <ul>
     *   <li>右上角「更多」按钮 {@code button[data-navbar-more-trigger]} 点击后，
     *       展开的是 {@code #navbar-sidebar}，标题写死为「閱讀記錄」，底部两个按钮：
     *       「我的書架」（外链 {@code m.xipmh.com/dashboard}，需登录）与「全部記錄」（{@code /history}）。
     *       ← 用户看到的「历史记录」就是它。</li>
     *   <li>{@code #navbar-sidebar-history-empty} 里是「暫無閱讀記錄」空态（未登录时可见）。</li>
     * </ul>
     *
     * <p>改造方式：**原地改造**，不动站点的开合交互（只做视觉与内容替换），
     * 因此不会像 v1.7 那样劫持点击却因时序问题失效：
     * <ol>
     *   <li>标题 {@code h2} 改「我的书架」；</li>
     *   <li>隐藏站点自己的历史列表 / loading / 空态 / 底部按钮行；</li>
     *   <li>注入我们自己的书架列表容器，内容由 {@code HipApp.shelfJson()} 提供；</li>
     *   <li>监听抽屉 aria-hidden 变化，每次打开时刷新一次列表。</li>
     * </ol>
     */
    private static final String DRAWER_JS = drawerJs();

    private static String drawerJs() {
        StringBuilder b = new StringBuilder();
        // ---------- 1) 样式（只注入一次） ----------
        b.append("(function(){");
        b.append("if(!document.getElementById('__hipDrawerCss')){");
        b.append("var st=document.createElement('style');st.id='__hipDrawerCss';");
        b.append("st.textContent='"
                // ★ v1.9.3：书架「挤在中间 / 上半空白」的真正解法。
                //
                //   实测抽屉 HTML 结构（m.hipmh.com 首页抓包，2026-09）：
                //   <div id="navbar-sidebar" … aria-hidden inert>
                //     <div data-navbar-sidebar-overlay …></div>
                //     <div data-navbar-sidebar-content class="absolute right-0 top-0 bottom-0
                //          w-full max-w-sm bg-background … flex flex-col h-full z-10">
                //       ├─ <div class="flex items-center justify-between px-5 py-4
                //       │        border-b … flex-shrink-0">   ← 标题栏（要保留）
                //       └─ <div class="flex-1 overflow-y-auto min-h-0 flex flex-col"> ← ★ 正文
                //             ├─ #navbar-sidebar-history-list    (… hidden)
                //             ├─ #navbar-sidebar-history-loading (hidden …)
                //             └─ #navbar-sidebar-history-empty   (flex … min-h-[200px])
                //
                //   病因：只把三个内层元素 display:none 是不够的 —— 外层那个 `.flex-1`
                //   依然占满剩余高度，我们 append 进去的 #__hipShelfWrap 只能落在它下面，
                //   于是表现为「书架挤在下半屏、上面一大片空白」。
                //
                //   v1.9.2 的 layoutFix 思路正确但实现有隐患：用 while 从历史元素向上找
                //   “host 的直接子节点”，若元素不在 host 下会一路爬到 <html> 并把它
                //   display:none（整页消失）。v1.9.3 改为**只遍历 host 的直接子元素**
                //   逐个判断，绝不越界；同时把标题栏之外的所有非书架直接子层清出布局。
                + "[data-navbar-sidebar-content]{display:flex!important;flex-direction:column!important;"
                + "justify-content:flex-start!important;align-items:stretch!important;}"
                + "#__hipShelfWrap{display:flex!important;flex-direction:column;"
                + "justify-content:flex-start;align-items:stretch;"
                + "flex:1 1 auto!important;align-self:stretch!important;"
                + "min-height:0!important;height:auto!important;"
                + "overflow-y:auto;overflow-x:hidden;"
                + "margin:0!important;padding:0!important;}"
                + "#__hipShelfWrap .__hipItm{display:flex;align-items:center;gap:10px;"
                + "padding:11px 20px;cursor:pointer;text-decoration:none;color:inherit;"
                + "border-bottom:1px solid rgba(128,128,128,.14);"
                // ★ v1.9.4：禁掉长按的系统「呼叫」行为和文本/图片选中，减少原生弹窗来源
                + "-webkit-touch-callout:none;-webkit-user-select:none;user-select:none;}"
                + "#__hipShelfWrap .__hipItm:active{background:rgba(128,128,128,.18);}"
                + "#__hipShelfWrap .__hipMark{flex:0 0 auto;font-size:15px;line-height:1;"
                + "width:14px;text-align:center;}"
                + "#__hipShelfWrap .__hipTt{flex:1 1 auto;min-width:0;font-size:14px;"
                + "line-height:1.35;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;}"
                // ★ v1.9.1：封面缩略图（44×60），取不到封面时是灰底占位块
                // ★ v1.9.4：封面加 pointer-events:none —— 长按时命中点落到条目 <a> 上，
                //   不会命中 <img>（原生长按对 IMAGE_TYPE 会弹「全屏查看图片」）。
                + "#__hipShelfWrap .__hipCv{flex:0 0 auto;width:44px;height:60px;"
                + "border-radius:6px;object-fit:cover;display:block;"
                + "background:rgba(128,128,128,.22);pointer-events:none;"
                + "-webkit-touch-callout:none;}"
                + "#__hipShelfWrap .__hipEmpty{padding:44px 22px;text-align:center;"
                + "font-size:13px;line-height:1.7;opacity:.6;}"
                + "#navbar-sidebar-history-list,#navbar-sidebar-history-loading,"
                + "#navbar-sidebar-history-empty{display:none!important;}'"
                // 由 layoutFix 给「该清出布局的直接子层」打上这个属性标记
                + "+'[data-hip-hide]{display:none!important;flex:0 0 auto!important;"
                + "height:0!important;min-height:0!important;overflow:hidden!important;}';");
        b.append("document.head.appendChild(st);}");
        // ---------- 2) 工具 ----------
        b.append("function hD(e){try{e.style.setProperty('display','none','important');}"
                + "catch(x){}}");
        b.append("function sb(){try{return document.querySelector('#navbar-sidebar');}"
                + "catch(e){return null;}}");
        // 抽屉底部「我的書架」(xipmh 外链) /「全部記錄」(/history) 整行收掉
        b.append("function killSite(){try{var s=sb();if(!s)return;");
        b.append("var as=s.querySelectorAll('a');");
        b.append("for(var k=0;k<as.length;k++){var a=as[k];var hr=a.getAttribute('href')||'';");
        b.append("if(/xipmh\\.com|\\/dashboard/i.test(hr)||hr==='/history'"
                + "||/^\\/history[\\/?]/.test(hr)){");
        b.append("hD(a);var row=a.parentNode;if(row&&row.parentNode){hD(row.parentNode);}}}");
        b.append("}catch(e){}}");
        // ★ v1.9.3：确保书架从抽屉「标题栏下方」开始排，而不是被挤到中间。
        //   实现要点（与 v1.9.2 的关键区别：**只遍历 host 的直接子元素**，
        //   不再用 while 从内层元素向上爬 —— 那样一旦元素不在 host 下就会爬到
        //   <html> 并把它 display:none，导致整页空白）：
        //     ① 先给站点三个历史元素本身打隐藏标记；
        //     ② 清掉 host 直接子元素里「不是标题栏、也不是 wrap」的层
        //        （= 那个 flex-1 的站点历史容器，它才是把书架挤到中间的元凶）；
        //     ③ 把 wrap 移到「标题栏之后、剩余内容之前」—— 标题栏必须留在最上面。
        b.append("function layoutFix(){try{var s=sb();if(!s)return;");
        b.append("var host=s.querySelector('[data-navbar-sidebar-content]');if(!host)return;");
        b.append("var wrap=document.getElementById('__hipShelfWrap');");
        // ① 站点历史元素本身（即使在别的层里，也先局部隐藏）
        b.append("var ids=['navbar-sidebar-history-list','navbar-sidebar-history-loading',"
                + "'navbar-sidebar-history-empty'];");
        b.append("for(var i=0;i<ids.length;i++){var el=document.getElementById(ids[i]);");
        b.append("if(el)el.setAttribute('data-hip-hide','1');}");
        // ② 清理 host 的直接子层：非标题栏、非 wrap 的 div 一律清出布局
        //    标题栏判定 = 内含 h1/h2/h3 的 div（站点标题栏里就是 <h2>閱讀記錄</h2>）
        b.append("try{var kids=host.children;");
        b.append("for(var j=kids.length-1;j>=0;j--){var k=kids[j];if(!k||k===wrap)continue;"
                + "if((k.tagName||'').toLowerCase()!=='div')continue;"
                + "if(k.querySelector&&k.querySelector('h1,h2,h3'))continue;"
                + "k.setAttribute('data-hip-hide','1');}}catch(x){}");
        // ③ 定位「标题栏」这个锚点，把 wrap 插在它后面
        b.append("if(wrap){try{var anchor=null;var ks=host.children;");
        b.append("for(var m=0;m<ks.length;m++){var c=ks[m];if(c===wrap)continue;"
                + "if(c.querySelector&&c.querySelector('h1,h2,h3')){anchor=c;break;}}");
        b.append("if(anchor){");
        // 插到 anchor 的下一个兄弟之前；若 anchor 已是最后一个则 append
        b.append("var nx=anchor.nextSibling;"
                + "if(nx){if(nx!==wrap)host.insertBefore(wrap,nx);}"
                + "else if(host.lastChild!==wrap)host.appendChild(wrap);");
        b.append("}else if(host.firstChild!==wrap){host.insertBefore(wrap,host.firstChild);}}"
                + "catch(x){try{host.appendChild(wrap);}catch(y){}}}");
        b.append("}catch(e){}}");
        // 标题：站点的「閱讀記錄」→「我的书架」
        b.append("function fixTitle(){try{var s=sb();if(!s)return;");
        b.append("var hs=s.querySelectorAll('h2,h3');");
        b.append("for(var i=0;i<hs.length;i++){var h=hs[i];");
        b.append("var t=(h.textContent||'').replace(/\\s+/g,'');");
        b.append("if(t&&/記錄|记录|歷史|历史/.test(t)){h.setAttribute('data-hip','1');");
        b.append("h.textContent='我的书架';}}}catch(e){}}");
        // ---------- 3) 书架子列表渲染 ----------
        b.append("function esc(v){return String(v==null?'':v)"
                + ".replace(/&/g,'&amp;').replace(/</g,'&lt;')"
                + ".replace(/>/g,'&gt;').replace(/\"/g,'&quot;');}");
        b.append("function render(){try{var s=sb();if(!s)return;");
        b.append("var host=s.querySelector('[data-navbar-sidebar-content]');if(!host)return;");
        b.append("var wrap=document.getElementById('__hipShelfWrap');");
        b.append("if(!wrap){wrap=document.createElement('div');wrap.id='__hipShelfWrap';");
        // ★ v1.9.3：先 append 占位，随后立刻 layoutFix() 会把它挪到标题栏之后 ——
        //   直接 append 会落在被隐藏的站点历史层后面，位置不对。
        b.append("wrap.setAttribute('data-hip','1');host.appendChild(wrap);}");
        b.append("try{layoutFix();}catch(x){}");
        b.append("var arr=[];try{var j=(window.HipApp&&HipApp.shelfJson)"
                + "?HipApp.shelfJson():'[]';arr=JSON.parse(j||'[]');}catch(e){arr=[];}");
        b.append("if(!arr||!arr.length){wrap.innerHTML="
                + "'<div class=\"__hipEmpty\">书架还是空的<br>"
                + "在漫画详情页或阅读页点右下角的星标即可加入</div>';return;}");
        b.append("var html='';for(var i=0;i<arr.length;i++){var it=arr[i]||{};");
        b.append("var stt=it.st|0;var mk='○';");
        b.append("if(stt===2){mk='●';}else if(stt===1){mk='◐';}");
        b.append("var ti=esc(it.title||it.url||'');var u=esc(it.url||'');");
        b.append("var cv=esc(it.cover||'');");
        b.append("html+='<a class=\"__hipItm\" href=\"'+u+'\" data-hip=\"1\" "
                + "data-hipurl=\"'+u+'\">';");
        b.append("html+='<span class=\"__hipMark\">'+mk+'</span>';");
        // ★ v1.9.1：条目左侧加封面缩略图（拿不到就留个占位块，保证行高一致）
        b.append("if(cv){html+='<img class=\"__hipCv\" src=\"'+cv+'\" alt=\"\" "
                + "loading=\"lazy\" decoding=\"async\">';}"
                + "else{html+='<span class=\"__hipCv __hipCvPh\"></span>';}");
        b.append("html+='<span class=\"__hipTt\">'+ti+'</span>';");
        // ★ v1.9.1：按用户要求去掉行尾的「×」删除按钮 —— 删除统一走长按菜单。
        b.append("html+='</a>';}");
        b.append("wrap.innerHTML=html;}catch(e){}}");
        // ---------- 4) 交互 ----------
        // ★ 监听挂在 wrap 自身上（innerHTML 替换不会丢监听），
        //   并且必须在 render() 之后调用，否则首次打开时会因容器尚未创建而漏绑定。
        b.append("function bind(){try{var wrap=document.getElementById('__hipShelfWrap');");
        b.append("if(!wrap||wrap.__hipB)return;wrap.__hipB=1;");
        b.append("wrap.addEventListener('click',function(e){var t=e.target;try{");
        // ★ v1.9.1：防双弹窗 —— 长按菜单弹出后，手指抬起仍会派发一次 click，
        //   用户若在菜单外再点一下就会「弹第二个窗」。长按后 700ms 内吞掉所有点击。
        b.append("if(Date.now()-(wrap.__hipLp||0)<700){e.preventDefault();"
                + "e.stopPropagation();return;}");
        b.append("var itm=(t&&t.closest)?t.closest('.__hipItm'):null;");
        b.append("if(itm){e.preventDefault();e.stopPropagation();");
        b.append("var u2=itm.getAttribute('data-hipurl');");
        b.append("if(u2&&window.HipApp&&HipApp.openUrl)HipApp.openUrl(u2);}");
        b.append("}catch(x){}},false);");
        // 长按 500ms -> 原生标记菜单
        b.append("var tm=null,cur=null;");
        // ★ v1.9.4：按下瞬间就告诉原生「手指在书架条目上」。
        //   必须用 touchstart（不是等 500ms 定时器）—— 因为 WebView 原生长按是
        //   ~500ms 触发的，两者几乎同时；提前上报才能保证原生长按回调读到 true 并吞掉，
        //   否则「全屏查看图片」菜单会和我们自己的书架菜单一起弹出来（用户看到两个窗）。
        b.append("function st_(e){try{var t=e.target;");
        b.append("var itm=(t&&t.closest)?t.closest('.__hipItm'):null;if(!itm)return;");
        b.append("cur=itm.getAttribute('data-hipurl');");
        b.append("try{if(window.HipApp&&HipApp.shelfTouch)HipApp.shelfTouch(true);}catch(y){}");
        b.append("if(tm){clearTimeout(tm);tm=null;}");
        b.append("tm=setTimeout(function(){tm=null;if(!cur)return;");
        // ★ v1.9.1：记下「刚长按过」，供上面的捕获阶段 click 拦截使用（防双弹窗）
        b.append("try{var w=document.getElementById('__hipShelfWrap');"
                + "if(w)w.__hipLp=Date.now();}catch(x){}");
        b.append("if(window.HipApp&&HipApp.favMenu)HipApp.favMenu(cur);},500);}catch(x){}}");
        b.append("function cx(){if(tm){clearTimeout(tm);tm=null;}");
        b.append("try{if(window.HipApp&&HipApp.shelfTouch)HipApp.shelfTouch(false);}catch(y){}}");
        b.append("wrap.addEventListener('touchstart',st_,{passive:true});");
        b.append("wrap.addEventListener('touchend',cx,false);");
        // ★ v1.9.4：touchmove 只在「明显位移」（>12px，判定为滚动而非长按）时才解除标记，
        //   否则手指长按时的微小抖动会提前把 shelfTouch(false) 上报给原生，
        //   原生长按就会抢在我们前面弹「全屏查看图片」。
        b.append("var mx0=0,my0=0,mHit=0;");
        b.append("wrap.addEventListener('touchstart',function(e){try{"
                + "var p=e.touches&&e.touches[0];if(!p)return;"
                + "mx0=p.clientX;my0=p.clientY;"
                + "var t=e.target;var itm=(t&&t.closest)?t.closest('.__hipItm'):null;"
                + "mHit=itm?1:0;}catch(y){}},{passive:true});");
        b.append("wrap.addEventListener('touchmove',function(e){try{"
                + "if(!mHit)return;var p=e.touches&&e.touches[0];if(!p)return;"
                + "var dx=Math.abs(p.clientX-mx0),dy=Math.abs(p.clientY-my0);"
                + "if(dx>12||dy>12){mHit=0;cx();}}catch(y){}},{passive:true});");
        b.append("wrap.addEventListener('touchcancel',cx,false);");
        // ★ v1.9.4：兜底解除标记 —— 万一 touchend/cancel 都没收到（被原生菜单抢走等），
        //   1.5s 后自动复位，避免标记卡在 true 让全站长按永久失效。
        b.append("var rst=null;");
        b.append("function arm(){try{clearTimeout(rst);}catch(y){}"
                + "rst=setTimeout(function(){try{"
                + "if(window.HipApp&&HipApp.shelfTouch)HipApp.shelfTouch(false);}catch(y){}},1500);}");
        b.append("wrap.addEventListener('touchstart',arm,{passive:true});");
        b.append("}catch(e){}}");
        // ---------- 5) 抽屉打开时刷新 ----------
        b.append("var lastOpen=false;");
        // ★ 顺序：先 render()（可能新建容器）再 bind()；之后每个 tick 都补一次 bind() 兜底。
        b.append("function tick(){try{var s=sb();if(!s)return;");
        b.append("killSite();fixTitle();");
        b.append("var open=(s.getAttribute('aria-hidden')==='false');");
        // ★ v1.10.0：把开合状态上报原生 —— 系统返回键要靠它「先收起抽屉」。
        //   只在**状态变化**时上报，避免每 600ms 一次的无谓跨线程调用。
        b.append("if(open!==window.__hipDrawerOpen){window.__hipDrawerOpen=open;");
        b.append("try{if(window.HipApp&&HipApp.drawerState)HipApp.drawerState(open);}catch(y){}}");
        b.append("if(open&&!lastOpen){render();}");
        // ★ v1.9.2：抽屉打开期间持续校正布局（隐藏站点历史容器 + 把书架置顶），
        //   因为站点会在打开动画/异步水合后重新排布子节点，只在 render 时做一次会失效。
        b.append("layoutFix();");
        b.append("bind();lastOpen=open;}catch(e){}}");
        b.append("tick();setInterval(tick,600);");
        // ★ v1.9.2：抽屉打开的一瞬间也立刻校正一次（不等 600ms tick）
        b.append("if(!window.__hipLayoutObs){window.__hipLayoutObs=1;");
        b.append("try{var s0=sb();if(s0){new MutationObserver(function(){");
        b.append("var s2=sb();if(s2&&s2.getAttribute('aria-hidden')==='false')layoutFix();");
        b.append("}).observe(s0,{attributes:true,attributeFilter:['aria-hidden','class']});}}catch(e){}}");
        // ★ v1.9.1：支持回调式重绘 —— 原生改完状态后 render 完再回调，
        //   避免「改完立刻 render 时数据还没落库」导致列表不刷新。
        b.append("window.__hipDrawerRender=function(cb){try{lastOpen=true;render();layoutFix();bind();"
                + "if(typeof cb==='function'){try{cb();}catch(x){}}}catch(e){}};");
        b.append("['astro:after-swap','astro:page-load','popstate','hashchange']"
                + ".forEach(function(ev){try{document.addEventListener(ev,function(){"
                // ★ v1.10.0：软导航换了整棵 body，旧抽屉节点已不存在 → 立刻上报「已关闭」，
                //   否则原生那边 drawerOpen 会一直停在 true，导致返回键永远只做「关抽屉」。
                + "window.__hipDrawerOpen=false;"
                + "try{if(window.HipApp&&HipApp.drawerState)HipApp.drawerState(false);}catch(y){}"
                + "lastOpen=false;setTimeout(tick,60);setTimeout(tick,400);},false);}"
                + "catch(x){}});");
        b.append("})();");
        return b.toString();
    }

    /** ★ v1.6：作品详情页 / 阅读器页的「收藏」悬浮按钮 */
    private void injectFav() {
        web.evaluateJavascript(FAV_JS, null);
    }

    /**
     * ★ v1.6：收藏按钮注入脚本。
     *
     * <p>只在 {@code /works/}（作品详情）与 {@code /chapter/}（阅读器）出现：
     * <ul>
     *   <li>阅读器：排在「返回书籍」按钮下方（top 62px）；详情页：贴右上角（top 14px）</li>
     *   <li>默认半透明 + 闲置淡出，滚动/触摸时短暂显现，不挡漫画</li>
     *   <li>已收藏时星星实心 + 琥珀色底</li>
     *   <li>作品身份（url）由原生 {@code favKeyFor()} 归一化，
     *       详情页与阅读器收藏的是同一条记录</li>
     * </ul>
     * 同样不带一次性守卫：Astro 软导航后靠事件 + MutationObserver 重建。
     */
    private static final String FAV_JS =
            "(function(){"
                    + "if(!document.getElementById('__hipFavCss')){var st=document.createElement('style');"
                    + "st.id='__hipFavCss';"
                    + "st.textContent='#__hipFavBtn{position:fixed;right:12px;z-index:2147483001;'"
                    + "+'width:38px;height:38px;border-radius:50%;display:flex;align-items:center;'"
                    + "+'justify-content:center;background:rgba(20,22,28,.30);color:#fff;border:0;'"
                    + "+'padding:0;cursor:pointer;opacity:.34;'"
                    + "+'transition:opacity .3s ease,background .3s ease;'"
                    + "+'-webkit-backdrop-filter:blur(2px);backdrop-filter:blur(2px);}'"
                    + "+'#__hipFavBtn.__act{opacity:1;background:rgba(20,22,28,.66);}'"
                    + "+'#__hipFavBtn.__on{background:rgba(250,204,21,.50);color:#fff8dc;opacity:.55;}'"
                    + "+'#__hipFavBtn.__on.__act{background:rgba(250,204,21,.95);color:#3a2c00;opacity:1;}';"
                    + "document.head.appendChild(st);}"
                    + "var SO='<svg width=\"20\" height=\"20\" viewBox=\"0 0 24 24\" fill=\"none\" "
                    + "stroke=\"currentColor\" stroke-width=\"2\" stroke-linejoin=\"round\">"
                    + "<path d=\"M12 2.8l2.9 5.9 6.5.9-4.7 4.6 1.1 6.5L12 17.6 6.2 20.7l1.1-6.5L2.6 9.6l6.5-.9z\"></path></svg>';"
                    + "var SN='<svg width=\"20\" height=\"20\" viewBox=\"0 0 24 24\" fill=\"currentColor\" "
                    + "stroke=\"currentColor\" stroke-width=\"1.5\" stroke-linejoin=\"round\">"
                    + "<path d=\"M12 2.8l2.9 5.9 6.5.9-4.7 4.6 1.1 6.5L12 17.6 6.2 20.7l1.1-6.5L2.6 9.6l6.5-.9z\"></path></svg>';"
                    + "var ft=0;function btn(){return document.getElementById('__hipFavBtn');}"
                    + "function flash(){var b=btn();if(!b)return;b.classList.add('__act');"
                    + "try{clearTimeout(ft);}catch(x){}"
                    + "ft=setTimeout(function(){var c=btn();if(c)c.classList.remove('__act');},1800);}"
                    + "function isFav(){try{return !!(window.HipApp&&HipApp.isFav"
                    + "&&HipApp.isFav(location.href||''));}catch(e){return false;}}"
                    + "function build(){try{var p=location.pathname||'';"
                    // ★ v1.9：只在作品详情页显示收藏星。
                    //   阅读页原本也显示，但阅读器的 URL（reader.hipmh.top/chapter/<hid>）
                    //   解析不出作品 id，点了只会弹「这个页面还不能收藏」，体验很差；
                    //   用户要求直接去掉 —— 收藏统一在详情页完成。
                    + "var ok=(p.indexOf('/works/')>=0)"
                    + "&&(p.indexOf('/chapter/')<0);"
                    + "var b=btn();if(!ok){if(b&&b.parentNode)b.parentNode.removeChild(b);return;}"
                    + "if(!b){b=document.createElement('button');b.id='__hipFavBtn';"
                    + "b.setAttribute('aria-label','收藏');b.setAttribute('data-hip','1');"
                    + "b.setAttribute('data-hipfav','1');"
                    + "b.addEventListener('click',function(e){e.preventDefault();e.stopPropagation();"
                    + "try{var r=false;if(window.HipApp&&HipApp.toggleFav)"
                    + "r=!!HipApp.toggleFav(location.href||'',document.title||'');"
                    + "var c=btn();if(c){c.classList.toggle('__on',r);c.innerHTML=r?SN:SO;}flash();}"
                    + "catch(x){}},true);"
                    + "document.body.appendChild(b);flash();}"
                    // 详情页：站点的 <nav> 是 fixed 且右上角有「更多」按钮，
                    //        所以按 nav 实际底边往下让 8px，避免压住它（nav 是两行，高度不固定）。
                    // ★ v1.9：不再有阅读页分支（阅读页已不显示收藏星）。
                    + "var nb=0;"
                    + "try{var nv=document.querySelector('nav');if(nv)nb=nv.getBoundingClientRect().bottom;}catch(e){}"
                    + "if(!(nb>40))nb=0;"
                    + "b.style.top=(nb>0?'calc('+Math.round(nb+8)+'px)':'calc(14px + env(safe-area-inset-top,0px))');"
                    + "var on=isFav();b.innerHTML=on?SN:SO;b.classList.toggle('__on',on);"
                    + "}catch(e){}}"
                    + "build();setTimeout(build,600);setTimeout(build,1800);"
                    + "['astro:after-swap','astro:page-load','popstate','hashchange'].forEach(function(ev){"
                    + "try{document.addEventListener(ev,function(){setTimeout(build,80);"
                    + "setTimeout(build,500);},false);}catch(x){}});"
                    + "try{new MutationObserver(function(){setTimeout(build,150);})"
                    + ".observe(document.documentElement,{childList:true,subtree:true});}catch(e){}"
                    + "try{window.addEventListener('scroll',flash,{passive:true});"
                    + "document.addEventListener('touchstart',flash,{passive:true});}catch(e){}"
                    + "})();";

    /**
     * ★ v1.9：作品详情页 —— 把封面图 URL 报给原生，供书架列表显示封面。
     *
     * <p><b>★ 这里刻意「照抄站点自己的历史记录取图逻辑」</b>（2026-09-21 逆向站点
     * {@code _MangaDetailPage.astro} + {@code readingHistoryStorage.js} + 
     * {@code ReadingHistoryList.astro} 得到）：
     * <pre>
     * var t=document.querySelector("[data-manga-id]");
     * var a=t.getAttribute("data-manga-id");
     * var r=t.getAttribute("data-manga-title");
     * var o=t.getAttribute("data-manga-path");
     * var n=t.getAttribute("data-cover-url");     // ← 封面就在这儿
     * e.recordMangaOnly({mangaId:a,mangaTitle:r,mangaPath:o,coverUrl:n,...});
     * </pre>
     * 真实页面上的锚点（已核实）：
     * <pre>
     * &lt;div class="w-full bg-background md:container mx-auto pb-24…"
     *      data-manga-id="15031"
     *      data-manga-title="我独自升级 : 诸神黄昏"
     *      data-manga-path="/works/bToxNTAzMQ"
     *      data-cover-url="https://cover.s3imgs.top/kk/vertical/…webp"
     *      data-cover-color="#586ea1"&gt;
     * </pre>
     * 所以**首选 {@code [data-cover-url]}**（一并取到官方标题与作品路径），
     * 其次 {@code og:image}、{@code ld+json} 的 {@code image}。
     *
     * <p><b>⚠️ 千万不要取 {@code img[alt="cover image"]}</b> —— v1.9 初版踩过这个坑：
     * 那张是顶部背景层的「模糊大图」，class 是
     * {@code absolute inset-0 h-full w-full object-cover object-center blur-[70px] md:blur-[10px] md:scale-110}，
     * 拿它当封面只会是一片糊（表现为「不显示封面」）。
     * 真正的海报是竖版容器 {@code div.w-40.h-[226px]} 里那张 {@code object-cover}。
     */
    private static final String COVER_JS =
            "(function(){try{"
                    + "var p=location.pathname||'';"
                    + "if(p.indexOf('/works/')<0)return;"
                    + "function abs(u){try{"
                    + "if(!u)return '';"
                    + "if(u.indexOf('//')===0)return location.protocol+u;"
                    + "if(u.charAt(0)==='/')return location.origin+u;"
                    + "return u;}catch(e){return u||'';}}"
                    + "function pick(){try{"
                    // ★ 1) 首选：站点自己写的历史记录数据源 —— [data-manga-id] 上的 data-cover-url。
                    //   站点 _MangaDetailPage.astro 也是读这一份（data-cover-url / data-cover-color），
                    //   它同时带 data-manga-path 与 data-manga-title，可一并回传标题。
                    + "var el=document.querySelector('[data-cover-url]');"
                    + "if(el){var cu=el.getAttribute('data-cover-url');"
                    + "if(cu){if(!window.__hipCoverMeta){window.__hipCoverMeta="
                    + "{path:el.getAttribute('data-manga-path')||'',"
                    + "title:el.getAttribute('data-manga-title')||'',"
                    + "color:el.getAttribute('data-cover-color')||''};}"
                    + "return abs(cu);}}"
                    // 2) 次选：og:image（站点 <meta property="og:image">，同一张图）
                    + "var og=document.querySelector('meta[property=\"og:image\"]');"
                    + "if(og){var c=og.getAttribute('content');if(c)return abs(c);}"
                    // 3) 再选：ld+json 里的 image（CreativeWorkSeries.image）
                    + "var lds=document.querySelectorAll('script[type=\"application/ld+json\"]');"
                    + "for(var k=0;k<lds.length;k++){try{var o=JSON.parse(lds[k].textContent||'');"
                    + "var g=o&&o['@graph'];if(g&&g.length){for(var q=0;q<g.length;q++){"
                    + "var nd=g[q];if(nd&&nd.image){var iv=nd.image;"
                    + "if(typeof iv==='object'&&iv&&iv.url)iv=iv.url;"
                    + "if(typeof iv==='string'&&iv)return abs(iv);}"
                    + "if(nd&&nd.name&&!window.__hipCoverMeta)"
                    + "{window.__hipCoverMeta={title:nd.name,path:'',color:''};}"
                    + "}}}catch(x){}}"
                    // 4) 兜底：详情页里「作品自己的海报」—— 竖版封面容器 w-40 h-[226px] 内的 img。
                    //   ⚠️ 绝不能取 img[alt="cover image"]：那是顶部背景层的「模糊大图」
                    //   （class 含 blur-[70px]/inset-0），取它做封面只会是一片糊。
                    + "var poster=document.querySelector('div[class*=\"w-40\"] img[class*=\"object-cover\"]');"
                    + "if(poster){var ps=poster.getAttribute('src');"
                    + "if(ps&&!/blur-\\[/.test(poster.getAttribute('class')||''))return abs(ps);}"
                    // 5) 最后：正文里第一张非模糊、非 logo/头像的作品封面
                    + "var ims=document.querySelectorAll('img');"
                    + "for(var i=0;i<ims.length;i++){var im=ims[i];"
                    + "var al=im.getAttribute('alt')||'';"
                    + "if(al==='logo'||/頭像|头像/.test(al))continue;"
                    + "if(/blur-\\[/.test(im.getAttribute('class')||''))continue;"
                    + "var src=im.getAttribute('src')||im.getAttribute('data-src')||'';"
                    + "if(/\\/kk\\/|cover\\./i.test(src))return abs(src);}"
                    + "return '';"
                    + "}catch(e){return '';}}"
                    + "function send(){try{"
                    + "var u=pick();if(!u)return;"
                    + "var m=window.__hipCoverMeta||{};"
                    + "if(window.HipApp&&HipApp.setCover)"
                    + "HipApp.setCover(location.href||'',u,m.title||'',m.path||'');"
                    + "}catch(e){}}"
                    + "send();setTimeout(send,500);setTimeout(send,1500);setTimeout(send,3000);"
                    + "['astro:after-swap','astro:page-load','popstate','hashchange']"
                    + ".forEach(function(ev){try{document.addEventListener(ev,function(){"
                    + "window.__hipCoverMeta=null;"
                    + "setTimeout(send,120);setTimeout(send,700);},false);}catch(x){}});"
                    + "}catch(e){}})();";

    /** ★ v1.9：注入封面采集脚本 */
    private void injectCover() {
        web.evaluateJavascript(COVER_JS, null);
    }

    /**
     * ★ v2.0.0：详情页「继续阅读」按钮。
     *
     * <p>站点详情页的 {@code #reading-btn} 永远指向第一章（{@code data-default-text=開始閱讀}），
     * 用户读完几十话回来还要去章节列表里翻。这里把按钮改成：
     * <ul>
     *   <li><b>有进度</b>（这本书读到过）：文字换成「继续阅读 第X话 · 共N章」，
     *       点击直达上次读到的章节（原生把落库的 API hid 重编码成前端 hid
     *       {@code b64url("m:<作品id>-c:<章节id>")}，与站点「開始閱讀」按钮同构，
     *       走 {@code /chapter/go} 中转页，被 {@code handleUrl} 拦进阅读器窗口）；</li>
     *   <li><b>无进度</b>（从没读过 / 解析失败）：按钮原样保留「開始閱讀」。</li>
     * </ul>
     *
     * <p>进度来源是 prog 表（阅读器每章实时上报，<b>不依赖收藏</b>）。
     * 幂等 + MutationObserver 双保险：站点自己的恢复脚本（data-default-text /
     * localStorage）晚于我们改写按钮时会被重新套用，且不会自激。
     */
    private static final String DETAIL_PROGRESS_JS =
            "(function(){try{"
                    + "var p=location.pathname||'';"
                    + "if(p.indexOf('/works/')<0)return;"
                    + "function tick(){try{"
                    + "var btn=document.getElementById('reading-btn');if(!btn)return;"
                    + "var pr='';try{pr=(window.HipApp&&HipApp.progressOf)?(HipApp.progressOf(location.href||'')||''):'';}catch(e){pr='';}"
                    + "if(!pr){return;}"
                    + "var o=null;try{o=JSON.parse(pr);}catch(e){o=null;}"
                    + "if(!o||!o.hid||!(o.num|0))return;"
                    + "var num=o.num|0,tot=o.total|0;"
                    + "var txt='继续阅读 '+(o.title&&o.title.indexOf(String(num))>=0?o.title:('第'+num+'话'));"
                    + "if(tot>0)txt+=' · 共'+tot+'章';"
                    // 幂等：站点自己的脚本（data-default-text 恢复逻辑）可能把按钮改回去，
                    // 已经是我们文案时就不再动，避免 MutationObserver 自激。
                    + "if((btn.textContent||'').indexOf('继续阅读')===0"
                    + "&&btn.getAttribute('data-hip-cont')==='1')return;"
                    + "var sp=btn.querySelector('span');"
                    + "if(sp)sp.textContent=txt;else btn.textContent=txt;"
                    + "btn.setAttribute('data-hip-cont','1');"
                    + "var u='/chapter/go?hid='+encodeURIComponent(o.hid)"
                    + "+'&m='+encodeURIComponent(String(o.mid||''));"
                    + "btn.setAttribute('href',u);"
                    + "btn.setAttribute('data-default-link',u);"
                    + "btn.setAttribute('data-default-text',txt);"
                    + "}catch(e){}}"
                    // 站点脚本晚于我们改写按钮时（异步水合/恢复 localStorage），
                    // 监听按钮自身变化重新套用；幂等判断防止自激循环。
                    + "try{var __b=document.getElementById('reading-btn');"
                    + "if(__b&&!window.__hipContObs){window.__hipContObs=1;"
                    + "new MutationObserver(function(){setTimeout(tick,60);})"
                    + ".observe(__b,{childList:true,attributes:true,characterData:true,subtree:true});}}catch(e){}"
                    + "tick();setTimeout(tick,400);setTimeout(tick,1200);setTimeout(tick,2600);"
                    + "['astro:after-swap','astro:page-load','popstate','hashchange']"
                    + ".forEach(function(ev){try{document.addEventListener(ev,function(){"
                    + "setTimeout(tick,150);setTimeout(tick,900);},false);}catch(x){}});"
                    + "}catch(e){}})();";

    /** ★ v2.0.0：注入详情页「继续阅读」改造脚本 */
    private void injectDetailProgress() {
        web.evaluateJavascript(DETAIL_PROGRESS_JS, null);
    }

    private static final String GUARD_JS =
            "(function(){if(window.__hipGuard)return;window.__hipGuard=1;function blocked(a){try{if(!a||!a.getAttribute)return 0;if(a.getAttribute('data-hip')||a.getAttribute('data-hipfav')||a.getAttribute('data-hip-cont'))return 0;"
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

    /**
     * ★ v1.10.0：把繁→简注入到**指定** WebView（阅读器窗口用）。
     *
     * <p>映射表 {@code window.__hipT2SMap} 是每个 WebView 各自的 JS 环境，
     * 所以阅读器窗口必须自己再灌一次 —— 不能复用主 WebView 的 {@code t2sInjected} 标志，
     * 否则阅读器里的繁体字永远不转（表现为「详情页是简体、进阅读页又变繁体」）。
     */
    private void injectT2sInto(WebView w) {
        if (w == null) return;
        try {
            String map = t2sMap();
            if (map == null || map.isEmpty()) return;
            w.evaluateJavascript(map + T2S_JS, null);
        } catch (Throwable ignore) {
        }
    }

    /** ★ v1.10.0：把清理脚本注入到指定 WebView（阅读器窗口用） */
    private void injectCleanInto(WebView w) {
        if (w == null) return;
        try {
            w.evaluateJavascript(cleanJs(prefs.adBlock()), null);
        } catch (Throwable ignore) {
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
        // ★ v2.1.0：章节进度上报（重写）。v2.0.0 的做法是每章一次
        //   fetch /v2/chapter 拿章号 —— 有两个致命弱点：
        //   ① 去重标记在 fetch【前】就置位，fetch 一旦失败/超时，该章进度
        //      永久丢失（表现就是「读到第 6 章还显示第 5 章」）；
        //   ② 后台预抓 WebView（bgWeb）注入的 readerJs(false) 也带上报块，
        //      会把【还没读的下一章】写进进度。
        //   现改为：
        //   - **整块只在 allowNext=true（真实阅读窗口）时注入**，预抓窗不再上报；
        //   - **直接读服务端渲染的 DOM 属性同步上报**（#chapcontent 自带
        //     data-chapter-num / data-frontend-hid / data-manga-id，站点自己的
        //     加载器还会补 data-chapter-number / data-chapter-title），
        //     不依赖任何网络请求 —— 章号是站点自己渲染出来的，天然准确；
        //   - DOM 缺章号时才回退站点 API（失败清去重标记，2 秒轮询会自动补报）；
        //   - 2 秒 setInterval 轮询 + MutationObserver 盯 #chapcontent 属性变化
        //     + astro 事件，任何时机漏掉的重跑都有兜底（去重保证代价极低）。
        String progressBlock = allowNext ? (
                "function __hipProg(){try{"
                + "var c=document.getElementById('chapcontent')"
                + "||document.querySelector('[data-api-hid]');if(!c)return;"
                + "var fh=c.getAttribute('data-frontend-hid')||'';"
                + "var ah=c.getAttribute('data-api-hid')||'';"
                + "var key=fh||ah;if(!key)return;"
                + "if(window.__hipProgLast===key)return;"
                + "var mid=c.getAttribute('data-manga-id')||'';"
                + "var tot=0;var te2=document.querySelector('[data-total-chapters]');"
                + "if(te2)tot=te2.getAttribute('data-total-chapters')|0;"
                + "var ns=(c.getAttribute('data-chapter-number')"
                + "||c.getAttribute('data-chapter-num')||'').replace(/[^0-9]/g,'');"
                + "if(mid&&ns){"
                + "var fmt=c.getAttribute('data-chapter-number-format')||'第{num}话';"
                + "var ti=fmt.indexOf('{num}')>=0?fmt.replace('{num}',ns):'第'+ns+'话';"
                + "var ct=c.getAttribute('data-chapter-title')||'';"
                + "window.__hipProgLast=key;"
                + "if(window.HipApp&&HipApp.reportProgress)"
                + "HipApp.reportProgress(mid,ns|0,ct||ti,tot|0,fh||ah);"
                + "return;}"
                + "var abe=document.querySelector('[data-api-base-url]');"
                + "var ab=(abe&&abe.getAttribute('data-api-base-url'))||'';"
                + "if(!ab||!mid||!ah)return;"
                + "window.__hipProgLast=key;"
                + "fetch(ab+'/v2/chapter?hid='+encodeURIComponent(ah))"
                + ".then(function(r){return r.json();})"
                + ".then(function(j){try{var d=j&&j.data?j.data:null;if(!d){"
                + "window.__hipProgLast=null;return;}"
                + "if(window.HipApp&&HipApp.reportProgress)"
                + "HipApp.reportProgress(mid,d.chapter_number|0,"
                + "d.chapter_title||'',tot|0,fh||ah);}"
                + "catch(e){window.__hipProgLast=null;}})"
                + ".catch(function(e){window.__hipProgLast=null;});"
                + "}catch(e){}}"
                + "__hipProg();setTimeout(__hipProg,900);setTimeout(__hipProg,2500);"
                + "try{if(!window.__hipProgT)window.__hipProgT=setInterval(__hipProg,2000);}catch(e){}"
                + "['astro:after-swap','astro:page-load','popstate','hashchange'].forEach(function(ev){"
                + "try{document.addEventListener(ev,function(){setTimeout(__hipProg,300);"
                + "setTimeout(__hipProg,1500);},false);}catch(x){}});"
                + "try{if(!window.__hipProgObs){window.__hipProgObs=1;"
                + "var pc=document.getElementById('chapcontent');"
                + "if(pc){var __po=new MutationObserver(function(){setTimeout(__hipProg,200);});"
                + "__po.observe(pc,{attributes:true,attributeFilter:["
                + "'data-frontend-hid','data-api-hid','data-chapter-num',"
                + "'data-chapter-number','data-chapter-title']});}}}catch(e){}"
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
                // ★ v1.9.2：默认**真正半透明**（背景 alpha .22 + 元素 opacity .55，且不加
                //   backdrop-filter —— 之前 blur(2px) 会把背后的漫画糊成一片，视觉上像实心圆盘）。
                //   只有「手动触摸按钮 / 主动滚动」才短暂变实心，1.8s 后自动淡回，
                //   且**新建按钮时不再自动 flash**（旧版建完就 __hipFlash()，导致一进页面
                //   就是实心态 —— 这是用户反复反馈「不透明」的真正原因）。
                + "+'#__hipBack{position:fixed;top:calc(14px + env(safe-area-inset-top,0px));right:12px;'"
                + "+'z-index:2147483000;width:40px;height:40px;border-radius:50%;display:flex;'"
                + "+'align-items:center;justify-content:center;background:rgba(24,26,32,.22);'"
                + "+'color:rgba(255,255,255,.92);border:0;padding:0;cursor:pointer;opacity:.55;'"
                + "+'transition:opacity .35s ease,background .35s ease;}'"
                + "+'#__hipBack.__act{opacity:1;background:rgba(24,26,32,.78);'"
                + "+'box-shadow:0 2px 8px rgba(0,0,0,.28);}';"
                + "document.head.appendChild(st);}"
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
                // ★ v1.2：「返回书籍」按钮 —— 点击后走原生 backToBook()。
                // ★ v1.10.0：语义已变为「关闭阅读器窗口」—— 阅读器是独立 WebView，
                //   主 WebView 始终停在详情页原地，关掉窗口详情页就露出来，
                //   不再需要按 hid 反推 /works/<b64> 去 loadUrl。
                //   **同时去掉了 history.back() 兜底**：那条兜底在旧结构里会退到
                //   中间跳转页/上一章，正是用户反复抱怨的行为，绝不能再留。
                // ★ v1.9.2：只在「手指按在按钮上」时短暂变实心（给个点击反馈），
                //   页面滚动/点击漫画**不再**触发 flash —— 旧版把这些都当触发源，
                //   结果是翻页时按钮一直在实心态，用户看到的就是「不透明」。
                + "var __hipBt=0;function __hipFlash(){var b=document.getElementById('__hipBack');if(!b)return;"
                + "b.classList.add('__act');try{clearTimeout(__hipBt);}catch(x){}"
                + "__hipBt=setTimeout(function(){var c=document.getElementById('__hipBack');"
                + "if(c)c.classList.remove('__act');},1800);}"
                + "function __hipBackBtn(){try{if(!location.pathname||location.pathname.indexOf('/chapter/')<0){"
                + "var eb=document.getElementById('__hipBack');if(eb&&eb.parentNode)eb.parentNode.removeChild(eb);return;}"
                + "if(document.getElementById('__hipBack'))return;"
                + "var b=document.createElement('button');b.id='__hipBack';b.setAttribute('aria-label','返回书籍');"
                + "b.innerHTML='<svg width=\"20\" height=\"20\" viewBox=\"0 0 24 24\" fill=\"none\" "
                + "stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" "
                + "stroke-linejoin=\"round\"><path d=\"M15 18l-6-6 6-6\"></path></svg>';"
                + "b.addEventListener('click',function(e){e.preventDefault();e.stopPropagation();"
                + "try{if(window.HipApp&&HipApp.backToBook)HipApp.backToBook();}catch(x){}},true);"
                + "b.addEventListener('touchstart',function(){__hipFlash();},{passive:true});"
                + "b.addEventListener('mousedown',function(){__hipFlash();});"
                + "document.body.appendChild(b);"
                + "}catch(e){}}"
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
+ progressBlock
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

    /**
     * 左下角「菜单」按钮弹出的功能列表。
     *
     * <p>★ v1.9：按用户要求去掉「后退 / 前进 / 刷新 / 我的书架」四项 ——
     * 前三个系统手势/下拉刷新已够用，书架入口统一走右上角「更多」抽屉。
     */
    private void showMenu() {
        final String[] items = {
                "首页", "发现漫画", "人气排行", "最新上架",
                "已完结", "连载中", "随机看看", "搜索",
                "设置",
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
                                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
                                break;
                            case 9:
                                openExternal(web.getUrl());
                                break;
                            case 10:
                                clearCache();
                                break;
                            case 11:
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
        // ================= ★ v1.10.0：返回优先级（自上而下逐级判断） =================
        //
        // 用户明确的期望：
        //   ① 书架（「更多」抽屉）打开时，返回 = **收起书架**（等同点右上角「×」），
        //      而不是退出软件、也不是退页面；
        //   ② 阅读漫画时，返回 = **关闭阅读器窗口** → 露出「刚点进书籍时」的详情页；
        //      再按一次返回 = 详情页 → 主页。
        //
        // 旧实现（v1.9.3 及以前）有两个硬伤：
        //   · 完全没判断抽屉开合 → 抽屉开着按返回，直接把整个页面退走；
        //   · 阅读页返回靠 loadUrl(详情页) 硬跳，而那是往历史栈**追加一格**，
        //     所以「再按一次返回」就 goBack() 回到了历史里的**阅读页**
        //     —— 即用户报的「返回详情页后，再返回又是漫画阅读页」。
        //
        // v1.10.0 的解法是**结构性的**：阅读器被拆成独立的 WebView（rdWeb），
        // 主 WebView 停在详情页原地不动，历史栈里根本没有阅读器，
        // 于是「关窗 → 再返回 → 首页」自然成立，不需要任何 URL 猜测。

        // ---- 第 1 优先级：抽屉（书架）开着 → 收起抽屉 ----
        if (drawerOpen) {
            closeDrawer();
            return;
        }

        // ---- 第 2 优先级：阅读器窗口开着 → 关窗，原地露出详情页 ----
        if (readerOpen) {
            exitReader();
            return;
        }

        // ---- 第 3 优先级：阅读器窗口虽已关但还有内部历史（极少见，兜底） ----
        //   rdWeb 是不可见的，如果它的历史还没走完说明状态异常，直接清干净。
        if (rdWeb != null && rdWeb.getVisibility() == View.VISIBLE) {
            exitReader();
            return;
        }

        // ---- 第 4 优先级：主 WebView 自己的历史（详情页 → 首页 等） ----
        if (web.canGoBack()) {
            web.goBack();
            return;
        }

        // ---- 第 5 优先级：再按一次退出 ----
        long now = System.currentTimeMillis();
        if (now - lastBackMs < 2000) {
            super.onBackPressed();
            return;
        }
        lastBackMs = now;
        toast(getString(R.string.exit_hint));
    }

    /** 作品 id -> 详情页 URL（{@code /works/<b64url("m:<id>")>}） */
    private static String workUrlOfId(int id) {
        try {
            String b64 = android.util.Base64.encodeToString(
                    ("m:" + id).getBytes("UTF-8"),
                    android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING
                            | android.util.Base64.NO_WRAP);
            return "https://m.hipmh.com/works/" + b64;
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * ★ v2.0.0：从作品详情页 URL 里抠出 {@code /works/<b64>} 的 b64 段
     * （{@code m:<id>} 的 base64url 原文），解不出返回空串。
     */
    private static String workUrlIdB64(String workUrl) {
        if (workUrl == null) return "";
        try {
            int i = workUrl.indexOf("/works/");
            if (i < 0) return "";
            String tail = workUrl.substring(i + 7);
            int e = tail.length();
            for (int j = 0; j < tail.length(); j++) {
                char c = tail.charAt(j);
                if (c == '/' || c == '?' || c == '#' || c == '&') {
                    e = j;
                    break;
                }
            }
            return tail.substring(0, e);
        } catch (Throwable ex) {
            return "";
        }
    }

    /**
     * ★ v2.0.0：从阅读器 API hid（{@code b64url("c:<章节id>")}[-杂项] 形态，
     * 即 {@code data-api-hid} 的值）解出章节 id，解不出返回 -1。
     */
    private static int chapterIdFromApiHid(String apiHid) {
        if (apiHid == null || apiHid.isEmpty()) return -1;
        try {
            String dec = decodeB64Url(apiHid);
            if (dec == null) return -1;
            int ci = dec.indexOf("c:");
            if (ci < 0) return -1;
            int p = ci + 2;
            int z = p;
            while (z < dec.length() && Character.isDigit(dec.charAt(z))) z++;
            if (z <= p) return -1;
            return Integer.parseInt(dec.substring(p, z));
        } catch (Throwable e) {
            return -1;
        }
    }

    /**
     * ★ v2.1.0：把落库的章 hid 归一成「前端 hid」{@code b64url("m:<作品id>-c:<章节id>")[-杂项]}。
     *
     * <p>v2.1.0 起上报直接传阅读器 DOM 的 {@code data-frontend-hid}
     * （已是前端形态，原样返回）；v2.0.0 老数据存的是 API hid（{@code c:<章节id>} 形态）
     * → 解出章节 id 后按本章作品 id 重新编码。
     */
    private static String chapterFeHid(String hid, int mid) {
        if (hid == null || hid.isEmpty()) return "";
        try {
            String dec = decodeB64Url(hid);
            if (dec != null && dec.indexOf("m:") >= 0 && dec.indexOf("c:") > dec.indexOf("m:")) {
                return hid;   // 已是前端形态（m:<id>-c:<章节id>），原样用
            }
            int chId = chapterIdFromApiHid(hid);
            if (chId <= 0 || mid <= 0) return "";
            return b64UrlEncode("m:" + mid + "-c:" + chId);
        } catch (Throwable e) {
            return "";
        }
    }

    /** ★ v2.0.0：base64url 编码（无 padding，与站点 hid 编码一致） */
    private static String b64UrlEncode(String s) {
        try {
            return android.util.Base64.encodeToString(s.getBytes("UTF-8"),
                    android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING
                            | android.util.Base64.NO_WRAP);
        } catch (Throwable e) {
            return "";
        }
    }

    /** 打开「我的书架」（本地收藏列表） */
    private void openFavList() {
        try {
            Intent i = new Intent(MainActivity.this, ListActivity.class);
            i.putExtra("tab", "fav");
            startActivity(i);
        } catch (Throwable ignore) {
        }
    }

    /**
     * ★ v1.8：书架条目长按菜单（侧边抽屉内长按触发）。
     * 与 {@code ListActivity.showFavMenu} 保持同一套文案与语义。
     */
    private void showShelfMenu(final String url) {
        try {
            String k = favKeyFor(url);
            if (k == null || k.isEmpty()) k = url;
            // 取标题用于菜单头显示
            String title = k;
            try {
                List<Store.Item> its = store.list("fav");
                for (Store.Item it : its) {
                    if (k.equals(it.url)) {
                        title = it.title == null ? k : it.title;
                        break;
                    }
                }
            } catch (Throwable ignore) {
            }
            final String key = k;
            final String[] opts = {"标记为「在看」", "标记为「已读完」", "清除标记", "取消收藏"};
            new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setItems(opts, (d, w) -> {
                        switch (w) {
                            case 0:
                                store.setFavStatus(key, Store.ST_READING);
                                refreshDrawer();
                                toast("已标记为「在看」");
                                break;
                            case 1:
                                store.setFavStatus(key, Store.ST_DONE);
                                refreshDrawer();
                                toast("已标记为「已读完」");
                                break;
                            case 2:
                                store.setFavStatus(key, Store.ST_NONE);
                                refreshDrawer();
                                toast("已清除标记");
                                break;
                            case 3:
                                store.remove("fav", key);
                                refreshDrawer();
                                toast("已取消收藏");
                                break;
                            default:
                                break;
                        }
                    })
                    .show();
        } catch (Throwable ignore) {
        }
    }

    /**
     * ★ v1.8：标记/删除后让抽屉里的书架列表立即刷新。
     *
     * <p>★ v1.9.1：改成**先落库再重绘** —— 以前是「改完直接 render」，
     * 但 DB 写入在主线程、JSON 又是从 DB 读的，遇上时序差就会看到旧列表
     * （表现为「长按选了没反应 / 删了还在」）。现在统一由回调驱动。
     */
    private void refreshDrawer() {
        try {
            if (web != null) {
                web.evaluateJavascript(
                        "try{if(window.__hipDrawerRender)window.__hipDrawerRender();}catch(x){}",
                        null);
            }
        } catch (Throwable ignore) {
        }
    }

    /** JSON 字符串字面量（转义引号/反斜杠/控制字符） */
    private static String jsonStr(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format(Locale.US, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                    break;
            }
        }
        return sb.append('"').toString();
    }

    /**
     * ★ v1.6：把任意页面 URL 归一化成「作品」维度，保证详情页与阅读器收藏到同一条记录。
     *
     * <ul>
     *   <li>{@code /works/<id>/...} → 截到作品路径</li>
     *   <li>{@code /chapter/...} → 取 m= / hid 里的作品 id，还原成 {@code /works/<b64>}</li>
     * </ul>
     *
     * @return 作品 URL；不是作品相关页面（或阅读器解析不出作品 id）返回 {@code null}
     */
    private static String favKeyFor(String u) {
        if (u == null || u.isEmpty()) return null;
        try {
            if (u.contains("/chapter/") || u.contains("/chapter/go")) {
                int id = workIdOf(u);
                // 阅读器解析不出作品 id 时宁可不收藏，避免往书架里塞脏数据
                return id > 0 ? workUrlOfId(id) : null;
            }
            if (u.contains("/works/")) return workUrl(u);
            return null;
        } catch (Throwable e) {
            return null;
        }
    }

    /** 收藏用的展示标题：去掉「- 第12话」章节后缀与站点名后缀 */
    private static String favTitleFor(String t, String url) {
        String s = workTitle(t);
        if (s == null) s = "";
        s = s.trim();
        String[] SUFS = {
                " - 嘻皮漫画", " | 嘻皮漫画", " - 嘻皮漫畫", " | 嘻皮漫畫",
                " - HipManga", " | HipManga", " - HIPMH", " | HIPMH"
        };
        for (String suf : SUFS) {
            if (s.endsWith(suf)) {
                s = s.substring(0, s.length() - suf.length()).trim();
                break;
            }
        }
        return s.isEmpty() ? url : s;
    }

    /**
     * 从 URL 里提取作品 id。
     *
     * <p>★ v1.9.3：新增第 3 条规则 —— 解析 {@code /chapter/<b64>} 路径段。
     * 真实阅读器跑在 {@code reader.hipmh.top/chapter/<b64url("m:<id>-c:<章节id>")>-<杂项>}，
     * 这种 URL **没有 {@code m=} 也没有 {@code hid=}**，旧版两条规则都拿不到 id，
     * 于是「按返回」只能退化到 goBack()（= 回上一章），这正是用户反馈的 bug。
     *
     * <p>解析顺序：
     * <ol>
     *   <li>{@code ?m=<数字>}（最稳）</li>
     *   <li>{@code ?hid=<b64url>} → 解出 {@code m:<id>}</li>
     *   <li>{@code /chapter/<b64url>} 路径段 → 解出 {@code m:<id>}（★ 新增）</li>
     * </ol>
     */
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
                int id = idFromB64(u.substring(s, e));
                if (id > 0) return id;
            }
            // 3) ★ v1.9.3：/chapter/<b64>（reader.hipmh.top 的形态，无 m= / hid=）
            int c = u.indexOf("/chapter/");
            if (c >= 0) {
                int s = c + 9;
                int e = s;
                while (e < u.length() && u.charAt(e) != '/' && u.charAt(e) != '?'
                        && u.charAt(e) != '&' && u.charAt(e) != '#') e++;
                int id = idFromB64(u.substring(s, e));
                if (id > 0) return id;
            }
        } catch (Throwable ignore) {
        }
        return -1;
    }

    /**
     * 从 base64url 串里解出 {@code m:<作品id>} 的数字部分。
     *
     * <p>串可能有多种形态，都能吃：
     * <ul>
     *   <li>{@code bToxNTAzMS1jOjEzOTc1} → {@code m:15031-c:13975}</li>
     *   <li>{@code bToxNTAzMS1jOjEzOTc1-MTUwMzE6MS4wMA}（尾部 {@code -} 后是杂物）</li>
     *   <li>{@code bToxNTAzMQ}（只到作品 id）</li>
     * </ul>
     * 尾部杂物用补齐 padding 的方式让解码器自行忽略（Android Base64 对超长串容忍）。
     *
     * @return 作品 id；解不出返回 -1
     */
    private static int idFromB64(String b64) {
        if (b64 == null || b64.isEmpty()) return -1;
        try {
            // 站点写成 "<b64>-<b64>"，只取第一段（'-' 是分隔符）
            int dash = b64.indexOf('-');
            String head = dash > 0 ? b64.substring(0, dash) : b64;
            if (head.isEmpty()) return -1;
            String dec = decodeB64Url(head);
            if (dec == null) return -1;
            int mi = dec.indexOf("m:");
            if (mi < 0) return -1;
            int p = mi + 2;
            int z = p;
            while (z < dec.length() && Character.isDigit(dec.charAt(z))) z++;
            if (z <= p) return -1;
            return Integer.parseInt(dec.substring(p, z));
        } catch (Throwable e) {
            return -1;
        }
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

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        try {
            web.saveState(out);
            // ★ v1.10.0：阅读器窗口状态一并保存 —— 否则横竖屏切换后
            //   readerOpen 还在（返回键以为有窗口要关）但 rdWeb 已经空了。
            rdWeb.saveState(out);
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
            if (i == null) return;
            setIntent(i);   // ★ v1.6：保留最新 Intent，避免重建后再次读到旧的 url
            String u = i.getStringExtra("url");
            // ★ v1.6：空值 / about:blank / 非 http 一律不加载，否则就是一片白
            if (u == null || u.isEmpty()) return;
            if (!u.startsWith("http")) return;
            if (web != null) web.loadUrl(u);
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
        try {
            if (coverPool != null) {
                coverPool.shutdownNow();
                coverPool = null;
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
        // ★ v1.10.0：阅读页现在跑在 rdWeb 里，判断「是不是当前正在读的那章」必须看
        //   **阅读器窗口**的 URL —— 主 WebView 的 web.getUrl() 是详情页，永远不匹配，
        //   否则会把当前章自己当成下一章去预抓（白耗流量）。
        if (readerOpen && readerUrl != null && readerUrl.contains(nextHid)) return;
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

        /** 点右上角「更多」（已被劫持为书架入口）/ 菜单「我的书架」-> 打开本地收藏列表 */
        @JavascriptInterface
        public void openFavList() {
            ui.post(() -> MainActivity.this.openFavList());
        }

        /**
         * ★ v1.8：侧边抽屉（＝书架）列表数据。
         *
         * <p>以 JSON 数组返回，每项 {@code {"title":..,"url":..,"st":0|1|2}}。
         * JS 侧解析后渲染进抽屉里，替代站点原本的「閱讀記錄」内容。
         */
        @JavascriptInterface
        public String shelfJson() {
            try {
                List<Store.Item> its = store.list("fav");
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < its.size(); i++) {
                    Store.Item it = its.get(i);
                    if (i > 0) sb.append(',');
                    sb.append("{\"title\":").append(jsonStr(it.title))
                            .append(",\"url\":").append(jsonStr(it.url))
                            .append(",\"cover\":").append(jsonStr(it.cover))
                            .append(",\"st\":").append(it.st).append('}');
                }
                return sb.append(']').toString();
            } catch (Throwable e) {
                return "[]";
            }
        }

        /** ★ v1.8：书架条目右侧「×」—— 直接从书架移除 */
        @JavascriptInterface
        public void removeFav(final String url) {
            try {
                String k = favKeyFor(url);
                if (k == null || k.isEmpty()) k = url;
                store.remove("fav", k);
            } catch (Throwable ignore) {
            }
        }

        /** ★ v1.8：书架条目点击 —— 在当前 WebView 里打开作品详情页 */
        @JavascriptInterface
        public void openUrl(final String url) {
            if (url == null || url.isEmpty()) return;
            ui.post(() -> {
                try {
                    if (web != null) web.loadUrl(url);
                } catch (Throwable ignore) {
                }
            });
        }

        /** ★ v1.8：书架条目长按 —— 弹出「在看 / 已读完 / 清除标记 / 取消收藏」菜单 */
        @JavascriptInterface
        public void favMenu(final String url) {
            ui.post(() -> MainActivity.this.showShelfMenu(url));
        }

        /**
         * ★ v1.9.4：JS 实时上报「手指是否按在书架条目上」。
         *
         * <p>专门用来消除长按书架时的**第二个弹窗**：条目封面是 {@code <img>}，
         * WebView 原生长按会命中 {@code IMAGE_TYPE} 并弹出「全屏查看图片…」，
         * 与我们的书架菜单同时出现。原生长按回调看到 {@code shelfTouchActive}
         * 就直接吞掉（见 {@code setOnLongClickListener}）。
         *
         * @param on true = touchstart 命中书架条目；false = touchend/cancel 离开
         */
        @JavascriptInterface
        public void shelfTouch(boolean on) {
            shelfTouchActive = on;
        }

        /**
         * ★ v1.10.0：抽屉（「我的书架」）开合状态上报 —— 返回键据此「先收起抽屉」。
         *
         * <p>由 {@code drawerJs} 的 {@code tick()} 在状态**发生变化**时上报一次
         * （打开 → true，关闭 → false），原生只读 {@link #drawerOpen} 这个标志，
         * 不必在返回键里注入 JS 查 DOM（那种做法是异步的，赶不上返回键的同步判断）。
         *
         * @param open true = 抽屉已展开
         */
        @JavascriptInterface
        public void drawerState(boolean open) {
            drawerOpen = open;
        }

        /**
         * ★ v2.0.0：阅读器实时上报阅读进度（第几章 / 章节标题 / 总章数 / 本章 hid）。
         *
         * <p>由 {@code readerJs} 的 {@code __hipProg()} 在每章加载后调用（JS 侧按
         * {@code data-api-hid} 去重）。原生按「作品 key」落库到 prog 表
         * （<b>所有读过的书都记，不依赖收藏</b>；已收藏的再镜像进 fav 供书架展示），
         * 详情页据此把「開始閱讀」换成「继续阅读 第X话」并可直达本章。
         *
         * @param mid    作品数字 id（阅读器 DOM {@code [data-manga-id]}）
         * @param num    章节号（优先 DOM {@code data-chapter-num/number}，兜底 API 的
         *               {@code chapter_number}）
         * @param title  章节标题（如「第6話」，可能为空）
         * @param total  总章数（阅读器 DOM {@code [data-total-chapters]}，0 = 未知）
         * @param hid    本章 hid —— v2.1.0 起通常是**前端形态**
         *               {@code data-frontend-hid}（{@code m:<id>-c:<章节id>}），
         *               兜底路径传 API hid（{@code c:<id>} 形态）；落库原样保存
         */
        @JavascriptInterface
        public void reportProgress(String mid, int num, String title, int total, String hid) {
            try {
                int id = 0;
                try {
                    id = Integer.parseInt(mid == null ? "" : mid.trim());
                } catch (Throwable ignore) {
                }
                if (id <= 0 || num <= 0) return;
                String key = workUrlOfId(id);
                if (key == null) return;
                store.saveProgress(key, num, title, total, hid);
            } catch (Throwable ignore) {
            }
        }

        /**
         * ★ v2.0.0：详情页同步查询阅读进度（JS 直接调、直接用返回值渲染按钮）。
         *
         * <p>返回 JSON 字符串 {@code {"num":12,"title":"第12话","total":69,
         * "mid":"15031","hid":"bToxNTAzMS1jOjEzOTc1"}}；无进度返回空串。
         * hid 是「继续阅读」要用的**前端 hid**（b64url("{@code m:<作品id>-c:<章节id>}")，
         * 与站点「開始閱讀」按钮同构 —— 由 {@link #chapterFeHid} 归一：
         * v2.1.0 起落库的多为前端 hid（原样返回），v2.0.0 老数据是 API hid
         * （解出章节 id 后重编码）。查询按「当前页 key + 短形态 key」双 key 兜底，
         * 长形态详情页 URL（/works/&lt;b64&gt;-&lt;slug&gt;）也能命中短形态落库的进度。
         */
        @JavascriptInterface
        public String progressOf(String url) {
            try {
                String k = favKeyFor(url);
                if (k == null || k.isEmpty()) return "";
                int id = idFromB64(workUrlIdB64(k));
                Store.Progress p = store.progressOf(k);
                if (p == null && id > 0) {
                    // ★ v2.1.0：详情页 URL 可能是长形态（/works/<b64>-<slug>），
                    // 而上报侧按短形态 key（workUrlOfId）落库 —— 长形态查不到时
                    // 用作品 id 还原短 key 再补查一次，两种形态都能命中。
                    String sk = workUrlOfId(id);
                    if (sk != null && !sk.equals(k)) p = store.progressOf(sk);
                }
                if (p == null) return "";
                String fhid = chapterFeHid(p.chHid, id);
                StringBuilder sb = new StringBuilder("{");
                sb.append("\"num\":").append(p.ch).append(',');
                sb.append("\"title\":").append(jsonStr(p.chTitle)).append(',');
                sb.append("\"total\":").append(p.chTotal).append(',');
                sb.append("\"mid\":").append(jsonStr(id > 0 ? String.valueOf(id) : "")).append(',');
                sb.append("\"hid\":").append(jsonStr(fhid));
                return sb.append('}').toString();
            } catch (Throwable e) {
                return "";
            }
        }

        /**
         * ★ v1.9：详情页把当前作品的封面图 URL 报到原生，用于书架列表显示封面。
         *
         * <p>从阅读页收藏时解析不出封面，所以详情页每次加载都回填一次；
         * 收藏时优先用这里缓存下来的封面。
         */
        @JavascriptInterface
        public void setCover(String url, String cover) {
            setCover(url, cover, null, null);
        }

        /**
         * ★ v1.9：详情页把封面 + 站点官方标题 + 作品路径一起报给原生。
         *
         * <p>标题与路径来自站点自己的 {@code [data-manga-title]} / {@code [data-manga-path]}，
         * 比刮页面 {@code <h1>} 干净（不会被「已完结」「共69章」等徽标污染）。
         */
        @JavascriptInterface
        public void setCover(String url, String cover, String title, String path) {
            try {
                if (cover == null || cover.isEmpty()) return;
                lastCoverUrl = cover;
                lastCoverFor = url;
                if (title != null && !title.isEmpty()) lastCoverTitle = title;
            } catch (Throwable ignore) {
            }
        }

        /**
         * 当前页面所属作品是否已收藏（JS 同步取值用于渲染星星状态）。
         * 传进来的可以是任意页面 URL，原生负责归一化成作品维度。
         */
        @JavascriptInterface
        public boolean isFav(String url) {
            try {
                String k = favKeyFor(url);
                if (k == null || k.isEmpty()) return false;
                return store.has("fav", k);
            } catch (Throwable e) {
                return false;
            }
        }

        /**
         * 切换收藏。
         *
         * @return 切换后的状态：{@code true} 已收藏，{@code false} 已取消
         */
        @JavascriptInterface
        public boolean toggleFav(String url, String title) {
            try {
                String k = favKeyFor(url);
                if (k == null || k.isEmpty()) {
                    ui.post(() -> MainActivity.this.toast("这个页面还不能收藏"));
                    return false;
                }
                if (store.has("fav", k)) {
                    store.remove("fav", k);
                    ui.post(() -> MainActivity.this.toast("已取消收藏"));
                    return false;
                }
                final String t = favTitleFor(title, k);
                // ★ v1.9：封面 + 标题优先用站点 [data-manga-*] 给出的官方值
                //   （拿不到就留空/沿用旧值，由 Store.put 保留数据库里已有封面，不会冲掉）。
                // 说明：这里先用普通局部变量算，再一次性赋给 final 变量给 lambda 用
                //       （Java 不允许 final 变量在 try/catch 两个分支里分别赋值）。
                boolean sp = true;
                try {
                    String cf = (lastCoverFor == null || lastCoverFor.isEmpty())
                            ? null : favKeyFor(lastCoverFor);
                    if (cf != null && !k.equals(cf)) sp = false;
                } catch (Throwable ignore) {
                    // 无法判断时按「同一页」处理（乐观），至少不会丢封面
                }
                // cv：本次抓到的封面优先，否则沿用数据库里已有的（之前从详情页收藏过）
                String cvv = null;
                try {
                    if (sp && lastCoverUrl != null && !lastCoverUrl.isEmpty()) cvv = lastCoverUrl;
                } catch (Throwable ignore) {
                }
                if (cvv == null || cvv.isEmpty()) cvv = store.coverOf(k);
                // 站点官方标题比页面 <h1> 干净（不含「已完结」「共69章」等徽标）
                String tt = t;
                try {
                    if (sp && lastCoverTitle != null && !lastCoverTitle.isEmpty()) tt = lastCoverTitle;
                } catch (Throwable ignore) {
                }
                final String fT = tt;
                store.put("fav", fT, k, cvv);
                ui.post(() -> MainActivity.this.toast("已加入书架：" + fT));
                return true;
            } catch (Throwable e) {
                return false;
            }
        }

        /**
         * ★ v1.2：阅读页内的「返回书籍」悬浮按钮走这里。
         *
         * <p>★ v1.10.0：改为直接「关闭阅读器窗口」{@link MainActivity#exitReader()}。
         *
         * <p>为什么不再用 loadUrl 跳详情页：阅读器现在是**独立窗口**，主 WebView
         * 一直就停在详情页原地没动 —— 关掉阅读器，详情页自然露出来，
         * 无需任何 URL 猜测，也不会在历史栈里留下多余的一格。
         * 这与系统返回键走的是同一条路径，行为完全一致。
         */
        @JavascriptInterface
        public void backToBook() {
            ui.post(() -> exitReader());
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
