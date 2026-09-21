// v1.9.1：详情页封面采集（COVER_JS）行为测试。
// 桩 DOM，跑 extract_js.py 反解出的真实脚本；并额外用「真实详情页 HTML」
// （fixtures/detail.html）跑一遍离线断言 —— 这是最有价值的一层：直接证明
// 脚本能从站点真实标记里取到正确的封面，而不是从我们臆想的 DOM 里取。
const fs = require('fs');
const vm = require('vm');
const path = require('path');

const jsPath = path.join(process.env.TEMP || '/tmp', 'hip_cover.js');
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

// ---------- 通用桩 DOM（完整 CSS 语义） ----------
function mkEl(tag, attrs) {
  const el = {
    tag: String(tag).toUpperCase(),
    attrs: Object.assign({}, attrs || {}),
    children: [],
    parentNode: null,
    textContent: attrs && attrs.__text !== undefined ? attrs.__text : '',
    getAttribute(k) { return this.attrs[k] === undefined ? null : this.attrs[k]; },
    setAttribute(k, v) { this.attrs[k] = String(v); },
    appendChild(c) { c.parentNode = el; el.children.push(c); return c; },
    querySelector(sel) { const r = el.querySelectorAll(sel); return r.length ? r[0] : null; },
    querySelectorAll(sel) { return queryAllFrom(el, sel); },
  };
  return el;
}

function queryAllFrom(root, sel) {
  const all = [];
  (function walk(n) { n.children.forEach(c => { all.push(c); walk(c); }); })(root);
  const sels = splitSels(sel);
  return all.filter(n => sels.some(s => matches(n, s, root)));
}

/** 按顶层逗号切分（不切括号/引号内的逗号） */
function splitSels(sel) {
  const out = []; let buf = ''; let q = null; let d = 0;
  for (const ch of String(sel)) {
    if (q) { buf += ch; if (ch === q) q = null; continue; }
    if (ch === '"' || ch === "'") { q = ch; buf += ch; continue; }
    if (ch === '(' || ch === '[') d++;
    if (ch === ')' || ch === ']') d--;
    if (ch === ',' && d === 0) { out.push(buf.trim()); buf = ''; continue; }
    buf += ch;
  }
  out.push(buf.trim());
  return out.filter(Boolean);
}

/** 按顶层空格切分复合选择器（后代组合） */
function splitParts(sel) {
  const out = []; let buf = ''; let q = null; let d = 0;
  for (const ch of String(sel)) {
    if (q) { buf += ch; if (ch === q) q = null; continue; }
    if (ch === '"' || ch === "'") { q = ch; buf += ch; continue; }
    if (ch === '[') d++;
    if (ch === ']') d--;
    if (/\s/.test(ch) && d === 0) { if (buf) { out.push(buf); buf = ''; } continue; }
    buf += ch;
  }
  if (buf) out.push(buf);
  return out;
}

/** 单个简单选择器单元：tag / #id / .cls / [attr] / [attr="v"]（★ 含 *= 子串匹配） */
function hitSimple(n, unit) {
  let rest = unit;
  const ta = /^[A-Za-z][\w-]*/.exec(rest);
  if (ta) {
    if (n.tag !== ta[0].toUpperCase()) return false;
    rest = rest.slice(ta[0].length);
  }
  while (rest.length) {
    if (rest.charAt(0) === '#') {
      const m = /^#([\w-]+)/.exec(rest); if (!m) return false;
      if (n.attrs.id !== m[1]) return false;
      rest = rest.slice(m[0].length); continue;
    }
    if (rest.charAt(0) === '.') {
      const m = /^\.([\w-]+)/.exec(rest); if (!m) return false;
      if (!(String(n.attrs.class || '').split(/\s+/).indexOf(m[1]) >= 0)) return false;
      rest = rest.slice(m[0].length); continue;
    }
    if (rest.charAt(0) === '[') {
      // ★ 支持 = / *= / ^= / $=，值与引号可选
      const m = /^\[\s*([\w:-]+)\s*(\*?=|\^=|\$=)?\s*(?:"([^"]*)"|'([^']*)'|([^\]\s]*))\s*\]/.exec(rest);
      if (!m) return false;
      const name = m[1], op = m[2];
      const want = m[3] !== undefined ? m[3] : (m[4] !== undefined ? m[4] : m[5]);
      const have = n.attrs[name];
      if (have === undefined) return false;
      const hs = String(have);
      if (op === '=' && hs !== want) return false;
      if (op === '*=' && hs.indexOf(want) < 0) return false;
      if (op === '^=' && hs.indexOf(want) !== 0) return false;
      if (op === '$=' && hs.slice(-want.length) !== want) return false;
      rest = rest.slice(m[0].length); continue;
    }
    return false;
  }
  return true;
}

