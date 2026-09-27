package com.uasflightdeck.sentry

import com.uasflightdeck.sentry.core.BannerLook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.4.4: the banner layouts must never take a colour from the system notification theme (0.4.2/0.4.3 body text was
 * black on the RC Plus's dark heads-up), and the XML card colours must equal core [BannerLook].
 */
class BannerColorsTest {
    private val res = File("src/main/res").takeIf { it.isDirectory } ?: File("app/src/main/res")
    private fun layout(n: String) = File(res, "layout/$n.xml").readText()
    private fun color(n: String): Int {
        val m = Regex("<color name=\"$n\">#([0-9A-Fa-f]{6})</color>").find(File(res, "values/colors.xml").readText())
            ?: error("color $n missing")
        return (0xFF000000 or m.groupValues[1].toLong(16)).toInt()
    }

    @Test fun xmlCardColoursEqualCore() {
        assertEquals(BannerLook.BG, color("banner_bg"))
        assertEquals(BannerLook.INK, color("banner_ink"))
        assertEquals(BannerLook.BUTTON, color("banner_button"))
    }

    @Test fun bannerLayoutsUseNoSystemNotificationTheme() {
        for (n in listOf("banner_headsup", "banner_4line", "banner_2line")) {
            val x = layout(n)
            assertFalse("$n uses a system text appearance", x.contains("TextAppearance"))
            assertFalse("$n uses a theme attr colour", x.contains("?android:attr") || x.contains("?attr"))
            assertTrue("$n has no own background", x.contains("android:background=\"@drawable/banner_bg\""))
            // every TextView gets an explicit colour through the Sentry.Banner* styles
            val tvs = Regex("<TextView[^>]*>").findAll(x).map { it.value }.toList()
            assertTrue(tvs.isNotEmpty())
            tvs.forEach { assertTrue("$n: $it", it.contains("@style/Sentry.BannerText") || it.contains("@style/Sentry.BannerButton")) }
        }
        val themes = File(res, "values/themes.xml").readText()
        for (s in listOf("Sentry.BannerText", "Sentry.BannerButton")) {
            val block = themes.substringAfter("<style name=\"$s\"").substringBefore("</style>")
            assertTrue("$s sets textColor", block.contains("<item name=\"android:textColor\">@color/banner_ink</item>"))
        }
    }

    @Test fun headsUpHasTwoTextRowsPlusActions() {
        val x = layout("banner_headsup")
        // title + where on row 1, closure + hint on row 2: four text ids, three buttons, no more text rows
        listOf("bTitle", "bLine2", "bLine3", "bLine4", "bActions", "bGotIt", "bIgnore", "bQuiet").forEach { assertTrue(it, x.contains("@+id/$it")) }
        assertEquals(4 + 3, Regex("<TextView").findAll(x).count())
    }
}
