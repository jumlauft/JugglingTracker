package com.juggling.tracker.billing

import com.juggling.tracker.billing.OwnedPurchase.State
import com.juggling.tracker.billing.SubscriptionStatus.ACTIVE
import com.juggling.tracker.billing.SubscriptionStatus.CHECKING
import com.juggling.tracker.billing.SubscriptionStatus.NOT_OFFERED
import com.juggling.tracker.billing.SubscriptionStatus.NOT_SUBSCRIBED
import com.juggling.tracker.billing.SubscriptionStatus.PENDING
import com.juggling.tracker.billing.SubscriptionStatus.UNAVAILABLE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionRulesTest {

    private val ours = SubscriptionProducts.SUBSCRIPTION_ID

    @Test
    fun aPurchasedSubscriptionIsActive() {
        assertEquals(ACTIVE, SubscriptionRules.classify(listOf(OwnedPurchase(listOf(ours), State.PURCHASED)), isOffered = true))
    }

    @Test
    fun anActiveSubscriptionCountsEvenIfThePlayProductIsGone() {
        assertEquals(ACTIVE, SubscriptionRules.classify(listOf(OwnedPurchase(listOf(ours), State.PURCHASED)), isOffered = false))
    }

    @Test
    fun aPendingPaymentIsPending() {
        assertEquals(PENDING, SubscriptionRules.classify(listOf(OwnedPurchase(listOf(ours), State.PENDING)), isOffered = true))
    }

    @Test
    fun otherProductsDoNotUnlock() {
        assertEquals(
            NOT_SUBSCRIBED,
            SubscriptionRules.classify(listOf(OwnedPurchase(listOf("something_else"), State.PURCHASED)), isOffered = true),
        )
    }

    @Test
    fun noPurchasesIsNotSubscribed() {
        assertEquals(NOT_SUBSCRIBED, SubscriptionRules.classify(emptyList(), isOffered = true))
    }

    @Test
    fun aProductMissingFromPlayIsNotOffered() {
        assertEquals(NOT_OFFERED, SubscriptionRules.classify(emptyList(), isOffered = false))
    }

    @Test
    fun onlyAPlainNoLocksTheApp() {
        assertEquals(true, SubscriptionRules.isUnlocked(ACTIVE, lastKnownActive = false))
        assertEquals(false, SubscriptionRules.isUnlocked(NOT_SUBSCRIBED, lastKnownActive = true))
        assertEquals(false, SubscriptionRules.isUnlocked(PENDING, lastKnownActive = true))
    }

    @Test
    fun aReleaseBeforeTheProductIsSetUpLocksNobodyOut() {
        assertEquals(true, SubscriptionRules.isUnlocked(NOT_OFFERED, lastKnownActive = null))
        assertEquals(true, SubscriptionRules.isUnlocked(NOT_OFFERED, lastKnownActive = false))
    }

    @Test
    fun whilePlayIsAskedTheLastAnswerStands() {
        assertEquals(true, SubscriptionRules.isUnlocked(CHECKING, lastKnownActive = true))
        assertEquals(false, SubscriptionRules.isUnlocked(CHECKING, lastKnownActive = false))
        assertNull(SubscriptionRules.isUnlocked(CHECKING, lastKnownActive = null))
    }

    @Test
    fun anUnreachablePlayKeepsSubscribersInAndLetsNewUsersIn() {
        assertEquals(true, SubscriptionRules.isUnlocked(UNAVAILABLE, lastKnownActive = true))
        assertEquals(false, SubscriptionRules.isUnlocked(UNAVAILABLE, lastKnownActive = false))
        assertEquals(true, SubscriptionRules.isUnlocked(UNAVAILABLE, lastKnownActive = null))
    }

    @Test
    fun testersAreAlwaysLetIn() {
        assertEquals(true, SubscriptionRules.isUnlocked(NOT_SUBSCRIBED, lastKnownActive = false, isTester = true))
        assertEquals(true, SubscriptionRules.isUnlocked(CHECKING, lastKnownActive = null, isTester = true))
    }

    @Test
    fun theTesterCodeIgnoresCaseAndSpaces() {
        assertTrue(SubscriptionRules.isTesterCode("juggle-5a02b4"))
        assertTrue(SubscriptionRules.isTesterCode("  JUGGLE-5A02B4 "))
        assertFalse(SubscriptionRules.isTesterCode("juggle-000000"))
        assertFalse(SubscriptionRules.isTesterCode(""))
    }

    @Test
    fun onlyAnswersAboutPaymentAreRemembered() {
        assertEquals(true, SubscriptionRules.nextLastKnown(ACTIVE, lastKnownActive = false))
        assertEquals(false, SubscriptionRules.nextLastKnown(NOT_SUBSCRIBED, lastKnownActive = true))
        assertEquals(false, SubscriptionRules.nextLastKnown(PENDING, lastKnownActive = true))
        assertEquals(true, SubscriptionRules.nextLastKnown(UNAVAILABLE, lastKnownActive = true))
        assertEquals(true, SubscriptionRules.nextLastKnown(CHECKING, lastKnownActive = true))
        assertNull(SubscriptionRules.nextLastKnown(NOT_OFFERED, lastKnownActive = true))
    }

    @Test
    fun theBasePlanIsPreferredOverOffers() {
        val trial = offer("plan", offerId = "trial")
        val base = offer("plan", offerId = null)
        assertEquals(base, SubscriptionRules.pickOffer(listOf(trial, base)))
        assertEquals(trial, SubscriptionRules.pickOffer(listOf(trial)))
        assertNull(SubscriptionRules.pickOffer(emptyList()))
    }

    @Test
    fun theRecurringPriceIsTheLastPhase() {
        val withTrial = SubscriptionOffer(
            "plan", "trial", "token",
            listOf(PricingPhase("Free", "P1W", 0), PricingPhase("€2.99", "P1M", 2_990_000)),
        )
        assertEquals("€2.99", withTrial.recurringPhase?.formattedPrice)
    }

    @Test
    fun periodsReadAsWords() {
        assertEquals("month", SubscriptionRules.describePeriod("P1M"))
        assertEquals("3 months", SubscriptionRules.describePeriod("P3M"))
        assertEquals("year", SubscriptionRules.describePeriod("P1Y"))
        assertEquals("week", SubscriptionRules.describePeriod("P1W"))
        assertEquals("P1Y6M", SubscriptionRules.describePeriod("P1Y6M"))
    }

    private fun offer(basePlanId: String, offerId: String?) =
        SubscriptionOffer(basePlanId, offerId, "token-$offerId", listOf(PricingPhase("€2.99", "P1M", 2_990_000)))
}
