# Testing Scripts

This directory contains testing utilities for local development and integration testing.

## Scripts

### local-call-sim.sh (Dev A)

Simulates SIP calls to the gateway for testing without needing a real SIP provider or Connect.

**Requirements:**
- SIPp installed (`brew install sipp` on macOS)

**Usage:**
```bash
# Standard SIP call (no Connect headers)
./local-call-sim.sh

# Simulate Connect call (with X-Connect-* headers)
./local-call-sim.sh --with-connect-headers
```

**What it does:**
- Generates a SIP INVITE with proper SDP
- Optionally includes Connect headers (ContactId, InstanceId, CorrelationId)
- Keeps call active for 30 seconds
- Sends BYE to terminate
- Creates SIPp trace logs for debugging

**Testing scenarios:**
1. **Non-Connect call:** Gateway should use DateTime-only tools
2. **Connect call:** Gateway should parse headers and enable Connect tools

---

### push-attrs-test.js (Dev B)

Tests `UpdateContactAttributes` API calls to Amazon Connect.

**Requirements:**
- Node.js 18+
- AWS credentials configured
- Active Connect contact (or test contact ID)

**Usage:**
```bash
# Install dependencies
npm install @aws-sdk/client-connect

# Basic test
node push-attrs-test.js \
  --contact-id abc-123-def-456 \
  --instance-id xyz-789

# Agent transfer test
node push-attrs-test.js \
  --contact-id abc-123 \
  --instance-id xyz-789 \
  --test agent-transfer

# Test with AWS profile
node push-attrs-test.js \
  --contact-id abc-123 \
  --instance-id xyz-789 \
  --profile prod \
  --region us-west-2

# See all options
node push-attrs-test.js --help
```

**Test scenarios:**
- `basic` - Simple attribute update (nova_next=end)
- `agent-transfer` - Full agent transfer with queue
- `mid-call` - Multiple incremental updates simulating live call
- `size-limit` - Test 32KB size limit handling

**What it does:**
- Calls Connect `UpdateContactAttributes` API
- Measures response time
- Validates attribute sizes
- Reports success/failure with detailed errors

---

## Development Workflow

### Dev A: Testing SIP/RTP Changes

```bash
# 1. Start gateway locally
cd voice-gateway
./run.sh

# 2. In another terminal, simulate a call
cd scripts
./local-call-sim.sh --with-connect-headers

# 3. Check gateway logs for:
#    - SIP INVITE received
#    - Headers parsed correctly
#    - Connect context detected
```

### Dev B: Testing Connect Integration

```bash
# 1. Get an active contact ID from Connect
#    (Make a test call and check Connect console)

# 2. Test attribute updates
node push-attrs-test.js \
  --contact-id <from-console> \
  --instance-id <your-instance> \
  --test mid-call

# 3. Check Connect console:
#    - View contact record
#    - Verify attributes appeared
#    - Check CloudWatch logs
```

### Combined Integration Test

```bash
# Terminal 1: Run gateway
cd voice-gateway
./run.sh

# Terminal 2: Simulate Connect call
cd scripts
./local-call-sim.sh --with-connect-headers

# Terminal 3: Monitor Connect attributes (if gateway connected to real Connect)
watch -n 2 'aws connect describe-contact --contact-id <id> --instance-id <id> | jq .Contact.Attributes'
```

---

## Troubleshooting

### local-call-sim.sh

**Problem:** `sipp: command not found`

**Solution:**
```bash
# macOS
brew install sipp

# Ubuntu/Debian
sudo apt-get install sipp

# From source
git clone https://github.com/SIPp/sipp.git
cd sipp
./build.sh
```

**Problem:** Gateway doesn't respond to INVITE

**Solution:**
- Check gateway is running: `ps aux | grep java`
- Verify SIP port is listening: `lsof -i :5060`
- Check firewall: `sudo pfctl -s all` (macOS) or `sudo iptables -L` (Linux)
- Review gateway logs for errors

**Problem:** Headers not appearing in gateway logs

**Solution:**
- Verify `--with-connect-headers` flag used
- Check gateway DEBUG logging enabled
- Inspect SIPp trace logs: `/tmp/sip-scenario-*.xml`

---

### push-attrs-test.js

**Problem:** `ResourceNotFoundException`

**Solution:**
- Verify contact ID is correct (copy from Connect console)
- Check contact is still active (not terminated)
- Verify instance ID format (should be UUID, not ARN)

**Problem:** `AccessDeniedException`

**Solution:**
- Check AWS credentials: `aws sts get-caller-identity`
- Verify IAM permissions include `connect:UpdateContactAttributes`
- Ensure correct AWS region

**Problem:** `InvalidParameterException`

**Solution:**
- Check attribute values are strings (not numbers/booleans)
- Verify total size < 32KB
- Ensure attribute keys are valid (no special chars)

**Problem:** Timeout

**Solution:**
- Check network connectivity to Connect API
- Verify region is correct
- Try with `--region` flag explicitly set

---

## Adding New Scripts

When adding new testing scripts:

1. **Name clearly:** Use verb-noun format (e.g., `test-audio-transcoding.sh`)
2. **Add shebang:** `#!/bin/bash` or `#!/usr/bin/env node`
3. **Make executable:** `chmod +x script.sh`
4. **Add help:** Support `--help` flag
5. **Document here:** Add section to this README
6. **Assign owner:** Mark as Dev A or Dev B script
7. **Error handling:** Exit with non-zero on errors
8. **Logging:** Print clear success/failure messages

Example template:

```bash
#!/bin/bash
# Script: test-something.sh
# Owner: Dev A/B
# Purpose: Brief description

set -e

if [[ "$1" == "--help" ]]; then
  echo "Usage: $0 [options]"
  echo "Description of what this does"
  exit 0
fi

# ... script logic ...

echo "✅ Success!"
```

---

## CI/CD Integration

These scripts can be integrated into GitHub Actions:

```yaml
# .github/workflows/integration-test.yml
jobs:
  test-connect-integration:
    runs-on: ubuntu-latest
    steps:
      - name: Test attribute updates
        run: |
          node scripts/push-attrs-test.js \
            --contact-id ${{ secrets.TEST_CONTACT_ID }} \
            --instance-id ${{ secrets.CONNECT_INSTANCE_ID }} \
            --test basic
```

---

## References

- [SIPp Documentation](https://sipp.sourceforge.net/)
- [Connect API Reference](https://docs.aws.amazon.com/connect/latest/APIReference/)
- [AWS SDK for JavaScript v3](https://docs.aws.amazon.com/AWSJavaScriptSDK/v3/latest/)
