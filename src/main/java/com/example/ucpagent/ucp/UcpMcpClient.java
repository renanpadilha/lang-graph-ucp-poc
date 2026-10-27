package com.example.ucpagent.ucp;

import com.example.ucpagent.trace.CallRecorder;
import com.example.ucpagent.ucp.UcpModels.Product;
import com.example.ucpagent.ucp.UcpModels.ShippingOption;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.client.RestClient;


import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JSON-RPC 2.0 sobre MCP. Só leitura + create_cart + create_checkout.
 * complete_checkout NÃO existe aqui de propósito.
 * Uma instância por loja (ver {@link StoreRegistry}) — não é bean singleton.
 */
public class UcpMcpClient {

    private static final Logger log = LoggerFactory.getLogger(UcpMcpClient.class);

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final RestClient http = RestClient.create();
    private final AtomicLong rpcId = new AtomicLong();
    private final String storeId;
    private final String storeName;
    private final String endpoint;
    private final String profileUrl;
    private final CallRecorder recorder;

    public UcpMcpClient(String storeId, String storeName, String endpoint, String profileUrl, CallRecorder recorder) {
        this.storeId = storeId;
        this.storeName = storeName;
        this.endpoint = endpoint;
        this.profileUrl = profileUrl;
        this.recorder = recorder;
    }

    public String storeId() { return storeId; }

    public String storeName() { return storeName; }

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
                        storeId,
                        storeName,
                        p.path("id").asText(),
                        p.path("title").asText() + " - " + v.path("title").asText(),
                        v.path("id").asText(),
                        v.path("price").path("amount").asLong(),
                        v.path("price").path("currency").asText("BRL"),
                        firstImage(v.path("media"), p.path("media")),
                        p.path("url").asText(null)));
                break; // 1 variante por produto basta pro POC
            }
        }
        return out;
    }

    public String createCart(Map<String, Integer> variantQty) {
        if (isMock()) return "mock-cart";
        JsonNode res = call("create_cart", Map.of("cart", Map.of("line_items", lineItems(variantQty))));
        return res.path("id").asText();
    }

    public String createCheckout(Map<String, Integer> variantQty) {
        if (isMock()) return "mock-checkout";
        JsonNode res = call("create_checkout", Map.of("checkout", Map.of("line_items", lineItems(variantQty))));
        return res.path("id").asText();
    }

    private static List<Map<String, Object>> lineItems(Map<String, Integer> variantQty) {
        return variantQty.entrySet().stream()
                .<Map<String, Object>>map(e -> Map.of("quantity", e.getValue(), "item", Map.of("id", e.getKey())))
                .toList();
    }

    public List<ShippingOption> shippingOptions(String checkoutId, List<String> variantIds) {
        if (isMock()) {
            return List.of(new ShippingOption("mock-std", "Padrão 5-8 dias", 1990, "BRL"),
                    new ShippingOption("mock-exp", "Expresso 1-2 dias", 3990, "BRL"));
        }
        JsonNode current = call("get_checkout", Map.of("id", checkoutId));
        // Todas as linhas do checkout entram na atualização e no método de entrega.
        List<Map<String, Object>> lines = new ArrayList<>();
        List<String> lineIds = new ArrayList<>();
        int i = 0;
        for (JsonNode line : current.path("line_items")) {
            String lineId = line.path("id").asText();
            String itemId = line.path("item").path("id").asText(i < variantIds.size() ? variantIds.get(i) : "");
            lines.add(Map.of("id", lineId, "quantity", line.path("quantity").asInt(1), "item", Map.of("id", itemId)));
            lineIds.add(lineId);
            i++;
        }
        // Dados FICTÍCIOS: o Shopify só calcula frete com contato + endereço.
        Map<String, Object> destination = Map.of(
                "id", "dest-1", "first_name", "Teste", "last_name", "POC",
                "phone_number", "+5511999999999",
                "street_address", "Avenida Paulista, 1000", "extended_address", "Apto 1", "address_locality", "São Paulo",
                "address_region", "SP", "postal_code", "01310-100", "address_country", "BR");
        JsonNode res = call("update_checkout", Map.of("id", checkoutId, "checkout", Map.of(
                "line_items", lines,
                "buyer", Map.of("email", "teste@example.com", "phone_number", "+5511999999999"),
                "fulfillment", Map.of("methods", List.of(Map.of(
                        "type", "shipping",
                        "line_item_ids", lineIds,
                        "destinations", List.of(destination),
                        "selected_destination_id", "dest-1"))))));
        // Shopify calcula frete de forma assíncrona: re-lê o checkout até aparecerem opções.
        JsonNode checkout = res;
        List<ShippingOption> out = parseShipping(checkout);
        for (int n = 0; n < 6 && out.isEmpty(); n++) {
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

    private static String firstImage(JsonNode... medias) {
        for (JsonNode media : medias) {
            for (JsonNode m : media) {
                if ("image".equals(m.path("type").asText()) && m.hasNonNull("url")) return m.get("url").asText();
            }
        }
        return null;
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
            return all.stream()
                    .filter(p -> q.contains("base") ? p.title().toLowerCase().contains("base")
                            : p.title().toLowerCase().contains(q))
                    .map(p -> new Product(storeId, storeName, p.productId(), p.title(), p.variantId(),
                            p.amount(), p.currency(), p.image(), p.url()))
                    .toList();
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

        long started = System.nanoTime();
        JsonNode resp = http.post().uri(endpoint)
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        recorder.ucp(storeId, tool, (System.nanoTime() - started) / 1_000_000, resp.has("error"), arguments);

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
