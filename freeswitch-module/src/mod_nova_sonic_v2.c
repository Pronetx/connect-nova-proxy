/*
 * FreeSWITCH Nova Sonic Module - Direct Frame Processing Version
 * Implements a blocking application that reads/writes frames directly
 */

#include <switch.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <netdb.h>

SWITCH_MODULE_LOAD_FUNCTION(mod_nova_sonic_load);
SWITCH_MODULE_SHUTDOWN_FUNCTION(mod_nova_sonic_shutdown);
SWITCH_MODULE_DEFINITION(mod_nova_sonic, mod_nova_sonic_load, mod_nova_sonic_shutdown, NULL);

/* Configuration */
static struct {
    char *gateway_host;
    int gateway_port;
    int sample_rate;
} globals;

/* Per-call context */
typedef struct {
    switch_core_session_t *session;
    int sock_fd;
    switch_bool_t running;

    /* Caller -> Nova buffers */
    switch_buffer_t *input_buffer;
    switch_mutex_t *input_mutex;
    switch_thread_cond_t *input_cond;

    /* Nova -> Caller buffers */
    switch_queue_t *playback_queue;

    /* Threads */
    switch_thread_t *recv_thread;
    switch_thread_t *send_thread;
    switch_memory_pool_t *pool;
} nova_ctx_t;

/* Connect to gateway and send handshake */
static int connect_to_gateway(nova_ctx_t *ctx, const char *uuid, const char *caller) {
    int sock_fd;
    struct sockaddr_in serv_addr;
    struct hostent *server;
    char handshake[512];

    switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_INFO,
        "Connecting to gateway %s:%d\n", globals.gateway_host, globals.gateway_port);

    /* Create socket */
    sock_fd = socket(AF_INET, SOCK_STREAM, 0);
    if (sock_fd < 0) {
        switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_ERROR,
            "Failed to create socket\n");
        return -1;
    }

    /* Resolve hostname */
    server = gethostbyname(globals.gateway_host);
    if (server == NULL) {
        switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_ERROR,
            "Failed to resolve gateway host %s\n", globals.gateway_host);
        close(sock_fd);
        return -1;
    }

    /* Connect */
    memset(&serv_addr, 0, sizeof(serv_addr));
    serv_addr.sin_family = AF_INET;
    memcpy(&serv_addr.sin_addr.s_addr, server->h_addr, server->h_length);
    serv_addr.sin_port = htons(globals.gateway_port);

    if (connect(sock_fd, (struct sockaddr *)&serv_addr, sizeof(serv_addr)) < 0) {
        switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_ERROR,
            "Failed to connect to gateway\n");
        close(sock_fd);
        return -1;
    }

    /* Send handshake in expected format: NOVA_SESSION:<uuid>:CALLER:<phone>\n */
    snprintf(handshake, sizeof(handshake),
        "NOVA_SESSION:%s:CALLER:%s\n",
        uuid, caller ? caller : "unknown");

    if (send(sock_fd, handshake, strlen(handshake), 0) < 0) {
        switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_ERROR,
            "Failed to send handshake\n");
        close(sock_fd);
        return -1;
    }

    switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_INFO,
        "Connected to gateway and sent handshake\n");

    return sock_fd;
}

/* Gateway receive thread: Nova -> Caller */
static void *SWITCH_THREAD_FUNC gateway_recv_thread(switch_thread_t *thread, void *obj) {
    nova_ctx_t *ctx = (nova_ctx_t *)obj;
    uint8_t tag;
    uint8_t frame_buf[640]; /* 20ms PCM16 mono @16kHz or 40ms @8kHz */
    ssize_t n;

    switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_DEBUG,
        "Gateway recv thread started\n");

    while (ctx->running) {
        /* Read frame tag */
        n = recv(ctx->sock_fd, &tag, 1, 0);
        if (n <= 0) {
            switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_INFO,
                "Gateway connection closed (recv tag)\n");
            ctx->running = SWITCH_FALSE;
            break;
        }

        if (tag == 0x01) {
            /* Audio frame - read 640 bytes */
            size_t total = 0;
            while (total < 640) {
                n = recv(ctx->sock_fd, frame_buf + total, 640 - total, 0);
                if (n <= 0) {
                    switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_ERROR,
                        "Gateway connection closed during audio frame read\n");
                    ctx->running = SWITCH_FALSE;
                    goto done;
                }
                total += n;
            }

            /* Queue for playback */
            void *queued_frame = malloc(640);
            if (queued_frame) {
                memcpy(queued_frame, frame_buf, 640);
                if (switch_queue_trypush(ctx->playback_queue, queued_frame) != SWITCH_STATUS_SUCCESS) {
                    free(queued_frame);
                    switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_WARNING,
                        "Playback queue full, dropping frame\n");
                }
            }

        } else if (tag == 0x02) {
            /* Control message - read until newline */
            char ctrl_buf[256];
            size_t pos = 0;
            while (pos < sizeof(ctrl_buf) - 1) {
                n = recv(ctx->sock_fd, &ctrl_buf[pos], 1, 0);
                if (n <= 0) break;
                if (ctrl_buf[pos] == '\n') break;
                pos++;
            }
            ctrl_buf[pos] = '\0';

            /* Check for hangup */
            if (strstr(ctrl_buf, "hangup")) {
                switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_INFO,
                    "Gateway requested hangup\n");
                ctx->running = SWITCH_FALSE;
                switch_channel_hangup(switch_core_session_get_channel(ctx->session),
                    SWITCH_CAUSE_NORMAL_CLEARING);
            }
        } else {
            switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_WARNING,
                "Unknown frame tag: 0x%02x\n", tag);
        }
    }

