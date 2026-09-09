package com.accenture.UCPAdaptor;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
public class UcpAdaptorController {

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    public String home() {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1.0">
                  <title>UCP Adaptor Store</title>
                  <style>
                    * { box-sizing: border-box; margin: 0; padding: 0; }
                    body { font-family: Arial, sans-serif; background: #f4f6f8; color: #2c3e50; }
                    header { background: #2c3e50; color: white; padding: 24px 32px; text-align: center; }
                    header h1 { font-size: 1.8rem; margin-bottom: 6px; }
                    header p { color: #bdc3c7; font-size: 0.95rem; }
                    .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 24px; padding: 32px; max-width: 1100px; margin: 0 auto; }
                    .card { background: white; border-radius: 10px; padding: 24px; box-shadow: 0 2px 10px rgba(0,0,0,0.08); display: flex; flex-direction: column; gap: 10px; }
                    .card h2 { font-size: 1rem; color: #2c3e50; }
                    .card p { font-size: 0.88rem; color: #7f8c8d; flex: 1; }
                    .tags { display: flex; gap: 6px; flex-wrap: wrap; }
                    .tag { background: #eaf4fb; color: #2980b9; padding: 3px 10px; border-radius: 12px; font-size: 0.75rem; }
                    .price { font-size: 1.1rem; font-weight: bold; color: #27ae60; }
                    .badge { text-align: center; margin-top: 8px; }
                    .badge span { background: #e8f5e9; color: #27ae60; padding: 4px 12px; border-radius: 20px; font-size: 0.75rem; }
                  </style>
                </head>
                <body>
                  <header>
                    <h1>Welcome to Our Store</h1>
                    <p>Powered by Universal Commerce Protocol (UCP v2026-08-25)</p>
                  </header>
                  <div class="grid">
                    <div class="card">
                      <h2>Wireless Bluetooth Headphones</h2>
                      <p>Premium noise-cancelling headphones with 30-hour battery life</p>
                      <div class="tags"><span class="tag">electronics</span><span class="tag">audio</span></div>
                      <div class="price">$129.99</div>
                      <div class="badge"><span>In Stock</span></div>
                    </div>
                    <div class="card">
                      <h2>Running Shoes</h2>
                      <p>Lightweight breathable running shoes with cushioned sole</p>
                      <div class="tags"><span class="tag">footwear</span><span class="tag">sports</span></div>
                      <div class="price">$89.99</div>
                      <div class="badge"><span>In Stock</span></div>
                    </div>
                    <div class="card">
                      <h2>Coffee Maker</h2>
                      <p>12-cup programmable coffee maker with built-in grinder</p>
                      <div class="tags"><span class="tag">kitchen</span><span class="tag">appliances</span></div>
                      <div class="price">$59.99</div>
                      <div class="badge"><span>In Stock</span></div>
                    </div>
                    <div class="card">
                      <h2>Yoga Mat</h2>
                      <p>Extra thick non-slip yoga mat with carrying strap</p>
                      <div class="tags"><span class="tag">sports</span><span class="tag">fitness</span></div>
                      <div class="price">$34.99</div>
                      <div class="badge"><span>In Stock</span></div>
                    </div>
                    <div class="card">
                      <h2>Laptop Stand</h2>
                      <p>Adjustable aluminum laptop stand for desk ergonomics</p>
                      <div class="tags"><span class="tag">electronics</span><span class="tag">accessories</span></div>
                      <div class="price">$49.99</div>
                      <div class="badge"><span>In Stock</span></div>
                    </div>
                    <div class="card">
                      <h2>Stainless Steel Water Bottle</h2>
                      <p>Insulated 32oz water bottle keeps drinks cold for 24 hours</p>
                      <div class="tags"><span class="tag">outdoor</span><span class="tag">accessories</span></div>
                      <div class="price">$24.99</div>
                      <div class="badge"><span>In Stock</span></div>
                    </div>
                  </div>
                </body>
                </html>
                """;
    }

    @GetMapping(value = "/.well-known/ucp", produces = MediaType.APPLICATION_JSON_VALUE)
    public String getUCPSupport() {
        return """
                {
                  "ucp": {
                    "version": "2026-08-25",
                    "payment_handlers": {},
                    "services": {
                      "dev.ucp.shopping": [
                        {
                          "version": "2026-08-25",
                          "spec": "https://ucp.dev/2026-08-25/specification/overview",
                          "transport": "mcp",
                          "endpoint": "https://ucpadaptor.onrender.com/ucp/mcp",
                          "schema": "https://ucp.dev/2026-08-25/services/shopping/mcp.openrpc.json"
                        }
                      ]
                    },
                    "capabilities": {
                
                      "dev.ucp.shopping.catalog.search": [
                        {
                          "version": "2026-08-25",
                          "spec": "https://ucp.dev/2026-08-25/specification/shopping/catalog/search",
                          "schema": "https://ucp.dev/2026-08-25/schemas/shopping/catalog_search.json"
               
                        }
                      ]
                    }
                  },
                  "keys": [
                    {
                      "kid": "ucpadaptor-key-2026",
                      "kty": "EC",
                      "crv": "P-256",
                      "x": "08dxr3fmRRgHgI5AuNfAzgdLLKxs5_V6LssqemQkSHA",
                      "y": "Jg4OMbHECBZF0EaL6lWO8Q4zudRj2lYm6JShabSDaq8",
                      "use": "sig",
                      "alg": "ES256"
                    }
                  ]
                }
                """;
    }

}
