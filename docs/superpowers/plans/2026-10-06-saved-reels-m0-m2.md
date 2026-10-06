# Saved Reels M0–M2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the project skeleton (M0), the complete app running on a deterministic fake Instagram library (M1), and real WebView login with paced session validation (M2).

**Architecture:** Two Gradle modules. `:instagram` is a pure Kotlin/JVM library: the adapter contract, the fake client, the cookie bridge, the error classifier and the session probe. `:app` holds the Compose UI, Room, the Pacer, the sync engine on WorkManager, and the session. The UI reads only Room, and every Instagram call goes through a Pacer.

**Tech Stack:** Kotlin 2.4.20, AGP 9.4.1 (built-in Kotlin), Gradle 9.8.0, Compose BOM 2026.09.00 (Material 3 1.4.0), Navigation Compose 2.10.2, Room 2.8.5 + KSP 2.3.12, Paging 3.5.1, WorkManager 2.12.0, DataStore 1.2.1, Media3 1.11.1, Coil 3.6.3, OkHttp 5.5.0, kotlinx.serialization 1.11.0, kotlinx.coroutines 1.11.0, Robolectric 4.17, JUnit 4.13.2.

**Spec:** `docs/superpowers/specs/2026-10-06-saved-reels-android-design.md`

**Scope:** Milestones M0, M1 and M2 of spec section 12. M3–M6 depend on what the M3 spike finds, so they get their own plans (see "Follow-up plans" at the end).

## Global Constraints

- Package root `io.github.yuriimurha.reels`; `:instagram` code lives under `io.github.yuriimurha.reels.instagram`.
- `minSdk 29`, `compileSdk 36`, `targetSdk 36`, JVM bytecode target 17. Robolectric tests run at `sdk=36`.
- AGP 9 has built-in Kotlin: never apply `org.jetbrains.kotlin.android`. `:instagram` applies only `org.jetbrains.kotlin.jvm` (plus serialization).
- `:instagram` has no Android dependency. Nothing in `:app` builds Instagram URLs, sets Instagram headers, or parses Instagram JSON.
- Every Instagram API call goes through `Pacer`. Real traffic uses `PacingPolicy.Conservative` only. `PacingPolicy.Fast` is referenced in `src/main` only from `di/Backend.kt`, and a test enforces that.
- Conservative values, verbatim from spec 7.3: gaps random 4–12 s with a median of ~6 s (log-normal); a 60–180 s break every 15–30 requests; 300 requests per run; 600 per rolling 24 h; the interactive lane has a 2 s minimum gap and priority; the CDN lane allows 2 concurrent downloads with 0.2–0.8 s jitter and is not budgeted.
- Cooldown after a `RateLimited`: 1 h, or 24 h if a second one happens within 24 h. Persisted.
- Transient backoff: 30 s, 60 s, 120 s, 240 s, each ±20 %, then the run is PAUSED.
- Nothing is ever deleted except by a FULL run's reconcile of a scope whose walk reached the end in that same run.
- `android:allowBackup="false"`, and the data extraction rules exclude every domain.
- Never log or commit session material. HTTP logging exists only in debug builds, at header level, with `Cookie`, `Set-Cookie` and `X-CSRFToken` redacted. Test cookie values are short fakes (`s1`, `42%3Aab`). Never put a realistic-looking session id in any file: the pre-commit guard rejects it.
- No destructive Room migrations; the schema is exported to `app/schemas/`.
- Dark-only Material 3 UI.
- Each task's commit updates `ARCHITECTURE.md` when it adds a component (the project `CLAUDE.md` rule). Milestone-closing tasks append to `PROGRESS.md` and tick `TODO.md`.
- Stage explicit paths only (never `git add -A`). Every commit message ends with the trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

These are the inputs the spec implies but doesn't spell out, ranked by how likely they are to bite. Each one has a test in the task named.

1. **The process is killed mid-sync:** the `sync_run` row stays RUNNING with no worker behind it. The next app start must show it as resumable, and Sync must resume from the cursor. Task 10 test `recoverInterruptedRunsPausesOrphans`.
2. **Sync is tapped twice quickly, or tapped while a run is RUNNING:** this must never create a second run or a parallel worker. Task 10 test `doubleTapDoesNotCreateSecondRun`.
3. **Search input containing FTS syntax** (quotes, `-`, `*`, `OR`, emoji, punctuation only, non-Latin letters) must never crash the query. It searches the words, or shows nothing. Task 5 tests in `FtsQueryTest` and `searchWithHostileInputDoesNotCrash`.
4. **Pasted `sessionid` variants:** with a `sessionid=` prefix, a trailing `;`, quotes, surrounding spaces, or `:` vs `%3A`. These are accepted; garbage is rejected with **no network request**. Task 17 `SessionIdInputTest` and `pasteRejectsGarbageWithoutRequest`.
5. **The login screen sees cookies that are present but expired.** The one-second cookie poll must not re-validate the same `sessionid` again and again, because each validation is an Instagram request. Task 18 test `doesNotRevalidateSameSessionId`.

## Prerequisites (owner)

- Android Studio installed and its first-run wizard completed, which downloads the SDK and accepts the licences. Then install **Android SDK Platform 36** through SDK Manager if the wizard didn't.
- Homebrew tools: `brew install gradle ffmpeg`. Gradle is used once to generate the wrapper; ffmpeg generates the sample clip.
- Every Gradle command in this plan assumes this shell environment:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

- Integration (push / PR) follows the owner's decision recorded in `TODO.md`. Until then, commit on the worktree branch and don't push.

## File Map

```
settings.gradle.kts, build.gradle.kts, gradle.properties, gradle/libs.versions.toml   build setup
.githooks/pre-commit, scripts/test-secret-guard.sh                                    secret guard
instagram/src/main/kotlin/io/github/yuriimurha/reels/instagram/
  Models.kt                 MediaType, Account, Page, RemoteCollection, RemoteMedia
  InstagramClient.kt        SessionProbe + InstagramClient contracts
  InstagramException.kt     typed failures (spec 6.4)
  Permalinks.kt             instagram.com links for a media code
  fake/FakeLibrary.kt       deterministic mutable library
  fake/FakeInstagramClient.kt  fixture-backed InstagramClient + scripted failures
  web/CookieStore.kt        cookie-store contract + CookieStoreJar (OkHttp bridge)
  web/WebHeaders.kt         User-Agent, X-IG-App-ID, CSRF header interceptor
  web/HttpClientFactory.kt  OkHttp client: no redirects, redacted debug logging
  web/ErrorClassifier.kt    HTTP response -> InstagramException
  web/WebJson.kt            Call.await, getJsonObject, JSON helpers
  web/WebEndpoints.kt       URL builders
  web/WebSessionProbe.kt    currentUser() over the web API
app/src/main/kotlin/io/github/yuriimurha/reels/
  ReelsApp.kt, MainActivity.kt
  di/AppContainer.kt, di/Backend.kt, di/ReelsWorkerFactory.kt
  data/db/Entities.kt, data/db/MediaDao.kt, data/db/CollectionDao.kt, data/db/SyncDao.kt,
  data/db/ApiRequestDao.kt, data/db/ReelsDatabase.kt
  data/library/FtsQuery.kt, data/library/MediaSource.kt, data/library/LibraryRepository.kt
  data/media/MediaFetcher.kt, data/media/FakeMediaFetcher.kt, data/media/ThumbnailStore.kt,
  data/media/VideoSourceResolver.kt
  data/settings/SettingsStore.kt
  sync/pacing/PacingPolicy.kt, Pacer.kt, RequestLog.kt, CooldownStore.kt, PacerRefusal.kt,
  sync/pacing/RoomRequestLog.kt, DataStoreCooldownStore.kt, TransientRetry.kt
  sync/SortKeys.kt, sync/SessionSignals.kt, sync/SyncEngine.kt
  sync/SyncScheduler.kt, sync/SyncController.kt, sync/SyncWorker.kt, sync/SyncNotifications.kt
  session/AndroidCookieStore.kt, session/SessionState.kt, session/SessionIdInput.kt,
  session/SessionRepository.kt
  ui/theme/Theme.kt, ui/LocalAppContainer.kt, ui/ReelsNavHost.kt
  ui/common/Thumbnail.kt, ui/common/SyncStatusSummary.kt, ui/common/MediaGrid.kt
  ui/home/*, ui/grid/*, ui/viewer/*, ui/search/*, ui/sync/*, ui/login/*
```

---

## Milestone M0: Skeleton

### Task 1: Gradle skeleton with privacy manifest

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`
- Create (generated): `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`
- Create: `instagram/build.gradle.kts`
- Create: `app/build.gradle.kts`, `app/proguard-rules.pro`
- Create: `app/src/main/AndroidManifest.xml`, `app/src/main/res/values/strings.xml`, `app/src/main/res/values/themes.xml`, `app/src/main/res/xml/data_extraction_rules.xml`
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/MainActivity.kt`
- Create: `app/src/test/resources/robolectric.properties`
- Test: `app/src/test/kotlin/io/github/yuriimurha/reels/PrivacyManifestTest.kt`
- Modify: `ARCHITECTURE.md`

**Interfaces:**
- Consumes: nothing.
- Produces: the `libs` version catalog aliases used by every later task, `R.xml.data_extraction_rules`, `R.string.app_name`, the theme `@style/Theme.Reels`, and `MainActivity` (replaced in Task 11).

- [ ] **Step 1: Pre-flight the toolchain**

Run:
```bash
"$JAVA_HOME/bin/java" -version
ls "$HOME/Library/Android/sdk/platforms"
gradle --version
```
Expected: Java 21 (JetBrains Runtime); `android-36` is in the platforms list; Gradle prints a version. If `android-36` is missing, the owner installs "Android SDK Platform 36" from Android Studio → Settings → Languages & Frameworks → Android SDK. Stop and ask rather than accepting licences yourself.

- [ ] **Step 2: Write the build files**

`settings.gradle.kts`:
```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "reels"
include(":app", ":instagram")
```

`build.gradle.kts`:
```kotlin
// Declaring the Kotlin plugins here (apply false) puts KGP 2.4.20 on the shared build classpath,
// which overrides the older KGP that AGP 9 bundles for its built-in Kotlin support.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}
```

`gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8
org.gradle.caching=true
android.useAndroidX=true
kotlin.code.style=official
```

`gradle/libs.versions.toml`:
```toml
[versions]
agp = "9.4.1"
kotlin = "2.4.20"
ksp = "2.3.12"
composeBom = "2026.09.00"
activity = "1.13.0"
lifecycle = "2.11.0"
navigation = "2.10.2"
room = "2.8.5"
paging = "3.5.1"
work = "2.12.0"
datastore = "1.2.1"
media3 = "1.11.1"
coreKtx = "1.19.1"
coil = "3.6.3"
okhttp = "5.5.0"
serialization = "1.11.0"
coroutines = "1.11.0"
robolectric = "4.17"
junit = "4.13.2"
androidxTestCore = "1.7.0"
androidxTestExtJunit = "1.3.0"

[libraries]
androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "coreKtx" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activity" }
androidx-lifecycle-runtime-compose = { module = "androidx.lifecycle:lifecycle-runtime-compose", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-navigation-compose = { module = "androidx.navigation:navigation-compose", version.ref = "navigation" }
compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
compose-ui = { module = "androidx.compose.ui:ui" }
compose-foundation = { module = "androidx.compose.foundation:foundation" }
compose-material3 = { module = "androidx.compose.material3:material3" }
compose-material-icons-core = { module = "androidx.compose.material:material-icons-core" }
compose-ui-tooling = { module = "androidx.compose.ui:ui-tooling" }
compose-ui-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
compose-ui-test-junit4 = { module = "androidx.compose.ui:ui-test-junit4" }
compose-ui-test-manifest = { module = "androidx.compose.ui:ui-test-manifest" }
room-runtime = { module = "androidx.room:room-runtime", version.ref = "room" }
room-ktx = { module = "androidx.room:room-ktx", version.ref = "room" }
room-paging = { module = "androidx.room:room-paging", version.ref = "room" }
room-compiler = { module = "androidx.room:room-compiler", version.ref = "room" }
paging-runtime = { module = "androidx.paging:paging-runtime", version.ref = "paging" }
paging-compose = { module = "androidx.paging:paging-compose", version.ref = "paging" }
work-runtime = { module = "androidx.work:work-runtime-ktx", version.ref = "work" }
datastore-preferences = { module = "androidx.datastore:datastore-preferences", version.ref = "datastore" }
media3-exoplayer = { module = "androidx.media3:media3-exoplayer", version.ref = "media3" }
media3-ui-compose = { module = "androidx.media3:media3-ui-compose", version.ref = "media3" }
coil-compose = { module = "io.coil-kt.coil3:coil-compose", version.ref = "coil" }
okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
okhttp-logging = { module = "com.squareup.okhttp3:logging-interceptor", version.ref = "okhttp" }
okhttp-mockwebserver = { module = "com.squareup.okhttp3:mockwebserver3", version.ref = "okhttp" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
kotlinx-coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
kotlin-test = { module = "org.jetbrains.kotlin:kotlin-test-junit", version.ref = "kotlin" }
junit = { module = "junit:junit", version.ref = "junit" }
robolectric = { module = "org.robolectric:robolectric", version.ref = "robolectric" }
androidx-test-core = { module = "androidx.test:core-ktx", version.ref = "androidxTestCore" }
androidx-test-ext-junit = { module = "androidx.test.ext:junit-ktx", version.ref = "androidxTestExtJunit" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
room = { id = "androidx.room", version.ref = "room" }
```

`instagram/build.gradle.kts`:
```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget = JvmTarget.JVM_17 }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
```

`app/build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "io.github.yuriimurha.reels"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.yuriimurha.reels"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(project(":instagram"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.room.paging)
    ksp(libs.room.compiler)
    implementation(libs.paging.runtime)
    implementation(libs.paging.compose)
    implementation(libs.work.runtime)
    implementation(libs.datastore.preferences)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui.compose)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
}
```

`app/proguard-rules.pro`:
```
# Release keep rules. Room, kotlinx.serialization and Media3 ship their own consumer rules.
```

`app/src/test/resources/robolectric.properties`:
```properties
sdk=36
```

- [ ] **Step 3: Generate the Gradle wrapper**

Run:
```bash
gradle wrapper --gradle-version 9.8.0 --distribution-type bin
./gradlew --version
```
Expected: `Gradle 9.8.0`.

- [ ] **Step 4: Write the failing privacy test**

`app/src/test/kotlin/io/github/yuriimurha/reels/PrivacyManifestTest.kt`:
```kotlin
package io.github.yuriimurha.reels

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

/** Spec 4.4: the session and library must never reach Google backup or a device transfer. */
@RunWith(AndroidJUnit4::class)
class PrivacyManifestTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun backupIsDisabled() {
        val info = context.packageManager.getApplicationInfo(context.packageName, 0)
        assertEquals(0, info.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }

    @Test
    fun dataExtractionRulesExcludeEveryDomain() {
        val excluded = mutableMapOf<String, MutableSet<String>>()
        var section: String? = null
        val parser = context.resources.getXml(R.xml.data_extraction_rules)
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "cloud-backup", "device-transfer" -> section = parser.name
                "exclude" -> excluded.getOrPut(section!!) { mutableSetOf() } += parser.getAttributeValue(null, "domain")
                "include" -> fail("data extraction rules must not include anything")
            }
        }
        val everyDomain = setOf(
            "root", "file", "database", "sharedpref", "external",
            "device_root", "device_file", "device_database", "device_sharedpref",
        )
        assertEquals(everyDomain, excluded["cloud-backup"])
        assertEquals(everyDomain, excluded["device-transfer"])
    }
}
```

- [ ] **Step 5: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.yuriimurha.reels.PrivacyManifestTest"`
Expected: FAIL, because the manifest and `R.xml.data_extraction_rules` don't exist yet (compilation error).

- [ ] **Step 6: Add the manifest, resources and activity**

`app/src/main/AndroidManifest.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />

    <application
        android:allowBackup="false"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.Reels">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`app/src/main/res/xml/data_extraction_rules.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- Spec 4.4: nothing leaves the device, not even through Google backup or a phone-to-phone transfer. -->
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="root" />
        <exclude domain="file" />
        <exclude domain="database" />
        <exclude domain="sharedpref" />
        <exclude domain="external" />
        <exclude domain="device_root" />
        <exclude domain="device_file" />
        <exclude domain="device_database" />
        <exclude domain="device_sharedpref" />
    </cloud-backup>
    <device-transfer>
        <exclude domain="root" />
        <exclude domain="file" />
        <exclude domain="database" />
        <exclude domain="sharedpref" />
        <exclude domain="external" />
        <exclude domain="device_root" />
        <exclude domain="device_file" />
        <exclude domain="device_database" />
        <exclude domain="device_sharedpref" />
    </device-transfer>
</data-extraction-rules>
```

`app/src/main/res/values/strings.xml`:
```xml
<resources>
    <string name="app_name">Reels</string>
</resources>
```

`app/src/main/res/values/themes.xml`:
```xml
<resources>
    <style name="Theme.Reels" parent="android:Theme.Material.NoActionBar">
        <item name="android:windowBackground">#FF0B0B0C</item>
    </style>
</resources>
```

`app/src/main/kotlin/io/github/yuriimurha/reels/MainActivity.kt`:
```kotlin
package io.github.yuriimurha.reels

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Text("Reels") }
    }
}
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.yuriimurha.reels.PrivacyManifestTest"`
Expected: PASS (2 tests).

- [ ] **Step 8: Run the whole check**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL. `:instagram:test` reports no tests (`NO-SOURCE`), and lint passes. If lint fails only on "newer library version available" or "obsolete SDK" warnings that it escalates to errors, add a `lint { warningsAsErrors = false }` block to `android {}` rather than downgrading anything.

- [ ] **Step 9: Record the components**

In `ARCHITECTURE.md`, replace the `## Status` paragraph with:
```markdown
## Status

M0 in progress. The design is in
[`docs/superpowers/specs/2026-10-06-saved-reels-android-design.md`](docs/superpowers/specs/2026-10-06-saved-reels-android-design.md);
this file describes only what exists.

## Components

| Component | Where | What it does |
|---|---|---|
| Build | `settings.gradle.kts`, `gradle/libs.versions.toml` | Two modules, versions pinned in one catalog. AGP 9 built-in Kotlin. |
| `:instagram` | `instagram/` | Pure Kotlin/JVM module reserved for all Instagram-specific code. Empty so far. |
| `:app` | `app/` | Android app. Backup and device transfer are disabled (`data_extraction_rules.xml`). |
```

- [ ] **Step 10: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle.properties gradle/libs.versions.toml gradle/wrapper gradlew gradlew.bat instagram/build.gradle.kts app/build.gradle.kts app/proguard-rules.pro app/src/main/AndroidManifest.xml app/src/main/res app/src/main/kotlin/io/github/yuriimurha/reels/MainActivity.kt app/src/test ARCHITECTURE.md
git commit -m "build: two-module Gradle skeleton with backup disabled" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 2: Secret guard

**Files:**
- Create: `.githooks/pre-commit`
- Create: `scripts/test-secret-guard.sh`
- Modify: `ARCHITECTURE.md`, `PROGRESS.md`, `TODO.md`

**Interfaces:**
- Consumes: nothing.
- Produces: a pre-commit hook, enabled per clone with `git config core.hooksPath .githooks`. Later tasks must keep test cookie values short (`s1`, `42%3Aab`) so they don't trip it.

- [ ] **Step 1: Write the failing guard test**

`scripts/test-secret-guard.sh`:
```bash
#!/usr/bin/env bash
# Plants session material in a throwaway repo and checks that .githooks/pre-commit blocks it.
# Secret-looking strings are assembled at runtime so this file never contains one itself.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

git -C "$tmp" init -q
git -C "$tmp" config user.email guard@test.invalid
git -C "$tmp" config user.name guard
mkdir -p "$tmp/.githooks"
cp "$root/.githooks/pre-commit" "$tmp/.githooks/pre-commit"
chmod +x "$tmp/.githooks/pre-commit"
git -C "$tmp" config core.hooksPath .githooks

planted_sid="session""id=1234567890%3A$(printf 'Ab%.0s' $(seq 1 12))"
planted_cookie="Coo""kie: session""id=x"

# check <blocked|allowed> <description> <path> <content>
check() {
  local want="$1" what="$2" path="$3" content="$4" got
  mkdir -p "$tmp/$(dirname "$path")"
  printf '%s\n' "$content" > "$tmp/$path"
  git -C "$tmp" add "$path"
  if git -C "$tmp" commit -q -m "$what" >/dev/null 2>&1; then got=allowed; else got=blocked; fi
  if [ "$got" = blocked ]; then
    git -C "$tmp" reset -q
    rm -f "$tmp/$path"
  fi
  if [ "$got" != "$want" ]; then
    echo "FAIL: $what was $got, expected $want"
    exit 1
  fi
  echo "ok: $what ($got)"
}

check allowed "ordinary file" "README.md" "hello"
check allowed "prose that mentions sessionid" "docs/howto.md" "Paste your sessionid; never share the Cookie: header"
check blocked "HAR capture" "captures/run.har" "{}"
check blocked "session json" "app/session.json" "{}"
check blocked "cookies dump" "tmp/cookies-www.txt" "x"
check blocked "planted sessionid value" "notes.txt" "token $planted_sid"
check blocked "planted Cookie header" "Api.kt" "val h = \"$planted_cookie\""
echo "secret guard: all checks passed"
```

- [ ] **Step 2: Run it to verify it fails**

Run: `bash scripts/test-secret-guard.sh`
Expected: FAIL. `cp` errors because `.githooks/pre-commit` doesn't exist yet.

- [ ] **Step 3: Write the hook**

`.githooks/pre-commit`:
```bash
#!/usr/bin/env bash
# Blocks commits that would publish Instagram session material (this repo is public).
# Enable once per clone: git config core.hooksPath .githooks
set -euo pipefail

fail=0

while IFS= read -r -d '' file; do
  case "$file" in
    *.har|*.session|*cookies*.txt|session*.json|*/session*.json)
      echo "pre-commit: refusing to commit $file (looks like session material)"
      fail=1
      ;;
  esac
done < <(git diff --cached --name-only -z --diff-filter=ACMR)

added="$(git diff --cached -U0 --diff-filter=ACMR | grep -E '^\+' | grep -vE '^\+\+\+' || true)"
if printf '%s\n' "$added" | grep -qE 'sessionid=[0-9]+(%3A|:)[A-Za-z0-9]{10,}'; then
  echo "pre-commit: staged changes contain a sessionid value"
  fail=1
fi
if printf '%s\n' "$added" | grep -qiE '(set-)?cookie: *[a-z0-9_-]+='; then
  echo "pre-commit: staged changes contain a Cookie header with a value"
  fail=1
fi

exit "$fail"
```

Run: `chmod +x .githooks/pre-commit scripts/test-secret-guard.sh`

- [ ] **Step 4: Run the test to verify it passes**

Run: `bash scripts/test-secret-guard.sh`
Expected: seven `ok:` lines, then `secret guard: all checks passed`.

- [ ] **Step 5: Enable the hook for this clone**

Run:
```bash
git config core.hooksPath .githooks
git config --get core.hooksPath
```
Expected: `.githooks`.

- [ ] **Step 6: Close milestone M0 in the docs**

Append to the `## Components` table in `ARCHITECTURE.md`:
```markdown
| Secret guard | `.githooks/pre-commit`, `scripts/test-secret-guard.sh` | Blocks staged HAR/session/cookie files, `sessionid` values and `Cookie:` headers. Enable per clone with `git config core.hooksPath .githooks`. |
```

