package com.accenture.UCPAdaptor.tool;

import com.accenture.UCPAdaptor.service.PaymentService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class PaymentTool {

    private final PaymentService paymentService;
    private final ObjectMapper mapper = new ObjectMapper();

    public PaymentTool(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InitiatePaymentParams(
            @JsonProperty("checkout_id") String checkoutId,
            @JsonProperty("payment_method") String paymentMethod
    ) {}

    @Tool(name = "initiate_payment",
            description = """
              Initiate a payment for a confirmed checkout. Supported mock payment methods: \
              credit_card, paypal, apple_pay. The checkout must be confirmed first via confirm_checkout. \
              Returns a payment_id to use with confirm_payment.
              """)
    public String initiatePayment(
            @ToolParam(description = "{ checkout_id, payment_method: one of credit_card | paypal | apple_pay }", required = true)
            InitiatePaymentParams params) {
        try {
            PaymentService.Payment payment = paymentService.initiatePayment(params.checkoutId(), params.paymentMethod());
            return buildPaymentResponse("initiate_payment", payment);
        } catch (IllegalArgumentException e) {
            return errorResponse(e.getMessage().equals("CHECKOUT_NOT_FOUND")
                    ? "Checkout not found: " + params.checkoutId()
                    : e.getMessage(), e.getMessage());
        } catch (IllegalStateException e) {
            return errorResponse("Checkout must be confirmed before payment can be initiated", "CHECKOUT_NOT_CONFIRMED");
        } catch (Exception e) {
            return errorResponse("Failed to initiate payment: " + e.getMessage(), "INTERNAL_ERROR");
        }
    }

    @Tool(name = "confirm_payment",
            description = "Confirm and process a pending payment. Returns COMPLETED status with a transaction ID.")
    public String confirmPayment(
            @ToolParam(description = "The payment ID returned by initiate_payment", required = true)
            String paymentId) {
        try {
            PaymentService.Payment payment = paymentService.confirmPayment(paymentId);
            if (payment == null) return errorResponse("Payment not found: " + paymentId, "PAYMENT_NOT_FOUND");
            return buildPaymentResponse("confirm_payment", payment);
        } catch (Exception e) {
            return errorResponse("Failed to confirm payment: " + e.getMessage(), "INTERNAL_ERROR");
        }
    }

    @Tool(name = "get_payment_status",
            description = "Get the current status of a payment (PENDING, COMPLETED, or FAILED).")
    public String getPaymentStatus(
            @ToolParam(description = "The payment ID to check", required = true)
            String paymentId) {
        try {
            PaymentService.Payment payment = paymentService.getPaymentStatus(paymentId);
            if (payment == null) return errorResponse("Payment not found: " + paymentId, "PAYMENT_NOT_FOUND");
            return buildPaymentResponse("get_payment_status", payment);
        } catch (Exception e) {
            return errorResponse("Failed to get payment status: " + e.getMessage(), "INTERNAL_ERROR");
        }
    }

    private String buildPaymentResponse(String capability, PaymentService.Payment payment) throws Exception {
        ObjectNode response = mapper.createObjectNode();

        ObjectNode ucp = mapper.createObjectNode();
        ucp.put("capability", "dev.ucp.shopping.payment." + capability);
        ucp.put("version", "2026-08-25");
        response.set("ucp", ucp);

        ObjectNode paymentNode = mapper.createObjectNode();
        paymentNode.put("payment_id", payment.paymentId());
        paymentNode.put("checkout_id", payment.checkoutId());
        paymentNode.put("method", payment.method());
        paymentNode.put("status", payment.status());

        ObjectNode amount = mapper.createObjectNode();
        amount.put("amount", payment.amount());
        amount.put("currency_code", payment.currency());
        paymentNode.set("amount", amount);

        if (payment.transactionId() != null) {
            paymentNode.put("transaction_id", payment.transactionId());
        }

        response.set("payment", paymentNode);
        return mapper.writeValueAsString(response);
    }

    private String errorResponse(String message, String code) {
        ObjectNode node = mapper.createObjectNode();
        node.put("error", message);
        node.put("code", code);
        try {
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            return "{\"error\":\"" + message + "\",\"code\":\"" + code + "\"}";
        }
    }
}
