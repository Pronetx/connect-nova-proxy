# Voice Gateway Module

**Owner:** Dev A

This module contains the core SIP/RTP voice gateway that interfaces with Nova Sonic.

## Original Code

This module contains the original Nova S2S VoIP Gateway code, reorganized into the new repo structure. See the original README in the root directory for full documentation.

## Structure

```
voice-gateway/
  ├── src/main/java/com/example/s2s/voipgateway/
  │   ├── NovaSonicVoipGateway.java        # Main entry point, SIP UA
  │   ├── NovaMediaConfig.java             # Configuration
  │   ├── NovaSonicAudioInput.java         # RTP → Nova audio bridge
  │   ├── NovaSonicAudioOutput.java        # Nova → RTP audio bridge
  │   ├── constants/                       # Audio format constants
  │   └── nova/                            # Nova Sonic integration
  │       ├── NovaStreamerFactory.java     # Creates media streamers
  │       ├── AbstractNovaS2SEventHandler.java  # Base event handler
  │       ├── NovaS2SBedrockInteractClient.java # Bedrock client
  │       ├── event/                       # Event types
  │       ├── io/                          # Audio I/O streams
  │       ├── observer/                    # Observer pattern
  │       ├── tools/                       # Nova tools (DateTime, etc.)
  │       └── transcode/                   # Audio transcoding (µ-law ↔ PCM)
  ├── docker/                              # Dockerfile for containerization
  ├── pom.xml                              # Maven build file
  ├── run.sh                               # Quick compile + run script
  ├── Makefile                             # Docker build targets
  └── environment.template                 # Environment variable template
```

## Key Responsibilities

### 1. SIP User Agent
- Register with SIP server
- Answer incoming calls (INVITE)
- Negotiate RTP media sessions
- Send keep-alive packets
- Handle call termination (BYE)

**Main class:** `NovaSonicVoipGateway.java` (extends `RegisteringMultipleUAS`)

### 2. RTP Media Handling
- Bind UDP port range for RTP (default: 10000-20000)
- Receive audio from caller (µ-law 8kHz)
- Transcode to PCM 16-bit for Nova
- Transmit audio back to caller

**Key classes:**
- `NovaSonicAudioInput.java` - RTP receiver
- `NovaSonicAudioOutput.java` - RTP transmitter
- `UlawToPcmTranscoder.java` / `PcmToULawTranscoder.java`

### 3. Nova Sonic Integration
- Establish bidirectional streaming session with Bedrock
- Send audio frames to Nova (`audioInput` events)
- Receive audio frames from Nova (`audioOutput` events)
- Handle tool invocations from Nova
- Manage session lifecycle

**Key classes:**
- `NovaStreamerFactory.java` - Creates streamers with Bedrock client
- `NovaS2SBedrockInteractClient.java` - Bedrock API client
- `NovaS2SResponseHandler.java` - Processes Nova events

### 4. Tool System
- Modular tool architecture with auto-discovery
- Tools are automatically registered via `ToolProvider.java`
- Each tool implements the `Tool` interface with schema, description, and handler logic

**Current Tools:**
- `DateTimeTool` - Provides current date and time
- `SendOTPTool` - Generates and sends 4-digit OTP via SMS
- `VerifyOTPTool` - Verifies OTP code from caller
- `HangupTool` - Ends the call gracefully
- `GetCallerPhoneTool` - Returns caller's phone number
- `CollectAddressTool` - Collects and validates caller's address
- `SendSMSTool` - Generic SMS sender for any message

**Architecture:**
- `ToolProvider.java` - Central registry with auto-discovery
- `ToolFactory.java` - Creates tool instances with dependency injection
- `ToolRegistry.java` - Manages tool configuration for Nova
- `ModularNovaS2SEventHandler.java` - Routes tool invocations

**Adding New Tools:**
1. Create a class implementing `Tool` interface
2. Add class name to `ToolProvider.TOOL_CLASSES` list
3. Tool is automatically discovered and registered

