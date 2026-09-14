package com.accenture.UCPAdaptor.web;

import com.accenture.UCPAdaptor.tools.CartTool;
import com.accenture.UCPAdaptor.tools.CatalogSearchTool;
import com.accenture.UCPAdaptor.tools.CheckoutTool;
import com.accenture.UCPAdaptor.ucp.ResolvedProfile;
import com.accenture.UCPAdaptor.ucp.UcpCapabilityContributor;
import com.accenture.UCPAdaptor.ucp.UcpProfileResolver;
import com.accenture.UCPAdaptor.ucp.UcpVersion;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/demo")
public class DemoController {

    private static final Logger log = LoggerFactory.getLogger(DemoController.class);

    private static final Pattern PROFILE_PATTERN =
        Pattern.compile("profile\\s*=\\s*\"([^\"]+)\"");

    private static final String SYSTEM_PROMPT =
        "You are a helpful shopping assistant for a store powered by UCP (Universal Commerce Protocol). " +
        "Help users find products, add them to their cart, and check out. " +
        "Use the search_catalog tool to search for products, the cart tools to manage the cart, " +
        "and the checkout tools to complete a purchase. " +
        "Always use the tools — never make up product information.";

    private final ChatClient chatClient;
    private final DemoEventEmitter demoEventEmitter;
    private final List<UcpCapabilityContributor> contributors;
    private final UcpProfileResolver profileResolver;

    public DemoController(ChatModel chatModel,
                          CatalogSearchTool catalogSearchTool,
                          CartTool cartTool,
                          CheckoutTool checkoutTool,
                          DemoEventEmitter demoEventEmitter,
                          List<UcpCapabilityContributor> contributors,
                          UcpProfileResolver profileResolver) {
        MessageChatMemoryAdvisor memoryAdvisor = MessageChatMemoryAdvisor.builder(
            MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(40)
                .build()
        ).build();
        this.chatClient = ChatClient.builder(chatModel)
            .defaultSystem(SYSTEM_PROMPT)
            .defaultTools(catalogSearchTool, cartTool, checkoutTool)
            .defaultAdvisors(memoryAdvisor)
            .build();
        this.demoEventEmitter = demoEventEmitter;
        this.contributors = contributors;
        this.profileResolver = profileResolver;
        log.info("[STARTUP] DemoController ready — tools: search_catalog, cart_*, checkout_*");
    }