done:
    switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_DEBUG,
        "Gateway recv thread exiting\n");
    return NULL;
}

/* Gateway send thread: Caller -> Nova */
static void *SWITCH_THREAD_FUNC gateway_send_thread(switch_thread_t *thread, void *obj) {
    nova_ctx_t *ctx = (nova_ctx_t *)obj;
    uint8_t frame_buf[640];

    switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_DEBUG,
        "Gateway send thread started\n");

    while (ctx->running) {
        /* Wait for caller audio */
        switch_mutex_lock(ctx->input_mutex);

        while (switch_buffer_inuse(ctx->input_buffer) < 640 && ctx->running) {
            switch_thread_cond_wait(ctx->input_cond, ctx->input_mutex);
        }

        if (!ctx->running) {
            switch_mutex_unlock(ctx->input_mutex);
            break;
        }

        /* Read 640 bytes (20ms @ 16kHz or 40ms @ 8kHz PCM16 mono) */
        uint32_t bytes_read = switch_buffer_read(ctx->input_buffer, frame_buf, 640);
        switch_mutex_unlock(ctx->input_mutex);

        if (bytes_read == 640) {
            /* Send to gateway */
            ssize_t sent = send(ctx->sock_fd, frame_buf, 640, 0);
            if (sent < 0) {
                switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_ERROR,
                    "Failed to send audio to gateway\n");
                ctx->running = SWITCH_FALSE;
                break;
            }
        }
    }

    switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_DEBUG,
        "Gateway send thread exiting\n");
    return NULL;
}

