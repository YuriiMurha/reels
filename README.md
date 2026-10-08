# reels

A personal Android app (Kotlin + Jetpack Compose) that syncs the saved reels and collections of a **throwaway
Instagram account** and shows them Pinterest-style: real thumbnails, smooth scrolling, grouped by collection.
Single user, never distributed.

> **Read this first.** The app talks to Instagram's private web endpoints through your own logged-in session.
> That is against Instagram's Terms of Service. Only ever log a throwaway/test account into it, never your main
> account. Everything stays on the phone: no server, no cloud, no telemetry. Sync is manual and deliberately
> slow (a request every 4–12 s, at most 300 per run and 600 per 24 h).

Design: [`docs/superpowers/specs/2026-10-06-saved-reels-android-design.md`](docs/superpowers/specs/2026-10-06-saved-reels-android-design.md).
Current state: [`ARCHITECTURE.md`](ARCHITECTURE.md). Plan: [`TODO.md`](TODO.md). History: [`PROGRESS.md`](PROGRESS.md).

First time through, do sections 1 to 5 in order. Sections 6 to 10 are reference and checklists.

## What works today

| Milestone | State |
|---|---|
| M0 Skeleton, M1 Mock app | Done. Home, grid, viewer, search and sync work on a built-in fake library of 2,000 items in 8 collections. |
| M2 Session | Code done. Logging in through Instagram's own page (or pasting a `sessionid`) needs your on-phone check ([section 8](#8-on-phone-checklist)). |
| M3 Adapter lab | Code done. A debug-only lab sends one request per endpoint and saves scrubbed copies of the answers. The parsers follow the open-source clients' known response shapes and have never met Instagram, so running the lab and handing the answers back is yours ([section 7](#7-adapter-lab-and-the-spike-handback)). |
| M4 Real sync | Code done and tested against fakes and a local test server only. Sync, Full sync, resume, budgets, cooldowns, the challenge stop and thumbnails need your first real sync ([section 5](#5-first-real-sync)). |
| M5 Video | Code done: reels play on demand and are cached. Needs your on-phone check ([section 6](#6-watching-videos)). |
| M6 Release build | Code done: `installRelease` builds a minified, baseline-profiled app, smoke-tested on an emulator while logged out and on the fake library. Needs your phone to check the parts that talk to Instagram under minification, and your signing key if you want one ([section 9](#9-release-build)). |

No agent has ever logged in to Instagram or sent it a request, so everything marked "needs your phone" can only be
checked by you.

A debug build starts in **Mock mode**: the library you see comes from the fake backend, and tapping **Sync** fills it
with generated placeholder items and never contacts Instagram. Turning Mock mode off (on the phone only) switches to
the real library.

## 1. One-time setup on the Mac

1. Install **Android Studio**. It bundles the JDK, the Android SDK and the emulator. In its SDK Manager, install
   **Android SDK Platform 36** and **37**, plus **Android SDK Platform-Tools** (for `adb`).
2. Clone the repo, step into it, and enable the secret guard (it refuses commits that contain session material or
   signing keys):

   ```bash
   git clone https://github.com/YuriiMurha/reels.git
   ```

   ```bash
   cd reels
   ```

   ```bash
   git config core.hooksPath .githooks
   ```

   Every command below runs from this folder (the one with `gradlew`) unless it says otherwise.

3. Point Gradle at the SDK. Run this once, on a fresh clone: it writes `local.properties` (gitignored), replacing any
   earlier one:

   ```bash
   echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
   ```

4. Every Gradle command below needs Android Studio's JDK, and `adb` has to be on the path. Run both once per
   terminal session. Open a new terminal later and you must run them again:

   ```bash
   export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ANDROID_HOME="$HOME/Library/Android/sdk"
   ```

   ```bash
   export PATH="$PATH:$HOME/Library/Android/sdk/platform-tools"
   ```

5. Check that everything builds and the tests pass (takes a few minutes the first time):

   ```bash
   ./gradlew check
   ```

## 2. Connect the phone

1. On the phone: **Settings → About phone →** tap **Build number** seven times to unlock Developer options.
2. **Settings → System → Developer options →** turn on **USB debugging**. Use a USB cable for all the steps below:
   the `adb -d` commands only see a phone attached by USB.
3. Plug the phone in by USB and accept the "Allow USB debugging?" prompt.
4. Confirm the Mac sees it (the phone should be listed as `device`, not `unauthorized`):

   ```bash
   adb devices
   ```

`./gradlew installDebug` installs to **every** connected device, so an emulator that is running would get the app as
well. The install commands in this guide therefore first read the serial of the phone attached by USB (`adb -d`
means "the one device attached by USB") and only then run Gradle for that one device (`ANDROID_SERIAL`). If no phone is
attached, or two are, `adb` fails and the command stops there, before Gradle runs: nothing is installed anywhere. Fix
the connection (the steps above) and run it again.

Never log into Instagram on an emulator: the account should only ever see your phone.

## 3. Install and run

With the phone connected (section 2) and the exports from step 1.4 done in this terminal:

```bash
SERIAL="$(adb -d get-serialno)" && ANDROID_SERIAL="$SERIAL" ./gradlew installDebug
```

Then open **Reels** on the phone. A fresh debug install is in Mock mode.

- **Saved** (home) lists All Saved, Uncategorized and your collections, each with its item count. Tap a card for its
  grid. When the library is empty it says "Nothing synced yet": tap **Open Sync**.
- **Grid**: tap a tile for the full-screen viewer; swipe up and down; **Back** returns to the same tile.
- **Search** (magnifier, top right) searches captions, authors and collection names, and can narrow to reels or
  posts.
- **Sync** (the status chip, top right: "Not synced", "Syncing…", "Synced 5 min ago", or "⚠" and a problem):
  - **Sync** fetches what's new. **Full sync** also removes what you unsaved on Instagram, applies moves between
    collections, and fetches any thumbnails an earlier run skipped. **Delete library** wipes the local copy (your
    login is kept).
  - Progress, budgets and any cooldown show here. When a run has stopped or paused, the two buttons become **Resume**
    and **Discard paused run**; **Cancel** shows while a run is going.
  - With Mock mode off (a release build always is), **Sync**, **Full sync** and **Resume** stay disabled until the Sync
    screen says "Logged in as @handle" (before that it says "Log in to Instagram to sync"). **Log out** also cancels a
    sync that is running.

### Mock mode (debug builds)

A debug build starts on the **fake library**, so an emulator, or a phone before you have logged in, never needs a login
and never contacts Instagram. A release build has no such switch and always uses the real library.

- **Where:** Sync (status chip, top right), scroll down to **Developer**, **Mock mode (fake library)**. It is **on by
  default**.
- **For real sync, on the phone:** switch it **off**. The app restarts on the real library, which is empty until its
  first sync. The switch is greyed out while a sync is running (and for a moment after the screen opens, until it has
  read the latest run). Never turn it off on an emulator.
- **Nothing is lost by switching back and forth.** The fake library and the real one are kept separately (fake:
  `reels.db`, real: `library.db`; their thumbnails and cached videos too). Shared between them are only the Instagram
  login and the request log behind the 600-per-24-hour budget, with its cooldown: session checks and lab calls count
  against it even in Mock mode. In Mock mode the Sync screen's own counters and cooldown are the fake library's, so under
  the session status it adds one line for the real ones: **Instagram requests in 24 h: X / 600**, or, while a real cooldown
  (from a session check or a lab call) is running, **Instagram requests paused: N min left (cooldown)**. It is only read
  from the app's own request log and never sends anything. With Mock mode off the line is not there: the existing
  counters already are the real ones.

### Automated smoke tests (emulator only)

An on-device UI suite (`app/src/androidTest/`) drives the real app through its screens, like a browser test for the phone
app. It covers: launch (Home's "Saved" bar, Search, the sync chip), the Sync screen (the session shown logged out, the
Developer section with Mock mode on), a fake **Sync** to the end, a collection grid, opening the viewer, a reel that
starts playing, and Search. It never contacts Instagram: it never logs in and never taps Log in, Check now, Open on
Instagram, the Adapter lab or the Mock mode switch (it only checks that they are there).

- **It skips on a real phone.** Every test begins by checking that the device is an emulator, before anything is
  launched, so a phone that is plugged in only gets the debug build installed over its own.
- **It needs Mock mode on** (a fresh debug install has it on). With Mock mode off the tests fail with "Turn Mock mode on
  (Sync → Developer) before running the smoke tests". Switch it on by hand: the tests never flip it.
- **Run it** with an emulator running and the exports of step 1.4 done in this terminal (that step also puts `adb` on the
  path). `-e` means "the only emulator": with none running, or two, `adb` fails and Gradle does not start.

  ```bash
  SERIAL="$(adb -e get-serialno)" && ANDROID_SERIAL="$SERIAL" ./gradlew connectedDebugAndroidTest
  ```

  Always with `ANDROID_SERIAL`: without it Gradle runs on every connected device, a phone included. The app stays
  installed afterwards (`gradle.properties`), so the emulator keeps its fake library and the screenshots survive.
- **Screenshots:** one PNG per step (`t1-01-home.png`, `t2-02-developer-mock-mode-on.png`, …) in the app's files folder
  on the emulator, replaced on every run. Copy them to the Mac with:

  ```bash
  rm -rf "$HOME/reels-smoke-shots" && adb -e pull /sdcard/Android/data/io.github.yuriimurha.reels/files/smoke/ "$HOME/reels-smoke-shots"
  ```

  The Gradle report (`app/build/reports/androidTests/connected/debug/index.html`) lists a skipped test as failed; the
  build itself still succeeds.

## 4. Log in (on the phone only)

On the Sync screen, under **Instagram session**:

- **Log in** opens Instagram's own login page inside the app. Log into the throwaway account there and finish any
  2FA. The screen closes by itself and shows "Logged in as @handle".
- **Paste sessionid** is the fallback: copy the `sessionid` cookie from a mobile browser on the same phone that is
  logged into the test account, and paste it.
- **Check now** asks Instagram once whether the session still works. **Log out** removes the session from the
  phone and keeps the library.
- If Instagram asks for verification ("Instagram wants verification"), **Resolve on Instagram** opens it; finish
  there, then tap **Check again**.
- If the session expires ("Session expired (@handle)"), **Log in again** opens the login page.

The app keeps the session only in Android's WebView cookie store: it writes no file of its own with it and never logs
it. Android's WebView keeps that cookie store on the phone (app-private, and excluded from backup).

## 5. First real sync

Do this once, on the phone, with the throwaway account. You need the Mac set up (section 1), the phone connected
(section 2) and the account's login (and its 2FA device).

1. **Install the debug build** on the phone (skip if section 3 already did; the exports from step 1.4 must be done in
   this terminal):

   ```bash
   SERIAL="$(adb -d get-serialno)" && ANDROID_SERIAL="$SERIAL" ./gradlew installDebug
   ```

2. **Turn Mock mode off.** Open **Reels**, tap the status chip (top right) to open **Sync**, scroll down to
   **Developer** and switch **Mock mode (fake library)** off. The app restarts on the real library, which is empty
   ("Nothing synced yet").
3. **Log in** (section 4). Sync must say "Logged in as @handle".
4. **Run the Adapter lab once, top to bottom, and export the files** ([section 7](#7-adapter-lab-and-the-spike-handback)).
   It costs five requests and shows the real shape of every answer before the first sync depends on it. It is
   recommended, not required. If any button shows a classification other than `ok`, stop there and paste the result
   in a Claude session before you sync.
5. **Tap Sync.** Android 13 and later may ask for notification permission: allow it, so the "Syncing saved reels"
   notification shows. You can leave the app while it runs; the run carries on in the background.
   - A run checks the session (one request), lists your collections, walks All Saved, then walks **every collection
     one by one**. That is the safe default until the lab confirms that saved items say which collections they are
     in; it costs more requests but is always correct.
   - On an empty library nothing is known yet to stop at, so the first Sync fetches everything, up to the budget below.
     Later Syncs stop each feed at the first page that holds an item already in the library.
   - Watch the **Syncing** section: the phase line, **Collections**, **New items**, **Items seen**,
     **Thumbnails cached**, **Failures**, **Requests (all attempts)** and **Requests in 24 h**.
   - A run of 300 requests takes roughly an hour (gaps plus breaks), so a library of a few thousand reels may need
     several runs.
6. **When Sync has succeeded** (no banner, and **History → Last sync** shows a time), look at the grid and the
   collections' counts. **Only then run a Full sync.** It is the only thing that removes items, so first prove that
   the plain walk works.

### The limits, in numbers

- **Gaps:** a random 4–12 s between two requests (typically about 6 s). **Breaks:** 60–180 s after every 15–30
  requests.
- **Budgets:** **300 requests per run** and **600 per rolling 24 hours**. The 24-hour count is a log kept on the
  phone; every request counts, including session checks, lab taps and the viewer's link renewals (not thumbnail
  downloads: they come from Instagram's image servers, 2 at a time with a short random pause).
- **Cooldowns:** if Instagram answers with a rate limit, the run stops and the app makes **no Instagram API request** for
  **1 hour**. A second rate limit within 24 hours means **24 hours**. The cooldown survives killing the app: don't
  clear the app's data or reinstall to get around it.
- **A flaky connection:** the app waits 30 s, 1, 2 and 4 minutes (each roughly ±20 %), retrying, and then pauses the
  run.
- **Runs pause and resume.** Cancel, a lost connection, the 300-request budget, an Android kill and a few other stops
  leave the run with its place saved. The button turns into **Resume** and carries on from there (a resumed run gets a
  fresh 300, but the 600 per 24 hours still holds). After an Android kill, Android may also restart the run by itself,
  later and without a tap (it re-runs sync work it was holding). That is the same run carrying on from its place, paced
  and budgeted like any other (gaps, breaks, 300 per run, 600 per 24 hours, cooldowns), and it sends nothing at all
  unless the session is still valid: an expired or challenged session stops it before its first request.
  **Discard paused run** abandons it and the next tap starts a fresh run. Neither deletes anything. Items an abandoned
  run had already added stay in the library. Until another sync finishes, a Full sync judges what it may remove against
  the library as of the last sync that finished, so those items can't make a large removal look small; once a later
  Sync finishes, they count as part of the library. Resume continues the same run with the same mode: a paused Full
  sync stays a Full sync.
- **Nothing is deleted** except by a Full sync whose walk of a feed reached the end in that same run, and by
  **Delete library**.

### When a run stops (the red banner on Sync)

- **"Instagram wants verification. Resolve it before syncing again."** Instagram asked for a check (a challenge).
  This is a hard stop with no automatic retries, and nothing more is sent. That holds also when the viewer, the lab or
  **Check now** is what met the challenge: a run that is going checks the session before every request and stops before
  its next one. A run Android restarts by itself sends nothing either while the session isn't valid. Under **Instagram
  session** tap **Resolve on Instagram**, finish the check in the page, tap **Check again**, and when Sync says "Logged
  in as @handle" tap **Resume**.
- **"Session expired. Log in again, then tap Resume."** Instagram no longer accepts the login, or a sessionid was pasted
  while the run was going (the run stops before its next request rather than carry on under another session). Tap
  **Log in again** (finish any 2FA), then **Resume**. If Sync already says "Logged in as @handle" but Resume stops again
  at once with this banner, the phone's login changed (for example you logged in as another account and left the login
  screen right away): tap **Check now** first, then **Resume**.
- **"Adapter needs repair: …"** Instagram's answer was not what the app expects; the text after the colon says where.
  Nothing was deleted. Don't keep tapping Resume (each tap sends the same request again). Run the Adapter lab once,
  and paste the banner and the lab's result in a Claude session. The common texts after the colon are explained in
  [Troubleshooting](#10-troubleshooting).
- **"This library belongs to another Instagram account. Delete library to switch."** The session is a different
  Instagram account from the one this library was first synced from. The run stopped right after its session check:
  nothing was fetched, written or removed. To switch accounts, use **Delete library** (below), then **Sync**. Otherwise
  log out, log in to the library's account, then **Resume**.
- **"Instagram limited requests. Tap Resume when you're ready."** A rate limit. While the cooldown lasts the banner
  reads "Cooling down after a rate limit: N min left" and the buttons are off. When it ends, tap **Resume**.

The other stops (paused, not stopped) are listed in [Troubleshooting](#10-troubleshooting).

### Delete library: the escape hatch

**Sync → Storage → Delete library**, then **Delete** in the dialog. It removes every synced item, collection, history
row, thumbnail and cached video from the phone, and forgets which Instagram account the library belonged to. It keeps
your login **and the request log**, so the 600-per-24-hour budget and any cooldown carry on. Then **Sync** (or **Full
sync**) refills from scratch. Use it when:

- a Full sync was refused with "full sync would remove N of M items" and you really did unsave that much;
- you really did delete every collection on Instagram ("empty collection list");
- you switch to a different Instagram account (log out, log in, Delete library, then Sync). Without Delete library, a
  sync under the other account stops with "This library belongs to another Instagram account";
- the library looks wrong and you would rather start clean.

It costs a full, paced re-sync. If a line under the **Delete library** button then says "Library deleted, but the
account record couldn't be cleared; try Delete library again", the items are gone but the phone still remembers the old
account: tap **Delete library** once more. "Library deleted; some cached files couldn't be removed" means only some
thumbnails or cached videos are left (they take space, nothing more; **Delete library** again tries them again), and
"Couldn't delete the library; try again" means nothing was deleted. (Log out has a similar one, under the session status:
"Couldn't finish logging out; try again". The login is already gone from the phone, and a second **Log out** finishes the
job.)

## 6. Watching videos

Videos are never downloaded during a sync. A reel is fetched when you open it:

- **On demand.** The saved link is used as it is while it has more than 10 minutes left. Otherwise the app asks
  Instagram once for a fresh link (one request on the interactive lane, at least 2 s after the previous request, counted
  in the 600-per-24-hour budget), then plays. While you watch, it may do the same for the next reel (after the one you
  are looking at has its link) so swiping on is instant; it never asks when the link is still good, and never for a
  reel it already has in full.
- **Only with a working login.** If Instagram wants verification, the session expired or you logged out, the viewer
  sends nothing to Instagram: reels you have in full still play, and a reel that needs a new link says "Instagram
  session needs attention (Sync screen)". Fix the session on the Sync screen and it works again.
- **Cache.** What you watch is kept on the phone (up to 512 MB, least recently used first) under the reel's id, not
  its link, so a renewed link still finds what was already downloaded. A reel you have watched in full plays from
  the phone even when its link has long expired. A reel you unsave and then Full sync away, and **Delete library**,
  drop their cached videos.
- **Offline or limited.** A reel you watched all the way through still plays with no network, or while the app is
  cooling down after a rate limit. One you haven't shows a message over its thumbnail: "Offline: this video isn't
  cached yet", "Can't play this video", or why it can't load ([Troubleshooting](#10-troubleshooting)).
- **Open on Instagram** is always under the thumbnail, also when a video can't play. It opens the link in whatever
  handles it on the phone (the Instagram app or a browser), so it uses whatever account is logged in there. If
  Instagram no longer has the reel, the app says so and keeps your copy of the item.
- A link Instagram refuses (HTTP 403 or 410) is renewed once and retried; if that fails too you see "Can't play this
  video". The player itself never retries a refused link (nor a 429 or 404): one refusal is one request. Video requests
  carry no cookies; if Instagram's video server redirects, the redirect is followed (the player can't refuse it).
- In Mock mode the bundled clip plays for every reel and nothing is requested.

## 7. Adapter lab and the spike handback

The lab shows what Instagram's endpoints really send back, so the parsers can be checked against real responses. It is
in debug builds only (`./gradlew installDebug`); a release build has no Developer section. Use it on the phone, with
Mock mode off and logged in to the throwaway account (section 5, steps 2 and 3).

**What it does.** Each button sends exactly **one** request as the logged-in account, through the app's one pacer: at
least 2 seconds after the previous request, counted in the 600-per-24-hour budget, and refused during a cooldown. A
rate-limit answer starts the usual cooldown (1 hour, then 24 hours) and the lab refuses further taps until it ends:
don't tap again to retry. The screen shows the call, the HTTP code, how the app classified the answer (`ok`, or the
kind of failure), and the response **shape** (key names, types and lengths; identifying values are redacted). It also
saves a scrubbed copy (synthetic ids, handles, captions and links) on the phone, and the path shows under the result.

**Run it once.**

1. **Sync → Developer → Adapter lab.** The button is enabled only while Sync says "Logged in as @handle".
2. Tap each button once, from top to bottom, and wait for its result before the next tap:
   **Who am I**, **Collections**, **All Saved (page 1)**, **First collection (page 1)** (enabled once Collections has
   returned a collection) and **Media info (first saved item)** (enabled once All Saved has returned an item).
3. Each button overwrites its own file, so export after the whole run. A button you never tapped has no file, and
   neither does one whose latest answer was not JSON: that answer removes the button's earlier file, so an exported
   file is never older than the result the screen showed. A tap that was refused (cooldown) or failed before any
   answer arrived leaves the earlier file as it was.

**Export the scrubbed files** with the phone connected by USB. `adb -d` talks to the one USB-attached device, so these
work with an emulator running too. First a folder outside the repo:

```bash
mkdir -p ~/reels-lab
```

One command per file:

```bash
adb -d exec-out run-as io.github.yuriimurha.reels cat files/lab/current_user.json > ~/reels-lab/current_user.json
```

```bash
adb -d exec-out run-as io.github.yuriimurha.reels cat files/lab/collections.json > ~/reels-lab/collections.json
```

```bash
adb -d exec-out run-as io.github.yuriimurha.reels cat files/lab/saved_all.json > ~/reels-lab/saved_all.json
```

```bash
adb -d exec-out run-as io.github.yuriimurha.reels cat files/lab/saved_collection.json > ~/reels-lab/saved_collection.json
```

```bash
adb -d exec-out run-as io.github.yuriimurha.reels cat files/lab/media_info.json > ~/reels-lab/media_info.json
```

If an exported file contains an error such as `No such file or directory`, that button has no scrubbed copy (never
tapped, or its latest answer was not JSON).

**Read each scrubbed file before you hand it over or commit it: redaction is heuristic.** A lowercase handle used
as a key, or a one-word value (letters and underscores, any case), can't be told from schema and is kept. The pre-commit guard only catches session
material, not personal data.

**Hand it back.** Once you have read them, give the five files and the on-screen results (long-press the shape text to select and copy it, or
send a screenshot) to a Claude session and ask it to answer the seven spike questions in section 6.3 of the design
doc. The seven questions, and where each answer is in the lab's output, are in the M3 part of the
[checklist](#8-on-phone-checklist). The answers get recorded in `ARCHITECTURE.md` and `TODO.md`, and the files you
have read become test fixtures.

## 8. On-phone checklist

Everything here needs your phone. Tick the boxes as you go.

**How to report back.** For every check, "paste the result in a Claude session" means: open a Claude session in this
repo and paste the text the check asks for (a banner, the logcat lines, the numbers on Sync, a lab shape), or a
screenshot. Never paste a `sessionid`, a cookie or a raw browser response. Logcat lines from a debug build already hide
cookies and tokens, but they do show the numeric ids of the throwaway account's saves: they are not secrets, trim them
if you like.

For the logcat checks, use a second terminal on the Mac (run the `PATH` export from step 1.4 in it too), and clear the
log first:

```bash
adb -d logcat -c
```

```bash
adb -d logcat -s InstagramHttp
```

(Ctrl-C stops it.) Debug builds log one block per request to Instagram's API (a `GET https://www.instagram.com/api/v1/...`
line and the answer), with every cookie and token replaced by `██`. One block is one request. Thumbnails and videos are
not in this log.

### M2: session

- [ ] **Log in.** Install, open **Sync → Log in**, log into the throwaway account and finish any 2FA. **Look for:**
  the screen closes and Sync shows "Logged in as @handle"; logcat shows exactly **one**
  `GET .../api/v1/users/<id>/info/` with `Cookie: ██` and a `200`. **Report:** a pass, or the text of the message
  under the buttons and the login screen's bar (for a shape problem it reads "Unexpected Instagram response at"
  followed by a path such as `user` or `http.404`; note the path), and the request lines.
- [ ] **No request at start.** Swipe the app away and reopen it. **Look for:** it still says "Logged in as @handle",
  and logcat shows **no** new request. **Report:** a pass, or the request lines that appeared.
- [ ] **Check now, twice quickly.** **Look for:** exactly one request. **Report:** how many request blocks logcat shows.
- [ ] **Log out and in again.** **Look for:** "Not logged in" after **Log out**, and **Log in** works again.
  **Report:** a pass, or the message you saw.
- [ ] **Optional: paste.** Log out, then **Paste sessionid** from a mobile browser on this phone. **Look for:** "Logged
  in as @handle". **Report:** a pass, or the red line under the paste box ("That doesn't look like a sessionid",
  "Instagram rejected that session; your current login is unchanged", "Couldn't check that session").
- [ ] **Header notes for the next milestone.** **Report:** the `X-IG-App-ID` value you saw, whether the
  `X-Requested-With` header appeared, the language of any error message, any request logged twice, and whether the
  `sessionid` changed in a checkpoint; "didn't see it" is an answer. How to look:
  - On the Mac, open `chrome://inspect/#devices` in Chrome **before** you tap Log in. The login screen is the only
    place the app shows a WebView, and it disappears when the screen closes, so inspect it while it is open: tap
    **Log in**, click **inspect** under the app's WebView, open the **Network** tab, and reload the page. On any
    `/api/v1/` request, read the `X-IG-App-ID` header. The app currently sends `936619743392459`, the desktop-web
    value; mobile web often uses `1217981644879628`.
  - On the same requests, does an `X-Requested-With: io.github.yuriimurha.reels` header appear?
  - If an error message appears, is it in your phone's language?
  - Does any request show up twice in logcat?
  - If you hit a checkpoint: did the `sessionid` change during it?

### M3: Adapter lab and the seven spike questions

- [ ] **Run the lab once** (section 7), export the five files, read them, and hand them over as described there. A
  Claude session answers the seven questions from them; the lines below say where each answer is (the shape on
  screen, or the same key in the exported file) and what only you can supply.
  - [ ] **1. Endpoints and headers.** Each button's HTTP code and classification should read `200` and `ok`. **Needs
    you:** the `X-IG-App-ID` the mobile site sends (the M2 note above). **Report:** the code and classification of
    the five buttons, and the header value, or "not found".
  - [ ] **2. `saved_collection_ids`.** In **All Saved (page 1)**, under `items` → `[0]` → `media`: is there a
    `saved_collection_ids` array, and is it filled for an item you know sits in a collection? **Report:** yes, no, or
    present-but-empty. If yes, a Claude session flips `SAVED_COLLECTION_IDS_CONFIRMED` (in
    `instagram/src/main/kotlin/io/github/yuriimurha/reels/instagram/web/WebInstagramClient.kt`), and sync walks only
    All Saved. If no, nothing changes.
  - [ ] **3. A save timestamp.** Any key in an `items` entry or its `media` that holds a time and is not `taken_at`
    (a `number(10 digits)` that looks like a Unix time, or a name that mentions saving). **Report:** the key names,
    or "none".
  - [ ] **4. Page size and cursor.** The `array[N]` next to `items` in **All Saved** and in **First collection**;
    `more_available` (a boolean); `next_max_id` (a string or a number). **Report:** both N, and whether
    `more_available` is ever `false` while `next_max_id` is present.
  - [ ] **5. Media types.** `media_type` (1 image, 2 video, 8 carousel) and `product_type` (`clips` for a reel) are
    shown as values. **Report:** the pairs you see in `saved_all.json`, and whether a reel you know is a reel shows
    `clips`.
  - [ ] **6. Error payloads.** Only an error that really happens can answer this (a rate limit, a login or a
    checkpoint). Don't provoke one. **Report:** if a lab button, a sync banner or a login ever shows an error, the
    HTTP code, the classification and the shape (`message`, `error_type`, `status` are visible).
  - [ ] **7. The CDN expiry.** In a `url(host=cdn, params=[...], oe=hex)` line (under `video_versions` and
    `image_versions2` in **Media info**): is `oe` listed and `hex`? **Report:** `oe=hex`, `absent`, or `malformed`,
    plus the parameter names.

### M4: real sync

Run section 5 first. Keep the second terminal's logcat running during these.

- [ ] **QUICK sync.** Tap **Sync** on the empty real library. **Look for:** the phase moves through "Checking
  session", "Listing collections" and "Syncing All Saved", then each collection; logcat shows one request every
  4–12 s and a longer pause (60–180 s) after every 15–30; **Requests in 24 h** rises by about the number of request
  blocks in logcat; the run ends with no banner (or pauses at "Run budget reached, tap Resume": tap
  **Resume**); the grid has thumbnails. **Report:** the Sync screen's counters at the end (Collections, New items,
  Items seen, Thumbnails cached, Failures, Requests, Requests in 24 h), how long it took, and any banner.
- [ ] **Cancel and resume.** During a run tap **Cancel**, then **Resume**. **Look for:** it carries on from where it
  stopped (**Items seen** and **Requests (all attempts)** keep their totals and rise; it doesn't fetch the finished
  pages again). **Report:** a pass, or what you saw.
- [ ] **A second Sync, later.** **Look for:** a handful of requests (each feed stops at the first known item), and the
  new saves appear. **Report:** Requests (all attempts).
- [ ] **FULL sync,** only after a Sync has succeeded. **Look for:** it ends with no banner. For a real test: unsave a
  reel on the throwaway account (and move another into a different collection) first; after the Full sync the unsaved
  one is gone from the grid and the moved one shows in its new collection. **Report:** the counters, and whether
  anything was removed that should still be there.
- [ ] **Budget and cooldown display.** **Look for:** **Requests in 24 h** reads `n / 600` and counts up. If a rate limit
  ever happens: the banner "Cooling down after a rate limit: N min left", **Sync** and **Full sync** off, the number
  falling, and the cooldown still there after you swipe the app away and reopen it. **Report:** what you saw, and
  when the rate limit happened. Don't provoke one.
- [ ] **A challenge hard stop,** only if one ever happens. **Look for:** the banner "Instagram wants verification.
  Resolve it before syncing again."; **Instagram session** reads "Instagram wants verification"; logcat shows **no**
  further request after the stop, also when you open a reel that isn't cached. After **Resolve on Instagram** and
  **Check again**, **Resume** works. **Report:** the banner, the logcat lines around the stop, and whether the check
  needed a new login.
- [ ] **Collection feeds end correctly (R72).** After a Full sync, for each collection compare the count on its card in
  **Saved** with the number of saves in that collection on Instagram. **Look for:** equal counts. A collection that
  is short, especially by a round number such as a page size, may have ended early. (A feed that ends early drops the
  later items from that collection, not from All Saved, until the next good Full sync.) **Report:** collection by collection, "phone N,
  Instagram M", and whether **First collection (page 1)** showed `more_available`.
- [ ] **Thumbnails.** **Look for:** every tile has an image. If some are blank the run still ended Done: Instagram's
  image servers told the app to slow down, and the next **Full sync** fetches them. **Report:** **Failures** and
  **Thumbnails cached** against **Items seen**.

### M5: video

- [ ] **Plays.** Open a reel. **Look for:** picture and sound start within a few seconds, it loops, a tap pauses, and
  **Mute** / **Unmute** sticks across reels and a restart. **Report:** a pass, or the reel and what happened.
- [ ] **Link renewal.** With logcat running, open a reel shortly after a sync (its link is fresh), then open one a day
  or more later. **Look for:** no `.../api/v1/media/<id>/info/` request for the fresh one; exactly **one** for the old
  one, then it plays. The next reel's link may be renewed in advance, once, after the current one plays. **Report:**
  the request lines and when the reel was synced.
- [ ] **Offline playback from the cache.** Watch a reel through at least one loop, then switch on airplane mode and
  open it again. **Look for:** it plays. Open a reel you have never watched: it shows a message over its thumbnail
  with **Open on Instagram** under it. **Report:** the message text.
- [ ] **403 and 410 recovery.** It can't be forced. If a reel fails right after you open it, **look for:** one
  `.../api/v1/media/<id>/info/` request (the renewal) and then playback, or "Can't play this video" if the renewed
  link fails too. **Report:** the request lines and what the screen showed.
- [ ] **A 403 on one reel's link renewal,** only if it happens. The app reads an HTTP 403 from Instagram as "the session
  is gone", and can't tell it from a 403 about that one reel (a private or removed one, say). So after a 403 on a
  `.../api/v1/media/<id>/info/` request, **Instagram session** reads "Session expired", the viewer says "Instagram
  session needs attention (Sync screen)", and a sync that is running stops ("Session expired"). If you see that while
  everything else worked, tap **Check now** before you log in again. **Look for:** whether Check now says "Logged in as
  @handle" (then the session was fine and the 403 was about that reel; **Resume** a stopped sync). **Report:** the
  logcat lines of that request (its id and the `403`), what Check now said, and the reel's link from **Open on
  Instagram**.
- [ ] **Smoothness on a `TextureView`.** The viewer draws on a `TextureView`, because the default surface often failed
  to show the picture of a page you swiped back to (audio played under the thumbnail). Swipe through at least 20
  reels, up and down and back. **Look for:** the right picture on every page, no sound with a still thumbnail, no black
  flicker, no freeze at a page change. **Report:** the number of pages that failed out of the number you tried.

### M6: release build

- [ ] **`installRelease`** ([section 9](#9-release-build)), with the debug key first (no setup), and with your own key
  if you made one. **Look for:** the app opens on Saved, and **Sync** has no Developer section. **Report:** which key
  (debug or yours), and a pass, or what you saw instead (an install error, a crash, a Developer section).
- [ ] **What agents couldn't exercise under R8:** with the release build, **log in** (the login page must show and
  accept you: that is the WebView login), run **Sync** (the real client and the thumbnail downloader), then open a grid
  and a viewer. **Look for:** no crash. If it crashes, the cause is a missing keep rule: **Report:** the lines under
  `FATAL EXCEPTION` from

  ```bash
  adb -d logcat -t 300
  ```

  The class names in a crash are short and meaningless; `app/build/outputs/mapping/release/mapping.txt` maps them back.
- [ ] **Scrolling.** The M1 debug build had 37 % janky frames. Zero the counters, scroll the grid and the viewer for about
  half a minute, then read them:

  ```bash
  adb -d shell dumpsys gfxinfo io.github.yuriimurha.reels reset
  ```

  ```bash
  adb -d shell dumpsys gfxinfo io.github.yuriimurha.reels
  ```

  **Look for:** the "Janky frames" line and the 50th, 90th and 95th percentile lines. **Report:** those lines.
- [ ] **Baseline profile (optional).** Android compiles the app ahead of time from the installed profile:

  ```bash
  adb -d shell dumpsys package io.github.yuriimurha.reels | grep status=
  ```

  **Look for:** `status=speed-profile` (the emulator smoke saw it). Compilation can lag after an install: if it still
  says `run-from-apk`, report that.

## 9. Release build

A release build is minified (R8), carries a baseline profile that Android compiles ahead of time on first launch
(`androidx.profileinstaller` installs it on a sideloaded app; the rules are the hand-written
[`app/src/main/baseline-prof.txt`](app/src/main/baseline-prof.txt)), and always uses the real library: it has no Mock
mode and no Developer section. Until you have logged in (section 4), **Instagram session** says "Not logged in" and
**Sync**/**Full sync** are disabled. It needs no key to try: **without `keystore.properties`, the release build is signed
with the debug key**, so `installRelease` replaces an installed debug build and keeps the app's data. Gradle prints one
line saying so ("release: no keystore.properties at the repo root; signing with the debug key") on every run; it is not
an error.

With the exports from step 1.4 done in this terminal:

```bash
SERIAL="$(adb -d get-serialno)" && ANDROID_SERIAL="$SERIAL" ./gradlew installRelease
```

To sign with your own key instead, do this once. The key and its password are yours to create: nothing in the repo
generates or stores them, and both files below are gitignored (the pre-commit hook also refuses them).

1. From the repo root (the folder with `gradlew`), create the key. `keytool` asks for **one** password: choose it (at
   least 6 characters) and **type it twice**, because it asks you to re-enter it. (A PKCS12 key store has no separate
   key password.) Then it asks for some details about you (any answers will do), and finally "Is ... correct?", to
   which you type `yes`. It writes `reels-release.jks` here:

   ```bash
   "/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool" -genkeypair -v -keystore reels-release.jks -keyalg RSA -keysize 4096 -validity 10000 -alias reels
   ```

2. Create `keystore.properties` in the same folder. This command writes the four keys, with the file name and alias
   the `keytool` command above used, and **blank passwords**. Run it **only once**: running it again would wipe the
   passwords you filled in.

   ```bash
   printf 'storeFile=reels-release.jks\nstorePassword=\nkeyAlias=reels\nkeyPassword=\n' > keystore.properties
   ```

   Then open it and type the password you chose after `storePassword=` and after `keyPassword=` (the **same** password
   in both, no spaces, no quotes), then save and close. A blank value is refused. `storeFile` is relative to the repo
   root:

   ```bash
   open -e keystore.properties
   ```

3. Keep a copy of `reels-release.jks` and its password outside the repo. If they are lost, the app installed with that
   key can't be updated: it has to be uninstalled first.
4. A build signed with your key and a build signed with the debug key can't replace each other. **Switching between them
   needs an uninstall, which deletes the library and the session** (you log in and sync again):

   ```bash
   adb -d uninstall io.github.yuriimurha.reels
   ```

5. Install it on the phone:

   ```bash
   SERIAL="$(adb -d get-serialno)" && ANDROID_SERIAL="$SERIAL" ./gradlew installRelease
   ```

If `keystore.properties` lacks one of the four keys, or has it blank, Gradle stops (for every task, not only the release
ones) and names the key, never its value. A crash report from a release build shows short, meaningless names;
`app/build/outputs/mapping/release/mapping.txt` (written by the build) maps them back.

Then run the M6 part of the [checklist](#8-on-phone-checklist).

## 10. Troubleshooting

### Build and install

- **`SDK location not found`**: `local.properties` is missing or points to the wrong folder (step 1.3).
- **`Unsupported class file major version` or a Gradle JVM error**: `JAVA_HOME` isn't set in this terminal (step 1.4).
- **`adb: command not found`**: the `PATH` export of step 1.4 isn't in this terminal.
- **`error: no devices/emulators found` from an `adb -d` command**: the phone isn't attached by USB, USB debugging is
  off, or you haven't accepted the prompt on the phone (section 2).
- **`INSTALL_FAILED_UPDATE_INCOMPATIBLE`**: a build signed with another key is installed. Uninstall it first (this
  also deletes the local library and session):

  ```bash
  adb -d uninstall io.github.yuriimurha.reels
  ```

- **`keystore.properties has no (or an empty) '...'`**: that key is missing or blank in the file (section 9).
- **A commit is refused with `pre-commit: ... session material`, `... sessionid value` or `... release signing
  material`**: the secret guard found something that looks like a cookie, a session id or a signing file. Remove it;
  never bypass the hook.

### Login and session

- **The login page is blank**: report it. Note whether a cookie/consent dialog is visible, and look at the page in
  `chrome://inspect`.
- **"Checking session…"** with no buttons: the app is still reading the saved session. It clears in a moment.
- **"Not logged in"**: log in (section 4). **"Session expired (@handle)"**: **Log in again**. **"Instagram wants
  verification"**: **Resolve on Instagram**, then **Check again**.
- **A red line under the session buttons, or in the login screen's bar** (it comes from **Check now** or the
  check after a login):
  - "Instagram session is not logged in", "Instagram requires verification", "Instagram is limiting requests",
    "Temporary network or server problem": what it says. Wait or fix it, then **Check now**.
  - "Unexpected Instagram response at `<path>`": Instagram's answer to the session check isn't what the app expects
    (`user` and `user.username` are the two fields it reads; `http.404` means the endpoint is wrong). Paste it in a
    Claude session with the `chrome://inspect` note from the M2 checklist.
  - "Cooling down after a rate limit" or "The 24-hour request budget is used up": the app sent nothing. See the
    cooldown and budget rules in section 5.
  - "That session isn't valid yet. Finish logging in, then check again." (login screen): you aren't through Instagram's
    login yet, or the session was rejected. Finish, then **Check again**.
  - "Finished verifying on Instagram?" with **Check again** (login screen): tap it once the check in the page is done.
- **After tapping Paste sessionid → Use it:** "That doesn't look like a sessionid" (nothing was sent), "Instagram
  rejected that session; your current login is unchanged", or "Couldn't check that session".

### Sync: paused runs (not stopped)

A paused run shows its reason as the banner (and in the status chip on Saved). The button reads **Resume**.

- **"Cancelled"**: you tapped Cancel, or logged out during a run. **Resume** continues.
- **"Interrupted, tap Resume"**: the app was killed mid-run and Android had not restarted it. Tap **Resume**.
- **"Network problem, try again later"**: the connection failed and the app gave up after its retries (30 s, 1, 2, 4
  minutes). Tap **Resume** when the connection is back.
- **"Run budget reached, tap Resume"**: the run used its 300 requests. Tap **Resume**.
- **"24-hour budget reached"**: 600 requests in the last 24 hours. Tap **Resume** only when the oldest of those is a
  day old; sooner it stops again at once, without sending anything.
- **"Unexpected error: `<ClassName>`"**: a bug. Tap **Resume** once; if it comes back, paste the class name in a Claude
  session.
- **"Paused"**: a pause with no reason recorded. **Resume**.

### Sync: "Adapter needs repair"

The text after the colon says what broke. Nothing was deleted by any of these.

- **`full sync would remove N of M items`**: the Full sync reached the end of the feed, but applying it would have
  removed N of the M items the library held before the run (at least 20, and more than half). M counts only items a
  finished sync had seen: not this run's new ones, and not what a stopped or discarded run added since the last sync that
  finished. That is usually a broken, partial or foreign answer, so nothing was removed and the run stopped. It can also
  happen for real: if you unsaved more than half of your library and saved new items in the same stretch, the new ones
  don't count towards M. If that is what happened, use **Delete library**, then **Full sync**: that mirrors Instagram
  exactly.
- **`empty collection list`**: Instagram listed no collections although the library has some. Try **Sync** again later.
  If you really did delete every collection on Instagram, use **Delete library**, then **Sync**.
- **`empty saved feed`**: a Full sync walked All Saved to its end without seeing a single item, over a library that has
  items. If you really did unsave everything, use **Delete library**, then **Sync**. Otherwise try again later.
- **`more_available` or `next_max_id`**: a page didn't say whether more pages follow, or said "more" with no cursor.
  The app treats that as a broken answer rather than the end of the feed.
- **`items[N]…`, `items`, `$`, `status`, `user`**: a required field is missing, or the answer isn't JSON, or Instagram
  said `fail`. Run the Adapter lab and paste the result.
- **`http.<code>` or `http.<code>.unreadable`**: an HTTP error the app doesn't classify (an unreadable answer is
  reported conservatively, because it could hide a rate limit).

### Sync: another Instagram account

**"This library belongs to another Instagram account. Delete library to switch."**: the session is not the account this
library was first synced from (a different account was pasted or logged in). The run stopped right after its session
check, so nothing was fetched, written or removed, and every **Resume** stops the same way. To switch accounts, use
**Delete library**, then **Sync**. To keep this library, log out, log in to its account, then **Resume**.

### Sync: thumbnails

**A sync ended Done but some thumbnails are missing**: Instagram's image servers told the app to slow down (a rate
limit), so the app stopped downloading thumbnails for the rest of that run. The items are all in the library. A later
**Full sync** fetches the missing thumbnails.

### Buttons that are greyed out

- **Sync, Full sync, Resume:** a run is going, a cooldown is active, or the session isn't "Logged in as @handle" (with
  Mock mode off).
- **Mock mode switch:** a run is going, or the screen hasn't read the latest run yet.
- **Adapter lab (Sync → Developer):** the session isn't "Logged in as @handle". In the lab, **First collection** and
  **Media info** also wait for an id from **Collections** and **All Saved**, and every button waits while a call is out.
- **Delete library:** a run is going.

### Adapter lab messages

"Log in on the Sync screen to use the lab." (no valid session). A line under the buttons, "`<button>`: `<text>`", names the
call that failed: "Cooling down after a rate limit" or "The 24-hour request budget is used up" (nothing was sent),
"Unexpected Instagram response at `<path>`" (note the path), "Instagram is limiting requests" (a cooldown has started: stop
tapping), "The call failed", or "Couldn't save the scrubbed copy".

### The viewer: messages over a video

- **"Instagram session needs attention (Sync screen)"**: no valid login, so nothing was sent. Fix it on Sync.
- **"This item is no longer available on Instagram"**: Instagram doesn't have the reel any more. Your copy stays.
- **"Offline: this video isn't cached yet"**: no connection, and the reel was never watched in full.
- **"Video can't load right now: ..."**: followed by "Cooling down after a rate limit" or "The 24-hour request budget is
  used up": the app won't ask Instagram for a new link yet.
- **"Instagram is limiting requests"**: a rate limit just happened; the cooldown has started.
- **"Can't load this video right now"**: an unexpected failure while asking for a new link.
- **"Can't play this video"**: the player failed and one renewal (if it was a 403 or 410) didn't help.
- **"Not available on Instagram"** (a tile with no picture): the item has no thumbnail.

In every case **Open on Instagram** stays under the thumbnail.
