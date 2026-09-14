package com.accenture.UCPAdaptor.web;

import com.accenture.UCPAdaptor.ucp.ResolvedProfile;
import com.accenture.UCPAdaptor.ucp.UcpCapabilityContributor;
import com.accenture.UCPAdaptor.ucp.UcpProfileResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the UCP-Agent request header on every call to the discovery endpoint
 * (/.well-known/ucp) and the MCP tool endpoint (/ucp/mcp).
 *
 * On /ucp/mcp calls with a valid UCP-Agent header, this filter:
 *   1. Fetches and validates the caller's agent profile
 *   2. Validates that the caller's UCP version is not newer than ours
 *   3. Computes capability intersection and stores it as a request attribute
 *
 * Returns HTTP 400 if version validation fails or the profile cannot be fetched.
 */
@Component
public class UcpAgentFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(UcpAgentFilter.class);

    /** Request attribute key for the capability intersection list. */
    public static final String ATTR_INTERSECTION = "ucp.intersection";

    private static final Pattern PROFILE_PATTERN =
        Pattern.compile("profile\\s*=\\s*\"([^\"]+)\"");

    private final UcpProfileResolver profileResolver;
    private final List<UcpCapabilityContributor> contributors;

    public UcpAgentFilter(UcpProfileResolver profileResolver,
                          List<UcpCapabilityContributor> contributors) {
        this.profileResolver = profileResolver;
        this.contributors = contributors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();
        boolean isDiscovery = "/.well-known/ucp".equals(path);
        boolean isMcp       = path.startsWith("/ucp/mcp");

        if (isDiscovery || isMcp) {
            String ucpAgent = request.getHeader("UCP-Agent");
            String phase    = isDiscovery ? "DISCOVERY" : "MCP TOOL CALL";

            if (ucpAgent != null && !ucpAgent.isBlank()) {
                String profileUrl = parseProfileUrl(ucpAgent);
                log.info("◆ UCP-Agent [{}]  path={}  profile={}",
                    phase, path, profileUrl != null ? profileUrl : ucpAgent);

                if (profileUrl != null && isMcp) {
                    try {
                        ResolvedProfile profile = profileResolver.resolveProfile(profileUrl);
                        List<String> intersection = profileResolver.getIntersection(profile, contributors);
                        request.setAttribute(ATTR_INTERSECTION, intersection);
                        log.info("◆ UCP-Agent [{}]  version={}  intersection={}", phase, profile.ucpVersion(), intersection);
                    } catch (IllegalArgumentException e) {
                        log.warn("◆ UCP-Agent [{}]  rejected: {}", phase, e.getMessage());
                        response.sendError(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
                        return;
                    }
                } else if (profileUrl != null) {
                    log.info("◆ UCP-Agent [{}]  agent profile URL: {}", phase, profileUrl);
                    log.info("◆ UCP-Agent [{}]  capability negotiation: client will intersect " +
                        "its supported capabilities against this store's /.well-known/ucp", phase);
                }
            } else {
                log.debug("◆ UCP-Agent [{}]  path={}  no UCP-Agent header (non-UCP caller: {})",
                    phase, path, request.getHeader("User-Agent"));
            }
        }

        filterChain.doFilter(request, response);
    }

    private String parseProfileUrl(String headerValue) {
        Matcher m = PROFILE_PATTERN.matcher(headerValue);
        return m.find() ? m.group(1) : null;
    }
}
