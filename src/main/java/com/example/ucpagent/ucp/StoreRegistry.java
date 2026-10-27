package com.example.ucpagent.ucp;

import com.example.ucpagent.trace.CallRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Monta um UcpMcpClient por loja configurada em ucp.stores. Lojas sem mcp-endpoint ficam de fora. */
@Component
@EnableConfigurationProperties(UcpProperties.class)
public class StoreRegistry {

    private static final Logger log = LoggerFactory.getLogger(StoreRegistry.class);

    private final List<UcpMcpClient> stores;
    private final Map<String, UcpMcpClient> byId;

    public StoreRegistry(UcpProperties props, CallRecorder recorder) {
        Map<String, UcpMcpClient> built = new LinkedHashMap<>();
        for (UcpProperties.Store s : props.stores()) {
            if (s.mcpEndpoint() == null || s.mcpEndpoint().isBlank()) {
                log.warn("loja {} sem mcp-endpoint configurado, ignorada", s.id());
                continue;
            }
            built.put(s.id(), new UcpMcpClient(s.id(), s.name(), s.mcpEndpoint(), props.agentProfileUrl(), recorder));
        }
        this.byId = Map.copyOf(built);
        this.stores = List.copyOf(byId.values());
    }

    public List<UcpMcpClient> all() {
        return stores;
    }

    public UcpMcpClient get(String storeId) {
        UcpMcpClient c = byId.get(storeId);
        if (c == null) throw new IllegalStateException("loja desconhecida ou sem endpoint: " + storeId);
        return c;
    }
}
