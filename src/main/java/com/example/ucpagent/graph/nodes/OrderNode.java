package com.example.ucpagent.graph.nodes;

import com.example.ucpagent.graph.OrderState;
import com.example.ucpagent.graph.OrderStatus;
import org.bsc.langgraph4j.action.NodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Determinístico. Sem complete_checkout, o pedido é só marcado no estado. */
@Component
public class OrderNode implements NodeAction<OrderState> {

    private static final Logger log = LoggerFactory.getLogger(OrderNode.class);

    @Override
    public Map<String, Object> apply(OrderState state) {
        log.info("order SIMULADO para checkouts={}", state.checkouts());
        return Map.of(OrderState.STATUS, OrderStatus.ORDER_PLACED);
    }
}
