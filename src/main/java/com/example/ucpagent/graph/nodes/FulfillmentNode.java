package com.example.ucpagent.graph.nodes;

import com.example.ucpagent.graph.Llm;
import com.example.ucpagent.graph.OrderState;
import com.example.ucpagent.graph.OrderStatus;
import com.example.ucpagent.ucp.StoreRegistry;
import com.example.ucpagent.ucp.UcpModels.Product;
import com.example.ucpagent.ucp.UcpModels.ShippingOption;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** LLM: pesa preço vs prazo e escolhe a opção de envio. */
@Component
public class FulfillmentNode implements NodeAction<OrderState> {

    public record Choice(String optionId, String reason) implements java.io.Serializable {}

    private final Llm llm;
    private final StoreRegistry stores;

    public FulfillmentNode(Llm llm, StoreRegistry stores) {
        this.llm = llm;
        this.stores = stores;
    }

    @Override
    public Map<String, Object> apply(OrderState state) {
        Map<String, List<String>> variantsByStore = state.selectedProducts().stream().collect(
                Collectors.groupingBy(Product::storeId, LinkedHashMap::new,
                        Collectors.mapping(Product::variantId, Collectors.toList())));

        Map<String, String> chosen = new LinkedHashMap<>();
        for (var entry : state.checkouts().entrySet()) {
            String storeId = entry.getKey();
            List<ShippingOption> options = stores.get(storeId)
                    .shippingOptions(entry.getValue(), variantsByStore.getOrDefault(storeId, List.of()));
            if (options.isEmpty()) {
                return Map.of(OrderState.ERROR, "Sem opções de envio na loja " + storeId,
                        OrderState.STATUS, OrderStatus.FAILED);
            }
            Choice choice = llm.structured("""
                    Escolha a opção de envio com melhor equilíbrio preço x prazo.
                    Responda com optionId (exatamente um da lista) e reason.
                    Opções (amount em centavos): %s""".formatted(options), Choice.class,
                    () -> new Choice(options.get(0).id(), "primeira opção (sem LLM)"));
            chosen.put(storeId, options.stream().map(ShippingOption::id)
                    .filter(choice.optionId()::equals).findFirst().orElse(options.get(0).id()));
        }
        return Map.of(OrderState.SHIPPING_OPTIONS, chosen, OrderState.STATUS, OrderStatus.FULFILLED);
    }
}
