package io.androllm.feature.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import io.androllm.core.models.MessageRole
import io.androllm.feature.chat.ui.components.ComposeInputArea
import io.androllm.feature.chat.ui.components.MessageBubble
import io.androllm.feature.chat.ui.components.NewChatEmptyState
import org.junit.Rule
import org.junit.Test

class ChatScreenUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun messageBubble_rendersUserMessage() {
        val message = ChatMessage(
            id = "1",
            conversationId = "conv1",
            role = MessageRole.USER,
            content = "Hello from user UI test",
            timestamp = System.currentTimeMillis()
        )

        composeTestRule.setContent {
            MessageBubble(message = message)
        }

        composeTestRule.onNodeWithText("Hello from user UI test").assertIsDisplayed()
    }

    @Test
    fun newChatEmptyState_displaysSuggestions() {
        composeTestRule.setContent {
            NewChatEmptyState(onSuggestionClick = {})
        }

        composeTestRule.onNodeWithText("What would you like to ask?").assertIsDisplayed()
        composeTestRule.onNodeWithText("Explain quantum computing simply").assertIsDisplayed()
    }

    @Test
    fun composeInputArea_rendersTextFieldAndSendButton() {
        composeTestRule.setContent {
            ComposeInputArea(
                text = "Draft text",
                onTextChanged = {},
                onSendMessage = {},
                onStopGeneration = {},
                isGenerating = false
            )
        }

        composeTestRule.onNodeWithText("Draft text").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Send message").assertIsDisplayed()
    }
}
