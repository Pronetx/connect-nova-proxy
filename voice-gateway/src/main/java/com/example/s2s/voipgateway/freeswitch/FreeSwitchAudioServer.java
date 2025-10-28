package com.example.s2s.voipgateway.freeswitch;

import com.example.s2s.voipgateway.NovaMediaConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TCP server that accepts audio connections from FreeSWITCH mod_nova_sonic.
 *
 * This server listens on port 8085 for incoming TCP connections from FreeSWITCH.
 * When a call comes in, mod_nova_sonic connects and streams raw PCM audio.
 *
 * Architecture:
 *   FreeSWITCH (SIP/RTP) → mod_nova_sonic (TCP client) → FreeSwitchAudioServer (TCP server)
 *                                                         ↓
 *                                                      Nova Sonic (Bedrock)
 */
public class FreeSwitchAudioServer implements Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(FreeSwitchAudioServer.class);
    private static final int DEFAULT_PORT = 8085;

    private final int port;
    private final NovaMediaConfig mediaConfig;
    private final ExecutorService executorService;
    private ServerSocket serverSocket;
    private volatile boolean running;

    public FreeSwitchAudioServer(NovaMediaConfig mediaConfig) {
        this(DEFAULT_PORT, mediaConfig);
    }

    public FreeSwitchAudioServer(int port, NovaMediaConfig mediaConfig) {
        this.port = port;
        this.mediaConfig = mediaConfig;
        this.executorService = Executors.newCachedThreadPool();
        this.running = false;
    }

    @Override
    public void run() {
        try {
            serverSocket = new ServerSocket(port);
            running = true;
            LOG.info("FreeSWITCH Audio Server listening on port {}", port);
            LOG.info("Waiting for audio connections from mod_nova_sonic...");

            while (running) {
                try {
                    // Accept incoming connection from FreeSWITCH
                    Socket clientSocket = serverSocket.accept();
                    String clientAddress = clientSocket.getRemoteSocketAddress().toString();

                    LOG.info("Accepted audio connection from FreeSWITCH: {}", clientAddress);

                    // Handle this audio session in a separate thread
                    FreeSwitchAudioHandler handler = new FreeSwitchAudioHandler(
                            clientSocket,
                            mediaConfig
                    );

                    executorService.submit(handler);

                } catch (IOException e) {
                    if (running) {
                        LOG.error("Error accepting connection", e);
                    }
                }
            }

        } catch (IOException e) {
            LOG.error("Failed to start FreeSWITCH Audio Server on port {}", port, e);
        } finally {
            shutdown();
        }
    }

    /**
     * Starts the server in a new thread.
     */
    public void start() {
        Thread serverThread = new Thread(this, "FreeSWITCH-Audio-Server");
        serverThread.setDaemon(false);
        serverThread.start();
        LOG.info("FreeSWITCH Audio Server started on port {}", port);
    }

    /**
     * Stops the server and closes all connections.
     */
    public void shutdown() {
        LOG.info("Shutting down FreeSWITCH Audio Server...");
        running = false;

        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            LOG.error("Error closing server socket", e);
        }

        executorService.shutdown();
        LOG.info("FreeSWITCH Audio Server shutdown complete");
    }

    public static void main(String[] args) {
        // Standalone server for testing
        NovaMediaConfig config = new NovaMediaConfig();
        FreeSwitchAudioServer server = new FreeSwitchAudioServer(config);
        server.start();

        // Keep alive
        Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown));

        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            LOG.error("Server interrupted", e);
        }
    }
}
