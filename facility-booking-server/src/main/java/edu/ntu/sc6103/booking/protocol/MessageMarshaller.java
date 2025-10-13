package edu.ntu.sc6103.booking.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * MessageMarshaller
 *
 * Binary protocol helper for the Facility Booking system.
 *
 * Header (Request):
 *   uint32 requestId
 *   uint8  opCode
 *   uint8  semanticFlag
 *   uint32 payloadLen
 *   payload...
 *
 * Header (Reply):
 *   uint32 requestId
 *   uint8  responseCode
 *   uint32 payloadLen
 *   payload...
 *
 * Strings: uint16 length (bytes) + UTF-8 bytes
 *
 * Note: All integers are big-endian (network byte order).
 */
public final class MessageMarshaller {

    private MessageMarshaller() {}

    /* -----------------------
       Enums and Data Classes
       ----------------------- */

    public enum InvocationSemantic {
        DEFAULT(0), AT_MOST_ONCE(1), AT_LEAST_ONCE(2);

        private final int code;
        InvocationSemantic(int c) { this.code = c; }
        public int code() { return code; }
        public static InvocationSemantic fromCode(int c) {
            for (InvocationSemantic s : values()) if (s.code == c) return s;
            return DEFAULT;
        }
    }

    public enum OperationType {
        QUERY(1),
        BOOK(2),
        CHANGE(3),
        REGISTER_MONITOR(4),
        OP_A_IDEMPOTENT(5),
        OP_B_NON_IDEMPOTENT(6);

        private final int code;
        OperationType(int c) { this.code = c; }
        public int code() { return code; }
        public static OperationType fromCode(int c) {
            for (OperationType o : values()) if (o.code == c) return o;
            throw new IllegalArgumentException("Unknown op code: " + c);
        }
    }

    public enum ResponseCode {
        OK(0),
        ERR_NOT_FOUND(1),
        ERR_CONFLICT(2),
        ERR_INVALID(3),
        ERR_INTERNAL(4);

        private final int code;
        ResponseCode(int c) { this.code = c; }
        public int code() { return code; }
        public static ResponseCode fromCode(int c) {
            for (ResponseCode r : values()) if (r.code == c) return r;
            return ERR_INTERNAL;
        }
    }

    public static final class Request {
        public final int requestId;
        public final OperationType op;
        public final InvocationSemantic semantic;
        public final byte[] payload; // raw payload bytes (operation-specific)

        public Request(int requestId, OperationType op, InvocationSemantic semantic, byte[] payload) {
            this.requestId = requestId;
            this.op = Objects.requireNonNull(op);
            this.semantic = Objects.requireNonNull(semantic);
            this.payload = payload == null ? new byte[0] : payload;
        }
    }

    public static final class Response {
        public final int requestId;
        public final ResponseCode code;
        public final byte[] payload;

        public Response(int requestId, ResponseCode code, byte[] payload) {
            this.requestId = requestId;
            this.code = Objects.requireNonNull(code);
            this.payload = payload == null ? new byte[0] : payload;
        }
    }

    /* -----------------------
       Marshall / Unmarshall
       ----------------------- */

    /**
     * Marshal a Request into a byte[] ready to send.
     */
    public static byte[] marshalRequest(Request req) {
        int payloadLen = req.payload.length;
        // header: 4 + 1 + 1 + 4 = 10 bytes
        ByteBuffer buf = ByteBuffer.allocate(10 + payloadLen);
        buf.putInt(req.requestId);
        buf.put((byte) req.op.code());
        buf.put((byte) req.semantic.code());
        buf.putInt(payloadLen);
        if (payloadLen > 0) buf.put(req.payload);
        return buf.array();
    }

    /**
     * Unmarshal raw bytes into Request.
     * Expects at least 10 bytes header.
     */
    public static Request unmarshalRequest(byte[] raw, int length) {
        if (length < 10) throw new IllegalArgumentException("Request too short: " + length);
        ByteBuffer buf = ByteBuffer.wrap(raw, 0, length);
        int requestId = buf.getInt();
        int opCode = Byte.toUnsignedInt(buf.get());
        int semanticFlag = Byte.toUnsignedInt(buf.get());
        int payloadLen = buf.getInt();
        if (payloadLen < 0 || payloadLen > buf.remaining()) {
            throw new IllegalArgumentException("Invalid payload length: " + payloadLen + ", remaining=" + buf.remaining());
        }
        byte[] payload = new byte[payloadLen];
        if (payloadLen > 0) buf.get(payload);
        OperationType op = OperationType.fromCode(opCode);
        InvocationSemantic sem = InvocationSemantic.fromCode(semanticFlag);
        return new Request(requestId, op, sem, payload);
    }

