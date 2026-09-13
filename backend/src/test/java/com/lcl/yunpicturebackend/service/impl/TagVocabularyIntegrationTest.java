package com.lcl.yunpicturebackend.service.impl;

import com.lcl.yunpicturebackend.common.BaseResponse;
import com.lcl.yunpicturebackend.controller.PictureController;
import com.lcl.yunpicturebackend.domain.dto.picture.PictureEditByBatchRequest;
import com.lcl.yunpicturebackend.domain.po.Picture;
import com.lcl.yunpicturebackend.domain.po.Space;
import com.lcl.yunpicturebackend.domain.po.Tag;
import com.lcl.yunpicturebackend.domain.po.User;
import com.lcl.yunpicturebackend.domain.vo.PictureTagCategory;
import com.lcl.yunpicturebackend.enums.TagTypeEnum;
import com.lcl.yunpicturebackend.service.IPictureService;
import com.lcl.yunpicturebackend.service.ISpaceService;
import com.lcl.yunpicturebackend.service.ITagService;
import com.lcl.yunpicturebackend.service.IUserService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T5 标签词表回归（docs/plan.md）：种子迁移在表、/tag_category 返回结构兼容、
 * 词表 upsert 的空白词跳过与同词去重。
 * 编辑接线（批编/单编同事务 upsert 计数）的红→绿测试随 Task 3 接线一并落在本类。
 * 断言用 contains 而非精确 size：本地库被多轮测试共享，词表可生长。
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TagVocabularyIntegrationTest {

    private static final List<String> SEED_TAGS = Arrays.asList(
            "热门", "搞笑", "生活", "高清", "艺术", "校园", "背景", "简历", "创意");
    private static final List<String> SEED_CATEGORIES = Arrays.asList(
            "模板", "电商", "表情包", "素材", "海报");

    @Autowired
    private ITagService tagService;
    @Autowired
    private IPictureService pictureService;
    @Autowired
    private ISpaceService spaceService;
    @Autowired
    private IUserService userService;
    @Autowired
    private PictureController pictureController;

    private User owner;
    private Space space;
    private Long pictureId;
    private final String uniqueSuffix = String.valueOf(System.currentTimeMillis());

    @BeforeAll
    void setUpFixtures() {
        owner = new User();
        owner.setUserAccount("tag-vocab-" + uniqueSuffix);
        owner.setUserPassword(userService.getEncryptPassword("password123"));
        owner.setUserName("tag-vocab-owner");
        userService.save(owner);
        space = new Space();
        space.setSpaceName("tag-vocab-space");
        space.setSpaceType(0);
        space.setSpaceLevel(0);
        space.setUserId(owner.getId());
        spaceService.save(space);
        Picture picture = new Picture();
        picture.setName("tag-vocab-picture");
        picture.setUrl("https://example.com/tag-vocab-" + uniqueSuffix + ".png");
        picture.setUserId(owner.getId());
        picture.setSpaceId(space.getId());
        pictureService.save(picture);
        pictureId = picture.getId();
    }

    @Test
    void seedMigratedAndTagCategoryDynamic() {
        BaseResponse<PictureTagCategory> response = pictureController.listPictureTagCategory();
        assertNotNull(response);
        PictureTagCategory data = response.getData();
        assertNotNull(data);
        // 返回结构逐字段兼容：仍是 tagList + categoryList 两个字符串列表
        assertNotNull(data.getTagList());
        assertNotNull(data.getCategoryList());
        assertTrue(data.getTagList().containsAll(SEED_TAGS), "9 个种子标签必须全部在动态词表中");
        assertTrue(data.getCategoryList().containsAll(SEED_CATEGORIES), "5 个种子分类必须全部在动态词表中");
    }

    @Test
    void batchEditUpsertsVocabularyAndCounts() {
        long beforeHot = usageCountOf("热门", TagTypeEnum.TAG);
        long beforePoster = usageCountOf("海报", TagTypeEnum.CATEGORY);
        String newTag = "T5新词" + uniqueSuffix;

        PictureEditByBatchRequest request = new PictureEditByBatchRequest();
        request.setPictureIdList(Collections.singletonList(pictureId));
        request.setSpaceId(space.getId());
        request.setTags(Arrays.asList("热门", newTag));
        request.setCategory("海报");
        pictureService.editPictureByBatch(request, owner);

        // 已有词 +1，新词注册且起始 1，一次编辑调用只计 1（不随图片数放大）
        assertEquals(beforeHot + 1, usageCountOf("热门", TagTypeEnum.TAG));
        assertEquals(beforePoster + 1, usageCountOf("海报", TagTypeEnum.CATEGORY));
        Tag created = tagService.lambdaQuery()
                .eq(Tag::getName, newTag)
                .eq(Tag::getType, TagTypeEnum.TAG.getValue())
                .one();
        assertNotNull(created, "编辑写入的新词必须自动注册进词表");
        assertEquals(1L, created.getUsageCount());
        // 新词对 /tag_category 立即可见（词表生长闭环）
        assertTrue(pictureController.listPictureTagCategory().getData().getTagList().contains(newTag));
    }

    @Test
    void upsertSkipsBlankAndDeduplicates() {
        long before = usageCountOf("热门", TagTypeEnum.TAG);
        tagService.upsertVocabulary(Arrays.asList("  ", "", "  热门  ", "热门"), TagTypeEnum.TAG);
        assertEquals(before + 1, usageCountOf("热门", TagTypeEnum.TAG), "空白词跳过、同词去重后只计 1 次");
        long blankRows = tagService.lambdaQuery()
                .eq(Tag::getType, TagTypeEnum.TAG.getValue())
                .and(q -> q.eq(Tag::getName, "").or().eq(Tag::getName, "  "))
                .count();
        assertEquals(0L, blankRows, "不得写入空白词条");
    }

    private long usageCountOf(String name, TagTypeEnum type) {
        Tag tag = tagService.lambdaQuery()
                .eq(Tag::getName, name)
                .eq(Tag::getType, type.getValue())
                .one();
        return tag == null ? 0L : tag.getUsageCount();
    }
}
