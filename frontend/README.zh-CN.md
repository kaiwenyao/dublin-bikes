# React App

<p align="center">
  <strong>React App</strong> 是一款基于 React 19、TypeScript 和 Vite 构建的现代 Web 应用，集成了 AI 聊天、交互式地图、实时新闻等功能。
  <br />
  <a href="./README.md">English</a> | 简体中文
</p>

<p align="center">
  <img src="https://img.shields.io/badge/React-19-blue?logo=react" alt="React 19" />
  <img src="https://img.shields.io/badge/TypeScript-5.9-blue?logo=typescript" alt="TypeScript 5.9" />
  <img src="https://img.shields.io/badge/Vite-7-purple?logo=vite" alt="Vite 7" />
  <img src="https://img.shields.io/badge/Tailwind_CSS-4-cyan?logo=tailwindcss" alt="Tailwind CSS 4" />
  <img src="https://img.shields.io/badge/Docker-支持-blue?logo=docker" alt="Docker" />
</p>

---

## 📋 目录

- [✨ 功能特性](#-功能特性)
- [🛠️ 技术栈](#️-技术栈)
- [🚀 快速开始](#-快速开始)
  - [🔧 环境要求](#-环境要求)
  - [📥 安装步骤](#-安装步骤)
  - [⚙️ 配置说明](#️-配置说明)
- [💻 使用说明](#-使用说明)
- [📁 项目结构](#-项目结构)
- [🐳 Docker 部署](#-docker-部署)
- [📜 可用脚本](#-可用脚本)
- [🔍 ESLint 配置](#-eslint-配置)
- [⚛️ React 编译器](#️-react-编译器)
- [🧪 测试](#-测试)
- [🤝 贡献指南](#-贡献指南)
- [📝 许可证](#-许可证)
- [📧 联系方式](#-联系方式)

---

## ✨ 功能特性

- **AI 智能聊天** — 支持流式 AI 响应的交互式聊天界面
- **交互式地图** — 集成 Google Maps，支持基于位置的功能
- **实时新闻** — 浏览和阅读最新新闻资讯
- **用户认证** — 完整的认证流程，包括注册、登录、邮箱验证和账户激活
- **用户资料** — 个性化的资料管理
- **现代化 UI** — 基于 Radix UI 原语和 Tailwind CSS 4 构建，提供精致的响应式体验
- **响应式设计** — 在桌面、平板和移动设备上均可完美运行
- **数据可视化** — 基于 Recharts 的图表和图形
- **通知系统** — 基于 Sonner 的实时通知反馈系统

---

## 🛠️ 技术栈

| 类别 | 技术 | 版本 |
|------|------|------|
| **框架** | React | 19 |
| **语言** | TypeScript | 5.9 |
| **构建工具** | Vite | 7 |
| **样式** | Tailwind CSS | 4 |
| **UI 原语** | Radix UI | — |
| **图表** | Recharts | 3 |
| **路由** | React Router | 7 |
| **HTTP 客户端** | Axios | 1.x |
| **通知** | Sonner | 2.x |
| **容器化** | Docker + Nginx | — |
| **CI/CD** | Jenkins | — |

---

## 🚀 快速开始

### 🔧 环境要求

- **Node.js**：18.x 或更高版本（使用 `node -v` 检查）
- **npm**（随 Node.js 安装）或 **pnpm** / **yarn**
- **Git**：用于克隆仓库
- **Docker**（可选）：用于容器化部署

### 📥 安装步骤

1. **克隆仓库：**

   ```bash
   git clone https://github.com/ucdse/react-app.git
   cd react-app
   ```

2. **安装依赖：**

   ```bash
   npm install
   ```

3. **设置环境变量**（参见下方[配置说明](#配置说明)）。

4. **启动开发服务器：**

   ```bash
   npm run dev
   ```

   应用将在 `http://localhost:5173` 上可用。已启用 `--host`，您可以通过局域网内其他设备使用机器 IP 访问。

### ⚙️ 配置说明

复制示例文件创建 `.env` 文件：

```bash
cp .env.example .env
```

然后填写 Google Maps API 密钥：

| 变量 | 说明 |
|------|------|
| `VITE_GOOGLE_MAPS_API_KEY` | Google Maps API 密钥（`/maps` 页面必需） |

> **注意：** Vite 仅将 `VITE_` 前缀的变量暴露给前端代码。请勿在此放置真正的机密密钥，因为它们在浏览器包中可见。

---

## 💻 使用说明

启动开发服务器后，您可以浏览以下页面：

| 路由 | 页面 | 说明 |
|------|------|------|
| `/` | 首页 | 着陆页 / 仪表盘 |
| `/login` | 登录 | 用户登录 |
| `/register` | 注册 | 新用户注册 |
| `/activate/:token` | 激活 | 通过邮件令牌激活账户 |
| `/verify-email` | 验证邮箱 | 邮箱验证流程 |
| `/news` | 新闻 | 浏览新闻资讯 |
| `/chat` | 聊天 | AI 聊天界面 |
| `/maps` | 地图 | 交互式 Google 地图 |
| `/profile` | 个人资料 | 用户资料管理 |

---

## 📁 项目结构

```
react-app/
├── public/                 # 静态资源
├── src/
│   ├── api/               # API 客户端模块
│   ├── assets/            # 图片、字体等
│   ├── components/        # 可复用 UI 组件
│   │   └── Layout.tsx     # 应用主布局
│   ├── lib/               # 工具库
│   ├── pages/             # 路由级页面组件
│   │   ├── Activate/      # 账户激活
│   │   ├── Chat/          # AI 聊天
│   │   ├── Home/          # 仪表盘
│   │   ├── Login/         # 登录
│   │   ├── Maps/          # Google 地图
│   │   ├── News/          # 新闻
│   │   ├── Profile/       # 个人资料
│   │   ├── Register/      # 注册
│   │   └── VerifyEmail/   # 邮箱验证
│   ├── router/            # React Router 配置
│   ├── utils/             # 辅助函数
│   ├── config.ts          # 应用配置
│   ├── index.css          # 全局样式
│   └── main.tsx           # 应用入口
├── deploy/                 # 部署配置（nginx 等）
├── Dockerfile              # 多阶段 Docker 构建
├── Jenkinsfile             # CI/CD 流水线
├── nginx.conf              # Nginx 配置
├── .env.example            # 环境变量模板
└── package.json            # 依赖与脚本
```

---

## 🐳 Docker 部署

项目包含一个可用于生产环境的 Docker 设置，采用多阶段构建：

1. **构建阶段** — 在 Node.js 容器中编译 React 应用
2. **运行阶段** — 通过 Nginx 提供构建后的静态文件

### 构建和运行

```bash
# 构建 Docker 镜像
docker build --secret id=env,src=.env -t react-app .

# 运行容器
docker run -d -p 80:80 \
  -e BACKEND_HOST=您的后端主机 \
  -e BACKEND_PORT=8080 \
  react-app
```

### 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `BACKEND_HOST` | `flask-app` | 后端服务主机名 |
| `BACKEND_PORT` | `8080` | 后端服务端口 |

Nginx 配置使用 `envsubst` 在运行时动态代理 API 请求到后端服务。

---

## 📜 可用脚本

| 命令 | 说明 |
|------|------|
| `npm run dev` | 启动开发服务器（已启用 `--host`） |
| `npm run build` | 构建生产版本（先运行 TypeScript 检查） |
| `npm run preview` | 本地预览生产构建 |
| `npm run lint` | 对所有文件运行 ESLint |

---

## 🔍 ESLint 配置

本项目使用以下 ESLint 插件：

- `@eslint/js` — 推荐的 JavaScript 规则
- `typescript-eslint` — TypeScript 特定规则
- `eslint-plugin-react-hooks` — React Hooks 规则
- `eslint-plugin-react-refresh` — React Refresh（Vite）规则

<details>
<summary>查看 eslint.config.js</summary>

```js
import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'
import { defineConfig, globalIgnores } from 'eslint/config'

export default defineConfig([
  globalIgnores(['dist']),
  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      js.configs.recommended,
      tseslint.configs.recommended,
      reactHooks.configs.flat.recommended,
      reactRefresh.configs.vite,
    ],
    languageOptions: {
      ecmaVersion: 2020,
      globals: globals.browser,
    },
    rules: {
      'react-refresh/only-export-components': [
        'warn',
        { allowConstantExport: true },
      ],
    },
  },
])
```

</details>

### 扩展 ESLint 用于生产环境

<details>
<summary>启用类型感知 lint 规则</summary>

```js
export default defineConfig([
  globalIgnores(['dist']),
  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      // 将 tseslint.configs.recommended 替换为：
      tseslint.configs.recommendedTypeChecked,
      // 或使用更严格的规则：
      // tseslint.configs.strictTypeChecked,
    ],
    languageOptions: {
      parserOptions: {
        project: ['./tsconfig.node.json', './tsconfig.app.json'],
        tsconfigRootDir: import.meta.dirname,
      },
    },
  },
])
```

如需额外的 React 特定规则，可安装：
- [eslint-plugin-react-x](https://github.com/Rel1cx/eslint-react/tree/main/packages/plugins/eslint-plugin-react-x)
- [eslint-plugin-react-dom](https://github.com/Rel1cx/eslint-react/tree/main/packages/plugins/eslint-plugin-react-dom)

</details>

---

## ⚛️ React 编译器

此模板**默认未启用** React 编译器，因为它会影响开发和构建性能。要启用它，请参阅 [React 编译器安装指南](https://react.dev/learn/react-compiler/installation)。

---

## 🧪 测试

本项目目前使用 **ESLint** 进行静态代码分析（`npm run lint`），但尚未配置专门的单元或集成测试框架。

### Lint 检查

```bash
# 运行 ESLint 检查代码质量问题
npm run lint
```

### 构建验证

```bash
# TypeScript 检查 + 生产构建（作为类型安全测试）
npm run build
```

### 添加测试框架

要添加测试支持，可考虑以下选项：

| 框架 | 说明 |
|------|------|
| [Vitest](https://vitest.dev/) | Vite 原生测试运行器，快速且支持 ESM（推荐） |
| [Jest](https://jestjs.io/) | 行业标准的 JavaScript 测试框架 |
| [React Testing Library](https://testing-library.com/docs/react-testing-library/intro/) | React UI 组件级测试 |

> **提示：** 对于基于 Vite 的项目，推荐使用 Vitest，因为它共享配置且启动即时。

---

## 🤝 贡献指南

欢迎贡献！贡献步骤如下：

1. **Fork** 本仓库
2. **创建** 功能分支：
   ```bash
   git checkout -b feature/your-feature-name
   ```
3. **提交** 您的更改：
   ```bash
   git commit -m "Add your feature"
   ```
4. **推送** 到您的分支：
   ```bash
   git push origin feature/your-feature-name
   ```
5. 在 GitHub 上**发起** Pull Request

提交前请确保代码通过 `npm run lint` 和 `npm run build` 检查。

---

## 📝 许可证

本项目基于 **MIT 许可证** 授权。详见 [LICENSE](LICENSE) 文件。

---

## 📧 联系方式

- **GitHub**: [ucdse/react-app](https://github.com/ucdse/react-app)
- **Issues**: [提交 Issue](https://github.com/ucdse/react-app/issues)

---

由 UCD SE 团队用 ❤️ 打造。