    /**
     * Marshal a Response into byte[].
     */
    public static byte[] marshalResponse(Response rsp) {
        int payloadLen = rsp.payload.length;
        // header: 4 + 1 + 4 = 9 bytes
        ByteBuffer buf = ByteBuffer.allocate(9 + payloadLen);
        buf.putInt(rsp.requestId);
        buf.put((byte) rsp.code.code());
        buf.putInt(payloadLen);
        if (payloadLen > 0) buf.put(rsp.payload);
        return buf.array();
    }

    /**
     * Unmarshal raw bytes into Response.
     */
    public static Response unmarshalResponse(byte[] raw, int length) {
        if (length < 9) throw new IllegalArgumentException("Response too short: " + length);
        ByteBuffer buf = ByteBuffer.wrap(raw, 0, length);
        int requestId = buf.getInt();
        int respCode = Byte.toUnsignedInt(buf.get());
        int payloadLen = buf.getInt();
        if (payloadLen < 0 || payloadLen > buf.remaining()) {
            throw new IllegalArgumentException("Invalid payload length: " + payloadLen + ", remaining=" + buf.remaining());
        }
        byte[] payload = new byte[payloadLen];
        if (payloadLen > 0) buf.get(payload);
        return new Response(requestId, ResponseCode.fromCode(respCode), payload);
    }

    /* -----------------------
       Helpers for payloads
       ----------------------- */

    // Write a UTF-8 string: uint16 length + bytes
    public static void putString(ByteBuffer buf, String s) {
        if (s == null) s = "";
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        if (b.length > 0xFFFF) throw new IllegalArgumentException("String too long");
        buf.putShort((short) b.length);
        buf.put(b);
    }

