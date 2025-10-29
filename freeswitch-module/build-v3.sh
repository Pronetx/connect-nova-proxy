#!/bin/bash
set -e

echo "Building mod_nova_sonic (v3 - direct frame loop)..."

# Compile
gcc -fPIC -O3 -I/usr/local/freeswitch/include/freeswitch \
    -c src/mod_nova_sonic_v3.c -o mod_nova_sonic.o

# Link
gcc -shared -o mod_nova_sonic.so mod_nova_sonic.o

# Create tarball
tar -czf mod_nova_sonic_v3.tar.gz mod_nova_sonic.so

echo "Build complete: mod_nova_sonic_v3.tar.gz"
echo ""
echo "To deploy:"
echo "1. scp mod_nova_sonic_v3.tar.gz admin@<freeswitch-ip>:/tmp/"
echo "2. ssh admin@<freeswitch-ip>"
echo "3. cd /tmp && tar -xzf mod_nova_sonic_v3.tar.gz"
echo "4. sudo mv mod_nova_sonic.so /usr/local/freeswitch/mod/"
echo "5. sudo fs_cli -x 'reload mod_nova_sonic'"
echo "6. Update dialplan to use 'nova_ai_session' app"
