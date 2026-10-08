(function () {
  if (window.__igFetch) return;
  // One AbortController per call id: a call the app gave up on is aborted by window.__igAbort(id), so its request ends.
  var aborts = {};
  function cookie(name) {
    var m = document.cookie.match('(?:^|; )' + name + '=([^;]*)');
    return m ? decodeURIComponent(m[1]) : '';
  }
  function claim() {
    try { return sessionStorage.getItem('www-claim-v2') || '0'; } catch (e) { return '0'; }
  }
  window.__igFetch = function (id, path) {
    var headers = {
      'x-ig-app-id': '1217981644879628',
      'x-asbd-id': '359341',
      'x-requested-with': 'XMLHttpRequest',
      'x-csrftoken': cookie('csrftoken'),
      'x-ig-www-claim': claim()
    };
    var controller = new AbortController();
    aborts[id] = controller;
    function forget() { delete aborts[id]; }
    fetch('/' + path, { method: 'GET', credentials: 'same-origin', redirect: 'manual', headers: headers, signal: controller.signal })
      .then(function (r) {
        if (r.type === 'opaqueredirect') {
          window.igBridge.postMessage(JSON.stringify({ id: id, code: 0, contentType: null, body: null, redirected: true }));
          return;
        }
        var ct = r.headers.get('content-type');
        return r.text().then(
          function (t) { window.igBridge.postMessage(JSON.stringify({ id: id, code: r.status, contentType: ct, body: t, redirected: false })); },
          function () { window.igBridge.postMessage(JSON.stringify({ id: id, code: r.status, contentType: ct, body: null, redirected: false })); }
        );
      })
      .catch(function () {
        window.igBridge.postMessage(JSON.stringify({ id: id, code: -1, contentType: null, body: null, redirected: false }));
      })
      .then(forget, forget);
  };
  // Aborting makes the fetch above post code -1 for this id, which the app no longer waits for and ignores.
  window.__igAbort = function (id) {
    var controller = aborts[id];
    if (!controller) return;
    delete aborts[id];
    controller.abort();
  };
})();
