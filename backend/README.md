# 火山图库（Backend）

基于 Spring Boot 的图片与素材管理平台后端，支持图片上传、空间管理、权限控制、多级缓存、批量抓取、WebSocket 协同编辑和 AI 扩图等功能。

[![Java](https://img.shields.io/badge/Java-17-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-2.7.6-brightgreen.svg)](https://spring.io/projects/spring-boot)

## :sparkles: 项目简介

火山图库是一个面向个人用户和团队的空间化图片素材管理平台。用户可以上传、管理、搜索和编辑图片，团队空间成员可以协作管理图片资源。系统集成了腾讯云 COS 对象存储、Redis + Caffeine 多级缓存、Sa-Token 权限认证、WebSocket 实时协作以及阿里云大模型 AI 能力。

**在线演示：** [http://www.lincode.online](http://www.lincode.online)

## :chart_with_upwards_trend: 核心亮点

| 技术点 | 效果 |
|--------|------|
| Redis + Caffeine 两级缓存 | 100 并发下 QPS 从 1186 提升至 9044（7.6 倍），平均响应时间从 82ms 降至 10ms（8.2 倍） |
| Sa-Token 多租户权限 | 接口级 + 数据级双维度权限控制，有效防止越权访问 |
| CompletableFuture 批量抓图 | Jsoup + 自定义线程池并发下载、上传、入库 |
| WebSocket 协同编辑 | Disruptor 高性能队列 + 事件驱动，多人实时状态同步 |
| AI 扩图 | 集成阿里百炼大模型，异步任务轮询与错误处理 |

## :rocket: 技术栈

### 后端框架
- **Spring Boot 2.7.6** — 应用框架
- **MyBatis-Plus 3.5.15** — ORM 框架，分页插件
- **Sa-Token 1.39.0** — 登录认证 + 角色权限 + 空间级数据权限
- **Knife4j 4.4.0** — OpenAPI 2 接口文档

### 数据库与缓存
- **MySQL 8.x** — 主数据库
- **Redis** — 分布式缓存 + Session 存储（Spring Session）
- **Caffeine 3.1.8** — 本地缓存
- **ShardingSphere JDBC 5.2.0** — 图片表按 spaceId 分表

### 中间件与工具
- **腾讯云 COS** — 对象存储
- **WebSocket** — 实时协同编辑
- **Disruptor 3.4.2** — 高性能无锁队列
- **Jsoup 1.22.1** — HTML 解析，批量图片抓取
- **Hutool 5.8.38** — Java 工具库
- **Lombok** — 代码简化

### 外部服务
- **阿里云 DashScope AI** — AI 扩图（Out Painting）

## :page_facing_up: 功能模块

### 用户模块
- 注册 / 登录 / 登出 / 个人信息编辑
- 管理员用户 CRUD

### 图片模块
- 图片上传（文件上传 / URL 导入）
- 图片信息编辑 / 批量编辑 / 删除
- 图片审核（管理员）
- 标签分类管理
- 颜色搜索 / 关键词搜索
- 批量抓取（Bing 图片）
- AI 扩图

### 空间模块
- 空间创建 / 编辑 / 删除
- 空间级别：普通版 / 专业版 / 旗舰版
- 空间类型：私有空间 / 团队空间
- 容量限制（最大图片数量 / 总大小）
- 空间数据分析（用量 / 分类 / 标签 / 尺寸 / 用户排行）

### 权限模块
- 接口级鉴权：登录态 + 角色校验（AOP 拦截器）
- 数据级权限：空间归属 + 成员角色校验
- 角色体系：用户 / 编辑 / 管理员 / 超级管理员

### 实时协作
- WebSocket 图片编辑状态同步
- 编辑事件广播
- 连接生命周期管理

## 📁 项目结构

```
yun-picture-backend/
├── src/main/java/com/lcl/yunpicturebackend/
│   ├── annotation/          # 自定义注解（@AuthCheck）
│   ├── aop/                 # AOP 切面（权限拦截器）
│   ├── api/                 # 第三方 API 客户端
│   │   ├── aliyunai/        # 阿里云 AI 接口
│   │   └── imagesearch/     # 图片搜索门面
│   ├── common/              # 通用类（统一响应、分页请求等）
│   ├── config/              # 配置类（CORS、COS、Redis、MyBatis-Plus、线程池等）
│   ├── controller/          # RESTful 控制器（11 个）
│   ├── domain/
│   │   ├── po/              # 持久化对象（实体类）
│   │   ├── vo/              # 视图对象
│   │   └── dto/             # 数据传输对象
│   ├── enums/               # 枚举类（空间级别、审核状态等）
│   ├── exception/           # 全局异常处理
│   ├── manager/             # 业务管理器（COS、文件、分表、上传策略、WebSocket）
│   ├── mapper/              # MyBatis-Plus Mapper 接口
│   ├── service/             # 业务服务层
│   └── utils/               # 工具类（颜色处理等）
├── src/main/resources/
│   ├── application.yaml         # 主配置文件
│   ├── application-local.yaml   # 本地开发配置
│   ├── application-prod.yaml    # 生产环境配置
│   └── mapper/                  # MyBatis XML 映射文件
├── sql/
│   └── create_table.sql     # 数据库建表脚本
├── httpTest/                # HTTP 接口测试用例
└── pom.xml                  # Maven 配置
```

## :computer: 快速开始

### 环境要求

- JDK 17+
- Maven 3.6+
- MySQL 8.x
- Redis 6.x+

### 本地运行

```bash
# 1. 克隆项目
git clone https://github.com/LCL-001/yun-picture-base.git
cd yun-picture-base

# 2. 初始化数据库
mysql -u root -p < sql/create_table.sql

# 3. 修改本地配置
# 编辑 src/main/resources/application-local.yaml，填入你的：
#    - 腾讯云 COS 密钥（secretId / secretKey）
#    - 阿里云 AI API Key

# 4. 启动项目
mvn spring-boot:run

# 5. 访问接口文档
# http://localhost:8123/api/doc.html
```

### 生产部署

```bash
# 打包
mvn clean package -DskipTests

# 运行
java -jar target/yun-picture-base-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
```

## :key: 核心设计

### 多级缓存架构

```
请求 → Caffeine (本地缓存) → Redis (分布式缓存) → MySQL
                ↓            ↓                  ↓
               <1μs         ~1ms              ~10ms
```

- **读多写少**的数据（热门图片、空间信息）使用两级缓存
- 更新策略：先更新数据库，再删除缓存（Cache-Aside）
- 随机过期时间防止缓存雪崩

### 权限控制模型

```
第一层：接口级权限（Sa-Token + AOP）
  ├─ 是否登录
  └─ 角色校验（admin / user）

第二层：数据级权限（业务层）
  ├─ 图片归属校验（公共 / 个人 / 团队）
  ├─ 空间成员角色校验（viewer / editor / admin）
  └─ 越权访问拦截
```

### 图片上传流程

```
用户上传 → 格式/大小校验 → 权限校验 → 生成唯一文件名
  → 上传腾讯云 COS → 提取图片元信息（宽高/大小/格式/主色调）
  → 写入 MySQL → 返回图片信息
```

## :bar_chart: 数据库设计

共 5 张核心表：

| 表名 | 说明 |
|------|------|
| `user` | 用户表 |
| `picture` | 图片表（支持分表） |
| `space` | 空间表 |
| `space_user` | 空间成员关联表 |
| `user_notification` | 用户通知表 |

## :test_tube: 接口测试

项目提供 IntelliJ HTTP Client 测试用例：

```bash
# 在 IntelliJ 中打开 httpTest/picture.http
# 支持环境变量替换（见 http-client.env.json）
```

## :book: 接口文档

启动项目后访问：[http://localhost:8123/api/doc.html](http://localhost:8123/api/doc.html)

## :hammer_and_wrench: 开发计划

- [ ] 图片秒传与断点续传
- [ ] Elasticsearch 全文检索
- [ ] 缓存预热与监控告警
- [ ] 批量任务消息队列化
- [ ] WebSocket 冲突合并（OT / CRDT）
