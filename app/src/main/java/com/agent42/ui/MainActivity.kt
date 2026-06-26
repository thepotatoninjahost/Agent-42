package com.agent42.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.ChangeCircle
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent42.core.AppInitializer
import com.agent42.core.Persona
import com.agent42.ui.chat.ChatScreen
import com.agent42.ui.learning.LearningDashboardScreen
import com.agent42.ui.memory.MemoryScreen
import com.agent42.ui.approval.ApprovalScreen
import com.agent42.ui.settings.SettingsScreen
import com.agent42.voice.VoiceIO
import kotlinx.coroutines.flow.collectLatest

class MainActivity : ComponentActivity() {

    private lateinit var voiceIO: VoiceIO

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) { /* user denied — mic button will show a toast via UI state */ }
    }

    fun requestMicPermission(): Boolean {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!granted) micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        return granted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val deps = AppInitializer.createAgentDependencies(this, nexaToken = "")
        voiceIO = VoiceIO(this)

        setContent {
            Agent42Theme {
                AgentApp(deps, voiceIO, ::requestMicPermission)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::voiceIO.isInitialized) voiceIO.destroy()
    }
}

@Composable
fun Agent42Theme(content: @Composable () -> Unit) {
    val darkScheme = darkColorScheme(
        primary = Color(0xFF7C9EFF),
        secondary = Color(0xFF6B7FCB),
        tertiary = Color(0xFFB4A0FF),
        background = Color(0xFF0F1117),
        surface = Color(0xFF16181F),
        surfaceVariant = Color(0xFF1E2029),
        onSurface = Color(0xFFE4E7EC),
        onSurfaceVariant = Color(0xFF9BA1B0),
        outline = Color(0xFF2A2D3A),
        error = Color(0xFFFF5252)
    )
    MaterialTheme(
        colorScheme = darkScheme,
        typography = Typography(
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
            titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
            labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)
        ),
        content = content
    )
}

