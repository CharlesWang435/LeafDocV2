# AI Provider Setup

LeafDoc performs disease diagnosis through a cloud vision model. Three providers are supported and are selected at runtime from **Settings → AI Provider & Prompts**; the analysis model is a user setting, not a build-time decision.

Diagnosis is optional. The application builds, captures, stores, and exports images with no API key configured.

## Quick start

1. Copy `local.properties.example` to `local.properties`.
2. Add at least one provider key (see [Obtaining keys](#obtaining-keys)).
3. Rebuild the application — keys are compiled in through `BuildConfig`.
4. Open **Settings → AI Provider & Prompts** and select a provider and an analysis type.

Only providers with a key present are selectable; the rest are shown with a **NOT CONFIGURED** badge.

---

## Supported providers

| Provider | Model ID in code | Source file | Default |
| --- | --- | --- | --- |
| Google Gemini | `gemini-2.5-flash` | `GeminiAiProvider.kt` | Yes |
| Anthropic Claude | `claude-sonnet-5` | `ClaudeAiProvider.kt` | No |
| OpenAI | `gpt-4o` | `ChatGptAiProvider.kt` | No |

### Note on the Claude provider

The Claude provider previously targeted `claude-3-5-sonnet-20241022`, which was retired on 28 October 2025 and returns HTTP 404. It now targets `claude-sonnet-5`, the current Sonnet-tier model; `claude-opus-5` is the higher-capability alternative if diagnosis quality matters more than cost.

Two details of that provider are deliberate and worth knowing before changing them:

- **Thinking is explicitly disabled** (`"thinking": {"type": "disabled"}`). Current Claude models otherwise run adaptive thinking, which shares the `max_tokens` budget with the answer — with the 2,048-token budget used here, a long reasoning pass can truncate the JSON before it is complete. To enable thinking instead, remove that field and raise `maxTokens` to roughly 8,000.
- **Response content blocks are parsed by `type`**, not by position, so a leading non-text block does not break parsing.

---

## Obtaining keys

Add each key to `local.properties`. That file is listed in `.gitignore` and must never be committed.

```properties
GEMINI_API_KEY=your_gemini_key_here
CLAUDE_API_KEY=your_anthropic_key_here
CHATGPT_API_KEY=your_openai_key_here
```

| Provider | Console | Notes |
| --- | --- | --- |
| Google Gemini | [Google AI Studio](https://aistudio.google.com/apikey) | Sign in, create an API key, copy it into `local.properties`. |
| Anthropic Claude | [Anthropic Console → API keys](https://console.anthropic.com/settings/keys) | Create a key; it is displayed once. |
| OpenAI | [OpenAI Platform → API keys](https://platform.openai.com/api-keys) | Create a secret key; it is displayed once. |

Rate limits, free-tier allowances, and per-token pricing change frequently and differ by account tier. Consult each provider's own pricing and limits pages rather than any figure cached in this repository:
[Google](https://ai.google.dev/pricing) · [Anthropic](https://platform.claude.com/docs/en/pricing) · [OpenAI](https://openai.com/api/pricing/).

As a rough planning guide, a single LeafDoc analysis sends one downscaled JPEG plus roughly 800–3,000 tokens of prompt text (depending on the selected template) and receives a structured JSON response of a few hundred tokens.

---

## Choosing a provider

| Use case | Suggested provider |
| --- | --- |
| Fast field screening, many images, cost-sensitive work | Gemini |
| Detailed pathology write-ups and differential diagnosis | Claude |
| Established OpenAI tooling or existing OpenAI billing | OpenAI |

All three return the same `DiagnosisDisplay` structure, so results remain comparable when switching providers. Re-running an analysis on a stored image with a different provider is supported from the results screen.

---

## Analysis templates

Four templates ship with the application. All four share a corn-disease reference set and a single JSON output contract; they differ in analysis depth and the amount of prompt context sent.

| Template | ID | Duration shown in app | Detail level | Intended use |
| --- | --- | --- | --- | --- |
| Quick health check | `quick_check` | 5–10 s | Basic | Rapid field screening |
| Standard disease analysis | `standard_analysis` | 10–20 s | Moderate | Routine diagnosis and treatment planning (recommended default) |
| Detailed pathology report | `detailed_diagnosis` | 20–30 s | Comprehensive | Ambiguous or complex cases |
| Research-grade analysis | `research_mode` | 20–30 s | Comprehensive | Documentation intended for publication |

Durations are the estimates surfaced in the settings UI (`PromptDuration` in `PromptTemplateInfo.kt`); actual latency depends on the provider, image size, and network conditions.

---

## API key handling

Keys are read from `local.properties` at build time and emitted as `BuildConfig` fields. This keeps keys out of source control, but it does **not** protect them at runtime: `BuildConfig` constants are recoverable from a distributed APK.

Recommended practice:

- Use development keys for debug builds and rotate them on a schedule.
- Restrict keys by referrer, IP, or usage quota wherever the provider supports it.
- Set billing alerts on each provider account.
- For any build distributed beyond the development team, route diagnosis requests through a backend service that holds the keys rather than shipping them in the APK.

---

## Data sent to providers

Each analysis transmits, to the selected provider only:

- The frame under analysis, as a JPEG (downscaled where required by the provider's image limits). TIFF and DNG captures are sent as their JPEG proxy; the lossless master never leaves the device.
- The prompt text for the selected template.
- The session's GPS coordinates, where recorded, as location context in the prompt.

No image or metadata is transmitted unless a user explicitly starts an analysis. Data retention, and whether inputs may be used for model training, are governed by each provider's terms — review them before using LeafDoc with confidential trial data, and confirm compliance with any applicable regulation (GDPR, CCPA, or institutional data policy).

---

## Troubleshooting

| Symptom | Cause and resolution |
| --- | --- |
| Provider shows **NOT CONFIGURED** | The key is missing from `local.properties`, or the project was not rebuilt after adding it. Run `./gradlew clean assembleDebug`. |
| Claude analyses fail with a 404 | The configured model ID no longer exists. Model IDs are retired over time; check the current list in the [Anthropic model documentation](https://platform.claude.com/docs/en/about-claude/models/overview) and update `CLAUDE_MODEL` in `ClaudeAiProvider.kt`. |
| Analysis returns a JSON parsing error | The response was truncated before the JSON closed. Raise `maxTokens` in the provider, or select a shorter analysis template. |
| `401` / authentication error | The key is invalid, revoked, or belongs to a different organisation. Verify it in the provider console. |
| `429` / rate-limit error | Wait and retry, switch providers temporarily, or raise the account's limits. |
| Analysis fails with no clear error | Check connectivity, then inspect Logcat — providers log request failures through Timber (debug builds log at all levels; release builds log warnings and errors only). |

---

## Extending the provider layer

Adding a provider means implementing the `AiProvider` interface in `data/remote/ai/`, registering it in `AiProviderFactory`, and adding an entry to the `AiProviderType` enum. Prompt templates are defined in `prompts/PromptLibrary.kt` and exposed to the UI through `PromptTemplateInfo`.

See [ARCHITECTURE_MULTI_PROVIDER_AI.md](ARCHITECTURE_MULTI_PROVIDER_AI.md) for the full design of this layer.

> `data/remote/GeminiAiService.kt` and `data/remote/DiagnosisApiService.kt` are legacy code paths that predate the provider abstraction and are no longer used. The live path is `data/remote/ai/`.
