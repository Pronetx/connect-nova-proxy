
# Nova Sonic → FreeSWITCH **Downlink Audio Fix** (PCM16 ↔ μ-law)
**Date:** 2025‑10‑29  
**Owner:** Pronetx / CxPortal Voice Gateway  
**Scope:** Make Nova’s TTS/audio replies audible to the caller when using the FreeSWITCH custom module + Java Nova gateway.

---

## TL;DR (What to change)
- **Java (Nova Gateway):** _Stop sending μ‑law_. Send **raw PCM16 LE at 8 kHz** in exact **320‑byte frames (20 ms)** to FreeSWITCH.
- **FreeSWITCH C module:** Keep converting **PCM16 → PCMU** on **downlink** before `switch_core_session_write_frame`. (Uplink already fixed: **PCMU → PCM16** before sending to Nova.)
- **Validate with logs:** You should see in **Java**: “Nova → FS: wrote 320 bytes” and in **FreeSWITCH**: “Received 320 bytes …” then “Wrote 160 bytes of μ‑law …”.

---

## Current State
- **Uplink (Caller → Nova):** ✅ Working. FreeSWITCH negotiated **PCMU** with SIP. Module decodes **PCMU (160B)** → **PCM16 (320B)** and streams to Nova.
- **Downlink (Nova → Caller):** ❌ Not audible. Java side was queuing/sending **μ‑law (160B)** while the FS module expects **PCM16 (320B)** from the gateway, then it encodes to μ‑law before writing to the channel.

**Root Cause:** **Format mismatch** on the downlink. Java produced μ‑law frames, but the C module expects PCM16 frames from the gateway. Result: FS never injected valid audio into RTP.

**Target Design:** One place only does the μ‑law work → **C module**. Java always sends **PCM16/320B**. C module converts to **PCMU/160B** and writes to the channel with the correct codec set.

---

## Changes Required

### 1) Java (Nova → FreeSWITCH): send **PCM16/320B** frames
Remove **ulaw** on the Java side (e.g., any `QueuedUlawInputStream`) and queue **PCM16**.

**Ingress from Nova (Audio Output Handler):**
```java
// Fields
private final BlockingQueue<byte[]> novaToFsPcmQueue = new LinkedBlockingQueue<>(400); // ~8s at 50 fps
private final ByteArrayOutputStream pcmAccumulator = new ByteArrayOutputStream(4096);

// Called per Nova audio chunk (base64 LPCM16 LE 8kHz mono)
void onNovaAudioChunk(String base64Lpcm) {
    byte[] pcm = Base64.getDecoder().decode(base64Lpcm); // PCM16 LE
    synchronized (pcmAccumulator) {
        try {
            pcmAccumulator.write(pcm);
            // Drain in exact 20ms (320-byte) frames
            byte[] acc = pcmAccumulator.toByteArray();
            int off = 0;
            while (acc.length - off >= 320) {
                byte[] frame = Arrays.copyOfRange(acc, off, off + 320);
                if (!novaToFsPcmQueue.offer(frame)) {
                    // Optional: drop oldest to avoid infinite growth
                    novaToFsPcmQueue.poll();
                    novaToFsPcmQueue.offer(frame);
                }
                off += 320;
            }
            pcmAccumulator.reset();
            pcmAccumulator.write(acc, off, acc.length - off);
        } catch (IOException e) {
            LOG.error("PCM accumulator error", e);
        }
    }
}
```

**Writer thread (Nova → FS):**
```java
new Thread(() -> {
    LOG.info("Nova → FreeSWITCH audio stream");
    try (OutputStream out = socket.getOutputStream()) {
        socket.setTcpNoDelay(true);
        int frames = 0;
        while (active && !socket.isClosed()) {
            byte[] frame = novaToFsPcmQueue.poll(200, TimeUnit.MILLISECONDS);
            if (frame == null) continue; // silence gap
            out.write(frame);  // 320 bytes PCM16
            out.flush();
            frames++;
            if (frames % 50 == 0) {
                LOG.info("Nova → FS: wrote {} frames ({} bytes)", frames, frames * 320);
            }
        }
    } catch (Exception e) {
        LOG.error("Error in Nova → FreeSWITCH audio stream", e);
    }
}, "Nova-to-FS-" + sessionId).start();
```

**Add instrumentation when enqueuing first frame:**
```java
LOG.info("Nova → FS: enqueued 320-byte PCM16 frame");
```

> **Expected new logs (Java):**
> - “Nova → FS: **enqueued** 320-byte PCM16 frame”
> - “Nova → FS: **wrote** N frames (N×320 bytes)”

---

### 2) FreeSWITCH C module: keep doing the **μ‑law** encode on downlink
Ensure socket reads **exactly 320 bytes** per frame and then convert.

**Exact-frame socket read (downlink intake):**
```c
// Expect 320 bytes PCM16 per frame from gateway
uint8_t bot_buf[320];
int need = 320, off = 0;
while (need > 0) {
    ssize_t got = recv(ctx->gateway_socket, bot_buf + off, need, 0);
    if (got <= 0) {
        switch_log_printf(SWITCH_CHANNEL_SESSION_LOG(session), SWITCH_LOG_INFO,
                          "Gateway socket closed or error\n");
        goto END_SESSION;
    }
    off  += (int)got;
    need -= (int)got;
}
// bot_buf now holds 320B PCM16 (160 samples)
```

