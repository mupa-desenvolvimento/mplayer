package com.mupa.engage.ui

import com.mupa.engage.analytics.EngageQuestion

enum class EngageCapability {
    PRODUCT_RECOMMENDATION,
    LOOK_SUGGESTION,
    CONVERSATIONAL_AI,
    VIRTUAL_ASSISTANT,
    VOICE_QUERY,
    QR_CODE,
    COUPONS,
    LOYALTY_PROGRAM,
    SMART_CATALOG,
    SATISFACTION_SURVEY,
}

data class EngageSessionProfile(
    val audience: String?,
    val emotion: String?,
    val context: String,
    val enabledCapabilities: Set<EngageCapability>,
)

data class EngageDetectedInfoUi(
    val text: String,
    val caption: String = "Estimativa realizada por IA",
)

private data class FallbackQuestionSpec(
    val id: Long,
    val context: String,
    val audiences: Set<String> = emptySet(),
    val emotions: Set<String> = emptySet(),
    val pergunta: String,
    val opcao1: String,
    val opcao2: String,
) {
    fun toQuestion(): EngageQuestion {
        return EngageQuestion(
            id = id,
            categoria = context,
            publico = audiences.firstOrNull(),
            pergunta = pergunta,
            opcao1 = opcao1,
            opcao2 = opcao2,
        )
    }
}

object EngageExperienceEngine {
    const val DEFAULT_CONTEXT = "renner"

    private val defaultCapabilities = setOf(
        EngageCapability.PRODUCT_RECOMMENDATION,
        EngageCapability.LOOK_SUGGESTION,
        EngageCapability.CONVERSATIONAL_AI,
        EngageCapability.VIRTUAL_ASSISTANT,
        EngageCapability.VOICE_QUERY,
        EngageCapability.QR_CODE,
        EngageCapability.COUPONS,
        EngageCapability.LOYALTY_PROGRAM,
        EngageCapability.SMART_CATALOG,
        EngageCapability.SATISFACTION_SURVEY,
    )

    private val welcomeMessages = listOf(
        "Olá. Que bom ter você por aqui. Posso fazer uma brincadeira rápida com você?",
        "Bem-vindo à Renner. Tenho uma pergunta rápida para você.",
        "Vamos testar seus conhecimentos sobre moda?",
        "Topa participar de um desafio rápido?",
        "Se quiser, eu preparo uma pergunta curtinha e divertida para você.",
    )

    private val cheerfulWelcomeMessages = listOf(
        "Olá. Seu astral deixou a vitrine ainda mais bonita. Topa uma brincadeira rápida?",
        "Que bom ter você por aqui. Vamos fazer um desafio leve sobre moda?",
    )

    private val declineMessages = listOf(
        "Sem problemas. Aproveite sua visita!",
        "Tudo bem. Fique a vontade para explorar a loja.",
        "Sem pressa. Se quiser brincar depois, eu continuo por aqui.",
    )

    private val timeoutMessages = listOf(
        "Tudo certo. Se quiser participar depois, eu volto com outra pergunta.",
        "Sem problema. Quando quiser, a brincadeira continua.",
    )

    private val thanksMessages = listOf(
        "Obrigado pela participação!",
        "Muito bem! Obrigado.",
        "Legal! Obrigado.",
        "Otimo! Ate a proxima.",
    )

    private val feedbackMessages = listOf(
        "Excelente!",
        "Muito legal!",
        "Gostei da sua escolha!",
        "Boa!",
        "Perfeito!",
    )

    private val questionTitles = listOf(
        "Uma pergunta rapidinha",
        "Vamos ver seu palpite",
        "Escolha a opcao que combina mais",
        "Desafio rapido de moda",
    )

