package com.mupa.player.enterprise.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.app.KeyguardManager
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.EditText
import android.text.method.PasswordTransformationMethod
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mupa.player.enterprise.bridge.AndroidBridge
import com.mupa.player.enterprise.databinding.ActivityLauncherBinding
import com.mupa.player.enterprise.databinding.ItemLauncherAppBinding
import com.mupa.player.enterprise.kiosk.KioskManager
import com.mupa.player.enterprise.managers.DeviceIdentityManager
import com.mupa.player.enterprise.managers.SettingsManager
import com.mupa.player.enterprise.services.FirebaseRealtimePolicyService
import kotlinx.coroutines.launch
import kotlin.math.abs

class LauncherActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        val override = Configuration(newBase.resources.configuration).apply {
            fontScale = 0.85f
            densityDpi = (densityDpi * 1.15f).toInt().coerceIn(120, 640)
        }
        super.attachBaseContext(newBase.createConfigurationContext(override))
    }

    private lateinit var binding: ActivityLauncherBinding
    private lateinit var settingsManager: SettingsManager
    private lateinit var kioskManager: KioskManager
    private var policyReceiver: BroadcastReceiver? = null
    private var backUnlockTapCount = 0
    private var backUnlockStartMs = 0L
    private var unlockDialogShowing = false

    private val uiHandler = Handler(Looper.getMainLooper())
    private var secretTwoFingerDown = false
    private var secretStartX = 0f
    private var secretStartY = 0f
    private var secretRunnable: Runnable? = null

    private var listMode = false
    private val adapter = AppsAdapter { item -> openItem(item) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsManager = SettingsManager(applicationContext)
        kioskManager = KioskManager(this, settingsManager)

        binding = ActivityLauncherBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val cachedId = DeviceIdentityManager(applicationContext).getCachedId().trim()
        binding.deviceIdWatermark.text = if (cachedId.isNotBlank()) "ID: $cachedId" else "ID: -"

        binding.appsRecycler.adapter = adapter
        applyLayoutMode()
        binding.modeToggle.setOnClickListener {
            listMode = !listMode
            binding.modeToggle.text = if (listMode) "Lista" else "Grade"
            applyLayoutMode()
        }

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                adapter.setQuery(s?.toString().orEmpty())
            }
        })

        binding.appsRecycler.setOnTouchListener { _, event ->
            handleSecretTwoFingerGesture(event)
            false
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (settingsManager.getMdmLockedCached() || settingsManager.getKioskModeCached()) {
                    kioskManager.hideSystemBars()
                    if (!unlockDialogShowing) handleBackUnlockTap()
                    return
                }
                finish()
            }
        })

        lifecycleScope.launch {
            val deviceId = DeviceIdentityManager(applicationContext).getPersistentId()
            binding.deviceIdWatermark.text = "ID: ${deviceId.trim()}"
            FirebaseRealtimePolicyService.start(applicationContext, deviceId)
            if (settingsManager.getAllowedPackagesCached().isEmpty()) {
                settingsManager.setAllowedPackages(
                    setOf(
                        packageName,
                        "com.android.settings",
                    ),
                )
            }
            reloadApps()
        }
    }

    override fun onResume() {
        super.onResume()
        applyWakeAndUnlock()
        applyAlwaysOnScreen()
        kioskManager.applyNow()
        reloadApps()
    }

    override fun onStart() {
        super.onStart()
        if (policyReceiver == null) {
            val filter = IntentFilter(FirebaseRealtimePolicyService.ACTION_KIOSK_POLICY_UPDATED)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    kioskManager.applyNow()
                    reloadApps()
                }
            }
            policyReceiver = receiver
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(receiver, filter)
            }
        }
    }

    override fun onStop() {
        val r = policyReceiver
        if (r != null) {
            runCatching { unregisterReceiver(r) }
            policyReceiver = null
        }
        super.onStop()
    }

    private fun applyLayoutMode() {
        val span = if (listMode) 1 else 2
        binding.appsRecycler.layoutManager = GridLayoutManager(this, span)
    }

    private fun applyAlwaysOnScreen() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val p = window.attributes
        p.screenBrightness = 1f
        window.attributes = p

        if (Settings.System.canWrite(this)) {
            runCatching {
                Settings.System.putInt(
                    contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                )
            }
            runCatching {
                Settings.System.putInt(
                    contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    255,
                )
            }
        }
    }

    private fun applyWakeAndUnlock() {
        val locked = settingsManager.getKioskModeCached() || settingsManager.getMdmLockedCached()
        if (!locked) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
            )
        }
        val km = getSystemService(KeyguardManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { km.requestDismissKeyguard(this, null) }
        }
    }

    private fun reloadApps() {
        val allowed = settingsManager.getAllowedPackagesCached()
        val items = ArrayList<LauncherItem>()
        items.add(
            LauncherItem(
                kind = LauncherItemKind.Player,
                packageName = packageName,
                label = "Consulta",
                icon = ContextCompat.getDrawable(this, com.mupa.player.enterprise.R.drawable.ic_mupa_logo),
            ),
        )

        val pm = packageManager
        val launchables = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .asSequence()
            .mapNotNull { appInfo ->
                val pkg = appInfo.packageName ?: return@mapNotNull null
                if (!allowed.contains(pkg)) return@mapNotNull null
                val intent = pm.getLaunchIntentForPackage(pkg) ?: return@mapNotNull null
                val label = pm.getApplicationLabel(appInfo)?.toString()?.trim().orEmpty()
                val icon = runCatching { pm.getApplicationIcon(appInfo) }.getOrNull()
                LauncherItem(
                    kind = LauncherItemKind.App,
                    packageName = pkg,
                    label = if (label.isBlank()) pkg else label,
                    icon = icon,
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()

        items.addAll(launchables)
        adapter.submit(items)
    }

    private fun openItem(item: LauncherItem) {
        when (item.kind) {
            LauncherItemKind.Player -> {
                startActivity(Intent(this, PlayerActivity::class.java))
            }
            LauncherItemKind.App -> {
                val intent = packageManager.getLaunchIntentForPackage(item.packageName) ?: return
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    private fun handleSecretTwoFingerGesture(event: MotionEvent) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop.toFloat()
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2 && !secretTwoFingerDown) {
                    secretTwoFingerDown = true
                    val x0 = event.getX(0)
                    val y0 = event.getY(0)
                    val x1 = event.getX(1)
                    val y1 = event.getY(1)
                    secretStartX = (x0 + x1) / 2f
                    secretStartY = (y0 + y1) / 2f

                    secretRunnable?.let { uiHandler.removeCallbacks(it) }
                    val r = Runnable {
                        if (secretTwoFingerDown) {
                            showUnlockDialog()
                        }
                    }
                    secretRunnable = r
                    uiHandler.postDelayed(r, SECRET_HOLD_MS)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!secretTwoFingerDown || event.pointerCount < 2) return
                val x0 = event.getX(0)
                val y0 = event.getY(0)
                val x1 = event.getX(1)
                val y1 = event.getY(1)
                val cx = (x0 + x1) / 2f
                val cy = (y0 + y1) / 2f
                val dx = abs(cx - secretStartX)
                val dy = abs(cy - secretStartY)
                if (dx > slop || dy > slop) {
                    cancelSecretGesture()
                }
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cancelSecretGesture()
        }
    }

    private fun cancelSecretGesture() {
        secretTwoFingerDown = false
        secretRunnable?.let { uiHandler.removeCallbacks(it) }
        secretRunnable = null
    }

    private fun showUnlockDialog() {
        if (unlockDialogShowing) return
        unlockDialogShowing = true
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
            isSingleLine = true
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Configurações")
            .setView(input)
            .setPositiveButton("OK") { dialog, _ ->
                val entered = input.text?.toString()?.trim().orEmpty()
                if (entered == PlayerActivity.UNLOCK_PASSWORD) {
                    dialog.dismiss()
                    startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_OPEN_SETTINGS_PANEL, true))
                } else {
                    AndroidBridge.showToast(this, "Senha incorreta")
                    dialog.dismiss()
                }
            }
            .setNegativeButton("Cancelar") { dialog, _ -> dialog.dismiss() }
            .create()
            .also { dlg ->
                dlg.setOnDismissListener { unlockDialogShowing = false }
                dlg.show()
            }
    }

    private fun handleBackUnlockTap() {
        val now = SystemClock.elapsedRealtime()
        if (now - backUnlockStartMs > BACK_UNLOCK_WINDOW_MS) {
            backUnlockStartMs = now
            backUnlockTapCount = 0
        }
        backUnlockTapCount += 1
        if (backUnlockTapCount >= 5) {
            backUnlockTapCount = 0
            backUnlockStartMs = 0L
            showUnlockDialog()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            if (settingsManager.getMdmLockedCached() || settingsManager.getKioskModeCached()) {
                kioskManager.hideSystemBars()
                if (!unlockDialogShowing) handleBackUnlockTap()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private data class LauncherItem(
        val kind: LauncherItemKind,
        val packageName: String,
        val label: String,
        val icon: Drawable?,
    )

    private enum class LauncherItemKind { Player, App }

    private class AppsAdapter(
        private val onClick: (LauncherItem) -> Unit,
    ) : RecyclerView.Adapter<AppsAdapter.VH>() {
        private val allItems = ArrayList<LauncherItem>()
        private val visibleItems = ArrayList<LauncherItem>()
        private var query: String = ""

        fun submit(items: List<LauncherItem>) {
            allItems.clear()
            allItems.addAll(items)
            applyFilter()
        }

        fun setQuery(value: String) {
            query = value.trim()
            applyFilter()
        }

        private fun applyFilter() {
            val q = query.lowercase()
            visibleItems.clear()
            if (q.isBlank()) {
                visibleItems.addAll(allItems)
            } else {
                visibleItems.addAll(allItems.filter { it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q) })
            }
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val inflater = android.view.LayoutInflater.from(parent.context)
            val binding = ItemLauncherAppBinding.inflate(inflater, parent, false)
            return VH(binding, onClick)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(visibleItems[position])
        }

        override fun getItemCount(): Int = visibleItems.size

        class VH(
            private val binding: ItemLauncherAppBinding,
            private val onClick: (LauncherItem) -> Unit,
        ) : RecyclerView.ViewHolder(binding.root) {
            fun bind(item: LauncherItem) {
                binding.label.text = item.label
                binding.icon.setImageDrawable(item.icon)
                binding.root.setOnClickListener { onClick(item) }
            }
        }
    }

    companion object {
        private const val SECRET_HOLD_MS = 3000L
        private const val BACK_UNLOCK_WINDOW_MS = 6000L
    }
}
