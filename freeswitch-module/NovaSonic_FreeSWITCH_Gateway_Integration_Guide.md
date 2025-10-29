# Nova Sonic ↔ FreeSWITCH Direct Frame Integration (Export for Assistant)

**Owner:** Pronetx (Yasser)  
**Date:** 2025‑10‑29  
**Goal:** Stabilize a production‑worthy, single‑leg SIP → FreeSWITCH → Java Gateway → Nova Sonic voice pipeline **without media bugs** or proxy‑media. Use a **direct frame loop** (`switch_core_session_read_frame` / `switch_core_session_write_frame`), JSON handshake, and explicit codec handling (PCM16 ⇄ PCMU).

---

## Executive Summary

You are **on the right architecture**. The only remaining problems are **when** we start writing audio and **what format** we write. Fixing those keeps the call from dropping (`DESTINATION_OUT_OF_ORDER`) and makes Nova Sonic audible.

**Do:**
- Answer in C (not in dialplan).
- Wait for **real inbound RTP** (not comfort noise) before speaking.
- Convert Nova’s PCM16 → **PCMU** (μ‑law) and set `wf.codec = write_codec` before `switch_core_session_write_frame()`.

**Don’t:**
- Don’t enable `CF_PROXY_MEDIA` / `CF_PROXY_MODE`.
- Don’t revert to media bugs—your 1‑leg scenario won’t reliably clock them.
- Don’t fiddle `rtp_timeout_sec` to 0 (hides symptoms).

---

## End‑to‑End Architecture

```
     PSTN/Carrier (Chime VC)
             │   SIP/RTP
             ▼
       FreeSWITCH (A-leg, Sofia)
             │   C app (nova_ai_session)
   ┌─────────┴────────────────────────────────────────────────────────┐
   │ 1) Answer (C)                                                    │
   │ 2) Loop:                                                         │
   │    - read_frame(): caller PCM (RTP→PCM decoded by FS)            │
   │    - send to Java Gateway over TCP                               │
   │    - recv Nova PCM16 → encode to PCMU                            │
   │    - write_frame(): bot audio → FS encodes to RTP                │
   └─────────┬────────────────────────────────────────────────────────┘
             │ TCP (framed)
             ▼
      Java Voice Gateway
             │
             ▼
        Nova Sonic (Bedrock)
```

**Key:** FreeSWITCH internally handles RTP. You use frames. You must align **timing** (wait for first real inbound frame) and **codec** (write PCMU if the leg is PCMU).

---

## FreeSWITCH Dialplan (Minimal)

```xml
<!-- /usr/local/freeswitch/conf/dialplan/public/01-voice-connector.xml -->
<include>
  <extension name="voice_connector_nova">
    <condition field="destination_number" expression="^\+1\d{10}$">
      <!-- Do NOT answer/sleep/park in dialplan -->
      <action application="nova_ai_session" data="10.0.0.68:8085"/>
    </condition>
  </extension>
</include>
```

- No `answer`, no `sleep`, no `park`, no `displace_session`. The C app will answer and drive media.

---

## FreeSWITCH C Application: `nova_ai_session` (Direct Frame Loop)

> File: `freeswitch-module/src/mod_nova_sonic_v3.c`

### 1) Registration

```c
SWITCH_MODULE_LOAD_FUNCTION(mod_nova_sonic_load);
SWITCH_MODULE_DEFINITION(mod_nova_sonic, mod_nova_sonic_load, NULL, NULL);

SWITCH_STANDARD_APP(nova_ai_session_function);

SWITCH_MODULE_LOAD_FUNCTION(mod_nova_sonic_load) {
  switch_application_interface_t *app_interface = NULL;
  *module_interface = switch_loadable_module_create_interface(pool, SWITCH_MODULE_INTERFACE);
  SWITCH_ADD_APP(app_interface, "nova_ai_session",
    "Nova AI Session", "Connect call to Nova Sonic via Java Gateway",
    nova_ai_session_function, "<gatewayHost:port>", SAF_NONE);
  switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_INFO,
    "mod_nova_sonic loaded - nova_ai_session registered\n");
  return SWITCH_STATUS_SUCCESS;
}
```

