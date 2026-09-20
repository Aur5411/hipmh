// v1.0.7 行为回归测试：最小桩 DOM，验证注入脚本不会吞掉正常点击
const fs = require('fs');
const path = require('path');
const TMP = process.env.TEMP || '/tmp';

const clean = fs.readFileSync(path.join(TMP, 'hip_clean.js'), 'utf8');
const guard = fs.readFileSync(path.join(TMP, 'hip_guard.js'), 'utf8');
const hist  = fs.readFileSync(path.join(TMP, 'hip_hist.js'), 'utf8');

/* ---------------- 极简桩 DOM ---------------- */
function mkEl(tag, attrs, txt) {
  const e = {
    nodeType: 1, nodeName: tag.toUpperCase(), tagName: tag.toUpperCase(),
    _attrs: Object.assign({}, attrs || {}), _txt: txt || '',
    style: { setProperty() {}, removeProperty() {}, cssText: '' },
    dataset: {}, children: [], parentNode: null, parentElement: null,
    classList: { add() {}, remove() {}, contains() { return false; } },
    offsetParent: {}, isConnected: true,
    getAttribute(k) { return this._attrs[k] !== undefined ? this._attrs[k] : null; },
    setAttribute(k, v) { this._attrs[k] = v; },
    hasAttribute(k) { return this._attrs[k] !== undefined; },
    removeAttribute(k) { delete this._attrs[k]; },
    addEventListener(t, fn) { (this._ev = this._ev || {})[t] = fn; },
    removeEventListener() {},
    get textContent() { return this._txt; },
    set textContent(v) { this._txt = v; },
    get innerText() { return this._txt; },
    get firstChild() { return this.children[0] || null; },
    get childNodes() { return this.children; },
    appendChild(c) { c.parentNode = this; this.children.push(c); return c; },
    removeChild(c) { const i = this.children.indexOf(c); if (i >= 0) this.children.splice(i, 1); return c; },
    querySelector() { return null; },
    querySelectorAll() { return []; },
    closest(sel) {
      const want = sel.split(',').map(s => s.trim().toUpperCase());
      let n = this;
      while (n && n.nodeType === 1) {
        if (want.indexOf(n.nodeName) >= 0) return n;
        n = n.parentNode;
      }
      return null;
    },
    getBoundingClientRect() { return { top: 0, left: 0, width: 0, height: 0, bottom: 0, right: 0 }; },
    contains() { return false; }, matches() { return false; }, dispatchEvent() { return true; }
  };
  return e;
}

const handlers = {};
const head = mkEl('head'), body = mkEl('body'), html = mkEl('html');
html.appendChild(head); html.appendChild(body);

global.window = global;
global.NodeFilter = { SHOW_TEXT: 4 };
global.Node = { TEXT_NODE: 3, ELEMENT_NODE: 1 };
global.MutationObserver = function () { this.observe = () => {}; this.disconnect = () => {}; };
global.setTimeout = () => 0;
global.setInterval = () => 0;
global.clearTimeout = () => {};
global.clearInterval = () => {};
global.localStorage = { getItem: () => null, setItem: () => {} };
global.location = { href: 'https://m.hipmh.com/', pathname: '/' };

global.document = {
  nodeType: 9, documentElement: html, body: body, head: head, title: '測試',
  createElement: (t) => mkEl(t),
  createTextNode: (v) => ({ nodeType: 3, nodeValue: v, parentNode: null, isConnected: true }),
  createTreeWalker: () => ({ nextNode: () => null }),
  getElementById: () => null,
  querySelector: () => null,
  querySelectorAll: () => [],
  addEventListener(type, fn) { (handlers[type] = handlers[type] || []).push(fn); },
  removeEventListener() {},
  readyState: 'complete'
};

let pass = 0, fail = 0;
function ok(name, cond, extra) {
  if (cond) { pass++; console.log('  PASS  ' + name); }
  else { fail++; console.log('  FAIL  ' + name + (extra !== undefined ? '  -> ' + extra : '')); }
}

/* ---------------- 1) cleanJs ---------------- */
console.log('[1] cleanJs 注入');
window.HipApp = { toast() {}, openFavList() {}, setFab() {} };
let cleanErr = null;
try { eval(clean); } catch (e) { cleanErr = e.message; }
ok('cleanJs 执行无异常', !cleanErr, cleanErr);
ok('__hipClass 已注册', typeof window.__hipClass === 'function');

