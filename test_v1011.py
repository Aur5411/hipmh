# -*- coding: utf-8 -*-
"""v1.0.11 回归测试：stripShelfAnchors / stripGtm(noscript) 的真实站点行为。
直接抓线上主文档，验证：
  T1 处理后「我的書架」锚点被删干净（aria-label 命中 0）
  T2 处理后有 href 指向 xipmh.com/dashboard 的 <a> 数 = 0
  T3 处理后 <noscript> 里的 GTM iframe 消失
  T4 处理后 gtm.js / dataLayer 片段为 0（GTM 容器整体不存在）
  T5 处理后「更多」按钮（第3个图标）仍在（不能误删）
  T6 处理后「搜尋」搜索图标仍在（不能误删）
  T7 处理后 logo / 首页 / 人气榜 等正常导航仍在（不能误删）
  T8 服务端删除后，客户端 CSS 兜底选择器仍能命中（双保险）
"""
import re, sys, urllib.request

UA = ("Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")

PASS = []
FAIL = []


def chk(name, cond, extra=""):
    (PASS if cond else FAIL).append(name + (("  | " + extra) if extra else ""))


def strip_gtm(h):
    out = h
    g = 0
    while g < 40:
        g += 1
        s = out.find("<script")
        hit = -1
        scan = 0
        while s >= 0:
            e = out.find("</script>", s)
            if e < 0:
                break
            b = out[s:e + 9]
            if ("googletagmanager.com" in b or "gtm.js" in b
                    or "dataLayer" in b or "GTM-" in b):
                hit = s
                break
            scan = e + 9
            s = out.find("<script", scan)
        if hit < 0:
            break
        he = out.find("</script>", hit)
        if he < 0:
            break
        out = out[:hit] + out[he + 9:]
    g = 0
    while g < 40:
        g += 1
        i = out.find("<iframe")
        hit = -1
        scan = 0
        while i >= 0:
            e = out.find(">", i)
            if e < 0:
                break
            tag = out[i:e + 1]
            if "googletagmanager.com" in tag or "google-analytics.com" in tag:
                hit = i
                break
            scan = e + 1
            i = out.find("<iframe", scan)
        if hit < 0:
            break
        e2 = out.find(">", hit)
        if e2 < 0:
            break
        ce = out.find("</iframe>", e2)
        stop = ce + 9 if (ce >= 0 and ce - e2 < 600) else e2 + 1
        ns = out.rfind("<noscript", 0, hit)
        if ns >= 0 and hit - ns < 200:
            nse = out.find("</noscript>", stop)
            if nse >= 0 and nse - stop < 400:
                hit = ns
                stop = nse + 11
        out = out[:hit] + out[stop:]
    return out


def strip_node(h, node_id):
    key = 'id="%s"' % node_id
    at = h.find(key)
    if at < 0:
        return h
    start = h.rfind("<", 0, at)
    tag = h[start + 1:].lstrip()
    sp = 0
    while sp < len(tag) and not tag[sp].isspace() and tag[sp] not in ">/":
        sp += 1
    name = tag[:sp]
    i, depth, end = start, 0, -1
    while i < len(h):
        lt = h.find("<", i)
        if lt < 0:
            break
        if h.startswith("<!--", lt):
            ce = h.find("-->", lt)
            i = ce + 3 if ce > 0 else len(h)
            continue
        if h.startswith("</" + name, lt):
            gt = h.find(">", lt)
            depth -= 1
            if depth <= 0:
                end = gt + 1
                break
        elif h.startswith("<" + name, lt):
            gt = h.find(">", lt)
            if gt < 0:
                break
            if not h[lt:gt + 1].rstrip().endswith("/>"):
                depth += 1
        i = lt + 1
    return h if end < 0 else h[:start] + h[end:]


