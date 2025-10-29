# FreeSWITCH → Nova Sonic Integration Guide

## Architecture Overview

This integration uses a **two-tier architecture** that separates telephony infrastructure from AI processing:

```
┌──────────────┐     ┌────────────────────┐     ┌──────────────────┐     ┌──────────────┐
│  Phone Call  │─RTP→│   FreeSWITCH       │─TCP→│  Java Gateway    │─HTTP→│ Nova Sonic   │
│   (Caller)   │←RTP─│  (Telephony Tier)  │←TCP─│  (AI Tier)       │←HTTP─│  (Bedrock)   │
│              │     │  mod_nova_sonic    │     │  Port 8085       │     │              │
└──────────────┘     └────────────────────┘     └──────────────────┘     └──────────────┘
```

### Benefits of This Architecture

✅ **Separation of Concerns**: Telephony (C/FreeSWITCH) vs AI Logic (Java)
✅ **Independent Scaling**: Scale telephony and AI tiers separately
✅ **Easier Development**: Most logic in Java (easier to maintain than C)
✅ **Production Ready**: Proper error handling, threading, cleanup
✅ **Minimal Dependencies**: C module only needs standard networking

## Components

### 1. FreeSWITCH Module (`mod_nova_sonic`)

**Location**: `/freeswitch-module/src/mod_nova_sonic.c`

**Purpose**: Audio proxy that bridges FreeSWITCH calls with the Java gateway

**Features**:
- Captures audio from FreeSWITCH channels using media bugs
- Streams raw L16 PCM audio (8kHz, 16-bit, mono) via TCP
- Receives audio from Java gateway and plays back to caller
- Simple handshake protocol with session metadata
- No AWS dependencies - pure C networking

**Key Functions**:
- `nova_bug_callback()` - Captures audio from FreeSWITCH channel
- `nova_send_thread()` - Streams audio to Java gateway
- `nova_recv_thread()` - Receives audio from Java gateway
- `nova_session_init()` - Establishes TCP connection to Java

### 2. Java Gateway (Audio Server)

**Location**: `/voice-gateway/src/main/java/com/example/s2s/voipgateway/freeswitch/`

**Components**:
- `FreeSwitchAudioServer.java` - TCP server listening on port 8085
- `FreeSwitchAudioHandler.java` - Handles each audio session
- `FreeSwitchGatewayMain.java` - Main entry point

**Purpose**: Bridges TCP audio streams with Nova Sonic (Bedrock)

**Features**:
- Accepts TCP connections from FreeSWITCH
- Parses handshake with session ID and caller ID
- Connects to Nova Sonic via Bedrock Runtime
- Streams audio bidirectionally:
  - FreeSWITCH → Java → Nova (caller speaking)
  - Nova → Java → FreeSWITCH (AI speaking)
- Supports all existing Nova tools (DateTime, Hangup, SMS, etc.)
- Call recording support (if enabled)

## Installation

### Prerequisites

**FreeSWITCH Instance**:
```bash
# Current FreeSWITCH instance (pre-built AMI)
# Instance ID: i-06fcbe4efc776029b
# Public IP: 44.237.82.96
# Private IP: 10.0.1.121
# SSH Key: /Users/yasser/freeswitch.pem

# Connect via SSH
ssh -i /Users/yasser/freeswitch.pem admin@44.237.82.96

# Or use SSM Session Manager
aws ssm start-session --target i-06fcbe4efc776029b

# Verify FreeSWITCH is running
systemctl status freeswitch

# Ensure FreeSWITCH development headers are available
ls /usr/include/freeswitch/switch.h
```

**Java Gateway Instance**:
```bash
# Current Java Gateway instance
# Instance ID: i-0fa82e4df8fcad08e
# Public IP: 34.208.83.171
# Private IP: 10.0.0.68

# Java 9+ required
java -version

# AWS credentials configured (for Bedrock access)
aws sts get-caller-identity
```

### Step 1: Build FreeSWITCH Module

