package com.mupa.player.enterprise.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mupa.player.enterprise.managers.DeviceCache
import com.mupa.player.enterprise.managers.DeviceCacheManager
import com.mupa.player.enterprise.services.CompanyInfo
import com.mupa.player.enterprise.services.CompanyLookupResult
import com.mupa.player.enterprise.services.CompanyLookupService
import com.mupa.player.enterprise.services.DeviceRegistrationPayload
import com.mupa.player.enterprise.services.DeviceRegistrationResult
import com.mupa.player.enterprise.services.DeviceRegistrationService
import com.mupa.player.enterprise.services.DeviceValidationResult
import com.mupa.player.enterprise.services.DeviceValidationService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DeviceRegistrationUiState(
    val deviceId: String,
    val companyCode: String,
    val company: CompanyInfo?,
    val nickname: String,
    val filial: String,
    val isLoading: Boolean,
    val status: String,
)

class DeviceRegistrationViewModel(app: Application) : AndroidViewModel(app) {
    private val companyLookupService = CompanyLookupService(app.applicationContext)
    private val registrationService = DeviceRegistrationService(app.applicationContext)
    private val validationService = DeviceValidationService(app.applicationContext)
    private val cacheManager = DeviceCacheManager(app.applicationContext)

    private val _uiState = MutableStateFlow(
        DeviceRegistrationUiState(
            deviceId = "",
            companyCode = "",
            company = null,
            nickname = "",
            filial = "",
            isLoading = false,
            status = "",
        ),
    )
    val uiState: StateFlow<DeviceRegistrationUiState> = _uiState.asStateFlow()

    private var lookupJob: Job? = null

    fun setDeviceId(id: String) {
        _uiState.value = _uiState.value.copy(deviceId = id)
    }

    fun setCompanyCode(value: String) {
        _uiState.value = _uiState.value.copy(companyCode = value, company = null)
        lookupJob?.cancel()
        val code = value.trim()
        if (code.length < 3) return
        lookupJob = viewModelScope.launch {
            delay(350)
            lookupCompany(code)
        }
    }

    fun setNickname(value: String) {
        _uiState.value = _uiState.value.copy(nickname = value)
    }

    fun setFilial(value: String) {
        _uiState.value = _uiState.value.copy(filial = value)
    }

    fun register() {
        viewModelScope.launch {
            val state = _uiState.value
            val deviceId = state.deviceId.trim()
            val companyCode = state.companyCode.trim()
            val nickname = state.nickname.trim()
            val filial = state.filial.trim()

            if (deviceId.isBlank()) {
                _uiState.value = state.copy(status = "Identificando dispositivo...")
                return@launch
            }
            if (companyCode.isBlank()) {
                _uiState.value = state.copy(status = "Informe o código da empresa.")
                return@launch
            }
            if (nickname.isBlank()) {
                _uiState.value = state.copy(status = "Informe o apelido do dispositivo.")
                return@launch
            }
            if (filial.isBlank()) {
                _uiState.value = state.copy(status = "Informe o número da filial.")
                return@launch
            }

            var company = _uiState.value.company
            if (company == null) {
                setLoading("Validando empresa...")
                when (val result = companyLookupService.lookupByCode(companyCode)) {
                    is CompanyLookupResult.Found -> {
                        company = result.company
                        _uiState.value = _uiState.value.copy(company = company, isLoading = false, status = "Empresa encontrada")
                    }
                    CompanyLookupResult.NotFound -> {
                        setLoadingDone("Empresa não encontrada")
                        return@launch
                    }
                    CompanyLookupResult.NotConfigured -> {
                        setLoadingDone("Token não configurado")
                        return@launch
                    }
                    is CompanyLookupResult.Error -> {
                        setLoadingDone("Erro: ${result.message}")
                        return@launch
                    }
                }
            }

            val resolvedCompany = company
            if (resolvedCompany == null) {
                setLoadingDone("Empresa não encontrada")
                return@launch
            }

            setLoading("Cadastrando dispositivo...")
            when (val reg = registrationService.register(
                DeviceRegistrationPayload(
                    apelidoInterno = nickname,
                    serial = deviceId,
                    empresaId = resolvedCompany.id,
                    empresaCode = companyCode,
                    numFilial = filial,
                ),
            )) {
                DeviceRegistrationResult.Success -> Unit
                DeviceRegistrationResult.NotConfigured -> {
                    setLoadingDone("Token não configurado.")
                    return@launch
                }
                is DeviceRegistrationResult.Error -> {
                    setLoadingDone("Erro no cadastro: ${reg.message}")
                    return@launch
                }
            }

            setLoading("Validando cadastro...")
            when (val validation = validationService.validateDevice(deviceId)) {
                is DeviceValidationResult.Found -> {
                    val cache = validation.cache.copy(
                        company = resolvedCompany.id,
                        companyCode = companyCode,
                        companyName = resolvedCompany.name,
                        tenant = resolvedCompany.tenantId,
                        deviceRegistered = true,
                    )
                    cacheManager.save(cache)
                    setLoadingDone("OK")
                }
                DeviceValidationResult.NotFound -> setLoadingDone("Ainda não localizado no banco.")
                DeviceValidationResult.NotConfigured -> setLoadingDone("Token não configurado.")
                is DeviceValidationResult.Error -> setLoadingDone("Erro na validação: ${validation.message}")
            }
        }
    }

    suspend fun getCachedRegisteredDevice(): DeviceCache? {
        val cached = cacheManager.load() ?: return null
        return cached.takeIf { it.deviceRegistered }
    }

    private suspend fun lookupCompany(code: String) {
        setLoading("Validando empresa...")
        when (val result = companyLookupService.lookupByCode(code)) {
            is CompanyLookupResult.Found -> {
                _uiState.value = _uiState.value.copy(
                    company = result.company,
                    isLoading = false,
                    status = "Empresa encontrada",
                )
            }
            CompanyLookupResult.NotFound -> {
                _uiState.value = _uiState.value.copy(
                    company = null,
                    isLoading = false,
                    status = "Empresa não encontrada",
                )
            }
            CompanyLookupResult.NotConfigured -> {
                _uiState.value = _uiState.value.copy(
                    company = null,
                    isLoading = false,
                    status = "Token não configurado",
                )
            }
            is CompanyLookupResult.Error -> {
                _uiState.value = _uiState.value.copy(
                    company = null,
                    isLoading = false,
                    status = "Erro: ${result.message}",
                )
            }
        }
    }

    private fun setLoading(message: String) {
        _uiState.value = _uiState.value.copy(isLoading = true, status = message)
    }

    private fun setLoadingDone(message: String) {
        _uiState.value = _uiState.value.copy(isLoading = false, status = message)
    }
}
