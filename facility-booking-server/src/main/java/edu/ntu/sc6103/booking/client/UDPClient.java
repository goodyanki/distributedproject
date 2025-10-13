package edu.ntu.sc6103.booking.client;

import edu.ntu.sc6103.booking.protocol.MessageMarshaller;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * UDPClient - interactive UDP client for Facility Booking Server.
 *
 * Features:
 *  - Interactive menu: query / book / change / monitor / op_a / op_b / exit
 *  - Retransmit on timeout: configurable timeoutMs and retries
 *  - Parsing of replies and monitor callback payloads
 *
 * Usage:
 *  java -cp target/facility-booking-server-1.0-SNAPSHOT.jar edu.ntu.sc6103.booking.client.UDPClient [serverHost] [serverPort]
 *
 * Example:
 *  java -cp target/facility-booking-server-1.0-SNAPSHOT.jar edu.ntu.sc6103.booking.client.UDPClient 127.0.0.1 9876
 */
public class UDPClient {

    private final InetAddress serverAddr;
    private final int serverPort;
    private final DatagramSocket socket;
    private int timeoutMs = 2000;
    private int retries = 3;

    public UDPClient(InetAddress serverAddr, int serverPort, int bindPort) throws Exception {
        this.serverAddr = serverAddr;
        this.serverPort = serverPort;
        if (bindPort <= 0) this.socket = new DatagramSocket();
        else this.socket = new DatagramSocket(bindPort);
        this.socket.setSoTimeout(timeoutMs);
    }

    public void setTimeoutMs(int t) throws SocketException {
        this.timeoutMs = t;
        this.socket.setSoTimeout(timeoutMs);
    }

    public void setRetries(int r) { this.retries = Math.max(0, r); }

    private int nextRequestId() {
        // simple pseudo-random 32-bit id (avoid 0)
        return ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
    }

    private void sendAndReceiveWithRetry(byte[] requestRaw, int requestId) throws Exception {
        int attempts = 0;
        boolean ok = false;
        byte[] recvBuf = new byte[65535];
        DatagramPacket recvPkt = new DatagramPacket(recvBuf, recvBuf.length);

        while (attempts <= retries && !ok) {
            attempts++;
            DatagramPacket pkt = new DatagramPacket(requestRaw, requestRaw.length, serverAddr, serverPort);
            socket.send(pkt);

            try {
                socket.receive(recvPkt);
                // got something: try to unmarshal response, check requestId match
                byte[] respBytes = Arrays.copyOf(recvPkt.getData(), recvPkt.getLength());
                MessageMarshaller.Response rsp = MessageMarshaller.unmarshalResponse(respBytes, respBytes.length);
                if (rsp.requestId != requestId) {
                    System.out.println("Received response for different requestId: " + rsp.requestId + " (expected " + requestId + "). Ignoring.");
                    // continue waiting within timeout
                    continue;
                }
                // success: process and return
                processResponse(rsp);
                ok = true;
            } catch (SocketTimeoutException ste) {
                System.out.println("Timeout waiting reply (attempt " + attempts + "/" + (retries+1) + "). Retrying...");
                // loop to resend if attempts <= retries
            }
        }

        if (!ok) {
            System.out.println("No reply after " + (retries+1) + " attempts.");
        }
    }

