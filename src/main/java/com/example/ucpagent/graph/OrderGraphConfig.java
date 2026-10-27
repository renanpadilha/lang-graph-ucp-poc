package com.example.ucpagent.graph;

import com.example.ucpagent.graph.nodes.*;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import com.example.ucpagent.trace.CallRecorder;
import org.bsc.langgraph4j.action.NodeAction;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

@Configuration
public class OrderGraphConfig {

    @Bean
    public CompiledGraph<OrderState> orderGraph(DiscoveryNode discovery, SelectNode select,
                                                CheckoutNode checkout, PaymentNode payment,
                                                OrderNode order, FulfillmentNode fulfillment,
                                                ErrorHandlerNode errorHandler, CallRecorder recorder,
                                                BaseCheckpointSaver checkpointSaver) throws GraphStateException {
        Map<String, NodeAction<OrderState>> actions = Map.of(
                "discovery", discovery, "select", select, "checkout", checkout, "payment", payment,
                "order", order, "fulfillment", fulfillment, "errorHandler", errorHandler);

        var graph = new StateGraph<>(OrderState.SCHEMA, OrderState::new)
                .addWrapCallNodeHook(TraceHook.of(recorder));
        for (var node : OrderTopology.NODES) {
            graph.addNode(node.id(), node_async(actions.get(node.id())));
        }

        // Arestas agrupadas por origem: uma só = addEdge; com rótulos = addConditionalEdges.
        var bySource = OrderTopology.EDGES.stream().collect(Collectors.groupingBy(
                OrderTopology.Edge::from, LinkedHashMap::new, Collectors.toList()));
        for (var entry : bySource.entrySet()) {
            String from = entry.getKey();
            var edges = entry.getValue();
            if (OrderTopology.ROUTERS.containsKey(from)) {
                var router = OrderTopology.ROUTERS.get(from);
                Map<String, String> targets = new LinkedHashMap<>();
                edges.forEach(e -> targets.put(e.label(), lg(e.to())));
                graph.addConditionalEdges(from, edge_async(router::apply), targets);
            } else {
                graph.addEdge(lg(from), lg(edges.get(0).to()));
            }
        }

        return graph.compile(CompileConfig.builder()
                .checkpointSaver(checkpointSaver)
                .interruptBefore(OrderTopology.INTERRUPT_BEFORE.toArray(String[]::new))
                .build());
    }

    private static String lg(String id) {
        return switch (id) {
            case OrderTopology.START -> START;
            case OrderTopology.END -> END;
            default -> id;
        };
    }
}