    private val fallbackQuestions = listOf(
        FallbackQuestionSpec(
            id = 1001,
            context = DEFAULT_CONTEXT,
            pergunta = "Qual dessas pecas basicas nao pode faltar no seu guarda-roupa?",
            opcao1 = "Camiseta branca",
            opcao2 = "Calca jeans",
        ),
        FallbackQuestionSpec(
            id = 1002,
            context = DEFAULT_CONTEXT,
            pergunta = "Qual voce escolheria para um look casual?",
            opcao1 = "Moletom confortavel",
            opcao2 = "Jaqueta jeans",
        ),
        FallbackQuestionSpec(
            id = 1003,
            context = DEFAULT_CONTEXT,
            pergunta = "Qual dessas pecas combina com praticamente tudo?",
            opcao1 = "Camiseta basica",
            opcao2 = "Jaqueta preta",
        ),
        FallbackQuestionSpec(
            id = 1101,
            context = DEFAULT_CONTEXT,
            pergunta = "🧥 INVERNO\nQual sua peca preferida para os dias frios?",
            opcao1 = "Trench Coat",
            opcao2 = "Jaqueta de couro",
        ),
        FallbackQuestionSpec(
            id = 1102,
            context = DEFAULT_CONTEXT,
            pergunta = "🧥 INVERNO\nQual voce usaria em uma viagem de inverno?",
            opcao1 = "Casaco de la",
            opcao2 = "Jaqueta puffer",
        ),
        FallbackQuestionSpec(
            id = 1103,
            context = DEFAULT_CONTEXT,
            pergunta = "🧥 INVERNO\nQual destes tecidos voce prefere no inverno?",
            opcao1 = "Moletom",
            opcao2 = "Trico",
        ),
        FallbackQuestionSpec(
            id = 1201,
            context = DEFAULT_CONTEXT,
            pergunta = "☀️ VERAO\nQual seu look favorito para o verao?",
            opcao1 = "Vestido leve",
            opcao2 = "Short e camiseta",
        ),
        FallbackQuestionSpec(
            id = 1202,
            context = DEFAULT_CONTEXT,
            pergunta = "☀️ VERAO\nQual dessas cores lembra mais o verao?",
            opcao1 = "Azul claro",
            opcao2 = "Amarelo",
        ),
        FallbackQuestionSpec(
            id = 1301,
            context = DEFAULT_CONTEXT,
            pergunta = "🏃 ESPORTES\nQual item e indispensavel para praticar esportes?",
            opcao1 = "Garrafinha de agua",
            opcao2 = "Look esportivo completo",
        ),
        FallbackQuestionSpec(
            id = 1302,
            context = DEFAULT_CONTEXT,
            pergunta = "🏃 ESPORTES\nQual voce levaria para uma caminhada?",
            opcao1 = "Tenis confortavel",
            opcao2 = "Bone",
        ),
        FallbackQuestionSpec(
            id = 1303,
            context = DEFAULT_CONTEXT,
            pergunta = "🏃 ESPORTES\nQual linha da Renner mais combina com voce?",
            opcao1 = "Casual",
            opcao2 = "Esportiva",
        ),
        FallbackQuestionSpec(
            id = 1401,
            context = DEFAULT_CONTEXT,
            pergunta = "🌸 PERFUMARIA\nQual tipo de fragrancia voce prefere?",
            opcao1 = "Floral",
            opcao2 = "Amadeirada",
        ),
        FallbackQuestionSpec(
            id = 1402,
            context = DEFAULT_CONTEXT,
            pergunta = "🌸 PERFUMARIA\nQual perfume combina mais com o dia a dia?",
            opcao1 = "Suave e refrescante",
            opcao2 = "Intenso e marcante",
        ),
        FallbackQuestionSpec(
            id = 1403,
            context = DEFAULT_CONTEXT,
            pergunta = "🌸 PERFUMARIA\nO que voce mais gosta de comprar na Renner?",
            opcao1 = "Roupas e acessorios",
            opcao2 = "Perfumaria e beleza",
        ),
        FallbackQuestionSpec(
            id = 1404,
            context = DEFAULT_CONTEXT,
            pergunta = "🌸 PERFUMARIA\nQual desses itens voce costuma usar todos os dias?",
            opcao1 = "Perfume",
            opcao2 = "Hidratante",
        ),
        FallbackQuestionSpec(
            id = 1405,
            context = DEFAULT_CONTEXT,
            pergunta = "🌸 PERFUMARIA\nQual produto nao pode faltar na sua rotina?",
            opcao1 = "Desodorante",
            opcao2 = "Perfume",
        ),
        FallbackQuestionSpec(
            id = 1501,
            context = DEFAULT_CONTEXT,
            pergunta = "💄 MAQUIAGEM\nQual produto de maquiagem voce considera indispensavel?",
            opcao1 = "Batom",
            opcao2 = "Mascara para cilios",
        ),
        FallbackQuestionSpec(
            id = 1502,
            context = DEFAULT_CONTEXT,
            pergunta = "💄 MAQUIAGEM\nQual acabamento voce prefere?",
            opcao1 = "Natural",
            opcao2 = "Mais elaborado",
        ),
        FallbackQuestionSpec(
            id = 1503,
            context = DEFAULT_CONTEXT,
            pergunta = "💄 MAQUIAGEM\nQual cor voce usaria em uma ocasiao especial?",
            opcao1 = "Tons neutros",
            opcao2 = "Tons vibrantes",
        ),
        FallbackQuestionSpec(
            id = 1601,
            context = DEFAULT_CONTEXT,
            pergunta = "🧴 CUIDADOS COM A PELE\nQual item faz parte da sua rotina?",
            opcao1 = "Protetor solar",
            opcao2 = "Hidratante facial",
        ),
        FallbackQuestionSpec(
            id = 1602,
            context = DEFAULT_CONTEXT,
            pergunta = "🧴 CUIDADOS COM A PELE\nVoce prefere:",
            opcao1 = "Skincare rapida",
            opcao2 = "Rotina completa",
        ),
        FallbackQuestionSpec(
            id = 1701,
            context = DEFAULT_CONTEXT,
            pergunta = "👜 ACESSORIOS\nQual acessorio voce mais gosta?",
            opcao1 = "Relogio",
            opcao2 = "Oculos de sol",
        ),
        FallbackQuestionSpec(
            id = 1702,
            context = DEFAULT_CONTEXT,
            pergunta = "👜 ACESSORIOS\nQual voce usaria para completar o visual?",
            opcao1 = "Bolsa",
            opcao2 = "Mochila",
        ),
        FallbackQuestionSpec(
            id = 1801,
            context = DEFAULT_CONTEXT,
            pergunta = "👖 JEANS\nQual modelo voce prefere?",
            opcao1 = "Skinny",
            opcao2 = "Mom Jeans",
        ),
        FallbackQuestionSpec(
            id = 1802,
            context = DEFAULT_CONTEXT,
            pergunta = "👖 JEANS\nQual lavagem voce mais gosta?",
            opcao1 = "Clara",
            opcao2 = "Escura",
        ),
        FallbackQuestionSpec(
            id = 1901,
            context = DEFAULT_CONTEXT,
            pergunta = "👟 SAPATOS\nQual destes combina mais com voce?",
            opcao1 = "Tenis",
            opcao2 = "Bota",
        ),
        FallbackQuestionSpec(
            id = 1902,
            context = DEFAULT_CONTEXT,
            pergunta = "👟 SAPATOS\nQual voce escolheria para um passeio?",
            opcao1 = "Tenis casual",
            opcao2 = "Sandalia confortavel",
        ),
        FallbackQuestionSpec(
            id = 2001,
            context = DEFAULT_CONTEXT,
            pergunta = "🏠 CASA E CONFORTO\nQual peca e perfeita para relaxar?",
            opcao1 = "Pijama confortavel",
            opcao2 = "Moletom",
        ),
        FallbackQuestionSpec(
            id = 2101,
            context = DEFAULT_CONTEXT,
            pergunta = "🎁 PRESENTES\nSe fosse presentear alguem, voce escolheria:",
            opcao1 = "Perfume",
            opcao2 = "Roupas",
        ),
        FallbackQuestionSpec(
            id = 2201,
            context = DEFAULT_CONTEXT,
            pergunta = "👔 MASCULINO\nQual peca masculina voce mais gosta?",
            opcao1 = "Polo",
            opcao2 = "Camiseta basica",
        ),
        FallbackQuestionSpec(
            id = 2202,
            context = DEFAULT_CONTEXT,
            pergunta = "👔 MASCULINO\nQual perfume masculino combina mais com voce?",
            opcao1 = "Amadeirado",
            opcao2 = "Cittrico",
        ),
        FallbackQuestionSpec(
            id = 2203,
            context = DEFAULT_CONTEXT,
            pergunta = "👔 MASCULINO\nQual peca voce usaria em um jantar especial?",
            opcao1 = "Camisa social",
            opcao2 = "Jaqueta casual",
        ),
        FallbackQuestionSpec(
            id = 2301,
            context = DEFAULT_CONTEXT,
            pergunta = "👗 FEMININO\nQual peca voce mais gosta de usar?",
            opcao1 = "Vestido",
            opcao2 = "Blazer",
        ),
        FallbackQuestionSpec(
            id = 2302,
            context = DEFAULT_CONTEXT,
            pergunta = "👗 FEMININO\nQual acessorio nao pode faltar?",
            opcao1 = "Bolsa",
            opcao2 = "Oculos de sol",
        ),
        FallbackQuestionSpec(
            id = 2401,
            context = DEFAULT_CONTEXT,
            pergunta = "🏷️ RENNER\nQual setor voce visita primeiro na Renner?",
            opcao1 = "Moda",
            opcao2 = "Perfumaria e Beleza",
        ),
        FallbackQuestionSpec(
            id = 2402,
            context = DEFAULT_CONTEXT,
            pergunta = "🏷️ RENNER\nO que mais chama sua atencao em uma loja?",
            opcao1 = "Novidades da colecao",
            opcao2 = "Promocoes",
        ),
        FallbackQuestionSpec(
            id = 2403,
            context = DEFAULT_CONTEXT,
            pergunta = "🏷️ RENNER\nQual experiencia voce mais gosta?",
            opcao1 = "Experimentar roupas",
            opcao2 = "Conhecer novos perfumes",
        ),
        FallbackQuestionSpec(
            id = 2404,
            context = DEFAULT_CONTEXT,
            pergunta = "🏷️ RENNER\nQual dessas categorias representa mais o seu estilo?",
            opcao1 = "Casual e confortavel",
            opcao2 = "Moderno e sofisticado",
        ),
        FallbackQuestionSpec(
            id = 2405,
            context = DEFAULT_CONTEXT,
            pergunta = "🏷️ RENNER\nQual dessas pecas basicas nao podem faltar no seu guarda-roupa?",
            opcao1 = "Camiseta branca",
            opcao2 = "Calca jeans",
        ),
        FallbackQuestionSpec(
            id = 2406,
            context = DEFAULT_CONTEXT,
            pergunta = "🏷️ RENNER\nQual sua peca da Renner preferida para o inverno?",
            opcao1 = "Trench Coat",
            opcao2 = "Jaqueta de couro",
        ),
        FallbackQuestionSpec(
            id = 2407,
            context = DEFAULT_CONTEXT,
            pergunta = "🏷️ RENNER\nQual seu item indispensavel para praticar esportes?",
            opcao1 = "Garrafinha de agua",
            opcao2 = "Look completo da Renner",
        ),
        FallbackQuestionSpec(
            id = 2408,
            context = DEFAULT_CONTEXT,
            pergunta = "🏷️ RENNER\nQual item voce mais gosta de comprar na Renner?",
            opcao1 = "Roupas e acessorios",
            opcao2 = "Perfumaria e Beleza",
        ),
    )

