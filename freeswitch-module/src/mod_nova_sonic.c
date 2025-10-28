/*
 * FreeSWITCH Modular Media Switching Software Library / Soft-Switch Application
 * Copyright (C) 2025, Amazon Web Services
 *
 * mod_nova_sonic.c -- Amazon Nova Sonic Audio Proxy Module
 *
 * This module acts as an audio proxy between FreeSWITCH and the Java Nova Gateway.
 * It captures audio from FreeSWITCH channels and streams it via TCP to the Java
 * gateway, which handles Nova Sonic integration, tools, and recording.
 *
 * Architecture:
 *   FreeSWITCH (SIP/RTP) <-> mod_nova_sonic (audio proxy) <-> Java Gateway <-> Nova Sonic
 *
 * Features:
 * - Real-time bidirectional audio streaming via TCP
 * - L16 PCM audio format (8kHz, 16-bit, mono)
 * - Media bug for efficient audio capture
 * - Simple TCP protocol (no encoding needed)
 *
 * Usage:
 *   <action application="nova_sonic" data="gateway_host=localhost,gateway_port=8085"/>
 */

#include <switch.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>

/* Module interface */
SWITCH_MODULE_LOAD_FUNCTION(mod_nova_sonic_load);
SWITCH_MODULE_SHUTDOWN_FUNCTION(mod_nova_sonic_shutdown);
SWITCH_MODULE_DEFINITION(mod_nova_sonic, mod_nova_sonic_load, mod_nova_sonic_shutdown, NULL);

/* Configuration */
static struct {
    char *gateway_host;
    int gateway_port;
    int sample_rate;
    int channels;
    int bits_per_sample;
    switch_memory_pool_t *pool;
} globals;

/* Audio buffer for streaming */
typedef struct {
    switch_buffer_t *audio_buffer;
    switch_mutex_t *mutex;
    switch_thread_cond_t *cond;
    int finished;
} audio_stream_t;

/* Nova session context */
typedef struct {
    switch_core_session_t *session;
    switch_channel_t *channel;

    /* Audio streams */
    audio_stream_t *input_stream;   /* Caller audio going to Java gateway */
    audio_stream_t *output_stream;  /* Java gateway audio going to caller */

    /* TCP connection to Java gateway */
    int gateway_socket;
    char *gateway_host;
    int gateway_port;
    char *session_id;
    char *caller_id;

    /* State */
    int running;
    switch_thread_t *send_thread;
    switch_thread_t *recv_thread;

    /* Memory pool */
    switch_memory_pool_t *pool;
} nova_session_t;

/* Forward declarations */
static switch_status_t nova_session_init(nova_session_t *nova_ctx);
static void nova_session_cleanup(nova_session_t *nova_ctx);
static void *SWITCH_THREAD_FUNC nova_send_thread(switch_thread_t *thread, void *obj);
static void *SWITCH_THREAD_FUNC nova_recv_thread(switch_thread_t *thread, void *obj);
static switch_bool_t nova_bug_callback(switch_media_bug_t *bug, void *user_data, switch_abc_type_t type);

/*
 * Create and initialize audio stream
 */
static audio_stream_t *audio_stream_create(switch_memory_pool_t *pool) {
    audio_stream_t *stream = switch_core_alloc(pool, sizeof(audio_stream_t));

    switch_buffer_create_dynamic(&stream->audio_buffer, 1024, 65536, 0);
    switch_mutex_init(&stream->mutex, SWITCH_MUTEX_NESTED, pool);
    switch_thread_cond_create(&stream->cond, pool);
    stream->finished = 0;

    return stream;
}

/*
 * Destroy audio stream
 */
static void audio_stream_destroy(audio_stream_t *stream) {
    if (stream) {
        stream->finished = 1;
        switch_thread_cond_broadcast(stream->cond);
        if (stream->audio_buffer) {
            switch_buffer_destroy(&stream->audio_buffer);
        }
    }
}

/*
 * Media bug callback - captures audio from the channel
 */
