package com.example.ucpagent.graph;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class ConcurrencyConfig {

    /** Uma virtual thread por loja consultada em paralelo (IO-bound: HTTP pro MCP de cada loja). */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService ucpFanOutExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