    fun buildProfile(
        rawAudience: String?,
        rawEmotion: String?,
        rawContext: String?,
    ): EngageSessionProfile {
        return EngageSessionProfile(
            audience = normalizeAudience(rawAudience),
            emotion = normalizeEmotion(rawEmotion),
            context = rawContext?.trim().orEmpty().ifBlank { DEFAULT_CONTEXT },
            enabledCapabilities = defaultCapabilities,
        )
    }

    fun buildDetectedInfo(profile: EngageSessionProfile): EngageDetectedInfoUi {
        val ageLabel = formatAudienceLabel(profile.audience) ?: "Em analise"
        val emotionLabel = formatEmotionLabel(profile.emotion) ?: "Em analise"
        val text =
            buildString {
                append("Curiosidade tecnologica\n")
                append("✨ Faixa etaria estimada:\n")
                append(ageLabel)
                append("\n\n")
                append("😊 Humor detectado:\n")
                append(emotionLabel)
            }
        return EngageDetectedInfoUi(text = text)
    }

    fun pickWelcomeMessage(profile: EngageSessionProfile): String {
        val options =
            if (profile.emotion == "feliz") cheerfulWelcomeMessages + welcomeMessages
            else welcomeMessages
        return options.random()
    }

    fun pickDeclineMessage(): String = declineMessages.random()

