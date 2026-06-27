# Agent 42 — Real Intelligence Plan

> **This document exists because the previous AI lost the design when the
> conversation compacted. Do not let that happen again.** If you are a fresh
> AI picking this up, read this whole document before writing any code.

## 0. What Agent 42 is, and what it is not

Agent 42 is an on-device Android AI agent for Samsung Galaxy S25, written in
Kotlin. The goal is **real synthetic intelligence** — actual reasoning, not
pattern-matching — on the path toward something like Star Trek's Data and his
positronic brain. Not a chatbot. Not an LLM wrapper. The owner has spent four
years, five hours a day, on this. Treat the work accordingly.

**Non-negotiables:**
- Kotlin only. Runs on Samsung Galaxy S25 (Android 15, API 35).
- On-device LLM via Nexa SDK (Qwen3-8B-NPU). No cloud.
- **NEVER use stubs. NEVER use placeholders. NEVER fake code.**
- Real logic, real reasoning, real cognition.
- The owner is the final authority (see `security/CoreConstitution.kt`).
- Don't strip the git token from the remote. The owner manages tokens.

## 1. Current state (as of commit 98fa926)

### 1.1 What builds
- **Build is GREEN** on GitHub Actions. First green build was run #28268281024.
- Debug APK (347.7 MB) is published to GitHub Releases automatically on every
  push to `main`.
- Repo: `https://github.com/thepotatoninjahost/Agent-42` (private)
- 32 Kotlin files, ~7,200 lines.

### 1.2 Build configuration that MUST NOT be changed blindly
These were paid for in real pain and credits. If you change them, you will
re-break the build:

- **AGP 8.7.0**, **Kotlin 2.0.0**, **Gradle 8.9**
- **compileSdk = 35**, **targetSdk = 35**, **minSdk = 29**
- **Compose Compiler plugin** is a SEPARATE Gradle plugin under Kotlin 2.0
  (`org.jetbrains.kotlin.plugin.compose` version `2.0.0`). Do NOT use the old
  `composeOptions { kotlinCompilerExtensionVersion = ... }` — it breaks K2.
- **KSP 2.0.0-1.0.24** (must match Kotlin 2.0.0)
- **Room 2.7.1** (2.6.1 predates K2 and fails KSP)
- `android:extractNativeLibs="true"` in manifest (Nexa SDK requires it)
- Theme is `Theme.Agent42` parent `Theme.AppCompat.DayNight.NoActionBar`
  (NOT `android:Theme.Material3.DayNight.NoActionBar` — that does not exist)
- `androidx.appcompat:appcompat:1.7.0` is an explicit dependency
  (activity-compose does NOT transitively include appcompat)
- GitHub Actions workflow installs `platforms;android-35` explicitly
- Nexa SDK real package is `com.nexa.sdk.*` (NOT `ai.nexa.ml.*`)
- `LlmCreateInput` takes 5 params including `tokenizer_path`
- `GenerationConfig(maxTokens = N)` — camelCase, no temperature/enable_thinking
- Stream returns `Flow<LlmStreamResult>` (sealed: Token/Completed/Error),
  NOT `Flow<String>`. Map it: `if (chunk is LlmStreamResult.Token) chunk.text`
- `SamplerConfig(temperature = F)` holds temperature, not GenerationConfig
- `const val` cannot hold a raw string `"""..."""` under K2 — use `val`
- Room `@Index("colName")` must match the entity property name exactly
  (camelCase: `strategyName`, not `strategy_name`)
- TypeConverters must exist for every non-primitive field type in entities
  (List<String>, List<Long>, List<ModificationRecord>)

### 1.3 The 9 cognitive systems that EXIST in code