```bash
cd freeswitch-module

# Build the module
make

# Install to FreeSWITCH
sudo make install

# Verify installation
ls -l /usr/local/freeswitch/mod/mod_nova_sonic.so
```

### Step 2: Configure FreeSWITCH Module

Set the Java gateway address to the Java Gateway instance:

```bash
# Via environment variable (current production setup)
export NOVA_GATEWAY_HOST=34.208.83.171  # Java Gateway public IP
export NOVA_GATEWAY_PORT=8085

# Restart FreeSWITCH
sudo systemctl restart freeswitch
```

Or create `/etc/freeswitch/autoload_configs/nova_sonic.conf.xml`:
```xml
<configuration name="nova_sonic.conf" description="Nova Sonic Audio Proxy">
  <settings>
    <param name="gateway-host" value="34.208.83.171"/>
    <param name="gateway-port" value="8085"/>
  </settings>
</configuration>
```

**Network Configuration**:
- Ensure security group on Java Gateway (i-0fa82e4df8fcad08e) allows TCP 8085 from FreeSWITCH instance (44.237.82.96)
- Or if using private IPs, configure `NOVA_GATEWAY_HOST=10.0.0.68` and ensure VPC routing is correct

### Step 3: Load FreeSWITCH Module

```bash
# In FreeSWITCH CLI (fs_cli)
load mod_nova_sonic

# Or add to modules.conf.xml for autoload
```

### Step 4: Deploy Java Gateway

**Option A: Run Locally**
```bash
cd voice-gateway
mvn package -DskipTests

# Run the gateway
java -jar target/s2s-voip-gateway-0.6-SNAPSHOT.jar
```

**Option B: Run as Service**
```bash
# Create systemd service
sudo nano /etc/systemd/system/nova-gateway.service

[Unit]
Description=Nova Sonic Audio Gateway
After=network.target

[Service]
Type=simple
User=ubuntu
WorkingDirectory=/opt/nova-gateway
ExecStart=/usr/bin/java -jar /opt/nova-gateway/s2s-voip-gateway-0.6-SNAPSHOT.jar
Restart=always
Environment="AWS_REGION=us-east-1"

[Install]
WantedBy=multi-user.target

# Enable and start
sudo systemctl daemon-reload
sudo systemctl enable nova-gateway
sudo systemctl start nova-gateway
```

## FreeSWITCH Dialplan Configuration

Add to `/usr/local/freeswitch/conf/dialplan/default.xml`:

```xml
<!-- Nova Sonic AI Assistant -->
<extension name="nova_sonic_demo">
  <condition field="destination_number" expression="^(8888)$">
    <action application="answer"/>
    <action application="sleep" data="500"/>
    <action application="set" data="bypass_media=false"/>
    <action application="nova_sonic" data=""/>
    <action application="hangup"/>
  </condition>
</extension>
```

Now calling `8888` will connect the caller to Nova Sonic!

## Protocol Details

### TCP Handshake (FreeSWITCH → Java)

When `mod_nova_sonic` connects to the Java gateway, it sends:

```
NOVA_SESSION:<session-uuid>:CALLER:<phone-number>\n
```

Example:
```
NOVA_SESSION:a1b2c3d4-e5f6-7890-abcd-ef1234567890:CALLER:+14155551234\n
```

### Audio Format

**Encoding**: L16 (Linear PCM)
**Sample Rate**: 8000 Hz
**Bits**: 16-bit signed, little-endian
**Channels**: 1 (mono)
**Chunk Size**: 320 bytes (20ms of audio)

Audio flows as raw PCM bytes over the TCP socket after the handshake.

### Nova Sonic Events

The Java gateway translates TCP audio into Nova Sonic events:

1. **StartAudioContent** - Sent once at the beginning
2. **AudioInputEvent** - Sent for each audio chunk (320 bytes)
3. **AudioOutput** - Received from Nova, sent back to FreeSWITCH

## Testing

### 1. Start Java Gateway

