package com.mupa.player.enterprise.security

import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Senha de acesso temporária (TOTP, RFC 6238) truncada para 4 dígitos — fácil de digitar num
 * terminal sem teclado físico. Mesmo princípio do Google Authenticator, com passo de 60s.
 *
 * O segredo (`BuildConfig.ARGOS_OTP_SECRET`) precisa ser o mesmo configurado no painel do
 * Argos, que calcula a mesma fórmula para mostrar a senha do minuto ao técnico — ver
 * `ARGOS_OPEN_SETTINGS_CONTRACT.md`.
 */
object TotpPasswordGenerator {

    private const val STEP_SECONDS = 60L
    private const val DIGITS = 4
    private const val MODULUS = 10_000 // 10^DIGITS

    /** Senha válida para o passo de tempo atual (muda a cada [STEP_SECONDS]). */
    fun currentCode(secret: String, nowEpochSeconds: Long = System.currentTimeMillis() / 1000): String =
        codeForStep(secret, nowEpochSeconds / STEP_SECONDS)

    /**
     * Valida [entered] contra o passo atual e o anterior (tolerância de relógio entre os dois
     * sistemas — sem isso, poucos segundos de diferença já invalidariam a senha).
     */
    fun isValid(entered: String, secret: String, nowEpochSeconds: Long = System.currentTimeMillis() / 1000): Boolean {
        val normalized = entered.trim()
        if (normalized.isBlank() || secret.isBlank()) return false
        val currentStep = nowEpochSeconds / STEP_SECONDS
        return normalized == codeForStep(secret, currentStep) || normalized == codeForStep(secret, currentStep - 1)
    }

    private fun codeForStep(secret: String, step: Long): String {
        val key = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA1")
        val msg = ByteBuffer.allocate(8).putLong(step).array()
        val hmac = Mac.getInstance("HmacSHA1").apply { init(key) }.doFinal(msg)

        // Truncamento dinâmico (RFC 4226).
        val offset = (hmac[hmac.size - 1].toInt() and 0x0F)
        val binCode =
            ((hmac[offset].toInt() and 0x7F) shl 24) or
                ((hmac[offset + 1].toInt() and 0xFF) shl 16) or
                ((hmac[offset + 2].toInt() and 0xFF) shl 8) or
                (hmac[offset + 3].toInt() and 0xFF)

        val code = binCode % MODULUS
        return code.toString().padStart(DIGITS, '0')
    }
}
