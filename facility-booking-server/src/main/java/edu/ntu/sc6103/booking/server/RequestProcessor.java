package edu.ntu.sc6103.booking.server;

import edu.ntu.sc6103.booking.core.BookingManager;
import edu.ntu.sc6103.booking.core.IdGenerator;
import edu.ntu.sc6103.booking.protocol.MessageMarshaller;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.logging.Logger;

/**
 * RequestProcessor
 *
 * Receives an unmarshalled MessageMarshaller.Request + client address,
 * dispatches the operation to BookingManager, and returns a ProcessResult
 * containing the Response to send back to the client and (optionally)
 * a list of callback notifications that the UDPServer should send to other clients.
 *
 * Notes:
 *  - This class keeps a simple MonitorRegistry (in-memory).
 *  - Callbacks' payload format (for monitor notifications):
 *      [facilityName (uint16 + bytes)] [uint16 bookingCount]
 *      for each booking: [int startMinute] [int endMinute]  (minute-of-week, 0..10079)
 */
public class RequestProcessor {
    private static final Logger logger = Logger.getLogger(RequestProcessor.class.getName());

    private final BookingManager bookingManager;
    private final MonitorRegistry monitorRegistry;

    public RequestProcessor(BookingManager bookingManager) {
        this.bookingManager = Objects.requireNonNull(bookingManager);
        this.monitorRegistry = new MonitorRegistry();
    }

    /**
     * Result object returned to UDPServer.
     * - response: the main reply to client
     * - callbacks: list of callbacks (target addr + bytes) to send (e.g., monitor notifications)
     */
    public static final class ProcessResult {
        public final MessageMarshaller.Response response;
        public final List<Callback> callbacks;

        public ProcessResult(MessageMarshaller.Response response, List<Callback> callbacks) {
            this.response = response;
            this.callbacks = callbacks == null ? Collections.emptyList() : callbacks;
        }
    }

    /**
     * A callback to be sent by UDPServer: target and raw payload
     */
    public static final class Callback {
        public final InetSocketAddress target;
        public final byte[] payload;

        public Callback(InetSocketAddress target, byte[] payload) {
            this.target = target;
            this.payload = payload;
        }
    }

    /**
     * Process a request from client at (clientAddr, clientPort).
     */
    public ProcessResult processRequest(MessageMarshaller.Request req, String clientHost, int clientPort) {
        Objects.requireNonNull(req);
        Objects.requireNonNull(clientHost);
        List<Callback> callbacks = new ArrayList<>();

        try {
            switch (req.op) {
                case QUERY:
                    return handleQuery(req);
                case BOOK:
                    ProcessResult prBook = handleBook(req);
                    // If booking succeeded, prepare monitor callbacks for that facility
                    if (!prBook.callbacks.isEmpty()) {
                        callbacks.addAll(prBook.callbacks);
                    }
                    return new ProcessResult(prBook.response, prBook.callbacks);
                case CHANGE:
                    ProcessResult prChange = handleChange(req);
                    if (!prChange.callbacks.isEmpty()) callbacks.addAll(prChange.callbacks);
                    return new ProcessResult(prChange.response, prChange.callbacks);
                case REGISTER_MONITOR:
                    return handleRegisterMonitor(req, clientHost, clientPort);
                case OP_A_IDEMPOTENT:
                    return handleOpA(req);
                case OP_B_NON_IDEMPOTENT:
                    return handleOpB(req);
                default:
                    return new ProcessResult(
                            new MessageMarshaller.Response(req.requestId, MessageMarshaller.ResponseCode.ERR_INVALID, new byte[0]),
                            null);
            }
        } catch (Exception ex) {
            logger.severe("Unhandled exception while processing request: " + ex);
            MessageMarshaller.ResponseCode rc = mapExceptionToResponseCode(ex);
            return new ProcessResult(new MessageMarshaller.Response(req.requestId, rc, ex.getMessage() == null ? new byte[0] : ex.getMessage().getBytes()), null);
        }
    }

