package com.hts.order.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class OrderAmountCalculator {
    private OrderAmountCalculator() {}

    /**
     * 주문 금액 (price * quantity) 계산
     * @param price 주문 가격 (long)
     * @param quantity 주문 수량 (long)
     * @return BigDecimal 형태의 주문 금액
     */
    public static BigDecimal compute(long price, long quantity) {
        // long overflow 방지 및 명확한 BigDecimal 연산 사용
        return BigDecimal.valueOf(price)
                .multiply(BigDecimal.valueOf(quantity))
                .setScale(0, RoundingMode.UNNECESSARY); // 정수 단위 금액 가정
    }
}