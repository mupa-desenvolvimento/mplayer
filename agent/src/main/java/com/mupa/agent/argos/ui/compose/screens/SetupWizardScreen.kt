package com.mupa.agent.argos.ui.compose.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.TextButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mupa.agent.argos.R
import com.mupa.agent.argos.managers.SettingsManager
import com.mupa.agent.argos.mdm.DeviceOwnerPolicyManager
import com.mupa.agent.argos.permissions.PermissionId
import com.mupa.agent.argos.permissions.PermissionManager
import com.mupa.agent.argos.permissions.PermissionState
import com.mupa.agent.argos.permissions.PermissionStatus
import com.mupa.agent.argos.ui.compose.ArgosTokens
import com.mupa.agent.argos.ui.compose.GlassCard
import com.mupa.agent.argos.ui.compose.LocalArgosDimens
import com.mupa.agent.argos.ui.ArgosLauncherActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class SetupWizardPhase {
    Permissions,
    WiFiSetup,
    BindDevice,
}

private fun isOnline(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return false
    val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return false
    return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

@Composable
fun SetupWizardScreen(
    onBack: () -> Unit,
    onStateChanged: () -> Unit,
    onComplete: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val manager = remember { PermissionManager(context) }
    val settings = remember { SettingsManager(context.applicationContext) }

    var states by remember { mutableStateOf<List<PermissionState>>(emptyList()) }
    var showDeviceOwnerHelp by remember { mutableStateOf(false) }
    var stepIndex by remember { mutableStateOf(0) }
    // Os 8 passos de permissões são pré-configurados na ROM — o wizard começa
    // direto na seleção de grupo, passando pelo Wi-Fi apenas se estiver offline.
    var phase by remember {
        mutableStateOf(if (isOnline(context)) SetupWizardPhase.BindDevice else SetupWizardPhase.WiFiSetup)
    }

    // Quando o usuário abre o Wi-Fi manualmente (via "Voltar" na seleção de
    // grupo) não há retorno automático — só quando ele caiu ali por estar offline.
    var wifiOpenedManually by remember { mutableStateOf(false) }

    // Enquanto estiver na fase de Wi-Fi, monitora a conectividade: assim que a
    // conexão for confirmada, retorna automaticamente para a seleção de grupo.
    LaunchedEffect(phase, wifiOpenedManually) {
        when (phase) {
            SetupWizardPhase.WiFiSetup -> {
                if (wifiOpenedManually) return@LaunchedEffect
                while (true) {
                    kotlinx.coroutines.delay(2_000)
                    if (isOnline(context)) {
                        phase = SetupWizardPhase.BindDevice
                        break
                    }
                }
            }
            SetupWizardPhase.BindDevice -> {
                wifiOpenedManually = false
                // Se a rede cair durante a seleção de grupo, volta para o Wi-Fi.
                while (true) {
                    kotlinx.coroutines.delay(3_000)
                    if (!isOnline(context)) {
                        phase = SetupWizardPhase.WiFiSetup
                        break
                    }
                }
            }
            else -> Unit
        }
    }

    fun refresh() {
        scope.launch {
            states = withContext(Dispatchers.Default) { manager.checkAllPermissions() }
            onStateChanged()
        }
    }

    LaunchedEffect(Unit) {
        refresh()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val ordered = remember {
        listOf(
            PermissionId.DeviceOwner,
            PermissionId.DeviceAdmin,
            PermissionId.BatteryOptimizations,
            PermissionId.Overlay,
            PermissionId.UsageStats,
            PermissionId.Accessibility,
            PermissionId.Notifications,
            PermissionId.UnknownAppSources,
        )
    }
    val byId = states.associateBy { it.id }

    LaunchedEffect(states) {
        if (ordered.isEmpty()) return@LaunchedEffect
        stepIndex = stepIndex.coerceIn(0, ordered.lastIndex)
    }

    // If permissions are all done AND the device is already enrolled in a group,
    // go directly to the launcher — skip WiFi setup and group linking.
    val criticalReadyForAutoSkip = states.filter { it.critical }.all { it.status == PermissionStatus.Completed } && states.isNotEmpty()
    LaunchedEffect(criticalReadyForAutoSkip) {
        if (!criticalReadyForAutoSkip) return@LaunchedEffect
        val reallyBound = withContext(Dispatchers.IO) {
            (settings.isDeviceBoundCached() && settings.getBoundCompanyIdCached() != "local-bypass") ||
                settings.getGroupSetupSkippedCached()
        }
        if (reallyBound) {
            val dpm = DeviceOwnerPolicyManager(context)
            if (dpm.isDeviceOwner(context.packageName)) {
                dpm.setLauncherAsHome(context.packageName, enabled = true)
            }
            runCatching {
                context.startActivity(
                    Intent(context, ArgosLauncherActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
                )
            }
            onBack()
        }
    }

    val completed = states.count { it.status == PermissionStatus.Completed }
    val total = states.size.coerceAtLeast(1)
    val criticalReady = states.filter { it.critical }.all { it.status == PermissionStatus.Completed }

    val dimens = LocalArgosDimens.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(dimens.padding),
    ) {
        if (phase == SetupWizardPhase.Permissions) {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                containerAlpha = 0.40f,
                borderAlpha = 0.12f,
                elevation = 6.dp,
            ) {
                Column {
                    Image(
                        painter = painterResource(id = R.drawable.logo_argos_hor),
                        contentDescription = "ARGOS",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("Configuração do dispositivo", style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text("Permissões e provisionamento", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            val currentId = ordered.getOrNull(stepIndex)
            val current = currentId?.let { byId[it] }
            val currentStatus = current?.status ?: PermissionStatus.Checking
            val currentCritical = current?.critical == true
            val currentReady = currentStatus == PermissionStatus.Completed || currentStatus == PermissionStatus.NotSupported
            val wizardProgress =
                if (ordered.isNotEmpty()) ((stepIndex + 1).toFloat() / ordered.size.toFloat()).coerceIn(0f, 1f) else 0f

            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                val scrollState = rememberScrollState()
                val screenWidthDp = LocalConfiguration.current.screenWidthDp
                val compact = screenWidthDp < 420

                Column(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(scrollState),
                    ) {
                        Text("Passo ${stepIndex + 1} de ${ordered.size}", style = MaterialTheme.typography.headlineSmall)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("$completed de $total concluídas", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(10.dp))
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            progress = { wizardProgress },
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        if (!criticalReady) {
                            Text(
                                "MDM bloqueado: faltam permissões críticas",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                        }

                        if (current != null) {
                            PermissionStepCard(
                                state = current,
                                onOpen = {
                                    val intent = current.intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    if (current.id == PermissionId.DeviceOwner) {
                                        showDeviceOwnerHelp = true
                                    } else {
                                        intent?.let { context.startActivity(it) }
                                    }
                                },
                            )
                        } else {
                            Text("Carregando…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    val atFirst = stepIndex <= 0
                    val atLast = stepIndex >= ordered.lastIndex
                    val canNext = when {
                        current == null -> false
                        currentReady -> true
                        currentCritical -> false
                        else -> true
                    }
                    val nextLabel = if (atLast && currentReady) "Concluir" else "Próximo"
                    val openEnabled = current != null && (current.status == PermissionStatus.Pending || current.status == PermissionStatus.Error)

                    val onNext: () -> Unit = onNext@{
                        if (current == null) return@onNext
                        if (atLast) {
                            val dpm = DeviceOwnerPolicyManager(context)
                            if (dpm.isDeviceOwner(context.packageName)) {
                                dpm.setLauncherAsHome(context.packageName, enabled = true)
                            }
                            val reallyBound = (settings.isDeviceBoundCached() && settings.getBoundCompanyIdCached() != "local-bypass") ||
                                settings.getGroupSetupSkippedCached()
                            if (reallyBound) {
                                if (onComplete != null) {
                                    onComplete()
                                } else {
                                    runCatching {
                                        context.startActivity(
                                            Intent(context, ArgosLauncherActivity::class.java)
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
                                        )
                                    }
                                    onBack()
                                }
                                return@onNext
                            }
                            phase = SetupWizardPhase.WiFiSetup
                            return@onNext
                        }
                        stepIndex = (stepIndex + 1).coerceAtMost(ordered.lastIndex)
                    }

                    if (compact) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    if (atFirst) onBack() else stepIndex = (stepIndex - 1).coerceAtLeast(0)
                                },
                            ) { Text("Voltar") }

                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                enabled = openEnabled,
                                onClick = {
                                    if (current == null) return@Button
                                    when (current.id) {
                                        PermissionId.DeviceOwner -> showDeviceOwnerHelp = true
                                        else -> current.intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { context.startActivity(it) }
                                    }
                                },
                            ) { Text(current?.actionLabel ?: "Abrir") }

                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                enabled = canNext,
                                onClick = onNext,
                            ) { Text(nextLabel) }
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    if (atFirst) onBack() else stepIndex = (stepIndex - 1).coerceAtLeast(0)
                                },
                            ) { Text("Voltar") }

                            Button(
                                modifier = Modifier.weight(1f),
                                enabled = openEnabled,
                                onClick = {
                                    if (current == null) return@Button
                                    when (current.id) {
                                        PermissionId.DeviceOwner -> showDeviceOwnerHelp = true
                                        else -> current.intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { context.startActivity(it) }
                                    }
                                },
                            ) { Text(current?.actionLabel ?: "Abrir") }

                            Button(
                                modifier = Modifier.weight(1f),
                                enabled = canNext,
                                onClick = onNext,
                            ) { Text(nextLabel) }
                        }
                    }
                }
            }
        } else if (phase == SetupWizardPhase.WiFiSetup) {
            WiFiSetupWizard(
                onBack = onBack,
                onDone = { phase = SetupWizardPhase.BindDevice },
            )
        } else {
            GroupLinkWizard(
                allowSkip = true,
                // A navegação para o launcher é feita pelo onDone→onComplete (NavController).
                // startActivity apontaria para a própria Activity e prenderia a tela.
                launchLauncherOnSuccess = false,
                onBack = {
                    wifiOpenedManually = true
                    phase = SetupWizardPhase.WiFiSetup
                },
                onDone = {
                    if (onComplete != null) {
                        onComplete()
                    } else {
                        runCatching {
                            context.startActivity(
                                // CLEAR_TASK força a ArgosLauncherActivity a reiniciar do zero e
                                // re-avaliar o roteamento com o estado já vinculado/"sem grupo" —
                                // sem isso ela era reusada (onNewIntent) presa na rota do grupo,
                                // reabrindo "Cadastrar Dispositivo" em loop após o vínculo.
                                Intent(context, ArgosLauncherActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                            )
                        }
                        onBack()
                    }
                },
            )
        }
    }

    if (showDeviceOwnerHelp) {
        DeviceOwnerHelpDialog(
            onDismiss = {
                showDeviceOwnerHelp = false
                refresh()
            },
        )
    }
}

@Composable
private fun PermissionStepCard(
    state: PermissionState,
    onOpen: () -> Unit,
) {
    val dot = when (state.status) {
        PermissionStatus.Completed -> ArgosTokens.Success
        PermissionStatus.Pending -> ArgosTokens.Warning
        PermissionStatus.Checking -> ArgosTokens.PrimaryBlue
        PermissionStatus.Error -> ArgosTokens.Danger
        PermissionStatus.NotSupported -> ArgosTokens.Offline
    }
    val status = when (state.status) {
        PermissionStatus.Completed -> "Concluído"
        PermissionStatus.Pending -> "Pendente"
        PermissionStatus.Checking -> "Em análise"
        PermissionStatus.Error -> "Erro"
        PermissionStatus.NotSupported -> "Não suportado pela ROM"
    }
    val critical = if (state.critical) " (crítico)" else ""

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Canvas(modifier = Modifier.padding(top = 6.dp)) {
                drawCircle(color = dot, radius = 10f)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(state.label + critical, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(2.dp))
                Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val info = state.info?.trim().orEmpty()
                if (info.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(info, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val actionable = state.status == PermissionStatus.Pending || state.status == PermissionStatus.Error
            if (actionable) OutlinedButton(onClick = onOpen) { Text(state.actionLabel ?: "Abrir") }
        }
    }
}

@Composable
private fun DeviceOwnerHelpDialog(
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val cmd = "adb shell dpm set-device-owner com.mupa.agent.argos/.mdm.ArgosDeviceAdminReceiver"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Device Owner (crítico)") },
        text = {
            Text(
                "Para ativar o Device Owner, execute no computador com o device conectado:\n\n$cmd\n\nDepois volte e toque em \"Conceder (guiado)\".",
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("argos_device_owner_adb", cmd))
                    onDismiss()
                },
            ) { Text("Copiar") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Fechar") }
        },
    )
}