| Package | System | Status |
|---|---|---|
| `reasoning/` | ReasoningEngine — chain-of-thought, decomposition, reflection, self-consistency check, confidence gating | Implemented |
| `cognition/` | MetacognitiveMonitor — real-time stream analysis (contradiction, repetition, hedging, off-topic) | Implemented |
| `cognition/` | System1Cache — fast-path for familiar queries | Implemented |
| `memory/` | MemorySystem + MemorySchema — Room+SQLCipher, 17 entities, episodic/semantic, consolidation, associative links, decay | Implemented |
| `prediction/` | PredictiveCoder — Friston-style expectation + surprise; PredictionEngine | Implemented |
| `debate/` | InternalDebate — argues with itself from multiple perspectives | Implemented |
| `curiosity/` | KnowledgeGapTracker — tracks weak areas | Implemented |
| `verification/` | ConstraintChecker — verifies against known facts | Implemented |
| `selfmodification/` | CodeModificationEngine + ApprovalGate — proposes behavior changes, auto-rollback, protected packages | Implemented |
| `security/` | CoreConstitution (12 rules, hardlocked), ConstitutionEnforcer, OwnerAuth, PermissionManager, ActionLog, SecurityLayer | Implemented |
| `nexa/` | NexaSdkAdapter | Implemented |
| `core/` | AgentViewModel, AppInitializer, ContextManager, ModelManager, ThermalManager | Implemented |
| `voice/` | VoiceIO — STT + TTS | Implemented |
| `sensors/` | SensorContextProvider — phone sensor data | Implemented |
| `ui/` | MainActivity + 5 screens (chat, memory, learning, approval, settings) | Implemented |

## 2. The 5 missing systems — the real-intelligence layer

These were designed in conversation with the previous AI but **never written to
files**. The conversation compacted and the design was lost. This section
reconstructs the design so it is not lost again.

These five are what separate Agent 42 from an LLM wrapper. The 9 existing
systems are the skeleton; these five are the substance.

### 2.1 Persistent World Model  ← BUILD FIRST (foundation)
A structured knowledge graph the agent maintains and updates, and reasons OVER
before it ever calls the LLM. Not chat memory (which already exists). A model
of reality: entities, relations, causal links, temporal state, uncertainty.

**Why first:** the other four systems plug into it. Active inference predicts
over it. Grounded concepts feed it. Continual learning updates it. Goals drive
exploration of it.

**Design — see section 3 for full detail.**

### 2.2 Active Inference
Extends the existing `PredictiveCoder`. The agent doesn't just predict the next
query — it selects actions that minimize long-term surprise (expected free
energy). Needs:
- A generative model of world dynamics (built on top of the world model)
- A policy/value estimator: for each candidate action, predict resulting world
  state, compute expected surprise
- A planning horizon (short at first — 1-3 steps)
- Exploration/exploitation balance (information gain vs. exploitation)
- The PredictiveCoder's `shouldTriggerDeepReasoning` is the seed of the
  surprise signal; generalize it from "query is surprising" to "world state
  is surprising"

**Concrete on-device:** policies are cheap LLM calls or pure heuristics that
score candidate actions against the world model. Not full POMDP solving —
bounded, tractable, runs on NPU.

### 2.3 Grounded Concepts
Symbols tied to sensorimotor patterns, not just text tokens. "Walking" is not
a token — it's tied to the accelerometer signature the SensorContextProvider
already exposes. "Morning" is tied to time + light level + the owner's
routines.

Needs:
- A `Concept` representation that carries both a linguistic label AND a
  perceptual signature (sensor features, temporal patterns, embodied context)
