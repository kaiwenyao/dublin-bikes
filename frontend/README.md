# React App

<p align="center">
  <strong>React App</strong> is a modern web application built with React 19, TypeScript, and Vite, featuring AI-powered chat, interactive maps, real-time news, and more. 
  <br />
  English | <a href="./README.zh-CN.md">简体中文</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/React-19-blue?logo=react" alt="React 19" />
  <img src="https://img.shields.io/badge/TypeScript-5.9-blue?logo=typescript" alt="TypeScript 5.9" />
  <img src="https://img.shields.io/badge/Vite-7-purple?logo=vite" alt="Vite 7" />
  <img src="https://img.shields.io/badge/Tailwind_CSS-4-cyan?logo=tailwindcss" alt="Tailwind CSS 4" />
  <img src="https://img.shields.io/badge/Docker-Supported-blue?logo=docker" alt="Docker" />
</p>

---

## 📋 Table of Contents

- [✨ Features](#-features)
- [🛠️ Tech Stack](#️-tech-stack)
- [🚀 Getting Started](#-getting-started)
  - [🔧 Prerequisites](#-prerequisites)
  - [📥 Installation](#-installation)
  - [⚙️ Configuration](#️-configuration)
- [💻 Usage](#-usage)
- [📁 Project Structure](#-project-structure)
- [🐳 Docker Deployment](#-docker-deployment)
- [📜 Available Scripts](#-available-scripts)
- [🔍 ESLint Configuration](#-eslint-configuration)
- [⚛️ React Compiler](#️-react-compiler)
- [🧪 Testing](#-testing)
- [🤝 Contributing](#-contributing)
- [📝 License](#-license)
- [📧 Contact](#-contact)

---

## ✨ Features

- **AI-Powered Chat** — Interactive chat interface with streaming AI responses
- **Interactive Maps** — Google Maps integration with location-based features
- **Real-Time News** — Browse and read the latest news articles
- **User Authentication** — Full auth flow with registration, login, email verification, and account activation
- **User Profile** — Personalized profile management
- **Modern UI** — Built with Radix UI primitives and Tailwind CSS 4 for a polished, responsive experience
- **Responsive Design** — Works seamlessly across desktop, tablet, and mobile devices
- **Data Visualization** — Recharts-powered charts and graphs
- **Toast Notifications** — Sonner-based notification system for real-time feedback

---

## 🛠️ Tech Stack

| Category | Technology | Version |
|----------|-----------|---------|
| **Framework** | React | 19 |
| **Language** | TypeScript | 5.9 |
| **Build Tool** | Vite | 7 |
| **Styling** | Tailwind CSS | 4 |
| **UI Primitives** | Radix UI | — |
| **Charts** | Recharts | 3 |
| **Routing** | React Router | 7 |
| **HTTP Client** | Axios | 1.x |
| **Notifications** | Sonner | 2.x |
| **Containerization** | Docker + Nginx | — |
| **CI/CD** | Jenkins | — |

---

## 🚀 Getting Started

### 🔧 Prerequisites

- **Node.js**: 18.x or higher (check with `node -v`)
- **npm** (comes with Node.js) or **pnpm** / **yarn**
- **Git**: For cloning the repository
- **Docker** (optional): For containerized deployment

### 📥 Installation

1. **Clone the repository:**

   ```bash
   git clone https://github.com/ucdse/react-app.git
   cd react-app
   ```

2. **Install dependencies:**

   ```bash
   npm install
   ```

3. **Set up environment variables** (see [Configuration](#configuration) below).

4. **Start the development server:**

   ```bash
   npm run dev
   ```

   The app will be available at `http://localhost:5173`. With `--host` enabled, you can also access it from other devices on the same network using your machine's IP address.

### ⚙️ Configuration

Create a `.env` file in the project root by copying the example:

```bash
cp .env.example .env
```

Then fill in your API keys:

| Variable | Description |
|----------|-------------|
| `VITE_PUBLIC_API_KEY` | Your public API key |
| `VITE_GOOGLE_MAPS_API_KEY` | Google Maps API key (required for maps features) |

> **Note:** Vite only exposes variables prefixed with `VITE_` to frontend code. Do not put truly secret keys here — they are visible in the browser bundle.

---

## 💻 Usage

After starting the development server, you can navigate through the following pages:

| Route | Page | Description |
|-------|------|-------------|
| `/` | Home | Landing page / dashboard |
| `/login` | Login | User sign-in |
| `/register` | Register | New user registration |
| `/activate/:token` | Activate | Account activation via email token |
| `/verify-email` | Verify Email | Email verification flow |
| `/news` | News | Browse news articles |
| `/chat` | Chat | AI-powered chat interface |
| `/maps` | Maps | Interactive Google Maps view |
| `/profile` | Profile | User profile management |

---

## 📁 Project Structure

```
react-app/
├── public/                 # Static assets
├── src/
│   ├── api/               # API client modules
│   ├── assets/            # Images, fonts, etc.
│   ├── components/        # Reusable UI components
│   │   └── Layout.tsx     # Main app layout
│   ├── lib/               # Utility libraries
│   ├── pages/             # Route-level page components
│   │   ├── Activate/      # Account activation
│   │   ├── Chat/          # AI chat
│   │   ├── Home/          # Dashboard
│   │   ├── Login/         # Sign in
│   │   ├── Maps/          # Google Maps
│   │   ├── News/          # News feed
│   │   ├── Profile/       # User profile
│   │   ├── Register/      # Sign up
│   │   └── VerifyEmail/   # Email verification
│   ├── router/            # React Router configuration
│   ├── utils/             # Helper functions
│   ├── config.ts          # App configuration
│   ├── index.css          # Global styles
│   └── main.tsx           # App entry point
├── deploy/                 # Deployment configs (nginx, etc.)
├── Dockerfile              # Multi-stage Docker build
├── Jenkinsfile             # CI/CD pipeline
├── nginx.conf              # Nginx configuration
├── .env.example            # Environment variable template
└── package.json            # Dependencies & scripts
```

---

## 🐳 Docker Deployment

The project includes a production-ready Docker setup using a multi-stage build:

1. **Build stage** — Compiles the React app inside a Node.js container
2. **Runtime stage** — Serves the built static files via Nginx

### Build and Run

```bash
# Build the Docker image
docker build --secret id=env,src=.env -t react-app .

# Run the container
docker run -d -p 80:80 \
  -e BACKEND_HOST=your-backend-host \
  -e BACKEND_PORT=5000 \
  react-app
```

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `BACKEND_HOST` | `flask-app` | Backend service hostname |
| `BACKEND_PORT` | `5000` | Backend service port |

The Nginx configuration uses `envsubst` to dynamically proxy API requests to the backend service at runtime.

---

## 📜 Available Scripts

| Command | Description |
|---------|-------------|
| `npm run dev` | Start development server with `--host` enabled |
| `npm run build` | Build for production (runs TypeScript check first) |
| `npm run preview` | Preview production build locally |
| `npm run lint` | Run ESLint on all files |

---

## 🔍 ESLint Configuration

The project uses ESLint with the following plugins:

- `@eslint/js` — Recommended JavaScript rules
- `typescript-eslint` — TypeScript-specific rules
- `eslint-plugin-react-hooks` — React Hooks lint rules
- `eslint-plugin-react-refresh` — React Refresh (Vite) rules

<details>
<summary>View eslint.config.js</summary>

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

### Expanding ESLint for Production

<details>
<summary>Enable type-aware lint rules</summary>

```js
export default defineConfig([
  globalIgnores(['dist']),
  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      // Replace tseslint.configs.recommended with:
      tseslint.configs.recommendedTypeChecked,
      // Or use stricter rules:
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

For additional React-specific rules, install:
- [eslint-plugin-react-x](https://github.com/Rel1cx/eslint-react/tree/main/packages/plugins/eslint-plugin-react-x)
- [eslint-plugin-react-dom](https://github.com/Rel1cx/eslint-react/tree/main/packages/plugins/eslint-plugin-react-dom)

</details>

---

## ⚛️ React Compiler

The React Compiler is **not enabled** by default in this template due to its impact on development and build performance. To enable it, refer to the [React Compiler installation guide](https://react.dev/learn/react-compiler/installation).

---

## 🧪 Testing

This project currently uses **ESLint** for static code analysis (`npm run lint`), but does not yet have a dedicated unit or integration test framework configured.

### Lint Checks

```bash
# Run ESLint to check for code quality issues
npm run lint
```

### Build Verification

```bash
# TypeScript check + production build (serves as a type-safety test)
npm run build
```

### Adding a Test Framework

To add testing support, consider one of the following:

| Framework | Description |
|-----------|-------------|
| [Vitest](https://vitest.dev/) | Vite-native test runner, fast and ESM-first (recommended) |
| [Jest](https://jestjs.io/) | Industry-standard JavaScript testing framework |
| [React Testing Library](https://testing-library.com/docs/react-testing-library/intro/) | Component-level testing for React UI |

> **Tip:** Vitest is the recommended choice for Vite-based projects due to its shared configuration and instant startup.

---

## 🤝 Contributing

Contributions are welcome! To contribute:

1. **Fork** the repository
2. **Create** a feature branch:
   ```bash
   git checkout -b feature/your-feature-name
   ```
3. **Commit** your changes:
   ```bash
   git commit -m "Add your feature"
   ```
4. **Push** to your branch:
   ```bash
   git push origin feature/your-feature-name
   ```
5. **Open** a Pull Request on GitHub

Please ensure your code passes `npm run lint` and `npm run build` before submitting.

---

## 📝 License

This project is licensed under the **MIT License**. See the [LICENSE](LICENSE) file for details.

---

## 📧 Contact

- **GitHub**: [ucdse/react-app](https://github.com/ucdse/react-app)
- **Issues**: [Open an Issue](https://github.com/ucdse/react-app/issues)

---

Made with ❤️ by the UCD SE team.