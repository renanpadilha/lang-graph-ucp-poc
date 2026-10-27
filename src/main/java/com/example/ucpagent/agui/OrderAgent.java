package com.example.ucpagent.agui;

import com.agui.community.core.agent.RunAgentInput;
import com.agui.community.core.event.*;
import com.agui.community.core.interrupt.Interrupt;
import com.agui.community.core.interrupt.InterruptOutcome;
import com.agui.community.core.interrupt.ResumeStatus;
import com.agui.community.core.interrupt.SuccessOutcome;
import com.agui.community.core.message.Message;
import com.agui.community.core.message.Role;
import com.example.ucpagent.graph.OrderState;
import com.example.ucpagent.graph.OrderStatus;
import com.example.ucpagent.graph.OrderTopology;
import com.example.ucpagent.ucp.UcpModels.Product;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.GraphResult;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.agui.sdk.AGUIAgentBase;
import org.bsc.langgraph4j.agui.sdk.AGUINodeOutput;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.streaming.StreamingOutput;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.text.NumberFormat;
import java.util.*;
import java.util.concurrent.Flow;

/**
 * Expõe o grafo de compra como agente AG-UI.
 * <ul>
 *   <li>STEP_* e CUSTOM (ucp_call/llm_call) vêm do TraceHook, via customDispatcher.</li>
 *   <li>Por node concluído: STATE_SNAPSHOT, CUSTOM edge e as mensagens/ferramentas do chat.</li>
 *   <li>Pausas do interruptBefore: antes de select (cliente marca os produtos; payload {items}) e antes de
 *       payment (aprovação). O front responde com {@code resume[{interruptId, status, payload}]} na mesma thread.</li>
 * </ul>
 * Cada mensagem nova do usuário abre uma execução nova do grafo (thread do grafo = threadId#n);
 * a resposta de aprovação reutiliza a thread pausada.
 */
public class OrderAgent extends AGUIAgentBase {

    static final String SELECT_PREFIX = "select-";
    static final int MAX_CARDS = 12;
    static final String APPROVE_PREFIX = "approve-";

    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final NumberFormat BRL = NumberFormat.getCurrencyInstance(Locale.of("pt", "BR"));

    private final CompiledGraph<OrderState> orderGraph;

    public OrderAgent(String id, CompiledGraph<OrderState> orderGraph) {
        super(id);
        this.orderGraph = orderGraph;
    }

    @Override
    protected CompiledGraph<? extends AgentState> newGraph() {
        return orderGraph;
    }

    // ---- entrada ------------------------------------------------------------------------------

    @Override
    protected RunnableConfig runnableConfig(RunAgentInput input) {
        long runs = input.messages().stream().filter(m -> m.role() == Role.USER).count();
        return RunnableConfig.builder().threadId(input.threadId() + "#" + runs).build();
    }

    @Override
    protected GraphInput graphInput(RunAgentInput input) {
        if (approvalAnswer(input).isPresent()) {
            return GraphInput.resume();
        }
        return GraphInput.args(Map.of(OrderState.USER_QUERY, lastUserMessage(input.messages())));
    }

    /** Resposta ao interrupt: true = resolved, false = cancelled. Vazio = execução normal. */
    private Optional<Boolean> approvalAnswer(RunAgentInput input) {
        if (input.resume() == null || input.resume().isEmpty()) return Optional.empty();
        return Optional.of(input.resume().stream().allMatch(r -> r.status() == ResumeStatus.RESOLVED));
    }

    /** Itens marcados nos cards: payload {"items":[{variantId, quantity}]} da resposta ao interrupt de seleção. */
    private Map<String, Integer> chosenItems(RunAgentInput input) {
        for (var r : input.resume()) {
            if (r.interruptId() == null || !r.interruptId().startsWith(SELECT_PREFIX) || r.payload() == null) continue;
            try {
                // O payload chega como Map ou JsonNode (Jackson 2): reserializa e lê como JSON.
                String json = r.payload() instanceof Map<?, ?> ? JSON.writeValueAsString(r.payload()) : r.payload().toString();
                Map<String, Integer> items = new LinkedHashMap<>();
                JSON.readTree(json).path("items").forEach(n -> {
                    int q = Math.max(1, Math.min(9, n.path("quantity").asInt(1)));
                    if (n.hasNonNull("variantId")) items.put(n.get("variantId").asText(), q);
                });
                return items;
            } catch (RuntimeException e) {
                log.warn("payload de seleção inválido: {}", r.payload());
            }
        }
        return Map.of();
    }

