package com.example.ucpagent.graph.nodes;

import com.example.ucpagent.graph.OrderState;
import com.example.ucpagent.graph.OrderStatus;
import org.bsc.langgraph4j.action.NodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Determinístico. Quem escolhe é o cliente: o grafo pausa antes deste nó (interruptBefore) e o
 * OrderAgent grava em selectedVariantIds o que foi marcado nos cards. Aqui só se confere que cada
 * id existe entre os candidatos.
 */
@Component
public class SelectNode implements NodeAction<OrderState> {

    private static final Logger log = LoggerFactory.getLogger(SelectNode.class);

    @Override
    public Map<String, Object> apply(OrderState state) {
        List<String> valid = state.selectedProducts().stream().map(p -> p.variantId()).toList();
        log.info("select: {} produto(s) escolhido(s) pelo cliente", valid.size());
        return Map.of(OrderState.SELECTED_VARIANT_IDS, valid, OrderState.STATUS, OrderStatus.SELECTED);
    }
}
