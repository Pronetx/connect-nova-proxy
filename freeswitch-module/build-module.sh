#!/bin/bash
# Build mod_nova_sonic.so using Docker

set -e

echo "Building mod_nova_sonic.so using Docker..."
cd "$(dirname "$0")"

# Build the Docker image
docker build -f Dockerfile.build -t freeswitch-module-builder .

# Run container and extract the compiled module
docker run --rm -v "$(pwd)":/output freeswitch-module-builder sh -c "
    gcc -fPIC -O2 -I/usr/include/freeswitch -shared -o /tmp/mod_nova_sonic.so /tmp/mod_nova_sonic.c && \
    cp /tmp/mod_nova_sonic.so /output/
"

echo "✅ Module built successfully: mod_nova_sonic.so"
ls -lh mod_nova_sonic.so
