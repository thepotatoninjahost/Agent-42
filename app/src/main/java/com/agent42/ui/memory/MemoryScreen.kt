package com.agent42.ui.memory

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agent42.memory.MemoryCategory
import com.agent42.memory.MemoryEntity

@Composable
fun MemoryScreen(memories: List<MemoryEntity>) {
    val searchQuery = remember { mutableStateOf("") }
    val selectedCategory = remember { mutableStateOf<MemoryCategory?>(null) }

    val filtered = memories.filter { mem ->
        (selectedCategory.value == null || mem.category == selectedCategory.value) &&
        (searchQuery.value.isBlank() ||
         mem.content.contains(searchQuery.value, ignoreCase = true))
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = searchQuery.value,
            onValueChange = { searchQuery.value = it },
            placeholder = { Text("Search memories...") },
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            shape = RoundedCornerShape(24.dp)
        )
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MemoryCategory.values().forEach { category ->
                FilterChip(
                    selected = selectedCategory.value == category,
                    onClick = {
                        selectedCategory.value =
                            if (selectedCategory.value == category) null else category
                    },
                    label = { Text(category.name, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filtered) { memory ->
                MemoryCard(memory)
            }
        }
    }
}

@Composable
private fun MemoryCard(memory: MemoryEntity) {
    val categoryColor = when (memory.category) {
        MemoryCategory.FACTUAL -> Color(0xFF4FC3F7)
        MemoryCategory.PREFERENCE -> Color(0xFF81C784)
        MemoryCategory.PATTERN -> Color(0xFFFFB74D)
        MemoryCategory.EPISODIC -> Color(0xFFB39DDB)
        MemoryCategory.SKILL -> Color(0xFFFF8A65)
        MemoryCategory.RELATIONSHIP -> Color(0xFFF06292)
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(12.dp)) {
            Box(
                modifier = Modifier.width(3.dp).height(40.dp)
                    .background(categoryColor, RoundedCornerShape(2.dp))
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = memory.content,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(memory.category.name, style = MaterialTheme.typography.labelSmall, color = categoryColor)
                    Text(
                        "Importance: ${(memory.importance * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