### 5. Address Collection & SMS Messaging

The gateway supports conversational address collection with optional SMS confirmation:

**Address Collection Flow:**
1. Nova collects address components conversationally (street, suite/apt, city, state, zip)
2. Reads back complete address for confirmation
3. Calls `CollectAddressTool` to validate and store address
4. Optionally asks about SMS confirmation
5. Uses `SendSMSTool` to send formatted address via SMS

**SMS Capabilities:**
- Send to caller's number (default) or alternate number provided
- Generic `SendSMSTool` can send any message
- Uses AWS Pinpoint for SMS delivery
- Supports transactional messaging

**Configuration:**
- Set `PINPOINT_APPLICATION_ID` environment variable
- Set `PINPOINT_ORIGINATION_NUMBER` (defaults to +13682104244)
- Enable prompt with `@tool collectAddressTool` and `@tool sendSMSTool`

## Building

```bash
# Standard Maven build
mvn clean package

# Output: target/s2s-voip-gateway-<version>.jar
```

## Running

### Quick Start (Compile + Run)
```bash
./run.sh
```

### Run JAR Directly
```bash
java -jar target/s2s-voip-gateway-*.jar
```

### Run with Maven
```bash
mvn exec:java -Dexec.mainClass=com.example.s2s.voipgateway.NovaSonicVoipGateway
```

### Docker
```bash
# Build image
make build

# Run (requires environment file)
make run
```

## Deployment Scripts

For deploying to EC2 instances, see [scripts/README.md](scripts/README.md).

Quick deployment:
```bash
# First time setup
./scripts/configure-environment.sh  # Configure environment variables
./scripts/setup-service.sh          # Create systemd service

# Subsequent deployments
./scripts/build-and-deploy.sh       # Build and deploy

# Monitoring
./scripts/status.sh                 # Check service status
./scripts/logs.sh                   # View logs
```

All scripts support deploying to multiple EC2 instances tagged with `NovaSonicVoIPGatewayInstance`.

## Logging

### CloudWatch Log Streams

The application uses AWS CloudWatch for centralized logging with automatic log stream management:

**Log Stream Naming Pattern:**
```
{INSTANCE_ID}/{YYYY-MM-DD}/{START_TIME}
```

Example: `i-0abc123/2025-10-26/1729900800`

**Benefits:**
- **Manageable file sizes** - New stream created per service restart
- **Easy troubleshooting** - Logs organized by instance, date, and session
- **Automatic rotation** - Prevents single large log files

**Environment Variables for Logging:**
- `INSTANCE_ID` - EC2 instance ID (auto-detected by AWS metadata)
- `START_TIME` - Unix timestamp of service start (auto-generated by systemd)

**Configuration:**
- Log group: `/aws/voip-gateway/application`
- Region: `us-west-2` (configured in `logback.xml`)
- Appender: AWS Logs Appender with async wrapper

See `src/main/resources/logback.xml` for detailed configuration.

## Configuration

### Environment Variables

**SIP Configuration:**
- `SIP_SERVER` - SIP server hostname (required for env mode)
- `SIP_USER` - SIP username
- `AUTH_USER`, `AUTH_PASSWORD`, `AUTH_REALM` - SIP authentication
- `DISPLAY_NAME` - Caller ID
- `SIP_VIA_ADDR` - Override Via header address
- `SIP_KEEPALIVE_TIME` - Keep-alive interval (ms, default: 60000)
- `DEBUG_SIP` - Log SIP packets (true/false)

**Media Configuration:**
- `MEDIA_ADDRESS` - IP address for RTP
- `MEDIA_PORT_BASE` - First RTP port (default: 10000)
- `MEDIA_PORT_COUNT` - RTP port pool size (default: 10000)
- `GREETING_FILENAME` - WAV file for call greeting

**Nova Configuration:**
- `NOVA_VOICE_ID` - Nova voice (default: en_us_matthew)
- `NOVA_PROMPT` - System prompt for Nova

