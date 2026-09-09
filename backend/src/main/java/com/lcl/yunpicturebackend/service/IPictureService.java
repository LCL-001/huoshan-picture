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
     * 根据URL查询图片
     * @param url 图片URL
     * @return 图片对象
     */
    Picture getByUrl(String url);

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
}
