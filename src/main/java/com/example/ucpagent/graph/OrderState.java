package com.example.ucpagent.graph;

import com.example.ucpagent.ucp.UcpModels.Product;
import com.example.ucpagent.ucp.UcpModels.SearchFilter;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.util.List;
import java.util.Map;

public class OrderState extends AgentState {

    private static final tools.jackson.databind.ObjectMapper MAPPER = tools.jackson.databind.json.JsonMapper.builder().build();

    public static final String USER_QUERY = "userQuery";
    public static final String SEARCH_FILTER = "searchFilter";
    public static final String CANDIDATES = "candidates";
    /** Variantes que o cliente marcou nos cards (preenchido pelo front ao retomar a pausa antes de select). */
    public static final String SELECTED_VARIANT_IDS = "selectedVariantIds";
    /** Quantidade por variantId (padrão 1). */
    public static final String SELECTED_QUANTITIES = "selectedQuantities";
    /** Um carrinho/checkout por loja: storeId -> id. */
    public static final String CARTS = "carts";
    public static final String CHECKOUTS = "checkouts";
    /** Frete escolhido por loja: storeId -> optionId. */
    public static final String SHIPPING_OPTIONS = "shippingOptions";
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

    /** Vindo do checkpoint (Postgres), os itens chegam como Map: converte de volta para Product. */
    public List<Product> candidates() {
        return this.<List<Object>>value(CANDIDATES).orElse(List.of()).stream()
                .map(o -> o instanceof Product p ? p : MAPPER.convertValue(o, Product.class))
                .toList();
    }

    public List<String> selectedVariantIds() { return this.<List<String>>value(SELECTED_VARIANT_IDS).orElse(List.of()); }

    /** Produtos dos candidatos que o cliente marcou, na ordem em que foram encontrados. */
    public List<Product> selectedProducts() {
        List<String> ids = selectedVariantIds();
        return candidates().stream().filter(p -> ids.contains(p.variantId())).distinct().toList();
    }

    public int quantityOf(String variantId) {
        Object q = this.<Map<String, Object>>value(SELECTED_QUANTITIES).orElse(Map.of()).get(variantId);
        return q instanceof Number n ? Math.max(1, n.intValue()) : 1;
    }

    public Map<String, String> carts() { return this.<Map<String, String>>value(CARTS).orElse(Map.of()); }

    public Map<String, String> checkouts() { return this.<Map<String, String>>value(CHECKOUTS).orElse(Map.of()); }

    public Map<String, String> shippingOptions() {
        return this.<Map<String, String>>value(SHIPPING_OPTIONS).orElse(Map.of());
    }

    public OrderStatus status() { return this.<OrderStatus>value(STATUS).orElse(OrderStatus.NEW); }

    public String error() { return this.<String>value(ERROR).orElse(""); }

    public int retries() { return this.<Integer>value(RETRIES).orElse(0); }
}
