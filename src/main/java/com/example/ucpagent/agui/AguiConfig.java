package com.example.ucpagent.agui;

import com.agui.json.AGUIJacksonSerializer;
import com.example.ucpagent.graph.OrderState;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.agui.sdk.AGUIAgentRegistry;
import org.bsc.langgraph4j.agui.sdk.AGUISSEController;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AguiConfig {

    @Bean
    public AGUIAgentRegistry aguiAgentRegistry(CompiledGraph<OrderState> orderGraph) {
        return new AGUIAgentRegistry(new OrderAgent("order", orderGraph));
    }

    /** POST /sse/{agentId} (rota fixa do langgraph4j). */
    @Bean
    public AGUISSEController aguiSseController(AGUIAgentRegistry registry) {
        return new AGUISSEController(registry, new AGUIJacksonSerializer());
    }
}
