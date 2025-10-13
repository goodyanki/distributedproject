package edu.ntu.sc6103.booking.core;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 简单的全局 ID 生成器（线程安全）
 */
public final class IdGenerator {
    private static final AtomicInteger COUNTER = new AtomicInteger(1);

    private IdGenerator() {}

    public static int nextId() {
        return COUNTER.getAndIncrement();
    }
}
