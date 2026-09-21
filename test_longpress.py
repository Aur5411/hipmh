# -*- coding: utf-8 -*-
"""v1.9.4 原生侧回归：长按书架条目不得再弹「第二个窗」（WebView 原生图片菜单）。

背景（真实故障）
    书架条目的封面是 <img>。长按它时两条路径同时命中：
      ① web.setOnLongClickListener → HitTestResult.IMAGE_TYPE
         → showLongPressMenu() → 弹「全屏查看图片 / 复制图片链接 / 在新页面打开」
      ② JS touchstart 的 500ms 定时器 → HipApp.favMenu() → 弹书架菜单
    → 用户看到**两个弹窗**。

修法
    JS 在 touchstart 的第一时间调用 HipApp.shelfTouch(true)，原生在长按回调里
    发现 shelfTouchActive 就直接 return true 吞掉，把长按完全交给 JS 书架菜单。

这个脚本对 MainActivity.java 做**源码级**断言（本项目 test_favbtn.py 的同款做法）。
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "app", "src", "main", "java", "com", "hipmh", "app",
                   "MainActivity.java")

s = open(SRC, encoding="utf-8").read()

passed = 0
failed = 0


def ck(name, cond, extra=""):
    global passed, failed
    if cond:
        passed += 1
        print("  PASS  %s" % name)
    else:
        failed += 1
        print("  FAIL  %s  %s" % (name, extra))


print("[A] 原生长按回调：书架条目上必须吞掉原生菜单")
# 抓 setOnLongClickListener 的整段 lambda
i = s.find("web.setOnLongClickListener(")
j = s.find("web.setWebContentsDebuggingEnabled", i)
seg = s[i:j] if i >= 0 and j > i else ""
ck("找到 setOnLongClickListener 代码块", bool(seg))
ck("★ 回调里引用了 shelfTouchActive 标志", "shelfTouchActive" in seg)
# 必须在 getHitTestResult() 之前就 return true（先判断、后取命中结果）
pos_flag = seg.find("shelfTouchActive")
pos_hit = seg.find("getHitTestResult")
ck("★ shelfTouchActive 判断早于 getHitTestResult()",
   pos_flag >= 0 and pos_hit > pos_flag, "flag@%d hit@%d" % (pos_flag, pos_hit))
ck("★ 命中书架时直接 return true（吞掉）",
   bool(re.search(r"if\s*\(\s*shelfTouchActive\s*\)\s*return\s+true\s*;", seg)),
   seg[:200])
ck("原生图片菜单 showLongPressMenu 仍然保留（非书架区域要能用）",
   "showLongPressMenu" in seg)

print()
print("[B] 字段声明")
ck("shelfTouchActive 已声明为字段", "private volatile boolean shelfTouchActive" in s)
ck("★ 用了 volatile（跨线程：JS 线程写、UI 线程读）",
   "volatile boolean shelfTouchActive" in s)

print()
print("[C] 桥接方法")
ck("存在 shelfTouch(boolean) 桥接", "public void shelfTouch(boolean on)" in s)
ck("桥接带 @JavascriptInterface",
   bool(re.search(r"@JavascriptInterface\s*(?://[^\n]*\n\s*)*public void shelfTouch", s)))
ck("桥接里写入 shelfTouchActive", bool(re.search(
    r"public void shelfTouch\(boolean on\)\s*\{[^}]*shelfTouchActive\s*=\s*on\s*;", s, re.S)))

print()
print("[D] JS 侧上报时机")
# 抽 DRAWER_JS 里的注入片段（Java 字符串拼接）
m = re.search(r"private static String drawerJs\(\)\s*\{(.*?)\n    \}", s, re.S)
drawer = m.group(1) if m else ""
ck("找到 drawerJs() 注入体", bool(drawer))
ck("★ JS 调用了 HipApp.shelfTouch", "shelfTouch" in drawer)
ck("★ touchstart 上报 true", bool(re.search(
    r"shelfTouch\(true\)", drawer)))
ck("★ touchend/cancel 上报 false", bool(re.search(
    r"shelfTouch\(false\)", drawer)))
# 上报 true 必须出现在 500ms 长按定时器之前（即"按下即报"，不能等长按判定）
pos_true = drawer.find("shelfTouch(true)")
pos_timer = drawer.find("tm=setTimeout")
ck("★ shelfTouch(true) 在 500ms 长按定时器之前（按下即报）",
   pos_true >= 0 and pos_timer > pos_true, "true@%d timer@%d" % (pos_true, pos_timer))
ck("★ touchmove 用位移阈值判断（轻微抖动不复位）",
   "dx>12||dy>12" in drawer or "dx > 12" in drawer)
ck("★ 有兜底复位定时器（防止标记卡死）", "arm()" in drawer or "rst=setTimeout" in drawer)

print()
print("[E] CSS 双保险")
ck("封面 img 有 pointer-events:none", bool(re.search(
    r"\.__hipCv\{[^}]*pointer-events:none", drawer)))
ck("条目禁掉 -webkit-touch-callout", "-webkit-touch-callout:none" in drawer)

print()
print("==== %d passed, %d failed ====" % (passed, failed))
sys.exit(1 if failed else 0)
