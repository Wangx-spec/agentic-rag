package com.agenticrag.agent;

import com.agenticrag.llm.dto.ChatMessage;
import com.agenticrag.llm.dto.ToolSchema;
import com.agenticrag.rag.retrieve.RetrievedChunk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;



public class AgentContext {

    private final List<ChatMessage> messages;
    private final List<ToolSchema> availableTools;
    private final int maxRounds;
    private int currentRound;
    private final List<String> stateTrajectory;
    private final Map<Long, RetrievedChunk> sourcesByChunkId;


    public AgentContext(List<ChatMessage> messages, List<ToolSchema> availableTools, int maxRounds) {
        this.messages = new ArrayList<>(messages);
        this.availableTools = Collections.unmodifiableList(new ArrayList<>(availableTools));
        this.maxRounds = maxRounds;
        this.currentRound = 0;
        this.stateTrajectory = new ArrayList<>();
        this.sourcesByChunkId = new LinkedHashMap<>();
    }


    public List<ChatMessage> getMessages() {
        return messages;
    }

    public void addMessage(ChatMessage message) {
        messages.add(message);
    }

    public List<ToolSchema> getAvailableTools() {
        return availableTools;
    }

    public int getMaxRounds() {
        return maxRounds;
    }

    public int getCurrentRound() {
        return currentRound;
    }

    public void incrementRound() {
        currentRound++;
    }

    public boolean isMaxRoundsReached() {
        return currentRound >= maxRounds;
    }

    public void recordState(AgentState state) {
        stateTrajectory.add(state.name());
    }

    public List<String> getStateTrajectory() {
        return Collections.unmodifiableList(stateTrajectory);
    }

    public void addSources(List<RetrievedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }

        for (RetrievedChunk chunk : chunks) {
            if (chunk != null && chunk.chunkId() != null) {
                sourcesByChunkId.putIfAbsent(chunk.chunkId(), chunk);
            }
        }
    }

    public List<RetrievedChunk> getSources() {
        return List.copyOf(sourcesByChunkId.values());
    }
}
