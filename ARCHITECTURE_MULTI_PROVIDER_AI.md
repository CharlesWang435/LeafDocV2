# Multi-Provider AI Architecture

This document describes the architecture for LeafDoc's multi-provider AI diagnosis system with premade prompt templates.

## Architecture Overview

The system follows the **Strategy Pattern** for AI providers and **Template Method Pattern** for prompts, enabling:

- Seamless switching between AI providers (Gemini, Claude, ChatGPT)
- Developer-defined, optimized prompt templates
- User-friendly selection UI
- Secure API key management
- Graceful error handling and fallbacks

---

## Component Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                         UI Layer                                 │
├─────────────────────────────────────────────────────────────────┤
│  AiSettingsScreen                                               │
│  └── AiSettingsViewModel                                        │
│      ├── Observes: selectedProvider, selectedPromptTemplate    │
│      └── Updates: UserPreferencesManager                        │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                      Repository Layer                            │
├─────────────────────────────────────────────────────────────────┤
│  DiagnosisRepository                                            │
│  ├── Reads: UserPreferencesManager (provider, template)        │
│  ├── Gets: AiProvider from AiProviderFactory                   │
│  ├── Builds: Prompt from PromptLibrary                         │
│  └── Executes: AI analysis                                     │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                      AI Provider Layer                           │
├─────────────────────────────────────────────────────────────────┤
│  AiProviderFactory                                              │
│  ├── GeminiAiProvider    (Gemini 2.5 Flash)                   │
│  ├── ClaudeAiProvider    (Claude 3.5 Sonnet)                  │
│  └── ChatGptAiProvider   (GPT-4o)                              │
│                                                                  │
│  All implement: AiProvider interface                            │
│  └── analyzeLeafImage(bitmap, promptText) -> DiagnosisDisplay  │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                      Prompt Layer                                │
├─────────────────────────────────────────────────────────────────┤
│  PromptLibrary                                                  │
│  ├── Quick Health Check                                        │
│  ├── Standard Disease Analysis (default)                       │
│  ├── Detailed Pathology Report                                 │
│  └── Research-Grade Analysis                                   │
│                                                                  │
│  PromptTemplate                                                 │
│  └── buildPrompt(lat, lon) -> String                           │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                      Data Layer                                  │
├─────────────────────────────────────────────────────────────────┤
│  UserPreferencesManager (DataStore)                            │
│  ├── aiProvider: Flow<AiProviderType>                         │
│  ├── promptTemplateId: Flow<String>                            │
│  └── Updates: persist user selections                          │
│                                                                  │
│  Room Database                                                  │
│  └── Stores: DiagnosisDisplay results                          │
└─────────────────────────────────────────────────────────────────┘
```

---

## Key Design Patterns

### 1. Strategy Pattern (AI Providers)

**Problem**: Need to support multiple AI providers with identical interfaces but different implementations.

**Solution**: Common `AiProvider` interface with provider-specific implementations.

```kotlin
interface AiProvider {
    suspend fun analyzeLeafImage(
        sessionId: String,
        bitmap: Bitmap,
        promptText: String,
        latitude: Double?,
        longitude: Double?
    ): Result<DiagnosisDisplay>

    fun isConfigured(): Boolean
    fun getProviderType(): String
}
```

**Benefits**:
- Easy to add new providers
- Provider selection at runtime
- Consistent error handling
- Testable in isolation

### 2. Factory Pattern (Provider Creation)

**Problem**: Need centralized provider instantiation and configuration checking.

**Solution**: `AiProviderFactory` manages provider lifecycle.

```kotlin
class AiProviderFactory @Inject constructor(
    private val geminiProvider: GeminiAiProvider,
    private val claudeProvider: ClaudeAiProvider,
    private val chatGptProvider: ChatGptAiProvider
) {
    fun getProvider(type: AiProviderType): AiProvider?
    fun getConfiguredProviders(): List<Pair<AiProviderType, AiProvider>>
}
```

**Benefits**:
- Single source of truth
- API key validation
- Provider enumeration for UI

### 3. Template Method Pattern (Prompts)

**Problem**: Need flexible, reusable prompts with contextual information.

**Solution**: `PromptTemplate` with placeholders and builder method.

```kotlin
data class PromptTemplate(
    val systemPrompt: String,
    val userPromptTemplate: String,
    val outputFormatInstructions: String
) {
    fun buildPrompt(lat: Double?, lon: Double?): String {
        // Replace placeholders and assemble
    }
}
```

**Benefits**:
- Developer-controlled prompt optimization
- Consistent output format
- Context injection (location, imaging method)

### 4. Repository Pattern (Data Access)

**Problem**: Need abstraction layer between ViewModels and data sources.

**Solution**: `DiagnosisRepository` coordinates AI providers and preferences.

```kotlin
class DiagnosisRepository @Inject constructor(
    private val aiProviderFactory: AiProviderFactory,
    private val preferencesManager: UserPreferencesManager,
    private val sessionDao: LeafSessionDao
) {
    suspend fun analyzeLeaf(...): Result<DiagnosisDisplay> {
        // 1. Get user preferences
        // 2. Get configured provider
        // 3. Build prompt from template
        // 4. Execute analysis
        // 5. Save to database
    }
}
```

**Benefits**:
- Single responsibility
- Testable business logic
- Error handling centralization

---

## Data Flow

### Analysis Request Flow

```
1. User taps "Analyze" button
   └── ResultsViewModel.analyzeDiagnosis()

