# Research Agent Frontend — Configuration Reference

## Overview

This document covers all configuration files and their purposes in the Angular frontend project.

---

## Development Environment Setup

### Prerequisites

- Node.js 20+ (for Angular 18)
- npm package manager

### Installation

```bash
cd research-agent-ui
npm install
```

### Starting the Dev Server

The dev server must be started with the proxy config to forward API requests to the Spring Boot backend:

```bash
npx ng serve --proxy-config proxy.conf.json
```

This starts the Angular app at `http://localhost:4200/` and proxies `/api/*` requests to `http://localhost:8080`.

---

## Configuration Files

### `proxy.conf.json` — Dev Server Proxy

Maps `/api/*` requests from the frontend dev server to the Spring Boot backend. This is required because Angular's dev server runs on a different port than the backend, and CORS would block direct cross-origin requests.

```json
{
  "/api": {
    "target": "http://localhost:8080",
    "secure": false,
    "changeOrigin": true,
    "logLevel": "debug"
  }
}
```

**Fields:**
- `/api` — Path prefix to match. All requests starting with `/api/` will be proxied.
- `target` — Backend server URL that receives proxied requests
- `secure: false` — Allow self-signed certificates (needed for local development)
- `changeOrigin: true` — Sets the `Host` header of the outgoing request to match the target, so the backend sees requests as coming from localhost instead of port 4200
- `logLevel: debug` — Logs proxy activity to the terminal

**Note:** In production, this proxy must be replaced with either a reverse proxy (nginx/Apache) or CORS configuration on the backend.

---

### `environments/` — Environment-Specific Configuration

Two environment files control runtime behavior based on the build target:

#### `environment.ts` (dev)
```typescript
export const environment = {
  production: false,
  apiUrl: ''   // Empty because dev server proxy handles /api routing
};
```

#### `environment.prod.ts` (production)
```typescript
export const environment = {
  production: true,
  apiUrl: ''   // Should be set to backend base URL in production deployment
};
```

**Usage:** Import and use `environment.apiUrl` throughout the application for API endpoints. The value is automatically swapped based on the build target (`ng serve` uses dev env, `ng build --configuration=production` uses prod env).

---

### `angular.json` — Project Build Configuration

#### Application Settings
- **Project name**: `research-agent-ui`
- **Application type** (not a library)
- **Component prefix**: `app` — all component selectors are prefixed with `app-` (e.g., `<app-root>`, `<app-research-input>`)
- **Component style format**: SCSS (`@schematics/angular:component` uses scss)
- **Output path**: `dist/research-agent-ui/`

#### Browser Entry Point
- **Browser main file**: `src/main.ts` — Application bootstrap entry point
- **Index HTML**: `src/index.html` — Shell page with `<app-root>` placeholder

#### Polyfills
- **zone.js** only — no additional polyfills needed beyond Angular's defaults (Angular 18+ requires zone.js for change detection)

#### Build Configuration
- **Default build**: production mode
- **Budget warnings** for initial bundle:
  - Initial Bundle total: warning at 500kb, error at 2MB
  - All Initial Bundles combined: warning at 400kb, error at 1.7MB
  - Any Single Chunk: warning at 350kb, error at 400kb

#### Dev Server Configuration
- **Host**: `localhost` (default)
- **Port**: `4200` (default)
- **Proxy config**: References `proxy.conf.json` for API proxying during development
- **Watch mode enabled** — auto-rebuilds on file changes

---

### `tsconfig.json` — TypeScript Configuration

```json
{
  "compileOnSave": false,
  "compilerOptions": {
    "outDir": "./dist/out-tsc",
    "strict": true,                    // All strict type checks enabled
    "noImplicitOverride": true,        // Must use 'override' keyword when overriding methods/properties
    "noPropertyAccessFromIndexSignature": true,  // Enforces dot notation vs bracket notation rules
    "noImplicitReturns": true,         // All code paths must return a value if function has declared return type
    "noFallthroughCasesInSwitch": true,        // Switch cases must have explicit breaks or fallthrough comments
    "allowSyntheticDefaultImports": true,   // Allow default imports from modules with no default export
    "esModuleInterop": true,                    // Enable ES module interop (CommonJS/ESM compatibility)
    "experimentalDecorators": true,           // Enable decorator support for Angular components
    "forceConsistentCasingInFileNames": true, // Prevent case-sensitivity issues across platforms
    "skipLibCheck": true,                     // Skip type checking of declaration files in node_modules
    "moduleResolution": "bundler",            // Modern ES module resolution (required for Vite/AOT)
    "module": "ES2022",                       // Target ES modules
    "target": "ES2022"                        // Compile to ES2022 target
  },
  "angularCompilerOptions": {
    "enableI18nLegacyMessageIdFormat": false, // Use new i18n message format (no legacy ID collisions)
    "strictInjectionParameters": true,        // Strict dependency injection parameter checking
    "strictInputAccessModifiers": true,       // Enforce access modifiers on component inputs
    "strictTemplates": true                   // Enable strict template type checking for bindings and pipes
  }
}
```