function matches(n, sel, root) {
  if (!sel) return false;
  const parts = splitParts(sel);
  if (!parts.length) return false;
  if (!hitSimple(n, parts[parts.length - 1])) return false;
  let cur = n.parentNode;
  for (let i = parts.length - 2; i >= 0; i--) {
    let found = false;
    while (cur) {
      if (hitSimple(cur, parts[i])) { found = true; cur = cur.parentNode; break; }
      if (cur === root) break;
      cur = cur.parentNode;
    }
    if (!found) return false;
  }
  return true;
}

/** 本站详情页真实结构（按 2026-09-21 线上抓取结果搭） */
function buildDoc(opts) {
  opts = opts || {};
  const doc = mkEl('#document');
  const head = mkEl('head');
  const body = mkEl('body');
  doc.appendChild(head);
  doc.appendChild(body);

  body.appendChild(mkEl('img', { src: '/assets/logo.abc.png', alt: 'logo' }));

  if (opts.dataCover !== false) {
    // ★ 站点给自己历史记录用的数据源：作品根 div 上的 data-cover-url / -path / -title
    body.appendChild(mkEl('div', {
      'data-manga-id': '15031',
      'data-manga-title': opts.mangaTitle || '我独自升级 : 诸神黄昏',
      'data-manga-path': '/works/bToxNTAzMQ',
      'data-cover-url': opts.dataCover === true
        ? 'https://cover.s3imgs.top/kk/vertical/work-1-abc.webp' : opts.dataCover,
      'data-cover-color': '#586ea1',
    }));
  }
  if (opts.og) {
    head.appendChild(mkEl('meta', { property: 'og:image', content: opts.og }));
  }
  if (opts.ld) {
    const sc = mkEl('script', { type: 'application/ld+json' });
    sc.textContent = JSON.stringify({
      '@context': 'https://schema.org',
      '@graph': [{ '@type': 'CreativeWorkSeries', name: 'LD 标题', image: opts.ld }],
    });
    head.appendChild(sc);
  }
  // 顶部背景「模糊大图」—— ★ 绝不能被当成封面
  body.appendChild(mkEl('img', {
    src: 'https://cover.s3imgs.top/kk/vertical/blur-bg-should-not-win.webp',
    alt: 'cover image',
    class: 'absolute inset-0 h-full w-full object-cover object-center blur-[70px] md:blur-[10px]',
  }));
  // 真正的竖版海报（兜底路径用）
  if (opts.poster !== false) {
    const box = mkEl('div', { class: 'relative mx-auto w-40 h-[226px]' });
    box.appendChild(mkEl('img', {
      src: opts.poster === true
        ? 'https://cover.s3imgs.top/kk/vertical/poster-real.webp' : opts.poster,
      alt: '我独自升级 : 诸神黄昏',
      width: '150', height: '226',
      class: 'absolute inset-0 w-full h-full object-cover rounded-lg',
    }));
    body.appendChild(box);
  }
  // 相关推荐（别的作品封面）
  body.appendChild(mkEl('img', {
    src: 'https://cover.s3imgs.top/kk/vertical/other-9-xyz.webp', alt: '别的作品',
  }));
  body.appendChild(mkEl('img', { src: 'https://x/a.png', alt: '使用者頭像' }));

  doc.createElement = t => mkEl(t);
  doc.addEventListener = () => {};
  return { doc, body, head };
}

