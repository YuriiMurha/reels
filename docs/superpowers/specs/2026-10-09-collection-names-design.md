# Saved-collection names through the website's own query, self-repairing: design spec

Status: approved in chat 2026-10-09 (owner: "I want the names", "I want it to be built and useful with no access to my
computer", option 1 "self-repair when the id breaks"). Amends `2026-10-06-saved-reels-android-design.md` §6.2
(endpoints) and `2026-10-08-webview-transport-design.md` §3.2–3.3 (the hidden page's script, endpoints). Everything else
there stands.

## 1. Why

The first phone tests of the WebView transport (2026-10-08/09, throwaway account: 10 saved posts in 3 collections):

| Call | Answer |
|---|---|
| `api/v1/accounts/edit/web_form_data/` (login check) | 200 |
| `api/v1/feed/saved/posts/` (All Saved) | 200; every item carries `saved_collection_ids` |
| `api/v1/collections/list/?collection_types=[…]` | **404**, the site's HTML "page not available": not served on the web |

So sync stops at "Listing collections". Captures of the website itself (debug WebView, values never read):

- The **mobile** website's Saved tab has no collections at all (`PolarisProfileSavedPostsTabContentQuery`, all saved).
- The **desktop** website lists them with one GraphQL query: `PolarisProfileSavedTabContentQuery`, `POST /api/graphql`,
  `doc_id` `27584326974521636` (2026-10-08), variables
  `{"collection_types":["ALL_MEDIA_AUTO_COLLECTION","MEDIA","AUDIO_AUTO_COLLECTION"],"first":12}`, reply
  `data.viewer.collections_unified_with_auto_collections` → `edges[].node` with `collection_id`, `collection_name`,
  `collection_media_count`, `cover_media`, `cover_media_list`, `__typename`; `page_info.{end_cursor,has_next_page}`.
  4 edges: "All posts" plus the 3 collections.
- The same query sent once from the **mobile** hidden page, with the page's own `fb_dtsg`/`lsd` read inside the page and
  the mobile app id, answered **200** with the same 4 edges and fields. No desktop identity is needed for the query.

Instagram changes a query's `doc_id` when it deploys the site. The owner wants the names and wants the app to keep them
working **without a computer**: no rebuild, no reinstall, no Claude session when the id changes.

## 2. Goal and success criteria

- A sync shows the account's saved collections under their Instagram names.
- When Instagram rejects the stored `doc_id`, the app learns the current one on the phone by itself and carries on.
- A normal sync sends **fewer** API requests than the current design (the per-collection feeds go, the names query
  replaces the 404 call one-for-one).
- Unchanged: the Pacer numbers, the session gates and epochs, the transport's one-call-at-a-time rule, the classifier,
  the reconcile guards, Mock mode, the CDN path.

Out of scope: learning other volatile values (`x-ig-app-id`, `x-asbd-id`) from the site (TODO "Later", same mechanism);
anything Instagram removes outright (the app cannot repair a removed endpoint by itself).

## 3. Design

### 3.1 Names query (`:instagram` + the hidden page)

- `InstagramTransport` gains `suspend fun graphql(query: GraphQlQuery, docId: String, variables: String): RawReply`, one
  POST, same rules as `get` (exactly one request, never retried, never redirected). `GraphQlQuery` is an allow-list in
  `:instagram` (`WebGraphQl.SAVED_COLLECTIONS`: friendly name `PolarisProfileSavedTabContentQuery`, built-in `doc_id`,
  root path `data.viewer.collections_unified_with_auto_collections`). The `doc_id` in use comes from the store (§3.3).
- `ig_fetch.js` gains `window.__igGraphQl(id, friendlyName, docId, variables)`. It refuses any friendly name not in its own
  fixed list (one name), reads `fb_dtsg` and `lsd` from the page (the site's module system first, the page HTML second)
  and `csrftoken` from `document.cookie`, and POSTs `application/x-www-form-urlencoded` to `/api/graphql` with
  `fb_dtsg, lsd, fb_api_caller_class=RelayModern, fb_api_req_friendly_name, variables, server_timestamps=true, doc_id`
  and headers `x-fb-friendly-name, x-fb-lsd, x-ig-app-id, x-asbd-id, x-csrftoken`, `credentials: 'same-origin'`,
  `redirect: 'manual'`. It posts back the same `{id, code, contentType, body, redirected}` as a GET. **No token leaves the
  page**; a page without tokens answers code -2 ("no tokens": `Transient`, the page is dropped).
- `WebInstagramClient.collections(cursor)` sends that query: `first: 12` (as the site), the page-2 cursor variable for
  later pages (its name confirmed on the phone, §5). The parser keeps the user collections and skips the automatic ones
  (identified by id or type as seen on the phone, never by a display name). `cover_media.pk` becomes the cover as today.
- The reply body may start with `for (;;);`; the parser strips it. GraphQL `errors` with no usable `data` is a **stale
  query** (§3.3), not `ShapeChanged`, unless §5 shows Instagram answers a stale id differently.

### 3.2 Collections from the saved feed (strategy A)

`SAVED_COLLECTION_IDS_CONFIRMED` becomes `true` (spike Q2: every saved item lists its collections). Sync walks All Saved
once and records each item's collections from `saved_collection_ids` (the existing strategy-A path in `SyncEngine`); the
per-collection feed requests stop.

### 3.3 Self-repair of the `doc_id`

- **Storage.** The `doc_id` in use lives in the app's settings (`graphql_doc_<friendly name>`), defaulting to the
  built-in value; learned values replace it. A Developer action **Forget collections query id** sets it to a value known to
  be wrong, so the owner can trigger and watch one real repair.
- **Trigger.** Only a stale-query reply (§3.1) starts a repair. A 429, a login or challenge landing or reply, a network
  failure or a timeout is handled as today (cooldown, stop, pause), never as a repair.
- **Repair.** A second, separate hidden WebView (the "repair page") is created on the main thread from the application
  context, in **desktop mode** through the WebView's own settings (`setUserAgentString` with a desktop Chrome UA,
  `WebSettingsCompat.setUserAgentMetadata` with `mobile = false` and a desktop platform, a wide viewport). Before the
  site's code runs, a document-start script (`WebViewCompat.addDocumentStartJavaScript`, origin rule
  `https://www.instagram.com` only) wraps `fetch`/`XMLHttpRequest` to watch for exactly one request: a POST to
  `/api/graphql` whose friendly name is `PolarisProfileSavedTabContentQuery`. For that request only, it reports the
  `doc_id` and the reply body through the same origin-locked message bridge; it never reads or reports tokens, cookies or
  any other request. The page loads `https://www.instagram.com/<own handle>/saved/`; the site's own code sends the query
  with its current id. The app takes the names from that reply, stores the new `doc_id`, and destroys the page. Bounded at
  45 s; any other ending (load error, login/challenge landing, no matching request, a 429) fails the repair.
- **Limits.** At most one repair per 24 h (persisted), only inside a sync run or a lab tap, through the Pacer like any call
  (it holds the gate while it runs; a cooldown refuses it). The repair page never exists while an API call runs on the
  normal hidden page.
- **When it fails.** The sync goes on: collections keep the names from the last good sync; a collection id seen on items but
  never named gets a placeholder "Collection N"; the Sync screen shows "Couldn't refresh collection names". The next repair
  waits out the 24 h limit.

### 3.4 Logging (debug only, as today)

`GRAPHQL <friendly name> -> <code> (<ms> ms)`, `collections query stale`, `repair: start | learned new id | failed
(<reason>)`. Never a `doc_id` value, variables, a token or a body in a 2xx line.

## 4. Safety

- **Pacing.** Normal sync: fewer requests than today. Repair: one desktop page view of Saved (the site's own requests,
  about 30, unpaced like the home-page load) at most once per 24 h, only after a rejected id. Stated in ARCHITECTURE and
  the commits as CLAUDE.md requires.
- **Identity.** The normal hidden page stays mobile. The desktop identity exists only on the repair page, the way a person
  uses "Desktop site" in Chrome. The query sent from the mobile page uses the mobile app id (tested: 200).
- **Secrets.** Tokens are read and used inside the page only. The repair script watches one named request and passes back
  only its `doc_id` and reply. No `addJavascriptInterface`; both bridges are origin-locked.

## 5. To confirm on the phone during the build (each a short spike, values never read)

1. Desktop mode through `WebSettings`/`setUserAgentMetadata` changes `user-agent` and `sec-ch-ua-mobile` on the phone's
   WebView, and the site serves the desktop Saved page.
2. What Instagram answers to an outdated `doc_id` (one request with a deliberately wrong id).
3. The page-2 cursor variable's name and how the automatic collections are marked in a node.

## 6. Testing

- **JVM:** the collections parser (user vs automatic collections, page 2, `for (;;);`, stale vs other errors); the
  `doc_id` store; the repair decision (only on stale, at most once per 24 h, never on 429/login/challenge/network); the
  fallback names and placeholders; a request-count test showing a sync sends fewer requests.
- **Emulator, local test server only:** `__igGraphQl` sends one POST with the expected form fields and headers, tokens
  taken from a fake page and never posted back, unknown friendly names refused; the repair page in desktop mode (UA and
  client hints seen by the server), its script catching a fake Saved page's request and reporting only `doc_id` + reply.
- **Phone:** one sync shows the names; then **Forget collections query id** and one sync shows a successful repair.

## 7. Amendments during implementation

The text above is the approved design and stays as written, except the settings key name in 3.3, which now reads as the
code has it. What changed while it was built (the numbers are the controller's rulings; `ARCHITECTURE.md` describes the
result):

- **R1.** The lab's Collections call moves to GraphQL in the commit that removes `WebEndpoints.collections` (task 4, not 5).
- **R2.** Task 4 wired `SettingsDocIdStore` and a placeholder repair that refuses ("not wired"), so every commit compiled; task 5
  replaced it with `QueryRepairer`.
- **R3.** `SettingsStore.setCollectionsRepairAt(at: Long?)`: null clears the limit, which Forget needs.
- **R4.** The phone spike (task 1) had not run when the parser was built: STALE, CURSOR and AUTO use the plan's stated
  defaults, marked "assumed" in the code, and the phone rollout verifies them.
- **R5.** The repo `CLAUDE.md` hard rule lists what the page scripts may repeat (`ig_fetch.js`'s GraphQL names, `ig_watch.js`).
- **R6.** The GraphQL form-field and header names are `:instagram` constants (`WebGraphQl`), which `OkHttpTransport` and the
  script pins use; the `DTSGInitialData` and `LSD` module names are a named exception, pinned by test literals.
- **R7.** The PR is squash-merged, so main never carries the commits in which docs lagged the code.
- **R8.** `SettingsDocIdStore` answers a stored id only when it is digits (at most 30), else the built-in one, and `learned()`
  never stores any other; both transports refuse a doc id that is not digits.
- **R9.** The repair page is laid out as a 1440 x 900 CSS-pixel window (device pixels = CSS pixels x density), and
  `ig_watch.js` catches the site's real request shapes (a `Request` or `URL` object, `FormData` and `Blob` bodies, a JSON XHR).
- **R10.** The repair page's identity is Chrome-on-Android's "Desktop site" (X11; Linux x86_64 user agent at the WebView's
  major version, client-hint platform Linux, not mobile, form factor Desktop, 64-bit, Chrome's GREASE brand, the WebView's real
  full version), not macOS: the phone's own platform, touch points and pixel density cannot be hidden, so macOS would
  contradict them.
- **R11.** The settings corruption fallback also sets `collections_repair_at` to now, so a lost file never allows a repair
  within a day of the last one.
- **R12.** In-band GraphQL `errors` go through the classifier's markers first (rate limit, login, challenge keep their
  meaning), and a reply whose `data` has `viewer` is never a stale query (3.1's "errors with no usable data is stale" holds
  only without `data.viewer`); a 2xx execution error with `data.viewer` is `Transient`.
- **R13.** The sync keeps a set of cursors already seen while it lists the collections; a repeated one is
  `ShapeChanged("page_info.end_cursor")`, as is a page whose next cursor is the one it was asked with.
- **R14.** A repaired reply the client cannot parse (a shape change) is a failed repair: the sync goes on with the last names.
- **R15.** Task 6's review was folded into the final review's docs lens.
- **R16.** Both hidden pages install one chrome client that keeps the site's console out of the system log and cancels or
  denies its dialogs and permission requests.
- **R17.** A 2xx names reply without `data.viewer` is stale with or without `errors` (3.1's rule widened), so a doc id that
  names another query is repaired within a day.
- **R18.** "Forget collections query id" (3.3) no longer stores a wrong id: it arms a persisted one-shot forced repair and
  clears the repair limit; the next sync sends no names query and repairs once.
- **R19.** The mobile page is not closed for a repair: for at most 45 s the idle mobile page and the desktop repair page are
  both live, with no API call overlapping (3.3's "never while an API call runs" still holds).
- **R20.** A names query the page could not send (no tokens) is `QueryNotSent`: never retried, the page kept, the last names.
- **R21.** The forced repair's flag is spent once the Pacer grants the attempt, it is read by the real backend only, and
  Forget is off while a run is RUNNING.
- **R22.** R17 stays: a 2xx that is no GraphQL reply (no `data` key, no GraphQL `errors`: an error envelope, a bare status)
  is still stale, since it may be how the site answers an outdated id and stopping every sync would be worse than one repair
  a day; the `StaleQuery` and the log now say so (`not graphql`, `http <code>`). The reply's other error text (a plain-string `errors`
  entry, an `errors` that is a string or one object, an envelope's `errorSummary`/`errorDescription`) is read for the
  rate-limit, login and challenge markers too. A repaired reply that reports a rate limit, a logout or a challenge logs
  `repair: failed (reply rate limit|reply login|reply challenge)` before the run stops.
- **Phone facts (5).** The rollout settles AUTO and a real repair. STALE stays assumed (Forget sends no wrong id any more,
  R18), and CURSOR stays unverified until the account has more than 12 collection edges, the automatic ones included.
- **Also, in 2 and 4.** The names query costs one request per 12 collections (the automatic ones count), not "one for one".
  A repair the 24 h limit refuses still costs one run-budget unit and one request-log entry. And with strategy A a Sync
  (QUICK) records an item's collections only for the pages it walks, so an older saved item newly added to a collection is
  picked up by a Full sync, no longer by a Sync.
