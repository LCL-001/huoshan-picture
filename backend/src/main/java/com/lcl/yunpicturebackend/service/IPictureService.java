package com.lcl.yunpicturebackend.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lcl.yunpicturebackend.api.aliyunai.model.CreateOutPaintingTaskResponse;
import com.lcl.yunpicturebackend.api.aliyunai.model.GetOutPaintingTaskResponse;
import com.lcl.yunpicturebackend.common.DeleteRequest;
import com.lcl.yunpicturebackend.domain.dto.picture.*;
import com.lcl.yunpicturebackend.domain.po.Picture;
import com.baomidou.mybatisplus.extension.service.IService;
import com.lcl.yunpicturebackend.domain.po.User;
import com.lcl.yunpicturebackend.domain.vo.PictureAiTagSuggestionVO;
import com.lcl.yunpicturebackend.domain.vo.PictureTrashResultVO;
import com.lcl.yunpicturebackend.domain.vo.PictureTrashVO;
import com.lcl.yunpicturebackend.domain.vo.PictureVO;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * <p>
 * 图片 服务类
 * </p>
 *
 * @author author
 * @since 2026-04-18
 */
public interface IPictureService extends IService<Picture> {

    /**
     * 上传图片
     *
     * @param inputSource  文件
     * @param pictureUploadRequest 上传图片请求
     * @param loginUser 登录用户
     * @return 上传图片结果
     */
    PictureVO uploadPicture(Object inputSource, PictureUploadRequest pictureUploadRequest, User loginUser);

    /**
     * 批量上传图片
     *
     * @param pictureUploadByBatchRequest 批量上传图片请求
     * @param loginUser                   登录用户
     * @return 批量上传图片结果
     */
    int uploadPictureByBatch(PictureUploadByBatchRequest pictureUploadByBatchRequest, User loginUser);

    /**
     * 获取查询条件
     *
     * @param pictureQueryRequest 查询条件
     * @return 查询条件
     */
    QueryWrapper<Picture> getQueryWrapper(PictureQueryRequest pictureQueryRequest);

    /**
     * 获取图片封装类
     *
     * @param picture 图片
     * @param request 请求
     * @return 图片封装类
     */
    PictureVO getPictureVO(Picture picture, HttpServletRequest request);

    /**
     * 获取图片分页封装类
     *
     * @param picturePage 图片分页
     * @param request 请求
     * @return 图片分页封装类
     */
    Page<PictureVO> getPictureVOPage(Page<Picture> picturePage, HttpServletRequest request);

    /**
     * 验证图片
     *
     * @param picture 图片
     */
    void validPicture(Picture picture);

    /**
     * 图片审核
     *
     * @param pictureReviewRequest 图片审核请求
     * @param loginUser 登录用户
     */
    void doPictureReview(PictureReviewRequest pictureReviewRequest, User loginUser);

    /**
     * 填充审核参数
     *
     * @param picture 图片
     * @param loginUser 登录用户
     */
    void fillReviewParams(Picture picture, User loginUser);

    /**
     * 获取图片分页封装类（缓存）
     *
     * @param pictureQueryRequest 查询条件
     * @param request 请求
     * @return 图片分页封装类
     */
    Page<PictureVO> listPictureVOByPageByCache(PictureQueryRequest pictureQueryRequest, HttpServletRequest request);


    /**
     * 删除图片
     *
     * @param deleteRequest 删除请求
     * @param request 请求
     */
    void deletePicture(DeleteRequest deleteRequest, HttpServletRequest request);

    /**
     * 更新图片
     *
     * @param pictureUpdateRequest 更新请求
     * @param request 请求
     */
    void updatePicture(PictureUpdateRequest pictureUpdateRequest, HttpServletRequest request);

    /**
     * 编辑图片
     *
     * @param pictureEditRequest 编辑请求
     * @param request 请求
     */
    void editPicture(PictureEditRequest pictureEditRequest, HttpServletRequest request);

    /**
     * 检查图片权限
     *
     * @param loginUser 登录用户
     * @param picture 图片
     */
    void checkPictureAuth(User loginUser, Picture picture);

    /**
     * 检查图片权限（指定所需权限码，空间内按成员角色判断）
     *
     * @param loginUser 登录用户
     * @param picture 图片
     * @param permission 所需权限码（见 SpaceUserPermissionConstant）
     */
    void checkPictureAuth(User loginUser, Picture picture, String permission);

