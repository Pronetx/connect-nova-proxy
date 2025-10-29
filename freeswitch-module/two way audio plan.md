# FreeSWITCH Realtime Voice Debug Plan  
## (Caller audio not reaching Nova Sonic / upstream path silent)

This doc is for you to continue implementation + debugging. The current situation:

- ✅ Bot → Caller audio path works  
  - FreeSWITCH module is receiving PCM frames **from** the Java gateway.
  - We see log lines like:  
    `Received 320 bytes of PCM audio from gateway`
  - So audio coming *out of Nova Sonic* and back to the caller is alive.

- ❌ Caller → Bot audio path is dead  
  - Nova Sonic throws `Timed out waiting for input events` after ~60s.
  - That means Nova Sonic never got caller audioInput frames.
  - We see NO logs for “Sent %d bytes” to gateway.
  - We see NO logs from the `READ_REPLACE` branch of the media bug.
  - So FreeSWITCH is not sending caller audio upstream to the Java gateway.

**Conclusion:**  
The media bug’s READ side is not firing or not wired, so the Java side never gets caller audio, so Nova Sonic times out.

We are going to fix that now.

---

## Goal of this pass

1. Confirm that our media bug is actually being invoked for inbound caller audio.
2. Make sure we’re attaching the bug with the correct flags.
3. Verify we’re attaching the bug to the right channel UUID.
4. Once we can see caller PCM in the callback, we’ll send it over the socket to the gateway and the Bedrock timeout should go away.

---

## Step 1. Instrument the media bug callback

In `mod_nova_sonic.c` (or `mod_nova_ai.c`, whatever file holds the bug callback), at the VERY TOP of `nova_ai_bug_callback`, before the `switch(type)` block, add this:

```c
switch_log_printf(
    SWITCH_CHANNEL_LOG,
    SWITCH_LOG_DEBUG,
    "nova_ai_bug_callback fired with type=%d\n",
    type
);
```

Now, inside EACH branch where we call `switch_core_media_bug_read(...)`, log the frame info.

Example instrumentation:

```c
case SWITCH_ABC_TYPE_READ_REPLACE:
    if (switch_core_media_bug_read(bug, &frame, SWITCH_FALSE) == SWITCH_STATUS_SUCCESS) {
        switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_DEBUG,
            "READ_REPLACE got frame: %u bytes, %u samples, rate=%d\n",
            frame->datalen, frame->samples, frame->rate
        );

        // Existing logic:
        // - write frame->data into input buffer
        // - signal cond var for the send thread
        // - (we will forward to gateway)
    }
    break;

case SWITCH_ABC_TYPE_WRITE_REPLACE:
    if (switch_core_media_bug_read(bug, &frame, SWITCH_FALSE) == SWITCH_STATUS_SUCCESS) {
        switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_DEBUG,
            "WRITE_REPLACE got frame: %u bytes, %u samples, rate=%d\n",
            frame->datalen, frame->samples, frame->rate
        );

        // Existing logic:
        // - pop bot PCM frame from playback queue
        // - copy to frame->data for playback to caller
        // - log if we injected audio
    }
    break;

default:
    switch_log_printf(SWITCH_CHANNEL_LOG, SWITCH_LOG_DEBUG,
        "Unhandled callback type=%d in nova_ai_bug_callback\n",
        type
    );
    break;
```

Why:
- Right now, we *assume* caller audio should arrive under `SWITCH_ABC_TYPE_READ_REPLACE`.  
- But the logs show that branch is never hit.  
- We need to KNOW which `type` is actually firing during the call and whether we’re getting non-zero `frame->datalen` anywhere.

After you add these logs, rebuild the module, restart FreeSWITCH, place a call again, and then `grep "nova_ai_bug_callback"` and `grep "READ_REPLACE got frame"` in `/usr/local/freeswitch/log/freeswitch.log`.

This will tell us:
- Are we getting ANY callback on READ side?
- Are we only getting WRITE side?
- Are we even seeing frames on READ with length > 0?

---

## Step 2. Update the media bug flags

When we attach the bug with `switch_core_media_bug_add(...)`, right now we’re (from the notes) using:

