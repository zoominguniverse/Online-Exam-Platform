package com.onlineexam.platform.repository;

import com.onlineexam.platform.domain.Identity.Grade;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface GradeMapper {
    @Select("SELECT id, name FROM grades WHERE enabled ORDER BY name, id")
    List<Grade> listEnabled();
}
