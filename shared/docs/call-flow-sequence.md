# Call Flow Sequence Diagram

This document describes the end-to-end call flow when Amazon Connect transfers a call to the Nova S2S VoIP Gateway.

## High-Level Flow

```
Customer → Connect Flow → External Transfer → Voice Gateway → Nova Sonic
                                    ↓
                            Update Attributes
                                    ↓
                            Resume Connect Flow → Route Decision
```

## Detailed Sequence

### Phase 1: Call Setup (Connect → Gateway)

```
┌─────────┐         ┌─────────┐         ┌──────────────┐         ┌──────────┐
│Customer │         │ Connect │         │Voice Gateway │         │Nova Sonic│
└────┬────┘         └────┬────┘         └──────┬───────┘         └────┬─────┘
     │                   │                     │                      │
     │ 1. Call Connect   │                     │                      │
     ├──────────────────>│                     │                      │
     │                   │                     │                      │
     │   2. IVR/Flow     │                     │                      │
     │<─────────────────>│                     │                      │
     │                   │                     │                      │
     │                   │ 3. Lambda: Set      │                      │
     │                   │    SIP Headers      │                      │
     │                   │    (contactId, etc) │                      │
     │                   │                     │                      │
     │                   │ 4. SIP INVITE       │                      │
     │                   │    (with headers)   │                      │
     │                   ├────────────────────>│                      │
     │                   │                     │                      │
     │                   │                     │ 5. Parse Headers     │
     │                   │                     │    Extract Context   │
     │                   │                     │                      │
     │                   │ 6. SIP 200 OK       │                      │
     │                   │<────────────────────┤                      │
     │                   │                     │                      │
     │                   │ 7. RTP Media        │                      │
     │<─────────────────────────────────────────┤                      │
     │   (Customer now connected to Gateway)    │                      │
```

**Key Details:**
- **Step 3:** Lambda function `prepareExternalTransfer` sets:
  - `X-Connect-ContactId: <InitialContactId>`
  - `X-Connect-InstanceId: <InstanceId>`
  - `X-Correlation-Id: <custom tracking ID>`
- **Step 5:** `SipHeaderParser.parseConnectContext()` extracts headers into `ConnectCallContext`

### Phase 2: Nova Conversation (Gateway ↔ Nova)

```
┌─────────┐         ┌──────────────┐         ┌──────────┐
│Customer │         │Voice Gateway │         │Nova Sonic│
└────┬────┘         └──────┬───────┘         └────┬─────┘
     │                     │                      │
     │                     │ 8. Start Session     │
     │                     ├─────────────────────>│
     │                     │                      │
     │                     │ 9. Greeting Audio    │
     │<────────────────────┤<─────────────────────┤
     │                     │                      │
     │ 10. Customer Speech │                      │
     ├────────────────────>│ 11. Audio Stream     │
     │                     ├─────────────────────>│
     │                     │                      │
     │                     │                      │ 12. Process +
     │                     │                      │     Generate
     │                     │                      │
     │                     │ 13. Nova Response    │
     │ 14. Play Audio      │<─────────────────────┤
     │<────────────────────┤                      │
     │                     │                      │
     │     (Conversation continues...)            │
     │                     │                      │
```

**Key Details:**
- **Step 8:** Gateway calls `BedrockRuntimeAsyncClient.invokeModelWithBidirectionalStream()`
- **Step 9:** Gateway plays greeting WAV (configurable via `GREETING_FILENAME`)
- **Steps 10-14:** Bidirectional streaming with turn-taking and barge-in

### Phase 3: Mid-Call Attribute Updates (Optional)

