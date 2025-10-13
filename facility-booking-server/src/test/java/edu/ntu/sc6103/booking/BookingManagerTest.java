package edu.ntu.sc6103.booking;

import edu.ntu.sc6103.booking.core.BookingManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays; // <-- 新增: 引入 Arrays
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class BookingManagerTest {

    private BookingManager mgr;

    @BeforeEach
    public void setup() {
        mgr = new BookingManager();
        // 确保你的 BookingManager 构造函数或 setup 逻辑中创建了 RoomA
        mgr.createFacilityIfAbsent("RoomA");
    }

    @Test
    public void testBookAndQueryNonOverlap() {
        int id1 = mgr.bookFacility("RoomA", 0, 9, 0, 0, 10, 0); // Mon 9:00-10:00
        int id2 = mgr.bookFacility("RoomA", 0, 10, 0, 0, 11, 0); // Mon 10:00-11:00
        assertNotEquals(id1, id2);

        // Map<Integer, List<int[]>> res = mgr.queryBookings("RoomA", List.of(0)); // Java 9+
        Map<Integer, List<int[]>> res = mgr.queryBookings("RoomA", Arrays.asList(0)); // Java 8 兼容
        assertTrue(res.containsKey(0));
        assertEquals(2, res.get(0).size());
    }

    @Test
    public void testConflictDetection() {
        mgr.bookFacility("RoomA", 0, 9, 0, 0, 11, 0);
        // overlapping
        try {
            mgr.bookFacility("RoomA", 0, 10, 30, 0, 11, 30);
            fail("Expected conflict");
        } catch (IllegalStateException expected) {
        }
    }

    @Test
    public void testChangeBooking() {
        int id = mgr.bookFacility("RoomA", 0, 9, 0, 0, 10, 0);
        mgr.changeBooking(id, 60); // shift +60 mins -> 10:00-11:00

        // Map<Integer, List<int[]>> res = mgr.queryBookings("RoomA", List.of(0)); // Java 9+
        Map<Integer, List<int[]>> res = mgr.queryBookings("RoomA", Arrays.asList(0)); // Java 8 兼容
        assertEquals(1, res.get(0).size());
        int[] iv = res.get(0).get(0);
        assertEquals(60*10, iv[0] / 60 * 60); // basic sanity - we primarily ensure no exception
    }
}