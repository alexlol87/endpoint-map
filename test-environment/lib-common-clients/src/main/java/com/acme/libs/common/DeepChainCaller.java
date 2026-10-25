package com.acme.libs.common;

/**
 * URL travels through four method levels before reaching the client.
 * Constant propagation stops at three levels, so the tool should emit a callSites entry.
 */
public class DeepChainCaller {

    private final InternalRestTemplateClient client;

    public DeepChainCaller(InternalRestTemplateClient client) {
        this.client = client;
    }

    public String level1(String url) {
        return level2(url);
    }

    private String level2(String url) {
        return level3(url);
    }

    private String level3(String url) {
        return level4(url);
    }

    private String level4(String url) {
        return client.fetch(url, String.class);
    }
}
