package com.example.ucpagent.agui;

import com.agui.community.core.agent.RunAgentInput;
import com.agui.community.core.event.Event;
import com.agui.community.core.event.RunFinishedEvent;
import com.agui.community.core.event.StateSnapshotEvent;
import com.agui.community.core.interrupt.Interrupt;
import com.agui.community.core.interrupt.InterruptOutcome;
import com.agui.community.core.message.Message;
import com.agui.community.core.message.Role;
import com.example.ucpagent.graph.OrderState;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.GraphResult;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.agui.sdk.AGUIAgentBase;
import org.bsc.langgraph4j.state.AgentState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Expõe o grafo de compra como agente AG-UI: STEP_* (via hook) + STATE_SNAPSHOT a cada nó. */
public class OrderAgent extends AGUIAgentBase {

    private final CompiledGraph<OrderState> orderGraph;

    public OrderAgent(String id, CompiledGraph<OrderState> orderGraph) {
        super(id);
        this.orderGraph = orderGraph;
    }

    @Override
    protected CompiledGraph<? extends AgentState> newGraph() {
        return orderGraph;
    }

    @Override
    protected GraphInput graphInput(RunAgentInput input) {
        if (input.resume() != null && !input.resume().isEmpty()) {
            return GraphInput.resume();
        }
        String query = lastUserMessage(input.messages());
        return GraphInput.args(Map.of(OrderState.USER_QUERY, query));
    }

    @Override
    protected Collection<? extends Event> onNextEvents(RunAgentInput input,
                                                       NodeOutput<? extends AgentState> output) {
        List<Event> events = new ArrayList<>(super.onNextEvents(input, output));
        events.add(new StateSnapshotEvent(output.state().data()));
        return events;
    }

    /** Pausa do interruptBefore não vem como "interrupt" no SDK: detecta pelo próximo nó no checkpoint. */
    @Override
    protected Collection<? extends Event> onCompleteEvents(RunAgentInput input, GraphResult result) {
        String next = nextNode(input);
        if (next == null) {
            return super.onCompleteEvents(input, result);
        }
        Interrupt interrupt = new Interrupt("approve-" + input.threadId(), "interrupt_before:" + next,
                "Aguardando aprovação antes de " + next);
        return List.of(new RunFinishedEvent(input.threadId(), input.runId(),
                new InterruptOutcome(List.of(interrupt)), null, null, null));
    }

    private String nextNode(RunAgentInput input) {
        try {
            String next = orderGraph.getState(runnableConfig(input)).nextNodeId();
            return next == null || next.isBlank() || next.contains("END") ? null : next;
        } catch (RuntimeException e) {
            return null; // thread terminou: checkpoint liberado
        }
    }

    private static String lastUserMessage(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role() == Role.USER) return messages.get(i).content();
        }
        return "";
    }
}
