package com.accenture.UCPAdaptor.ucp;

import java.util.List;

public record ResolvedProfile(
    String url,
    String ucpVersion,
    List<String> capabilities
) {}