2. ViewModel calls repository
   └── DiagnosisRepository.analyzeLeaf(sessionId, imagePath, lat, lon)

3. Repository reads user preferences
   ├── selectedProvider = preferencesManager.aiProvider.first()
   └── promptTemplateId = preferencesManager.promptTemplateId.first()

4. Repository gets AI provider
   └── aiProvider = aiProviderFactory.getProvider(selectedProvider)

5. Repository builds prompt
   ├── template = PromptLibrary.getTemplateById(promptTemplateId)
   └── promptText = template.buildPrompt(lat, lon)

6. Repository executes analysis
   └── result = aiProvider.analyzeLeafImage(sessionId, bitmap, promptText)

7. Repository saves result
   ├── sessionDao.updateDiagnosis(sessionId, COMPLETED, result)
   └── return DiagnosisDisplay to ViewModel

8. ViewModel updates UI state
   └── UI displays diagnosis results
```

### Settings Update Flow

```
1. User selects new AI provider
   └── AiSettingsScreen: onProviderSelected(CLAUDE)

2. ViewModel updates preferences
   └── viewModel.updateProvider(CLAUDE)

3. Preferences persisted to DataStore
   └── preferencesManager.updateAiProvider(CLAUDE)

4. Next analysis uses new provider
   └── DiagnosisRepository reads updated preference
