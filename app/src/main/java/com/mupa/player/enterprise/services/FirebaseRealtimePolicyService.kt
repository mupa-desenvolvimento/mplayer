package com.mupa.player.enterprise.services

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.google.firebase.FirebaseApp
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.mupa.player.enterprise.managers.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object FirebaseRealtimePolicyService {
    private var ref: DatabaseReference? = null
    private var listener: ValueEventListener? = null
    private var currentDeviceId: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(context: Context, deviceId: String): Boolean {
        val id = deviceId.trim()
        if (id.isBlank()) return false
        if (id == currentDeviceId && ref != null && listener != null) return true

        stop()

        val app = FirebaseApp.initializeApp(context) ?: return false
        val db = FirebaseDatabase.getInstance(app)
        val r = db.getReference("kiosk_policies").child(id)
        val settings = SettingsManager(context.applicationContext)
        scope.launch {
            pushInstalledAppsInventory(
                context = context.applicationContext,
                db = db,
                deviceId = id,
            )
        }

        val l = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) return
                val kioskEnabled = snapshot.child("kiosk_enabled").getValue(Boolean::class.java) ?: false
                val allowed = parseAllowedPackages(snapshot.child("allowed_packages"))

                val finalAllowed = if (allowed.isEmpty()) {
                    setOf(context.packageName, "com.android.settings")
                } else {
                    allowed + context.packageName
                }

                scope.launch {
                    settings.setAllowedPackages(finalAllowed)
                    settings.setKioskMode(kioskEnabled)
                    settings.setMdmLocked(kioskEnabled)
                    notifyPolicyUpdated(context.applicationContext)
                }
            }

            override fun onCancelled(error: DatabaseError) = Unit
        }

        r.addValueEventListener(l)
        ref = r
        listener = l
        currentDeviceId = id
        return true
    }

    fun stop() {
        val r = ref
        val l = listener
        if (r != null && l != null) {
            runCatching { r.removeEventListener(l) }
        }
        ref = null
        listener = null
        currentDeviceId = null
    }

    private fun parseAllowedPackages(snapshot: DataSnapshot): Set<String> {
        if (!snapshot.exists()) return emptySet()
        val v = snapshot.value ?: return emptySet()
        return when (v) {
            is List<*> -> v.mapNotNull { it?.toString()?.trim() }.filter { it.isNotBlank() }.toSet()
            is Map<*, *> -> v.values.mapNotNull { it?.toString()?.trim() }.filter { it.isNotBlank() }.toSet()
            is String -> v.split(',').map { it.trim() }.filter { it.isNotBlank() }.toSet()
            else -> emptySet()
        }
    }

    private fun notifyPolicyUpdated(appContext: Context) {
        appContext.sendBroadcast(Intent(ACTION_KIOSK_POLICY_UPDATED).setPackage(appContext.packageName))
    }

    const val ACTION_KIOSK_POLICY_UPDATED = "com.mupa.player.enterprise.ACTION_KIOSK_POLICY_UPDATED"

    private fun pushInstalledAppsInventory(context: Context, db: FirebaseDatabase, deviceId: String) {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .asSequence()
            .mapNotNull { appInfo ->
                val pkg = appInfo.packageName ?: return@mapNotNull null
                if (pm.getLaunchIntentForPackage(pkg) == null) return@mapNotNull null
                val label = runCatching { pm.getApplicationLabel(appInfo).toString().trim() }.getOrNull().orEmpty()
                val pInfo = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull()
                val versionName = pInfo?.versionName.orEmpty()
                val versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) {
                    pInfo?.longVersionCode ?: 0L
                } else {
                    @Suppress("DEPRECATION")
                    (pInfo?.versionCode ?: 0).toLong()
                }
                mapOf(
                    "package" to pkg,
                    "label" to (if (label.isBlank()) pkg else label),
                    "versionName" to versionName,
                    "versionCode" to versionCode,
                    "updatedAt" to System.currentTimeMillis(),
                )
            }
            .sortedBy { (it["label"] as? String).orEmpty().lowercase() }
            .toList()

        val ref = db.getReference("device_inventory").child(deviceId)
        val payload = mapOf(
            "device_id" to deviceId,
            "updated_at" to System.currentTimeMillis(),
            "installed_apps" to apps,
        )
        runCatching { ref.updateChildren(payload) }
    }
}
