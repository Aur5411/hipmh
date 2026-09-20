// 用真实站点的 nav 结构做桩 DOM，验证 CSS 选择器与 __hipClass 的判定
const fs = require('fs');

// —— 真实结构（照 m.hipmh.com 首页 2026-09-20 抓取，aria-label 为真实繁体）——
const NAV_RIGHT = `
<div class="flex items-center justify-end md:space-x-6 space-x-4 flex-shrink-0 pl-0 md:pl-3">
  <form action="/search" method="get" class="relative items-center h-[34px] w-[200px] rounded-full border hidden md:flex">
    <input type="text" name="q" placeholder="搜尋作品" maxlength="70">
    <button type="submit" aria-label="搜尋" class="shrink-0 text-foreground transition-colors"><svg/></button>
  </form>
  <a href="/search" class="text-foreground transition-colors p-1 block md:hidden" aria-label="搜尋"><svg/></a>
  <a href="https://m.xipmh.com/dashboard?lang=zh" target="_blank" rel="noopener noreferrer" class="text-foreground transition-colors p-1" aria-label="我的書架"><svg/></a>
  <button type="button" class="text-foreground transition-colors p-1" aria-label="更多" data-navbar-more-trigger><svg/></button>
</div>`;

// 站点自身的 tailwind 类：hidden md:flex 在小屏 = hidden；block md:hidden 在小屏 = block
function mobileVisible(cls) {
  const m = cls.match(/\bhidden\b/) ? 'hidden' : null;
  const mdFlex = /md:flex/.test(cls);
  const blockMdHidden = /block\s+md:hidden/.test(cls);
  if (blockMdHidden) return true;           // md:hidden → 手机可见
  if (mdFlex) return false;                  // md:flex → 手机隐藏
  return true;
}

// —— 我当前的 CSS 规则 ——
const MY_CSS = [
  'a[href*="g-mh.com"]', 'a[href*="18gallery.com"]',
  'a[href*="xipmh.com/dashboard"]',
  'a[aria-label="個人中心"]', 'a[aria-label="个人中心"]', 'a[aria-label="登出"]',
  'a[aria-label="我的書架"]', 'a[aria-label="我的书架"]', 'a[aria-label="書架"]', 'a[aria-label="书架"]',
  'a[href*="/dashboard"]', 'a[href*="shelf"]',
  'a[href$="/login"]', 'a[href$="/register"]', 'a[href$="/signin"]', 'a[href$="/signup"]',
];

// 简易属性选择器匹配
function matches(sel, el) {
  const m = sel.match(/^a\[([a-z-]+)([*$^]?)=["']?([^"'\]]+)["']?\]$/i);
  if (!m) return false;
  const [, attr, op, val] = m;
  if (el.tag !== 'a') return false;
  const a = el.attrs[attr];
  if (a == null) return false;
  if (op === '*') return a.includes(val);
  if (op === '$') return a.endsWith(val);
  if (op === '^') return a.startsWith(val);
  return a === val;
}

function parse(html) {
  const els = [];
  // 极简：抓 <a ...> 与 <button ...>
  const re = /<(a|button)\b([^>]*)>/gi;
  let m;
  while ((m = re.exec(html))) {
    const tag = m[1].toLowerCase();
    const attrs = {};
    const ar = /([\w:-]+)="([^"]*)"/g;
    let a;
    while ((a = ar.exec(m[2]))) attrs[a[1]] = a[2];
    // 裸属性（无 ="" ）
    const br = /(?:^|\s)([a-z][\w:-]*)(?=\s|$)/g;
    let b;
    while ((b = br.exec(m[2]))) if (!(b[1] in attrs)) attrs[b[1]] = '';
    els.push({ tag, attrs, raw: m[0] });
  }
  return els;
}

const els = parse(NAV_RIGHT);
let pass = 0, fail = 0;
function ck(name, cond) { if (cond) { pass++; console.log('  PASS ' + name); } else { fail++; console.log('  FAIL ' + name); } }

console.log('=== 测试 1：CSS 能否命中「右上角书架」===');
const shelf = els.find(e => e.attrs['aria-label'] === '我的書架');
ck('找到书架元素', !!shelf);
ck('书架 href 含 xipmh.com/dashboard', /xipmh\.com\/dashboard/.test(shelf.attrs.href));
const shelfHit = MY_CSS.filter(s => matches(s, shelf));
ck('书架被 >=1 条 CSS 规则命中 -> ' + JSON.stringify(shelfHit), shelfHit.length >= 1);