**Connect Integration** (used by `/connect-integration` module):
- `CONNECT_REGION` - AWS region (default: us-east-1)
- `CONNECT_INSTANCE_ID` - Connect instance ID (optional)

See `environment.template` for full list.

## Integration with Connect Module

This module is being extended to support Amazon Connect integration. The `/connect-integration` module will:
- Parse Connect context from SIP INVITE headers
- Provide Connect-specific Nova tools
- Update Connect contact attributes
- Handle graceful call termination

**Integration point:** `NovaSonicVoipGateway.createCallHandler()` (line 102)

See `/shared/docs/call-flow-sequence.md` for details.

## Testing

### Unit Tests
```bash
mvn test
```

### Integration Test with SIPp
```bash
# In one terminal
./run.sh

# In another terminal
cd ../scripts
./local-call-sim.sh --with-connect-headers
```

### Manual SIP Testing
1. Configure environment variables
2. Start gateway: `./run.sh`
3. Watch for SIP registration (200 OK)
4. Call the SIP account from any SIP phone
5. Gateway answers and starts Nova conversation

## Development Guidelines

### Adding New Audio Codecs
1. Add transcode class in `nova/transcode/`
2. Update `MediaDesc` in `NovaSonicVoipGateway.createDefaultMediaDescs()`
3. Update SDP in SIP negotiation

### Adding New Nova Tools
1. Create tool class extending `AbstractNovaS2SEventHandler`
2. Implement `handleToolInvocation()` method
3. Define tool spec in `getToolConfiguration()`
4. Update `NovaStreamerFactory.createMediaStreamer()` to instantiate

Example: See `DateTimeNovaS2SEventHandler.java`

### Modifying Call Flow
Main call handling in `NovaSonicVoipGateway`:
- `createCallHandler()` - Creates handler for incoming call
- `onUaIncomingCall()` - Callback when INVITE received
- `onUaCallClosed()` - Callback when call terminates

## Dependencies

Key external libraries:
- **mjSIP** (2.0.5) - SIP stack and UA
- **AWS SDK v2** (2.31.19) - Bedrock client
- **Jackson** (2.18.3) - JSON processing
- **Lombok** (1.18.38) - Boilerplate reduction
- **Logback** (1.5.11) - Logging

See `pom.xml` for full list.

## Troubleshooting

**SIP registration fails:**
- Verify `SIP_SERVER` is reachable
- Check credentials in environment variables
- Enable `DEBUG_SIP=true` to see packets

**No audio on calls:**
- Check RTP ports are open (UDP 10000-20000)
- Verify `MEDIA_ADDRESS` is correct (public IP if behind NAT)
- Enable audio transcoding logs

**Nova connection fails:**
- Check AWS credentials are configured
- Verify IAM permissions for Bedrock
- Check region is `us-east-1` (Nova Sonic availability)
- Review `NovaStreamerFactory` logs

**Greeting doesn't play:**
- Verify `GREETING_FILENAME` points to valid WAV file
- Check file format: 8kHz, 16-bit PCM, mono
- File can be absolute path or in classpath

## Performance Tuning

**For high call volumes:**
- Increase `MEDIA_PORT_COUNT` (more concurrent calls)
- Use ECS auto-scaling (see `/infra/cdk-ecs`)
- Tune JVM heap size
- Enable connection pooling for Bedrock client

**For low latency:**
- Deploy in same region as Nova Sonic (us-east-1)
- Use enhanced networking on EC2
- Reduce audio buffer sizes
- Optimize transcoding loops

## References

- [Original README](/README.md) - Full project documentation
- [mjSIP GitHub](https://github.com/haumacher/mjSIP) - SIP library
- [Nova Sonic Docs](https://docs.aws.amazon.com/nova/) - AWS documentation
- [Call Flow Sequence](/shared/docs/call-flow-sequence.md) - Integration architecture
