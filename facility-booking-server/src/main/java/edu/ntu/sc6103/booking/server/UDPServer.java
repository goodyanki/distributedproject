package edu.ntu.sc6103.booking.server;

import edu.ntu.sc6103.booking.core.BookingManager;
import edu.ntu.sc6103.booking.protocol.MessageMarshaller;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * UDPServer - main UDP server for the distributed facility booking system.
 *
 * Command line options:
 *   --port <port>                (default 9876)
 *   --semantic <AT_MOST_ONCE|AT_LEAST_ONCE>  (default AT_MOST_ONCE)
 *   --lossRate <0.0..1.0>        simulate incoming packet loss (default 0.0)
 *   --replyLossRate <0.0..1.0>   simulate reply (or callback) packet loss (default 0.0)
 *   --delayMs <ms>               simulate reply delay in milliseconds (default 0)
 *   --cacheTtlSeconds <sec>      TTL for duplicate request cache entries (default 300)
 *
 * Behaviour:
 *  - When AT_MOST_ONCE: server will check DuplicateRequestCache before executing a request.
 *      If duplicate found, server will re-send the cached reply bytes.
 *  - When AT_LEAST_ONCE: server always executes the request (may lead to duplicate effects if client retransmits).
 *
 *  - The server uses RequestProcessor to handle unmarshalled requests and get callbacks to send to monitors.
 *
 * Note: this class uses a simple Random-based simulator to optionally drop incoming packets or replies.
 */
public final class UDPServer {
    private static final Logger logger = Logger.getLogger(UDPServer.class.getName());

    private final int port;
    private final InvocationSemantic semantic;
    private final double lossRate;
    private final double replyLossRate;
    private final int delayMs;
    private final long cacheTtlMillis;

    private final BookingManager bookingManager;
    private final RequestProcessor processor;
    private final DuplicateRequestCache dupCache;
    private final Simulator simulator;

    private DatagramSocket socket;
    private volatile boolean running = true;

