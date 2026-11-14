package com.firstrow.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Demo only: every payment succeeds instantly. No money moves. */
@Component
public class MockPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(MockPaymentGateway.class);

    @Override
    public PaymentResult charge(long userId, long amountPaise, String idempotencyKey) {
        PaymentResult result = new PaymentResult("mock_" + UUID.randomUUID());
        log.info("payment.mock_charged userId={} amountPaise={} reference={}", userId, amountPaise, result.reference());
        return result;
    }
}