    /* ----------------------
       Handlers for ops
       ---------------------- */

    private ProcessResult handleQuery(MessageMarshaller.Request req) {
        try {
            MessageMarshaller.QueryPayload qp = MessageMarshaller.parseQueryPayload(req.payload);
            // perform query
            Map<Integer, List<int[]>> map = bookingManager.queryBookings(qp.facilityName, qp.days);

            // build payload:
            // [uint16 dayCount] [for each day: uint8 dayIndex, uint16 intervalCount, for each interval: uint16 startMinOfDay, uint16 endMinOfDay]
            // Note: minute-of-day up to 0..1439 fits in uint16
            int dayCount = map.size();
            // Estimate size roughly (we'll build dynamically with ByteBuffer)
            int approx = 2 + dayCount * (1 + 2 + 4 * 4);
            ByteBuffer buf = ByteBuffer.allocate(Math.max(approx, 64));
            buf.putShort((short) dayCount);
            for (Map.Entry<Integer, List<int[]>> e : map.entrySet()) {
                int day = e.getKey();
                List<int[]> intervals = e.getValue();
                buf.put((byte) (day & 0xFF));
                buf.putShort((short) intervals.size());
                for (int[] iv : intervals) {
                    // iv[0], iv[1] are minute-of-day (0..1439)
                    buf.putShort((short) iv[0]);
                    buf.putShort((short) iv[1]);
                }
            }
            byte[] payload = Arrays.copyOf(buf.array(), buf.position());
            MessageMarshaller.Response rsp = new MessageMarshaller.Response(req.requestId, MessageMarshaller.ResponseCode.OK, payload);
            return new ProcessResult(rsp, null);
        } catch (NoSuchElementException nse) {
            return new ProcessResult(new MessageMarshaller.Response(req.requestId, MessageMarshaller.ResponseCode.ERR_NOT_FOUND, nse.getMessage().getBytes()), null);
        } catch (IllegalArgumentException iae) {
            return new ProcessResult(new MessageMarshaller.Response(req.requestId, MessageMarshaller.ResponseCode.ERR_INVALID, iae.getMessage().getBytes()), null);
        } catch (Exception ex) {
            return new ProcessResult(new MessageMarshaller.Response(req.requestId, MessageMarshaller.ResponseCode.ERR_INTERNAL, ex.getMessage() == null ? new byte[0] : ex.getMessage().getBytes()), null);
        }
    }

    private ProcessResult handleBook(MessageMarshaller.Request req) {
        try {
            MessageMarshaller.BookPayload bp = MessageMarshaller.parseBookPayload(req.payload);
            int confId = bookingManager.bookFacility(bp.facilityName, bp.startDay, bp.startHour, bp.startMin, bp.endDay, bp.endHour, bp.endMin);
            // payload: uint32 confirmationId
            ByteBuffer b = ByteBuffer.allocate(4);
            b.putInt(confId);
            MessageMarshaller.Response rsp = new MessageMarshaller.Response(req.requestId, MessageMarshaller.ResponseCode.OK, b.array());

            // prepare monitor callbacks: generate payload containing this facility's current bookings
            List<Callback> cbs = buildMonitorCallbacksForFacility(bp.facilityName);
            return new ProcessResult(rsp, cbs);
        } catch (NoSuchElementException nse) {
            return new ProcessResult(new MessageMarshaller.Response(req.requestId, MessageMarshaller.ResponseCode.ERR_NOT_FOUND, nse.getMessage().getBytes()), null);
        } catch (IllegalStateException ise) {
            return new ProcessResult(new MessageMarshaller.Response(req.requestId, MessageMarshaller.ResponseCode.ERR_CONFLICT, ise.getMessage().getBytes()), null);
        } catch (IllegalArgumentException iae) {
            return new ProcessResult(new MessageMarshaller.Response(req.requestId, MessageMarshaller.ResponseCode.ERR_INVALID, iae.getMessage().getBytes()), null);
        } catch (Exception ex) {
            return new ProcessResult(new MessageMarshaller.Response(req.requestId, MessageMarshaller.ResponseCode.ERR_INTERNAL, ex.getMessage() == null ? new byte[0] : ex.getMessage().getBytes()), null);
        }
    }

