package com.leafdoc.app.data.remote.ai.prompts

/**
 * Library of predefined, developer-optimized prompts for corn leaf diagnosis.
 * These prompts are designed for maximum accuracy and consistency across AI providers.
 */
object PromptLibrary {

    // Common disease database used across all prompts
    private const val CORN_DISEASE_DATABASE = """
CORN DISEASE DATABASE (Focus on these common diseases):
- Northern Corn Leaf Blight (Exserohilum turcicum) - Long, elliptical gray-green lesions
- Gray Leaf Spot (Cercospora zeae-maydis) - Rectangular gray/tan lesions
- Southern Corn Leaf Blight (Cochliobolus heterostrophus) - Small tan lesions with dark borders
- Common Rust (Puccinia sorghi) - Orange-brown pustules on both leaf surfaces
- Anthracene (Colletotrichum graminicola) - Irregular necrotic lesions
- Goss's Wilt (Clavibacter michiganensis) - Water-soaked lesions with freckles
- Eyespot (Aureobasidium zeae) - Small circular lesions with tan centers
- Holcus Spot (Pseudomonas syringae) - Small round tan spots
- Tar Spot (Phyllachora maydis) - Raised black spots
- Diplodia Leaf Streak - Long tan streaks
- Physoderma Brown Spot - Yellow-brown spots in bands
"""

    // Common transmittance imaging guidance
    private const val TRANSMITTANCE_IMAGING_CONTEXT = """
VISUAL INDICATORS IN TRANSMITTANCE IMAGING:
- Healthy: Uniform green translucence, clear midrib, no lesions
- Diseased: Dark spots/lesions, necrotic areas, discoloration, irregular patterns
- The backlit imaging enhances lesion visibility and midrib detail
"""

    // Standard JSON output format
    private const val JSON_OUTPUT_FORMAT = """
OUTPUT FORMAT (Respond with ONLY valid JSON, no other text):
{
  "is_healthy": true/false,
  "health_score": 0-100,
  "primary_diagnosis": "disease name or Healthy",
  "confidence": 0-100,
  "leaf_description": "Detailed description of the corn leaf appearance...",
  "diseases": [
    {
      "name": "scientific name",
      "common_name": "common name",
      "probability": 0-100,
      "severity": "LOW" or "MODERATE" or "HIGH" or "SEVERE",
      "description": "brief description of observed symptoms",
      "treatments": ["treatment1", "treatment2"]
    }
  ],
  "suggestions": ["actionable recommendation 1", "recommendation 2"]
}
"""

    /**
     * Quick Health Check - Fast assessment for basic screening
     */
    fun getQuickCheckTemplate(): PromptTemplate {
        return PromptTemplate(
            id = "quick_check",
            info = PromptTemplateInfoFactory.createQuickCheckInfo(),
            systemPrompt = """
You are an expert agricultural pathologist specializing in corn (Zea mays) disease diagnosis.
Provide a QUICK preliminary assessment focusing on overall health status.
            """.trimIndent(),
            userPromptTemplate = """
Analyze this corn leaf image for QUICK health assessment.

IMAGE CONTEXT:
- Imaging method: {{IMAGING_METHOD}}
- Crop: Corn/Maize (Zea mays){{LOCATION_CONTEXT}}

QUICK ASSESSMENT REQUIREMENTS:
1. Overall health status (healthy vs diseased)
2. Estimated health score
3. If diseased, identify the most likely disease
4. Brief visual description
5. One or two key recommendations

$TRANSMITTANCE_IMAGING_CONTEXT

$CORN_DISEASE_DATABASE

IMPORTANT NOTES:
- This is a PRELIMINARY assessment for quick screening
- Health_score is an ESTIMATED indicator, not a definitive diagnosis
- If uncertain, mark confidence as low and recommend detailed analysis
- Focus on obvious, visible symptoms
            """.trimIndent(),
            outputFormatInstructions = JSON_OUTPUT_FORMAT,
            temperature = 0.15f,
            maxTokens = 1024
        )
    }

