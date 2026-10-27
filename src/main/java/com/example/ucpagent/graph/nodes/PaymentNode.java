package com.example.ucpagent.graph.nodes;

import com.example.ucpagent.graph.OrderState;
import com.example.ucpagent.graph.OrderStatus;
import org.bsc.langgraph4j.action.NodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Determinístico. SIMULA pagamento: monta payload, loga, marca PAYMENT_SIMULATED.
 * Nunca chama complete_checkout (cobrança real).
 */
@Component
public class PaymentNode implements NodeAction<OrderState> {

    private static final Logger log = LoggerFactory.getLogger(PaymentNode.class);

    @Override
    public Map<String, Object> apply(OrderState state) {
        log.info("payment SIMULADO: checkouts={} variants={}", state.checkouts(), state.selectedVariantIds());
        return Map.of(OrderState.STATUS, OrderStatus.PAYMENT_SIMULATED);
    }
}
