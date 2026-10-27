package com.example.ucpagent.web;

import com.example.ucpagent.graph.OrderTopology;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Topologia do grafo para o painel de depuração do front. */
@RestController
@RequestMapping("/api")
public class GraphController {

    @GetMapping("/graph")
    public Map<String, Object> graph() {
        List<Map<String, Object>> edges = OrderTopology.EDGES.stream().map(e -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("from", e.from());
            m.put("to", e.to());
            if (e.label() != null) m.put("label", e.label());
            m.put("conditional", e.conditional());
            return m;
        }).toList();
        return Map.of("nodes", OrderTopology.NODES, "edges", edges,
                "interruptBefore", OrderTopology.INTERRUPT_BEFORE);
    }
}
