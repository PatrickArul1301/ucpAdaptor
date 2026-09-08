#!/bin/bash
BASE="http://localhost:8080/ucp/mcp"
HEADERS=('-H' 'Content-Type: application/json' '-H' 'Accept: text/event-stream, application/json')

echo "=== 1. Initialize & get session ID ==="
INIT_RESPONSE=$(curl -s -X POST "$BASE" "${HEADERS[@]}" -D /tmp/mcp-headers.txt \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","clientInfo":{"name":"test","version":"1.0"},"capabilities":{}}}')

SESSION_ID=$(grep -i "mcp-session-id" /tmp/mcp-headers.txt | awk '{print $2}' | tr -d '\r')
echo "Session ID: $SESSION_ID"

echo ""
echo "=== 2. List tools ==="
curl -s -X POST "$BASE" "${HEADERS[@]}" -H "Mcp-Session-Id: $SESSION_ID" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' \
  | grep -o '"name":"[^"]*"' | head -5

echo ""
echo "=== 3. Search: query=headphones ==="
curl -s -X POST "$BASE" "${HEADERS[@]}" -H "Mcp-Session-Id: $SESSION_ID" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"search_catalog","arguments":{"query":"headphones"}}}' \
  | python3 -c "import sys,json; d=json.loads(sys.stdin.read().split('data:')[-1]); print(json.dumps(json.loads(d['result']['content'][0]['text']), indent=2))"

echo ""
echo "=== 4. Search: category=electronics, price max=\$100 (10000 cents) ==="
curl -s -X POST "$BASE" "${HEADERS[@]}" -H "Mcp-Session-Id: $SESSION_ID" \
  -d '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"search_catalog","arguments":{"filters":{"categories":["electronics"],"price":{"max":10000}}}}}' \
  | python3 -c "import sys,json; d=json.loads(sys.stdin.read().split('data:')[-1]); print(json.dumps(json.loads(d['result']['content'][0]['text']), indent=2))"

echo ""
echo "=== 5. Search: all products (no filters) ==="
curl -s -X POST "$BASE" "${HEADERS[@]}" -H "Mcp-Session-Id: $SESSION_ID" \
  -d '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"search_catalog","arguments":{}}}' \
  | python3 -c "import sys,json; d=json.loads(sys.stdin.read().split('data:')[-1]); [print(f\"  {p['name']} - \${p['price']['amount']/100:.2f}\") for p in json.loads(d['result']['content'][0]['text'])['products']]"