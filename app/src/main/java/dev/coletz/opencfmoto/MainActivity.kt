package dev.coletz.opencfmoto

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusHeadline: TextView
    private lateinit var statusSub: TextView
    private lateinit var activityLine: TextView
    private lateinit var primaryBtn: Button
    private lateinit var prober: EasyConnProber
    private var bleWakeUp: BleWakeUp? = null
    private val handler = Handler(Looper.getMainLooper())

    private val scanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val raw = result.data?.getStringExtra(QrScanActivity.RESULT_QR)
        if (result.resultCode != RESULT_OK || raw == null) {
            log("QR scan cancelled")
            return@registerForActivityResult
        }
        log("QR raw: $raw")
        val qr = QrData.parse(raw)
        if (qr == null) {
            log("QR parse FAILED — missing ssid/pwd?")
            Toast.makeText(this, "Invalid QR", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        log(
            "QR parsed: ssid=${qr.ssid} mac=${qr.mac} action=${qr.action} " +
                "(ap=${qr.supportsAp}, p2p=${qr.supportsP2p}) modelId=${qr.modelId} sn=${qr.sn}"
        )
        joinAndStart(qr)
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK || result.data == null) {
            log("screen-capture consent declined")
            return@registerForActivityResult
        }
        // FGS of type mediaProjection must be RUNNING before getMediaProjection() on API 34+.
        // startForegroundService is async, so poll the service's foreground flag (~every 100ms)
        // instead of guessing a fixed delay.
        ProjectionService.start(this)
        val code = result.resultCode
        val data = result.data!!
        val maxTries = 50  // 50 * 100ms = 5s ceiling
        val poll = object : Runnable {
            var tries = 0
            override fun run() {
                if (ProjectionService.isForeground) {
                    try {
                        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        ProjectionHolder.projection = mpm.getMediaProjection(code, data)
                        log("screen-capture armed (FGS up after ${tries * 100}ms) — now scan the QR")
                        scanLauncher.launch(Intent(this@MainActivity, QrScanActivity::class.java))
                    } catch (e: Exception) {
                        log("getMediaProjection failed: $e")
                        ProjectionService.stop(this@MainActivity)
                    }
                } else if (tries++ < maxTries) {
                    handler.postDelayed(this, 100)
                } else {
                    log("foreground service did not start within 5s — aborting mirror")
                    ProjectionService.stop(this@MainActivity)
                }
            }
        }
        handler.post(poll)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        val basePad = (20 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(basePad + b.left, basePad + b.top, basePad + b.right, basePad + b.bottom)
            insets
        }

        statusHeadline = findViewById(R.id.status_headline)
        statusSub = findViewById(R.id.status_sub)
        activityLine = findViewById(R.id.activity_line)
        primaryBtn = findViewById(R.id.btn_primary)

        BikeConfig.load(applicationContext)
        prober = EasyConnProber(applicationContext, ::log)

        // Android 13+: request notification permission up front so the mediaProjection
        // foreground-service notification can be posted (some setups gate the FGS on it).
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3,
            )
        }

        primaryBtn.setOnClickListener {
            if (AndroidAutoService.isRunning) doStop() else doStart()
        }
        findViewById<Button>(R.id.btn_settings).setOnClickListener { showSettingsDialog() }
        findViewById<Button>(R.id.btn_logs).setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }

        log("Ready. Tap Start Android Auto.")
    }

    override fun onResume() {
        super.onResume()
        // Reclaim the log stream (LogActivity may have held it) and surface the latest line as a
        // subtle activity ticker, so the home screen feels live without showing the full log.
        LogBus.listener = { line -> runOnUiThread { activityLine.text = line } }
        refreshUi()
    }

    override fun onPause() {
        super.onPause()
        LogBus.listener = null   // LogActivity (or nothing) takes over
    }

    /** Reflect the (settled) service run state on resume. */
    private fun refreshUi() = setRunningUi(AndroidAutoService.isRunning)

    /** Apply the run-state look explicitly (start/stop flip the UI before the service settles). */
    private fun setRunningUi(running: Boolean) {
        primaryBtn.text = if (running) "Stop" else "Start Android Auto"
        primaryBtn.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (running) R.color.stop_red else R.color.start_green)
        )
        statusSub.text = "${BikeConfig.model.displayName}  ·  ${BikeConfig.transport.displayName}"
        setHeadline(if (running) "Android Auto running" else "Not connected", running)
    }

    private fun setHeadline(text: String, active: Boolean) {
        statusHeadline.text = text
        statusHeadline.setTextColor(
            ContextCompat.getColor(this, if (active) R.color.start_green else R.color.status_idle)
        )
    }

    /** Start the Android Auto receiver; the bike QR scanner auto-opens once AA video is steady. */
    private fun doStart() {
        log("→ starting Android Auto receiver (loopback self-mode). Ensure Android Auto is installed & set up.")
        // Once AA video is flowing steadily, auto-open the bike QR scanner so the hand-off
        // doesn't depend on scanning in the right order. One-shot; runs on the UI thread.
        AaVideoBridge.onSteadyVideo = {
            runOnUiThread {
                AaVideoBridge.onSteadyVideo = null
                log("→ Android Auto video is live — opening bike QR scanner")
                setHeadline("Scan the bike QR…", true)
                ProjectionHolder.projection = null   // bike uses the AA pipeline, not mirror
                ensureLocationPermission()
                try {
                    scanLauncher.launch(Intent(this, QrScanActivity::class.java))
                } catch (e: Exception) {
                    log("auto-scan launch failed ($e) — tap Scan manually")
                }
            }
        }
        AndroidAutoService.start(this)
        // Trigger Google AA to project from the FOREGROUND activity (background-activity-launch
        // safe on Android 12+/15), after giving the service's :5288 server time to bind.
        handler.postDelayed({
            dev.coletz.opencfmoto.aa.AaSelfMode.trigger(this, log = ::log)
        }, 900)
        setRunningUi(true)
        setHeadline("Starting Android Auto…", true)   // more specific than setRunningUi's default
    }

    /** Stop everything: Android Auto receiver, bike PXC, projection, and leave the bike Wi-Fi. */
    private fun doStop() {
        log("→ stopping everything (Android Auto + bike)")
        AaVideoBridge.onSteadyVideo = null
        AndroidAutoService.stop(this)
        prober.stop()
        bleWakeUp?.stop()
        bleWakeUp = null
        ProjectionHolder.projection?.let { try { it.stop() } catch (_: Exception) {} }
        ProjectionHolder.projection = null
        ProjectionService.stop(this)
        BikeWifi.leave(this, ::log)
        BikeWifiP2p.stop(::log)
        setRunningUi(false)
    }

    override fun onDestroy() {
        LogBus.listener = null
        AaVideoBridge.onSteadyVideo = null
        prober.stop()
        bleWakeUp?.stop()
        bleWakeUp = null
        ProjectionHolder.projection?.let { try { it.stop() } catch (_: Exception) {} }
        ProjectionHolder.projection = null
        ProjectionService.stop(this)
        // NOTE: AndroidAutoService is intentionally NOT stopped here — it is a foreground service
        // meant to keep running when the phone is backgrounded/locked. Use "Stop Android Auto".
        BikeWifi.leave(this, ::log)
        BikeWifiP2p.stop(::log)
        super.onDestroy()
    }

    private fun joinAndStart(qr: QrData) {
        val useP2p = when (BikeConfig.transport) {
            Transport.AP -> false
            Transport.P2P -> true
            Transport.AUTO -> qr.supportsP2p && !qr.supportsAp
        }
        log("→ connection mode: ${BikeConfig.transport.displayName} → using ${if (useP2p) "Wi-Fi Direct (P2P)" else "Wi-Fi AP"}")
        if (useP2p) joinP2pAndStart(qr) else joinApAndStart(qr)
    }

    private fun joinApAndStart(qr: QrData) {
        BikeWifi.join(
            context = this,
            ssid = qr.ssid,
            psk = qr.pwd,
            onAvailable = {
                // BLE wake-up is NOT required for projection (confirmed via TCP capture) — go
                // straight to the PXC flow. runBleWakeUpThenProber() remains available if needed.
                log("→ Wi-Fi bound; starting EasyConn PXC flow …")
                try {
                    prober.start(BikeWifi.currentNetwork)
                } catch (e: Exception) {
                    log("prober start failed: $e")
                }
            },
            onLost = { log("bike network lost") },
            log = ::log,
        )
    }

    private fun joinP2pAndStart(qr: QrData) {
        if (!ensureP2pPermission()) {
            log("→ Wi-Fi Direct needs a permission; grant it and tap Scan again")
            return
        }
        BikeWifiP2p.connect(
            context = this,
            qr = qr,
            onConnected = { bindIp, gatewayIp ->
                log("→ P2P group up; starting EasyConn PXC flow (bind=${bindIp.hostAddress} bike=${gatewayIp.hostAddress}) …")
                try {
                    // No Network object for P2P: pass the explicit addresses so the prober binds
                    // its sockets to the P2P interface directly.
                    prober.start(network = null, bindIpOverride = bindIp, gatewayOverride = gatewayIp)
                } catch (e: Exception) {
                    log("prober start failed: $e")
                }
            },
            onFailed = { reason -> log("→ Wi-Fi Direct connect failed: $reason") },
            log = ::log,
        )
    }

    /**
     * Wi-Fi Direct needs NEARBY_WIFI_DEVICES on Android 13+ (else ACCESS_FINE_LOCATION). Returns
     * true if already granted; otherwise requests it and returns false (user re-taps Scan after).
     */
    private fun ensureP2pPermission(): Boolean {
        val perm = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.NEARBY_WIFI_DEVICES
        else
            Manifest.permission.ACCESS_FINE_LOCATION
        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) return true
        ActivityCompat.requestPermissions(this, arrayOf(perm), 4)
        return false
    }

    private fun runBleWakeUpThenProber() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                ), 2,
            )
            // The user will need to tap Scan again after granting; keeping it simple for PoC.
            return
        }
        bleWakeUp?.stop()
        bleWakeUp = BleWakeUp(
            context = this,
            log = ::log,
            onUnlocked = {
                log("→ BLE wake-up OK; starting EasyConn prober …")
                try {
                    prober.start(BikeWifi.currentNetwork)
                } catch (e: Exception) {
                    log("prober start failed: $e")
                }
            },
            onFailed = { reason ->
                log("BLE wake-up failed: $reason — TCP probe likely useless, starting anyway")
                try {
                    prober.start(BikeWifi.currentNetwork)
                } catch (e: Exception) {
                    log("prober start failed: $e")
                }
            },
        ).also { it.start() }
    }

    private fun showSettingsDialog() {
        val dpiLabel = BikeConfig.dpiOverride?.let { "$it (custom)" }
            ?: "${BikeConfig.model.densityDpi} (bike default)"
        val items = arrayOf(
            "Bike model: ${BikeConfig.model.displayName}",
            "Android Auto DPI: $dpiLabel",
            "Connection mode: ${BikeConfig.transport.displayName}",
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Settings")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showBikeModelDialog()
                    1 -> showDpiDialog()
                    2 -> showTransportDialog()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showTransportDialog() {
        val options = Transport.entries
        val labels = options.map { it.displayName }.toTypedArray()
        val current = options.indexOf(BikeConfig.transport)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Connection mode")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                BikeConfig.saveTransport(applicationContext, options[which])
                log("→ connection mode set: ${options[which].displayName}")
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showBikeModelDialog() {
        val models = BikeModel.entries
        val labels = models.map { "${it.displayName}  (${it.bikeWidth}x${it.bikeHeight})" }.toTypedArray()
        val current = models.indexOf(BikeConfig.model)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Bike model")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                val model = models[which]
                BikeConfig.save(applicationContext, model)
                log("→ bike model set: $model (effective dpi=${BikeConfig.effectiveDpi})")
                warnIfAaRunning()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDpiDialog() {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "Bike default: ${BikeConfig.model.densityDpi}"
            BikeConfig.dpiOverride?.let { setText(it.toString()) }
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Android Auto DPI")
            .setMessage(
                "Higher = bigger UI elements, lower = more content on screen. " +
                    "Allowed: ${BikeConfig.DPI_MIN}–${BikeConfig.DPI_MAX}."
            )
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val dpi = input.text.toString().toIntOrNull()
                if (dpi == null || dpi !in BikeConfig.DPI_MIN..BikeConfig.DPI_MAX) {
                    Toast.makeText(
                        this,
                        "DPI must be ${BikeConfig.DPI_MIN}–${BikeConfig.DPI_MAX}",
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@setPositiveButton
                }
                BikeConfig.saveDpiOverride(applicationContext, dpi)
                log("→ DPI override set: $dpi")
                warnIfAaRunning()
            }
            .setNeutralButton("Use bike default") { _, _ ->
                BikeConfig.saveDpiOverride(applicationContext, null)
                log("→ DPI override cleared — using bike default (${BikeConfig.model.densityDpi})")
                warnIfAaRunning()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun warnIfAaRunning() {
        if (AndroidAutoService.isRunning) {
            log("!! Android Auto is running — the new setting applies on the next Start")
            Toast.makeText(this, "Applies on next Start", Toast.LENGTH_SHORT).show()
        }
    }

    private fun ensureLocationPermission() {
        // Some OEMs require fine location to associate via WifiNetworkSpecifier.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1,
            )
        }
    }

    private fun log(msg: String) = LogBus.log(msg)
}
