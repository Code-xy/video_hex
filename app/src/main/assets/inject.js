/* 注入脚本：抓视频地址 -> 甩给原生 GSY 播放器；原生播完回来接着播 */
(function () {
  if (window.__SG__) return;
  window.__SG__ = 1;

  var MEDIA_RE = /\.(m3u8|mp4|flv|ts|mkv|mov|webm|mpd)(\?|#|$)/i;
  var sniffed = [];
  var seen = {};
  var videoEl = null;

  function post(name, payload) {
    try { if (window.Seagull && window.Seagull[name]) window.Seagull[name](payload == null ? '' : String(payload)); } catch (e) {}
  }
  function isMedia(u) { return typeof u === 'string' && MEDIA_RE.test(u.split('#')[0]); }
  function remember(u, via) {
    if (!u || seen[u] || !/^https?:/i.test(u)) return;
    seen[u] = 1; sniffed.push({ url: u, via: via });
  }

  /* ---- 嗅探三层：fetch / XHR / src 赋值 ---- */
  var _fetch = window.fetch;
  if (_fetch) {
    window.fetch = function (input, init) {
      try {
        var u = (typeof input === 'string') ? input : (input && input.url);
        if (isMedia(u)) remember(u, 'fetch');
      } catch (e) {}
      return _fetch.apply(this, arguments);
    };
  }

  var _open = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function (m, u) {
    try { if (isMedia(u)) remember(u, 'xhr'); } catch (e) {}
    return _open.apply(this, arguments);
  };

  try {
    var d = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
    if (d && d.set) {
      var os = d.set;
      Object.defineProperty(HTMLMediaElement.prototype, 'src', {
        configurable: true, enumerable: d.enumerable, get: d.get,
        set: function (v) { try { if (isMedia(v)) remember(v, 'src'); } catch (e) {} return os.call(this, v); }
      });
    }
  } catch (e) {}

  /* ---- 取当前视频地址 ---- */
  function pick() {
    var c = [];
    try {
      var s = document.getElementById('video-source');
      if (s && s.src) c.push(s.src);

      var v = document.querySelector('video#video-play') || document.querySelector('video.video-js') || document.querySelector('video');
      if (v) {
        videoEl = v;
        if (v.currentSrc) c.push(v.currentSrc);
        if (v.src) c.push(v.src);
        if (window.videojs) {
          try {
            var p = (v.id && window.videojs.getPlayer(v.id)) || window.videojs.getPlayer(v);
            if (p && p.currentSrc) c.push(p.currentSrc());
          } catch (e) {}
        }
      }
      var ns = document.querySelectorAll('source');
      for (var i = 0; i < ns.length; i++) if (ns[i].src) c.push(ns[i].src);
      for (var j = sniffed.length - 1; j >= 0; j--) c.push(sniffed[j].url);
    } catch (e) {}
    for (var k = 0; k < c.length; k++) if (c[k] && isMedia(c[k])) return c[k];
    return c.length ? c[0] : '';
  }

  function requestPlay() {
    var u = pick();
    if (!u) { post('toast', '没抓到视频地址，先点一下网页里的播放再说'); return; }
    var pos = (videoEl && isFinite(videoEl.currentTime)) ? Math.floor(videoEl.currentTime * 1000) : 0;
    var title = (document.title || '').slice(0, 100);
    post('play', JSON.stringify({ url: u, title: title, pos: pos }));
  }

  /* ---- 浮动按钮，只在视频页出现 ---- */
  function addBtn() {
    if (document.getElementById('__sg_btn')) return;
    if (!/\/video-\d+\.htm/i.test(location.pathname)) return;
    var b = document.createElement('div');
    b.id = '__sg_btn';
    b.textContent = '▶ 原生播放';
    b.style.cssText = 'position:fixed;right:14px;bottom:104px;z-index:2147483647;' +
      'padding:11px 16px;border-radius:24px;background:rgba(18,18,22,.88);color:#3ddc97;' +
      'font:600 14px/1 system-ui,-apple-system,sans-serif;border:1px solid #3ddc97;' +
      'box-shadow:0 4px 18px rgba(0,0,0,.55)';
    b.addEventListener('click', function (ev) { ev.preventDefault(); ev.stopPropagation(); requestPlay(); }, true);
    (document.body || document.documentElement).appendChild(b);
  }


  /* ---- 清掉站点广告位 DOM（网络层已经拦了脚本，这里兜底） ---- */
  function killAds() {
    try {
      if (!document.getElementById('__sg_css')) {
        var st = document.createElement('style');
        st.id = '__sg_css';
        st.textContent = [
          '[id*="smartpop"]', '[class*="smartpop"]',
          'ins.adsbygoogle', '[id*="google_ads"]', '[class*="adsbygoogle"]',
          '[id^="ad_"]', '[class^="ad_"]', '[class^="ad-"]',
          '[class*="float-ad"]', '[id*="float-ad"]', '[class*="float_ad"]'
        ].join(',') + '{display:none!important;visibility:hidden!important}';
        (document.head || document.documentElement).appendChild(st);
      }
      var junk = document.querySelectorAll(
        'iframe[src*="tscprts"],iframe[src*="whitetrafsa"],iframe[src*="smartpop"]');
      for (var i = 0; i < junk.length; i++) { try { junk[i].remove(); } catch (e) {} }
    } catch (e) {}
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', killAds);
  else killAds();
  setTimeout(killAds, 1200);
  setTimeout(killAds, 3500);

  /* ---- 原生播放器退出后续播 ---- */
  window.__sgResume = function (url, posMs) {
    try {
      var v = videoEl || document.querySelector('video');
      if (!v) return false;
      if (posMs > 0 && isFinite(posMs)) v.currentTime = posMs / 1000;
      var pr = v.play();
      if (pr && pr.catch) pr.catch(function () {});
      return true;
    } catch (e) { return false; }
  };
  window.__sgPick = pick;
  window.__sgPlay = requestPlay;

  /* ---- 长按视频区域也能唤起 ---- */
  document.addEventListener('contextmenu', function (e) {
    var t = e.target;
    if (t && t.closest && t.closest('video')) { videoEl = t.closest('video'); e.preventDefault(); requestPlay(); }
  }, true);

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', addBtn);
  else addBtn();
  setTimeout(addBtn, 1500);
  setTimeout(addBtn, 4000);
})();
