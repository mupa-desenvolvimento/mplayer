package com.mupa.agent.argos.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager.BadTokenException
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.view.WindowManager
import com.mupa.agent.argos.BuildConfig
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import androidx.core.app.NotificationCompat
import com.mupa.agent.argos.R
import com.mupa.agent.argos.accessibility.AccessibilityServiceEnabler
import com.mupa.agent.argos.accessibility.ArgosAccessibilityService
import com.mupa.agent.argos.ui.MaintenanceMenuOverlay
import com.mupa.agent.argos.ui.PersistCountdownOverlay
import com.mupa.agent.argos.ui.OfflineIndicatorOverlay
import com.mupa.agent.argos.apps.ApplicationManager
import com.mupa.agent.argos.commands.LocalCommandStore
import com.mupa.agent.argos.launcher.AutoStartManager
import com.mupa.agent.argos.commands.ArgosCommandScheduler
import com.mupa.agent.argos.commands.RealtimeCommandManager
import com.mupa.agent.argos.firebase.FirebaseRuntimeMode
import com.mupa.agent.argos.launcher.PersistenceManager
import com.mupa.agent.argos.launcher.WatchdogManager
import com.mupa.agent.argos.managers.SettingsManager
import com.mupa.agent.argos.managers.DeviceIdentityManager
import com.mupa.agent.argos.mdm.DeviceOwnerPolicyManager
import com.mupa.agent.argos.ota.OtaManager
import com.mupa.agent.argos.ota.MPlayerAutoUpdateManager
import com.mupa.agent.argos.provisioning.ApkInstallManager
import com.mupa.agent.argos.provisioning.ApkInstallRequest
import com.mupa.agent.argos.provisioning.ProvisioningManager
import com.mupa.agent.argos.telemetry.RequestMeter
import com.mupa.agent.argos.telemetry.TelemetryManager
import com.mupa.agent.argos.ui.ArgosLauncherActivity
import com.mupa.agent.argos.ui.compose.Routes
import com.mupa.agent.argos.player.PlayerPackagePolicy
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import com.mupa.agent.argos.mdm.LockTaskControl