Append to `PROGRESS.md`:
```markdown

## 2026-10-06: M0 skeleton

- Two-module Gradle build (AGP 9.4.1 built-in Kotlin, Kotlin 2.4.20, Gradle 9.8.0); `./gradlew check` green.
- Backup and device transfer disabled, with a test that checks every domain is excluded.
- Pre-commit secret guard with a self-test script.
```
(Use the actual date of the work if it isn't 2026-10-06.)

In `TODO.md`, tick `M0 Skeleton`.

- [ ] **Step 7: Commit**

```bash
git add .githooks/pre-commit scripts/test-secret-guard.sh ARCHITECTURE.md PROGRESS.md TODO.md
git commit -m "chore: pre-commit guard against committing session material" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Milestone M1: Mock app

### Task 3: Adapter contract and fake client

**Files:**
- Create: `instagram/src/main/kotlin/io/github/yuriimurha/reels/instagram/Models.kt`
- Create: `instagram/src/main/kotlin/io/github/yuriimurha/reels/instagram/InstagramClient.kt`
- Create: `instagram/src/main/kotlin/io/github/yuriimurha/reels/instagram/InstagramException.kt`
- Create: `instagram/src/main/kotlin/io/github/yuriimurha/reels/instagram/Permalinks.kt`
- Create: `instagram/src/main/kotlin/io/github/yuriimurha/reels/instagram/fake/FakeLibrary.kt`
- Create: `instagram/src/main/kotlin/io/github/yuriimurha/reels/instagram/fake/FakeInstagramClient.kt`
- Test: `instagram/src/test/kotlin/io/github/yuriimurha/reels/instagram/fake/FakeLibraryTest.kt`
- Test: `instagram/src/test/kotlin/io/github/yuriimurha/reels/instagram/fake/FakeInstagramClientTest.kt`
- Test: `instagram/src/test/kotlin/io/github/yuriimurha/reels/instagram/PermalinksTest.kt`
- Modify: `ARCHITECTURE.md`

**Interfaces:**
- Consumes: nothing.
- Produces (package `io.github.yuriimurha.reels.instagram`):
  - `enum class MediaType { REEL, VIDEO, IMAGE, CAROUSEL }`
  - `data class Account(val pk: String, val username: String)`
  - `data class Page<T>(val items: List<T>, val nextCursor: String?)`
  - `data class RemoteCollection(val id: String, val name: String, val coverMediaPk: String?)`
  - `data class RemoteMedia(pk, code, type, author, caption: String?, takenAt: Instant, width: Int, height: Int, carouselCount: Int?, thumbnailUrl: String, videoUrl: String?, videoUrlExpiresAt: Instant?, savedCollectionIds: List<String>?)`
  - `interface SessionProbe { suspend fun currentUser(): Account }`
  - `interface InstagramClient : SessionProbe { val reportsSavedCollectionIds: Boolean; suspend fun collections(cursor: String?): Page<RemoteCollection>; suspend fun savedMedia(collectionId: String?, cursor: String?): Page<RemoteMedia>; suspend fun mediaInfo(mediaPk: String): RemoteMedia }`
  - `sealed class InstagramException`: `LoginRequired()`, `ChallengeRequired(challengeUrl: String?)`, `RateLimited()`, `Transient(cause: Throwable? = null)`, `ShapeChanged(fieldPath: String)`
  - `object Permalinks { fun of(type: MediaType, code: String): String }`
  - `fake.FakeLibrary(seed: Long = 42, itemCount: Int = 2_000, collectionCount: Int = 8)` with `collections`, `allSaved()`, `itemsIn(id)`, `media(pk)`, `addNewSaves(count, collectionIds)`, `unsave(pk)`, `setCollections(pk, ids)`
  - `fake.FakeFailures` (`fun interface`, `failureFor(call: Int): InstagramException?`) and `fake.FakeInstagramClient(library, pageSize = 20, reportsSavedCollectionIds = true, failures)` with a public `calls: List<String>`

- [ ] **Step 1: Write the failing tests**

`instagram/src/test/kotlin/io/github/yuriimurha/reels/instagram/fake/FakeLibraryTest.kt`:
```kotlin
package io.github.yuriimurha.reels.instagram.fake

import io.github.yuriimurha.reels.instagram.MediaType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FakeLibraryTest {
    @Test
    fun sameSeedGivesSameLibrary() {
        assertEquals(FakeLibrary(seed = 7, itemCount = 50).allSaved(), FakeLibrary(seed = 7, itemCount = 50).allSaved())
    }

    @Test
    fun allSavedIsNewestFirst() {
        val taken = FakeLibrary(itemCount = 100).allSaved().map { it.takenAt }
        assertEquals(taken.sortedDescending(), taken)
    }

    @Test
    fun defaultLibraryCoversEveryShape() {
        val items = FakeLibrary().allSaved()
        assertEquals(2_000, items.size)
        assertEquals(MediaType.entries.toSet(), items.map { it.type }.toSet())
        assertTrue(items.any { it.savedCollectionIds.orEmpty().isEmpty() }, "some items in no collection")
        assertTrue(items.any { it.savedCollectionIds.orEmpty().size == 2 }, "some items in two collections")
        assertTrue(items.any { it.thumbnailUrl.startsWith("fake://missing/") }, "some unavailable items")
        assertEquals(items.size, items.map { it.code }.toSet().size, "codes are unique")
    }

    @Test
    fun itemsInFollowsMembership() {
        val library = FakeLibrary(itemCount = 200)
        val inFirst = library.itemsIn("c1")
        assertTrue(inFirst.isNotEmpty())
        assertTrue(inFirst.all { "c1" in it.savedCollectionIds.orEmpty() })
    }

    @Test
    fun newSavesGoOnTopNewestFirst() {
        val library = FakeLibrary(itemCount = 30)
        val added = library.addNewSaves(3, setOf("c2"))
        assertEquals(added, library.allSaved().take(3))
        assertEquals(added, library.itemsIn("c2").take(3))
    }

    @Test
    fun unsaveAndMoveChangeTheViews() {
        val library = FakeLibrary(itemCount = 30)
        val first = library.allSaved().first()
        library.unsave(first.pk)
        assertTrue(library.allSaved().none { it.pk == first.pk })

        val second = library.allSaved().first()
        library.setCollections(second.pk, setOf("c3"))
        assertEquals(listOf("c3"), library.media(second.pk)!!.savedCollectionIds)
    }
}
```

`instagram/src/test/kotlin/io/github/yuriimurha/reels/instagram/fake/FakeInstagramClientTest.kt`:
```kotlin
package io.github.yuriimurha.reels.instagram.fake

import io.github.yuriimurha.reels.instagram.InstagramException
import io.github.yuriimurha.reels.instagram.RemoteMedia
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeInstagramClientTest {
    @Test
    fun pagesThroughAllSaved() = runTest {
        val client = FakeInstagramClient(FakeLibrary(itemCount = 45))
        val seen = mutableListOf<RemoteMedia>()
        var cursor: String? = null
        do {
            val page = client.savedMedia(collectionId = null, cursor = cursor)
            assertTrue(page.items.size <= 20)
            seen += page.items
            cursor = page.nextCursor
        } while (cursor != null)
        assertEquals(client.library.allSaved(), seen)
        assertEquals(listOf("saved:all:null", "saved:all:o:20", "saved:all:o:40"), client.calls)
    }

    @Test
    fun collectionPagesFollowMembership() = runTest {
        val client = FakeInstagramClient(FakeLibrary(itemCount = 60))
        val page = client.savedMedia(collectionId = "c1", cursor = null)
        assertEquals(client.library.itemsIn("c1").take(20), page.items)
    }

    @Test
    fun hidesSavedCollectionIdsWhenNotReported() = runTest {
        val client = FakeInstagramClient(FakeLibrary(itemCount = 10), reportsSavedCollectionIds = false)
        assertTrue(client.savedMedia(null, null).items.all { it.savedCollectionIds == null })
    }

    @Test
    fun scriptedFailureHitsExactlyThatCall() = runTest {
        val client = FakeInstagramClient(
            FakeLibrary(itemCount = 10),
            failures = FakeFailures { call -> if (call == 2) InstagramException.ChallengeRequired(null) else null },
        )
        client.currentUser()
        assertFailsWith<InstagramException.ChallengeRequired> { client.collections(null) }
        assertEquals(2, client.calls.size)
    }

    @Test
    fun cursorPastTheEndGivesAnEmptyLastPage() = runTest {
        val client = FakeInstagramClient(FakeLibrary(itemCount = 5))
        val page = client.savedMedia(null, "o:40")
        assertTrue(page.items.isEmpty())
        assertNull(page.nextCursor)
    }
}
```

`instagram/src/test/kotlin/io/github/yuriimurha/reels/instagram/PermalinksTest.kt`:
```kotlin
package io.github.yuriimurha.reels.instagram

import kotlin.test.Test
import kotlin.test.assertEquals

class PermalinksTest {
    @Test
    fun reelsUseReelPathEverythingElsePostPath() {
        assertEquals("https://www.instagram.com/reel/ABC/", Permalinks.of(MediaType.REEL, "ABC"))
        assertEquals("https://www.instagram.com/p/ABC/", Permalinks.of(MediaType.VIDEO, "ABC"))
        assertEquals("https://www.instagram.com/p/ABC/", Permalinks.of(MediaType.IMAGE, "ABC"))
        assertEquals("https://www.instagram.com/p/ABC/", Permalinks.of(MediaType.CAROUSEL, "ABC"))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :instagram:test`
Expected: FAIL with unresolved references (`FakeLibrary`, `MediaType`, `Permalinks`).

- [ ] **Step 3: Write the contract**

`instagram/src/main/kotlin/io/github/yuriimurha/reels/instagram/Models.kt`:
```kotlin
package io.github.yuriimurha.reels.instagram

import java.time.Instant

enum class MediaType { REEL, VIDEO, IMAGE, CAROUSEL }

data class Account(val pk: String, val username: String)

/** One page of a paginated Instagram listing. [nextCursor] is null on the last page. */
data class Page<T>(val items: List<T>, val nextCursor: String?)

data class RemoteCollection(val id: String, val name: String, val coverMediaPk: String?)

data class RemoteMedia(
    val pk: String,
    val code: String,
    val type: MediaType,
    val author: String,
    val caption: String?,
    val takenAt: Instant,
    val width: Int,
    val height: Int,
    val carouselCount: Int?,
    val thumbnailUrl: String,
    val videoUrl: String?,
    val videoUrlExpiresAt: Instant?,
    /** Real collections this item is saved in, or null when the response doesn't say (spec 7.2). */
    val savedCollectionIds: List<String>?,
)
```

`instagram/src/main/kotlin/io/github/yuriimurha/reels/instagram/InstagramClient.kt`:
```kotlin
package io.github.yuriimurha.reels.instagram

/** Answers "who is logged in?". Separate so login (M2) can ship before the rest of the adapter (M3). */
interface SessionProbe {
    suspend fun currentUser(): Account
}

interface InstagramClient : SessionProbe {
    /** True when [RemoteMedia.savedCollectionIds] is populated, which lets sync walk only All Saved (strategy A). */
    val reportsSavedCollectionIds: Boolean

    suspend fun collections(cursor: String?): Page<RemoteCollection>

    /** Saved items newest first. [collectionId] null means All Saved. */
    suspend fun savedMedia(collectionId: String?, cursor: String?): Page<RemoteMedia>

    /** One item with fresh media links. */
    suspend fun mediaInfo(mediaPk: String): RemoteMedia
}
```

`instagram/src/main/kotlin/io/github/yuriimurha/reels/instagram/InstagramException.kt`:
```kotlin
package io.github.yuriimurha.reels.instagram

/** Every failure the adapter reports. Messages never contain session material or challenge URLs. */
sealed class InstagramException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class LoginRequired : InstagramException("Instagram session is not logged in")

    /** [challengeUrl] is where Instagram wants the owner to verify. Never log it. */
    class ChallengeRequired(val challengeUrl: String?) : InstagramException("Instagram requires verification")

    class RateLimited : InstagramException("Instagram is limiting requests")

    class Transient(cause: Throwable? = null) : InstagramException("Temporary network or server problem", cause)

    class ShapeChanged(val fieldPath: String) : InstagramException("Unexpected Instagram response at $fieldPath")
}
```

`instagram/src/main/kotlin/io/github/yuriimurha/reels/instagram/Permalinks.kt`:
```kotlin
package io.github.yuriimurha.reels.instagram

object Permalinks {
    fun of(type: MediaType, code: String): String = when (type) {
        MediaType.REEL -> "https://www.instagram.com/reel/$code/"
        else -> "https://www.instagram.com/p/$code/"
    }
}
```

- [ ] **Step 4: Write the fake library and client**

`instagram/src/main/kotlin/io/github/yuriimurha/reels/instagram/fake/FakeLibrary.kt`:
```kotlin
package io.github.yuriimurha.reels.instagram.fake

import io.github.yuriimurha.reels.instagram.MediaType
import io.github.yuriimurha.reels.instagram.RemoteCollection
import io.github.yuriimurha.reels.instagram.RemoteMedia
import java.time.Instant
import kotlin.random.Random

/**
 * A deterministic, mutable stand-in for one account's saved items. Index 0 is the newest save.
 * Thumbnails use `fake://thumb/{pk}`; `fake://missing/{pk}` simulates an item Instagram no longer serves.
 */
class FakeLibrary(seed: Long = 42, itemCount: Int = 2_000, collectionCount: Int = 8) {
    private class Entry(val media: RemoteMedia, val collectionIds: MutableSet<String>)

    private val random = Random(seed)
    private val entries = mutableListOf<Entry>()
    private val collectionList = mutableListOf<RemoteCollection>()
    private var nextIndex = 0

    val collections: List<RemoteCollection> get() = collectionList.toList()

    init {
        require(collectionCount <= NAMES.size) { "At most ${NAMES.size} fake collections" }
        repeat(collectionCount) { i -> collectionList += RemoteCollection("c${i + 1}", NAMES[i], coverMediaPk = null) }
        // Generated oldest first, so the highest index (newest) ends up at position 0.
        val generated = List(itemCount) { newEntry(randomCollections()) }
        entries += generated.asReversed()
    }

    fun allSaved(): List<RemoteMedia> = entries.map { it.snapshot() }

    fun itemsIn(collectionId: String): List<RemoteMedia> =
        entries.filter { collectionId in it.collectionIds }.map { it.snapshot() }

    fun media(pk: String): RemoteMedia? = entries.firstOrNull { it.media.pk == pk }?.snapshot()

    /** Saves [count] new items on top. Returns them newest first. */
    fun addNewSaves(count: Int, collectionIds: Set<String> = emptySet()): List<RemoteMedia> {
        val added = List(count) { newEntry(collectionIds.toMutableSet()) }.asReversed()
        entries.addAll(0, added)
        return added.map { it.snapshot() }
    }

    fun unsave(pk: String) {
        entries.removeAll { it.media.pk == pk }
    }

    fun setCollections(pk: String, collectionIds: Set<String>) {
        val entry = entries.first { it.media.pk == pk }
        entry.collectionIds.clear()
        entry.collectionIds.addAll(collectionIds)
    }

    private fun Entry.snapshot() = media.copy(savedCollectionIds = collectionIds.sorted())

    private fun randomCollections(): MutableSet<String> {
        val roll = random.nextInt(100)
        val count = when {
            roll < 25 -> 0
            roll < 85 -> 1
            else -> 2
        }
        return collectionList.shuffled(random).take(count).map { it.id }.toMutableSet()
    }

    private fun newEntry(collectionIds: MutableSet<String>): Entry {
        val i = nextIndex++
        val type = when (random.nextInt(100)) {
            in 0..59 -> MediaType.REEL
            in 60..69 -> MediaType.VIDEO
            in 70..89 -> MediaType.IMAGE
            else -> MediaType.CAROUSEL
        }
        val pk = (1_000_000 + i).toString()
        val (width, height) = when (type) {
            MediaType.REEL, MediaType.VIDEO -> 1080 to 1920
            MediaType.IMAGE -> if (random.nextBoolean()) 1080 to 1080 else 1080 to 1350
            MediaType.CAROUSEL -> 1080 to 1350
        }
        val caption = if (random.nextInt(10) == 0) {
            null
        } else {
            List(3 + random.nextInt(8)) { WORDS[random.nextInt(WORDS.size)] }.joinToString(" ")
        }
        val missing = i % 97 == 96
        val media = RemoteMedia(
            pk = pk,
            code = "F" + i.toString(36).uppercase().padStart(6, '0'),
            type = type,
            author = "creator_${random.nextInt(200)}",
            caption = caption,
            takenAt = BASE_TIME.plusSeconds(i * 3_600L),
            width = width,
            height = height,
            carouselCount = if (type == MediaType.CAROUSEL) 2 + random.nextInt(9) else null,
            thumbnailUrl = if (missing) "fake://missing/$pk" else "fake://thumb/$pk",
            videoUrl = if (type == MediaType.REEL || type == MediaType.VIDEO) "fake://video/$pk" else null,
            videoUrlExpiresAt = null,
            savedCollectionIds = null,
        )
        return Entry(media, collectionIds)
    }

    private companion object {
        val NAMES = listOf("Workouts", "Recipes", "Travel", "Music", "Funny", "Tech", "Design", "Later")
        val WORDS = listOf(
            "morning", "routine", "pasta", "quick", "lisbon", "sunset", "guitar", "cover", "cat", "dog",
            "kotlin", "compose", "tips", "leg", "day", "mobility", "coffee", "street", "food", "minimal",
            "poster", "type", "beach", "train", "hack", "laugh", "drums", "bread", "hike", "city",
        )
        val BASE_TIME: Instant = Instant.parse("2024-01-01T00:00:00Z")
    }
}
```

`instagram/src/main/kotlin/io/github/yuriimurha/reels/instagram/fake/FakeInstagramClient.kt`:
```kotlin
package io.github.yuriimurha.reels.instagram.fake

import io.github.yuriimurha.reels.instagram.Account
import io.github.yuriimurha.reels.instagram.InstagramClient
import io.github.yuriimurha.reels.instagram.InstagramException
import io.github.yuriimurha.reels.instagram.Page
import io.github.yuriimurha.reels.instagram.RemoteCollection
import io.github.yuriimurha.reels.instagram.RemoteMedia

/** Decides whether call number [call] (1-based, counting every call) fails instead of answering. */
fun interface FakeFailures {
    fun failureFor(call: Int): InstagramException?
}

/** A fixture-backed [InstagramClient]. Never talks to the network. */
class FakeInstagramClient(
    val library: FakeLibrary = FakeLibrary(),
    private val pageSize: Int = 20,
    override val reportsSavedCollectionIds: Boolean = true,
    var failures: FakeFailures = FakeFailures { null },
) : InstagramClient {
    private val callLog = mutableListOf<String>()

    /** Every call made, in order, e.g. "currentUser", "collections:null", "saved:c3:o:40". */
    val calls: List<String> get() = callLog.toList()

    override suspend fun currentUser(): Account = answer("currentUser") { Account(pk = "1", username = "test_account") }

    override suspend fun collections(cursor: String?): Page<RemoteCollection> =
        answer("collections:$cursor") { library.collections.page(cursor) }

    override suspend fun savedMedia(collectionId: String?, cursor: String?): Page<RemoteMedia> =
        answer("saved:${collectionId ?: "all"}:$cursor") {
            val items = if (collectionId == null) library.allSaved() else library.itemsIn(collectionId)
            val page = items.page(cursor)
            if (reportsSavedCollectionIds) page else page.copy(items = page.items.map { it.copy(savedCollectionIds = null) })
        }

    override suspend fun mediaInfo(mediaPk: String): RemoteMedia =
        answer("mediaInfo:$mediaPk") { library.media(mediaPk) ?: throw InstagramException.ShapeChanged("items[0]") }

    private inline fun <T> answer(call: String, block: () -> T): T {
        callLog += call
        failures.failureFor(callLog.size)?.let { throw it }
        return block()
    }

    private fun <T> List<T>.page(cursor: String?): Page<T> {
        val start = (cursor?.removePrefix("o:")?.toInt() ?: 0).coerceAtMost(size)
        val end = minOf(start + pageSize, size)
        return Page(subList(start, end).toList(), if (end < size) "o:$end" else null)
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :instagram:test`
Expected: PASS (12 tests).

- [ ] **Step 6: Record the component**

In `ARCHITECTURE.md`, change the `:instagram` row to:
```markdown
| `:instagram` | `instagram/` | Pure Kotlin/JVM. Adapter contract (`InstagramClient`, `SessionProbe`, typed `InstagramException`s), `Permalinks`, and `FakeInstagramClient` over a deterministic `FakeLibrary` with scripted failures. |
```

- [ ] **Step 7: Commit**

```bash
git add instagram/src ARCHITECTURE.md
git commit -m "feat(instagram): adapter contract and deterministic fake client" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 4: Room schema and DAOs

**Files:**
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/data/db/Entities.kt`
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/data/db/MediaDao.kt`
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/data/db/CollectionDao.kt`
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/data/db/SyncDao.kt`
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/data/db/ApiRequestDao.kt`
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/data/db/ReelsDatabase.kt`
- Create (generated by the build): `app/schemas/io.github.yuriimurha.reels.data.db.ReelsDatabase/1.json`
- Test: `app/src/test/kotlin/io/github/yuriimurha/reels/testutil/TestDb.kt`
- Test: `app/src/test/kotlin/io/github/yuriimurha/reels/data/db/DaoTest.kt`
- Modify: `ARCHITECTURE.md`

**Interfaces:**
- Consumes: `MediaType` (Task 3).
- Produces (package `io.github.yuriimurha.reels.data.db`):
  - `const val ALL_SAVED_ID = "__all__"`
  - Entities `MediaEntity`, `MediaFts`, `CollectionEntity`, `CollectionMediaEntity`, `SyncRunEntity`, `SyncCursorEntity`, `ApiRequestEntity`; enums `SyncMode { QUICK, FULL }` and `SyncStatus` (with `isResumable`)
  - `data class CollectionCard(val id: String, val name: String, val count: Int, val coverThumbPath: String?)`
  - `MediaDao`: `byPks`, `upsert`, `setThumbPath`, `markRemoved`, `refreshCollectionNames`, `pageCollection(collectionId)`, `pageUncategorized()`, `pageSearch(match, types: List<String>, scope)`, `uncategorizedCount()`, `uncategorizedCover()`, `deleteAll()`
  - `CollectionDao`: `upsert`, `liveCollections()`, `markRemovedExcept`, `memberships`, `upsertMemberships`, `maxSortKey`, `deleteRealMembershipsExcept`, `unseenPks`, `deleteUnseen`, `deleteAllMembershipsOf`, `cards()`, `collectionsOf(pk)`, `deleteAll()`, `deleteAllMemberships()`
  - `SyncDao`: `insertRun`, `updateRun`, `run(id)`, `latestRun()`, `latestRunFlow()`, `lastSyncAt()`, `lastFullSyncAt()`, `pauseRunningRuns(reason)`, `cursor(runId, scope)`, `upsertCursor`, `deleteAllRuns()`, `deleteAllCursors()`
  - `ApiRequestDao`: `insert`, `countSince`, `oldestSince`, `deleteUpTo`
  - `ReelsDatabase` with the four DAOs, `deleteLibrary()`, and `ReelsDatabase.build(context)`
  - Test helpers `inMemoryDb()`, `mediaEntity(...)`, and `PagingSource.loadAll()`

- [ ] **Step 1: Write the test helpers**

`app/src/test/kotlin/io/github/yuriimurha/reels/testutil/TestDb.kt`:
```kotlin
package io.github.yuriimurha.reels.testutil

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.yuriimurha.reels.data.db.MediaEntity
import io.github.yuriimurha.reels.data.db.ReelsDatabase
import io.github.yuriimurha.reels.instagram.MediaType

fun inMemoryDb(): ReelsDatabase =
    Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ReelsDatabase::class.java)
        .allowMainThreadQueries()
        .build()

fun mediaEntity(
    pk: String,
    type: MediaType = MediaType.REEL,
    author: String = "author_$pk",
    caption: String? = "caption $pk",
    thumbPath: String? = "/thumbs/$pk.jpg",
    removedAt: Long? = null,
) = MediaEntity(
    pk = pk, code = "C$pk", type = type, author = author, caption = caption, takenAt = 0,
    width = 1080, height = 1920, carouselCount = null, thumbPath = thumbPath, thumbUrl = "fake://thumb/$pk",
    videoUrl = null, videoUrlExpiresAt = null, collectionNames = "", firstSeenAt = 0, lastSeenAt = 0,
    removedAt = removedAt,
)

suspend fun <T : Any> PagingSource<Int, T>.loadAll(): List<T> {
    val result = load(PagingSource.LoadParams.Refresh(key = null, loadSize = 10_000, placeholdersEnabled = false))
    return (result as PagingSource.LoadResult.Page).data
}
```

- [ ] **Step 2: Write the failing DAO tests**

`app/src/test/kotlin/io/github/yuriimurha/reels/data/db/DaoTest.kt`:
```kotlin
package io.github.yuriimurha.reels.data.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.testutil.inMemoryDb
import io.github.yuriimurha.reels.testutil.loadAll
import io.github.yuriimurha.reels.testutil.mediaEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
class DaoTest {
    private val db = inMemoryDb()
    private val media = db.mediaDao()
    private val collections = db.collectionDao()
    private val sync = db.syncDao()

    @After
    fun close() = db.close()

    private suspend fun givenLibrary() {
        collections.upsert(
            listOf(
                CollectionEntity(ALL_SAVED_ID, "All Saved", coverPk = null, position = -1),
                CollectionEntity("c1", "Workouts", coverPk = null, position = 0),
                CollectionEntity("c2", "Recipes", coverPk = "m1", position = 1),
            ),
        )
        media.upsert(listOf(mediaEntity("m1"), mediaEntity("m2"), mediaEntity("m3"), mediaEntity("m4", removedAt = 5)))
        collections.upsertMemberships(
            listOf(
                CollectionMediaEntity(ALL_SAVED_ID, "m1", sortKey = 10, lastSeenRunId = 1),
                CollectionMediaEntity(ALL_SAVED_ID, "m2", sortKey = 30, lastSeenRunId = 1),
                CollectionMediaEntity(ALL_SAVED_ID, "m3", sortKey = 20, lastSeenRunId = 1),
                CollectionMediaEntity(ALL_SAVED_ID, "m4", sortKey = 40, lastSeenRunId = 1),
                CollectionMediaEntity("c1", "m2", sortKey = 30, lastSeenRunId = 1),
                CollectionMediaEntity("c2", "m1", sortKey = 10, lastSeenRunId = 1),
            ),
        )
    }

    @Test
    fun collectionPagesAreNewestSavedFirstAndHideRemoved() = runTest {
        givenLibrary()
        assertEquals(listOf("m2", "m3", "m1"), media.pageCollection(ALL_SAVED_ID).loadAll().map { it.pk })
    }

    @Test
    fun uncategorizedIgnoresMembershipInRemovedCollections() = runTest {
        givenLibrary()
        assertEquals(listOf("m3"), media.pageUncategorized().loadAll().map { it.pk })
        collections.markRemovedExcept(keep = listOf("c1"), at = 99)
        assertEquals(listOf("m3", "m1"), media.pageUncategorized().loadAll().map { it.pk })
        assertEquals(2, media.uncategorizedCount().first())
    }

    @Test
    fun cardsPutAllSavedFirstAndFallBackToNewestCover() = runTest {
        givenLibrary()
        val cards = collections.cards().first()
        assertEquals(listOf(ALL_SAVED_ID, "c1", "c2"), cards.map { it.id })
        assertEquals(listOf(3, 1, 1), cards.map { it.count })
        assertEquals("/thumbs/m2.jpg", cards[0].coverThumbPath, "All Saved falls back to its newest live item")
        assertEquals("/thumbs/m1.jpg", cards[2].coverThumbPath, "explicit cover wins")
    }

    @Test
    fun refreshCollectionNamesJoinsLiveRealCollections() = runTest {
        givenLibrary()
        collections.upsertMemberships(listOf(CollectionMediaEntity("c2", "m2", sortKey = 30, lastSeenRunId = 1)))
        media.refreshCollectionNames()
        assertEquals("Recipes Workouts", media.byPks(listOf("m2")).single().collectionNames)
        assertEquals("", media.byPks(listOf("m3")).single().collectionNames)
    }

    @Test
    fun unseenMembershipsAreFoundAndDeletedPerScope() = runTest {
        givenLibrary()
        collections.upsertMemberships(listOf(CollectionMediaEntity(ALL_SAVED_ID, "m1", sortKey = 10, lastSeenRunId = 2)))
        assertEquals(setOf("m2", "m3", "m4"), collections.unseenPks(ALL_SAVED_ID, runId = 2).toSet())
        collections.deleteUnseen("c1", runId = 2)
        assertEquals(emptyList(), media.pageCollection("c1").loadAll())
    }

    @Test
    fun deleteLibraryKeepsTheRequestLog() = runTest {
        givenLibrary()
        db.apiRequestDao().insert(ApiRequestEntity(at = 1_000))
        sync.insertRun(SyncRunEntity(mode = SyncMode.QUICK, status = SyncStatus.DONE, startedAt = 0))
        db.deleteLibrary()
        assertEquals(emptyList(), collections.cards().first())
        assertNull(sync.latestRun())
        assertEquals(1, db.apiRequestDao().countSince(0), "the rolling 24 h budget must survive a library wipe")
    }

    @Test
    fun pauseRunningRunsOnlyTouchesRunning() = runTest {
        val running = sync.insertRun(SyncRunEntity(mode = SyncMode.FULL, status = SyncStatus.RUNNING, startedAt = 0))
        val done = sync.insertRun(SyncRunEntity(mode = SyncMode.QUICK, status = SyncStatus.DONE, startedAt = 0))
        sync.pauseRunningRuns("Interrupted")
        assertEquals(SyncStatus.PAUSED, sync.run(running)!!.status)
        assertEquals("Interrupted", sync.run(running)!!.lastError)
        assertEquals(SyncStatus.DONE, sync.run(done)!!.status)
    }

    @Test
    fun resumableStatuses() {
        val resumable = SyncStatus.entries.filter { it.isResumable }.toSet()
        assertEquals(
            setOf(
                SyncStatus.PAUSED, SyncStatus.STOPPED_CHALLENGE, SyncStatus.STOPPED_LOGIN,
                SyncStatus.STOPPED_RATE_LIMIT, SyncStatus.STOPPED_SHAPE,
            ),
            resumable,
        )
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.yuriimurha.reels.data.db.DaoTest"`
Expected: FAIL to compile (`ReelsDatabase` and the entities don't exist).

- [ ] **Step 4: Write the entities**

`app/src/main/kotlin/io/github/yuriimurha/reels/data/db/Entities.kt`:
```kotlin
package io.github.yuriimurha.reels.data.db

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.yuriimurha.reels.instagram.MediaType

/** The pseudo-collection holding every saved item ("All Saved"). */
const val ALL_SAVED_ID = "__all__"

@Entity(tableName = "media", indices = [Index(value = ["code"], unique = true)])
data class MediaEntity(
    @PrimaryKey val pk: String,
    val code: String,
    val type: MediaType,
    val author: String,
    val caption: String?,
    val takenAt: Long,
    val width: Int,
    val height: Int,
    val carouselCount: Int?,
    val thumbPath: String?,
    val thumbUrl: String,
    val videoUrl: String?,
    val videoUrlExpiresAt: Long?,
    /** Space-joined names of the item's live real collections; only for search. */
    val collectionNames: String,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val removedAt: Long?,
)

@Fts4(contentEntity = MediaEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "media_fts")
data class MediaFts(val caption: String?, val author: String, val collectionNames: String)

@Entity(tableName = "collection")
data class CollectionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val coverPk: String?,
    val position: Int,
    val removedAt: Long? = null,
)

@Entity(
    tableName = "collection_media",
    primaryKeys = ["collectionId", "mediaPk"],
    indices = [Index(value = ["collectionId", "sortKey"]), Index(value = ["mediaPk"])],
)
data class CollectionMediaEntity(
    val collectionId: String,
    val mediaPk: String,
    val sortKey: Long,
    val lastSeenRunId: Long,
)

enum class SyncMode { QUICK, FULL }

enum class SyncStatus {
    RUNNING, PAUSED, DONE, CANCELLED, STOPPED_CHALLENGE, STOPPED_LOGIN, STOPPED_RATE_LIMIT, STOPPED_SHAPE;

    /** A run that ended unfinished; the next Sync tap resumes it from its cursors (spec 7.1). */
    val isResumable: Boolean get() = this == PAUSED || name.startsWith("STOPPED_")
}

@Entity(tableName = "sync_run")
data class SyncRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mode: SyncMode,
    val status: SyncStatus,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val phase: String = "",
    val collectionsDone: Int = 0,
    val collectionsTotal: Int = 0,
    val requestsUsed: Int = 0,
    val newItems: Int = 0,
    val seenItems: Int = 0,
    val thumbsCached: Int = 0,
    val failures: Int = 0,
    /** Already-redacted text shown to the owner. */
    val lastError: String? = null,
)

@Entity(tableName = "sync_cursor", primaryKeys = ["runId", "scope"])
data class SyncCursorEntity(
    val runId: Long,
    val scope: String,
    val nextCursor: String?,
    val walkBase: Long,
    val walkIndex: Long,
    val done: Boolean,
)

/** One row per Instagram API request; backs the rolling 24 h budget. */
@Entity(tableName = "api_request")
data class ApiRequestEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val at: Long)

data class CollectionCard(val id: String, val name: String, val count: Int, val coverThumbPath: String?)
```

- [ ] **Step 5: Write the DAOs and database**

`app/src/main/kotlin/io/github/yuriimurha/reels/data/db/MediaDao.kt`:
```kotlin
package io.github.yuriimurha.reels.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {
    @Query("SELECT * FROM media WHERE pk IN (:pks)")
    suspend fun byPks(pks: List<String>): List<MediaEntity>

    @Upsert
    suspend fun upsert(media: List<MediaEntity>)

    @Query("UPDATE media SET thumbPath = :path WHERE pk = :pk")
    suspend fun setThumbPath(pk: String, path: String?)

    @Query("UPDATE media SET removedAt = :at, thumbPath = NULL WHERE pk IN (:pks)")
    suspend fun markRemoved(pks: List<String>, at: Long)

    @Query(
        """
        UPDATE media SET collectionNames = COALESCE((
            SELECT GROUP_CONCAT(name, ' ') FROM (
                SELECT c.name AS name FROM collection_media cm JOIN collection c ON c.id = cm.collectionId
                WHERE cm.mediaPk = media.pk AND cm.collectionId != '__all__' AND c.removedAt IS NULL
                ORDER BY c.name)), '')
        WHERE collectionNames != COALESCE((
            SELECT GROUP_CONCAT(name, ' ') FROM (
                SELECT c.name AS name FROM collection_media cm JOIN collection c ON c.id = cm.collectionId
                WHERE cm.mediaPk = media.pk AND cm.collectionId != '__all__' AND c.removedAt IS NULL
                ORDER BY c.name)), '')
        """,
    )
    suspend fun refreshCollectionNames()

    @Query(
        """
        SELECT m.* FROM media m JOIN collection_media cm ON cm.mediaPk = m.pk
        WHERE cm.collectionId = :collectionId AND m.removedAt IS NULL
        ORDER BY cm.sortKey DESC
        """,
    )
    fun pageCollection(collectionId: String): PagingSource<Int, MediaEntity>

    @Query(
        """
        SELECT m.* FROM media m JOIN collection_media cm ON cm.mediaPk = m.pk AND cm.collectionId = '__all__'
        WHERE m.removedAt IS NULL AND NOT EXISTS (
            SELECT 1 FROM collection_media x JOIN collection c ON c.id = x.collectionId
            WHERE x.mediaPk = m.pk AND x.collectionId != '__all__' AND c.removedAt IS NULL)
        ORDER BY cm.sortKey DESC
        """,
    )
    fun pageUncategorized(): PagingSource<Int, MediaEntity>

    @Query(
        """
        SELECT COUNT(*) FROM media m JOIN collection_media cm ON cm.mediaPk = m.pk AND cm.collectionId = '__all__'
        WHERE m.removedAt IS NULL AND NOT EXISTS (
            SELECT 1 FROM collection_media x JOIN collection c ON c.id = x.collectionId
            WHERE x.mediaPk = m.pk AND x.collectionId != '__all__' AND c.removedAt IS NULL)
        """,
    )
    fun uncategorizedCount(): Flow<Int>

    @Query(
        """
        SELECT m.thumbPath FROM media m JOIN collection_media cm ON cm.mediaPk = m.pk AND cm.collectionId = '__all__'
        WHERE m.removedAt IS NULL AND NOT EXISTS (
            SELECT 1 FROM collection_media x JOIN collection c ON c.id = x.collectionId
            WHERE x.mediaPk = m.pk AND x.collectionId != '__all__' AND c.removedAt IS NULL)
        ORDER BY cm.sortKey DESC LIMIT 1
        """,
    )
    fun uncategorizedCover(): Flow<String?>

    /** [match] must come from FtsQuery.from; [types] are MediaType names. */
    @Query(
        """
        SELECT m.* FROM media m
        JOIN media_fts ON media_fts.rowid = m.rowid
        JOIN collection_media cm ON cm.mediaPk = m.pk AND cm.collectionId = :scope
        WHERE media_fts MATCH :match AND m.removedAt IS NULL AND m.type IN (:types)
        ORDER BY cm.sortKey DESC
        """,
    )
    fun pageSearch(match: String, types: List<String>, scope: String): PagingSource<Int, MediaEntity>

    @Query("DELETE FROM media")
    suspend fun deleteAll()
}
```

`app/src/main/kotlin/io/github/yuriimurha/reels/data/db/CollectionDao.kt`:
```kotlin
package io.github.yuriimurha.reels.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CollectionDao {
    @Upsert
    suspend fun upsert(collections: List<CollectionEntity>)

    @Query("SELECT * FROM collection WHERE removedAt IS NULL AND id != '__all__' ORDER BY position")
    fun liveCollections(): Flow<List<CollectionEntity>>

    @Query("UPDATE collection SET removedAt = :at WHERE id NOT IN (:keep) AND id != '__all__' AND removedAt IS NULL")
    suspend fun markRemovedExcept(keep: List<String>, at: Long)

    @Query("SELECT * FROM collection_media WHERE collectionId = :collectionId AND mediaPk IN (:pks)")
    suspend fun memberships(collectionId: String, pks: List<String>): List<CollectionMediaEntity>

    @Upsert
    suspend fun upsertMemberships(memberships: List<CollectionMediaEntity>)

    @Query("SELECT MAX(sortKey) FROM collection_media WHERE collectionId = :collectionId")
    suspend fun maxSortKey(collectionId: String): Long?

    @Query("DELETE FROM collection_media WHERE mediaPk = :pk AND collectionId != '__all__' AND collectionId NOT IN (:keep)")
    suspend fun deleteRealMembershipsExcept(pk: String, keep: List<String>)

    @Query("SELECT mediaPk FROM collection_media WHERE collectionId = :collectionId AND lastSeenRunId != :runId")
    suspend fun unseenPks(collectionId: String, runId: Long): List<String>

    @Query("DELETE FROM collection_media WHERE collectionId = :collectionId AND lastSeenRunId != :runId")
    suspend fun deleteUnseen(collectionId: String, runId: Long)

    @Query("DELETE FROM collection_media WHERE mediaPk IN (:pks)")
    suspend fun deleteAllMembershipsOf(pks: List<String>)

    @Query(
        """
        SELECT c.id AS id, c.name AS name,
            (SELECT COUNT(*) FROM collection_media cm JOIN media m ON m.pk = cm.mediaPk
             WHERE cm.collectionId = c.id AND m.removedAt IS NULL) AS count,
            COALESCE(
                (SELECT m.thumbPath FROM media m WHERE m.pk = c.coverPk AND m.removedAt IS NULL),
                (SELECT m.thumbPath FROM collection_media cm JOIN media m ON m.pk = cm.mediaPk
                 WHERE cm.collectionId = c.id AND m.removedAt IS NULL
                 ORDER BY cm.sortKey DESC LIMIT 1)) AS coverThumbPath
        FROM collection c WHERE c.removedAt IS NULL
        ORDER BY CASE WHEN c.id = '__all__' THEN 0 ELSE 1 END, c.position
        """,
    )
    fun cards(): Flow<List<CollectionCard>>

    @Query(
        """
        SELECT c.* FROM collection c JOIN collection_media cm ON cm.collectionId = c.id
        WHERE cm.mediaPk = :pk AND c.id != '__all__' AND c.removedAt IS NULL ORDER BY c.position
        """,
    )
    suspend fun collectionsOf(pk: String): List<CollectionEntity>

    @Query("DELETE FROM collection")
    suspend fun deleteAll()

    @Query("DELETE FROM collection_media")
    suspend fun deleteAllMemberships()
}
```

`app/src/main/kotlin/io/github/yuriimurha/reels/data/db/SyncDao.kt`:
```kotlin
package io.github.yuriimurha.reels.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncDao {
    @Insert
    suspend fun insertRun(run: SyncRunEntity): Long

    @Update
    suspend fun updateRun(run: SyncRunEntity)

    @Query("SELECT * FROM sync_run WHERE id = :id")
    suspend fun run(id: Long): SyncRunEntity?

    @Query("SELECT * FROM sync_run ORDER BY id DESC LIMIT 1")
    suspend fun latestRun(): SyncRunEntity?

    @Query("SELECT * FROM sync_run ORDER BY id DESC LIMIT 1")
    fun latestRunFlow(): Flow<SyncRunEntity?>

    @Query("SELECT MAX(finishedAt) FROM sync_run WHERE status = 'DONE'")
    fun lastSyncAt(): Flow<Long?>

    @Query("SELECT MAX(finishedAt) FROM sync_run WHERE status = 'DONE' AND mode = 'FULL'")
    fun lastFullSyncAt(): Flow<Long?>

    @Query("UPDATE sync_run SET status = 'PAUSED', lastError = :reason WHERE status = 'RUNNING'")
    suspend fun pauseRunningRuns(reason: String)

    @Query("SELECT * FROM sync_cursor WHERE runId = :runId AND scope = :scope")
    suspend fun cursor(runId: Long, scope: String): SyncCursorEntity?

    @Upsert
    suspend fun upsertCursor(cursor: SyncCursorEntity)

    @Query("DELETE FROM sync_run")
    suspend fun deleteAllRuns()

    @Query("DELETE FROM sync_cursor")
    suspend fun deleteAllCursors()
}
```

`app/src/main/kotlin/io/github/yuriimurha/reels/data/db/ApiRequestDao.kt`:
```kotlin
package io.github.yuriimurha.reels.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ApiRequestDao {
    @Insert
    suspend fun insert(request: ApiRequestEntity)

    @Query("SELECT COUNT(*) FROM api_request WHERE at > :since")
    suspend fun countSince(since: Long): Int

    @Query("SELECT MIN(at) FROM api_request WHERE at > :since")
    suspend fun oldestSince(since: Long): Long?

    @Query("DELETE FROM api_request WHERE at <= :before")
    suspend fun deleteUpTo(before: Long)
}
```

`app/src/main/kotlin/io/github/yuriimurha/reels/data/db/ReelsDatabase.kt`:
```kotlin
package io.github.yuriimurha.reels.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction

@Database(
    entities = [
        MediaEntity::class, MediaFts::class, CollectionEntity::class, CollectionMediaEntity::class,
        SyncRunEntity::class, SyncCursorEntity::class, ApiRequestEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class ReelsDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao
    abstract fun collectionDao(): CollectionDao
    abstract fun syncDao(): SyncDao
    abstract fun apiRequestDao(): ApiRequestDao

    /** Wipes the library and sync history but keeps `api_request`, so the rolling budget survives (spec 7.3). */
    suspend fun deleteLibrary() = withTransaction {
        collectionDao().deleteAllMemberships()
        collectionDao().deleteAll()
        mediaDao().deleteAll()
        syncDao().deleteAllCursors()
        syncDao().deleteAllRuns()
    }

    companion object {
        /** No destructive fallback: a lost library costs a full, paced re-sync (spec 5.4). */
        fun build(context: Context): ReelsDatabase =
            Room.databaseBuilder(context, ReelsDatabase::class.java, "reels.db").build()
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.yuriimurha.reels.data.db.DaoTest"`
Expected: PASS (8 tests), and `app/schemas/io.github.yuriimurha.reels.data.db.ReelsDatabase/1.json` now exists.

- [ ] **Step 7: Record the component**

Append to the `## Components` table in `ARCHITECTURE.md`:
```markdown
| Database | `app/.../data/db/` | Room v1: `media` (+ FTS4 `media_fts`, unicode61), `collection` (with the `__all__` pseudo-collection), `collection_media` (`sortKey`), `sync_run`, `sync_cursor`, `api_request`. Schema exported to `app/schemas/`. `deleteLibrary()` keeps `api_request`. |
```

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/io/github/yuriimurha/reels/data/db app/src/test/kotlin/io/github/yuriimurha/reels/testutil app/src/test/kotlin/io/github/yuriimurha/reels/data/db app/schemas ARCHITECTURE.md
git commit -m "feat(db): Room schema with collections, ordering, FTS and sync state" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 5: Library repository, search query and media sources

**Files:**
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/data/library/FtsQuery.kt`
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/data/library/MediaSource.kt`
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/data/library/LibraryRepository.kt`
- Test: `app/src/test/kotlin/io/github/yuriimurha/reels/data/library/FtsQueryTest.kt`
- Test: `app/src/test/kotlin/io/github/yuriimurha/reels/data/library/LibraryRepositoryTest.kt`
- Modify: `ARCHITECTURE.md`

**Interfaces:**
- Consumes: `ReelsDatabase`, `MediaDao`, `CollectionDao`, `CollectionCard`, `ALL_SAVED_ID` (Task 4); `MediaType` (Task 3).
- Produces (package `io.github.yuriimurha.reels.data.library`):
  - `object FtsQuery { fun from(input: String): String? }`
  - `enum class TypeFilter(val types: List<MediaType>) { ALL, REELS, POSTS }`
  - `@Serializable sealed interface MediaSource` with `Collection(id)`, `Uncategorized`, `Search(match, filter, scope)`, `fun encode(): String`, `MediaSource.decode(encoded)`
  - `const val UNCATEGORIZED_ID = "uncategorized"`
  - `class LibraryRepository(db: ReelsDatabase)`: `collectionCards(): Flow<List<CollectionCard>>`, `pagingSource(source)`, `pager(source, initialIndex = 0): Flow<PagingData<MediaEntity>>`, `liveCollections()`, `collectionsOf(pk): List<CollectionEntity>`

- [ ] **Step 1: Write the failing tests**

`app/src/test/kotlin/io/github/yuriimurha/reels/data/library/FtsQueryTest.kt`:
```kotlin
package io.github.yuriimurha.reels.data.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FtsQueryTest {
    @Test
    fun wordsBecomePrefixTerms() = assertEquals("leg* day*", FtsQuery.from("Leg day"))

    @Test
    fun ftsOperatorsAreNeutralised() {
        assertEquals("or* cats*", FtsQuery.from("OR -cats"))
        assertEquals("hello* world*", FtsQuery.from("\"hello world\""))
        assertEquals("c*", FtsQuery.from("c++*"))
        assertEquals("near* not* and*", FtsQuery.from("NEAR NOT AND"))
    }

    @Test
    fun nonLatinLettersAreKept() = assertEquals("привет* café*", FtsQuery.from("Привет café"))

    @Test
    fun nothingSearchableGivesNull() {
        assertNull(FtsQuery.from(""))
        assertNull(FtsQuery.from("   "))
        assertNull(FtsQuery.from("\"'*-()"))
        assertNull(FtsQuery.from("🔥🔥"))
    }
}
```

`app/src/test/kotlin/io/github/yuriimurha/reels/data/library/LibraryRepositoryTest.kt`:
```kotlin
package io.github.yuriimurha.reels.data.library

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.data.db.ALL_SAVED_ID
import io.github.yuriimurha.reels.data.db.CollectionEntity
import io.github.yuriimurha.reels.data.db.CollectionMediaEntity
import io.github.yuriimurha.reels.instagram.MediaType
import io.github.yuriimurha.reels.testutil.inMemoryDb
import io.github.yuriimurha.reels.testutil.loadAll
import io.github.yuriimurha.reels.testutil.mediaEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class LibraryRepositoryTest {
    private val db = inMemoryDb()
    private val repository = LibraryRepository(db)

    @After
    fun close() = db.close()

    private suspend fun givenLibrary() {
        db.collectionDao().upsert(
            listOf(
                CollectionEntity(ALL_SAVED_ID, "All Saved", null, -1),
                CollectionEntity("c1", "Workouts", null, 0),
                CollectionEntity("c2", "Recipes", null, 1),
            ),
        )
        db.mediaDao().upsert(
            listOf(
                mediaEntity("m1", MediaType.REEL, author = "chef_anna", caption = "Quick pasta"),
                mediaEntity("m2", MediaType.IMAGE, author = "coach_bo", caption = "Leg day plan"),
                mediaEntity("m3", MediaType.CAROUSEL, author = "wanderer", caption = "Lisbon sunset"),
            ),
        )
        db.collectionDao().upsertMemberships(
            listOf(
                CollectionMediaEntity(ALL_SAVED_ID, "m1", 3, 1),
                CollectionMediaEntity(ALL_SAVED_ID, "m2", 2, 1),
                CollectionMediaEntity(ALL_SAVED_ID, "m3", 1, 1),
                CollectionMediaEntity("c2", "m1", 3, 1),
                CollectionMediaEntity("c1", "m2", 2, 1),
            ),
        )
        db.mediaDao().refreshCollectionNames()
    }

    private suspend fun search(text: String, filter: TypeFilter = TypeFilter.ALL, scope: String = ALL_SAVED_ID) =
        repository.pagingSource(MediaSource.Search(FtsQuery.from(text)!!, filter, scope)).loadAll().map { it.pk }

    @Test
    fun cardsListAllSavedThenUncategorizedThenCollections() = runTest {
        givenLibrary()
        val cards = repository.collectionCards().first()
        assertEquals(listOf(ALL_SAVED_ID, UNCATEGORIZED_ID, "c1", "c2"), cards.map { it.id })
        assertEquals(1, cards[1].count)
    }

    @Test
    fun noCardsBeforeTheFirstSync() = runTest {
        assertEquals(emptyList(), repository.collectionCards().first())
    }

    @Test
    fun searchMatchesCaptionAuthorAndCollectionName() = runTest {
        givenLibrary()
        assertEquals(listOf("m1"), search("pasta"))
        assertEquals(listOf("m2"), search("coach"))
        assertEquals(listOf("m1"), search("recipes"))
        assertEquals(listOf("m3"), search("lis"))
    }

    @Test
    fun searchHonoursTypeFilterAndScope() = runTest {
        givenLibrary()
        assertEquals(listOf("m2"), search("plan", TypeFilter.POSTS))
        assertEquals(listOf("m1"), search("pasta", TypeFilter.REELS))
        assertEquals(emptyList(), search("pasta", TypeFilter.POSTS))
        assertEquals(listOf("m1"), search("pasta", scope = "c2"))
        assertEquals(emptyList(), search("pasta", scope = "c1"))
    }

    @Test
    fun searchWithHostileInputDoesNotCrash() = runTest {
        givenLibrary()
        for (input in listOf("\"OR -pasta*", "NEAR(pasta)", "pasta AND", "'; DROP TABLE media; --")) {
            val match = FtsQuery.from(input) ?: continue
            repository.pagingSource(MediaSource.Search(match, TypeFilter.ALL, ALL_SAVED_ID)).loadAll()
        }
    }

    @Test
    fun uncategorizedSourceListsItemsWithoutCollections() = runTest {
        givenLibrary()
        assertEquals(listOf("m3"), repository.pagingSource(MediaSource.Uncategorized).loadAll().map { it.pk })
    }

    @Test
    fun mediaSourceSurvivesEncoding() {
        for (source in listOf(
            MediaSource.Collection("c1"),
            MediaSource.Uncategorized,
            MediaSource.Search("leg* day*", TypeFilter.REELS, "c2"),
        )) {
            assertEquals(source, MediaSource.decode(source.encode()))
        }
        assertTrue(MediaSource.Collection("c1").encode().isNotBlank())
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.yuriimurha.reels.data.library.*"`
Expected: FAIL to compile (`FtsQuery`, `LibraryRepository` don't exist).

- [ ] **Step 3: Implement**

`app/src/main/kotlin/io/github/yuriimurha/reels/data/library/FtsQuery.kt`:
```kotlin
package io.github.yuriimurha.reels.data.library

/** Turns free text into a safe FTS4 MATCH expression: every word becomes a lowercase prefix term, all must match. */
object FtsQuery {
    private val word = Regex("[\\p{L}\\p{N}]+")

    fun from(input: String): String? {
        val terms = word.findAll(input.lowercase()).map { it.value }.toList()
        if (terms.isEmpty()) return null
        // Lowercasing turns FTS operators (OR, AND, NOT, NEAR) into plain terms; quotes, '-', '*', '(' are dropped.
        return terms.joinToString(" ") { "$it*" }
    }
}
```

`app/src/main/kotlin/io/github/yuriimurha/reels/data/library/MediaSource.kt`:
```kotlin
package io.github.yuriimurha.reels.data.library

import io.github.yuriimurha.reels.instagram.MediaType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The card id Home uses for the computed "Uncategorized" view. */
const val UNCATEGORIZED_ID = "uncategorized"

enum class TypeFilter(val types: List<MediaType>) {
    ALL(MediaType.entries),
    REELS(listOf(MediaType.REEL, MediaType.VIDEO)),
    POSTS(listOf(MediaType.IMAGE, MediaType.CAROUSEL)),
}

/** What a grid or the viewer is showing. Encoded into navigation routes. */
@Serializable
sealed interface MediaSource {
    @Serializable
    @SerialName("collection")
    data class Collection(val id: String) : MediaSource

    @Serializable
    @SerialName("uncategorized")
    data object Uncategorized : MediaSource

    /** [match] is already sanitised by [FtsQuery.from]. [scope] is a collection id or the All Saved id. */
    @Serializable
    @SerialName("search")
    data class Search(val match: String, val filter: TypeFilter, val scope: String) : MediaSource

    fun encode(): String = Json.encodeToString(MediaSource.serializer(), this)

    companion object {
        fun decode(encoded: String): MediaSource = Json.decodeFromString(MediaSource.serializer(), encoded)
    }
}
```

`app/src/main/kotlin/io/github/yuriimurha/reels/data/library/LibraryRepository.kt`:
```kotlin
package io.github.yuriimurha.reels.data.library

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import io.github.yuriimurha.reels.data.db.ALL_SAVED_ID
import io.github.yuriimurha.reels.data.db.CollectionCard
import io.github.yuriimurha.reels.data.db.CollectionEntity
import io.github.yuriimurha.reels.data.db.MediaEntity
import io.github.yuriimurha.reels.data.db.ReelsDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** Read side of the library for the UI. */
class LibraryRepository(private val db: ReelsDatabase) {
    private val mediaDao = db.mediaDao()
    private val collectionDao = db.collectionDao()

    /** All Saved, then Uncategorized, then real collections; empty until the first sync creates All Saved. */
    fun collectionCards(): Flow<List<CollectionCard>> = combine(
        collectionDao.cards(),
        mediaDao.uncategorizedCount(),
        mediaDao.uncategorizedCover(),
    ) { cards, uncategorizedCount, uncategorizedCover ->
        val allSaved = cards.filter { it.id == ALL_SAVED_ID }
        if (allSaved.isEmpty()) {
            emptyList()
        } else {
            allSaved +
                CollectionCard(UNCATEGORIZED_ID, "Uncategorized", uncategorizedCount, uncategorizedCover) +
                cards.filter { it.id != ALL_SAVED_ID }
        }
    }

    fun pagingSource(source: MediaSource): PagingSource<Int, MediaEntity> = when (source) {
        is MediaSource.Collection -> mediaDao.pageCollection(source.id)
        MediaSource.Uncategorized -> mediaDao.pageUncategorized()
        is MediaSource.Search -> mediaDao.pageSearch(source.match, source.filter.types.map { it.name }, source.scope)
    }

    /** Placeholders are on, so list positions are absolute and the viewer can open at [initialIndex]. */
    fun pager(source: MediaSource, initialIndex: Int = 0): Flow<PagingData<MediaEntity>> =
        Pager(
            config = PagingConfig(pageSize = 60, enablePlaceholders = true),
            initialKey = initialIndex,
            pagingSourceFactory = { pagingSource(source) },
        ).flow

    fun liveCollections(): Flow<List<CollectionEntity>> = collectionDao.liveCollections()

    suspend fun collectionsOf(pk: String): List<CollectionEntity> = collectionDao.collectionsOf(pk)
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.yuriimurha.reels.data.library.*"`
Expected: PASS (11 tests).

- [ ] **Step 5: Record the component**

Append to the `## Components` table in `ARCHITECTURE.md`:
```markdown
| Library | `app/.../data/library/` | `LibraryRepository` (home cards, Paging 3 per `MediaSource`), `FtsQuery` (sanitises search input into prefix terms), `MediaSource` (serialisable grid/viewer source). |
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/io/github/yuriimurha/reels/data/library app/src/test/kotlin/io/github/yuriimurha/reels/data/library ARCHITECTURE.md
git commit -m "feat(library): repository, safe FTS queries and media sources" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 6: Pacer

**Files:**
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/sync/pacing/PacingPolicy.kt`
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/sync/pacing/RequestLog.kt`
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/sync/pacing/CooldownStore.kt`
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/sync/pacing/PacerRefusal.kt`
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/sync/pacing/Pacer.kt`
- Test: `app/src/test/kotlin/io/github/yuriimurha/reels/sync/pacing/PacerTest.kt`
- Test: `app/src/test/kotlin/io/github/yuriimurha/reels/sync/pacing/CooldownsTest.kt`
- Modify: `ARCHITECTURE.md`

**Interfaces:**
- Consumes: `InstagramException.RateLimited` (Task 3).
- Produces (package `io.github.yuriimurha.reels.sync.pacing`):
  - `class PacingPolicy` (private constructor) with `Conservative` and `Fast`, its fields, and `sampleGap(random): Long`
  - `interface RequestLog { suspend fun record(at: Long); suspend fun countSince(since: Long): Int; suspend fun oldestSince(since: Long): Long? }` and `InMemoryRequestLog(initial: List<Long> = emptyList())`
  - `interface CooldownStore { suspend fun activeUntil(): Long?; suspend fun onRateLimited(now: Long): Long }`, `InMemoryCooldownStore()`, and `object Cooldowns { SHORT_MS, LONG_MS, WINDOW_MS, fun next(previousRateLimitAt: Long?, now: Long): Long }`
  - `sealed class PacerRefusal : Exception` with `CoolingDown(until)`, `RunBudgetReached()`, `DailyBudgetReached(freesAt)`
  - `class Pacer(policy, requestLog, cooldowns, random = Random.Default, now = System::currentTimeMillis)` with `newRun(): Pacer.RunBudget` (`used: Int`), `sync(run, request)`, `interactive(request)`, `cdn(download)`, `status(): PacerStatus`, and `Pacer.DAY_MS`
  - `data class PacerStatus(requestsLast24h: Int, dailyBudget: Int, perRunBudget: Int, cooldownUntil: Long?)`

- [ ] **Step 1: Write the failing tests**

`app/src/test/kotlin/io/github/yuriimurha/reels/sync/pacing/CooldownsTest.kt`:
```kotlin
package io.github.yuriimurha.reels.sync.pacing

import kotlin.test.Test
import kotlin.test.assertEquals

class CooldownsTest {
    @Test
    fun firstRateLimitCoolsDownForAnHour() =
        assertEquals(1_000 + Cooldowns.SHORT_MS, Cooldowns.next(previousRateLimitAt = null, now = 1_000))

    @Test
    fun secondWithin24HoursCoolsDownForADay() =
        assertEquals(5_000 + Cooldowns.LONG_MS, Cooldowns.next(previousRateLimitAt = 1_000, now = 5_000))

    @Test
    fun secondAfter24HoursIsShortAgain() {
        val now = 1_000 + Cooldowns.WINDOW_MS
        assertEquals(now + Cooldowns.SHORT_MS, Cooldowns.next(previousRateLimitAt = 1_000, now = now))
    }
}
```

`app/src/test/kotlin/io/github/yuriimurha/reels/sync/pacing/PacerTest.kt`:
```kotlin
package io.github.yuriimurha.reels.sync.pacing

import io.github.yuriimurha.reels.instagram.InstagramException
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PacerTest {
    private fun TestScope.pacer(
        log: RequestLog = InMemoryRequestLog(),
        cooldowns: CooldownStore = InMemoryCooldownStore(),
    ) = Pacer(PacingPolicy.Conservative, log, cooldowns, Random(1), now = { testScheduler.currentTime })

    @Test
    fun gapsStayInBoundsWithMedianNearSixSecondsAndRegularBreaks() = runTest {
        val pacer = pacer()
        val run = pacer.newRun()
        val starts = mutableListOf<Long>()
        repeat(200) { pacer.sync(run) { starts += testScheduler.currentTime } }

        val gaps = starts.zipWithNext { a, b -> b - a }
        val (breaks, normal) = gaps.partition { it > 59_000 }
        assertTrue(normal.all { it in 4_000..12_000 }, "gaps ${normal.minOrNull()}..${normal.maxOrNull()}")
        assertTrue(breaks.all { it in 64_000..192_000 }, "breaks $breaks")
        assertTrue(breaks.size in 6..14, "break count ${breaks.size}")
        val median = normal.sorted()[normal.size / 2]
        assertTrue(median in 5_000..7_000, "median $median")
    }

    @Test
    fun runBudgetStopsAt300Requests() = runTest {
        val log = InMemoryRequestLog()
        val pacer = pacer(log = log)
        val run = pacer.newRun()
        repeat(300) { pacer.sync(run) {} }
        assertFailsWith<PacerRefusal.RunBudgetReached> { pacer.sync(run) {} }
        assertEquals(300, run.used)
        assertEquals(300, log.countSince(-1))
    }

    @Test
    fun dailyBudgetSpansRunsAndFreesAfter24Hours() = runTest {
        val pacer = pacer(log = InMemoryRequestLog(List(600) { 0L }))
        val refusal = assertFailsWith<PacerRefusal.DailyBudgetReached> { pacer.sync(pacer.newRun()) {} }
        assertEquals(Pacer.DAY_MS, refusal.freesAt)
        advanceTimeBy(Pacer.DAY_MS + 1)
        pacer.sync(pacer.newRun()) {}
    }

    @Test
    fun interactiveRequestJumpsAheadOfQueuedSyncRequests() = runTest {
        val pacer = pacer()
        val run = pacer.newRun()
        val order = mutableListOf<String>()
        val syncJob = launch { repeat(3) { i -> pacer.sync(run) { order += "sync$i"; delay(1_000) } } }
        advanceTimeBy(500)
        val interactiveJob = launch { pacer.interactive { order += "interactive" } }
        joinAll(syncJob, interactiveJob)
        assertEquals(listOf("sync0", "interactive", "sync1", "sync2"), order)
    }

    @Test
    fun interactiveKeepsTwoSecondsFromThePreviousRequest() = runTest {
        val pacer = pacer()
        pacer.sync(pacer.newRun()) {}
        val previousEnd = testScheduler.currentTime
        var startedAt = -1L
        pacer.interactive { startedAt = testScheduler.currentTime }
        assertEquals(previousEnd + 2_000, startedAt)
    }

    @Test
    fun rateLimitStartsACooldownThatRefusesBothLanes() = runTest {
        val pacer = pacer()
        assertFailsWith<InstagramException.RateLimited> {
            pacer.sync(pacer.newRun()) { throw InstagramException.RateLimited() }
        }
        val refusal = assertFailsWith<PacerRefusal.CoolingDown> { pacer.sync(pacer.newRun()) {} }
        assertEquals(Cooldowns.SHORT_MS, refusal.until)
        assertFailsWith<PacerRefusal.CoolingDown> { pacer.interactive {} }
    }

    @Test
    fun failedRequestsStillCountAgainstTheBudget() = runTest {
        val log = InMemoryRequestLog()
        val pacer = pacer(log = log)
        assertFailsWith<InstagramException.Transient> {
            pacer.sync(pacer.newRun()) { throw InstagramException.Transient() }
        }
        assertEquals(1, log.countSince(-1))
    }

    @Test
    fun cdnLaneAllowsTwoAtATimeAndIsNotBudgeted() = runTest {
        val log = InMemoryRequestLog()
        val pacer = pacer(log = log)
        var active = 0
        var peak = 0
        List(6) {
            launch {
                pacer.cdn {
                    active++
                    peak = maxOf(peak, active)
                    delay(1_000)
                    active--
                }
            }
        }.joinAll()
        assertEquals(2, peak)
        assertEquals(0, log.countSince(-1))
    }

    @Test
    fun statusReportsUsageAndCooldown() = runTest {
        val pacer = pacer()
        val run = pacer.newRun()
        repeat(3) { pacer.sync(run) {} }
        val before = pacer.status()
        assertEquals(3, before.requestsLast24h)
        assertEquals(600, before.dailyBudget)
        assertEquals(300, before.perRunBudget)
        assertNull(before.cooldownUntil)
        assertFailsWith<InstagramException.RateLimited> { pacer.sync(run) { throw InstagramException.RateLimited() } }
        assertTrue(pacer.status().cooldownUntil!! > testScheduler.currentTime)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.yuriimurha.reels.sync.pacing.*"`
Expected: FAIL to compile (`Pacer`, `PacingPolicy` don't exist).

- [ ] **Step 3: Implement the policy and stores**

`app/src/main/kotlin/io/github/yuriimurha/reels/sync/pacing/PacingPolicy.kt`:
```kotlin
package io.github.yuriimurha.reels.sync.pacing

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/** Pacing values (spec 7.3). Only two instances exist; raising any value needs an explicit commit. */
class PacingPolicy private constructor(
    val minGapMs: Long,
    val medianGapMs: Long,
    val maxGapMs: Long,
    /** A break comes after a random number of sync requests in this range; null means never. */
    val breakEvery: IntRange?,
    val breakMs: LongRange,
    val perRunBudget: Int,
    val dailyBudget: Int,
    val interactiveMinGapMs: Long,
    val cdnConcurrency: Int,
    val cdnJitterMs: LongRange,
) {
    /** Next gap between two sync requests: log-normal around the median, clamped to [minGapMs, maxGapMs]. */
    fun sampleGap(random: Random): Long =
        (medianGapMs * exp(GAP_SIGMA * random.nextGaussian())).toLong().coerceIn(minGapMs, maxGapMs)

    companion object {
        private const val GAP_SIGMA = 0.45

        /** The only policy allowed for real Instagram traffic. */
        val Conservative = PacingPolicy(
            minGapMs = 4_000, medianGapMs = 6_000, maxGapMs = 12_000,
            breakEvery = 15..30, breakMs = 60_000L..180_000L,
            perRunBudget = 300, dailyBudget = 600,
            interactiveMinGapMs = 2_000,
            cdnConcurrency = 2, cdnJitterMs = 200L..800L,
        )

        /** Fake backend only. In src/main it is referenced from di/Backend.kt and nowhere else (FastPolicyGuardTest). */
        val Fast = PacingPolicy(
            minGapMs = 20, medianGapMs = 35, maxGapMs = 60,
            breakEvery = null, breakMs = 0L..0L,
            perRunBudget = 300, dailyBudget = 600,
            interactiveMinGapMs = 0,
            cdnConcurrency = 4, cdnJitterMs = 0L..0L,
        )
    }
}

/** Standard normal sample (Box–Muller). */
internal fun Random.nextGaussian(): Double {
    val u1 = nextDouble().coerceAtLeast(1e-12)
    val u2 = nextDouble()
    return sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
}
```

`app/src/main/kotlin/io/github/yuriimurha/reels/sync/pacing/RequestLog.kt`:
```kotlin
package io.github.yuriimurha.reels.sync.pacing

/** Timestamps of Instagram API requests, for the rolling 24 h budget. */
interface RequestLog {
    suspend fun record(at: Long)
    suspend fun countSince(since: Long): Int
    suspend fun oldestSince(since: Long): Long?
}

class InMemoryRequestLog(initial: List<Long> = emptyList()) : RequestLog {
    private val times = initial.toMutableList()

    override suspend fun record(at: Long) {
        times += at
    }

    override suspend fun countSince(since: Long): Int = times.count { it > since }

    override suspend fun oldestSince(since: Long): Long? = times.filter { it > since }.minOrNull()
}
```

`app/src/main/kotlin/io/github/yuriimurha/reels/sync/pacing/CooldownStore.kt`:
```kotlin
package io.github.yuriimurha.reels.sync.pacing

/** Persists the rate-limit cooldown (spec 7.4). */
interface CooldownStore {
    /** Epoch millis until which no Instagram request may be made, or null. */
    suspend fun activeUntil(): Long?

    /** Records a rate limit at [now] and returns the new cooldown end. */
    suspend fun onRateLimited(now: Long): Long
}

object Cooldowns {
    const val SHORT_MS = 3_600_000L
    const val LONG_MS = 86_400_000L
    const val WINDOW_MS = 86_400_000L

    /** 1 h, or 24 h when the previous rate limit was less than 24 h ago. */
    fun next(previousRateLimitAt: Long?, now: Long): Long {
        val repeated = previousRateLimitAt != null && now - previousRateLimitAt < WINDOW_MS
        return now + if (repeated) LONG_MS else SHORT_MS
    }
}

class InMemoryCooldownStore : CooldownStore {
    private var until: Long? = null
    private var lastRateLimitAt: Long? = null

    override suspend fun activeUntil(): Long? = until

    override suspend fun onRateLimited(now: Long): Long {
        val next = Cooldowns.next(lastRateLimitAt, now)
        until = next
        lastRateLimitAt = now
        return next
    }
}
```

`app/src/main/kotlin/io/github/yuriimurha/reels/sync/pacing/PacerRefusal.kt`:
```kotlin
package io.github.yuriimurha.reels.sync.pacing

/** The Pacer refused to make a request. No request was sent. */
sealed class PacerRefusal(message: String) : Exception(message) {
    class CoolingDown(val until: Long) : PacerRefusal("Cooling down after a rate limit")

    class RunBudgetReached : PacerRefusal("This run used its request budget")

    class DailyBudgetReached(val freesAt: Long) : PacerRefusal("The 24-hour request budget is used up")
}
```

- [ ] **Step 4: Implement the Pacer**

`app/src/main/kotlin/io/github/yuriimurha/reels/sync/pacing/Pacer.kt`:
```kotlin
package io.github.yuriimurha.reels.sync.pacing

import io.github.yuriimurha.reels.instagram.InstagramException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

data class PacerStatus(
    val requestsLast24h: Int,
    val dailyBudget: Int,
    val perRunBudget: Int,
    val cooldownUntil: Long?,
)

/**
 * The single gate for Instagram API requests (spec 7.3): one request at a time, randomised gaps,
 * breaks, per-run and rolling 24 h budgets, an interactive lane with priority, and a separate CDN lane.
 */
class Pacer(
    val policy: PacingPolicy,
    private val requestLog: RequestLog,
    private val cooldowns: CooldownStore,
    private val random: Random = Random.Default,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Counts one sync run's requests against [PacingPolicy.perRunBudget]. */
    class RunBudget internal constructor() {
        var used: Int = 0
            internal set
    }

    private val gate = Mutex()
    private val interactiveWaiting = AtomicInteger(0)
    private val cdnLane = Semaphore(policy.cdnConcurrency)
    private var lastRequestEndedAt: Long? = null
    private var syncRequestsUntilBreak = sampleBreakInterval()

    fun newRun(): RunBudget = RunBudget()

    /** A sync request: waits its gap (and any break), yields to waiting interactive requests. */
    suspend fun <T> sync(run: RunBudget, request: suspend () -> T): T {
        while (true) {
            ensureAllowed()
            if (run.used >= policy.perRunBudget) throw PacerRefusal.RunBudgetReached()
            gate.lock()
            if (interactiveWaiting.get() > 0) {
                gate.unlock()
                while (interactiveWaiting.get() > 0) delay(YIELD_MS)
                continue
            }
            try {
                waitSinceLastRequest(policy.sampleGap(random))
                takeBreakIfDue()
                ensureAllowed()
                run.used++
                return execute(request)
            } finally {
                gate.unlock()
            }
        }
    }

    /** A request the owner is waiting for (viewer link refresh, session check): short gap, goes first. */
    suspend fun <T> interactive(request: suspend () -> T): T {
        ensureAllowed()
        interactiveWaiting.incrementAndGet()
        var holding = false
        try {
            gate.lock()
            holding = true
            interactiveWaiting.decrementAndGet()
            waitSinceLastRequest(policy.interactiveMinGapMs)
            ensureAllowed()
            return execute(request)
        } finally {
            if (holding) gate.unlock() else interactiveWaiting.decrementAndGet()
        }
    }

    /** A CDN download (thumbnails, video). Limited concurrency with jitter; not an API request, not budgeted. */
    suspend fun <T> cdn(download: suspend () -> T): T = cdnLane.withPermit {
        val jitter = policy.cdnJitterMs
        if (jitter.last > 0) delay(random.nextLong(jitter.first, jitter.last + 1))
        download()
    }

    suspend fun status(): PacerStatus {
        val t = now()
        return PacerStatus(
            requestsLast24h = requestLog.countSince(t - DAY_MS),
            dailyBudget = policy.dailyBudget,
            perRunBudget = policy.perRunBudget,
            cooldownUntil = cooldowns.activeUntil()?.takeIf { it > t },
        )
    }

    private suspend fun ensureAllowed() {
        val t = now()
        cooldowns.activeUntil()?.let { until -> if (until > t) throw PacerRefusal.CoolingDown(until) }
        val since = t - DAY_MS
        if (requestLog.countSince(since) >= policy.dailyBudget) {
            throw PacerRefusal.DailyBudgetReached((requestLog.oldestSince(since) ?: t) + DAY_MS)
        }
    }

    private suspend fun waitSinceLastRequest(gapMs: Long) {
        val last = lastRequestEndedAt ?: return
        val remaining = last + gapMs - now()
        if (remaining > 0) delay(remaining)
    }

    private suspend fun takeBreakIfDue() {
        if (policy.breakEvery == null) return
        syncRequestsUntilBreak -= 1
        if (syncRequestsUntilBreak > 0) return
        delay(random.nextLong(policy.breakMs.first, policy.breakMs.last + 1))
        syncRequestsUntilBreak = sampleBreakInterval()
    }

    private fun sampleBreakInterval(): Int =
        policy.breakEvery?.let { random.nextInt(it.first, it.last + 1) } ?: Int.MAX_VALUE

    private suspend fun <T> execute(request: suspend () -> T): T {
        requestLog.record(now())
        try {
            return request()
        } catch (e: InstagramException.RateLimited) {
            cooldowns.onRateLimited(now())
            throw e
        } finally {
            lastRequestEndedAt = now()
        }
    }

    companion object {
        const val DAY_MS = 86_400_000L
        private const val YIELD_MS = 10L
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.yuriimurha.reels.sync.pacing.*"`
Expected: PASS (12 tests). If `gapsStayInBounds...` fails only on the break-count range, print `breaks.size` and check `takeBreakIfDue`. Don't widen the range: 200 requests with a break every 15–30 must give 6 to 14 breaks.

- [ ] **Step 6: Record the component**

Append to the `## Components` table in `ARCHITECTURE.md`:
```markdown
| Pacer | `app/.../sync/pacing/` | Single gate for Instagram API calls: one at a time, 4–12 s log-normal gaps, 60–180 s breaks every 15–30, 300/run and 600/24 h budgets, an interactive lane with priority, a 2-wide CDN lane, rate-limit cooldowns (1 h, then 24 h). Policies: `Conservative` (real) and `Fast` (fake only). |
```

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/io/github/yuriimurha/reels/sync/pacing app/src/test/kotlin/io/github/yuriimurha/reels/sync/pacing ARCHITECTURE.md
git commit -m "feat(sync): Pacer with gaps, breaks, budgets, lanes and cooldowns" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 7: Persistent budgets, cooldowns and transient retry

**Files:**
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/data/settings/SettingsStore.kt`
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/sync/pacing/DataStoreCooldownStore.kt`
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/sync/pacing/RoomRequestLog.kt`
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/sync/pacing/TransientRetry.kt`
- Test: `app/src/test/kotlin/io/github/yuriimurha/reels/sync/pacing/CooldownPersistenceTest.kt`
- Test: `app/src/test/kotlin/io/github/yuriimurha/reels/sync/pacing/RoomRequestLogTest.kt`
- Test: `app/src/test/kotlin/io/github/yuriimurha/reels/sync/pacing/TransientRetryTest.kt`
- Modify: `ARCHITECTURE.md`

**Interfaces:**
- Consumes: `CooldownStore`, `Cooldowns`, `RequestLog`, `Pacer.DAY_MS` (Task 6); `ApiRequestDao`, `ApiRequestEntity` (Task 4); `InstagramException.Transient` (Task 3).
- Produces:
  - `class SettingsStore(store: DataStore<Preferences>)` with `muted: Flow<Boolean>`, `setMuted(Boolean)`, `cooldown(): CooldownState`, `setCooldown(until, rateLimitAt)`, and `SettingsStore.create(context)`. Task 17 adds session keys.
  - `data class CooldownState(val until: Long?, val lastRateLimitAt: Long?)`
  - `class DataStoreCooldownStore(settings: SettingsStore) : CooldownStore`
  - `class RoomRequestLog(dao: ApiRequestDao) : RequestLog`
  - `suspend fun <T> retryTransient(random: Random = Random.Default, block: suspend () -> T): T`

- [ ] **Step 1: Write the failing tests**

`app/src/test/kotlin/io/github/yuriimurha/reels/sync/pacing/CooldownPersistenceTest.kt`:
```kotlin
package io.github.yuriimurha.reels.sync.pacing

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class CooldownPersistenceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun cooldownStore(file: File, scope: CoroutineScope) =
        DataStoreCooldownStore(SettingsStore(PreferenceDataStoreFactory.create(scope = scope) { file }))

    @Test
    fun cooldownSurvivesARestartAndEscalates() = runTest {
        val file = File(tmp.root, "settings.preferences_pb")

        val firstScope = CoroutineScope(Dispatchers.IO + Job())
        val until = cooldownStore(file, firstScope).onRateLimited(now = 1_000)
        assertEquals(1_000 + Cooldowns.SHORT_MS, until)
        firstScope.coroutineContext.job.cancelAndJoin()

        val secondScope = CoroutineScope(Dispatchers.IO + Job())
        val restarted = cooldownStore(file, secondScope)
        assertEquals(until, restarted.activeUntil())
        assertEquals(2_000 + Cooldowns.LONG_MS, restarted.onRateLimited(now = 2_000))
        secondScope.coroutineContext.job.cancelAndJoin()
    }
}
```

`app/src/test/kotlin/io/github/yuriimurha/reels/sync/pacing/RoomRequestLogTest.kt`:
```kotlin
package io.github.yuriimurha.reels.sync.pacing

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.testutil.inMemoryDb
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class RoomRequestLogTest {
    @Test
    fun countsTheLast24HoursAndPrunesOlderRows() = runTest {
        val db = inMemoryDb()
        val log = RoomRequestLog(db.apiRequestDao())
        log.record(at = 1_000)
        log.record(at = 1_000 + Pacer.DAY_MS)
        assertEquals(1, log.countSince(0), "the row at 1_000 is pruned once a day has passed")
        assertEquals(1_000 + Pacer.DAY_MS, log.oldestSince(0))
        db.close()
    }
}
```

`app/src/test/kotlin/io/github/yuriimurha/reels/sync/pacing/TransientRetryTest.kt`:
```kotlin
package io.github.yuriimurha.reels.sync.pacing

import io.github.yuriimurha.reels.instagram.InstagramException
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TransientRetryTest {
    @Test
    fun retriesWithGrowingWaitsThenSucceeds() = runTest {
        var attempts = 0
        val result = retryTransient(Random(1)) {
            attempts++
            if (attempts <= 4) throw InstagramException.Transient() else "ok"
        }
        assertEquals("ok", result)
        assertEquals(5, attempts)
        assertTrue(testScheduler.currentTime in 360_000L..540_000L, "waited ${testScheduler.currentTime} ms")
    }

    @Test
    fun givesUpAfterTheFifthAttempt() = runTest {
        var attempts = 0
        assertFailsWith<InstagramException.Transient> {
            retryTransient(Random(1)) { attempts++; throw InstagramException.Transient() }
        }
        assertEquals(5, attempts)
    }

    @Test
    fun otherFailuresAreNeverRetried() = runTest {
        var attempts = 0
        assertFailsWith<InstagramException.ChallengeRequired> {
            retryTransient { attempts++; throw InstagramException.ChallengeRequired(null) }
        }
        assertEquals(1, attempts)
        assertEquals(0L, testScheduler.currentTime)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.yuriimurha.reels.sync.pacing.*"`
Expected: FAIL to compile (`SettingsStore`, `DataStoreCooldownStore`, `RoomRequestLog`, `retryTransient` don't exist).

- [ ] **Step 3: Implement**

`app/src/main/kotlin/io/github/yuriimurha/reels/data/settings/SettingsStore.kt`:
```kotlin
package io.github.yuriimurha.reels.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class CooldownState(val until: Long?, val lastRateLimitAt: Long?)

/** Small app settings and state that must survive process death (spec 5.4). One instance per process. */
class SettingsStore(private val store: DataStore<Preferences>) {
    val muted: Flow<Boolean> = store.data.map { it[MUTED] ?: false }

    suspend fun setMuted(muted: Boolean) {
        store.edit { it[MUTED] = muted }
    }

    suspend fun cooldown(): CooldownState =
        store.data.first().let { CooldownState(until = it[COOLDOWN_UNTIL], lastRateLimitAt = it[LAST_RATE_LIMIT_AT]) }

    suspend fun setCooldown(until: Long, rateLimitAt: Long) {
        store.edit {
            it[COOLDOWN_UNTIL] = until
            it[LAST_RATE_LIMIT_AT] = rateLimitAt
        }
    }

    companion object {
        private val MUTED = booleanPreferencesKey("muted")
        private val COOLDOWN_UNTIL = longPreferencesKey("cooldown_until")
        private val LAST_RATE_LIMIT_AT = longPreferencesKey("last_rate_limit_at")

        fun create(context: Context): SettingsStore = SettingsStore(
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                produceFile = { context.preferencesDataStoreFile("settings") },
            ),
        )
    }
}
```

`app/src/main/kotlin/io/github/yuriimurha/reels/sync/pacing/DataStoreCooldownStore.kt`:
```kotlin
package io.github.yuriimurha.reels.sync.pacing

import io.github.yuriimurha.reels.data.settings.SettingsStore

/** Cooldown state in DataStore, so killing the app doesn't reset it (spec 7.4). */
class DataStoreCooldownStore(private val settings: SettingsStore) : CooldownStore {
    override suspend fun activeUntil(): Long? = settings.cooldown().until

    override suspend fun onRateLimited(now: Long): Long {
        val until = Cooldowns.next(settings.cooldown().lastRateLimitAt, now)
        settings.setCooldown(until = until, rateLimitAt = now)
        return until
    }
}
```

`app/src/main/kotlin/io/github/yuriimurha/reels/sync/pacing/RoomRequestLog.kt`:
```kotlin
package io.github.yuriimurha.reels.sync.pacing

import io.github.yuriimurha.reels.data.db.ApiRequestDao
import io.github.yuriimurha.reels.data.db.ApiRequestEntity

class RoomRequestLog(private val dao: ApiRequestDao) : RequestLog {
    override suspend fun record(at: Long) {
        dao.insert(ApiRequestEntity(at = at))
        dao.deleteUpTo(at - Pacer.DAY_MS)
    }

    override suspend fun countSince(since: Long): Int = dao.countSince(since)

    override suspend fun oldestSince(since: Long): Long? = dao.oldestSince(since)
}
```

`app/src/main/kotlin/io/github/yuriimurha/reels/sync/pacing/TransientRetry.kt`:
```kotlin
package io.github.yuriimurha.reels.sync.pacing

import io.github.yuriimurha.reels.instagram.InstagramException
import kotlinx.coroutines.delay
import kotlin.random.Random

private val BACKOFF_MS = longArrayOf(30_000, 60_000, 120_000, 240_000)

/**
 * Retries [block] after [InstagramException.Transient] with 30 s, 60 s, 120 s, 240 s waits (each ±20 %),
 * then rethrows (spec 6.4). Any other failure propagates immediately. Wrap each attempt in the Pacer so
 * retries count as requests.
 */
suspend fun <T> retryTransient(random: Random = Random.Default, block: suspend () -> T): T {
    for (base in BACKOFF_MS) {
        try {
            return block()
        } catch (e: InstagramException.Transient) {
            delay((base * (0.8 + 0.4 * random.nextDouble())).toLong())
        }
    }
    return block()
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.yuriimurha.reels.sync.pacing.*"`
Expected: PASS (17 tests: 12 from Task 6, plus 5).

- [ ] **Step 5: Record the component**

Append to the `## Components` table in `ARCHITECTURE.md`:
```markdown
| Settings | `app/.../data/settings/SettingsStore.kt` | DataStore preferences: mute and the persisted cooldown. Budgets persist in `api_request` (`RoomRequestLog`). `retryTransient` backs off 30/60/120/240 s ±20 %. |
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/io/github/yuriimurha/reels/data/settings app/src/main/kotlin/io/github/yuriimurha/reels/sync/pacing app/src/test/kotlin/io/github/yuriimurha/reels/sync/pacing ARCHITECTURE.md
git commit -m "feat(sync): persistent request log, cooldowns and transient backoff" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 8: Thumbnail store and fake media fetcher

**Files:**
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/data/media/MediaFetcher.kt`
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/data/media/FakeMediaFetcher.kt`
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/data/media/ThumbnailStore.kt`
- Test: `app/src/test/kotlin/io/github/yuriimurha/reels/data/media/ThumbnailStoreTest.kt`
- Test: `app/src/test/kotlin/io/github/yuriimurha/reels/data/media/FakeMediaFetcherTest.kt`
- Modify: `ARCHITECTURE.md`

**Interfaces:**
- Consumes: nothing new.
- Produces (package `io.github.yuriimurha.reels.data.media`):
  - `fun interface MediaFetcher { suspend fun fetch(url: String): ByteArray? }`: null means the item is unavailable; it throws `IOException` on network trouble
  - `class FakeMediaFetcher : MediaFetcher`, which draws placeholder JPEGs for `fake://thumb/…` and returns null for `fake://missing/…`
  - `class ThumbnailStore(dir: File)` with `write(pk, bytes): String` (absolute path), `delete(pk)`, `deleteAll()`

- [ ] **Step 1: Write the failing tests**

`app/src/test/kotlin/io/github/yuriimurha/reels/data/media/ThumbnailStoreTest.kt`:
```kotlin
package io.github.yuriimurha.reels.data.media

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThumbnailStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val dir by lazy { File(tmp.root, "thumbs") }
    private val store by lazy { ThumbnailStore(dir) }

    @Test
    fun writesIntoPlaceAndReturnsTheAbsolutePath() {
        val path = store.write("123", byteArrayOf(1, 2, 3))
        assertEquals(File(dir, "123.jpg").absolutePath, path)
        assertContentEquals(byteArrayOf(1, 2, 3), File(path).readBytes())
        assertFalse(File(dir, "123.jpg.tmp").exists())
    }

    @Test
    fun overwriteReplacesContent() {
        store.write("123", byteArrayOf(1))
        store.write("123", byteArrayOf(9, 9))
        assertContentEquals(byteArrayOf(9, 9), File(dir, "123.jpg").readBytes())
    }

    @Test
    fun deleteAndDeleteAll() {
        store.write("1", byteArrayOf(1))
        store.write("2", byteArrayOf(2))
        store.delete("1")
        assertFalse(File(dir, "1.jpg").exists())
        assertTrue(File(dir, "2.jpg").exists())
        store.deleteAll()
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test
    fun rejectsKeysThatCouldEscapeTheFolder() {
        assertFailsWith<IllegalArgumentException> { store.write("../evil", byteArrayOf(1)) }
        assertFailsWith<IllegalArgumentException> { store.delete("a/b") }
    }
}
```

`app/src/test/kotlin/io/github/yuriimurha/reels/data/media/FakeMediaFetcherTest.kt`:
```kotlin
package io.github.yuriimurha.reels.data.media

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FakeMediaFetcherTest {
    @Test
    fun thumbnailsAreJpegs() = runTest {
        val bytes = FakeMediaFetcher().fetch("fake://thumb/1000001")!!
        assertEquals(0xFF.toByte(), bytes[0])
        assertEquals(0xD8.toByte(), bytes[1])
    }

    @Test
    fun missingAndUnknownUrlsAreUnavailable() = runTest {
        assertNull(FakeMediaFetcher().fetch("fake://missing/1000096"))
        assertNull(FakeMediaFetcher().fetch("https://example.invalid/x.jpg"))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.yuriimurha.reels.data.media.*"`
Expected: FAIL to compile.

- [ ] **Step 3: Implement**

`app/src/main/kotlin/io/github/yuriimurha/reels/data/media/MediaFetcher.kt`:
```kotlin
package io.github.yuriimurha.reels.data.media

/** Downloads media bytes for a URL the adapter returned. */
fun interface MediaFetcher {
    /** The bytes, or null when the item is unavailable (deleted, private, 404). Throws IOException on network trouble. */
    suspend fun fetch(url: String): ByteArray?
}
```

`app/src/main/kotlin/io/github/yuriimurha/reels/data/media/FakeMediaFetcher.kt`:
```kotlin
package io.github.yuriimurha.reels.data.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.absoluteValue

/** Draws placeholder thumbnails for the fake backend, so no third-party images live in the repo. */
class FakeMediaFetcher : MediaFetcher {
    override suspend fun fetch(url: String): ByteArray? = withContext(Dispatchers.Default) {
        if (url.startsWith("fake://thumb/")) placeholder(url.substringAfterLast('/')) else null
    }

    private fun placeholder(pk: String): ByteArray {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val hue = (pk.hashCode().absoluteValue % 360).toFloat()
        val top = Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.85f))
        val bottom = Color.HSVToColor(floatArrayOf((hue + 40f) % 360f, 0.65f, 0.35f))
        canvas.drawPaint(
            Paint().apply { shader = LinearGradient(0f, 0f, 0f, HEIGHT.toFloat(), top, bottom, Shader.TileMode.CLAMP) },
        )
        canvas.drawText(
            pk.takeLast(4),
            WIDTH / 2f,
            HEIGHT / 2f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 72f
                textAlign = Paint.Align.CENTER
            },
        )
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    private companion object {
        const val WIDTH = 432
        const val HEIGHT = 768
    }
}
```

`app/src/main/kotlin/io/github/yuriimurha/reels/data/media/ThumbnailStore.kt`:
```kotlin
package io.github.yuriimurha.reels.data.media

import java.io.File

/** Thumbnails in app-private `filesDir/thumbs/{pk}.jpg`, which the OS never evicts (spec 5.4). */
class ThumbnailStore(private val dir: File) {
    fun write(pk: String, bytes: ByteArray): String {
        val target = fileFor(pk)
        dir.mkdirs()
        val partial = File(dir, "${target.name}.tmp")
        partial.writeBytes(bytes)
        check(partial.renameTo(target)) { "Could not move thumbnail into place" }
        return target.absolutePath
    }

    fun delete(pk: String) {
        fileFor(pk).delete()
    }

    fun deleteAll() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun fileFor(pk: String): File {
        require(SAFE_KEY.matches(pk)) { "Unexpected media key" }
        return File(dir, "$pk.jpg")
    }

    private companion object {
        val SAFE_KEY = Regex("[A-Za-z0-9_]+")
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.yuriimurha.reels.data.media.*"`
Expected: PASS (6 tests).

- [ ] **Step 5: Record the component**

Append to the `## Components` table in `ARCHITECTURE.md`:
```markdown
| Media files | `app/.../data/media/` | `ThumbnailStore` (`filesDir/thumbs/{pk}.jpg`, atomic writes, key validation), `MediaFetcher` contract, `FakeMediaFetcher` (placeholder JPEGs for the fake backend). |
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/io/github/yuriimurha/reels/data/media app/src/test/kotlin/io/github/yuriimurha/reels/data/media ARCHITECTURE.md
git commit -m "feat(media): thumbnail store and placeholder fetcher for the fake backend" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 9: Sync engine

**Files:**
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/sync/SortKeys.kt`
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/sync/SessionSignals.kt`
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/sync/SyncEngine.kt`
- Test: `app/src/test/kotlin/io/github/yuriimurha/reels/sync/SortKeysTest.kt`
- Test: `app/src/test/kotlin/io/github/yuriimurha/reels/sync/SyncEngineTest.kt`
- Modify: `ARCHITECTURE.md`

**Interfaces:**
- Consumes: `InstagramClient`, `RemoteMedia`, `RemoteCollection`, `Page`, `InstagramException`, `FakeInstagramClient`, `FakeLibrary`, `FakeFailures` (Task 3); `ReelsDatabase`, the entities, `ALL_SAVED_ID`, `SyncMode`, `SyncStatus` (Task 4); `Pacer`, `PacingPolicy`, `PacerRefusal`, `InMemoryRequestLog`, `InMemoryCooldownStore`, `RequestLog`, `CooldownStore` (Task 6); `retryTransient` (Task 7); `MediaFetcher`, `ThumbnailStore` (Task 8); test helpers `inMemoryDb`, `loadAll` (Task 4).
- Produces (package `io.github.yuriimurha.reels.sync`):
  - `object SortKeys { const val WALK_SPAN: Long; fun walkBase(currentMax: Long?): Long; fun key(walkBase: Long, walkIndex: Long): Long }`
  - `interface SessionSignals { suspend fun loginRequired(); suspend fun challengeRequired(challengeUrl: String?) }` with `SessionSignals.None`
  - `class SyncEngine(client, pacer, db, fetcher, thumbnails, signals = SessionSignals.None, random = Random.Default, now = System::currentTimeMillis)` with `suspend fun run(runId: Long)`. Status texts the UI shows: `"Cancelled"`, `"Instagram wants verification"`, `"Session expired"`, `"Instagram is limiting requests"`, `"Cooling down"`, `"Adapter needs repair: <fieldPath>"`, `"Network problem, try again later"`, `"Run budget reached, tap Sync to continue"`, `"24-hour budget reached"`

> **Note on ordering (spec 5.2, as amended by this plan):** quick and full walks use the same numbering. At the start of a scope's walk, `walkBase = max(sortKey in scope) + WALK_SPAN`. A full walk re-keys every item it sees to `walkBase - index`. A quick walk assigns that key only to items the scope doesn't have yet. This keeps new items found across several pages in feed order. The earlier "max + n … max + 1" scheme put a later page's items above an earlier page's.

- [ ] **Step 1: Write the failing SortKeys test**

`app/src/test/kotlin/io/github/yuriimurha/reels/sync/SortKeysTest.kt`:
```kotlin
package io.github.yuriimurha.reels.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SortKeysTest {
    @Test
    fun keysFallWithWalkIndex() {
        val base = SortKeys.walkBase(currentMax = null)
        val keys = (0L until 100L).map { SortKeys.key(base, it) }
        assertEquals(keys.sortedDescending(), keys)
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun aNewWalkSitsAboveEverythingBefore() {
        val oldMax = SortKeys.key(SortKeys.walkBase(null), 0)
        val base = SortKeys.walkBase(oldMax)
        assertTrue(SortKeys.key(base, SortKeys.WALK_SPAN - 1) > oldMax)
    }

    @Test
    fun indexOutsideTheSpanIsRejected() {
        assertFailsWith<IllegalArgumentException> { SortKeys.key(SortKeys.walkBase(null), SortKeys.WALK_SPAN) }
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.yuriimurha.reels.sync.SortKeysTest"`
Expected: FAIL to compile (`SortKeys` doesn't exist).

- [ ] **Step 3: Implement SortKeys and SessionSignals**

`app/src/main/kotlin/io/github/yuriimurha/reels/sync/SortKeys.kt`:
```kotlin
package io.github.yuriimurha.reels.sync

/** Newest-saved-first ordering keys (spec 5.2). Views sort by `sortKey DESC`. */
object SortKeys {
    /** Larger than any collection, so a new walk's keys always sit above the previous ones. */
    const val WALK_SPAN = 1_000_000L

    fun walkBase(currentMax: Long?): Long = (currentMax ?: 0L) + WALK_SPAN

    fun key(walkBase: Long, walkIndex: Long): Long {
        require(walkIndex in 0 until WALK_SPAN) { "Walk index out of range" }
        return walkBase - walkIndex
    }
}
```

`app/src/main/kotlin/io/github/yuriimurha/reels/sync/SessionSignals.kt`:
```kotlin
package io.github.yuriimurha.reels.sync

/** Session problems the engine discovers mid-run. Wired to SessionRepository in Task 17. */
interface SessionSignals {
    suspend fun loginRequired()

    suspend fun challengeRequired(challengeUrl: String?)

    object None : SessionSignals {
        override suspend fun loginRequired() = Unit

        override suspend fun challengeRequired(challengeUrl: String?) = Unit
    }
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.yuriimurha.reels.sync.SortKeysTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Write the failing engine tests**

`app/src/test/kotlin/io/github/yuriimurha/reels/sync/SyncEngineTest.kt`:
```kotlin
package io.github.yuriimurha.reels.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.data.db.ALL_SAVED_ID
import io.github.yuriimurha.reels.data.db.SyncMode
import io.github.yuriimurha.reels.data.db.SyncRunEntity
import io.github.yuriimurha.reels.data.db.SyncStatus
import io.github.yuriimurha.reels.data.media.MediaFetcher
import io.github.yuriimurha.reels.data.media.ThumbnailStore
import io.github.yuriimurha.reels.instagram.Account
import io.github.yuriimurha.reels.instagram.InstagramClient
import io.github.yuriimurha.reels.instagram.InstagramException
import io.github.yuriimurha.reels.instagram.fake.FakeFailures
import io.github.yuriimurha.reels.instagram.fake.FakeInstagramClient
import io.github.yuriimurha.reels.instagram.fake.FakeLibrary
import io.github.yuriimurha.reels.sync.pacing.CooldownStore
import io.github.yuriimurha.reels.sync.pacing.InMemoryCooldownStore
import io.github.yuriimurha.reels.sync.pacing.InMemoryRequestLog
import io.github.yuriimurha.reels.sync.pacing.Pacer
import io.github.yuriimurha.reels.sync.pacing.PacingPolicy
import io.github.yuriimurha.reels.sync.pacing.RequestLog
import io.github.yuriimurha.reels.testutil.inMemoryDb
import io.github.yuriimurha.reels.testutil.loadAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class SyncEngineTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val db = inMemoryDb()
    private val thumbs by lazy { ThumbnailStore(File(tmp.root, "thumbs")) }
    private val fetcher = MediaFetcher { url -> if (url.startsWith("fake://missing/")) null else byteArrayOf(1, 2, 3) }
    private val signals = RecordingSignals()

    @After
    fun close() = db.close()

    private fun TestScope.engine(
        client: InstagramClient,
        log: RequestLog = InMemoryRequestLog(),
        cooldowns: CooldownStore = InMemoryCooldownStore(),
    ): SyncEngine {
        val pacer = Pacer(PacingPolicy.Fast, log, cooldowns, Random(1), now = { testScheduler.currentTime })
        return SyncEngine(client, pacer, db, fetcher, thumbs, signals, Random(1), now = { testScheduler.currentTime })
    }

    private suspend fun newRun(mode: SyncMode): Long =
        db.syncDao().insertRun(SyncRunEntity(mode = mode, status = SyncStatus.RUNNING, startedAt = 0))

    private suspend fun runSync(engine: SyncEngine, mode: SyncMode): SyncRunEntity {
        val id = newRun(mode)
        engine.run(id)
        return db.syncDao().run(id)!!
    }

    private suspend fun pks(collectionId: String) =
        db.mediaDao().pageCollection(collectionId).loadAll().map { it.pk }

    private fun smallClient() = FakeInstagramClient(FakeLibrary(itemCount = 50, collectionCount = 3))

    @Test
    fun firstSyncImportsEverythingNewestFirst() = runTest {
        val client = smallClient()
        val run = runSync(engine(client), SyncMode.QUICK)
        assertEquals(SyncStatus.DONE, run.status)
        assertEquals(client.library.allSaved().map { it.pk }, pks(ALL_SAVED_ID))
        for (collection in client.library.collections) {
            assertEquals(client.library.itemsIn(collection.id).map { it.pk }, pks(collection.id))
        }
        assertEquals(50, run.newItems)
        assertEquals(50, run.thumbsCached + run.failures)
    }

    @Test
    fun quickSyncStopsAtTheFirstKnownItem() = runTest {
        val client = smallClient()
        val engine = engine(client)
        runSync(engine, SyncMode.QUICK)
        val added = client.library.addNewSaves(3)
        val callsBefore = client.calls.size
        val run = runSync(engine, SyncMode.QUICK)
        assertEquals(listOf("saved:all:null"), client.calls.drop(callsBefore).filter { it.startsWith("saved:") })
        assertEquals(added.map { it.pk }, pks(ALL_SAVED_ID).take(3))
        assertEquals(3, run.newItems)
    }

    @Test
    fun newItemsAcrossSeveralPagesKeepFeedOrder() = runTest {
        val client = smallClient()
        val engine = engine(client)
        runSync(engine, SyncMode.QUICK)
        val added = client.library.addNewSaves(45)
        runSync(engine, SyncMode.QUICK)
        assertEquals(added.map { it.pk }, pks(ALL_SAVED_ID).take(45))
        assertEquals(client.library.allSaved().map { it.pk }, pks(ALL_SAVED_ID))
    }

    @Test
    fun interruptedFullSyncDeletesNothing() = runTest {
        val client = smallClient()
        val engine = engine(client)
        runSync(engine, SyncMode.QUICK)
        val gone = client.library.allSaved().last().pk
        client.library.unsave(gone)
        val failAt = client.calls.size + 4 // currentUser, collections, page 1, then page 2 fails
        client.failures = FakeFailures {
            if (it == failAt) InstagramException.ChallengeRequired("https://www.instagram.com/challenge/x/") else null
        }
        val run = runSync(engine, SyncMode.FULL)
        assertEquals(SyncStatus.STOPPED_CHALLENGE, run.status)
        assertEquals(failAt, client.calls.size, "no request after a challenge")
        assertTrue(gone in pks(ALL_SAVED_ID), "an interrupted walk must not delete")
        assertEquals(listOf("challenge:https://www.instagram.com/challenge/x/"), signals.events)
    }

    @Test
    fun completedFullSyncRemovesUnsavedAndAppliesMoves() = runTest {
        val client = smallClient()
        val engine = engine(client)
        runSync(engine, SyncMode.QUICK)
        val all = client.library.allSaved()
        val gone = all[5].pk
        val moved = all.first { it.pk != gone && "c1" in it.savedCollectionIds.orEmpty() }.pk
        val thumbFile = File(tmp.root, "thumbs/$gone.jpg")
        assertTrue(thumbFile.exists())

        client.library.unsave(gone)
        client.library.setCollections(moved, setOf("c2"))
        val run = runSync(engine, SyncMode.FULL)

        assertEquals(SyncStatus.DONE, run.status)
        assertFalse(gone in pks(ALL_SAVED_ID))
        assertNotNull(db.mediaDao().byPks(listOf(gone)).single().removedAt)
        assertFalse(thumbFile.exists())
        assertTrue(moved in pks("c2"))
        assertFalse(moved in pks("c1"))
    }

    @Test
    fun resumedRunContinuesFromItsCursorWithTheSameNumbering() = runTest {
        val client = smallClient()
        val engine = engine(client)
        client.failures = FakeFailures { if (it == 4) InstagramException.ChallengeRequired(null) else null }
        val id = newRun(SyncMode.FULL)
        engine.run(id)
        assertEquals(SyncStatus.STOPPED_CHALLENGE, db.syncDao().run(id)!!.status)
        val cursorBefore = db.syncDao().cursor(id, ALL_SAVED_ID)!!
        assertEquals("o:20", cursorBefore.nextCursor)

        client.failures = FakeFailures { null }
        val callsBefore = client.calls.size
        engine.run(id)

        assertEquals(SyncStatus.DONE, db.syncDao().run(id)!!.status)
        assertEquals(
            listOf("saved:all:o:20", "saved:all:o:40"),
            client.calls.drop(callsBefore).filter { it.startsWith("saved:") },
        )
        assertEquals(cursorBefore.walkBase, db.syncDao().cursor(id, ALL_SAVED_ID)!!.walkBase)
        assertEquals(client.library.allSaved().map { it.pk }, pks(ALL_SAVED_ID))
    }

    @Test
    fun bothStrategiesProduceTheSameMemberships() = runTest {
        fun library() = FakeLibrary(seed = 3, itemCount = 60, collectionCount = 4)
        suspend fun memberships() = (1..4).associate { "c$it" to pks("c$it").toSet() }

        runSync(engine(FakeInstagramClient(library(), reportsSavedCollectionIds = true)), SyncMode.QUICK)
        val strategyA = memberships()
        db.deleteLibrary()
        runSync(engine(FakeInstagramClient(library(), reportsSavedCollectionIds = false)), SyncMode.QUICK)
        assertEquals(strategyA, memberships())
    }

    @Test
    fun loginRequiredStopsAndSignals() = runTest {
        val client = smallClient()
        client.failures = FakeFailures { if (it == 1) InstagramException.LoginRequired() else null }
        val run = runSync(engine(client), SyncMode.QUICK)
        assertEquals(SyncStatus.STOPPED_LOGIN, run.status)
        assertEquals(listOf("login"), signals.events)
        assertEquals(1, client.calls.size)
    }

    @Test
    fun rateLimitStopsAndTheCooldownBlocksTheNextRun() = runTest {
        val client = smallClient()
        val engine = engine(client, cooldowns = InMemoryCooldownStore())
        client.failures = FakeFailures { if (it == 3) InstagramException.RateLimited() else null }
        assertEquals(SyncStatus.STOPPED_RATE_LIMIT, runSync(engine, SyncMode.QUICK).status)
        val calls = client.calls.size
        val next = runSync(engine, SyncMode.QUICK)
        assertEquals(SyncStatus.STOPPED_RATE_LIMIT, next.status)
        assertEquals("Cooling down", next.lastError)
        assertEquals(calls, client.calls.size, "no request during a cooldown")
    }

    @Test
    fun shapeChangeStopsWithTheFieldPath() = runTest {
        val client = smallClient()
        client.failures = FakeFailures { if (it == 3) InstagramException.ShapeChanged("items[0].code") else null }
        val run = runSync(engine(client), SyncMode.QUICK)
        assertEquals(SyncStatus.STOPPED_SHAPE, run.status)
        assertEquals("Adapter needs repair: items[0].code", run.lastError)
    }

    @Test
    fun transientFailuresAreRetriedThenTheRunPauses() = runTest {
        val client = smallClient()
        client.failures = FakeFailures { if (it >= 3) InstagramException.Transient() else null }
        val run = runSync(engine(client), SyncMode.QUICK)
        assertEquals(SyncStatus.PAUSED, run.status)
        assertEquals(7, client.calls.size, "currentUser, collections, then 5 attempts")
    }

    @Test
    fun dailyBudgetPausesTheRun() = runTest {
        val client = smallClient()
        val run = runSync(engine(client, log = InMemoryRequestLog(List(599) { 0L })), SyncMode.QUICK)
        assertEquals(SyncStatus.PAUSED, run.status)
        assertEquals("24-hour budget reached", run.lastError)
        assertEquals(1, client.calls.size)
    }

    @Test
    fun unavailableThumbnailsAreCountedAndSyncContinues() = runTest {
        val client = FakeInstagramClient(FakeLibrary(itemCount = 200, collectionCount = 3))
        val run = runSync(engine(client), SyncMode.QUICK)
        val missing = client.library.allSaved().filter { it.thumbnailUrl.startsWith("fake://missing/") }
        assertEquals(SyncStatus.DONE, run.status)
        assertEquals(missing.size, run.failures)
        assertTrue(missing.isNotEmpty())
        assertNull(db.mediaDao().byPks(listOf(missing.first().pk)).single().thumbPath)
    }

    @Test
    fun cancellationLeavesTheRunPaused() = runTest {
        val entered = CompletableDeferred<Unit>()
        val fake = smallClient()
        val blocking = object : InstagramClient by fake {
            override suspend fun currentUser(): Account {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        val engine = engine(blocking)
        val id = newRun(SyncMode.QUICK)
        val job = launch { engine.run(id) }
        entered.await()
        job.cancelAndJoin()
        val run = db.syncDao().run(id)!!
        assertEquals(SyncStatus.PAUSED, run.status)
        assertEquals("Cancelled", run.lastError)
    }

    private class RecordingSignals : SessionSignals {
        val events = mutableListOf<String>()

        override suspend fun loginRequired() {
            events += "login"
        }

        override suspend fun challengeRequired(challengeUrl: String?) {
            events += "challenge:$challengeUrl"
        }
    }
}
```

- [ ] **Step 6: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.yuriimurha.reels.sync.SyncEngineTest"`
Expected: FAIL to compile (`SyncEngine` doesn't exist).

- [ ] **Step 7: Implement the engine**

`app/src/main/kotlin/io/github/yuriimurha/reels/sync/SyncEngine.kt`:
```kotlin
package io.github.yuriimurha.reels.sync

import androidx.room.withTransaction
import io.github.yuriimurha.reels.data.db.ALL_SAVED_ID
import io.github.yuriimurha.reels.data.db.CollectionEntity
import io.github.yuriimurha.reels.data.db.CollectionMediaEntity
import io.github.yuriimurha.reels.data.db.MediaEntity
import io.github.yuriimurha.reels.data.db.ReelsDatabase
import io.github.yuriimurha.reels.data.db.SyncCursorEntity
import io.github.yuriimurha.reels.data.db.SyncMode
import io.github.yuriimurha.reels.data.db.SyncRunEntity
import io.github.yuriimurha.reels.data.db.SyncStatus
import io.github.yuriimurha.reels.data.media.MediaFetcher
import io.github.yuriimurha.reels.data.media.ThumbnailStore
import io.github.yuriimurha.reels.instagram.InstagramClient
import io.github.yuriimurha.reels.instagram.InstagramException
import io.github.yuriimurha.reels.instagram.Page
import io.github.yuriimurha.reels.instagram.RemoteCollection
import io.github.yuriimurha.reels.instagram.RemoteMedia
import io.github.yuriimurha.reels.sync.pacing.Pacer
import io.github.yuriimurha.reels.sync.pacing.PacerRefusal
import io.github.yuriimurha.reels.sync.pacing.retryTransient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random

/** Runs one sync (spec 7.2): session check, collection list, scope walks, reconcile, thumbnails. */
class SyncEngine(
    private val client: InstagramClient,
    private val pacer: Pacer,
    private val db: ReelsDatabase,
    private val fetcher: MediaFetcher,
    private val thumbnails: ThumbnailStore,
    private val signals: SessionSignals = SessionSignals.None,
    private val random: Random = Random.Default,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mediaDao = db.mediaDao()
    private val collectionDao = db.collectionDao()
    private val syncDao = db.syncDao()

    /** Executes or resumes run [runId]. Its final status is left in `sync_run`; only cancellation propagates. */
    suspend fun run(runId: Long) {
        val stored = checkNotNull(syncDao.run(runId)) { "No sync run $runId" }
        val progress = Progress(
            stored.copy(status = SyncStatus.RUNNING, lastError = null, finishedAt = null, collectionsDone = 0),
            pacer.newRun(),
        )
        progress.save()
        try {
            progress.phase("Checking session")
            call(progress) { client.currentUser() }
            progress.phase("Listing collections")
            val collections = fetchCollections(progress)
            val scopes = listOf(ALL_SAVED_ID to "All Saved") +
                if (client.reportsSavedCollectionIds) emptyList() else collections.map { it.id to it.name }
            progress.update { it.copy(collectionsTotal = scopes.size) }
            val knownCollections = collections.map { it.id }.toSet()
            for ((scope, label) in scopes) walkScope(progress, scope, label, knownCollections)
            progress.finish(SyncStatus.DONE, null)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { progress.finish(SyncStatus.PAUSED, "Cancelled") }
            throw e
        } catch (e: InstagramException.ChallengeRequired) {
            signals.challengeRequired(e.challengeUrl)
            progress.finish(SyncStatus.STOPPED_CHALLENGE, "Instagram wants verification")
        } catch (e: InstagramException.LoginRequired) {
            signals.loginRequired()
            progress.finish(SyncStatus.STOPPED_LOGIN, "Session expired")
        } catch (e: InstagramException.RateLimited) {
            progress.finish(SyncStatus.STOPPED_RATE_LIMIT, "Instagram is limiting requests")
        } catch (e: PacerRefusal.CoolingDown) {
            progress.finish(SyncStatus.STOPPED_RATE_LIMIT, "Cooling down")
        } catch (e: InstagramException.ShapeChanged) {
            progress.finish(SyncStatus.STOPPED_SHAPE, "Adapter needs repair: ${e.fieldPath}")
        } catch (e: InstagramException.Transient) {
            progress.finish(SyncStatus.PAUSED, "Network problem, try again later")
        } catch (e: PacerRefusal.RunBudgetReached) {
            progress.finish(SyncStatus.PAUSED, "Run budget reached, tap Sync to continue")
        } catch (e: PacerRefusal.DailyBudgetReached) {
            progress.finish(SyncStatus.PAUSED, "24-hour budget reached")
        }
    }

    private suspend fun <T> call(progress: Progress, request: suspend () -> T): T =
        retryTransient(random) { pacer.sync(progress.budget, request) }

    private suspend fun fetchCollections(progress: Progress): List<CollectionEntity> {
        val remote = mutableListOf<RemoteCollection>()
        var cursor: String? = null
        do {
            val from = cursor
            val page = call(progress) { client.collections(from) }
            remote += page.items
            cursor = page.nextCursor
        } while (cursor != null)
        val live = remote.mapIndexed { index, c -> CollectionEntity(c.id, c.name, c.coverMediaPk, position = index) }
        db.withTransaction {
            collectionDao.upsert(live + CollectionEntity(ALL_SAVED_ID, "All Saved", coverPk = null, position = -1))
            collectionDao.markRemovedExcept(live.map { it.id }, now())
        }
        return live
    }

    private suspend fun walkScope(progress: Progress, scope: String, label: String, knownCollections: Set<String>) {
        var cursor = syncDao.cursor(progress.run.id, scope) ?: SyncCursorEntity(
            runId = progress.run.id,
            scope = scope,
            nextCursor = null,
            walkBase = SortKeys.walkBase(collectionDao.maxSortKey(scope)),
            walkIndex = 0,
            done = false,
        ).also { syncDao.upsertCursor(it) }
        if (!cursor.done) progress.phase("Syncing $label")
        while (!cursor.done) {
            val from = cursor.nextCursor
            val page = call(progress) { client.savedMedia(scope.takeUnless { it == ALL_SAVED_ID }, from) }
            val current = cursor
            val outcome = db.withTransaction { applyPage(progress, scope, current, page, knownCollections) }
            cursor = outcome.cursor
            outcome.removedPks.forEach(thumbnails::delete)
            cacheThumbnails(progress, outcome.needThumbnails)
        }
        progress.update { it.copy(collectionsDone = it.collectionsDone + 1) }
    }

    private class PageOutcome(
        val cursor: SyncCursorEntity,
        val needThumbnails: List<RemoteMedia>,
        val removedPks: List<String>,
    )

    /** One page in one transaction: media, memberships, cursor and (at the end of a FULL walk) reconcile. */
    private suspend fun applyPage(
        progress: Progress,
        scope: String,
        cursor: SyncCursorEntity,
        page: Page<RemoteMedia>,
        knownCollections: Set<String>,
    ): PageOutcome {
        val runId = progress.run.id
        val mode = progress.run.mode
        val at = now()
        val pks = page.items.map { it.pk }
        val existing = mediaDao.byPks(pks).associateBy { it.pk }
        val members = collectionDao.memberships(scope, pks).associateBy { it.mediaPk }

        mediaDao.upsert(page.items.map { it.toEntity(existing[it.pk], at) })
        val memberships = page.items.mapIndexed { offset, item ->
            val known = members[item.pk]
            val key = if (mode == SyncMode.FULL || known == null) {
                SortKeys.key(cursor.walkBase, cursor.walkIndex + offset)
            } else {
                known.sortKey
            }
            CollectionMediaEntity(scope, item.pk, key, runId)
        }
        collectionDao.upsertMemberships(memberships)

        if (scope == ALL_SAVED_ID && client.reportsSavedCollectionIds) {
            page.items.zip(memberships).forEach { (item, member) ->
                val ids = item.savedCollectionIds.orEmpty().filter { it in knownCollections }
                collectionDao.deleteRealMembershipsExcept(item.pk, ids)
                collectionDao.upsertMemberships(ids.map { CollectionMediaEntity(it, item.pk, member.sortKey, runId) })
            }
        }

        val reachedEnd = page.nextCursor == null
        val hitKnownItem = mode == SyncMode.QUICK && page.items.any { it.pk in members }
        val next = cursor.copy(
            nextCursor = page.nextCursor,
            walkIndex = cursor.walkIndex + page.items.size,
            done = reachedEnd || hitKnownItem,
        )
        syncDao.upsertCursor(next)

        val removed = if (mode == SyncMode.FULL && reachedEnd) reconcile(scope, runId) else emptyList()
        progress.update { r ->
            r.copy(newItems = r.newItems + page.items.count { it.pk !in existing }, seenItems = r.seenItems + page.items.size)
        }
        return PageOutcome(next, page.items.filter { existing[it.pk]?.thumbPath == null }, removed)
    }

    /** Spec 7.2 step 5. Called only when a FULL walk of [scope] reached the end in this run. */
    private suspend fun reconcile(scope: String, runId: Long): List<String> {
        if (scope != ALL_SAVED_ID) {
            collectionDao.deleteUnseen(scope, runId)
            return emptyList()
        }
        val unsaved = collectionDao.unseenPks(ALL_SAVED_ID, runId)
        unsaved.chunked(500).forEach { chunk ->
            mediaDao.markRemoved(chunk, now())
            collectionDao.deleteAllMembershipsOf(chunk)
        }
        return unsaved
    }

    private suspend fun cacheThumbnails(progress: Progress, items: List<RemoteMedia>) {
        if (items.isEmpty()) return
        val results = coroutineScope {
            items.map { item ->
                async {
                    val bytes = try {
                        pacer.cdn { fetcher.fetch(item.thumbnailUrl) }
                    } catch (e: IOException) {
                        null
                    }
                    item.pk to bytes?.let { withContext(Dispatchers.IO) { thumbnails.write(item.pk, it) } }
                }
            }.awaitAll()
        }
        results.forEach { (pk, path) -> if (path != null) mediaDao.setThumbPath(pk, path) }
        progress.update { r ->
            r.copy(
                thumbsCached = r.thumbsCached + results.count { it.second != null },
                failures = r.failures + results.count { it.second == null },
            )
        }
    }

    private fun RemoteMedia.toEntity(previous: MediaEntity?, at: Long) = MediaEntity(
        pk = pk,
        code = code,
        type = type,
        author = author,
        caption = caption,
        takenAt = takenAt.toEpochMilli(),
        width = width,
        height = height,
        carouselCount = carouselCount,
        thumbPath = previous?.thumbPath,
        thumbUrl = thumbnailUrl,
        videoUrl = videoUrl,
        videoUrlExpiresAt = videoUrlExpiresAt?.toEpochMilli(),
        collectionNames = previous?.collectionNames.orEmpty(),
        firstSeenAt = previous?.firstSeenAt ?: at,
        lastSeenAt = at,
        removedAt = null,
    )

    /** The run row plus this invocation's request budget; every change is written straight through. */
    private inner class Progress(var run: SyncRunEntity, val budget: Pacer.RunBudget) {
        private val requestsBefore = run.requestsUsed

        suspend fun save() = syncDao.updateRun(run)

        suspend fun update(change: (SyncRunEntity) -> SyncRunEntity) {
            run = change(run).copy(requestsUsed = requestsBefore + budget.used)
            syncDao.updateRun(run)
        }

        suspend fun phase(text: String) = update { it.copy(phase = text) }

        suspend fun finish(status: SyncStatus, error: String?) {
            mediaDao.refreshCollectionNames()
            update { it.copy(status = status, lastError = error, finishedAt = now(), phase = "") }
        }
    }
}
```

- [ ] **Step 8: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.yuriimurha.reels.sync.*"`
Expected: PASS (17 sync tests plus the pacing tests). If `bothStrategiesProduceTheSameMemberships` fails, compare per-collection sets before touching the engine. The fake's `itemsIn` and `savedCollectionIds` come from the same membership sets, so a difference is an engine bug.

- [ ] **Step 9: Record the component**

Append to the `## Components` table in `ARCHITECTURE.md`:
```markdown
| Sync engine | `app/.../sync/SyncEngine.kt` | One run = session check, collection list, scope walks (strategy A: All Saved only, using `savedCollectionIds`; B: every collection), one transaction per page (media, memberships, cursor), thumbnails on the CDN lane. FULL runs reconcile a scope only when its walk reached the end in that run. Errors map to run statuses; only cancellation propagates. `SortKeys` gives newest-first keys. |
```

- [ ] **Step 10: Commit**

```bash
git add app/src/main/kotlin/io/github/yuriimurha/reels/sync/SortKeys.kt app/src/main/kotlin/io/github/yuriimurha/reels/sync/SessionSignals.kt app/src/main/kotlin/io/github/yuriimurha/reels/sync/SyncEngine.kt app/src/test/kotlin/io/github/yuriimurha/reels/sync/SortKeysTest.kt app/src/test/kotlin/io/github/yuriimurha/reels/sync/SyncEngineTest.kt ARCHITECTURE.md
git commit -m "feat(sync): resumable engine with quick/full walks and safe reconcile" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 10: Sync controller, worker and app container

**Files:**
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/sync/SyncScheduler.kt`
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/sync/SyncController.kt`
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/sync/SyncWorker.kt`
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/sync/SyncNotifications.kt`
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/di/Backend.kt`
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/di/AppContainer.kt`
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/di/ReelsWorkerFactory.kt`
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/ReelsApp.kt`
- Modify: `app/src/main/AndroidManifest.xml`, `app/src/main/res/values/strings.xml`, `app/src/test/resources/robolectric.properties`
- Test: `app/src/test/kotlin/io/github/yuriimurha/reels/sync/SyncControllerTest.kt`
- Test: `app/src/test/kotlin/io/github/yuriimurha/reels/di/FastPolicyGuardTest.kt`
- Modify: `ARCHITECTURE.md`

**Interfaces:**
- Consumes: `SyncEngine` (Task 9); `ReelsDatabase`, `SyncRunEntity`, `SyncMode`, `SyncStatus` (Task 4); `Pacer`, `PacingPolicy`, `InMemoryRequestLog`, `InMemoryCooldownStore` (Task 6); `SettingsStore` (Task 7); `MediaFetcher`, `FakeMediaFetcher`, `ThumbnailStore` (Task 8); `LibraryRepository` (Task 5); `FakeInstagramClient` (Task 3).
- Produces:
  - `interface SyncScheduler { fun enqueue(runId: Long); fun cancel(); suspend fun isActive(): Boolean }`, `WorkManagerSyncScheduler(context)`, and `WorkManagerSyncScheduler.UNIQUE_WORK = "sync"`
  - `class SyncController(db, scheduler, now = System::currentTimeMillis)` with `latestRun: Flow<SyncRunEntity?>`, `lastSyncAt: Flow<Long?>`, `lastFullSyncAt: Flow<Long?>`, `start(mode): Long`, `cancel()`, `discardResumable()`, `recoverInterruptedRuns()`
  - `class SyncWorker(context, params, engine)` with `SyncWorker.KEY_RUN_ID`
  - `sealed interface Backend { val client: InstagramClient; val fetcher: MediaFetcher; val pacer: Pacer }` with `Backend.Fake`
  - `class AppContainer(context)` with `settings`, `db`, `thumbnails`, `backend`, `library`, `syncController`, `syncEngine()`
  - `class ReelsApp : Application, Configuration.Provider` with `container: AppContainer`

- [ ] **Step 1: Write the failing tests**

`app/src/test/kotlin/io/github/yuriimurha/reels/sync/SyncControllerTest.kt`:
```kotlin
package io.github.yuriimurha.reels.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.data.db.SyncMode
import io.github.yuriimurha.reels.data.db.SyncRunEntity
import io.github.yuriimurha.reels.data.db.SyncStatus
import io.github.yuriimurha.reels.testutil.inMemoryDb
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
class SyncControllerTest {
    private val db = inMemoryDb()
    private val scheduler = FakeScheduler()
    private val controller = SyncController(db, scheduler, now = { 1_000 })

    @After
    fun close() = db.close()

    @Test
    fun startCreatesARunningRunAndEnqueuesIt() = runTest {
        val id = controller.start(SyncMode.QUICK)
        val run = db.syncDao().run(id)!!
        assertEquals(SyncStatus.RUNNING, run.status)
        assertEquals(SyncMode.QUICK, run.mode)
        assertEquals(listOf(id), scheduler.enqueued)
    }

    @Test
    fun doubleTapDoesNotCreateSecondRun() = runTest {
        val first = controller.start(SyncMode.QUICK)
        val second = controller.start(SyncMode.QUICK)
        val concurrent = listOf(async { controller.start(SyncMode.FULL) }, async { controller.start(SyncMode.QUICK) }).awaitAll()
        assertEquals(setOf(first), (listOf(second) + concurrent).toSet())
        assertNull(db.syncDao().run(first + 1), "only one run row exists")
    }

    @Test
    fun aResumableRunIsResumedEvenFromTheOtherButton() = runTest {
        val paused = db.syncDao().insertRun(SyncRunEntity(mode = SyncMode.QUICK, status = SyncStatus.STOPPED_LOGIN, startedAt = 0))
        val id = controller.start(SyncMode.FULL)
        assertEquals(paused, id)
        val run = db.syncDao().run(id)!!
        assertEquals(SyncStatus.RUNNING, run.status)
        assertEquals(SyncMode.QUICK, run.mode)
    }

    @Test
    fun discardMakesTheNextTapStartFresh() = runTest {
        val paused = db.syncDao().insertRun(SyncRunEntity(mode = SyncMode.FULL, status = SyncStatus.PAUSED, startedAt = 0))
        controller.discardResumable()
        assertEquals(SyncStatus.CANCELLED, db.syncDao().run(paused)!!.status)
        assertNotEquals(paused, controller.start(SyncMode.QUICK))
    }

    @Test
    fun recoverInterruptedRunsPausesOrphans() = runTest {
        val orphan = db.syncDao().insertRun(SyncRunEntity(mode = SyncMode.FULL, status = SyncStatus.RUNNING, startedAt = 0))
        scheduler.active = false
        controller.recoverInterruptedRuns()
        val run = db.syncDao().run(orphan)!!
        assertEquals(SyncStatus.PAUSED, run.status)
        assertEquals("Interrupted, tap Sync to resume", run.lastError)
        assertEquals(orphan, controller.start(SyncMode.QUICK), "the orphan resumes from its cursor")
    }

    @Test
    fun recoverLeavesAnActiveRunAlone() = runTest {
        val running = db.syncDao().insertRun(SyncRunEntity(mode = SyncMode.FULL, status = SyncStatus.RUNNING, startedAt = 0))
        scheduler.active = true
        controller.recoverInterruptedRuns()
        assertEquals(SyncStatus.RUNNING, db.syncDao().run(running)!!.status)
    }

    @Test
    fun cancelStopsTheWorkAndLeavesTheRunResumable() = runTest {
        val id = controller.start(SyncMode.QUICK)
        controller.cancel()
        assertEquals(1, scheduler.cancelled)
        assertEquals(SyncStatus.PAUSED, db.syncDao().run(id)!!.status)
    }

    private class FakeScheduler : SyncScheduler {
        val enqueued = mutableListOf<Long>()
        var cancelled = 0
        var active = false

        override fun enqueue(runId: Long) {
            enqueued += runId
        }

        override fun cancel() {
            cancelled++
        }

        override suspend fun isActive(): Boolean = active
    }
}
```

`app/src/test/kotlin/io/github/yuriimurha/reels/di/FastPolicyGuardTest.kt`:
```kotlin
package io.github.yuriimurha.reels.di

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Spec 4.5: the fast pacing policy can only exist next to the fake client. */
class FastPolicyGuardTest {
    @Test
    fun fastPacingIsOnlyReferencedByTheFakeBackend() {
        val sources = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(sources.isNotEmpty(), "unit tests must run from the app module directory")
        val users = sources
            .filter { "PacingPolicy.Fast" in it.readText() }
            .map { it.invariantSeparatorsPath.substringAfter("src/main/kotlin/") }
        assertEquals(listOf("io/github/yuriimurha/reels/di/Backend.kt"), users)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.yuriimurha.reels.sync.SyncControllerTest" --tests "io.github.yuriimurha.reels.di.FastPolicyGuardTest"`
Expected: FAIL to compile (`SyncController`, `SyncScheduler` don't exist).

- [ ] **Step 3: Implement scheduling and the controller**

`app/src/main/kotlin/io/github/yuriimurha/reels/sync/SyncScheduler.kt`:
```kotlin
package io.github.yuriimurha.reels.sync

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.first

/** Where sync runs execute. WorkManager in the app; a fake in tests. */
interface SyncScheduler {
    fun enqueue(runId: Long)
    fun cancel()
    suspend fun isActive(): Boolean
}

class WorkManagerSyncScheduler(private val context: Context) : SyncScheduler {
    private val workManager get() = WorkManager.getInstance(context)

    /** KEEP: while a sync is queued or running, another enqueue is a no-op (spec 7.1). */
    override fun enqueue(runId: Long) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(workDataOf(SyncWorker.KEY_RUN_ID to runId))
            .build()
        workManager.enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.KEEP, request)
    }

    override fun cancel() {
        workManager.cancelUniqueWork(UNIQUE_WORK)
    }

    override suspend fun isActive(): Boolean =
        workManager.getWorkInfosForUniqueWorkFlow(UNIQUE_WORK).first().any { !it.state.isFinished }

    companion object {
        const val UNIQUE_WORK = "sync"
    }
}
```

`app/src/main/kotlin/io/github/yuriimurha/reels/sync/SyncController.kt`:
```kotlin
package io.github.yuriimurha.reels.sync

import io.github.yuriimurha.reels.data.db.ReelsDatabase
import io.github.yuriimurha.reels.data.db.SyncMode
import io.github.yuriimurha.reels.data.db.SyncRunEntity
import io.github.yuriimurha.reels.data.db.SyncStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The Sync and Full sync buttons (spec 7.1). */
class SyncController(
    db: ReelsDatabase,
    private val scheduler: SyncScheduler,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val syncDao = db.syncDao()
    private val mutex = Mutex()

    val latestRun: Flow<SyncRunEntity?> = syncDao.latestRunFlow()
    val lastSyncAt: Flow<Long?> = syncDao.lastSyncAt()
    val lastFullSyncAt: Flow<Long?> = syncDao.lastFullSyncAt()

    /** Resumes the latest unfinished run (whatever its mode), or starts a new one. Never creates a second run. */
    suspend fun start(mode: SyncMode): Long = mutex.withLock {
        val latest = syncDao.latestRun()
        val id = if (latest != null && (latest.status == SyncStatus.RUNNING || latest.status.isResumable)) {
            if (latest.status != SyncStatus.RUNNING) {
                syncDao.updateRun(latest.copy(status = SyncStatus.RUNNING, lastError = null))
            }
            latest.id
        } else {
            syncDao.insertRun(SyncRunEntity(mode = mode, status = SyncStatus.RUNNING, startedAt = now()))
        }
        scheduler.enqueue(id)
        id
    }

    /** Stops after the current request; the run stays resumable from its cursors. */
    suspend fun cancel() {
        scheduler.cancel()
        syncDao.pauseRunningRuns("Cancelled")
    }

    /** Abandons an unfinished run so the next tap starts fresh. Nothing is deleted (spec 7.1). */
    suspend fun discardResumable() {
        mutex.withLock {
            val latest = syncDao.latestRun() ?: return@withLock
            if (latest.status.isResumable) {
                syncDao.updateRun(latest.copy(status = SyncStatus.CANCELLED, finishedAt = now()))
            }
        }
    }

    /** After process death a RUNNING row can be left with no worker behind it; make it resumable. */
    suspend fun recoverInterruptedRuns() {
        mutex.withLock {
            if (!scheduler.isActive()) syncDao.pauseRunningRuns("Interrupted, tap Sync to resume")
        }
    }
}
```

`app/src/main/kotlin/io/github/yuriimurha/reels/sync/SyncNotifications.kt`:
```kotlin
package io.github.yuriimurha.reels.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import io.github.yuriimurha.reels.R

object SyncNotifications {
    private const val CHANNEL_ID = "sync"
    private const val NOTIFICATION_ID = 1

    fun foregroundInfo(context: Context): ForegroundInfo {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.sync_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(context.getString(R.string.sync_notification_title))
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
}
```

`app/src/main/kotlin/io/github/yuriimurha/reels/sync/SyncWorker.kt`:
```kotlin
package io.github.yuriimurha.reels.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters

/** Runs one sync in the foreground so it survives leaving the app. The outcome lives in `sync_run`. */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
    private val engine: SyncEngine,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val runId = inputData.getLong(KEY_RUN_ID, -1)
        if (runId < 0) return Result.failure()
        try {
            setForeground(getForegroundInfo())
        } catch (e: IllegalStateException) {
            // Started while the app was in the background: run without the notification.
        }
        engine.run(runId)
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = SyncNotifications.foregroundInfo(applicationContext)

    companion object {
        const val KEY_RUN_ID = "runId"
    }
}
```

- [ ] **Step 4: Implement the backend, container and application**

`app/src/main/kotlin/io/github/yuriimurha/reels/di/Backend.kt`:
```kotlin
package io.github.yuriimurha.reels.di

import io.github.yuriimurha.reels.data.media.FakeMediaFetcher
import io.github.yuriimurha.reels.data.media.MediaFetcher
import io.github.yuriimurha.reels.instagram.InstagramClient
import io.github.yuriimurha.reels.instagram.fake.FakeInstagramClient
import io.github.yuriimurha.reels.sync.pacing.InMemoryCooldownStore
import io.github.yuriimurha.reels.sync.pacing.InMemoryRequestLog
import io.github.yuriimurha.reels.sync.pacing.Pacer
import io.github.yuriimurha.reels.sync.pacing.PacingPolicy

/** Where the library comes from (spec 4.5). Each backend owns the pacing that matches it. */
sealed interface Backend {
    val client: InstagramClient
    val fetcher: MediaFetcher
    val pacer: Pacer

    /**
     * Fixture library for development. Fast pacing with its own in-memory budgets, so fake syncs never
     * touch the real 24 h budget or cooldown, and fast pacing can never reach real traffic.
     */
    class Fake(
        override val client: FakeInstagramClient = FakeInstagramClient(),
        override val fetcher: MediaFetcher = FakeMediaFetcher(),
    ) : Backend {
        override val pacer = Pacer(PacingPolicy.Fast, InMemoryRequestLog(), InMemoryCooldownStore())
    }
}
```

`app/src/main/kotlin/io/github/yuriimurha/reels/di/AppContainer.kt`:
```kotlin
package io.github.yuriimurha.reels.di

import android.content.Context
import io.github.yuriimurha.reels.data.db.ReelsDatabase
import io.github.yuriimurha.reels.data.library.LibraryRepository
import io.github.yuriimurha.reels.data.media.ThumbnailStore
import io.github.yuriimurha.reels.data.settings.SettingsStore
import io.github.yuriimurha.reels.sync.SyncController
import io.github.yuriimurha.reels.sync.SyncEngine
import io.github.yuriimurha.reels.sync.WorkManagerSyncScheduler
import java.io.File

/** Hand-wired dependencies, one instance per process (spec 4.1). */
class AppContainer(context: Context) {
    val settings: SettingsStore by lazy { SettingsStore.create(context) }
    val db: ReelsDatabase by lazy { ReelsDatabase.build(context) }
    val thumbnails: ThumbnailStore by lazy { ThumbnailStore(File(context.filesDir, "thumbs")) }
    val backend: Backend by lazy { Backend.Fake() }
    val library: LibraryRepository by lazy { LibraryRepository(db) }
    val syncController: SyncController by lazy { SyncController(db, WorkManagerSyncScheduler(context)) }

    fun syncEngine(): SyncEngine = SyncEngine(backend.client, backend.pacer, db, backend.fetcher, thumbnails)
}
```

`app/src/main/kotlin/io/github/yuriimurha/reels/di/ReelsWorkerFactory.kt`:
```kotlin
package io.github.yuriimurha.reels.di

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import io.github.yuriimurha.reels.sync.SyncWorker

class ReelsWorkerFactory(private val container: () -> AppContainer) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = when (workerClassName) {
        SyncWorker::class.java.name -> SyncWorker(appContext, workerParameters, container().syncEngine())
        else -> null
    }
}
```

`app/src/main/kotlin/io/github/yuriimurha/reels/ReelsApp.kt`:
```kotlin
package io.github.yuriimurha.reels

import android.app.Application
import androidx.work.Configuration
import io.github.yuriimurha.reels.di.AppContainer
import io.github.yuriimurha.reels.di.ReelsWorkerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ReelsApp : Application(), Configuration.Provider {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        appScope.launch { container.syncController.recoverInterruptedRuns() }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(ReelsWorkerFactory { container }).build()
}
```

- [ ] **Step 5: Wire the manifest, strings and test application**

Replace `app/src/main/AndroidManifest.xml` with:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <application
        android:name=".ReelsApp"
        android:allowBackup="false"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.Reels">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <!-- WorkManager is initialised on demand from ReelsApp (custom WorkerFactory). -->
        <provider
            android:name="androidx.startup.InitializationProvider"
            android:authorities="${applicationId}.androidx-startup"
            android:exported="false"
            tools:node="merge">
            <meta-data
                android:name="androidx.work.WorkManagerInitializer"
                android:value="androidx.startup"
                tools:node="remove" />
        </provider>

        <service
            android:name="androidx.work.impl.foreground.SystemForegroundService"
            android:foregroundServiceType="dataSync"
            tools:node="merge" />
    </application>
</manifest>
```

Add to `app/src/main/res/values/strings.xml` inside `<resources>`:
```xml
    <string name="sync_channel">Sync</string>
    <string name="sync_notification_title">Syncing saved reels</string>
```

Replace `app/src/test/resources/robolectric.properties` with:
```properties
sdk=36
# Tests build their own dependencies; don't start ReelsApp (it would open the real database and WorkManager).
application=android.app.Application
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS. That covers the 8 new tests (`SyncControllerTest` 7, `FastPolicyGuardTest` 1) and every earlier test, including `PrivacyManifestTest`.

- [ ] **Step 7: Check that the app still builds and starts**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. On a running emulator or a connected phone, `./gradlew installDebug` then launch "Reels": the placeholder text appears and nothing crashes. `adb logcat -s AndroidRuntime` shows no exception.

- [ ] **Step 8: Record the components**

Append to the `## Components` table in `ARCHITECTURE.md`:
```markdown
| Sync control | `app/.../sync/SyncController.kt`, `SyncWorker.kt`, `SyncScheduler.kt` | Buttons resume the latest unfinished run or start one; unique WorkManager work (`KEEP`) means never two runs; foreground `dataSync` worker; orphaned RUNNING rows become PAUSED at app start. |
| Wiring | `app/.../di/`, `ReelsApp.kt` | `AppContainer` (hand-wired, lazy), `Backend.Fake` (fake client + placeholder fetcher + its own fast Pacer with in-memory budgets), custom `WorkerFactory`. A test keeps `PacingPolicy.Fast` confined to `Backend.kt`. |
```

- [ ] **Step 9: Commit**

```bash
git add app/src/main/kotlin/io/github/yuriimurha/reels/sync/SyncScheduler.kt app/src/main/kotlin/io/github/yuriimurha/reels/sync/SyncController.kt app/src/main/kotlin/io/github/yuriimurha/reels/sync/SyncWorker.kt app/src/main/kotlin/io/github/yuriimurha/reels/sync/SyncNotifications.kt app/src/main/kotlin/io/github/yuriimurha/reels/di app/src/main/kotlin/io/github/yuriimurha/reels/ReelsApp.kt app/src/main/AndroidManifest.xml app/src/main/res/values/strings.xml app/src/test/resources/robolectric.properties app/src/test/kotlin/io/github/yuriimurha/reels/sync/SyncControllerTest.kt app/src/test/kotlin/io/github/yuriimurha/reels/di ARCHITECTURE.md
git commit -m "feat(sync): controller, foreground worker and app wiring" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 11: UI shell, Home and collection grid

**Files:**
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/ui/theme/Theme.kt`
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/ui/LocalAppContainer.kt`
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/ui/ReelsNavHost.kt`
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/ui/common/Thumbnail.kt`
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/ui/common/SyncStatusSummary.kt`
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/ui/common/MediaGrid.kt`
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/ui/home/HomeViewModel.kt`
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/ui/home/HomeScreen.kt`
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/ui/grid/GridViewModel.kt`
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/ui/grid/GridScreen.kt`
- Modify: `app/src/main/kotlin/io/github/yuriimurha/reels/MainActivity.kt`
- Test: `app/src/test/kotlin/io/github/yuriimurha/reels/ui/common/SyncStatusSummaryTest.kt`
- Test: `app/src/test/kotlin/io/github/yuriimurha/reels/ui/home/HomeContentTest.kt`
- Test: `app/src/test/kotlin/io/github/yuriimurha/reels/ui/common/MediaGridTest.kt`
- Modify: `ARCHITECTURE.md`

**Interfaces:**
- Consumes: `AppContainer` (Task 10); `LibraryRepository`, `MediaSource`, `UNCATEGORIZED_ID` (Task 5); `CollectionCard`, `MediaEntity`, `SyncRunEntity`, `SyncStatus` (Task 4); `SyncController` (Task 10); `MediaType` (Task 3).
- Produces:
  - `ReelsTheme { }`, `LocalAppContainer`
  - Routes `HomeRoute`, `GridRoute(source: String, title: String)`, `ViewerRoute(source: String, index: Int)`, `SearchRoute`, `SyncRoute`, the constant `VIEWER_INDEX_KEY`, and `ReelsNavHost()`
  - `Thumbnail(path: String?, modifier, contentScale = Crop)`
  - `sealed interface SyncStatusSummary` (`Never`, `Running`, `Problem(text)`, `SyncedAt(at)`) with `SyncStatusSummary.from(run, lastSyncAt)` and `SyncStatusChip(status, onClick)`
  - `MediaGrid(items: LazyPagingItems<MediaEntity>, onOpen: (Int) -> Unit, modifier, state, contentPadding)` and `MediaTile(media: MediaEntity?, onClick)`. Tiles carry test tag `"tile"`.
  - `HomeContent(cards, status, onOpenCard, onOpenSearch, onOpenSync)`; card names carry test tag `"collection-name"`
  - `GridScreen(source, title, returnedIndex, onBack, onOpenViewer)`

- [ ] **Step 1: Write the failing tests**

`app/src/test/kotlin/io/github/yuriimurha/reels/ui/common/SyncStatusSummaryTest.kt`:
```kotlin
package io.github.yuriimurha.reels.ui.common

import io.github.yuriimurha.reels.data.db.SyncMode
import io.github.yuriimurha.reels.data.db.SyncRunEntity
import io.github.yuriimurha.reels.data.db.SyncStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncStatusSummaryTest {
    private fun run(status: SyncStatus, error: String? = null) =
        SyncRunEntity(mode = SyncMode.QUICK, status = status, startedAt = 0, lastError = error)

    @Test
    fun runningWins() = assertEquals(SyncStatusSummary.Running, SyncStatusSummary.from(run(SyncStatus.RUNNING), 5))

    @Test
    fun unfinishedRunsShowTheirReason() = assertEquals(
        SyncStatusSummary.Problem("Session expired"),
        SyncStatusSummary.from(run(SyncStatus.STOPPED_LOGIN, "Session expired"), 5),
    )

    @Test
    fun finishedRunsShowTheLastSync() {
        assertEquals(SyncStatusSummary.SyncedAt(5), SyncStatusSummary.from(run(SyncStatus.DONE), 5))
        assertEquals(SyncStatusSummary.SyncedAt(5), SyncStatusSummary.from(run(SyncStatus.CANCELLED), 5))
    }

    @Test
    fun nothingYet() = assertEquals(SyncStatusSummary.Never, SyncStatusSummary.from(null, null))
}
```

`app/src/test/kotlin/io/github/yuriimurha/reels/ui/home/HomeContentTest.kt`:
```kotlin
package io.github.yuriimurha.reels.ui.home

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.data.db.CollectionCard
import io.github.yuriimurha.reels.ui.common.SyncStatusSummary
import io.github.yuriimurha.reels.ui.theme.ReelsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class HomeContentTest {
    @get:Rule
    val compose = createComposeRule()

    private val cards = listOf(
        CollectionCard("__all__", "All Saved", 12, null),
        CollectionCard("uncategorized", "Uncategorized", 3, null),
        CollectionCard("c1", "Workouts", 9, null),
    )

    @Test
    fun cardsRenderInTheGivenOrder() {
        compose.setContent {
            ReelsTheme { HomeContent(cards, SyncStatusSummary.Never, onOpenCard = {}, onOpenSearch = {}, onOpenSync = {}) }
        }
        val names = compose.onAllNodesWithTag("collection-name").fetchSemanticsNodes()
            .map { node -> node.config[SemanticsProperties.Text].joinToString("") { it.text } }
        assertEquals(listOf("All Saved", "Uncategorized", "Workouts"), names)
    }

    @Test
    fun tappingACardOpensIt() {
        var opened: CollectionCard? = null
        compose.setContent {
            ReelsTheme { HomeContent(cards, SyncStatusSummary.Never, onOpenCard = { opened = it }, onOpenSearch = {}, onOpenSync = {}) }
        }
        compose.onNodeWithText("Workouts").performClick()
        assertEquals("c1", opened?.id)
    }

    @Test
    fun emptyLibraryPointsToSync() {
        var syncOpened = false
        compose.setContent {
            ReelsTheme {
                HomeContent(emptyList(), SyncStatusSummary.Never, onOpenCard = {}, onOpenSearch = {}, onOpenSync = { syncOpened = true })
            }
        }
        compose.onNodeWithText("Nothing synced yet").assertIsDisplayed()
        compose.onNodeWithText("Open Sync").performClick()
        assertEquals(true, syncOpened)
    }
}
```

`app/src/test/kotlin/io/github/yuriimurha/reels/ui/common/MediaGridTest.kt`:
```kotlin
package io.github.yuriimurha.reels.ui.common

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.testutil.mediaEntity
import io.github.yuriimurha.reels.ui.theme.ReelsTheme
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class MediaGridTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun tappingATileOpensItsIndex() {
        var opened = -1
        compose.setContent {
            ReelsTheme {
                val items = flowOf(PagingData.from(List(4) { mediaEntity("m$it", thumbPath = null) })).collectAsLazyPagingItems()
                MediaGrid(items, onOpen = { opened = it })
            }
        }
        compose.onAllNodesWithTag("tile")[2].performClick()
        assertEquals(2, opened)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.yuriimurha.reels.ui.*"`
Expected: FAIL to compile (`HomeContent`, `MediaGrid`, `SyncStatusSummary`, `ReelsTheme` don't exist).

- [ ] **Step 3: Write the theme, container local and shared composables**

`app/src/main/kotlin/io/github/yuriimurha/reels/ui/theme/Theme.kt`:
```kotlin
package io.github.yuriimurha.reels.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ReelsColors = darkColorScheme(
    primary = Color(0xFFF2C14E),
    onPrimary = Color(0xFF1A1300),
    secondary = Color(0xFF9AA4B2),
    background = Color(0xFF0B0B0C),
    onBackground = Color(0xFFECE8E1),
    surface = Color(0xFF141416),
    onSurface = Color(0xFFECE8E1),
    surfaceVariant = Color(0xFF1E1F22),
    onSurfaceVariant = Color(0xFFA9A49B),
    error = Color(0xFFFF6B6B),
)

/** Dark only (spec 9). */
@Composable
fun ReelsTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = ReelsColors, content = content)
```

`app/src/main/kotlin/io/github/yuriimurha/reels/ui/LocalAppContainer.kt`:
```kotlin
package io.github.yuriimurha.reels.ui

import androidx.compose.runtime.staticCompositionLocalOf
import io.github.yuriimurha.reels.di.AppContainer

val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }
```

`app/src/main/kotlin/io/github/yuriimurha/reels/ui/common/Thumbnail.kt`:
```kotlin
package io.github.yuriimurha.reels.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import java.io.File

/** A cached local thumbnail, or a quiet placeholder when there is none (spec 5.1: null renders a placeholder). */
@Composable
fun Thumbnail(path: String?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val placeholderColor = MaterialTheme.colorScheme.surfaceVariant
    if (path == null) {
        Box(modifier.background(placeholderColor))
    } else {
        val placeholder = ColorPainter(placeholderColor)
        AsyncImage(
            model = File(path),
            contentDescription = null,
            modifier = modifier,
            placeholder = placeholder,
            error = placeholder,
            contentScale = contentScale,
        )
    }
}
```

`app/src/main/kotlin/io/github/yuriimurha/reels/ui/common/SyncStatusSummary.kt`:
```kotlin
package io.github.yuriimurha.reels.ui.common

import android.text.format.DateUtils
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yuriimurha.reels.data.db.SyncRunEntity
import io.github.yuriimurha.reels.data.db.SyncStatus

sealed interface SyncStatusSummary {
    data object Never : SyncStatusSummary
    data object Running : SyncStatusSummary
    data class Problem(val text: String) : SyncStatusSummary
    data class SyncedAt(val at: Long) : SyncStatusSummary

    companion object {
        fun from(run: SyncRunEntity?, lastSyncAt: Long?): SyncStatusSummary = when {
            run?.status == SyncStatus.RUNNING -> Running
            run != null && run.status.isResumable -> Problem(run.lastError ?: "Sync paused")
            lastSyncAt != null -> SyncedAt(lastSyncAt)
            else -> Never
        }
    }
}

@Composable
fun SyncStatusChip(status: SyncStatusSummary, onClick: () -> Unit) {
    val label = when (status) {
        SyncStatusSummary.Never -> "Not synced"
        SyncStatusSummary.Running -> "Syncing…"
        is SyncStatusSummary.Problem -> "⚠ ${status.text}"
        is SyncStatusSummary.SyncedAt -> "Synced " +
            DateUtils.getRelativeTimeSpanString(status.at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
    }
    AssistChip(
        onClick = onClick,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = Modifier.widthIn(max = 200.dp),
    )
}
```

`app/src/main/kotlin/io/github/yuriimurha/reels/ui/common/MediaGrid.kt`:
```kotlin
package io.github.yuriimurha.reels.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import io.github.yuriimurha.reels.data.db.MediaEntity
import io.github.yuriimurha.reels.instagram.MediaType

/** Two-column staggered grid over a paged list (spec 9.2). [onOpen] receives the absolute index. */
@Composable
fun MediaGrid(
    items: LazyPagingItems<MediaEntity>,
    onOpen: (Int) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyStaggeredGridState = rememberLazyStaggeredGridState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(2),
        state = state,
        modifier = modifier,
        contentPadding = contentPadding,
        verticalItemSpacing = 12.dp,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(count = items.itemCount, key = items.itemKey { it.pk }) { index ->
            MediaTile(media = items[index], onClick = { onOpen(index) })
        }
    }
}

@Composable
fun MediaTile(media: MediaEntity?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val ratio = media?.let { (it.width.toFloat() / it.height).coerceIn(0.5f, 1f) } ?: (9f / 16f)
    Column(modifier.testTag("tile").clickable(enabled = media != null, onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(ratio).clip(RoundedCornerShape(10.dp))) {
            Thumbnail(media?.thumbPath, Modifier.matchParentSize())
            if (media != null) TypeBadge(media, Modifier.align(Alignment.TopEnd).padding(6.dp))
        }
        if (media != null) {
            Text(
                "@${media.author}",
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
            media.caption?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun TypeBadge(media: MediaEntity, modifier: Modifier = Modifier) {
    val label = when (media.type) {
        MediaType.REEL, MediaType.VIDEO -> "▶"
        MediaType.CAROUSEL -> "1/${media.carouselCount ?: 1}"
        MediaType.IMAGE -> return
    }
    Text(
        label,
        color = Color.White,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
```

- [ ] **Step 4: Write Home and the grid screen**

`app/src/main/kotlin/io/github/yuriimurha/reels/ui/home/HomeViewModel.kt`:
```kotlin
package io.github.yuriimurha.reels.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yuriimurha.reels.data.db.CollectionCard
import io.github.yuriimurha.reels.data.library.LibraryRepository
import io.github.yuriimurha.reels.sync.SyncController
import io.github.yuriimurha.reels.ui.common.SyncStatusSummary
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class HomeViewModel(library: LibraryRepository, sync: SyncController) : ViewModel() {
    /** Null until the first emission, so the screen doesn't flash the empty state. */
    val cards: StateFlow<List<CollectionCard>?> =
        library.collectionCards().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val status: StateFlow<SyncStatusSummary> =
        combine(sync.latestRun, sync.lastSyncAt) { run, last -> SyncStatusSummary.from(run, last) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncStatusSummary.Never)
}
```

`app/src/main/kotlin/io/github/yuriimurha/reels/ui/home/HomeScreen.kt`:
```kotlin
package io.github.yuriimurha.reels.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.yuriimurha.reels.data.db.CollectionCard
import io.github.yuriimurha.reels.data.library.MediaSource
import io.github.yuriimurha.reels.data.library.UNCATEGORIZED_ID
import io.github.yuriimurha.reels.ui.LocalAppContainer
import io.github.yuriimurha.reels.ui.common.SyncStatusChip
import io.github.yuriimurha.reels.ui.common.SyncStatusSummary
import io.github.yuriimurha.reels.ui.common.Thumbnail

@Composable
fun HomeScreen(
    onOpenSource: (MediaSource, String) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSync: () -> Unit,
) {
    val container = LocalAppContainer.current
    val viewModel = viewModel { HomeViewModel(container.library, container.syncController) }
    val cards by viewModel.cards.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    HomeContent(
        cards = cards,
        status = status,
        onOpenCard = { card -> onOpenSource(card.toSource(), card.name) },
        onOpenSearch = onOpenSearch,
        onOpenSync = onOpenSync,
    )
}

private fun CollectionCard.toSource(): MediaSource =
    if (id == UNCATEGORIZED_ID) MediaSource.Uncategorized else MediaSource.Collection(id)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(
    cards: List<CollectionCard>?,
    status: SyncStatusSummary,
    onOpenCard: (CollectionCard) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSync: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Saved") },
                actions = {
                    IconButton(onClick = onOpenSearch) { Icon(Icons.Default.Search, contentDescription = "Search") }
                    SyncStatusChip(status, onOpenSync)
                    Spacer(Modifier.width(8.dp))
                },
            )
        },
    ) { padding ->
        when {
            cards == null -> Unit
            cards.isEmpty() -> EmptyLibrary(onOpenSync, Modifier.padding(padding))
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = padding,
                modifier = Modifier.padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(cards, key = { it.id }) { card -> CollectionCardView(card, onClick = { onOpenCard(card) }) }
            }
        }
    }
}

@Composable
private fun CollectionCardView(card: CollectionCard, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Thumbnail(card.coverThumbPath, Modifier.fillMaxWidth().aspectRatio(4f / 5f).clip(RoundedCornerShape(14.dp)))
        Text(
            card.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp).testTag("collection-name"),
        )
        Text(
            if (card.count == 1) "1 item" else "${card.count} items",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyLibrary(onOpenSync: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Nothing synced yet", style = MaterialTheme.typography.titleMedium)
        Text(
            "Sync pulls your saved reels and collections onto this phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Button(onClick = onOpenSync, modifier = Modifier.padding(top = 16.dp)) { Text("Open Sync") }
    }
}
```

`app/src/main/kotlin/io/github/yuriimurha/reels/ui/grid/GridViewModel.kt`:
```kotlin
package io.github.yuriimurha.reels.ui.grid

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import io.github.yuriimurha.reels.data.db.MediaEntity
import io.github.yuriimurha.reels.data.library.LibraryRepository
import io.github.yuriimurha.reels.data.library.MediaSource
import kotlinx.coroutines.flow.Flow

class GridViewModel(source: MediaSource, library: LibraryRepository) : ViewModel() {
    val items: Flow<PagingData<MediaEntity>> = library.pager(source).cachedIn(viewModelScope)
}
```

`app/src/main/kotlin/io/github/yuriimurha/reels/ui/grid/GridScreen.kt`:
```kotlin
package io.github.yuriimurha.reels.ui.grid

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.compose.collectAsLazyPagingItems
import io.github.yuriimurha.reels.data.library.MediaSource
import io.github.yuriimurha.reels.ui.LocalAppContainer
import io.github.yuriimurha.reels.ui.common.MediaGrid

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GridScreen(
    source: MediaSource,
    title: String,
    returnedIndex: Int?,
    onBack: () -> Unit,
    onOpenViewer: (Int) -> Unit,
) {
    val container = LocalAppContainer.current
    val viewModel = viewModel(key = "grid:${source.encode()}") { GridViewModel(source, container.library) }
    val items = viewModel.items.collectAsLazyPagingItems()
    val gridState = rememberLazyStaggeredGridState()
    LaunchedEffect(returnedIndex) {
        if (returnedIndex != null) gridState.scrollToItem(returnedIndex)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        MediaGrid(
            items = items,
            onOpen = onOpenViewer,
            modifier = Modifier.padding(horizontal = 8.dp),
            state = gridState,
            contentPadding = padding,
        )
    }
}
```

- [ ] **Step 5: Write navigation and wire the activity**

`app/src/main/kotlin/io/github/yuriimurha/reels/ui/ReelsNavHost.kt`:
```kotlin
package io.github.yuriimurha.reels.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.yuriimurha.reels.data.library.MediaSource
import io.github.yuriimurha.reels.ui.grid.GridScreen
import io.github.yuriimurha.reels.ui.home.HomeScreen
import kotlinx.serialization.Serializable

@Serializable
data object HomeRoute

@Serializable
data class GridRoute(val source: String, val title: String)

@Serializable
data class ViewerRoute(val source: String, val index: Int)

@Serializable
data object SearchRoute

@Serializable
data object SyncRoute

/** The viewer writes its current index here on the previous entry, so the grid scrolls back to it. */
const val VIEWER_INDEX_KEY = "viewerIndex"

@Composable
fun ReelsNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = HomeRoute) {
        composable<HomeRoute> {
            HomeScreen(
                onOpenSource = { source, title -> navController.navigate(GridRoute(source.encode(), title)) },
                onOpenSearch = {},
                onOpenSync = {},
            )
        }
        composable<GridRoute> { entry ->
            val route = entry.toRoute<GridRoute>()
            val returnedIndex by entry.savedStateHandle.getStateFlow<Int?>(VIEWER_INDEX_KEY, null)
                .collectAsStateWithLifecycle()
            GridScreen(
                source = MediaSource.decode(route.source),
                title = route.title,
                returnedIndex = returnedIndex,
                onBack = { navController.popBackStack() },
                onOpenViewer = {},
            )
        }
    }
}
```
The empty `onOpenSearch`, `onOpenSync` and `onOpenViewer` lambdas get wired in Tasks 13, 14 and 12, when those destinations exist. Navigating to an unregistered route would crash, so they stay empty until then.

Replace `app/src/main/kotlin/io/github/yuriimurha/reels/MainActivity.kt` with:
```kotlin
package io.github.yuriimurha.reels

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import io.github.yuriimurha.reels.ui.LocalAppContainer
import io.github.yuriimurha.reels.ui.ReelsNavHost
import io.github.yuriimurha.reels.ui.theme.ReelsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val container = (application as ReelsApp).container
        setContent {
            ReelsTheme {
                CompositionLocalProvider(LocalAppContainer provides container) { ReelsNavHost() }
            }
        }
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.yuriimurha.reels.ui.*"`
Expected: PASS (8 tests).

- [ ] **Step 7: Look at it**

Run: `./gradlew installDebug`, then launch Reels on the emulator or phone.
Expected: a dark "Saved" screen with "Nothing synced yet" and an "Open Sync" button (which does nothing until Task 14). The status chip reads "Not synced".

- [ ] **Step 8: Record the component**

Append to the `## Components` table in `ARCHITECTURE.md`:
```markdown
| UI shell | `app/.../ui/` | Dark Material 3 theme, type-safe Navigation Compose routes (`MediaSource` encoded into routes), `LocalAppContainer`. Home: collection cards (All Saved, Uncategorized, collections) and a sync status chip. Grid: two-column staggered Paging grid with real aspect ratios and type badges. |
```

- [ ] **Step 9: Commit**

```bash
git add app/src/main/kotlin/io/github/yuriimurha/reels/ui app/src/main/kotlin/io/github/yuriimurha/reels/MainActivity.kt app/src/test/kotlin/io/github/yuriimurha/reels/ui ARCHITECTURE.md
git commit -m "feat(ui): dark shell with collections home and staggered grid" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 12: Viewer

**Files:**
- Create (generated): `app/src/main/res/raw/sample_clip.mp4`
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/data/media/VideoSourceResolver.kt`
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/ui/viewer/ViewerViewModel.kt`
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/ui/viewer/ViewerScreen.kt`
- Modify: `app/src/main/kotlin/io/github/yuriimurha/reels/di/AppContainer.kt`
- Modify: `app/src/main/kotlin/io/github/yuriimurha/reels/ui/ReelsNavHost.kt`
- Test: `app/src/test/kotlin/io/github/yuriimurha/reels/data/media/FakeVideoSourceResolverTest.kt`
- Test: `app/src/test/kotlin/io/github/yuriimurha/reels/ui/viewer/ViewerPageTest.kt`
- Modify: `ARCHITECTURE.md`

**Interfaces:**
- Consumes: `LibraryRepository`, `MediaSource` (Task 5); `MediaEntity` (Task 4); `SettingsStore.muted`/`setMuted` (Task 7); `Thumbnail`, `ReelsTheme`, `LocalAppContainer`, `ViewerRoute`, `VIEWER_INDEX_KEY` (Task 11); `Permalinks`, `MediaType` (Task 3).
- Produces:
  - `fun interface VideoSourceResolver { suspend fun resolve(media: MediaEntity): Uri? }` and `FakeVideoSourceResolver(packageName)`. M5 replaces it with the real resolver (link refresh + `pk`-keyed cache).
  - `AppContainer.videoResolver`
  - `ViewerScreen(source, startIndex, onIndexSettled, onBack)` and `ViewerPage(media, player, collections, muted, onToggleMute, onTogglePlay, onOpenInstagram)`

- [ ] **Step 1: Generate the sample clip**

Run:
```bash
mkdir -p app/src/main/res/raw
ffmpeg -y -f lavfi -i testsrc2=size=540x960:rate=30 -f lavfi -i sine=frequency=330:sample_rate=44100 -t 6 -c:v libx264 -profile:v baseline -pix_fmt yuv420p -crf 30 -c:a aac -b:a 64k -shortest -movflags +faststart app/src/main/res/raw/sample_clip.mp4
ls -lh app/src/main/res/raw/sample_clip.mp4
```
Expected: the file exists and is under 1 MB. It's a synthetic test pattern with a tone, so there's no third-party content.

- [ ] **Step 2: Write the failing tests**

`app/src/test/kotlin/io/github/yuriimurha/reels/data/media/FakeVideoSourceResolverTest.kt`:
```kotlin
package io.github.yuriimurha.reels.data.media

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.R
import io.github.yuriimurha.reels.instagram.MediaType
import io.github.yuriimurha.reels.testutil.mediaEntity
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
class FakeVideoSourceResolverTest {
    private val resolver = FakeVideoSourceResolver("io.github.yuriimurha.reels")

    @Test
    fun videosResolveToTheBundledClip() = runTest {
        assertEquals(
            "android.resource://io.github.yuriimurha.reels/${R.raw.sample_clip}",
            resolver.resolve(mediaEntity("m1", MediaType.REEL)).toString(),
        )
        assertEquals(
            "android.resource://io.github.yuriimurha.reels/${R.raw.sample_clip}",
            resolver.resolve(mediaEntity("m2", MediaType.VIDEO)).toString(),
        )
    }

    @Test
    fun imagesHaveNoVideo() = runTest {
        assertNull(resolver.resolve(mediaEntity("m3", MediaType.IMAGE)))
        assertNull(resolver.resolve(mediaEntity("m4", MediaType.CAROUSEL)))
    }
}
```

`app/src/test/kotlin/io/github/yuriimurha/reels/ui/viewer/ViewerPageTest.kt`:
```kotlin
package io.github.yuriimurha.reels.ui.viewer

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.data.db.MediaEntity
import io.github.yuriimurha.reels.instagram.MediaType
import io.github.yuriimurha.reels.testutil.mediaEntity
import io.github.yuriimurha.reels.ui.theme.ReelsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class ViewerPageTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(media: MediaEntity, onOpenInstagram: (String) -> Unit = {}) = compose.setContent {
        ReelsTheme {
            ViewerPage(
                media = media,
                player = null,
                collections = listOf("Workouts"),
                muted = false,
                onToggleMute = {},
                onTogglePlay = {},
                onOpenInstagram = onOpenInstagram,
            )
        }
    }

    @Test
    fun itemWithoutAThumbnailShowsAPlaceholder() {
        show(mediaEntity("m1", MediaType.IMAGE, thumbPath = null))
        compose.onNodeWithText("Not available on Instagram").assertIsDisplayed()
    }

    @Test
    fun openOnInstagramUsesThePermalink() {
        var opened: String? = null
        show(mediaEntity("m1", MediaType.REEL)) { opened = it }
        compose.onNodeWithText("Open on Instagram").performClick()
        assertEquals("https://www.instagram.com/reel/Cm1/", opened)
    }

    @Test
    fun overlayShowsAuthorAndCollections() {
        show(mediaEntity("m1", author = "chef_anna"))
        compose.onNodeWithText("@chef_anna").assertIsDisplayed()
        compose.onNodeWithText("Workouts").assertIsDisplayed()
    }

    @Test
    fun imagesHaveNoMuteButton() {
        show(mediaEntity("m1", MediaType.IMAGE))
        compose.onAllNodesWithText("Mute").assertCountEquals(0)
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.yuriimurha.reels.data.media.FakeVideoSourceResolverTest" --tests "io.github.yuriimurha.reels.ui.viewer.*"`
Expected: FAIL to compile.

- [ ] **Step 4: Implement the resolver**

`app/src/main/kotlin/io/github/yuriimurha/reels/data/media/VideoSourceResolver.kt`:
```kotlin
package io.github.yuriimurha.reels.data.media

import android.net.Uri
import io.github.yuriimurha.reels.R
import io.github.yuriimurha.reels.data.db.MediaEntity
import io.github.yuriimurha.reels.instagram.MediaType

/** A playable URI for an item, or null when it can't play right now (spec 8). */
fun interface VideoSourceResolver {
    suspend fun resolve(media: MediaEntity): Uri?
}

/** Every fake video plays the bundled synthetic clip. */
class FakeVideoSourceResolver(private val packageName: String) : VideoSourceResolver {
    override suspend fun resolve(media: MediaEntity): Uri? = when (media.type) {
        MediaType.REEL, MediaType.VIDEO -> Uri.parse("android.resource://$packageName/${R.raw.sample_clip}")
        MediaType.IMAGE, MediaType.CAROUSEL -> null
    }
}
```

In `app/src/main/kotlin/io/github/yuriimurha/reels/di/AppContainer.kt`, add the import `io.github.yuriimurha.reels.data.media.FakeVideoSourceResolver` and `io.github.yuriimurha.reels.data.media.VideoSourceResolver`, and add this property below `syncController`:
```kotlin
    /** M5 replaces this with the real resolver (link refresh on the interactive lane, pk-keyed cache). */
    val videoResolver: VideoSourceResolver by lazy { FakeVideoSourceResolver(context.packageName) }
```

- [ ] **Step 5: Implement the viewer**

`app/src/main/kotlin/io/github/yuriimurha/reels/ui/viewer/ViewerViewModel.kt`:
```kotlin
package io.github.yuriimurha.reels.ui.viewer

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import io.github.yuriimurha.reels.data.db.MediaEntity
import io.github.yuriimurha.reels.data.library.LibraryRepository
import io.github.yuriimurha.reels.data.library.MediaSource
import io.github.yuriimurha.reels.data.media.VideoSourceResolver
import io.github.yuriimurha.reels.data.settings.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ViewerViewModel(
    source: MediaSource,
    startIndex: Int,
    private val library: LibraryRepository,
    private val resolver: VideoSourceResolver,
    private val settings: SettingsStore,
) : ViewModel() {
    val items: Flow<PagingData<MediaEntity>> = library.pager(source, initialIndex = startIndex).cachedIn(viewModelScope)
    val muted: Flow<Boolean> = settings.muted

    fun toggleMute() {
        viewModelScope.launch { settings.setMuted(!settings.muted.first()) }
    }

    suspend fun videoUri(media: MediaEntity): Uri? = resolver.resolve(media)

    suspend fun collectionNames(pk: String): List<String> = library.collectionsOf(pk).map { it.name }
}
```

`app/src/main/kotlin/io/github/yuriimurha/reels/ui/viewer/ViewerScreen.kt`:
```kotlin
package io.github.yuriimurha.reels.ui.viewer

import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.ContentFrame
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import io.github.yuriimurha.reels.data.db.MediaEntity
import io.github.yuriimurha.reels.data.library.MediaSource
import io.github.yuriimurha.reels.instagram.MediaType
import io.github.yuriimurha.reels.instagram.Permalinks
import io.github.yuriimurha.reels.ui.LocalAppContainer
import io.github.yuriimurha.reels.ui.common.Thumbnail
import kotlinx.coroutines.flow.distinctUntilChanged

/** Full-screen vertical pager over the same list the grid showed (spec 9.3). One player is reused across pages. */
@Composable
fun ViewerScreen(source: MediaSource, startIndex: Int, onIndexSettled: (Int) -> Unit, onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val viewModel = viewModel(key = "viewer:${source.encode()}:$startIndex") {
        ViewerViewModel(source, startIndex, container.library, container.videoResolver, container.settings)
    }
    val items = viewModel.items.collectAsLazyPagingItems()
    val muted by viewModel.muted.collectAsStateWithLifecycle(initialValue = false)
    val pagerState = rememberPagerState(initialPage = startIndex) { items.itemCount }
    val player = rememberViewerPlayer()
    var playingPk by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(muted) { player.volume = if (muted) 0f else 1f }
    LaunchedEffect(pagerState, items) {
        snapshotFlow { pagerState.settledPage to items.itemSnapshotList.getOrNull(pagerState.settledPage) }
            .distinctUntilChanged { a, b -> a.first == b.first && a.second?.pk == b.second?.pk }
            .collect { (page, media) ->
                onIndexSettled(page)
                player.stop()
                player.clearMediaItems()
                playingPk = null
                val uri = media?.let { viewModel.videoUri(it) } ?: return@collect
                player.setMediaItem(MediaItem.fromUri(uri))
                player.prepare()
                player.play()
                playingPk = media.pk
            }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        VerticalPager(
            state = pagerState,
            key = items.itemKey { it.pk },
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val media = items[page]
            val collections by produceState(emptyList<String>(), media?.pk) {
                value = media?.let { viewModel.collectionNames(it.pk) }.orEmpty()
            }
            ViewerPage(
                media = media,
                player = player.takeIf { media != null && media.pk == playingPk },
                collections = collections,
                muted = muted,
                onToggleMute = viewModel::toggleMute,
                onTogglePlay = { if (player.isPlaying) player.pause() else player.play() },
                onOpenInstagram = { url -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
            )
        }
        IconButton(onClick = onBack, modifier = Modifier.statusBarsPadding().padding(8.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
        }
    }
}

@Composable
private fun rememberViewerPlayer(): ExoPlayer {
    val context = LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build().apply { repeatMode = Player.REPEAT_MODE_ONE } }
    DisposableEffect(player) { onDispose { player.release() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.pause() }
    return player
}

/** One page. [player] is non-null only for the page that is currently playing. */
@OptIn(UnstableApi::class)
@Composable
fun ViewerPage(
    media: MediaEntity?,
    player: Player?,
    collections: List<String>,
    muted: Boolean,
    onToggleMute: () -> Unit,
    onTogglePlay: () -> Unit,
    onOpenInstagram: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().background(Color.Black)) {
        if (media == null) return@Box
        val isVideo = media.type == MediaType.REEL || media.type == MediaType.VIDEO
        when {
            player != null -> ContentFrame(
                player = player,
                modifier = Modifier.fillMaxSize().clickable(onClick = onTogglePlay),
                contentScale = ContentScale.Fit,
                shutter = { Thumbnail(media.thumbPath, Modifier.fillMaxSize(), ContentScale.Fit) },
            )
            media.thumbPath != null -> Thumbnail(media.thumbPath, Modifier.fillMaxSize(), ContentScale.Fit)
            else -> Text(
                "Not available on Instagram",
                color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.Center),
            )
        }
        Overlay(media, collections, isVideo, muted, onToggleMute, onOpenInstagram, Modifier.align(Alignment.BottomStart))
    }
}

@Composable
private fun Overlay(
    media: MediaEntity,
    collections: List<String>,
    isVideo: Boolean,
    muted: Boolean,
    onToggleMute: () -> Unit,
    onOpenInstagram: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(media.pk) { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
            .navigationBarsPadding()
            .padding(16.dp),
    ) {
        Text("@${media.author}", color = Color.White, style = MaterialTheme.typography.titleSmall)
        media.caption?.let { caption ->
            Text(
                caption,
                color = Color.White.copy(alpha = 0.9f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp).clickable { expanded = !expanded },
            )
        }
        if (collections.isNotEmpty()) {
            Row(
                Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                collections.forEach { name ->
                    Text(
                        name,
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(50))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onOpenInstagram(Permalinks.of(media.type, media.code)) }) { Text("Open on Instagram") }
            if (isVideo) TextButton(onClick = onToggleMute) { Text(if (muted) "Unmute" else "Mute") }
        }
    }
}
```

- [ ] **Step 6: Register the viewer route**

In `app/src/main/kotlin/io/github/yuriimurha/reels/ui/ReelsNavHost.kt`, add the import `io.github.yuriimurha.reels.ui.viewer.ViewerScreen`, then in the `composable<GridRoute>` block replace `onOpenViewer = {},` with:
```kotlin
                onOpenViewer = { index -> navController.navigate(ViewerRoute(route.source, index)) },
```
and add this destination after the `composable<GridRoute>` block:
```kotlin
        composable<ViewerRoute> { entry ->
            val route = entry.toRoute<ViewerRoute>()
            ViewerScreen(
                source = MediaSource.decode(route.source),
                startIndex = route.index,
                onIndexSettled = { index ->
                    navController.previousBackStackEntry?.savedStateHandle?.set(VIEWER_INDEX_KEY, index)
                },
                onBack = { navController.popBackStack() },
            )
        }
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.yuriimurha.reels.data.media.*" --tests "io.github.yuriimurha.reels.ui.*"`
Expected: PASS. That's 6 new tests, plus the earlier media and UI tests.

- [ ] **Step 8: Record the component**

Append to the `## Components` table in `ARCHITECTURE.md`:
```markdown
| Viewer | `app/.../ui/viewer/` | Vertical pager over the grid's paged list; one reused ExoPlayer (Media3 `ContentFrame`, thumbnail as shutter), loop, remembered mute, author/caption/collection overlay, "Open on Instagram" via `Permalinks`. Videos come from `VideoSourceResolver` (fake: a bundled synthetic clip). |
```

- [ ] **Step 9: Commit**

```bash
git add app/src/main/res/raw/sample_clip.mp4 app/src/main/kotlin/io/github/yuriimurha/reels/data/media/VideoSourceResolver.kt app/src/main/kotlin/io/github/yuriimurha/reels/ui/viewer app/src/main/kotlin/io/github/yuriimurha/reels/di/AppContainer.kt app/src/main/kotlin/io/github/yuriimurha/reels/ui/ReelsNavHost.kt app/src/test/kotlin/io/github/yuriimurha/reels/data/media/FakeVideoSourceResolverTest.kt app/src/test/kotlin/io/github/yuriimurha/reels/ui/viewer ARCHITECTURE.md
git commit -m "feat(ui): full-screen viewer with one reused player" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 13: Search

**Files:**
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/ui/search/SearchViewModel.kt`
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/ui/search/SearchScreen.kt`
- Modify: `app/src/main/kotlin/io/github/yuriimurha/reels/ui/ReelsNavHost.kt`
- Test: `app/src/test/kotlin/io/github/yuriimurha/reels/ui/search/SearchViewModelTest.kt`
- Modify: `ARCHITECTURE.md`

**Interfaces:**
- Consumes: `LibraryRepository`, `FtsQuery`, `MediaSource`, `TypeFilter` (Task 5); `ALL_SAVED_ID`, `CollectionEntity`, `MediaEntity` (Task 4); `MediaGrid` (Task 11); `ViewerRoute`, `SearchRoute` (Task 11).
- Produces: `SearchViewModel(library)` with `query`, `filter`, `scope` (`MutableStateFlow`s), `collections`, `source: StateFlow<MediaSource.Search?>`, `results`; and `SearchScreen(onBack, onOpenViewer: (MediaSource, Int) -> Unit)`.

- [ ] **Step 1: Write the failing test**

`app/src/test/kotlin/io/github/yuriimurha/reels/ui/search/SearchViewModelTest.kt`:
```kotlin
package io.github.yuriimurha.reels.ui.search

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.data.db.ALL_SAVED_ID
import io.github.yuriimurha.reels.data.library.LibraryRepository
import io.github.yuriimurha.reels.data.library.MediaSource
import io.github.yuriimurha.reels.data.library.TypeFilter
import io.github.yuriimurha.reels.testutil.inMemoryDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SearchViewModelTest {
    private val db = inMemoryDb()
    private val viewModel by lazy { SearchViewModel(LibraryRepository(db)) }

    @Before
    fun setMain() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    @Test
    fun typingProducesASearchAfterTheDebounce() = runTest {
        viewModel.query.value = "Leg day"
        advanceTimeBy(199)
        runCurrent()
        assertNull(viewModel.source.value)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(MediaSource.Search("leg* day*", TypeFilter.ALL, ALL_SAVED_ID), viewModel.source.value)
    }

    @Test
    fun nothingSearchableGivesNoSearch() = runTest {
        viewModel.query.value = "\"-*"
        advanceUntilIdle()
        assertNull(viewModel.source.value)
    }

    @Test
    fun filterAndScopeAreApplied() = runTest {
        viewModel.query.value = "pasta"
        viewModel.filter.value = TypeFilter.REELS
        viewModel.scope.value = "c2"
        advanceUntilIdle()
        assertEquals(MediaSource.Search("pasta*", TypeFilter.REELS, "c2"), viewModel.source.value)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.yuriimurha.reels.ui.search.*"`
Expected: FAIL to compile (`SearchViewModel` doesn't exist).

- [ ] **Step 3: Implement**

`app/src/main/kotlin/io/github/yuriimurha/reels/ui/search/SearchViewModel.kt`:
```kotlin
package io.github.yuriimurha.reels.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import io.github.yuriimurha.reels.data.db.ALL_SAVED_ID
import io.github.yuriimurha.reels.data.db.CollectionEntity
import io.github.yuriimurha.reels.data.db.MediaEntity
import io.github.yuriimurha.reels.data.library.FtsQuery
import io.github.yuriimurha.reels.data.library.LibraryRepository
import io.github.yuriimurha.reels.data.library.MediaSource
import io.github.yuriimurha.reels.data.library.TypeFilter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(private val library: LibraryRepository) : ViewModel() {
    val query = MutableStateFlow("")
    val filter = MutableStateFlow(TypeFilter.ALL)
    val scope = MutableStateFlow(ALL_SAVED_ID)

    val collections: StateFlow<List<CollectionEntity>> =
        library.liveCollections().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The current search, or null when the text has nothing searchable. Typing is debounced by 200 ms (spec 9.4). */
    val source: StateFlow<MediaSource.Search?> =
        combine(query.debounce(200), filter, scope) { text, type, inScope ->
            FtsQuery.from(text)?.let { MediaSource.Search(it, type, inScope) }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val results: Flow<PagingData<MediaEntity>> =
        source.flatMapLatest { search -> if (search == null) flowOf(PagingData.empty()) else library.pager(search) }
            .cachedIn(viewModelScope)
}
```

`app/src/main/kotlin/io/github/yuriimurha/reels/ui/search/SearchScreen.kt`:
```kotlin
package io.github.yuriimurha.reels.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import io.github.yuriimurha.reels.data.db.ALL_SAVED_ID
import io.github.yuriimurha.reels.data.library.MediaSource
import io.github.yuriimurha.reels.data.library.TypeFilter
import io.github.yuriimurha.reels.ui.LocalAppContainer
import io.github.yuriimurha.reels.ui.common.MediaGrid

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(onBack: () -> Unit, onOpenViewer: (MediaSource, Int) -> Unit) {
    val container = LocalAppContainer.current
    val viewModel = viewModel { SearchViewModel(container.library) }
    val query by viewModel.query.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val scope by viewModel.scope.collectAsStateWithLifecycle()
    val collections by viewModel.collections.collectAsStateWithLifecycle()
    val source by viewModel.source.collectAsStateWithLifecycle()
    val results = viewModel.results.collectAsLazyPagingItems()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                title = {
                    TextField(
                        value = query,
                        onValueChange = { viewModel.query.value = it },
                        placeholder = { Text("Captions, authors, collections") },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(TypeFilter.entries) { type ->
                    FilterChip(selected = filter == type, onClick = { viewModel.filter.value = type }, label = { Text(type.label) })
                }
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(
                        selected = scope == ALL_SAVED_ID,
                        onClick = { viewModel.scope.value = ALL_SAVED_ID },
                        label = { Text("All Saved") },
                    )
                }
                items(collections, key = { it.id }) { collection ->
                    FilterChip(
                        selected = scope == collection.id,
                        onClick = { viewModel.scope.value = collection.id },
                        label = { Text(collection.name) },
                    )
                }
            }
            val current = source
            when {
                current == null -> Hint("Search captions, authors and collections")
                results.itemCount == 0 && results.loadState.refresh is LoadState.NotLoading -> Hint("No matches")
                else -> MediaGrid(
                    items = results,
                    onOpen = { index -> onOpenViewer(current, index) },
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
    }
}

private val TypeFilter.label: String
    get() = when (this) {
        TypeFilter.ALL -> "All"
        TypeFilter.REELS -> "Reels"
        TypeFilter.POSTS -> "Posts"
    }

@Composable
private fun Hint(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
```

- [ ] **Step 4: Register the search route**

In `app/src/main/kotlin/io/github/yuriimurha/reels/ui/ReelsNavHost.kt`, add the import `io.github.yuriimurha.reels.ui.search.SearchScreen`. In the `composable<HomeRoute>` block, replace `onOpenSearch = {},` with:
```kotlin
                onOpenSearch = { navController.navigate(SearchRoute) },
```
and add this destination:
```kotlin
        composable<SearchRoute> {
            SearchScreen(
                onBack = { navController.popBackStack() },
                onOpenViewer = { source, index -> navController.navigate(ViewerRoute(source.encode(), index)) },
            )
        }
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.yuriimurha.reels.ui.search.*"`
Expected: PASS (3 tests).

- [ ] **Step 6: Record the component and commit**

Append to the `## Components` table in `ARCHITECTURE.md`:
```markdown
| Search | `app/.../ui/search/` | 200 ms debounced FTS search over caption, author and collection names; Reels/Posts and collection chips; results in the shared grid, opening the viewer on the same `MediaSource.Search`. |
```

```bash
git add app/src/main/kotlin/io/github/yuriimurha/reels/ui/search app/src/main/kotlin/io/github/yuriimurha/reels/ui/ReelsNavHost.kt app/src/test/kotlin/io/github/yuriimurha/reels/ui/search ARCHITECTURE.md
git commit -m "feat(ui): debounced search with type and collection filters" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 14: Sync screen and M1 close

**Files:**
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/ui/sync/SyncUiState.kt`
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/ui/sync/SyncViewModel.kt`
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/ui/sync/SyncScreen.kt`
- Modify: `app/src/main/kotlin/io/github/yuriimurha/reels/data/library/LibraryRepository.kt`
- Modify: `app/src/main/kotlin/io/github/yuriimurha/reels/di/AppContainer.kt`
- Modify: `app/src/main/kotlin/io/github/yuriimurha/reels/ui/ReelsNavHost.kt`
- Modify: `app/src/test/kotlin/io/github/yuriimurha/reels/data/library/LibraryRepositoryTest.kt`
- Test: `app/src/test/kotlin/io/github/yuriimurha/reels/ui/sync/SyncUiStateTest.kt`
- Test: `app/src/test/kotlin/io/github/yuriimurha/reels/ui/sync/SyncContentTest.kt`
- Modify: `ARCHITECTURE.md`, `PROGRESS.md`, `TODO.md`

**Interfaces:**
- Consumes: `SyncController` (Task 10); `Pacer.status()`, `PacerStatus` (Task 6); `SyncRunEntity`, `SyncStatus`, `SyncMode` (Task 4); `ThumbnailStore` (Task 8); `LibraryRepository` (Task 5); `SyncRoute` (Task 11).
- Produces:
  - `LibraryRepository(db, thumbnails)` with `deleteLibrary()`
  - `data class SyncUiState(running, resumable, canStart, canCancel, canDiscard, canDeleteLibrary, banner: String?)` and `fun syncUiState(run: SyncRunEntity?, pacer: PacerStatus?, now: Long): SyncUiState`
  - `SyncViewModel(controller, library, pacer, now)` and `SyncScreen(onBack)`. Task 18 adds the session section.
  - `SyncContent(run, ui, pacer, lastSyncAt, lastFullSyncAt, onSync, onFullSync, onCancel, onDiscard, onDeleteLibrary, modifier, sessionSection: @Composable () -> Unit = {})`

- [ ] **Step 1: Write the failing tests**

`app/src/test/kotlin/io/github/yuriimurha/reels/ui/sync/SyncUiStateTest.kt`:
```kotlin
package io.github.yuriimurha.reels.ui.sync

import io.github.yuriimurha.reels.data.db.SyncMode
import io.github.yuriimurha.reels.data.db.SyncRunEntity
import io.github.yuriimurha.reels.data.db.SyncStatus
import io.github.yuriimurha.reels.sync.pacing.PacerStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncUiStateTest {
    private val now = 1_000_000L

    private fun run(status: SyncStatus, error: String? = null) =
        SyncRunEntity(mode = SyncMode.FULL, status = status, startedAt = 0, lastError = error)

    private fun pacer(cooldownUntil: Long? = null) = PacerStatus(10, 600, 300, cooldownUntil)

    @Test
    fun nothingYet() {
        val ui = syncUiState(null, pacer(), now)
        assertTrue(ui.canStart)
        assertFalse(ui.resumable)
        assertNull(ui.banner)
    }

    @Test
    fun runningAllowsOnlyCancel() {
        val ui = syncUiState(run(SyncStatus.RUNNING), pacer(), now)
        assertFalse(ui.canStart)
        assertTrue(ui.canCancel)
        assertFalse(ui.canDiscard)
        assertFalse(ui.canDeleteLibrary)
    }

    @Test
    fun pausedRunOffersResumeAndDiscardWithItsReason() {
        val ui = syncUiState(run(SyncStatus.PAUSED, "24-hour budget reached"), pacer(), now)
        assertTrue(ui.resumable)
        assertTrue(ui.canStart)
        assertTrue(ui.canDiscard)
        assertEquals("24-hour budget reached", ui.banner)
    }

    @Test
    fun cooldownBlocksStartingAndCountsDown() {
        val ui = syncUiState(run(SyncStatus.STOPPED_RATE_LIMIT), pacer(cooldownUntil = now + 90_000), now)
        assertFalse(ui.canStart)
        assertEquals("Cooling down after a rate limit: 2 min left", ui.banner)
    }

    @Test
    fun stoppedRunsExplainWhatToDo() {
        assertEquals(
            "Instagram wants verification. Resolve it before syncing again.",
            syncUiState(run(SyncStatus.STOPPED_CHALLENGE), pacer(), now).banner,
        )
        assertEquals(
            "Session expired. Log in again, then tap Resume.",
            syncUiState(run(SyncStatus.STOPPED_LOGIN), pacer(), now).banner,
        )
        assertEquals(
            "Adapter needs repair: items[0].code",
            syncUiState(run(SyncStatus.STOPPED_SHAPE, "Adapter needs repair: items[0].code"), pacer(), now).banner,
        )
    }
}
```

`app/src/test/kotlin/io/github/yuriimurha/reels/ui/sync/SyncContentTest.kt`:
```kotlin
package io.github.yuriimurha.reels.ui.sync

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.data.db.SyncMode
import io.github.yuriimurha.reels.data.db.SyncRunEntity
import io.github.yuriimurha.reels.data.db.SyncStatus
import io.github.yuriimurha.reels.ui.theme.ReelsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncContentTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(run: SyncRunEntity?) = compose.setContent {
        ReelsTheme {
            SyncContent(
                run = run,
                ui = syncUiState(run, null, 0),
                pacer = null,
                lastSyncAt = null,
                lastFullSyncAt = null,
                onSync = {}, onFullSync = {}, onCancel = {}, onDiscard = {}, onDeleteLibrary = {},
            )
        }
    }

    @Test
    fun freshStateOffersBothModes() {
        show(null)
        compose.onNodeWithText("Sync").assertIsDisplayed()
        compose.onNodeWithText("Full sync").assertIsDisplayed()
    }

    @Test
    fun resumableRunOffersResumeAndDiscardOnly() {
        show(SyncRunEntity(mode = SyncMode.FULL, status = SyncStatus.PAUSED, startedAt = 0, lastError = "Cancelled"))
        compose.onNodeWithText("Resume").assertIsDisplayed()
        compose.onNodeWithText("Discard paused run").assertIsDisplayed()
        compose.onAllNodesWithText("Full sync").assertCountEquals(0)
    }
}
```

In `app/src/test/kotlin/io/github/yuriimurha/reels/data/library/LibraryRepositoryTest.kt`:
- add the imports `io.github.yuriimurha.reels.data.media.ThumbnailStore`, `org.junit.Rule`, `org.junit.rules.TemporaryFolder`, `java.io.File`, `kotlin.test.assertFalse`
- replace `private val repository = LibraryRepository(db)` with:
```kotlin
    @get:Rule
    val tmp = TemporaryFolder()

    private val thumbs by lazy { ThumbnailStore(File(tmp.root, "thumbs")) }
    private val repository by lazy { LibraryRepository(db, thumbs) }
```
- add this test:
```kotlin
    @Test
    fun deleteLibraryRemovesRowsAndThumbnails() = runTest {
        givenLibrary()
        val path = thumbs.write("m1", byteArrayOf(1))
        repository.deleteLibrary()
        assertEquals(emptyList(), repository.collectionCards().first())
        assertFalse(File(path).exists())
    }
```
In `app/src/test/kotlin/io/github/yuriimurha/reels/ui/search/SearchViewModelTest.kt`:
- add the imports `io.github.yuriimurha.reels.data.media.ThumbnailStore`, `org.junit.Rule`, `org.junit.rules.TemporaryFolder`, `java.io.File`
- replace `private val viewModel by lazy { SearchViewModel(LibraryRepository(db)) }` with:
```kotlin
    @get:Rule
    val tmp = TemporaryFolder()

    private val viewModel by lazy { SearchViewModel(LibraryRepository(db, ThumbnailStore(File(tmp.root, "thumbs")))) }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.yuriimurha.reels.ui.sync.*" --tests "io.github.yuriimurha.reels.data.library.*"`
Expected: FAIL to compile (`syncUiState`, `SyncContent`, and the two-argument `LibraryRepository` don't exist).

- [ ] **Step 3: Add `deleteLibrary` to the repository**

In `app/src/main/kotlin/io/github/yuriimurha/reels/data/library/LibraryRepository.kt`, change the class header to:
```kotlin
class LibraryRepository(private val db: ReelsDatabase, private val thumbnails: ThumbnailStore) {
```
add the imports `io.github.yuriimurha.reels.data.media.ThumbnailStore`, `kotlinx.coroutines.Dispatchers`, `kotlinx.coroutines.withContext`, and add this method at the end of the class:
```kotlin
    /** Wipes synced items, collections, history and thumbnails. Keeps the session and the request log (spec 9.5). */
    suspend fun deleteLibrary() {
        db.deleteLibrary()
        withContext(Dispatchers.IO) { thumbnails.deleteAll() }
    }
```

In `app/src/main/kotlin/io/github/yuriimurha/reels/di/AppContainer.kt`, change the `library` line to:
```kotlin
    val library: LibraryRepository by lazy { LibraryRepository(db, thumbnails) }
```

- [ ] **Step 4: Implement the sync screen**

`app/src/main/kotlin/io/github/yuriimurha/reels/ui/sync/SyncUiState.kt`:
```kotlin
package io.github.yuriimurha.reels.ui.sync

import io.github.yuriimurha.reels.data.db.SyncRunEntity
import io.github.yuriimurha.reels.data.db.SyncStatus
import io.github.yuriimurha.reels.sync.pacing.PacerStatus

data class SyncUiState(
    val running: Boolean,
    val resumable: Boolean,
    val canStart: Boolean,
    val canCancel: Boolean,
    val canDiscard: Boolean,
    val canDeleteLibrary: Boolean,
    val banner: String?,
)

/** What the Sync screen offers for the latest run and the Pacer's state (spec 7.1, 7.4, 9.5). */
fun syncUiState(run: SyncRunEntity?, pacer: PacerStatus?, now: Long): SyncUiState {
    val running = run?.status == SyncStatus.RUNNING
    val resumable = run?.status?.isResumable == true
    val coolingUntil = pacer?.cooldownUntil?.takeIf { it > now }
    val banner = when {
        coolingUntil != null -> "Cooling down after a rate limit: ${(coolingUntil - now + 59_999) / 60_000} min left"
        run == null -> null
        else -> when (run.status) {
            SyncStatus.STOPPED_CHALLENGE -> "Instagram wants verification. Resolve it before syncing again."
            SyncStatus.STOPPED_LOGIN -> "Session expired. Log in again, then tap Resume."
            SyncStatus.STOPPED_SHAPE -> run.lastError ?: "Adapter needs repair"
            SyncStatus.STOPPED_RATE_LIMIT -> "Instagram limited requests. Tap Resume when you're ready."
            SyncStatus.PAUSED -> run.lastError ?: "Paused"
            SyncStatus.RUNNING, SyncStatus.DONE, SyncStatus.CANCELLED -> null
        }
    }
    return SyncUiState(
        running = running,
        resumable = resumable,
        canStart = !running && coolingUntil == null,
        canCancel = running,
        canDiscard = resumable && !running,
        canDeleteLibrary = !running,
        banner = banner,
    )
}
```

`app/src/main/kotlin/io/github/yuriimurha/reels/ui/sync/SyncViewModel.kt`:
```kotlin
package io.github.yuriimurha.reels.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yuriimurha.reels.data.db.SyncMode
import io.github.yuriimurha.reels.data.db.SyncRunEntity
import io.github.yuriimurha.reels.data.db.SyncStatus
import io.github.yuriimurha.reels.data.library.LibraryRepository
import io.github.yuriimurha.reels.sync.SyncController
import io.github.yuriimurha.reels.sync.pacing.Pacer
import io.github.yuriimurha.reels.sync.pacing.PacerStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SyncViewModel(
    private val controller: SyncController,
    private val library: LibraryRepository,
    private val pacer: Pacer,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val sharing = SharingStarted.WhileSubscribed(5_000)

    val run: StateFlow<SyncRunEntity?> = controller.latestRun.stateIn(viewModelScope, sharing, null)
    val lastSyncAt: StateFlow<Long?> = controller.lastSyncAt.stateIn(viewModelScope, sharing, null)
    val lastFullSyncAt: StateFlow<Long?> = controller.lastFullSyncAt.stateIn(viewModelScope, sharing, null)

    /** Budgets and cooldown, refreshed every second while the screen is visible. Local reads only. */
    val pacerStatus: StateFlow<PacerStatus?> = flow {
        while (true) {
            emit(pacer.status())
            delay(1_000)
        }
    }.stateIn(viewModelScope, sharing, null)

    val ui: StateFlow<SyncUiState> = combine(run, pacerStatus) { r, p -> syncUiState(r, p, now()) }
        .stateIn(viewModelScope, sharing, syncUiState(null, null, now()))

    fun start(mode: SyncMode) {
        viewModelScope.launch { controller.start(mode) }
    }

    fun cancel() {
        viewModelScope.launch { controller.cancel() }
    }

    fun discard() {
        viewModelScope.launch { controller.discardResumable() }
    }

    fun deleteLibrary() {
        if (run.value?.status == SyncStatus.RUNNING) return
        viewModelScope.launch { library.deleteLibrary() }
    }
}
```

`app/src/main/kotlin/io/github/yuriimurha/reels/ui/sync/SyncScreen.kt`:
```kotlin
package io.github.yuriimurha.reels.ui.sync

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.yuriimurha.reels.data.db.SyncMode
import io.github.yuriimurha.reels.data.db.SyncRunEntity
import io.github.yuriimurha.reels.data.db.SyncStatus
import io.github.yuriimurha.reels.sync.pacing.PacerStatus
import io.github.yuriimurha.reels.ui.LocalAppContainer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val viewModel = viewModel { SyncViewModel(container.syncController, container.library, container.backend.pacer) }
    val run by viewModel.run.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val pacer by viewModel.pacerStatus.collectAsStateWithLifecycle()
    val lastSync by viewModel.lastSyncAt.collectAsStateWithLifecycle()
    val lastFull by viewModel.lastFullSyncAt.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    val startSync: (SyncMode) -> Unit = { mode ->
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        viewModel.start(mode)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sync") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        SyncContent(
            run = run,
            ui = ui,
            pacer = pacer,
            lastSyncAt = lastSync,
            lastFullSyncAt = lastFull,
            onSync = { startSync(SyncMode.QUICK) },
            onFullSync = { startSync(SyncMode.FULL) },
            onCancel = viewModel::cancel,
            onDiscard = viewModel::discard,
            onDeleteLibrary = { confirmDelete = true },
            modifier = Modifier.padding(padding),
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete library?") },
            text = {
                Text("Removes every synced item and thumbnail from this phone. Getting them back takes a full, paced sync. Your Instagram session is kept.")
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; viewModel.deleteLibrary() }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
        )
    }
}

@Composable
fun SyncContent(
    run: SyncRunEntity?,
    ui: SyncUiState,
    pacer: PacerStatus?,
    lastSyncAt: Long?,
    lastFullSyncAt: Long?,
    onSync: () -> Unit,
    onFullSync: () -> Unit,
    onCancel: () -> Unit,
    onDiscard: () -> Unit,
    onDeleteLibrary: () -> Unit,
    modifier: Modifier = Modifier,
    sessionSection: @Composable () -> Unit = {},
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ui.banner?.let { Banner(it) }
        sessionSection()
        Section("Library") {
            if (ui.resumable) {
                Button(onClick = onSync, enabled = ui.canStart, modifier = Modifier.fillMaxWidth()) { Text("Resume") }
                OutlinedButton(onClick = onDiscard, enabled = ui.canDiscard, modifier = Modifier.fillMaxWidth()) {
                    Text("Discard paused run")
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onSync, enabled = ui.canStart, modifier = Modifier.weight(1f)) { Text("Sync") }
                    OutlinedButton(onClick = onFullSync, enabled = ui.canStart, modifier = Modifier.weight(1f)) { Text("Full sync") }
                }
            }
            if (ui.canCancel) TextButton(onClick = onCancel) { Text("Cancel") }
            Text(
                "Sync adds new saves. Full sync also removes unsaves and applies moves between collections.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        run?.let { RunProgress(it, pacer) }
        Section("History") {
            Line("Last sync", lastSyncAt.relative())
            Line("Last full sync", lastFullSyncAt.relative())
        }
        Section("Storage") {
            OutlinedButton(onClick = onDeleteLibrary, enabled = ui.canDeleteLibrary) { Text("Delete library") }
        }
    }
}

@Composable
private fun RunProgress(run: SyncRunEntity, pacer: PacerStatus?) {
    Section(if (run.status == SyncStatus.RUNNING) "Syncing" else "Last run") {
        if (run.phase.isNotBlank()) Text(run.phase, style = MaterialTheme.typography.bodyMedium)
        Line("Collections", "${run.collectionsDone} / ${run.collectionsTotal}")
        Line("New items", run.newItems.toString())
        Line("Items seen", run.seenItems.toString())
        Line("Thumbnails cached", run.thumbsCached.toString())
        Line("Failures", run.failures.toString())
        if (pacer != null) {
            Line("Requests this run", "${run.requestsUsed} / ${pacer.perRunBudget}")
            Line("Requests in 24 h", "${pacer.requestsLast24h} / ${pacer.dailyBudget}")
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}

@Composable
private fun Banner(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(12.dp))
            .padding(12.dp),
    )
}

private fun Long?.relative(): String = this?.let { DateUtils.getRelativeTimeSpanString(it).toString() } ?: "Never"
```

- [ ] **Step 5: Register the sync route**

In `app/src/main/kotlin/io/github/yuriimurha/reels/ui/ReelsNavHost.kt`, add the import `io.github.yuriimurha.reels.ui.sync.SyncScreen`. In the `composable<HomeRoute>` block, replace `onOpenSync = {},` with:
```kotlin
                onOpenSync = { navController.navigate(SyncRoute) },
```
and add this destination:
```kotlin
        composable<SyncRoute> {
            SyncScreen(onBack = { navController.popBackStack() })
        }
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (all tests, including 5 + 2 new ones and the extra repository test).

- [ ] **Step 7: Run the whole check**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Walk through M1 on a device**

Run `./gradlew installDebug` on the emulator or the phone, then check each line:
1. Home shows "Nothing synced yet". Open Sync, then tap **Sync** (allow notifications). A "Syncing saved reels" notification appears and the counters move. Done means "Last run" shows 2,000 new items.
2. Home shows **All Saved** (2,000), **Uncategorized**, and 8 collections with covers.
3. Open All Saved: scrolling a long way stays smooth. Debug builds of Compose stutter more than release builds, and smoothness is judged on the M6 release build, so only flag obvious jank here. Tiles show ▶ and "1/N" badges.
4. Tap a reel. The test clip plays with sound and loops. Tapping pauses. Mute survives swiping to another reel and leaving the viewer. Back returns to the grid at the item you were on.
5. A carousel shows its cover and "1/N". A missing item (every 97th) shows "Not available on Instagram".
6. Search `pasta` returns results. The **Reels** chip narrows them, and a collection chip scopes them.
7. **Full sync** completes with 0 new items. **Delete library** asks for confirmation, then Home is empty again. The 24 h counter on Sync doesn't reset.
8. Start a Sync and run `adb shell am kill io.github.yuriimurha.reels` while the app is in the background. Reopen it: Sync shows **Resume** with "Interrupted, tap Sync to resume". Resume finishes the run.

Fix anything that fails before continuing, and add a test for it in the task that owns the code.

- [ ] **Step 9: Close milestone M1 in the docs**

Append to the `## Components` table in `ARCHITECTURE.md`:
```markdown
| Sync screen | `app/.../ui/sync/` | Sync / Full sync (or Resume + Discard), Cancel, live run counters, budgets, cooldown countdown, status banners, history, Delete library (keeps the session and request log). |
```
and change `## Status` to say: `M1 complete: the full app runs on the fake backend (2,000 generated items). No Instagram access yet.`

Append to `PROGRESS.md`:
```markdown

## 2026-10-06: M1 mock app

- Fake backend through the real sync engine and Pacer: 2,000 items across 8 collections, quick and full syncs, resume after process death.
- Home, grid, viewer (reused ExoPlayer, remembered mute), search (debounced FTS with filters) and the Sync screen.
- Device walkthrough passed (list any deviations here).
```
(Use the actual date.) In `TODO.md`, tick `M1 Mock app`.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/kotlin/io/github/yuriimurha/reels/ui/sync app/src/main/kotlin/io/github/yuriimurha/reels/data/library/LibraryRepository.kt app/src/main/kotlin/io/github/yuriimurha/reels/di/AppContainer.kt app/src/main/kotlin/io/github/yuriimurha/reels/ui/ReelsNavHost.kt app/src/test/kotlin/io/github/yuriimurha/reels/ui/sync app/src/test/kotlin/io/github/yuriimurha/reels/data/library/LibraryRepositoryTest.kt app/src/test/kotlin/io/github/yuriimurha/reels/ui/search/SearchViewModelTest.kt ARCHITECTURE.md PROGRESS.md TODO.md
git commit -m "feat(ui): sync screen with resume, budgets and library wipe" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Milestone M2: Session

### Task 15: Cookie bridge and Instagram HTTP client

**Files:**
- Create: `instagram/src/main/kotlin/io/github/yuriimurha/reels/instagram/web/CookieStore.kt`
- Create: `instagram/src/main/kotlin/io/github/yuriimurha/reels/instagram/web/InMemoryCookieStore.kt`
- Create: `instagram/src/main/kotlin/io/github/yuriimurha/reels/instagram/web/WebHeaders.kt`
- Create: `instagram/src/main/kotlin/io/github/yuriimurha/reels/instagram/web/HttpClientFactory.kt`
- Test: `instagram/src/test/kotlin/io/github/yuriimurha/reels/instagram/web/CookieStoreJarTest.kt`
- Test: `instagram/src/test/kotlin/io/github/yuriimurha/reels/instagram/web/HttpClientFactoryTest.kt`
- Modify: `ARCHITECTURE.md`

**Interfaces:**
- Consumes: nothing new.
- Produces (package `io.github.yuriimurha.reels.instagram.web`):
  - `interface CookieStore { fun cookieHeader(url: String): String?; fun setCookie(url: String, setCookie: String); fun flush(); fun clearAll() }`
  - `fun CookieStore.cookieValue(url: String, name: String): String?`
  - `class CookieStoreJar(store: CookieStore) : okhttp3.CookieJar`
  - `class InMemoryCookieStore : CookieStore` (JVM tests; exposes `flushes: Int`)
  - `object WebHeaders { const val APP_ID: String; fun interceptor(userAgent: String, cookies: CookieStore): Interceptor }`
  - `object HttpClientFactory { fun create(cookies: CookieStore, userAgent: String, logger: ((String) -> Unit)? = null): OkHttpClient }`

- [ ] **Step 1: Write the failing tests**

`instagram/src/test/kotlin/io/github/yuriimurha/reels/instagram/web/CookieStoreJarTest.kt`:
```kotlin
package io.github.yuriimurha.reels.instagram.web

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CookieStoreJarTest {
    private val url = "https://www.instagram.com/api/v1/x/".toHttpUrl()

    @Test
    fun loadsEveryWellFormedCookie() {
        val store = InMemoryCookieStore().apply {
            setCookie(url.toString(), "a=1")
            setCookie(url.toString(), "b=2")
        }
        assertEquals(listOf("a" to "1", "b" to "2"), CookieStoreJar(store).loadForRequest(url).map { it.name to it.value })
    }

    @Test
    fun savesUpdatesAndFlushes() {
        val store = InMemoryCookieStore()
        val cookie = Cookie.Builder().name("rur").value("r1").domain("www.instagram.com").build()
        CookieStoreJar(store).saveFromResponse(url, listOf(cookie))
        assertEquals("r1", store.cookieValue(url.toString(), "rur"))
        assertEquals(1, store.flushes)
    }

    @Test
    fun cookieValueFindsOneCookie() {
        val store = InMemoryCookieStore().apply {
            setCookie(url.toString(), "sessionid=s1")
            setCookie(url.toString(), "ds_user_id=42")
        }
        assertEquals("42", store.cookieValue(url.toString(), "ds_user_id"))
        assertNull(store.cookieValue(url.toString(), "csrftoken"))
    }

    @Test
    fun expiredSetCookieRemovesTheCookie() {
        val store = InMemoryCookieStore().apply { setCookie(url.toString(), "a=1") }
        store.setCookie(url.toString(), "a=; Max-Age=0")
        assertNull(store.cookieHeader(url.toString()))
    }
}
```

`instagram/src/test/kotlin/io/github/yuriimurha/reels/instagram/web/HttpClientFactoryTest.kt`:
```kotlin
package io.github.yuriimurha.reels.instagram.web

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Request
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HttpClientFactoryTest {
    private val server = MockWebServer()
    private val site = "https://www.instagram.com"

    @BeforeTest
    fun start() = server.start()

    @AfterTest
    fun stop() = server.close()

    @Test
    fun sendsTheWebViewIdentityAndTheSharedCookies() {
        val cookies = InMemoryCookieStore().apply {
            setCookie(site, "sessionid=s1")
            setCookie(site, "csrftoken=t1")
        }
        server.enqueue(MockResponse.Builder().code(200).addHeader("Set-Cookie", "rur=r2; Path=/").body("{}").build())

        HttpClientFactory.create(cookies, userAgent = "TestWebView/1.0")
            .newCall(Request.Builder().url(server.url("/api/v1/x/")).build()).execute().close()

        val recorded = server.takeRequest()
        assertEquals("TestWebView/1.0", recorded.headers["User-Agent"])
        assertEquals(WebHeaders.APP_ID, recorded.headers["X-IG-App-ID"])
        assertEquals("t1", recorded.headers["X-CSRFToken"])
        assertEquals("sessionid=s1; csrftoken=t1", recorded.headers["Cookie"])
        assertEquals("r2", cookies.cookieValue(site, "rur"), "Instagram's cookie update reaches the shared store")
        assertTrue(cookies.flushes > 0)
    }

    @Test
    fun redirectsAreNotFollowed() {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/challenge/?next=/").build())
        val response = HttpClientFactory.create(InMemoryCookieStore(), "UA")
            .newCall(Request.Builder().url(server.url("/api/v1/x/")).build()).execute()
        assertEquals(302, response.code)
        response.close()
        assertEquals(1, server.requestCount)
    }

    @Test
    fun debugLoggingNeverShowsCookieValues() {
        val cookies = InMemoryCookieStore().apply {
            setCookie(site, "sessionid=zq9")
            setCookie(site, "csrftoken=zq8")
        }
        server.enqueue(MockResponse.Builder().code(200).addHeader("Set-Cookie", "rur=zq7; Path=/").body("{}").build())
        val lines = mutableListOf<String>()

        HttpClientFactory.create(cookies, "UA", logger = { lines += it })
            .newCall(Request.Builder().url(server.url("/x")).build()).execute().close()

        assertTrue(lines.isNotEmpty())
        for (secret in listOf("zq9", "zq8", "zq7")) {
            assertTrue(lines.none { secret in it }, "$secret leaked into the log")
        }
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :instagram:test --tests "io.github.yuriimurha.reels.instagram.web.*"`
Expected: FAIL to compile.

- [ ] **Step 3: Implement**

`instagram/src/main/kotlin/io/github/yuriimurha/reels/instagram/web/CookieStore.kt`:
```kotlin
package io.github.yuriimurha.reels.instagram.web

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/** Where Instagram's cookies live. On Android it's the WebView's CookieManager, so login and API calls share one jar. */
interface CookieStore {
    /** Cookies for [url] as a request header value ("a=1; b=2"), or null. */
    fun cookieHeader(url: String): String?

    /** Stores one Set-Cookie header value received from [url]. */
    fun setCookie(url: String, setCookie: String)

    fun flush()

    /** Logout: forgets every cookie. */
    fun clearAll()
}

fun CookieStore.cookieValue(url: String, name: String): String? =
    cookieHeader(url)
        ?.split(';')
        ?.map { it.trim() }
        ?.firstOrNull { it.startsWith("$name=") }
        ?.substringAfter('=')
        ?.takeIf { it.isNotEmpty() }

/** Gives OkHttp the store's cookies and writes Instagram's Set-Cookie updates back into it (spec 4.3). */
class CookieStoreJar(private val store: CookieStore) : CookieJar {
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val header = store.cookieHeader(url.toString()) ?: return emptyList()
        return header.split(';').mapNotNull { part ->
            val name = part.substringBefore('=').trim()
            val value = part.substringAfter('=', "").trim()
            if (name.isEmpty()) {
                null
            } else {
                runCatching { Cookie.Builder().name(name).value(value).domain(url.host).build() }.getOrNull()
            }
        }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        cookies.forEach { store.setCookie(url.toString(), it.toString()) }
        store.flush()
    }
}
```

`instagram/src/main/kotlin/io/github/yuriimurha/reels/instagram/web/InMemoryCookieStore.kt`:
```kotlin
package io.github.yuriimurha.reels.instagram.web

/** A single-site cookie store for JVM tests. Ignores domains and paths; an empty value or Max-Age=0 deletes. */
class InMemoryCookieStore : CookieStore {
    private val cookies = linkedMapOf<String, String>()

    var flushes = 0
        private set

    override fun cookieHeader(url: String): String? =
        cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }.ifEmpty { null }

    override fun setCookie(url: String, setCookie: String) {
        val pair = setCookie.substringBefore(';')
        val name = pair.substringBefore('=').trim()
        val value = pair.substringAfter('=', "").trim()
        val expired = setCookie.split(';').any { it.trim().equals("Max-Age=0", ignoreCase = true) }
        if (expired || value.isEmpty()) cookies.remove(name) else cookies[name] = value
    }

    override fun flush() {
        flushes++
    }

    override fun clearAll() {
        cookies.clear()
    }
}
```

`instagram/src/main/kotlin/io/github/yuriimurha/reels/instagram/web/WebHeaders.kt`:
```kotlin
package io.github.yuriimurha.reels.instagram.web

import okhttp3.Interceptor

/** Makes API calls look like the website running in the app's WebView (spec 6.1). */
object WebHeaders {
    /** The X-IG-App-ID the website sends. Checked on the phone in Task 18 and again in the M3 spike. */
    const val APP_ID = "936619743392459"

    fun interceptor(userAgent: String, cookies: CookieStore) = Interceptor { chain ->
        val request = chain.request()
        val builder = request.newBuilder()
            .header("User-Agent", userAgent)
            .header("X-IG-App-ID", APP_ID)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "*/*")
            .header("Referer", "https://www.instagram.com/")
        cookies.cookieValue(request.url.toString(), "csrftoken")?.let { builder.header("X-CSRFToken", it) }
        chain.proceed(builder.build())
    }
}
```

`instagram/src/main/kotlin/io/github/yuriimurha/reels/instagram/web/HttpClientFactory.kt`:
```kotlin
package io.github.yuriimurha.reels.instagram.web

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

object HttpClientFactory {
    /**
     * The client for Instagram API calls. Redirects are not followed, so a bounce to /challenge/ or
     * /accounts/login reaches ErrorClassifier. Pass [logger] only in debug builds; secrets are redacted (spec 4.4).
     */
    fun create(cookies: CookieStore, userAgent: String, logger: ((String) -> Unit)? = null): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .cookieJar(CookieStoreJar(cookies))
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(WebHeaders.interceptor(userAgent, cookies))
        if (logger != null) {
            builder.addNetworkInterceptor(
                HttpLoggingInterceptor { message -> logger(message) }.apply {
                    level = HttpLoggingInterceptor.Level.HEADERS
                    redactHeader("Cookie")
                    redactHeader("Set-Cookie")
                    redactHeader("X-CSRFToken")
                },
            )
        }
        return builder.build()
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :instagram:test`
Expected: PASS (all `:instagram` tests, 7 of them new).

- [ ] **Step 5: Record the component and commit**

In `ARCHITECTURE.md`, append to the `:instagram` row: `Web layer: CookieStore bridge (OkHttp ↔ the shared cookie jar, Set-Cookie written back), WebView-identity headers, no-redirect client with redacted debug logging.`

```bash
git add instagram/src ARCHITECTURE.md
git commit -m "feat(instagram): shared cookie jar bridge and redacting HTTP client" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 16: Error classifier and session probe

**Files:**
- Create: `instagram/src/main/kotlin/io/github/yuriimurha/reels/instagram/web/WebJson.kt`
- Create: `instagram/src/main/kotlin/io/github/yuriimurha/reels/instagram/web/ErrorClassifier.kt`
- Create: `instagram/src/main/kotlin/io/github/yuriimurha/reels/instagram/web/WebEndpoints.kt`
- Create: `instagram/src/main/kotlin/io/github/yuriimurha/reels/instagram/web/WebSessionProbe.kt`
- Test: `instagram/src/test/kotlin/io/github/yuriimurha/reels/instagram/web/ErrorClassifierTest.kt`
- Test: `instagram/src/test/kotlin/io/github/yuriimurha/reels/instagram/web/WebSessionProbeTest.kt`
- Modify: `ARCHITECTURE.md`

**Interfaces:**
- Consumes: `InstagramException`, `Account`, `SessionProbe` (Task 3); `CookieStore`, `cookieValue`, `InMemoryCookieStore`, `HttpClientFactory` (Task 15).
- Produces:
  - `object ErrorClassifier { fun classify(code: Int, location: String?, contentType: String?, body: String): InstagramException? }`
  - internal `Call.await()`, `OkHttpClient.getJsonObject(url)`, `parseObject(body)`, and `JsonObject.string(key)`
  - `object WebEndpoints { val BASE: HttpUrl; fun currentUser(base: HttpUrl, userId: String): HttpUrl }`
  - `class WebSessionProbe(http: OkHttpClient, cookies: CookieStore, base: HttpUrl = WebEndpoints.BASE) : SessionProbe`

- [ ] **Step 1: Write the failing tests**

`instagram/src/test/kotlin/io/github/yuriimurha/reels/instagram/web/ErrorClassifierTest.kt`:
```kotlin
package io.github.yuriimurha.reels.instagram.web

import io.github.yuriimurha.reels.instagram.InstagramException.ChallengeRequired
import io.github.yuriimurha.reels.instagram.InstagramException.LoginRequired
import io.github.yuriimurha.reels.instagram.InstagramException.RateLimited
import io.github.yuriimurha.reels.instagram.InstagramException.ShapeChanged
import io.github.yuriimurha.reels.instagram.InstagramException.Transient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** One case per row of spec 6.4. */
class ErrorClassifierTest {
    private fun classify(code: Int, body: String = "{}", location: String? = null, contentType: String? = "application/json") =
        ErrorClassifier.classify(code, location, contentType, body)

    @Test
    fun checkpointInTheBody() {
        val error = assertIs<ChallengeRequired>(
            classify(400, """{"message":"checkpoint_required","checkpoint_url":"https://www.instagram.com/challenge/x/","status":"fail"}"""),
        )
        assertEquals("https://www.instagram.com/challenge/x/", error.challengeUrl)
    }

    @Test
    fun challengeObjectInTheBody() {
        val error = assertIs<ChallengeRequired>(
            classify(400, """{"message":"challenge_required","challenge":{"url":"https://i.instagram.com/challenge/y/"},"status":"fail"}"""),
        )
        assertEquals("https://i.instagram.com/challenge/y/", error.challengeUrl)
    }

    @Test
    fun redirectToAChallengeBecomesAbsolute() {
        val error = assertIs<ChallengeRequired>(classify(302, body = "", location = "/challenge/?next=/"))
        assertEquals("https://www.instagram.com/challenge/?next=/", error.challengeUrl)
    }

    @Test
    fun redirectToLogin() {
        assertIs<LoginRequired>(classify(302, body = "", location = "https://www.instagram.com/accounts/login/?next=/api/"))
    }

    @Test
    fun unknownRedirectStopsLikeAChallenge() {
        assertIs<ChallengeRequired>(classify(302, body = "", location = "https://www.instagram.com/accounts/suspended/"))
    }

    @Test
    fun loginRequiredVariants() {
        assertIs<LoginRequired>(classify(401))
        assertIs<LoginRequired>(classify(403, """{"message":"login_required"}"""))
        assertIs<LoginRequired>(classify(400, """{"require_login":true}"""))
    }

    @Test
    fun rateLimitVariants() {
        assertIs<RateLimited>(classify(429, body = ""))
        assertIs<RateLimited>(classify(400, """{"message":"feedback_required"}"""))
        assertIs<RateLimited>(classify(400, """{"message":"Please wait a few minutes before you try again."}"""))
    }

    @Test
    fun serverErrorsAreTransient() {
        assertIs<Transient>(classify(500, body = ""))
        assertIs<Transient>(classify(503, body = "<html></html>", contentType = "text/html"))
    }

    @Test
    fun unknownClientErrorsNeedRepair() {
        assertEquals("http.404", assertIs<ShapeChanged>(classify(404)).fieldPath)
    }

    @Test
    fun htmlInsteadOfJsonMeansLoggedOut() {
        assertIs<LoginRequired>(classify(200, "<!DOCTYPE html><html></html>", contentType = "text/html; charset=utf-8"))
    }

    @Test
    fun nonJsonBodyNeedsRepair() {
        assertEquals("$", assertIs<ShapeChanged>(classify(200, "nope", contentType = "text/plain")).fieldPath)
    }

    @Test
    fun okJsonPasses() {
        assertNull(classify(200, """{"status":"ok"}"""))
    }
}
```

`instagram/src/test/kotlin/io/github/yuriimurha/reels/instagram/web/WebSessionProbeTest.kt`:
```kotlin
package io.github.yuriimurha.reels.instagram.web

import io.github.yuriimurha.reels.instagram.Account
import io.github.yuriimurha.reels.instagram.InstagramException
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WebSessionProbeTest {
    private val server = MockWebServer()
    private val cookies = InMemoryCookieStore()

    @BeforeTest
    fun start() {
        server.start()
        cookies.setCookie("https://www.instagram.com", "ds_user_id=42")
    }

    @AfterTest
    fun stop() = server.close()

    private fun probe() = WebSessionProbe(HttpClientFactory.create(cookies, "UA"), cookies, base = server.url("/"))

    @Test
    fun returnsTheLoggedInAccount() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("""{"user":{"pk":42,"username":"tester"},"status":"ok"}""").build())
        assertEquals(Account("42", "tester"), probe().currentUser())
        assertEquals("/api/v1/users/42/info/", server.takeRequest().url.encodedPath)
    }

    @Test
    fun noUserCookieMeansLoggedOutWithoutARequest() = runTest {
        cookies.clearAll()
        assertFailsWith<InstagramException.LoginRequired> { probe().currentUser() }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun challengeRedirectIsNotFollowed() = runTest {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/challenge/?next=/").build())
        assertFailsWith<InstagramException.ChallengeRequired> { probe().currentUser() }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun missingUsernameNeedsRepair() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("""{"user":{},"status":"ok"}""").build())
        val error = assertFailsWith<InstagramException.ShapeChanged> { probe().currentUser() }
        assertEquals("user.username", error.fieldPath)
    }

    @Test
    fun networkFailureIsTransient() = runTest {
        val dead = MockWebServer().apply { start() }
        val url = dead.url("/")
        dead.close()
        val unreachable = WebSessionProbe(HttpClientFactory.create(cookies, "UA"), cookies, base = url)
        assertFailsWith<InstagramException.Transient> { unreachable.currentUser() }
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :instagram:test --tests "io.github.yuriimurha.reels.instagram.web.*"`
Expected: FAIL to compile (`ErrorClassifier`, `WebSessionProbe` don't exist).

- [ ] **Step 3: Implement**

`instagram/src/main/kotlin/io/github/yuriimurha/reels/instagram/web/WebJson.kt`:
```kotlin
package io.github.yuriimurha.reels.instagram.web

import io.github.yuriimurha.reels.instagram.InstagramException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }
        },
    )
}

/** GETs [url] and returns its JSON object, or throws the InstagramException the response signals. */
internal suspend fun OkHttpClient.getJsonObject(url: HttpUrl): JsonObject {
    val response = try {
        newCall(Request.Builder().url(url).get().build()).await()
    } catch (e: IOException) {
        throw InstagramException.Transient(e)
    }
    response.use {
        val body = try {
            it.body.string()
        } catch (e: IOException) {
            throw InstagramException.Transient(e)
        }
        ErrorClassifier.classify(it.code, it.header("Location"), it.header("Content-Type"), body)?.let { error -> throw error }
        return parseObject(body) ?: throw InstagramException.ShapeChanged("$")
    }
}

internal fun parseObject(body: String): JsonObject? =
    runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()

internal fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
```

`instagram/src/main/kotlin/io/github/yuriimurha/reels/instagram/web/ErrorClassifier.kt`:
```kotlin
package io.github.yuriimurha.reels.instagram.web

import io.github.yuriimurha.reels.instagram.InstagramException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Maps one HTTP response to the failure it signals, or null when it is safe to parse (spec 6.4). */
object ErrorClassifier {
    private const val SITE = "https://www.instagram.com"

    fun classify(code: Int, location: String?, contentType: String?, body: String): InstagramException? {
        val json = parseObject(body)
        val markers = listOfNotNull(json?.string("message"), json?.string("error_type")).joinToString(" ").lowercase()
        when {
            "checkpoint_required" in markers || "challenge_required" in markers || json?.get("challenge") != null ->
                return InstagramException.ChallengeRequired(json?.challengeUrl()?.let(::absolute))
            "login_required" in markers || (json?.get("require_login") as? JsonPrimitive)?.booleanOrNull == true ->
                return InstagramException.LoginRequired()
            code == 429 || "feedback_required" in markers || "please wait a few minutes" in markers ->
                return InstagramException.RateLimited()
        }
        if (code in 300..399) {
            val target = location.orEmpty()
            return if ("/accounts/login" in target) {
                InstagramException.LoginRequired()
            } else {
                // A challenge, or an interstitial we don't know: stop and let the owner look in the WebView.
                InstagramException.ChallengeRequired(target.takeIf { it.isNotEmpty() }?.let(::absolute))
            }
        }
        return when {
            code == 401 || code == 403 -> InstagramException.LoginRequired()
            code >= 500 -> InstagramException.Transient()
            code >= 400 -> InstagramException.ShapeChanged("http.$code")
            json == null && contentType.orEmpty().contains("html") -> InstagramException.LoginRequired()
            json == null -> InstagramException.ShapeChanged("$")
            json.string("status") == "fail" -> InstagramException.ShapeChanged("status")
            else -> null
        }
    }

    private fun JsonObject.challengeUrl(): String? =
        (this["challenge"] as? JsonObject)?.string("url") ?: string("checkpoint_url")

    private fun absolute(url: String): String = if (url.startsWith("/")) SITE + url else url
}
```

`instagram/src/main/kotlin/io/github/yuriimurha/reels/instagram/web/WebEndpoints.kt`:
```kotlin
package io.github.yuriimurha.reels.instagram.web

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

object WebEndpoints {
    val BASE: HttpUrl = "https://www.instagram.com/".toHttpUrl()

    /** Candidate from spec 6.2, confirmed on the phone in Task 18. */
    fun currentUser(base: HttpUrl, userId: String): HttpUrl =
        base.newBuilder()
            .addPathSegments("api/v1/users")
            .addPathSegment(userId)
            .addPathSegment("info")
            .addPathSegment("")
            .build()
}
```

`instagram/src/main/kotlin/io/github/yuriimurha/reels/instagram/web/WebSessionProbe.kt`:
```kotlin
package io.github.yuriimurha.reels.instagram.web

import io.github.yuriimurha.reels.instagram.Account
import io.github.yuriimurha.reels.instagram.InstagramException
import io.github.yuriimurha.reels.instagram.SessionProbe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

/** "Who is logged in?" over the website's API, using the shared cookie jar. */
class WebSessionProbe(
    private val http: OkHttpClient,
    private val cookies: CookieStore,
    private val base: HttpUrl = WebEndpoints.BASE,
) : SessionProbe {
    override suspend fun currentUser(): Account {
        val userId = cookies.cookieValue(WebEndpoints.BASE.toString(), "ds_user_id") ?: throw InstagramException.LoginRequired()
        val json = http.getJsonObject(WebEndpoints.currentUser(base, userId))
        val user = json["user"] as? JsonObject ?: throw InstagramException.ShapeChanged("user")
        val username = user.string("username") ?: throw InstagramException.ShapeChanged("user.username")
        val pk = (user["pk"] as? JsonPrimitive)?.content ?: userId
        return Account(pk = pk, username = username)
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :instagram:test`
Expected: PASS (17 new tests: 12 classifier, 5 probe).

- [ ] **Step 5: Record the component and commit**

In `ARCHITECTURE.md`, append to the `:instagram` row: `ErrorClassifier maps responses to typed failures (unknown redirects stop like challenges); WebSessionProbe answers currentUser() via api/v1/users/{ds_user_id}/info/.`

```bash
git add instagram/src ARCHITECTURE.md
git commit -m "feat(instagram): error classification and session probe" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 17: Session repository

**Files:**
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/session/SessionState.kt`
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/session/SessionIdInput.kt`
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/session/AndroidCookieStore.kt`
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/session/SessionRepository.kt`
- Modify: `app/src/main/kotlin/io/github/yuriimurha/reels/data/settings/SettingsStore.kt`
- Modify: `app/src/main/kotlin/io/github/yuriimurha/reels/di/AppContainer.kt`
- Test: `app/src/test/kotlin/io/github/yuriimurha/reels/session/SessionIdInputTest.kt`
- Test: `app/src/test/kotlin/io/github/yuriimurha/reels/session/SessionRepositoryTest.kt`
- Modify: `ARCHITECTURE.md`

**Interfaces:**
- Consumes: `SessionProbe`, `Account`, `InstagramException` (Task 3); `CookieStore`, `cookieValue`, `InMemoryCookieStore`, `HttpClientFactory` (Task 15); `WebSessionProbe` (Task 16); `Pacer`, `PacingPolicy.Conservative`, `RoomRequestLog`, `DataStoreCooldownStore` (Tasks 6–7); `SettingsStore` (Task 7); `SessionSignals` (Task 9); `Backend` (Task 10).
- Produces:
  - `SettingsStore.session: Flow<StoredSession>` and `setSession(StoredSession)`; `data class StoredSession(kind: String?, handle: String?, challengeUrl: String?)`
  - `sealed interface SessionState { val handle: String? }` with `LoggedOut`, `Valid(handle)`, `Expired(handle)`, `Challenge(challengeUrl, handle)`, plus `toStored()` / `toState()`
  - `object SessionIdInput { data class Parsed(sessionId, userId); fun parse(input: String): Parsed? }`
  - `interface LoginSession { fun currentSessionId(): String?; fun hasSessionCookies(): Boolean; suspend fun validate(): SessionState }`
  - `class SessionRepository(cookies, probe, pacer, settings) : SessionSignals, LoginSession` with `state: Flow<SessionState>`, `hasCsrfToken()`, `pasteSessionId(input): SessionState?`, `logout()`, and `SessionRepository.INSTAGRAM`
  - `class AndroidCookieStore : CookieStore`
  - `AppContainer.cookieStore`, `AppContainer.instagramPacer`, `AppContainer.session`

- [ ] **Step 1: Write the failing tests**

`app/src/test/kotlin/io/github/yuriimurha/reels/session/SessionIdInputTest.kt`:
```kotlin
package io.github.yuriimurha.reels.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SessionIdInputTest {
    private val expected = SessionIdInput.Parsed(sessionId = "42%3Aab", userId = "42")

    @Test
    fun acceptsTheShapesPeopleCopy() {
        val inputs = listOf(
            "42%3Aab",
            "42:ab",
            "sessionid=42%3Aab",
            "Sessionid=42%3Aab; Path=/",
            "\"42%3Aab\"",
            "  42%3Aab ;  ",
            "'sessionid=42:ab'",
        )
        for (input in inputs) assertEquals(expected, SessionIdInput.parse(input), input)
    }

    @Test
    fun rejectsAnythingElse() {
        val inputs = listOf("", "   ", "hello", "%3Aab", "ab%3A42x", "42%3Aa b", "42 %3Aab", "42%3Aab,43%3Acd")
        for (input in inputs) assertNull(SessionIdInput.parse(input), input)
    }
}
```

`app/src/test/kotlin/io/github/yuriimurha/reels/session/SessionRepositoryTest.kt`:
```kotlin
package io.github.yuriimurha.reels.session

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.data.settings.SettingsStore
import io.github.yuriimurha.reels.instagram.Account
import io.github.yuriimurha.reels.instagram.InstagramException
import io.github.yuriimurha.reels.instagram.SessionProbe
import io.github.yuriimurha.reels.instagram.web.InMemoryCookieStore
import io.github.yuriimurha.reels.instagram.web.cookieValue
import io.github.yuriimurha.reels.sync.pacing.InMemoryCooldownStore
import io.github.yuriimurha.reels.sync.pacing.InMemoryRequestLog
import io.github.yuriimurha.reels.sync.pacing.Pacer
import io.github.yuriimurha.reels.sync.pacing.PacingPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class SessionRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val storeScope = CoroutineScope(Dispatchers.IO + Job())
    private val cookies = InMemoryCookieStore()
    private val probe = FakeProbe()
    private val log = InMemoryRequestLog()

    @After
    fun tearDown() = storeScope.cancel()

    private fun TestScope.repository(): SessionRepository {
        val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = storeScope) { File(tmp.root, "s.preferences_pb") })
        val pacer = Pacer(PacingPolicy.Conservative, log, InMemoryCooldownStore(), Random(1), now = { testScheduler.currentTime })
        return SessionRepository(cookies, probe, pacer, settings)
    }

    @Test
    fun validationIsPacedAndStoresTheHandle() = runTest {
        val repository = repository()
        assertEquals(SessionState.Valid("tester"), repository.validate())
        assertEquals(SessionState.Valid("tester"), repository.state.first())
        assertEquals(1, log.countSince(-1), "a session check is an Instagram request and goes through the Pacer")
    }

    @Test
    fun loginRequiredKeepsTheLastHandle() = runTest {
        val repository = repository()
        repository.validate()
        probe.next = { throw InstagramException.LoginRequired() }
        assertEquals(SessionState.Expired("tester"), repository.validate())
    }

    @Test
    fun challengeKeepsItsUrl() = runTest {
        val repository = repository()
        probe.next = { throw InstagramException.ChallengeRequired("https://www.instagram.com/challenge/x/") }
        assertEquals(SessionState.Challenge("https://www.instagram.com/challenge/x/", null), repository.validate())
    }

    @Test
    fun pasteRejectsGarbageWithoutRequest() = runTest {
        val repository = repository()
        assertNull(repository.pasteSessionId("hello there"))
        assertEquals(0, probe.calls)
        assertEquals(0, log.countSince(-1))
        assertFalse(repository.hasSessionCookies())
    }

    @Test
    fun pasteWritesBothCookiesThenValidates() = runTest {
        val repository = repository()
        assertEquals(SessionState.Valid("tester"), repository.pasteSessionId(" sessionid=\"42%3Aab\"; "))
        assertEquals("42%3Aab", cookies.cookieValue(SessionRepository.INSTAGRAM, "sessionid"))
        assertEquals("42", cookies.cookieValue(SessionRepository.INSTAGRAM, "ds_user_id"))
        assertEquals(1, probe.calls)
    }

    @Test
    fun logoutForgetsTheCookiesAndTheHandle() = runTest {
        val repository = repository()
        repository.pasteSessionId("42%3Aab")
        repository.logout()
        assertFalse(repository.hasSessionCookies())
        assertEquals(SessionState.LoggedOut, repository.state.first())
    }

    @Test
    fun engineSignalsUpdateTheState() = runTest {
        val repository = repository()
        repository.validate()
        repository.challengeRequired("https://www.instagram.com/challenge/z/")
        assertEquals(SessionState.Challenge("https://www.instagram.com/challenge/z/", "tester"), repository.state.first())
        repository.loginRequired()
        assertEquals(SessionState.Expired("tester"), repository.state.first())
    }

    @Test
    fun sessionCookiesNeedBothValues() = runTest {
        val repository = repository()
        cookies.setCookie(SessionRepository.INSTAGRAM, "sessionid=s1")
        assertFalse(repository.hasSessionCookies())
        cookies.setCookie(SessionRepository.INSTAGRAM, "ds_user_id=42")
        assertTrue(repository.hasSessionCookies())
        assertEquals("s1", repository.currentSessionId())
    }

    private class FakeProbe : SessionProbe {
        var calls = 0
        var next: () -> Account = { Account("42", "tester") }

        override suspend fun currentUser(): Account {
            calls++
            return next()
        }
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.yuriimurha.reels.session.*"`
Expected: FAIL to compile.

- [ ] **Step 3: Extend the settings store**

In `app/src/main/kotlin/io/github/yuriimurha/reels/data/settings/SettingsStore.kt`:
- add the imports `androidx.datastore.preferences.core.MutablePreferences` and `androidx.datastore.preferences.core.stringPreferencesKey`
- add this class next to `CooldownState`:
```kotlin
/** The persisted session state, encoded by the session package. The session itself lives only in CookieManager. */
data class StoredSession(val kind: String?, val handle: String?, val challengeUrl: String?)
```
- add these members to `SettingsStore`, above the `companion object`:
```kotlin
    val session: Flow<StoredSession> = store.data.map {
        StoredSession(kind = it[SESSION_KIND], handle = it[SESSION_HANDLE], challengeUrl = it[SESSION_CHALLENGE_URL])
    }

    suspend fun setSession(session: StoredSession) {
        store.edit {
            it.setOrRemove(SESSION_KIND, session.kind)
            it.setOrRemove(SESSION_HANDLE, session.handle)
            it.setOrRemove(SESSION_CHALLENGE_URL, session.challengeUrl)
        }
    }

    private fun <T> MutablePreferences.setOrRemove(key: Preferences.Key<T>, value: T?) {
        if (value == null) remove(key) else this[key] = value
    }
```
- add these keys inside the `companion object`:
```kotlin
        private val SESSION_KIND = stringPreferencesKey("session_kind")
        private val SESSION_HANDLE = stringPreferencesKey("session_handle")
        private val SESSION_CHALLENGE_URL = stringPreferencesKey("session_challenge_url")
```

- [ ] **Step 4: Implement the session package**

`app/src/main/kotlin/io/github/yuriimurha/reels/session/SessionState.kt`:
```kotlin
package io.github.yuriimurha.reels.session

import io.github.yuriimurha.reels.data.settings.StoredSession

sealed interface SessionState {
    val handle: String?

    data object LoggedOut : SessionState {
        override val handle: String? = null
    }

    data class Valid(override val handle: String) : SessionState

    data class Expired(override val handle: String?) : SessionState

    /** [challengeUrl] is where Instagram wants verification. Kept in app-private settings, never logged. */
    data class Challenge(val challengeUrl: String?, override val handle: String?) : SessionState
}

fun SessionState.toStored(): StoredSession = when (this) {
    SessionState.LoggedOut -> StoredSession("LOGGED_OUT", null, null)
    is SessionState.Valid -> StoredSession("VALID", handle, null)
    is SessionState.Expired -> StoredSession("EXPIRED", handle, null)
    is SessionState.Challenge -> StoredSession("CHALLENGE", handle, challengeUrl)
}

fun StoredSession.toState(): SessionState = when (kind) {
    "VALID" -> handle?.let { SessionState.Valid(it) } ?: SessionState.Expired(null)
    "EXPIRED" -> SessionState.Expired(handle)
    "CHALLENGE" -> SessionState.Challenge(challengeUrl, handle)
    else -> SessionState.LoggedOut
}
```

`app/src/main/kotlin/io/github/yuriimurha/reels/session/SessionIdInput.kt`:
```kotlin
package io.github.yuriimurha.reels.session

/** Accepts a pasted sessionid in the shapes people copy it (Review Focus 4). Never makes a request. */
object SessionIdInput {
    data class Parsed(val sessionId: String, val userId: String)

    fun parse(input: String): Parsed? {
        var value = input.trim().trim('"', '\'').trim()
        if (value.startsWith("sessionid=", ignoreCase = true)) value = value.substringAfter('=')
        value = value.substringBefore(';').trim().trim('"', '\'')
        if (value.isEmpty() || value.any { it.isWhitespace() || it == ',' }) return null
        val encoded = value.replace(":", "%3A")
        if (!encoded.contains("%3A")) return null
        val userId = encoded.substringBefore("%3A")
        if (userId.isEmpty() || !userId.all { it.isDigit() }) return null
        return Parsed(sessionId = encoded, userId = userId)
    }
}
```

`app/src/main/kotlin/io/github/yuriimurha/reels/session/AndroidCookieStore.kt`:
```kotlin
package io.github.yuriimurha.reels.session

import android.webkit.CookieManager
import io.github.yuriimurha.reels.instagram.web.CookieStore

/** The WebView's cookie jar: login and API calls share it (spec 4.3). The only place the session is stored. */
class AndroidCookieStore : CookieStore {
    private val manager: CookieManager get() = CookieManager.getInstance()

    override fun cookieHeader(url: String): String? = manager.getCookie(url)

    override fun setCookie(url: String, setCookie: String) = manager.setCookie(url, setCookie)

    override fun flush() = manager.flush()

    override fun clearAll() {
        manager.removeAllCookies(null)
        manager.flush()
    }
}
```

`app/src/main/kotlin/io/github/yuriimurha/reels/session/SessionRepository.kt`:
```kotlin
package io.github.yuriimurha.reels.session

import io.github.yuriimurha.reels.data.settings.SettingsStore
import io.github.yuriimurha.reels.instagram.InstagramException
import io.github.yuriimurha.reels.instagram.SessionProbe
import io.github.yuriimurha.reels.instagram.web.CookieStore
import io.github.yuriimurha.reels.instagram.web.cookieValue
import io.github.yuriimurha.reels.sync.SessionSignals
import io.github.yuriimurha.reels.sync.pacing.Pacer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** What the login screen needs; split out so its ViewModel can be tested without Android. */
interface LoginSession {
    fun currentSessionId(): String?
    fun hasSessionCookies(): Boolean
    suspend fun validate(): SessionState
}

class SessionRepository(
    private val cookies: CookieStore,
    private val probe: SessionProbe,
    private val pacer: Pacer,
    private val settings: SettingsStore,
) : SessionSignals, LoginSession {
    /** The last known state, shown without a request (spec D7). */
    val state: Flow<SessionState> = settings.session.map { it.toState() }

    override fun currentSessionId(): String? = cookies.cookieValue(INSTAGRAM, "sessionid")

    override fun hasSessionCookies(): Boolean =
        currentSessionId() != null && cookies.cookieValue(INSTAGRAM, "ds_user_id") != null

    fun hasCsrfToken(): Boolean = cookies.cookieValue(INSTAGRAM, "csrftoken") != null

    /** One paced request on the interactive lane. Network, rate-limit and budget failures propagate unchanged. */
    override suspend fun validate(): SessionState {
        val handle = state.first().handle
        val result = try {
            SessionState.Valid(pacer.interactive { probe.currentUser() }.username)
        } catch (e: InstagramException.LoginRequired) {
            SessionState.Expired(handle)
        } catch (e: InstagramException.ChallengeRequired) {
            SessionState.Challenge(e.challengeUrl, handle)
        }
        settings.setSession(result.toStored())
        return result
    }

    /** Writes a pasted sessionid into the cookie store and validates it. Null (and no request) for anything else. */
    suspend fun pasteSessionId(input: String): SessionState? {
        val parsed = SessionIdInput.parse(input) ?: return null
        cookies.setCookie(INSTAGRAM, "sessionid=${parsed.sessionId}; Domain=.instagram.com; Path=/; Secure; HttpOnly; Max-Age=31536000")
        cookies.setCookie(INSTAGRAM, "ds_user_id=${parsed.userId}; Domain=.instagram.com; Path=/; Secure; Max-Age=7776000")
        cookies.flush()
        return validate()
    }

    /** Forgets the session. The library is kept (spec 9.5). */
    suspend fun logout() {
        cookies.clearAll()
        settings.setSession(SessionState.LoggedOut.toStored())
    }

    override suspend fun loginRequired() {
        settings.setSession(SessionState.Expired(state.first().handle).toStored())
    }

    override suspend fun challengeRequired(challengeUrl: String?) {
        settings.setSession(SessionState.Challenge(challengeUrl, state.first().handle).toStored())
    }

    companion object {
        const val INSTAGRAM = "https://www.instagram.com"
    }
}
```

- [ ] **Step 5: Wire the container**

In `app/src/main/kotlin/io/github/yuriimurha/reels/di/AppContainer.kt`, add these imports:
```kotlin
import android.util.Log
import android.webkit.WebSettings
import io.github.yuriimurha.reels.BuildConfig
import io.github.yuriimurha.reels.instagram.web.CookieStore
import io.github.yuriimurha.reels.instagram.web.HttpClientFactory
import io.github.yuriimurha.reels.instagram.web.WebSessionProbe
import io.github.yuriimurha.reels.session.AndroidCookieStore
import io.github.yuriimurha.reels.session.SessionRepository
import io.github.yuriimurha.reels.sync.SessionSignals
import io.github.yuriimurha.reels.sync.pacing.DataStoreCooldownStore
import io.github.yuriimurha.reels.sync.pacing.Pacer
import io.github.yuriimurha.reels.sync.pacing.PacingPolicy
import io.github.yuriimurha.reels.sync.pacing.RoomRequestLog
import okhttp3.OkHttpClient
```
add these properties below `videoResolver`:
```kotlin
    val cookieStore: CookieStore by lazy { AndroidCookieStore() }

    /** Paces every request to real Instagram; one per process for real traffic (spec 7.3). */
    val instagramPacer: Pacer by lazy {
        Pacer(PacingPolicy.Conservative, RoomRequestLog(db.apiRequestDao()), DataStoreCooldownStore(settings))
    }

    private val instagramHttp: OkHttpClient by lazy {
        HttpClientFactory.create(
            cookies = cookieStore,
            userAgent = WebSettings.getDefaultUserAgent(context),
            logger = if (BuildConfig.DEBUG) { line -> Log.d("InstagramHttp", line) } else null,
        )
    }

    val session: SessionRepository by lazy {
        SessionRepository(cookieStore, WebSessionProbe(instagramHttp, cookieStore), instagramPacer, settings)
    }
```
and replace `fun syncEngine()` with:
```kotlin
    fun syncEngine(): SyncEngine {
        // Exhaustive on purpose: adding Backend.Real (M4) forces a decision about session signals.
        val signals: SessionSignals = when (backend) {
            is Backend.Fake -> SessionSignals.None
        }
        return SyncEngine(backend.client, backend.pacer, db, backend.fetcher, thumbnails, signals)
    }
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS. That's 10 new tests, `FastPolicyGuardTest` still passes (the container uses `Conservative`), and everything earlier passes too.

- [ ] **Step 7: Record the component and commit**

Append to the `## Components` table in `ARCHITECTURE.md`:
```markdown
| Session | `app/.../session/` | The session lives only in the WebView's `CookieManager` (`AndroidCookieStore`), shared with OkHttp. `SessionRepository` validates via one paced interactive request, stores only state + handle in DataStore, accepts pasted sessionids (parsed locally, no request for garbage), logs out by clearing cookies. Real traffic uses `AppContainer.instagramPacer` (Conservative, Room request log, DataStore cooldown). |
```

```bash
git add app/src/main/kotlin/io/github/yuriimurha/reels/session app/src/main/kotlin/io/github/yuriimurha/reels/data/settings/SettingsStore.kt app/src/main/kotlin/io/github/yuriimurha/reels/di/AppContainer.kt app/src/test/kotlin/io/github/yuriimurha/reels/session ARCHITECTURE.md
git commit -m "feat(session): cookie-backed session with paced validation and paste fallback" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 18: Login screen, session UI and the on-device check

**Files:**
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/ui/login/LoginViewModel.kt`
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/ui/login/LoginScreen.kt`
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/ui/sync/SessionSection.kt`
- Modify: `app/src/main/kotlin/io/github/yuriimurha/reels/ui/sync/SyncViewModel.kt`
- Modify: `app/src/main/kotlin/io/github/yuriimurha/reels/ui/sync/SyncScreen.kt`
- Modify: `app/src/main/kotlin/io/github/yuriimurha/reels/ui/ReelsNavHost.kt`
- Modify: `app/src/main/kotlin/io/github/yuriimurha/reels/ReelsApp.kt`
- Test: `app/src/test/kotlin/io/github/yuriimurha/reels/ui/login/LoginViewModelTest.kt`
- Test: `app/src/test/kotlin/io/github/yuriimurha/reels/ui/sync/SessionSectionTest.kt`
- Modify: `ARCHITECTURE.md`, `PROGRESS.md`, `TODO.md`

**Interfaces:**
- Consumes: `LoginSession`, `SessionRepository`, `SessionState` (Task 17); `SyncViewModel`, `SyncScreen`, `SyncContent` (Task 14); `SyncRoute` (Task 11).
- Produces:
  - `LoginViewModel(session: LoginSession)` with `status: StateFlow<Status>` (`Waiting`, `Checking`, `Done(state)`, `Failed(message)`), `onCookiesMaybeReady()`, `retry()`
  - `LoginScreen(startUrl: String?, onDone, onBack)` and the route `LoginRoute(url: String? = null)`
  - `SessionSection(state, message, onLogin, onResolveChallenge, onLogout, onCheck, onPaste)` and `PasteSessionDialog(error, onSubmit, onDismiss)`
  - `SyncScreen(onBack, onOpenLogin: (String?) -> Unit)`

- [ ] **Step 1: Write the failing tests**

`app/src/test/kotlin/io/github/yuriimurha/reels/ui/login/LoginViewModelTest.kt`:
```kotlin
package io.github.yuriimurha.reels.ui.login

import io.github.yuriimurha.reels.instagram.InstagramException
import io.github.yuriimurha.reels.session.LoginSession
import io.github.yuriimurha.reels.session.SessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {
    private val session = FakeLoginSession()

    @BeforeTest
    fun setMain() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest
    fun resetMain() = Dispatchers.resetMain()

    @Test
    fun validatesOnceWhenTheCookiesAppear() = runTest {
        val viewModel = LoginViewModel(session)
        viewModel.onCookiesMaybeReady()
        advanceUntilIdle()
        assertEquals(0, session.validations)

        session.sessionId = "s1"
        viewModel.onCookiesMaybeReady()
        advanceUntilIdle()
        assertEquals(1, session.validations)
        assertEquals(LoginViewModel.Status.Done(SessionState.Valid("tester")), viewModel.status.value)
    }

    @Test
    fun doesNotRevalidateSameSessionId() = runTest {
        session.sessionId = "s1"
        session.result = { SessionState.Expired(null) }
        val viewModel = LoginViewModel(session)
        repeat(5) {
            viewModel.onCookiesMaybeReady()
            advanceUntilIdle()
        }
        assertEquals(1, session.validations, "each validation is an Instagram request")
    }

    @Test
    fun aNewSessionIdIsChecked() = runTest {
        session.sessionId = "s1"
        session.result = { SessionState.Expired(null) }
        val viewModel = LoginViewModel(session)
        viewModel.onCookiesMaybeReady()
        advanceUntilIdle()
        session.sessionId = "s2"
        viewModel.onCookiesMaybeReady()
        advanceUntilIdle()
        assertEquals(2, session.validations)
    }

    @Test
    fun retryChecksTheSameSessionAgain() = runTest {
        session.sessionId = "s1"
        session.result = { SessionState.Challenge("https://www.instagram.com/challenge/x/", null) }
        val viewModel = LoginViewModel(session)
        viewModel.onCookiesMaybeReady()
        advanceUntilIdle()
        session.result = { SessionState.Valid("tester") }
        viewModel.retry()
        viewModel.onCookiesMaybeReady()
        advanceUntilIdle()
        assertEquals(2, session.validations)
        assertEquals(LoginViewModel.Status.Done(SessionState.Valid("tester")), viewModel.status.value)
    }

    @Test
    fun failuresAreReported() = runTest {
        session.sessionId = "s1"
        session.result = { throw InstagramException.Transient() }
        val viewModel = LoginViewModel(session)
        viewModel.onCookiesMaybeReady()
        advanceUntilIdle()
        assertIs<LoginViewModel.Status.Failed>(viewModel.status.value)
    }

    private class FakeLoginSession : LoginSession {
        var sessionId: String? = null
        var result: () -> SessionState = { SessionState.Valid("tester") }
        var validations = 0

        override fun currentSessionId(): String? = sessionId

        override fun hasSessionCookies(): Boolean = sessionId != null

        override suspend fun validate(): SessionState {
            validations++
            return result()
        }
    }
}
```

`app/src/test/kotlin/io/github/yuriimurha/reels/ui/sync/SessionSectionTest.kt`:
```kotlin
package io.github.yuriimurha.reels.ui.sync

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.session.SessionState
import io.github.yuriimurha.reels.ui.theme.ReelsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class SessionSectionTest {
    @get:Rule
    val compose = createComposeRule()

    private var resolved: String? = "unset"

    private fun show(state: SessionState) = compose.setContent {
        ReelsTheme {
            SessionSection(
                state = state,
                message = null,
                onLogin = {},
                onResolveChallenge = { resolved = it },
                onLogout = {},
                onCheck = {},
                onPaste = {},
            )
        }
    }

    @Test
    fun loggedOutOffersLoginAndPaste() {
        show(SessionState.LoggedOut)
        compose.onNodeWithText("Log in").assertIsDisplayed()
        compose.onNodeWithText("Paste sessionid").assertIsDisplayed()
    }

    @Test
    fun validShowsTheHandleAndLogout() {
        show(SessionState.Valid("tester"))
        compose.onNodeWithText("Logged in as @tester").assertIsDisplayed()
        compose.onNodeWithText("Log out").assertIsDisplayed()
    }

    @Test
    fun challengeOpensItsUrl() {
        show(SessionState.Challenge("https://www.instagram.com/challenge/x/", "tester"))
        compose.onNodeWithText("Resolve on Instagram").performClick()
        assertEquals("https://www.instagram.com/challenge/x/", resolved)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.yuriimurha.reels.ui.login.*" --tests "io.github.yuriimurha.reels.ui.sync.SessionSectionTest"`
Expected: FAIL to compile.

- [ ] **Step 3: Implement the login screen**

`app/src/main/kotlin/io/github/yuriimurha/reels/ui/login/LoginViewModel.kt`:
```kotlin
package io.github.yuriimurha.reels.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yuriimurha.reels.session.LoginSession
import io.github.yuriimurha.reels.session.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

class LoginViewModel(private val session: LoginSession) : ViewModel() {
    sealed interface Status {
        data object Waiting : Status
        data object Checking : Status
        data class Done(val state: SessionState) : Status
        data class Failed(val message: String) : Status
    }

    private val mutableStatus = MutableStateFlow<Status>(Status.Waiting)
    val status: StateFlow<Status> = mutableStatus

    private var lastChecked: String? = null

    /**
     * Called every second by the screen. Reading cookies is local and free; validating is an Instagram
     * request, so each sessionid value is checked at most once until the owner asks to [retry].
     */
    fun onCookiesMaybeReady() {
        if (mutableStatus.value is Status.Checking || !session.hasSessionCookies()) return
        val sessionId = session.currentSessionId() ?: return
        if (sessionId == lastChecked) return
        lastChecked = sessionId
        mutableStatus.value = Status.Checking
        viewModelScope.launch {
            mutableStatus.value = try {
                Status.Done(session.validate())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Status.Failed(e.message ?: "Couldn't check the session")
            }
        }
    }

    /** The owner finished something in the WebView (a challenge, say) and wants the same session checked again. */
    fun retry() {
        lastChecked = null
        mutableStatus.value = Status.Waiting
    }
}
```

`app/src/main/kotlin/io/github/yuriimurha/reels/ui/login/LoginScreen.kt`:
```kotlin
package io.github.yuriimurha.reels.ui.login

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.yuriimurha.reels.session.SessionState
import io.github.yuriimurha.reels.ui.LocalAppContainer
import kotlinx.coroutines.delay

private const val LOGIN_URL = "https://www.instagram.com/accounts/login/"

/** Instagram's own login page in a WebView (spec 9.6). The session lands in CookieManager, shared with the API client. */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(startUrl: String?, onDone: () -> Unit, onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val viewModel = viewModel { LoginViewModel(container.session) }
    val status by viewModel.status.collectAsStateWithLifecycle()
    var webView by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            viewModel.onCookiesMaybeReady()
            delay(1_000)
        }
    }
    LaunchedEffect(status) {
        val done = status as? LoginViewModel.Status.Done ?: return@LaunchedEffect
        when (val state = done.state) {
            is SessionState.Valid -> onDone()
            is SessionState.Challenge -> state.challengeUrl?.let { webView?.loadUrl(it) }
            SessionState.LoggedOut, is SessionState.Expired -> Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Log in to Instagram") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when (val current = status) {
                LoginViewModel.Status.Waiting -> Unit
                LoginViewModel.Status.Checking -> LinearProgressIndicator(Modifier.fillMaxWidth())
                is LoginViewModel.Status.Failed -> RetryBar(current.message, viewModel::retry)
                is LoginViewModel.Status.Done -> when (current.state) {
                    is SessionState.Challenge -> RetryBar("Instagram wants verification. Finish it below, then check again.", viewModel::retry)
                    is SessionState.Expired, SessionState.LoggedOut ->
                        RetryBar("That session isn't valid yet. Finish logging in, then check again.", viewModel::retry)
                    is SessionState.Valid -> Unit
                }
            }
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        CookieManager.getInstance().setAcceptCookie(true)
                        webViewClient = InstagramOnlyClient()
                        loadUrl(startUrl ?: LOGIN_URL)
                    }.also { webView = it }
                },
                onRelease = { it.destroy() },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Keeps every page inside the WebView and never hands a link to another app (such as the Instagram app on another account). */
private class InstagramOnlyClient : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        request.url.scheme != "https"
}

@Composable
private fun RetryBar(message: String, onRetry: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onRetry) { Text("Check again") }
    }
}
```

- [ ] **Step 4: Implement the session section**

`app/src/main/kotlin/io/github/yuriimurha/reels/ui/sync/SessionSection.kt`:
```kotlin
package io.github.yuriimurha.reels.ui.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.yuriimurha.reels.session.SessionState

@Composable
fun SessionSection(
    state: SessionState,
    message: String?,
    onLogin: () -> Unit,
    onResolveChallenge: (String?) -> Unit,
    onLogout: () -> Unit,
    onCheck: () -> Unit,
    onPaste: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Instagram session", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        when (state) {
            SessionState.LoggedOut -> {
                Text("Not logged in")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onLogin) { Text("Log in") }
                    TextButton(onClick = onPaste) { Text("Paste sessionid") }
                }
            }
            is SessionState.Valid -> {
                Text("Logged in as @${state.handle}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onCheck) { Text("Check now") }
                    TextButton(onClick = onLogout) { Text("Log out") }
                }
            }
            is SessionState.Expired -> {
                Text("Session expired" + (state.handle?.let { " (@$it)" } ?: ""))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onLogin) { Text("Log in again") }
                    TextButton(onClick = onPaste) { Text("Paste sessionid") }
                }
            }
            is SessionState.Challenge -> {
                Text("Instagram wants verification")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onResolveChallenge(state.challengeUrl) }) { Text("Resolve on Instagram") }
                    TextButton(onClick = onCheck) { Text("Check now") }
                }
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

/** The fallback from spec D3. The value is hidden while typing and is stored only in the WebView's cookie jar. */
@Composable
fun PasteSessionDialog(error: String?, onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paste sessionid") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Copy it from a mobile browser on this phone that is logged into the test account.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    isError = error != null,
                    visualTransformation = PasswordVisualTransformation(),
                    supportingText = error?.let { { Text(it) } },
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(text) }, enabled = text.isNotBlank()) { Text("Use it") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
```

- [ ] **Step 5: Add the session to the Sync screen**

In `app/src/main/kotlin/io/github/yuriimurha/reels/ui/sync/SyncViewModel.kt`:
- add the imports `io.github.yuriimurha.reels.session.SessionRepository`, `io.github.yuriimurha.reels.session.SessionState`, `kotlinx.coroutines.flow.MutableStateFlow`, `kotlin.coroutines.cancellation.CancellationException`
- add `private val session: SessionRepository,` to the constructor, after `pacer`
- add these members at the end of the class:
```kotlin
    val sessionState: StateFlow<SessionState> = session.state.stateIn(viewModelScope, sharing, SessionState.LoggedOut)

    private val mutableSessionMessage = MutableStateFlow<String?>(null)
    val sessionMessage: StateFlow<String?> = mutableSessionMessage

    private val mutablePasteError = MutableStateFlow<String?>(null)
    val pasteError: StateFlow<String?> = mutablePasteError

    fun checkSession() {
        viewModelScope.launch {
            mutableSessionMessage.value = null
            try {
                session.validate()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableSessionMessage.value = e.message
            }
        }
    }

    fun logout() {
        viewModelScope.launch { session.logout() }
    }

    /** [onAccepted] gets whether the WebView still needs to fetch a csrftoken (spec 9.6). */
    fun paste(input: String, onAccepted: (needsCsrf: Boolean) -> Unit) {
        viewModelScope.launch {
            val result = try {
                session.pasteSessionId(input)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutablePasteError.value = e.message ?: "Couldn't check that session"
                return@launch
            }
            if (result == null) {
                mutablePasteError.value = "That doesn't look like a sessionid"
            } else {
                mutablePasteError.value = null
                onAccepted(!session.hasCsrfToken())
            }
        }
    }

    fun clearPasteError() {
        mutablePasteError.value = null
    }
```

In `app/src/main/kotlin/io/github/yuriimurha/reels/ui/sync/SyncScreen.kt`:
- change the signature to `fun SyncScreen(onBack: () -> Unit, onOpenLogin: (String?) -> Unit)`
- change the ViewModel line to:
```kotlin
    val viewModel = viewModel {
        SyncViewModel(container.syncController, container.library, container.backend.pacer, container.session)
    }
```
- below `var confirmDelete ...` add:
```kotlin
    val sessionState by viewModel.sessionState.collectAsStateWithLifecycle()
    val sessionMessage by viewModel.sessionMessage.collectAsStateWithLifecycle()
    val pasteError by viewModel.pasteError.collectAsStateWithLifecycle()
    var pasting by remember { mutableStateOf(false) }
```
- pass this argument in the `SyncContent(...)` call, after `modifier = Modifier.padding(padding),`:
```kotlin
            sessionSection = {
                SessionSection(
                    state = sessionState,
                    message = sessionMessage,
                    onLogin = { onOpenLogin(null) },
                    onResolveChallenge = onOpenLogin,
                    onLogout = viewModel::logout,
                    onCheck = viewModel::checkSession,
                    onPaste = { viewModel.clearPasteError(); pasting = true },
                )
            },
```
- after the `if (confirmDelete) { ... }` block add:
```kotlin
    if (pasting) {
        PasteSessionDialog(
            error = pasteError,
            onSubmit = { input ->
                viewModel.paste(input) { needsCsrf ->
                    pasting = false
                    if (needsCsrf) onOpenLogin("https://www.instagram.com/")
                }
            },
            onDismiss = { pasting = false },
        )
    }
```

- [ ] **Step 6: Register the login route and enable WebView debugging**

In `app/src/main/kotlin/io/github/yuriimurha/reels/ui/ReelsNavHost.kt`, add the import `io.github.yuriimurha.reels.ui.login.LoginScreen`, add this route class next to the others:
```kotlin
@Serializable
data class LoginRoute(val url: String? = null)
```
replace the `composable<SyncRoute> { ... }` block with:
```kotlin
        composable<SyncRoute> {
            SyncScreen(
                onBack = { navController.popBackStack() },
                onOpenLogin = { url -> navController.navigate(LoginRoute(url)) },
            )
        }
        composable<LoginRoute> { entry ->
            val route = entry.toRoute<LoginRoute>()
            LoginScreen(
                startUrl = route.url,
                onDone = { navController.popBackStack() },
                onBack = { navController.popBackStack() },
            )
        }
```

In `app/src/main/kotlin/io/github/yuriimurha/reels/ReelsApp.kt`, add the import `android.webkit.WebView` and, as the first line after `super.onCreate()`:
```kotlin
        // Debug builds only: lets chrome://inspect show what the real Instagram site requests (spec 6.3).
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
```

- [ ] **Step 7: Run the tests and the check**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL, including 8 new tests (5 login, 3 session section).

- [ ] **Step 8: On-device check (the owner logs in; the agent never types credentials)**

Install on **the phone** (not the emulator, so the account only ever sees one device) with USB or wireless debugging. In one terminal:
```bash
adb logcat -c
```
```bash
adb logcat -s InstagramHttp
```
Then:
1. `./gradlew installDebug`, open Reels → Sync → **Log in**. **The owner** logs into the throwaway account in the WebView and completes any 2FA there.
2. The screen closes on its own and the Sync screen shows "Logged in as @<handle>". Logcat shows exactly **one** `GET .../api/v1/users/<id>/info/`, with `Cookie: ██` (redacted) and a `200`.
3. Kill the app and reopen it. Sync still shows "Logged in as @<handle>", and logcat shows **no** new request (D7).
4. Tap **Check now** twice quickly. Two requests at least 2 s apart (interactive lane).
5. **Log out**: "Not logged in". **Log in** again works.
6. Optional: log out, copy `sessionid` from a mobile browser on this phone logged into the test account, then **Paste sessionid**. "Logged in as @<handle>".

If step 2 shows "Adapter needs repair" (for example `http.404`) or a `ShapeChanged` path instead:
- On the Mac, open `chrome://inspect/#devices`, click **inspect** under the app's WebView, and open the **Network** tab. In the WebView, open the account's profile page.
- Find an `/api/v1/` request whose response contains the logged-in username. Note its path and the `X-IG-App-ID` request header.
- If the path differs, change `WebEndpoints.currentUser` and the field names in `WebSessionProbe`, then update `WebSessionProbeTest` so it expects the new path and shape (keep the values fake).
- If `X-IG-App-ID` differs from `WebHeaders.APP_ID`, update the constant (the test references it, so no test change).
- Re-run `./gradlew :instagram:test`, reinstall, and repeat from step 1.

Record what was confirmed (endpoint, app id) in `ARCHITECTURE.md`'s `:instagram` row.

- [ ] **Step 9: Close milestone M2 in the docs**

Append to the `## Components` table in `ARCHITECTURE.md`:
```markdown
| Login | `app/.../ui/login/`, `ui/sync/SessionSection.kt` | Instagram's own login page in a WebView (https only, links never leave the app). A 1 s local cookie poll triggers one validation per new sessionid; "Check again" re-validates on request. Sync shows session state, Check now, Log out, Paste sessionid (masked). |
```
Change `## Status` to: `M2 complete: real login and session validation on the phone; the library still comes from the fake backend until M4.`

Append to `PROGRESS.md`:
```markdown

## 2026-10-06: M2 session

- WebView login on the phone; cookies shared with OkHttp; one paced validation per new session.
- Confirmed on device: currentUser endpoint `<path>`, X-IG-App-ID `<value>` (fill in from Task 18 Step 8).
- Paste fallback, logout and persisted session state work; HTTP logs redact cookies.
```
(Use the actual date and fill in the confirmed values.) In `TODO.md`, tick `M2 Session` and replace the `Write the implementation plan` item with `Write the M3 adapter-spike plan`.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/kotlin/io/github/yuriimurha/reels/ui/login app/src/main/kotlin/io/github/yuriimurha/reels/ui/sync app/src/main/kotlin/io/github/yuriimurha/reels/ui/ReelsNavHost.kt app/src/main/kotlin/io/github/yuriimurha/reels/ReelsApp.kt app/src/test/kotlin/io/github/yuriimurha/reels/ui/login app/src/test/kotlin/io/github/yuriimurha/reels/ui/sync/SessionSectionTest.kt instagram/src ARCHITECTURE.md PROGRESS.md TODO.md
git commit -m "feat(ui): WebView login, session controls and device-confirmed probe" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Follow-up plans (not part of this plan)

Each one gets its own plan, written after the previous milestone lands. Use the same skill and the same spec.

- **M3 Adapter spike (on the phone):** a debug-only "Adapter lab" screen that calls each spec 6.2 candidate once through `instagramPacer` and shows redacted response shapes. Export scrubbed fixtures and answer the seven questions in spec 6.3. Then write `WebInstagramClient : InstagramClient` (collections, saved media, media info, media-type mapping, the 720 px thumbnail choice, CDN expiry), with golden fixture tests and the fixture guard test (spec 10). The answer on `saved_collection_ids` sets `reportsSavedCollectionIds`.
- **M4 Real sync:** add `Backend.Real(client, HttpMediaFetcher, instagramPacer)`, which forces the `when` in `AppContainer.syncEngine()` to pass `session` as the signals. Add a debug-only mock-mode toggle (restart to apply), gate Sync on a valid session, and run the first real quick and full syncs on the test account within budget.
- **M5 Video:** add the real `VideoSourceResolver`: use the stored link until 10 minutes before expiry, otherwise call `mediaInfo` on the interactive lane. Retry once on 403/410. Use a `CacheDataSource` keyed by media `pk`, an LRU cap setting with "Clear video cache", a refreshed link for the next item, and evict the cached video on reconcile removal.
- **M6 Polish:** release signing from `keystore.properties`, a baseline profile, judging smoothness on `installRelease`, and the README (getting the `sessionid`, running, sync behaviour, the private-endpoint note).