    // Read a uint16-prefixed UTF-8 string
    public static String getString(ByteBuffer buf) {
        int len = Short.toUnsignedInt(buf.getShort());
        if (len == 0) return "";
        byte[] b = new byte[len];
        buf.get(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    // Helper to create payload for QUERY operation:
    // payload: facilityName (string) | uint8 dayCount | [days uint8 ...]
    public static byte[] buildQueryPayload(String facilityName, List<Integer> days) {
        // days: each day encoded as uint8 (0..6 for Mon..Sun)
        int daysLen = days == null ? 0 : days.size();
        // estimate size: 2 + name + 1 + daysLen
        byte[] nameBytes = facilityName == null ? new byte[0] : facilityName.getBytes(StandardCharsets.UTF_8);
        int total = 2 + nameBytes.length + 1 + daysLen;
        ByteBuffer buf = ByteBuffer.allocate(total);
        putString(buf, facilityName);
        buf.put((byte) daysLen);
        if (daysLen > 0) {
            for (Integer d : days) buf.put((byte) (d & 0xFF));
        }
        return buf.array();
    }

    // Parse query payload into a pair (name, days)
    public static QueryPayload parseQueryPayload(byte[] payload) {
        ByteBuffer buf = ByteBuffer.wrap(payload);
        String name = getString(buf);
        int dayCount = Byte.toUnsignedInt(buf.get());
        List<Integer> days = new ArrayList<>(dayCount);
        for (int i = 0; i < dayCount; i++) days.add(Byte.toUnsignedInt(buf.get()));
        return new QueryPayload(name, days);
    }

    public static final class QueryPayload {
        public final String facilityName;
        public final List<Integer> days;
        public QueryPayload(String facilityName, List<Integer> days) {
            this.facilityName = facilityName;
            this.days = days;
        }
    }

    // Helper to build BOOK payload:
    // facilityName (string) | startDay(uint8)| startHour(uint8)| startMin(uint8) | endDay | endHour | endMin
    public static byte[] buildBookPayload(String facilityName,
                                          int startDay, int startHour, int startMin,
                                          int endDay, int endHour, int endMin) {
        byte[] nameBytes = facilityName == null ? new byte[0] : facilityName.getBytes(StandardCharsets.UTF_8);
        int total = 2 + nameBytes.length + 6;
        ByteBuffer buf = ByteBuffer.allocate(total);
        putString(buf, facilityName);
        buf.put((byte) startDay);
        buf.put((byte) startHour);
        buf.put((byte) startMin);
        buf.put((byte) endDay);
        buf.put((byte) endHour);
        buf.put((byte) endMin);
        return buf.array();
    }

    public static final class BookPayload {
        public final String facilityName;
        public final int startDay, startHour, startMin, endDay, endHour, endMin;
        public BookPayload(String facilityName, int sd, int sh, int sm, int ed, int eh, int em) {
            this.facilityName = facilityName;
            this.startDay = sd; this.startHour = sh; this.startMin = sm;
            this.endDay = ed; this.endHour = eh; this.endMin = em;
        }
    }

    public static BookPayload parseBookPayload(byte[] payload) {
        ByteBuffer buf = ByteBuffer.wrap(payload);
        String name = getString(buf);
        int sd = Byte.toUnsignedInt(buf.get());
        int sh = Byte.toUnsignedInt(buf.get());
        int sm = Byte.toUnsignedInt(buf.get());
        int ed = Byte.toUnsignedInt(buf.get());
        int eh = Byte.toUnsignedInt(buf.get());
        int em = Byte.toUnsignedInt(buf.get());
        return new BookPayload(name, sd, sh, sm, ed, eh, em);
    }

    // Helper to build CHANGE payload:
    // confirmationId (uint32) | offsetMinutes (int32)
    public static byte[] buildChangePayload(int confirmationId, int offsetMinutes) {
        ByteBuffer buf = ByteBuffer.allocate(8);
        buf.putInt(confirmationId);
        buf.putInt(offsetMinutes);
        return buf.array();
    }

    public static ChangePayload parseChangePayload(byte[] payload) {
        ByteBuffer buf = ByteBuffer.wrap(payload);
        int confId = buf.getInt();
        int offset = buf.getInt();
        return new ChangePayload(confId, offset);
    }

    public static final class ChangePayload {
        public final int confirmationId;
        public final int offsetMinutes;
        public ChangePayload(int confirmationId, int offsetMinutes) {
            this.confirmationId = confirmationId;
            this.offsetMinutes = offsetMinutes;
        }
    }

    // Helper to build REGISTER_MONITOR payload:
    // facilityName (string) | uint32 monitorIntervalSeconds | (optional) clientPort not included here since server learns it from UDP packet
    public static byte[] buildRegisterMonitorPayload(String facilityName, int monitorIntervalSeconds) {
        byte[] nameBytes = facilityName == null ? new byte[0] : facilityName.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(2 + nameBytes.length + 4);
        putString(buf, facilityName);
        buf.putInt(monitorIntervalSeconds);
        return buf.array();
    }

    public static RegisterMonitorPayload parseRegisterMonitorPayload(byte[] payload) {
        ByteBuffer buf = ByteBuffer.wrap(payload);
        String name = getString(buf);
        int interval = buf.getInt();
        return new RegisterMonitorPayload(name, interval);
    }

    public static final class RegisterMonitorPayload {
        public final String facilityName;
        public final int intervalSeconds;
        public RegisterMonitorPayload(String facilityName, int intervalSeconds) {
            this.facilityName = facilityName;
            this.intervalSeconds = intervalSeconds;
        }
    }

    /* -----------------------
       Small usage example
       ----------------------- */

    /**
     * Example:
     *   Request r = new Request(1234, OperationType.BOOK, InvocationSemantic.AT_MOST_ONCE,
     *       buildBookPayload("Room A", 0,9,0, 0,11,0));
     *   byte[] raw = MessageMarshaller.marshalRequest(r);
     *   // send raw via UDP
     *
     *   // on server receive:
     *   Request r2 = MessageMarshaller.unmarshalRequest(raw, raw.length);
     *   BookPayload bp = MessageMarshaller.parseBookPayload(r2.payload);
     */
}
