package com.agenticrag.service;

import com.agenticrag.rag.retrieve.RetrievedChunk;

import java.util.List;

public interface ChatEventSink {

    default void onThinking(String text) {
    }

    default void onDelta(String text) {
    }

    default void onDone(List<RetrievedChunk> sources) {
    }

    default void onError(String message) {
    }
}
