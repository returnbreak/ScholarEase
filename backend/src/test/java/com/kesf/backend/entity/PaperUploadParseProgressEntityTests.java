package com.kesf.backend.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class PaperUploadParseProgressEntityTests {

    @Test
    void mapsProgressTableAndFields() throws Exception {
        Class<?> entityClass = Class.forName("com.kesf.backend.entity.PaperUploadParseProgressEntity");

        assertThat(entityClass.getAnnotation(TableName.class).value()).isEqualTo("paper_upload_parse_progress");
        assertThat(tableId(entityClass, "traceId").value()).isEqualTo("trace_id");
        assertThat(tableField(entityClass, "paperMd5").value()).isEqualTo("paper_md5");
        assertThat(tableField(entityClass, "filename").value()).isEqualTo("filename");
        assertThat(tableField(entityClass, "submissionTime").value()).isEqualTo("submission_time");
        assertThat(tableField(entityClass, "parseStatus").value()).isEqualTo("parse_status");
        assertThat(entityClass.getDeclaredField("submissionTime").getType()).isEqualTo(LocalDateTime.class);
        assertThat(entityClass.getDeclaredField("parseStatus").getType()).isEqualTo(Integer.class);
    }

    private static TableId tableId(Class<?> entityClass, String fieldName) throws Exception {
        Field field = entityClass.getDeclaredField(fieldName);
        return field.getAnnotation(TableId.class);
    }

    private static TableField tableField(Class<?> entityClass, String fieldName) throws Exception {
        Field field = entityClass.getDeclaredField(fieldName);
        return field.getAnnotation(TableField.class);
    }
}