class ArgosForegroundService : Service() {
    // `by lazy` e nao construcao direta: applicationContext so existe depois de attachBaseContext.
    // Campo de classe (o resto do arquivo constroi SettingsManager localmente) porque este e lido
    // no laco quente — computeCommandPollIntervalMs roda a cada iteracao do loop operacional.
    private val busModeSettings by lazy { SettingsManager(applicationContext) }

    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + serviceJob)
    private var loopJob: Job? = null
    private var persistMonitorJob: Job? = null
    private var presenceJob: Job? = null
    private var realtime: RealtimeCommandManager? = null
    private var firebaseInfoListener: ValueEventListener? = null
    private var firebaseConnected: Boolean = false
    private var offlineLastApiCheckMs: Long = 0L
    private var offlineApiOk: Boolean = true
    private var overlayTapView: View? = null
    private var overlayDialogView: View? = null
    private var registrationOverlayView: View? = null
    private var geofenceLockOverlayView: View? = null
    private val recoveryTapTimes = ArrayDeque<Long>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastEnsureGpsMs: Long = 0L
    private var lastEnsurePowerMs: Long = 0L
    private var lastAppliedKeepAwake: Boolean? = null
    private var lastEnsureRemoteSupportMs: Long = 0L
    private var remoteSupportInstalled: Boolean = false
    private var lastMplayerAutoUpdateCheckMs: Long = 0L
    private val telemetry by lazy { TelemetryManager(applicationContext) }
    private val apps by lazy { ApplicationManager(applicationContext) }
    private var lastHealthSnapshotMs: Long = 0L
    private var lastCriticalCapLogMs: Long = 0L
    private var lastReplaySampleMs: Long = 0L
    private var lastAutoHealMs: Long = 0L
    private var lastSyncRestartMs: Long = 0L
    private var lastBatteryAlertMs: Long = 0L
    private var lastForegroundPlayerMs: Long = 0L
    private var lastForegroundLauncherMs: Long = 0L
    private var lastGeofenceCheckMs: Long = 0L
    private var lastGeofenceEventMs: Long = 0L
    private var geofenceWasInside: Boolean? = null
    private var lastMainHeartbeatMs: Long = 0L
    private var mainHeartbeatStarted: Boolean = false
    private var lastExitCheckMs: Long = 0L
    private var lastAdaptivePollMs: Long = 0L
    private var lastHeartbeatCheckMs: Long = 0L
    private var lastOperationalSweepMs: Long = 0L
    private var lastProvisioningSweepMs: Long = 0L
    private var recoveryOverlayEnabled: Boolean = false
    private var bootAutostartDueAtMs: Long = 0L
    private var initialAutostartDone: Boolean = false
    private val pollingService by lazy { PollingService(applicationContext) }
    private val heartbeatService by lazy { HeartbeatService(applicationContext) }
    private val logFlushService by lazy { LogFlushService(applicationContext) }
    private val localCommandStore by lazy { LocalCommandStore(applicationContext) }
    private val otaManager by lazy { OtaManager(applicationContext) }
    private val presenceHelper by lazy { FirebasePresenceHelper(applicationContext) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        // Self-grant runtime permissions (READ_PHONE_STATE, location) BEFORE anything resolves
        // the device ID or promotes the FGS type — Build.getSerial() needs READ_PHONE_STATE even
        // as Device Owner, and the "location" FGS type needs location granted on Android 14.
        runCatching {
            com.mupa.agent.argos.mdm.DeviceOwnerPolicyManager(applicationContext)
                .selfGrantRuntimePermissions(packageName)
        }
        // Auto-connect to saved WiFi, if configured
        runCatching {
            com.mupa.agent.argos.wifi.WifiConfig(applicationContext).autoConnectIfConfigured()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_BOOT_COMPLETED_STARTUP) {
            bootAutostartDueAtMs = System.currentTimeMillis() + SettingsManager(applicationContext).getAutostartDelayMsCached()
            initialAutostartDone = false
        }
        startForegroundCompat()
        ensureMainHeartbeat()
        if (intent?.action == ACTION_RECOVERY_OVERLAY_STATE) {
            val enabled = intent.getBooleanExtra(EXTRA_RECOVERY_OVERLAY_ENABLED, false)
            updateRecoveryOverlayEnabled(enabled)
        }
        // Menu flutuante de manutenção: reusa EXATAMENTE o mesmo diálogo de senha do gesto de
        // recuperação (5 toques) — mesmo visual, mesmo SecurityAccessManager, mesmo overlay.
        // Não passa pela Activity antes de autenticar, então o app de trás (MPlayer) continua
        // rodando até a senha ser aceita (docs/CONTRATO_MENU_MANUTENCAO.md §4.3).
        if (intent?.action == ACTION_SHOW_MAINTENANCE_GATE) {
            val target = intent.getStringExtra(EXTRA_MAINTENANCE_TARGET)?.trim().orEmpty()
            mainHandler.post { showRecoveryPasswordOverlay(target.ifBlank { Routes.DeviceSettings }) }
            return START_STICKY
        }
        // Atalho do controle: executa DIRETO, sem gate de senha (ver runMaintenanceTarget).
        if (intent?.action == ACTION_RUN_MAINTENANCE_TARGET) {
            val target = intent.getStringExtra(EXTRA_MAINTENANCE_TARGET)?.trim().orEmpty()
            Log.i("MaintenanceGate", "remote_shortcut_run target=$target")
            mainHandler.post { runMaintenanceTarget(target.ifBlank { Routes.DeviceSettings }) }
            return START_STICKY
        }
        if (intent?.action == ACTION_FORCE_PRESENCE_SYNC) {
            realtime?.triggerImmediatePresencePush()
        }
        if (realtime == null && shouldUseLegacyRealtime()) {
            realtime = RealtimeCommandManager(applicationContext).also { it.start() }
        }
        ensureFirebaseConnectedListener()
        scope.launch {
            runCatching { ProvisioningManager(applicationContext).runNow() }
        }
        // isActive, not null: a job that already completed (loop body threw) stays non-null
        // forever, so the old `== null` check silently never restarted it — the service stayed
        // alive with no polling, no heartbeat and no presence writes until the process died.
        if (loopJob?.isActive != true) {
            loopJob = scope.launch {
                while (isActive) {
                    // Whole-iteration guard: every individual step below has its own runCatching,
                    // but any future unguarded call (or one added by mistake) must not be able to
                    // kill the loop permanently. delay() is outside so cancellation still works.
                    runCatching {
                    val now = System.currentTimeMillis()
                    // Same gate as ensurePersistTargetIfIdle() below — this is the *other*,
                    // independent autostart trigger (cold/boot launch of the kiosk app),
                    // which was covering the registration wizard within seconds of startup
                    // since it never checked apelido_interno at all.
                    if (!initialAutostartDone && bootAutostartDueAtMs > 0L && now >= bootAutostartDueAtMs) {
                        runCatching { WatchdogManager(applicationContext).runNow() }
                        initialAutostartDone = true
                    } else if (!initialAutostartDone && bootAutostartDueAtMs == 0L) {
                        runCatching { WatchdogManager(applicationContext).runNow() }
                        initialAutostartDone = true
                    }
                    runCatching { runAdaptivePollingAndHeartbeat(now) }
                    if (now - lastOperationalSweepMs >= OPERATIONAL_SWEEP_INTERVAL_MS) {
                        lastOperationalSweepMs = now
                        runCatching { recordIntelligenceSampleAndHeal() }
                        runCatching { ensureMaintenanceOrTemporaryUnlock() }
                        runCatching { updateOfflineSnapshot() }
                        runCatching { ensureFloatingRecoveryButton() }
                        // Registration overlay removed — GroupLinkWizard handles enrollment+naming now
                        dismissRegistrationOverlay()
                        runCatching { com.mupa.agent.argos.geofence.GeofenceEngine(applicationContext).check() }
                        runCatching { com.mupa.agent.argos.geofence.OperatingHoursEngine(applicationContext).check() }
                        runCatching { ensureGeofenceLockOverlay() }
                        runCatching { ensureBaselineRestrictions() }
                        runCatching { ensureGpsEnabled() }
                        runCatching { ensurePowerPolicy() }
                        runCatching { ensureArgosLauncherIfFinalized() }
                        runCatching { ensureWifiSetupIfOffline() }
                        runCatching { ensureMaintenanceMenu() }
                        runCatching { ensureOwnAccessibilityService() }
                        runCatching { ensureRemoteSupportAppsInstalled() }
                        runCatching { ensureMplayerAutoUpdated() }
                        runCatching { ensureArgosRemoteHealthy() }
                        runCatching {
                            val settings = SettingsManager(applicationContext)
                            val maintenance = settings.getMaintenanceModeEnabledCached()
                            val tempUnlockActive = settings.getTemporaryUnlockUntilMsCached() > System.currentTimeMillis()
                            val bootAutostartPending = !initialAutostartDone && bootAutostartDueAtMs > 0L
                            if (!maintenance && !tempUnlockActive && !bootAutostartPending &&
                                !settings.isMaintenanceSessionActiveCached()
                            ) {
                                val pkgs = PersistenceManager(applicationContext).getPersistencePackages()
                                WatchdogManager(applicationContext).ensureRunning(pkgs)
                            }
                        }
                    }
                    if (now - lastProvisioningSweepMs >= PROVISIONING_SWEEP_INTERVAL_MS) {
                        lastProvisioningSweepMs = now
                        runCatching { ProvisioningManager(applicationContext).runIfDue() }
                    }
                    }.onFailure { Log.w("ArgosForegroundSvc", "loop_iteration_failed err=${it.message}") }
                    delay(
                        runCatching { computeOperationalDelayMs(System.currentTimeMillis()) }
                            .getOrDefault(COMMAND_POLL_INTERVAL_NORMAL_MS),
                    )
                }
            }
        }
        if (persistMonitorJob?.isActive != true) {
            persistMonitorJob = scope.launch {
                while (isActive) {
                    runCatching { ensurePersistTargetIfIdle() }
                    // Cadência adaptativa: enquanto a contagem está na tela, alguém está mexendo
                    // no aparelho e o retorno precisa cair perto do prazo mostrado. Fora dessa
                    // janela volta ao intervalo folgado — o passo caro aqui é a consulta de app
                    // em foreground (UsageStats), que não vale rodar 5x mais em regime normal.
                    delay(
                        if (PersistCountdownOverlay.isVisible()) PERSIST_COUNTDOWN_INTERVAL_MS
                        else PERSIST_CHECK_INTERVAL_MS,
                    )
                }
            }
        }
        // Presence runs on its OWN coroutine, never inside the main loop. info.updated_at is the
        // single value the web console's online/offline badge reads, and it is a 2-request write
        // that costs milliseconds — but the main loop runs pollingService.tick() sequentially,
        // which chains HTTP calls with 8-15s timeouts across four candidate config routes and can
        // stall a whole iteration for minutes when the API is unreachable. Sharing that loop meant
        // a slow/unreachable backend made the device look offline in the panel while it was
        // perfectly healthy. Decoupled, presence keeps ticking regardless of backend latency.
        if (presenceJob?.isActive != true) {
            presenceJob = scope.launch {
                while (isActive) {
                    runCatching { presenceHelper.push() }
                        .onFailure { Log.w("ArgosForegroundSvc", "presence_push_failed err=${it.message}") }
                    delay(FIREBASE_PRESENCE_INTERVAL_MS)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { MaintenanceMenuOverlay.hide() }
        loopJob?.cancel()
        persistMonitorJob?.cancel()
        presenceJob?.cancel()
        realtime?.stop()
        realtime = null
        if (FirebaseRuntimeMode.allowFirebaseConnectivityTracking) {
            firebaseInfoListener?.let { FirebaseDatabase.getInstance().getReference(".info/connected").removeEventListener(it) }
        }
        firebaseInfoListener = null
        removeFloatingRecoveryButton()
        dismissRegistrationOverlay()
        serviceJob.cancel()
        super.onDestroy()
    }

    private fun hasFirebaseRuntimeConfig(): Boolean {
        return BuildConfig.FIREBASE_DATABASE_URL.trim().isNotBlank() &&
            BuildConfig.FIREBASE_PROJECT_ID.trim().isNotBlank() &&
            BuildConfig.FIREBASE_APP_ID.trim().isNotBlank() &&
            BuildConfig.FIREBASE_API_KEY.trim().isNotBlank()
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val hasLocation = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val fgsType = if (hasLocation) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            startForeground(NOTIFICATION_ID, notification, fgsType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, ArgosLauncherActivity::class.java)
        val pending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Ativo")
            .setOngoing(true)
            .setContentIntent(pending)
            .build()
    }

    private fun ensureGpsEnabled() {
        val now = System.currentTimeMillis()
        if (now - lastEnsureGpsMs < 5 * 60_000L) return
        lastEnsureGpsMs = now

        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val enabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false) ||
                runCatching { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)
        }
        if (enabled) return

        val dpm = DeviceOwnerPolicyManager(applicationContext)
        if (!dpm.isDeviceOwner(packageName)) return
        runCatching { dpm.setLocationEnabledError(packageName, enabled = true) }
    }

    private fun ensurePowerPolicy() {
        val now = System.currentTimeMillis()
        if (now - lastEnsurePowerMs < 20_000L && lastAppliedKeepAwake != null) return
        lastEnsurePowerMs = now

        val settings = SettingsManager(applicationContext)

        val dpm = DeviceOwnerPolicyManager(applicationContext)
        if (!dpm.isDeviceOwner(packageName)) {
            lastAppliedKeepAwake = null
            return
        }

        val keepAwake = settings.shouldKeepAwakeNow(now)
        val changed = lastAppliedKeepAwake == null || lastAppliedKeepAwake != keepAwake
        if (!changed) return

        if (keepAwake) {
            runCatching { dpm.setScreenOffTimeoutMsError(packageName, 24 * 60 * 60 * 1000L) }
            runCatching { dpm.setStayOnWhilePluggedInError(packageName, enabled = true) }
        } else {
            runCatching { dpm.setScreenOffTimeoutMsError(packageName, settings.getRestScreenTimeoutMsCached()) }
            runCatching { dpm.setStayOnWhilePluggedInError(packageName, enabled = false) }
        }
        lastAppliedKeepAwake = keepAwake
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Argos Agent",
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun ensureArgosLauncherIfFinalized() {
        val settings = SettingsManager(applicationContext)
        val tempUnlockActive = settings.getTemporaryUnlockUntilMsCached() > System.currentTimeMillis()
        if (tempUnlockActive) return
        // Sessão de manutenção aberta: não reconduzir o player ao foreground, senão o gate de
        // senha e as telas de manutenção são expulsos (docs/CONTRATO_MENU_MANUTENCAO.md §4.3).
        if (settings.isMaintenanceSessionActiveCached()) return
        if (settings.getMaintenanceModeEnabledCached()) return
        val finalized = settings.getMdmLockedCached() || settings.getKioskModeCached()
        if (!finalized) return
        val dpm = DeviceOwnerPolicyManager(applicationContext)
        if (!dpm.isDeviceOwner(packageName)) return
        dpm.setLauncherAsHome(packageName, enabled = true)

        val allowed = settings.getAllowedPackagesCached() + packageName
        val fg = currentForegroundPackage()?.trim().orEmpty()
        if (fg.isBlank()) return
        if (allowed.contains(fg)) return
        if (fg == "com.android.systemui") return
        val i = Intent(applicationContext, ArgosLauncherActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        runCatching { startActivity(i) }
    }

    private fun currentForegroundPackage(): String? {
        return currentForegroundState()?.packageName
    }

    private data class ForegroundState(
        val packageName: String,
        val lastResumedAtMs: Long,
    )

    private fun currentForegroundState(): ForegroundState? {
        val now = System.currentTimeMillis()
        val accessibilityPkg = ArgosAccessibilityService.getLastForegroundPackage(applicationContext).trim()
        val accessibilityAt = ArgosAccessibilityService.getLastForegroundAt(applicationContext)
        if (accessibilityPkg.isNotBlank() && now - accessibilityAt <= 2 * 60_000L) {
            return ForegroundState(accessibilityPkg, accessibilityAt)
        }
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val events = usm.queryEvents(now - 2 * 60_000L, now)
        val ev = UsageEvents.Event()
        var lastPkg: String? = null
        var lastAt = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            if (ev.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                lastPkg = ev.packageName?.trim()?.ifBlank { null }
                lastAt = ev.timeStamp
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && ev.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                lastPkg = ev.packageName?.trim()?.ifBlank { null }
                lastAt = ev.timeStamp
            }
        }
        return lastPkg?.let { ForegroundState(it, lastAt) }
    }

    /**
     * Traz o app alvo (player) de volta quando o aparelho fica ocioso fora dele, e enquanto a
     * contagem corre exibe [PersistCountdownOverlay].
     *
     * O contador não é enfeite: é ele que enxerga o toque. `ArgosAccessibilityService` não
     * consegue ver toque cru sem *touch exploration* (que quebraria o player), então sem o
     * overlay o carimbo de interação fica parado e o relaunch acontece mesmo com alguém mexendo
     * no aparelho — medido no Gertec Gbot em 2026-08-25. Ver o kdoc do overlay.
     *
     * `hide()` em todo caminho de saída: a janela é dona do próprio ciclo de vida e ficaria presa
     * na tela se apenas parássemos de atualizá-la.
     */
    private suspend fun ensurePersistTargetIfIdle() {
        val settings = SettingsManager(applicationContext)
        if (!settings.getPersistModeEnabledCached()) return PersistCountdownOverlay.hide()
        if (settings.getMaintenanceModeEnabledCached()) return PersistCountdownOverlay.hide()
        if (settings.getTemporaryUnlockUntilMsCached() > System.currentTimeMillis()) return PersistCountdownOverlay.hide()
        // Registered but apelido_interno still blank — the step-by-step registration
        // wizard needs to stay on screen instead of being immediately covered by the
        // autostart/persist target (otherwise the device sits unnamed in the fleet forever,
        // since nothing else ever prompts for it again).
        if (settings.getArgosDeviceTokenCached().isNotBlank() && settings.getDeviceApelidoInternoCached().isBlank()) {
            return PersistCountdownOverlay.hide()
        }

        val targetPkg = AutoStartManager(applicationContext).getAutostartPackagesInPriorityOrder()
            .firstOrNull()
            ?.trim()
            ?.ifBlank { null }
            ?: settings.getAutostartPackageCached().trim().ifBlank { PlayerPackagePolicy.expectedPackage(applicationContext) }
        if (targetPkg.isBlank() || targetPkg.equals(packageName, ignoreCase = true)) {
            return PersistCountdownOverlay.hide()
        }

        val state = currentForegroundState() ?: return PersistCountdownOverlay.hide()
        val foregroundPkg = state.packageName.trim()
        // Player já está rodando: esconde a contagem — é o caso "quando o mplayer rodar, some".
        if (foregroundPkg.equals(targetPkg, ignoreCase = true)) return PersistCountdownOverlay.hide()
        if (foregroundPkg.equals("com.android.systemui", ignoreCase = true)) return PersistCountdownOverlay.hide()
        if (foregroundPkg.equals("com.google.android.permissioncontroller", ignoreCase = true)) {
            return PersistCountdownOverlay.hide()
        }
        if (foregroundPkg.equals("com.android.permissioncontroller", ignoreCase = true)) {
            return PersistCountdownOverlay.hide()
        }

        val timeoutMs = settings.getPersistModeTimeoutMsCached()
        val lastInteractionAt = ArgosAccessibilityService.getLastUserInteractionAt(applicationContext)
            .takeIf { it > 0L }
            ?: state.lastResumedAtMs
        // O prazo é sempre relativo à ÚLTIMA interação, então cada toque registrado empurra o
        // deadline para frente e a contagem recomeça sozinha — sem estado extra para sincronizar.
        val deadlineAt = lastInteractionAt + timeoutMs
        if (System.currentTimeMillis() < deadlineAt) {
            PersistCountdownOverlay.show(applicationContext, deadlineAt)
            return
        }

        PersistCountdownOverlay.hide()
        WatchdogManager(applicationContext).launch(targetPkg)
    }

    private fun recordIntelligenceSampleAndHeal() {
        val now = System.currentTimeMillis()
        val settings = SettingsManager(applicationContext)
        val onlineState = settings.getOfflineStateCached().trim().ifBlank { "unknown" }
        val fg = currentForegroundPackage()?.trim().orEmpty()

        if (fg == PlayerPackagePolicy.expectedPackage(applicationContext)) lastForegroundPlayerMs = now
        if (fg == packageName) lastForegroundLauncherMs = now

        val hb = lastMainHeartbeatMs
        if (hb > 0L && now - hb > 20_000L && now - lastAutoHealMs > 60_000L) {
            lastAutoHealMs = now
            emitIncidentSnapshot(reason = "anr_suspected", details = "main_heartbeat_stale_ms=${now - hb}")
        }

        if (now - lastReplaySampleMs >= 20_000L) {
            lastReplaySampleMs = now
            val sample = telemetry.collectReplaySample(foregroundPackage = fg.ifBlank { null }, onlineState = onlineState)
            telemetry.recordReplaySample(sample)

            val lowMem = sample.optJSONObject("ram")?.optBoolean("low_memory", false) == true
            if (lowMem && now - lastAutoHealMs > 60_000L) {
                lastAutoHealMs = now
                // Report memory pressure; do NOT auto-wipe app data — clearing all user data
                // on every low-memory spike causes MPlayer to lose configuration and enter a
                // re-setup loop. Let the operator decide via a remote clear_cache command.
                emitIncidentSnapshot(reason = "memory_critical", details = "low_memory_reported")
            }
        }

        ensureGeofence(settings, now)

        val maintenance = settings.getMaintenanceModeEnabledCached()
        val tempUnlockActive = settings.getTemporaryUnlockUntilMsCached() > now
        if (maintenance || tempUnlockActive) return
        if (settings.isMaintenanceSessionActiveCached()) return

        val locked = settings.getMdmLockedCached() || settings.getKioskModeCached()
        if (locked && settings.isDeviceBoundCached()) {
            if (now - lastForegroundPlayerMs > 60_000L && now - lastAutoHealMs > 30_000L) {
                lastAutoHealMs = now
                val opened = apps.open(PlayerPackagePolicy.expectedPackage(applicationContext))
                emitIncidentSnapshot(reason = "player_missing", details = if (opened) "reopened" else "open_failed")
            }
        }

        val internetOk = hasInternet(applicationContext)
        // firebaseConnected is only ever updated by ensureFirebaseConnectedListener(), which is
        // gated off by FirebaseRuntimeMode.allowFirebaseConnectivityTracking — with that flag
        // disabled (current architecture), firebaseConnected is permanently stuck at its default
        // `false`, making `!firebaseOk` always true. That previously forced a full Firebase RTDB
        // reconnect (stop + restart the seq listener) every 5 minutes for no reason, generating
        // unnecessary connection churn. Without a real connectivity signal, don't "self-heal" —
        // the Firebase SDK already reconnects on its own when the network actually drops.
        val firebaseOk = firebaseConnected
        val hasReliableFirebaseSignal = FirebaseRuntimeMode.allowFirebaseConnectivityTracking
        if (shouldUseLegacyRealtime() && hasReliableFirebaseSignal && internetOk && !firebaseOk && now - lastSyncRestartMs > 5 * 60_000L) {
            lastSyncRestartMs = now
            runCatching {
                realtime?.stop()
                realtime = if (shouldUseLegacyRealtime()) {
                    RealtimeCommandManager(applicationContext).also { it.start() }
                } else {
                    null
                }
            }
            emitIncidentSnapshot(reason = "sync_restart", details = "firebase_disconnected")
        }
    }

    private suspend fun runAdaptivePollingAndHeartbeat(now: Long) {
        val pollIntervalMs = computeCommandPollIntervalMs(now)
        if (now - lastAdaptivePollMs >= pollIntervalMs) {
            lastAdaptivePollMs = now
            runCatching { pollingService.tick() }
                .onFailure { Log.w("ArgosForegroundSvc", "polling_tick_failed err=${it.message}") }
        }
        if (now - lastHeartbeatCheckMs >= HEARTBEAT_CHECK_INTERVAL_MS) {
            lastHeartbeatCheckMs = now
            runCatching { heartbeatService.send() }
                .onFailure { Log.w("ArgosForegroundSvc", "heartbeat_failed err=${it.message}") }
            // Um aviso por ciclo (60s), não por requisição: o objetivo é o estouro aparecer no
            // logcat do aparelho sem depender de ninguém abrir o painel.
            runCatching {
                RequestMeter.warnIfAbove(RequestMeter.FIREBASE, REQUEST_WARN_FIREBASE_PER_HOUR)
                RequestMeter.warnIfAbove(RequestMeter.API, REQUEST_WARN_API_PER_HOUR)
                // O teto do alarme acompanha o modo: no modo supabase o poll de 5s produz ~720
                // req/h por desenho, e manter o limite de 200 faria o alarme gritar sempre —
                // um alarme que sempre toca deixa de ser alarme e esconde o estouro real.
                RequestMeter.warnIfAbove(
                    RequestMeter.SUPABASE,
                    if (busModeSettings.getEventBusModeCached().needsFastPolling) REQUEST_WARN_SUPABASE_FAST_POLL_PER_HOUR
                    else REQUEST_WARN_SUPABASE_PER_HOUR,
                )
            }
            runCatching { logFlushService.flushIfDue() }
                .onFailure { Log.w("ArgosForegroundSvc", "log_flush_failed err=${it.message}") }
        }
        runCatching { checkCriticalAppExits(now) }
    }

    private fun computeOperationalDelayMs(now: Long): Long {
        return computeCommandPollIntervalMs(now)
            .coerceAtMost(OPERATIONAL_SWEEP_INTERVAL_MS)
            .coerceAtLeast(MIN_LOOP_DELAY_MS)
    }

    // Instante em que a cadência crítica começou. 0 = não está em modo crítico.
    private var criticalPollSinceMs: Long = 0L

    private fun computeCommandPollIntervalMs(now: Long): Long {
        if (hasCriticalCommandPending(now)) {
            if (criticalPollSinceMs == 0L) criticalPollSinceMs = now
            val elapsed = now - criticalPollSinceMs
            if (elapsed <= CRITICAL_POLL_MAX_DURATION_MS) return COMMAND_POLL_INTERVAL_CRITICAL_MS
            // TETO. A cadência crítica é 1 REQUISIÇÃO POR SEGUNDO — ela existe para pegar um
            // comando novo de alta prioridade em segundos, não para ficar ligada indefinidamente.
            // Sem teto, qualquer condição que mantenha hasCriticalCommandPending() verdadeiro vira
            // ~3.600 req/h por aparelho: foi exatamente o que aconteceu com um `sincronizar` preso
            // em `processing` (~80 syncs/min medidos em campo). O gatilho conhecido já foi
            // corrigido, mas o teto garante que QUALQUER gatilho futuro degrade sozinho em vez de
            // sangrar custo em silêncio.
            if (now - lastCriticalCapLogMs > CRITICAL_POLL_CAP_LOG_INTERVAL_MS) {
                lastCriticalCapLogMs = now
                Log.w(
                    "ArgosForegroundSvc",
                    "critical_poll_capped elapsed_min=${elapsed / 60_000L} — degradando para cadência normal",
                )
            }
            return COMMAND_POLL_INTERVAL_NORMAL_MS
        }
        criticalPollSinceMs = 0L
        if (otaManager.hasPendingOrActiveUpdate()) return COMMAND_POLL_INTERVAL_UPDATE_MS
        if (isRemoteSessionActive()) return COMMAND_POLL_INTERVAL_REMOTE_MS
        // Aparelho migrado para o Supabase nao tem push do RTDB (ver EventBusMode): a latencia
        // do comando passa a ser EXATAMENTE o intervalo de poll. 5s e o teto acordado com o
        // negocio. Note que o ramo de ocioso fica ABAIXO deste — no modo supabase nao existe
        // "ocioso barato": sem push, espacar o poll significa comando demorando minutos.
        //
        // Custo: 720 req/h/aparelho contra os 60 do modo firebase. E por isso que este modo e
        // ligado por aparelho e que o alarme do RequestMeter tem teto proprio aqui embaixo —
        // e por isso que, antes de escalar alem do aparelho de teste, o poll de 5s deve dar
        // lugar ao Supabase Realtime.
        if (busModeSettings.getEventBusModeCached().needsFastPolling) return COMMAND_POLL_INTERVAL_SUPABASE_BUS_MS
        if (isLongIdle(now)) return COMMAND_POLL_INTERVAL_IDLE_MS
        return COMMAND_POLL_INTERVAL_NORMAL_MS
    }

    // Second guard on the same failure (see LocalCommandStore.listRunnable kdoc): the 1s critical
    // poll exists to pick up NEW high-priority work quickly, so it must key off genuinely pending
    // commands. A row already in STATUS_PROCESSING is either running right now or abandoned — in
    // both cases polling the backend every second changes nothing, and on the X96 at .84 a single
    // stuck priority-5 `sincronizar` in that state held the whole agent at a 1s poll indefinitely.
    // listRunnable() only returns stale processing rows now, but excluding them here means a
    // command that legitimately takes longer than PROCESSING_STALE_MS can't pin the poll either.
    private fun hasCriticalCommandPending(now: Long): Boolean {
        val command = runCatching { localCommandStore.listRunnable(now = now, limit = 1).firstOrNull() }.getOrNull() ?: return false
        if (command.status == LocalCommandStore.STATUS_PROCESSING) return false
        return command.priority >= CRITICAL_COMMAND_PRIORITY
    }

    private fun isRemoteSessionActive(): Boolean {
        return remoteHealthManager.getLastHealthSnapshot()?.optBoolean("session_active", false) == true
    }

    private fun isLongIdle(now: Long): Boolean {
        val lastInteractionAt = ArgosAccessibilityService.getLastUserInteractionAt(applicationContext)
        if (lastInteractionAt <= 0L) return false
        return now - lastInteractionAt >= LONG_IDLE_THRESHOLD_MS
    }

    private fun checkCriticalAppExits(now: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (now - lastExitCheckMs < 5 * 60_000L) return
        lastExitCheckMs = now

        val prefs = applicationContext.getSharedPreferences("argos_agent_intelligence", Context.MODE_PRIVATE)
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val packages = listOf(PlayerPackagePolicy.expectedPackage(applicationContext), packageName)
        for (pkg in packages) {
            val lastTs = prefs.getLong("exit_last_ts_$pkg", 0L).coerceAtLeast(0L)
            val exits = runCatching { am.getHistoricalProcessExitReasons(pkg, 0, 5) }.getOrDefault(emptyList())
            var maxSeen = lastTs
            for (e in exits) {
                val ts = e.timestamp
                if (ts <= lastTs) continue
                if (ts > maxSeen) maxSeen = ts
                val reason = e.reason
                val r = when (reason) {
                    android.app.ApplicationExitInfo.REASON_ANR -> "anr"
                    android.app.ApplicationExitInfo.REASON_CRASH -> "crash"
                    android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "native_crash"
                    android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory"
                    android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "resource"
                    android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "user"
                    android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "init_failure"
                    android.app.ApplicationExitInfo.REASON_EXIT_SELF -> "exit_self"
                    else -> "other"
                }
                val detail = "pkg=$pkg reason=$r status=${e.status} pss=${e.pss} rss=${e.rss} ts=$ts"
                emitIncidentSnapshot(reason = "app_exit_$r", details = detail)
            }
            if (maxSeen > lastTs) prefs.edit().putLong("exit_last_ts_$pkg", maxSeen).apply()
        }
    }

    private fun ensureGeofence(settings: SettingsManager, now: Long) {
        if (now - lastGeofenceCheckMs < 60_000L) return
        lastGeofenceCheckMs = now

        val cfg = settings.getGeofenceConfigCached() ?: return
        val loc = readBestLastKnownLocation() ?: return
        val results = FloatArray(1)
        Location.distanceBetween(loc.latitude, loc.longitude, cfg.lat, cfg.lng, results)
        val distanceM = results[0].toDouble()
        val inside = distanceM <= cfg.radiusM
        val wasInside = geofenceWasInside
        geofenceWasInside = inside
        if (wasInside == null) return
        if (inside) return
        if (now - lastGeofenceEventMs < 5 * 60_000L) return
        lastGeofenceEventMs = now

        val deviceId = DeviceIdentityManager.getPersistentDeviceId(applicationContext)
        val event = JSONObject()
            .put("ts", now)
            .put("type", "geofence_exit")
            .put("device_id", deviceId)
            .put("distance_m", distanceM)
            .put("radius_m", cfg.radiusM)
            .put("center_lat", cfg.lat)
            .put("center_lng", cfg.lng)
            .put(
                "location",
                JSONObject()
                    .put("lat", loc.latitude)
                    .put("lng", loc.longitude)
                    .put("accuracy_m", loc.accuracy.toDouble())
                    .put("time_ms", loc.time),
            )
            .put("actions", org.json.JSONArray(cfg.actions.toList()))

        if (FirebaseRuntimeMode.allowAgentPublish && deviceId.isNotBlank()) {
            val ref = FirebaseDatabase.getInstance().getReference("m_argos/devices").child(deviceId)
            runCatching { ref.child("alerts").push().setValue(jsonToFirebaseValue(event)) }
            runCatching { ref.child("intelligence").child("geofence").child("events").push().setValue(jsonToFirebaseValue(event)) }
        }

        if (cfg.actions.contains("intensive_tracking") || cfg.actions.contains("lost_mode")) {
            settings.applyNow { setLocationTelemetryIntervalMin(1) }
        }
        if (cfg.actions.contains("lock") || cfg.actions.contains("lost_mode")) {
            val dpm = DeviceOwnerPolicyManager(applicationContext)
            if (dpm.isDeviceOwner(packageName)) {
                settings.applyNow {
                    setMdmLocked(true)
                    setKioskMode(true)
                }
                runCatching { dpm.applyLocked(packageName, settings.getAllowedPackagesCached()) }
                requestLockTaskStart()
            }
        }

        emitIncidentSnapshot(reason = "geofence_exit", details = "distance_m=$distanceM")
    }

    private fun ensureMainHeartbeat() {
        if (mainHeartbeatStarted) return
        mainHeartbeatStarted = true
        mainHandler.post(object : Runnable {
            override fun run() {
                lastMainHeartbeatMs = System.currentTimeMillis()
                mainHandler.postDelayed(this, 2000L)
            }
        })
    }

    private fun readBestLastKnownLocation(): Location? {
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        var best: Location? = null
        for (p in providers) {
            val l = runCatching { lm.getLastKnownLocation(p) }.getOrNull() ?: continue
            if (best == null) {
                best = l
                continue
            }
            val newer = l.time > best.time
            val betterAcc = l.accuracy < best.accuracy
            if (newer && betterAcc) best = l
            else if (newer && (best.time + 120_000L) < l.time) best = l
        }
        return best
    }

    private fun emitIncidentSnapshot(reason: String, details: String) {
        val now = System.currentTimeMillis()
        val deviceId = DeviceIdentityManager.getPersistentDeviceId(applicationContext)
        val snapshot = telemetry.buildReplaySnapshot(reason = reason, details = details)
            .put("device_id", deviceId)
        if (FirebaseRuntimeMode.allowAgentPublish && deviceId.isNotBlank()) {
            val ref = FirebaseDatabase.getInstance().getReference("m_argos/devices").child(deviceId)
            runCatching { ref.child("intelligence").child("incidents").push().setValue(jsonToFirebaseValue(snapshot)) }
        }
        val supa = JSONObject()
            .put("device_id", deviceId)
            .put("created_at_epoch_ms", now)
            .put("reason", reason)
            .put("details", details)
            .put("payload_json", snapshot.toString())
        telemetry.postToSupabaseIfConfigured("rest/v1/agent_incidents", supa)
    }

    private fun jsonToFirebaseValue(any: Any?): Any? {
        return when (any) {
            null -> null
            JSONObject.NULL -> null
            is JSONObject -> {
                val map = HashMap<String, Any?>()
                val it = any.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    map[k] = jsonToFirebaseValue(any.opt(k))
                }
                map
            }
            is org.json.JSONArray -> {
                val list = ArrayList<Any?>()
                for (i in 0 until any.length()) list.add(jsonToFirebaseValue(any.opt(i)))
                list
            }
            is Number, is Boolean, is String -> any
            else -> any.toString()
        }
    }

    private fun ensureRemoteSupportAppsInstalled() {
        val now = System.currentTimeMillis()
        if (remoteSupportInstalled) return
        if (now - lastEnsureRemoteSupportMs < 60_000L) return
        lastEnsureRemoteSupportMs = now

        if (!hasInternet(applicationContext)) return

        val dpm = DeviceOwnerPolicyManager(applicationContext)
        if (!dpm.isDeviceOwner(packageName)) return

        val anyDeskPkg = "com.anydesk.anydeskandroid"
        val pluginPkg = "com.anydesk.adcontrol.ad1"

        val installer = ApkInstallManager(applicationContext)

        if (!isInstalled(anyDeskPkg)) {
            val res = installer.install(
                ApkInstallRequest(
                    url = ANYDESK_URL,
                    expectedPackage = anyDeskPkg,
                    expectedVersion = null,
                    force = false,
                    silent = true,
                    autoOpen = false,
                    sha256 = ANYDESK_APK_SHA256,
                ),
            )
            if (res.status != "success" && res.status != "ignored") return
        }

        if (!isInstalled(pluginPkg)) {
            val res = installer.install(
                ApkInstallRequest(
                    url = ANYDESK_PLUGIN_AD1_URL,
                    expectedPackage = pluginPkg,
                    expectedVersion = null,
                    force = false,
                    silent = true,
                    autoOpen = false,
                    sha256 = ANYDESK_PLUGIN_AD1_APK_SHA256,
                ),
            )
            if (res.status != "success" && res.status != "ignored") return
        }

        remoteSupportInstalled = isInstalled(anyDeskPkg) && isInstalled(pluginPkg)
    }

    private fun isInstalled(packageName: String): Boolean {
        val pm = applicationContext.packageManager
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
            true
        }.getOrDefault(false)
    }

    // CONTRATO.md (argos-integracao), decisão registrada em 2026-08-05: auto-update do MPlayer
    // com seleção por tipo de dispositivo (com.mupa.player.x96 para X96/DroidLogic,
    // com.mupa.mplayer para os demais). Reaproveita o mesmo padrão/ciclo de manutenção de
    // ensureRemoteSupportAppsInstalled() acima (roda dentro do "operational sweep" de 60s do
    // loop principal — o mesmo usado por heartbeat/config sync — com seu próprio debounce mais
    // longo, para não bater no catálogo do Web a cada tick). Todo o parsing/decisão de app_id
    // fica em MPlayerAutoUpdateManager; esta função só cuida do gating local (rede, flag de
    // rollback, Device Owner) e do device_id.
    private fun ensureMplayerAutoUpdated() {
        val now = System.currentTimeMillis()
        if (now - lastMplayerAutoUpdateCheckMs < MPLAYER_AUTO_UPDATE_CHECK_INTERVAL_MS) return
        lastMplayerAutoUpdateCheckMs = now

        if (!hasInternet(applicationContext)) return

        val settings = SettingsManager(applicationContext)
        if (!settings.getMplayerAutoUpdateEnabledCached()) {
            Log.d("ArgosForegroundSvc", "mplayer_auto_update_skip reason=disabled_by_local_flag")
            return
        }

        // Instalação silenciosa via PackageInstaller exige Device Owner (mesma exigência de
        // ensureRemoteSupportAppsInstalled acima) — sem isso, ApkInstallManager.install()
        // rejeitaria com "silent_unavailable:not_device_owner" depois de já ter baixado o APK.
        val dpm = DeviceOwnerPolicyManager(applicationContext)
        if (!dpm.isDeviceOwner(packageName)) return

        val deviceId = DeviceIdentityManager.getPersistentDeviceId(applicationContext)
        MPlayerAutoUpdateManager(applicationContext).checkAndUpdate(deviceId)
    }

    private val remoteHealthManager by lazy { com.mupa.agent.argos.remote.RemoteHealthManager(applicationContext) }

    private fun ensureArgosRemoteHealthy() {
        try {
            remoteHealthManager.runIfDue()
        } catch (e: Exception) {
            Log.w("ArgosForegroundSvc", "Remote health check error: ${e.message}")
        }
    }

    /**
     * Liga/desliga o menu flutuante de manutenção conforme a flag remota
     * (docs/CONTRATO_MENU_MANUTENCAO.md §11, default FALSE). Roda no mesmo "operational sweep" de 60s
     * que já cuida do indicador off-line, então desligar a flag pelo painel faz o overlay sumir
     * no ciclo seguinte, sem reinstalação. show/hide são idempotentes.
     *
     * O overlay é dono da própria janela e sobrevive à troca de app em foreground; ficar sob o
     * ciclo de vida deste serviço é o que garante que ele seja recriado se o serviço reiniciar.
     */
    private fun ensureMaintenanceMenu() {
        val enabled = SettingsManager(applicationContext).getMaintenanceMenuEnabledCached()
        if (enabled) MaintenanceMenuOverlay.show(applicationContext) else MaintenanceMenuOverlay.hide()
    }

    /**
     * Religa o próprio serviço de acessibilidade se ele tiver caído.
     *
     * **Reinstalar o APK desliga o serviço** — o Android o tira de
     * `ENABLED_ACCESSIBILITY_SERVICES` quando o pacote é substituído, e nada o repõe. Sem ele o
     * agent perde a detecção de interação do usuário, e aí o Modo Persistência passa a relançar
     * o app alvo por cima de quem está usando o aparelho: `getLastUserInteractionAt()` fica
     * congelado no passado, então o gate de ociosidade libera em todo ciclo.
     *
     * Confirmado num Gertec Gbot em 2026-08-25: depois de alguns updates seguidos o serviço
     * estava desligado, `dumpsys accessibility` mostrava `services:{}`, e tocar a tela não
     * impedia o relaunch. Como nada avisa quando isso acontece, a checagem entra no sweep — é
     * idempotente e barata quando o serviço já está no ar.
     *
     * Depende de `WRITE_SECURE_SETTINGS` (concedida no provisionamento). Sem ela vira no-op.
     */
    private fun ensureOwnAccessibilityService() {
        AccessibilityServiceEnabler.ensureEnabled(
            applicationContext,
            ArgosAccessibilityService.componentName(applicationContext),
        )
    }

    private fun ensureWifiSetupIfOffline() {
        val online = hasInternet(applicationContext)
        val settings = SettingsManager(applicationContext)
        if (settings.getMaintenanceModeEnabledCached()) {
            OfflineIndicatorOverlay.hide()
            return
        }
        val finalized = settings.getMdmLockedCached() || settings.getKioskModeCached()
        if (!finalized) {
            OfflineIndicatorOverlay.hide()
            return
        }

        // Fluxo novo: em vez de abrir as Configurações de Wi-Fi (que interrompia a operação),
        // só sinaliza o estado offline com um indicador flutuante discreto no canto inferior
        // esquerdo. Some assim que a conexão volta.
        if (online) {
            OfflineIndicatorOverlay.hide()
        } else {
            OfflineIndicatorOverlay.show(applicationContext)
        }
    }

    /**
     * `true` se o aparelho está de fato preso em lock task AGORA.
     *
     * Lido do `ActivityManager`, não dos flags do agent: os flags dizem o que o agent *quer*, e
     * este método diz o que o sistema *está fazendo*. A diferença importa em manutenção, quando o
     * kiosk já foi desmontado mas o loop continua rodando.
     */
    private fun isLockTaskActive(): Boolean = runCatching {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        am.lockTaskModeState != android.app.ActivityManager.LOCK_TASK_MODE_NONE
    }.getOrDefault(false)

    private fun ensureBaselineRestrictions() {
        val dpm = DeviceOwnerPolicyManager(applicationContext)
        if (!dpm.isDeviceOwner(packageName)) return
        dpm.applyBaselineRestrictions(packageName)
    }

    private fun ensureMaintenanceOrTemporaryUnlock() {
        val settings = SettingsManager(applicationContext)
        val now = System.currentTimeMillis()
        val until = settings.getTemporaryUnlockUntilMsCached()
        val dpm = DeviceOwnerPolicyManager(applicationContext)
        val isOwner = dpm.isDeviceOwner(packageName)

        val maintenanceEnabled = settings.getMaintenanceModeEnabledCached()
        val maintenanceUntil = settings.getMaintenanceModeUntilMsCached()
        if (maintenanceEnabled) {
            if (maintenanceUntil > 0L && now > maintenanceUntil) {
                settings.applyNow { setMaintenanceMode(false, 0L) }
            } else if (isOwner) {
                settings.applyNow {
                    setMdmLocked(false)
                    setKioskMode(false)
                }
                runCatching { dpm.applyUnlocked(packageName) }
                runCatching { dpm.setAndroidLauncherAsHome(packageName) }
                // SÓ pede para sair do lock task se ainda houver lock task.
                //
                // `LockTaskControl.requestStop()` funciona trazendo a ArgosLauncherActivity para
                // frente (é ela que precisa chamar stopLockTask()). Como este bloco roda a cada
                // ciclo enquanto a manutenção está aberta, chamar incondicionalmente jogava o
                // launcher do agent por cima da tela do técnico a cada ~60s — na prática,
                // expulsava a pessoa das Configurações do Android bem no meio da configuração de
                // Wi-Fi, que é justamente o que a manutenção existe para permitir.
                // Observado no Gertec Gbot em 2026-08-26: `mLockTaskModeState=NONE` (já não havia
                // nada para encerrar) e mesmo assim um START da launcher a cada sweep.
                if (isLockTaskActive()) {
                    LockTaskControl.requestStop(applicationContext, "service")
                }
            }
            return
        }

        if (until > 0L && now > until) {
            val restoreLocked = settings.getTemporaryUnlockPrevMdmLockedCached()
            val restoreKiosk = settings.getTemporaryUnlockPrevKioskModeCached()
            settings.applyNow { clearTemporaryUnlock() }
            if (isOwner && (restoreLocked || restoreKiosk)) {
                settings.applyNow {
                    setMdmLocked(restoreLocked)
                    setKioskMode(restoreKiosk)
                }
                runCatching { dpm.setLauncherAsHome(packageName, enabled = true) }
                runCatching { dpm.updateLockTaskPackages(packageName, settings.getAllowedPackagesCached()) }
                runCatching { dpm.applyLocked(packageName, settings.getAllowedPackagesCached()) }
                requestLockTaskStart()
            }
        } else if (until > now && isOwner) {
            runCatching { dpm.setAndroidLauncherAsHome(packageName) }
            LockTaskControl.requestStop(applicationContext, "service")
        }
    }

    private fun requestLockTaskStart() {
        val i = Intent("com.mupa.agent.argos.action.LOCKTASK_START")
            .setClassName(packageName, "com.mupa.agent.argos.ui.LockTaskBridgeActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        runCatching { startActivity(i) }
    }


    private fun ensureFloatingRecoveryButton() {
        removeFloatingRecoveryButton()
        return
        @Suppress("UNREACHABLE_CODE")
        if (!recoveryOverlayEnabled) {
            removeFloatingRecoveryButton()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(applicationContext)) {
                removeFloatingRecoveryButton()
                return
            }
        }
        if (overlayTapView != null) return

        mainHandler.post {
            if (overlayTapView != null) return@post
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val sizePx = (48f * resources.displayMetrics.density).toInt().coerceAtLeast(32)
            val marginPx = (12f * resources.displayMetrics.density).toInt().coerceAtLeast(0)

            val view = ImageView(this).apply {
                alpha = 0.85f
                setImageResource(android.R.drawable.ic_menu_manage)
                setPadding(marginPx / 2, marginPx / 2, marginPx / 2, marginPx / 2)
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Color.parseColor("#B91C1C"))
                    setStroke((1f * resources.displayMetrics.density).toInt().coerceAtLeast(1), Color.parseColor("#FFFFFF"))
                }
                setOnClickListener { onRecoveryTap() }
            }

            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                sizePx,
                sizePx,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = marginPx
                y = marginPx
            }

            runCatching {
                wm.addView(view, params)
                overlayTapView = view
            }
        }
    }

    private fun updateRecoveryOverlayEnabled(enabled: Boolean) {
        if (recoveryOverlayEnabled == enabled) return
        recoveryOverlayEnabled = enabled
        if (!enabled) {
            removeFloatingRecoveryButton()
        } else {
            ensureFloatingRecoveryButton()
        }
    }

    private fun removeFloatingRecoveryButton() {
        val tap = overlayTapView
        overlayTapView = null
        val dialog = overlayDialogView
        overlayDialogView = null
        mainHandler.post {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            if (dialog != null) runCatching { wm.removeView(dialog) }
            if (tap != null) runCatching { wm.removeView(tap) }
        }
    }

    private fun onRecoveryTap() {
        val now = System.currentTimeMillis()
        recoveryTapTimes.addLast(now)
        while (recoveryTapTimes.isNotEmpty() && now - recoveryTapTimes.first() > 3_000L) {
            recoveryTapTimes.removeFirst()
        }
        if (recoveryTapTimes.size >= 5) {
            recoveryTapTimes.clear()
            showRecoveryPasswordOverlay()
        }
    }

    /**
     * Executa a ação de um destino de manutenção (sair do kiosk / abrir tela), SEM senha.
     *
     * Dois caminhos chegam aqui:
     *  - o gate de senha (showRecoveryPasswordOverlay), depois de MaintenanceUnlock.validate — via
     *    o BOTÃO FLUTUANTE, que qualquer um pode tocar e por isso continua exigindo senha;
     *  - o ATALHO DO CONTROLE (ArgosAccessibilityService.onKeyEvent → ACTION_RUN_MAINTENANCE_TARGET),
     *    que executa DIRETO, sem senha. A proteção do atalho é o próprio segredo de qual tecla está
     *    vinculada — vincular exige autenticar primeiro (a tela de atalhos fica atrás do gate).
     *    Decisão do Antunes em 2026-08-14.
     */
    private fun runMaintenanceTarget(target: String) {
        // Segura o watchdog de kiosk para a tela de destino não ser expulsa pelo player.
        runCatching { SettingsManager(applicationContext).applyNow { startMaintenanceSession() } }
        if (target == MaintenanceMenuOverlay.TARGET_EXIT_KIOSK) {
            // Ativa o MODO MANUTENÇÃO, não só a sessão.
            //
            // São duas flags com papéis diferentes e é fácil confundir:
            //  - `maintenanceSession` (startMaintenanceSession, acima) apenas SEGURA o watchdog,
            //    para a tela de destino não ser expulsa pelo player;
            //  - `maintenanceModeEnabled` é o que `ensureMaintenanceOrTemporaryUnlock()` lê para
            //    de fato desmontar o kiosk (applyUnlocked + launcher do Android + stop do lock task).
            //
            // Até 2026-08-25 este ramo setava só a primeira e chamava stopLockTask(). O resultado
            // é que "Sair do kiosk" tirava o player da frente mas deixava as políticas de kiosk
            // de pé: o aparelho seguia em lock task e as Configurações do Android abriam e
            // fechavam sozinhas, mostrando o diálogo de "ação bloqueada pelo administrador" —
            // exatamente o que impedia configurar Wi-Fi, que é o motivo deste botão existir.
            // O caminho do código de barras (MaintenanceBarcodeReceiver) sempre setou a segunda,
            // e por isso funcionava; só o botão ficava pela metade.
            //
            // `0L` = sem expiração automática, igual ao código de barras. Sair da manutenção é
            // ação explícita do operador (o kiosk volta pelo painel ou pelo próprio menu).
            runCatching {
                SettingsManager(applicationContext).applyNow { setMaintenanceMode(true, 0L) }
            }
            // Aplica na hora em vez de esperar o sweep de 60s: quem apertou o botão está com o
            // aparelho na mão e precisa das Configurações agora.
            runCatching {
                val dpm = DeviceOwnerPolicyManager(applicationContext)
                if (dpm.isDeviceOwner(packageName)) {
                    SettingsManager(applicationContext).applyNow {
                        setMdmLocked(false)
                        setKioskMode(false)
                    }
                    dpm.applyUnlocked(packageName)
                    dpm.setAndroidLauncherAsHome(packageName)
                }
            }
            runCatching {
                startActivity(
                    Intent(this, ArgosLauncherActivity::class.java)
                        .setAction(ArgosLauncherActivity.ACTION_LOCKTASK_STOP)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                )
            }
        } else {
            val route = when (target) {
                MaintenanceMenuOverlay.TARGET_KIOSK_APPS -> Routes.KioskApps
                MaintenanceMenuOverlay.TARGET_REMOTE_SHORTCUTS -> Routes.RemoteShortcuts
                else -> Routes.DeviceSettings
            }
            runCatching {
                startActivity(
                    Intent(this, ArgosLauncherActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .putExtra(ArgosLauncherActivity.EXTRA_NAV_ROUTE, route),
                )
            }
        }
    }

    private fun showRecoveryPasswordOverlay(target: String = Routes.DeviceSettings) {
        if (overlayDialogView != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = resources.displayMetrics.density
        val pad = (16f * density).toInt()
        val cardPad = (14f * density).toInt()
        val spacing = (10f * density).toInt()

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#88000000"))
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(cardPad, cardPad, cardPad, cardPad)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 16f * density
                setColor(Color.parseColor("#FF0B1220"))
                setStroke((1f * density).toInt().coerceAtLeast(1), Color.parseColor("#334155"))
            }
        }

        val title = TextView(this).apply {
            text = "Modo Manutenção"
            setTextColor(Color.WHITE)
            textSize = 18f
        }
        val subtitle = TextView(this).apply {
            text = "Dispositivo sem comunicação com o ARGOS. Informe a senha mestre ou o código de 4 dígitos gerado pela central."
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 14f
        }
        // Data/hora EXATAMENTE como o "Modo Manutenção" (MaintenanceUnlock.humanNow): é o valor
        // que o técnico informa na central para gerar o código. Formato dd/MM/yyyy HH:mm, mesma
        // base do token yyyyMMddHHmm que a central usa — não há serial nem fuso aqui, porque o
        // código do painel depende só de data/hora (precisão de minuto, tolerância ±2 min).
        val genInfo = TextView(this).apply {
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            text = buildString {
                appendLine("Data/hora deste dispositivo:")
                appendLine(com.mupa.agent.argos.managers.MaintenanceUnlock.humanNow())
                append("Informe esta data/hora na central para gerar o código.")
            }
        }
        // Teclado numérico PRÓPRIO, dentro do diálogo — nada de IME do sistema.
        //
        // Motivo: neste X96 o teclado numérico do Android cobre exatamente a faixa onde ficam
        // CANCELAR/DESBLOQUEAR, deixando o diálogo sem como submeter. E BACK, o reflexo natural
        // para fechar o teclado, fecha o DIÁLOGO inteiro. Resultado prático: era impossível
        // concluir a autenticação no aparelho (confirmado em 2026-08-12).
        //
        // Sendo TV box, a operação real é por controle remoto: todos os botões abaixo são
        // focáveis, então o D-pad navega e OK/ENTER aciona, sem depender de toque na tela.
        val pin = StringBuilder()
        val input = TextView(this).apply {
            setTextColor(Color.WHITE)
            text = "Senha"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 20f
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 12f * density
                setColor(Color.parseColor("#141B2D"))
                setStroke((1f * density).toInt().coerceAtLeast(1), Color.parseColor("#334155"))
            }
            setPadding(pad, pad, pad, pad)
        }
        fun renderPin() {
            if (pin.isEmpty()) {
                input.text = "Senha"
                input.setTextColor(Color.parseColor("#94A3B8"))
            } else {
                input.text = "•".repeat(pin.length)
                input.setTextColor(Color.WHITE)
            }
        }
        val error = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#FCA5A5"))
            textSize = 13f
            visibility = View.GONE
        }

        // Grade 3x4: 1..9, apagar, 0, e o próprio "OK" fica na linha de botões abaixo.
        // Cada tecla é focável para o D-pad do controle percorrer a grade.
        val keypad = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun keyButton(label: String, onTap: () -> Unit): Button = Button(this).apply {
            text = label
            textSize = 20f
            isFocusable = true
            isFocusableInTouchMode = false
            setTextColor(Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 10f * density
                setColor(Color.parseColor("#1E293B"))
                setStroke((1f * density).toInt().coerceAtLeast(1), Color.parseColor("#334155"))
            }
            setOnClickListener { onTap() }
            // Realce ao receber foco do controle — sem isso o operador não sabe onde está.
            setOnFocusChangeListener { v, hasFocus ->
                (v.background as? android.graphics.drawable.GradientDrawable)?.setColor(
                    Color.parseColor(if (hasFocus) "#2563EB" else "#1E293B"),
                )
            }
        }
        val keyRows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", " "))
        for (r in keyRows) {
            val rowView = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (k in r) {
                val btn = when (k) {
                    "⌫" -> keyButton("⌫") { if (pin.isNotEmpty()) { pin.deleteCharAt(pin.length - 1); renderPin() } }
                    " " -> keyButton("C") { pin.setLength(0); renderPin() }
                    else -> keyButton(k) { if (pin.length < 12) { pin.append(k); renderPin() } }
                }
                rowView.addView(
                    btn,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins((4f * density).toInt(), (4f * density).toInt(), (4f * density).toInt(), (4f * density).toInt())
                    },
                )
            }
            keypad.addView(rowView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val cancel = Button(this).apply {
            text = "Cancelar"
            isFocusable = true
            setOnClickListener {
                Log.i("MaintenanceGate", "gate_cancelled")
                dismissRecoveryPasswordOverlay()
            }
        }
        val ok = Button(this).apply {
            text = "Desbloquear"
            isFocusable = true
            setOnClickListener {
                val pwd = pin.toString()
                // Valida pelo MESMO mecanismo do "Modo Manutenção" (MaintenanceUnlock): senha mestre
                // OU o código de 4 dígitos gerado pela central. É o algoritmo que REALMENTE casa com
                // o painel (hash polinomial "ARGOS-MNT-v1", idêntico a src/lib/maintenance-code.ts).
                // Antes este gate usava SecurityAccessManager/DynamicPasswordManager, que é outro
                // algoritmo (HMAC) e nunca batia com a senha do painel. Log sem a senha, só o tamanho.
                Log.i("MaintenanceGate", "unlock_clicked len=${pwd.length} target=$target")
                val ok = runCatching {
                    com.mupa.agent.argos.managers.MaintenanceUnlock.validate(pwd, System.currentTimeMillis())
                }.getOrDefault(false)
                if (ok) {
                    Log.i("MaintenanceGate", "auth_success target=$target")
                    dismissRecoveryPasswordOverlay()
                    runMaintenanceTarget(target)
                } else {
                    Log.w("MaintenanceGate", "auth_failed")
                    error.text = "Senha ou código inválido."
                    error.visibility = View.VISIBLE
                    pin.setLength(0)
                    renderPin()
                }
            }
        }
        row.addView(cancel)
        row.addView(ok)

        fun addSpace(px: Int) {
            card.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, px) })
        }

        card.addView(title)
        addSpace((6f * density).toInt())
        card.addView(subtitle)
        addSpace((6f * density).toInt())
        card.addView(genInfo)
        addSpace(spacing)
        card.addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addSpace((8f * density).toInt())
        card.addView(keypad, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addSpace((8f * density).toInt())
        card.addView(error)
        addSpace(spacing)
        card.addView(row)

        val cardParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.CENTER
            leftMargin = (18f * density).toInt()
            rightMargin = (18f * density).toInt()
        }
        root.addView(card, cardParams)
        root.setOnClickListener { }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
        }
        try {
            wm.addView(root, params)
            overlayDialogView = root
            // Foco na primeira tecla do teclado próprio — nunca no campo de texto, para o IME
            // do sistema não ser invocado. Dá ao controle remoto um ponto de partida na grade.
            keypad.getChildAt(0)?.let { (it as? LinearLayout)?.getChildAt(0)?.requestFocus() }
        } catch (_: BadTokenException) {
            overlayDialogView = null
        }
    }

    private fun dismissRecoveryPasswordOverlay() {
        val view = overlayDialogView ?: return
        overlayDialogView = null
        mainHandler.post {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            runCatching { wm.removeView(view) }
        }
    }

    /**
     * Devices in kiosk/lock-task mode keep the pinned content app (MPlayer) forced back to
     * the foreground at the OS level — the agent's own Activity can never just "stay on
     * screen" there, no matter how autostart is delayed. Same TYPE_APPLICATION_OVERLAY
     * technique as showRecoveryPasswordOverlay() above is the only way to put the
     * registration wizard in front of a locked kiosk app. Shown/dismissed from the same
     * periodic sweep that already calls ensureFloatingRecoveryButton().
     */
    private fun ensureRegistrationOverlay() {
        val settings = SettingsManager(applicationContext)
        val needed = settings.getArgosDeviceTokenCached().isNotBlank() && settings.getDeviceApelidoInternoCached().isBlank()
        if (!needed) {
            dismissRegistrationOverlay()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(applicationContext)) return
        if (registrationOverlayView != null) return

        mainHandler.post {
            if (registrationOverlayView != null) return@post
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val density = resources.displayMetrics.density
            val pad = (16f * density).toInt()
            val cardPad = (16f * density).toInt()
            val spacing = (10f * density).toInt()

            val serial = runCatching {
                com.mupa.agent.argos.managers.DeviceIdentityManager.getPersistentDeviceId(applicationContext).trim()
            }.getOrDefault("")

            val root = FrameLayout(this).apply {
                setBackgroundColor(Color.parseColor("#CC000000"))
            }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(cardPad, cardPad, cardPad, cardPad)
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 16f * density
                    setColor(Color.parseColor("#FF0B1220"))
                    setStroke((1f * density).toInt().coerceAtLeast(1), Color.parseColor("#334155"))
                }
            }
            val title = TextView(this).apply {
                text = "Cadastro do terminal"
                setTextColor(Color.WHITE)
                textSize = 18f
            }
            val subtitle = TextView(this).apply {
                text = "Este aparelho ainda não foi nomeado. Preencha pra aparecer certo no ARGOS Web."
                setTextColor(Color.parseColor("#CBD5E1"))
                textSize = 13f
            }
            fun field(hintText: String) = EditText(this).apply {
                setTextColor(Color.WHITE)
                setHintTextColor(Color.parseColor("#94A3B8"))
                hint = hintText
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 12f * density
                    setColor(Color.parseColor("#141B2D"))
                    setStroke((1f * density).toInt().coerceAtLeast(1), Color.parseColor("#334155"))
                }
                setPadding(pad, pad, pad, pad)
            }
            val apelidoInput = field("Apelido (ex: Caixa 01)")
            val empresaInput = field("Empresa")
            val filialInput = field("Número da filial")
            val error = TextView(this).apply {
                text = ""
                setTextColor(Color.parseColor("#FCA5A5"))
                textSize = 13f
                visibility = View.GONE
            }
            val save = Button(this).apply {
                text = "Salvar"
                setOnClickListener {
                    val apelido = apelidoInput.text?.toString()?.trim().orEmpty()
                    if (apelido.isBlank()) {
                        error.text = "Informe pelo menos o apelido."
                        error.visibility = View.VISIBLE
                        return@setOnClickListener
                    }
                    isEnabled = false
                    scope.launch(Dispatchers.IO) {
                        val result = com.mupa.agent.argos.network.AgentControlApi(applicationContext, settings).registerDevice(
                            serial = serial,
                            apelidoInterno = apelido,
                            empresa = empresaInput.text?.toString()?.trim().orEmpty(),
                            numFilial = filialInput.text?.toString()?.trim().orEmpty(),
                        )
                        mainHandler.post {
                            if (result.ok) {
                                dismissRegistrationOverlay()
                            } else {
                                isEnabled = true
                                error.text = result.error ?: "Falha ao salvar."
                                error.visibility = View.VISIBLE
                            }
                        }
                    }
                }
            }

            fun addSpace(px: Int) {
                card.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, px) })
            }
            card.addView(title)
            addSpace((6f * density).toInt())
            card.addView(subtitle)
            addSpace(spacing)
            card.addView(apelidoInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addSpace((8f * density).toInt())
            card.addView(empresaInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addSpace((8f * density).toInt())
            card.addView(filialInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addSpace((8f * density).toInt())
            card.addView(error)
            addSpace(spacing)
            card.addView(save)

            val cardParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.CENTER
                leftMargin = (18f * density).toInt()
                rightMargin = (18f * density).toInt()
            }
            root.addView(card, cardParams)
            root.setOnClickListener { }

            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.CENTER
            }
            try {
                wm.addView(root, params)
                registrationOverlayView = root
                apelidoInput.requestFocus()
            } catch (_: BadTokenException) {
                registrationOverlayView = null
            }
        }
    }

    private fun dismissRegistrationOverlay() {
        val view = registrationOverlayView ?: return
        registrationOverlayView = null
        mainHandler.post {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            runCatching { wm.removeView(view) }
        }
    }

    private fun ensureGeofenceLockOverlay() {
        val settings = SettingsManager(applicationContext)
        val geofenceLocked = settings.isGeofenceLockedCached()
        val hoursLocked = settings.isOperatingHoursLockedCached()
        if (!geofenceLocked && !hoursLocked) {
            dismissGeofenceLockOverlay()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(applicationContext)) return
        if (geofenceLockOverlayView != null) return

        mainHandler.post {
            if (geofenceLockOverlayView != null) return@post
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val density = resources.displayMetrics.density
            val pad = (16f * density).toInt()
            val cardPad = (16f * density).toInt()
            val spacing = (10f * density).toInt()

            val fenceName = settings.getGeofenceLockedFenceNameCached().ifBlank { "Perímetro" }
            val lockReason = if (settings.isOperatingHoursLockedCached() && !settings.isGeofenceLockedCached()) {
                val start = settings.getOperatingHoursStartCached()
                val end = settings.getOperatingHoursEndCached()
                "Fora do horário de funcionamento ($start - $end)"
            } else {
                "Fora do perímetro: $fenceName"
            }

            val root = FrameLayout(this).apply {
                setBackgroundColor(Color.parseColor("#EE000000"))
            }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(cardPad, cardPad, cardPad, cardPad)
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 16f * density
                    setColor(Color.parseColor("#FF0B1220"))
                    setStroke((1f * density).toInt().coerceAtLeast(1), Color.parseColor("#334155"))
                }
            }

            val iconText = TextView(this).apply {
                text = "🔒"
                textSize = 36f
                gravity = Gravity.CENTER
            }
            val title = TextView(this).apply {
                text = "Dispositivo Bloqueado"
                setTextColor(Color.WHITE)
                textSize = 18f
                gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            val subtitle = TextView(this).apply {
                text = lockReason
                setTextColor(Color.parseColor("#CBD5E1"))
                textSize = 14f
                gravity = Gravity.CENTER
            }
            val input = EditText(this).apply {
                setTextColor(Color.WHITE)
                setHintTextColor(Color.parseColor("#94A3B8"))
                hint = "Senha de desbloqueio"
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 12f * density
                    setColor(Color.parseColor("#141B2D"))
                    setStroke((1f * density).toInt().coerceAtLeast(1), Color.parseColor("#334155"))
                }
                setPadding(pad, pad, pad, pad)
            }
            val error = TextView(this).apply {
                text = ""
                setTextColor(Color.parseColor("#FCA5A5"))
                textSize = 13f
                gravity = Gravity.CENTER
                visibility = View.GONE
            }
            val ok = Button(this).apply {
                text = "Desbloquear"
                setOnClickListener {
                    val typed = input.text?.toString().orEmpty()
                    val expected = settings.getGeofenceUnlockPasswordCached()
                    val masterOk = com.mupa.agent.argos.managers.MaintenanceUnlock.validate(typed)
                    if (typed == expected || masterOk) {
                        scope.launch(Dispatchers.IO) {
                            settings.setGeofenceLocked(false)
                            settings.setOperatingHoursLocked(false)
                        }
                        dismissGeofenceLockOverlay()
                    } else {
                        error.text = "Senha incorreta"
                        error.visibility = View.VISIBLE
                    }
                }
            }

            fun addSpace(px: Int) {
                card.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, px) })
            }
            card.addView(iconText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addSpace(spacing)
            card.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addSpace((4f * density).toInt())
            card.addView(subtitle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addSpace(spacing)
            card.addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addSpace((8f * density).toInt())
            card.addView(error, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addSpace(spacing)
            card.addView(ok, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

            val cardParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.CENTER
                leftMargin = (24f * density).toInt()
                rightMargin = (24f * density).toInt()
            }
            root.addView(card, cardParams)
            root.setOnClickListener { }

            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.CENTER
            }
            try {
                wm.addView(root, params)
                geofenceLockOverlayView = root
                input.requestFocus()
            } catch (_: BadTokenException) {
                geofenceLockOverlayView = null
            }
        }
    }

    private fun dismissGeofenceLockOverlay() {
        val view = geofenceLockOverlayView ?: return
        geofenceLockOverlayView = null
        mainHandler.post {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            runCatching { wm.removeView(view) }
        }
    }

    private fun ensureFirebaseConnectedListener() {
        if (!shouldUseLegacyRealtime() || !FirebaseRuntimeMode.allowFirebaseConnectivityTracking) {
            firebaseConnected = true
            return
        }
        if (firebaseInfoListener != null) return
        val ref = FirebaseDatabase.getInstance().getReference(".info/connected")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                firebaseConnected = snapshot.getValue(Boolean::class.java) == true
            }

            override fun onCancelled(error: DatabaseError) {
                firebaseConnected = false
            }
        }
        firebaseInfoListener = listener
        ref.addValueEventListener(listener)
    }

    private fun shouldUseLegacyRealtime(): Boolean {
        // Only Firebase runtime config is required. The realtime manager's legacy command
        // listener (m_argos/devices/{id}/commands) executes commands directly and reports
        // back to Firebase logs — it does NOT need argos_api_url. Devices enrolled via group
        // linking (TV boxes) never get an argos_api_url, so gating on the HTTP API here left
        // them unable to receive any Firebase command (terminal, reboot, WiFi push, etc.).
        return hasFirebaseRuntimeConfig()
    }

    private fun updateOfflineSnapshot() {
        val settings = SettingsManager(applicationContext)
        val now = System.currentTimeMillis()
        val internetOk = hasInternet(applicationContext)
        if (internetOk && now - offlineLastApiCheckMs > 30_000L) {
            offlineLastApiCheckMs = now
            offlineApiOk = checkApiQuick(settings.getArgosApiUrlCached())
        } else if (!internetOk) {
            offlineApiOk = false
        }
        // Quando o tracking de conectividade Firebase está desabilitado, firebaseConnected fica
        // permanentemente false — não há sinal confiável, então não penalizamos o estado com isso.
        val hasReliableFirebaseSignal = com.mupa.agent.argos.firebase.FirebaseRuntimeMode.allowFirebaseConnectivityTracking
        val firebaseOk = if (hasReliableFirebaseSignal) firebaseConnected else true
        val apiOk = if (settings.getArgosApiUrlCached().trim().isBlank()) true else offlineApiOk
        val state = when {
            !internetOk -> "offline"
            internetOk && firebaseOk && apiOk -> "online"
            else -> "unstable"
        }
        settings.applyNow {
            setOfflineSnapshot(
                state = state,
                internetOk = internetOk,
                firebaseOk = firebaseOk,
                apiOk = apiOk,
                nowMs = now,
            )
        }
    }

    private fun checkApiQuick(argosApiUrl: String): Boolean {
        val base = argosApiUrl.trim().trimEnd('/')
        if (base.isBlank()) return true
        val url = if (base.endsWith("/api", ignoreCase = true)) base else "$base/api"
        return runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = 2000
                readTimeout = 2000
                setRequestProperty("User-Agent", "ArgosAgent/1.0")
            }
            val code = conn.responseCode
            conn.disconnect()
            // Este probe responde "a API esta ALCANCAVEL?", nao "esta rota existe?". Qualquer
            // resposta HTTP bem formada ja prova que o servidor atendeu.
            //
            // O criterio anterior era `code in 200..399`, e a URL pedida e `{base}/api` — que
            // NAO e uma rota do Worker e responde 404. Resultado: `offlineApiOk` era false em
            // todo aparelho com API configurada, e o estado reportado ao painel ficava preso em
            // "unstable" com a API respondendo 200 nas rotas reais. Mesmo padrao do 204 tratado
            // como indisponibilidade (ver debug-config-204-fallback-firebase.md): uma resposta
            // legitima do servidor sendo lida como servidor fora do ar.
            //
            // 5xx continua contando como falha: ai o servidor respondeu, mas respondeu quebrado.
            code in 200..499
        }.getOrDefault(false)
    }

    private fun hasInternet(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    companion object {
        private const val CHANNEL_ID = "argos_agent"
        private const val NOTIFICATION_ID = 1101
        private const val PERSIST_CHECK_INTERVAL_MS = 10_000L

        /** Cadência enquanto a contagem regressiva está visível — ver persistMonitorJob. */
        private const val PERSIST_COUNTDOWN_INTERVAL_MS = 2_000L
        private const val MIN_LOOP_DELAY_MS = 1_000L
        private const val OPERATIONAL_SWEEP_INTERVAL_MS = 60_000L
        private const val PROVISIONING_SWEEP_INTERVAL_MS = 60_000L
        // Mesma ordem de grandeza do ProvisioningManager.MIN_INTERVAL_MS (10 min) — checagem de
        // "há versão nova?" não é urgente como heartbeat (60s), mas roda dentro do mesmo loop.
        private const val MPLAYER_AUTO_UPDATE_CHECK_INTERVAL_MS = 10 * 60_000L
        private const val HEARTBEAT_CHECK_INTERVAL_MS = 60_000L
        private const val FIREBASE_PRESENCE_INTERVAL_MS = 30_000L
        private const val COMMAND_POLL_INTERVAL_NORMAL_MS = 60_000L
        private const val COMMAND_POLL_INTERVAL_REMOTE_MS = 15_000L
        private const val COMMAND_POLL_INTERVAL_UPDATE_MS = 5_000L
        private const val COMMAND_POLL_INTERVAL_CRITICAL_MS = 1_000L
        // Teto absoluto da cadência crítica (ver computeCommandPollIntervalMs). 5 min é folga
        // suficiente para um comando legítimo de alta prioridade ser buscado, executado e
        // confirmado, e curto o bastante para um gatilho travado não virar custo.
        private const val CRITICAL_POLL_MAX_DURATION_MS = 5 * 60_000L
        private const val CRITICAL_POLL_CAP_LOG_INTERVAL_MS = 5 * 60_000L
        // Limites de alerta por hora, por destino. Acima disso o RequestMeter loga request_burst.
        // Referência: presence 30s + heartbeat 60s + sweep 60s => ~180 escritas/h de Firebase em
        // operação normal. 600 dá 3x de folga antes de considerar anômalo.
        private const val REQUEST_WARN_FIREBASE_PER_HOUR = 600L
        private const val REQUEST_WARN_API_PER_HOUR = 400L
        private const val REQUEST_WARN_SUPABASE_PER_HOUR = 200L

        // 720 req/h e o esperado do poll de 5s; a folga cobre heartbeat, sync e retries. Acima
        // disto ha requisicao a mais do que o desenho preve, e ai o alarme volta a significar
        // alguma coisa.
        private const val REQUEST_WARN_SUPABASE_FAST_POLL_PER_HOUR = 900L
        private const val COMMAND_POLL_INTERVAL_IDLE_MS = 120_000L

        // Cadencia do modo `supabase` do EventBusMode: sem push do RTDB, a latencia do comando
        // e este intervalo. 5s e o limite acordado para comando de dispositivo e de grupo.
        private const val COMMAND_POLL_INTERVAL_SUPABASE_BUS_MS = 5_000L
        private const val LONG_IDLE_THRESHOLD_MS = 30 * 60_000L
        private const val CRITICAL_COMMAND_PRIORITY = 4
        private const val ANYDESK_URL = "https://pub-51a41b32f55f47d180e51ed6679732af.r2.dev/anydesk.apk"
        private const val ANYDESK_PLUGIN_AD1_URL = "https://pub-51a41b32f55f47d180e51ed6679732af.r2.dev/adcontrol-ad1.apk"

        // SHA256 calculado manualmente por Antunes em 2026-08-04 a partir das URLs acima
        // (bucket R2 próprio da Mupa). Se o APK hospedado nessas URLs for atualizado,
        // este hash precisa ser recalculado, senão a instalação passa a falhar
        // silenciosamente em ensureRemoteSupportAppsInstalled().
        private const val ANYDESK_APK_SHA256 = "240d28f2bd6829f36826b0fed28fe65a86009005d7dc8567385393e7047b90a2"
        private const val ANYDESK_PLUGIN_AD1_APK_SHA256 = "39ad9b74ce06c5eaedfaf4421ab4d75067e2011ca12ca9c070aebc70d2f95233"
        const val ACTION_BOOT_COMPLETED_STARTUP = "com.mupa.agent.argos.action.BOOT_COMPLETED_STARTUP"
        const val ACTION_RECOVERY_OVERLAY_STATE = "com.mupa.agent.argos.action.RECOVERY_OVERLAY_STATE"
        const val EXTRA_RECOVERY_OVERLAY_ENABLED = "enabled"
        const val ACTION_FORCE_PRESENCE_SYNC = "com.mupa.agent.argos.action.FORCE_PRESENCE_SYNC"
        const val ACTION_SHOW_MAINTENANCE_GATE = "com.mupa.agent.argos.action.SHOW_MAINTENANCE_GATE"
        const val ACTION_RUN_MAINTENANCE_TARGET = "com.mupa.agent.argos.action.RUN_MAINTENANCE_TARGET"
        const val EXTRA_MAINTENANCE_TARGET = "maintenance_target"
    }
}
