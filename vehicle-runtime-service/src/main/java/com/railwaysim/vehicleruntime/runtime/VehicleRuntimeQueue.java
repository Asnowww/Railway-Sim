package com.railwaysim.vehicleruntime.runtime;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.Supplier;

/**
 * 每个队列容量固定为 1，用于显式拒绝堆积的同步 tick。
 */
final class VehicleRuntimeQueue {

    private final ArrayBlockingQueue<Long> queue;

    VehicleRuntimeQueue(int capacity) {
        this.queue = new ArrayBlockingQueue<>(Math.max(1, capacity));
    }

    <T> T execute(long tick, Supplier<T> supplier) {
        if (!queue.offer(tick)) {
            throw new QueueRejectedException("QUEUE_BUSY");
        }
        try {
            return supplier.get();
        } finally {
            queue.remove(tick);
        }
    }

    static final class QueueRejectedException extends RuntimeException {
        QueueRejectedException(String message) {
            super(message);
        }
    }
}