```

---

## API Key Management

### Build-Time Configuration

```kotlin
// app/build.gradle.kts
buildConfigField("String", "GEMINI_API_KEY", "\"${localProperties.getProperty("GEMINI_API_KEY", "")}\"")
buildConfigField("String", "CLAUDE_API_KEY", "\"${localProperties.getProperty("CLAUDE_API_KEY", "")}\"")
buildConfigField("String", "CHATGPT_API_KEY", "\"${localProperties.getProperty("CHATGPT_API_KEY", "")}\"")
```

### Dependency Injection

```kotlin
// AiModule.kt
@Provides
@Singleton
fun provideGeminiAiProvider(gson: Gson): GeminiAiProvider {
    return GeminiAiProvider(
        gson = gson,
        apiKey = BuildConfig.GEMINI_API_KEY
    )
}
```

### Security Considerations

1. **Never hardcode keys**: Always use `local.properties`
2. **Gitignore protection**: `local.properties` is excluded from version control
3. **ProGuard/R8**: Keys are obfuscated in release builds
4. **Runtime validation**: `isConfigured()` checks key presence before use

---

## Error Handling Strategy

### Provider-Level Errors

```kotlin
// In each AiProvider implementation
override suspend fun analyzeLeafImage(...): Result<DiagnosisDisplay> {
    return try {
        // 1. Network request
        // 2. Response parsing
        // 3. DiagnosisDisplay creation
        Result.success(diagnosis)
    } catch (e: Exception) {
        Timber.e(e, "Analysis failed")
        Result.failure(e)
    }
}
```

### Repository-Level Errors

```kotlin
// In DiagnosisRepository
suspend fun analyzeLeaf(...): Result<DiagnosisDisplay> {
    try {
        val aiProvider = aiProviderFactory.getProvider(selectedProvider)
        if (aiProvider == null) {
            // Provider not configured
            sessionDao.updateDiagnosis(sessionId, FAILED, "Provider not configured")
            return Result.failure(Exception("Provider not configured"))
        }

        val result = aiProvider.analyzeLeafImage(...)
        result.fold(
            onSuccess = { /* Update DB with success */ },
            onFailure = { /* Update DB with error */ }
        )
    } catch (e: Exception) {
        // Unexpected error
        sessionDao.updateDiagnosis(sessionId, FAILED, e.message)
        return Result.failure(e)
    }
}
```

### UI-Level Errors

```kotlin
// In ResultsViewModel
viewModelScope.launch {
    val result = diagnosisRepository.analyzeLeaf(...)
    result.fold(
        onSuccess = { diagnosis ->
            _uiState.value = UiState.Success(diagnosis)
        },
        onFailure = { error ->
            _uiState.value = UiState.Error(error.message ?: "Analysis failed")
        }
    )
}
```

---

## Prompt Template Design

### Structure

Each `PromptTemplate` has:

1. **System Prompt**: Defines AI's role and expertise
2. **User Prompt Template**: Analysis instructions with placeholders
3. **Output Format Instructions**: JSON schema specification
4. **Configuration**: Temperature, max tokens, etc.

### Placeholders

- `{{LOCATION_CONTEXT}}`: Replaced with GPS coordinates
- `{{IMAGING_METHOD}}`: Replaced with transmittance imaging description

### Optimization Techniques

1. **Specificity**: Detailed instructions reduce ambiguity
2. **Examples**: Show desired output format
3. **Constraints**: Limit response to required fields
4. **Context**: Provide domain knowledge (disease database)
5. **Tone**: Professional, precise language

### Template Comparison

| Template | Tokens | Duration | Best For |
|----------|--------|----------|----------|
| Quick Check | ~800 | 5-10s | Field screening |
| Standard | ~1500 | 10-20s | Regular diagnosis |
| Detailed | ~2500 | 20-30s | Complex cases |
| Research | ~3000 | 20-30s | Academic work |

---

## Testing Strategy

### Unit Tests

```kotlin
// Test provider selection
@Test
fun `factory returns configured provider`() {
    val provider = factory.getProvider(AiProviderType.GEMINI)
    assertNotNull(provider)
    assertTrue(provider.isConfigured())
}

// Test prompt building
@Test
fun `prompt template builds with location context`() {
    val template = PromptLibrary.getStandardAnalysisTemplate()
    val prompt = template.buildPrompt(lat = 40.0, lon = -75.0)
    assertTrue(prompt.contains("Latitude 40.0"))
}

