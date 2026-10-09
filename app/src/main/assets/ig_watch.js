(function () {
  if (window.__igWatch) return;
  window.__igWatch = true;
  // The one query watched, and the paths the site may post it to.
  var NAME = 'PolarisProfileSavedTabContentQuery';
  var PATHS = ['/api/graphql', '/graphql/query'];
  // Whether a request is a POST to one of the paths (its URL a string or a URL object, relative or not).
  function watchedPost(url, method) {
    return String(method).toUpperCase() === 'POST' && PATHS.indexOf(new URL(String(url), location.href).pathname) >= 0;
  }
  // A request body as text, through a promise: null for none, or for a kind the site never sends a query as.
  function textOf(body) {
    if (typeof body === 'string') return Promise.resolve(body);
    if (body instanceof URLSearchParams) return Promise.resolve(body.toString());
    if (body instanceof FormData) return Promise.resolve(new URLSearchParams(Array.from(body.entries())).toString());
    if (body instanceof Blob || body instanceof ArrayBuffer || ArrayBuffer.isView(body)) return new Blob([body]).text();
    return Promise.resolve(null);
  }
  // The doc id of a request whose form names the query, else null.
  function docIdOf(text) {
    if (text === null) return null;
    var form = new URLSearchParams(text);
    return form.get('fb_api_req_friendly_name') === NAME ? form.get('doc_id') : null;
  }
  // The one message to the app, for a watched request that got a reply: its doc id, the status and the text (or null).
  function report(docId, code, text) {
    if (!docId || !(code >= 100 && code <= 599)) return;
    window.igBridge.postMessage(JSON.stringify({ kind: 'watched', docId: docId, code: code, body: text }));
  }
  // Nothing below may throw into the site: each step of the watch is inside a try or a promise with a catch.
  var origFetch = window.fetch;
  window.fetch = function (input, init) {
    var docId = null;
    try {
      var request = input instanceof Request ? input : null;
      var method = (init && init.method) || (request ? request.method : 'GET');
      if (watchedPost(request ? request.url : input, method)) {
        // A Request's own body is read from a copy, before the fetch below uses it up.
        var text = init && init.body != null ? textOf(init.body) : (request ? request.clone().text() : Promise.resolve(null));
        docId = text.then(docIdOf).catch(function () { return null; });
      }
    } catch (e) {}
    var p = origFetch.apply(this, arguments);
    if (docId) {
      try {
        // The reply is copied as soon as it arrives, before the site reads it; a fetch that fails reports nothing.
        Promise.all([docId, p.then(function (r) { return r.clone(); })])
          .then(function (both) { if (both[0]) return both[1].text().then(function (t) { report(both[0], both[1].status, t); }); })
          .catch(function () {});
      } catch (e) {}
    }
    return p;
  };
  // The reply's text by its type: as sent for text, re-serialised for JSON the browser parsed, else null.
  function replyOf(xhr) {
    try {
      if (xhr.responseType === '' || xhr.responseType === 'text') return xhr.responseText;
      if (xhr.responseType === 'json' && xhr.response !== null) return JSON.stringify(xhr.response);
    } catch (e) {}
    return null;
  }
  var open = XMLHttpRequest.prototype.open, send = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function (method, url) {
    try { this.__igWatched = watchedPost(url, method); } catch (e) { this.__igWatched = false; }
    return open.apply(this, arguments);
  };
  XMLHttpRequest.prototype.send = function (body) {
    var xhr = this;
    try {
      if (xhr.__igWatched) {
        var docId = textOf(body).then(docIdOf).catch(function () { return null; });
        // A request that got no reply ends with status 0, which report() refuses.
        xhr.addEventListener('loadend', function () {
          var code = xhr.status, text = replyOf(xhr);
          docId.then(function (id) { report(id, code, text); }).catch(function () {});
        }, { once: true });
      }
    } catch (e) {}
    return send.apply(this, arguments);
  };
})();
