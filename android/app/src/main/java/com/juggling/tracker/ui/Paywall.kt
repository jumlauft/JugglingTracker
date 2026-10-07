package com.juggling.tracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.juggling.tracker.R
import com.juggling.tracker.billing.SubscriptionAccess
import com.juggling.tracker.billing.SubscriptionRules
import com.juggling.tracker.billing.SubscriptionStatus

/**
 * Shows [content] when the subscription lets the user in, the paywall when it
 * does not, and a spinner while the first answer from Play is on its way.
 */
@Composable
fun SubscriptionGate(
    access: SubscriptionAccess,
    onSubscribe: () -> Unit,
    onCheckAgain: () -> Unit,
    onTesterCode: (String) -> Boolean,
    content: @Composable () -> Unit,
) {
    when (access.unlocked) {
        true -> content()
        false -> PaywallScreen(access, onSubscribe, onCheckAgain, onTesterCode)
        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

@Composable
fun PaywallScreen(
    access: SubscriptionAccess,
    onSubscribe: () -> Unit,
    onCheckAgain: () -> Unit,
    onTesterCode: (String) -> Boolean,
) {
    var enteringTesterCode by remember { mutableStateOf(false) }
    if (enteringTesterCode) {
        TesterCodeDialog(onSubmit = onTesterCode, onDismiss = { enteringTesterCode = false })
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.paywall_title),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.paywall_body),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    R.string.paywall_feature_watch,
                    R.string.paywall_feature_phone,
                    R.string.paywall_feature_history,
                ).forEach { Text("• " + stringResource(it), style = MaterialTheme.typography.bodyMedium) }
            }

            Spacer(Modifier.height(8.dp))

            val price = access.offer?.recurringPhase
            Text(
                text = if (price != null) {
                    stringResource(
                        R.string.paywall_price,
                        price.formattedPrice,
                        SubscriptionRules.describePeriod(price.billingPeriod),
                    )
                } else {
                    stringResource(R.string.paywall_price_unknown)
                },
                style = MaterialTheme.typography.titleMedium,
            )

            when (access.status) {
                SubscriptionStatus.PENDING -> R.string.paywall_pending
                SubscriptionStatus.UNAVAILABLE -> R.string.paywall_unavailable
                else -> null
            }?.let {
                Text(
                    text = stringResource(it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }

            Button(
                onClick = onSubscribe,
                enabled = access.offer != null && access.status != SubscriptionStatus.PENDING,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.paywall_subscribe))
            }
            TextButton(onClick = onCheckAgain) {
                Text(stringResource(R.string.paywall_check_again))
            }
            TextButton(onClick = { enteringTesterCode = true }) {
                Text(stringResource(R.string.paywall_tester_code))
            }
            Text(
                text = stringResource(R.string.paywall_cancel_anytime),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Where a tester types the code Jonas gave them; a wrong code says so and stays open. */
@Composable
private fun TesterCodeDialog(onSubmit: (String) -> Boolean, onDismiss: () -> Unit) {
    var code by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.paywall_tester_code)) },
        text = {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it; wrong = false },
                singleLine = true,
                label = { Text(stringResource(R.string.tester_code_label)) },
                isError = wrong,
                supportingText = if (wrong) {
                    { Text(stringResource(R.string.tester_code_wrong)) }
                } else {
                    null
                },
            )
        },
        confirmButton = {
            TextButton(onClick = { if (onSubmit(code)) onDismiss() else wrong = true }) {
                Text(stringResource(R.string.tester_code_unlock))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel_tester_code)) }
        },
    )
}