function run(env, opts) {
  opts = opts || {};
  const calls = [];
  const timers = [];
  const events = {};
  const loc = {
    pathname: opts.pathname === undefined ? '/works/bToxNTAzMQ' : opts.pathname,
    href: opts.href || 'https://m.hipmh.com/works/bToxNTAzMQ',
    origin: 'https://m.hipmh.com',
    protocol: 'https:',
  };
  const doc = env.doc;
  doc.addEventListener = (ev, fn) => { (events[ev] = events[ev] || []).push(fn); };
  const ctx = {
    document: doc,
    location: loc,
    JSON, String, Object, Array, Date, console,
    setTimeout(f) { timers.push(f); return timers.length; },
    clearTimeout() {},
    setInterval() { return 0; },
    HipApp: {
      setCover: (u, c, t, p) => calls.push([u, c, t || '', p || '']),
    },
  };
  ctx.window = ctx;
  vm.createContext(ctx);
  vm.runInContext(SRC, ctx);
  return { calls, timers, loc };
}

const lastCover = r => (r.calls.length ? r.calls[r.calls.length - 1][1] : null);

// ---------- 测试 1：首选 [data-cover-url] ----------
console.log('=== 测试 1：[data-cover-url] 优先 ===');
{
  const r = run(buildDoc({ dataCover: true }));
  ck('调用了 setCover', r.calls.length >= 1, JSON.stringify(r.calls));
  ck('取到 data-cover-url 那张',
    lastCover(r) === 'https://cover.s3imgs.top/kk/vertical/work-1-abc.webp', lastCover(r));
  ck('没有误取顶部「模糊大图」', !String(lastCover(r)).includes('blur-bg'), lastCover(r));
  ck('没有误取「相关推荐」的封面', !String(lastCover(r)).includes('other-9-xyz'), lastCover(r));
  ck('回传了当前页面 URL',
    r.calls[0][0] === 'https://m.hipmh.com/works/bToxNTAzMQ', r.calls[0][0]);
  ck('一并回传了站点官方标题',
    r.calls[0][2] === '我独自升级 : 诸神黄昏', r.calls[0][2]);
}

// 半角属性顺序不同也要命中
console.log();
console.log('=== 测试 1b：只认 data-cover-url（模糊图不是候选）===');
{
  // 把模糊图放在 data-cover-url 元素之前，验证不会先命中它
  const env = buildDoc({ dataCover: true });
  const r = run(env);
  ck('即便模糊图更靠前，仍取 data-cover-url', !String(lastCover(r)).includes('blur-bg'), lastCover(r));
}

// ---------- 测试 2：没有 data-cover-url -> og:image ----------
console.log();
console.log('=== 测试 2：兜底 og:image ===');
{
  const r = run(buildDoc({ dataCover: false, og: '/kk/vertical/og-fallback.webp' }));
  ck('退到 og:image 并补全成绝对地址',
    lastCover(r) === 'https://m.hipmh.com/kk/vertical/og-fallback.webp', lastCover(r));
}

// ---------- 测试 3：没有 data-cover-url / og -> ld+json ----------
console.log();
console.log('=== 测试 3：兜底 ld+json 的 image ===');
{
  const r = run(buildDoc({ dataCover: false, ld: 'https://cover.s3imgs.top/kk/vertical/ld.webp' }));
  ck('退到 ld+json 的 image',
    lastCover(r) === 'https://cover.s3imgs.top/kk/vertical/ld.webp', lastCover(r));
}

// ---------- 测试 4：全都没有 -> 竖版海报（跳过模糊图）----------
console.log();
console.log('=== 测试 4：兜底「竖版海报」，跳过模糊大图 ===');
{
  const r = run(buildDoc({ dataCover: false, poster: true }));
  ck('取到真正的海报图',
    lastCover(r) === 'https://cover.s3imgs.top/kk/vertical/poster-real.webp', lastCover(r));
  ck('★ 没把 blur-[70px] 的模糊图当封面',
    !String(lastCover(r)).includes('blur-bg'), lastCover(r));
  ck('跳过头像', !String(lastCover(r)).includes('a.png'), lastCover(r));
}

// ---------- 测试 5：非详情页不上报 ----------
console.log();
console.log('=== 测试 5：非详情页 ===');
{
  for (const p of ['/', '/search', '/chapter/go', '/history']) {
    const r = run(buildDoc({ dataCover: true }), {
      pathname: p, href: 'https://m.hipmh.com' + p,
    });
    ck('路径 ' + p + ' 不上报封面', r.calls.length === 0, JSON.stringify(r.calls));
  }
}