**PCM16 → PCMU, then write to channel:**
```c
const switch_codec_t *write_codec = switch_core_session_get_write_codec(session);
if (!write_codec) {
    switch_log_printf(SWITCH_CHANNEL_SESSION_LOG(session), SWITCH_LOG_WARNING,
                      "Write codec is NULL; writes may fail\n");
}
// Convert PCM16 -> PCMU
const int16_t *pcm16 = (const int16_t*)bot_buf;
uint8_t ulaw[160];
pcm16_to_ulaw(pcm16, 160, ulaw);

// Prepare frame (20 ms @ 8kHz)
switch_frame_t wf = {0};
wf.data     = ulaw;
wf.datalen  = 160;   // μ-law bytes
wf.samples  = 160;   // samples
wf.rate     = 8000;
wf.channels = 1;
wf.codec    = (switch_codec_t *)write_codec;

switch_status_t st = switch_core_session_write_frame(session, &wf, SWITCH_IO_FLAG_NONE, 0);
if (st == SWITCH_STATUS_SUCCESS) {
    switch_log_printf(SWITCH_CHANNEL_SESSION_LOG(session), SWITCH_LOG_DEBUG,
                      "Wrote 160 bytes of μ-law audio to channel\n");
} else {
    switch_log_printf(SWITCH_CHANNEL_SESSION_LOG(session), SWITCH_LOG_DEBUG,
                      "write_frame returned status: %d\n", st);
}
```

**One-time visibility for codec:**
```c
const switch_codec_t *wc = switch_core_session_get_write_codec(session);
if (wc) {
    switch_log_printf(SWITCH_CHANNEL_SESSION_LOG(session), SWITCH_LOG_INFO,
        "Write codec: %s @ %dHz, ptime=%dms\n",
        wc->implementation->iananame,
        wc->implementation->actual_samples_per_second,
        (int)(wc->implementation->microseconds_per_packet / 1000));
}
```

> If your trunk negotiates **PCMA** instead of **PCMU**, replace μ‑law with A‑law conversion when building `wf`.

---

## Validation Checklist

### During a call, verify logs show:
**Java gateway**
- `Starting Nova → FreeSWITCH audio stream`
- `Nova → FS: enqueued 320-byte PCM16 frame`
- `Nova → FS: wrote 50 frames (16000 bytes)` (and keeps incrementing)
- `Session started, playing greeting.`
- `Text output [ASSISTANT]: …` (already present)

**FreeSWITCH**
- `Received 320 bytes of PCM audio from gateway` (downlink intake per frame)
- `Wrote 160 bytes of μ-law audio to channel` (successful injection)
- No repeated `write_frame returned status: 1`
- No `DESTINATION_OUT_OF_ORDER` hangup

If you **see intake but no writes**, confirm:
- `media_ready == true` before writing frames
- `write_codec` not NULL (expect `PCMU`)
- Your dialplan doesn’t double‑answer (let the app handle answer if designed so)

---

## Troubleshooting

1) **Still no audio to caller**
- Check that Java is sending **320B** frames (not 160B). Your periodic counter should show `… wrote N frames (N×320 bytes)`.
- FS logs should show **exactly 320 bytes** received per frame.

2) **`write_frame returned status: 1`**
- The channel may not be ready or the codec mismatched.
- Force codecs in dialplan (example):
  ```xml
  <action application="set" data="absolute_codec_string=PCMU"/>
  ```
- Ensure there’s no residual `proxy media` flags set on the channel for this app path.

3) **Audio sounds sped up or choppy**
- Double-check the chunk size and rate:
  - Exactly **20 ms** frames: **320B PCM16** at 8kHz mono
  - Do **not** send partial frames
  - Endianness: **little‑endian** (Java byte order OK for decoded base64)

4) **Barge‑in works but audio still silent**
- Confirm the Java downlink code path is triggered (enqueue logs).
- Ensure the FS module’s socket read loop isn’t stuck (must read until 320B).

---

## Optional: A‑law Support (PCMA)
If the write codec is **PCMA**, use A‑law encoder instead of μ‑law prior to `write_frame`. Provide an `pcm16_to_alaw()` and set `wf.datalen = 160` similarly.

---

## Smoke Test Procedure
1. Place a call; wait for Nova’s greeting.
2. Say “Hello”; confirm **ASR text** appears in Java logs (it does now).
3. Watch **Java downlink logs**: “enqueued 320‑byte PCM16” and “wrote 50 frames (16000 bytes)…”
4. Watch **FreeSWITCH logs**: “Received 320 bytes of PCM audio from gateway” and “Wrote 160 bytes of μ‑law audio to channel”.
5. You should hear Nova within ~200–500 ms.

---

## Why this works
- We removed the ambiguity of **where** ulaw/PCM16 conversion happens.
- The gateway and FS now share a **fixed 20 ms PCM16 framing contract** on the socket.
- FreeSWITCH owns the μ‑law encoding for the SIP leg, guaranteeing compatibility with negotiated codecs and ptime.

---

## Appendix: Quick Diff Summary
- **Java**
  - **Removed:** ulaw queues/streams
  - **Added:** PCM16 accumulator → 320B framing → queue → socket writer
  - **Logs:** “enqueued 320‑byte PCM16”, “wrote N frames”
- **C module**
  - **Downlink:** exact 320B reads → PCM16 → μ‑law → `write_frame`
  - **Uplink:** already doing PCMU → PCM16 (keep)
  - **Logs:** “Received 320 bytes…”, “Wrote 160 bytes of μ‑law…”

---

## Done
Once these two patches are in place, Nova’s replies are audible; ASR remains solid; barge‑in continues to function.
