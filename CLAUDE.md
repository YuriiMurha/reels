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
  Instagram adapter module. Nothing else talks to Instagram.
- Sync pacing is a hard requirement. No change may raise request rates or concurrency without saying so
  explicitly in the commit and in `ARCHITECTURE.md`.
- The repo is public on GitHub: treat every commit as published.
