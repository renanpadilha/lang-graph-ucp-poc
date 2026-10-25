package com.example.ucpagent.graph.nodes;

import com.example.ucpagent.graph.Llm;
import com.example.ucpagent.graph.OrderState;
import com.example.ucpagent.graph.OrderStatus;
import com.example.ucpagent.ucp.UcpModels.Product;
import com.example.ucpagent.ucp.UcpModels.Selection;
import org.bsc.langgraph4j.action.NodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** LLM: rerank dos candidatos + justificativa. */
@Component
public class SelectNode implements NodeAction<OrderState> {

    private static final Logger log = LoggerFactory.getLogger(SelectNode.class);

    private final Llm llm;

    public SelectNode(Llm llm) {
        this.llm = llm;
    }

    @Override
    public Map<String, Object> apply(OrderState state) {
        List<Product> candidates = state.candidates();
        Selection sel = llm.structured("""
                Escolha o melhor produto para o pedido. Responda com variantId (exatamente um da lista) e reason.
                Pedido: %s
                Candidatos: %s""".formatted(state.userQuery(), candidates), Selection.class,
                () -> new Selection(candidates.get(0).variantId(), "primeiro candidato (sem LLM)"));

        String variantId = candidates.stream().map(Product::variantId)
                .filter(sel.variantId()::equals).findFirst()
                .orElse(candidates.get(0).variantId());
        log.info("select: {} — {}", variantId, sel.reason());
        return Map.of(OrderState.SELECTED_VARIANT_ID, variantId, OrderState.STATUS, OrderStatus.SELECTED);
    }
}
