package io.github.yuriimurha.reels.data.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.BuildConfig
import io.github.yuriimurha.reels.testutil.inMemoryDb
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertTrue

/** What the build must do when the database schema changes (it runs from the app module directory). */
@RunWith(AndroidJUnit4::class)
class SchemaBuildGuardTest {
    /**
     * R21: the app's `versionCode` moves with the database. A build whose code is not lower than the one that upgraded the
     * library would install over it without complaint and then fail at every start, since Room has no way back (there is no
     * destructive fallback, and there must be none: it would wipe the library and the request log). With the code at least
     * the schema version, Android refuses the older build (`INSTALL_FAILED_VERSION_DOWNGRADE`) before anything changes.
     */
    @Test
    fun theVersionCodeIsAtLeastTheDatabaseVersion() {
        val db = inMemoryDb()
        val schema = try {
            db.openHelper.readableDatabase.version
        } finally {
            db.close()
        }
        assertTrue(
            BuildConfig.VERSION_CODE >= schema,
            "versionCode ${BuildConfig.VERSION_CODE} is below the database version $schema: raise it in app/build.gradle.kts",
        )
    }

    /**
     * D4: the exported schemas are debug assets (R5), which the migration test reads. Their merge must run after Room has
     * written them, or the first build after a schema change merges the old files. `./gradlew :app:mergeDebugAssets --dry-run`
     * lists `copyRoomSchemas` first; this pins the line that makes it so.
     */
    @Test
    fun theSchemaAssetsAreMergedAfterRoomWritesThem() {
        val script = File("build.gradle.kts").also { assertTrue(it.isFile, "${it.path} is missing") }.readText()
        val dependency = Regex("""tasks\.matching\s*\{\s*it\.name\s*==\s*"mergeDebugAssets"\s*}\s*\.configureEach\s*\{\s*dependsOn\("copyRoomSchemas"\)\s*}""")
        assertTrue(dependency.containsMatchIn(script), "mergeDebugAssets must depend on copyRoomSchemas")
    }
}