    private void processResponse(MessageMarshaller.Response rsp) {
        System.out.println("=== Server Response ===");
        System.out.println("requestId: " + rsp.requestId + " code: " + rsp.code);
        if (rsp.payload == null || rsp.payload.length == 0) {
            System.out.println("(no payload)");
            return;
        }
        switch (rsp.code) {
            case OK:
                // decode based on context? we print raw plus try some guesses:
                System.out.println("Payload (len=" + rsp.payload.length + " bytes)");
                // try: BOOK returns uint32 confirmationId (4 bytes)
                if (rsp.payload.length == 4) {
                    ByteBuffer b = ByteBuffer.wrap(rsp.payload);
                    int conf = b.getInt();
                    System.out.println("ConfirmationId: " + conf);
                } else {
                    // try query generic format: first two bytes could be dayCount
                    try {
                        ByteBuffer b = ByteBuffer.wrap(rsp.payload);
                        if (b.remaining() >= 2) {
                            int dayCount = Short.toUnsignedInt(b.getShort());
                            System.out.println("Query result: dayCount=" + dayCount);
                            for (int i = 0; i < dayCount; i++) {
                                int day = Byte.toUnsignedInt(b.get());
                                int intervalCount = Short.toUnsignedInt(b.getShort());
                                System.out.println(" Day " + day + " intervals:" );
                                for (int j = 0; j < intervalCount; j++) {
                                    int s = Short.toUnsignedInt(b.getShort());
                                    int e = Short.toUnsignedInt(b.getShort());
                                    System.out.println("  - " + minuteOfDayToStr(s) + " .. " + minuteOfDayToStr(e));
                                }
                            }
                        } else {
                            // print hex
                            System.out.println(bytesToHex(rsp.payload));
                        }
                    } catch (Exception ex) {
                        System.out.println("Unable to parse payload: " + ex.getMessage());
                        System.out.println(bytesToHex(rsp.payload));
                    }
                }
                break;
            default:
                // print payload as string for error messages
                String s = new String(rsp.payload, StandardCharsets.UTF_8);
                System.out.println("Error payload: " + s);
        }
        System.out.println("=======================");
    }

    private void startInteractive() throws Exception {
        Scanner sc = new Scanner(System.in);
        System.out.println("Connected to server " + serverAddr.getHostAddress() + ":" + serverPort);
        System.out.println("Timeout=" + timeoutMs + "ms Retries=" + retries);
        outer:
        while (true) {
            System.out.println("\nCommands: query book change monitor op_a op_b set timeout set retries exit");
            System.out.print("> ");
            String line = sc.nextLine().trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\s+");
            String cmd = parts[0].toLowerCase(Locale.ROOT);

            switch (cmd) {
                case "exit":
                    break outer;
                case "set":
                    if (parts.length >= 3) {
                        if ("timeout".equalsIgnoreCase(parts[1])) {
                            int t = Integer.parseInt(parts[2]);
                            setTimeoutMs(t);
                            System.out.println("timeout set to " + t + " ms");
                        } else if ("retries".equalsIgnoreCase(parts[1])) {
                            int r = Integer.parseInt(parts[2]);
                            setRetries(r);
                            System.out.println("retries set to " + r);
                        }
                    } else {
                        System.out.println("Usage: set timeout <ms> | set retries <n>");
                    }
                    break;
                case "query":
                    // usage: query <facilityName> [day1 day2 ...]  (days 0=Mon..6=Sun)
                    if (parts.length < 2) {
                        System.out.println("Usage: query <facilityName> [day0 day1 ...]");
                        break;
                    }
                    String qname = parts[1];
                    List<Integer> days = new ArrayList<>();
                    for (int i = 2; i < parts.length; i++) days.add(Integer.parseInt(parts[i]));
                    sendQuery(qname, days);
                    break;
                case "book":
                    // book <facilityName> <sDay> <sHour> <sMin> <eDay> <eHour> <eMin>
                    if (parts.length != 8) {
                        System.out.println("Usage: book <name> sDay sHour sMin eDay eHour eMin");
                        break;
                    }
                    String bname = parts[1];
                    int sd = Integer.parseInt(parts[2]), sh = Integer.parseInt(parts[3]), sm = Integer.parseInt(parts[4]);
                    int ed = Integer.parseInt(parts[5]), eh = Integer.parseInt(parts[6]), em = Integer.parseInt(parts[7]);
                    sendBook(bname, sd, sh, sm, ed, eh, em);
                    break;
                case "change":
                    // change <confirmationId> <offsetMinutes>
                    if (parts.length != 3) {
                        System.out.println("Usage: change <confirmationId> <offsetMinutes>");
                        break;
                    }
                    int conf = Integer.parseInt(parts[1]);
                    int offset = Integer.parseInt(parts[2]);
                    sendChange(conf, offset);
                    break;
                case "monitor":
                    // monitor <facilityName> <intervalSeconds>
                    if (parts.length != 3) {
                        System.out.println("Usage: monitor <facilityName> <intervalSeconds>");
                        break;
                    }
                    String mname = parts[1];
                    int interval = Integer.parseInt(parts[2]);
                    sendRegisterMonitorAndWait(mname, interval);
                    break;
                case "op_a":
                    // optional: op_a <optional-facilityName>
                    String nameA = (parts.length >= 2) ? parts[1] : "";
                    sendOpA(nameA);
                    break;
                case "op_b":
                    String nameB = (parts.length >= 2) ? parts[1] : "";
                    sendOpB(nameB);
                    break;
                default:
                    System.out.println("Unknown command: " + cmd);
            }
        }
        sc.close();
        socket.close();
    }