- A grounding map: concept → sensorimotor pattern, learned from observation
- The world model's entities reference grounded concepts, not bare strings
- When the LLM emits a concept, the agent can check it against the current
  sensorimotor reality ("you said I'm walking, but the accelerometer says I'm
  sitting — contradiction")

**Concrete on-device:** SensorContextProvider already pulls accelerometer,
gyro, location, time, light. Add a concept-grounding layer that clusters
sensor patterns and links them to linguistic labels the LLM uses.

### 2.4 Continual Learning
The hardest one. Qwen3-8B weights are frozen. Two real options:

- **Option A — on-device LoRA adapters.** Train small low-rank adapters from
  owner feedback (positive/negative signals already in FeedbackDao). S25 NPU
  can do small-scale adaptation. Bleeding edge but feasible.
- **Option B — world-model consolidation.** A background process that reshapes
  the world model from experience: revises belief confidence, merges
  duplicate entities, prunes contradicted facts, strengthens frequently-
  confirmed relations. This is "learning" without touching LLM weights.

**Recommendation:** Start with Option B (world-model consolidation). It is
tractable, runs entirely on existing infrastructure, and produces real
behavioral change. Option A is a later phase.

### 2.5 Goal / Drive System
Intrinsic motivation beyond "answer the query." The agent has drives it
pursues proactively in the background, not just reactively.

Drives (each is a background scheduler entry):
- **Curiosity** — fill knowledge gaps (KnowledgeGapTracker is the seed)
- **Coherence** — resolve contradictions in the world model
- **Competence** — improve at tasks it has failed before
- **Anticipation** — keep the world model's predictions calibrated (monitor
  prediction error over time, refine the model when error is high)

Needs:
- A `DriveScheduler` (WorkManager periodic) that runs drives when idle
- Each drive produces goals; goals produce actions; actions go through the
  ConstitutionEnforcer before execution
- Drives are bounded — they don't run when the owner is active, don't drain
  battery, respect ThermalManager

## 3. World Model — detailed design

### 3.1 Package
`com.agent42.worldmodel/`

### 3.2 Core entities (Room)

```
@Entity(tableName = "wm_entities")
data class WorldEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,              // "person", "place", "object", "event", "concept", "self"
    val label: String,             // linguistic label
    val groundedConceptId: Long? = null,  // FK to grounded concept (system 2.3)
    val confidence: Float = 0.5f,  // belief confidence 0..1
    val createdAt: Long,
    val lastUpdated: Long,
    val source: String,            // "observation", "inference", "owner_statement", "llm"
    val embedding: ByteArray? = null
)

@Entity(tableName = "wm_relations",
    indices = [Index("subjectId"), Index("objectId")])
data class WorldRelation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val subjectId: Long,
    val objectId: Long,
    val relationType: String,      // "is_a", "causes", "part_of", "located_at", "occurred_during", "likes", ...
    val confidence: Float = 0.5f,
    val evidenceCount: Int = 0,
    val createdAt: Long,
    val lastConfirmed: Long,
    val temporalStart: Long? = null,   // for temporal relations
    val temporalEnd: Long? = null
)

@Entity(tableName = "wm_causal_models")
data class CausalModel(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val causeRelationId: Long,     // FK to a relation of type "causes"
    val effectRelationId: Long,
    val conditions: String,        // JSON of preconditions
    val confidence: Float = 0.5f,
    val observedCount: Int = 0
)

@Entity(tableName = "wm_belief_revisions")
data class BeliefRevision(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val targetType: String,        // "entity" or "relation"
    val targetId: Long,
    val oldConfidence: Float,
    val newConfidence: Float,
    val reason: String,
    val evidence: String,
    val timestamp: Long
)

@Entity(tableName = "wm_observations")
data class Observation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val source: String,            // "sensor", "user_input", "llm_inference", "external"
    val raw: String,               // raw observation text/JSON
    val extractedEntities: List<Long>,   // entity IDs mentioned
    val extractedRelations: List<Long>,  // relation IDs mentioned
    val sessionId: String? = null
)
```

TypeConverters for `List<Long>` already exist in AgentConverters (added in
commit 11f0cd1). Reuse them.

### 3.3 Update engine — `WorldModelEngine`

The core. Takes observations and revises the world model. Rules:

- **Extract:** from an observation (user input, sensor event, LLM inference),
  extract candidate entities and relations (LLM-assisted, structured prompt
  that returns JSON).
