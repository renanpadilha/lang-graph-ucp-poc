package com.example.ucpagent.graph;

import com.example.ucpagent.graph.nodes.*;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

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
                                                ErrorHandlerNode errorHandler) throws GraphStateException {
        return new StateGraph<>(OrderState.SCHEMA, OrderState::new)
                .addNode("discovery", node_async(discovery))
                .addNode("select", node_async(select))
                .addNode("checkout", node_async(checkout))
                .addNode("payment", node_async(payment))
                .addNode("order", node_async(order))
                .addNode("fulfillment", node_async(fulfillment))
                .addNode("errorHandler", node_async(errorHandler))

                .addEdge(START, "discovery")
                .addConditionalEdges("discovery",
                        edge_async(s -> s.error().isBlank() ? "ok" : "fail"),
                        Map.of("ok", "select", "fail", "errorHandler"))
                .addEdge("select", "checkout")
                .addConditionalEdges("checkout",
                        edge_async(s -> s.error().isBlank() ? "ok" : "fail"),
                        Map.of("ok", "payment", "fail", "errorHandler"))
                .addEdge("payment", "order")
                .addEdge("order", "fulfillment")
                .addEdge("fulfillment", END)
                .addConditionalEdges("errorHandler",
                        edge_async(s -> s.status() == OrderStatus.FAILED ? "giveUp" : "retry"),
                        Map.of("retry", "discovery", "giveUp", END))
                .compile(CompileConfig.builder()
                        .checkpointSaver(new MemorySaver())
                        .interruptBefore("payment")
                        .build());
    }
}
