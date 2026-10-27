package com.example.ucpagent.ucp;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "ucp")
public record UcpProperties(String agentProfileUrl, List<Store> stores) {

    public record Store(String id, String name, String mcpEndpoint) {}
}
