package com.accenture.UCPAdaptor.tool;

import com.accenture.UCPAdaptor.service.CartService;
import com.accenture.UCPAdaptor.service.OrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class OrderTool {

    private final OrderService orderService;
    private final ObjectMapper mapper = new ObjectMapper();

    public OrderTool(OrderService orderService) {
        this.orderService = orderService;
    }

    @Tool(name = "get_order",
            description = "Retrieve a placed order by ID. Returns order details including line items and totals in minor units (cents).")
    public String getOrder(
            @ToolParam(description = "The order ID returned in the complete_checkout response", required = true)
            String id) {
        try {
            OrderService.Order order = orderService.getOrder(id);
            if (order == null) return errorResponse("Order not found: " + id, "ORDER_NOT_FOUND");
            return buildOrderResponse(order);
        } catch (Exception e) {
            return errorResponse("Failed to get order: " + e.getMessage(), "INTERNAL_ERROR");
        }
    }

    private String buildOrderResponse(OrderService.Order order) throws Exception {
        ObjectNode response = mapper.createObjectNode();

        ObjectNode ucp = mapper.createObjectNode();
        ucp.put("capability", "dev.ucp.shopping.order");
        ucp.put("version", "2026-08-25");
        response.set("ucp", ucp);

        ObjectNode orderNode = mapper.createObjectNode();
        orderNode.put("id", order.id());
        orderNode.put("checkout_id", order.checkoutId());

        ArrayNode items = mapper.createArrayNode();
        for (CartService.CartItem item : order.lineItems()) {
            ObjectNode itemNode = mapper.createObjectNode();
            itemNode.put("product_id", item.productId());
            itemNode.put("product_name", item.productName());
            itemNode.put("unit_price", item.unitPrice());
            itemNode.put("currency", item.currency());
            itemNode.put("quantity", item.quantity());
            if (item.imageUrl() != null && !item.imageUrl().isBlank()) {
                itemNode.put("image_url", item.imageUrl());
            }
            items.add(itemNode);
        }
        orderNode.set("line_items", items);

        ObjectNode totals = mapper.createObjectNode();
        totals.set("subtotal", amountNode(order.subtotal(), order.currency()));
        totals.set("shipping", amountNode(order.shippingCost(), order.currency()));
        totals.set("tax", amountNode(order.tax(), order.currency()));
        totals.set("total", amountNode(order.total(), order.currency()));
        orderNode.set("totals", totals);
        orderNode.put("currency", order.currency());

        response.set("order", orderNode);
        return mapper.writeValueAsString(response);
    }

    private ObjectNode amountNode(long amount, String currency) {
        ObjectNode node = mapper.createObjectNode();
        node.put("amount", amount);
        node.put("currency", currency);
        return node;
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
