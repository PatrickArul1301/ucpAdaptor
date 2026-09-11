package com.accenture.UCPAdaptor.web;

import com.accenture.UCPAdaptor.tools.CartTool;
import com.accenture.UCPAdaptor.tools.CatalogSearchTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@RestController
@RequestMapping("/api/demo")
public class DemoController {

    private static final Logger log = LoggerFactory.getLogger(DemoController.class);

    private static final String SYSTEM_PROMPT =
        "You are a helpful shopping assistant for a store powered by UCP (Universal Commerce Protocol). " +
        "Help users find products, add them to their cart, and check out. " +
        "Use the search_catalog tool to search for products and the cart tools to manage the cart. " +
        "Always use the tools — never make up product information.";

    private final ChatClient chatClient;
    private final DemoEventEmitter demoEventEmitter;

    public DemoController(ChatModel chatModel,
                          CatalogSearchTool catalogSearchTool,
                          CartTool cartTool,
                          DemoEventEmitter demoEventEmitter) {
        this.chatClient = ChatClient.builder(chatModel)
            .defaultSystem(SYSTEM_PROMPT)
            .defaultTools(catalogSearchTool, cartTool)
            .build();
        this.demoEventEmitter = demoEventEmitter;
        log.info("[STARTUP] DemoController ready — tools registered: search_catalog, cart_create, cart_add_item, cart_get, cart_remove_item");
    }

    @PostMapping("/chat")
    public SseEmitter chat(@RequestBody Map<String, String> body) {
        String message = body.getOrDefault("message", "");
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("[STEP 1] Browser → POST /api/demo/chat | message: '{}'", message);

        SseEmitter emitter = new SseEmitter(120_000L);
        demoEventEmitter.setEmitter(emitter);
        log.info("[STEP 2] SSE emitter created — HTTP connection held open for streaming");

        new Thread(() -> {
            try {
                log.info("[STEP 3] Calling Gemini (gemini-3.6-flash) with tools: search_catalog, cart_*");

                ChatResponse response = chatClient.prompt()
                    .user(message)
                    .call()
                    .chatResponse();

                // Steps 4–9 happen inside Gemini + CatalogSearchTool/CartTool (see their logs)

                String responseText = null;
                if (response != null && response.getResult() != null
                        && response.getResult().getOutput() != null) {
                    responseText = response.getResult().getOutput().getText();
                }

                log.info("[STEP 10] Gemini final response received — {} chars", responseText == null ? "null" : responseText.length());

                if (responseText != null && !responseText.isEmpty()) {
                    String safe = responseText
                        .replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\n", "\\n")
                        .replace("\r", "\\r");
                    emitter.send(SseEmitter.event().name("text").data("{\"chunk\":\"" + safe + "\"}"));
                    log.info("[STEP 11] SSE 'text' event sent → browser renders assistant bubble");
                } else {
                    log.warn("[STEP 10] Model returned empty content");
                    emitter.send(SseEmitter.event().name("text")
                        .data("{\"chunk\":\"(the model returned no text — check server logs)\"}"));
                }

                emitter.send(SseEmitter.event().name("done").data("{}"));
                emitter.complete();
                log.info("[STEP 11] SSE 'done' event sent → browser removes streaming cursor");
                log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

            } catch (Exception e) {
                log.error("[STEP 3/10] Error during LLM call: {}", e.getMessage(), e);
                try {
                    String msg = (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())
                        .replace("\"", "'").replace("\\", "/");
                    emitter.send(SseEmitter.event().name("text").data("{\"chunk\":\"Error: " + msg + "\"}"));
                    emitter.send(SseEmitter.event().name("done").data("{}"));
                    emitter.complete();
                } catch (Exception ignored) {
                    emitter.completeWithError(e);
                }
            } finally {
                demoEventEmitter.clear();
            }
        }, "demo-chat").start();

        return emitter;
    }
}
