package com.juggling.tracker.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.juggling.tracker.billing.PricingPhase
import com.juggling.tracker.billing.SubscriptionAccess
import com.juggling.tracker.billing.SubscriptionOffer
import com.juggling.tracker.billing.SubscriptionStatus
import com.juggling.tracker.ui.theme.JugglingTrackerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The app opens for subscribers and shows the paywall, with Play's price, to everyone else. */
@RunWith(RobolectricTestRunner::class)
// A full phone screen, so the whole paywall fits without scrolling.
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class PaywallTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val monthly = SubscriptionOffer("monthly", null, "token", listOf(PricingPhase("€2.99", "P1M", 2_990_000)))

    @Test
    fun subscribersSeeTheApp() {
        show(SubscriptionAccess(SubscriptionStatus.ACTIVE, monthly, unlocked = true))

        composeTestRule.onNodeWithText("The app").assertIsDisplayed()
        composeTestRule.onNodeWithText("Subscribe").assertDoesNotExist()
    }

    @Test
    fun othersSeeThePaywallWithThePrice() {
        var subscribeClicks = 0
        show(SubscriptionAccess(SubscriptionStatus.NOT_SUBSCRIBED, monthly, unlocked = false), onSubscribe = { subscribeClicks++ })

        composeTestRule.onNodeWithText("The app").assertDoesNotExist()
        composeTestRule.onNodeWithText("€2.99 per month").assertIsDisplayed()
        composeTestRule.onNodeWithText("Subscribe").assertIsEnabled().performClick()
        assertEquals(1, subscribeClicks)
    }

    @Test
    fun subscribeWaitsForAPriceFromPlay() {
        show(SubscriptionAccess(SubscriptionStatus.UNAVAILABLE, offer = null, unlocked = false))

        composeTestRule.onNodeWithText("Subscribe").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Google Play cannot be reached. Check your connection and try again.").assertIsDisplayed()
    }

    @Test
    fun aPendingPaymentSaysSoAndCannotBeBoughtTwice() {
        show(SubscriptionAccess(SubscriptionStatus.PENDING, monthly, unlocked = false))

        composeTestRule.onNodeWithText("Your payment is pending. The app opens once Google Play confirms it.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Subscribe").assertIsNotEnabled()
    }

    @Test
    fun checkAgainAsksPlayAgain() {
        var checks = 0
        show(SubscriptionAccess(SubscriptionStatus.NOT_SUBSCRIBED, monthly, unlocked = false), onCheckAgain = { checks++ })

        composeTestRule.onNodeWithText("Already subscribed? Check again").performClick()
        assertEquals(1, checks)
    }

    @Test
    fun theAppOpensOnceTheSubscriptionGoesThrough() {
        var access by mutableStateOf(SubscriptionAccess(SubscriptionStatus.NOT_SUBSCRIBED, monthly, unlocked = false))
        composeTestRule.setContent {
            JugglingTrackerTheme {
                SubscriptionGate(access, onSubscribe = {}, onCheckAgain = {}) { Text("The app") }
            }
        }
        composeTestRule.onNodeWithText("The app").assertDoesNotExist()

        access = SubscriptionAccess(SubscriptionStatus.ACTIVE, monthly, unlocked = true)

        composeTestRule.onNodeWithText("The app").assertIsDisplayed()
    }

    private fun show(access: SubscriptionAccess, onSubscribe: () -> Unit = {}, onCheckAgain: () -> Unit = {}) {
        composeTestRule.setContent {
            JugglingTrackerTheme {
                SubscriptionGate(access, onSubscribe = onSubscribe, onCheckAgain = onCheckAgain) { Text("The app") }
            }
        }
    }
}
