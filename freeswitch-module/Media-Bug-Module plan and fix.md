# FreeSWITCH Realtime AI Media Bridge (Nova Sonic Integration)

## Purpose

We're moving off the mjSIP prototype and into a split architecture that can scale, be observed, and eventually hardened (TLS/SRTP, call recording, routing, handoff to Connect, etc.).

**Old world (mjSIP):**
- One JVM handled everything:
  - SIP signaling
  - RTP send/receive
  - Nova Sonic (Bedrock) bidirectional session
  - Barge-in
  - Silence/reprompt
  - Consent (“Can I text this to you?”)
  - Hangup
  - Connect attribute updates

**New world (FreeSWITCH):**
- FreeSWITCH = telephony edge (SIP, RTP, SRTP, jitter buffer, CDRs)
- Our Java AI Gateway = “the brain” (Nova Sonic, policy, conversation control)
- A custom FreeSWITCH media bug module = the live audio bridge between the two

Why this is good:
- We get production-grade SIP/RTP behavior (and SRTP later) from FreeSWITCH instead of homegrown mjSIP code.
- We keep all the call policy and AI/LLM logic in Java, where we already built it.
- We get back low-latency full-duplex voice with barge-in.

---

## High-Level Call Flow

1. Caller dials a PSTN number.
2. Amazon Chime Voice Connector sends SIP/RTP to FreeSWITCH.
3. FreeSWITCH answers the call.
4. The dialplan runs a custom application `nova_ai_start`.
5. `nova_ai_start`:
   - Allocates a per-call session.
   - Opens a persistent socket to the Java AI Gateway.
   - Sends session metadata (call UUID, caller number, sample rate).
   - Attaches a FreeSWITCH **media bug** to the channel.
6. The media bug:
   - Streams caller audio frames to the AI Gateway in real time.
   - Receives assistant audio frames from the AI Gateway and injects them back into the call in real time.
7. The AI Gateway:
   - Maintains the Nova Sonic bidirectional session.
   - Sends caller PCM → Nova Sonic `audioInput`.
   - Sends Nova Sonic `audioOutput` PCM → FreeSWITCH to play to the caller.
   - Runs barge-in logic.
   - Runs silence/reprompt (“are you still there?”).
   - Handles “consent” prompts like “Can I text this to you?” and fallback if no response.
   - Sends hangup/transfer decisions back to FreeSWITCH.
8. When the AI decides to hang up, it sends a control message. The module hangs up the channel; FreeSWITCH sends BYE; call ends cleanly.

Result: Caller talks to AI voice agent, with barge-in, through FreeSWITCH instead of mjSIP.

---

## Components

### FreeSWITCH
- Handles SIP and RTP. Can run from an AWS Marketplace FreeSWITCH AMI or Docker with `--network host`.
- Dialplan invokes `nova_ai_start`.
- Hosts the media bug which taps audio and injects audio.
- Eventually will give us CDRs, call stats, SRTP/TLS, etc.

### `mod_nova_ai` (custom FreeSWITCH module in C)
- Exposes a dialplan application called `nova_ai_start`.
- Allocates a per-call state struct (`nova_ai_session_t`).
- Opens a TCP/WebSocket connection to the Java AI Gateway.
- Sends a `session_start` header on connect.
- Attaches a media bug via `switch_core_media_bug_add`.
- Media bug callback:
  - On READ: caller audio → AI Gateway.
  - On WRITE: bot audio from queue → caller.
- Listens for control messages (like hangup) from the AI Gateway.

### AI Gateway Service (Java)
- Accepts one persistent socket per call from `mod_nova_ai`.
- Reads handshake header for that call (UUID, caller, sample rate).
- Starts the Nova Sonic streaming session.
- Thread A:
  - Reads 20ms PCM16 mono frames from FreeSWITCH (caller audio),
  - streams them into Nova Sonic as `audioInput`.
- Thread B:
  - Reads Nova Sonic `audioOutput`,
  - streams 20ms PCM16 mono frames back to FreeSWITCH.
- Implements:
  - barge-in (stop bot audio when caller starts talking),
  - silence reprompt logic,
  - consent/non-response fallback,
  - AI-triggered hangup,
  - Connect attribute updates.

