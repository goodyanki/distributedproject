#!/bin/bash
java -jar target/facility-booking-server-1.0-SNAPSHOT.jar \
    --port 9876 \
    --semantic AT_MOST_ONCE \
    --lossRate 0.0 \
    --replyLossRate 0.0 \
    --delayMs 0
