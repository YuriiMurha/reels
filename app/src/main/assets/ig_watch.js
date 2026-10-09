(function () {
  if (window.__igWatch) return;
  window.__igWatch = true;
  var NAME = 'PolarisProfileSavedTabContentQuery';
  function formOf(body) {
    try {
      if (typeof body === 'string') return new URLSearchParams(body);
      if (body instanceof URLSearchParams) return body;
      if (typeof FormData !== 'undefined' && body instanceof FormData) return new URLSearchParams(Array.from(body.entries()));
    } catch (e) {}
    return null;
  }
  function matches(url, method, body) {
    if (String(method).toUpperCase() !== 'POST') return null;
    var path;
    try { path = new URL(url, location.href).pathname; } catch (e) { return null; }
    if (path !== '/api/graphql' && path !== '/graphql/query') return null;
    var form = formOf(body);
    if (!form || form.get('fb_api_req_friendly_name') !== NAME) return null;
    return form.get('doc_id');
  }
  function report(docId, code, text) {
    window.igBridge.postMessage(JSON.stringify({ kind: 'watched', docId: docId, code: code, body: text }));
  }
  var origFetch = window.fetch;
  window.fetch = function (input, init) {
    var url = typeof input === 'string' ? input : (input && input.url);
    var method = (init && init.method) || (input && input.method) || 'GET';
    var docId = matches(url, method, init && init.body);
    var p = origFetch.apply(this, arguments);
    if (docId) p.then(function (r) { return r.clone().text().then(function (t) { report(docId, r.status, t); }); }, function () {});
    return p;
  };
  var open = XMLHttpRequest.prototype.open, send = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function (method, url) { this.__w = { method: method, url: url }; return open.apply(this, arguments); };
  XMLHttpRequest.prototype.send = function (body) {
    var w = this.__w, xhr = this;
    var docId = w && matches(w.url, w.method, body);
    if (docId) xhr.addEventListener('loadend', function () { report(docId, xhr.status, typeof xhr.responseText === 'string' ? xhr.responseText : null); });
    return send.apply(this, arguments);
  };
})();