---

## Dialplan Integration

We'll have something like:

```xml
<action application="answer"/>
<action application="nova_ai_start" data="ws://10.0.0.68:8085/session"/>
<action application="park"/>
```

- `answer`: pick up the call.
- `nova_ai_start`: our module function that starts the AI session for this channel.
- `park`: keep the call up while the media bug and AI Gateway run.

`data` (in `nova_ai_start`) gives the AI Gateway endpoint.

---

## Per-Call Session Struct in `mod_nova_ai`

We'll track everything about the active call in a struct. The module allocates one per call.

```c
typedef struct {
    switch_core_session_t *session;
    switch_media_bug_t *bug;

    char call_uuid[SWITCH_UUID_FORMATTED_LENGTH + 1];
    char caller_id_number[64];

    // Socket to AI Gateway (TCP or WebSocket)
    int sock_fd;

    // Queue of PCM frames waiting to play to the caller
    switch_queue_t *playback_queue;

    // State flags
    switch_bool_t running;
    switch_bool_t barge_active;

    switch_mutex_t *mutex;
} nova_ai_session_t;
```

We:
- Initialize this in `nova_ai_start`.
- Store it (e.g. as channel private data).
- Use it in the media bug callback.
- Tear it down on call close.

---

## `nova_ai_start` (Dialplan App)

What `nova_ai_start` does, step by step:

1. Get call UUID and caller ID from the channel/session.
2. Allocate and initialize `nova_ai_session_t ns`.
   - `ns->running = SWITCH_TRUE`
   - `ns->sock_fd = connect_to_ai_gateway(data)` // `data` is `ws://10.0.0.68:8085/session`
   - create `ns->playback_queue`
3. Send a `session_start` header to the AI Gateway over `ns->sock_fd`.
4. Attach a media bug:

```c
switch_core_media_bug_add(
    session,
    "nova_ai_media_bug",
    NULL,
    nova_ai_bug_callback,
    ns, // user_data
    0,
    SMBF_READ_STREAM | SMBF_WRITE_STREAM | SMBF_READ_REPLACE | SMBF_WRITE_REPLACE,
    &ns->bug
);
```

5. Start a tiny socket-reader thread on the FreeSWITCH side that:
   - reads frames/control messages from the AI Gateway,
   - enqueues PCM audio into `ns->playback_queue`,
   - if it sees a hangup control, calls `switch_channel_hangup(...)`.

At this point:
- The call is answered.
- The AI Gateway knows about it.
- The media bug is active.

---

## Media Bug Callback

The media bug callback is invoked for audio flowing through the channel. We care about two phases:

### 1. Caller → AI Gateway (READ side)

When invoked with `SWITCH_ABC_TYPE_READ_REPLACE`:
- FreeSWITCH gives us a `frame` with caller audio.
- That frame is already decoded PCM16 mono (e.g. 16 kHz).
- We forward that PCM directly to the AI Gateway over `ns->sock_fd`.

### 2. AI Gateway → Caller (WRITE side)

When invoked with `SWITCH_ABC_TYPE_WRITE_REPLACE`:
- We get a `frame` that is about to go out to the caller.
- We pop the next bot audio frame (PCM16 mono) from `ns->playback_queue`.
- We copy it into `frame->data`.
- If no bot audio is available, we zero-fill `frame->data` (silence).

Skeleton:

```c
static switch_bool_t nova_ai_bug_callback(
    switch_media_bug_t *bug,
    void *user_data,
    switch_abc_type_t type
) {
    nova_ai_session_t *ns = (nova_ai_session_t *)user_data;
    switch_frame_t *frame;

    switch (type) {

    case SWITCH_ABC_TYPE_READ_REPLACE:
        // Caller audio flowing INTO FreeSWITCH
        if (switch_core_media_bug_read(bug, &frame, SWITCH_FALSE) == SWITCH_STATUS_SUCCESS) {
            // frame->data: PCM16 mono samples
            // frame->samples: number of samples (e.g. ~320 for 20ms @16kHz)
            // Send this PCM frame to AI Gateway on ns->sock_fd.
            // (We'll talk about framing below.)
        }
        break;

    case SWITCH_ABC_TYPE_WRITE_REPLACE:
        // Audio flowing OUT to the caller
        if (switch_core_media_bug_read(bug, &frame, SWITCH_FALSE) == SWITCH_STATUS_SUCCESS) {
            // Pull one bot frame from ns->playback_queue
            // If present: copy PCM16 into frame->data
            // If absent: memset(frame->data, 0, frame->datalen) for silence
        }
        break;

    case SWITCH_ABC_TYPE_INIT:
        // Called when bug is first attached
        break;

    case SWITCH_ABC_TYPE_CLOSE:
        // Channel tearing down
        ns->running = SWITCH_FALSE;
        // close ns->sock_fd, free queue, etc.
        break;

    default:
        break;
    }

    return SWITCH_TRUE;
}
```

