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
