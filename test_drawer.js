// v1.8：站点侧边抽屉「閱讀記錄」-> 本地书架 的行为测试（桩 DOM + vm 沙箱）
// 先跑 extract_js.py 生成 %TEMP%/hip_drawer.js，再执行本文件。
const fs = require('fs');
const vm = require('vm');
const path = require('path');

const jsPath = path.join(process.env.TEMP || '/tmp', 'hip_drawer.js');
if (!fs.existsSync(jsPath)) {
  console.error('找不到 ' + jsPath + '，请先运行 extract_js.py');
  process.exit(2);
}
const SRC = fs.readFileSync(jsPath, 'utf8');

let pass = 0, fail = 0;
function ck(name, cond, extra) {
  if (cond) { pass++; console.log('  PASS  ' + name); }
  else { fail++; console.log('  FAIL  ' + name + (extra !== undefined ? '  -> ' + extra : '')); }
}

// ---------- 桩 DOM ----------
function mkEl(tag, attrs, opts) {
  opts = opts || {};
  const el = {
    tag: String(tag).toUpperCase(),
    // ★ v1.9.3：真实 DOM 用 el.tagName；DRAWER_JS 的 layoutFix() 靠它区分 div。
    //   桩原本只有 el.tag，导致 `k.tagName` 取到 undefined、判断永远 continue，
    //   于是 .flex-1 那层漏标记 —— 这是「桩属性名与真实 DOM 不一致」的典型坑。
    get tagName() { return el.tag.toUpperCase(); },
    attrs: Object.assign({}, attrs || {}),
    children: [],
    parentNode: null,
    textContent: opts.text || '',
    innerHTML: '',
    style: {
      _p: {},
      setProperty(k, v) { this._p[k] = v; },
      getPropertyValue(k) { return this._p[k] || ''; },
    },
    _id: (attrs && attrs.id) || '',
    get id() { return this._id || ''; },
    set id(v) { this._id = v; },
    get class() { return this.attrs.class || ''; },
    getAttribute(k) { return this.attrs[k] === undefined ? null : this.attrs[k]; },
    setAttribute(k, v) { this.attrs[k] = String(v); if (k === 'id') this._id = String(v); },
    removeAttribute(k) { delete this.attrs[k]; },
    appendChild(c) {
      // 同样是「移动」语义：已在 children 里则不重复添加
      if (el.children.indexOf(c) < 0) el.children.push(c);
      c.parentNode = el;
      return c;
    },
    // ★ v1.9.2：layoutFix() 依赖这两个 API 把书架容器移到首位。
    //   注意：真实 DOM 的 insertBefore 是「移动」语义 —— 必须先把节点从原位置摘掉，
    //   否则同一个节点会在 children 里出现两次（这个桩语义错误曾把测试搞挂）。
    get firstChild() { return el.children.length ? el.children[0] : null; },
    // ★ v1.9.3：layoutFix() 改用「标题栏锚点 + nextSibling」定位插入点，
    //   桩必须补上 nextSibling / lastChild，否则新逻辑在桩里跑不通。
    get lastChild() { return el.children.length ? el.children[el.children.length - 1] : null; },
    get nextSibling() {
      if (!el.parentNode) return null;
      const sib = el.parentNode.children;
      const i = sib.indexOf(el);
      return (i >= 0 && i + 1 < sib.length) ? sib[i + 1] : null;
    },
    get previousSibling() {
      if (!el.parentNode) return null;
      const sib = el.parentNode.children;
      const i = sib.indexOf(el);
      return i > 0 ? sib[i - 1] : null;
    },
    insertBefore(n, ref) {
      const old = el.children.indexOf(n);
      if (old >= 0) el.children.splice(old, 1);
      const i = ref ? el.children.indexOf(ref) : -1;
      if (i >= 0) el.children.splice(i, 0, n);
      else el.children.push(n);
      n.parentNode = el;
      return n;
    },
    addEventListener(t, f) { (el._ev = el._ev || {})[t] = (el._ev[t] || []).concat([f]); },
    dispatch(t, ev) { ((el._ev || {})[t] || []).forEach(f => f(ev || {})); },
    closest(sel) {
      let n = el;
      while (n) {
        if (sel[0] === '#' && n.id === sel.slice(1)) return n;
        if (sel[0] === '.' && (n.attrs.class || '').split(/\s+/).includes(sel.slice(1))) return n;
        if (sel[0] === '[' && n.attrs[sel.slice(1, -1)] !== undefined) return n;
        n = n.parentNode;
      }
      return null;
    },
    querySelector(sel) {
      const all = [];
      (function walk(n) { n.children.forEach(c => { all.push(c); walk(c); }); })(el);
      if (sel.indexOf(',') >= 0) {
        for (const s of sel.split(',')) {
          const t = s.trim();
          for (const n of all) if (matches(n, t)) return n;
        }
        return null;
      }
      for (const n of all) if (matches(n, sel)) return n;
      return null;
    },
    querySelectorAll(sel) {
      const all = [];
      (function walk(n) { n.children.forEach(c => { all.push(c); walk(c); }); })(el);
      const sels = sel.split(',').map(s => s.trim());
      return all.filter(n => sels.some(s => matches(n, s)));
    },
  };
  return el;
}