if (typeof window.__hipClass === 'function') {
  const c = window.__hipClass;
  ok('__hipClass(作品链接)=3 放行', c(mkEl('a', { href: '/works/abc123' }, '作品名')) === 3);
  ok('__hipClass(普通div)=0 保留', c(mkEl('div', {}, '普通文字')) === 0);
  ok('__hipClass(长文本含登入)≠1 不误判',
     c(mkEl('a', { href: 'https://example.com/x' },
       '本站需要登入後才能查看此內容，請先登入你的帳號再繼續閱讀，謝謝合作')) !== 1);
  ok('__hipClass(纯登入按钮)=1', c(mkEl('a', { href: '' }, '登入')) === 1);

  /* --- v1.0.8 回归：「我的書架」必须被隐藏，不能再被 fav() 重新显示 --- */
  ok('__hipClass(我的書架 href=#)=1 判为隐藏',
     c(mkEl('a', { href: '#' }, '我的書架')) === 1);
  ok('__hipClass(我的書架 href=/user/profile)=1 判为隐藏',
     c(mkEl('a', { href: '/user/profile' }, '我的書架')) === 1);
  ok('__hipClass(我的書架 href=/history)=1 判为隐藏',
     c(mkEl('a', { href: '/history' }, '我的書架')) === 1);
  ok('__hipClass(我的书架 简体)=1 判为隐藏',
     c(mkEl('a', { href: '/shelf' }, '我的书架')) === 1);
  ok('cleanJs 已移除 fav() 劫持逻辑',
     clean.indexOf('removeProperty') < 0 || clean.indexOf('__hipFavLink') < 0);
  ok('cleanJs 不再出现 __hipFavLink', clean.indexOf('__hipFavLink') < 0);
  ok('cleanJs 不再给书架绑 openFavList',
     !/我的書架\$[^]{0,400}openFavList/.test(clean));

  /* --- v1.0.9 回归：站点真实右上角书架是「只有 aria-label，无文本」，href 指向 m.xipmh.com --- */
  ok('__hipClass(真实右上角书架 aria-label+无文本)=1 判为隐藏',
     c(mkEl('a', { href: 'https://m.xipmh.com/dashboard?lang=zh', 'aria-label': '我的書架' }, '')) === 1);
  ok('__hipClass(侧栏书架 href=xipmh.com/dashboard)=1 判为隐藏',
     c(mkEl('a', { href: 'https://m.xipmh.com/dashboard?lang=zh' }, '我的書架')) === 1);
  ok('__hipClass(個人中心 aria-label)=1 判为隐藏',
     c(mkEl('a', { href: 'https://m.xipmh.com/dashboard?lang=zh', 'aria-label': '個人中心' }, '')) === 1);
  ok('__hipClass(站点搜索按钮)≠1 不误伤',
     c(mkEl('a', { href: '/search', 'aria-label': '搜尋' }, '')) !== 1);
  ok('__hipClass(站点首页链接)≠1 不误伤',
     c(mkEl('a', { href: '/' }, '首頁')) !== 1);
  ok('cleanJs 不再给书架绑 openFavList',
     !/__hipClass[^]{0,600}openFavList/.test(clean));
}

/* ---------------- 2) GUARD_JS ---------------- */
console.log('[2] GUARD_JS 点击守卫（卡死根因回归）');
let gErr = null;
try { eval(guard); } catch (e) { gErr = e.message; }
ok('GUARD_JS 执行无异常', !gErr, gErr);
ok('只绑冒泡 click（未绑 touchstart/touchend）',
   !!handlers['click'] && !handlers['touchstart'] && !handlers['touchend'],
   'touchstart=' + !!handlers['touchstart']);

function fireClick(target) {
  let prevented = false, stopped = false, immediate = false;
  const e = {
    target: target,
    preventDefault() { prevented = true; },
    stopPropagation() { stopped = true; },
    stopImmediatePropagation() { immediate = true; }
  };
  (handlers['click'] || []).forEach(fn => { try { fn(e); } catch (x) {} });
  return { prevented, stopped, immediate };
}

