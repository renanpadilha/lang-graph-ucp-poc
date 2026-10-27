package com.example.ucpagent.web;

import com.example.ucpagent.graph.OrderState;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/orders")
public class OrderController {

    public record CreateOrder(String query) {}

    private final CompiledGraph<OrderState> graph;

    public OrderController(CompiledGraph<OrderState> graph) {
        this.graph = graph;
    }

    /** Roda o grafo até o interrupt antes de "payment". */
    @PostMapping
    public Map<String, Object> create(@RequestBody CreateOrder req) {
        String threadId = UUID.randomUUID().toString();
        RunnableConfig cfg = RunnableConfig.builder().threadId(threadId).build();
        OrderState paused = graph.invoke(Map.of(OrderState.USER_QUERY, req.query()), cfg).orElseThrow();
        return view(threadId, paused, graph.getState(cfg).nextNodeId());
    }

    /** Retoma o mesmo thread: payment -> order -> fulfillment. */
    @PostMapping("/{threadId}/approve")
    public Map<String, Object> approve(@PathVariable String threadId) {
        RunnableConfig cfg = RunnableConfig.builder().threadId(threadId).build();
        OrderState done = graph.invoke(GraphInput.resume(), cfg.withCheckPointId(graph.getState(cfg).checkpointId()))
                .orElseThrow();
        return view(threadId, done, "");
    }

    private Map<String, Object> view(String threadId, OrderState s, String next) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("threadId", threadId);
        out.put("status", s.status());
        out.put("nextNode", next);
        out.put("candidates", s.candidates());
        out.put("selectedVariantIds", s.selectedVariantIds());
        out.put("checkouts", s.checkouts());
        out.put("shippingOptions", s.shippingOptions());
        out.put("error", s.error());
        return out;
    }
}