console.log();
console.log('=== 测试 2：CSS 不能误伤「搜索」===');
const searchA = els.find(e => e.tag === 'a' && e.attrs.href === '/search');
const searchBtn = els.find(e => e.tag === 'button' && e.attrs['aria-label'] === '搜尋');
ck('找到搜索 <a>', !!searchA);
ck('搜索 <a> 未被任何 CSS 规则命中 -> ' + JSON.stringify(MY_CSS.filter(s => matches(s, searchA))), MY_CSS.filter(s => matches(s, searchA)).length === 0);
ck('搜索 <button> 未被任何 CSS 规则命中 -> ' + JSON.stringify(MY_CSS.filter(s => matches(s, searchBtn))), MY_CSS.filter(s => matches(s, searchBtn)).length === 0);

console.log();
console.log('=== 测试 3：手机端可见性 ===');
const form = { cls: 'relative items-center h-[34px] w-[200px] rounded-full border hidden md:flex' };
ck('搜索 <form> 手机端隐藏 (hidden md:flex)', mobileVisible(form.cls) === false);
ck('搜索 <a> 手机端可见 (block md:hidden)', mobileVisible('text-foreground transition-colors p-1 block md:hidden') === true);
ck('书架 <a> 手机端可见 (p-1)', mobileVisible('text-foreground transition-colors p-1') === true);
ck('汉堡 <button> 手机端可见 (p-1)', mobileVisible('text-foreground transition-colors p-1') === true);

console.log();
console.log('=== 测试 4：__hipClass 判定 ===');
const LB = /^我的書架$|^我的书架$|^個人中心$|^个人中心$|^登出$|^退出$|^登入$|^登录$|^登錄$|^註冊$|^注册$/;
function isLoginHref(h) {
  if (!h) return 0;
  h = String(h).toLowerCase();
  const i = h.indexOf('?'); if (i > 0) h = h.substring(0, i);
  if (h.indexOf('/works/') >= 0) return 0;
  const s = h.indexOf('//');
  if (s >= 0) { const e = h.indexOf('/', s + 2); h = e >= 0 ? h.substring(e) : ''; }
  if (h === '/history' || h.indexOf('/history/') === 0) return 0;
  if (h === '/search' || h.indexOf('/search/') === 0) return 0;
  const LOGINP = ['/login', '/register', '/signin', '/signup', '/user/profile', '/account', '/dashboard'];
  for (let k = 0; k < LOGINP.length; k++) { const x = LOGINP[k]; if (h === x || h.indexOf(x + '/') === 0) return 1; }
  return 0;
}
function hipClass(el) {
  const h = el.attrs.href || '';
  if (h.indexOf('/works/') >= 0) return 3;
  if (/g-mh\.com|18gallery\.com/i.test(h)) return 2;
  if (isLoginHref(h)) return 1;
  const ar = el.attrs['aria-label'] || '';
  if (ar && LB.test(ar.trim())) return 1;
  return 0;
}
ck('书架 -> 1（隐藏）实际=' + hipClass(shelf), hipClass(shelf) === 1);
ck('搜索<a> -> 0（放行）实际=' + hipClass(searchA), hipClass(searchA) === 0);
ck('搜索<button> -> 0（放行）实际=' + hipClass(searchBtn), hipClass(searchBtn) === 0);
const burger = els.find(e => e.attrs['data-navbar-more-trigger'] !== undefined);
ck('汉堡 -> 0（放行）实际=' + (burger ? hipClass(burger) : 'NOT-FOUND'), burger ? hipClass(burger) === 0 : false);

console.log();
console.log('=== 测试 5：handleUrl 对 /search 的处理 ===');
function isInternal(u) { return /m\.hipmh\.com|xipmh\.com/i.test(u) || u.startsWith('/'); }
function isLoginPath(u) {
  const p = u.toLowerCase();
  if (/\/works\//.test(p)) return false;
  if (/\/search(\/|\?|$)/.test(p)) return false;
  if (/\/history(\/|\?|$)/.test(p)) return false;
  return /\/(login|register|signin|signup|dashboard|account|user\/profile)(\/|\?|$)/.test(p);
}
ck('/search 不被 isLoginPath 拦', isLoginPath('/search') === false);
ck('/search 是 internal', isInternal('/search') === true);
ck('https://m.xipmh.com/dashboard?lang=zh 被 isLoginPath 拦', isLoginPath('https://m.xipmh.com/dashboard?lang=zh') === true);

console.log();
console.log('RESULT: ' + pass + ' passed, ' + fail + ' failed');
process.exit(fail ? 1 : 0);
