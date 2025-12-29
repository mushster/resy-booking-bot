#!/bin/bash
# Resy Booking Bot runner with sleep prevention

export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export PATH="$JAVA_HOME/bin:$PATH"

echo "Starting Resy Booking Bot (sleep prevention enabled)..."
echo "Press Ctrl+C to stop"
echo ""

# caffeinate -i prevents idle sleep while the process runs
caffeinate -i sbt run
