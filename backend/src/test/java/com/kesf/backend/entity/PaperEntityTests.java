package com.kesf.backend.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class PaperEntityTests {

    @Test
    void mapsPapersTableWithoutParseStatusField() {
        Class<PaperEntity> entityClass = PaperEntity.class;

        assertThat(entityClass.getAnnotation(TableName.class).value()).isEqualTo("papers");
        assertThat(Arrays.stream(entityClass.getDeclaredFields()).map(field -> field.getName()))
                .doesNotContain("parseStatus");
    }
}
