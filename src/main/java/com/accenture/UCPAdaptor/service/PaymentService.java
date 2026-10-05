package com.accenture.UCPAdaptor.service;

import org.springframework.stereotype.Service;

import java.util.HexFormat;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PaymentService {

    public record Payment(
            String paymentId, String checkoutId,
            String method, String status,
            double amount, String currency,
            String transactionId
    ) {}

    private final ConcurrentHashMap<String, Payment> payments = new ConcurrentHashMap<>();
    private final CheckoutService checkoutService;
    private final Random random = new Random();

    public PaymentService(CheckoutService checkoutService) {
        this.checkoutService = checkoutService;
    }

    public Payment initiatePayment(String checkoutId, String method) {
        CheckoutService.Checkout checkout = checkoutService.getCheckout(checkoutId);
        if (checkout == null) throw new IllegalArgumentException("CHECKOUT_NOT_FOUND");
        if (!"CONFIRMED".equals(checkout.status())) throw new IllegalStateException("CHECKOUT_NOT_CONFIRMED");

        String paymentId = UUID.randomUUID().toString();
        Payment payment = new Payment(paymentId, checkoutId, method, "PENDING",
                checkout.total(), checkout.currency(), null);
        payments.put(paymentId, payment);
        return payment;
    }

    public Payment confirmPayment(String paymentId) {
        Payment existing = payments.get(paymentId);
        if (existing == null) return null;

        byte[] bytes = new byte[4];
        random.nextBytes(bytes);
        String txnId = "TXN-" + HexFormat.of().formatHex(bytes).toUpperCase();

        Payment confirmed = new Payment(
                existing.paymentId(), existing.checkoutId(), existing.method(), "COMPLETED",
                existing.amount(), existing.currency(), txnId);
        payments.put(paymentId, confirmed);
        return confirmed;
    }

    public Payment getPaymentStatus(String paymentId) {
        return payments.get(paymentId);
    }
}
