package com.leafdoc.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.leafdoc.app.data.model.AiProviderType
import com.leafdoc.app.data.model.DetailLevel
import com.leafdoc.app.data.model.PromptDuration
import com.leafdoc.app.data.preferences.UserPreferencesManager
import com.leafdoc.app.data.remote.ai.AiProviderFactory
import com.leafdoc.app.data.remote.ai.prompts.PromptLibrary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AiSettingsViewModel @Inject constructor(
    private val preferencesManager: UserPreferencesManager,
    private val aiProviderFactory: AiProviderFactory
) : ViewModel() {

    val selectedProvider: StateFlow<AiProviderType> = preferencesManager.aiProvider
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AiProviderType.GEMINI
        )

    val selectedPromptTemplateId: StateFlow<String> = preferencesManager.promptTemplateId
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = "standard_analysis"
        )

    fun updateProvider(provider: AiProviderType) {
        viewModelScope.launch {
            preferencesManager.updateAiProvider(provider)
        }
    }

    fun updatePromptTemplate(templateId: String) {
        viewModelScope.launch {
            preferencesManager.updatePromptTemplateId(templateId)
        }
    }

    fun isProviderConfigured(provider: AiProviderType): Boolean {
        return aiProviderFactory.isProviderConfigured(provider)
    }

    fun getConfiguredProvidersCount(): Int {
        return aiProviderFactory.getConfiguredProviders().size
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: AiSettingsViewModel = hiltViewModel()
) {
    val selectedProvider by viewModel.selectedProvider.collectAsState()
    val selectedPromptTemplateId by viewModel.selectedPromptTemplateId.collectAsState()
    val allTemplates = remember { PromptLibrary.getAllTemplates() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI Diagnosis Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // AI Provider Selection Section
            AiProviderSection(
                selectedProvider = selectedProvider,
                onProviderSelected = { viewModel.updateProvider(it) },
                isProviderConfigured = { viewModel.isProviderConfigured(it) }
            )

            HorizontalDivider()

            // Prompt Template Selection Section
            PromptTemplateSection(
                selectedTemplateId = selectedPromptTemplateId,
                templates = allTemplates,
                onTemplateSelected = { viewModel.updatePromptTemplate(it) }
            )

            HorizontalDivider()

            // Configuration Status
            ConfigurationStatusCard(
                configuredProvidersCount = viewModel.getConfiguredProvidersCount()
            )
        }
    }
}

@Composable
private fun AiProviderSection(
    selectedProvider: AiProviderType,
    onProviderSelected: (AiProviderType) -> Unit,
    isProviderConfigured: (AiProviderType) -> Boolean
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "AI Provider",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Text(
            text = "Choose the AI model for analyzing corn leaf images",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(4.dp))

        AiProviderType.entries.forEach { provider ->
            val isConfigured = isProviderConfigured(provider)
            ProviderCard(
                provider = provider,
                isSelected = provider == selectedProvider,
                isConfigured = isConfigured,
                onSelected = { if (isConfigured) onProviderSelected(provider) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderCard(
    provider: AiProviderType,
    isSelected: Boolean,
    isConfigured: Boolean,
    onSelected: () -> Unit
) {
    Card(
        onClick = onSelected,
        modifier = Modifier.fillMaxWidth(),
        enabled = isConfigured,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        border = if (isSelected) {
            CardDefaults.outlinedCardBorder().copy(
                width = 2.dp,
                brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary)
            )
        } else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = provider.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isConfigured) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        }
                    )

                    if (!isConfigured) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.errorContainer
                        ) {
                            Text(
                                text = "NOT CONFIGURED",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Text(
                    text = provider.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isConfigured) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    }
                )

                if (isConfigured) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.AttachMoney,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "~$${provider.estimatedCostPerAnalysis / 100f} per analysis",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (isSelected && isConfigured) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
private fun PromptTemplateSection(
    selectedTemplateId: String,
    templates: List<com.leafdoc.app.data.remote.ai.prompts.PromptTemplate>,
    onTemplateSelected: (String) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Analysis Type",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Text(
            text = "Choose the level of detail for AI diagnosis",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(4.dp))

        templates.forEach { template ->
            PromptTemplateCard(
                template = template,
                isSelected = template.id == selectedTemplateId,
                onSelected = { onTemplateSelected(template.id) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PromptTemplateCard(
    template: com.leafdoc.app.data.remote.ai.prompts.PromptTemplate,
    isSelected: Boolean,
    onSelected: () -> Unit
) {
    Card(
        onClick = onSelected,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        border = if (isSelected) {
            CardDefaults.outlinedCardBorder().copy(
                width = 2.dp,
                brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.secondary)
            )
        } else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = template.info.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                )

                Text(
                    text = template.info.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Duration badge
                    DetailBadge(
                        icon = Icons.Default.Schedule,
                        text = template.info.estimatedDuration.displayText,
                        color = when (template.info.estimatedDuration) {
                            PromptDuration.QUICK -> MaterialTheme.colorScheme.tertiary
                            PromptDuration.STANDARD -> MaterialTheme.colorScheme.secondary
                            PromptDuration.DETAILED -> MaterialTheme.colorScheme.primary
                        }
                    )

                    // Detail level badge
                    DetailBadge(
                        icon = Icons.Default.Info,
                        text = template.info.detailLevel.displayText,
                        color = when (template.info.detailLevel) {
                            DetailLevel.BASIC -> MaterialTheme.colorScheme.tertiary
                            DetailLevel.MODERATE -> MaterialTheme.colorScheme.secondary
                            DetailLevel.COMPREHENSIVE -> MaterialTheme.colorScheme.primary
                        }
                    )
                }
            }

            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
private fun DetailBadge(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    color: androidx.compose.ui.graphics.Color
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.1f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = color
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

@Composable
private fun ConfigurationStatusCard(
    configuredProvidersCount: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (configuredProvidersCount > 0) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (configuredProvidersCount > 0) {
                    Icons.Default.CheckCircle
                } else {
                    Icons.Default.Warning
                },
                contentDescription = null,
                tint = if (configuredProvidersCount > 0) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onErrorContainer
                }
            )

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = if (configuredProvidersCount > 0) {
                        "API Configuration Status"
                    } else {
                        "No Providers Configured"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (configuredProvidersCount > 0) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    }
                )

                Text(
                    text = if (configuredProvidersCount > 0) {
                        "$configuredProvidersCount provider${if (configuredProvidersCount > 1) "s" else ""} available"
                    } else {
                        "Add API keys to local.properties to enable AI diagnosis"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (configuredProvidersCount > 0) {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                    }
                )
            }
        }
    }
}
