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
import androidx.compose.material.icons.filled.Hub
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
import com.agent42.ui.worldmodel.WorldModelScreen
import com.agent42.voice.VoiceIO
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var voiceIO: VoiceIO
    private lateinit var appDeps: com.agent42.core.AgentDependencies

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) { /* user denied — mic button will show a toast via UI state */ }
    }

    /**
     * Model-folder picker. The owner picks a folder (anywhere SAF grants)
     * that contains the model they downloaded — the Qwen3-8B-NPU bundle,
     * an OmniNeural folder, or a folder with a .gguf. ModelManager searches
     * the tree, finds the bundle, copies it into app storage, and reloads.
     * This is the owner's "use whatever model I want" path.
     */
    private val modelFolderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) importModelFromFolder(uri)
    }

    fun requestMicPermission(): Boolean {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!granted) micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        return granted
    }

    fun launchModelFolderPicker() {
        modelFolderPicker.launch(null)
    }

    fun clearImportError() {
        _importError.value = null
    }

    private fun importModelFromFolder(uri: android.net.Uri) {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        scope.launch {
            _importProgress.value = "Searching selected folder for a model…"
            val result = appDeps.modelManager.importModelFromTreeUri(uri) { frac, msg ->
                _importProgress.value = msg
            }
            _importProgress.value = null
            if (result.isSuccess) {
                // Reload from the freshly imported bundle.
                _reloadTrigger.intValue++
            } else {
                _importError.value = result.exceptionOrNull()?.message ?: "Import failed"
                _reloadTrigger.intValue++  // re-trigger to surface the error path
            }
        }
    }

    // Shared state read by AgentApp so the activity can drive reloads and
    // surface import progress/errors after an async model import completes.
    private val _reloadTrigger = androidx.compose.runtime.mutableIntStateOf(0)
    val reloadTrigger: androidx.compose.runtime.State<Int> = _reloadTrigger
    private val _importError = androidx.compose.runtime.mutableStateOf<String?>(null)
    val importError: androidx.compose.runtime.State<String?> = _importError
    private val _importProgress = androidx.compose.runtime.mutableStateOf<String?>(null)
    val importProgress: androidx.compose.runtime.State<String?> = _importProgress

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appDeps = AppInitializer.createAgentDependencies(this, nexaToken = "")
        voiceIO = VoiceIO(this)

        setContent {
            Agent42Theme {
                AgentApp(
                    appDeps, voiceIO, ::requestMicPermission, ::launchModelFolderPicker,
                    activityReloadTrigger = reloadTrigger,
                    activityImportError = importError,
                    activityImportProgress = importProgress,
                    onClearImportError = ::clearImportError
                )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentApp(
    deps: com.agent42.core.AgentDependencies,
    voiceIO: VoiceIO,
    requestMicPermission: () -> Boolean,
    onPickModelFolder: () -> Unit,
    activityReloadTrigger: androidx.compose.runtime.State<Int>,
    activityImportError: androidx.compose.runtime.State<String?>,
    activityImportProgress: androidx.compose.runtime.State<String?>,
    onClearImportError: () -> Unit
) {
    var currentScreen by rememberSaveable { mutableStateOf(0) }
    var viewModel by remember { mutableStateOf<com.agent42.core.AgentViewModel?>(null) }
    var voiceText by remember { mutableStateOf("") }
    var isListening by remember { mutableStateOf(false) }
    var modelError by remember { mutableStateOf<String?>(null) }
    var isLoadingModel by remember { mutableStateOf(false) }
    // Incremented by the "Retry" button to re-trigger the load LaunchedEffect.
    var retryTrigger by remember { mutableStateOf(0) }

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
        "World" to Icons.Default.Hub,
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

    // Combined reload trigger: the local retry button OR the activity's
    // post-import reload both re-run the model load.
    val combinedReload = retryTrigger + activityReloadTrigger.value
    LaunchedEffect(combinedReload) {
        // If an import just failed, surface that error and don't attempt load.
        val importErr = activityImportError.value
        if (importErr != null) {
            modelError = importErr
            isLoadingModel = false
            return@LaunchedEffect
        }
        // Otherwise (re)attempt the load. Clear any prior error so the spinner
        // shows during the retry rather than the stale error text.
        modelError = null
        isLoadingModel = true
        deps.modelManager.loadModel(
            onProgress = {},
            onComplete = {
                val llm = deps.modelManager.getModel()
                if (llm != null) {
                    viewModel = AppInitializer.createViewModel(deps, llm)
                }
                isLoadingModel = false
            },
            onError = { error ->
                modelError = error
                isLoadingModel = false
            }
        )
    }

    val vm = viewModel
    val importProgress = activityImportProgress.value

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
                        // Rule 7: STOP NOW.
                        // 1. Cancel any in-flight reasoning so the owner can
                        //    interrupt an agent that won't stop responding.
                        // 2. Then halt pending action approvals (the original
                        //    Rule 7 semantics for action execution).
                        viewModel?.cancelReasoning()
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
            // Nav is ALWAYS visible — the owner is never locked out of any
            // screen, including Settings (where models are loaded). The
            // no-model state is handled inside each screen, not by hiding nav.
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
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (currentScreen) {
                0 -> {
                    // Chat: needs the model. If not loaded, show a recoverable
                    // prompt with a direct "find model" action — never a
                    // dead black screen.
                    val me = modelError
                    if (vm == null) {
                        NoModelPanel(
                            error = me,
                            isLoading = isLoadingModel,
                            importProgress = importProgress,
                            onPickModelFolder = {
                                onClearImportError()
                                onPickModelFolder()
                            },
                            onRetry = {
                                onClearImportError()
                                retryTrigger++
                            },
                            onOpenDownloadPage = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://huggingface.co/NexaAI/Qwen3-8B-NPU"))
                                context.startActivity(intent)
                            },
                            onGoToSettings = { currentScreen = 5 }
                        )
                    } else {
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
                }
                1 -> {
                    if (vm == null) {
                        RequiresModelPlaceholder(onPickModelFolder = onPickModelFolder, onGoToSettings = { currentScreen = 5 })
                    } else {
                        MemoryScreen(memories = vm.allMemories.collectAsState().value)
                    }
                }
                2 -> {
                    if (vm == null) {
                        RequiresModelPlaceholder(onPickModelFolder = onPickModelFolder, onGoToSettings = { currentScreen = 5 })
                    } else {
                        WorldModelScreen(
                            stats = vm.worldModelStats.collectAsState().value,
                            entities = vm.worldModelEntities.collectAsState().value,
                            revisions = vm.worldModelRevisions.collectAsState().value,
                            onCorrectEntity = { id, conf, note -> vm.correctWorldModelEntity(id, conf, note) }
                        )
                    }
                }
                3 -> {
                    if (vm == null) {
                        RequiresModelPlaceholder(onPickModelFolder = onPickModelFolder, onGoToSettings = { currentScreen = 5 })
                    } else {
                        LearningDashboardScreen(
                            strategyWeights = vm.strategyWeights.collectAsState().value,
                            memoryCount = vm.memoryCount.collectAsState().value,
                            totalInteractions = vm.totalInteractions.collectAsState().value,
                            recentReflections = vm.recentReflections.collectAsState().value
                        )
                    }
                }
                4 -> {
                    if (vm == null) {
                        RequiresModelPlaceholder(onPickModelFolder = onPickModelFolder, onGoToSettings = { currentScreen = 5 })
                    } else {
                        ApprovalScreen(
                            pendingProposals = vm.pendingProposals.collectAsState().value,
                            onApprove = { vm.approveProposal(it) },
                            onReject = { id, reason -> vm.rejectProposal(id, reason) }
                        )
                    }
                }
                5 -> SettingsScreen(
                    isFirstRun = showFirstRun,
                    modelInfo = deps.modelManager.getModelStorageInfo(),
                    hasModel = deps.modelManager.hasInstalledModel(),
                    isLoading = isLoadingModel,
                    importProgress = importProgress,
                    personas = personaMap.keys.toList(),
                    activePersona = activePersonaName,
                    personaEnabled = vm != null,
                    onPickModelFolder = {
                        onClearImportError()
                        onPickModelFolder()
                    },
                    onReloadModel = {
                        onClearImportError()
                        retryTrigger++
                    },
                    onDeleteModel = {
                        deps.modelManager.deleteModel()
                        viewModel = null
                        modelError = null
                        retryTrigger++  // re-runs load → shows the no-model prompt
                    },
                    onPersonaChange = { name ->
                        activePersonaName = name
                        prefs.edit().putString("active_persona", name).apply()
                        personaMap[name]?.let { vm?.switchPersona(it) }
                    },
                    onDownloadGuide = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://huggingface.co/NexaAI/Qwen3-8B-NPU"))
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

/**
 * Shown on the Chat tab (and as a fallback) when no model is loaded. This is
 * the recoverable no-deadlock state: the owner can pick the folder they
 * downloaded the model into, retry, open the download page, or jump to
 * Settings — all without leaving the app or needing a computer.
 */
@Composable
private fun NoModelPanel(
    error: String?,
    isLoading: Boolean,
    importProgress: String?,
    onPickModelFolder: () -> Unit,
    onRetry: () -> Unit,
    onOpenDownloadPage: () -> Unit,
    onGoToSettings: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
        Column(
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            if (isLoading) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(16.dp))
                Text(importProgress ?: "Loading model…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            Text("No model loaded", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            if (error != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(modifier = Modifier.height(20.dp))
            Button(onClick = onPickModelFolder, modifier = Modifier.fillMaxWidth()) {
                Text("Find model on my phone")
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenDownloadPage, modifier = Modifier.fillMaxWidth()) {
                Text("Open download page (HuggingFace)")
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Text("Retry loading")
            }
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onGoToSettings) { Text("Go to Settings") }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Tap \"Find model\" and pick the folder that contains the model files you " +
                    "downloaded (all 10 files for Qwen3-8B-NPU, or a .gguf). Agent 42 copies " +
                    "them into his own storage and runs them. Works with any model you choose.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Lightweight placeholder for tabs that need a loaded model. */
@Composable
private fun RequiresModelPlaceholder(onPickModelFolder: () -> Unit, onGoToSettings: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
        Column(
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            Text("Load a model to use this", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = onPickModelFolder) { Text("Find model on my phone") }
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onGoToSettings) { Text("Go to Settings") }
        }
    }
}