```
┌──────────────┐         ┌──────────┐         ┌─────────┐
│Voice Gateway │         │Nova Sonic│         │ Connect │
└──────┬───────┘         └────┬─────┘         └────┬────┘
       │                      │                    │
       │                      │ 15. Tool Use:      │
       │                      │     updateContact  │
       │                      │     Attributes     │
       │ 16. Tool Invocation  │                    │
       │<─────────────────────┤                    │
       │                      │                    │
       │ 17. Connect API:     │                    │
       │     UpdateContact    │                    │
       │     Attributes       │                    │
       ├──────────────────────────────────────────>│
       │                      │                    │
       │                      │                    │ 18. Store
       │                      │                    │     Attributes
       │                      │                    │
       │ 19. Success Response │                    │
       │<──────────────────────────────────────────┤
       │                      │                    │
       │ 20. Tool Result      │                    │
       ├─────────────────────>│                    │
       │                      │                    │
```

**Key Details:**
- **Step 15:** Nova decides to record information (e.g., customer account number)
- **Step 17:** `ConnectAttributeManager.updateAttributes()` called
- **Step 18:** Attributes stored on live contact (visible in real-time)
- Example attributes: `nova_customer_id=12345`, `nova_issue_type=billing`

### Phase 4: Call Termination (Gateway → Connect)

```
┌─────────┐    ┌──────────────┐    ┌──────────┐    ┌─────────┐
│Customer │    │Voice Gateway │    │Nova Sonic│    │ Connect │
└────┬────┘    └──────┬───────┘    └────┬─────┘    └────┬────┘
     │                │                 │               │
     │                │ 21. Tool Use:   │               │
     │                │     endCallAnd  │               │
     │                │     Transfer    │               │
     │                │<────────────────┤               │
     │                │                 │               │
     │                │ 22. Validate    │               │
     │                │     nextAction  │               │
     │                │                 │               │
     │                │ 23. Connect API:│               │
     │                │     Update Final│               │
     │                │     Attributes  │               │
     │                ├─────────────────────────────────>│
     │                │                 │               │
     │                │                 │               │ 24. Store:
     │                │                 │               │     nova_next
     │                │                 │               │     nova_summary
     │                │                 │               │     nova_target_queue
     │                │                 │               │
     │                │ 25. Tool Success│               │
     │                ├────────────────>│               │
     │                │                 │               │
     │                │ 26. Set End Flag│               │
     │                │                 │               │
     │                │ 27. Close Streams               │
     │                │<────────────────┤               │
     │                │                 │               │
     │                │ 28. SIP BYE     │               │
     │                ├─────────────────────────────────>│
     │                │                 │               │
     │                │                 │               │ 29. Resume Flow
     │                │                 │               │     Read Attrs
     │                │                 │               │
     │                │ 30. Route based on nova_next    │
     │<────────────────────────────────────────────────┤
     │                │                 │               │
```

**Key Details:**
- **Step 21:** Nova calls `endCallAndTransfer` tool with routing decision
- **Step 22:** Gateway validates `nextAction` enum: `agent` | `survey` | `end`
- **Step 23:** Final attributes written:
  - `nova_next` - Routing decision
  - `nova_target_queue` - Queue ARN/name (if agent)
  - `nova_summary` - Plain text conversation summary
  - `nova_reason` - Reason code (e.g., `issue_resolved`, `needs_agent`)
  - `nova_call_duration` - Duration in seconds
  - `nova_timestamp` - ISO-8601 timestamp
- **Step 26:** `ConnectNovaEventHandler.isCallEndRequested()` returns true
- **Step 27:** `NovaSonicAudioInput` detects flag, calls `halt()` → cascades to RTP close
- **Step 28:** mjSIP sends BYE automatically when media closes
- **Step 29:** Connect resumes flow from "Transfer to phone number" block (resume after disconnect enabled)
- **Step 30:** Connect flow checks `$.Attributes.nova_next` and routes accordingly

### Phase 5: Post-Transfer Routing (Connect Flow)

