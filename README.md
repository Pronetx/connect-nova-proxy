# Nova S2S VoIP Gateway with Amazon Connect Integration

This project implements a SIP endpoint that acts as a gateway to Amazon Nova Sonic speech-to-speech AI, with deep integration for Amazon Connect contact centers.

## What This Does

**Voice Gateway:** Acts as a SIP user agent that bridges phone calls to Nova Sonic for natural voice conversations with AI.

**Connect Integration:** When called from Amazon Connect via External Voice Transfer, the gateway extracts contact context, updates contact attributes during/after the call, and returns control to Connect with routing instructions.

## Repository Structure

```
connect-nova-proxy/
├── voice-gateway/           # Core SIP/RTP gateway (Dev A)
│   ├── src/                # Java source code
│   ├── docker/             # Container image
│   ├── pom.xml             # Maven build
│   └── README.md
│
├── connect-integration/     # Connect-specific code (Dev B)
│   ├── src/                # SIP parser, attribute manager, tools
│   ├── pom.xml
│   └── README.md
│
├── infra/                   # CDK infrastructure (Dev B)
│   ├── cdk-ecs/            # ECS deployment
│   ├── cdk-ec2-instance/   # EC2 dev deployment
│   └── README.md
│
├── lambdas/                 # Lambda functions (Dev B)
│   └── prepareExternalTransfer/  # Sets SIP headers for Connect
│
├── shared/                  # Shared documentation
│   ├── docs/               # Architecture docs
│   │   ├── call-flow-sequence.md
│   │   └── attribute-contract.md
│   └── types/              # Shared type definitions
│       └── CallDisposition.md
│
├── scripts/                 # Testing utilities
│   ├── local-call-sim.sh   # SIP call simulator (Dev A)
│   ├── push-attrs-test.js  # Connect API tester (Dev B)
│   └── README.md
│
├── .claude/                 # Claude Code documentation
│   └── IMPLEMENTATION_PLAN.md
│
└── .github/
    └── workflows/
        └── build-and-test.yml
```

## Quick Start

### Prerequisites

- **Java 9+** (Amazon Corretto recommended)
- **Maven 3.6+**
- **Node.js 18+** (for CDK and Lambda)
- **AWS Account** with Bedrock Nova Sonic access
- **SIP Account** (VoIP provider or PBX)
- **Amazon Connect Instance** (for Connect integration)

### Build

```bash
# Build voice gateway
cd voice-gateway
mvn clean package

# Build connect integration
cd connect-integration
mvn clean package
```

### Run Locally

```bash
# Configure environment
cd voice-gateway
cp environment.template environment
# Edit environment with your SIP credentials

# Run gateway
./run.sh
```

### Deploy to AWS

```bash
# Option 1: ECS on EC2 (production)
cd infra/cdk-ecs
npm install
cp cdk.context.json.template cdk.context.json
# Edit cdk.context.json
cdk deploy

# Option 2: Single EC2 (development)
cd infra/cdk-ec2-instance
npm install
# Edit bin/cdk.ts with key pair name
cdk deploy
```

## Architecture

### Standard SIP Call Flow
```
Caller → SIP Server → Gateway → Nova Sonic
                         ↓
                    Audio Bridge (RTP ↔ Bedrock)
```

### Amazon Connect Integration Flow
```
Customer → Connect Flow → Lambda (set headers)
                            ↓
                    External Transfer
                            ↓
                    Gateway (parse headers)
                            ↓
                    Nova Conversation
                            ↓
                    Update Contact Attributes
                            ↓
                    BYE (return to Connect)
                            ↓
                    Connect Flow (check attributes)
                            ↓
                    Route (agent/survey/end)
```

## Key Features

### Voice Gateway (`/voice-gateway`)
- ✅ SIP user agent with registration
- ✅ RTP media handling (µ-law 8kHz)
- ✅ Bidirectional audio streaming with Nova Sonic
- ✅ Audio transcoding (µ-law ↔ PCM)
- ✅ Extensible tool system for Nova
- ✅ Docker containerization
- ✅ Host-mode ECS deployment for UDP port binding