static switch_bool_t nova_bug_callback(switch_media_bug_t *bug, void *user_data, switch_abc_type_t type) {
    nova_session_t *nova_ctx = (nova_session_t *)user_data;

    switch (type) {
        case SWITCH_ABC_TYPE_INIT:
            switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_INFO, "Nova media bug initialized\n");
            break;

        case SWITCH_ABC_TYPE_READ_REPLACE:
        case SWITCH_ABC_TYPE_READ_PING: {
            /* Get audio frame from the channel (caller speaking) */
            switch_frame_t *frame = switch_core_media_bug_get_read_replace_frame(bug);

            if (frame && frame->datalen > 0) {
                /* Write audio to input stream (going to Nova) */
                switch_mutex_lock(nova_ctx->input_stream->mutex);
                switch_buffer_write(nova_ctx->input_stream->audio_buffer, frame->data, frame->datalen);
                switch_thread_cond_signal(nova_ctx->input_stream->cond);
                switch_mutex_unlock(nova_ctx->input_stream->mutex);
            }
            break;
        }

        case SWITCH_ABC_TYPE_WRITE_REPLACE: {
            /* Replace outgoing audio with Nova's audio */
            switch_frame_t *frame = switch_core_media_bug_get_write_replace_frame(bug);

            if (frame && nova_ctx->output_stream) {
                switch_mutex_lock(nova_ctx->output_stream->mutex);

                /* Read Nova audio if available */
                uint32_t bytes_read = switch_buffer_read(
                    nova_ctx->output_stream->audio_buffer,
                    frame->data,
                    frame->datalen
                );

                if (bytes_read > 0) {
                    frame->datalen = bytes_read;
                } else {
                    /* No audio from Nova yet, send silence */
                    memset(frame->data, 0, frame->datalen);
                }

                switch_mutex_unlock(nova_ctx->output_stream->mutex);
                switch_core_media_bug_set_write_replace_frame(bug, frame);
            }
            break;
        }

        case SWITCH_ABC_TYPE_CLOSE:
            switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_INFO, "Nova media bug closed\n");
            nova_ctx->running = 0;
            break;

        default:
            break;
    }

    return SWITCH_TRUE;
}

/*
 * Thread to send audio to Java gateway via TCP
 */
static void *SWITCH_THREAD_FUNC nova_send_thread(switch_thread_t *thread, void *obj) {
    nova_session_t *nova_ctx = (nova_session_t *)obj;
    uint8_t audio_buffer[320]; /* 20ms at 8kHz, 16-bit = 160 samples * 2 = 320 bytes */

    switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_INFO,
        "Audio send thread started - streaming to %s:%d\n",
        nova_ctx->gateway_host, nova_ctx->gateway_port);

    while (nova_ctx->running && nova_ctx->gateway_socket > 0) {
        switch_mutex_lock(nova_ctx->input_stream->mutex);

        /* Wait for audio data or timeout */
        if (switch_buffer_inuse(nova_ctx->input_stream->audio_buffer) == 0) {
            switch_thread_cond_timedwait(nova_ctx->input_stream->cond,
                                        nova_ctx->input_stream->mutex,
                                        100000); /* 100ms timeout */
        }

        /* Read audio from buffer */
        uint32_t bytes_read = switch_buffer_read(
            nova_ctx->input_stream->audio_buffer,
            audio_buffer,
            sizeof(audio_buffer)
        );

        switch_mutex_unlock(nova_ctx->input_stream->mutex);

        if (bytes_read > 0) {
            /* Send raw PCM audio to Java gateway via TCP */
            ssize_t sent = send(nova_ctx->gateway_socket, audio_buffer, bytes_read, 0);
            if (sent < 0) {
                switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_ERROR,
                    "Failed to send audio to gateway: %s\n", strerror(errno));
                nova_ctx->running = 0;
                break;
            }
            switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_DEBUG,
                "Sent %d bytes of PCM audio to gateway\n", bytes_read);
        }
    }

    switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_INFO, "Audio send thread ended\n");
    return NULL;
}