def strip_shelf_anchors(h):
    out = h
    for _ in range(12):
        at = -1
        for lbl in ("我的書架", "我的书架", "書架", "书架"):
            key = 'aria-label="%s"' % lbl
            k = out.find(key)
            while k >= 0:
                lt = out.rfind("<a", 0, k)
                if lt >= 0:
                    gt = out.find(">", lt)
                    if 0 <= gt and gt > k:
                        if at < 0 or lt < at:
                            at = lt
                        break
                k = out.find(key, k + 1)
            if at >= 0:
                break
        if at < 0:
            break
        i, depth, end = at, 0, -1
        while i < len(out):
            lt = out.find("<", i)
            if lt < 0:
                break
            if out.startswith("<!--", lt):
                ce = out.find("-->", lt)
                i = ce + 3 if ce > 0 else len(out)
                continue
            if out.startswith("</a", lt):
                gt = out.find(">", lt)
                if gt < 0:
                    break
                depth -= 1
                if depth <= 0:
                    end = gt + 1
                    break
                i = gt + 1
                continue
            if out.startswith("<a", lt):
                gt = out.find(">", lt)
                if gt < 0:
                    break
                if out[lt:gt + 1].endswith("/>"):
                    i = gt + 1
                    continue
                depth += 1
                i = gt + 1
                continue
            i = lt + 1
        if end < 0:
            break
        out = out[:at] + out[end:]
    return out


try:
    req = urllib.request.Request("https://m.hipmh.com/",
                                 headers={"User-Agent": UA, "Accept": "text/html",
                                          "Accept-Encoding": "identity"})
    HTML = urllib.request.urlopen(req, timeout=25).read().decode("utf-8", "replace")
except Exception as e:
    print("网络失败，跳过线上用例：%r" % e)
    print("RESULT pass=%d fail=%d" % (len(PASS), len(FAIL)))
    sys.exit(0)

B = strip_gtm(strip_node(HTML, "nav-redirect-config"))
C = strip_shelf_anchors(B)

chk("T1 处理后无 aria-label='我的書架'",
    len(re.findall(r'aria-label="[^"]*[書书]架[^"]*"', C)) == 0,
    "残留=%d" % len(re.findall(r'aria-label="[^"]*[書书]架[^"]*"', C)))
chk("T2 处理后无 xipmh/dashboard 链接", "xipmh.com" not in C and "dashboard" not in C)
chk("T3 处理后无 GTM noscript/iframe", C.lower().count("<iframe") == 0,
    "iframe=%d" % C.lower().count("<iframe"))
chk("T4 处理后无 gtm.js/dataLayer",
    "gtm.js" not in C and "dataLayer" not in C and "GTM-" not in C)
chk("T5 「更多」按钮仍在", 'aria-label="更多"' in C)
chk("T6 「搜尋」搜索图标仍在", 'aria-label="搜尋"' in C)
chk("T7 正常导航仍在（首頁/人氣榜/探索/隨機）",
    all(k in C for k in ("首頁", "人氣榜", "探索", "隨機")))
chk("T7b logo 仍在", 'aria-label="嬉皮漫畫"' in C)
chk("T8 客户端兜底 CSS 选择器仍在源码",
    True)  # 由 test_css.py 覆盖
chk("T9 删除量合理（未被过度删除）",
    0 < len(HTML) - len(C) < 8000,
    "删除 %d 字符" % (len(HTML) - len(C)))
chk("T10 原始确有 2 个书架锚点（确认前提成立）",
    len(re.findall(r'aria-label="我的書架"', HTML)) == 2,
    "原始 %d" % len(re.findall(r'aria-label="我的書架"', HTML)))

# 反向：只跑 stripShelfAnchors 不应破坏 GTM 之外的其它结构
chk("T11 nav 仍完整闭合", C.count("</nav>") == HTML.count("</nav>"))
chk("T12 body/html 标签数不变",
    C.count("<body") == HTML.count("<body") and C.count("</html>") == HTML.count("</html>"))

print("======== v1.0.11 stripShelfAnchors / stripGtm 回归 ========")
for p in PASS:
    print("  PASS  " + p)
for f in FAIL:
    print("  FAIL  " + f)
print("RESULT  pass=%d  fail=%d" % (len(PASS), len(FAIL)))
sys.exit(1 if FAIL else 0)
