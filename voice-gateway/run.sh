#!/bin/bash
set -e

mvn compile exec:java -Dexec.mainClass=com.example.s2s.voipgateway.NovaSonicVoipGateway

