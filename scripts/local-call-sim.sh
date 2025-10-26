#!/bin/bash
#
# Local Call Simulator
#
# Simulates a SIP INVITE to the voice gateway for testing purposes.
# This allows Dev A to test SIP handling without needing Connect.
#
# Owner: Dev A
#
# Usage:
#   ./local-call-sim.sh [--with-connect-headers]
#

set -e

# Configuration
SIP_SERVER="${SIP_SERVER:-localhost}"
SIP_PORT="${SIP_PORT:-5060}"
FROM_USER="${FROM_USER:-testuserc}"
TO_USER="${TO_USER:-gateway}"
CALL_ID="$(uuidgen | tr '[:upper:]' '[:lower:]')"
WITH_CONNECT_HEADERS=false

# Parse arguments
if [[ "$1" == "--with-connect-headers" ]]; then
  WITH_CONNECT_HEADERS=true
fi

echo "=========================================="
echo "SIP Call Simulator"
echo "=========================================="
echo "Target: $SIP_SERVER:$SIP_PORT"
echo "From: $FROM_USER"
echo "To: $TO_USER"
echo "Call-ID: $CALL_ID"
echo "Connect Headers: $WITH_CONNECT_HEADERS"
echo "=========================================="
echo

# Check if SIPp is installed
if ! command -v sipp &> /dev/null; then
  echo "ERROR: SIPp not found. Please install SIPp first:"
  echo "  macOS: brew install sipp"
  echo "  Linux: sudo apt-get install sipp"
  exit 1
fi

# Create temporary SIPp scenario file
SCENARIO_FILE="/tmp/sip-scenario-$CALL_ID.xml"

if [ "$WITH_CONNECT_HEADERS" = true ]; then
  # Generate scenario with Connect headers
  TEST_CONTACT_ID="test-contact-$(date +%s)"
  TEST_INSTANCE_ID="test-instance-123"
  TEST_CORRELATION_ID="$TEST_CONTACT_ID-$(date +%s%3N)"

  echo "Using Connect context:"
  echo "  ContactId: $TEST_CONTACT_ID"
  echo "  InstanceId: $TEST_INSTANCE_ID"
  echo "  CorrelationId: $TEST_CORRELATION_ID"
  echo

  cat > "$SCENARIO_FILE" << EOF
<?xml version="1.0" encoding="ISO-8859-1" ?>
<scenario name="Connect Call Test">
  <send retrans="500">
    <![CDATA[
      INVITE sip:[$TO_USER]@[remote_ip]:[remote_port] SIP/2.0
      Via: SIP/2.0/UDP [local_ip]:[local_port];branch=[branch]
      From: <sip:$FROM_USER@[local_ip]:[local_port]>;tag=[pid]SIPpTag00[call_number]
      To: <sip:$TO_USER@[remote_ip]:[remote_port]>
      Call-ID: $CALL_ID
      CSeq: 1 INVITE
      Contact: <sip:$FROM_USER@[local_ip]:[local_port]>
      X-Connect-ContactId: $TEST_CONTACT_ID
      X-Connect-InstanceId: $TEST_INSTANCE_ID
      X-Correlation-Id: $TEST_CORRELATION_ID
      Max-Forwards: 70
      Content-Type: application/sdp
      Content-Length: [len]

      v=0
      o=user1 53655765 2353687637 IN IP4 [local_ip]
      s=-
      c=IN IP4 [local_ip]
      t=0 0
      m=audio 6000 RTP/AVP 0
      a=rtpmap:0 PCMU/8000
    ]]>
  </send>

  <recv response="100" optional="true"></recv>
  <recv response="180" optional="true"></recv>
  <recv response="200"></recv>

  <send>
    <![CDATA[
      ACK sip:[$TO_USER]@[remote_ip]:[remote_port] SIP/2.0
      Via: SIP/2.0/UDP [local_ip]:[local_port];branch=[branch]
      From: <sip:$FROM_USER@[local_ip]:[local_port]>;tag=[pid]SIPpTag00[call_number]
      To: <sip:$TO_USER@[remote_ip]:[remote_port]>[peer_tag_param]
      Call-ID: $CALL_ID
      CSeq: 1 ACK
      Contact: <sip:$FROM_USER@[local_ip]:[local_port]>
      Max-Forwards: 70
      Content-Length: 0
    ]]>
  </send>

  <!-- Keep call active for 30 seconds -->
  <pause milliseconds="30000"/>

  <send retrans="500">
    <![CDATA[
      BYE sip:[$TO_USER]@[remote_ip]:[remote_port] SIP/2.0
      Via: SIP/2.0/UDP [local_ip]:[local_port];branch=[branch]
      From: <sip:$FROM_USER@[local_ip]:[local_port]>;tag=[pid]SIPpTag00[call_number]
      To: <sip:$TO_USER@[remote_ip]:[remote_port]>[peer_tag_param]
      Call-ID: $CALL_ID
      CSeq: 2 BYE
      Contact: <sip:$FROM_USER@[local_ip]:[local_port]>
      Max-Forwards: 70
      Content-Length: 0
    ]]>
  </send>

  <recv response="200"></recv>