### Hangup from AI

We’ll have a separate socket-reader thread in `mod_nova_ai` that:
- Reads data from `ns->sock_fd`.
- Distinguishes between:
  - bot PCM frames
  - control messages (like hangup)
- If control message `"hangup"` arrives:
  - Calls `switch_channel_hangup(switch_core_session_get_channel(ns->session), SWITCH_CAUSE_NORMAL_CLEARING);`
  - Sets `ns->running = SWITCH_FALSE`.

---

## Socket Protocol Between FreeSWITCH Module and AI Gateway

We are going to run ONE persistent TCP/WebSocket connection per call.

That connection carries:
1. A one-time handshake header (`session_start` JSON).
2. Streaming caller audio from FreeSWITCH → AI Gateway.
3. Streaming bot audio and control signals from AI Gateway → FreeSWITCH.

### Handshake / Session Start

Right after `nova_ai_start` connects to the AI Gateway, it sends session metadata. Example JSON:

```json
{
  "type": "session_start",
  "call_uuid": "33f16454-77c9-464b-9f1f-68855329ed82",
  "caller": "+14435383548",
  "sample_rate": 16000,
  "format": "PCM16_MONO"
}
```

We need a framing rule. Two options:

#### Option A (preferred): length-prefixed
- Send a 4-byte big-endian integer N.
- Then send N bytes of UTF-8 JSON.
- After that, immediately start binary audio frames (no newline).

#### Option B (acceptable for first working version): newline-delimited
- Send the JSON followed by a single `\n`.
- From then on, send only raw binary frames, no more text lines.
- The Java side will read bytes from the raw InputStream until `\n` just once, parse JSON, and then stop doing line-oriented reads forever.

**Important:**  
Do NOT use `BufferedReader.readLine()` and then later try to use `socket.getInputStream()` for raw frames. `BufferedReader` can over-read and buffer ahead, which caused the “11 second then disconnect” bug we saw. We must manually read the header from the raw InputStream, then reuse that same InputStream for audio.

### Audio Frame Format

We’re standardizing on 20ms PCM16 mono @ 16 kHz.

- 16,000 samples/sec × 0.020 sec = 320 samples.
- PCM16 = 2 bytes/sample.
- So each audio frame is exactly **640 bytes**.

#### FreeSWITCH → AI Gateway (caller audio upstream)
- Send raw 640-byte PCM16 frames back-to-back, continually.
- No per-frame header needed in this direction.

#### AI Gateway → FreeSWITCH (bot audio downstream + control)
We need to tell FreeSWITCH whether we're sending audio frames or a control message (like hangup). We'll use a 1-byte prefix:

- `0x01` = an audio frame follows (exactly 640 bytes PCM16 mono)
- `0x02` = a control message follows (newline-terminated UTF-8 JSON)

Examples:
- Bot speech:
  - `[0x01][640 bytes PCM16]`
  - `[0x01][640 bytes PCM16]`
  - etc.
- AI-triggered hangup:
  - `[0x02]{"type":"hangup"}\n`

On the FreeSWITCH side, the socket-reader thread for `ns->sock_fd`:
- Reads 1 byte.
- If it's `0x01`, reads 640 bytes and pushes that PCM into `ns->playback_queue`.
- If it's `0x02`, reads until `\n`, parses JSON, and if it's `{"type":"hangup"}`, calls `switch_channel_hangup(...)`.

---

## AI Gateway (Java)

This service is still the brain. Responsibilities:

