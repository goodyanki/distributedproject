package edu.ntu.sc6103.booking.core;

import java.util.Objects;

/**
 * Booking: 表示一次预订
 * - confirmationId: 全局唯一 id (int)
 * - facilityName: 设施名称（冗余，便于查找/调试）
 * - startMinute, endMinute: 周内分钟偏移 [0, 7*24*60)
 *
 * 约定：区间以 [startMinute, endMinute) 表示（左闭右开）
 */
public class Booking {
    private final int confirmationId;
    private final String facilityName;
    private int startMinute; // inclusive
    private int endMinute;   // exclusive

    public Booking(int confirmationId, String facilityName, int startMinute, int endMinute) {
        if (startMinute < 0 || endMinute <= startMinute || endMinute > 7 * 24 * 60)
            throw new IllegalArgumentException("Invalid booking time");
        this.confirmationId = confirmationId;
        this.facilityName = Objects.requireNonNull(facilityName);
        this.startMinute = startMinute;
        this.endMinute = endMinute;
    }

    public int getConfirmationId() { return confirmationId; }
    public String getFacilityName() { return facilityName; }

    public int getStartMinute() { return startMinute; }
    public int getEndMinute() { return endMinute; }

    public void shiftByMinutes(int offset) {
        int newStart = startMinute + offset;
        int newEnd = endMinute + offset;
        if (newStart < 0 || newEnd > 7 * 24 * 60 || newEnd <= newStart)
            throw new IllegalArgumentException("Shift leads to invalid time window");
        this.startMinute = newStart;
        this.endMinute = newEnd;
    }

    @Override
    public String toString() {
        return "Booking{id=" + confirmationId +
                ", facility='" + facilityName + '\'' +
                ", start=" + startMinute +
                ", end=" + endMinute + '}';
    }
}
