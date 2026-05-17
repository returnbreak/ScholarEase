package com.kesf.backend.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kesf.backend.entity.PaperEntity;
import com.kesf.backend.entity.PaperLocationsEntity;
import com.kesf.backend.kafka.PaperVectorIndexTask;
import com.kesf.backend.mapper.PaperLocationsMapper;
import com.kesf.backend.mapper.PaperMapper;
import com.kesf.backend.service.ObjectStorageService;
import com.kesf.backend.service.PaperUploadParseProgressService;
import com.kesf.backend.service.PaperVectorSearchIndexService;
import com.kesf.backend.service.ZoteroImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaperVectorIndexFailureHandler {

    private static final int PARSE_STATUS_FAILED_VALUE = 3;
    private static final int MYSQL_LOOKUP_ATTEMPTS = 3;
    private static final long MYSQL_LOOKUP_INTERVAL_MILLIS = 100L;

    private final PaperMapper paperMapper;
    private final PaperLocationsMapper paperLocationsMapper;
    private final PaperUploadParseProgressService progressService;
    private final ObjectStorageService objectStorageService;
    private final ZoteroImportService zoteroImportService;
    private final PaperVectorSearchIndexService searchIndexService;

    public void handleFailure(PaperVectorIndexTask task, RuntimeException exception) {
        if (task == null) {
            log.error("Paper vector index failed with empty task", exception);
            return;
        }

        String traceId = task.getTraceId();
        String paperMd5 = task.getPaperMd5();
        log.error("Paper vector index failed, start cleanup: taskId={}, traceId={}, paperMd5={}",
                task.getTaskId(), traceId, paperMd5, exception);

        runCleanupStep("update upload progress failed", () -> {
            if (StringUtils.hasText(traceId)) {
                progressService.updateParseStatus(traceId, PARSE_STATUS_FAILED_VALUE);
            }
        });

        CleanupTarget target = loadCleanupTarget(paperMd5);
        PaperLocationsEntity locations = target.locations();
        PaperEntity paper = target.paper();

        runCleanupStep("delete MinIO original PDF", () -> {
            if (locations != null && StringUtils.hasText(locations.getMinioOriginalKey())) {
                objectStorageService.deleteObject(locations.getMinioOriginalKey());
            }
        });
        runCleanupStep("delete MinIO parsed artifacts", () -> {
            if (locations != null && StringUtils.hasText(locations.getMinioParsedPrefix())) {
                objectStorageService.deleteObjectsByPrefix(locations.getMinioParsedPrefix());
            }
        });
        runCleanupStep("delete MinIO trace artifacts", () -> {
            String tracePrefix = tracePrefix(task, locations);
            if (StringUtils.hasText(tracePrefix)) {
                objectStorageService.deleteObjectsByPrefix(tracePrefix);
            }
        });
        runCleanupStep("delete Zotero item", () -> {
            if (locations != null && StringUtils.hasText(locations.getZoteroItemKey())) {
                zoteroImportService.deleteItem(locations.getZoteroItemKey());
            }
        });
        runCleanupStep("delete Elasticsearch partial vectors", () -> {
            if (StringUtils.hasText(paperMd5)) {
                searchIndexService.deleteByPaperMd5(paperMd5);
            }
        });
        runCleanupStep("delete paper_locations record", () -> {
            if (locations != null && locations.getId() != null) {
                paperLocationsMapper.deleteById(locations.getId());
            } else if (StringUtils.hasText(paperMd5)) {
                paperLocationsMapper.delete(new LambdaQueryWrapper<PaperLocationsEntity>()
                        .eq(PaperLocationsEntity::getPaperMd5, paperMd5));
            }
        });
        runCleanupStep("delete papers record", () -> {
            if (paper != null && paper.getPaperId() != null) {
                paperMapper.deleteById(paper.getPaperId());
            } else if (StringUtils.hasText(paperMd5)) {
                paperMapper.delete(new LambdaQueryWrapper<PaperEntity>()
                        .eq(PaperEntity::getPaperMd5, paperMd5));
            }
        });
    }

    private CleanupTarget loadCleanupTarget(String paperMd5) {
        if (!StringUtils.hasText(paperMd5)) {
            return new CleanupTarget(null, null);
        }

        PaperEntity paper = null;
        PaperLocationsEntity locations = null;
        for (int attempt = 1; attempt <= MYSQL_LOOKUP_ATTEMPTS; attempt++) {
            paper = paperMapper.selectOne(new LambdaQueryWrapper<PaperEntity>()
                    .eq(PaperEntity::getPaperMd5, paperMd5)
                    .last("LIMIT 1"));
            locations = paperLocationsMapper.selectOne(new LambdaQueryWrapper<PaperLocationsEntity>()
                    .eq(PaperLocationsEntity::getPaperMd5, paperMd5)
                    .last("LIMIT 1"));
            if (paper != null || locations != null || attempt == MYSQL_LOOKUP_ATTEMPTS) {
                return new CleanupTarget(paper, locations);
            }
            sleepBeforeRetry();
        }
        return new CleanupTarget(paper, locations);
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(MYSQL_LOOKUP_INTERVAL_MILLIS);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    private String tracePrefix(PaperVectorIndexTask task, PaperLocationsEntity locations) {
        if (locations != null) {
            String prefix = extractTracePrefix(locations.getMinioParsedPrefix());
            if (StringUtils.hasText(prefix)) {
                return prefix;
            }
            prefix = extractTracePrefix(locations.getMinioOriginalKey());
            if (StringUtils.hasText(prefix)) {
                return prefix;
            }
        }

        String prefix = extractTracePrefix(task.getContentListObjectKey());
        if (StringUtils.hasText(prefix)) {
            return prefix;
        }
        if (StringUtils.hasText(task.getTraceId())) {
            return "uploads/" + task.getTraceId().replaceAll("[^A-Za-z0-9_-]", "-") + "/";
        }
        return null;
    }

    private String extractTracePrefix(String objectKey) {
        if (!StringUtils.hasText(objectKey)) {
            return null;
        }
        int firstSlash = objectKey.indexOf('/');
        if (firstSlash < 0) {
            return null;
        }
        int secondSlash = objectKey.indexOf('/', firstSlash + 1);
        if (secondSlash < 0) {
            return null;
        }
        return objectKey.substring(0, secondSlash + 1);
    }

    private void runCleanupStep(String step, Runnable cleanup) {
        try {
            cleanup.run();
        } catch (RuntimeException exception) {
            log.error("Paper vector index failure cleanup step failed: step={}", step, exception);
        }
    }

    private record CleanupTarget(PaperEntity paper, PaperLocationsEntity locations) {
    }
}
