# Amazon Connect Integration - Implementation Plan

**Repository Structure:** This repo has been reorganized into modules:
- `/voice-gateway` - Core SIP/RTP gateway (Dev A)
- `/connect-integration` - Connect-specific code (Dev B)
- `/infra` - CDK infrastructure
- `/lambdas` - Lambda functions
- `/shared` - Shared documentation and contracts

## Executive Summary

This plan details the modifications needed to adapt the Nova S2S VoIP Gateway for Amazon Connect integration. The gateway will act as an external voice transfer target, extract Connect context from SIP headers, stream audio with Nova Sonic, update contact attributes during/after the call, and gracefully return control to Connect.

**Note:** All file paths in this document have been updated to reflect the new modular structure.

---

## Phase 1: Foundation - Connect Client & Context Management

### 1.1 Add AWS Connect SDK Dependency

**File:** `/connect-integration/pom.xml`

**Status:** ✅ Already added to connect-integration module

**Action:** Dependency already present in the new module structure

```xml
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>connect</artifactId>
    <version>2.31.19</version>
</dependency>
```

**Justification:** Required for `UpdateContactAttributes` API calls.

---

### 1.2 Create ConnectCallContext Class

**New File:** `/connect-integration/src/main/java/com/example/s2s/connect/context/ConnectCallContext.java`

**Purpose:** Stores Amazon Connect context extracted from SIP INVITE headers for the duration of a call.

**Structure:**
```java
package com.example.s2s.connect.context;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ConnectCallContext {
    private String initialContactId;    // From SIP header
    private String instanceId;          // From SIP header or env var
    private String correlationId;       // Custom tracking ID (optional)
    private String callId;              // SIP Call-ID for logging
    private long callStartTime;         // Timestamp for duration tracking
}
```

**Key Design Decisions:**
- Uses Lombok `@Data` and `@Builder` for consistency with existing codebase
- Immutable after creation (use builder pattern)
- Includes `callStartTime` for analytics/debugging

---

### 1.3 Create SIP Header Parser Utility

**New File:** `/connect-integration/src/main/java/com/example/s2s/connect/uui/SipHeaderParser.java`

**Purpose:** Extract Connect context from SIP INVITE message headers.

**Key Methods:**
```java
public static ConnectCallContext parseConnectContext(SipMessage msg, String fallbackInstanceId)
```

**Implementation Details:**
- Check for standard headers:
  - `User-to-User` (UUI) header - Connect uses this for some integrations
  - `X-Connect-ContactId` (custom header set in Connect flow via Lambda)
  - `X-Connect-InstanceId`
  - `X-Correlation-Id`
- Parse SIP `Call-ID` for logging/correlation
- Fall back to `CONNECT_INSTANCE_ID` env var if header not present
- Log warnings if critical headers missing
- Return `null` if `initialContactId` cannot be determined (indicates non-Connect call)

**Header Format Examples:**
```
User-to-User: contactId=abc123;instanceId=xyz789
X-Connect-ContactId: abc123
X-Connect-InstanceId: xyz789
```

**Error Handling:**
- Gracefully handle malformed headers
- Log parsing failures but don't crash the call
- Return partial context if some fields available

---

### 1.4 Create ConnectAttributeManager

**New File:** `/connect-integration/src/main/java/com/example/s2s/connect/attributes/ConnectAttributeManager.java`

**Purpose:** Handles all Amazon Connect API interactions (primarily `UpdateContactAttributes`).

**Key Methods:**

```java
public ConnectAttributeManager(String region)
public void updateAttributes(ConnectCallContext context, Map<String, String> attributes)
public void updateSingleAttribute(ConnectCallContext context, String key, String value)
```

