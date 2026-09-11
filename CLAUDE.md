# UCPAdaptor — Claude Session Guide

## What this project is

A Spring Boot app that serves as a **UCP (Universal Commerce Protocol) MCP server**. It has two purposes:

1. **Reusable onboarding asset** — any client sets env vars and has a UCP-compliant MCP server running against their product catalog in under 5 minutes, zero code changes.
2. **Agentic Commerce demo** — a browser chat UI at `/demo` where Google Gemini uses UCP tools (search, cart) live, making the value of UCP tangible to stakeholders.

UCP version: `2026-08-25` (constant in `ucp/UcpVersion.java`).

---

## Tech stack

- **Java 17**, Spring Boot 4.1.1, Spring AI 2.0.1
- **MCP server**: `spring-ai-starter-mcp-server-webmvc` — tools exposed at `/ucp/mcp`
- **Chat model**: Google Gemini via `spring-ai-starter-model-google-genai` (`gemini-3.6-flash`)
- **Lombok** for `@Data` on config classes
- **Jackson** for all JSON building (no string templates for JSON)
- Build: Maven wrapper (`./mvnw`)

---

## Package structure

```
com.accenture.UCPAdaptor/
  UcpAdaptorApplication.java      entry point; @EnableConfigurationProperties(UcpProperties.class)
  McpConfig.java                  registers CatalogSearchTool + CartTool with MCP server AND demo ChatClient

  config/
    UcpProperties.java            @ConfigurationProperties(prefix="ucp") — all externalized config

  catalog/
    CatalogPort.java              interface: search(filter, limit, cursor) + getProduct(id)
    model/
      Product.java                record: id, name, description, categories, priceMinorUnits, currency, inStock
      SearchFilter.java           record: query, categories, priceMin, priceMax
      SearchResult.java           record: products, totalCount, hasNextPage, nextCursor
      CartItem.java               record: productId, productName, quantity, unitPriceMinorUnits, currency
    adapter/
      MockCatalogAdapter.java     @ConditionalOnProperty(backend=mock, matchIfMissing=true) — 6 hardcoded products
      RestCatalogAdapter.java     @ConditionalOnProperty(backend=rest) — generic REST/JSON with field mapping

  tools/
    CatalogSearchTool.java        @Tool search_catalog; implements UcpCapabilityContributor
    CartTool.java                 @Tool cart_create/add_item/get/remove_item; in-memory ConcurrentHashMap

  ucp/
    UcpVersion.java               CURRENT = "2026-08-25" — single source of truth
    UcpCapabilityContributor.java interface: getCapabilityId(), getSpecUrl(), getSchemaUrl()
    UcpManifestBuilder.java       collects all UcpCapabilityContributor beans; builds /.well-known/ucp JSON

  web/
    UcpAdaptorController.java     GET / (storefront) + GET /.well-known/ucp (dynamic manifest)
    DemoController.java           POST /api/demo/chat — SSE endpoint; Gemini + tools
    DemoPageController.java       GET /demo → forward:/demo.html
    DemoEventEmitter.java         singleton; AtomicReference<SseEmitter>; tools call emit() during execution

src/main/resources/
  application.properties          all config with comments
  static/demo.html                self-contained chat UI; no CDN dependencies
```

---

## Running the app

```bash
# Minimum (MCP server + storefront only — no demo chat):
./mvnw spring-boot:run

# With Gemini demo chat:
export GOOGLE_API_KEY=AIza...
./mvnw spring-boot:run

# Via Docker:
export GOOGLE_API_KEY=AIza...
docker compose up --build
```

**Key endpoints:**
- `http://localhost:8080/` — product storefront
- `http://localhost:8080/demo` — Agentic Commerce chat UI
- `http://localhost:8080/.well-known/ucp` — UCP discovery manifest (dynamic)
- `http://localhost:8080/ucp/mcp` — MCP server (for external UCP clients)

---

## Environment variables

| Variable | Default | Purpose |
|---|---|---|
| `GOOGLE_API_KEY` | (empty) | Google AI Studio key for Gemini; get from aistudio.google.com |
| `UCP_STORE_NAME` | `UCP Demo Store` | Store name shown in storefront header |
| `UCP_BASE_URL` | (auto) | Override the MCP endpoint URL in the manifest; blank = derive from request Host |
| `UCP_CATALOG_BACKEND` | `mock` | `mock` or `rest` |
| `UCP_CATALOG_REST_PRODUCTS_URL` | — | REST adapter: URL to GET product list |
| `UCP_CATALOG_REST_AUTH_HEADER_NAME` | — | e.g. `Authorization` |
| `UCP_CATALOG_REST_AUTH_HEADER_VALUE` | — | e.g. `Bearer your-token` |
| `UCP_CATALOG_REST_FIELD_MAPPING_*` | `id/name/description/price/currency/categories` | Dot-notation field paths into the API JSON |

