# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a **Nova S2S VoIP Gateway** - a Java application that acts as a SIP (Session Initiation Protocol) user agent to bridge phone calls with Amazon Nova Sonic speech-to-speech AI. When someone calls the configured SIP number, the gateway answers and routes audio bidirectionally between RTP (Real-time Transport Protocol) and Nova Sonic.

## Architecture

The application has three main integration points:

1. **SIP/VoIP Layer** (mjSIP library)
   - Entry point: `NovaSonicVoipGateway.java` (main class at `com.example.s2s.voipgateway.NovaSonicVoipGateway`)
   - Handles SIP registration, call answering, and RTP media sessions
   - Uses mjSIP fork from https://github.com/haumacher/mjSIP (GPLv2 licensed)

2. **Nova Integration Layer**
   - Entry point: `NovaStreamerFactory.java`
   - Creates Bedrock client and establishes audio streams with Nova Sonic
   - Uses AWS SDK v2 with HTTP/2 for streaming
   - Region: `US_EAST_1`, Model: `amazon.nova-sonic-v1:0`

3. **Audio Bridging**
   - `NovaSonicAudioInput.java`: RTP → Nova (sends audio from caller to Nova)
   - `NovaSonicAudioOutput.java`: Nova → RTP (sends audio from Nova to caller)
   - Audio transcoding: µ-law (8kHz) ↔ PCM (16-bit, 8kHz)

## Tool Extension System

Nova Sonic can be extended with custom tools/functions. The current implementation includes DateTime and Hangup tools.

### Available Built-in Tools

**DateTimeNovaS2SEventHandler** (`com.example.s2s.voipgateway.nova.tools.DateTimeNovaS2SEventHandler`):
- `getDateTool` - Provides current date information (timezone, year, month, day, day of week)
- `getTimeTool` - Provides current time information (timezone, formatted time)
- `hangupTool` - Allows Nova to terminate the call when conversation is complete or user requests to hang up

### Adding New Tools

To add custom tools:
1. Extend `AbstractNovaS2SEventHandler` class
2. Implement `handleToolInvocation()` method to process tool calls
3. Define tool specifications in your handler's `getToolConfiguration()`
4. Instantiate your handler in `NovaStreamerFactory.createMediaStreamer()` (currently line 61 instantiates `DateTimeNovaS2SEventHandler`)

### Tool Implementation Pattern

Tools follow the Nova Sonic event protocol:
1. Register tool in `PromptStartEvent.ToolConfiguration` with name, description, and input schema
2. Nova emits `toolUse` event when invoking the tool
3. Handler detects `toolUse` in response stream and calls `handleToolInvocation()`
4. Handler sends `toolResult` event back to Nova with results
5. For tools requiring SIP-level actions (like hangup), use callback pattern via `NovaMediaConfig`

### Hangup Tool Implementation

The hangup tool demonstrates the callback pattern for bridging architectural layers:
- **Callback Setup**: `NovaSonicVoipGateway.onUaIncomingCall()` sets `mediaConfig.setHangupCallback(() -> ua.hangup())`
- **Callback Threading**: `NovaStreamerFactory` passes callback to event handler via `eventHandler.setHangupCallback()`
- **Callback Invocation**: `handleHangupTool()` invokes callback after 3-second delay to allow Nova to say goodbye
- **Result**: Properly terminates SIP session with BYE message

This pattern allows event handlers deep in the streaming layer to trigger actions at the SIP layer.

## Development Commands

### Prerequisites Setup
- JDK 9+ (Amazon Corretto, OpenJDK, or Oracle JDK)
- Maven (or `sudo yum install maven` on Amazon Linux)
- Configure Maven `~/.m2/settings.xml` with GitHub credentials for mjSIP repository (see README section "Maven settings.xml")
- For CDK deployments: Node.js + AWS CDK v2

### Build & Run

**Quick run** (compile + execute):
```bash
./run.sh
```

**Standard Maven build**:
```bash
mvn package
# Output: target/s2s-voip-gateway-<version>.jar
```

**Run compiled JAR**:
```bash
java -jar target/s2s-voip-gateway-<version>.jar
```

**Compile only**:
```bash
mvn compile
```

**Run main class directly**:
```bash
mvn exec:java -Dexec.mainClass=com.example.s2s.voipgateway.NovaSonicVoipGateway
```

### Docker

**Build Docker image**:
```bash
make build
# Requires: target/s2s-voip-gateway*.jar already built
```

**Run in Docker** (requires `environment` file):
```bash
make run          # Interactive mode
make run-bg       # Background daemon
```

### CDK Deployments

**ECS deployment** (production-like):
```bash
cd cdk-ecs
npm install
cdk bootstrap      # First time only
cdk deploy
cdk destroy        # Cleanup
```

**EC2 deployment** (development/testing):
```bash
cd cdk-ec2-instance
npm install
cdk bootstrap      # First time only
cdk deploy
cdk destroy        # Cleanup
```

## Configuration

The application supports two configuration modes:

1. **Environment Variable Mode** (preferred for containers): Set `SIP_SERVER` to activate
2. **Config File Mode**: Uses `.mjsip-ua` file if `SIP_SERVER` is not set

### Key Environment Variables