1. Accept a socket from FreeSWITCH.
2. Parse the one-time `session_start` header.
3. Create a Nova Sonic bidirectional streaming session.
4. Start two threads:
   - Thread A: FS → Nova Sonic (caller audio upstream)
   - Thread B: Nova Sonic → FS (bot audio downstream)
5. Run barge-in / silence / consent logic.
6. Send hangup control back to FreeSWITCH when appropriate.

### Socket Handling (CRITICAL FIX)

We hit a bug before:
- We used `BufferedReader.readLine()` for handshake, then tried to read raw PCM from the same socket with `socket.getInputStream()` in another thread.
- `BufferedReader` over-read and ate some audio bytes.
- Result: FreeSWITCH timed out and tore down the socket after ~11 seconds, and Nova Sonic later threw `Timed out waiting for input events`.

**Fix:**
- Never wrap the socket with `BufferedReader`/`PrintWriter`.
- Use only `InputStream` and `OutputStream`.
- Read the handshake manually from the raw stream, then keep using that same stream in the audio threads.

### Pseudocode: Handling a new call

```java
ServerSocket server = new ServerSocket(8085);

while (true) {
    Socket sock = server.accept();
    handleNewCall(sock);
}

void handleNewCall(Socket sock) throws IOException {
    InputStream in = sock.getInputStream();
    OutputStream out = sock.getOutputStream();

    // 1. Read session_start header
    // Option B (newline-delimited handshake) for first version:
    ByteArrayOutputStream headerBuf = new ByteArrayOutputStream();
    while (true) {
        int b = in.read();
        if (b == -1) throw new IOException("socket closed during handshake");
        if (b == '\n') break;
        headerBuf.write(b);
    }
    String headerJson = headerBuf.toString(StandardCharsets.UTF_8);
    SessionInit init = parseJson(headerJson); // contains callUuid, caller, sample_rate, etc.

    CallSession session = new CallSession(init.callUuid, in, out);
    session.running = true;

    // 2. Start Nova Sonic streaming session
    NovaStream nova = novaClient.startSession(init); // sets up bidirectional Bedrock session

    // 3. Thread A: FreeSWITCH -> Nova Sonic
    new Thread(() -> {
        byte[] frameBuf = new byte[640]; // 20ms @16kHz mono PCM16
        while (session.running) {
            int readSoFar = 0;
            while (readSoFar < 640) {
                int n = in.read(frameBuf, readSoFar, 640 - readSoFar);
                if (n < 0) {
                    session.running = false;
                    break;
                }
                readSoFar += n;
            }
            if (!session.running) break;

            // Send frameBuf to Nova Sonic as audioInput
            nova.sendAudioInputFrame(frameBuf);

            // Update barge-in / silence timers:
            // - detect voice activity => mark user is talking
            // - set bargeActive if user talks while bot is talking
            session.markUserVoice(frameBuf);
        }
    }, "FS->Nova-" + init.callUuid).start();

    // 4. Thread B: Nova Sonic -> FreeSWITCH
    new Thread(() -> {
        while (session.running) {
            NovaFrame nf = nova.takeNextOutputFrame(); // blocking until Nova Sonic outputs
            if (nf.isAudioFrame()) {
                byte[] pcmFrame = nf.get640BytePcm16Mono();

                // If bargeActive is set because user is talking, we may skip sending bot audio here
                if (!session.bargeActive()) {
                    // [0x01] + 640 bytes PCM16
                    synchronized (out) {
                        out.write(0x01);
                        out.write(pcmFrame);
                        out.flush();
                    }
                    session.markBotSpeaking();
                }

            } else if (nf.isHangupSignal()) {
                // Send control frame to FreeSWITCH to hang up
                synchronized (out) {
                    out.write(0x02);
                    out.write("{\"type\":\"hangup\"}\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
                session.running = false;
            } else if (nf.isToolUse("hangupCall")) {
                // same as hangupSignal
                synchronized (out) {
                    out.write(0x02);
                    out.write("{\"type\":\"hangup\"}\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
                session.running = false;
            }

            // Also drive silence reprompts here:
            // - if session.awaitingUserReply && now - session.questionAskedAtMs > 3000ms && no userVoice
            //   -> generate polite "Sorry, I didn't hear you..." via Nova Sonic
            //   -> send as audio frames like normal
        }
    }, "Nova->FS-" + init.callUuid).start();
}
```

