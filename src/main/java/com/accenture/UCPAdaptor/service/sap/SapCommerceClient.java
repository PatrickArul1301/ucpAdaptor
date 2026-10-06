package com.accenture.UCPAdaptor.service.sap;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.apache.hc.core5.ssl.SSLContexts;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

@Component
public class SapCommerceClient {

    private final RestClient restClient;
    private final String baseUrl;

    public SapCommerceClient(SapCommerceConfig config) throws Exception {
        this.baseUrl = config.getBaseUrl();
        String apiBase = config.getBaseUrl() + "/occ/v2/" + config.getBaseSite();

        if (!config.isSslVerify()) {
            SSLContext sslCtx = SSLContext.getInstance("TLS");
            sslCtx.init(null, new TrustManager[]{new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                public void checkClientTrusted(X509Certificate[] c, String t) {}
                public void checkServerTrusted(X509Certificate[] c, String t) {}
            }}, new SecureRandom());

            CloseableHttpClient httpClient = HttpClients.custom()
                    .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                            .setSSLSocketFactory(SSLConnectionSocketFactoryBuilder.create()
                                    .setSslContext(sslCtx)
                                    .setHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                                    .build())
                            .build())
                    .build();

            this.restClient = RestClient.builder()
                    .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
                    .baseUrl(apiBase)
                    .build();
        } else {
            this.restClient = RestClient.builder().baseUrl(apiBase).build();
        }
    }

    public RestClient get() {
        return restClient;
    }

    public String absoluteUrl(String relativePath) {
        if (relativePath == null) return null;
        if (relativePath.startsWith("http")) return relativePath;
        return baseUrl + relativePath;
    }
}
