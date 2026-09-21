// ★ v1.6：收藏按钮（FAV_JS）行为测试 —— 用桩 DOM 跑 extract_js.py 反解出的真实脚本。
// 先跑 extract_js.py 生成 %TEMP%/hip_fav.js，再执行本文件。
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const SRC = path.join(process.env.TEMP || '/tmp', 'hip_fav.js');
if (!fs.existsSync(SRC)) {
  console.error('找不到 ' + SRC + '，请先运行 extract_js.py');
  process.exit(2);
}
const src = fs.readFileSync(SRC, 'utf8');

let pass = 0, fail = 0;
function ck(name, cond, extra) {
  if (cond) { pass++; console.log('  PASS  ' + name); }
  else { fail++; console.log('  FAIL  ' + name + (extra !== undefined ? '  -> ' + extra : '')); }
}

function mkEl(tag) {
  const cls = new Set();
  const el = {
    tagName: tag, id: '', innerHTML: '', style: {}, attrs: {}, _h: {},
    textContent: '', parentNode: null,
    classList: {
      add: c => cls.add(c),
      remove: c => cls.delete(c),
      contains: c => cls.has(c),
      toggle: (c, on) => {
        if (on === undefined) { cls.has(c) ? cls.delete(c) : cls.add(c); }
        else { on ? cls.add(c) : cls.delete(c); }
        return cls.has(c);
      }
    },
    setAttribute: (k, v) => { el.attrs[k] = String(v); },
    getAttribute: k => (k in el.attrs ? el.attrs[k] : null),
    addEventListener: (t, f) => { el._h[t] = f; },
    appendChild: () => {},
  };
  Object.defineProperty(el, 'className', { get: () => Array.from(cls).join(' ') });
  return el;
}

/** 建一套桩环境并跑一次 FAV_JS */
function run(opts) {
  const nodes = {};
  const head = { appendChild(n) { nodes[n.id] = n; } };
  const body = {
    // 真实 DOM 的 appendChild 会挂上 parentNode，脚本的移除分支依赖它
    appendChild(n) { nodes[n.id] = n; n.parentNode = body; },
    removeChild(n) { delete nodes[n.id]; n.parentNode = null; }
  };
  const document = {
    head, body, documentElement: {},
    getElementById: id => nodes[id] || null,
    createElement: tag => mkEl(tag),
    addEventListener: () => {},
    // 详情页靠 nav 底边避让：opts.navBottom 模拟站点 fixed <nav> 的底边 y 值
    querySelector: sel => (opts.navBottom
      ? { getBoundingClientRect: () => ({ bottom: opts.navBottom }) }
      : null),
    querySelectorAll: () => [],
    title: opts.title || ''
  };
  const calls = [];
  let fav = !!opts.fav;
  const sandbox = {
    document,
    location: { href: opts.href, pathname: opts.pathname },
    console,
    setTimeout: f => { try { f(); } catch (e) {} return 0; },   // 同步执行，便于断言
    clearTimeout: () => {},
    setInterval: () => 0,
    MutationObserver: class { constructor(cb) { sandbox.__obs = cb; } observe() {} },
    HipApp: {
      isFav: u => { calls.push(['isFav', u]); return fav; },
      toggleFav: (u, t) => { calls.push(['toggleFav', u, t]); fav = !fav; return fav; }
    }
  };
  sandbox.window = sandbox;
  vm.runInNewContext(src, sandbox);
  return {
    nodes, calls, sandbox,
    btn: () => nodes['__hipFavBtn'] || null
  };
}

console.log('=== 测试 1：作品详情页出现收藏按钮（未收藏）===');
{
  const r = run({
    href: 'https://m.hipmh.com/works/bToxNTAzMQ',
    pathname: '/works/bToxNTAzMQ',
    title: '我独自升级 : 诸神黄昏 - 嘻皮漫画',
    fav: false
  });
  const b = r.btn();
  ck('按钮已创建', !!b);
  ck('位置贴右上角（14px）', !!b && /14px/.test(b.style.top || ''), b && b.style.top);
  ck('未收藏时星形为空心（fill="none"）', !!b && b.innerHTML.indexOf('fill="none"') >= 0);
  ck('未收藏时无 __on 类', !!b && !b.classList.contains('__on'));
  ck('查询过收藏状态 isFav(href)', r.calls.some(c => c[0] === 'isFav'
    && c[1] === 'https://m.hipmh.com/works/bToxNTAzMQ'));
  ck('带 data-hip=1（cleanJs/Guard 不会误伤）', !!b && b.getAttribute('data-hip') === '1');
  ck('样式表只注入一次 __hipFavCss', !!r.nodes['__hipFavCss']);
}

