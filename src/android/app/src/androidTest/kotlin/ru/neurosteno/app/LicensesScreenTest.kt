package ru.neurosteno.app

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.neurosteno.app.licenses.Notice
import ru.neurosteno.app.licenses.Notices
import ru.neurosteno.app.ui.screens.LicenseTextScreen
import ru.neurosteno.app.ui.screens.LicensesScreen
import ru.neurosteno.app.ui.theme.NeuroTheme

/** Экран «Лицензии и модели»: разделы, карточки, переход к тексту лицензии из assets (офлайн). */
@RunWith(AndroidJUnit4::class)
class LicensesScreenTest {
    @get:Rule val rule = createComposeRule()

    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()
    private fun scrollTo(tag: String) = rule.onNode(hasScrollAction()).performScrollToNode(hasTestTag(tag))

    @Test
    fun registryFromAssetsHasAllSectionsAndEveryLicenseTextLoads() {
        val notices = Notices.load(ctx)
        assertTrue(notices.size >= 30)
        for (n in notices) assertNotNull("нет текста лицензии ${n.licenseFile} у ${n.id}", Notices.licenseText(ctx, n))
        assertEquals(setOf("model", "library", "font"), notices.map { it.kind }.toSet())
    }

    @Test
    fun screenShowsSectionsCardsAndOpensNotice() {
        val notices = Notices.load(ctx)
        var opened: Notice? = null
        rule.setContent { NeuroTheme { LicensesScreen(notices, onOpen = { opened = it }, onBack = {}) } }
        rule.onNodeWithTag("licenses_intro").assertIsDisplayed()
        rule.onNodeWithTag("licenses_section_model").assertIsDisplayed()
        rule.onNodeWithTag("notice_asr/gigaam-v3-rnnt-punct-int8").assertIsDisplayed()
        rule.onNodeWithTag("notice_asr/gigaam-v3-rnnt-punct-int8").performClick()
        assertEquals("asr/gigaam-v3-rnnt-punct-int8", opened?.id)
        scrollTo("licenses_section_library"); rule.onNodeWithTag("licenses_section_library").assertIsDisplayed()
        scrollTo("licenses_section_font"); rule.onNodeWithTag("licenses_section_font").assertIsDisplayed()
        scrollTo("notice_font/unbounded"); rule.onNodeWithTag("notice_font/unbounded").assertIsDisplayed()
    }

    @Test
    fun licenseTextScreenShowsTextAndNoteForLinkOnlyNotice() {
        val notice = Notices.load(ctx).first { it.id == "objectbox-native" }
        rule.setContent { NeuroTheme { LicenseTextScreen(notice, Notices.licenseText(ctx, notice), onBack = {}) } }
        rule.onNodeWithTag("license_note").assertIsDisplayed()
        rule.onNodeWithTag("license_text").assertIsDisplayed()
        rule.onNodeWithTag("screen_title").assertIsDisplayed().assertTextContains("ObjectBox", substring = true)
    }

    @Test
    fun missingTextShowsError() {
        val notice = Notices.load(ctx).first()
        rule.setContent { NeuroTheme { LicenseTextScreen(notice, null, onBack = {}) } }
        rule.onNodeWithText("Файл с текстом лицензии не найден.").assertIsDisplayed()
    }
}