```c
SMBF_READ_REPLACE | SMBF_WRITE_REPLACE | SMBF_ANSWER_REQ
```

We should expand the flags to also include the STREAM variants so FreeSWITCH guarantees audio capture:

```c
SMBF_READ_STREAM | SMBF_WRITE_STREAM |
SMBF_READ_REPLACE | SMBF_WRITE_REPLACE |
SMBF_ANSWER_REQ
```

Why:
- `*_REPLACE` lets us intercept/modify the frame.
- `*_STREAM` is more "tap-style" and tends to ensure our callback gets invoked for that direction.
- `SMBF_ANSWER_REQ` is fine because we answer() in the dialplan before we call `nova_ai_start`.

**Action:**  
Update that flags argument in `switch_core_media_bug_add`, rebuild, restart FreeSWITCH, retest.

---

## Step 3. Confirm we are attaching to the correct channel / leg

We *think* we’re doing:

```xml
answer()
nova_ai_start()
park()
```

We want to be 100% sure that:
- `nova_ai_start()` is running on the same UUID that’s actually receiving the PSTN audio from Chime Voice Connector.
- We are not accidentally attaching the bug to a “B-leg” or a bridged leg that never gets caller’s mic.

Add this log in `nova_ai_start`, right before you call `switch_core_media_bug_add`:

```c
switch_log_printf(
    SWITCH_CHANNEL_LOG,
    SWITCH_LOG_INFO,
    "nova_ai_start: attaching media bug to channel %s caller=%s\n",
    switch_core_session_get_uuid(session),
    caller_id_number
);
```

Then after you place a test call:
- `grep` freeswitch.log for `nova_ai_start: attaching media bug`.
- Confirm that UUID matches the UUID shown in logs like `Dialplan: ... Action answer()` and `Starting Nova Sonic session`.

If those UUIDs differ, you’re attaching to the wrong leg and you’ll never see caller audio.

---

## Step 4. Verify the send thread is actually getting data

In your module you likely have:
- A buffer `input_stream->audio_buffer`
- A condition variable `input_stream->cond`
- A “send thread” that does something like:
  ```c
  switch_mutex_lock(...);
  switch_thread_cond_wait(...);
  // read buffered audio
  // write to gateway socket
  switch_mutex_unlock(...);
  ```

And in `READ_REPLACE` you do:
```c
switch_mutex_lock(...);
switch_buffer_write(audio_buffer, frame->data, frame->datalen);
switch_thread_cond_signal(cond);
switch_mutex_unlock(...);
```

If `READ_REPLACE` is never firing, that condition variable never signals, so the send thread just waits forever and never logs “Sent X bytes”. That perfectly matches what we’re seeing.

So after instrumenting per Step 1, if you still do NOT see “READ_REPLACE got frame”, that’s why Nova never sees audio.

---

## Step 5. After instrumenting, what we expect to see

Make another test call and then pull `/usr/local/freeswitch/log/freeswitch.log`. You should now see things like:

- `nova_ai_start: attaching media bug to channel 0ce1e259-6164-47a4-926d-448954ee49df caller=+1443...`
- `nova_ai_bug_callback fired with type=7`
- `WRITE_REPLACE got frame: 320 bytes, 160 samples, rate=8000`
- …and hopefully…
- `READ_REPLACE got frame: 320 bytes, 160 samples, rate=8000`
  - (or some other branch that clearly corresponds to inbound caller audio)

Important:  
If you ONLY see WRITE_REPLACE logs and never READ_REPLACE logs, then we know FreeSWITCH is only calling us for outbound audio (bot -> caller). That means we’re not actually tapping the caller leg on READ. Then we fix that by adjusting flags / which leg we attach to.

If you DO see READ_REPLACE logs with non-zero `frame->datalen`, then we’re finally getting caller PCM. At that point:

- Take `frame->data` from READ_REPLACE,
- Push that into `input_stream->audio_buffer`,
- The send thread should wake up, pull from the buffer, and write those bytes straight to the Java gateway socket in 20ms (640-byte) frames.

That will feed Nova Sonic and kill the 60s timeout.

---

## Step 6. Reminder on the Java Gateway socket contract

