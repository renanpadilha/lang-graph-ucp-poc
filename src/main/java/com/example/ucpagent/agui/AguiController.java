package com.example.ucpagent.agui;

import org.bsc.langgraph4j.agui.sdk.AGUISSEController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Mesma rota do front (/api/agui), delegando ao controller AG-UI do langgraph4j. */
@RestController
@RequestMapping("/api")
public class AguiController {

    private final AGUISSEController sse;

    public AguiController(AGUISSEController sse) {
        this.sse = sse;
    }

    @PostMapping(value = "/agui", produces = "text/event-stream")
    public ResponseEntity<SseEmitter> run(@RequestBody String body) throws Exception {
        return sse.streamDataWithSseEmitter("order", body);
    }
}
