package com.accenture.UCPAdaptor;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UcpAdaptorController {

    @GetMapping(value = "/.well-known/ucp", produces = MediaType.APPLICATION_JSON_VALUE)
    public String getUCPSupport(){
        return """
                {
                          "ucp": {
                            "version": "2026-04-08",
                            "services": {
                              "dev.ucp.shopping": [
                                {
                                  "version": "2026-04-08",
                                  "spec": "https://ucp.dev/2026-04-08/specification/overview",
                                  "transport": "mcp",
                                  "endpoint": "https://stunning-cobbler-2b2a26.netlify.app/ucp/mcp",
                                  "schema": "https://ucp.dev/2026-04-08/services/shopping/mcp.openrpc.json"
                                }
                              ]
                            },
                            "capabilities": {
                              "dev.ucp.shopping.catalog": [
                                {
                                  "version": "2026-04-08",
                                  "spec": "https://ucp.dev/2026-04-08/specification/catalog",
                                  "schema": "https://ucp.dev/2026-04-08/schemas/shopping/catalog.json"
                                }
                              ]
                            }
                          },
                          "signing_keys": [
                            {
                              "kid": "abc-key-2026",
                              "kty": "EC",
                              "crv": "P-256",
                              "x": "REPLACE_WITH_YOUR_PUBLIC_KEY_X",
                              "y": "REPLACE_WITH_YOUR_PUBLIC_KEY_Y",
                              "use": "sig",
                              "alg": "ES256"
                            }
                          ]
                        }
                """;
    }
}
