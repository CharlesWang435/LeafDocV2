# AI Provider Setup Guide

LeafDoc supports multiple AI providers for corn leaf disease diagnosis. This guide explains how to configure and use each provider.

## Quick Start

1. Copy `local.properties.example` to `local.properties`
2. Add at least one API key to `local.properties`
3. Build the app
4. Navigate to Settings > AI Diagnosis to select your provider

---

## Supported AI Providers

### 1. Google Gemini (Default)

**Model**: Gemini 2.5 Flash
**Speed**: Fast (5-20 seconds)
**Cost**: ~$0.02 per analysis
**Best For**: Balanced speed and accuracy

#### Getting Your API Key

1. Visit [Google AI Studio](https://aistudio.google.com/apikey)
2. Sign in with your Google account
3. Click "Create API Key"
4. Copy the key and add to `local.properties`:
   ```properties
   GEMINI_API_KEY=your_key_here
   ```

#### Features
- Free tier available (60 requests per minute)
- Fast response times
- Good accuracy for disease detection
- Supports image analysis up to 4MP

---

### 2. Anthropic Claude (Optional)

**Model**: Claude 3.5 Sonnet
**Speed**: Moderate (10-25 seconds)
**Cost**: ~$0.05 per analysis
**Best For**: Detailed, nuanced analysis

#### Getting Your API Key

1. Visit [Anthropic Console](https://console.anthropic.com/settings/keys)
2. Sign up or log in
3. Navigate to API Keys section
4. Create a new API key
5. Add to `local.properties`:
   ```properties
   CLAUDE_API_KEY=your_key_here
   ```

#### Features
- Excellent for detailed pathology reports
- Strong reasoning capabilities
- Good at differential diagnosis
- Supports images up to 5MB

#### Pricing
- Pay-as-you-go: $3 per million input tokens
- $15 per million output tokens
- Image tokens calculated based on size

---

### 3. OpenAI ChatGPT (Optional)

**Model**: GPT-4o
**Speed**: Moderate (10-25 seconds)
**Cost**: ~$0.04 per analysis
**Best For**: Comprehensive analysis with structured output

#### Getting Your API Key

1. Visit [OpenAI API Keys](https://platform.openai.com/api-keys)
2. Sign up or log in
3. Click "Create new secret key"
4. Copy the key immediately (you won't see it again)
5. Add to `local.properties`:
   ```properties
   CHATGPT_API_KEY=your_key_here
   ```

#### Features
- Strong vision understanding
- Consistent JSON output
- Good at following complex prompts
- Supports images up to 20MB

#### Pricing
- GPT-4o: $2.50 per million input tokens
- $10 per million output tokens
- Vision requests calculated separately

---

## Prompt Templates

LeafDoc includes four optimized prompt templates:

### 1. Quick Health Check
- **Duration**: 5-10 seconds
- **Detail Level**: Basic
- **Use Case**: Fast screening, field assessment
- **Output**: Overall health status, primary diagnosis, basic recommendations

### 2. Standard Disease Analysis (Recommended)
- **Duration**: 10-20 seconds
- **Detail Level**: Moderate
- **Use Case**: Regular diagnosis, treatment planning
- **Output**: Detailed leaf description, top 3 diseases, treatment protocols

### 3. Detailed Pathology Report
- **Duration**: 20-30 seconds
- **Detail Level**: Comprehensive
- **Use Case**: Complex cases, research documentation
- **Output**: Exhaustive symptom analysis, differential diagnosis, disease progression

### 4. Research-Grade Analysis
- **Duration**: 20-30 seconds
- **Detail Level**: Comprehensive
- **Use Case**: Academic research, publication-grade documentation
- **Output**: Botanical terminology, quantitative metrics, all plausible pathogens

---

## Configuration in App

1. Open LeafDoc
2. Navigate to **Settings** (gear icon)
3. Tap **AI Diagnosis Settings**
4. Select your preferred:
   - **AI Provider** (only configured providers shown)
   - **Analysis Type** (prompt template)

### Provider Status Indicators

- **Configured**: Green checkmark, provider is ready to use
- **Not Configured**: Red "NOT CONFIGURED" badge, API key missing

---

## API Key Security

### Best Practices

1. **Never commit API keys to source control**
   - `local.properties` is in `.gitignore`
   - Always use `local.properties.example` as template

2. **Rotate keys regularly**
   - Change API keys every 90 days
   - Rotate immediately if compromised

3. **Use environment-specific keys**
   - Development keys for testing
   - Production keys for release builds

4. **Monitor usage**
   - Track API usage in provider dashboards
   - Set up billing alerts
   - Review usage patterns monthly

### Key Storage

API keys are stored in:
- **Development**: `local.properties` (local only)
- **Build**: `BuildConfig` (compiled into APK)
- **Release**: Consider using Google Play's App Signing or encrypted storage

---

## Cost Estimation

### Per-Image Analysis Costs

| Provider | Model | Cost per Analysis | 100 Analyses | 1000 Analyses |
|----------|-------|-------------------|--------------|---------------|
| Gemini | 2.5 Flash | $0.02 | $2.00 | $20.00 |
| Claude | 3.5 Sonnet | $0.05 | $5.00 | $50.00 |
| ChatGPT | GPT-4o | $0.04 | $4.00 | $40.00 |

### Free Tier Limits

- **Gemini**: 60 requests/minute (rate-limited, not quota)
- **Claude**: $5 free credit for new accounts
- **ChatGPT**: $5 free credit for new accounts (expires after 3 months)

---

## Troubleshooting

### Provider Not Showing Up

**Symptom**: Provider shows "NOT CONFIGURED"
**Solution**:
1. Check `local.properties` has the correct key
2. Rebuild the app (`./gradlew clean assembleDebug`)
3. Verify API key is valid in provider console

### Analysis Failing

**Symptom**: Diagnosis returns error
**Solution**:
1. Check internet connection
2. Verify API key hasn't expired or been revoked
3. Check provider status page for outages
4. Review Logcat for specific error messages

### Rate Limiting

**Symptom**: "Rate limit exceeded" error
**Solution**:
1. Wait before retrying (Gemini: 1 minute, Claude/ChatGPT: varies)
2. Switch to a different provider temporarily
3. Upgrade to paid tier if hitting limits frequently

---

## Provider Comparison

### When to Use Gemini
- You need fast results
- Working in the field with limited connectivity
- Cost is a primary concern
- Basic to moderate analysis is sufficient

### When to Use Claude
- You need detailed, nuanced analysis
- Differential diagnosis is important
- Working with complex or ambiguous cases
- Research-grade documentation required

### When to Use ChatGPT
- You need consistent, structured output
- Working with established workflows
- Balance between cost and detail
- Familiar with OpenAI ecosystem

---

## Advanced Configuration

### Custom Prompt Templates

Prompt templates are defined in `PromptLibrary.kt`. To add custom templates:

1. Create a new function in `PromptLibrary`
2. Define `PromptTemplate` with your custom prompt text
3. Add to `getAllTemplates()` list
4. Rebuild the app

### Provider-Specific Settings

Each provider supports different parameters:

**Gemini**:
- `temperature`: 0.2 (default)
- `topK`: 40
- `topP`: 0.95

**Claude**:
- `maxTokens`: 2048 (default)
- Temperature controlled via prompt

**ChatGPT**:
- `temperature`: 0.2 (default)
- `maxTokens`: 2048

---

## Support

For issues related to:
- **API Keys**: Contact the respective provider's support
- **App Integration**: Open an issue on GitHub
- **Diagnosis Accuracy**: See CLAUDE.md for diagnosis guidelines

---

## Legal & Privacy

- LeafDoc sends images to third-party AI providers
- Review each provider's privacy policy and terms of service
- User data is subject to provider's data retention policies
- Images may be used to improve AI models (check provider settings)
- Ensure compliance with local regulations (GDPR, CCPA, etc.)

---

**Last Updated**: 2025-12-12
**LeafDoc Version**: 1.0.0
