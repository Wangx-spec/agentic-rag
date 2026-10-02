package com.agenticrag.api;

import com.agenticrag.memory.entity.EntityMemoryService;
import com.agenticrag.memory.longterm.LongTermMemoryRepository;
import com.agenticrag.memory.longterm.LongTermMemoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 记忆管理端点（M9 F8）：查询/清除某 userId 的实体画像与长期记忆。
 * <p>
 * 仅 jdbc 记忆模式装配；无鉴权（M8 拦截器统一覆盖，见方案已知边界）。
 */
@RestController
@RequestMapping("/api/memory")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rag.memory", name = "type", havingValue = "jdbc")
public class MemoryAdminController {

    private final EntityMemoryService entityMemoryService;
    private final LongTermMemoryService longTermMemoryService;

    @GetMapping("/profile/{userId}")
    public ResponseEntity<Map<String, String>> getProfile(@PathVariable long userId) {
        return ResponseEntity.ok(entityMemoryService.loadEntries(userId));
    }

    @DeleteMapping("/profile/{userId}")
    public ResponseEntity<Void> deleteProfile(@PathVariable long userId) {
        entityMemoryService.clear(userId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/longterm/{userId}")
    public ResponseEntity<List<LongTermMemoryRepository.MemoryEntry>> getLongTerm(@PathVariable long userId) {
        return ResponseEntity.ok(longTermMemoryService.listAll(userId));
    }

    @DeleteMapping("/longterm/{userId}")
    public ResponseEntity<Void> deleteLongTerm(@PathVariable long userId) {
        longTermMemoryService.clearAll(userId);
        return ResponseEntity.noContent().build();
    }
}
