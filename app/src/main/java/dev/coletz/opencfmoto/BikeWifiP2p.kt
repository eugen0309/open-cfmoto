package dev.coletz.opencfmoto

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import kotlin.concurrent.thread

/**
 * Joins the bike over **Wi-Fi Direct (Wi-Fi P2P)** instead of an infrastructure AP.
 *
 * Some CFMoto dashboards (e.g. CL-C450) advertise **P2P** in the QR `action` bitmask (bit3)
 * and run the EasyConn head unit as a Wi-Fi Direct **Group Owner (GO)** rather than a normal
 * WPA2 access point. [BikeWifi] (which uses `WifiNetworkSpecifier`) cannot associate to a GO,
 * so those bikes need this path.
 *
 * Topology once the group forms (standard Android Wi-Fi Direct):
 *   - The bike is the **GO** at the fixed address **192.168.49.1** (this is our gateway/bike IP).
 *   - The phone is a **client** at **192.168.49.x** on a `p2p-*` interface.
 *   - We do NOT `bindProcessToNetwork` (there is no `ConnectivityManager.Network` for a P2P
 *     group the way there is for a specifier join). Instead the caller binds its sockets to the
 *     phone's P2P interface IP, which we hand back via [onConnected]. The 192.168.49.0/24 subnet
 *     is on-link, so binding the source IP is enough to route over the P2P interface. A bonus is
 *     that Android Auto / GMS keep using cellular for map tiles — no VPN, no route capture.
 *
 * ⚠️ UNVERIFIED AGAINST HARDWARE. The exact CL-C450 QR contents and P2P association method are
 * not yet confirmed. This helper logs verbosely (raw peers, group info, addresses) so a single
 * bike session reveals what actually happens and what — if anything — needs correcting. Follow
 * the project's "implement → one logged bike session → adjust" loop (see docs/00 and docs/01 §7).
 */
object BikeWifiP2p {

    private const val TAG = "[P2P]"
    private const val CONNECT_TIMEOUT_MS = 25_000L
    private const val IP_POLL_TIMEOUT_MS = 10_000L
    private const val IP_POLL_INTERVAL_MS = 500L

    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var appContext: Context? = null
    @Volatile private var active = false
    @Volatile private var connected = false
    @Volatile private var timeoutThread: Thread? = null

    /**
     * @param onConnected called with (phoneBindIp, bikeGatewayIp) once the group is formed and
     *   both addresses are known. Pass these straight to [EasyConnProber.start].
     */
    @SuppressLint("MissingPermission")
    fun connect(
        context: Context,
        qr: QrData,
        onConnected: (bindIp: Inet4Address, gatewayIp: Inet4Address) -> Unit,
        onFailed: (reason: String) -> Unit,
        log: (String) -> Unit,
    ) {
        stop(log) // clean any prior attempt
        active = true
        connected = false
        val ctx = context.applicationContext
        appContext = ctx

        val mgr = ctx.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (mgr == null) { onFailed("device has no Wi-Fi P2P service"); return }
        manager = mgr
        val chan = mgr.initialize(ctx, Looper.getMainLooper(), null)
        channel = chan

        log("$TAG starting Wi-Fi Direct connect")
        log("$TAG   qr ssid='${qr.ssid}' mac=${qr.mac} action=${qr.action} (p2p=${qr.supportsP2p} ap=${qr.supportsAp})")
        log("$TAG   expecting bike GO at 192.168.49.1; phone will be a P2P client")

        registerReceiver(ctx, mgr, chan, onConnected, onFailed, log)

        // Peer discovery is not strictly required to join by credentials, but it (a) is required
        // on some devices before connect() works and (b) surfaces the bike's real P2P device
        // name/address in the log, which is exactly what we need to see on the first session.
        mgr.discoverPeers(chan, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { log("$TAG discoverPeers: started") }
            override fun onFailure(reason: Int) {
                log("$TAG discoverPeers: failed (${reasonStr(reason)}) — continuing to direct connect anyway")
            }
        })

        // Attempt the credential-based join (join an existing group as a legacy client).
        attemptCredentialJoin(mgr, chan, qr, onFailed, log)