### `tsconfig.app.json` — App-Specific TypeScript Config

Extends base `tsconfig.json` with app-specific options:
- **Extends**: `"./tsconfig.json"` (inherits all strict mode settings)
- **outDir**: `"./dist/out-tsc/app"` — compiled output for the app
- **Files**: Only `src/main.ts` is included as an entry point (Angular handles other file compilation separately via its build system, not through tsconfig)

### `tsconfig.spec.json` — Test-Specific TypeScript Config

Extends base `tsconfig.json` with test-specific options:
- **Extends**: `"./tsconfig.json"` (inherits all strict mode settings)
- **outDir**: `"./dist/out-tsc/spec"` — compiled output for tests
- **Types**: Includes `"jasmine"` type definitions for Angular testing

---

## Build Commands

### Development Build (with hot reload)
```bash
npx ng serve --proxy-config proxy.conf.json
# → http://localhost:4200/
```

### Production Build
```bash
npx ng build --configuration=production
# → dist/research-agent-ui/ directory with optimized production files
```

### Watch Mode (development rebuild)
```bash
npm run watch
# → Watches for changes and rebuilds in development mode
```

---

## Key Files Summary

| File | Purpose | Category |
|------|---------|----------|
| `angular.json` | Project structure, build targets, proxy reference | Configuration |
| `package.json` | Dependencies and npm scripts (start, build, watch) | Configuration |
| `proxy.conf.json` | Dev server API proxy (`/api` → backend) | Development |
| `tsconfig.json` | Base TypeScript config (strict mode, module resolution) | Configuration |
| `tsconfig.app.json` | App-specific TS config for build | Configuration |
| `tsconfig.spec.json` | Test-specific TS config for Jasmine testing | Configuration |
| `src/main.ts` | Application bootstrap entry point | Source |
| `src/styles.scss` | Global SCSS: Material M3 theme, font setup, component styles | Styling |
| `src/app/app.routes.ts` | Route definitions (lazy-loaded standalone components) | Routing |
| `src/app/app.config.ts` | Application providers: Router, HttpClient, Animations, Markdown | Configuration |
| `src/index.html` | HTML shell with `<app-root>` placeholder | Source |

---

## Styling Architecture

All styling is handled through Angular's **component-level SCSS** approach — there are no separate `.scss` files per component. Instead:

1. **Global styles**: `src/styles.scss` defines the Material M3 theme, core mixin inclusion, and body font
2. **Component styles**: Each component uses inline `styles` property on the `@Component` decorator (not `styleUrls`) — all styling is defined as SCSS strings within the TypeScript file

This means there are zero `.scss` files in the `src/app/` directory tree. The only global SCSS file is `src/styles.scss`, which:
- Includes Angular Material M3 theming via `mat.define-theme((color: ()))` wrapped in a CSS selector for `all-component-themes($theme)`
- Sets up Roboto font from Google Fonts
- Defines body margin reset

### Why Inline Styles?

The project uses inline styles rather than separate `.scss` files. This keeps component styling co-located with the component code, which:
- Reduces file count and navigation overhead for small-to-medium components
- Avoids cross-file references between components (no `@import 'styles.scss'` in individual components)
- Makes each component self-contained and portable

**Trade-off**: For very large components with extensive styling, separate `.scss` files would be more maintainable. The current approach works well for the small-to-medium component sizes in this project.

---

## npm Scripts (`package.json`)

| Script | Command | Description |
|--------|---------|-------------|
| `npm start` | `ng serve --proxy-config proxy.conf.json` | Start dev server with API proxy |
| `npm run build` | `ng build` | Production build to `dist/research-agent-ui/` |
| `npm run watch` | `ng build --watch --configuration development` | Watch mode for development |

**Note:** No lint, test, or typecheck scripts are defined in the current package.json. Angular karma tests are configured but no npm script invokes them either.