/* Main application function */
SWITCH_STANDARD_APP(nova_sonic_function) {
    switch_channel_t *channel = switch_core_session_get_channel(session);
    const char *uuid = switch_core_session_get_uuid(session);
    const char *caller = switch_channel_get_variable(channel, "caller_id_number");
    nova_ctx_t *ctx = NULL;
    switch_memory_pool_t *pool = NULL;
    switch_threadattr_t *thd_attr = NULL;

    switch_log_printf(SWITCH_CHANNEL_SESSION_LOG(session), SWITCH_LOG_INFO,
        "nova_sonic: starting for channel %s caller=%s\n",
        uuid, caller ? caller : "unknown");

    /* Create memory pool */
    if (switch_core_new_memory_pool(&pool) != SWITCH_STATUS_SUCCESS) {
        switch_log_printf(SWITCH_CHANNEL_SESSION_LOG(session), SWITCH_LOG_ERROR,
            "Failed to create memory pool\n");
        return;
    }

    /* Allocate context */
    ctx = switch_core_alloc(pool, sizeof(*ctx));
    memset(ctx, 0, sizeof(*ctx));
    ctx->session = session;
    ctx->pool = pool;
    ctx->running = SWITCH_TRUE;
    ctx->sock_fd = -1;

    /* Create buffers and queues */
    switch_buffer_create_dynamic(&ctx->input_buffer, 8192, 16384, 0);
    switch_mutex_init(&ctx->input_mutex, SWITCH_MUTEX_NESTED, pool);
    switch_thread_cond_create(&ctx->input_cond, pool);
    switch_queue_create(&ctx->playback_queue, 100, pool);

    /* Connect to gateway */
    ctx->sock_fd = connect_to_gateway(ctx, uuid, caller);
    if (ctx->sock_fd < 0) {
        switch_log_printf(SWITCH_CHANNEL_SESSION_LOG(session), SWITCH_LOG_ERROR,
            "Failed to connect to gateway\n");
        goto cleanup;
    }

    /* Start helper threads */
    switch_threadattr_create(&thd_attr, pool);
    switch_threadattr_detach_set(thd_attr, 1);
    switch_threadattr_stacksize_set(thd_attr, SWITCH_THREAD_STACKSIZE);

    if (switch_thread_create(&ctx->recv_thread, thd_attr, gateway_recv_thread, ctx, pool) != SWITCH_STATUS_SUCCESS) {
        switch_log_printf(SWITCH_CHANNEL_SESSION_LOG(session), SWITCH_LOG_ERROR,
            "Failed to create recv thread\n");
        goto cleanup;
    }

    if (switch_thread_create(&ctx->send_thread, thd_attr, gateway_send_thread, ctx, pool) != SWITCH_STATUS_SUCCESS) {
        switch_log_printf(SWITCH_CHANNEL_SESSION_LOG(session), SWITCH_LOG_ERROR,
            "Failed to create send thread\n");
        goto cleanup;
    }

    switch_log_printf(SWITCH_CHANNEL_SESSION_LOG(session), SWITCH_LOG_INFO,
        "nova_sonic: entering main media loop\n");

    /* Main media loop */
    while (switch_channel_ready(channel) && ctx->running) {
        switch_frame_t *read_frame = NULL;
        switch_status_t status;

        /* Read caller audio */
        status = switch_core_session_read_frame(session, &read_frame, SWITCH_IO_FLAG_NONE, 0);

        if (status == SWITCH_STATUS_SUCCESS && read_frame && read_frame->data && read_frame->datalen > 0) {
            /* Push to send thread */
            switch_mutex_lock(ctx->input_mutex);
            switch_buffer_write(ctx->input_buffer, read_frame->data, read_frame->datalen);
            switch_thread_cond_signal(ctx->input_cond);
            switch_mutex_unlock(ctx->input_mutex);
        }

        /* Check for bot audio to play */
        void *queued_frame = NULL;
        if (switch_queue_trypop(ctx->playback_queue, &queued_frame) == SWITCH_STATUS_SUCCESS) {
            switch_frame_t write_frame = { 0 };
            write_frame.data = queued_frame;
            write_frame.datalen = 640;
            write_frame.samples = 320; /* 640 bytes / 2 bytes per sample */
            write_frame.rate = globals.sample_rate;
            write_frame.channels = 1;

            /* Write to caller */
            switch_core_session_write_frame(session, &write_frame, SWITCH_IO_FLAG_NONE, 0);
            free(queued_frame);
        }
    }

    switch_log_printf(SWITCH_CHANNEL_SESSION_LOG(session), SWITCH_LOG_INFO,
        "nova_sonic: exiting main loop\n");

cleanup:
    /* Stop threads */
    ctx->running = SWITCH_FALSE;

    if (ctx->input_cond) {
        switch_thread_cond_broadcast(ctx->input_cond);
    }

    /* Close socket */
    if (ctx->sock_fd >= 0) {
        close(ctx->sock_fd);
    }

    /* Cleanup queued frames */
    if (ctx->playback_queue) {
        void *pop = NULL;
        while (switch_queue_trypop(ctx->playback_queue, &pop) == SWITCH_STATUS_SUCCESS) {
            if (pop) free(pop);
        }
    }

    /* Cleanup */
    if (ctx->input_buffer) {
        switch_buffer_destroy(&ctx->input_buffer);
    }

    if (pool) {
        switch_core_destroy_memory_pool(&pool);
    }

    switch_log_printf(SWITCH_CHANNEL_SESSION_LOG(session), SWITCH_LOG_INFO,
        "nova_sonic: cleanup complete\n");
}

/* Module load */
SWITCH_MODULE_LOAD_FUNCTION(mod_nova_sonic_load) {
    switch_application_interface_t *app_interface;

    /* Set defaults */
    globals.gateway_host = "10.0.0.68";
    globals.gateway_port = 8085;
    globals.sample_rate = 8000;

    *module_interface = switch_loadable_module_create_module_interface(pool, modname);

    SWITCH_ADD_APP(app_interface, "nova_sonic", "Nova Sonic Voice AI",
                   "Connects call to Nova Sonic AI", nova_sonic_function, "", SAF_NONE);

    switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_INFO,
        "Nova Sonic module loaded (gateway=%s:%d, rate=%d)\n",
        globals.gateway_host, globals.gateway_port, globals.sample_rate);

    return SWITCH_STATUS_SUCCESS;
}

/* Module shutdown */
SWITCH_MODULE_SHUTDOWN_FUNCTION(mod_nova_sonic_shutdown) {
    switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_INFO,
        "Nova Sonic module shutting down\n");
    return SWITCH_STATUS_SUCCESS;
}
