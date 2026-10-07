package com.juggling.tracker.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryPurchasesAsync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The app's subscription, through Google Play Billing. One per process, held by
 * the application; the screen asks it to [refresh] when it comes to the front
 * and shows the paywall while [access] says the app is locked.
 */
class BillingRepository(context: Context, private val cache: EntitlementCache) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _access = MutableStateFlow(access(SubscriptionStatus.CHECKING, offer = null))
    val access: StateFlow<SubscriptionAccess> = _access.asStateFlow()

    private var productDetails: ProductDetails? = null

    private val purchasesListener = PurchasesUpdatedListener { result, purchases ->
        when (result.responseCode) {
            BillingResponseCode.OK -> scope.launch { handlePurchases(purchases.orEmpty()) }
            BillingResponseCode.USER_CANCELED -> Unit
            // Already owned, or any other failure: ask Play again for the real state.
            else -> {
                Log.w(TAG, "Purchase did not complete: ${result.responseCode} ${result.debugMessage}")
                refresh()
            }
        }
    }

    private val client: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener(purchasesListener)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    /** Asks Play for the product and the user's purchases, connecting first if needed. */
    fun refresh() {
        if (client.isReady) {
            scope.launch { query() }
            return
        }
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingResponseCode.OK) {
                    scope.launch { query() }
                } else {
                    Log.w(TAG, "Billing setup failed: ${result.responseCode} ${result.debugMessage}")
                    publish(SubscriptionStatus.UNAVAILABLE)
                }
            }

            // With automatic reconnection the next call reconnects on its own.
            override fun onBillingServiceDisconnected() = Unit
        })
    }

    /** Opens Play's purchase sheet; false when there is nothing to buy yet. */
    fun launchPurchase(activity: Activity): Boolean {
        val details = productDetails ?: return false
        val offer = _access.value.offer ?: return false
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .setOfferToken(offer.offerToken)
                        .build()
                )
            )
            .build()
        val result = client.launchBillingFlow(activity, params)
        if (result.responseCode != BillingResponseCode.OK) {
            Log.w(TAG, "Cannot start purchase: ${result.responseCode} ${result.debugMessage}")
            return false
        }
        return true
    }

    /** Unlocks the app for good on this phone when [code] is the tester code. */
    fun redeemTesterCode(code: String): Boolean {
        if (!SubscriptionRules.isTesterCode(code)) return false
        cache.isTester = true
        _access.value = access(_access.value.status, _access.value.offer)
        return true
    }

    private suspend fun query() {
        val details = queryProductDetails()
        if (details == null) {
            publish(SubscriptionStatus.UNAVAILABLE)
            return
        }
        productDetails = details.firstOrNull()
        val purchases = client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
        )
        if (purchases.billingResult.responseCode != BillingResponseCode.OK) {
            Log.w(TAG, "Purchases query failed: ${purchases.billingResult.debugMessage}")
            publish(SubscriptionStatus.UNAVAILABLE)
            return
        }
        handlePurchases(purchases.purchasesList)
    }

    /** The subscription's details from Play: empty when Play has no such product, null on error. */
    private suspend fun queryProductDetails(): List<ProductDetails>? {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(SubscriptionProducts.SUBSCRIPTION_ID)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                )
            )
            .build()
        return suspendCancellableCoroutine { continuation ->
            client.queryProductDetailsAsync(params) { result, detailsResult ->
                if (result.responseCode == BillingResponseCode.OK) {
                    continuation.resume(detailsResult.productDetailsList)
                } else {
                    Log.w(TAG, "Product query failed: ${result.responseCode} ${result.debugMessage}")
                    continuation.resume(null)
                }
            }
        }
    }

    private suspend fun handlePurchases(purchases: List<Purchase>) {
        // Play refunds a subscription that is not acknowledged within three days.
        purchases
            .filter { it.purchaseState == Purchase.PurchaseState.PURCHASED && !it.isAcknowledged }
            .forEach { purchase ->
                val result = client.acknowledgePurchase(
                    AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
                )
                if (result.responseCode != BillingResponseCode.OK) {
                    Log.w(TAG, "Acknowledge failed: ${result.responseCode} ${result.debugMessage}")
                }
            }
        val owned = purchases.map { purchase ->
            OwnedPurchase(
                productIds = purchase.products,
                state = when (purchase.purchaseState) {
                    Purchase.PurchaseState.PURCHASED -> OwnedPurchase.State.PURCHASED
                    Purchase.PurchaseState.PENDING -> OwnedPurchase.State.PENDING
                    else -> OwnedPurchase.State.OTHER
                },
            )
        }
        publish(SubscriptionRules.classify(owned, isOffered = productDetails != null))
    }

    private fun publish(status: SubscriptionStatus) {
        cache.lastKnownActive = SubscriptionRules.nextLastKnown(status, cache.lastKnownActive)
        _access.value = access(status, offer = productDetails?.let(::offerOf))
    }

    private fun access(status: SubscriptionStatus, offer: SubscriptionOffer?) =
        SubscriptionAccess(status, offer, SubscriptionRules.isUnlocked(status, cache.lastKnownActive, cache.isTester))

    private fun offerOf(details: ProductDetails): SubscriptionOffer? =
        SubscriptionRules.pickOffer(
            details.subscriptionOfferDetails.orEmpty().map { offer ->
                SubscriptionOffer(
                    basePlanId = offer.basePlanId,
                    offerId = offer.offerId,
                    offerToken = offer.offerToken,
                    phases = offer.pricingPhases.pricingPhaseList.map {
                        PricingPhase(it.formattedPrice, it.billingPeriod, it.priceAmountMicros)
                    },
                )
            }
        )

    private companion object {
        const val TAG = "BillingRepository"
    }
}