    public UDPServer(int port,
                     InvocationSemantic semantic,
                     double lossRate,
                     double replyLossRate,
                     int delayMs,
                     long cacheTtlSeconds) {
        this.port = port;
        this.semantic = Objects.requireNonNull(semantic);
        this.lossRate = clamp01(lossRate);
        this.replyLossRate = clamp01(replyLossRate);
        this.delayMs = Math.max(0, delayMs);
        this.cacheTtlMillis = Math.max(0, cacheTtlSeconds) * 1000L;

        this.bookingManager = new BookingManager();
        this.processor = new RequestProcessor(bookingManager);
        this.dupCache = new DuplicateRequestCache(cacheTtlMillis);
        this.simulator = new Simulator(this.lossRate, this.replyLossRate, this.delayMs);
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v) || v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }

    public void start() throws SocketException {
        socket = new DatagramSocket(port);
        socket.setSoTimeout(0); // block indefinitely
        logger.info("Server started on port " + port + " with semantic=" + semantic);
        logger.info(String.format("Simulator: lossRate=%.3f replyLossRate=%.3f delayMs=%dms cacheTTL=%dsec",
                lossRate, replyLossRate, delayMs, cacheTtlMillis / 1000));

        byte[] buf = new byte[65535];
        while (running) {
            try {
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                socket.receive(pkt);

                // copy bytes because buf is reused
                byte[] raw = Arrays.copyOf(pkt.getData(), pkt.getLength());
                InetAddress clientAddr = pkt.getAddress();
                int clientPort = pkt.getPort();

                // simulate incoming packet loss
                if (simulator.dropIncoming()) {
                    logger.info("Simulated drop of incoming packet from " + clientAddr + ":" + clientPort);
                    continue;
                }

                // process request
                processRawRequest(raw, clientAddr, clientPort);
            } catch (SocketException se) {
                if (!running) break;
                logger.severe("SocketException: " + se);
            } catch (IOException ioe) {
                logger.severe("IOException receiving packet: " + ioe);
            } catch (Exception ex) {
                logger.severe("Unexpected exception in main loop: " + ex);
            }

            // cleanup expired entries in cache periodically
            dupCache.cleanupExpired();
        }
        if (socket != null && !socket.isClosed()) socket.close();
    }

    public void stop() {
        running = false;
        if (socket != null) socket.close();
    }

    private void processRawRequest(byte[] raw, InetAddress clientAddr, int clientPort) {
        MessageMarshaller.Request req;
        try {
            req = MessageMarshaller.unmarshalRequest(raw, raw.length);
        } catch (Exception e) {
            logger.warning("Failed to unmarshal request from " + clientAddr + ":" + clientPort + " -> " + e.getMessage());
            // reply with ERR_INVALID
            MessageMarshaller.Response rsp = new MessageMarshaller.Response(0, MessageMarshaller.ResponseCode.ERR_INVALID, ("bad request: " + e.getMessage()).getBytes());
            sendResponseImmediate(rsp, clientAddr, clientPort);
            return;
        }

        String clientKey = clientAddr.getHostAddress() + ":" + clientPort;
        String dupKey = DuplicateRequestCache.makeKey(clientKey, req.requestId);

        // at-most-once handling: check duplicate cache BEFORE executing
        if (semantic == InvocationSemantic.AT_MOST_ONCE) {
            byte[] cached = dupCache.get(dupKey);
            if (cached != null) {
                logger.info("Detected duplicate request (at-most-once) from " + clientKey + " reqId=" + req.requestId + " -> resend cached reply");
                sendRawWithSimulator(cached, clientAddr, clientPort);
                return;
            }
        }

        // Not duplicate (or using at-least-once): execute
        logger.info("Processing request id=" + req.requestId + " op=" + req.op + " from " + clientKey + " semantic=" + req.semantic);
        RequestProcessor.ProcessResult pr = processor.processRequest(req, clientAddr.getHostAddress(), clientPort);

        // marshal the response
        byte[] rspBytes = MessageMarshaller.marshalResponse(pr.response);

        // send main reply (with possible simulator)
        sendRawWithSimulator(rspBytes, clientAddr, clientPort);

        // if at-most-once, store reply bytes in cache for future duplicates
        if (semantic == InvocationSemantic.AT_MOST_ONCE) {
            dupCache.put(dupKey, rspBytes);
        }

        // send callbacks (monitor notifications). Each callback payload is assumed to be ready bytes
        if (pr.callbacks != null && !pr.callbacks.isEmpty()) {
            for (RequestProcessor.Callback cb : pr.callbacks) {
                // Each callback payload might be large; we send it as a single UDP datagram
                InetSocketAddress target = cb.target;
                byte[] payload = cb.payload;
                if (payload == null) continue;
                // Optionally wrap callback in a simple header so client can know it's a monitor notification.
                // For now, send raw payload (spec documented in RequestProcessor).
                sendRawWithSimulator(payload, target.getAddress(), target.getPort());
                logger.info("Sent monitor callback to " + target + " len=" + payload.length);
            }
        }
    }

    private void sendResponseImmediate(MessageMarshaller.Response rsp, InetAddress addr, int port) {
        byte[] b = MessageMarshaller.marshalResponse(rsp);
        sendRawWithSimulator(b, addr, port);
    }

    /**
     * Send raw bytes applying simulator for reply loss/delay.
     */
    private void sendRawWithSimulator(byte[] bytes, InetAddress addr, int port) {
        // simulate reply loss
        if (simulator.dropReply()) {
            logger.info("Simulated drop of reply to " + addr + ":" + port);
            return;
        }
        if (delayMs <= 0) {
            sendDatagram(bytes, addr, port);
        } else {
            // simple synchronous delay (we keep single-thread model per project instruction)
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ignored) {}
            sendDatagram(bytes, addr, port);
        }
    }

    private void sendDatagram(byte[] bytes, InetAddress addr, int port) {
        DatagramPacket pkt = new DatagramPacket(bytes, bytes.length, addr, port);
        try {
            socket.send(pkt);
            logger.info("Sent " + bytes.length + " bytes to " + addr + ":" + port);
        } catch (IOException e) {
            logger.warning("Failed to send packet to " + addr + ":" + port + " -> " + e.getMessage());
        }
    }

    /* ---------------------------
       Helper classes & enums
       --------------------------- */

    public enum InvocationSemantic {
        AT_MOST_ONCE,
        AT_LEAST_ONCE
    }

    /**
     * DuplicateRequestCache: simple TTL cache mapping (clientKey+reqId) -> lastReplyBytes
     */
    private static final class DuplicateRequestCache {
        // value holds bytes + expiryTime
        private static final class Entry {
            final byte[] reply;
            final long expiryMs;
            Entry(byte[] reply, long expiryMs) { this.reply = reply; this.expiryMs = expiryMs; }
        }

        private final Map<String, Entry> map = new ConcurrentHashMap<>();
        private final long ttlMs;

        DuplicateRequestCache(long ttlMs) {
            this.ttlMs = Math.max(0, ttlMs);
        }

        static String makeKey(String clientKey, int requestId) {
            return clientKey + "#" + requestId;
        }

        byte[] get(String key) {
            Entry e = map.get(key);
            if (e == null) return null;
            if (e.expiryMs <= System.currentTimeMillis()) {
                map.remove(key);
                return null;
            }
            return e.reply;
        }

        void put(String key, byte[] reply) {
            long exp = System.currentTimeMillis() + ttlMs;
            map.put(key, new Entry(Arrays.copyOf(reply, reply.length), exp));
        }

        void cleanupExpired() {
            long now = System.currentTimeMillis();
            for (Iterator<Map.Entry<String, Entry>> it = map.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<String, Entry> e = it.next();
                if (e.getValue().expiryMs <= now) it.remove();
            }
        }
    }

    /**
     * Simple Simulator to optionally drop incoming packets or reply packets and to add reply delays.
     */
    private static final class Simulator {
        private final double incomingDropProb;
        private final double replyDropProb;
        private final int delayMs;
        private final Random rnd = new Random();

        Simulator(double incomingDropProb, double replyDropProb, int delayMs) {
            this.incomingDropProb = clamp(incomingDropProb);
            this.replyDropProb = clamp(replyDropProb);
            this.delayMs = Math.max(0, delayMs);
        }

        boolean dropIncoming() {
            return rnd.nextDouble() < incomingDropProb;
        }

        boolean dropReply() {
            return rnd.nextDouble() < replyDropProb;
        }

        boolean hasDelay() {
            return delayMs > 0;
        }

        int getDelayMs() {
            return delayMs;
        }

        private static double clamp(double v) {
            if (Double.isNaN(v) || v < 0.0) return 0.0;
            if (v > 1.0) return 1.0;
            return v;
        }
    }

    /* ---------------------------
       Main & argument parsing
       --------------------------- */

    public static void main(String[] args) {
        // defaults
        int port = 9876;
        InvocationSemantic semantic = InvocationSemantic.AT_MOST_ONCE;
        double lossRate = 0.0;
        double replyLossRate = 0.0;
        int delayMs = 0;
        int cacheTtlSeconds = 300;

        // Parse args simple style
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            try {
                switch (a) {
                    case "--port":
                        port = Integer.parseInt(args[++i]);
                        break;
                    case "--semantic":
                        String s = args[++i].toUpperCase(Locale.ROOT);
                        if ("AT_LEAST_ONCE".equals(s)) semantic = InvocationSemantic.AT_LEAST_ONCE;
                        else semantic = InvocationSemantic.AT_MOST_ONCE;
                        break;
                    case "--lossRate":
                        lossRate = Double.parseDouble(args[++i]);
                        break;
                    case "--replyLossRate":
                        replyLossRate = Double.parseDouble(args[++i]);
                        break;
                    case "--delayMs":
                        delayMs = Integer.parseInt(args[++i]);
                        break;
                    case "--cacheTtlSeconds":
                        cacheTtlSeconds = Integer.parseInt(args[++i]);
                        break;
                    default:
                        System.err.println("Unknown arg: " + a);
                }
            } catch (Exception ex) {
                System.err.println("Invalid usage for arg " + a + ": " + ex.getMessage());
                System.exit(2);
            }
        }

        UDPServer srv = new UDPServer(port, semantic, lossRate, replyLossRate, delayMs, cacheTtlSeconds);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutdown requested - stopping server...");
            srv.stop();
        }));

        try {
            srv.start();
        } catch (SocketException se) {
            logger.severe("Failed to start server: " + se.getMessage());
            System.exit(1);
        }
    }
}
