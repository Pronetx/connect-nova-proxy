# mod_nova_sonic - Amazon Nova Sonic FreeSWITCH Module
Architecture (what we’re building)

Here’s the flow once the bug is in place:

Caller (PSTN)
→ Amazon Voice Connector (SIP/RTP)
→ FreeSWITCH (SIP, RTP termination)
→ Media Bug (our module)
↔ AI Gateway Service (Java)
↔ Bedrock Nova Sonic (bidi stream)

Details:
	•	FreeSWITCH handles SIP and RTP like normal. We don’t touch SIP at all here.
	•	When the call is answered, dialplan attaches our media bug to that channel.
	•	The media bug:
	•	pulls 20ms PCM frames from the call
	•	sends those frames to the Java AI gateway over a persistent socket
	•	receives audio frames back from the Java AI gateway (Nova Sonic’s reply audio)
	•	injects them into the call in real time
	•	The Java AI gateway:
	•	maintains the Nova Sonic bidirectional session
	•	tracks state (barge-in, silence timeout, “are you still there?”, etc.)
	•	can send control messages (“hang up”, “transfer”) back to FreeSWITCH when Nova Sonic calls tools like hangupCall

The result: to the caller, it behaves exactly like your mjSIP version did — full duplex, barge-in, responsive — but now FreeSWITCH is the call edge.

⸻

3. Step-by-step implementation plan for the assistant

Step 0. Prep
	•	Create a new FreeSWITCH module, e.g. mod_nova_ai.
	•	This will live under src/mod/applications/mod_nova_ai/ (or similar).
	•	It will expose a dialplan application, e.g. nova_ai_start, that we can call from the dialplan.

So in your FreeSWITCH dialplan you’ll eventually do:
<action application="answer"/>
<action application="nova_ai_start" data="ws://<AI_GATEWAY>:8084/session"/>
<action application="park"/>
nova_ai_start = attach media bug + open socket to your AI gateway service.


Step 1. Module skeleton (C code)

