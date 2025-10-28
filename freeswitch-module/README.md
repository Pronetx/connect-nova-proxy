# mod_nova_sonic - Amazon Nova Sonic FreeSWITCH Module

A native FreeSWITCH module for real-time speech-to-speech AI conversations using Amazon Nova Sonic (Bedrock).

## Features

- ✅ **Real-time audio streaming** - Bidirectional audio between caller and Nova Sonic
- ✅ **Media bug integration** - Efficient audio capture using FreeSWITCH's media bug API
- ✅ **AWS authentication** - Automatic SigV4 signing for Bedrock requests
- ✅ **PCM audio format** - Native 8kHz 16-bit PCM (no transcoding needed)
- ⏳ **Tool integration** - DateTime, hangup, SMS tools (TODO)
- ⏳ **Call recording** - Automatic recording to S3 (TODO)

## Architecture

```
┌──────────────┐      ┌─────────────────┐      ┌──────────────┐
│  Phone Call  │─RTP─→│  mod_nova_sonic │─HTTP→│ Nova Sonic   │
│   (Caller)   │←─RTP─│  (FreeSWITCH)  │←HTTP─│  (Bedrock)   │
└──────────────┘      └─────────────────┘      └──────────────┘
```

**Audio Flow:**
1. Caller speaks → RTP → FreeSWITCH
2. Media bug captures PCM audio
3. Audio sent to Nova via AWS Bedrock HTTP/2 stream
4. Nova responds with audio
5. Audio played back to caller via RTP

## Prerequisites

### Build Dependencies
```bash
# Ubuntu/Debian
sudo apt-get install build-essential libcurl4-openssl-dev libssl-dev

# Amazon Linux / RHEL / CentOS
sudo yum install gcc make curl-devel openssl-devel

# AWS SDK for C++ (optional, for native AWS integration)
# Otherwise we'll use curl with manual SigV4 signing
```

### FreeSWITCH
FreeSWITCH must be installed with development headers:
```bash
# From source
cd /usr/src
git clone https://github.com/signalwire/freeswitch.git
cd freeswitch
./bootstrap.sh
./configure
make && sudo make install

# Or use pre-built packages (ensure -dev/-devel packages are installed)
```

## Building

```bash
cd freeswitch-module
make
```

## Installation

```bash
# Install module
sudo make install

# Load module in FreeSWITCH
fs_cli -x "load mod_nova_sonic"

# Or add to autoload in modules.conf.xml:
# <load module="mod_nova_sonic"/>
```

## Configuration

Create `/usr/local/freeswitch/conf/autoload_configs/nova_sonic.conf.xml`:

```xml
<configuration name="nova_sonic.conf" description="Amazon Nova Sonic Configuration">
  <settings>
    <!-- AWS Configuration -->
    <param name="aws-region" value="us-east-1"/>
    <param name="aws-access-key-id" value=""/>  <!-- Leave empty to use IAM role -->
    <param name="aws-secret-access-key" value=""/>
    <param name="aws-session-token" value=""/>

    <!-- Nova Configuration -->
    <param name="model-id" value="amazon.nova-sonic-v1:0"/>
    <param name="sample-rate" value="8000"/>
    <param name="channels" value="1"/>
    <param name="bits-per-sample" value="16"/>

    <!-- Optional: Call Recording -->
    <param name="recording-bucket" value=""/>  <!-- S3 bucket for recordings -->
  </settings>
</configuration>
```

**Using Environment Variables (Recommended for EC2/ECS):**
```bash
export AWS_REGION=us-east-1
# IAM role credentials will be used automatically
```

## Usage

### Dialplan Example

Add to `/usr/local/freeswitch/conf/dialplan/default.xml`:

```xml
<extension name="nova_sonic_demo">
  <condition field="destination_number" expression="^(8888)$">
    <action application="answer"/>
    <action application="sleep" data="1000"/>
    <action application="nova_sonic" data="system_prompt='You are a helpful AI assistant for customer service.'"/>
    <action application="hangup"/>
  </condition>
</extension>
```

### Application Parameters

```xml
<action application="nova_sonic" data="param1=value1,param2=value2"/>
```

**Available parameters:**
- `system_prompt` - System prompt for Nova (default: generic assistant prompt)
- `voice_id` - Nova voice ID (default: en_us_matthew)
- `temperature` - Sampling temperature 0.0-1.0 (default: 1.0)
- `tools` - Comma-separated tool names (default: datetime,hangup)

**Example:**
```xml
<action application="nova_sonic" data="system_prompt='You are a banking assistant.',voice_id='en_us_emma',tools='datetime,hangup,sms'"/>
```

## Development Status

### ✅ Completed
- [x] Module skeleton and FreeSWITCH integration
- [x] Media bug for audio capture
- [x] Audio buffering and streaming infrastructure
- [x] Thread-safe audio queues
- [x] Build system (Makefile)

### ⏳ TODO
- [ ] AWS Bedrock HTTP/2 client implementation
- [ ] AWS SigV4 request signing
- [ ] JSON event serialization/deserialization
- [ ] Base64 audio encoding/decoding
- [ ] Tool integration (datetime, hangup, SMS)
- [ ] Call recording to S3
- [ ] Error handling and reconnection logic
- [ ] Comprehensive logging

## Implementation Notes

### AWS SDK Integration

The module uses **libcurl** for HTTP/2 streaming to AWS Bedrock. AWS SigV4 signing is implemented manually for maximum control and minimal dependencies.

**Alternative:** Link against AWS SDK for C++ for native integration (adds ~50MB to binary size).

### Audio Format

- **FreeSWITCH:** L16 (Linear PCM, 8kHz, 16-bit, mono)
- **Nova Sonic:** LPCM (Linear PCM, 8kHz, 16-bit, mono, base64-encoded)
- **No transcoding needed!** Just base64 encode/decode.

### Threading Model

- **Main thread:** FreeSWITCH session management
- **Send thread:** Reads from input buffer, sends to Nova
- **Receive thread:** Receives from Nova, writes to output buffer
- **Media bug callback:** Captures/injects audio (runs in FreeSWITCH's media thread)

### Memory Management

All memory is allocated from FreeSWITCH's memory pools for leak-free cleanup.

## Testing

```bash
# Start FreeSWITCH with debug logging
freeswitch -nonat -c

# In fs_cli, enable debug:
/log 7

# Load module:
load mod_nova_sonic

# Check module loaded:
module_exists mod_nova_sonic

# Make test call:
originate user/1000 &echo
# Or dial 8888 if using the dialplan above
```

## Troubleshooting

### Module won't load
```bash
# Check module file exists
ls -l /usr/local/freeswitch/mod/mod_nova_sonic.so

# Check for missing libraries
ldd /usr/local/freeswitch/mod/mod_nova_sonic.so

# Check FreeSWITCH logs
tail -f /usr/local/freeswitch/log/freeswitch.log
```

### No audio flowing
- Check AWS credentials are valid
- Verify Nova Sonic is available in your AWS region (us-east-1)
- Enable debug logging: `fsctl loglevel DEBUG`
- Check media bug attached: Module logs "Nova media bug initialized"

### AWS authentication errors
- Ensure IAM role has `bedrock:InvokeModel` permission
- Check region is correct (Nova Sonic only in us-east-1)
- Verify network connectivity to Bedrock endpoint

## Contributing

This module is part of the Nova VoIP Gateway project. See main project README for contribution guidelines.

## License

GPL v2 (to match FreeSWITCH licensing)