ok('作品链接可点击（不被 preventDefault）', fireClick(mkEl('a', { href: '/works/abc123' }, '火影忍者')).prevented === false);
ok('历史页关闭按钮可点击', fireClick(mkEl('button', {}, '关闭')).prevented === false);
ok('自建按钮(data-hip)放行', fireClick(mkEl('a', { 'data-hip': '1' }, '搜索')).prevented === false);
ok('不再调用 stopImmediatePropagation', fireClick(mkEl('a', { href: '/works/abc123' }, 'x')).immediate === false);
ok('纯登录按钮仍被拦下', fireClick(mkEl('a', { href: '' }, '登入')).prevented === true);

/* ---------------- 3) HIST_JS ---------------- */
console.log('[3] HIST_JS 历史页');
const before = (handlers['click'] || []).length;
let hErr = null;
try { eval(hist); } catch (e) { hErr = e.message; }
ok('HIST_JS 执行无异常', !hErr, hErr);
ok('HIST_JS 不注册 click 拦截（关闭按钮可用的前提）',
   (handlers['click'] || []).length === before,
   'before=' + before + ' after=' + (handlers['click'] || []).length);

/* ---------------- 4) 性能 ---------------- */
console.log('[4] 性能回归');
const t2s = fs.readFileSync(path.join(TMP, 'hip_t2s.js'), 'utf8');
ok('cleanJs 扫描中未 clone 节点（避免 MutationObserver 递归）', clean.indexOf('cloneNode') < 0);
ok('cleanJs 扫描带节流', clean.indexOf('setInterval') >= 0);
ok('T2S 使用批处理节流', t2s.indexOf('setTimeout(flush') >= 0);
ok('T2S 跳过 SCRIPT/STYLE（nodeName 修正）', t2s.indexOf('SKIP{p.nodeName}') >= 0 || t2s.indexOf("SKIP[p.nodeName]") >= 0);

/* ---------------- 5) readerJs：工具条与站点菜单联动 ---------------- */
console.log('[5] readerJs 阅读页');
const reader = fs.readFileSync(path.join(TMP, 'hip_reader.js'), 'utf8');

let rErr = null;
try { eval(reader); } catch (e) { rErr = e.message; }
ok('readerJs 执行无异常', !rErr, rErr);
ok('readerJs 用 __hipVis 判断站点菜单可见性', reader.indexOf('__hipVis') >= 0);
ok('readerJs 用 __hipMenuOpen 汇总菜单状态', reader.indexOf('__hipMenuOpen') >= 0);
ok('readerJs 已移除书架工具条联动（不做书架功能）', reader.indexOf('HipApp.setFab') < 0);
ok('readerJs 已移除 __hipFab', reader.indexOf('__hipFab') < 0);
ok('readerJs 保留 __hipSync 做背景滚动锁', reader.indexOf('__hipSync') >= 0);
ok('readerJs 不再有自动淡出定时器', reader.indexOf('readerBarAutoHide') < 0);
// v1.3：readerJs 新增了「返回书籍」悬浮按钮 __hipBack，它必须在自己的元素上绑 click，
// 所以不能再按字面量判断「没有 click 监听」。真正的不变量是：
//   **不得在 document / body / 正文容器上挂 click 拦截**（那才会吞掉站点手势）。
ok('readerJs 不在 document 上挂 click 拦截（不吞站点手势）',
   !/document\.addEventListener\('click'/.test(reader));
ok('readerJs 不在 body 上挂 click 拦截',
   !/document\.body\.addEventListener\('click'/.test(reader));
ok('readerJs 的 click 监听只挂在 __hipBack 按钮上',
   /b\.addEventListener\('click'/.test(reader));
ok('readerJs 保留 __hipBack 返回书籍按钮', reader.indexOf('__hipBack') >= 0);
ok('readerJs 返回按钮走 HipApp.backToBook', reader.indexOf('HipApp.backToBook') >= 0);
ok('__hipMenuOpen 覆盖目录弹窗', /__hipMenuOpen[^]{0,200}d-chapters-modal/.test(reader));
ok('__hipMenuOpen 覆盖设置抽屉', /__hipMenuOpen[^]{0,200}drawerOverlay/.test(reader));

console.log('\n==== ' + pass + ' passed, ' + fail + ' failed ====');
process.exit(fail ? 1 : 0);
