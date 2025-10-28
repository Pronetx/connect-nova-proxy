#!/bin/bash
# Deploy mod_nova_sonic to FreeSWITCH Docker container

set -e

GATEWAY_IP="${1:-10.0.0.68}"
GATEWAY_PORT="${2:-8085}"

echo "Deploying mod_nova_sonic to FreeSWITCH Docker container..."
echo "Gateway IP: $GATEWAY_IP"
echo "Gateway Port: $GATEWAY_PORT"

# Build module using Docker build container
echo "Building module using temporary build container..."
cd "$(dirname "$0")"

# Create a temporary build container from the same image
docker run --name freeswitch-builder -d safarov/freeswitch:latest tail -f /dev/null

# Copy source first
docker cp src/mod_nova_sonic.c freeswitch-builder:/tmp/

# Install build tools and build module
docker exec freeswitch-builder sh -c "
  apk add --no-cache gcc g++ make musl-dev linux-headers && \
  cd /tmp && \
  gcc -fPIC -O2 -I/usr/include/freeswitch -I/usr/local/include/freeswitch -shared -o mod_nova_sonic.so mod_nova_sonic.c 2>&1 || \
  gcc -fPIC -O2 -shared -o mod_nova_sonic.so mod_nova_sonic.c 2>&1
"

# Extract compiled module
docker cp freeswitch-builder:/tmp/mod_nova_sonic.so ./

# Clean up builder container
docker rm -f freeswitch-builder

# Copy compiled module to running FreeSWITCH
echo "Copying compiled module to FreeSWITCH container..."
docker cp mod_nova_sonic.so freeswitch:/usr/lib/freeswitch/mod/

# Create configuration file
echo "Creating module configuration..."
cat > /tmp/nova_sonic.conf.xml <<EOF
<configuration name="nova_sonic.conf" description="Nova Sonic Audio Proxy">
  <settings>
    <param name="gateway-host" value="$GATEWAY_IP"/>
    <param name="gateway-port" value="$GATEWAY_PORT"/>
  </settings>
</configuration>
EOF

# Copy config to container
docker cp /tmp/nova_sonic.conf.xml freeswitch:/etc/freeswitch/autoload_configs/

# Load the module
echo "Loading module in FreeSWITCH..."
docker exec freeswitch fs_cli -x "load mod_nova_sonic"

echo "Module deployed successfully!"
echo ""
echo "To test, run: docker exec freeswitch fs_cli -x 'module_exists mod_nova_sonic'"