### 2) μ‑law Encoder (PCM16 → PCMU)

```c
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
  for (size_t i = 0; i < samples; i++) out[i] = linear2ulaw(in[i]);
}
```

### 3) The App Function (Answer + Frame Loop)

```c
static void nova_ai_session_function(switch_core_session_t *session, const char *gateway_addr) {
  switch_channel_t *channel = switch_core_session_get_channel(session);
  const char *uuid = switch_core_session_get_uuid(session);

  // 1) Answer here (not in dialplan)
  if (!switch_channel_test_flag(channel, CF_ANSWERED)) {
    switch_status_t ans = switch_channel_answer(channel);
    switch_log_printf(SWITCH_CHANNEL_SESSION_LOG(session), SWITCH_LOG_INFO,
      "nova_ai_session: answered %s (status=%d)\n", uuid, ans);
  }

  // 2) Connect to Java Gateway (TCP) and send JSON handshake
  int sock = connect_and_handshake_json(gateway_addr, uuid, channel);
  if (sock < 0) {
    switch_log_printf(SWITCH_CHANNEL_SESSION_LOG(session), SWITCH_LOG_ERROR,
      "nova_ai_session: failed to connect/handshake with gateway %s\n", gateway_addr);
    return;
  }

  // 3) Inspect write codec (for logging/debug)
  const switch_codec_t *wcodec = switch_core_session_get_write_codec(session);
  if (wcodec) {
    switch_log_printf(SWITCH_CHANNEL_SESSION_LOG(session), SWITCH_LOG_INFO,
      "Write codec: %s @ %dHz, packet=%dms\n",
      wcodec->implementation->iananame,
      wcodec->implementation->actual_samples_per_second,
      (int)(wcodec->implementation->microseconds_per_packet / 1000));
  } else {
    switch_log_printf(SWITCH_CHANNEL_SESSION_LOG(session), SWITCH_LOG_WARNING,
      "Write codec is NULL; continuing but writes may fail.\n");
  }

  // 4) Start a thread to read bot audio FROM gateway and queue it
  nova_ctx_t *ctx = init_nova_ctx(session, sock);
  start_gateway_recv_thread(ctx); // fills ctx->bot_queue with PCM16 20ms frames (320 bytes)

  // 5) Main loop: read caller frames, send to gateway; once media_ready, write μ-law to channel
  switch_bool_t running = SWITCH_TRUE;
  switch_bool_t media_ready = SWITCH_FALSE;

  while (running && switch_channel_ready(channel)) {
    switch_frame_t *rf = NULL;
    switch_status_t st = switch_core_session_read_frame(session, &rf, SWITCH_IO_FLAG_NONE, 20);

    if (st == SWITCH_STATUS_SUCCESS && rf && rf->data && rf->datalen > 0) {
      // Comfort noise frames are tiny (e.g., 2 bytes). Require real audio >=160 bytes.
      if (rf->datalen >= 160) {
        if (!media_ready) {
          media_ready = SWITCH_TRUE;
          switch_log_printf(SWITCH_CHANNEL_SESSION_LOG(session), SWITCH_LOG_INFO,
            "Media ready on %s (inbound RTP present)\n", uuid);
        }
        // Send caller audio up to gateway/Nova
        send_caller_audio_to_gateway(sock, rf->data, rf->datalen);
        // Optional: debug
        // switch_log_printf(..., "Sent %d bytes to gateway\n", rf->datalen);
      }
    } else if (st != SWITCH_STATUS_BREAK && st != SWITCH_STATUS_SUCCESS) {
      switch_log_printf(SWITCH_CHANNEL_SESSION_LOG(session), SWITCH_LOG_DEBUG,
        "read_frame status=%d\n", st);
    }

    // If we have Nova PCM16 20ms frame (320 bytes), encode to μ-law and play back — BUT ONLY after media_ready
    if (media_ready) {
      uint8_t *bot_pcm16 = NULL; size_t bot_len = 0;
      if (dequeue_bot_frame(ctx, &bot_pcm16, &bot_len) && bot_len >= 320) {
        const int16_t *s16 = (const int16_t *)bot_pcm16;
        uint8_t ulaw[160];
        pcm16_to_ulaw(s16, 160, ulaw);

        switch_frame_t wf = {0};
        wf.data     = ulaw;
        wf.datalen  = 160;         // μ-law 8kHz 20ms
        wf.samples  = 160;
        wf.rate     = 8000;
        wf.channels = 1;
        wf.codec    = (switch_codec_t *)wcodec; // match the session's write codec

        st = switch_core_session_write_frame(session, &wf, SWITCH_IO_FLAG_NONE, 0);
        if (st != SWITCH_STATUS_SUCCESS) {
          switch_log_printf(SWITCH_CHANNEL_SESSION_LOG(session), SWITCH_LOG_DEBUG,
            "write_frame status=%d\n", st);
        } else {
          // switch_log_printf(..., "Wrote 160B μ-law to channel\n");
        }
      }
    }

    switch_yield(20000); // ~20ms pacing
  }

  // Cleanup
  stop_gateway_recv_thread(ctx);
  close(sock);
}
```