### Connect Integration (`/connect-integration`)
- 🚧 SIP header parsing (ContactId, InstanceId)
- 🚧 Contact attribute management (UpdateContactAttributes API)
- 🚧 Connect-aware Nova tools:
  - `updateContactAttributes` - Mid-call attribute updates
  - `endCallAndTransfer` - Graceful termination with routing
- 🚧 Call context management
- 🚧 Graceful fallback for non-Connect calls

**Status:** 🚧 = Planned (see [Implementation Plan](./.claude/IMPLEMENTATION_PLAN.md))

## Contact Attributes

When used with Amazon Connect, the gateway sets these attributes:

**Core Attributes:**
- `nova_next` - Routing decision: `agent`, `survey`, or `end`
- `nova_summary` - Conversation summary (plain text)
- `nova_timestamp` - ISO-8601 timestamp

**Routing Attributes:**
- `nova_target_queue` - Queue name/ARN (if transferring to agent)
- `nova_reason` - Reason code (e.g., `needs_agent`, `issue_resolved`)
- `nova_call_duration` - Duration in seconds

**Domain-Specific Attributes:**
- `nova_customer_id`, `nova_account_number` - Customer identifiers
- `nova_issue_type`, `nova_issue_subtype` - Issue categorization
- `nova_customer_sentiment` - Detected sentiment

See [Attribute Contract](./shared/docs/attribute-contract.md) for full specification.

## Development Workflow

### Dev A: Voice Gateway
**Responsibilities:**
- SIP/RTP stack
- Audio transcoding
- Nova Sonic integration
- Base tool system
- Media streaming

**Testing:**
```bash
# Start gateway
cd voice-gateway
./run.sh

# Simulate call (another terminal)
cd scripts
./local-call-sim.sh --with-connect-headers
```

### Dev B: Connect Integration
**Responsibilities:**
- SIP header parsing
- Connect API integration
- Connect-specific tools
- Lambda functions
- CDK infrastructure

**Testing:**
```bash
# Test Connect API
cd scripts
node push-attrs-test.js \
  --contact-id <test-id> \
  --instance-id <instance-id> \
  --test agent-transfer
```

### Working in Parallel

The repository structure allows both developers to work independently:
- **Dev A** works in `/voice-gateway` (Java, SIP, RTP)
- **Dev B** works in `/connect-integration` and `/lambdas` (Java, TypeScript, Connect)
- **Shared contract:** `/shared/docs/` defines interfaces
- **Integration point:** `NovaSonicVoipGateway.createCallHandler()` (line 102)

## Configuration

### Environment Variables

**SIP Configuration:**
```bash
SIP_SERVER=sip.example.com
SIP_USER=gateway
AUTH_USER=gateway
AUTH_PASSWORD=secret
AUTH_REALM=example.com
DISPLAY_NAME="Nova Gateway"
```

**Nova Configuration:**
```bash
NOVA_VOICE_ID=en_us_matthew
NOVA_PROMPT="You are a helpful assistant..."
```

**Connect Configuration:**
```bash
CONNECT_REGION=us-east-1
CONNECT_INSTANCE_ID=12345678-1234-1234-1234-123456789012
```

**Media Configuration:**
```bash
MEDIA_PORT_BASE=10000
MEDIA_PORT_COUNT=10000
GREETING_FILENAME=hello.wav
```

See `voice-gateway/environment.template` for complete list.

## IAM Permissions