// Test preference persistence
@Test
fun `preferences manager saves provider selection`() = runTest {
    preferencesManager.updateAiProvider(AiProviderType.CLAUDE)
    val savedProvider = preferencesManager.aiProvider.first()
    assertEquals(AiProviderType.CLAUDE, savedProvider)
}
```

### Integration Tests

```kotlin
@Test
fun `diagnosis repository uses selected provider`() = runTest {
    // Setup: Configure Claude provider
    preferencesManager.updateAiProvider(AiProviderType.CLAUDE)

    // Execute: Analyze image
    val result = repository.analyzeLeaf(sessionId, imagePath)

    // Verify: Claude was used
    assertTrue(result.isSuccess)
    verify(claudeProvider).analyzeLeafImage(any(), any(), any(), any(), any())
}
```

### UI Tests

```kotlin
@Test
fun `settings screen shows configured providers`() {
    composeTestRule.setContent {
        AiSettingsScreen(onNavigateBack = {})
    }

    // Verify all providers displayed
    composeTestRule.onNodeWithText("Google Gemini").assertExists()
    composeTestRule.onNodeWithText("Anthropic Claude").assertExists()

    // Verify configuration status
    composeTestRule.onNodeWithText("NOT CONFIGURED").assertExists()
}
```

---

## Performance Considerations

### Image Compression

Each provider has size limits:
- **Gemini**: 4MP max
- **Claude**: 5MB max
- **ChatGPT**: 20MB max

Repository handles compression:
```kotlin
private fun resizeBitmapIfNeeded(bitmap: Bitmap): Bitmap {
    val maxPixels = 4_000_000
    if (currentPixels > maxPixels) {
        // Scale down proportionally
    }
    return bitmap
}
```

### Network Efficiency

1. **Request Batching**: Not applicable (one image per request)
2. **Caching**: Diagnoses cached in Room database
3. **Timeout Handling**: OkHttp default timeouts (10s connect, 30s read)
4. **Retry Logic**: Not implemented (user can retry manually)

### Memory Management

1. **Bitmap Recycling**: Always recycle bitmaps after analysis
2. **Lazy Initialization**: Providers instantiated on-demand
3. **Flow-Based State**: Prevents memory leaks in ViewModels

---

## Migration Guide

### From GeminiAiService to Multi-Provider

#### Before
```kotlin
@Singleton
class DiagnosisRepository @Inject constructor(
    private val geminiAiService: GeminiAiService,
    ...
) {
    suspend fun analyzeLeaf(...) {
        val result = geminiAiService.analyzeLeafImage(...)
    }
}
```

#### After
```kotlin
@Singleton
class DiagnosisRepository @Inject constructor(
    private val aiProviderFactory: AiProviderFactory,
    private val preferencesManager: UserPreferencesManager,
    ...
) {
    suspend fun analyzeLeaf(...) {
        val selectedProvider = preferencesManager.aiProvider.first()
        val aiProvider = aiProviderFactory.getProvider(selectedProvider)
        val template = PromptLibrary.getTemplateById(templateId)
        val prompt = template.buildPrompt(lat, lon)
        val result = aiProvider.analyzeLeafImage(..., prompt)
    }
}
```

### Backward Compatibility

- `GeminiAiService` remains in codebase but deprecated
- Existing diagnoses in database remain valid
- Old code paths can be removed after migration verification

---

## Future Enhancements

### 1. Custom Prompt Editor

Allow users to create custom prompts:
- In-app template editor
- Save custom templates to DataStore
- Share templates between devices

### 2. A/B Testing

Compare provider accuracy:
- Analyze same image with multiple providers
- Show side-by-side results
- Collect user feedback on accuracy

### 3. Offline Support

Local AI models for offline diagnosis:
- TensorFlow Lite integration
- Pre-trained corn disease model
- Fallback when no internet connection

### 4. Provider Fallback

Automatic failover on errors:
- If primary provider fails, try secondary
- Configurable fallback order
- Success/failure tracking

### 5. Usage Analytics

Track provider performance:
- Response times
- Error rates
- Cost tracking
- User satisfaction ratings

---

## File Structure Summary

```
app/src/main/java/com/leafdoc/app/
├── data/
│   ├── model/
│   │   ├── AiProviderType.kt                    # Provider enum
│   │   ├── PromptTemplateInfo.kt                # UI-facing template info
│   │   └── DiagnosisDisplay.kt                  # Existing diagnosis model
│   ├── remote/
│   │   ├── ai/
│   │   │   ├── AiProvider.kt                    # Common interface
│   │   │   ├── GeminiAiProvider.kt              # Gemini implementation
│   │   │   ├── ClaudeAiProvider.kt              # Claude implementation
│   │   │   ├── ChatGptAiProvider.kt             # ChatGPT implementation
│   │   │   ├── AiProviderFactory.kt             # Provider factory
│   │   │   └── prompts/
│   │   │       ├── PromptTemplate.kt            # Template model
│   │   │       └── PromptLibrary.kt             # Predefined prompts
│   │   └── GeminiAiService.kt                   # DEPRECATED
│   ├── preferences/
│   │   └── UserPreferencesManager.kt            # Updated with AI settings
│   └── repository/
│       └── DiagnosisRepository.kt               # Updated to use providers
├── di/
│   ├── AiModule.kt                              # NEW: AI provider DI
│   └── AppModule.kt                             # Updated dependencies
└── ui/
    └── settings/
        └── AiSettingsScreen.kt                  # NEW: Settings UI
```

---

## Dependencies Added

No new dependencies required. The implementation uses existing:
- `okhttp` (for Claude and ChatGPT HTTP requests)
- `gson` (for JSON parsing)
- `datastore-preferences` (for settings persistence)
- `hilt` (for dependency injection)

---

## Conclusion

This architecture provides:

1. **Flexibility**: Easy to add new providers or prompt templates
2. **Maintainability**: Clear separation of concerns
3. **Testability**: Each component testable in isolation
4. **User Control**: Simple UI for provider/template selection
5. **Security**: Secure API key management
6. **Performance**: Efficient bitmap handling and caching
7. **Scalability**: Supports future enhancements

The system is production-ready and follows Android best practices for MVVM architecture, dependency injection, and reactive programming with Kotlin Flows.

---

**Document Version**: 1.0
**Last Updated**: 2025-12-12
**Author**: Claude Opus 4.5
