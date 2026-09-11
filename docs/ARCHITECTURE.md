# UCPAdaptor — Architecture & Component Guide

## Overview

UCPAdaptor is a Spring Boot application that turns any product catalog into a **UCP-compliant commerce service** — discoverable by AI agents and other UCP-aware systems in under 5 minutes of setup. It ships with a live browser demo that shows Google Gemini using the UCP tools in real time.

**UCP** (Universal Commerce Protocol) is an open standard that lets AI agents discover and interact with commerce services — searching products, managing carts, placing orders — through a machine-readable protocol. Think of it as "OpenAPI for commerce, built for AI agents."

---

## System Components

```
┌─────────────────────────────────────────────────────────────────────┐
│                          UCPAdaptor (port 8080)                     │
│                                                                     │
│  ┌─────────────┐   ┌──────────────────┐   ┌────────────────────┐   │
│  │  Storefront │   │   UCP Manifest   │   │   Demo Chat UI     │   │
│  │  GET /      │   │GET /.well-known/ │   │   GET /demo        │   │
│  │             │   │      /ucp        │   │                    │   │
│  └──────┬──────┘   └────────┬─────────┘   └────────┬───────────┘   │
│         │                   │                       │               │
│         ▼                   ▼                       ▼               │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │                    CatalogPort (interface)                    │   │
│  │         MockCatalogAdapter  │  RestCatalogAdapter            │   │
│  └──────────────────────────────────────────────────────────────┘   │
│                                                                     │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │                    MCP Server  /ucp/mcp                       │   │
│  │           CatalogSearchTool  │  CartTool                      │   │
│  └──────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
         ▲                                        ▲
         │  HTTP                                  │  MCP (SSE/HTTP)
    Browser / User                     External AI Agents
                                    (Claude, custom UCP clients)
```

---

## Component Reference

### 1. UCP Discovery Manifest — `/.well-known/ucp`

**Class:** `UcpAdaptorController` + `UcpManifestBuilder`

The entry point for any UCP-aware system. An AI agent or UCP client fetches this URL to discover what capabilities this store offers and how to connect to them.

**Sample response:**
```json
{
  "ucp": {
    "version": "2026-08-25",
    "services": {
      "dev.ucp.shopping": [{
        "transport": "mcp",
        "endpoint": "http://localhost:8080/ucp/mcp"
      }]
    },
    "capabilities": {
      "dev.ucp.shopping.catalog.search": [{ "version": "2026-08-25", ... }],
      "dev.ucp.shopping.cart":           [{ "version": "2026-08-25", ... }]
    }
  },
  "keys": [{ "kid": "ucpadaptor-key-2026", "kty": "EC", ... }]
}
```

`UcpManifestBuilder` collects every bean that implements `UcpCapabilityContributor` (both tools do) and automatically includes them in the manifest. Adding a new tool never requires touching `UcpManifestBuilder`.

---

### 2. MCP Server — `/ucp/mcp`

**Managed by:** Spring AI MCP auto-configuration; tools registered in `McpConfig`

This is the transport layer through which external AI agents call UCP tools. It speaks the **Model Context Protocol** over HTTP streaming. An external agent that has discovered the manifest connects here and can call:

| Tool name | Description |
|---|---|
| `search_catalog` | Search products by text, category, and price range |
| `cart_create` | Create a new shopping cart, returns a cart ID |
| `cart_add_item` | Add a product to a cart by ID |
| `cart_get` | Retrieve cart contents and total |
| `cart_remove_item` | Remove a product from the cart |

---

### 3. CatalogSearchTool — `tools/CatalogSearchTool.java`

The `search_catalog` UCP capability. Receives structured search parameters from an AI model, delegates to `CatalogPort`, wraps the result in a UCP-format JSON envelope, and returns it.

**UCP response envelope:**
```json
{
  "ucp": { "capability": "dev.ucp.shopping.catalog.search", "version": "2026-08-25" },
  "products": [ { "id": "...", "name": "...", "price": { "amount": 12999, "currency": "USD" }, ... } ],
  "pagination": { "total_count": 1, "has_next_page": false }
}
```

---

### 4. CartTool — `tools/CartTool.java`

Four `@Tool` methods implementing the `dev.ucp.shopping.cart` UCP capability. State is kept in an in-memory `ConcurrentHashMap` — sufficient for demo use. Cart IDs are UUIDs.

**UCP cart response envelope:**
```json
{
  "ucp": { "capability": "dev.ucp.shopping.cart", "version": "2026-08-25" },
  "cart": {
    "id": "a1b2c3...",
    "items": [{ "productId": "prod-001", "quantity": 1, "unitPriceMinorUnits": 12999 }],
    "total": { "amount": 12999, "currency": "USD" }
  }
}
```

---