    private ProcessResult handleChange(MessageMarshaller.Request req) {
        try {
            MessageMarshaller.ChangePayload cp = MessageMarshaller.parseChangePayload(req.payload);
            bookingManager.changeBooking(cp.confirmationId, cp.offsetMinutes);
            MessageMarshaller.Response rsp = new MessageMarshaller.Response(req.requestId, MessageMarshaller.ResponseCode.OK, new byte[0]);

            // find facility name from booking (BookingManager doesn't expose lookup, so we can try to keep bookings map - but here we will generate callbacks for all monitors whose facility matches)
            // For simplicity, build callbacks for all monitors for which facility equals booking's facility.
            // We need facility name: since BookingManager doesn't expose direct method, a pragmatic approach: scan registered monitors and build callbacks for those facilities
            // Instead, we'll ask BookingManager to expose bookingsToString earlier; but we need exact facility. For now, build callbacks for all monitors referencing the changed booking's facility by scanning monitors and checking if facility exists in BookingManager
            // Simpler: build callbacks for all monitors (safe but possibly noisy)
            List<Callback> cbs = new ArrayList<>();
            for (String fac : monitorRegistry.getAllFacilitiesMonitored()) {
                cbs.addAll(buildMonitorCallbacksForFacility(fac));
            }

            return new ProcessResult(rsp, cbs);
        } catch (NoSuchElementException nse) {
            return new ProcessResult(new MessageMarshaller.Response(req.requestId, MessageMarshaller.ResponseCode.ERR_NOT_FOUND, nse.getMessage().getBytes()), null);
        } catch (IllegalStateException ise) {
            return new ProcessResult(new MessageMarshaller.Response(req.requestId, MessageMarshaller.ResponseCode.ERR_CONFLICT, ise.getMessage().getBytes()), null);
        } catch (IllegalArgumentException iae) {
            return new ProcessResult(new MessageMarshaller.Response(req.requestId, MessageMarshaller.ResponseCode.ERR_INVALID, iae.getMessage().getBytes()), null);
        } catch (Exception ex) {
            return new ProcessResult(new MessageMarshaller.Response(req.requestId, MessageMarshaller.ResponseCode.ERR_INTERNAL, ex.getMessage() == null ? new byte[0] : ex.getMessage().getBytes()), null);
        }
    }

    private ProcessResult handleRegisterMonitor(MessageMarshaller.Request req, String clientHost, int clientPort) {
        try {
            MessageMarshaller.RegisterMonitorPayload rmp = MessageMarshaller.parseRegisterMonitorPayload(req.payload);
            InetSocketAddress clientAddr = new InetSocketAddress(clientHost, clientPort);
            monitorRegistry.register(rmp.facilityName, clientAddr, rmp.intervalSeconds);

            // acknowledgement payload: empty for now
            MessageMarshaller.Response rsp = new MessageMarshaller.Response(req.requestId, MessageMarshaller.ResponseCode.OK, new byte[0]);
            return new ProcessResult(rsp, null);
        } catch (NoSuchElementException nse) {
            return new ProcessResult(new MessageMarshaller.Response(req.requestId, MessageMarshaller.ResponseCode.ERR_NOT_FOUND, nse.getMessage().getBytes()), null);
        } catch (IllegalArgumentException iae) {
            return new ProcessResult(new MessageMarshaller.Response(req.requestId, MessageMarshaller.ResponseCode.ERR_INVALID, iae.getMessage().getBytes()), null);
        } catch (Exception ex) {
            return new ProcessResult(new MessageMarshaller.Response(req.requestId, MessageMarshaller.ResponseCode.ERR_INTERNAL, ex.getMessage() == null ? new byte[0] : ex.getMessage().getBytes()), null);
        }
    }