    /**
     * Standard Disease Analysis - Balanced detail and speed
     */
    fun getStandardAnalysisTemplate(): PromptTemplate {
        return PromptTemplate(
            id = "standard_analysis",
            info = PromptTemplateInfoFactory.createStandardAnalysisInfo(),
            systemPrompt = """
You are an expert agricultural pathologist specializing in corn (Zea mays) diseases.
Analyze the provided leaf image for disease symptoms and provide a balanced assessment with treatment recommendations.
            """.trimIndent(),
            userPromptTemplate = """
Analyze this corn leaf image for disease diagnosis with treatment recommendations.

IMAGE CONTEXT:
- Imaging method: {{IMAGING_METHOD}}
- Crop: Corn/Maize (Zea mays){{LOCATION_CONTEXT}}

ANALYSIS REQUIREMENTS:
1. Detailed visual description of the corn leaf
2. Overall health assessment with estimated health score
3. Identify specific diseases if present (up to 3 most likely)
4. Estimate disease severity for each identified disease
5. Provide practical treatment recommendations
6. Suggest actionable next steps

$TRANSMITTANCE_IMAGING_CONTEXT

$CORN_DISEASE_DATABASE

DESCRIPTION GUIDELINES for leaf_description:
- Overall leaf condition (healthy appearance, signs of stress)
- Coloration: base color, yellowing, browning, unusual hues
- Midrib: visibility, color, abnormalities
- Lesions: quantity, distribution, size, shape, color, borders
- Tissue health: turgidity, wilting, necrotic areas
- Leaf edges and tip condition
- Symptom distribution patterns

IMPORTANT NOTES:
- The health_score is an ESTIMATED indicator only, not a definitive diagnosis
- This analysis should guide further investigation and treatment planning
- Always recommend consulting with local agricultural experts for confirmation
- Be precise and conservative in disease identification
- If uncertain, provide multiple disease candidates with probabilities
            """.trimIndent(),
            outputFormatInstructions = JSON_OUTPUT_FORMAT,
            temperature = 0.2f,
            maxTokens = 2048
        )
    }

    /**
     * Detailed Pathology Report - Comprehensive analysis
     */
    fun getDetailedDiagnosisTemplate(): PromptTemplate {
        return PromptTemplate(
            id = "detailed_diagnosis",
            info = PromptTemplateInfoFactory.createDetailedDiagnosisInfo(),
            systemPrompt = """
You are a senior agricultural pathologist with expertise in corn (Zea mays) disease diagnosis.
Provide a COMPREHENSIVE pathology report with detailed symptom analysis and differential diagnosis.
            """.trimIndent(),
            userPromptTemplate = """
Conduct a COMPREHENSIVE pathological analysis of this corn leaf image.

IMAGE CONTEXT:
- Imaging method: {{IMAGING_METHOD}}
- Crop: Corn/Maize (Zea mays){{LOCATION_CONTEXT}}

COMPREHENSIVE ANALYSIS REQUIREMENTS:
1. EXTREMELY DETAILED visual description of the corn leaf
   - Overall morphology and condition
   - Color patterns and gradients
   - Midrib structure and integrity
   - Lesion characteristics (size, shape, color, texture, distribution)
   - Tissue condition (necrosis, chlorosis, water-soaking)
   - Edge and tip condition
2. Comprehensive health assessment
3. Differential diagnosis with ALL plausible diseases (ranked by probability)
4. Severity assessment for each disease
5. Disease progression stage if applicable
6. Detailed treatment protocols for top candidates
7. Preventative measures
8. Follow-up recommendations

$TRANSMITTANCE_IMAGING_CONTEXT

$CORN_DISEASE_DATABASE

DETAILED DESCRIPTION GUIDELINES:
- Begin with overall leaf structure and condition
- Describe coloration systematically: base color, variations, gradients
- Detail the midrib: prominence, color, texture, any lesions or damage
- Enumerate lesions with precision:
  * Total count or density estimate
  * Size range (e.g., 2-5mm diameter)
  * Shape (circular, elliptical, irregular, rectangular)
  * Color (tan, gray, brown, black, orange)
  * Border characteristics (defined, diffuse, dark-bordered)
  * Distribution pattern (scattered, clustered, linear, bands)
- Describe tissue health:
  * Turgor (firm, wilted, flaccid)
  * Areas of necrosis (location, extent)
  * Chlorotic zones (yellowing patterns)
  * Water-soaked regions if present
- Note leaf margins and tips: intact, damaged, curled, necrotic
- Comment on any patterns suggesting systemic vs localized infection

TREATMENT PROTOCOL GUIDELINES:
- Specify fungicide/bactericide names and application rates
- Include cultural practices (irrigation management, crop rotation)
- Note timing of interventions
- Provide resistance management strategies

IMPORTANT NOTES:
- The health_score is an ESTIMATED indicator based on visual analysis
- This comprehensive report supports treatment planning and research
- Multiple diseases may be present simultaneously
- Recommend laboratory confirmation for critical cases
- Consider environmental factors and local disease pressure
            """.trimIndent(),
            outputFormatInstructions = JSON_OUTPUT_FORMAT,
            temperature = 0.25f,
            maxTokens = 4096
        )
    }

