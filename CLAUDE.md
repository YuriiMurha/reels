# reels: project notes

Personal Android app (Kotlin + Jetpack Compose) that syncs the saved reels and collections of a throwaway
Instagram account through Instagram's private web endpoints and shows them Pinterest-style. Single user,
never distributed.

## Docs to keep current

- `TODO.md`: the plan. Update when scope or order changes; tick items as they land.
- `PROGRESS.md`: append-only history. Add a dated entry per meaningful step; never edit or delete past entries.
- `ARCHITECTURE.md`: the app as it exists now (modules, data flow, decisions). Update it in the same commit
  as the code that changes it.

## Hard rules

- Never commit or log session material (`sessionid`, cookies, CSRF tokens) or raw Instagram responses.
  Test fixtures must be scrubbed of real handles, captions and URLs.
- All Instagram-specific code (endpoints, headers, pagination, JSON field extraction) lives in the single
  Instagram adapter module. Nothing else talks to Instagram. The only things in `:app` that repeat its names are:
  - the hidden page's `ig_fetch.js`: three header values, pinned to `WebHeaders` by `WebViewTransportTest`, plus the
    header names, the `csrftoken` cookie and the `www-claim-v2` storage key it needs to build the site's own request;
    and, for its GraphQL call, the friendly name, `/api/graphql`, the form field names, the `x-fb-*` header names, the
    caller class value `RelayModern`, the form's content type `application/x-www-form-urlencoded` and the doc id's
    digits-only rule, all pinned against the `WebGraphQl` constants (and the content type by its literal) by the same
    test. The named exception is the
    module names `DTSGInitialData` and `LSD`, and the patterns that read the page's own data for them: `:instagram` has no
    constant for them, and only that test's literals pin them;
  - the repair page's `ig_watch.js`: the friendly name, `QUERY_PATHS` and the two form field names it reads
    (`fb_api_req_friendly_name`, `doc_id`), pinned against `WebGraphQl` by `RepairPageGuardTest`;
  - `AndroidWebPage`'s default origin, pinned to the scheme and host of `WebEndpoints.HOME_URL` by
    `AndroidWebPageGuardTest` (`AndroidRepairPage` takes its origin from `WebEndpoints.HOME_URL` and spells none).
- Sync pacing is a hard requirement. No change may raise request rates or concurrency without saying so
  explicitly in the commit and in `ARCHITECTURE.md`.
- The repo is public on GitHub: treat every commit as published.
