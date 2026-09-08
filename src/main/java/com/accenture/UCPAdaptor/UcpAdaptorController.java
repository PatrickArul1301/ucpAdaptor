package com.accenture.UCPAdaptor;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
public class UcpAdaptorController {

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