    private boolean isSelectionAnswer(RunAgentInput input) {
        return input.resume().stream().anyMatch(r -> r.interruptId() != null && r.interruptId().startsWith(SELECT_PREFIX));
    }

    @Override
    public Flow.Publisher<? extends Event> run(RunAgentInput input) {
        Optional<Boolean> answer = approvalAnswer(input);
        if (answer.isEmpty()) return super.run(input);

        boolean selection = isSelectionAnswer(input);
        Map<String, Integer> items = selection ? chosenItems(input) : Map.of();
        if (!answer.get() || (selection && items.isEmpty())) {
            return finishWith(input, selection ? "Nenhum produto selecionado. Nada foi comprado."
                    : "Pedido recusado. Nada foi comprado.");
        }
        if (selection) {
            try {
                // A escolha do cliente entra no estado da thread pausada; o resume segue dali.
                orderGraph.updateState(runnableConfig(input), Map.of(OrderState.SELECTED_VARIANT_IDS, List.copyOf(items.keySet()),
                        OrderState.SELECTED_QUANTITIES, items));
            } catch (Exception e) {
                log.error("Falha ao gravar a seleção", e);
                return finishWith(input, "Não consegui registrar a seleção: " + e.getMessage());
            }
        }
        return super.run(input);
    }

    /** Encerra a execução só com uma mensagem, sem retomar o grafo. */
    private Flow.Publisher<? extends Event> finishWith(RunAgentInput input, String message) {
        return subscriber -> {
            subscriber.onSubscribe(new Flow.Subscription() {
                public void request(long n) {}
                public void cancel() {}
            });
            subscriber.onNext(new RunStartedEvent(input.threadId(), input.runId(), null, null,
                    System.currentTimeMillis(), null));
            text(message).forEach(subscriber::onNext);
            subscriber.onNext(new RunFinishedEvent(input.threadId(), input.runId(), new SuccessOutcome(),
                    null, System.currentTimeMillis(), null));
            subscriber.onComplete();
        };
    }

    // ---- saída --------------------------------------------------------------------------------

