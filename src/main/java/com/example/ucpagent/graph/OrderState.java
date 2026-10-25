package com.example.ucpagent.graph;

import com.example.ucpagent.ucp.UcpModels.Product;
import com.example.ucpagent.ucp.UcpModels.SearchFilter;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.util.List;
import java.util.Map;

public class OrderState extends AgentState {

    public static final String USER_QUERY = "userQuery";
    public static final String SEARCH_FILTER = "searchFilter";
    public static final String CANDIDATES = "candidates";
    public static final String SELECTED_VARIANT_ID = "selectedVariantId";
    public static final String CART_ID = "cartId";
    public static final String CHECKOUT_ID = "checkoutId";
    public static final String SHIPPING_OPTION = "shippingOption";
    public static final String STATUS = "status";
    public static final String ERROR = "error";
    public static final String RETRIES = "retries";

    /** candidates acumula (append); o resto é last-write-wins. */
    public static final Map<String, Channel<?>> SCHEMA = Map.of(
            CANDIDATES, Channels.<Product>appender(java.util.ArrayList::new));

    public OrderState(Map<String, Object> init) {
        super(init);
    }

    public String userQuery() { return this.<String>value(USER_QUERY).orElse(""); }

    public SearchFilter searchFilter() { return this.<SearchFilter>value(SEARCH_FILTER).orElse(null); }

    public List<Product> candidates() { return this.<List<Product>>value(CANDIDATES).orElse(List.of()); }

    public String selectedVariantId() { return this.<String>value(SELECTED_VARIANT_ID).orElse(null); }

    public String cartId() { return this.<String>value(CART_ID).orElse(null); }

    public String checkoutId() { return this.<String>value(CHECKOUT_ID).orElse(null); }

    public String shippingOption() { return this.<String>value(SHIPPING_OPTION).orElse(null); }

    public OrderStatus status() { return this.<OrderStatus>value(STATUS).orElse(OrderStatus.NEW); }

    public String error() { return this.<String>value(ERROR).orElse(""); }

    public int retries() { return this.<Integer>value(RETRIES).orElse(0); }
}
