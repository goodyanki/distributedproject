package edu.ntu.sc6103.booking.core;

import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * BookingManager: 线程安全地管理设施与预订
 *
 * 主要方法：
 * - createFacilityIfAbsent(name)
 * - listFacilities()
 * - bookFacility(name, startDay,hour,min, endDay,hour,min) -> confirmationId
 * - changeBooking(confirmationId, offsetMinutes) -> void / exception
 * - queryBookings(name, days) -> Map<dayIndex, List<BookingInterval>>
 *
 * 时间表示：使用周内分钟偏移（0..10079），方便跨日比较。
 *
 * 错误抛出：使用运行时异常（可以根据需要改为自定义异常类型）
 */
public class BookingManager {

    private static final Logger logger = Logger.getLogger(BookingManager.class.getName()); // <-- 确保 Logger 存在

    private final Map<String, Facility> facilities = new HashMap<>();
    private final Map<Integer, Booking> bookingsById = new HashMap<>();
    private final ReentrantReadWriteLock rw = new ReentrantReadWriteLock();

    public BookingManager() {
        // !!! 关键修改: 在构造函数中调用初始化方法 !!!
        initializeDefaultFacilities();
    }

    // 新增: 初始化默认设施
    private void initializeDefaultFacilities() {
        createFacilityIfAbsent("RoomA");
        createFacilityIfAbsent("RoomB");
        logger.info("BookingManager initialized with default facilities (RoomA, RoomB).");
    }


    // ---------- facility management ----------
    public void createFacilityIfAbsent(String name) {
        rw.writeLock().lock();
        try {
            // !!! 修复点 !!!：回到原始的兼容代码：只使用 Facility(String name)
            facilities.computeIfAbsent(name, Facility::new);
            // 记录日志，但不尝试注入 ID
            logger.info("Ensure facility created: " + name);
        } finally {
            rw.writeLock().unlock();
        }
    }

    public List<String> listFacilities() {
        rw.readLock().lock();
        try {
            return new ArrayList<>(facilities.keySet());
        } finally {
            rw.readLock().unlock();
        }
    }

    private Facility getFacilityOrThrow(String name) {
        Facility f = facilities.get(name);
        if (f == null) throw new NoSuchElementException("Facility not found: " + name);
        return f;
    }

    // ---------- booking operations ----------

    /**
     * Book a facility for given start/end specification.
     * ...
     */
    public int bookFacility(String facilityName,
                             int startDay, int startHour, int startMin,
                             int endDay, int endHour, int endMin) {
        Objects.requireNonNull(facilityName);
        int s = toMinuteOfWeek(startDay, startHour, startMin);
        int e = toMinuteOfWeek(endDay, endHour, endMin);
        if (e <= s) throw new IllegalArgumentException("End must be after start");

        rw.writeLock().lock();
        try {
            Facility f = facilities.get(facilityName);
            if (f == null) throw new NoSuchElementException("Facility not found: " + facilityName);

            // conflict detection: check any existing booking overlaps [s, e)
            for (Booking b : f.getBookings()) {
                if (overlap(s, e, b.getStartMinute(), b.getEndMinute())) {
                    throw new IllegalStateException("Conflict with existing booking: " + b.getConfirmationId());
                }
            }

            int id = IdGenerator.nextId();
            Booking nb = new Booking(id, facilityName, s, e);
            f.addBooking(nb);
            bookingsById.put(id, nb);
            return id;
        } finally {
            rw.writeLock().unlock();
        }
    }

    /**
     * Change booking by confirmation id by shifting start & end by offsetMinutes.
     * ...
     */
    public void changeBooking(int confirmationId, int offsetMinutes) {
        rw.writeLock().lock();
        try {
            Booking b = bookingsById.get(confirmationId);
            if (b == null) throw new NoSuchElementException("Confirmation ID not found: " + confirmationId);

            Facility f = facilities.get(b.getFacilityName());
            if (f == null) throw new IllegalStateException("Facility data missing for booking");

            // compute new interval
            int newStart = b.getStartMinute() + offsetMinutes;
            int newEnd = b.getEndMinute() + offsetMinutes;
            if (newStart < 0 || newEnd > 7 * 24 * 60 || newEnd <= newStart)
                throw new IllegalArgumentException("Shift results in invalid time range");

            // check conflicts with others (skip itself)
            for (Booking other : f.getBookings()) {
                if (other.getConfirmationId() == confirmationId) continue;
                if (overlap(newStart, newEnd, other.getStartMinute(), other.getEndMinute())) {
                    throw new IllegalStateException("Conflict with existing booking id=" + other.getConfirmationId());
                }
            }

            // apply shift
            b.shiftByMinutes(offsetMinutes);
        } finally {
            rw.writeLock().unlock();
        }
    }

    /**
     * Query bookings / availability for given facility and list of day indices (0..6).
     * ...
     */
    public Map<Integer, List<int[]>> queryBookings(String facilityName, List<Integer> days) {
        Objects.requireNonNull(facilityName);
        if (days == null) days = Arrays.asList(0,1,2,3,4,5,6);

        rw.readLock().lock();
        try {
            Facility f = facilities.get(facilityName);
            if (f == null) throw new NoSuchElementException("Facility not found: " + facilityName);

            // prepare empty lists
            Map<Integer, List<int[]>> result = new HashMap<>();
            for (Integer d : days) result.put(d, new ArrayList<>());

            // for each booking, map its overlap with requested days
            for (Booking b : f.getBookings()) {
                int s = b.getStartMinute();
                int e = b.getEndMinute();
                // for each day in requested days, compute overlap
                for (Integer d : days) {
                    int dayStart = d * 24 * 60;
                    int dayEnd = dayStart + 24 * 60;
                    int overlapStart = Math.max(s, dayStart);
                    int overlapEnd = Math.min(e, dayEnd);
                    if (overlapStart < overlapEnd) {
                        // convert to minute-of-day
                        int sd = overlapStart - dayStart;
                        int ed = overlapEnd - dayStart;
                        result.get(d).add(new int[]{sd, ed});
                    }
                }
            }

            // sort intervals per day by start
            for (List<int[]> lst : result.values()) {
                lst.sort(Comparator.comparingInt(a -> a[0]));
            }
            return result;
        } finally {
            rw.readLock().unlock();
        }
    }

    /**
     * Remove a booking by id (helper for tests/demo).
     */
    public boolean cancelBooking(int confirmationId) {
        rw.writeLock().lock();
        try {
            Booking b = bookingsById.remove(confirmationId);
            if (b == null) return false;
            Facility f = facilities.get(b.getFacilityName());
            if (f != null) f.removeBooking(b);
            return true;
        } finally {
            rw.writeLock().unlock();
        }
    }

    // ---------- utilities ----------

    private static int toMinuteOfWeek(int day, int hour, int minute) {
        if (day < 0 || day > 6) throw new IllegalArgumentException("Invalid day");
        if (hour < 0 || hour > 23) throw new IllegalArgumentException("Invalid hour");
        if (minute < 0 || minute > 59) throw new IllegalArgumentException("Invalid minute");
        return day * 24 * 60 + hour * 60 + minute;
    }

    private static boolean overlap(int s1, int e1, int s2, int e2) {
        return s1 < e2 && s2 < e1;
    }

    // For debugging: pretty print bookings for a facility
    public String bookingsToString(String facilityName) {
        rw.readLock().lock();
        try {
            Facility f = facilities.get(facilityName);
            if (f == null) return "Facility not found: " + facilityName;
            return f.getBookings().stream().map(Booking::toString).collect(Collectors.joining("\n"));
        } finally {
            rw.readLock().unlock();
        }
    }
}