/*
 * Thread to receive audio from Java gateway via TCP
 */
static void *SWITCH_THREAD_FUNC nova_recv_thread(switch_thread_t *thread, void *obj) {
    nova_session_t *nova_ctx = (nova_session_t *)obj;
    uint8_t audio_buffer[320]; /* 20ms at 8kHz, 16-bit */

    switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_INFO,
        "Audio receive thread started - receiving from %s:%d\n",
        nova_ctx->gateway_host, nova_ctx->gateway_port);

    while (nova_ctx->running && nova_ctx->gateway_socket > 0) {
        /* Receive raw PCM audio from Java gateway */
        ssize_t received = recv(nova_ctx->gateway_socket, audio_buffer, sizeof(audio_buffer), 0);

        if (received < 0) {
            if (errno != EAGAIN && errno != EWOULDBLOCK) {
                switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_ERROR,
                    "Failed to receive audio from gateway: %s\n", strerror(errno));
                nova_ctx->running = 0;
                break;
            }
            switch_yield(10000); /* No data, sleep 10ms */
            continue;
        } else if (received == 0) {
            switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_INFO,
                "Gateway closed connection\n");
            nova_ctx->running = 0;
            break;
        }

        /* Write PCM audio to output buffer (will be played back via media bug) */
        switch_mutex_lock(nova_ctx->output_stream->mutex);
        switch_buffer_write(nova_ctx->output_stream->audio_buffer, audio_buffer, received);
        switch_mutex_unlock(nova_ctx->output_stream->mutex);

        switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_DEBUG,
            "Received %zd bytes of PCM audio from gateway\n", received);
    }

    switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_INFO, "Audio receive thread ended\n");
    return NULL;
}

/*
 * Initialize Nova session and connect to Java gateway
 */
static switch_status_t nova_session_init(nova_session_t *nova_ctx) {
    switch_threadattr_t *thd_attr = NULL;
    struct sockaddr_in gateway_addr;

    /* Create audio streams */
    nova_ctx->input_stream = audio_stream_create(nova_ctx->pool);
    nova_ctx->output_stream = audio_stream_create(nova_ctx->pool);

    /* Create TCP socket to Java gateway */
    nova_ctx->gateway_socket = socket(AF_INET, SOCK_STREAM, 0);
    if (nova_ctx->gateway_socket < 0) {
        switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_ERROR,
            "Failed to create socket: %s\n", strerror(errno));
        return SWITCH_STATUS_FALSE;
    }

    /* Configure gateway address */
    memset(&gateway_addr, 0, sizeof(gateway_addr));
    gateway_addr.sin_family = AF_INET;
    gateway_addr.sin_port = htons(nova_ctx->gateway_port);

    if (inet_pton(AF_INET, nova_ctx->gateway_host, &gateway_addr.sin_addr) <= 0) {
        switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_ERROR,
            "Invalid gateway host address: %s\n", nova_ctx->gateway_host);
        close(nova_ctx->gateway_socket);
        return SWITCH_STATUS_FALSE;
    }

    /* Connect to Java gateway */
    switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_INFO,
        "Connecting to Java gateway at %s:%d...\n",
        nova_ctx->gateway_host, nova_ctx->gateway_port);

    if (connect(nova_ctx->gateway_socket, (struct sockaddr *)&gateway_addr, sizeof(gateway_addr)) < 0) {
        switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_ERROR,
            "Failed to connect to gateway at %s:%d: %s\n",
            nova_ctx->gateway_host, nova_ctx->gateway_port, strerror(errno));
        close(nova_ctx->gateway_socket);
        return SWITCH_STATUS_FALSE;
    }

    switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_INFO,
        "Successfully connected to Java gateway at %s:%d\n",
        nova_ctx->gateway_host, nova_ctx->gateway_port);

    /* Send initial metadata to gateway (caller ID, session ID) */
    char handshake[256];
    snprintf(handshake, sizeof(handshake), "NOVA_SESSION:%s:CALLER:%s\n",
             nova_ctx->session_id, nova_ctx->caller_id);
    send(nova_ctx->gateway_socket, handshake, strlen(handshake), 0);

    nova_ctx->running = 1;

    /* Start send thread */
    switch_threadattr_create(&thd_attr, nova_ctx->pool);
    switch_threadattr_detach_set(thd_attr, 1);
    switch_thread_create(&nova_ctx->send_thread, thd_attr, nova_send_thread, nova_ctx, nova_ctx->pool);

    /* Start receive thread */
    switch_thread_create(&nova_ctx->recv_thread, thd_attr, nova_recv_thread, nova_ctx, nova_ctx->pool);

    return SWITCH_STATUS_SUCCESS;
}