The gateway requires these AWS permissions:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "bedrock:InvokeModel",
        "bedrock:InvokeModelWithResponseStream"
      ],
      "Resource": "*"
    },
    {
      "Effect": "Allow",
      "Action": [
        "connect:UpdateContactAttributes"
      ],
      "Resource": "arn:aws:connect:*:*:instance/*/contact/*"
    }
  ]
}
```

These are automatically configured in the CDK stacks.

## Documentation

- **[CLAUDE.md](./CLAUDE.md)** - Overview for Claude Code
- **[Implementation Plan](./.claude/IMPLEMENTATION_PLAN.md)** - Detailed implementation roadmap
- **[Call Flow Sequence](./shared/docs/call-flow-sequence.md)** - End-to-end call flow with sequence diagrams
- **[Attribute Contract](./shared/docs/attribute-contract.md)** - Contact attribute specification
- **[Voice Gateway README](./voice-gateway/README.md)** - Gateway module details
- **[Connect Integration README](./connect-integration/README.md)** - Connect module details
- **[Infrastructure README](./infra/README.md)** - Deployment options
- **[Lambda README](./lambdas/prepareExternalTransfer/README.md)** - Lambda function details
- **[Scripts README](./scripts/README.md)** - Testing utilities

## Testing

### Unit Tests
```bash
# Voice gateway
cd voice-gateway
mvn test

# Connect integration
cd connect-integration
mvn test
```

### Integration Tests
```bash
# SIP call simulation
cd scripts
./local-call-sim.sh --with-connect-headers

# Connect attribute updates
node push-attrs-test.js \
  --contact-id <id> \
  --instance-id <id> \
  --test mid-call
```

### CI/CD
GitHub Actions automatically runs:
- Maven build for both modules
- Lambda packaging
- CDK synth validation
- Security scanning

See [.github/workflows/build-and-test.yml](./.github/workflows/build-and-test.yml)

## Troubleshooting

### SIP Issues
**Gateway not registering:**
- Check `SIP_SERVER` is reachable
- Verify credentials in environment
- Enable `DEBUG_SIP=true` for packet logs

**No audio on calls:**
- Verify UDP ports 10000-20000 open
- Check `MEDIA_ADDRESS` is correct (public IP if NAT)
- Review security group rules

### Connect Issues
**Headers not appearing:**
- Verify Lambda `prepareExternalTransfer` is invoked
- Check Lambda logs in CloudWatch
- Confirm Connect flow sets SIP headers from Lambda response

**Attribute updates failing:**
- Check IAM permissions for `connect:UpdateContactAttributes`
- Verify contact ID is correct and contact is active
- Review attribute size (< 32KB total)

### Nova Issues
**Bedrock connection fails:**
- Check AWS credentials configured
- Verify IAM permissions for Bedrock
- Ensure region is `us-east-1` (Nova Sonic availability)

## Production Considerations

- **Scaling:** Use ECS auto-scaling based on active connections
- **Monitoring:** CloudWatch metrics, logs, and alarms
- **Security:** VPC endpoints, security groups, secrets management
- **Cost:** ~$35-50/month for single t3.medium instance
- **High Availability:** Multi-AZ deployment with ECS
- **Disaster Recovery:** Automated CDK deployment, configuration in IaC

## Third-Party Dependencies

- **mjSIP** (2.0.5) - SIP stack, GPLv2 license
- **AWS SDK v2** - Bedrock and Connect clients
- **Jackson** - JSON processing
- **Lombok** - Java boilerplate reduction
- **Logback** - Logging

See `pom.xml` files for complete dependency lists.

## License

MIT-0 License. See [LICENSE](./LICENSE) file.

## Contributing

See [CONTRIBUTING.md](./CONTRIBUTING.md) for guidelines.

## Support

For issues and questions:
- Check documentation in `/shared/docs/`
- Review troubleshooting sections in module READMEs
- Open GitHub issue with:
  - Module affected (voice-gateway, connect-integration, etc.)
  - Steps to reproduce
  - Relevant logs (sanitize sensitive data)

## Acknowledgments

Based on the Amazon Nova Sonic S2S VoIP Gateway sample. Enhanced with Amazon Connect integration for enterprise contact center use cases.
