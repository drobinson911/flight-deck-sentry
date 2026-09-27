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
 * 0.4.5: EVERY notification builder in the app (not only traffic) must draw our own card: the test scans the sources
 * for every `Notification*.Builder(` and fails if one could fall back to the system template or text style.
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

    private val bannerLayouts: List<String> get() =
        File(res, "layout").listFiles().orEmpty().map { it.name }.filter { it.startsWith("banner_") && it.endsWith(".xml") }
            .map { it.removeSuffix(".xml") }.sorted()

    @Test fun bannerLayoutsUseNoSystemNotificationTheme() {
        assertEquals(listOf("banner_2line", "banner_4line", "banner_headsup", "banner_info", "banner_info_big"), bannerLayouts)
        for (n in bannerLayouts) {
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

    private val src = File("src/main/java").takeIf { it.isDirectory } ?: File("app/src/main/java")
    private val sources: Map<String, String> get() =
        src.walkTopDown().filter { it.isFile && (it.name.endsWith(".kt") || it.name.endsWith(".java")) }
            .associate { it.name to stripComments(it.readText()) }

    /** Code only: block and line comments out (a comment may say "no BigTextStyle"); "http://" in strings is kept. */
    private fun stripComments(t: String) = t.replace(Regex("""/\*[\s\S]*?\*/""")) { m -> "\n".repeat(m.value.count { it == '\n' }) }.replace(Regex("""(?<![:"])//.*"""), "")

    /** The statement a builder starts: from `Builder(` to the matching `.build()` (or the end of the call). */
    private fun chainFrom(text: String, at: Int): String {
        val end = text.indexOf(".build()", at).let { if (it < 0) text.length else it }
        return text.substring(at, end)
    }

    @Test fun everyNotificationBuilderDrawsOurOwnCard() {
        val found = ArrayList<String>()
        for ((file, text) in sources) {
            // no system text styles anywhere in the app's notifications
            for (bad in listOf("BigTextStyle", "InboxStyle", "DecoratedCustomViewStyle", "MessagingStyle", "BigPictureStyle",
                    "setStyle(", "TextAppearance.Compat.Notification", "TextAppearance.Material.Notification")) {
                assertFalse("$file uses the system notification style $bad", text.contains(bad))
            }
            for (m in Regex("""(NotificationCompat|Notification)\.Builder\(""").findAll(text)) {
                val line = text.substring(0, m.range.first).count { it == '\n' } + 1
                found += "$file:$line"
                val before = text.substring(maxOf(0, m.range.first - 12), m.range.first)
                if (before.endsWith("infoCard(")) continue          // the shared dark card (checked below)
                val chain = chainFrom(text, m.range.first)
                for (need in listOf("setCustomContentView(", "setCustomBigContentView(", "setCustomHeadsUpContentView(")) {
                    assertTrue("$file:$line builds a notification without $need (system template)", chain.contains(need))
                }
            }
        }
        println("Notification builders: " + found.sorted().joinToString())
        // traffic, housekeeping, status, update notice (Notifier.kt): a new builder must be added here knowingly
        assertEquals(found.sorted().toString(), 4, found.size)
        val notifier = sources.getValue("Notifier.kt")
        val card = notifier.substringAfter("fun infoCard(").substringBefore("\n        }\n")
        for (need in listOf("setCustomContentView(rv(R.layout.banner_info,", "setCustomBigContentView(big)",
                "setCustomHeadsUpContentView(rv(R.layout.banner_info,", "rv(R.layout.banner_info_big,",
                "setTextColor(R.id.bTitle, tone.argb)", "setTextColor(R.id.bLine2, BannerLook.INK)")) {
            assertTrue("infoCard misses $need", card.contains(need))
        }
        // every layout the app inflates into a notification is one of the checked banner_* layouts
        Regex("""R\.layout\.(\w+)""").findAll(notifier).map { it.groupValues[1] }.toSet().forEach {
            assertTrue("Notifier uses layout $it outside the checked banner_* set", it in bannerLayouts)
        }
    }
}