**SIP Configuration**:
- `SIP_SERVER` - SIP server hostname/IP (triggers env mode when set)
- `SIP_USER`, `AUTH_USER`, `AUTH_PASSWORD`, `AUTH_REALM` - Authentication
- `DISPLAY_NAME` - Caller ID display name
- `SIP_VIA_ADDR` - Override Via header address
- `SIP_KEEPALIVE_TIME` - Keep-alive interval in ms (default: 60000)
- `DEBUG_SIP` - Log SIP packets (true/false)

**Media/RTP Configuration**:
- `MEDIA_ADDRESS` - IP address for RTP traffic
- `MEDIA_PORT_BASE` - First RTP port (default: 10000)
- `MEDIA_PORT_COUNT` - RTP port pool size (default: 10000)
- `GREETING_FILENAME` - WAV file to play on call answer (default: "hello-how.wav")

**Nova Configuration**:
- `NOVA_VOICE_ID` - Voice to use (default: "en_us_matthew", see AWS docs for available voices)
- `NOVA_PROMPT` - System prompt (default in `NovaMediaConfig.java:7-9`)

Configuration resolution: `NovaSonicVoipGateway.main()` at line 116

## Networking Requirements

**Inbound**:
- UDP 5060 (SIP)
- UDP 10000-20000 (RTP, configurable via `MEDIA_PORT_*` variables)

**Outbound**: All traffic

Note: mjSIP lacks uPNP/ICE/STUN; instance needs public IP or proper NAT configuration.

## Key Implementation Details

- **Main entry**: `com.example.s2s.voipgateway.NovaSonicVoipGateway.main()`
- **Audio format**: µ-law 8kHz (RTP) ↔ PCM 16-bit 8kHz signed (Nova)
- **Event handling**: Event-driven architecture with `NovaS2SEventHandler` interface
- **Audio streaming**: Queued buffering via `QueuedUlawInputStream`
- **Tool results**: JSON-serialized, sent via `ToolResultEvent`
- **Dependencies**: Lombok for boilerplate reduction, Jackson for JSON, RxJava/Reactor for reactive streams

## Deployment Guide (EC2 with Chime Voice Connector)

### Prerequisites
1. Build mjSIP locally and install to Maven repository:
   ```bash
   git clone https://github.com/haumacher/mjSIP.git
   cd mjSIP
   mvn clean install -DskipTests
   ```

2. Build voice gateway JAR:
   ```bash
   cd voice-gateway
   mvn clean package -DskipTests
   ```

3. Deploy EC2 instance with CDK:
   ```bash
   cd infra/cdk-ec2-instance
   npm install
   cdk deploy
   ```

### Chime Voice Connector Setup

1. **Disable Encryption**: Voice Connector must have encryption disabled since mjSIP doesn't support TLS/SRTP:
   ```bash
   aws chime-sdk-voice update-voice-connector \
     --voice-connector-id <ID> \
     --encryption Disabled
   ```

2. **Configure Origination**: Route inbound calls to gateway EC2 instance:
   - Protocol: UDP
   - Host: EC2 Elastic IP
   - Port: 5060
   - Priority: 1
   - Weight: 1

### Gateway Configuration

Create `/home/ec2-user/.mjsip-ua` on EC2 instance:
```properties
# Inbound trunk mode - no registration required
do_register=false
via-addr=<EC2_ELASTIC_IP>
host-port=5060
media-port=10000
port-count=10000
no-prompt=yes
log-all-packets=yes
```

### Deploy and Start Gateway

```bash
# Upload JAR to S3
aws s3 cp target/s2s-voip-gateway-*.jar s3://<deployment-bucket>/

# Download on EC2 (via SSM)
aws ssm send-command \
  --instance-ids <INSTANCE_ID> \
  --document-name "AWS-RunShellScript" \
  --parameters 'commands=["cd /home/ec2-user && aws s3 cp s3://<bucket>/s2s-voip-gateway-*.jar ."]'

# Start gateway with us-east-1 region for Nova Sonic
aws ssm send-command \
  --instance-ids <INSTANCE_ID> \
  --document-name "AWS-RunShellScript" \
  --parameters 'commands=["cd /home/ec2-user && AWS_REGION=us-east-1 nohup java -jar s2s-voip-gateway-*.jar > gateway.log 2>&1 &"]'
```

### Troubleshooting

**Issue**: Call connects but stays ringing forever
**Cause**: Missing SDP media descriptor in 200 OK response
**Solution**: Fixed in `NovaSonicVoipGateway.java:101-118` - now falls back to `createDefaultMediaDescs()` when config file mode is used

**Issue**: TLS/SRTP encryption errors
**Cause**: Voice Connector encryption enabled
**Solution**: Disable encryption on Voice Connector (see above)

**Issue**: Wrong AWS region for Bedrock
**Solution**: Always set `AWS_REGION=us-east-1` when starting gateway (Nova Sonic only available in us-east-1)

## Important Notes

- This is a **proof of concept** - not production-ready
- The application uses **host network mode** in ECS deployments to bind large UDP port ranges
- GitHub Maven credentials required for mjSIP dependency (or build mjSIP locally first)
- Default Nova prompt optimized for short, conversational responses (2-3 sentences)
- **Critical**: When using `.mjsip-ua` config file, media descriptors must be initialized properly (see `NovaSonicVoipGateway.java:101-118`)
