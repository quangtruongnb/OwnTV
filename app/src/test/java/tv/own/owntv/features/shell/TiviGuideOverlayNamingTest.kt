package tv.own.owntv.features.shell

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TiviGuideOverlayNamingTest {
    @Test
    fun `guide overlay uses the product-neutral TiviGuideOverlay name`() {
        val component = File("src/main/java/tv/own/owntv/features/shell/components/TiviGuideOverlay.kt")

        assertTrue("the guide component must use its product-neutral filename", component.isFile)
        assertFalse(
            "the implementation must not retain the third-party product filename",
            File("src/main/java/tv/own/owntv/features/shell/components/TiviMateGuideOverlay.kt").isFile,
        )
        assertTrue(component.readText().contains("fun TiviGuideOverlay("))
    }
}
