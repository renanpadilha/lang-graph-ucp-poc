package com.example.ucpagent.ucp;

import com.example.ucpagent.ucp.UcpModels.Product;
import com.example.ucpagent.ucp.UcpModels.ShippingOption;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;


import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JSON-RPC 2.0 sobre MCP. Só leitura + create_cart + create_checkout.
 * complete_checkout NÃO existe aqui de propósito.
 */
@Component
public class UcpMcpClient {

    private static final Logger log = LoggerFactory.getLogger(UcpMcpClient.class);

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final RestClient http = RestClient.create();
    private final AtomicLong rpcId = new AtomicLong();
    private final String endpoint;
    private final String profileUrl;

    public UcpMcpClient(@Value("${ucp.mcp-endpoint}") String endpoint,
                        @Value("${ucp.agent-profile-url:}") String profileUrl) {
        this.endpoint = endpoint;
        this.profileUrl = profileUrl;
    }

    public boolean isMock() {
        return profileUrl == null || profileUrl.isBlank();
    }

    public List<Product> searchCatalog(String query) {
        if (isMock()) return mockProducts(query);
        JsonNode res = call("search_catalog", Map.of("catalog", Map.of("query", query)));
        List<Product> out = new ArrayList<>();
        for (JsonNode p : res.path("products")) {
            for (JsonNode v : p.path("variants")) {
                if (!v.path("availability").path("available").asBoolean(false)) continue;
                out.add(new Product(
                        p.path("id").asText(),
                        p.path("title").asText() + " - " + v.path("title").asText(),
                        v.path("id").asText(),
                        v.path("price").path("amount").asLong(),
                        v.path("price").path("currency").asText("BRL")));
                break; // 1 variante por produto basta pro POC
            }
        }
        return out;
    }

    public String createCart(String variantId) {
        if (isMock()) return "mock-cart";
        JsonNode res = call("create_cart", Map.of("cart", Map.of(
                "line_items", List.of(Map.of("quantity", 1, "item", Map.of("id", variantId))))));
        return res.path("id").asText();
    }

    public String createCheckout(String variantId) {
        if (isMock()) return "mock-checkout";
        JsonNode res = call("create_checkout", Map.of("checkout", Map.of(
                "line_items", List.of(Map.of("quantity", 1, "item", Map.of("id", variantId))))));
        return res.path("id").asText();
    }

    public List<ShippingOption> shippingOptions(String checkoutId, String variantId) {
        if (isMock()) {
            return List.of(new ShippingOption("mock-std", "Padrão 5-8 dias", 1990, "BRL"),
                    new ShippingOption("mock-exp", "Expresso 1-2 dias", 3990, "BRL"));
        }
        JsonNode current = call("get_checkout", Map.of("id", checkoutId));
        String lineId = current.path("line_items").path(0).path("id").asText();
        // Dados FICTÍCIOS: o Shopify só calcula frete com contato + endereço.
        Map<String, Object> destination = Map.of(
                "id", "dest-1", "first_name", "Teste", "last_name", "POC",
                "phone_number", "+5511999999999",
                "street_address", "Avenida Paulista, 1000", "extended_address", "Apto 1", "address_locality", "São Paulo",
                "address_region", "SP", "postal_code", "01310-100", "address_country", "BR");
        JsonNode res = call("update_checkout", Map.of("id", checkoutId, "checkout", Map.of(
                "line_items", List.of(Map.of("id", lineId, "quantity", 1, "item", Map.of("id", variantId))),
                "buyer", Map.of("email", "teste@example.com", "phone_number", "+5511999999999"),
                "fulfillment", Map.of("methods", List.of(Map.of(
                        "type", "shipping",
                        "line_item_ids", List.of(lineId),
                        "destinations", List.of(destination),
                        "selected_destination_id", "dest-1"))))));
        // Shopify calcula frete de forma assíncrona: re-lê o checkout até aparecerem opções.
        JsonNode checkout = res;
        List<ShippingOption> out = parseShipping(checkout);
        for (int i = 0; i < 6 && out.isEmpty(); i++) {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            checkout = call("get_checkout", Map.of("id", checkoutId));
            out = parseShipping(checkout);
        }
        return out;
    }

    private List<ShippingOption> parseShipping(JsonNode checkout) {
        List<ShippingOption> out = new ArrayList<>();
        for (JsonNode m : checkout.path("fulfillment").path("methods")) {
            for (JsonNode g : m.path("groups")) {
                for (JsonNode o : g.path("options")) {
                    out.add(new ShippingOption(
                            o.path("id").asText(),
                            o.path("title").asText() + " (" + o.path("description").path("plain").asText() + ")",
                            o.path("totals").path(0).path("amount").asLong(),
                            "BRL"));
                }
            }
        }
        return out;
    }

    private List<Product> mockProducts(String query) {
        try {
            List<Product> all = mapper.readValue(new ClassPathResource("ucp-products.json").getInputStream(),
                    new TypeReference<>() {});
            String q = query.toLowerCase();
            return all.stream().filter(p -> q.contains("base") ? p.title().toLowerCase().contains("base")
                    : p.title().toLowerCase().contains(q)).toList();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Chama uma tool MCP e devolve o payload JSON de resultado. Lança em erro JSON-RPC. */
    private JsonNode call(String tool, Map<String, Object> args) {
        Map<String, Object> arguments = new java.util.HashMap<>(args);
        arguments.put("meta", Map.of("ucp-agent", Map.of("profile", profileUrl)));
        Map<String, Object> body = Map.of(
                "jsonrpc", "2.0",
                "id", rpcId.incrementAndGet(),
                "method", "tools/call",
                "params", Map.of("name", tool, "arguments", arguments));

        JsonNode resp = http.post().uri(endpoint)
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        log.info("UCP {} -> {}", tool, resp);
        if (resp.has("error")) {
            throw new UcpException(tool + ": " + resp.get("error").toString());
        }
        JsonNode result = resp.path("result");
        if (result.has("structuredContent")) return result.get("structuredContent");
        JsonNode text = result.path("content").path(0).path("text");
        try {
            return text.isMissingNode() ? result : mapper.readTree(text.asText());
        } catch (RuntimeException e) {
            throw new UcpException(tool + ": resposta não-JSON: " + text.asText());
        }
    }

    public static class UcpException extends RuntimeException {
        public UcpException(String msg) { super(msg); }
    }
}