```bash
cd voice-gateway
mvn exec:java -Dexec.mainClass=com.example.s2s.voipgateway.freeswitch.FreeSwitchGatewayMain
```

You should see:
```
INFO  FreeSwitchAudioServer - FreeSWITCH Audio Server listening on port 8085
INFO  FreeSwitchAudioServer - Waiting for audio connections from mod_nova_sonic...
```

### 2. Load FreeSWITCH Module

```bash
fs_cli
> load mod_nova_sonic
```

You should see:
```
+OK module loaded
```

### 3. Make Test Call

```bash
# From fs_cli
> originate user/1000 &park

# Or dial 8888 from a SIP phone
```

### 4. Check Logs

**Java Gateway**:
```
INFO  FreeSwitchAudioServer - Accepted audio connection from FreeSWITCH: /127.0.0.1:xxxxx
INFO  FreeSwitchAudioHandler - Handshake received - Session: xxx, Caller: +1234567890
INFO  FreeSwitchAudioHandler - Nova Sonic streaming initialized for FreeSWITCH session xxx
INFO  FreeSwitchAudioHandler - Starting FreeSWITCH → Nova audio stream
INFO  FreeSwitchAudioHandler - Starting Nova → FreeSWITCH audio stream
```

**FreeSWITCH**:
```
INFO mod_nova_sonic - Connecting to Java gateway at 127.0.0.1:8085...
INFO mod_nova_sonic - Successfully connected to Java gateway at 127.0.0.1:8085
INFO mod_nova_sonic - Audio send thread started - streaming to 127.0.0.1:8085
INFO mod_nova_sonic - Audio receive thread started - receiving from 127.0.0.1:8085
```

## Deployment Architectures

### Architecture 1: Single Instance (Development)

```
┌─────────────────────────────────┐
│      EC2 Instance               │
│                                 │
│  ┌──────────────┐               │
│  │ FreeSWITCH   │               │
│  │ (SIP/RTP)    │               │
│  │ mod_nova     │               │
│  └──────┬───────┘               │
│         │ localhost:8085        │
│  ┌──────▼───────┐               │
│  │ Java Gateway │───┐           │
│  │ (AI Engine)  │   │           │
│  └──────────────┘   │           │
│                     │           │
└─────────────────────┼───────────┘
                      │
                      ▼
              ┌─────────────┐
              │ Nova Sonic  │
              │  (Bedrock)  │
              └─────────────┘
```

### Architecture 2: Separate Instances (Production)

```
┌────────────────────┐          ┌────────────────────┐
│  FreeSWITCH        │          │  Java Gateway      │
│  EC2 Instance      │          │  EC2/ECS Instance  │
│                    │          │                    │
│  ┌──────────────┐  │          │  ┌──────────────┐  │
│  │ FreeSWITCH   │  │  TCP     │  │ Java Gateway │  │
│  │ (SIP/RTP)    │──┼──────────┼─→│ (AI Engine)  │──┼──┐
│  │ mod_nova     │  │ :8085    │  │ Port 8085    │  │  │
│  └──────────────┘  │          │  └──────────────┘  │  │
│                    │          │                    │  │
└────────────────────┘          └────────────────────┘  │
                                                        │
                                                        ▼
                                                ┌─────────────┐
                                                │ Nova Sonic  │
                                                │  (Bedrock)  │
                                                └─────────────┘
```

**Benefits**:
- Independent scaling (scale Java gateway without affecting telephony)
- Better fault isolation
- Easier updates and maintenance

### Architecture 3: Load Balanced (High Availability)

```
┌─────────────┐   ┌─────────────┐
│FreeSWITCH #1│   │FreeSWITCH #2│
└──────┬──────┘   └──────┬──────┘
       │                 │
       │    TCP :8085    │
       └────────┬─────────┘
                │
         ┌──────▼──────┐
         │     NLB     │ (Network Load Balancer)
         └──────┬──────┘
                │
       ┌────────┴────────┐
       │                 │
┌──────▼──────┐   ┌──────▼──────┐
│Java Gateway │   │Java Gateway │
│    #1       │   │    #2       │
└─────────────┘   └─────────────┘
       │                 │
       └────────┬─────────┘
                │
         ┌──────▼──────┐
         │ Nova Sonic  │
         │  (Bedrock)  │
         └─────────────┘
```

