package com.accenture.UCPAdaptor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Set;

/**
 * Translates UCP direct method calls (e.g. method:"search_catalog") into
 * standard MCP tools/call requests so Spring AI's transport can handle them.
 *
 * UCP clients call capabilities as direct JSON-RPC methods with params wrapped
 * under a "catalog" key plus a "meta" envelope. This filter strips "meta" and
 * rewrites the request as a tools/call before it reaches the MCP transport.
 */
@Component
public class UcpMcpFilter extends OncePerRequestFilter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> STANDARD_MCP_METHODS = Set.of(
        "initialize", "tools/list", "tools/call",
        "resources/list", "resources/read",
        "prompts/list", "prompts/get",
        "ping", "completions/complete",
        "notifications/initialized", "notifications/cancelled"
    );

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().endsWith("/ucp/mcp")
            || !"POST".equals(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        byte[] bodyBytes = request.getInputStream().readAllBytes();

        try {
            JsonNode root = MAPPER.readTree(bodyBytes);
            String method = root.path("method").asText("");

            if (!STANDARD_MCP_METHODS.contains(method) && !method.isBlank()) {
                ObjectNode rewritten = MAPPER.createObjectNode();
                if (root.has("jsonrpc")) rewritten.set("jsonrpc", root.get("jsonrpc"));
                if (root.has("id"))      rewritten.set("id",      root.get("id"));
                rewritten.put("method", "tools/call");

                ObjectNode params = MAPPER.createObjectNode();
                params.put("name", method);

                // UCP wraps catalog-specific args under a "catalog" key alongside "meta".
                // Strip "meta" and pass the rest (including the "catalog" key) as arguments —
                // the tool schema expects { catalog: { query, filters, pagination } }.
                JsonNode originalParams = root.path("params");
                if (!originalParams.isMissingNode() && originalParams.isObject()) {
                    ObjectNode args = (ObjectNode) originalParams.deepCopy();
                    args.remove("meta");
                    params.set("arguments", args);
                } else {
                    params.set("arguments", MAPPER.createObjectNode());
                }

                rewritten.set("params", params);
                bodyBytes = MAPPER.writeValueAsBytes(rewritten);
            }
        } catch (Exception ignored) {
            // parsing failed — pass original bytes through unchanged
        }

        chain.doFilter(new BodyReplacingRequestWrapper(request, bodyBytes), response);
    }

    private static class BodyReplacingRequestWrapper extends HttpServletRequestWrapper {
        private final byte[] body;
        private final String contentLength;

        BodyReplacingRequestWrapper(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
            this.contentLength = String.valueOf(body.length);
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream bais = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public boolean isFinished() { return bais.available() == 0; }
                @Override public boolean isReady()    { return true; }
                @Override public void setReadListener(ReadListener rl) {}
                @Override public int read()           { return bais.read(); }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(body), StandardCharsets.UTF_8));
        }

        @Override public int getContentLength()      { return body.length; }
        @Override public long getContentLengthLong() { return body.length; }

        // Ensure Content-Length header also reflects the rewritten body size
        @Override
        public String getHeader(String name) {
            if ("content-length".equalsIgnoreCase(name)) return contentLength;
            return super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if ("content-length".equalsIgnoreCase(name))
                return Collections.enumeration(Collections.singletonList(contentLength));
            return super.getHeaders(name);
        }

        @Override
        public int getIntHeader(String name) {
            if ("content-length".equalsIgnoreCase(name)) return body.length;
            return super.getIntHeader(name);
        }
    }
}