    /* -----------------------------
       Senders for each operation
       ----------------------------- */

    private void sendQuery(String facilityName, List<Integer> days) throws Exception {
        int reqId = nextRequestId();
        byte[] payload = MessageMarshaller.buildQueryPayload(facilityName, days);
        MessageMarshaller.Request req = new MessageMarshaller.Request(reqId, MessageMarshaller.OperationType.QUERY, MessageMarshaller.InvocationSemantic.DEFAULT, payload);
        byte[] raw = MessageMarshaller.marshalRequest(req);
        sendAndReceiveWithRetry(raw, reqId);
    }

    private void sendBook(String facilityName, int sd, int sh, int sm, int ed, int eh, int em) throws Exception {
        int reqId = nextRequestId();
        byte[] payload = MessageMarshaller.buildBookPayload(facilityName, sd, sh, sm, ed, eh, em);
        MessageMarshaller.Request req = new MessageMarshaller.Request(reqId, MessageMarshaller.OperationType.BOOK, MessageMarshaller.InvocationSemantic.DEFAULT, payload);
        byte[] raw = MessageMarshaller.marshalRequest(req);
        sendAndReceiveWithRetry(raw, reqId);
    }

    private void sendChange(int confirmationId, int offsetMinutes) throws Exception {
        int reqId = nextRequestId();
        byte[] payload = MessageMarshaller.buildChangePayload(confirmationId, offsetMinutes);
        MessageMarshaller.Request req = new MessageMarshaller.Request(reqId, MessageMarshaller.OperationType.CHANGE, MessageMarshaller.InvocationSemantic.DEFAULT, payload);
        byte[] raw = MessageMarshaller.marshalRequest(req);
        sendAndReceiveWithRetry(raw, reqId);
    }

    private void sendRegisterMonitorAndWait(String facilityName, int intervalSeconds) throws Exception {
        int reqId = nextRequestId();
        byte[] payload = MessageMarshaller.buildRegisterMonitorPayload(facilityName, intervalSeconds);
        MessageMarshaller.Request req = new MessageMarshaller.Request(reqId, MessageMarshaller.OperationType.REGISTER_MONITOR, MessageMarshaller.InvocationSemantic.DEFAULT, payload);
        byte[] raw = MessageMarshaller.marshalRequest(req);

        // send registration and wait for acknowledgement (with retries)
        sendAndReceiveWithRetry(raw, reqId);

        // If ACK OK, now block and listen for callbacks for intervalSeconds.
        System.out.println("Now waiting for monitor callbacks for " + intervalSeconds + " seconds...");
        long end = System.currentTimeMillis() + intervalSeconds * 1000L;
        byte[] buf = new byte[65535];
        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
        while (System.currentTimeMillis() < end) {
            long timeLeft = end - System.currentTimeMillis();
            int to = (int) Math.max(1, Math.min(timeLeft, timeoutMs));
            socket.setSoTimeout(to);
            try {
                socket.receive(pkt);
                byte[] msg = Arrays.copyOf(pkt.getData(), pkt.getLength());
                // parse monitor payload format from RequestProcessor:
                parseAndPrintMonitorPayload(msg, pkt.getAddress(), pkt.getPort());
            } catch (SocketTimeoutException ste) {
                // loop until end
            }
        }
        // restore original timeout
        socket.setSoTimeout(timeoutMs);
        System.out.println("Monitor interval ended. You may issue new commands now.");
    }

