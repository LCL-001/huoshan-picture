package com.lcl.yunpicturebackend.api.imagesearch;

import com.lcl.yunpicturebackend.api.imagesearch.model.ImageSearchResult;
import com.lcl.yunpicturebackend.api.imagesearch.sub.GetImageFirstUrlApi;
import com.lcl.yunpicturebackend.api.imagesearch.sub.GetImageListApi;
import com.lcl.yunpicturebackend.api.imagesearch.sub.GetImagePageUrlApi;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class ImageSearchApiFacade {

    /**
     * 搜索图片
     *
     * @param imageUrl
     * @return
     */
    public static List<ImageSearchResult> searchImage(String imageUrl) {
        String imagePageUrl = GetImagePageUrlApi.getImagePageUrl(imageUrl);
        String imageFirstUrl = GetImageFirstUrlApi.getImageFirstUrl(imagePageUrl);
        List<ImageSearchResult> imageList = GetImageListApi.getImageList(imageFirstUrl);
        return imageList;
    }
}
