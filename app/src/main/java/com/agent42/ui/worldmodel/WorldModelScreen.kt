package com.agent42.ui.worldmodel

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agent42.worldmodel.BeliefRevision
import com.agent42.worldmodel.WorldEntity
import com.agent42.worldmodel.WorldEntityType
import com.agent42.worldmodel.WorldModelStats
import com.agent42.worldmodel.RevisionRules

// ═══════════════════════════════════════════════════════════════
// WORLD MODEL SCREEN
//
// Section 3.5: "add a 'World Model' screen showing the entity graph,
// belief confidences, recent revisions. The owner should be able to
// see and correct what the agent believes."
//
// Three tabs:
//  · Entities   — every belief, its confidence, source, and an
//                 owner-correction control (slider + note).
//  · Revisions  — the immutable audit trail of every belief change.
//  · Overview   — aggregate stats (counts by type, flagged count).
// ═══════════════════════════════════════════════════════════════

private enum class WorldModelTab(val label: String) {
    ENTITIES("Entities"), REVISIONS("Revisions"), OVERVIEW("Overview")
}

@Composable
fun WorldModelScreen(
    stats: WorldModelStats?,
    entities: List<WorldEntity>,
    revisions: List<BeliefRevision>,
    onCorrectEntity: (Long, Float, String) -> Unit
) {
    var tab by remember { mutableStateOf(WorldModelTab.ENTITIES) }
    var searchQuery by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        // Stats header — always visible
        StatsHeader(stats)

        TabRow(selectedTabIndex = tab.ordinal) {
            WorldModelTab.values().forEach { t ->
                Tab(
                    selected = tab == t,
                    onClick = { tab = t },
                    text = { Text(t.label) }
                )
            }
        }

        when (tab) {
            WorldModelTab.ENTITIES -> {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search beliefs...") },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    shape = RoundedCornerShape(24.dp),
                    singleLine = true
                )
                val filtered = entities.filter {
                    searchQuery.isBlank() ||
                        it.label.contains(searchQuery, ignoreCase = true) ||
                        it.canonicalLabel.contains(searchQuery, ignoreCase = true) ||
                        it.type.contains(searchQuery, ignoreCase = true)
                }
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (filtered.isEmpty()) {
                        item { EmptyHint("No beliefs yet. Talk to the agent — it learns from every exchange.") }
                    }
                    items(filtered, key = { it.id }) { entity ->
                        EntityCard(entity, onCorrectEntity)
                    }
                }
            }
            WorldModelTab.REVISIONS -> {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (revisions.isEmpty()) {
                        item { EmptyHint("No belief revisions yet.") }
                    }
                    items(revisions, key = { it.id }) { rev ->
                        RevisionCard(rev)
                    }
                }
            }
            WorldModelTab.OVERVIEW -> {
                OverviewBody(stats)
            }
        }
    }
}

// ═══ HEADER ════════════════════════════════════════════════

@Composable
private fun StatsHeader(stats: WorldModelStats?) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            StatPillar("Entities", stats?.entityCount?.toString() ?: "—")
            StatPillar("Relations", stats?.relationCount?.toString() ?: "—")
            StatPillar("Causal", stats?.causalModelCount?.toString() ?: "—")
            StatPillar("Flagged", stats?.flaggedCount?.toString() ?: "—", highlight = (stats?.flaggedCount ?: 0) > 0)
        }
    }
}