/*
 * Cleanup Nova session
 */
static void nova_session_cleanup(nova_session_t *nova_ctx) {
    if (nova_ctx) {
        nova_ctx->running = 0;

        /* Close socket to trigger thread exit */
        if (nova_ctx->gateway_socket > 0) {
            close(nova_ctx->gateway_socket);
            nova_ctx->gateway_socket = -1;
        }

        /* Wait for threads to finish */
        if (nova_ctx->send_thread) {
            switch_thread_join(NULL, nova_ctx->send_thread);
        }
        if (nova_ctx->recv_thread) {
            switch_thread_join(NULL, nova_ctx->recv_thread);
        }

        /* Cleanup streams */
        if (nova_ctx->input_stream) {
            audio_stream_destroy(nova_ctx->input_stream);
        }
        if (nova_ctx->output_stream) {
            audio_stream_destroy(nova_ctx->output_stream);
        }
    }
}

/*
 * Application function: nova_sonic
 * Usage: <action application="nova_sonic" data="system_prompt='You are a helpful assistant'"/>
 */
SWITCH_STANDARD_APP(nova_sonic_function) {
    switch_channel_t *channel = switch_core_session_get_channel(session);
    switch_media_bug_t *bug = NULL;
    switch_media_bug_flag_t flags = SMBF_READ_REPLACE | SMBF_WRITE_REPLACE | SMBF_NO_PAUSE;
    nova_session_t *nova_ctx = NULL;
    switch_memory_pool_t *pool = NULL;
    switch_codec_implementation_t read_impl = { 0 };

    /* Get codec information */
    switch_core_session_get_read_impl(session, &read_impl);

    switch_log_printf(SWITCH_CHANNEL_SESSION_LOG(session), SWITCH_LOG_INFO,
        "Starting Nova Sonic session - codec: %s, rate: %d, channels: %d\n",
        read_impl.iananame, read_impl.actual_samples_per_second, read_impl.number_of_channels);

    /* Create memory pool */
    if (switch_core_new_memory_pool(&pool) != SWITCH_STATUS_SUCCESS) {
        switch_log_printf(SWITCH_CHANNEL_SESSION_LOG(session), SWITCH_LOG_ERROR,
            "Failed to allocate memory pool\n");
        return;
    }

    /* Allocate context */
    nova_ctx = switch_core_alloc(pool, sizeof(nova_session_t));
    nova_ctx->session = session;
    nova_ctx->channel = channel;
    nova_ctx->pool = pool;

    /* Generate session ID */
    char uuid_str[SWITCH_UUID_FORMATTED_LENGTH + 1];
    switch_uuid_t uuid;
    switch_uuid_get(&uuid);
    switch_uuid_format(uuid_str, &uuid);
    nova_ctx->session_id = switch_core_strdup(pool, uuid_str);

    /* Get caller ID */
    const char *caller_id_number = switch_channel_get_variable(channel, "caller_id_number");
    nova_ctx->caller_id = switch_core_strdup(pool, caller_id_number ? caller_id_number : "Unknown");

    /* Set gateway connection details (use global defaults) */
    nova_ctx->gateway_host = switch_core_strdup(pool, globals.gateway_host);
    nova_ctx->gateway_port = globals.gateway_port;

    /* Initialize Nova session */
    if (nova_session_init(nova_ctx) != SWITCH_STATUS_SUCCESS) {
        switch_log_printf(SWITCH_CHANNEL_SESSION_LOG(session), SWITCH_LOG_ERROR,
            "Failed to initialize Nova session\n");
        switch_core_destroy_memory_pool(&pool);
        return;
    }

    /* Add media bug to capture audio */
    if (switch_core_media_bug_add(session, "nova_sonic", NULL,
                                  nova_bug_callback, nova_ctx, 0, flags, &bug) != SWITCH_STATUS_SUCCESS) {
        switch_log_printf(SWITCH_CHANNEL_SESSION_LOG(session), SWITCH_LOG_ERROR,
            "Failed to attach media bug\n");
        nova_session_cleanup(nova_ctx);
        switch_core_destroy_memory_pool(&pool);
        return;
    }

    switch_log_printf(SWITCH_CHANNEL_SESSION_LOG(session), SWITCH_LOG_INFO,
        "Nova Sonic media bug attached successfully\n");

    /* Keep the call active while Nova session is running */
    while (switch_channel_ready(channel) && nova_ctx->running) {
        switch_yield(100000); /* Sleep 100ms */
    }

    /* Cleanup */
    switch_log_printf(SWITCH_CHANNEL_SESSION_LOG(session), SWITCH_LOG_INFO,
        "Cleaning up Nova Sonic session\n");

    if (bug) {
        switch_core_media_bug_remove(session, &bug);
    }

    nova_session_cleanup(nova_ctx);
    switch_core_destroy_memory_pool(&pool);
}