Spring Boot's relaxed binding maps `UCP_CATALOG_BACKEND` → `ucp.catalog.backend` automatically.

---

## Key architectural decisions (do not change without understanding)

### DemoController injects ChatModel directly, not ChatClient.Builder
`ChatClient.Builder` (the Spring-managed bean) is pre-wired by `ChatClientBuilderConfigurer`, which picks up the `ToolCallbackProvider` from `McpConfig`. Using `ChatClient.builder(chatModel)` bypasses this and gives a clean builder. Adding `.defaultTools()` on top of the pre-wired builder caused a "Multiple tools with the same name" error.

### DemoEventEmitter is a singleton, not @RequestScope
`@RequestScope` ties the bean to the HTTP request thread via `ThreadLocal`. The demo chat runs in a background thread (`demo-chat`) that has no request context. The singleton with `AtomicReference<SseEmitter>` avoids this. Acceptable for a single-user demo; if concurrent sessions matter, use a session-keyed map.

### Tools use .call() not .stream() in DemoController
Spring AI 2.0.1 streaming with Gemini tool calls does not reliably loop back after tool execution to deliver the final text. The blocking `.call()` path handles the full tool-call → tool-result → final-response cycle correctly.

### UcpCapabilityContributor on each tool
Each tool owns its capability metadata (ID, spec URL, schema URL). `UcpManifestBuilder` collects all `UcpCapabilityContributor` beans via Spring injection — adding a new tool automatically appears in `/.well-known/ucp` with zero changes to `UcpManifestBuilder`.

### Cursor-based pagination = integer offset encoded as string
Preserves the existing API contract. Opaque to UCP clients. MockCatalogAdapter and RestCatalogAdapter both parse it as an integer offset.

---

## Adding a new UCP tool

1. Create `tools/MyNewTool.java` — `@Component`, implement `UcpCapabilityContributor`
2. Add `@Tool` methods with `@ToolParam` annotations
3. In `McpConfig.catalogToolCallbackProvider()`, add `myNewTool` to `.toolObjects(...)`
4. In `DemoController` constructor, add `myNewTool` to `.defaultTools(...)`
5. Tool automatically appears in `/.well-known/ucp` — no other changes needed

---

## Adding a new catalog backend

1. Create `catalog/adapter/MyAdapter.java` — `@Component @ConditionalOnProperty(name="ucp.catalog.backend", havingValue="myadapter")`
2. Implement `CatalogPort`: `search()` and `getProduct()`
3. Set `UCP_CATALOG_BACKEND=myadapter` (or `ucp.catalog.backend=myadapter` in properties)
4. No other changes — Spring injects the active `CatalogPort` implementation everywhere

---

## Logging conventions

All request-flow logs use step-numbered prefixes so they can be correlated across classes:

```
[STARTUP]   DemoController bean initialization
[STEP 1]    DemoController  — HTTP request received
[STEP 2]    DemoController  — SSE emitter created
[STEP 3]    DemoController  — Gemini call dispatched
[STEP 5]    CatalogSearchTool / CartTool — Spring AI invoked the tool
[STEP 6]    CatalogSearchTool / CartTool — SSE tool_use event emitted
[STEP 7]    CatalogSearchTool / MockCatalogAdapter / CartTool — data work
[STEP 8]    CatalogSearchTool / CartTool — UCP JSON built, SSE tool_result emitted
[STEP 9]    CatalogSearchTool — result returned to Spring AI / Gemini
[STEP 10]   DemoController  — Gemini final response received
[STEP 11]   DemoController  — SSE text + done events sent
```

Steps 4 (Gemini decides to call a tool) and 9 part 2 (Spring AI forwards to Gemini) happen remotely and are not logged.

---

## Known gotchas

- **Gemini model deprecation**: `gemini-2.0-flash` was retired; current model is `gemini-3.6-flash` in `application.properties`. Check for deprecation notices if the demo stops working.
- **Java version**: pom.xml targets Java 17 (downgraded from 21 because the system JDK is 17). The Dockerfile still uses Java 21 JRE — this is intentional for production but means you cannot use Java 21 APIs in source code.
- **Test properties**: `src/test/resources/application.properties` has `spring.ai.google.genai.api-key=test-placeholder-key` so the Spring context loads without a real API key in CI.
- **Cart is in-memory**: `CartTool` uses `ConcurrentHashMap`. Carts are lost on restart. Sufficient for demo; swap `CatalogPort` for a persistence layer if needed.
- **UCP manifest endpoint auto-detection**: `UcpManifestBuilder` derives the MCP endpoint URL from `HttpServletRequest`. Behind a reverse proxy, set `UCP_BASE_URL` to the public URL or Spring will return the internal host.
