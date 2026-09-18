package com.habitcoach.mobile

import android.util.Log
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * Finds the Spring Boot backend's current LAN IP without the user typing
 * it into ApiHostModal (see CLAUDE_CONTEXT.md's networking investigation
 * and apiConfig.ts). Plain UDP broadcast/reply, matching
 * DiscoveryListener.java on the backend exactly (same port/message
 * strings, must stay in sync). Dev-only — never used against a deployed
 * backend, see apiConfig.ts.
 */
class DiscoveryModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {

    override fun getName() = "Discovery"

    /** Sends a broadcast probe on every usable network interface (not just
     * 255.255.255.255) and waits up to [timeoutMs] for a reply. Runs on a
     * plain background thread since this is a short one-shot operation. */
    @ReactMethod
    fun discoverHost(timeoutMs: Double, promise: Promise) {
        Thread {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket().apply { broadcast = true }
                Log.d(TAG, "socket bound to local port ${socket.localPort}, broadcast=${socket.broadcast}")
                val probe = PROBE_MESSAGE.toByteArray(Charsets.UTF_8)
                val targets = broadcastAddresses()
                Log.d(TAG, "sending probe to ${targets.size} address(es): $targets")
                for (address in targets) {
                    try {
                        socket.send(DatagramPacket(probe, probe.size, address, DISCOVERY_PORT))
                        Log.d(TAG, "sent probe to $address:$DISCOVERY_PORT")
                    } catch (e: Exception) {
                        Log.w(TAG, "failed to send probe to $address:$DISCOVERY_PORT", e)
                    }
                }

                val deadline = System.currentTimeMillis() + timeoutMs.toLong()
                val buffer = ByteArray(256)
                while (System.currentTimeMillis() < deadline) {
                    val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(1L)
                    socket.soTimeout = remaining.toInt()
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (e: SocketTimeoutException) {
                        Log.d(TAG, "timed out waiting for a reply")
                        break
                    }
                    val received = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    Log.d(TAG, "received \"$received\" from ${packet.address.hostAddress}")
                    if (received.startsWith(REPLY_PREFIX)) {
                        promise.resolve(packet.address.hostAddress)
                        return@Thread
                    }
                }
                promise.reject("discovery_timeout", "No backend responded within ${timeoutMs.toInt()}ms")
            } catch (e: Exception) {
                Log.e(TAG, "discovery failed", e)
                promise.reject("discovery_error", e)
            } finally {
                socket?.close()
            }
        }.start()
    }

    /** 255.255.255.255 (the "limited broadcast" address) plus each active
     * interface's own directed broadcast address — some Android/router
     * combinations (notably hotspot mode) handle one more reliably than
     * the other, so both are tried. */
    private fun broadcastAddresses(): List<InetAddress> {
        val addresses = mutableListOf(InetAddress.getByName("255.255.255.255"))
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                for (interfaceAddress in iface.interfaceAddresses) {
                    val broadcast = interfaceAddress.broadcast
                    if (broadcast != null && interfaceAddress.address is Inet4Address) {
                        addresses.add(broadcast)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "interface enumeration failed, using global broadcast only", e)
        }
        return addresses
    }

    companion object {
        private const val TAG = "DiscoveryModule"
        private const val DISCOVERY_PORT = 18899
        private const val PROBE_MESSAGE = "HABITCOACH_DISCOVER_V1"
        private const val REPLY_PREFIX = "HABITCOACH_REPLY_V1:"
    }
}