    fun pickTimeoutMessage(): String = timeoutMessages.random()

    fun pickThanksMessage(): String = thanksMessages.random()

    fun pickQuestionTitle(profile: EngageSessionProfile): String {
        return when (profile.audience) {
            "crianca" -> "Vamos brincar?"
            "adolescente" -> "Desafio de estilo"
            "jovem_adulto" -> "Moda em um toque"
            "adulto" -> "Escolha seu palpite"
            else -> questionTitles.random()
        }
    }

    fun selectQuestion(
        remoteQuestions: List<EngageQuestion>,
        profile: EngageSessionProfile,
    ): EngageQuestion {
        val remote = selectRemoteQuestion(remoteQuestions, profile)
        if (remote != null) return remote
        return selectFallbackQuestion(profile)
    }

    fun selectQuestions(
        remoteQuestions: List<EngageQuestion>,
        profile: EngageSessionProfile,
        count: Int,
    ): List<EngageQuestion> {
        val target = count.coerceIn(1, 10)
        val selected = ArrayList<EngageQuestion>(target)
        val usedIds = HashSet<Long>()

        val remoteSorted =
            remoteQuestions
                .distinctBy { it.id }
                .sortedByDescending { scoreRemote(it, profile) }

        for (q in remoteSorted) {
            if (selected.size >= target) break
            if (usedIds.add(q.id)) selected += q
        }

        if (selected.size >= target) return selected

        val fallbackSorted =
            fallbackQuestions
                .sortedByDescending { scoreFallback(it, profile) }
                .map { it.toQuestion() }
                .distinctBy { it.id }

        for (q in fallbackSorted) {
            if (selected.size >= target) break
            if (usedIds.add(q.id)) selected += q
        }

        return selected
    }