### 4) JSON Handshake (C side)

Send once after TCP connect (newline‑terminated):

```json
{"call_uuid":"<uuid>","caller":"${caller_id_number}","sample_rate":8000,"channels":1,"format":"PCM16"}
```

**Note:** The Java gateway is updated to accept this JSON (see below).

---

## Java Gateway: Accept JSON Handshake + Stream Logging

> File: `voice-gateway/src/main/java/com/example/s2s/voipgateway/freeswitch/FreeSwitchAudioHandler.java`

### 1) Accept JSON **or** legacy handshake

```java
static class SessionInfo {
  String callUuid;
  String caller;
  int sampleRate = 8000;
  int channels   = 1;
  String format  = "PCM16";
}

private SessionInfo parseHandshake(String line) {
  if (line == null) return null;
  line = line.trim();
  if (line.startsWith("{")) return parseJsonHandshake(line);
  if (line.startsWith("NOVA_SESSION:")) return parseLegacyHandshake(line);
  return null;
}
```

Minimal JSON parser (or use Jackson/Gson if available):

```java
private SessionInfo parseJsonHandshake(String json) {
  SessionInfo info = new SessionInfo();
  info.callUuid   = extractJsonString(json, "call_uuid");
  info.caller     = extractJsonString(json, "caller");
  String sr       = extractJsonNumber(json, "sample_rate");
  String ch       = extractJsonNumber(json, "channels");
  String fmt      = extractJsonString(json, "format");
  if (sr != null) info.sampleRate = Integer.parseInt(sr);
  if (ch != null) info.channels   = Integer.parseInt(ch);
  if (fmt != null) info.format    = fmt;
  return info;
}
```

### 2) Stream logs (already added)

- Count chunks/bytes from FS → Nova (`socketInput.read(...)` loop).  
- Log first `AudioInputEvent` sent.  
- Mirror logging on Nova → FS write loop to catch “connection reset by peer”.

---

## Why We Don’t Use Media Bugs Here

- Single‑leg inbound call with no B‑leg and no playback/bridge often **doesn’t clock** media → bug callbacks do not fire.
- You burned time trying `displace_session`, `switch_ivr_sleep`, and flags; it remained inconsistent.
- The **direct frame loop** is deterministic, explicit, and production‑proven for voice bots.

---

## Common Failure → Fix

| Symptom | Likely Cause | Fix |
|---|---|---|
| Immediate hangup `DESTINATION_OUT_OF_ORDER` on first write | Writing before channel’s media is fully established | Answer in C, **wait for first real inbound frame** (`datalen ≥ 160`) before any `write_frame` |
| `write_frame status: 1` repeatedly | Codec mismatch (you wrote PCM16 but leg is PCMU) | Convert PCM16 → **PCMU** (μ‑law) and set `wf.codec = write_codec` |
| Java shows `Connection reset by peer` | FS closed TCP because call hung up | Fix hangup root cause (timing/codec) |
| Only `2 bytes` inbound frames | Comfort noise (CN/CNG), not speech | Don’t mark `media_ready` on CN; wait for `≥160` bytes |
| Media bug callbacks only at CLOSE | No active media clock | Don’t use bugs here; use direct frame loop |