// ---------- 测试 6：定时补发 ----------
console.log();
console.log('=== 测试 6：定时补发 ===');
{
  const r = run(buildDoc({ dataCover: true }));
  ck('注册了多次 setTimeout 补发', r.timers.length >= 3, r.timers.length);
  const n0 = r.calls.length;
  r.timers.forEach(f => f());
  ck('补发后依然报的是同一张封面',
    r.calls.slice(n0).every(c => c[1] === 'https://cover.s3imgs.top/kk/vertical/work-1-abc.webp'));
}

// ---------- 测试 7：拿不到封面就不上报 ----------
console.log();
console.log('=== 测试 7：拿不到封面就不上报 ===');
{
  const doc = mkEl('#document');
  const body = mkEl('body');
  body.appendChild(mkEl('img', { src: '/assets/logo.png', alt: 'logo' }));
  doc.appendChild(body);
  doc.createElement = t => mkEl(t);
  const r = run({ doc, body });
  ck('没有可用封面时不调用 setCover', r.calls.length === 0, JSON.stringify(r.calls));
}

// ---------- 测试 8：★ 用真实详情页 HTML 离线验（最有说服力） ----------
console.log();
console.log('=== 测试 8：真实详情页 fixtures/detail.html ===');
{
  const fx = path.join(__dirname, 'fixtures', 'detail.html');
  if (!fs.existsSync(fx)) {
    ck('fixtures/detail.html 存在', false, fx);
  } else {
    const html = fs.readFileSync(fx, 'utf8');
    const expect = /data-cover-url="([^"]+)"/.exec(html);
    const expTitle = /data-manga-title="([^"]+)"/.exec(html);
    const expPath = /data-manga-path="([^"]+)"/.exec(html);
    ck('真实页面里存在 data-cover-url', !!expect, '未找到');
    if (expect) {
      const doc = parseRealHtml(html);
      const r = run({ doc, body: doc });
      ck('★ 从真实详情页取到正确封面',
        lastCover(r) === expect[1], lastCover(r));
      ck('★ 封面不是那张 blur-[70px] 的模糊大图',
        !String(lastCover(r)).includes('blur') && lastCover(r) !== '', lastCover(r));
      ck('封面域名是 cover.s3imgs.top',
        /^https:\/\/cover\.s3imgs\.top\//.test(String(lastCover(r))), lastCover(r));
      ck('回传的标题与站点 data-manga-title 一致',
        r.calls[0][2] === (expTitle ? expTitle[1] : ''), r.calls[0][2]);
      ck('回传的作品路径与站点 data-manga-path 一致',
        r.calls[0][3] === (expPath ? expPath[1] : ''), r.calls[0][3]);
    }
  }
}

/** 极简 HTML 解析：只抽我们关心的标签与属性（够用即可，不引第三方库） */
function parseRealHtml(html) {
  const doc = mkEl('#document');
  const stack = [doc];
  const tagRe = /<(\/?)([a-zA-Z][\w-]*)((?:\s+[^>]*?)?)(\/?)>/g;
  let m;
  while ((m = tagRe.exec(html)) !== null) {
    const closing = m[1] === '/';
    const name = m[2].toLowerCase();
    const attrStr = m[3] || '';
    const selfClose = m[4] === '/' || ['meta', 'link', 'img', 'br', 'hr', 'input', 'source'].indexOf(name) >= 0;
    if (closing) {
      if (stack.length > 1) stack.pop();
      continue;
    }
    const attrs = {};
    const aRe = /([\w:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))/g;
    let a;
    while ((a = aRe.exec(attrStr)) !== null) {
      attrs[a[1]] = a[2] !== undefined ? a[2] : (a[3] !== undefined ? a[3] : a[4]);
    }
    const el = mkEl(name, attrs);
    stack[stack.length - 1].appendChild(el);
    if (!selfClose) stack.push(el);
  }
  doc.createElement = t => mkEl(t);
  return doc;
}

console.log();
console.log('RESULT: ' + pass + ' passed, ' + fail + ' failed');
process.exit(fail ? 1 : 0);
