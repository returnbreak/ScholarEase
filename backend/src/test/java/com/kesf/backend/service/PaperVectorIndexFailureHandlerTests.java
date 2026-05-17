package com.kesf.backend.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.kesf.backend.entity.PaperEntity;
import com.kesf.backend.entity.PaperLocationsEntity;
import com.kesf.backend.kafka.PaperVectorIndexTask;
import com.kesf.backend.mapper.PaperLocationsMapper;
import com.kesf.backend.mapper.PaperMapper;
import com.kesf.backend.service.impl.PaperVectorIndexFailureHandler;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaperVectorIndexFailureHandlerTests {

    @Test
    void handleFailureMarksProgressFailedAndDeletesUploadedPaperArtifacts() {
        PaperMapper paperMapper = mock(PaperMapper.class);
        PaperLocationsMapper paperLocationsMapper = mock(PaperLocationsMapper.class);
        PaperUploadParseProgressService progressService = mock(PaperUploadParseProgressService.class);
        ObjectStorageService objectStorageService = mock(ObjectStorageService.class);
        ZoteroImportService zoteroImportService = mock(ZoteroImportService.class);
        PaperVectorSearchIndexService searchIndexService = mock(PaperVectorSearchIndexService.class);
        PaperVectorIndexFailureHandler handler = new PaperVectorIndexFailureHandler(
                paperMapper,
                paperLocationsMapper,
                progressService,
                objectStorageService,
                zoteroImportService,
                searchIndexService
        );

        PaperVectorIndexTask task = task();
        PaperEntity paper = new PaperEntity();
        paper.setPaperId(10001L);
        paper.setPaperMd5("md5-001");
        PaperLocationsEntity locations = new PaperLocationsEntity();
        locations.setId(20001L);
        locations.setPaperMd5("md5-001");
        locations.setMinioOriginalKey("uploads/trace-001/original/attention.pdf");
        locations.setMinioParsedPrefix("uploads/trace-001/mineru/");
        locations.setZoteroItemKey("ITEM-KEY-001");
        when(paperMapper.selectOne(any(Wrapper.class))).thenReturn(paper);
        when(paperLocationsMapper.selectOne(any(Wrapper.class))).thenReturn(locations);

        handler.handleFailure(task, new IllegalStateException("Elasticsearch bulk index failed"));

        var inOrder = inOrder(
                progressService,
                objectStorageService,
                zoteroImportService,
                searchIndexService,
                paperLocationsMapper,
                paperMapper
        );
        inOrder.verify(progressService).updateParseStatus("trace-001", 3);
        inOrder.verify(objectStorageService).deleteObject("uploads/trace-001/original/attention.pdf");
        inOrder.verify(objectStorageService).deleteObjectsByPrefix("uploads/trace-001/mineru/");
        inOrder.verify(objectStorageService).deleteObjectsByPrefix("uploads/trace-001/");
        inOrder.verify(zoteroImportService).deleteItem("ITEM-KEY-001");
        inOrder.verify(searchIndexService).deleteByPaperMd5("md5-001");
        inOrder.verify(paperLocationsMapper).deleteById(20001L);
        inOrder.verify(paperMapper).deleteById(10001L);
    }

    @Test
    void handleFailureUsesTaskTracePrefixWhenLocationRecordDoesNotExistYet() {
        PaperMapper paperMapper = mock(PaperMapper.class);
        PaperLocationsMapper paperLocationsMapper = mock(PaperLocationsMapper.class);
        PaperUploadParseProgressService progressService = mock(PaperUploadParseProgressService.class);
        ObjectStorageService objectStorageService = mock(ObjectStorageService.class);
        ZoteroImportService zoteroImportService = mock(ZoteroImportService.class);
        PaperVectorSearchIndexService searchIndexService = mock(PaperVectorSearchIndexService.class);
        PaperVectorIndexFailureHandler handler = new PaperVectorIndexFailureHandler(
                paperMapper,
                paperLocationsMapper,
                progressService,
                objectStorageService,
                zoteroImportService,
                searchIndexService
        );
        when(paperMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(paperLocationsMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        handler.handleFailure(task(), new IllegalStateException("Elasticsearch is unavailable"));

        verify(progressService).updateParseStatus("trace-001", 3);
        verify(objectStorageService).deleteObjectsByPrefix("uploads/trace-001/");
        verify(searchIndexService).deleteByPaperMd5("md5-001");
    }

    private static PaperVectorIndexTask task() {
        PaperVectorIndexTask task = new PaperVectorIndexTask();
        task.setTaskId("trace-001:md5-001");
        task.setTraceId("trace-001");
        task.setPaperMd5("md5-001");
        task.setContentListObjectKey("uploads/trace-001/mineru/content_list_v2.json");
        return task;
    }
}