    private void parseAndPrintMonitorPayload(byte[] payload, InetAddress from, int port) {
        try {
            ByteBuffer b = ByteBuffer.wrap(payload);
            int nameLen = Short.toUnsignedInt(b.getShort());
            byte[] nb = new byte[nameLen];
            b.get(nb);
            String fname = new String(nb, StandardCharsets.UTF_8);
            int bookingCount = Short.toUnsignedInt(b.getShort());
            System.out.println("\n[Monitor update] from " + from + ":" + port + " facility=" + fname + " bookings=" + bookingCount);
            for (int i = 0; i < bookingCount; i++) {
                int s = b.getInt();
                int e = b.getInt();
                System.out.println("  - " + minuteOfWeekToString(s) + " .. " + minuteOfWeekToString(e));
            }
            System.out.println();
        } catch (Exception ex) {
            System.out.println("Failed to parse monitor payload: " + ex.getMessage());
        }
    }

    private void sendOpA(String optionalName) throws Exception {
        int reqId = nextRequestId();
        byte[] payload = new byte[0];
        if (optionalName != null && !optionalName.isEmpty()) {
            // encode as uint16 + bytes using MessageMarshaller helper style
            byte[] nameBytes = optionalName.getBytes(StandardCharsets.UTF_8);
            ByteBuffer b = ByteBuffer.allocate(2 + nameBytes.length);
            b.putShort((short) nameBytes.length);
            b.put(nameBytes);
            payload = Arrays.copyOf(b.array(), b.position());
        }
        MessageMarshaller.Request req = new MessageMarshaller.Request(reqId, MessageMarshaller.OperationType.OP_A_IDEMPOTENT, MessageMarshaller.InvocationSemantic.DEFAULT, payload);
        byte[] raw = MessageMarshaller.marshalRequest(req);
        sendAndReceiveWithRetry(raw, reqId);
    }

    private void sendOpB(String optionalName) throws Exception {
        int reqId = nextRequestId();
        byte[] payload = new byte[0];
        if (optionalName != null && !optionalName.isEmpty()) {
            byte[] nameBytes = optionalName.getBytes(StandardCharsets.UTF_8);
            ByteBuffer b = ByteBuffer.allocate(2 + nameBytes.length);
            b.putShort((short) nameBytes.length);
            b.put(nameBytes);
            payload = Arrays.copyOf(b.array(), b.position());
        }
        MessageMarshaller.Request req = new MessageMarshaller.Request(reqId, MessageMarshaller.OperationType.OP_B_NON_IDEMPOTENT, MessageMarshaller.InvocationSemantic.DEFAULT, payload);
        byte[] raw = MessageMarshaller.marshalRequest(req);
        sendAndReceiveWithRetry(raw, reqId);
    }

    /* -----------------------------
       Helpers
       ----------------------------- */

    private static String minuteOfDayToStr(int m) {
        int h = m / 60;
        int mm = m % 60;
        return String.format("%02d:%02d", h, mm);
    }

    private static String minuteOfWeekToString(int m) {
        int day = m / (24 * 60);
        int dmin = m % (24 * 60);
        int h = dmin / 60;
        int mm = dmin % 60;
        String[] names = {"Mon","Tue","Wed","Thu","Fri","Sat","Sun"};
        String dayName = (day >= 0 && day <= 6) ? names[day] : ("day" + day);
        return String.format("%s %02d:%02d", dayName, h, mm);
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /* -----------------------------
       Main
       ----------------------------- */

    public static void main(String[] args) throws Exception {
        String host = "127.0.0.1";
        int port = 9876;
        int bindPort = 0;
        if (args.length >= 1) host = args[0];
        if (args.length >= 2) port = Integer.parseInt(args[1]);
        if (args.length >= 3) bindPort = Integer.parseInt(args[2]);

        InetAddress addr = InetAddress.getByName(host);
        UDPClient client = new UDPClient(addr, port, bindPort);

        // Optional: allow system env or args to override timeout/retries (not required)
        System.out.println("UDP Client starting. Server=" + host + ":" + port);
        client.startInteractive();
    }
}
