package com.example.ucpagent.graph;

import com.example.ucpagent.trace.CallRecorder;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/** Wrapper fino: sem chave DeepSeek, devolve o fallback determinístico. */
@Component
public class Llm {

    private final ChatClient chat;
    private final boolean enabled;
    private final CallRecorder recorder;

    public Llm(ChatModel model, @Value("${spring.ai.deepseek.api-key}") String apiKey, CallRecorder recorder) {
        this.recorder = recorder;
        this.chat = ChatClient.create(model);
        this.enabled = !"none".equals(apiKey) && !apiKey.isBlank();
    }

    public <T> T structured(String prompt, Class<T> type, Supplier<T> fallback) {
        long started = System.nanoTime();
        if (!enabled) {
            T out = fallback.get();
            recorder.llm(type.getSimpleName(), 0, true, out);
            return out;
        }
        T out = chat.prompt(prompt).call().entity(type);
        recorder.llm(type.getSimpleName(), (System.nanoTime() - started) / 1_000_000, false, out);
        return out;
    }
}
