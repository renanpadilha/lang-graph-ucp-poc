package com.example.ucpagent.graph;

import com.agui.community.core.event.CustomEvent;
import com.example.ucpagent.trace.CallRecorder;
import org.bsc.langgraph4j.agui.sdk.AGUINodeOutput;
import org.bsc.langgraph4j.hook.NodeHook;

/** Envolve cada node: STEP_STARTED, chamadas UCP/LLM como CUSTOM, STEP_FINISHED. */
public final class TraceHook {
    private TraceHook() {}

    public static NodeHook.WrapCall<OrderState> of(CallRecorder recorder) {
        return (nodeId, state, config, action) -> {
            recorder.drain();
            config.customDispatcher().dispatchAsync(
                    AGUINodeOutput.builder().stepStartedEvent(nodeId).build(nodeId, state));

            var result = action.apply(state, config);

            var calls = recorder.drain();
            if (!calls.isEmpty()) {
                var out = AGUINodeOutput.builder();
                for (var c : calls) {
                    var value = new java.util.LinkedHashMap<String, Object>(c.value());
                    value.put("node", nodeId);
                    out.addEvent(new CustomEvent(c.event(), value));
                }
                config.customDispatcher().dispatchAsync(out.build(nodeId, state));
            }

            return result.whenComplete((r, ex) -> {
                if (ex == null) {
                    config.customDispatcher().dispatchAsync(
                            AGUINodeOutput.builder().stepFinishedEvent(nodeId).build(nodeId, state));
                }
            });
        };
    }
}
