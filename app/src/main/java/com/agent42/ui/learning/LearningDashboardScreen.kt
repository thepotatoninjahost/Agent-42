package com.agent42.ui.learning

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
import com.agent42.memory.MemoryEntity
import com.agent42.memory.StrategyWeightEntity

@Composable
fun LearningDashboardScreen(
    strategyWeights: List<StrategyWeightEntity>,
    memoryCount: Int,
    totalInteractions: Int,
    recentReflections: List<MemoryEntity>
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatCard("Interactions", totalInteractions.toString())
                StatCard("Memories", memoryCount.toString())
                StatCard("Strategies", strategyWeights.size.toString())
            }
        }
        item {
            Text("Strategy Performance", style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface)
        }
        items(strategyWeights) { strategy -> StrategyWeightCard(strategy) }
        item {
            Text("Self-Reflections", style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface)
        }
        items(recentReflections) { ReflectionCard(it) }
    }
}

@Composable
private fun StrategyWeightCard(strategy: StrategyWeightEntity) {
    val weightColor = when {
        strategy.weight > 0.7f -> Color(0xFF81C784)
        strategy.weight < 0.3f -> Color(0xFFEF5350)
        else -> Color(0xFFFFB74D)
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    strategy.strategyName.replace("_", " "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "${(strategy.weight * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = weightColor
                )
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = strategy.weight,
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = weightColor,
                trackColor = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "${strategy.usesCount} uses • ${strategy.positiveFeedback} positive • ${strategy.negativeFeedback} negative",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ReflectionCard(reflection: MemoryEntity) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                reflection.content.take(200) + if (reflection.content.length > 200) "..." else "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 5, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun StatCard(label: String, value: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface)
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
