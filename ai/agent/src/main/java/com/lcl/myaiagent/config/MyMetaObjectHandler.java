package com.lcl.myaiagent.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * MyBatis Plus 自动填充处理器
 * 用于在插入和更新操作时自动填充 createTime 和 updateTime 字段
 */
@Component
public class MyMetaObjectHandler implements MetaObjectHandler {

    /**
     * 插入数据时自动填充创建时间和更新时间
     * 当实体类中包含 createTime 和 updateTime 字段且未手动赋值时，自动填充当前时间
     *
     * @param metaObject MyBatis Plus 的元数据对象，包含实体类字段信息
     */
    @Override
    public void insertFill(MetaObject metaObject) {
        this.strictInsertFill(metaObject, "createTime", Date.class, new Date());
        this.strictInsertFill(metaObject, "updateTime", Date.class, new Date());
    }

    /**
     * 更新数据时自动填充更新时间
     * 当实体类中包含 updateTime 字段且未手动赋值时，自动填充当前时间
     *
     * @param metaObject MyBatis Plus 的元数据对象，包含实体类字段信息
     */
    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updateTime", Date.class, new Date());
    }
}
