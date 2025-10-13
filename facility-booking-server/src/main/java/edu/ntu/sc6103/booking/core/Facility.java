package edu.ntu.sc6103.booking.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Facility: 表示一个设施和其预订记录（内存列表，BookingManager 负责并发控制）
 */
public class Facility {
    private final String name;
    // bookings 保持为一个列表；BookingManager 在高层负责同步/读写锁
    private final List<Booking> bookings = new ArrayList<>();

    public Facility(String name) {
        this.name = Objects.requireNonNull(name);
    }

    public String getName() { return name; }

    public List<Booking> getBookings() {
        return Collections.unmodifiableList(bookings);
    }

    // package-private helpers for BookingManager
    void addBooking(Booking b) { bookings.add(b); }
    void removeBooking(Booking b) { bookings.remove(b); }
}
