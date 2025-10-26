# Connect Integration Module

**Owner:** Dev B

This module handles all Amazon Connect-specific integration logic, including:
- SIP header parsing (UUI and custom headers)
- Contact attribute management via Connect API
- Connect-aware Nova tools
- Call context management

## Structure

```
src/main/java/com/example/s2s/connect/
  ├── attributes/
  │   └── ConnectAttributeManager.java      # Connect API client
  ├── context/
  │   └── ConnectCallContext.java           # Call session context
  ├── uui/
  │   └── SipHeaderParser.java              # SIP header extraction
  ├── tools/
  │   ├── ConnectNovaEventHandler.java      # Unified event handler
  │   ├── ConnectUpdateAttributesTool.java  # Mid-call attribute updates
  │   └── ConnectEndCallTool.java           # Graceful termination
  └── factory/
      └── ConnectAwareStreamerFactory.java  # Streamer factory with Connect context
```

## Building

```bash
mvn clean package
```

## Testing

```bash
mvn test
```

## Dependencies

This module depends on:
- `/voice-gateway` - For integration with the SIP/RTP stack
- AWS SDK Connect client
- Lombok for boilerplate reduction

## Environment Variables

- `CONNECT_REGION` - AWS region for Connect API (default: us-east-1)
- `CONNECT_INSTANCE_ID` - Connect instance ID (optional if passed via SIP headers)

## Integration Points

### With Voice Gateway
- Called from `NovaSonicVoipGateway.createCallHandler()` to parse SIP headers
- Provides `ConnectAwareStreamerFactory` for Connect-aware calls

### With Connect
- Calls `UpdateContactAttributes` API during/after calls
- Reads contact context from SIP INVITE headers

## Development Guidelines

1. **Error Handling:** Never crash calls on Connect API failures - log and continue
2. **Logging:** Use SLF4J with MDC for call correlation (callId, contactId)
3. **Testing:** Mock Connect API calls in unit tests
4. **Thread Safety:** `ConnectAttributeManager` is thread-safe (concurrent calls)

## Contact Attribute Contract

See `/shared/docs/attribute-contract.md` for the agreed-upon attribute schema.