@Composable
private fun StatPillar(label: String, value: String, highlight: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = if (highlight) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ═══ ENTITY CARD (with owner correction) ═══════════════════

@Composable
private fun EntityCard(entity: WorldEntity, onCorrectEntity: (Long, Float, String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var sliderValue by remember(entity.id) { mutableStateOf(entity.confidence) }
    var note by remember(entity.id) { mutableStateOf("") }

    val typeColor = colorForType(runCatching { WorldEntityType.valueOf(entity.type) }.getOrNull() ?: WorldEntityType.OTHER)
    val confidenceColor = when {
        entity.confidence >= RevisionRules.HIGH_CONFIDENCE -> Color(0xFF66BB6A)
        entity.confidence >= 0.5f -> Color(0xFFFFB74D)
        else -> Color(0xFFEF5350)
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.width(3.dp).height(36.dp)
                        .background(typeColor, RoundedCornerShape(2.dp))
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entity.canonicalLabel,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${entity.type.lowercase()} · source: ${entity.source.lowercase()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                ConfidenceBadge(entity.confidence, confidenceColor)
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (entity.flaggedForReview) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "⚠ Flagged for review — low confidence or contradicting evidence",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    Divider()
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Owner correction — override the agent's confidence:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${(sliderValue * 100).toInt()}% confidence",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = confidenceColor.copy(alpha = if (sliderValue == entity.confidence) 0.6f else 1f)
                    )
                    Slider(
                        value = sliderValue,
                        onValueChange = { sliderValue = it },
                        valueRange = 0f..1f,
                        steps = 19
                    )
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        placeholder = { Text("Why? (recorded in the audit trail)") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = false,
                        minLines = 1, maxLines = 3
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = {
                            sliderValue = entity.confidence
                            note = ""
                        }) { Text("Reset") }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { onCorrectEntity(entity.id, sliderValue, note.ifBlank { "Owner override" }) },
                            enabled = sliderValue != entity.confidence || note.isNotBlank()
                        ) { Text("Apply correction") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfidenceBadge(confidence: Float, color: Color) {
    Surface(
        color = color.copy(alpha = 0.18f),
        shape = RoundedCornerShape(10.dp)
    ) {
        Text(
            text = "${(confidence * 100).toInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

// ═══ REVISION CARD ════════════════════════════════════════

@Composable
private fun RevisionCard(rev: BeliefRevision) {
    val delta = rev.newConfidence - rev.oldConfidence
    val arrow = if (delta > 0.001f) "↑" else if (delta < -0.001f) "↓" else "→"
    val deltaColor = if (delta > 0.001f) Color(0xFF66BB6A) else if (delta < -0.001f) Color(0xFFEF5350) else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(arrow, color = deltaColor, fontWeight = FontWeight.Bold, modifier = Modifier.width(20.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${rev.reason} · ${rev.targetType.lowercase()} #${rev.targetId}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = rev.evidence,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${(rev.oldConfidence * 100).toInt()}% → ${(rev.newConfidence * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = deltaColor
                )
                Text(
                    text = formatTimeAgo(rev.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ═══ OVERVIEW ═════════════════════════════════════════════

@Composable
private fun OverviewBody(stats: WorldModelStats?) {
    if (stats == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text("Beliefs by type", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        }
        items(WorldEntityType.values().toList()) { type ->
            val count = stats.byType[type] ?: 0
            val maxCount = (stats.byType.values.maxOrNull() ?: 1).coerceAtLeast(1)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(type.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(110.dp), color = MaterialTheme.colorScheme.onSurface)
                Box(
                    modifier = Modifier.weight(1f).height(14.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(7.dp))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(count.toFloat() / maxCount)
                            .height(14.dp)
                            .background(colorForType(type), RoundedCornerShape(7.dp))
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text("$count", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(36.dp))
            }
        }
        item {
            Spacer(Modifier.height(16.dp))
            Divider()
            Spacer(Modifier.height(12.dp))
            Text("How the agent learns", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(6.dp))
            Text(
                "Every observation moves a belief's confidence along the protected revision rules. " +
                    "Corroborating evidence nudges it toward certainty; contradictions pull it toward zero. " +
                    "Below ${(RevisionRules.PRUNE_THRESHOLD * 100).toInt()}% a belief is flagged for your review — never auto-deleted. " +
                    "Owner statements outrank all other sources and can override any belief directly from the Entities tab.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Total revisions logged: ${stats.revisionCount}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ═══ HELPERS ══════════════════════════════════════════════

@Composable
private fun EmptyHint(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun colorForType(type: WorldEntityType): Color = when (type) {
    WorldEntityType.PERSON -> Color(0xFF4FC3F7)
    WorldEntityType.PLACE -> Color(0xFF81C784)
    WorldEntityType.OBJECT -> Color(0xFFFFB74D)
    WorldEntityType.EVENT -> Color(0xFFB39DDB)
    WorldEntityType.CONCEPT -> Color(0xFFFF8A65)
    WorldEntityType.SELF -> Color(0xFFF06292)
    WorldEntityType.AGENT -> Color(0xFFA1887F)
    WorldEntityType.ORGANIZATION -> Color(0xFF90A4AE)
    WorldEntityType.TOOL -> Color(0xFF4DB6AC)
    WorldEntityType.OTHER -> Color(0xFFB0BEC5)
}

private fun formatTimeAgo(ts: Long): String {
    val delta = System.currentTimeMillis() - ts
    val sec = delta / 1000
    return when {
        sec < 60 -> "${sec}s ago"
        sec < 3600 -> "${sec / 60}m ago"
        sec < 86400 -> "${sec / 3600}h ago"
        else -> "${sec / 86400}d ago"
    }
}