- **Match:** for each candidate, find existing entities/relations by label +
  embedding similarity. Threshold > 0.85 = same entity.
- **Revise beliefs:** Bayesian-ish update.
  - New observation supporting an entity/relation: confidence moves toward 1
    by `(1 - confidence) * evidenceWeight`. Increment evidenceCount.
  - New observation contradicting: confidence moves toward 0 by
    `confidence * contradictionWeight`. Log a BeliefRevision.
  - If confidence drops below 0.2, flag for pruning (don't auto-delete —
    owner may correct).
- **Causal learning:** when two relations of type "causes" co-occur
  repeatedly under the same conditions, create/strengthen a CausalModel.
- **Consolidation:** periodic background job that merges duplicate entities,
  prunes low-confidence unconfirmed entries older than N days, recalibrates
  confidence based on recency.

### 3.4 Query layer — `WorldModelQuery`

The reasoning engine consults this BEFORE calling the LLM:

- `relevantEntities(query, k)`: returns top-k entities by embedding similarity
  to the query.
- `relationsFor(entityId)`: returns all relations involving the entity.
- `causalChain(entityId)`: returns causal model chains rooted at the entity.
- `beliefState(entityId)`: returns current confidence + revision history.
- `snapshot()`: returns a bounded textual snapshot of the currently-relevant
  world state, to inject into the LLM context.

### 3.5 Integration points

- **ReasoningEngine**: before generating, call `WorldModelQuery.snapshot()`
  and prepend to the prompt context. After answering, extract entities/
  relations from the exchange and feed to `WorldModelEngine.ingest()`.
- **MetacognitiveMonitor**: when a contradiction is detected in LLM output,
  check it against world-model beliefs; emit a `ConstraintViolation` if the
  LLM contradicts a high-confidence belief.
- **ConstraintChecker**: extend its known-facts DB by pulling high-confidence
  (>0.8) world-model facts.
