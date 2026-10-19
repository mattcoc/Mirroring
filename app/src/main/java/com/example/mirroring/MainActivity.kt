package com.example.mirroring

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var hostStatus: TextView
    private lateinit var touchStatus: TextView
    private lateinit var touchButton: Button
    private lateinit var shareButton: Button
    private lateinit var searchText: TextView
    private lateinit var deviceList: LinearLayout
    private lateinit var myIp: TextView
    private lateinit var listener: Discovery.Listener
    private val devices = linkedMapOf<String, Pair<String, Long>>()
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getPreferences(MODE_PRIVATE) }

    private val refresh = object : Runnable {
        override fun run() {
            renderDevices()
            handler.postDelayed(this, 1000)
        }
    }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val data = r.data
            if (r.resultCode == RESULT_OK && data != null) {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, HostService::class.java)
                        .putExtra(HostService.EXTRA_CODE, r.resultCode)
                        .putExtra(HostService.EXTRA_DATA, data)
                )
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= 37) perms += "android.permission.ACCESS_LOCAL_NETWORK"
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 0)

        hostStatus = findViewById(R.id.hostStatus)
        touchStatus = findViewById(R.id.touchStatus)
        touchButton = findViewById(R.id.touchButton)
        shareButton = findViewById(R.id.shareButton)
        searchText = findViewById(R.id.searchText)
        deviceList = findViewById(R.id.deviceList)
        myIp = findViewById(R.id.myIp)

        touchButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        shareButton.setOnClickListener {
            if (HostService.status != null) {
                stopService(Intent(this, HostService::class.java))
            } else {
                val mpm = getSystemService(MediaProjectionManager::class.java)
                projectionLauncher.launch(mpm.createScreenCaptureIntent())
            }
        }
        findViewById<View>(R.id.manualButton).setOnClickListener { showManualDialog() }
        findViewById<View>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        if (savedInstanceState == null && SettingsActivity.prefs(this).getBoolean(SettingsActivity.KEY_AUTO, true)) {
            checkUpdate(root)
        }

        listener = Discovery.Listener(this) { ip, name ->
            handler.post {
                if (ip !in Protocol.localIps()) {
                    val isNew = ip !in devices
                    devices[ip] = name to SystemClock.elapsedRealtime()
                    if (isNew) renderDevices()
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        listener.stop()
        listener.start()
    }

    override fun onStart() {
        super.onStart()
        listener.start()
        HostService.onStatus = { renderHost() }
        renderHost()
        handler.post(refresh)
    }

    override fun onStop() {
        listener.stop()
        HostService.onStatus = null
        handler.removeCallbacks(refresh)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        val enabled = TouchService.isEnabled(this)
        touchStatus.text = if (enabled) "Touch remoto attivo" else "Touch remoto disattivato"
        touchButton.visibility = if (enabled) View.GONE else View.VISIBLE
    }

    private fun renderHost() {
        val s = HostService.status
        hostStatus.text = s ?: "Non attivo"
        shareButton.text = if (s == null) "Avvia condivisione" else "Interrompi condivisione"
    }

    private fun renderDevices() {
        val now = SystemClock.elapsedRealtime()
        devices.entries.removeAll { now - it.value.second > 4000 }
        deviceList.removeAllViews()
        for ((ip, value) in devices) {
            val item = layoutInflater.inflate(R.layout.item_device, deviceList, false)
            item.findViewById<TextView>(R.id.deviceName).text = value.first
            item.findViewById<TextView>(R.id.deviceIp).text = ip
            item.setOnClickListener { open(ip, value.first) }
            deviceList.addView(item)
        }
        searchText.text = if (devices.isEmpty()) {
            "Cerco dispositivi che condividono lo schermo…"
        } else {
            "Tocca un dispositivo per connetterti"
        }
        myIp.text = "Questo dispositivo: ${Protocol.localIps().joinToString().ifEmpty { "non connesso" }}"
    }

    private fun open(ip: String, name: String) {
        prefs.edit().putString("ip", ip).apply()
        startActivity(
            Intent(this, ViewerActivity::class.java)
                .putExtra(ViewerActivity.EXTRA_HOST, ip)
                .putExtra(ViewerActivity.EXTRA_NAME, name)
        )
    }

    private fun checkUpdate(anchor: View) {
        thread {
            val r = runCatching { Updater.latest() }.getOrNull() ?: return@thread
            if (!Updater.isNewer(r.version, Updater.currentVersion(this))) return@thread
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                Snackbar.make(anchor, "Disponibile la versione ${r.version}", Snackbar.LENGTH_LONG)
                    .setAction("Aggiorna") {
                        startActivity(
                            Intent(this, SettingsActivity::class.java)
                                .putExtra(SettingsActivity.EXTRA_CHECK, true)
                        )
                    }
                    .show()
            }
        }
    }

    private fun showManualDialog() {
        val pad = (24 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            hint = "Es. 192.168.1.20"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.getString("ip", ""))
        }
        val box = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Connetti tramite IP")
            .setView(box)
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Connetti") { _, _ ->
                val ip = input.text.toString().trim()
                if (ip.isNotEmpty()) open(ip, ip)
            }
            .show()
    }
}
