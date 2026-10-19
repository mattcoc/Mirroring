package com.example.mirroring

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlin.concurrent.thread

class SettingsActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var checkButton: Button
    private var release: Updater.Release? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        status = findViewById(R.id.updateStatus)
        progress = findViewById(R.id.progress)
        checkButton = findViewById(R.id.checkButton)
        findViewById<TextView>(R.id.version).text = "Versione installata: ${Updater.currentVersion(this)}"

        val prefs = prefs(this)
        findViewById<MaterialSwitch>(R.id.autoCheck).apply {
            isChecked = prefs.getBoolean(KEY_AUTO, true)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(KEY_AUTO, on).apply() }
        }
        findViewById<View>(R.id.githubButton).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Updater.PAGE)))
        }
        checkButton.setOnClickListener {
            val r = release
            if (r != null) download(r) else check()
        }
        if (intent.getBooleanExtra(EXTRA_CHECK, false)) check()
    }

    private fun check() {
        show("Controllo in corso…", indeterminate = true)
        checkButton.isEnabled = false
        thread {
            val result = runCatching { Updater.latest() }
            runOnUiThread {
                checkButton.isEnabled = true
                progress.visibility = View.GONE
                val r = result.getOrElse {
                    show("Impossibile controllare: ${it.message}")
                    return@runOnUiThread
                }
                if (r != null && Updater.isNewer(r.version, Updater.currentVersion(this))) {
                    release = r
                    show("Disponibile la versione ${r.version}" + if (r.notes.isNotBlank()) "\n\n${r.notes}" else "")
                    checkButton.text = "Scarica e installa ${r.version}"
                } else {
                    show("Hai già l'ultima versione")
                }
            }
        }
    }

    private fun download(r: Updater.Release) {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            show("Consenti a Mirroring di installare app, poi premi di nuovo il pulsante")
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            )
            return
        }
        checkButton.isEnabled = false
        show("Download in corso…", indeterminate = false)
        thread {
            val result = runCatching {
                val file = Updater.download(this, r.apkUrl) { p -> runOnUiThread { progress.setProgressCompat(p, true) } }
                Updater.install(this, file)
            }
            runOnUiThread {
                checkButton.isEnabled = true
                progress.visibility = View.GONE
                show(result.fold({ "Conferma l'installazione nella finestra di Android" }, { "Download non riuscito: ${it.message}" }))
            }
        }
    }

    private fun show(text: String, indeterminate: Boolean? = null) {
        status.visibility = View.VISIBLE
        status.text = text
        if (indeterminate != null) {
            progress.visibility = View.VISIBLE
            progress.isIndeterminate = indeterminate
        }
    }

    companion object {
        const val EXTRA_CHECK = "check"
        const val KEY_AUTO = "auto_update"
        fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    }
}
