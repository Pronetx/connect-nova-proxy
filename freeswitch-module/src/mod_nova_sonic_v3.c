/*
 * mod_nova_sonic - FreeSWITCH module for Amazon Nova Sonic integration
 *
 * This module provides a nova_ai_session application that:
 * - Answers the call
 * - Connects to Java gateway via TCP
 * - Uses direct frame read/write loop (no media bug)
 * - Streams bidirectional audio with Nova Sonic
 */

#include <switch.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <unistd.h>

SWITCH_MODULE_LOAD_FUNCTION(mod_nova_sonic_load);
SWITCH_MODULE_SHUTDOWN_FUNCTION(mod_nova_sonic_shutdown);
SWITCH_MODULE_DEFINITION(mod_nova_sonic, mod_nova_sonic_load, mod_nova_sonic_shutdown, NULL);

static const char *GATEWAY_HOST = "10.0.0.68";  // Java gateway private IP
static const int GATEWAY_PORT = 8085;

/*
 * μ-law decoder (PCMU → PCM16)
 * Converts 8-bit μ-law to 16-bit linear PCM
 */
static inline int16_t ulaw2linear(uint8_t u) {
    static const int exp_lut[8] = {0, 132, 396, 924, 1980, 4092, 8316, 16764};
    u = ~u;
    int t = ((u & 0x0F) << 3) + 0x84;
    t <<= ((unsigned)u & 0x70) >> 4;
    return (u & 0x80) ? (int16_t)(0x84 - t) : (int16_t)(t - 0x84);
}

static void ulaw_to_pcm16(const uint8_t *in, size_t samples, int16_t *out) {
    for (size_t i = 0; i < samples; i++) {
        out[i] = ulaw2linear(in[i]);
    }
}

/*
 * μ-law encoder (PCM16 → PCMU)
 * Converts 16-bit linear PCM to 8-bit μ-law (PCMU)
 */
static inline uint8_t linear2ulaw(int16_t sample) {
    const int cBias = 0x84;  // 132
    const int cClip = 32635;
    int sign = (sample >> 8) & 0x80;
    if (sign) sample = -sample;
    if (sample > cClip) sample = cClip;
    sample += cBias;
    int exponent = 7;
    for (int expMask = 0x4000; (sample & expMask) == 0 && exponent > 0; expMask >>= 1) exponent--;
    int mantissa = (sample >> ((exponent == 0) ? 4 : (exponent + 3))) & 0x0F;
    return ~(sign | (exponent << 4) | mantissa);
}

static void pcm16_to_ulaw(const int16_t *in, size_t samples, uint8_t *out) {
    for (size_t i = 0; i < samples; i++) {
        out[i] = linear2ulaw(in[i]);
    }
}

/*
 * Audio stream for queuing bot audio from gateway
 */
typedef struct {
    switch_buffer_t *audio_buffer;
    switch_mutex_t *mutex;
} audio_stream_t;

/*
 * Nova session context
 */
typedef struct {
    switch_core_session_t *session;
    switch_channel_t *channel;
    switch_memory_pool_t *pool;

    char session_id[128];
    char caller_id[128];

    int gateway_socket;
    char *gateway_host;
    int gateway_port;

    audio_stream_t *output_stream;  // Bot audio from Nova

    switch_thread_t *recv_thread;
    volatile int running;
} nova_session_t;

/*
 * Initialize audio stream
 */
static switch_status_t audio_stream_init(audio_stream_t **stream, switch_memory_pool_t *pool) {
    audio_stream_t *s = switch_core_alloc(pool, sizeof(audio_stream_t));

    if (switch_buffer_create_dynamic(&s->audio_buffer, 1024, 8192, 32768) != SWITCH_STATUS_SUCCESS) {
        return SWITCH_STATUS_FALSE;
    }

    if (switch_mutex_init(&s->mutex, SWITCH_MUTEX_NESTED, pool) != SWITCH_STATUS_SUCCESS) {
        return SWITCH_STATUS_FALSE;
    }

    *stream = s;
    return SWITCH_STATUS_SUCCESS;
}

/*
 * Connect to Java gateway via TCP
 */
