package com.mupa.player.enterprise.ui

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.FirebaseApp
import com.google.firebase.database.FirebaseDatabase
import com.mupa.player.enterprise.bridge.AndroidBridge
import com.mupa.player.enterprise.databinding.ActivityAllowedAppsBinding
import com.mupa.player.enterprise.databinding.ItemAllowedAppBinding
import com.mupa.player.enterprise.managers.DeviceIdentityManager
import com.mupa.player.enterprise.managers.SettingsManager
import kotlinx.coroutines.launch

class AllowedAppsActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        val override = Configuration(newBase.resources.configuration).apply {
            fontScale = 0.85f
            densityDpi = (densityDpi * 1.15f).toInt().coerceIn(120, 640)
        }
        super.attachBaseContext(newBase.createConfigurationContext(override))
    }

    private lateinit var binding: ActivityAllowedAppsBinding
    private lateinit var settingsManager: SettingsManager
    private lateinit var adapter: AllowedAppsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyAlwaysOnScreen()
        settingsManager = SettingsManager(applicationContext)
        binding = ActivityAllowedAppsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val cachedId = DeviceIdentityManager(applicationContext).getCachedId().trim()
        binding.deviceIdWatermark.text = if (cachedId.isNotBlank()) "ID: $cachedId" else "ID: -"
        lifecycleScope.launch {
            val deviceId = DeviceIdentityManager(applicationContext).getPersistentId().trim()
            binding.deviceIdWatermark.text = "ID: $deviceId"
        }

        adapter = AllowedAppsAdapter { item, checked ->
            adapter.setChecked(item.packageName, checked)
        }
        binding.appsRecycler.layoutManager = LinearLayoutManager(this)
        binding.appsRecycler.adapter = adapter

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                adapter.setQuery(s?.toString().orEmpty())
            }
        })

        binding.saveBtn.setOnClickListener {
            lifecycleScope.launch {
                val selected = adapter.getChecked().toMutableSet().apply { add(packageName) }
                settingsManager.setAllowedPackages(selected)
                val deviceId = DeviceIdentityManager(applicationContext).getPersistentId().trim()
                pushToFirebase(deviceId, selected)
                AndroidBridge.showToast(this@AllowedAppsActivity, "Apps permitidos salvos")
                finish()
            }
        }

        lifecycleScope.launch {
            val checked = settingsManager.getAllowedPackagesCached().toMutableSet().apply { add(packageName) }
            adapter.submit(loadLaunchableApps(), checked, requiredPackage = packageName)
        }
    }

    override fun onResume() {
        super.onResume()
        applyAlwaysOnScreen()
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

    private fun loadLaunchableApps(): List<AllowedAppItem> {
        val pm = packageManager
        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .asSequence()
            .mapNotNull { appInfo ->
                val pkg = appInfo.packageName ?: return@mapNotNull null
                val intent = pm.getLaunchIntentForPackage(pkg) ?: return@mapNotNull null
                val label = pm.getApplicationLabel(appInfo)?.toString()?.trim().orEmpty()
                val icon = runCatching { pm.getApplicationIcon(appInfo) }.getOrNull()
                AllowedAppItem(
                    packageName = pkg,
                    label = if (label.isBlank()) pkg else label,
                    icon = icon,
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    private fun pushToFirebase(deviceId: String, selected: Set<String>) {
        if (deviceId.isBlank()) return
        val app = FirebaseApp.initializeApp(applicationContext) ?: return
        val db = FirebaseDatabase.getInstance(app)
        val ref = db.getReference("kiosk_policies").child(deviceId)
        val map = hashMapOf<String, Any?>(
            "kiosk_enabled" to (settingsManager.getKioskModeCached() || settingsManager.getMdmLockedCached()),
            "allowed_packages" to selected.toList(),
        )
        ref.updateChildren(map)
    }

    private data class AllowedAppItem(
        val packageName: String,
        val label: String,
        val icon: Drawable?,
    )

    private class AllowedAppsAdapter(
        private val onToggle: (AllowedAppItem, Boolean) -> Unit,
    ) : RecyclerView.Adapter<AllowedAppsAdapter.VH>() {
        private val allItems = ArrayList<AllowedAppItem>()
        private val visibleItems = ArrayList<AllowedAppItem>()
        private val checked = HashSet<String>()
        private var requiredPackage: String? = null
        private var query: String = ""

        fun submit(items: List<AllowedAppItem>, checkedPackages: Set<String>, requiredPackage: String) {
            allItems.clear()
            allItems.addAll(items)
            checked.clear()
            checked.addAll(checkedPackages)
            this.requiredPackage = requiredPackage
            applyFilter()
        }

        fun setQuery(value: String) {
            query = value.trim()
            applyFilter()
        }

        fun setChecked(packageName: String, enabled: Boolean) {
            if (packageName == requiredPackage) return
            if (enabled) checked.add(packageName) else checked.remove(packageName)
            notifyDataSetChanged()
        }

        fun getChecked(): Set<String> = checked.toSet()

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
            val binding = ItemAllowedAppBinding.inflate(inflater, parent, false)
            return VH(binding, onToggle)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = visibleItems[position]
            val required = item.packageName == requiredPackage
            holder.bind(item, checked.contains(item.packageName), required)
        }

        override fun getItemCount(): Int = visibleItems.size

        class VH(
            private val binding: ItemAllowedAppBinding,
            private val onToggle: (AllowedAppItem, Boolean) -> Unit,
        ) : RecyclerView.ViewHolder(binding.root) {
            fun bind(item: AllowedAppItem, isChecked: Boolean, required: Boolean) {
                binding.label.text = item.label
                binding.packageName.text = item.packageName
                binding.icon.setImageDrawable(item.icon)
                binding.check.setOnCheckedChangeListener(null)
                binding.check.isChecked = isChecked
                binding.check.isEnabled = !required
                binding.root.setOnClickListener {
                    if (required) return@setOnClickListener
                    val next = !binding.check.isChecked
                    binding.check.isChecked = next
                    onToggle(item, next)
                }
                binding.check.setOnCheckedChangeListener { _, checked ->
                    onToggle(item, checked)
                }
            }
        }
    }
}