**Implementation Details:**
- Lazy-initialize AWS Connect client (singleton per region)
- Use AWS SDK v2 `ConnectClient` with default credential provider chain
- Validate attribute sizes (Connect limit: 32KB total, keys/values must be strings)
- Retry logic for transient failures (use SDK's built-in retry with exponential backoff)
- Comprehensive error logging (don't crash call on Connect API failure)

**Configuration:**
- Region from `CONNECT_REGION` env var (default: `us-east-1`)
- Timeout: 5 seconds per API call

**Error Handling:**
- Catch and log `ResourceNotFoundException` (invalid contact ID)
- Catch and log `InvalidParameterException` (malformed attributes)
- Don't propagate exceptions to call handling layer

---

## Phase 2: Tool Integration - Connect-Aware Nova Tools

### 2.1 Create ConnectUpdateAttributesTool

**New File:** `/connect-integration/src/main/java/com/example/s2s/connect/tools/ConnectUpdateAttributesTool.java`

**Purpose:** Nova Sonic tool that allows the AI to update Connect contact attributes during the conversation.

**Extends:** `AbstractNovaS2SEventHandler`

**Tool Specification:**
- **Name:** `updateContactAttributes`
- **Description:** "Update Amazon Connect contact attributes with key-value pairs. Use this to record information learned during the conversation for downstream routing or analytics."
- **Input Schema:**
```json
{
  "type": "object",
  "properties": {
    "attributes": {
      "type": "object",
      "description": "Key-value pairs to set as contact attributes",
      "additionalProperties": {"type": "string"}
    }
  },
  "required": ["attributes"]
}
```

**Implementation Details:**
- Parse `attributes` JSON object from tool input
- Call `ConnectAttributeManager.updateAttributes()`
- Return success/failure status to Nova
- Add attribute key prefix: `nova_` (e.g., `nova_customer_sentiment`, `nova_issue_type`)

**Example Tool Use:**
Nova decides: "Customer mentioned billing issue for account 12345"
→ Calls tool with `{"attributes": {"issue_type": "billing", "account_id": "12345"}}`
→ Connect attributes updated: `nova_issue_type=billing`, `nova_account_id=12345`

---

### 2.2 Create ConnectEndCallTool

**New File:** `/connect-integration/src/main/java/com/example/s2s/connect/tools/ConnectEndCallTool.java`

**Purpose:** Nova Sonic tool that allows the AI to gracefully end the call and return control to Connect.

**Extends:** `AbstractNovaS2SEventHandler`

**Tool Specification:**
- **Name:** `endCallAndTransfer`
- **Description:** "End the Nova conversation and return to Amazon Connect with routing instructions. Use this when the conversation is complete and you know what should happen next."
- **Input Schema:**
```json
{
  "type": "object",
  "properties": {
    "nextAction": {
      "type": "string",
      "enum": ["agent", "survey", "end"],
      "description": "Where to route after this call: agent (transfer to queue), survey (post-call survey), or end (hang up)"
    },
    "targetQueue": {
      "type": "string",
      "description": "Queue name or ARN if nextAction is 'agent'"
    },
    "summary": {
      "type": "string",
      "description": "Brief summary of the conversation for agent/analytics"
    },
    "reason": {
      "type": "string",
      "description": "Reason for ending (e.g., 'issue_resolved', 'needs_agent', 'customer_hangup')"
    }
  },
  "required": ["nextAction", "summary"]
}
```

**Implementation Details:**
1. Parse tool input
2. Validate `nextAction` (must be: `agent`, `survey`, or `end`)
3. If `nextAction=agent`, require `targetQueue`
4. Call `ConnectAttributeManager` with final attributes:
   - `nova_next` = nextAction
   - `nova_target_queue` = targetQueue (if applicable)
   - `nova_summary` = summary
   - `nova_reason` = reason
   - `nova_call_duration` = calculate from `ConnectCallContext.callStartTime`
   - `nova_timestamp` = ISO-8601 timestamp
5. Set internal flag: `callEndRequested = true`
6. Return success to Nova (this will be the last tool response)

**Call Termination Flow:**
- Tool execution sets flag but doesn't immediately hang up
- After Nova completes current turn (processes tool result), the media streamer detects the flag
- Media streamer closes streams → triggers SIP BYE in `NovaSonicVoipGateway`

---

### 2.3 Create Unified ConnectNovaEventHandler

**New File:** `/connect-integration/src/main/java/com/example/s2s/connect/tools/ConnectNovaEventHandler.java`

**Purpose:** Unified event handler that combines DateTime tools + Connect tools.

**Extends:** `AbstractNovaS2SEventHandler`

**Constructor:**
```java
public ConnectNovaEventHandler(ConnectCallContext context, ConnectAttributeManager attributeManager)
```

**Tool Configuration:**
Returns `ToolConfiguration` with 4 tools:
1. `getDateTool` (from existing `DateTimeNovaS2SEventHandler`)
2. `getTimeTool` (from existing `DateTimeNovaS2SEventHandler`)
3. `updateContactAttributes` (new)
4. `endCallAndTransfer` (new)

**Implementation Strategy:**
- Composition over inheritance: Delegate DateTime tool calls to existing logic
- Add new switch cases in `handleToolInvocation()` for Connect tools
- Store `ConnectCallContext` and `ConnectAttributeManager` as instance fields
- Expose `isCallEndRequested()` method for termination detection

**Call End Detection:**
- `UserAgent` (or media streamer) polls `isCallEndRequested()` after each turn
- When `true`, initiate graceful shutdown: close streams → send BYE

---

## Phase 3: Call Flow Integration

### 3.1 Modify NovaSonicVoipGateway

**File:** `/voice-gateway/src/main/java/com/example/s2s/voipgateway/NovaSonicVoipGateway.java`

**Changes:**

#### 3.1.1 Add Instance Fields (after line 37)
```java
private final ConnectAttributeManager connectAttributeManager;
private final String connectInstanceId; // From env var
```

#### 3.1.2 Update Constructor (line 44)
```java
public NovaSonicVoipGateway(SipProvider sipProvider, PortPool portPool, ServiceOptions serviceConfig,
                            UAConfig uaConfig, NovaMediaConfig mediaConfig,
                            ConnectAttributeManager connectAttributeManager, String connectInstanceId) {
    // ... existing code ...
    this.connectAttributeManager = connectAttributeManager;
    this.connectInstanceId = connectInstanceId;
}
```

#### 3.1.3 Modify createCallHandler (line 102)
**Current Code:**
```java
protected UserAgentListener createCallHandler(SipMessage msg) {
    register();
    return new UserAgentListenerAdapter() {
        @Override
        public void onUaIncomingCall(UserAgent ua, NameAddress callee, NameAddress caller,
                                     MediaDesc[] media_descs) {
            LOG.info("Incomming call from: {}", callee.getAddress());
            ua.accept(new MediaAgent(mediaConfig.getMediaDescs(), streamerFactory));
        }
    };
}
```

**New Code:**
```java
protected UserAgentListener createCallHandler(SipMessage msg) {
    register();

    // Extract Connect context from SIP headers
    ConnectCallContext connectContext = SipHeaderParser.parseConnectContext(msg, connectInstanceId);
    if (connectContext != null) {
        LOG.info("Connect call detected: contactId={}, instanceId={}, callId={}",
                 connectContext.getInitialContactId(),
                 connectContext.getInstanceId(),
                 connectContext.getCallId());
    } else {
        LOG.info("Non-Connect call (no Connect headers found)");
    }

    return new UserAgentListenerAdapter() {
        @Override
        public void onUaIncomingCall(UserAgent ua, NameAddress callee, NameAddress caller,
                                     MediaDesc[] media_descs) {
            LOG.info("Incoming call from: {}", caller.getAddress());

            // Create Connect-aware streamer factory if Connect context present
            StreamerFactory factory = (connectContext != null)
                ? new ConnectAwareStreamerFactory(mediaConfig, connectContext, connectAttributeManager)
                : streamerFactory;

            ua.accept(new MediaAgent(mediaConfig.getMediaDescs(), factory));
        }

        @Override
        public void onUaCallClosed(UserAgent ua) {
            LOG.info("Call closed: callId={}", connectContext != null ? connectContext.getCallId() : "unknown");
            // Cleanup if needed
        }
    };
}
```

**Key Changes:**
- Parse SIP message for Connect headers at call setup time
- Pass `connectContext` to new `ConnectAwareStreamerFactory`
- Gracefully handle non-Connect calls (fall back to original behavior)
- Add logging for call lifecycle events

#### 3.1.4 Update main() Method (line 116)
```java
// After line 138 (after mediaConfig/portConfig/sipConfig setup)

// Initialize Connect integration
String connectRegion = environ.getOrDefault("CONNECT_REGION", "us-east-1");
String connectInstanceId = environ.get("CONNECT_INSTANCE_ID"); // Optional
ConnectAttributeManager connectAttributeManager = new ConnectAttributeManager(connectRegion);

// Update gateway instantiation (line 140)
NovaSonicVoipGateway gateway = new NovaSonicVoipGateway(
    sipProvider,
    portConfig.createPool(),
    serviceConfig,
    uaConfig,
    mediaConfig,
    connectAttributeManager,  // NEW
    connectInstanceId         // NEW
);
```

---

### 3.2 Create ConnectAwareStreamerFactory

**New File:** `/connect-integration/src/main/java/com/example/s2s/connect/factory/ConnectAwareStreamerFactory.java`

**Purpose:** Variant of `NovaStreamerFactory` that injects Connect-aware event handler.

**Extends/Implements:** `StreamerFactory` interface

**Constructor:**
```java
public ConnectAwareStreamerFactory(NovaMediaConfig mediaConfig,
                                   ConnectCallContext connectContext,
                                   ConnectAttributeManager attributeManager)
```

**Implementation:**
- Copy logic from `NovaStreamerFactory.createMediaStreamer()` (lines 45-82)
- **Key Difference:** Replace line 61:
  ```java
  // OLD:
  NovaS2SEventHandler eventHandler = new DateTimeNovaS2SEventHandler();

  // NEW:
  NovaS2SEventHandler eventHandler = new ConnectNovaEventHandler(connectContext, attributeManager);
  ```
- Rest of the method remains identical (session start, prompt config, audio setup)

**Rationale:**
- Keeps separation of concerns (Connect logic isolated)
- Allows fallback to original `NovaStreamerFactory` for non-Connect calls
- Could refactor into single factory with optional context later, but this is cleaner for Phase 1

---

### 3.3 Handle Call Termination on Tool Request

**File:** `/voice-gateway/src/main/java/com/example/s2s/voipgateway/NovaSonicAudioInput.java` or new class

**Problem:** When `endCallAndTransfer` tool is invoked, we need to close the media session and send SIP BYE.

**Solution Option A - Modify NovaSonicAudioInput:**

Add termination check in the audio read loop. Currently `NovaSonicAudioInput` extends `AudioTransmitter`. We can inject a reference to the event handler:

```java
// In NovaSonicAudioInput
private final ConnectNovaEventHandler eventHandler; // If Connect call

@Override
public void run() {
    // ... existing audio read loop ...

    // Add check:
    if (eventHandler != null && eventHandler.isCallEndRequested()) {
        LOG.info("Call end requested by Nova, closing media session");
        this.halt(); // Stop transmitter
        break;
    }
}
```

**Solution Option B - Polling in UserAgent:**

The `UserAgent` class (from mjSIP) manages the call state machine. After accepting a call, it has a callback structure. We can use `onUaCallClosed()` or create a timer that checks the event handler's status.

**Recommended Approach:** Option A (modify audio input)
- More immediate response
- Cleaner architecture (media layer detects media-related termination)
- Requires passing event handler reference through `AudioStreamer` constructor chain

**Implementation:**
1. Update `ConnectAwareStreamerFactory` to pass event handler to audio components
2. Modify `NovaSonicAudioInput` constructor to accept optional `ConnectNovaEventHandler`
3. Add termination check in `run()` method
4. When detected, call `halt()` which will cascade to closing the RTP session and triggering SIP BYE

---

## Phase 4: Configuration & Environment

### 4.1 Add Environment Variables

**File:** `/voice-gateway/environment.template`

**Status:** ✅ Environment variables documented in README

**Add (after line 6):**
```bash
# Amazon Connect Integration
CONNECT_INSTANCE_ID=            # Optional: Connect instance ID (can be passed via SIP header)
CONNECT_REGION=us-east-1        # AWS region for Connect API calls
```

**File:** `/CLAUDE.md`

**Status:** ✅ Already updated

**Update Environment Variables section** (after line 71) with:
```markdown
**Amazon Connect Integration**:
- `CONNECT_INSTANCE_ID` - Connect instance ID (optional if passed via SIP header)
- `CONNECT_REGION` - AWS region for Connect API (default: us-east-1)
```

---

### 4.2 Update README.md

**File:** `/README.md`

**Status:** ✅ Completely rewritten with new structure

**Add new section after "Environment Variables" (after line 137):**

```markdown
## Amazon Connect Integration

This gateway can be used as an external voice transfer target for Amazon Connect contact flows.

### Setup

1. **Deploy the Gateway** using either the ECS or EC2 CDK stack as documented above.

2. **Configure SIP Headers in Connect Flow:**
   - Use a Lambda function in your Connect flow to set custom SIP headers before the External Voice Transfer block:
     - `X-Connect-ContactId`: Set to `$.InitialContactId`
     - `X-Connect-InstanceId`: Set to your Connect instance ID
     - `X-Correlation-Id`: Optional custom tracking ID

3. **Configure External Voice Transfer Block:**
   - Set destination to your gateway's SIP URI (e.g., `sip:gateway@your-domain.com:5060`)
   - Enable "Resume flow after disconnect"

4. **Set Environment Variables:**
   - `CONNECT_REGION`: AWS region of your Connect instance
   - `CONNECT_INSTANCE_ID`: (Optional) Fallback instance ID if not in SIP headers

### Routing After Nova Conversation

After the Nova conversation ends, the gateway updates contact attributes that you can use in your Connect flow:

- `nova_next`: Routing decision (`agent`, `survey`, or `end`)
- `nova_target_queue`: Queue ARN/name if `nova_next=agent`
- `nova_summary`: Conversation summary (plain text)
- `nova_reason`: Reason for ending (e.g., `issue_resolved`, `needs_agent`)
- `nova_call_duration`: Duration of Nova portion in seconds
- `nova_timestamp`: ISO-8601 timestamp

**Example Post-Transfer Flow:**
```
Check Contact Attributes
├─ If $.Attributes.nova_next = "agent"
│  └─ Transfer to Queue ($.Attributes.nova_target_queue)
├─ If $.Attributes.nova_next = "survey"
│  └─ Get Customer Input (satisfaction survey)
└─ If $.Attributes.nova_next = "end"
   └─ Disconnect
```

### IAM Permissions

The ECS task role (or EC2 instance role) requires these additional permissions:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "connect:UpdateContactAttributes"
      ],
      "Resource": "arn:aws:connect:REGION:ACCOUNT:instance/INSTANCE_ID/contact/*"
    }
  ]
}
```

This is automatically configured in the CDK stacks if you're using those.
```

---

### 4.3 Update CDK Stacks

**File:** `/infra/cdk-ecs/lib/cdk-ecs-stack.ts`

**Add Connect permissions to task role** (after line 138, inside `taskRole` inline policies):

```typescript
// After Bedrock policy (line 142)
'ConnectAccess': new iam.PolicyDocument({
  statements: [
    new iam.PolicyStatement({
      actions: ['connect:UpdateContactAttributes'],
      resources: ['arn:aws:connect:*:*:instance/*/contact/*'],
      effect: iam.Effect.ALLOW
    })
  ]
})
```

**Update environment variables** (line 180, add to `environment` object):
```typescript
environment: {
  MEDIA_PORT_BASE: baseRtpPort.toString(),
  MEDIA_PORT_COUNT: rtpPortCount.toString(),
  CONNECT_REGION: this.region,  // NEW
}
```

**Add optional stack prop** (line 11, in `VoipGatewayContainerStackProps` interface):
```typescript
connectInstanceId?: string;
```

**Add to secrets if provided** (line 172, inside `secrets` object):
```typescript
secrets: {
  // ... existing secrets ...
  ...(props.connectInstanceId && {
    CONNECT_INSTANCE_ID: ecs.Secret.fromSecretsManager(
      sipServerSecret, 'connectInstanceId'
    )
  })
}
```

**Update secret creation** (line 148, add to JSON):
```typescript
secretStringValue: cdk.SecretValue.unsafePlainText(JSON.stringify({
  username: props.sipUsername,
  password: props.sipPassword,
  server: props.sipServer,
  realm: props.sipRealm,
  displayName: props.displayName,
  connectInstanceId: props.connectInstanceId || ''  // NEW
}))
```

**File:** `/infra/cdk-ec2-instance/lib/cdk-stack.ts` - Apply similar changes

---

## Phase 5: Testing & Validation

### 5.1 Unit Tests (New Files)

**Test Files to Create:**

1. **`/connect-integration/src/test/java/com/example/s2s/connect/SipHeaderParserTest.java`**
   - Test parsing valid UUI header
   - Test parsing custom X-Connect headers
   - Test malformed headers (graceful degradation)
   - Test missing headers (null return)

2. **`/connect-integration/src/test/java/com/example/s2s/connect/ConnectAttributeManagerTest.java`**
   - Mock Connect client
   - Test successful attribute update
   - Test oversized attributes (should truncate/warn)
   - Test API failures (should log, not throw)

3. **`/connect-integration/src/test/java/com/example/s2s/connect/tools/ConnectNovaEventHandlerTest.java`**
   - Test tool routing (DateTime vs Connect tools)
   - Test `endCallAndTransfer` flag setting
   - Test attribute key prefixing (`nova_`)

**Add JUnit dependency to `/connect-integration/pom.xml`:**

**Status:** ✅ Already added to connect-integration module
```xml
<dependency>
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter</artifactId>
    <version>5.10.0</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.mockito</groupId>
    <artifactId>mockito-core</artifactId>
    <version>5.5.0</version>
    <scope>test</scope>
</dependency>
```

---

### 5.2 Integration Test Plan

**Test Scenario 1: Connect Call with Successful Resolution**
1. Configure Connect flow to transfer to gateway with headers
2. Call Connect number
3. Speak with Nova, have it resolve issue
4. Nova calls `endCallAndTransfer` with `nextAction=end`
5. **Verify:** Gateway updates attributes and hangs up
6. **Verify:** Connect flow resumes, checks `nova_next=end`

**Test Scenario 2: Connect Call Requiring Agent**
1. Same setup
2. Tell Nova you need a human
3. Nova calls `endCallAndTransfer` with `nextAction=agent`, `targetQueue=Support`
4. **Verify:** Attributes updated with `nova_next=agent`, `nova_target_queue=Support`
5. **Verify:** Connect flow transfers to Support queue

**Test Scenario 3: Non-Connect Call (Fallback)**
1. Call gateway directly via SIP (no Connect headers)
2. **Verify:** Call proceeds normally with DateTime tools only
3. **Verify:** No Connect API calls attempted (check logs)

**Test Scenario 4: Mid-Call Attribute Updates**
1. During conversation, Nova gathers customer info
2. Nova calls `updateContactAttributes` multiple times
3. **Verify:** Attributes appear in Connect real-time (check CloudWatch/Connect console)

---

### 5.3 Logging & Observability

**Add Structured Logging:**

All new classes should use SLF4J with MDC (Mapped Diagnostic Context) for call correlation:

```java
// In SipHeaderParser (when parsing)
MDC.put("callId", callId);
MDC.put("contactId", contactId);

// In ConnectAttributeManager
LOG.info("Updating contact attributes: contactId={}, keys={}",
         context.getInitialContactId(),
         attributes.keySet());
```

**Metrics to Track:**
- Connect API call latency (log timing)
- Connect API failures (count/types)
- Calls with vs without Connect context (ratio)
- Average call duration before `endCallAndTransfer`

**CloudWatch Logs Filter Patterns:**
```
[time, request_id, level, logger, message = "Connect call detected*"]
[time, request_id, level, logger, message = "Updating contact attributes*"]
[time, request_id, level, logger, message = "Call end requested*"]
```

---

## Phase 6: Documentation & Rollout

### 6.1 Update CLAUDE.md

**File:** `CLAUDE.md`

**Add new section after "Tool Extension System" (after line 45):**

```markdown
## Amazon Connect Integration Architecture

When deployed as an Amazon Connect external voice transfer target:

1. **Call Ingress Flow:**
   - Connect flow → External Voice Transfer block → Gateway SIP endpoint
   - Connect sets SIP headers: `X-Connect-ContactId`, `X-Connect-InstanceId`
   - Gateway extracts headers via `SipHeaderParser` in `createCallHandler()` (NovaSonicVoipGateway.java:102)

2. **Connect-Aware Session:**
   - If Connect headers present → uses `ConnectAwareStreamerFactory`
   - Event handler: `ConnectNovaEventHandler` (extends DateTime tools + adds Connect tools)
   - Stores context in `ConnectCallContext` for API calls

3. **Nova Tools for Connect:**
   - `updateContactAttributes`: Mid-call attribute updates
   - `endCallAndTransfer`: Graceful termination with routing instructions
   - Tool implementations: `com.example.s2s.voipgateway.connect.tools.*`

4. **Call Termination Flow:**
   - Nova calls `endCallAndTransfer` → sets flag in event handler
   - Audio input detects flag → closes streams → triggers SIP BYE
   - Gateway updates final attributes via `ConnectAttributeManager`
   - Connect resumes flow, reads `nova_next` attribute for routing

5. **Fallback Behavior:**
   - Non-Connect calls (no headers) → uses standard `NovaStreamerFactory`
   - DateTime-only tools, no Connect API calls

**Key Files:**
- Connect integration entry point: `NovaSonicVoipGateway.createCallHandler()` (line 102)
- SIP header parsing: `SipHeaderParser.parseConnectContext()`
- Connect API client: `ConnectAttributeManager`
- Unified event handler: `ConnectNovaEventHandler`
```

---

### 6.2 Create Architecture Diagram

**New File:** `/shared/docs/architecture-connect.png`

**Status:** 🚧 Sequence diagrams exist in `/shared/docs/call-flow-sequence.md`

**Diagram Should Show:**
```
┌─────────────────┐
│  Amazon Connect │
│   Contact Flow  │
└────────┬────────┘
         │ External Voice Transfer
         │ (SIP INVITE + headers)
         ▼
┌─────────────────────────────────────────┐
│    Nova S2S VoIP Gateway (EC2/ECS)     │
│  ┌─────────────────────────────────┐   │
│  │  SipHeaderParser                │   │
│  │  └─> ConnectCallContext         │   │
│  └─────────────────────────────────┘   │
│  ┌─────────────────────────────────┐   │
│  │  ConnectAwareStreamerFactory    │   │
│  │  └─> ConnectNovaEventHandler    │   │
│  └─────────────────────────────────┘   │
│           │                             │
│           ├─> Nova Sonic (Bedrock)      │
│           │   ├─> updateContactAttributes│
│           │   └─> endCallAndTransfer    │
│           │                             │
│           └─> ConnectAttributeManager   │
│               └─> Connect API           │
└─────────────────────────────────────────┘
         │ SIP BYE
         ▼
┌─────────────────┐
│  Amazon Connect │
│  Resume Flow    │
│  (Check nova_*) │
└─────────────────┘
```

---

### 6.3 Sample Connect Flow JSON

**New File:** `/shared/docs/sample-connect-flow.json`

**Status:** 🚧 Planned

**Purpose:** Minimal example Connect flow showing:
1. Set contact attributes with contact ID in Lambda
2. External voice transfer to gateway
3. Post-transfer routing based on `nova_next`

**Structure:**
```json
{
  "Version": "2019-10-30",
  "StartAction": "SetContactId",
  "Actions": [
    {
      "Identifier": "SetContactId",
      "Type": "InvokeExternalResource",
      "Parameters": {
        "FunctionArn": "arn:aws:lambda:...:set-sip-headers"
      },
      "Transitions": { "NextAction": "TransferToGateway" }
    },
    {
      "Identifier": "TransferToGateway",
      "Type": "TransferParticipantToThirdParty",
      "Parameters": {
        "SipUri": "sip:gateway@example.com:5060",
        "ResumeFlowAfterDisconnect": true
      },
      "Transitions": { "NextAction": "CheckNovaDecision" }
    },
    {
      "Identifier": "CheckNovaDecision",
      "Type": "CheckAttribute",
      "Parameters": {
        "Attribute": "nova_next",
        "ComparisonValue": "agent"
      },
      "Transitions": {
        "ConditionMatched": "TransferToAgent",
        "NoConditionMatched": "EndCall"
      }
    }
  ]
}
```

**Also include:** Lambda function code snippet for setting SIP headers

---

## Implementation Order & Milestones

### Milestone 1: Core Foundation (Week 1)
- [ ] Add Connect SDK dependency (1.1)
- [ ] Create `ConnectCallContext` (1.2)
- [ ] Create `SipHeaderParser` (1.3)
- [ ] Create `ConnectAttributeManager` (1.4)
- [ ] Unit tests for above

**Validation:** Can parse SIP headers and call Connect API in isolation

---

### Milestone 2: Tool System (Week 2)
- [ ] Create `ConnectUpdateAttributesTool` (2.1)
- [ ] Create `ConnectEndCallTool` (2.2)
- [ ] Create `ConnectNovaEventHandler` (2.3)
- [ ] Unit tests for tools

**Validation:** Tools can be invoked in test harness, attributes update

---

### Milestone 3: Call Flow Integration (Week 2-3)
- [ ] Modify `NovaSonicVoipGateway.createCallHandler()` (3.1)
- [ ] Create `ConnectAwareStreamerFactory` (3.2)
- [ ] Implement call termination detection (3.3)
- [ ] Update `main()` for Connect config (3.1.4)

**Validation:** End-to-end test with mock Connect flow (SIP headers, call completes)

---

### Milestone 4: Infrastructure & Config (Week 3)
- [ ] Update environment variables (4.1)
- [ ] Update README with Connect docs (4.2)
- [ ] Update CDK stacks with IAM/config (4.3)
- [ ] Deploy to test environment

**Validation:** CDK deploy succeeds, gateway starts with Connect config

---

### Milestone 5: Testing & Docs (Week 4)
- [ ] Integration testing with real Connect instance (5.2)
- [ ] Performance/load testing
- [ ] Update CLAUDE.md with Connect architecture (6.1)
- [ ] Create architecture diagram (6.2)
- [ ] Create sample Connect flow (6.3)

**Validation:** All test scenarios pass, documentation complete

---

## Risk Mitigation

### Risk 1: SIP Header Format Variations
**Mitigation:**
- Support multiple header formats (UUI, custom headers)
- Extensive logging of header parsing
- Graceful fallback to env var if headers missing

### Risk 2: Connect API Rate Limits
**Mitigation:**
- Batch attribute updates where possible
- Exponential backoff on throttling
- Don't fail call on API errors (log and continue)

### Risk 3: Call Termination Race Conditions
**Mitigation:**
- Atomic flag for call end request
- Ensure final attribute update completes before BYE
- Add 500ms delay between API call and BYE if needed

### Risk 4: Breaking Non-Connect Use Cases
**Mitigation:**
- All Connect code is additive (no changes to existing flows)
- Explicit check for Connect context before using Connect features
- Maintain separate `NovaStreamerFactory` for non-Connect calls
- Comprehensive testing of both paths

---

## File Checklist

### Repository Structure (✅ COMPLETED)
- [x] `/voice-gateway/` - Core gateway module
- [x] `/connect-integration/` - Connect-specific code module
- [x] `/infra/` - CDK stacks
- [x] `/lambdas/prepareExternalTransfer/` - Lambda function
- [x] `/shared/docs/` - Shared documentation
- [x] `/scripts/` - Testing utilities

### New Files in Connect Integration Module (11 total)
- [ ] `/connect-integration/src/main/java/com/example/s2s/connect/context/ConnectCallContext.java`
- [ ] `/connect-integration/src/main/java/com/example/s2s/connect/uui/SipHeaderParser.java`
- [ ] `/connect-integration/src/main/java/com/example/s2s/connect/attributes/ConnectAttributeManager.java`
- [ ] `/connect-integration/src/main/java/com/example/s2s/connect/tools/ConnectUpdateAttributesTool.java`
- [ ] `/connect-integration/src/main/java/com/example/s2s/connect/tools/ConnectEndCallTool.java`
- [ ] `/connect-integration/src/main/java/com/example/s2s/connect/tools/ConnectNovaEventHandler.java`
- [ ] `/connect-integration/src/main/java/com/example/s2s/connect/factory/ConnectAwareStreamerFactory.java`
- [ ] `/connect-integration/src/test/java/com/example/s2s/connect/SipHeaderParserTest.java`
- [ ] `/connect-integration/src/test/java/com/example/s2s/connect/ConnectAttributeManagerTest.java`
- [ ] `/connect-integration/src/test/java/com/example/s2s/connect/tools/ConnectNovaEventHandlerTest.java`
- [ ] `/shared/docs/sample-connect-flow.json`

### Modified Files in Voice Gateway Module (3 total)
- [ ] `/voice-gateway/src/main/java/com/example/s2s/voipgateway/NovaSonicVoipGateway.java`
- [ ] `/voice-gateway/src/main/java/com/example/s2s/voipgateway/NovaSonicAudioInput.java`
- [ ] `/voice-gateway/environment.template`

### Modified Files in Infrastructure Module (2 total)
- [ ] `/infra/cdk-ecs/lib/cdk-ecs-stack.ts`
- [ ] `/infra/cdk-ec2-instance/lib/cdk-stack.ts`

### Documentation Files (✅ COMPLETED)
- [x] `/README.md` - Completely rewritten
- [x] `/CLAUDE.md` - Updated with new structure
- [x] `/voice-gateway/README.md` - Module documentation
- [x] `/connect-integration/README.md` - Module documentation
- [x] `/infra/README.md` - Infrastructure documentation
- [x] `/lambdas/prepareExternalTransfer/README.md` - Lambda documentation
- [x] `/scripts/README.md` - Testing utilities documentation
- [x] `/shared/docs/call-flow-sequence.md` - Detailed sequence diagrams
- [x] `/shared/docs/attribute-contract.md` - Attribute specification
- [x] `/shared/types/CallDisposition.md` - Type definitions
- [x] `/.github/workflows/build-and-test.yml` - CI/CD pipeline

---

## Success Criteria

1. **Functional:**
   - Gateway successfully extracts Connect context from SIP headers
   - Nova can call Connect tools during conversation
   - Call ends gracefully with attributes updated
   - Connect flow resumes and routes based on attributes

2. **Non-Functional:**
   - No regression in non-Connect call handling
   - Connect API calls complete in <2s (p95)
   - No call drops due to integration failures
   - All logs/metrics in place for debugging

3. **Documentation:**
   - README covers Connect setup end-to-end
   - CLAUDE.md updated with architecture
   - Sample Connect flow provided
   - All code has javadoc comments

---

## Next Steps After Completion

1. **Performance Optimization:**
   - Connection pooling for Connect client
   - Async attribute updates (don't block audio)

2. **Enhanced Features:**
   - Support for Connect Chat (text modality)
   - Integration with Connect Customer Profiles
   - Real-time transcription storage in Connect

3. **Monitoring:**
   - Custom CloudWatch metrics
   - Connect integration dashboard
   - Alerting on API failures

---

**Document Version:** 1.0
**Last Updated:** 2025-10-25
**Author:** Claude Code