- **Self-modification**: the world model is NOT in PROTECTED_PACKAGES — the
  agent can revise its own beliefs — BUT the update engine's revision rules
  ARE protected (it can't change HOW it learns, only WHAT it believes).
- **UI**: add a "World Model" screen showing the entity graph, belief
  confidences, recent revisions. The owner should be able to see and correct
  what the agent believes.

### 3.6 Build order for the world model

1. Room schema (entities + DAOs + TypeConverters). Bump DB version 3 → 4
   with migration (or fallbackToDestructiveMigration during dev).
2. `WorldModelEngine.ingest()` — extraction + matching + belief revision.
3. `WorldModelQuery` — the read API.
4. Wire into ReasoningEngine (snapshot before, ingest after).
5. Wire into MetacognitiveMonitor (contradiction check).
6. UI screen.
7. Background consolidation job (WorkManager).

## 4. Build configuration reference (the file contents that work)

### 4.1 `build.gradle.kts` (root)
```kotlin
plugins {
    id("com.android.application") version "8.7.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.0" apply false
    id("com.google.devtools.ksp") version "2.0.0-1.0.24" apply false
}
```

### 4.2 `app/build.gradle.kts` (key parts)
```kotlin
android {
    namespace = "com.agent42"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.agent42"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    buildFeatures { compose = true }
    // NO composeOptions block — K2 uses the plugin, not the extension
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}
dependencies {
    implementation("ai.nexa:core:0.0.24")
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")
    implementation("net.zetetic:android-database-sqlcipher:4.5.4")
    implementation("androidx.sqlite:sqlite-ktx:2.4.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    // ... compose, lifecycle, coroutines, workmanager
}
```

### 4.3 `gradle/wrapper/gradle-wrapper.properties`
```
distributionUrl=https\://services.gradle.org/distributions/gradle-8.9-bin.zip
```

### 4.4 `settings.gradle.kts`
```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // NO custom maven repo — Nexa SDK is on Maven Central
    }
}
```

### 4.5 `AndroidManifest.xml` (application tag)
```xml
<application
    android:allowBackup="true"
    android:extractNativeLibs="true"
    android:theme="@style/Theme.Agent42"
    android:icon="@mipmap/ic_launcher"
    android:roundIcon="@mipmap/ic_launcher_round">
```

### 4.6 `res/values/themes.xml`
```xml
<style name="Theme.Agent42" parent="Theme.AppCompat.DayNight.NoActionBar">
    <item name="android:statusBarColor">@android:color/transparent</item>
    <item name="android:navigationBarColor">@android:color/transparent</item>
    <item name="android:windowLightStatusBar">false</item>
</style>
```

### 4.7 GitHub Actions — `.github/workflows/build-apk.yml`
- Triggers: `push` to main + `workflow_dispatch`
- JDK 17, explicit `platforms;android-35` install
- `permissions: { actions: write, contents: write }`
- Purge old artifacts step (github-script) before upload — quota fills fast
- Upload APK as artifact (continue-on-error) AND to GitHub Releases (the
  reliable path — Releases storage is separate from Actions artifact storage)

## 5. How to resume (for the next AI, or after compaction)

1. Read this whole document.
2. `cd /data/users/HMz891mIWiOCDTetTWuY4OkRbCE2/Workspace/project_completion/agent_42`
3. Check `git log --oneline -5` and `git status` to see current state.
4. Check the latest CI run status before assuming the build is broken:
   ```
   TOKEN=$(git remote get-url origin | sed -E 's|.*://[^:]+:([^@]+)@.*|\1|')
   curl -s -H "Authorization: token $TOKEN" \
     "https://api.github.com/repos/thepotatoninjahost/Agent-42/actions/runs?per_page=1"
   ```
5. The APK is in **GitHub Releases**, not Actions artifacts (quota). Check:
   ```
   curl -s -H "Authorization: token $TOKEN" \
     "https://api.github.com/repos/thepotatoninjahost/Agent-42/releases?per_page=1"
   ```
6. **Before changing build config**, re-read section 1.2. Most "fixes" that
   seem obvious will re-break the build. The config is the way it is for
   real reasons (K2, AGP/SDK compat, Nexa SDK real API).
7. When fixing a build error: get the CI logs first (don't guess):
   ```
   curl -sL -H "Authorization: token $TOKEN" \
     "https://api.github.com/repos/thepotatoninjahost/Agent-42/actions/jobs/<JOB_ID>/logs"
   ```
8. **Never run `git add -A` blindly.** Always check `git status` and
   `git diff --cached --stat` before committing. The 68f2ab4 revert disaster
   happened because of a blind `git add -A` that swept up stale working-tree
   files.
9. After every push, poll the CI run to completion and verify the conclusion
   is `success`, not just that it started.
10. **Update this document** whenever a system is built, a design decision is
    made, or a build config fact changes. This is the record. The previous
    AI failed because it didn't keep one.

## 6. Owner context (read this)

- The owner has spent four years on this. Five hours a day. Missed life
  events. Burned thousands of dollars. Do not waste their time or credits.
- Previous AIs (hundreds of them) failed to help. The owner is exhausted and
  has low trust. Be honest, be concrete, do not over-promise.
- The owner's custom instructions: "Agent42 uses real logic reasoning, context
  understanding, evolves, learns, adapts to situations and improves with every
  interaction. Written in Kotlin. NEVER USE STUBS. NEVER USE PLACEHOLDERS."
- Do not strip the git token from the remote. The owner manages tokens.
- The owner decides what gets built. The AI advises honestly, including
  pushback, then executes the owner's decision fully. (Loyalty Directive,
  `security/CoreConstitution.kt`.)

## 7. Immediate next step

Build the **world model** (section 3), in the order in section 3.6.
Start with the Room schema. Do not touch the other four systems until the
world model is integrated and the build is green.
