package com.example.ucpagent.graph.nodes;

import com.example.ucpagent.graph.Llm;
import com.example.ucpagent.graph.OrderState;
import com.example.ucpagent.graph.OrderStatus;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.Map;

/** LLM: lê o erro, decide RETRY ou GIVE_UP e reformula a query. */
@Component
public class ErrorHandlerNode implements NodeAction<OrderState> {

    static final int MAX_RETRIES = 2;

    public record Decision(String action, String newQuery) implements java.io.Serializable {}

    private final Llm llm;

    public ErrorHandlerNode(Llm llm) {
        this.llm = llm;
    }

    @Override
    public Map<String, Object> apply(OrderState state) {
        int retries = state.retries();
        if (retries >= MAX_RETRIES) {
            return Map.of(OrderState.STATUS, OrderStatus.FAILED);
        }
        Decision d = llm.structured("""
                Uma etapa de compra falhou. Decida action = RETRY ou GIVE_UP.
                Se RETRY, newQuery = busca reformulada mais ampla, em português.
                Pedido original: %s
                Erro: %s""".formatted(state.userQuery(), state.error()), Decision.class,
                () -> new Decision("RETRY", "base"));

        if (!"RETRY".equalsIgnoreCase(d.action())) {
            return Map.of(OrderState.STATUS, OrderStatus.FAILED);
        }
        return Map.of(OrderState.USER_QUERY, d.newQuery(), OrderState.RETRIES, retries + 1,
                OrderState.ERROR, "");
    }
}