@Composable
private fun WiFiSetupWizard(
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val wifiConfig = remember { com.mupa.agent.argos.wifi.WifiConfig(context) }

    // ACCESS_FINE_LOCATION é runtime permission obrigatória para scan no Android 10+
    var locationGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        locationGranted = granted
    }

    // SSID selection state
    var scanResults by remember { mutableStateOf<List<String>>(emptyList()) }
    var isScanning by remember { mutableStateOf(false) }
    var selectedSsid by remember { mutableStateOf(wifiConfig.getSsid()) }
    var manualSsid by remember { mutableStateOf("") }
    var showManualEntry by remember { mutableStateOf(false) }

    // Password state (shown only after SSID selected)
    var password by remember { mutableStateOf(wifiConfig.getPassword()) }
    var showPassword by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var isConnecting by remember { mutableStateOf(false) }

    // Effective SSID: manual entry overrides scan selection
    val effectiveSsid = if (showManualEntry) manualSsid.trim() else selectedSsid

    fun scan() {
        if (isScanning || !locationGranted) return
        isScanning = true
        scope.launch {
            try {
                val results = withContext(Dispatchers.IO) {
                    kotlinx.coroutines.withTimeoutOrNull(8_000) { wifiConfig.getScanResults() } ?: emptyList()
                }
                scanResults = results
            } finally {
                isScanning = false
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!locationGranted) {
            locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            scan()
        }
    }

    // Re-scan automaticamente quando a permissão for concedida
    LaunchedEffect(locationGranted) {
        if (locationGranted && scanResults.isEmpty()) scan()
    }

    val dimens = LocalArgosDimens.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(dimens.padding),
    ) {
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            containerAlpha = 0.40f,
            borderAlpha = 0.12f,
            elevation = 6.dp,
        ) {
            Column {
                Image(
                    painter = painterResource(id = R.drawable.logo_argos_hor),
                    contentDescription = "ARGOS",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text("Configuração de WiFi", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(2.dp))
                Text("Selecione a rede (opcional)", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // SSID section header with toggle between scan list and manual entry
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (showManualEntry) "SSID manual" else "Redes disponíveis",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!showManualEntry) {
                            if (isScanning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.padding(4.dp).then(Modifier.then(Modifier)),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                TextButton(onClick = { scan() }, enabled = !isConnecting) {
                                    Text("Atualizar")
                                }
                            }
                        }
                        TextButton(
                            onClick = {
                                showManualEntry = !showManualEntry
                                message = ""
                            },
                            enabled = !isConnecting,
                        ) {
                            Text(if (showManualEntry) "Ver lista" else "Digitar")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))

                if (showManualEntry) {
                    // Manual SSID entry
                    OutlinedTextField(
                        value = manualSsid,
                        onValueChange = { manualSsid = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Nome da rede (SSID)") },
                        placeholder = { Text("Ex: MinhaRede") },
                        enabled = !isConnecting,
                        singleLine = true,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                } else {
                    if (!locationGranted) {
                        Text(
                            "Permissão de localização necessária para listar redes WiFi. " +
                                "Conceda a permissão ou use \"Digitar\" para inserir o SSID manualmente.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedButton(
                            onClick = { locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Conceder permissão de localização") }
                        Spacer(modifier = Modifier.height(4.dp))
                    } else if (scanResults.isEmpty() && !isScanning) {
                        Text(
                            "Nenhuma rede encontrada. Toque em \"Digitar\" para inserir o SSID manualmente.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(scanResults) { ssid ->
                            val isSelected = ssid == selectedSsid
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !isConnecting) {
                                        selectedSsid = ssid
                                        if (ssid != wifiConfig.getSsid()) password = ""
                                        else password = wifiConfig.getPassword()
                                        message = ""
                                    }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    ssid,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurface,
                                )
                                if (isSelected) {
                                    Text("✓", color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        }
                    }
                }

                // Password field — shown when an SSID is selected (scan) or typed (manual)
                if (effectiveSsid.isNotBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Rede: $effectiveSsid", style = MaterialTheme.typography.labelLarge)
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Senha (WPA2)") },
                        placeholder = { Text("Digite a senha") },
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            TextButton(onClick = { showPassword = !showPassword }) {
                                Text(if (showPassword) "Ocultar" else "Mostrar", style = MaterialTheme.typography.labelSmall)
                            }
                        },
                        enabled = !isConnecting,
                        singleLine = true,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                if (message.isNotEmpty()) {
                    Text(
                        message,
                        color = if ("Erro" in message) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                if (isConnecting) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // No Android 10+, conectar programaticamente é bloqueante/não-confiável.
                // Salvamos as credenciais e abrimos as configurações do sistema.
                val isModernAndroid = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = onBack,
                        enabled = !isConnecting,
                    ) { Text("Voltar") }

                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = onDone,
                    ) { Text(if (isModernAndroid && message.isNotBlank()) "Próximo" else "Pular") }

                    Button(
                        modifier = Modifier.weight(1f),
                        enabled = !isConnecting && effectiveSsid.isNotBlank(),
                        onClick = {
                            if (password.isEmpty()) {
                                message = "Digite a senha"
                                return@Button
                            }
                            val targetSsid = effectiveSsid
                            if (isModernAndroid) {
                                // Android 10+: salva credenciais e abre configurações Wi-Fi do sistema.
                                // O app não pode conectar diretamente — o usuário conecta no sistema
                                // e volta para clicar em "Próximo".
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        wifiConfig.setCredentials(targetSsid, password)
                                    }
                                    message = "Credenciais salvas. Conecte ao Wi-Fi e toque em \"Próximo\"."
                                }
                                runCatching {
                                    context.startActivity(
                                        Intent(Settings.ACTION_WIFI_SETTINGS)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                            } else {
                                // Android < 10: conexão programática via WifiConfiguration (legado).
                                isConnecting = true
                                message = "Conectando..."
                                scope.launch {
                                    try {
                                        val connected = withContext(Dispatchers.IO) {
                                            wifiConfig.setAndConnect(targetSsid, password)
                                            var ok = false
                                            var tries = 0
                                            while (tries < 10 && !ok) {
                                                kotlinx.coroutines.delay(1000)
                                                if (wifiConfig.getCurrentSsid() == targetSsid) ok = true
                                                tries++
                                            }
                                            ok
                                        }
                                        message = if (connected) "Conectado a $targetSsid!"
                                            else "Credenciais salvas (conectará quando a rede estiver disponível)."
                                        kotlinx.coroutines.delay(1200)
                                        onDone()
                                    } catch (e: Exception) {
                                        message = "Erro ao conectar: ${e.message}"
                                    } finally {
                                        isConnecting = false
                                    }
                                }
                            }
                        },
                    ) { Text(if (isModernAndroid) "Salvar" else "Conectar") }
                }
            }
        }
    }
}

