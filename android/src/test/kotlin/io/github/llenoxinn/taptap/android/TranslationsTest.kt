package io.github.llenoxinn.taptap.android

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class TranslationsTest {
    @Test
    fun allThreeLanguagesResolveKeyStrings() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val enRes = resourcesFor(base, "en")
        val filRes = resourcesFor(base, "fil")
        val cebRes = resourcesFor(base, "ceb")

        val keys = listOf(
            R.string.sos_button,
            R.string.priority_critical,
            R.string.status_saved,
            R.string.status_ack,
            R.string.relay_on,
            R.string.not_monitored,
            R.string.onboarding_test_warning,
            R.string.home_test_banner,
            R.string.home_im_safe,
            R.string.home_disaster_mode,
            R.string.home_disaster_mode_hint,
        )

        val unchangedAllowed = setOf(R.string.sos_button, R.string.home_disaster_mode)
        keys.forEach { id ->
            val en = enRes.getString(id)
            val fil = filRes.getString(id)
            val ceb = cebRes.getString(id)
            assertTrue("EN blank for $id", en.isNotBlank())
            assertTrue("FIL blank for $id", fil.isNotBlank())
            assertTrue("CEB blank for $id", ceb.isNotBlank())
            if (id !in unchangedAllowed) {
                assertNotEquals("FIL not translated for $en", en, fil)
                assertNotEquals("CEB not translated for $en", en, ceb)
            }
        }

        // Exact DESIGN.md §4 smoke checks
        assertEquals("Help is coming.", enRes.getString(R.string.status_ack))
        assertEquals("Naabot na. Parating na ang tulong.", filRes.getString(R.string.status_ack))
        assertEquals("Naabot na. Padulong na ang tabang.", cebRes.getString(R.string.status_ack))
        assertTrue(cebRes.getQuantityString(R.plurals.carrying_messages, 3, 3).contains("3"))
    }

    private fun resourcesFor(base: Context, tag: String) =
        base.createConfigurationContext(
            Configuration(base.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(tag))
            },
        ).resources
}
