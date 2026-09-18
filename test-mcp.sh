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

echo ""
echo "=== 6. Get product details: SM-S938UZBAVZW ==="
curl -s -X POST "$BASE" "${HEADERS[@]}" -H "Mcp-Session-Id: $SESSION_ID" \
  -d '{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"get_product_details","arguments":{"productCode":"SM-S938UZBAVZW"}}}' \
  | python3 -c "
import sys, json
raw = sys.stdin.read()
data = json.loads(raw.split('data:')[-1])
result = json.loads(data['result']['content'][0]['text'])
if 'error' in result:
    print('  ERROR:', result['error'])
else:
    p = result.get('product', {})
    print('  Name      :', p.get('name', 'N/A'))
    print('  Code      :', p.get('code', 'N/A'))
    print('  Summary   :', str(p.get('summary', p.get('description', 'N/A')))[:120])
    prices = p.get('price', {})
    print('  Price     :', prices.get('formattedValue', 'N/A'))
    print('  UCP cap   :', result.get('ucp', {}).get('capability', 'N/A'))
"