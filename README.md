# 火山图库（huoshan-picture）

图片与素材管理平台，前后端一体。支持按空间管理图片、空间级 RBAC 成员权限、列表多级缓存、批量抓取、AI 扩图与多人协同编辑。

- 线上站点：https://www.lincode.online

## 目录结构

```
huoshan-picture/
├── backend/     Spring Boot 后端服务（端口 8124，context-path /api）
└── frontend/    Vue 3 前端（开发端口 5173）
```

## 技术栈

- **后端**：Spring Boot 2.7.6、MyBatis-Plus、MySQL、Redis、ShardingSphere、Sa-Token、WebSocket + Disruptor、腾讯云 COS、阿里百炼
- **前端**：Vue 3、Vite、TypeScript、Pinia、Ant Design Vue

## 快速开始

### 环境依赖

- JDK 17+
- Maven 3.8+
- MySQL 8、Redis 6+
- Node.js 18+

### 后端

```bash
cd backend
mvn spring-boot:run
```

默认地址：http://localhost:8124/api

数据库口令、对象存储与 AI 密钥等本地配置放在 `backend/src/main/resources/application-local.yaml`，clone 后需自行创建。

### 前端

```bash
cd frontend
npm install
npm run dev
```

默认地址：http://localhost:5173

开发环境通过 `frontend/.env.development.local` 指定后端地址：

```
VITE_API_BASE=http://localhost:8124
```

