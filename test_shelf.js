// -*- coding: utf-8 -*-
/**
 * 用站点真实 header/nav 链接数据，回归验证 cleanJs 的 __hipClass 判定：
 * 右上角「我的書架」(aria-label="我的書架", href=https://m.xipmh.com/dashboard?lang=zh)
 * 必须被判为 1 (隐藏)，而「搜索」「记录」「作品链接」「图库推广」不能被误伤。
 *
 * 用法: node test_shelf.js
 */
'use strict';

// ---- 从 MainActivity.java 的 cleanJs 里精确反解出的判定函数（与生产代码一致） ----
var LOGINP = ['/login', '/register', '/signin', '/signup', '/user/profile', '/account', '/dashboard'];

function isLoginHref(h) {
  if (!h) return 0;
  h = String(h).toLowerCase();
  var i = h.indexOf('?');
  if (i > 0) h = h.substring(0, i);
  if (h.indexOf('/works/') >= 0) return 0;
  var s = h.indexOf('//');
  if (s >= 0) {
    var e = h.indexOf('/', s + 2);
    h = e >= 0 ? h.substring(e) : '';
  }
  if (h === '/history' || h.indexOf('/history/') === 0) return 0;
  if (h === '/search' || h.indexOf('/search/') === 0) return 0;
  for (var i2 = 0; i2 < LOGINP.length; i2++) {
    var x = LOGINP[i2];
    if (h === x || h.indexOf(x + '/') === 0) return 1;
  }
  return 0;
}

var LB = /^我的書架$|^我的书架$|^個人中心$|^个人中心$|^登出$|^退出$|^登入$|^登录$|^登錄$|^註冊$|^注册$/;
var GK = /图库|圖庫|免费高清|免費高清/;

function classify(el) {
  try {
    if (!el || !el.getAttribute) return 0;
    var h = el.getAttribute('href') || '';
    if (h.indexOf('/works/') >= 0) return 3;
    if (/g-mh\.com|18gallery\.com/i.test(h)) return 2;
    var t = (el.textContent || '').trim();
    if (t && t.length <= 24 && GK.test(t)) return 2;
    var im = el.querySelector ? el.querySelector('img') : null;
    if (im) {
      var al2 = im.getAttribute('alt') || '';
      var sr = im.getAttribute('src') || '';
      if (/^G-MH$|^18GAL$/.test(al2)) return 2;
      if (/g-mh-900|18gallery-1/i.test(sr)) return 2;
    }
    if (isLoginHref(h)) return 1;
    var ar = el.getAttribute('aria-label') || '';
    if (ar && LB.test(ar.trim())) return 1;
    if (t && t.length <= 8 && LB.test(t)) return 1;
  } catch (e) { }
  return 0;
}

// ---- 桩元素 ----
function mkEl(text, attrs) {
  attrs = attrs || {};
  return {
    textContent: text,
    getAttribute: function (k) { return attrs[k] !== undefined ? attrs[k] : null; },
    querySelector: function () { return null; }
  };
}

// 站点真实抓包得到的 nav 链接（aria-label / href 原样）
var REAL_LINKS = [
  // [说明, textContent, aria-label, href, 期望归类, 期望是否隐藏]
  ['右上角 我的書架(真实站点)', '', '我的書架', 'https://m.xipmh.com/dashboard?lang=zh', 1, true],
  ['右上角 搜索(要保留)', '', '搜尋', '/search', 0, false],
  ['侧栏 我的書架(繁体文本)', '我的書架', '', 'https://m.xipmh.com/dashboard?lang=zh', 1, true],
  ['侧栏 個人中心', '個人中心', '', '/user/profile', 1, true],
  ['登出', '登出', '', '/logout', 1, true],
  ['登入', '登入', '', '/login', 1, true],
  ['首页(保留)', '首頁', '', '/', 0, false],
  ['人氣榜(保留)', '人氣榜', '', '/popularity', 0, false],
  ['探索(保留)', '探索', '', '/explore', 0, false],
  ['搜尋页(保留)', '搜尋', '', '/search', 0, false],
  ['作品链接(保留)', '某漫画', '', '/works/bTo4MDgz-mo-huang', 3, false],
  ['图库推广(g-mh)', '免費高清圖庫', '', 'https://g-mh.com/', 2, true],
  ['图库推广(18GAL)', '18GAL-免費高清圖庫', '', 'https://18gallery.com/', 2, true]
];

// 我方注入的按钮不能被误伤
var MINE = [
  ['注入 搜索按钮', '搜索', { 'data-hip': '1', href: 'https://m.hipmh.com/search' }],
  ['注入 记录按钮', '记录', { 'data-hip': '1', href: 'https://m.hipmh.com/history' }]
];

var pass = 0, fail = 0;
function chk(name, got, want) {
  var ok = got === want;
  if (ok) pass++; else fail++;
  console.log((ok ? '  PASS  ' : '  FAIL  ') + name + '  got=' + got + ' want=' + want);
}

console.log('== 站点真实链接判定 ==');
REAL_LINKS.forEach(function (r) {
  var el = mkEl(r[1], { 'aria-label': r[2], href: r[3] });
  var c = classify(el);
  chk(r[0], c, r[4]);
});

console.log('');
console.log('== 我方注入按钮不被误伤 ==');
MINE.forEach(function (m) {
  var el = mkEl(m[1], m[2]);
  var c = classify(el);
  chk(m[0] + ' 归类应为0', c, 0);
});

console.log('');
console.log('== 自定义跳转按钮(href=/"": remote 站外) ==');
chk('xipmh.com/history 不当登录页', isLoginHref('https://m.xipmh.com/history'), 0);
chk('xipmh.com/dashboard 当登录页', isLoginHref('https://m.xipmh.com/dashboard?lang=zh'), 1);
chk('相对 /dashboard 当登录页', isLoginHref('/dashboard'), 1);
chk('相对 /search 不当登录页', isLoginHref('/search'), 0);

console.log('');
console.log('结果: ' + pass + ' PASS / ' + fail + ' FAIL');
process.exit(fail === 0 ? 0 : 1);