### Barge-in Logic (Java side)

We don't rely on Nova Sonic to send “interrupted”:true. We enforce barge-in locally:

- Track if bot is currently speaking (`session.botIsSpeaking`).
- Track if user just started talking (`session.userStartedTalking()` via VAD on incoming frames).
- If user starts talking while botIsSpeaking:
  - Set `session.setBargeActive(true)`.
  - Stop sending bot audio frames to FreeSWITCH until user stops talking / there's a new turn.
  - Clear any queued bot frames (don’t “finish the sentence”).

That recreates real-time barge-in from mjSIP.

### Silence / "Are you still there?" Logic (Java side)

Track conversation state per call:

```java
boolean awaitingUserReply;
String pendingQuestionType; // e.g. "sms_opt_in"
long questionAskedAtMs;
int repromptCount;
long lastUserVoiceMs;
```

Watchdog timer in Java (ScheduledExecutor every ~250ms):
- If `awaitingUserReply == true` AND
  - (now - questionAskedAtMs > ~3000ms) AND
  - (now - lastUserVoiceMs > ~3000ms) AND
  - repromptCount == 0:
    - Ask Nova Sonic to generate a reprompt like:
      "I can text you the info. Would you like that? You can say yes or no."
    - Send that audio downstream.
    - repromptCount = 1
    - questionAskedAtMs = now (reset timer)

- If still no response after another ~3000ms:
    - Decide fallback:
      - Assume "no", continue
      - or gracefully end call
    - If ending: send hangup control to FreeSWITCH.

This logic stays in Java (AI Gateway), not in FreeSWITCH. FreeSWITCH shouldn't be responsible for conversational policy.

---

## Hangup Flow

We want Nova Sonic to be able to end the call (“Okay, I’ll disconnect now. Thank you.”). Steps:

1. Nova Sonic triggers a tool call like `hangupCall`.
2. AI Gateway sees that, and sends a control frame to FreeSWITCH:
   - First byte `0x02`
   - Then `{"type":"hangup"}\n`
3. The `mod_nova_ai` socket-reader thread on FreeSWITCH parses this and calls:

```c
switch_channel_hangup(
    switch_core_session_get_channel(ns->session),
    SWITCH_CAUSE_NORMAL_CLEARING
);
```

4. FreeSWITCH sends SIP BYE to Voice Connector.
5. We also can update Amazon Connect contact attributes before doing this (Java side) so post-call flows know why the call ended.

---

## Critical Bug We Already Hit (and How We Fix It)

**Observed:**
- FreeSWITCH connected to the Java Gateway.
- Java did `BufferedReader.readLine()` to parse the handshake.
- Then we spawned threads that tried to read raw PCM from `socket.getInputStream()`.
- After ~11 seconds, FreeSWITCH closed the socket.
- Nova Sonic timed out ~60 seconds later with `Timed out waiting for input events`.

**Why:**
- `BufferedReader` buffers ahead, so it “ate” part of the binary audio after the newline.
- The raw PCM reader then blocked forever or saw nothing.
- FreeSWITCH saw us not reading and killed the socket.

**Fix:**
- Never mix `BufferedReader` / `readLine()` with binary streaming on the same socket.
- Only use the raw `InputStream`.
- Either:
  - Read 4-byte length then that many bytes JSON (length-prefixed header), OR
  - Read bytes until first `\n` manually, then never do line-oriented I/O again.

Example safe handshake read (newline-delimited version):

```java
InputStream in = socket.getInputStream();
OutputStream out = socket.getOutputStream();

// Read handshake header up to '\n'
ByteArrayOutputStream headerBuf = new ByteArrayOutputStream();
while (true) {
    int b = in.read();
    if (b == -1) throw new IOException("socket closed during handshake");
    if (b == '\n') break;
    headerBuf.write(b);
}
String headerJson = headerBuf.toString(StandardCharsets.UTF_8);
SessionInit init = parseJson(headerJson);

// From here on:
// - Thread A reads 640-byte PCM frames from `in` and sends those frames to Nova Sonic.
// - Thread B writes bot frames to `out` with the 0x01/0x02 tags.
// - No more Readers/Writers. No more readLine(). No try-with-resources that closes the socket early.
```

