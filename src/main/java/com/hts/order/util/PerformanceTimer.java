package com.hts.order.util;

public final class PerformanceTimer {
    private final long start = System.nanoTime();

    /**
     * 시작 시점부터 현재까지 경과 시간을 밀리초(ms)로 반환
     */
    public long stopMillis() {
        return (System.nanoTime() - start) / 1_000_000;
    }
}