    /**
     * 获取图片分页封装类（不缓存）
     *
     * @param pictureQueryRequest 查询条件
     * @param request 请求
     * @return 图片分页封装类
     */
    Page<Picture> listPictureVOByPage(PictureQueryRequest pictureQueryRequest, HttpServletRequest request);

    /**
     * 根据颜色搜索图片
     *
     * @param spaceId 空间id
     * @param picColor 图片颜色
     * @param loginUser 登录用户
     * @return 图片封装类
     */
    List<PictureVO> searchPictureByColor(Long spaceId, String picColor, User loginUser);

    /**
     * 批量编辑图片
     *
     * @param pictureEditByBatchRequest 批量编辑图片请求
     * @param loginUser                 登录用户
     */
    void editPictureByBatch(PictureEditByBatchRequest pictureEditByBatchRequest, User loginUser);

    /**
     * 创建图片扩展任务
     *
     * @param createPictureOutPaintingTaskRequest 创建图片扩展任务请求
     * @param loginUser                           登录用户
     * @return 创建外画任务结果
     */
    CreateOutPaintingTaskResponse createOutPaintingTask(CreatePictureOutPaintingTaskRequest createPictureOutPaintingTaskRequest, User loginUser);

    /**
     * 查询图片扩展任务结果（校验任务归属）
     *
     * @param taskId    任务 id
     * @param loginUser 登录用户
     * @return 外画任务结果
     */
    GetOutPaintingTaskResponse getOutPaintingTask(String taskId, User loginUser);

    /**
     * AI 打标：逐张看图给出标签/分类建议（**仅管理员**，只读——不写库、不动审核状态）。
     *
     * @param pictureAiTagRequest 要打标的图片 id 列表（单次上限见 app.ai.vision.max-per-request）
     * @param loginUser           调用者（控制器已用 @AuthCheck 限定管理员）
     * @return 每张一条建议，单张失败/超时只降级该条
     */
    List<PictureAiTagSuggestionVO> suggestAiTags(PictureAiTagRequest pictureAiTagRequest, User loginUser);

    /**
     * AI 打标：应用（管理员确认后的）建议——**只写 tags 与 category**，
     * 审核状态与 reviewerId/reviewMessage/reviewTime 一律不动（公共图库的图不能被"打回待审核"）。
     *
     * @param pictureAiTagApplyRequest 逐张要写入的标签/分类（空值表示该项不改）
     * @param loginUser                调用者（控制器已用 @AuthCheck 限定管理员）
     * @return 每张一条写入结果
     */
    List<PictureAiTagSuggestionVO> applyAiTags(PictureAiTagApplyRequest pictureAiTagApplyRequest, User loginUser);

    /**
     * 回收站列表（T50）：管理员看全部，普通用户看"公共图库本人上传的 + 我有 picture:delete 的空间里的"。
     *
     * @param queryRequest 分页参数
     * @param request      请求（取登录用户）
     * @return 回收站里图片的分页视图
     */
    Page<PictureTrashVO> listTrash(PictureTrashQueryRequest queryRequest, HttpServletRequest request);

    /**
     * 从回收站恢复（T50）：逐张复核权限与空间配额，恢复后名额回补、清列表缓存；逐条上报结果，
     * 一张失败不影响同批其它张。
     *
     * @param batchRequest 要恢复的图片 id 列表
     * @param request      请求（取登录用户）
     * @return 逐张结果
     */
    List<PictureTrashResultVO> restorePictures(PictureTrashBatchRequest batchRequest, HttpServletRequest request);

    /**
     * 从回收站彻底删除（T50）：逐张复核权限，物理删行后按引用计数清 COS；逐条上报结果。
     *
     * @param batchRequest 要彻底删除的图片 id 列表
     * @param request      请求（取登录用户）
     * @return 逐张结果
     */
    List<PictureTrashResultVO> purgePictures(PictureTrashBatchRequest batchRequest, HttpServletRequest request);

    /**
     * 彻底删除超过保留期的回收站记录（T51，系统任务）：分批取过期行，物理删行后按引用计数清 COS。
     * **不做逐张权限校验**——这是系统级清理，不是用户操作。
     *
     * @param retentionDays 保留天数
     * @param limit         单次最多处理条数（分批，避免一次拉太多）
     * @return 实际彻底删除的条数
     */
    int purgeExpiredTrash(int retentionDays, int limit);
}
