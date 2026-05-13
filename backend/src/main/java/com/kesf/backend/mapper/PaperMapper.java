package com.kesf.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kesf.backend.entity.PaperEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PaperMapper extends BaseMapper<PaperEntity> {
}