### 5. CatalogPort — `catalog/CatalogPort.java`

An interface with two methods:

```java
SearchResult search(SearchFilter filter, int limit, String cursor);
Optional<Product> getProduct(String id);
```

Spring injects the active implementation at startup based on `ucp.catalog.backend`:

| Backend | Class | When active |
|---|---|---|
| `mock` (default) | `MockCatalogAdapter` | Development and demos |
| `rest` | `RestCatalogAdapter` | Real client integration |

**MockCatalogAdapter** holds 6 hardcoded products and filters them in memory.

**RestCatalogAdapter** calls a client's REST API, applies dot-notation field mapping to handle any JSON schema, and filters/paginates the result. It uses Spring's `RestClient` (synchronous, no Reactor dependency).

---

### 6. UcpProperties — `config/UcpProperties.java`

All externalized configuration, bound via `@ConfigurationProperties(prefix="ucp")`. Key fields:

```
ucp.store-name          Display name for the storefront
ucp.base-url            Override MCP endpoint URL in manifest (blank = auto-detect)
ucp.catalog.backend     "mock" or "rest"
ucp.catalog.rest.*      REST adapter connection and field mapping settings
ucp.jwk-*               EC signing key fields published in the manifest
```

---

### 7. Demo Chat UI — `/demo`

**Classes:** `DemoPageController`, `DemoController`, `DemoEventEmitter`
**Static file:** `src/main/resources/static/demo.html`

A two-panel browser interface:
- **Left panel (35%)** — Tool Trace: shows every tool call and result as collapsible cards in real time
- **Right panel (65%)** — Chat: user messages on the right, Gemini responses on the left

Communication uses **Server-Sent Events (SSE)**. The browser opens a long-lived HTTP connection to `POST /api/demo/chat` and receives events as they happen:

| Event type | When sent | Browser action |
|---|---|---|
| `tool_use` | A tool is about to execute | Renders a call card in Tool Trace |
| `tool_result` | A tool has returned a result | Attaches result to the call card |
| `text` | Gemini has generated its final answer | Appends text to assistant bubble |
| `done` | Stream complete | Removes blinking cursor |

**DemoEventEmitter** is a singleton `@Component` with an `AtomicReference<SseEmitter>`. It is injected into both tools. During a chat request, the controller sets the active emitter; the tools call `emit()` as side effects during their execution. The emitter is cleared when the request completes.

---

### 8. Storefront — `GET /`

**Class:** `UcpAdaptorController`

A simple HTML product grid built dynamically from `CatalogPort.search()`. Prices are formatted from minor units (e.g., 12999 → $129.99). Stock status is read from the `Product.inStock` field.

---

## Product Search Request Flow

This traces a user typing "Find me headphones under $150" in the demo chat.