    /**
     * OP_A: an example idempotent operation — does not change booking state (no-op). Returns OK.
     */
    private ProcessResult handleOpA(MessageMarshaller.Request req) {
        // idempotent: do nothing (or record lastSeenRequestId per client if desired)
        return new ProcessResult(new MessageMarshaller.Response(req.requestId, MessageMarshaller.ResponseCode.OK, new byte[0]), null);
    }

    /**
     * OP_B: an example non-idempotent operation — it will create a small booking (1 minute)
     * at the first available minute in the week. Each call will create a distinct booking
     * if a slot is free (so repeated calls can create multiple bookings) — this demonstrates
     * non-idempotence under at-least-once semantics.
     */
    private ProcessResult handleOpB(MessageMarshaller.Request req) {
        try {
            // For OP_B we expect payload to include a facility name (string), but to be tolerant:
            String facilityName = "";
            try {
                ByteBuffer tmp = ByteBuffer.wrap(req.payload);
                facilityName = MessageMarshaller.getString(tmp);
            } catch (Exception ignored) {
            }
            if (facilityName == null || facilityName.isEmpty()) {
                // if not provided, pick the first facility available
                List<String> facs = bookingManager.listFacilities();
                if (facs.isEmpty()) throw new NoSuchElementException("No facility available for OP_B");
                facilityName = facs.get(0);
            }
            // try to find first 1-minute free slot by attempting bookings
            final int DURATION = 1; // minute
            int maxStart = 7 * 24 * 60 - DURATION;
            int selectedId = -1;
            for (int start = 0; start <= maxStart; start++) {
                int day = start / (24 * 60);
                int minuteOfDay = start % (24 * 60);
                int hour = minuteOfDay / 60;
                int minute = minuteOfDay % 60;
                try {
                    int id = bookingManager.bookFacility(facilityName, day, hour, minute, day, hour, minute + DURATION);
                    selectedId = id;
                    break;
                } catch (IllegalStateException conflict) {
                    // try next minute
                } catch (IllegalArgumentException iae) {
                    // skip invalid
                } catch (NoSuchElementException nse) {
                    throw nse;
                }
            }
            if (selectedId == -1) {
                // couldn't find free slot
                return new ProcessResult(new MessageMarshaller.Response(req.requestId, MessageMarshaller.ResponseCode.ERR_CONFLICT, "no free slot".getBytes()), null);
            } else {
                ByteBuffer b = ByteBuffer.allocate(4);
                b.putInt(selectedId);
                // also create monitor callbacks for this facility
                List<Callback> cbs = buildMonitorCallbacksForFacility(facilityName);
                return new ProcessResult(new MessageMarshaller.Response(req.requestId, MessageMarshaller.ResponseCode.OK, b.array()), cbs);
            }
        } catch (NoSuchElementException nse) {
            return new ProcessResult(new MessageMarshaller.Response(req.requestId, MessageMarshaller.ResponseCode.ERR_NOT_FOUND, nse.getMessage().getBytes()), null);
        } catch (Exception ex) {
            return new ProcessResult(new MessageMarshaller.Response(req.requestId, MessageMarshaller.ResponseCode.ERR_INTERNAL, ex.getMessage() == null ? new byte[0] : ex.getMessage().getBytes()), null);
        }
    }

    /* ----------------------
       Utility helpers
       ---------------------- */

    private MessageMarshaller.ResponseCode mapExceptionToResponseCode(Exception ex) {
        if (ex instanceof NoSuchElementException) return MessageMarshaller.ResponseCode.ERR_NOT_FOUND;
        if (ex instanceof IllegalStateException) return MessageMarshaller.ResponseCode.ERR_CONFLICT;
        if (ex instanceof IllegalArgumentException) return MessageMarshaller.ResponseCode.ERR_INVALID;
        return MessageMarshaller.ResponseCode.ERR_INTERNAL;
    }

