package com.example.ucpagent.graph;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Fonte única da topologia: o StateGraph é montado a partir daqui e o GET /api/graph devolve o
 * mesmo dado, então o desenho do front nunca diverge do código.
 */
public final class OrderTopology {
    private OrderTopology() {}

    public static final String START = "START";
    public static final String END = "END";

    public record Node(String id, String kind) {}

    public record Edge(String from, String to, String label, boolean conditional) {}

    public static final List<Node> NODES = List.of(
            new Node("discovery", "llm"),
            new Node("select", "deterministic"),
            new Node("checkout", "deterministic"),
            new Node("payment", "deterministic"),
            new Node("order", "deterministic"),
            new Node("fulfillment", "llm"),
            new Node("errorHandler", "llm"));

    public static final List<Edge> EDGES = List.of(
            new Edge(START, "discovery", null, false),
            new Edge("discovery", "select", "ok", true),
            new Edge("discovery", "errorHandler", "fail", true),
            new Edge("select", "checkout", null, false),
            new Edge("checkout", "payment", "ok", true),
            new Edge("checkout", "errorHandler", "fail", true),
            new Edge("payment", "order", null, false),
            new Edge("order", "fulfillment", null, false),
            new Edge("fulfillment", END, null, false),
            new Edge("errorHandler", "discovery", "retry", true),
            new Edge("errorHandler", END, "giveUp", true));

    public static final List<String> INTERRUPT_BEFORE = List.of("select", "payment");

    /** Roteadores das arestas condicionais: estado -> rótulo. */
    public static final Map<String, Function<OrderState, String>> ROUTERS = Map.of(
            "discovery", s -> s.error().isBlank() ? "ok" : "fail",
            "checkout", s -> s.error().isBlank() ? "ok" : "fail",
            "errorHandler", s -> s.status() == OrderStatus.FAILED ? "giveUp" : "retry");

    /** Aresta que o grafo vai percorrer depois de `from`, dado o estado. */
    public static Edge next(String from, OrderState state) {
        Function<OrderState, String> router = ROUTERS.get(from);
        String label = router == null ? null : router.apply(state);
        return EDGES.stream()
                .filter(e -> e.from().equals(from) && java.util.Objects.equals(e.label(), label))
                .findFirst().orElse(null);
    }
}
