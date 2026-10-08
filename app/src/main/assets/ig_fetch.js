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
  // What every call posts back for its reply, for a GET and a GraphQL POST alike, so the shape cannot drift between them.
  function answer(id) {
    return function (r) {
      if (r.type === 'opaqueredirect') {
        window.igBridge.postMessage(JSON.stringify({ id: id, code: 0, contentType: null, body: null, redirected: true }));
        return;
      }
      var ct = r.headers.get('content-type');
      return r.text().then(
        function (t) { window.igBridge.postMessage(JSON.stringify({ id: id, code: r.status, contentType: ct, body: t, redirected: false })); },
        function () { window.igBridge.postMessage(JSON.stringify({ id: id, code: r.status, contentType: ct, body: null, redirected: false })); }
      );
    };
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
      .then(answer(id))
      .catch(function () {
        window.igBridge.postMessage(JSON.stringify({ id: id, code: -1, contentType: null, body: null, redirected: false }));
      })
      .then(forget, forget);
  };
  // The only GraphQL queries this page sends, by the friendly names of the website itself (the same list as the app).
  var QUERIES = ['PolarisProfileSavedTabContentQuery'];
  function tokens() {
    // The two form tokens of the site: from its own module system first, else from the data its server put into the page.
    // They are used for the request below and go nowhere else.
    var dtsg = null, lsd = null;
    try { dtsg = require('DTSGInitialData').token; } catch (e) {}
    try { lsd = require('LSD').token; } catch (e) {}
    if (!dtsg || !lsd) {
      var html = document.documentElement.innerHTML;
      var d = html.match(new RegExp('"DTSGInitialData",\\[\\],\\{"token":"([^"]+)"'));
      var l = html.match(new RegExp('"LSD",\\[\\],\\{"token":"([^"]+)"'));
      dtsg = dtsg || (d && d[1]);
      lsd = lsd || (l && l[1]);
    }
    return dtsg && lsd ? { dtsg: dtsg, lsd: lsd } : null;
  }
  // A doc id names the persisted query the server runs, so only the shape the website sends goes out: digits, at most 30.
  window.__igGraphQl = function (id, name, docId, variables) {
    if (QUERIES.indexOf(name) < 0 || !/^\d{1,30}$/.test(docId)) {
      window.igBridge.postMessage(JSON.stringify({ id: id, code: -3, contentType: null, body: null, redirected: false }));
      return;
    }
    var tok = tokens();
    if (!tok) {
      window.igBridge.postMessage(JSON.stringify({ id: id, code: -2, contentType: null, body: null, redirected: false }));
      return;
    }
    var body = new URLSearchParams({ fb_dtsg: tok.dtsg, lsd: tok.lsd, fb_api_caller_class: 'RelayModern',
      fb_api_req_friendly_name: name, variables: variables, server_timestamps: 'true', doc_id: docId });
    var headers = { 'content-type': 'application/x-www-form-urlencoded', 'x-fb-friendly-name': name, 'x-fb-lsd': tok.lsd,
      'x-ig-app-id': '1217981644879628', 'x-asbd-id': '359341', 'x-csrftoken': cookie('csrftoken') };
    var controller = new AbortController();
    aborts[id] = controller;
    function forget() { delete aborts[id]; }
    fetch('/api/graphql', { method: 'POST', credentials: 'same-origin', redirect: 'manual', headers: headers, body: body, signal: controller.signal })
      .then(answer(id))
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
