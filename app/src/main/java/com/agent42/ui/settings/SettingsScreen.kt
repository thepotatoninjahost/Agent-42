package com.agent42.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.agent42.core.ModelStorageInfo

@Composable
fun SettingsScreen(
    isFirstRun: Boolean,
    modelInfo: ModelStorageInfo?,
    hasModel: Boolean,
    isLoading: Boolean,
    importProgress: String?,
    personas: List<String>,
    activePersona: String,
    personaEnabled: Boolean,
    onPickModelFolder: () -> Unit,
    onReloadModel: () -> Unit,
    onDeleteModel: () -> Unit,
    onPersonaChange: (String) -> Unit,
    onDownloadGuide: () -> Unit,
    onDismissFirstRun: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (isFirstRun) {
            FirstRunBanner(onDownloadGuide = onDownloadGuide, onDismiss = onDismissFirstRun)
        }

        ModelStatusCard(
            info = modelInfo,
            hasModel = hasModel,
            isLoading = isLoading,
            importProgress = importProgress,
            onPickModelFolder = onPickModelFolder,
            onReloadModel = onReloadModel,
            onDeleteModel = onDeleteModel
        )
        PersonaSelector(
            personas = personas,
            activePersona = activePersona,
            enabled = personaEnabled,
            onPersonaChange = onPersonaChange
        )
        AboutCard()
    }
}

@Composable
private fun FirstRunBanner(onDownloadGuide: () -> Unit, onDismiss: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.School, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Welcome to Agent 42", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                "Agent 42 runs an AI model directly on your phone. You need to download the Qwen3-8B-NPU model files (~4–5 GB) and place them in the app storage before first use.",
                style = MaterialTheme.typography.bodyMedium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDismiss) { Text("Dismiss") }
                Button(onClick = onDownloadGuide) { Text("Open Model Guide") }
            }
        }
    }
}

@Composable
private fun ModelStatusCard(
    info: ModelStorageInfo?,
    hasModel: Boolean,
    isLoading: Boolean,
    importProgress: String?,
    onPickModelFolder: () -> Unit,
    onReloadModel: () -> Unit,
    onDeleteModel: () -> Unit
) {
    Card {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Storage, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Model Status", style = MaterialTheme.typography.titleMedium)
            }

            if (info != null && (hasModel || info.fileCount > 0)) {
                Text("Model: ${info.modelName.ifBlank { "unknown" }}", style = MaterialTheme.typography.bodyMedium)
                Text("Files: ${info.fileCount}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Entry: ${info.entryFile.ifBlank { "—" }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Size: %.2f GB".format(info.sizeGB), style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (info.isLoaded) "Status: Loaded ✅" else "Status: Installed — not loaded",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (info.isLoaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(info.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(
                    "No model installed. Agent 42 runs any model you choose — pick the folder " +
                        "where you downloaded it and he'll copy it in and run it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (isLoading) {
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(importProgress ?: "Working…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPickModelFolder, enabled = !isLoading) {
                    Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (hasModel) "Replace model" else "Find model")
                }
                if (hasModel) {
                    OutlinedButton(onClick = onReloadModel, enabled = !isLoading) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Reload")
                    }
                    OutlinedButton(onClick = onDeleteModel, enabled = !isLoading) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(6.dp))
                        Text("Delete")
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonaSelector(
    personas: List<String>,
    activePersona: String,
    enabled: Boolean,
    onPersonaChange: (String) -> Unit
) {
    Card {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Person, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Persona", style = MaterialTheme.typography.titleMedium)
            }
            if (!enabled) {
                Text(
                    "Load a model to switch persona.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                personas.forEachIndexed { index, persona ->
                    SegmentedButton(
                        selected = persona == activePersona && enabled,
                        onClick = { onPersonaChange(persona) },
                        enabled = enabled,
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = personas.size)
                    ) { Text(persona) }
                }
            }
        }
    }
}

@Composable
private fun AboutCard() {
    Card {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("About Agent 42", style = MaterialTheme.typography.titleMedium)
            Text("Version 1.0", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("On-device AI that learns, adapts, and improves with every conversation.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