On the Java side:
- We must NOT use `BufferedReader.readLine()` on the same socket we later use for raw PCM streaming.
- We read the handshake header from raw `InputStream` exactly once (either newline-delimited or length-prefixed).
- After the header, we keep using the same `InputStream` to read fixed-size PCM frames from FreeSWITCH.

Example safe pattern for handshake + audio:

```java
InputStream in = socket.getInputStream();
OutputStream out = socket.getOutputStream();

// Read handshake JSON up to '\n' (temporary framing)
ByteArrayOutputStream headerBuf = new ByteArrayOutputStream();
while (true) {
    int b = in.read();
    if (b == -1) throw new IOException("socket closed during handshake");
    if (b == '\n') break;
    headerBuf.write(b);
}
String headerJson = headerBuf.toString(StandardCharsets.UTF_8);
// parse headerJson into (callUuid, caller, sample_rate, etc.)

// Spawn Thread A (FreeSWITCH -> Nova Sonic)
new Thread(() -> {
    byte[] frameBuf = new byte[640]; // 20ms PCM16 mono @16kHz
    while (session.running) {
        int readSoFar = 0;
        while (readSoFar < 640) {
            int n = in.read(frameBuf, readSoFar, 640 - readSoFar);
            if (n < 0) { session.running = false; break; }
            readSoFar += n;
        }
        if (!session.running) break;

        nova.sendAudioInputFrame(frameBuf); // forward to Nova Sonic
        session.markUserVoice(frameBuf);    // update barge-in/silence state
    }
}, "FS->Nova-" + callUuid).start();

// Spawn Thread B (Nova Sonic -> FreeSWITCH)
new Thread(() -> {
    while (session.running) {
        NovaFrame nf = nova.takeNextOutputFrame(); // blocking pull from Nova Sonic
        if (nf.isAudioFrame()) {
            byte[] pcmFrame = nf.get640BytePcm16Mono();
            synchronized (out) {
                out.write(0x01);        // audio frame tag
                out.write(pcmFrame);    // 640 bytes PCM16
                out.flush();
            }
        } else if (nf.isHangupSignal() || nf.isToolUse("hangupCall")) {
            synchronized (out) {
                out.write(0x02); // control tag
                out.write("{\"type\":\"hangup\"}\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            session.running = false;
        }
    }
}, "Nova->FS-" + callUuid).start();
```

This is still correct. You don’t need to change this yet. You only need to confirm that Thread A is now actually receiving non-zero PCM frames from FreeSWITCH once we fix the media bug READ side.

---

## Step 7. Success criteria for this round

We consider this round a success when:
1. During a test call, freeswitch.log shows `nova_ai_bug_callback` firing repeatedly with at least one branch giving us non-zero `frame->datalen` for inbound caller audio.
2. After those changes, Nova Sonic **stops** throwing `Timed out waiting for input events`.
3. You start seeing logs from the Java gateway that indicate it’s streaming `audioInput` into Nova Sonic.
4. Optional but ideal: You start to hear Nova Sonic speak back to you on the phone (we already see “Received 320 bytes” logs, so downstream is close).

Once that’s true, we’ll move on to tightening barge-in and silence handling. But first we need caller audio upstream to live.

---

## TL;DR instructions you can act on immediately

- Add debug logging at the top of `nova_ai_bug_callback` to print the callback `type` every time.
- Log frame size/rate in every branch you handle (`READ_REPLACE`, `WRITE_REPLACE`, etc.).
- Add STREAM flags to `switch_core_media_bug_add`:
  ```c
  SMBF_READ_STREAM | SMBF_WRITE_STREAM |
  SMBF_READ_REPLACE | SMBF_WRITE_REPLACE |
  SMBF_ANSWER_REQ
  ```
- Log in `nova_ai_start` which channel UUID you're attaching to.
- Rebuild module, restart FreeSWITCH, place a call, pull `/usr/local/freeswitch/log/freeswitch.log`, and confirm:
  - We’re getting READ-side callback calls,
  - We’re seeing non-zero `frame->datalen`,
  - The UUID matches the answered channel.

If READ still never fires after that, send back the new freeswitch.log so we can look at which `type` values *are* coming in and map them properly.