    @PostMapping("/chat")
    public SseEmitter chat(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String message = body.getOrDefault("message", "");
        String conversationId = body.getOrDefault("conversationId", "default");
        String ucpAgentHeader = request.getHeader("UCP-Agent");
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("[STEP 1] Browser → POST /api/demo/chat | conversationId={} | message: '{}' | UCP-Agent: {}",
            conversationId, message, ucpAgentHeader != null ? ucpAgentHeader : "(none)");

        SseEmitter emitter = new SseEmitter(120_000L);
        demoEventEmitter.setEmitter(emitter);
        log.info("[STEP 2] SSE emitter created — HTTP connection held open for streaming");

        new Thread(() -> {
            try {
                // ── UCP DISCOVERY (Step 2a) ──────────────────────────────────────────
                List<String> storeCapabilities = contributors.stream()
                    .map(UcpCapabilityContributor::getCapabilityId)
                    .collect(Collectors.toList());

                String discoveryJson = buildDiscoveryJson(storeCapabilities);
                demoEventEmitter.emit("ucp_discovery", discoveryJson);
                log.info("[STEP 2a] UCP Discovery — store advertises {} capability(-ies): {}",
                    storeCapabilities.size(), storeCapabilities);

                // ── CAPABILITY INTERSECTION (Step 2b) ────────────────────────────────
                String intersectionJson;
                if (ucpAgentHeader != null && !ucpAgentHeader.isBlank()) {
                    intersectionJson = resolveRealIntersection(ucpAgentHeader, storeCapabilities);
                } else {
                    intersectionJson = buildDirectCallIntersectionJson(storeCapabilities);
                }
                demoEventEmitter.emit("ucp_intersection", intersectionJson);

                // ── GEMINI CALL (Steps 3–10) ─────────────────────────────────────────
                log.info("[STEP 3] Calling Gemini (gemini-3.6-flash) with tools: search_catalog, cart_*, checkout_*");

                ChatResponse response = chatClient.prompt()
                    .user(message)
                    .advisors(a -> a.param("chat_memory_conversation_id", conversationId))
                    .call()
                    .chatResponse();

                String responseText = null;
                if (response != null && response.getResult() != null
                        && response.getResult().getOutput() != null) {
                    responseText = response.getResult().getOutput().getText();
                }

                log.info("[STEP 10] Gemini final response received — {} chars",
                    responseText == null ? "null" : responseText.length());

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

    private String resolveRealIntersection(String ucpAgentHeader, List<String> storeCapabilities) {
        String profileUrl = parseProfileUrl(ucpAgentHeader);
        if (profileUrl == null) {
            log.warn("[STEP 2b] UCP-Agent header present but no profile= URL found — skipping intersection");
            return buildDirectCallIntersectionJson(storeCapabilities);
        }
        try {
            ResolvedProfile profile = profileResolver.resolveProfile(profileUrl);
            List<String> intersection = profileResolver.getIntersection(profile, contributors);
            List<String> agentOnly = profile.capabilities().stream()
                .filter(c -> !storeCapabilities.contains(c))
                .collect(Collectors.toList());
            log.info("[STEP 2b] Real intersection — {} matched, {} agent-only: {}", intersection.size(), agentOnly.size(), agentOnly);
            return "{" +
                "\"source\":\"profile\"," +
                "\"profileUrl\":\"" + profileUrl + "\"," +
                "\"agentVersion\":\"" + profile.ucpVersion() + "\"," +
                "\"agentCapabilities\":" + toJsonArray(profile.capabilities()) + "," +
                "\"storeCapabilities\":" + toJsonArray(storeCapabilities) + "," +
                "\"intersection\":" + toJsonArray(intersection) + "," +
                "\"notAvailable\":" + toJsonArray(agentOnly) + "," +
                "\"result\":\"Agent will use " + intersection.size() + " of " + profile.capabilities().size() + " supported capabilities\"" +
                "}";
        } catch (IllegalArgumentException e) {
            log.warn("[STEP 2b] Profile resolution failed: {}", e.getMessage());
            return "{\"source\":\"profile\",\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    private String buildDirectCallIntersectionJson(List<String> storeCapabilities) {
        log.info("[STEP 2b] No UCP-Agent header — direct browser call; showing store capabilities");
        return "{" +
            "\"source\":\"direct\"," +
            "\"note\":\"No UCP-Agent header — this is a direct browser call, not a UCP agent session\"," +
            "\"storeCapabilities\":" + toJsonArray(storeCapabilities) + "," +
            "\"intersection\":" + toJsonArray(storeCapabilities) + "," +
            "\"result\":\"All " + storeCapabilities.size() + " store capabilities available\"" +
            "}";
    }

    private String buildDiscoveryJson(List<String> storeCapabilities) {
        StringBuilder caps = new StringBuilder();
        for (int i = 0; i < storeCapabilities.size(); i++) {
            if (i > 0) caps.append(",");
            caps.append("\"").append(storeCapabilities.get(i)).append("\"");
        }
        return "{" +
            "\"endpoint\":\"/.well-known/ucp\"," +
            "\"ucpVersion\":\"" + UcpVersion.CURRENT + "\"," +
            "\"mcpEndpoint\":\"/ucp/mcp\"," +
            "\"storeCapabilities\":[" + caps + "]" +
            "}";
    }

    private String parseProfileUrl(String headerValue) {
        Matcher m = PROFILE_PATTERN.matcher(headerValue);
        return m.find() ? m.group(1) : null;
    }

    private String toJsonArray(List<String> items) {
        return "[" + items.stream().map(s -> "\"" + s + "\"").collect(Collectors.joining(",")) + "]";
    }
}
