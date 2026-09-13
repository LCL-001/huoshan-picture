package com.lcl.yunpicturebackend.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.lcl.yunpicturebackend.domain.po.Tag;
import com.lcl.yunpicturebackend.enums.TagTypeEnum;

import java.util.List;

/**
 * <p>
 * 标签词表 服务
 * </p>
 *
 * @author author
 * @since 2026-09-13
 */
public interface ITagService extends IService<Tag> {

    /**
     * 词表 upsert：已存在的词条 usage_count 原子 +1，不存在的注册且 usage_count 起始 1。
     * 空白词跳过、同词去重；通常在编辑图片的事务内被调用，无外层事务时整批自成一个事务。
     *
     * @param names 本词条列表（如一次编辑写入的 tags）
     * @param type  词条类型（tag / category）
     */
    void upsertVocabulary(List<String> names, TagTypeEnum type);

    /**
     * 按类型列出词条名称，按 id 升序（与种子顺序一致，前端展示稳定）。
     *
     * @param type 词条类型
     * @return 词条名称列表
     */
    List<String> listNamesByType(TagTypeEnum type);
}
