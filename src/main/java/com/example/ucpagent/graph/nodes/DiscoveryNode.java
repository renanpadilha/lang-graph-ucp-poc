package com.example.ucpagent.graph.nodes;

import com.example.ucpagent.graph.Llm;
import com.example.ucpagent.graph.OrderState;
import com.example.ucpagent.graph.OrderStatus;
import com.example.ucpagent.ucp.UcpMcpClient;
import com.example.ucpagent.ucp.UcpModels.Product;
import com.example.ucpagent.ucp.UcpModels.SearchFilter;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** LLM: texto livre -> filtro estruturado. Depois search_catalog. */
@Component
public class DiscoveryNode implements NodeAction<OrderState> {

    private final Llm llm;
    private final UcpMcpClient ucp;

    public DiscoveryNode(Llm llm, UcpMcpClient ucp) {
        this.llm = llm;
        this.ucp = ucp;
    }

    @Override
    public Map<String, Object> apply(OrderState state) {
        String userQuery = state.userQuery();
        SearchFilter filter = llm.structured("""
                Converta o pedido do cliente em um filtro de busca de catálogo de maquiagem.
                query: termos curtos de busca em português. maxAmount: preço máximo em centavos, ou null.
                Pedido: %s""".formatted(userQuery), SearchFilter.class, () -> new SearchFilter(userQuery, null));

        List<Product> found = ucp.searchCatalog(filter.query());
        if (filter.maxAmount() != null) {
            found = found.stream().filter(p -> p.amount() <= filter.maxAmount()).toList();
        }

        if (found.isEmpty()) {
            return Map.of(OrderState.SEARCH_FILTER, filter,
                    OrderState.ERROR, "Nenhum produto para a busca: " + filter.query());
        }
        return Map.of(OrderState.SEARCH_FILTER, filter,
                OrderState.CANDIDATES, found,
                OrderState.ERROR, "",
                OrderState.STATUS, OrderStatus.DISCOVERED);
    }
}
