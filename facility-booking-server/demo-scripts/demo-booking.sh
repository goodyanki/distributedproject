#!/usr/bin/env bash
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$PROJECT_ROOT/target/facility-booking-server-1.0-SNAPSHOT.jar"
DEMO_OUT="$PROJECT_ROOT/demo-output"
mkdir -p "$DEMO_OUT"

echo "1) Build project..."
cd "$PROJECT_ROOT"
mvn -q clean package

# helper to start server in background
start_server() {
  MODE=$1
  LOSS=$2
  REPLYLOSS=$3
  DELAYMS=$4
  PORT=$5
  LOGFILE="$DEMO_OUT/server_${MODE}_loss${LOSS}_port${PORT}.log"
  echo "Starting server: semantic=$MODE loss=$LOSS replyLoss=$REPLYLOSS delayMs=$DELAYMS port=$PORT -> log=$LOGFILE"
  # Run server in background; redirect stdout/stderr to logfile
  nohup java -jar "$JAR" --port "$PORT" --semantic "$MODE" --lossRate "$LOSS" --replyLossRate "$REPLYLOSS" --delayMs "$DELAYMS" > "$LOGFILE" 2>&1 &
  SERVER_PID=$!
  echo $SERVER_PID
}

# helper to run client commands non-interactively (feed commands via here-doc)
run_client_script() {
  HOST=$1
  PORT=$2
  OUTFILE="$3"
  shift 3
  CMDS="$@"
  echo "Running client commands -> $OUTFILE"
  # use bash heredoc to pipe commands to client
  # we use small sleeps between commands to make logs clearer
  {
    sleep 0.5
    for c in "${CMDS[@]}"; do
      echo "$c"
      sleep 0.5
    done
    echo "exit"
  } | java -cp "$JAR" edu.ntu.sc6103.booking.client.UDPClient "$HOST" "$PORT" > "$OUTFILE" 2>&1 || true
}

# scenario A: AT_LEAST_ONCE with loss -> repeated op_b may create multiple bookings
PORT1=9876
PID1=$(start_server "AT_LEAST_ONCE" 0.3 0.0 100 "$PORT1")
sleep 1.5

echo "Scenario A: AT_LEAST_ONCE + 30% incoming loss + 100ms delay"
# We'll issue OP_B twice in quick succession from client with small retries to exercise retransmission.
OUT1="$DEMO_OUT/client_scenarioA_opb.txt"
run_client_script "127.0.0.1" "$PORT1" "$OUT1" \
  "set timeout 500" \
  "set retries 2" \
  "op_b RoomA" \
  "op_b RoomA"

sleep 1
echo "Scenario A client output (tail):"
tail -n 80 "$OUT1"

# scenario B: AT_MOST_ONCE with same loss -> duplicates should be suppressed
PORT2=9877
PID2=$(start_server "AT_MOST_ONCE" 0.3 0.0 100 "$PORT2")
sleep 1.5

echo "Scenario B: AT_MOST_ONCE + 30% incoming loss + 100ms delay"
OUT2="$DEMO_OUT/client_scenarioB_opb.txt"
run_client_script "127.0.0.1" "$PORT2" "$OUT2" \
  "set timeout 500" \
  "set retries 2" \
  "op_b RoomA" \
  "op_b RoomA"

sleep 1
echo "Scenario B client output (tail):"
tail -n 80 "$OUT2"

# Monitor demo: register a monitor and then from another client make bookings to trigger callback
PORT3=9878
PID3=$(start_server "AT_MOST_ONCE" 0.0 0.0 0 "$PORT3")
sleep 1

echo "Monitor demo: register monitor for RoomA for 10s and then book to trigger callback"
OUT_MONITOR="$DEMO_OUT/client_monitor.txt"
# client1 registers monitor (blocks waiting for 10s and prints callbacks)
# run in background
{
  sleep 0.5
  echo "monitor RoomA 10"
  sleep 12
  echo "exit"
} | java -cp "$JAR" edu.ntu.sc6103.booking.client.UDPClient 127.0.0.1 $PORT3 > "$OUT_MONITOR" 2>&1 &
MON_PID=$!

# wait a bit then use another client to book
sleep 1.5
run_client_script "127.0.0.1" "$PORT3" "$DEMO_OUT/client_trigger_book.txt" \
  "book RoomA 0 9 0 0 9 1" \
  "exit"

sleep 2
echo "Monitor client output (tail):"
tail -n 120 "$OUT_MONITOR"

# cleanup
echo "Cleaning up server processes: $PID1 $PID2 $PID3"
kill "$PID1" "$PID2" "$PID3" 2>/dev/null || true
# kill monitor client
kill "$MON_PID" 2>/dev/null || true

echo "Demo finished. See $DEMO_OUT for logs."
