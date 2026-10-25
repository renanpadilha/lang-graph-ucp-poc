package com.example.ucpagent.graph.nodes;

import com.example.ucpagent.graph.OrderState;
import com.example.ucpagent.graph.OrderStatus;
import com.example.ucpagent.ucp.UcpMcpClient;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Determinístico: create_cart + create_checkout. */
@Component
public class CheckoutNode implements NodeAction<OrderState> {

    private final UcpMcpClient ucp;

    public CheckoutNode(UcpMcpClient ucp) {
        this.ucp = ucp;
    }

    @Override
    public Map<String, Object> apply(OrderState state) {
        try {
            String cartId = ucp.createCart(state.selectedVariantId());
            String checkoutId = ucp.createCheckout(state.selectedVariantId());
            return Map.of(OrderState.CART_ID, cartId, OrderState.CHECKOUT_ID, checkoutId,
                    OrderState.ERROR, "", OrderState.STATUS, OrderStatus.CHECKOUT_CREATED);
        } catch (UcpMcpClient.UcpException e) {
            return Map.of(OrderState.ERROR, e.getMessage());
        }
    }
}
