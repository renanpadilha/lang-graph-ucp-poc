package com.example.ucpagent.graph;

import org.bsc.langgraph4j.checkpoint.PostgresSaver;
import org.bsc.langgraph4j.spring.ai.serializer.jackson.SpringAIJacksonStateSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/** Estado das threads em Postgres, igual em todos os ambientes: pausa sobrevive a reinício. */
@Configuration
public class CheckpointerConfig {

    @Bean
    public PostgresSaver checkpointSaver(DataSource dataSource) throws Exception {
        return PostgresSaver.builder()
                .datasource(dataSource)
                .stateSerializer(new SpringAIJacksonStateSerializer<>(OrderState::new))
                .createTables(true)
                .build();
    }
}
