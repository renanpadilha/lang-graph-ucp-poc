package com.example.ucpagent.graph.nodes;

import com.example.ucpagent.graph.Llm;
import com.example.ucpagent.graph.OrderState;
import com.example.ucpagent.graph.OrderStatus;
import com.example.ucpagent.ucp.StoreRegistry;
import com.example.ucpagent.ucp.UcpModels.Product;
import com.example.ucpagent.ucp.UcpModels.SearchFilter;
import org.bsc.langgraph4j.action.NodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** LLM: texto livre -> filtro estruturado. Depois search_catalog em paralelo em todas as lojas. */
@Component
public class DiscoveryNode implements NodeAction<OrderState> {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryNode.class);

    private final Llm llm;
    private final StoreRegistry stores;
    private final ExecutorService executor;

    public DiscoveryNode(Llm llm, StoreRegistry stores, ExecutorService ucpFanOutExecutor) {
        this.llm = llm;
        this.stores = stores;
        this.executor = ucpFanOutExecutor;
    }

    @Override
    public Map<String, Object> apply(OrderState state) {
        String userQuery = state.userQuery();
        SearchFilter filter = llm.structured("""
                Converta o pedido do cliente em um filtro de busca de catálogo de maquiagem.
                query: termos curtos de busca em português. maxAmount: preço máximo em centavos, ou null.
                Pedido: %s""".formatted(userQuery), SearchFilter.class, () -> new SearchFilter(userQuery, null));

        List<Product> found = searchAllStores(filter.query());
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

    /** search_catalog em todas as lojas em paralelo. Uma loja falhando/lenta não derruba as outras. */
    private List<Product> searchAllStores(String query) {
        List<CompletableFuture<List<Product>>> futures = stores.all().stream()
                .map(store -> CompletableFuture.supplyAsync(() -> store.searchCatalog(query), executor)
                        .orTimeout(8, TimeUnit.SECONDS)
                        .exceptionally(ex -> {
                            log.warn("busca falhou na loja {}: {}", store.storeId(), ex.toString());
                            return List.<Product>of();
                        }))
                .toList();
        return futures.stream().flatMap(f -> f.join().stream()).toList();
    }
}
