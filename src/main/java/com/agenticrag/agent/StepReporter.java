package com.agenticrag.agent;

public interface StepReporter {
    
    default void onThinking(String toolName) {}
    default void onActing(String toolName, String arguments) {}
    default void onObserving(String summary) {}
    default void onFinal(String message) {}
    
}