```
┌─────────┐         ┌─────────┐
│Customer │         │ Connect │
└────┬────┘         └────┬────┘
     │                   │
     │                   │ 31. Check Attribute
     │                   │     nova_next
     │                   │
     │                   ├─ If "agent":
     │                   │    Transfer to Queue
     │                   │    (nova_target_queue)
     │                   │
     │                   ├─ If "survey":
     │                   │    Get Customer Input
     │                   │    (satisfaction survey)
     │                   │
     │                   └─ If "end":
     │                        Disconnect
     │                   │
     │ 32. Final Action  │
     │<──────────────────┤
     │                   │
```

**Example Connect Flow Logic:**
```json
{
  "Type": "CheckAttribute",
  "Attribute": "nova_next",
  "Conditions": [
    {"Value": "agent", "NextAction": "TransferToQueue"},
    {"Value": "survey", "NextAction": "PlaySurvey"},
    {"Value": "end", "NextAction": "Disconnect"}
  ]
}
```

## Timing Considerations

| Phase | Typical Duration | Notes |
|-------|------------------|-------|
| Call Setup (1-7) | 1-3 seconds | SIP negotiation + RTP setup |
| Nova Session Start (8-9) | 500ms-1s | Bedrock connection + greeting |
| Per Turn (10-14) | 2-5 seconds | Voice activity detection + inference |
| Attribute Update (15-20) | 200-500ms | Connect API call |
| Call Termination (21-28) | 500ms-1s | Final update + SIP teardown |
| Connect Routing (29-32) | <500ms | Flow resume + routing logic |

## Error Scenarios

### Scenario A: Connect API Failure

```
Gateway → Connect: UpdateContactAttributes
Connect → Gateway: 500 Internal Server Error
Gateway: Log error, continue call (don't crash)
```

**Handling:** Gateway logs error but doesn't terminate call. Attributes may be missing but conversation continues.

### Scenario B: Missing SIP Headers

```
Connect → Gateway: SIP INVITE (no X-Connect-* headers)
Gateway: parseConnectContext() returns null
Gateway: Falls back to standard NovaStreamerFactory (no Connect tools)
```

**Handling:** Call proceeds as non-Connect call with DateTime tools only.

### Scenario C: Premature Customer Hangup

```
Customer → Gateway: SIP BYE (before Nova ends call)
Gateway: Detect hangup, write best-effort attributes
Gateway → Connect: UpdateContactAttributes (nova_reason=customer_hangup)
```

**Handling:** Gateway catches hangup event, writes partial attributes with `nova_reason=customer_hangup`.

### Scenario D: Nova Tool Timeout

```
Nova → Gateway: endCallAndTransfer tool invocation
Gateway → Connect: UpdateContactAttributes (timeout after 5s)
Gateway: Log error, send BYE anyway (don't block hangup)
```

**Handling:** Gateway has 5s timeout on Connect API. If timeout, logs error and proceeds with BYE. Connect flow handles missing attributes gracefully.

## Security Considerations

1. **SIP Header Injection:** Validate all header values, sanitize before using in API calls
2. **Contact ID Validation:** Ensure contactId is UUID format before Connect API calls
3. **Attribute Size Limits:** Enforce 32KB total attribute size limit
4. **Sensitive Data:** Never log customer PII from attributes or audio
5. **IAM Permissions:** Least privilege - only `UpdateContactAttributes` on specific instance

## Monitoring Points

Track these metrics for operational health:

1. **Connect API Success Rate:** `UpdateContactAttributes` success vs. failure
2. **Header Parse Success Rate:** SIP headers present and valid vs. missing
3. **Average Call Duration:** Time from INVITE to BYE
4. **Routing Distribution:** Counts of `nova_next` values (agent/survey/end)
5. **Premature Hangups:** Customer BYE before Nova ends call

## References

- SIP RFC 3261: https://www.rfc-editor.org/rfc/rfc3261
- Connect API Reference: https://docs.aws.amazon.com/connect/latest/APIReference/
- Nova Sonic Documentation: https://docs.aws.amazon.com/nova/
