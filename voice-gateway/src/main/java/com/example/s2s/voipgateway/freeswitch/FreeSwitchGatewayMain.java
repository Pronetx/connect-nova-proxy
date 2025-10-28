package com.example.s2s.voipgateway.freeswitch;

import com.example.s2s.voipgateway.NovaMediaConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main class for running the FreeSWITCH Audio Gateway.
 *
 * This starts a TCP server on port 8085 that accepts audio connections
 * from FreeSWITCH mod_nova_sonic and bridges them to Nova Sonic.
 *
 * Usage:
 *   java -jar s2s-voip-gateway.jar
 *
 * Or:
 *   mvn exec:java -Dexec.mainClass=com.example.s2s.voipgateway.freeswitch.FreeSwitchGatewayMain
 */
public class FreeSwitchGatewayMain {
    private static final Logger LOG = LoggerFactory.getLogger(FreeSwitchGatewayMain.class);

    public static void main(String[] args) {
        LOG.info("Starting FreeSWITCH Nova Gateway...");

        // Create configuration
        NovaMediaConfig config = new NovaMediaConfig();
        LOG.info("Gateway configuration loaded");

        // Start audio server on port 8085
        FreeSwitchAudioServer server = new FreeSwitchAudioServer(config);
        server.start();

        LOG.info("FreeSWITCH Nova Gateway started successfully");
        LOG.info("Listening for audio connections from FreeSWITCH on port 8085");
        LOG.info("Press Ctrl+C to stop");

        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Shutdown signal received");
            server.shutdown();
        }));

        // Keep alive
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            LOG.error("Main thread interrupted", e);
        }
    }
}