console.log('\n=== 测试 2：点击收藏 -> 星星变实心 ===');
{
  const r = run({
    href: 'https://m.hipmh.com/works/bToxNTAzMQ',
    pathname: '/works/bToxNTAzMQ',
    title: '我独自升级 : 诸神黄昏 - 嘻皮漫画',
    fav: false
  });
  const b = r.btn();
  b._h.click({ preventDefault() {}, stopPropagation() {} });
  ck('调用了 toggleFav(url, title)', r.calls.some(c => c[0] === 'toggleFav'
    && c[1] === 'https://m.hipmh.com/works/bToxNTAzMQ'
    && c[2] === '我独自升级 : 诸神黄昏 - 嘻皮漫画'));
  ck('收藏后加 __on 类', b.classList.contains('__on'));
  ck('收藏后星形变实心', b.innerHTML.indexOf('fill="currentColor"') >= 0);
  // 再点一次 = 取消收藏
  b._h.click({ preventDefault() {}, stopPropagation() {} });
  ck('再点一次取消收藏（__on 移除）', !b.classList.contains('__on'));
  ck('取消后星形变回空心', b.innerHTML.indexOf('fill="none"') >= 0);
}

console.log('\n=== 测试 3：★v1.9 阅读器页不再显示收藏按钮 ===');
{
  // 阅读器的真实 URL 是 reader.hipmh.top/chapter/<hid>，解析不出作品 id，
  // 点了只会弹「这个页面还不能收藏」。v1.9 起阅读页直接不注入收藏星。
  const r = run({
    href: 'https://m.hipmh.com/chapter/go?hid=bToxNTAzMS1jOjEzOTc1&m=15031',
    pathname: '/chapter/go',
    title: '我独自升级 - 第12话',
    fav: true
  });
  ck('阅读页不创建按钮', r.btn() === null);

  // reader.hipmh.top 域名形式同样不创建
  const r2 = run({
    href: 'https://reader.hipmh.top/chapter/bToxNTAzMS1jOjEzOTc1',
    pathname: '/chapter/bToxNTAzMS1jOjEzOTc1',
    title: '我独自升级 - 第12话',
    fav: true
  });
  ck('reader 域名下也不创建按钮', r2.btn() === null);

  // 从阅读页软导航回详情页 -> 按钮出现
  const r3 = run({
    href: 'https://m.hipmh.com/works/bToxNTAzMQ',
    pathname: '/works/bToxNTAzMQ',
    title: '我独自升级',
    fav: true
  });
  ck('详情页仍然创建按钮', !!r3.btn());
  ck('已收藏时带 __on 类', !!r3.btn() && r3.btn().classList.contains('__on'));
  ck('已收藏时星形实心',
    !!r3.btn() && r3.btn().innerHTML.indexOf('fill="currentColor"') >= 0);
}

console.log('\n=== 测试 3b：阅读页曾经注入过按钮 -> 会被清掉 ===');
{
  const r = run({
    href: 'https://m.hipmh.com/works/bToxNTAzMQ',
    pathname: '/works/bToxNTAzMQ',
    title: '某作品'
  });
  ck('先在详情页有按钮', !!r.btn());
  // 软导航到阅读页
  r.sandbox.location.pathname = '/chapter/go';
  r.sandbox.location.href = 'https://m.hipmh.com/chapter/go?hid=x&m=15031';
  if (r.sandbox.__obs) r.sandbox.__obs([]);
  ck('软导航到阅读页后按钮被移除', r.btn() === null);
}

console.log('\n=== 测试 4：非作品页不显示按钮 ===');
{
  const r = run({ href: 'https://m.hipmh.com/', pathname: '/', title: '嘻皮漫画' });
  ck('首页不创建按钮', r.btn() === null);
  const r2 = run({ href: 'https://m.hipmh.com/search', pathname: '/search', title: '搜索' });
  ck('搜索页不创建按钮', r2.btn() === null);
}

console.log('\n=== 测试 5：软导航离开作品页后按钮自动移除 ===');
{
  const r = run({
    href: 'https://m.hipmh.com/works/bToxNTAzMQ',
    pathname: '/works/bToxNTAzMQ',
    title: '某作品'
  });
  ck('先在详情页有按钮', !!r.btn());
  r.sandbox.location.pathname = '/';           // 回到首页（Astro 换页）
  r.sandbox.location.href = 'https://m.hipmh.com/';
  if (r.sandbox.__obs) r.sandbox.__obs([]);    // 触发 MutationObserver -> build
  ck('离开后按钮被移除', r.btn() === null);
}

console.log('\n=== 测试 6：详情页有固定 nav 时，按钮下移到 nav 下方 ===');
{
  const r = run({
    href: 'https://m.hipmh.com/works/bToxNTAzMQ',
    pathname: '/works/bToxNTAzMQ',
    title: '某作品',
    navBottom: 96                 // 站点 nav 两行，底边 96px
  });
  const b = r.btn();
  ck('下移到 nav 下方（96+8=104px）', !!b && /104px/.test(b.style.top || ''), b && b.style.top);
  ck('不再压在 14px（避免挡住 nav 的「更多」）', !!b && !/14px/.test(b.style.top || ''));
}

console.log('\nRESULT: ' + pass + ' passed, ' + fail + ' failed');
process.exit(fail ? 1 : 0);