## Troubleshooting

### FreeSWITCH Module Won't Load

```bash
# Check module exists
ls -l /usr/local/freeswitch/mod/mod_nova_sonic.so

# Check for missing dependencies
ldd /usr/local/freeswitch/mod/mod_nova_sonic.so

# Check FreeSWITCH logs
tail -f /usr/local/freeswitch/log/freeswitch.log
```

### Cannot Connect to Java Gateway

```bash
# Check Java gateway is running
netstat -tlnp | grep 8085

# Check firewall allows TCP 8085
sudo iptables -L -n | grep 8085

# Check security group (if on AWS)
aws ec2 describe-security-groups --group-ids sg-xxxxx

# Test connection manually
telnet <java-gateway-ip> 8085
```

### No Audio / Silent Calls

**Check FreeSWITCH logs**:
```bash
fs_cli
> console loglevel 7
> originate user/1000 &echo
```

Look for:
- "Nova media bug initialized"
- "Audio send thread started"
- "Sent X bytes of PCM audio to gateway"

**Check Java logs**:
```bash
# Look for audio streaming messages
grep "audio stream" /var/log/nova-gateway.log
```

### Nova Times Out

**Cause**: Audio not reaching Nova within timeout period

**Solutions**:
- Verify FreeSWITCH is sending audio (check logs)
- Verify Java gateway receives audio
- Check AWS credentials are valid
- Verify Bedrock is available in us-east-1

## Performance Tuning

### FreeSWITCH

```xml
<!-- In sofia profile -->
<param name="rtp-timeout-sec" value="300"/>
<param name="rtp-hold-timeout-sec" value="1800"/>
<param name="disable-transcoding" value="true"/>
```

### Java Gateway

```bash
# Increase JVM heap for high call volumes
java -Xmx2G -Xms2G -jar s2s-voip-gateway.jar

# Use G1GC for better latency
java -XX:+UseG1GC -jar s2s-voip-gateway.jar
```

### Network

- Use **VPC peering** or **PrivateLink** between FreeSWITCH and Java gateway
- Keep latency < 50ms between tiers
- Use **dedicated TCP connections** (avoid connection pooling overhead)

## Security Considerations

1. **Network Isolation**: Put Java gateway in private subnet
2. **Security Groups**: Only allow TCP 8085 from FreeSWITCH instances
3. **Encryption**: Consider TLS for TCP connection (production)
4. **IAM Roles**: Use EC2 instance roles instead of access keys
5. **Logging**: Enable CloudWatch logs for audit trail

## Current Status

### Deployed Infrastructure
- **FreeSWITCH Instance**: i-06fcbe4efc776029b (44.237.82.96) - Pre-built AMI
- **Java Gateway Instance**: i-0fa82e4df8fcad08e (34.208.83.171) - Running mjSIP-based gateway
- **Java Gateway Listening**: TCP port 8085 for FreeSWITCH connections

### Next Steps

1. ⏳ Build and install mod_nova_sonic.so on FreeSWITCH instance
2. ⏳ Configure mod_nova_sonic to point to Java Gateway (34.208.83.171:8085)
3. ⏳ Load mod_nova_sonic in FreeSWITCH
4. ⏳ Configure FreeSWITCH dialplan to use nova_sonic application
5. ⏳ Test end-to-end call flow
6. ⏳ Add TLS encryption to TCP connection
7. ⏳ Add authentication/authorization
8. ⏳ Implement connection pooling for scale
9. ⏳ Add metrics and monitoring (CloudWatch)
10. ⏳ Implement circuit breakers for resilience

## Support

For issues or questions:
- Check logs first (both FreeSWITCH and Java)
- Review this integration guide
- Check the main project README
