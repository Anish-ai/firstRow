package com.firstrow.payment;

/**
 * Where a real payment provider (Razorpay, Stripe...) would plug in. Today only {@link MockPaymentGateway} exists.
 * The idempotency key is passed through because real providers accept one too, so a retried charge
 * is never taken twice.
 */
public interface PaymentGateway {

    PaymentResult charge(long userId, long amountPaise, String idempotencyKey);

    record PaymentResult(String reference) {
    }
}