    /**
     * Research-Grade Analysis - Maximum detail for research purposes
     */
    fun getResearchModeTemplate(): PromptTemplate {
        return PromptTemplate(
            id = "research_mode",
            info = PromptTemplateInfoFactory.createResearchModeInfo(),
            systemPrompt = """
You are a research-level plant pathologist specializing in corn (Zea mays) disease diagnostics.
Provide a research-grade analysis suitable for academic publication or detailed field trials.
Use precise botanical and pathological terminology.
            """.trimIndent(),
            userPromptTemplate = """
Conduct a RESEARCH-GRADE pathological analysis of this corn leaf specimen.

SPECIMEN CONTEXT:
- Imaging method: {{IMAGING_METHOD}}
- Species: Zea mays L. (Poaceae){{LOCATION_CONTEXT}}

RESEARCH-GRADE ANALYSIS REQUIREMENTS:
1. EXHAUSTIVE morphological description using botanical terminology
   - Leaf architecture and venation patterns
   - Epidermal integrity assessment
   - Mesophyll tissue condition (visible through transmittance)
   - Vascular bundle (midrib) structure
   - Symptom morphology with precise measurements
2. Quantitative health metrics
   - Estimated photosynthetic area remaining
   - Necrotic tissue percentage
   - Lesion density (per cm²)
3. Complete differential diagnosis
   - All plausible pathogens with probability estimates
   - Evidence for and against each candidate
   - Consider co-infections and secondary pathogens
4. Disease severity using standardized scales
5. Epidemiological considerations
   - Disease cycle stage
   - Environmental conditions favoring each pathogen
   - Inoculum source hypotheses
6. Comprehensive management recommendations
   - Chemical control with active ingredients and FRAC codes
   - Biological control options
   - Cultural practices and resistant varieties
   - Integrated pest management (IPM) strategies
7. Research recommendations and knowledge gaps

$TRANSMITTANCE_IMAGING_CONTEXT

$CORN_DISEASE_DATABASE

ADDITIONAL PATHOGENS TO CONSIDER:
- Stewart's Wilt (Pantoea stewartii)
- Bacterial Leaf Streak (Xanthomonas vasicola)
- Yellow Leaf Blight (Ascochyta spp.)
- Fusarium Ear and Stalk Rot (secondary leaf symptoms)
- Helminthosporium Leaf Spot complex
- Viral infections (MDMV, SCMV)

BOTANICAL DESCRIPTION GUIDELINES:
- Use standard botanical terminology
- Provide quantitative data where possible
- Note developmental stage indicators
- Describe symptom progression if stages are visible
- Comment on leaf anatomy visible through transmittance

IMPORTANT NOTES:
- This analysis supports research-grade documentation
- Include confidence intervals and uncertainty assessments
- Note any limitations of single-image analysis
- Recommend complementary diagnostic methods (PCR, culture, ELISA)
- The health_score is an estimated indicator for research tracking
- Consider genetic resistance patterns if identifiable
            """.trimIndent(),
            outputFormatInstructions = JSON_OUTPUT_FORMAT,
            temperature = 0.3f,
            maxTokens = 4096
        )
    }

    /**
     * Gets all available prompt templates.
     */
    fun getAllTemplates(): List<PromptTemplate> = listOf(
        getQuickCheckTemplate(),
        getStandardAnalysisTemplate(),
        getDetailedDiagnosisTemplate(),
        getResearchModeTemplate()
    )

    /**
     * Gets a template by ID.
     */
    fun getTemplateById(id: String): PromptTemplate? = when (id) {
        "quick_check" -> getQuickCheckTemplate()
        "standard_analysis" -> getStandardAnalysisTemplate()
        "detailed_diagnosis" -> getDetailedDiagnosisTemplate()
        "research_mode" -> getResearchModeTemplate()
        else -> null
    }
}
