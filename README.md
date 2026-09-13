# 火山图库（huoshan-picture）

图片与素材管理平台，前后端一体。支持按空间管理图片、空间级 RBAC 成员权限、列表多级缓存、批量抓取、AI 扩图与多人协同编辑。

- 线上站点：https://www.lincode.online

## 目录结构

```
huoshan-picture/
├── backend/     Spring Boot 后端服务（端口 8123，context-path /api）
├── ai/          AI 模块（独立 Maven 应用，各自独立进程，不并入 backend）
│   ├── agent/                   MyManus 智能体引擎（SB 3.5/Java 21，端口 8124，context-path /api）
│   └── image-search-mcp-server/ Pexels 搜图 MCP 服务（端口 8127，SSE）
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

# 1. 生成本地配置：复制模板后把 replace-me 换成自己的值
cp src/main/resources/application-local.yaml.example src/main/resources/application-local.yaml

# 2. 打包并启动
mvn -B package -DskipTests
java -jar target/yun-picture-base-0.0.1-SNAPSHOT.jar
```

默认地址：http://localhost:8123/api

本地配置（MySQL 口令、腾讯云 COS 密钥、阿里云百炼 API Key）放在
`backend/src/main/resources/application-local.yaml`，该文件被 `.gitignore` 忽略；
模板见同目录下的 `application-local.yaml.example`，复制后填入自己的值即可。

> Windows 下 `mvn spring-boot:run` 可能因命令行 classpath 过长报 `CreateProcess error=206`，
> 用上面的打包后 `java -jar` 方式启动即可。

### 前端

```bash
cd frontend
npm install
npm run dev
```

默认地址：http://localhost:5173

开发环境通过 `frontend/.env.development.local` 指定后端地址：

```
VITE_API_BASE=http://localhost:8123
```

## 说明

- `application-local.yaml`、`application-prod.yaml`、`application-test.yaml` 含密钥或环境配置，未纳入版本控制。
- `backend/jmeter/` 为本地压测产物，未纳入版本控制。