    /**
     * Build monitor callbacks for a facility: for each registered monitor for that facility,
     * build a payload summarizing current bookings.
     *
     * Payload format:
     *   facilityName (uint16 + bytes)
     *   uint16 bookingCount
     *   for each booking: int startMinute (4 bytes), int endMinute (4 bytes)  -- minute-of-week
     */
    private List<Callback> buildMonitorCallbacksForFacility(String facilityName) {
        List<InetSocketAddress> watchers = monitorRegistry.getWatchersForFacility(facilityName);
        if (watchers.isEmpty()) return Collections.emptyList();

        // get booking map for whole week via queryBookings for days 0..6
        List<Integer> days = Arrays.asList(0,1,2,3,4,5,6);
        Map<Integer, List<int[]>> map;
        try {
            map = bookingManager.queryBookings(facilityName, days);
        } catch (Exception e) {
            // facility missing; no callbacks
            return Collections.emptyList();
        }

        // flatten to list of minute-of-week intervals
        List<int[]> all = new ArrayList<>();
        for (int d = 0; d <= 6; d++) {
            List<int[]> lst = map.getOrDefault(d, Collections.emptyList());
            int dayStart = d * 24 * 60;
            for (int[] iv : lst) {
                int s = dayStart + iv[0];
                int e = dayStart + iv[1];
                all.add(new int[]{s, e});
            }
        }

        // build payload
        // size = 2 + facilityNameLen + 2 + N * 8
        byte[] nameBytes = facilityName.getBytes();
        int total = 2 + nameBytes.length + 2 + all.size() * 8;
        ByteBuffer buf = ByteBuffer.allocate(total);
        // facilityName (uint16 + bytes)
        buf.putShort((short) nameBytes.length);
        buf.put(nameBytes);
        buf.putShort((short) all.size());
        for (int[] iv : all) {
            buf.putInt(iv[0]);
            buf.putInt(iv[1]);
        }
        byte[] payload = Arrays.copyOf(buf.array(), buf.position());

        List<Callback> cbs = new ArrayList<>();
        long now = System.currentTimeMillis();
        // remove expired monitors before building callback list
        monitorRegistry.cleanupExpired(now);

        for (InetSocketAddress target : monitorRegistry.getWatchersForFacility(facilityName)) {
            cbs.add(new Callback(target, payload));
        }
        return cbs;
    }

    /* ----------------------
       Simple MonitorRegistry (in-memory)
       ---------------------- */

    /**
     * MonitorRegistry: maps facilityName -> list of (InetSocketAddress, expiryEpochMs)
     */
    private static final class MonitorRegistry {
        // facilityName -> list of entries
        private final Map<String, List<MonitorEntry>> map = new HashMap<>();

        public synchronized void register(String facilityName, InetSocketAddress client, int intervalSeconds) {
            long expiry = System.currentTimeMillis() + Math.max(0, intervalSeconds) * 1000L;
            map.computeIfAbsent(facilityName, k -> new ArrayList<>()).add(new MonitorEntry(client, expiry));
            logger.info("Registered monitor for facility=" + facilityName + " client=" + client + " expires=" + new Date(expiry));
        }

        public synchronized List<InetSocketAddress> getWatchersForFacility(String facilityName) {
            List<MonitorEntry> lst = map.getOrDefault(facilityName, Collections.emptyList());
            List<InetSocketAddress> result = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (MonitorEntry me : lst) {
                if (me.expiryMs > now) result.add(me.client);
            }
            return result;
        }

        public synchronized Set<String> getAllFacilitiesMonitored() {
            return new HashSet<>(map.keySet());
        }

        public synchronized void cleanupExpired(long nowMs) {
            Iterator<Map.Entry<String, List<MonitorEntry>>> it = map.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, List<MonitorEntry>> e = it.next();
                List<MonitorEntry> lst = e.getValue();
                lst.removeIf(me -> me.expiryMs <= nowMs);
                if (lst.isEmpty()) it.remove();
            }
        }

        private static final class MonitorEntry {
            final InetSocketAddress client;
            final long expiryMs;

            MonitorEntry(InetSocketAddress client, long expiryMs) {
                this.client = client;
                this.expiryMs = expiryMs;
            }
        }
    }
}
