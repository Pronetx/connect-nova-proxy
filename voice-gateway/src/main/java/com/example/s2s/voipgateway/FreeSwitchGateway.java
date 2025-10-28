package com.example.s2s.voipgateway;

import com.example.s2s.voipgateway.freeswitch.FreeSwitchSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main entry point for FreeSWITCH-based gateway.
 * Replaces mjSIP with FreeSWITCH Event Socket integration.
 */
public class FreeSwitchGateway {
    private static final Logger LOG = LoggerFactory.getLogger(FreeSwitchGateway.class);

    public static void main(String[] args) {
        LOG.info("Starting FreeSWITCH Voice Gateway for Nova Sonic...");

        try {
            // Create media configuration
            NovaMediaConfig mediaConfig = new NovaMediaConfig();

            // Set Nova voice and prompt from environment or use defaults from NovaMediaConfig
            String novaVoiceId = System.getenv("NOVA_VOICE_ID");
            if (novaVoiceId != null) {
                mediaConfig.setNovaVoiceId(novaVoiceId);
            }

            String novaPrompt = System.getenv("NOVA_PROMPT");
            if (novaPrompt != null) {
                mediaConfig.setNovaPrompt(novaPrompt);
            }

            LOG.info("Nova Voice ID: {}", mediaConfig.getNovaVoiceId());
            String prompt = mediaConfig.getNovaPrompt();
            LOG.info("Nova Prompt: {}", prompt.substring(0, Math.min(100, prompt.length())) + "...");

            // Start Event Socket server on port 9090
            int port = Integer.parseInt(System.getenv().getOrDefault("SOCKET_PORT", "9090"));
            FreeSwitchSocketServer server = new FreeSwitchSocketServer(port, mediaConfig);
            server.start();

            LOG.info("Gateway ready - waiting for calls from FreeSWITCH...");

            // Keep the application running
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                LOG.info("Shutting down...");
                server.stop();
            }));

            // Block forever
            Thread.currentThread().join();

        } catch (Exception e) {
            LOG.error("Fatal error starting gateway", e);
            System.exit(1);
        }
    }
}
