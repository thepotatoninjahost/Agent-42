package com.agent42.ui.approval

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agent42.selfmodification.ChangeProposal
import com.agent42.selfmodification.RiskLevel

@Composable
fun ApprovalScreen(
    pendingProposals: List<ChangeProposal>,
    onApprove: (String) -> Unit,
    onReject: (String, String) -> Unit
) {
    if (pendingProposals.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No pending changes", style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface)
                Text("The agent has no proposed modifications right now.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("${pendingProposals.size} Proposed Changes",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface)
        }
        items(pendingProposals) { proposal ->
            ProposalCard(
                proposal = proposal,
                onApprove = { onApprove(proposal.proposalId) },
                onReject = { reason -> onReject(proposal.proposalId, reason) }
            )
        }
    }
}

@Composable
private fun ProposalCard(
    proposal: ChangeProposal,
    onApprove: () -> Unit,
    onReject: (String) -> Unit
) {
    val riskColor = when (proposal.riskLevel) {
        RiskLevel.LOW -> Color(0xFF81C784)
        RiskLevel.MEDIUM -> Color(0xFFFFB74D)
        RiskLevel.HIGH -> Color(0xFFEF5350)
    }
    val showRejectField = remember { mutableStateOf(false) }
    val rejectionText = remember { mutableStateOf("") }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(proposal.moduleName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface)
                Surface(
                    color = riskColor.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(proposal.riskLevel.name,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = riskColor)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(proposal.diagnosis,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            Text("Reasoning: ${proposal.reasoning.take(150)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onApprove,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                ) { Text("Approve") }
                OutlinedButton(
                    onClick = { showRejectField.value = true },
                    modifier = Modifier.weight(1f)
                ) { Text("Reject", color = MaterialTheme.colorScheme.error) }
            }
            if (showRejectField.value) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = rejectionText.value,
                    onValueChange = { rejectionText.value = it },
                    label = { Text("Why reject?") },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        TextButton(onClick = {
                            onReject(rejectionText.value.ifBlank { "No reason given" })
                            showRejectField.value = false
                        }) { Text("Submit") }
                    }
                )
            }
        }
    }
}