    @Override
    protected Collection<? extends Event> onNextEvents(RunAgentInput input,
                                                       NodeOutput<? extends AgentState> output) {
        // Eventos já prontos (STEP_*, CUSTOM) despachados pelo TraceHook.
        if (output instanceof AGUINodeOutput<? extends AgentState>) return super.onNextEvents(input, output);
        if (output instanceof StreamingOutput<?>) return List.of();

        String node = output.node();
        if (StateGraphIds.isEnd(node)) return List.of();

        OrderState state = (OrderState) output.state();
        List<Event> events = new ArrayList<>();
        events.add(new StateSnapshotEvent(state.data()));

        String from = StateGraphIds.isStart(node) ? OrderTopology.START : node;
        var edge = OrderTopology.next(from, state);
        if (edge != null) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("from", edge.from());
            v.put("to", edge.to());
            if (edge.label() != null) v.put("label", edge.label());
            events.add(new CustomEvent("edge", v));
        }
        events.addAll(chat(node, state));
        return events;
    }

    private List<Event> chat(String node, OrderState s) {
        return switch (node) {
            case "discovery" -> s.error().isBlank()
                    ? text("Encontrei " + s.candidates().size() + " produtos em " + storeCount(s.candidates())
                            + ". Marque os que quiser levar.")
                    : List.of();
            case "select" -> text("Você escolheu " + s.selectedProducts().stream().mapToInt(p -> s.quantityOf(p.variantId())).sum()
                    + " item(ns): " + s.selectedProducts().stream()
                    .map(p -> s.quantityOf(p.variantId()) + "× " + p.title()).collect(java.util.stream.Collectors.joining("; ")) + ".");
            case "checkout" -> s.error().isBlank()
                    ? text("Carrinho e checkout criados em " + s.checkouts().size() + " loja(s).")
                    : List.of();
            case "errorHandler" -> s.status() == OrderStatus.FAILED
                    ? text("Não consegui concluir: " + s.error())
                    : text("Não achei nada. Tentando de novo com a busca: " + s.userQuery());
            case "fulfillment" -> s.status() == OrderStatus.FULFILLED
                    ? text("Pedido simulado (nenhuma cobrança feita). Envio escolhido por loja: " + s.shippingOptions())
                    : text("Sem opção de envio: " + s.error());
            default -> List.of();
        };
    }

    /** Intercala as lojas (1 de cada por rodada) antes de cortar em MAX_CARDS, para nenhuma sumir da tela. */
    private static List<Product> spread(List<Product> all) {
        Map<String, java.util.Deque<Product>> byStore = new LinkedHashMap<>();
        all.forEach(p -> byStore.computeIfAbsent(p.storeId(), k -> new java.util.ArrayDeque<>()).add(p));
        List<Product> out = new ArrayList<>();
        while (out.size() < MAX_CARDS && byStore.values().stream().anyMatch(d -> !d.isEmpty())) {
            for (var d : byStore.values()) {
                if (!d.isEmpty() && out.size() < MAX_CARDS) out.add(d.poll());
            }
        }
        return out;
    }

    private static String storeCount(List<Product> ps) {
        long n = ps.stream().map(Product::storeId).distinct().count();
        return n + (n == 1 ? " loja" : " lojas");
    }

    private static String names(List<Product> ps) {
        return ps.stream().map(Product::title).collect(java.util.stream.Collectors.joining("; "));
    }

    private static Map<String, Object> card(Product p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("variantId", p.variantId());
        m.put("name", p.title());
        m.put("price", money(p));
        m.put("store", p.storeName());
        if (p.image() != null) m.put("image", p.image());
        if (p.url() != null) m.put("url", p.url());
        return m;
    }

    @Override
    protected Collection<? extends Event> onCompleteEvents(RunAgentInput input, GraphResult result) {
        try {
            return completeEvents(input, result);
        } catch (RuntimeException e) {
            // whenComplete engole exceções: sem isto o stream morre calado.
            log.error("Falha ao montar eventos finais", e);
            return List.of(new RunErrorEvent("falha ao finalizar: " + e.getMessage()));
        }
    }

    private Collection<? extends Event> completeEvents(RunAgentInput input, GraphResult result) {
        String next = nextNode(input);
        if (next == null) return super.onCompleteEvents(input, result);

        OrderState s = orderGraph.getState(runnableConfig(input)).state();
        // O front (useAgUiInterrupts) desenha o cartão a partir de message + metadata.
        Interrupt interrupt;
        if ("select".equals(next)) {
            Map<String, Object> meta = Map.of("kind", "select",
                    "products", spread(s.candidates()).stream().map(OrderAgent::card).toList());
            interrupt = new Interrupt(SELECT_PREFIX + input.threadId(), "interrupt_before:select",
                    "Escolha os produtos que quer levar", null, null, null, meta);
        } else {
            List<Product> items = s.selectedProducts();
            long total = items.stream().mapToLong(p -> p.amount() * s.quantityOf(p.variantId())).sum();
            Map<String, Object> meta = Map.of("kind", "approve",
                    "summary", "Finalizar a compra de " + items.stream().mapToInt(p -> s.quantityOf(p.variantId())).sum() + " item(ns) (simulada, sem cobrança real).",
                    "items", items.stream().map(p -> {
                        Map<String, Object> c = new LinkedHashMap<>(card(p));
                        c.put("quantity", s.quantityOf(p.variantId()));
                        return c;
                    }).toList(),
                    "total", BRL.format(total / 100.0));
            interrupt = new Interrupt(APPROVE_PREFIX + input.threadId(), "interrupt_before:" + next,
                    "Aguardando aprovação antes de " + next, null, null, null, meta);
        }
        List<Event> events = new ArrayList<>();
        events.add(new RunFinishedEvent(input.threadId(), input.runId(),
                new InterruptOutcome(List.of(interrupt)), null, System.currentTimeMillis(), null));
        return events;
    }

    /** interruptBefore não vem como "interrupt" no SDK: detecta o próximo nó pelo checkpoint. */
    private String nextNode(RunAgentInput input) {
        try {
            String next = orderGraph.getState(runnableConfig(input)).nextNodeId();
            return next == null || next.isBlank() || StateGraphIds.isEnd(next) ? null : next;
        } catch (RuntimeException e) {
            return null; // thread terminou: checkpoint liberado
        }
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static String money(Product p) {
        if (p == null) return "";
        return BRL.format(p.amount() / 100.0);
    }

    private static List<Event> text(String content) {
        String id = UUID.randomUUID().toString();
        return List.of(new TextMessageStartEvent(id, Role.ASSISTANT), new TextMessageContentEvent(id, content),
                new TextMessageEndEvent(id));
    }

    private static String lastUserMessage(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role() == Role.USER) return messages.get(i).content();
        }
        return "";
    }

    private static final class StateGraphIds {
        static boolean isStart(String id) { return org.bsc.langgraph4j.StateGraph.START.equals(id); }
        static boolean isEnd(String id) { return id != null && id.contains("END"); }
    }
}