/*
 * Load module configuration
 */
static switch_status_t load_config(void) {
    char *cf = "nova_sonic.conf";
    switch_xml_t cfg, xml, settings, param;

    /* Set defaults */
    globals.gateway_host = "127.0.0.1";  /* Java gateway on localhost */
    globals.gateway_port = 8085;          /* Port for TCP audio streaming */
    globals.sample_rate = 8000;
    globals.channels = 1;
    globals.bits_per_sample = 16;

    /* Load from environment if available */
    const char *gateway_host = getenv("NOVA_GATEWAY_HOST");
    const char *gateway_port = getenv("NOVA_GATEWAY_PORT");

    if (gateway_host) globals.gateway_host = strdup(gateway_host);
    if (gateway_port) globals.gateway_port = atoi(gateway_port);

    switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_INFO,
        "Nova Sonic Audio Proxy config: gateway=%s:%d, rate=%d, channels=%d, bits=%d\n",
        globals.gateway_host, globals.gateway_port,
        globals.sample_rate, globals.channels, globals.bits_per_sample);

    return SWITCH_STATUS_SUCCESS;
}

/*
 * Module load function
 */
SWITCH_MODULE_LOAD_FUNCTION(mod_nova_sonic_load) {
    switch_application_interface_t *app_interface;

    /* Connect internal structure to global module structure */
    *module_interface = switch_loadable_module_create_module_interface(pool, modname);

    /* Load configuration */
    if (load_config() != SWITCH_STATUS_SUCCESS) {
        return SWITCH_STATUS_FALSE;
    }

    /* Register application */
    SWITCH_ADD_APP(app_interface, "nova_sonic", "Amazon Nova Sonic Integration",
                   "Streams call audio to Amazon Nova Sonic for AI conversation",
                   nova_sonic_function, "", SAF_SUPPORT_NOMEDIA);

    switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_INFO, "Nova Sonic module loaded successfully\n");

    return SWITCH_STATUS_SUCCESS;
}

/*
 * Module shutdown function
 */
SWITCH_MODULE_SHUTDOWN_FUNCTION(mod_nova_sonic_shutdown) {
    switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_INFO, "Nova Sonic Audio Proxy shutting down\n");

    /* Cleanup global resources */
    /* Nothing to cleanup - gateway_host is either static or heap-allocated */

    return SWITCH_STATUS_SUCCESS;
}
