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

## What works today

| Milestone | State |
|---|---|
| M0 Skeleton, M1 Mock app | Done. Home, grid, viewer, search and sync work on a built-in fake library of 2,000 items. |
| M2 Session | Code done. Logging in through Instagram's own page (or pasting a `sessionid`) needs your on-phone check (see below). |
| M3–M4 Real sync | In progress. The real backend and its thumbnail downloader are wired in, behind **Mock mode** (debug builds, see section 3). It has not been run against Instagram yet, and the engine's safety gates in [`TODO.md`](TODO.md) are not all in. |
| M5–M6 (video, release build) | Not started. |

A debug build starts in Mock mode: the library you see comes from the fake backend, and tapping **Sync** fills it with
generated placeholder items and never contacts Instagram. Turning Mock mode off (on the phone only) switches to the real
library.

## 1. One-time setup on the Mac

1. Install **Android Studio**. It bundles the JDK, the Android SDK and the emulator. In its SDK Manager, install
   **Android SDK Platform 36** and **37**, plus **Android SDK Platform-Tools** (for `adb`).
2. Clone the repo and enable the secret guard (it refuses commits that contain session material):

   ```bash
   git clone https://github.com/YuriiMurha/reels.git
   ```

   ```bash
   git -C reels config core.hooksPath .githooks
   ```

3. Point Gradle at the SDK. Create `reels/local.properties` (gitignored) with one line, replacing the user name if
   yours differs:

   ```properties
   sdk.dir=/Users/yurii/Library/Android/sdk
   ```

4. Every Gradle command below needs Android Studio's JDK. Run this once per terminal session:

   ```bash
   export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ANDROID_HOME="$HOME/Library/Android/sdk"
   ```

5. Check that everything builds and the tests pass (takes a few minutes the first time):

   ```bash
   ./gradlew check
   ```

## 2. Connect the phone

1. On the phone: **Settings → About phone →** tap **Build number** seven times to unlock Developer options.
2. **Settings → System → Developer options →** turn on **USB debugging** (or **Wireless debugging**).
3. Plug the phone in (or pair it over Wi-Fi with `adb pair`) and accept the "Allow USB debugging?" prompt.
4. Confirm the Mac sees it:

   ```bash
   adb devices
   ```

`./gradlew installDebug` installs to **every** connected device. If an emulator is running too, stop it, or
install to the phone only. `adb -d` means "the one device attached by USB", so it skips an emulator, and its serial
can be passed to Gradle:

```bash
ANDROID_SERIAL="$(adb -d get-serialno)" ./gradlew installDebug
```

(With Wireless debugging the phone is not a USB device, so `-d` finds nothing: stop the emulator and run plain
`./gradlew installDebug`.)

Never log into Instagram on an emulator: the account should only ever see your phone.

## 3. Install and run

```bash
./gradlew installDebug
```

Then open **Reels** on the phone.

- **Saved** (home) lists All Saved, Uncategorized and your collections. Tap a card for its grid. The first time, the library is empty: tap **Open Sync**.
- **Grid**: tap a tile for the full-screen viewer; swipe up and down; **Back** returns to the same tile.
- **Search** (magnifier, top right) searches captions, authors and collection names, and can narrow to reels or
  posts.
- **Sync** (the status chip, top right): **Sync** fetches what's new, **Full sync** also mirrors unsaves, **Delete library**
  wipes the local copy (your login is kept). Progress, budgets and any cooldown show here.

### Mock mode (debug builds)

A debug build starts on the **fake library**, so an emulator, or a phone before you have logged in, never needs a login
and never contacts Instagram. A release build has no such switch and always uses the real library.

- **Where:** Sync (status chip, top right), scroll to **Developer**, **Mock mode (fake library)**. It is **on by
  default**.
- **For real sync, on the phone:** switch it **off**. The app restarts on the real library, which is empty until its
  first sync. Log in (section 4) and sync from there. The switch is disabled while a sync is running (and for a moment
  after the screen opens, until it has read the latest run). Never turn it off on an emulator.
- **Nothing is lost by switching back and forth.** The fake library and the real one are kept separately (fake:
  `reels.db`, real: `library.db`; their thumbnails too). Only the request log behind the 600-per-24-hour budget is
  shared: session checks and lab calls count against it even in Mock mode.
- Real sync is still being finished (the unticked M4 gates in [`TODO.md`](TODO.md)). Until they are in, use **Sync**
  (it only adds) and hold off on **Full sync** (it removes unsaves).

## 4. Log in (on the phone only)

On the Sync screen, under **Instagram session**:

- **Log in** opens Instagram's own login page inside the app. Log into the throwaway account there and finish any
  2FA. The screen closes by itself and shows "Logged in as @handle".
- **Paste sessionid** is the fallback: copy the `sessionid` cookie from a mobile browser on the same phone that is
  logged into the test account, and paste it.
- **Check now** asks Instagram once whether the session still works. **Log out** removes the session from the
  phone and keeps the library.
- If Instagram asks for verification, **Resolve on Instagram** opens it; finish there, then tap **Check again**.

The app keeps the session only in Android's WebView cookie store. It is never written to a file, logged or backed
up.

## 5. On-phone check for M2 (please run once)

Watch the app's Instagram requests in a second terminal while you test (debug builds log headers only, with every
cookie and token redacted):

```bash
adb logcat -c
```

```bash
adb logcat -s InstagramHttp
```