```
Browser
  │
  │  POST /api/demo/chat  { "message": "Find me headphones under $150" }
  ▼
DemoController.chat()                               [STEP 1, 2]
  │  Creates SseEmitter, sets it on DemoEventEmitter
  │  Spawns background thread "demo-chat"
  │  Returns SseEmitter immediately (HTTP connection stays open)
  │
  ▼  [background thread]
DemoController (thread)                             [STEP 3]
  │  chatClient.prompt().user(message).call().chatResponse()
  │  ChatClient has search_catalog + cart_* tools declared
  │  Sends user message + tool definitions to Gemini API
  │
  ▼  [network: Google Gemini API]
Gemini decides to call search_catalog              [STEP 4 — remote]
  │  Returns function call:
  │  { "name": "search_catalog",
  │    "args": { "query": "headphones", "filters": { "price": { "max": 15000 } } } }
  │
  ▼  [Spring AI intercepts the function call response]
Spring AI ToolCallingManager
  │  Looks up registered ToolCallback named "search_catalog"
  │  Reflectively invokes CatalogSearchTool.searchCatalog()
  │
  ▼
CatalogSearchTool.searchCatalog()                  [STEP 5]
  │  Logs received parameters
  │  Calls DemoEventEmitter.emit("tool_use", {...})
  │    └─► SSE event sent to browser                [STEP 6]
  │         Browser renders ⚡ search_catalog call card
  │
  │  Builds SearchFilter(query="headphones", priceMax=15000)
  │  Calls catalogPort.search(filter, 10, null)
  │
  ▼
MockCatalogAdapter.search()                        [STEP 7]
  │  Streams over 6 hardcoded products:
  │    prod-001 "Wireless Bluetooth Headphones"  12999 ✓ (contains "headphones", price ≤ 15000)
  │    prod-002 "Running Shoes"                   8999 ✗ (no text match)
  │    prod-003 "Coffee Maker"                    5999 ✗ (no text match)
  │    prod-004 "Yoga Mat"                        3499 ✗ (no text match)
  │    prod-005 "Laptop Stand"                    4999 ✗ (no text match)
  │    prod-006 "Stainless Steel Water Bottle"    2499 ✗ (no text match)
  │  Returns SearchResult(products=[prod-001], totalCount=1, hasNextPage=false)
  │
  ▼
CatalogSearchTool (continued)                      [STEP 8]
  │  Builds UCP JSON:
  │  { "ucp": { "capability": "dev.ucp.shopping.catalog.search",
  │             "version": "2026-08-25" },
  │    "products": [{ "id": "prod-001", "name": "Wireless Bluetooth Headphones",
  │                   "price": { "amount": 12999, "currency": "USD" },
  │                   "availability": { "in_stock": true } }],
  │    "pagination": { "total_count": 1, "has_next_page": false } }
  │
  │  Calls DemoEventEmitter.emit("tool_result", {...})
  │    └─► SSE event sent to browser
  │         Browser renders ✓ search_catalog result card
  │
  │  Returns UCP JSON string to Spring AI              [STEP 9]
  │
  ▼  [Spring AI appends tool result to conversation]
Spring AI ToolCallingManager
  │  Sends second request to Gemini with full context:
  │    - Original user message
  │    - Tool call it made
  │    - UCP tool result
  │
  ▼  [network: Google Gemini API]
Gemini generates natural language answer           [STEP 10 — remote]
  │  "Here is a headphone option under $150:
  │   **Wireless Bluetooth Headphones** (ID: prod-001)
  │   **Price:** $129.99  **Availability:** In Stock"
  │
  ▼
DemoController (thread)                            [STEP 10, 11]
  │  response.getResult().getOutput().getText() → 187 chars
  │  Sends SSE event: { event: "text", data: { "chunk": "Here is..." } }
  │    └─► Browser appends text to assistant bubble
  │  Sends SSE event: { event: "done", data: {} }
  │    └─► Browser removes blinking cursor
  │  emitter.complete()
  │  demoEventEmitter.clear()
  │
  ▼
Browser renders final state
  Left panel:  ⚡ search_catalog [call]  ←  click to expand JSON args
               ✓ search_catalog [result] ←  click to expand UCP response
  Right panel: "Here is a headphone option under $150: ..."
```

---

## Configuration Quick Reference

### Switch to a real product catalog (REST adapter)

```bash
export UCP_CATALOG_BACKEND=rest
export UCP_CATALOG_REST_PRODUCTS_URL=https://api.mystore.com/v1/products
export UCP_CATALOG_REST_AUTH_HEADER_NAME=Authorization
export UCP_CATALOG_REST_AUTH_HEADER_VALUE="Bearer sk-..."

# If your API uses different field names:
export UCP_CATALOG_REST_FIELD_MAPPING_PRICE=variants.0.price
export UCP_CATALOG_REST_FIELD_MAPPING_CATEGORIES=product_type

./mvnw spring-boot:run
```

### Deploy behind a reverse proxy

```bash
# Set this so the manifest advertises the public URL, not the internal one:
export UCP_BASE_URL=https://my-store.example.com

./mvnw spring-boot:run
```

### Run with Docker

```bash
export GOOGLE_API_KEY=AIza...
export UCP_STORE_NAME="Acme Corp Store"
docker compose up --build
```

---

## Project File Map

```
ucpAdaptor/
├── CLAUDE.md                          AI session guide
├── docs/
│   └── ARCHITECTURE.md               This file
├── docker-compose.yml                 One-command startup
├── Dockerfile                         Multi-stage build (Maven + JRE)
├── pom.xml                            Spring Boot 4.1.1, Spring AI 2.0.1, Java 17
├── test-mcp.sh                        curl-based MCP smoke tests
└── src/
    ├── main/
    │   ├── java/com/accenture/UCPAdaptor/
    │   │   ├── UcpAdaptorApplication.java
    │   │   ├── McpConfig.java
    │   │   ├── UcpAdaptorController.java
    │   │   ├── catalog/
    │   │   │   ├── CatalogPort.java
    │   │   │   ├── adapter/MockCatalogAdapter.java
    │   │   │   ├── adapter/RestCatalogAdapter.java
    │   │   │   └── model/{Product,SearchFilter,SearchResult,CartItem}.java
    │   │   ├── config/UcpProperties.java
    │   │   ├── tools/{CatalogSearchTool,CartTool}.java
    │   │   ├── ucp/{UcpVersion,UcpCapabilityContributor,UcpManifestBuilder}.java
    │   │   └── web/{DemoController,DemoPageController,DemoEventEmitter}.java
    │   └── resources/
    │       ├── application.properties
    │       └── static/demo.html
    └── test/
        ├── java/.../UcpAdaptorApplicationTests.java
        └── resources/application.properties  (placeholder API key for CI)
```