</scenario>
EOF
else
  # Generate scenario without Connect headers (standard SIP)
  cat > "$SCENARIO_FILE" << EOF
<?xml version="1.0" encoding="ISO-8859-1" ?>
<scenario name="Standard SIP Call Test">
  <send retrans="500">
    <![CDATA[
      INVITE sip:[$TO_USER]@[remote_ip]:[remote_port] SIP/2.0
      Via: SIP/2.0/UDP [local_ip]:[local_port];branch=[branch]
      From: <sip:$FROM_USER@[local_ip]:[local_port]>;tag=[pid]SIPpTag00[call_number]
      To: <sip:$TO_USER@[remote_ip]:[remote_port]>
      Call-ID: $CALL_ID
      CSeq: 1 INVITE
      Contact: <sip:$FROM_USER@[local_ip]:[local_port]>
      Max-Forwards: 70
      Content-Type: application/sdp
      Content-Length: [len]

      v=0
      o=user1 53655765 2353687637 IN IP4 [local_ip]
      s=-
      c=IN IP4 [local_ip]
      t=0 0
      m=audio 6000 RTP/AVP 0
      a=rtpmap:0 PCMU/8000
    ]]>
  </send>

  <recv response="100" optional="true"></recv>
  <recv response="180" optional="true"></recv>
  <recv response="200"></recv>

  <send>
    <![CDATA[
      ACK sip:[$TO_USER]@[remote_ip]:[remote_port] SIP/2.0
      Via: SIP/2.0/UDP [local_ip]:[local_port];branch=[branch]
      From: <sip:$FROM_USER@[local_ip]:[local_port]>;tag=[pid]SIPpTag00[call_number]
      To: <sip:$TO_USER@[remote_ip]:[remote_port]>[peer_tag_param]
      Call-ID: $CALL_ID
      CSeq: 1 ACK
      Contact: <sip:$FROM_USER@[local_ip]:[local_port]>
      Max-Forwards: 70
      Content-Length: 0
    ]]>
  </send>

  <pause milliseconds="30000"/>

  <send retrans="500">
    <![CDATA[
      BYE sip:[$TO_USER]@[remote_ip]:[remote_port] SIP/2.0
      Via: SIP/2.0/UDP [local_ip]:[local_port];branch=[branch]
      From: <sip:$FROM_USER@[local_ip]:[local_port]>;tag=[pid]SIPpTag00[call_number]
      To: <sip:$TO_USER@[remote_ip]:[remote_port]>[peer_tag_param]
      Call-ID: $CALL_ID
      CSeq: 2 BYE
      Contact: <sip:$FROM_USER@[local_ip]:[local_port]>
      Max-Forwards: 70
      Content-Length: 0
    ]]>
  </send>

  <recv response="200"></recv>
</scenario>
EOF
fi

# Run SIPp
echo "Starting SIPp call..."
echo "Press Ctrl+C to abort early"
echo

sipp -sf "$SCENARIO_FILE" \
     -m 1 \
     -i 127.0.0.1 \
     -p 5061 \
     -trace_msg \
     -trace_err \
     "$SIP_SERVER:$SIP_PORT"

# Cleanup
rm -f "$SCENARIO_FILE"

echo
echo "=========================================="
echo "Call simulation complete!"
echo "=========================================="
echo
echo "Next steps:"
echo "1. Check gateway logs for incoming INVITE"
echo "2. Verify SIP headers were parsed correctly"
echo "3. Check if Connect context was detected"
if [ "$WITH_CONNECT_HEADERS" = true ]; then
  echo "4. Verify Connect tools were enabled"
else
  echo "4. Verify fallback to DateTime-only tools"
fi