1. Install, open **Sync → Log in**, log into the throwaway account and finish any 2FA.
2. The screen closes and Sync shows "Logged in as @handle". Logcat shows exactly **one**
   `GET .../api/v1/users/<id>/info/` with `Cookie: ██` and a `200`.
3. Kill the app and reopen it. It still says "Logged in as @handle", and logcat shows **no** new request.
4. Tap **Check now** twice quickly: exactly one request.
5. **Log out**: "Not logged in". **Log in** works again.
6. Optional: log out, then **Paste sessionid** from a mobile browser on this phone: "Logged in as @handle".

While logged in, also note these, for the next milestone:

- On the Mac, open `chrome://inspect/#devices` in Chrome, click **inspect** under the app's WebView, open the
  **Network** tab, and open any page in the WebView. On any `/api/v1/` request, note the `X-IG-App-ID` header. The
  app currently sends `936619743392459`, the desktop-web value; mobile web often uses `1217981644879628`.
- On the same requests, does an `X-Requested-With: io.github.yuriimurha.reels` header appear?
- If an error message appears, is it in your phone's language?
- Does any request show up twice in logcat?
- If you hit a checkpoint: did the `sessionid` change during it?

If step 2 says "Adapter needs repair" instead, look up the request that returns your username in the
`chrome://inspect` Network tab and note its path. Send the answers back in a Claude session, and they get recorded
in `ARCHITECTURE.md` and `TODO.md`.

## 6. Adapter lab (M3 spike, debug builds)

The lab shows what Instagram's endpoints really send back, so the parsers can be checked against real responses. It is
in debug builds only (`./gradlew installDebug`); a release build has no Developer section. Use it on the phone, logged
in to the throwaway account.

**What it does.** Each button sends exactly **one** request as the logged-in account, through the app's one pacer: at
least 2 seconds after the previous request, counted in the 600-per-24-hour budget, and refused during a cooldown. A
rate-limit answer starts the usual cooldown (1 hour, then 24 hours) and the lab refuses further taps until it ends:
don't tap again to retry. The screen shows the call, the HTTP code, how the app classified the answer, and the
response **shape** (key names, types and lengths; identifying values are redacted). It also saves a scrubbed copy (synthetic ids, handles,
captions and links) on the phone, and the path shows under the result.

**Run it once.**

1. **Sync → Developer → Adapter lab.** The button is enabled only while Sync says "Logged in as @handle".
2. Tap each button once, from top to bottom, and wait for its result before the next tap:
   **Who am I**, **Collections**, **All Saved (page 1)**, **First collection (page 1)** (enabled once Collections has
   returned a collection) and **Media info (first saved item)** (enabled once All Saved has returned an item).
3. Each button overwrites its own file, so export after the whole run. A button you never tapped has no file, and
   neither does one whose latest answer was not JSON: that answer removes the button's earlier file, so an exported
   file is never older than the result the screen showed. A tap that was refused (cooldown) or failed before any
   answer arrived leaves the earlier file as it was.

**Export the scrubbed files.** In an empty folder outside the repo:

```bash
mkdir -p ~/reels-lab && cd ~/reels-lab
```

One command per file, with the phone connected by USB. `adb -d` talks to the one USB-attached device, so these work
with an emulator running too. (With Wireless debugging, stop the emulator and drop the `-d`.)

```bash
adb -d exec-out run-as io.github.yuriimurha.reels cat files/lab/current_user.json > current_user.json
```

```bash
adb -d exec-out run-as io.github.yuriimurha.reels cat files/lab/collections.json > collections.json
```

```bash
adb -d exec-out run-as io.github.yuriimurha.reels cat files/lab/saved_all.json > saved_all.json
```

```bash
adb -d exec-out run-as io.github.yuriimurha.reels cat files/lab/saved_collection.json > saved_collection.json
```

```bash
adb -d exec-out run-as io.github.yuriimurha.reels cat files/lab/media_info.json > media_info.json
```

If an exported file contains an error such as `No such file or directory`, that button has no scrubbed copy (never tapped, or its latest answer was not JSON).

**Read each scrubbed file before committing it as a fixture: redaction is heuristic.** A bare lowercase handle used
as a key, or as a one-word value, can't be told from schema and is kept. The pre-commit guard only catches session
material, not personal data.

**Then:** give the five files and the on-screen shapes (long-press the shape text to select and copy it, or send a
screenshot) to a Claude session and ask it to answer the seven spike questions in section 6.3 of the design doc: the
working endpoints and headers, whether saved items carry `saved_collection_ids`, whether a save timestamp exists, the
page size and cursor, the media-type mapping, the error payload formats, and the CDN expiry parameter. The answers
get recorded in `ARCHITECTURE.md` and `TODO.md`, and the files you have read become test fixtures.

## Troubleshooting

- **`SDK location not found`**: `local.properties` is missing or points to the wrong folder (step 1.3).
- **`Unsupported class file major version` or a Gradle JVM error**: `JAVA_HOME` isn't set in this terminal
  (step 1.4).
- **`INSTALL_FAILED_UPDATE_INCOMPATIBLE`**: a build signed with another key is installed. Uninstall it first (this
  also deletes the local library and session):

  ```bash
  adb uninstall io.github.yuriimurha.reels
  ```

- **The login page is blank**: report it. Note whether a cookie/consent dialog is visible, and look at the page in
  `chrome://inspect`.
- **A commit is refused with `pre-commit: ... session material` or `... sessionid value`**: the secret guard
  found something that looks like a cookie or session id. Remove it; never bypass the hook.
