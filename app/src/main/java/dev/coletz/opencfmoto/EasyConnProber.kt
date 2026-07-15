package dev.coletz.opencfmoto

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.Network
import android.net.wifi.WifiManager
import java.io.BufferedInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * EasyConn / Carbit PXC client for CFMoto MotoPlay.
 *
 * Topology (verified in cfmoto-tcp-v5.log): the PHONE is the SERVER.
 *  1. Discover the bike (gateway 192.168.0.1, EasyConn mDNS advertises :10930).
 *  2. Open TCP servers on 10920, 10921, 10922 bound to our bike-network IP.
 *  3. Connect once to bike:10930 and send ECP_PXC_MDNS_RESPOND (cmd 0x70000010, JSON);
 *     bike replies {"status":true} and we close that socket.
 *  4. The bike then connects BACK to our listening ports and drives the PXC handshake
 *     (channel selects, CLIENT_INFO, SN check, heartbeats) — handled by [PxcHandshake].
 */
class EasyConnProber(
    private val context: Context,
    private val log: (String) -> Unit,
) {
    companion object {
        const val PORT_MEDIA_DATA = 10920   // MediaProjectService data
        const val PORT_MEDIA_CTRL = 10921   // MediaProjectService ctrl
        const val PORT_PXC_CTRL   = 10922   // PXCService ctrl (channel selects + CLIENT_INFO)
        const val BIKE_PROBE_PORT = 10930   // bike's EasyConn mDNS/probe endpoint
        const val SPOOFED_PACKAGE = "com.cfmoto.cfmotointernational"
        private val LISTEN_PORTS = intArrayOf(PORT_PXC_CTRL, PORT_MEDIA_CTRL, PORT_MEDIA_DATA)
    }

    private val handshake = PxcHandshake(log)
    private val servers = ArrayList<ServerSocket>()
    private var multicastLock: WifiManager.MulticastLock? = null
    private var heartbeatThread: Thread? = null
    // Open PXC control (10922) sockets. Some bikes (e.g. CL-C450, sdk 0.9.23.4) never send
    // heartbeats and drop the whole session if the control socket sees no traffic for ~7s. We
    // proactively send 0x70000000 heartbeats on every open control socket to keep it alive.
    private val ctrlSockets = java.util.concurrent.CopyOnWriteArrayList<Socket>()
    private var ctrlHeartbeatThread: Thread? = null
    @Volatile private var running = false
    @Volatile private var probed = false
    @Volatile private var video: VideoPipeline? = null
    @Volatile private var ownsVideo = false
    // Mirror-mode capture size; defaults to the selected bike model's panel, overwritten by
    // the bike's own REQ_RV_CONFIG_CAPTURE during the media handshake.
    @Volatile private var negW = BikeConfig.model.bikeWidth
    @Volatile private var negH = BikeConfig.model.bikeHeight
    @Volatile private var framesSent = 0

    /**
     * @param network the bike Wi-Fi network for an AP (infrastructure) join, or null for Wi-Fi
     *   Direct (P2P) where there is no such [Network] object.
     * @param bindIpOverride when non-null (P2P path), the phone's P2P-interface IPv4 to bind our
     *   server/probe sockets to, instead of deriving it from [network]. On a 192.168.49.0/24 P2P
     *   link this is enough to route over the P2P interface without `bindProcessToNetwork`.
     * @param gatewayOverride when non-null (P2P path), the bike's address (the Group Owner,
     *   typically 192.168.49.1), instead of deriving it from [network]'s routes.
     */
    fun start(
        network: Network?,
        bindIpOverride: Inet4Address? = null,
        gatewayOverride: Inet4Address? = null,
    ) {
        if (running) { log("already running"); return }
        dumpEnvironment(network)

        val myIp = bindIpOverride ?: pickBikeInterfaceIp(network)
        if (myIp == null) { log("could not resolve our IPv4 on the bike network; aborting"); return }
        val bikeIp = gatewayOverride ?: resolveGateway(network) ?: deriveGatewayFromSubnet(myIp)
        if (bikeIp == null) { log("could not resolve bike gateway IP; aborting"); return }
        log("our IP=${myIp.hostAddress}  bike IP=${bikeIp.hostAddress}" +
            if (bindIpOverride != null) "  (Wi-Fi Direct / P2P)" else "")

        running = true
        acquireMulticastLock()

        // 1. Listen on all three ports BEFORE probing, so we're ready for the bike's call-back.
        for (port in LISTEN_PORTS) {
            try {
                val ss = ServerSocket(port, 50, myIp)
                servers.add(ss)
                spawnAccept(port, ss)
            } catch (e: Exception) {
                log("bind :$port failed: ${e.message}")
            }
        }
        log("listening on ${myIp.hostAddress} ports ${LISTEN_PORTS.toList()}")

        // 2. Send the probe (gives the bike our IP → it connects back).
        thread(name = "ec-probe", isDaemon = true) {
            sendMdnsRespond(bikeIp, myIp, network)
        }
        startHeartbeatLog()
        startCtrlHeartbeats()
    }

    /**
     * Keep the PXC control socket(s) alive by sending 0x70000000 heartbeats every 2s. Bikes that
     * don't emit their own heartbeats (CL-C450) drop the whole session after ~7s of control-socket
     * silence even while media frames keep flowing on 10920. Writes are frame-atomic (PxcFrame.write
     * synchronizes on the stream) so they can't corrupt a concurrent handshake reply.
     */
    private fun startCtrlHeartbeats() {
        ctrlHeartbeatThread = thread(name = "ec-ctrl-hb", isDaemon = true) {
            while (running) {
                try { Thread.sleep(2000) } catch (_: InterruptedException) { break }
                for (s in ctrlSockets) {
                    try { PxcFrame(PxcFrame.CMD_HEARTBEAT, ByteArray(0)).write(s.getOutputStream()) }
                    catch (_: Exception) { /* socket closing; readLoop's finally removes it */ }
                }
            }
        }
    }

    fun stop() {
        running = false
        // Only stop the pipeline if we created it; the shared Android Auto pipeline is owned
        // by AndroidAutoService and must outlive a bike disconnect.
        if (ownsVideo) video?.stop()
        video = null; ownsVideo = false
        heartbeatThread?.interrupt(); heartbeatThread = null
        ctrlHeartbeatThread?.interrupt(); ctrlHeartbeatThread = null
        ctrlSockets.clear()
        for (s in servers) try { s.close() } catch (_: IOException) {}
        servers.clear()
        multicastLock?.let { try { if (it.isHeld) it.release() } catch (_: Exception) {} }
        multicastLock = null
        log("stopped")
    }

    /** Step 3: phone→bike probe. cmd 0x70000010 + JSON; expect 0x70000011 {"status":true}. */
    private fun sendMdnsRespond(bikeIp: Inet4Address, myIp: Inet4Address, network: Network?) {
        var attempt = 0
        while (running && attempt < 5 && !probed) {
            attempt++
            try {
                log("[PROBE] connect #$attempt -> ${bikeIp.hostAddress}:$BIKE_PROBE_PORT")
                val sock = Socket()
                try { sock.bind(InetSocketAddress(myIp, 0)) } catch (_: Exception) {}
                network?.let { try { it.bindSocket(sock) } catch (_: Exception) {} }
                sock.connect(InetSocketAddress(bikeIp, BIKE_PROBE_PORT), 3000)
                sock.soTimeout = 5000

                val json = JSONProbe()
                log("[PROBE] -> MDNS_RESPOND (0x70000010) $json")
                PxcFrame(PxcFrame.CMD_MDNS_RESPOND, json.toByteArray(Charsets.UTF_8))
                    .write(sock.getOutputStream())

                val resp = PxcFrame.read(sock.getInputStream())
                if (resp == null) {
                    log("[PROBE] bike closed before responding")
                } else {
                    val body = String(resp.payload, Charsets.UTF_8)
                    log("[PROBE] <- cmd=0x${resp.cmd.toUInt().toString(16)} $body")
                    if (resp.cmd == PxcFrame.CMD_MDNS_RESPOND_ACK && body.contains("true")) {
                        log("[PROBE] *** accepted — bike should now connect back to our ports ***")
                        probed = true
                    } else {
                        log("[PROBE] !! not accepted: $body")
                    }
                }
                try { sock.close() } catch (_: IOException) {}
                if (probed) return
            } catch (e: Exception) {
                log("[PROBE] failed: ${e.javaClass.simpleName}: ${e.message}")
            }
            try { Thread.sleep(2000) } catch (_: InterruptedException) { return }
        }
    }

    private fun JSONProbe(): String =
        "{\"phoneType\":\"Android\",\"packageName\":\"$SPOOFED_PACKAGE\"}"

    private fun spawnAccept(port: Int, server: ServerSocket) =
        thread(name = "ec-accept-$port", isDaemon = true) {
            while (running) {
                val client = try { server.accept() } catch (e: IOException) {
                    if (running) log("[:$port] accept ended: ${e.message}"); break
                }
                log("[:$port] <<< bike connected from ${client.remoteSocketAddress}")
                thread(name = "ec-conn-$port", isDaemon = true) { readLoop(port, client) }
            }
        }

    private fun readLoop(port: Int, socket: Socket) {
        val tag = ":$port"
        socket.soTimeout = 0
        socket.tcpNoDelay = true
        try {
            val input = BufferedInputStream(socket.getInputStream())
            // Framing is by port (consistent across every run):
            //   10922 = PXC control (16-byte CmdBaseHead); 10921/10920 = media (8-byte ReqBase).
            if (port == PORT_PXC_CTRL) {
                log("[$tag] framing=CmdBaseHead (PXC control)")
                ctrlSockets.add(socket)   // keep this socket warm via ctrlHeartbeatThread
                while (running) {
                    val frame = try { PxcFrame.read(input) } catch (e: Exception) {
                        log("[$tag] frame error: ${e.message}"); return
                    } ?: run { log("[$tag] bike closed"); return }
                    try { handshake.handle(tag, frame, socket) }
                    catch (e: Exception) { log("[$tag] handler error: $e") }
                }
            } else {
                log("[$tag] framing=ReqBase (media plane)")
                mediaLoop(tag, input, socket.getOutputStream())
            }
        } catch (e: IOException) {
            log("[$tag] read error: ${e.message}")
        } finally {
            ctrlSockets.remove(socket)
            try { socket.close() } catch (_: IOException) {}
        }
    }

    // ---- Media plane: Protocol.ReqBase framing (8-byte LE header + body) ----
    // header: cmdType(s16) | cmdLen(s16) | token(i32); reply uses the same header.
    private fun mediaLoop(tag: String, input: java.io.InputStream, out: OutputStream) {
        val header = ByteArray(8)
        while (running) {
            if (!PxcFrame.readFully(input, header, 8)) { log("[$tag] media closed"); return }
            val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val cmdType = b.getShort(0).toInt()
            val cmdLen = b.getShort(2).toInt() and 0xFFFF
            val token = b.getInt(4)
            val body = ByteArray(cmdLen)
            if (cmdLen > 0 && !PxcFrame.readFully(input, body, cmdLen)) { log("[$tag] media body short"); return }
            handleMediaReq(tag, cmdType, token, body, out)
        }
    }

    /** Frame reply on the data socket is written RAW (not ReqBase): [size i32 LE][access unit].
     *  Inferred from the partial-decompiled MediaProjectServerDataExecuteThread.reply*Data(). */
    private fun sendFrameRaw(out: OutputStream, frame: ByteArray) {
        val sz = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0, frame.size).array()
        synchronized(out) {
            out.write(sz)
            out.write(frame)
            out.flush()
        }
    }

    private fun sendReqBase(out: OutputStream, cmdType: Int, body: ByteArray?) {
        val len = body?.size ?: 0
        val h = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        h.putShort(0, cmdType.toShort())
        h.putShort(2, len.toShort())
        h.putInt(4, 0)
        synchronized(out) {
            out.write(h.array())
            if (body != null && body.isNotEmpty()) out.write(body)
            out.flush()
        }
    }

    private fun handleMediaReq(tag: String, cmdType: Int, token: Int, body: ByteArray, out: OutputStream) {
        when (cmdType) {
            16 -> { // REQ_RV_CONFIG_CAPTURE
                val cfg = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
                val w = if (body.size >= 2) cfg.getShort(0).toInt() and 0xFFFF else 0
                val h = if (body.size >= 4) cfg.getShort(2).toInt() and 0xFFFF else 0
                val fps = if (body.size >= 8) cfg.getInt(4) else 0
                val wantEncoder = if (body.size >= 12) cfg.getInt(8) else 2
                val supportExtend = if (body.size >= 30) body[29] else 0
                log("[$tag] REQ_CONFIG_CAPTURE w=$w h=$h fps=$fps wantEncoder=$wantEncoder ext=$supportExtend len=${body.size}")
                logBikeReport(w, h, fps)
                negW = w and 0xFFF0
                negH = h and 0xFFF0
                // RLY_RV_CONFIG_CAPTURE (17): encoder(i32) | width&~15(s16) | height&~15(s16) | ext(byte)
                val rly = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
                rly.putInt(0, if (wantEncoder == 0) 2 else wantEncoder)
                rly.putShort(4, negW.toShort())
                rly.putShort(6, negH.toShort())
                rly.put(8, supportExtend)
                log("[$tag] → RLY_CONFIG_CAPTURE(17) encoder=${if (wantEncoder==0) 2 else wantEncoder} w=$negW h=$negH")
                sendReqBase(out, 17, rly.array())
            }
            48 -> { // REQ_GET_VERSION → 49 (two LE ints: version, subVersion=1) per ctrl thread
                log("[$tag] REQ_GET_VERSION → RLY 49")
                val v = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                v.putInt(0, 3); v.putInt(4, 1)
                sendReqBase(out, 49, v.array())
            }
            64 -> { // REQ_HEARTBEAT → 65
                sendReqBase(out, 65, null)
            }
            96 -> { // REQ_CONFIGCAPTUREREXTEND → 97 (JSON). Send state OK.
                log("[$tag] REQ_CONFIGCAPTUREREXTEND len=${body.size} ${String(body, Charsets.UTF_8).take(120)} → RLY 97")
                sendReqBase(out, 97, "{\"state\":0}".toByteArray(Charsets.UTF_8))
            }
            128 -> { // REQ_START_H264 → 129 (then bike expects frames on data socket)
                log("[$tag] *** REQ_START_H264 *** len=${body.size} → RLY 129 (no encoder yet — video stage TODO)")
                sendReqBase(out, 129, null)
            }
            112 -> { // REQ_RV_DATA_START → start encoder, then RLY_RV_DATA_START(113)
                if (video == null) {
                    val shared = AaVideoBridge.pipeline
                    if (shared != null) {
                        // Android Auto is running: pull encoded frames from its (already started)
                        // pipeline instead of creating our own Presentation/mirror source.
                        video = shared
                        ownsVideo = false
                        log("[$tag] REQ_RV_DATA_START(112): using shared Android Auto video pipeline")
                    } else {
                        log("[$tag] REQ_RV_DATA_START(112): starting video ${negW}x${negH}")
                        video = VideoPipeline(context, negW, negH, log).also { it.start() }
                        ownsVideo = true
                    }
                }
                log("[$tag] → RLY 113")
                sendReqBase(out, 113, null)
            }
            114 -> { // REQ_RV_DATA_NEXT — bike pulling a frame (data socket); send one access unit raw
                val frame = video?.pollFrame(1500)
                if (frame == null) {
                    log("[$tag] REQ_RV_DATA_NEXT(114): no frame ready")
                } else {
                    sendFrameRaw(out, frame)
                    framesSent++
                    if (framesSent <= 5 || framesSent % 60 == 0)
                        log("[$tag] sent frame #$framesSent (${frame.size}b)")
                }
            }
            else -> {
                val preview = BleProtocol.bytesToHex(body.copyOf(minOf(32, body.size)))
                log("[$tag] media cmdType=$cmdType len=${body.size} $preview")
            }
        }
    }

    /**
     * One-line summary of what THIS bike actually is, for adding new models to [BikeModel]:
     * panel resolution from REQ_RV_CONFIG_CAPTURE plus the head-unit identity from the PXC
     * CLIENT_INFO JSON. Users hitting a wrong/stretched picture can share their log and this
     * line has everything needed to define the enum entry.
     */
    private fun logBikeReport(w: Int, h: Int, fps: Int) {
        val info = handshake.lastClientInfo
        val huName = info?.optString("HUName")?.ifEmpty { null } ?: "?"
        val channel = info?.optString("channel")?.ifEmpty { null } ?: "?"
        log("[BIKE-REPORT] *** panel=${w}x$h fps=$fps huName=$huName channel=$channel — " +
            "share this line (plus your bike model name) to get your bike supported ***")
        val selected = BikeConfig.model
        if (w != selected.bikeWidth || h != selected.bikeHeight) {
            log("[BIKE-REPORT] !! bike reports ${w}x$h but selected model is $selected — " +
                "video will be wrong; pick the right model in Settings (or report this line)")
        }
    }

    private fun resolveGateway(network: Network?): Inet4Address? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val target = network ?: cm.activeNetwork ?: return null
        val lp = cm.getLinkProperties(target) ?: return null
        for (r in lp.routes) {
            if (r.isDefaultRoute) {
                val gw = r.gateway
                if (gw is Inet4Address && !gw.isAnyLocalAddress) return gw
            }
        }
        return lp.dnsServers.filterIsInstance<Inet4Address>().firstOrNull()
    }

    /**
     * Fallback when the link exposes no default route and no DNS server — which is exactly the
     * case for a Wi-Fi Direct Group Owner (e.g. CL-C450: phone gets 192.168.49.122/24 on an
     * on-link-only route, bike GO is at 192.168.49.1). Derives the gateway as `.1` of our own /24.
     * This also matches the AP-mode bike (192.168.0.50 → 192.168.0.1), so it is a safe last resort;
     * it only runs after the route/DNS lookups above have already failed.
     */
    private fun deriveGatewayFromSubnet(myIp: Inet4Address): Inet4Address? {
        val octets = myIp.address
        if (octets.size != 4 || (octets[3].toInt() and 0xFF) == 1) return null  // we ARE .1 → can't derive
        octets[3] = 1
        return try {
            (java.net.InetAddress.getByAddress(octets) as? Inet4Address)?.also {
                log("[gw] no default route/DNS on this link; derived bike gateway ${it.hostAddress} from our subnet")
            }
        } catch (_: Exception) { null }
    }

    private fun pickBikeInterfaceIp(network: Network?): Inet4Address? {
        if (network != null) {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.getLinkProperties(network)?.linkAddresses
                ?.map(LinkAddress::getAddress)
                ?.filterIsInstance<Inet4Address>()
                ?.firstOrNull { !it.isLoopbackAddress }
                ?.let { return it }
        }
        for (nic in NetworkInterface.getNetworkInterfaces()) {
            if (!nic.isUp || nic.isLoopback) continue
            for (addr in nic.inetAddresses) {
                if (addr is Inet4Address && !addr.isLoopbackAddress) return addr
            }
        }
        return null
    }

    private fun acquireMulticastLock() {
        val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wm.createMulticastLock("opencfmoto").apply {
            setReferenceCounted(false); acquire()
        }
    }

    private fun startHeartbeatLog() {
        heartbeatThread = thread(name = "ec-hb", isDaemon = true) {
            var i = 0
            while (running) {
                try { Thread.sleep(5000) } catch (_: InterruptedException) { break }
                i++
                log("hb#$i probed=$probed video=${video != null} framesSent=$framesSent openServers=${servers.count { !it.isClosed }} ctrlSockets=${ctrlSockets.size}")
            }
        }
    }

    private fun dumpEnvironment(network: Network?) {
        log("---- environment ----")
        log("Build: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (network != null) {
            val lp = cm.getLinkProperties(network)
            log("linkProps iface=${lp?.interfaceName} addrs=${lp?.linkAddresses} routes=${lp?.routes}")
        }
        log("---------------------")
    }
}