---

## Build & Deploy Quick Notes

- **C module**: compile on the FS host against the running FS version headers. Copy `mod_nova_sonic_v3.so` into `/usr/local/freeswitch/mod/`, then `fs_cli -x "reload mod_nova_sonic_v3"` or restart FS.
- **Dialplan**: `reloadxml` after updating. Ensure *only* `nova_ai_session` is invoked (no `answer` / `park` / `sleep`).  
- **Gateway**: rebuild/redeploy after JSON handshake parser change.

---

## Test Plan (5 minutes)

1. **Place a call** to the VC DID.  
2. **Gateway log** should show:  
   - “Handshake parsed (JSON)”  
   - “FreeSWITCH → Nova: received N chunks, M bytes” (increments)  
   - “First AudioInputEvent sent …”  
3. **FS log** should show:  
   - “answered <uuid>”  
   - When first `read_frame ≥160B`: “Media ready …”  
   - “Wrote 160B μ‑law frame to channel” (repeating)  
4. **Phone**: you should hear Nova within ~1–2 seconds.

If call still drops, capture:  
- the line that prints write codec (iananame/Hz/ms),  
- the first `write_frame status`,  
- the nearest `switch_core_media.c` hangup lines.

---

## Optional Enhancements

- **Backpressure**: cap bot playback queue to a few frames; drop oldest on overflow.
- **Silence control**: if no inbound ≥160B frames for N seconds, prompt “Didn’t catch that—are you still there?”
- **Barge‑in**: when inbound frame energy crosses threshold, pause Nova playback (stop writing frames) until user is done.
- **Metrics**: count frames up/down, average size, end‑to‑end latency.

---

## Appendix A — Minimal C Helpers (Sketches)

```c
int connect_and_handshake_json(const char *addr, const char *uuid, switch_channel_t *channel);

void send_caller_audio_to_gateway(int sock, const void *buf, size_t len);

typedef struct {
  switch_core_session_t *session;
  int sock;
  switch_mutex_t *mutex;
  switch_thread_cond_t *cond;
  // a simple ring buffer/queue for bot frames (PCM16 20ms = 320B)
  // implement: dequeue_bot_frame(ctx, &ptr, &len)
} nova_ctx_t;

nova_ctx_t *init_nova_ctx(switch_core_session_t *session, int sock);
void start_gateway_recv_thread(nova_ctx_t *ctx);
switch_bool_t dequeue_bot_frame(nova_ctx_t *ctx, uint8_t **buf, size_t *len);
void stop_gateway_recv_thread(nova_ctx_t *ctx);
```

---

## Appendix B — Handshake (Examples)

**JSON (preferred):**
```json
{"call_uuid":"233cc87b-547d-4408-a846-0b10f81d8a3c","caller":"+14435383548","sample_rate":8000,"channels":1,"format":"PCM16"}
```

**Legacy (still accepted by gateway):**
```
NOVA_SESSION:233cc87b-547d-4408-a846-0b10f81d8a3c:CALLER:+14435383548:SR:8000:CH:1:FORMAT:PCM16
```

---

## Final Checklist

- [ ] Dialplan calls only `nova_ai_session` (no answer/park).  
- [ ] C app answers channel at start.  
- [ ] Wait for first **real** inbound frame (`≥160B`) before any write.  
- [ ] Encode Nova PCM16 → PCMU (160B) and set `wf.codec = write_codec`.  
- [ ] JSON handshake support added in Java gateway.  
- [ ] Observe logs: “Media ready…”, steady chunks both directions.  

Ship this, then iterate on barge‑in sensitivity and silence prompts.
