package io.github.yuriimurha.reels.ui.sync

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.ui.theme.ReelsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class DeveloperSectionTest {
    @get:Rule
    val compose = createComposeRule()

    private var opened = 0
    private val mockChanges = mutableListOf<Boolean>()

    private fun show(mockMode: Boolean? = null, mockSwitchEnabled: Boolean = true, labEnabled: Boolean = true) = compose.setContent {
        ReelsTheme {
            DeveloperSection(
                mockMode = mockMode,
                mockSwitchEnabled = mockSwitchEnabled,
                onMockModeChange = { mockChanges += it },
                onOpenLab = { opened++ },
                labEnabled = labEnabled,
            )
        }
    }

    @Test
    fun offersTheAdapterLabAndOpensIt() {
        show()
        compose.onNodeWithText("Developer").assertExists()
        compose.onNodeWithText("Adapter lab").assertIsEnabled().performClick()
        assertEquals(1, opened)
    }

    @Test
    fun theLabButtonCanBeDisabled() {
        show(labEnabled = false)
        compose.onNodeWithText("Adapter lab").assertIsNotEnabled().performClick()
        assertEquals(0, opened)
    }

    /** Task 7 wires the switch; until then the screen passes null and no switch is offered. */
    @Test
    fun noMockSwitchWhenTheModeIsUnknown() {
        show(mockMode = null)
        compose.onAllNodesWithText("Mock mode (fake library)").assertCountEquals(0)
    }

    @Test
    fun theMockSwitchShowsTheModeAndReportsAChange() {
        show(mockMode = true)
        compose.onNodeWithText("Mock mode (fake library)").assertIsEnabled().performClick()
        assertEquals(listOf(false), mockChanges)
        compose.onNodeWithText("The app restarts. Real and fake libraries are kept separately.").assertExists()
    }

    @Test
    fun theMockSwitchCanBeDisabled() {
        show(mockMode = false, mockSwitchEnabled = false)
        compose.onNodeWithText("Mock mode (fake library)").assertIsNotEnabled().performClick()
        assertEquals(emptyList(), mockChanges)
    }
}