        startTimeout(onFailed, log)
    }

    @SuppressLint("MissingPermission")
    private fun attemptCredentialJoin(
        mgr: WifiP2pManager,
        chan: WifiP2pManager.Channel,
        qr: QrData,
        onFailed: (String) -> Unit,
        log: (String) -> Unit,
    ) {
        val config = try {
            // A Wi-Fi Direct group SSID must start with "DIRECT-". The QR ssid may or may not
            // already be in that form; if the builder rejects it we log and rely on the receiver
            // path (peer discovery) so the session still tells us the real group name.
            WifiP2pConfig.Builder()
                .setNetworkName(qr.ssid)
                .setPassphrase(qr.pwd)
                .enablePersistentMode(false)
                .build()
        } catch (e: RuntimeException) {
            // setNetworkName rejects non-"DIRECT-" names (IllegalArgumentException); build() can
            // also reject a bad passphrase length (IllegalStateException). Either way, log & bail.
            log("$TAG credential-join not usable: ${e.message}")
            log("$TAG   (Wi-Fi Direct group names must start with 'DIRECT-'. If the CL-C450 QR ssid " +
                "'${qr.ssid}' is not the raw P2P group name, share this log so we can map it.)")
            return
        }

        log("$TAG connect(): joining group name='${qr.ssid}' as legacy client …")
        mgr.connect(chan, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                log("$TAG connect(): request accepted — waiting for group to form")
            }
            override fun onFailure(reason: Int) {
                log("$TAG connect(): failed (${reasonStr(reason)})")
                if (reason != WifiP2pManager.BUSY) {
                    fail(onFailed, log, "connect() rejected: ${reasonStr(reason)}")
                }
            }
        })
    }

    private fun registerReceiver(
        ctx: Context,
        mgr: WifiP2pManager,
        chan: WifiP2pManager.Channel,
        onConnected: (Inet4Address, Inet4Address) -> Unit,
        onFailed: (String) -> Unit,
        log: (String) -> Unit,
    ) {
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        val rx = object : BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(c: Context, intent: Intent) {
                if (!active) return
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val enabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) ==
                            WifiP2pManager.WIFI_P2P_STATE_ENABLED
                        log("$TAG state: Wi-Fi P2P ${if (enabled) "ENABLED" else "DISABLED"}")
                        if (!enabled) fail(onFailed, log, "Wi-Fi P2P is disabled — enable Wi-Fi and retry")
                    }
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        mgr.requestPeers(chan) { peers ->
                            if (peers.deviceList.isEmpty()) {
                                log("$TAG peers: (none yet)")
                            } else {
                                for (d: WifiP2pDevice in peers.deviceList) {
                                    log("$TAG peer: name='${d.deviceName}' addr=${d.deviceAddress} " +
                                        "status=${deviceStatus(d.status)} isGO=${d.isGroupOwner}")
                                }
                            }
                        }
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        mgr.requestConnectionInfo(chan) { info ->
                            log("$TAG conn: groupFormed=${info.groupFormed} isGO=${info.isGroupOwner} " +
                                "goAddr=${info.groupOwnerAddress?.hostAddress}")
                            if (info.groupFormed && !connected) onGroupFormed(mgr, chan, info, onConnected, onFailed, log)
                        }
                    }
                }
            }
        }
        receiver = rx
        // Not exported: only the system broadcasts these actions to us.
        registerSystemReceiver(ctx, rx, filter)
    }

    @SuppressLint("MissingPermission")
    private fun onGroupFormed(
        mgr: WifiP2pManager,
        chan: WifiP2pManager.Channel,
        info: WifiP2pInfo,
        onConnected: (Inet4Address, Inet4Address) -> Unit,
        onFailed: (String) -> Unit,
        log: (String) -> Unit,
    ) {
        if (info.isGroupOwner) {
            // We should be the client, not the GO. If we became GO the bike won't connect back.
            log("$TAG !! WE became the Group Owner — the bike is expected to be the GO. " +
                "This usually means the join fell back to creating a new group. Share this log.")
        }
        val gateway = info.groupOwnerAddress as? Inet4Address
            ?: (info.groupOwnerAddress?.let { asInet4(it) })
            ?: run { fail(onFailed, log, "group formed but GO address is not IPv4"); return }

        mgr.requestGroupInfo(chan) { group ->
            val iface = group?.`interface`
            log("$TAG group: name='${group?.networkName}' owner='${group?.owner?.deviceName}' iface=$iface " +
                "clients=${group?.clientList?.size ?: 0}")
            // DHCP on the P2P link can lag the "group formed" event; poll for our local address.
            thread(name = "p2p-ip-poll", isDaemon = true) {
                val bindIp = pollLocalP2pIp(iface, log)
                if (bindIp == null) {
                    fail(onFailed, log, "group formed but could not resolve our 192.168.49.x address on $iface")
                    return@thread
                }
                if (!active || connected) return@thread
                connected = true
                cancelTimeout()
                log("$TAG *** connected: phone=${bindIp.hostAddress} bike(GO)=${gateway.hostAddress} — starting PXC ***")
                onConnected(bindIp, gateway)
            }
        }
    }

    /** Poll for our IPv4 on the P2P interface (falls back to any 192.168.49.x that is not the GO). */
    private fun pollLocalP2pIp(ifaceName: String?, log: (String) -> Unit): Inet4Address? {
        val deadline = System.currentTimeMillis() + IP_POLL_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline && active) {
            localP2pIpv4(ifaceName)?.let { return it }
            try { Thread.sleep(IP_POLL_INTERVAL_MS) } catch (_: InterruptedException) { return null }
        }
        return localP2pIpv4(ifaceName)
    }

    private fun localP2pIpv4(ifaceName: String?): Inet4Address? {
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                val nameMatches = ifaceName == null || ni.name == ifaceName || ni.name.startsWith("p2p")
                for (addr in ni.inetAddresses) {
                    if (addr !is Inet4Address || addr.isLoopbackAddress) continue
                    val host = addr.hostAddress ?: continue
                    // Prefer the exact P2P interface; otherwise accept the canonical P2P subnet.
                    if (ni.name == ifaceName) return addr
                    if (nameMatches && host.startsWith("192.168.49.") && host != "192.168.49.1") return addr
                }
            }
        } catch (e: Exception) {
            log("$TAG localP2pIpv4 error: ${e.message}")
        }
        return null
    }

    private fun startTimeout(onFailed: (String) -> Unit, log: (String) -> Unit) {
        cancelTimeout()
        timeoutThread = thread(name = "p2p-timeout", isDaemon = true) {
            try { Thread.sleep(CONNECT_TIMEOUT_MS) } catch (_: InterruptedException) { return@thread }
            if (active && !connected) {
                fail(onFailed, log, "no P2P group formed within ${CONNECT_TIMEOUT_MS / 1000}s")
            }
        }
    }

    private fun cancelTimeout() {
        timeoutThread?.interrupt()
        timeoutThread = null
    }

    private fun fail(onFailed: (String) -> Unit, log: (String) -> Unit, reason: String) {
        if (!active) return
        active = false
        cancelTimeout()
        log("$TAG FAILED: $reason")
        onFailed(reason)
    }

    fun stop(log: (String) -> Unit) {
        active = false
        connected = false
        cancelTimeout()
        val ctx = appContext
        receiver?.let { r -> if (ctx != null) try { ctx.unregisterReceiver(r) } catch (_: Exception) {} }
        receiver = null
        val mgr = manager
        val chan = channel
        if (mgr != null && chan != null) {
            try {
                mgr.removeGroup(chan, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { log("$TAG group removed") }
                    override fun onFailure(reason: Int) { /* no active group; ignore */ }
                })
            } catch (_: Exception) {}
        }
        manager = null
        channel = null
        appContext = null
    }

    private fun asInet4(a: InetAddress): Inet4Address? =
        (a as? Inet4Address) ?: try { InetAddress.getByName(a.hostAddress) as? Inet4Address } catch (_: Exception) { null }

    private fun reasonStr(reason: Int): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
        WifiP2pManager.ERROR -> "ERROR"
        WifiP2pManager.BUSY -> "BUSY"
        WifiP2pManager.NO_SERVICE_REQUESTS -> "NO_SERVICE_REQUESTS"
        else -> "reason=$reason"
    }

    private fun deviceStatus(status: Int): String = when (status) {
        WifiP2pDevice.CONNECTED -> "CONNECTED"
        WifiP2pDevice.INVITED -> "INVITED"
        WifiP2pDevice.FAILED -> "FAILED"
        WifiP2pDevice.AVAILABLE -> "AVAILABLE"
        WifiP2pDevice.UNAVAILABLE -> "UNAVAILABLE"
        else -> "status=$status"
    }

    /**
     * Register a receiver that only the system feeds. On API 33+ the exported flag is mandatory;
     * these P2P actions are system broadcasts, so NOT_EXPORTED is correct and safe.
     */
    private fun registerSystemReceiver(ctx: Context, rx: BroadcastReceiver, filter: IntentFilter) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(rx, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            ctx.registerReceiver(rx, filter)
        }
    }
}
