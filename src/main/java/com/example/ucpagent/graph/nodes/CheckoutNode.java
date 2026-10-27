package com.example.ucpagent.graph.nodes;

import com.example.ucpagent.graph.OrderState;
import com.example.ucpagent.graph.OrderStatus;
import com.example.ucpagent.ucp.StoreRegistry;
import com.example.ucpagent.ucp.UcpMcpClient;
import com.example.ucpagent.ucp.UcpModels.Product;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Determinístico: create_cart + create_checkout, um par por loja (um checkout só vale numa loja). */
@Component
public class CheckoutNode implements NodeAction<OrderState> {

    private final StoreRegistry stores;

    public CheckoutNode(StoreRegistry stores) {
        this.stores = stores;
    }

    @Override
    public Map<String, Object> apply(OrderState state) {
        List<Product> chosen = state.selectedProducts();
        if (chosen.isEmpty()) {
            return Map.of(OrderState.ERROR, "Nenhum produto selecionado");
        }
        Map<String, Map<String, Integer>> byStore = new LinkedHashMap<>();
        for (Product p : chosen) {
            byStore.computeIfAbsent(p.storeId(), k -> new LinkedHashMap<>()).put(p.variantId(), state.quantityOf(p.variantId()));
        }
        Map<String, String> carts = new LinkedHashMap<>();
        Map<String, String> checkouts = new LinkedHashMap<>();
        try {
            for (var entry : byStore.entrySet()) {
                UcpMcpClient ucp = stores.get(entry.getKey());
                carts.put(entry.getKey(), ucp.createCart(entry.getValue()));
                checkouts.put(entry.getKey(), ucp.createCheckout(entry.getValue()));
            }
            return Map.of(OrderState.CARTS, carts, OrderState.CHECKOUTS, checkouts,
                    OrderState.ERROR, "", OrderState.STATUS, OrderStatus.CHECKOUT_CREATED);
        } catch (UcpMcpClient.UcpException e) {
            return Map.of(OrderState.ERROR, e.getMessage());
        }
    }
}
