package com.accenture.UCPAdaptor.ucp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Component
public class UcpProfileResolver {

    private static final Logger log = LoggerFactory.getLogger(UcpProfileResolver.class);

    private final RestClient restClient;
    private final ObjectMapper mapper;
    private final ConcurrentHashMap<String, ResolvedProfile> cache = new ConcurrentHashMap<>();

    public UcpProfileResolver(ObjectMapper mapper) {
        this.mapper = mapper;
        this.restClient = RestClient.builder().build();
    }

    /**
     * Fetches and caches the agent profile at profileUrl.
     * Throws IllegalArgumentException if the profile's ucpVersion is newer than ours
     * or if the profile cannot be fetched/parsed.
     */
    public ResolvedProfile resolveProfile(String profileUrl) {
        return cache.computeIfAbsent(profileUrl, this::fetchAndValidate);
    }

    public List<String> getIntersection(ResolvedProfile client, List<UcpCapabilityContributor> merchantContributors) {
        Set<String> merchantIds = merchantContributors.stream()
            .map(UcpCapabilityContributor::getCapabilityId)
            .collect(Collectors.toSet());
        return client.capabilities().stream()
            .filter(merchantIds::contains)
            .collect(Collectors.toList());
    }

    private ResolvedProfile fetchAndValidate(String url) {
        log.info("◆ UcpProfileResolver — fetching agent profile: {}", url);
        try {
            String json = restClient.get().uri(url).retrieve().body(String.class);
            JsonNode root = mapper.readTree(json);

            String clientVersion = root.path("ucpVersion").asText(null);
            if (clientVersion == null || clientVersion.isBlank()) {
                throw new IllegalArgumentException("Agent profile missing 'ucpVersion' field at: " + url);
            }

            LocalDate clientDate = LocalDate.parse(clientVersion);
            LocalDate merchantDate = LocalDate.parse(UcpVersion.CURRENT);
            if (clientDate.isAfter(merchantDate)) {
                throw new IllegalArgumentException(
                    "Agent UCP version " + clientVersion + " is newer than merchant version "
                    + UcpVersion.CURRENT + " — upgrade the merchant adapter to serve this agent");
            }

            List<String> capabilities = new ArrayList<>();
            JsonNode caps = root.path("capabilities");
            if (caps.isArray()) {
                for (JsonNode cap : caps) {
                    capabilities.add(cap.asText());
                }
            }

            ResolvedProfile profile = new ResolvedProfile(url, clientVersion, capabilities);
            log.info("◆ UcpProfileResolver — resolved: version={} capabilities={}", clientVersion, capabilities);
            return profile;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "Failed to fetch/parse agent profile from " + url + ": " + e.getMessage(), e);
        }
    }
}