Assistant should:
	1.	Create module boilerplate modeled after an existing media-bug-style module (look at mod_audio_fork or mod_avmd as reference).
	•	Implement SWITCH_MODULE_LOAD_FUNCTION and SWITCH_MODULE_RUNTIME_FUNCTION.
	•	Register a new application nova_ai_start.
	2.	nova_ai_start should:
	•	Grab the current channel UUID.
	•	Create a struct to hold session state (call UUID, socket handle to AI gateway, buffers, flags).
	•	Open a TCP/WebSocket connection to the AI gateway using the data argument (e.g. ws://host:port/session).
	•	Attach a media bug to this channel via switch_core_media_bug_add(...).

This is the “hook.”

⸻

Step 2. Define the session state struct

We need a per-call struct (nova_ai_session_t) that will live in C on the FreeSWITCH side. It should contain:
typedef struct {
    switch_core_session_t *session;

    switch_media_bug_t *bug;

    char call_uuid[SWITCH_UUID_FORMATTED_LENGTH + 1];

    // socket/connection to AI gateway
    int sock_fd; // or libwebsockets handle if using WS

    // outbound buffer mgmt
    switch_queue_t *playback_queue; // queue of PCM frames received from AI to inject

    // flags / state
    switch_bool_t running;
    switch_bool_t barge_active;
    switch_mutex_t *mutex;
} nova_ai_session_t;

Store a pointer to this struct in the channel’s private data so we can recover it.

Step 3. Attach the media bug

When we call switch_core_media_bug_add, we give it:
	•	a callback function that FreeSWITCH will call repeatedly with audio frames
	•	flags that say we want read/write audio
	•	SMBF_READ_STREAM to tap caller input (what the human is saying)
	•	SMBF_WRITE_STREAM to inject bot audio toward the caller

Your callback signature will look like:
static switch_bool_t nova_ai_bug_callback(
    switch_media_bug_t *bug,
    void *user_data,
    switch_abc_type_t type
);

Inside user_data you’ll get back the nova_ai_session_t *.

We care about type == SWITCH_ABC_TYPE_READ_REPLACE or SWITCH_ABC_TYPE_WRITE_REPLACE depending on how you configure it — basically: FreeSWITCH is passing you audio frames.

In READ phase:
	•	FreeSWITCH gives you PCM frames representing caller speech (already decoded to linear PCM).
	•	You:
	•	forward those frames over the socket to the AI gateway (Java side),
	•	maybe also run simple VAD here and set barge flags if needed.

In WRITE phase:
	•	You check if you have any PCM frames waiting in playback_queue (frames received from AI gateway).
	•	If yes, you copy that PCM into the frame->data FreeSWITCH gave you so it plays to the caller.
	•	If no, you return silence.

This effectively gives you a full-duplex bridge in-process.

The assistant should model this closely on mod_audio_fork patterns: that module is basically “take audio from channel and fork it somewhere.” We’re doing that, plus feeding audio back.

⸻

Step 4. Define wire protocol between FreeSWITCH module and AI gateway

Your AI gateway (Java) needs to understand what the module is sending, and vice versa.

Keep it dead simple to start. For each call, when nova_ai_start runs:
	1.	Module opens a socket / WebSocket: ws://ai-gateway:8084/session
	2.	Module sends an initial JSON blob:

  {
  "type": "session_start",
  "call_uuid": "<freeswitch-channel-uuid>",
  "sample_rate": 16000,
  "audio_format": "PCM16_MONO"
}

3.	After that:
	•	For every inbound caller frame (READ):
	•	Send binary frame with just raw PCM16 mono at 16 kHz. (No headers, just audio chunks of e.g. 20ms = 320 samples @16k.)
	•	OR wrap small JSON metadata messages when state changes (like “barge” events, hangup, etc.).
	•	For audio coming back from the AI gateway:
	•	The Java service will send you binary PCM16 chunks meant to go to the caller (bot TTS output).
	•	When the module receives those binary chunks, it should enqueue them into playback_queue.

So assistant needs to:
	•	Pick a fixed frame size. 20ms @ 16kHz mono PCM16 = 320 samples = 640 bytes.
	•	Stream that size consistently in both directions.
	•	Assume little-endian 16-bit signed PCM.

Do not overthink compression yet. Uncompressed PCM is fine inside your VPC.

⸻

Step 5. Java AI gateway changes

Now we talk about the other side (the Java service you already have talking to Nova Sonic).

Your assistant needs to:
	1.	Add a lightweight WebSocket (or TCP socket) server on port 8084 that:
	•	Accepts one connection per call
	•	Reads the session_start JSON
	•	Starts a Nova Sonic bidirectional streaming session for that call
	2.	For every inbound PCM frame from FreeSWITCH:
	•	Feed that 16k PCM chunk into Nova Sonic audioInput.
You’ve already done this logic in mjSIP-land; reuse it.
	3.	For every audioOutput chunk from Nova Sonic:
	•	Immediately push that PCM chunk back out over the socket to FreeSWITCH
	•	(This is where you used to encode to μ-law RTP; now you just pass PCM16 straight to the module.)
	4.	Implement barge-in + silence policy:
	•	If the caller starts talking and your barge-in logic says “mute bot,” stop sending new PCM to FreeSWITCH for playback.
	•	If Nova Sonic triggers hangup (hangupCall tool), send a control message back to FreeSWITCH like:

  { "type": "hangup" }

and FreeSWITCH module should then issue a switch_channel_hangup() on that call UUID.

5.	Implement “are you still there?” logic, silence timeout, consent prompts, Connect attribute updates, etc., exactly where you do them now (Java). That does NOT move into C. Java remains the brain.

So: The module streams audio, your Java service does all policy and Nova Sonic orchestration.

⸻

Step 6. Hangup flow

You need to support AI-driven hangup just like you had with mjSIP.

Flow:
	•	Nova Sonic calls your hangupCall tool.
	•	Java gateway sends { "type": "hangup" } over the socket.
	•	The media bug’s network thread (C side) receives that, sets session->running = SWITCH_FALSE, and calls:
  switch_channel_hangup(channel, SWITCH_CAUSE_NORMAL_CLEARING);

  	•	That ends the call on FreeSWITCH, which sends BYE back to Amazon Voice Connector. Perfect.

Step 7. Cleanup / teardown

When:
	•	the caller hangs up,
	•	or Nova Sonic ends session,
	•	or your Java gateway closes the socket,

the module must:
	•	stop the media bug (switch_core_media_bug_remove)
	•	close the socket
	•	free the session struct

make sure we don’t leak memory or leave bugs attached after hangup.

In Summary, the plan is:
	1.	Create a new FreeSWITCH module (call it mod_nova_ai) that:
	•	Registers a dialplan app nova_ai_start.
	•	When called, it:
	•	opens a TCP/WebSocket connection to our Java AI gateway (ws://...:8084/session)
	•	allocates a nova_ai_session_t struct to track state
	•	attaches a media bug to the current channel using switch_core_media_bug_add.
	2.	The media bug callback should:
	•	On READ frames (caller audio): send 20ms PCM16 mono chunks to the Java gateway over that socket.
	•	On WRITE frames (bot audio): pull PCM16 chunks from a queue filled by the socket receiver thread and copy them into the frame so the caller hears Nova Sonic.
	3.	On the Java side, extend the AI gateway to:
	•	Accept that socket connection.
	•	After session_start, spin up a Nova Sonic bidirectional stream.
	•	For each PCM chunk from FreeSWITCH: forward to Nova Sonic audioInput.
	•	For each Nova Sonic audioOutput chunk: send PCM back over the socket to FreeSWITCH.
	•	Implement barge-in and silence logic you already wrote.
	•	When Nova calls hangupCall, send { "type": "hangup" } on the socket.
	4.	The FreeSWITCH module must handle { "type": "hangup" } from Java by hanging up the channel.
	5.	We’re “done” when:
	•	I can call in via PSTN and talk to Nova Sonic in real time through FreeSWITCH (no file buffering),
	•	I can interrupt Nova and it stops talking,
	•	Nova can hang up the call.