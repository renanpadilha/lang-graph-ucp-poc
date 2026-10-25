package com.example.ucpagent.graph.nodes;

import com.example.ucpagent.graph.Llm;
import com.example.ucpagent.graph.OrderState;
import com.example.ucpagent.graph.OrderStatus;
import com.example.ucpagent.ucp.UcpMcpClient;
import com.example.ucpagent.ucp.UcpModels.ShippingOption;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** LLM: pesa preço vs prazo e escolhe a opção de envio. */
@Component
public class FulfillmentNode implements NodeAction<OrderState> {

    public record Choice(String optionId, String reason) implements java.io.Serializable {}

    private final Llm llm;
    private final UcpMcpClient ucp;

    public FulfillmentNode(Llm llm, UcpMcpClient ucp) {
        this.llm = llm;
        this.ucp = ucp;
    }

    @Override
    public Map<String, Object> apply(OrderState state) {
        List<ShippingOption> options = ucp.shippingOptions(state.checkoutId(), state.selectedVariantId());
        if (options.isEmpty()) {
            return Map.of(OrderState.ERROR, "Sem opções de envio", OrderState.STATUS, OrderStatus.FAILED);
        }
        Choice choice = llm.structured("""
                Escolha a opção de envio com melhor equilíbrio preço x prazo.
                Responda com optionId (exatamente um da lista) e reason.
                Opções (amount em centavos): %s""".formatted(options), Choice.class,
                () -> new Choice(options.get(0).id(), "primeira opção (sem LLM)"));

        String optionId = options.stream().map(ShippingOption::id)
                .filter(choice.optionId()::equals).findFirst().orElse(options.get(0).id());
        return Map.of(OrderState.SHIPPING_OPTION, optionId, OrderState.STATUS, OrderStatus.FULFILLED);
    }
}