Keep that socket open for the entire life of the call. Do not accidentally auto-close it when `handleNewCall()` returns. Store the session in a map keyed by call UUID so it lives until hangup.

---

## Acceptance Criteria

We’re “done” with first working version of the media bug when all of these are true in a live PSTN call hitting FreeSWITCH:

1. **Session handshake works:**
   - FreeSWITCH answers, runs `nova_ai_start`, and connects to AI Gateway.
   - AI Gateway receives the `session_start` header (call_uuid, caller, sample_rate, etc.) and starts a Nova Sonic session.

2. **Caller audio reaches Nova Sonic in real time:**
   - The media bug’s READ side sends PCM frames to AI Gateway continuously.
   - AI Gateway Thread A streams those frames to Nova Sonic as `audioInput`.
   - Nova Sonic does **not** timeout with "Timed out waiting for input events."

3. **Bot audio plays to caller in real time:**
   - Nova Sonic produces `audioOutput` PCM.
   - AI Gateway Thread B sends `[0x01][640 bytes PCM16]` frames back to FreeSWITCH.
   - The media bug’s WRITE side injects those frames so the caller hears the assistant speaking live.
   - No file-based `uuid_record` / `uuid_broadcast`.

4. **Barge-in works:**
   - If the caller starts talking while the bot is speaking, the AI Gateway (Java) sets `bargeActive=true` and stops sending bot frames.
   - The caller hears the bot stop immediately.

5. **Silence reprompt works:**
   - AI asks a yes/no (“Can I text this to you?”).
   - If the caller is silent ~3s, AI Gateway triggers a reprompt (“I can text it to you. Would you like that? You can say yes or no.”).
   - If still silent after another ~3s, AI Gateway decides fallback (assume no / politely end).

6. **AI-driven hangup works:**
   - Nova Sonic triggers `hangupCall`.
   - AI Gateway sends control frame `[0x02]{"type":"hangup"}\n`.
   - The module sees that and calls `switch_channel_hangup(...)`.
   - The PSTN call actually drops.

When all 6 pass, we’ve reproduced mjSIP’s behavior — low-latency duplex audio, barge-in, silence handling, AI-controlled hangup — but with FreeSWITCH as the call edge and clean separation of concerns.

---

## What To Build First (Dev Order of Operations)

1. Stand up FreeSWITCH (use AMI if you want minimal friction). Confirm you can receive a call from Amazon Chime Voice Connector and answer it.

2. Implement `mod_nova_ai`:
   - Create `nova_ai_start` dialplan app.
   - Allocate `nova_ai_session_t`.
   - Connect to AI Gateway socket.
   - Send `session_start` header.
   - Attach media bug (`switch_core_media_bug_add`).
   - Spawn a socket-reader thread that:
     - reads `[0x01]` audio frames (640 bytes) from AI Gateway and enqueues them,
     - reads `[0x02]{"type":"hangup"}` and hangs up.

3. In the media bug callback:
   - On READ: push caller PCM frames to AI Gateway (no buffering to disk).
   - On WRITE: pull bot PCM frames from queue and inject them, or silence if none.

4. Update the AI Gateway service:
   - Stop using `BufferedReader` / `readLine()`.
   - Read the initial handshake (length-prefixed or newline).
   - Keep using the same `InputStream` for all following 640-byte frames from FreeSWITCH.
   - Start Nova Sonic session.
   - Thread A: caller audio → Nova Sonic `audioInput`.
   - Thread B: Nova Sonic `audioOutput` → `[0x01]frame` → FreeSWITCH.
   - On hangup tool or finalization: send `[0x02]{"type":"hangup"}\n`.

5. Test a real call from your cell:
   - You speak, Nova Sonic responds, you hear it in near-real-time.
   - You interrupt, bot stops (barge-in).
   - You say “thank you bye,” Nova Sonic decides to end, call drops.

At that point we are essentially production-architecture-ready: FreeSWITCH on the edge, Nova Sonic brain in Java, and a deterministic media bridge between them.