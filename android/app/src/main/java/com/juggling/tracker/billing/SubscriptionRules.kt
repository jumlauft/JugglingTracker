package com.juggling.tracker.billing

/**
 * The subscription that unlocks the app. The ID is a placeholder until the
 * product exists in Play Console (Monetize > Subscriptions); it must match the
 * product ID created there exactly.
 */
object SubscriptionProducts {
    const val SUBSCRIPTION_ID = "juggling_tracker_subscription"

    /** Google Play's page for managing (and cancelling) this subscription. */
    fun manageUrl(packageName: String): String =
        "https://play.google.com/store/account/subscriptions?sku=$SUBSCRIPTION_ID&package=$packageName"
}

/** Where the user stands with Google Play, as last asked. */
enum class SubscriptionStatus {
    /** Not asked yet, or the answer is on its way. */
    CHECKING,

    /** Subscribed, including Play's grace period. */
    ACTIVE,

    /** Bought, but the payment has not gone through yet (e.g. cash at a shop). */
    PENDING,

    /** Not subscribed, and the subscription can be bought. */
    NOT_SUBSCRIBED,

    /**
     * Play knows no such product: it has not been set up in Play Console yet, or
     * this build was not installed from Play (a local debug build).
     */
    NOT_OFFERED,

    /** Google Play could not be reached (offline, no Play Store, Play error). */
    UNAVAILABLE,
}

/** A purchase as Play reports it, without the Play types, so the rules test on the JVM. */
data class OwnedPurchase(val productIds: List<String>, val state: State) {
    enum class State { PURCHASED, PENDING, OTHER }
}

/** One pricing phase of an offer: "€2.99" every "P1M". */
data class PricingPhase(val formattedPrice: String, val billingPeriod: String, val priceMicros: Long)

/** One way to buy the subscription, as listed by Play: a base plan, or an offer on it. */
data class SubscriptionOffer(
    val basePlanId: String,
    val offerId: String?,
    val offerToken: String,
    val phases: List<PricingPhase>,
) {
    /** The price paid once any introductory phases are over. */
    val recurringPhase: PricingPhase? get() = phases.lastOrNull()
}

/** What the screen needs: the status, what it would cost, and whether to let the user in. */
data class SubscriptionAccess(
    val status: SubscriptionStatus,
    val offer: SubscriptionOffer?,
    /** True lets the user in, false shows the paywall, null shows a short wait. */
    val unlocked: Boolean?,
)

object SubscriptionRules {

    fun classify(purchases: List<OwnedPurchase>, isOffered: Boolean): SubscriptionStatus {
        val ours = purchases.filter { SubscriptionProducts.SUBSCRIPTION_ID in it.productIds }
        return when {
            ours.any { it.state == OwnedPurchase.State.PURCHASED } -> SubscriptionStatus.ACTIVE
            ours.any { it.state == OwnedPurchase.State.PENDING } -> SubscriptionStatus.PENDING
            !isOffered -> SubscriptionStatus.NOT_OFFERED
            else -> SubscriptionStatus.NOT_SUBSCRIBED
        }
    }

    /**
     * Whether the app opens. It locks only when Play says plainly that the user
     * has not paid. A product missing from Play opens it, so a release that goes
     * out before the subscription is set up in Play Console locks nobody out.
     * While Play is being asked or cannot be reached, the last answer stands, so
     * a subscriber who opens the app offline still gets in; with no answer ever
     * seen, a check in progress waits and an unreachable Play lets the user in.
     */
    fun isUnlocked(status: SubscriptionStatus, lastKnownActive: Boolean?): Boolean? = when (status) {
        SubscriptionStatus.ACTIVE, SubscriptionStatus.NOT_OFFERED -> true
        SubscriptionStatus.PENDING, SubscriptionStatus.NOT_SUBSCRIBED -> false
        SubscriptionStatus.CHECKING -> lastKnownActive
        SubscriptionStatus.UNAVAILABLE -> lastKnownActive ?: true
    }

    /** The answer to remember for next time; statuses that say nothing about payment keep the old one. */
    fun nextLastKnown(status: SubscriptionStatus, lastKnownActive: Boolean?): Boolean? = when (status) {
        SubscriptionStatus.ACTIVE -> true
        SubscriptionStatus.PENDING, SubscriptionStatus.NOT_SUBSCRIBED -> false
        SubscriptionStatus.NOT_OFFERED -> null
        SubscriptionStatus.CHECKING, SubscriptionStatus.UNAVAILABLE -> lastKnownActive
    }

    /**
     * The offer to show and buy: the base plan itself when Play lists it, so the
     * paywall shows the plain recurring price; otherwise whatever Play lists first.
     */
    fun pickOffer(offers: List<SubscriptionOffer>): SubscriptionOffer? =
        offers.firstOrNull { it.offerId == null } ?: offers.firstOrNull()

    /**
     * "P1M" as "month", "P3M" as "3 months", "P1Y" as "year", "P1W" as "week";
     * anything else is returned unchanged.
     */
    fun describePeriod(isoPeriod: String): String {
        val match = Regex("^P(\\d+)([DWMY])$").matchEntire(isoPeriod) ?: return isoPeriod
        val count = match.groupValues[1].toInt()
        val unit = when (match.groupValues[2]) {
            "D" -> "day"
            "W" -> "week"
            "M" -> "month"
            else -> "year"
        }
        return if (count == 1) unit else "$count ${unit}s"
    }
}
