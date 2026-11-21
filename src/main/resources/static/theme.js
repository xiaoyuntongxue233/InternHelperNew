(function () {
  'use strict';

  var THEME_KEY = 'internHelper.theme';
  var FOLLOW_KEY = 'internHelper.followSystemTheme';
  var VALID = ['grass', 'blue', 'dark', 'coffee'];
  var media = window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null;

  function normalize(theme) {
    return VALID.indexOf(theme) >= 0 ? theme : 'grass';
  }

  function isFollow() {
    return localStorage.getItem(FOLLOW_KEY) === 'true';
  }

  function manualTheme() {
    return normalize(localStorage.getItem(THEME_KEY));
  }

  function effectiveTheme() {
    if (isFollow()) {
      return media && media.matches ? 'dark' : 'grass';
    }
    return manualTheme();
  }

  function apply(theme) {
    var value = normalize(theme);
    document.documentElement.setAttribute('data-theme', value);
    var meta = document.querySelector('meta[name="theme-color"]');
    if (!meta) {
      meta = document.createElement('meta');
      meta.setAttribute('name', 'theme-color');
      document.head.appendChild(meta);
    }
    var colors = { grass: '#1f6f63', blue: '#2563a8', dark: '#12151a', coffee: '#6f4e37' };
    meta.setAttribute('content', colors[value]);
    try {
      window.dispatchEvent(new CustomEvent('internhelper:themechange', { detail: { theme: value } }));
    } catch (e) {
      // 老浏览器不支持 CustomEvent 时忽略，主题本身已生效
    }
  }

  function setTheme(theme) {
    localStorage.setItem(THEME_KEY, normalize(theme));
    apply(effectiveTheme());
  }

  function setFollow(follow) {
    localStorage.setItem(FOLLOW_KEY, follow ? 'true' : 'false');
    apply(effectiveTheme());
  }

  function syncFromBackend() {
    fetch('/api/settings', { credentials: 'same-origin' })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (data) {
        if (!data || !data.settings) return;
        var s = data.settings;
        if (s.theme) setTheme(s.theme);
        if (typeof s.followSystemTheme === 'boolean') setFollow(s.followSystemTheme);
      })
      .catch(function () {
        // 后端不可用时继续使用 localStorage 中已保存的主题
      });
  }

  function onSystemChange() {
    if (isFollow()) apply(effectiveTheme());
  }

  // 尽早应用，避免页面先显示草绿再切换导致闪烁
  apply(effectiveTheme());

  if (media) {
    if (media.addEventListener) media.addEventListener('change', onSystemChange);
    else if (media.addListener) media.addListener(onSystemChange);
  }

  document.addEventListener('DOMContentLoaded', function () {
    syncFromBackend();
    window.dispatchEvent(new Event('internhelper:theme-ready'));
  });

  window.InternHelperTheme = {
    apply: apply,
    setTheme: setTheme,
    setFollow: setFollow,
    manualTheme: manualTheme,
    isFollow: isFollow,
    effectiveTheme: effectiveTheme,
    sync: syncFromBackend
  };
})();