static int connect_to_gateway(const char *host, int port) {
    int sock;
    struct sockaddr_in server_addr;
    struct hostent *server;

    sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock < 0) {
        switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_ERROR,
            "Failed to create socket: %s\n", strerror(errno));
        return -1;
    }

    server = gethostbyname(host);
    if (server == NULL) {
        switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_ERROR,
            "Failed to resolve host: %s\n", host);
        close(sock);
        return -1;
    }

    memset(&server_addr, 0, sizeof(server_addr));
    server_addr.sin_family = AF_INET;
    memcpy(&server_addr.sin_addr.s_addr, server->h_addr, server->h_length);
    server_addr.sin_port = htons(port);

    if (connect(sock, (struct sockaddr *)&server_addr, sizeof(server_addr)) < 0) {
        switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_ERROR,
            "Failed to connect to gateway %s:%d: %s\n", host, port, strerror(errno));
        close(sock);
        return -1;
    }

    switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_INFO,
        "Connected to gateway at %s:%d (socket %d)\n", host, port, sock);

    return sock;
}

/*
 * Thread to receive audio and control messages from Java gateway
 */
static void *SWITCH_THREAD_FUNC nova_recv_thread(switch_thread_t *thread, void *obj) {
    nova_session_t *ctx = (nova_session_t *)obj;
    uint8_t audio_buffer[320]; /* 20ms at 8kHz, 16-bit */

    switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_INFO,
        "Audio receive thread started - receiving from %s:%d\n",
        ctx->gateway_host, ctx->gateway_port);

    while (ctx->running && ctx->gateway_socket > 0) {
        /* Peek at first 4 bytes to check if this is a control message */
        uint8_t header[4];
        ssize_t peeked = recv(ctx->gateway_socket, header, 4, MSG_PEEK);

        if (peeked == 4) {
            /* Check if this looks like a length-prefixed control message */
            uint32_t potential_length = (header[0] << 24) | (header[1] << 16) |
                                       (header[2] << 8) | header[3];

            /* Control messages are typically < 1KB, audio is exactly 320 bytes */
            if (potential_length > 0 && potential_length < 1024 && potential_length != 320) {
                /* This is likely a control message - read length prefix */
                uint8_t length_buf[4];
                recv(ctx->gateway_socket, length_buf, 4, 0);

                uint32_t msg_length = (length_buf[0] << 24) | (length_buf[1] << 16) |
                                     (length_buf[2] << 8) | length_buf[3];

                if (msg_length > 0 && msg_length < 1024) {
                    /* Read the control message */
                    char *control_msg = malloc(msg_length + 1);
                    ssize_t received = recv(ctx->gateway_socket, control_msg, msg_length, 0);

                    if (received == msg_length) {
                        control_msg[msg_length] = '\0';
                        switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_INFO,
                            "Received control message from gateway: %s\n", control_msg);

                        /* Check if this is a hangup command */
                        if (strstr(control_msg, "\"type\":\"hangup\"") != NULL) {
                            switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_INFO,
                                "Nova requested hangup - terminating call\n");

                            /* Hangup the channel */
                            switch_channel_hangup(ctx->channel, SWITCH_CAUSE_NORMAL_CLEARING);
                            ctx->running = 0;
                        }
                    }

                    free(control_msg);
                }
                continue;
            }
        }

        /* Read exact 320 bytes of PCM16 audio (blocking until complete frame received) */
        size_t need = 320;
        size_t got = 0;

        while (got < need) {
            ssize_t r = recv(ctx->gateway_socket, audio_buffer + got, need - got, 0);

            if (r < 0) {
                switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_ERROR,
                    "Failed to receive audio from gateway: %s\n", strerror(errno));
                ctx->running = 0;
                goto END_THREAD;
            } else if (r == 0) {
                switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_INFO,
                    "Gateway closed connection\n");
                ctx->running = 0;
                goto END_THREAD;
            }

            got += (size_t)r;
        }

        /* Write complete 320-byte PCM16 frame to output buffer */
        switch_mutex_lock(ctx->output_stream->mutex);
        switch_buffer_write(ctx->output_stream->audio_buffer, audio_buffer, 320);
        switch_mutex_unlock(ctx->output_stream->mutex);

        switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_DEBUG,
            "Received 320 bytes of PCM16 audio from gateway\n");
    }

