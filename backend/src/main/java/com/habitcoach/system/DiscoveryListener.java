package com.habitcoach.system;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

/**
 * LAN auto-discovery for local development ONLY — lets the mobile app find
 * this backend's current IP over Wi-Fi/hotspot without the user typing it
 * into ApiHostModal (see CLAUDE_CONTEXT.md's networking investigation).
 * Plain UDP broadcast/reply rather than mDNS: hotspot mode's multicast
 * forwarding is unreliable across Android/OEM Wi-Fi stacks, whereas plain
 * broadcast is load-bearing for DHCP itself and works far more
 * consistently in exactly the two topologies this needs to support (home
 * Wi-Fi and phone-hotspot-with-laptop-as-client).
 *
 * {@code @Profile("!prod")}: this must not run in a real deployment (e.g.
 * AWS) — set spring.profiles.active=prod there and this component is
 * skipped entirely. Scaffold-stage, same spirit as DiagnosticsController —
 * safe to delete once the backend has a real deployed home and LAN
 * discovery is no longer needed.
 */
@Component
@Profile("!prod")
public class DiscoveryListener {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryListener.class);

    static final int DISCOVERY_PORT = 18899;
    private static final String PROBE_MESSAGE = "HABITCOACH_DISCOVER_V1";
    private static final String REPLY_PREFIX = "HABITCOACH_REPLY_V1:";

    private final int apiPort;
    private volatile DatagramSocket socket;
    private volatile boolean shuttingDown = false;

    public DiscoveryListener(@Value("${server.port}") int apiPort) {
        this.apiPort = apiPort;
    }

    /** Started once the app is fully up, not at construction time, so it
     * never advertises readiness before the API it points at actually
     * exists. */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        Thread thread = new Thread(this::listenLoop, "habit-coach-discovery");
        thread.setDaemon(true);
        thread.start();
    }

    private void listenLoop() {
        try {
            socket = new DatagramSocket(DISCOVERY_PORT);
            log.info("LAN discovery listening on UDP port {}", DISCOVERY_PORT);
        } catch (IOException e) {
            log.warn("LAN discovery disabled: could not bind UDP port {} ({})", DISCOVERY_PORT, e.getMessage());
            return;
        }

        byte[] buffer = new byte[256];
        while (!shuttingDown) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
                String received = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                if (PROBE_MESSAGE.equals(received)) {
                    reply(packet.getAddress(), packet.getPort());
                }
            } catch (IOException e) {
                if (!shuttingDown) {
                    log.debug("LAN discovery: ignoring malformed/failed packet ({})", e.getMessage());
                }
            }
        }
    }

    private void reply(InetAddress toAddress, int toPort) {
        try {
            byte[] payload = (REPLY_PREFIX + apiPort).getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(payload, payload.length, toAddress, toPort));
            log.debug("LAN discovery: replied to {}:{}", toAddress.getHostAddress(), toPort);
        } catch (IOException e) {
            log.debug("LAN discovery: failed to reply to {} ({})", toAddress, e.getMessage());
        }
    }

    @PreDestroy
    public void stop() {
        shuttingDown = true;
        if (socket != null) {
            socket.close();
        }
    }
}
