package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CustomThemeBoundsTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun rejectsOversizedThemeJsonBeforeLoadingIntoMemory() {
        val dir = temp.newFolder("oversized_theme")
        val jsonFile = File(dir, "theme.json")
        jsonFile.writeBytes(ByteArray((CustomThemeRepository.MAX_THEME_JSON_BYTES + 1).toInt()) { ' '.code.toByte() })

        try {
            CustomThemeRepository.readJsonText(jsonFile)
            fail("Expected IllegalStateException for oversized theme.json")
        } catch (_: IllegalStateException) {
            // Expected.
        }
    }

    @Test
    fun capsLayerCountAndRejectsPathTraversalAssets() {
        val dir = temp.newFolder("bounded_theme")
        val secretOutside = File(temp.root, "outside.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val layersJson = (0 until 200).joinToString(",") { idx ->
            """{"id":"l$idx","type":"image","src":"../${secretOutside.name}"}"""
        }
        File(dir, "theme.json").writeText("""{"id":"bounded","name":"Bounded","layers":[$layersJson]}""")

        val spec = CustomThemeRepository.parseForTest(
            CustomThemeEntry("bounded", "Bounded", "", dir),
        )
        assertEquals(CustomThemeRepository.MAX_LAYERS, spec.layers.size)
        spec.layers.forEach { layer ->
            assertNull(layer.src)
        }
    }

    @Test
    fun importsExternalThemesIntoPrivateAppSandboxAndResetsExternalPath() {
        val context = RuntimeEnvironment.getApplication()
        val externalRoot = temp.newFolder("external_tmp_themes")
        val themeDir = File(externalRoot, "univ_sport").apply { mkdirs() }
        File(themeDir, "theme.json").writeText("""{"id":"univ_sport","name":"UNI-V Sport","layers":[]}""")
        File(themeDir, "bg.png").writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))

        AirPlayPersistence.saveClusterThemeDir(context, externalRoot.absolutePath)
        val imported = CustomThemeRepository.importExternalThemesToSandbox(context)

        assertEquals(1, imported)
        assertEquals("", AirPlayPersistence.loadClusterThemeDir(context))
        assertTrue(File(context.filesDir, "themes/univ_sport/theme.json").isFile)
        assertTrue(File(context.filesDir, "themes/univ_sport/bg.png").isFile)
    }
}
