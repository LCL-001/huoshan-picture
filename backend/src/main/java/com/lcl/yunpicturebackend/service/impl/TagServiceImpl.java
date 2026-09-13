package com.lcl.yunpicturebackend.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lcl.yunpicturebackend.domain.po.Tag;
import com.lcl.yunpicturebackend.enums.TagTypeEnum;
import com.lcl.yunpicturebackend.exception.ErrorCode;
import com.lcl.yunpicturebackend.exception.ThrowUtils;
import com.lcl.yunpicturebackend.mapper.TagMapper;
import com.lcl.yunpicturebackend.service.ITagService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;

/**
 * <p>
 * 标签词表 服务实现类
 * </p>
 *
 * @author author
 * @since 2026-09-13
 */
@Service
@Slf4j
public class TagServiceImpl extends ServiceImpl<TagMapper, Tag> implements ITagService {

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void upsertVocabulary(List<String> names, TagTypeEnum type) {
        ThrowUtils.throwIf(type == null, ErrorCode.PARAMS_ERROR, "词条类型不能为空");
        if (CollUtil.isEmpty(names)) {
            return;
        }
        // 去空白、去重，保持出现顺序
        LinkedHashSet<String> distinctNames = names.stream()
                .filter(StrUtil::isNotBlank)
                .map(String::trim)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (distinctNames.isEmpty()) {
            return;
        }
        distinctNames.forEach(name -> this.upsertOne(name, type));
    }

    /**
     * 先原子自增（存在即命中，无需先读后写）；未命中再插入，
     * 并发首插撞 uk_name_type 唯一键时退化为自增。
     */
    private void upsertOne(String name, TagTypeEnum type) {
        boolean hit = this.lambdaUpdate()
                .eq(Tag::getName, name)
                .eq(Tag::getType, type.getValue())
                .setSql("usageCount = usageCount + 1")
                .update();
        if (hit) {
            return;
        }
        Tag tag = new Tag();
        tag.setName(name);
        tag.setType(type.getValue());
        tag.setUsageCount(1L);
        try {
            this.save(tag);
        } catch (DuplicateKeyException e) {
            log.info("tag upsert concurrent insert fallback, name={}, type={}", name, type.getValue());
            this.lambdaUpdate()
                    .eq(Tag::getName, name)
                    .eq(Tag::getType, type.getValue())
                    .setSql("usageCount = usageCount + 1")
                    .update();
        }
    }

    @Override
    public List<String> listNamesByType(TagTypeEnum type) {
        ThrowUtils.throwIf(type == null, ErrorCode.PARAMS_ERROR, "词条类型不能为空");
        return this.lambdaQuery()
                .eq(Tag::getType, type.getValue())
                .orderByAsc(Tag::getId)
                .list()
                .stream()
                .map(Tag::getName)
                .collect(Collectors.toList());
    }
}
