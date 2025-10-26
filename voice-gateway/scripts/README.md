# Deployment Scripts

Quick scripts for building and deploying the Nova VoIP Gateway to EC2.

## Prerequisites

- AWS CLI configured with credentials
- Maven installed
- EC2 instance(s) running with tag `NovaSonicVoIPGatewayInstance`
- S3 bucket: `voip-gateway-deployment-1761445331`
- Instance(s) must have SSM agent running

## Initial Setup (One-time)

Before deploying for the first time, you need to configure the environment and set up the systemd service:

### 1. Configure Environment Variables

```bash
./scripts/configure-environment.sh
```

**For inbound trunk mode (no SIP registration):**
- When prompted "Register with SIP server?", answer "N" or press Enter
- Creates `/etc/nova-gateway.env` with just `PINPOINT_APPLICATION_ID`

**For SIP registration mode:**
- When prompted "Register with SIP server?", answer "Y"
- You'll be prompted for:
  - SIP Server (e.g., sip.example.com)
  - SIP User
  - Auth User (defaults to SIP User)
  - Auth Password
  - Auth Realm (defaults to SIP Server)
  - Display Name

### 2. Setup Systemd Service

```bash
./scripts/setup-service.sh
```

This script will:
- Stop any existing Java processes
- Create `/opt/nova-gateway` directory
- Copy `.mjsip-ua` config file if it exists in `/home/ec2-user/`
- Create systemd service at `/etc/systemd/system/nova-gateway.service`
- Enable the service to start on boot
- Start the service

**Note:** Run `configure-environment.sh` first to create `/etc/nova-gateway.env`.

## Scripts

### `build.sh`
Build the JAR file locally.

```bash
./scripts/build.sh
```

### `deploy-to-ec2.sh`
Deploy the built JAR to EC2 via S3 and SSM.

```bash
./scripts/deploy-to-ec2.sh
```

**What it does:**
1. Uploads JAR to S3 once
2. For each instance with the configured tag:
   - Stops the nova-gateway service
   - Downloads JAR from S3
   - Moves JAR to `/opt/nova-gateway/`
   - Starts the nova-gateway service
   - Shows service status

**Note:** Deploys to ALL instances with tag `NovaSonicVoIPGatewayInstance`.

### `build-and-deploy.sh`
Build and deploy in one command.

```bash
./scripts/build-and-deploy.sh
```

### `logs.sh`
View recent logs from the gateway service via systemd journal.

```bash
./scripts/logs.sh [lines]
```

Default: 50 lines

Examples:
```bash
./scripts/logs.sh      # Last 50 lines
./scripts/logs.sh 100  # Last 100 lines
```

**Note:** Shows logs from ALL instances with tag `NovaSonicVoIPGatewayInstance`.

### `status.sh`
Check the systemd service status on all instances.

```bash
./scripts/status.sh
```

Shows whether the service is active (running), failed, or in another state.

**Note:** Checks ALL instances with tag `NovaSonicVoIPGatewayInstance`.

### `configure-environment.sh`
Configure environment variables for the nova-gateway service.

```bash
./scripts/configure-environment.sh
```

Creates `/etc/nova-gateway.env` on all instances. Run this before `setup-service.sh`.

## Environment Variables

**Script Configuration:**
- `AWS_REGION` - AWS region (default: `us-west-2`)
- `S3_BUCKET` - S3 bucket for deployment (default: `voip-gateway-deployment-1761445331`)
- `INSTANCE_TAG` - EC2 instance tag to filter by (default: `NovaSonicVoIPGatewayInstance`)

**Application Configuration (set via configure-environment.sh):**
- `PINPOINT_APPLICATION_ID` - AWS Pinpoint application ID (always set)
- `SIP_SERVER`, `SIP_USER`, `AUTH_USER`, `AUTH_PASSWORD`, `AUTH_REALM`, `DISPLAY_NAME` - SIP credentials (only if registering)

## Quick Start

### First Time Setup

```bash
# 1. Configure environment variables
./scripts/configure-environment.sh

# 2. Setup systemd service
./scripts/setup-service.sh

# 3. Build and deploy
./scripts/build-and-deploy.sh

# 4. Check status
./scripts/status.sh

# 5. View logs
./scripts/logs.sh
```

### Subsequent Deployments

```bash
# Build and deploy
./scripts/build-and-deploy.sh

# Check status
./scripts/status.sh

# View logs
./scripts/logs.sh
```