    fun formatAudienceLabel(audience: String?): String? {
        return when (audience) {
            "crianca" -> "Crianca"
            "adolescente" -> "Adolescente"
            "jovem_adulto" -> "Jovem Adulto"
            "adulto" -> "Adulto"
            "senior" -> "Senior"
            else -> null
        }
    }

    fun formatAgeYears(audience: String?): String? {
        return when (audience) {
            "crianca" -> "6-12 anos"
            "adolescente" -> "13-17 anos"
            "jovem_adulto" -> "25-35 anos"
            "adulto" -> "35-50 anos"
            "senior" -> "60+ anos"
            else -> null
        }
    }

    fun formatEmotionLabel(emotion: String?): String? {
        return when (emotion) {
            "feliz" -> "Feliz"
            "neutro" -> "Neutro"
            "curioso" -> "Curioso"
            "surpreso" -> "Surpreso"
            else -> null
        }
    }

    fun pickFeedbackMessage(): String = feedbackMessages.random()

    private fun selectRemoteQuestion(
        remoteQuestions: List<EngageQuestion>,
        profile: EngageSessionProfile,
    ): EngageQuestion? {
        if (remoteQuestions.isEmpty()) return null
        val bestScore = remoteQuestions.maxOfOrNull { scoreRemote(it, profile) } ?: return null
        return remoteQuestions.filter { scoreRemote(it, profile) == bestScore }.randomOrNull()
    }

    private fun selectFallbackQuestion(profile: EngageSessionProfile): EngageQuestion {
        val bestScore = fallbackQuestions.maxOfOrNull { scoreFallback(it, profile) } ?: 0
        return fallbackQuestions
            .filter { scoreFallback(it, profile) == bestScore }
            .map { it.toQuestion() }
            .random()
    }

    private fun scoreRemote(question: EngageQuestion, profile: EngageSessionProfile): Int {
        val audience = question.publico?.trim()?.lowercase()
        val category = question.categoria?.trim()?.lowercase()
        var score = 0

        if (audience == null) score += 1
        else if (audience == profile.audience) score += 4

        if (category == null) score += 1
        else if (category == profile.context.lowercase()) score += 3

        return score
    }

    private fun scoreFallback(spec: FallbackQuestionSpec, profile: EngageSessionProfile): Int {
        var score = 0

        if (spec.context == profile.context.lowercase()) score += 3

        if (spec.audiences.isEmpty()) score += 1
        else if (profile.audience in spec.audiences) score += 4

        if (spec.emotions.isEmpty()) score += 1
        else if (profile.emotion in spec.emotions) score += 2

        return score
    }

    private fun normalizeAudience(raw: String?): String? {
        val s = raw?.trim().orEmpty().lowercase()
        if (s.isBlank()) return null

        val range =
            Regex("""(\d{1,2})\s*[-–]\s*(\d{1,2})""")
                .find(s)
                ?.let {
                    val a = it.groupValues[1].toIntOrNull()
                    val b = it.groupValues[2].toIntOrNull()
                    if (a != null && b != null) (a + b) / 2 else null
                }

        val plus =
            Regex("""(\d{1,2})\s*\+""")
                .find(s)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

        val approxAge = range ?: plus
        if (approxAge != null) {
            return when {
                approxAge < 13 -> "crianca"
                approxAge < 18 -> "adolescente"
                approxAge < 30 -> "jovem_adulto"
                approxAge < 60 -> "adulto"
                else -> "senior"
            }
        }

        return when (s) {
            "crianca", "criança" -> "crianca"
            "adolescente" -> "adolescente"
            "jovem adulto", "jovem_adulto", "jovem-adulto" -> "jovem_adulto"
            "adulto" -> "adulto"
            "senior", "sênior" -> "senior"
            else -> null
        }
    }

    private fun normalizeEmotion(raw: String?): String? {
        return when (raw?.trim()?.lowercase()) {
            "feliz", "alegre", "sorrindo" -> "feliz"
            "neutro", "serio", "sério" -> "neutro"
            "curioso", "curiosa" -> "curioso"
            "surpreso", "surpresa" -> "surpreso"
            else -> null
        }
    }
}
