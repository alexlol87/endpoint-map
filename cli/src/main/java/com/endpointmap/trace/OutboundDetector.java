package com.endpointmap.trace;

import java.util.List;

/** One client family (Feign, WebClient, RestTemplate...). Classifies a call; empty means "not mine, keep walking". */
public interface OutboundDetector {

    List<OutboundCall> detect(CallSite site);
}
