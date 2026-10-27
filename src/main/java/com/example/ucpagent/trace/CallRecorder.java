package com.example.ucpagent.trace;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Coleta chamadas UCP/LLM feitas dentro de um node. Nodes rodam via node_async (síncrono, na thread
 * do grafo), então o hook de trace drena a lista logo após o node, na mesma thread, e a publica
 * como eventos CUSTOM do AG-UI.
 *
 * InheritableThreadLocal (não ThreadLocal simples): o DiscoveryNode faz fan-out de search_catalog
 * em virtual threads (uma por loja); cada uma precisa escrever na MESMA lista que o drain() do nó
 * principal vai ler. Lista é sincronizada porque os fan-outs escrevem concorrentemente.
 */
@Component
public class CallRecorder {

    public record Call(String event, Map<String, Object> value) {}

    private final InheritableThreadLocal<List<Call>> calls = new InheritableThreadLocal<>() {
        @Override
        protected List<Call> initialValue() {
            return Collections.synchronizedList(new ArrayList<>());
        }
    };

    public void ucp(String storeId, String tool, long durationMs, boolean error, Object payload) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("store", storeId);
        v.put("tool", tool);
        v.put("durationMs", durationMs);
        v.put("error", error);
        v.put("payload", payload);
        calls.get().add(new Call("ucp_call", v));
    }

    public void llm(String task, long durationMs, boolean fallback, Object result) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("model", "deepseek");
        v.put("summary", task);
        v.put("durationMs", durationMs);
        v.put("fallback", fallback);
        v.put("payload", result);
        calls.get().add(new Call("llm_call", v));
    }

    public List<Call> drain() {
        List<Call> out = List.copyOf(calls.get());
        calls.remove();
        return out;
    }
}