END_THREAD:
    switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_INFO, "Audio receive thread ended\n");
    return NULL;
}

/*
 * Try to dequeue bot audio frame
 * Returns true if frame was dequeued, false if no audio available
 */
static switch_bool_t dequeue_bot_frame(nova_session_t *ctx, uint8_t *buf, uint32_t *len) {
    switch_mutex_lock(ctx->output_stream->mutex);

    uint32_t available = switch_buffer_inuse(ctx->output_stream->audio_buffer);
    if (available >= 320) {
        *len = switch_buffer_read(ctx->output_stream->audio_buffer, buf, 320);
        switch_mutex_unlock(ctx->output_stream->mutex);
        return SWITCH_TRUE;
    }

    switch_mutex_unlock(ctx->output_stream->mutex);
    return SWITCH_FALSE;
}

/*
 * Main application: nova_ai_session
 */
SWITCH_STANDARD_APP(nova_ai_session_function) {
    switch_channel_t *channel = switch_core_session_get_channel(session);
    nova_session_t *ctx = NULL;
    switch_memory_pool_t *pool = NULL;
    switch_threadattr_t *thd_attr = NULL;
    switch_frame_t *read_frame;
    uint8_t bot_buf[320];
    uint32_t bot_len;

    switch_log_printf(SWITCH_CHANNEL_SESSION_LOG(session), SWITCH_LOG_INFO,
        "nova_ai_session started\n");

    /* Create memory pool for session */
    if (switch_core_new_memory_pool(&pool) != SWITCH_STATUS_SUCCESS) {
        switch_log_printf(SWITCH_CHANNEL_SESSION_LOG(session), SWITCH_LOG_ERROR,
            "Failed to create memory pool\n");
        return;
    }

    /* Allocate session context */
    ctx = switch_core_alloc(pool, sizeof(nova_session_t));
    ctx->session = session;
    ctx->channel = channel;
    ctx->pool = pool;
    ctx->running = 1;
    ctx->gateway_socket = -1;
    ctx->gateway_host = GATEWAY_HOST;
    ctx->gateway_port = GATEWAY_PORT;

    /* Generate session ID */
    const char *uuid = switch_core_session_get_uuid(session);
    switch_snprintf(ctx->session_id, sizeof(ctx->session_id), "%s", uuid);

    /* Get caller ID */
    const char *caller_number = switch_channel_get_variable(channel, "caller_id_number");
    if (caller_number) {
        switch_snprintf(ctx->caller_id, sizeof(ctx->caller_id), "%s", caller_number);
    } else {
        switch_snprintf(ctx->caller_id, sizeof(ctx->caller_id), "Unknown");
    }

    switch_log_printf(SWITCH_CHANNEL_SESSION_LOG(session), SWITCH_LOG_INFO,
        "Session: %s, Caller: %s\n", ctx->session_id, ctx->caller_id);

    /* Answer the call if not already answered */
    if (!switch_channel_test_flag(channel, CF_ANSWERED)) {
        if (switch_channel_answer(channel) != SWITCH_STATUS_SUCCESS) {
            switch_log_printf(SWITCH_CHANNEL_SESSION_LOG(session), SWITCH_LOG_ERROR,
                "Failed to answer channel\n");
            switch_core_destroy_memory_pool(&pool);
            return;
        }
        switch_log_printf(SWITCH_CHANNEL_SESSION_LOG(session), SWITCH_LOG_INFO,
            "Channel answered\n");
    } else {
        switch_log_printf(SWITCH_CHANNEL_SESSION_LOG(session), SWITCH_LOG_INFO,
            "Channel already answered\n");
    }

    /* Initialize output audio stream */
    if (audio_stream_init(&ctx->output_stream, pool) != SWITCH_STATUS_SUCCESS) {
        switch_log_printf(SWITCH_CHANNEL_SESSION_LOG(session), SWITCH_LOG_ERROR,
            "Failed to initialize output stream\n");
        switch_core_destroy_memory_pool(&pool);
        return;
    }

    /* Connect to Java gateway */
    ctx->gateway_socket = connect_to_gateway(ctx->gateway_host, ctx->gateway_port);
    if (ctx->gateway_socket < 0) {
        switch_log_printf(SWITCH_CHANNEL_SESSION_LOG(session), SWITCH_LOG_ERROR,
            "Failed to connect to gateway\n");
        switch_core_destroy_memory_pool(&pool);
        return;
    }

    /* Send JSON handshake to gateway */
    char handshake[512];
    snprintf(handshake, sizeof(handshake),
        "{\"call_uuid\":\"%s\",\"caller\":\"%s\",\"sample_rate\":8000,\"channels\":1,\"format\":\"PCM16\"}\n",
        ctx->session_id, ctx->caller_id);

    ssize_t sent = send(ctx->gateway_socket, handshake, strlen(handshake), 0);
    if (sent < 0) {
        switch_log_printf(SWITCH_CHANNEL_SESSION_LOG(session), SWITCH_LOG_ERROR,
            "Failed to send handshake: %s\n", strerror(errno));
        close(ctx->gateway_socket);
        switch_core_destroy_memory_pool(&pool);
        return;
    }

    switch_log_printf(SWITCH_CHANNEL_SESSION_LOG(session), SWITCH_LOG_INFO,
        "Sent JSON handshake: %s", handshake);

    /* Get the write codec for the session (needed for write_frame) */
    const switch_codec_t *write_codec = switch_core_session_get_write_codec(session);
    if (write_codec) {
        switch_log_printf(SWITCH_CHANNEL_SESSION_LOG(session), SWITCH_LOG_INFO,
            "Write codec: %s @ %dHz, packet=%dms\n",
            write_codec->implementation->iananame,
            write_codec->implementation->actual_samples_per_second,
            (int)(write_codec->implementation->microseconds_per_packet / 1000));
    } else {
        switch_log_printf(SWITCH_CHANNEL_SESSION_LOG(session), SWITCH_LOG_WARNING,
            "Write codec is NULL; continuing but writes may fail\n");
    }

    /* Start receive thread for bot audio */
    switch_threadattr_create(&thd_attr, pool);
    switch_threadattr_detach_set(thd_attr, 1);
    switch_thread_create(&ctx->recv_thread, thd_attr, nova_recv_thread, ctx, pool);

    /* Main audio loop - direct frame read/write */
    switch_log_printf(SWITCH_CHANNEL_SESSION_LOG(session), SWITCH_LOG_INFO,
        "Entering main audio loop\n");

    int media_ready = 0;

    while (switch_channel_ready(channel) && ctx->running) {
        /* 1. Read caller audio from FreeSWITCH */
        switch_status_t st = switch_core_session_read_frame(session, &read_frame, SWITCH_IO_FLAG_NONE, 0);

        if (st == SWITCH_STATUS_SUCCESS && read_frame && read_frame->datalen > 0) {
            /* Only process real audio frames (≥160 bytes), not comfort noise (2 bytes) */
            if (read_frame->datalen >= 160) {
                /* Mark media as ready on first valid frame */
                if (!media_ready) {
                    media_ready = 1;
                    switch_log_printf(SWITCH_CHANNEL_SESSION_LOG(session), SWITCH_LOG_INFO,
                        "Media ready - received first real inbound frame (%d bytes)\n", read_frame->datalen);
                }

                /* Decode PCMU (160 bytes) to PCM16 (320 bytes) for Nova */
                if (read_frame->datalen == 160) {
                    /* PCMU 8-bit → PCM16 16-bit */
                    int16_t pcm16_buf[160];
                    ulaw_to_pcm16((const uint8_t*)read_frame->data, 160, pcm16_buf);

                    ssize_t sent = send(ctx->gateway_socket, pcm16_buf, 320, 0);
                    if (sent < 0) {
                        switch_log_printf(SWITCH_CHANNEL_SESSION_LOG(session), SWITCH_LOG_ERROR,
                            "Failed to send audio to gateway: %s\n", strerror(errno));
                        break;
                    }

                    switch_log_printf(SWITCH_CHANNEL_SESSION_LOG(session), SWITCH_LOG_DEBUG,
                        "Sent 320 bytes of PCM16 caller audio to gateway (decoded from %d PCMU)\n",
                        read_frame->datalen);
                } else if (read_frame->datalen == 320) {
                    /* Already PCM16, send as-is */
                    ssize_t sent = send(ctx->gateway_socket, read_frame->data, 320, 0);
                    if (sent < 0) {
                        switch_log_printf(SWITCH_CHANNEL_SESSION_LOG(session), SWITCH_LOG_ERROR,
                            "Failed to send audio to gateway: %s\n", strerror(errno));
                        break;
                    }

                    switch_log_printf(SWITCH_CHANNEL_SESSION_LOG(session), SWITCH_LOG_DEBUG,
                        "Sent 320 bytes of PCM16 caller audio to gateway\n");
                } else {
                    switch_log_printf(SWITCH_CHANNEL_SESSION_LOG(session), SWITCH_LOG_WARNING,
                        "Unexpected frame size: %d bytes (expected 160 or 320)\n", read_frame->datalen);
                }
            }
        } else if (st != SWITCH_STATUS_SUCCESS && st != SWITCH_STATUS_BREAK) {
            /* Log non-success status but continue */
            switch_log_printf(SWITCH_CHANNEL_SESSION_LOG(session), SWITCH_LOG_DEBUG,
                "read_frame returned status: %d\n", st);
        }

        /* 2. Only write bot audio after media is ready */
        if (media_ready && write_codec && dequeue_bot_frame(ctx, bot_buf, &bot_len) && bot_len >= 320) {
            /* Convert PCM16 (320 bytes = 160 samples) to PCMU (160 bytes) */
            const int16_t *pcm16_samples = (const int16_t *)bot_buf;
            uint8_t ulaw_buf[160];
            pcm16_to_ulaw(pcm16_samples, 160, ulaw_buf);

            /* Write μ-law audio to channel */
            switch_frame_t write_frame = {0};
            write_frame.data = ulaw_buf;
            write_frame.datalen = 160;      // 160 bytes of μ-law
            write_frame.samples = 160;       // 160 samples @ 8kHz = 20ms
            write_frame.rate = 8000;
            write_frame.channels = 1;
            write_frame.codec = (switch_codec_t *)write_codec;  // CRITICAL: set frame codec

            st = switch_core_session_write_frame(session, &write_frame, SWITCH_IO_FLAG_NONE, 0);
            if (st != SWITCH_STATUS_SUCCESS) {
                switch_log_printf(SWITCH_CHANNEL_SESSION_LOG(session), SWITCH_LOG_DEBUG,
                    "write_frame returned status: %d\n", st);
            } else {
                switch_log_printf(SWITCH_CHANNEL_SESSION_LOG(session), SWITCH_LOG_DEBUG,
                    "Wrote 160 bytes of μ-law audio to channel\n");
            }
        }

        /* Small yield to prevent CPU spinning */
        switch_yield(1000); // 1ms
    }

    switch_log_printf(SWITCH_CHANNEL_SESSION_LOG(session), SWITCH_LOG_INFO,
        "Exiting main audio loop\n");

    /* Cleanup */
    ctx->running = 0;
    if (ctx->gateway_socket >= 0) {
        close(ctx->gateway_socket);
    }

    switch_core_destroy_memory_pool(&pool);

    switch_log_printf(SWITCH_CHANNEL_SESSION_LOG(session), SWITCH_LOG_INFO,
        "nova_ai_session ended\n");
}

/*
 * Module load
 */
SWITCH_MODULE_LOAD_FUNCTION(mod_nova_sonic_load) {
    switch_application_interface_t *app_interface;

    *module_interface = switch_loadable_module_create_module_interface(pool, modname);

    SWITCH_ADD_APP(app_interface, "nova_ai_session", "Nova AI Session",
                   "Connects call to Nova Sonic AI via Java gateway",
                   nova_ai_session_function, "", SAF_NONE);

    switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_INFO,
        "mod_nova_sonic loaded - nova_ai_session application registered\n");

    return SWITCH_STATUS_SUCCESS;
}

/*
 * Module shutdown
 */
SWITCH_MODULE_SHUTDOWN_FUNCTION(mod_nova_sonic_shutdown) {
    switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_INFO,
        "mod_nova_sonic shutting down\n");
    return SWITCH_STATUS_SUCCESS;
}
