package com.example.ucpagent.graph;

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

    public Llm(ChatModel model, @Value("${spring.ai.deepseek.api-key}") String apiKey) {
        this.chat = ChatClient.create(model);
        this.enabled = !"none".equals(apiKey) && !apiKey.isBlank();
    }

    public <T> T structured(String prompt, Class<T> type, Supplier<T> fallback) {
        if (!enabled) return fallback.get();
        return chat.prompt(prompt).call().entity(type);
    }
}
