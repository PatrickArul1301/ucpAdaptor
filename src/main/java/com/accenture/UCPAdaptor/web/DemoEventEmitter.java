package com.accenture.UCPAdaptor.web;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.atomic.AtomicReference;

@Component
public class DemoEventEmitter {

    private final AtomicReference<SseEmitter> emitterRef = new AtomicReference<>();

    public SseEmitter getEmitter() {
        return emitterRef.get();
    }

    public void setEmitter(SseEmitter emitter) {
        emitterRef.set(emitter);
    }

    public void clear() {
        emitterRef.set(null);
    }

    public void emit(String eventType, String jsonData) {
        SseEmitter emitter = emitterRef.get();
        if (emitter == null) return;
        try {
            emitter.send(SseEmitter.event().name(eventType).data(jsonData));
        } catch (Exception ignored) {
        }
    }
}
