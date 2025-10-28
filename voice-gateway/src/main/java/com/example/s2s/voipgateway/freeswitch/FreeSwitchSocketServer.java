package com.example.s2s.voipgateway.freeswitch;

import com.example.s2s.voipgateway.NovaMediaConfig;
import com.example.s2s.voipgateway.nova.NovaStreamerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * FreeSWITCH Event Socket outbound server.
 * Listens on port 9090 for connections from FreeSWITCH when a call is routed via the socket application.
 */
public class FreeSwitchSocketServer {
    private static final Logger LOG = LoggerFactory.getLogger(FreeSwitchSocketServer.class);
    private final int port;
    private final NovaMediaConfig mediaConfig;
    private final ExecutorService executor;
    private ServerSocket serverSocket;
    private volatile boolean running;

    public FreeSwitchSocketServer(int port, NovaMediaConfig mediaConfig) {
        this.port = port;
        this.mediaConfig = mediaConfig;
        this.executor = Executors.newCachedThreadPool();
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        running = true;
        LOG.info("FreeSWITCH Event Socket server listening on port {}", port);

        // Accept connections in a loop
        executor.submit(() -> {
            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    LOG.info("Accepted connection from {}", clientSocket.getRemoteSocketAddress());

                    // Handle each call in a separate thread
                    executor.submit(new FreeSwitchCallHandler(clientSocket, mediaConfig));
                } catch (IOException e) {
                    if (running) {
                        LOG.error("Error accepting connection", e);
                    }
                }
            }
        });
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            LOG.error("Error closing server socket", e);
        }
        executor.shutdown();
    }
}