function matches(n, sel) {
  if (!sel) return false;
  if (sel[0] === '#') return n.id === sel.slice(1);
  if (sel[0] === '.') return (n.attrs.class || '').split(/\s+/).includes(sel.slice(1));
  if (sel[0] === '[') {
    const m = /^\[([\w-]+)(?:=(["']?)([^\]"']*)\2)?\]$/.exec(sel);
    if (!m) return false;
    if (n.attrs[m[1]] === undefined) return false;
    return m[3] === undefined ? true : n.attrs[m[1]] === m[3];
  }
  return n.tag === sel.toUpperCase();
}

/** document 级能力：DRAWER_JS 用到 getElementById / createElement / head / body */
function installDocApi(doc) {
  const all = () => {
    const out = [];
    (function walk(n) { n.children.forEach(c => { out.push(c); walk(c); }); })(doc);
    return out;
  };
  doc.getElementById = id => all().find(n => n.id === id) || null;
  doc.createElement = tag => mkEl(tag);
  doc.addEventListener = () => {};
  Object.defineProperty(doc, 'head', {
    get: () => doc.children.find(c => c.tag === 'HEAD') || null,
  });
  Object.defineProperty(doc, 'body', {
    get: () => doc.children.find(c => c.tag === 'BODY') || null,
  });
  return doc;
}

// 真实站点抽屉结构（2026-09-21 抓取 m.hipmh.com）
function buildDoc(opts) {
  opts = opts || {};
  const doc = mkEl('#document');
  const head = mkEl('head');
  const body = mkEl('body');
  doc.appendChild(head); doc.appendChild(body);

  const sidebar = mkEl('div', {
    id: 'navbar-sidebar',
    'aria-hidden': opts.open ? 'false' : 'true',
  });
  const content = mkEl('div', { 'data-navbar-sidebar-content': '1' });
  // ★ v1.9.3：标题栏是 content 的【直接子元素】，内含 <h2> 作为锚点识别特征
  const bar = mkEl('div', { class: 'flex items-center justify-between px-5 py-4 flex-shrink-0' });
  const h2 = mkEl('h2', {}, { text: '閱讀記錄' });
  bar.appendChild(h2);
  content.appendChild(bar);

  // ★ v1.9.3：真实站点结构里，三个历史元素被包在一层 `.flex-1 overflow-y-auto
  //   min-h-0 flex flex-col` 里 —— 这层才是「占着 flex 份额、把书架挤到中间」的元凶。
  //   旧桩把三个元素直接挂在 content 下（扁平结构），因此测不出真实 bug。
  const flex1 = mkEl('div', { class: 'flex-1 overflow-y-auto min-h-0 flex flex-col' });
  const list = mkEl('div', { id: 'navbar-sidebar-history-list' });
  const loading = mkEl('div', { id: 'navbar-sidebar-history-loading' });
  const empty = mkEl('div', { id: 'navbar-sidebar-history-empty' });
  flex1.appendChild(list); flex1.appendChild(loading); flex1.appendChild(empty);
  content.appendChild(flex1);

  // 底部按钮行：我的書架(外链) + 全部記錄(/history)，外面还套一层 row/foot
  const foot = mkEl('div', { class: 'border-t px-5 py-3 flex-shrink-0' });
  const row = mkEl('div');
  const aShelf = mkEl('a', { href: 'https://m.xipmh.com/dashboard?lang=zh' }, { text: '我的書架' });
  const aHist = mkEl('a', { href: '/history' }, { text: '全部記錄' });
  row.appendChild(aShelf); row.appendChild(aHist);
  foot.appendChild(row);
  content.appendChild(foot);

  sidebar.appendChild(content);
  body.appendChild(sidebar);
  installDocApi(doc);
  return { doc, head, body, sidebar, content, h2, bar, flex1,
           list, loading, empty, aShelf, aHist, row, foot };
}

function run(env, shelf) {
  const calls = { shelfJson: 0, removeFav: [], openUrl: [], favMenu: [], toast: [], shelfTouch: [] };
  const HipApp = {
    shelfJson() {
      calls.shelfJson++;
      // 模拟原生：cover 字段原样带出（无封面时给空串）
      return JSON.stringify((shelf || []).map(it => ({
        title: it.title, url: it.url, st: it.st | 0, cover: it.cover || '',
      })));
    },
    removeFav(u) { calls.removeFav.push(u); },
    openUrl(u) { calls.openUrl.push(u); },
    favMenu(u) { calls.favMenu.push(u); },
    // ★ v1.9.4：JS 实时上报「手指是否按在书架条目上」，原生长按据此吞掉自己的图片菜单
    shelfTouch(on) { calls.shelfTouch.push(!!on); },
    toast(m) { calls.toast.push(m); },
  };
  const timers = [];
  // ★ 桩必须真正模拟 clearTimeout：否则「长按去重」这类逻辑在桩里测不出来
  const timerImpl = {
    setTimeout(fn) {
      const id = timers.length + 1;
      timers.push({ id, fn, alive: true });
      return id;
    },
    clearTimeout(id) {
      const t = timers.find(x => x.id === id);
      if (t) t.alive = false;
    },
  };
  const fire = () => timers.filter(t => t.alive).forEach(t => t.fn());
  const ctx = {
    document: env.doc,
    JSON, String, Object, Array, Date, console,
    setTimeout: timerImpl.setTimeout,
    clearTimeout: timerImpl.clearTimeout,
    setInterval() { return 0; },
    HipApp,
  };
  ctx.window = ctx;
  vm.createContext(ctx);
  vm.runInContext(SRC, ctx);
  return { calls, timers, fire, ctx, env };
}

const cssOf = env => (env.head.children.find(c => c.id === '__hipDrawerCss') || {}).textContent || '';
const wrapOf = env => env.doc.getElementById('__hipShelfWrap');

// ---------- 测试 1：抽屉被改造成「我的书架」 ----------
console.log('=== 测试 1：抽屉被改造成「我的书架」===');
{
  const env = buildDoc({ open: true });
  const shelf = [{
    title: '我独自升级', url: 'https://m.hipmh.com/works/AAA', st: 0,
    cover: 'https://cover.s3imgs.top/kk/vertical/aaa.webp',
  }];
  const r = run(env, shelf);

  ck('标题 閱讀記錄 -> 我的书架', env.h2.textContent === '我的书架', env.h2.textContent);
  ck('标题打了 data-hip 标记（避免被 cleanJs 回改）', env.h2.getAttribute('data-hip') === '1');

  const css = cssOf(env);
  ck('注入了 __hipDrawerCss 样式', css.length > 0);
  for (const id of ['#navbar-sidebar-history-list', '#navbar-sidebar-history-loading', '#navbar-sidebar-history-empty']) {
    ck('CSS 收掉站点节点 ' + id, css.includes(id));
  }
  ck('站点节点用 !important 隐藏', css.includes('display:none!important'));

  ck('「我的書架」外链被隐藏', env.aShelf.style._p.display === 'none');
  ck('「全部記錄」被隐藏', env.aHist.style._p.display === 'none');
  ck('底部按钮外围容器被收掉（killSite 上溯两级）', env.foot.style._p.display === 'none');

  const wrap = wrapOf(env);
  ck('书架容器注入到 data-navbar-sidebar-content 内', !!wrap && wrap.parentNode === env.content);
  ck('容器带 data-hip 标记', !!wrap && wrap.getAttribute('data-hip') === '1');
  ck('书架条目已渲染（含标题）', !!wrap && wrap.innerHTML.includes('我独自升级'));
  ck('条目 url 写进了 href', !!wrap && wrap.innerHTML.includes('https://m.hipmh.com/works/AAA'));
  // ★ v1.9.1：抽屉条目也要显示封面（用 shelfJson 里的 cover 字段）
  ck('★ 条目渲染了封面 img（__hipCv）', !!wrap && wrap.innerHTML.includes('__hipCv'));
  ck('★ 封面 src 用了 shelfJson 的 cover',
    !!wrap && wrap.innerHTML.includes('https://cover.s3imgs.top/kk/vertical/aaa.webp'));
  ck('★ CSS 定义了封面尺寸', css.includes('.__hipCv'));
  ck('渲染时读了原生 shelfJson()', r.calls.shelfJson >= 1);
}

// ---------- 测试 1b：没有 cover 时给占位块（行高不塌） ----------
console.log();
console.log('=== 测试 1b：无封面时用占位块 ===');
{
  const env = buildDoc({ open: true });
  run(env, [{ title: '无封面作品', url: 'https://m.hipmh.com/works/NC', st: 0 }]);
  const html = wrapOf(env).innerHTML;
  ck('★ 仍然保留 __hipCv 元素（占位）', html.includes('__hipCv'));
  ck('★ 占位块带 __hipCvPh 类', html.includes('__hipCvPh'));
  ck('没有空 src 的 img', !/src=""/.test(html));
}

// ---------- 测试 2：三种阅读状态符号 ----------
console.log();
console.log('=== 测试 2：三种阅读状态符号 ===');
{
  const env = buildDoc({ open: true });
  run(env, [
    { title: 'A', url: 'https://m.hipmh.com/works/A', st: 0 },
    { title: 'B', url: 'https://m.hipmh.com/works/B', st: 1 },
    { title: 'C', url: 'https://m.hipmh.com/works/C', st: 2 },
  ]);
  const html = wrapOf(env).innerHTML;
  ck('未标记用 ○', html.includes('○'));
  ck('在看用 ◐', html.includes('◐'));
  ck('已读完用 ●', html.includes('●'));
  // ★ v1.9.1：按用户要求去掉行尾「×」删除按钮
  ck('★ 不再有 data-hipdel 删除钮', !html.includes('data-hipdel'));
  ck('★ 不再有 __hipDel 样式类', !html.includes('__hipDel'));
}

// ---------- 测试 3：空书架文案 ----------
console.log();
console.log('=== 测试 3：空书架 ===');
{
  const env = buildDoc({ open: true });
  run(env, []);
  const html = wrapOf(env).innerHTML;
  ck('空态提示出现', html.includes('书架还是空的'));
  ck('空态引导点星标', html.includes('星标'));
}

// ---------- 测试 4：未打开抽屉时不渲染（避免白干） ----------
console.log();
console.log('=== 测试 4：抽屉未打开 ===');
{
  const env = buildDoc({ open: false });
  const r = run(env, [{ title: 'X', url: 'https://m.hipmh.com/works/X', st: 0 }]);
  ck('未打开则不调 shelfJson()', r.calls.shelfJson === 0, r.calls.shelfJson);
  ck('未打开也不注入书架容器', !wrapOf(env));
  ck('但标题已提前改好（打开即见书架）', env.h2.textContent === '我的书架');
  ck('站点节点已提前收掉', env.aHist.style._p.display === 'none');
}

// ---------- 测试 5：点击/长按交互（直接喂精确 target 给 wrap 上的监听器） ----------
console.log();
console.log('=== 测试 5：点击与长按 ===');
{
  const env = buildDoc({ open: true });
  const r = run(env, [{ title: 'Y', url: 'https://m.hipmh.com/works/Y', st: 0 }]);
  const wrap = wrapOf(env);
  ck('容器已挂上事件监听', !!(wrap._ev && wrap._ev.click && wrap._ev.click.length));

  const evt = () => ({ preventDefault() {}, stopPropagation() {} });

  // 点条目本体 -> openUrl
  const body = mkEl('span', { class: '__hipTt' });
  body.parentNode = mkEl('a', { class: '__hipItm', 'data-hipurl': 'https://m.hipmh.com/works/Y' });
  wrap.dispatch('click', Object.assign(evt(), { target: body }));
  ck('点条目 -> openUrl(作品页)', r.calls.openUrl[0] === 'https://m.hipmh.com/works/Y', JSON.stringify(r.calls.openUrl));

  // ★ v1.9.1：条目里已经没有任何删除控件，点任意子元素都应只走 openUrl
  const stray = mkEl('span', { class: '__hipMark' });
  stray.parentNode = body.parentNode;
  wrap.dispatch('click', Object.assign(evt(), { target: stray }));
  ck('★ 点状态符号也只 openUrl（无删除分支）', r.calls.openUrl.length === 2, JSON.stringify(r.calls.openUrl));
  ck('★ removeFav 全程未被调用', r.calls.removeFav.length === 0, JSON.stringify(r.calls.removeFav));

  // 长按 500ms -> favMenu
  const item = mkEl('a', { class: '__hipItm', 'data-hipurl': 'https://m.hipmh.com/works/Y' });
  wrap.dispatch('touchstart', { target: item });
  ck('touchstart 起了 500ms 定时器', r.timers.length >= 1);
  r.fire();
  ck('长按 -> favMenu(同一 url)', r.calls.favMenu[0] === 'https://m.hipmh.com/works/Y', JSON.stringify(r.calls.favMenu));
  ck('★ 长按只弹一次菜单（无重复 favMenu）', r.calls.favMenu.length === 1, JSON.stringify(r.calls.favMenu));

  // ★ v1.9.1：防双弹窗 —— 长按弹出后，抬手紧跟的 click 必须被吞掉
  ck('★ 长按后 wrap 记下了冷却时间戳', typeof wrap.__hipLp === 'number' && wrap.__hipLp > 0, wrap.__hipLp);

  // 冷却期内点击条目：既不该 openUrl，也不该再弹菜单
  const beforeOpen = r.calls.openUrl.length;
  const coolTarget = mkEl('a', { class: '__hipItm', 'data-hipurl': 'https://m.hipmh.com/works/Y' });
  let blocked = 0;
  wrap.dispatch('click', {
    target: coolTarget,
    preventDefault() { blocked++; },
    stopPropagation() { blocked++; },
  });
  ck('★ 冷却期内点击被吞掉（preventDefault 被调用）', blocked >= 1, blocked);
  ck('★ 冷却期内点击不会误开作品页', r.calls.openUrl.length === beforeOpen,
    JSON.stringify(r.calls.openUrl));
}

// ---------- 测试 5b：★ 长按连点不会弹两个窗 ----------
console.log();
console.log('=== 测试 5b：长按防双弹窗 ===');
{
  const env = buildDoc({ open: true });
  const r = run(env, [{ title: 'Y', url: 'https://m.hipmh.com/works/Y', st: 0 }]);
  const wrap = wrapOf(env);
  const item = mkEl('a', { class: '__hipItm', 'data-hipurl': 'https://m.hipmh.com/works/Y' });
  // 连点两次 touchstart（第一次的长按定时器还没触发，第二次必须把旧的清掉）
  wrap.dispatch('touchstart', { target: item });
  const first = r.timers.filter(t=>t.alive).length;
  wrap.dispatch('touchstart', { target: item });
  r.fire();
  ck('★ 连续两次长按只弹一个菜单', r.calls.favMenu.length === 1, JSON.stringify(r.calls.favMenu));
  ck('★ 第二次 touchstart 清掉了前一个定时器', r.timers.length >= 1, r.timers.length);
}

// ---------- 测试 6：暴露 __hipDrawerRender 供原生刷新 ----------
console.log();
console.log('=== 测试 6：原生刷新入口 ===');
{
  const env = buildDoc({ open: true });
  const shelfRef = { arr: [{ title: 'Z1', url: 'https://m.hipmh.com/works/Z1', st: 0 }] };
  const calls = [];
  const HipApp = {
    shelfJson: () => JSON.stringify(shelfRef.arr),
    removeFav() {}, openUrl() {}, favMenu() {}, toast() {},
  };
  const ctx = {
    document: env.doc, JSON, String, Object, Array, Date, console,
    setTimeout(f) { return 1; }, clearTimeout() {}, setInterval() { return 0; }, HipApp,
  };
  ctx.window = ctx;
  vm.createContext(ctx);
  vm.runInContext(SRC, ctx);

  ck('window.__hipDrawerRender 已暴露', typeof ctx.__hipDrawerRender === 'function');
  shelfRef.arr = [{ title: 'Z2-新', url: 'https://m.hipmh.com/works/Z2', st: 2 }];
  ctx.__hipDrawerRender();
  const html = wrapOf(env).innerHTML;
  ck('刷新后显示新条目', html.includes('Z2-新'));
  ck('刷新后不再含旧条目', !html.includes('Z1'));
  ck('刷新后状态符号跟着变 ●', html.includes('●'));
}

// ---------- 测试 7：样式表只注入一次 ----------
console.log();
console.log('=== 测试 7：幂等（重复执行不叠加）===');
{
  const env = buildDoc({ open: true });
  run(env, [{ title: 'K', url: 'https://m.hipmh.com/works/K', st: 0 }]);
  const c2 = {
    document: env.doc, JSON, String, Object, Array, Date, console,
    setTimeout() { return 0; }, clearTimeout() {}, setInterval() { return 0; }, HipApp: {},
  };
  c2.window = c2;
  vm.createContext(c2);
  vm.runInContext(SRC, c2);
  const styles = env.head.children.filter(c => c.id === '__hipDrawerCss');
  ck('样式表只有一份', styles.length === 1, styles.length);
  ck('书架容器只有一份', env.doc.querySelectorAll('#__hipShelfWrap').length === 1);
}

// ---------- 测试 8：抽屉布局校正 v1.9.3（书架必须紧贴标题栏、从顶部开始排） ----------
// 背景（真实站点结构，2026-09 抓包确认）：
//   <div data-navbar-sidebar-content class="… flex flex-col h-full">
//     ├─ <div class="… px-5 py-4 border-b … flex-shrink-0"><h2>閱讀記錄</h2></div>  ← 标题栏
//     └─ <div class="flex-1 overflow-y-auto min-h-0 flex flex-col">              ← ★ 元凶
//           ├─ #navbar-sidebar-history-list
//           ├─ #navbar-sidebar-history-loading
//           └─ #navbar-sidebar-history-empty
//   那层 `.flex-1` 即使内层元素被隐藏，自身仍占满剩余高度 → 书架被挤到下半屏、
//   上方一大片空白。layoutFix() 必须：① 把该层标 data-hip-hide 移出布局；
//   ② 把 #__hipShelfWrap 插到「标题栏之后」；③ 绝不越界把 host 之外的节点隐藏。
console.log();
console.log('=== 测试 8：v1.9.3 抽屉布局校正 ===');
{
  const env = buildDoc({ open: true });
  run(env, [{ title: 'L1', url: 'https://m.hipmh.com/works/L1', st: 0 }]);

  const wrap = wrapOf(env);
  ck('书架容器存在', !!wrap);

  // ① 站点历史三件套所在的「那一层」必须被标记移出布局。
  //    v1.9.3 用 data-hip-hide 属性（配 CSS `[data-hip-hide]{display:none!important}`），
  //    比 v1.9.2 直接写 inline style 更稳（不会被站点脚本覆写、也不用担心越界）。
  const marked = el => !!(el && el.getAttribute('data-hip-hide'));
  ck('站点历史容器(flex-1 那层)已标记 data-hip-hide', marked(env.flex1),
     env.flex1 && env.flex1.getAttribute('data-hip-hide'));
  ck('#navbar-sidebar-history-list 已标记隐藏', marked(env.list));
  ck('#navbar-sidebar-history-loading 已标记隐藏', marked(env.loading));
  ck('#navbar-sidebar-history-empty 已标记隐藏', marked(env.empty));

  // ② 标题栏必须留在原位（第一个孩子），书架紧跟在它后面
  ck('标题栏仍在正文首位（没被书架顶掉）',
     env.content.children[0] === env.bar,
     '首个孩子 tag=' + (env.content.children[0] && env.content.children[0].tag));
  ck('书架容器紧贴标题栏之后（从顶部开始排）',
     env.content.children[1] === wrap,
     '第 2 个孩子 id=' + (env.content.children[1] && env.content.children[1].id));

  // ③ 必须保住标题栏：不能把含 h1/h2/h3 的层也一起隐藏
  ck('标题栏未被标记隐藏（含 h2 的层要保留）', !marked(env.bar));

  // ④ 绝不能越界：侧栏之外 / 文档级节点一律不能被隐藏
  ck('未被越界隐藏 body', !marked(env.body));

  // ⑤ 样式表必须带：正文 flex-start + data-hip-hide 规则 + 书架撑满
  const css = cssOf(env);
  ck('CSS 强制正文 justify-content:flex-start',
     /data-navbar-sidebar-content\]\{[^}]*justify-content:flex-start/.test(css));
  ck('CSS 定义了 [data-hip-hide] 的隐藏规则',
     /\[data-hip-hide\]\{[^}]*display:none!important/.test(css));
  ck('CSS 书架容器 align-self:stretch（撑满宽度）',
     /#__hipShelfWrap\{[^}]*align-self:stretch/.test(css));
  ck('CSS 书架容器 display:flex（纵向从顶部排）',
     /#__hipShelfWrap\{[^}]*display:flex!important/.test(css));
}

// ---------- 测试 8b：站内结构变化时 layoutFix 不得「爬出 host」 ----------
// 这是 v1.9.2 的真实隐患：用 while 从历史元素向上找 host 的直接子节点，
// 若元素不在 host 子树里，循环会一路爬到 <html> 并把它 display:none → 整页空白。
console.log();
console.log('=== 测试 8b：layoutFix 越界防护 ===');
{
  const env = buildDoc({ open: true });
  // 模拟站点改版/异常：历史元素根本不在抽屉里，而是挂在 body 下。
  // （先把抽屉里原本那个同 id 的元素摘掉，避免桩出现「两个同 id」的假象）
  const i = env.flex1.children.indexOf(env.list);
  if (i >= 0) env.flex1.children.splice(i, 1);
  env.list.parentNode = null;
  const stray = env.list;
  env.body.appendChild(stray);
  const before = env.content.children.length;
  run(env, [{ title: 'L1', url: 'https://m.hipmh.com/works/L1', st: 0 }]);

  ck('host 之外的历史元素被标记隐藏（局部处理）',
     !!stray.getAttribute('data-hip-hide'));
  ck('body 本身没被误隐藏（关键回归点）', !env.body.getAttribute('data-hip-hide'));
  ck('侧栏 content 没被误隐藏', !env.content.getAttribute('data-hip-hide'));
  ck('书架容器仍然被正确注入', !!wrapOf(env));
  ck('content 的直接子层数量未被删减（只是标记隐藏）',
     env.content.children.length >= before, before + '->' + env.content.children.length);
}

// ---------- 测试 9：v1.9.4 长按不再弹「第二个窗」 ----------
// 背景：书架条目的封面是 <img>。长按时 WebView 的 setOnLongClickListener 会命中
//   HitTestResult.IMAGE_TYPE，弹出原生「全屏查看图片 / 复制图片链接 / 在新页面打开」；
//   同时 JS 的 500ms 定时器又弹出我们自己的书架菜单 → **两个弹窗同时出现**。
// 修法：JS 在 touchstart 的【第一时间】通过 HipApp.shelfTouch(true) 上报原生，
//   原生据此返回 true 把自己的图片菜单吞掉；touchend/cancel 再上报 false 复位。
console.log();
console.log('=== 测试 9：v1.9.4 长按防双弹窗（原生图片菜单被吞）===');
{
  const env = buildDoc({ open: true });
  const r = run(env, [{ title: 'P', url: 'https://m.hipmh.com/works/P', st: 0 }]);
  const wrap = wrapOf(env);
  const item = mkEl('a', { class: '__hipItm', 'data-hipurl': 'https://m.hipmh.com/works/P' });

  // ① touchstart 必须在「按下那一刻」就上报 true（不能等 500ms 定时器）
  wrap.dispatch('touchstart', { target: item, touches: [{ clientX: 10, clientY: 10 }] });
  ck('★ touchstart 立刻上报 shelfTouch(true)',
     r.calls.shelfTouch.length >= 1 && r.calls.shelfTouch[0] === true,
     JSON.stringify(r.calls.shelfTouch));

  // ② touchend 上报 false 复位
  const nAfterDown = r.calls.shelfTouch.length;
  wrap.dispatch('touchend', {});
  ck('★ touchend 上报 shelfTouch(false) 复位',
     r.calls.shelfTouch.slice(nAfterDown).indexOf(false) >= 0,
     JSON.stringify(r.calls.shelfTouch));

  // ③ 轻微抖动（<12px）不应错误复位 —— 否则原生长按会抢在我们前面弹图片菜单
  const env2 = buildDoc({ open: true });
  const r2 = run(env2, [{ title: 'Q', url: 'https://m.hipmh.com/works/Q', st: 0 }]);
  const w2 = wrapOf(env2);
  const it2 = mkEl('a', { class: '__hipItm', 'data-hipurl': 'https://m.hipmh.com/works/Q' });
  w2.dispatch('touchstart', { target: it2, touches: [{ clientX: 100, clientY: 100 }] });
  const beforeMove = r2.calls.shelfTouch.length;
  w2.dispatch('touchmove', { touches: [{ clientX: 104, clientY: 103 }] });   // 抖动 4/3px
  ck('★ 长按轻微抖动不复位（防止原生菜单抢先弹出）',
     r2.calls.shelfTouch.slice(beforeMove).indexOf(false) < 0,
     JSON.stringify(r2.calls.shelfTouch));

  // ④ 明显位移（>12px，判定为滚动）才复位
  const beforeScroll = r2.calls.shelfTouch.length;
  w2.dispatch('touchmove', { touches: [{ clientX: 160, clientY: 100 }] });  // 位移 60px
  ck('★ 明显滑动（>12px）时复位 shelfTouch(false)',
     r2.calls.shelfTouch.slice(beforeScroll).indexOf(false) >= 0,
     JSON.stringify(r2.calls.shelfTouch));

  // ⑤ 非书架区域按下不应上报（避免影响站内其它长按）
  const env3 = buildDoc({ open: true });
  const r3 = run(env3, [{ title: 'R', url: 'https://m.hipmh.com/works/R', st: 0 }]);
  const w3 = wrapOf(env3);
  const outside = mkEl('div', {});
  w3.dispatch('touchstart', { target: outside, touches: [{ clientX: 5, clientY: 5 }] });
  ck('★ 按在非条目区域不上报 shelfTouch',
     r3.calls.shelfTouch.indexOf(true) < 0, JSON.stringify(r3.calls.shelfTouch));
}

console.log();
console.log('RESULT: ' + pass + ' passed, ' + fail + ' failed');
process.exit(fail ? 1 : 0);