@Composable
fun AgentApp(
    deps: com.agent42.core.AgentDependencies,
    voiceIO: VoiceIO,
    requestMicPermission: () -> Boolean
) {
    var currentScreen by rememberSaveable { mutableStateOf(0) }
    var viewModel by remember { mutableStateOf<com.agent42.core.AgentViewModel?>(null) }
    var voiceText by remember { mutableStateOf("") }
    var isListening by remember { mutableStateOf(false) }
    var modelError by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("agent42_prefs", android.content.Context.MODE_PRIVATE) }
    var showFirstRun by remember { mutableStateOf(prefs.getBoolean("is_first_run", true)) }
    var activePersonaName by remember { mutableStateOf(prefs.getString("active_persona", "Default") ?: "Default") }

    val personaMap = remember {
        linkedMapOf(
            "Default" to Persona.DEFAULT,
            "Analytical" to Persona.ANALYTICAL,
            "Creative" to Persona.CREATIVE,
            "Tutor" to Persona.TUTOR
        )
    }

    val screens = listOf(
        "Chat" to Icons.Default.Chat,
        "Memory" to Icons.Default.Memory,
        "Learning" to Icons.Default.School,
        "Changes" to Icons.Default.ChangeCircle,
        "Settings" to Icons.Default.Settings
    )

    // Separate LaunchedEffect per flow — chained collectLatest never reaches the second one
    LaunchedEffect(Unit) { voiceIO.initTTS {} }

    LaunchedEffect(Unit) {
        voiceIO.transcribedText.collectLatest { voiceText = it }
    }

    LaunchedEffect(Unit) {
        voiceIO.isListening.collectLatest { isListening = it }
    }

    LaunchedEffect(Unit) {
        deps.modelManager.loadModel(
            onProgress = {},
            onComplete = {
                val llm = deps.modelManager.getModel()
                if (llm != null) {
                    viewModel = AppInitializer.createViewModel(deps, llm)
                }
            },
            onError = { error -> modelError = error }
        )
    }

    val vm = viewModel

    // TTS — speak assistant responses when they complete streaming
    val lastMessage by remember { derivedStateOf { vm?.messages?.value?.lastOrNull() } }
    LaunchedEffect(lastMessage?.id, lastMessage?.isStreaming) {
        if (lastMessage?.role == "assistant" && lastMessage?.isStreaming == false && lastMessage?.isError == false && !lastMessage?.content.isNullOrBlank()) {
            voiceIO.speak(lastMessage!!.content)
        }
    }

    Scaffold(
        topBar = {
            // Rule 7: STOP NOW + Lockdown controls always visible
            TopAppBar(
                title = { Text("Agent 42", style = MaterialTheme.typography.titleMedium) },
                actions = {
                    val isLockdown = deps.constitutionEnforcer.isLockdownMode()
                    IconButton(onClick = {
                        if (isLockdown) {
                            deps.constitutionEnforcer.disableLockdown()
                        } else {
                            deps.constitutionEnforcer.enableLockdown()
                        }
                    }) {
                        Icon(
                            imageVector = if (isLockdown) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = if (isLockdown) "Disable lockdown" else "Enable lockdown",
                            tint = if (isLockdown) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = {
                        // Rule 7: STOP NOW — halts all pending actions immediately
                        deps.constitutionEnforcer.stopNow()
                    }) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "STOP NOW",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            )
        },
        bottomBar = {
            if (vm != null) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    screens.forEachIndexed { index, (label, icon) ->
                        NavigationBarItem(
                            selected = currentScreen == index,
                            onClick = { currentScreen = index },
                            icon = { Icon(icon, contentDescription = label) },
                            label = { Text(label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            if (vm == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                        if (modelError != null) {
                            Text("Model Error", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(modelError!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("See Settings → Model Status for details", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("Loading Qwen3-8B on NPU...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                when (currentScreen) {
                    0 -> {
                        ChatScreen(
                            messages = vm.messages.collectAsState().value,
                            isThinking = vm.isThinking.collectAsState().value,
                            currentTrace = vm.currentTrace.collectAsState().value,
                            onSend = { vm.sendQuery(it) },
                            onFeedback = { id, positive, correction -> vm.recordFeedback(id, positive, correction) },
                            voiceText = voiceText,
                            isListening = isListening,
                            onVoiceToggle = {
                                if (isListening) {
                                    voiceIO.stopListening()
                                } else {
                                    if (requestMicPermission()) voiceIO.startListening()
                                }
                            }
                        )
                    }
                    1 -> MemoryScreen(
                        memories = vm.allMemories.collectAsState().value
                    )
                    2 -> LearningDashboardScreen(
                        strategyWeights = vm.strategyWeights.collectAsState().value,
                        memoryCount = vm.memoryCount.collectAsState().value,
                        totalInteractions = vm.totalInteractions.collectAsState().value,
                        recentReflections = vm.recentReflections.collectAsState().value
                    )
                    3 -> ApprovalScreen(
                        pendingProposals = vm.pendingProposals.collectAsState().value,
                        onApprove = { vm.approveProposal(it) },
                        onReject = { id, reason -> vm.rejectProposal(id, reason) }
                    )
                    4 -> SettingsScreen(
                        isFirstRun = showFirstRun,
                        modelInfo = deps.modelManager.getModelStorageInfo(),
                        personas = personaMap.keys.toList(),
                        activePersona = activePersonaName,
                        onPersonaChange = { name ->
                            activePersonaName = name
                            prefs.edit().putString("active_persona", name).apply()
                            personaMap[name]?.let { vm.switchPersona(it) }
                        },
                        onDownloadGuide = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://huggingface.co/Nexa/Qwen3-8B-NPU"))
                            context.startActivity(intent)
                        },
                        onDismissFirstRun = {
                            showFirstRun = false
                            prefs.edit().putBoolean("is_first_run", false).apply()
                        }
                    )
                }
            }
        }
    }
}
