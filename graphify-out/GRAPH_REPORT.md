# Graph Report - intermanage  (2026-10-24)

## Corpus Check
- 100 files · ~20,994 words
- Verdict: corpus is large enough that graph structure adds value.
- Unclassified: 7 file(s) not represented in the graph (top: (none) 3, .properties 2, .cmd 1)

## Summary
- 871 nodes · 2227 edges · 43 communities (27 shown, 16 thin omitted)
- Extraction: 94% EXTRACTED · 6% INFERRED · 0% AMBIGUOUS · INFERRED: 136 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- RoleFormDialog.tsx
- PermissionCategory
- API Errors & Controllers
- settings/api.ts
- Auth Integration Tests
- App Layout & Sidebar
- Security Config
- ActivationService
- shadcn Components Config
- AppUser
- TS App Config
- Frontend Dev Dependencies
- Global Exception Handler
- Frontend Runtime Dependencies
- TS Node Config
- Owner Bootstrap
- Architecture Docs
- JPA Repositories
- Docker Postgres
- React Query Bootstrap
- Vite Build Setup
- Template Icon Sprites
- Permission Model Docs
- Oxlint Config
- Spring Boot Entry
- AppUserDetailsService.java
- NPM Scripts
- TS Root Config
- graphify Rules
- react
- Vite Favicon
- Maven Artifact
- RolesPage.tsx
- auth/api.ts
- JwtService.java
- App.tsx
- JwtAuthenticationFilter.java
- Role
- ActivationToken

## God Nodes (most connected - your core abstractions)
1. `AppUser` - 46 edges
2. `AuthAndRolesIntegrationTests` - 37 edges
3. `Role` - 33 edges
4. `AppUserRepository` - 30 edges
5. `react` - 29 edges
6. `Button()` - 29 edges
7. `RolesPage()` - 26 edges
8. `AppUserPrincipal` - 24 edges
9. `PermissionCategory` - 23 edges
10. `RoleService` - 19 edges

## Surprising Connections (you probably didn't know these)
- `index.html SPA entry (#root, /src/main.tsx)` --implements--> `Frontend: Vite + React 19 + TS + shadcn/ui (Base UI) + Tailwind 4`  [INFERRED]
  frontend/index.html → CLAUDE.md
- `React + TypeScript + Vite template` --conceptually_related_to--> `Frontend: Vite + React 19 + TS + shadcn/ui (Base UI) + Tailwind 4`  [INFERRED]
  frontend/README.md → CLAUDE.md
- `graphify knowledge graph usage rules` --conceptually_related_to--> `graphify skill (.claude/skills/graphify/SKILL.md)`  [INFERRED]
  CLAUDE.md → .claude/CLAUDE.md
- `AuthController` --references--> `AuthService`  [EXTRACTED]
  backend/src/main/java/com/intermanage/backend/auth/AuthController.java → backend/src/main/java/com/intermanage/backend/auth/AuthService.java
- `AuthController` --references--> `JwtService`  [EXTRACTED]
  backend/src/main/java/com/intermanage/backend/auth/AuthController.java → backend/src/main/java/com/intermanage/backend/security/JwtService.java

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Permission enforcement across backend and frontend** — claude_permissioncategory, claude_preauthorize_endpoints, claude_usecan_requireauth, claude_tool_routes, claude_new_module_procedure [EXTRACTED 1.00]
- **Session-based authentication flow** — claude_session_csrf_auth, claude_lib_api, claude_principalrefreshfilter, claude_owner_role [INFERRED 0.85]

## Communities (43 total, 16 thin omitted)

### Community 0 - "RoleFormDialog.tsx"
Cohesion: 0.10
Nodes (42): Form(), FormActions(), FormNote(), FormProps, SegmentedField(), SegmentOption, SwitchField(), TextField() (+34 more)

### Community 1 - "PermissionCategory"
Cohesion: 0.10
Nodes (9): PermissionCategory, IMPORTS, INVENTORY, ORDERS, SALES, STAFF, PermissionLevel, EDIT (+1 more)

### Community 2 - "API Errors & Controllers"
Cohesion: 0.07
Nodes (12): ApiException, PermissionCategoryController, PermissionCategoryResponse, RoleController, RoleRequest, RoleResponse, RoleService, UserController (+4 more)

### Community 3 - "settings/api.ts"
Cohesion: 0.12
Nodes (25): rolesKey, useCreateUser(), useDeleteRole(), useInvalidateSettings(), useResendActivationLink(), usersKey, useSaveRole(), useUpdateUser() (+17 more)

### Community 5 - "App Layout & Sidebar"
Cohesion: 0.10
Nodes (23): @reduxjs/toolkit@^2.13.0, AppLayout(), SectionLabel(), Sidebar(), SidebarLink(), UserMenu(), DropdownMenu(), DropdownMenuContent() (+15 more)

### Community 7 - "Security Config"
Cohesion: 0.08
Nodes (4): SecurityConfig, Config, RecordingAccountMailer, TestcontainersConfiguration

### Community 8 - "ActivationService"
Cohesion: 0.17
Nodes (3): AuthService, AccountMailer, ActivationService

### Community 9 - "shadcn Components Config"
Cohesion: 0.09
Nodes (21): aliases, components, hooks, lib, ui, utils, iconLibrary, menuAccent (+13 more)

### Community 10 - "AppUser"
Cohesion: 0.09
Nodes (8): ActivateRequest, AuthController, ChangePasswordRequest, LoginRequest, SetUpPasswordRequest, MeResponse, AppUserPrincipal, AppUser

### Community 11 - "TS App Config"
Cohesion: 0.10
Nodes (20): compilerOptions, allowArbitraryExtensions, allowImportingTsExtensions, erasableSyntaxOnly, jsx, lib, module, moduleDetection (+12 more)

### Community 12 - "Frontend Dev Dependencies"
Cohesion: 0.14
Nodes (19): devDependencies, oxlint@^1.88.0, tailwindcss@^4.3.3, @tanstack/react-query-devtools@^5.104.5, @types/node@^24.19.2, @types/react@^19.3.0, @types/react-dom@^19.3.0, typescript@~6.0.3 (+11 more)

### Community 14 - "Frontend Runtime Dependencies"
Cohesion: 0.12
Nodes (17): dependencies, @base-ui/react@^1.9.0, class-variance-authority@^0.7.1, cn@^0.5.0, @fontsource-variable/geist@^5.3.0, lucide-react@^1.61.0, react@^19.3.1, react-hook-form@^7.91.0 (+9 more)

### Community 15 - "TS Node Config"
Cohesion: 0.12
Nodes (16): compilerOptions, allowImportingTsExtensions, erasableSyntaxOnly, lib, module, moduleDetection, noEmit, noFallthroughCasesInSwitch (+8 more)

### Community 16 - "Owner Bootstrap"
Cohesion: 0.13
Nodes (3): LoggingAccountMailer, RoleRepository, OwnerBootstrap

### Community 17 - "Architecture Docs"
Cohesion: 0.13
Nodes (10): Backend: Spring Boot 4.1 / Java 25 / Spring Security 7 / JPA, Frontend: Vite + React 19 + TS + shadcn/ui (Base UI) + Tailwind 4, Intermanage (Intermarché store management app), lib/api.ts request layer (CSRF, ApiError, applyServerErrors), PostgreSQL 17 via docker compose (port 5433), PrincipalRefreshFilter (session_version), Excel .xlsm data import, index.html SPA entry (#root, /src/main.tsx) (+2 more)

### Community 19 - "Docker Postgres"
Cohesion: 0.15
Nodes (12): POSTGRES_DB, POSTGRES_PASSWORD, POSTGRES_USER, container_name, environment, image, ports, volumes (+4 more)

### Community 20 - "React Query Bootstrap"
Cohesion: 0.14
Nodes (12): next-themes@^0.4.6, react-dom@^19.3.1, react-redux@^9.3.0, @tanstack/react-query@^5.104.5, App(), Toaster(), meQueryKey, queryClient (+4 more)

### Community 22 - "Vite Build Setup"
Cohesion: 0.25
Nodes (6): @tailwindcss/vite@^4.3.3, vite@^8.3.4, @vitejs/plugin-react@^6.1.2, @tailwindcss/vite, vite, @vitejs/plugin-react

### Community 23 - "Template Icon Sprites"
Cohesion: 0.25
Nodes (8): bluesky-icon (Bluesky butterfly logo), discord-icon (Discord logo), documentation-icon (document with code brackets), github-icon (GitHub Octocat logo), social-icon (person with star), icons.svg Icon Sprite Sheet, Vite React Template Boilerplate Icons, x-icon (X/Twitter logo)

### Community 24 - "Permission Model Docs"
Cohesion: 0.38
Nodes (6): Właściciel (system owner role), OwnerBootstrap, PermissionCategory (VIEW/EDIT per tool category), @PreAuthorize hasAuthority endpoint security, TOOL_ROUTES (features/tools/categories.ts), useCan / RequireAuth frontend permission checks

### Community 25 - "Oxlint Config"
Cohesion: 0.33
Nodes (5): plugins, rules, react/only-export-components, react/rules-of-hooks, $schema

### Community 28 - "NPM Scripts"
Cohesion: 0.40
Nodes (5): scripts, build, dev, lint, preview

### Community 29 - "TS Root Config"
Cohesion: 0.40
Nodes (4): compilerOptions, paths, files, references

### Community 31 - "react"
Cohesion: 0.08
Nodes (23): FieldShell(), FieldShellProps, describedBy(), fieldControlClass, floatedLabelClass, floatingLabelClass, SegmentedFieldProps, SelectField() (+15 more)

### Community 35 - "RolesPage.tsx"
Cohesion: 0.17
Nodes (23): TableSkeletonRows(), AlertDialog(), AlertDialogAction(), AlertDialogCancel(), AlertDialogContent(), AlertDialogDescription(), AlertDialogFooter(), AlertDialogHeader() (+15 more)

### Community 36 - "auth/api.ts"
Cohesion: 0.16
Nodes (20): toolRoutes, BrandMark(), FormAlert(), FullPageMessage(), Props, AuthLayout(), Props, ActivationPage() (+12 more)

### Community 38 - "App.tsx"
Cohesion: 0.30
Nodes (11): router, PageHeader(), Card(), CardDescription(), CardHeader(), CardTitle(), TOOL_ROUTES, usePermissionCategories() (+3 more)

## Knowledge Gaps
- **25 isolated node(s):** `com.trytoma:backend`, `SALES`, `INVENTORY`, `ORDERS`, `STAFF` (+20 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 253 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **16 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `AppUserRepository` connect `JPA Repositories` to `API Errors & Controllers`, `JwtAuthenticationFilter.java`, `ActivationService`, `Security Config`, `AppUser`, `Owner Bootstrap`, `AppUserDetailsService.java`?**
  _High betweenness centrality (0.038) - this node is a cross-community bridge._
- **What connects `com.trytoma:backend`, `SALES`, `INVENTORY` to the rest of the system?**
  _25 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `RoleFormDialog.tsx` be split into smaller, more focused modules?**
  _Cohesion score 0.10449927431059507 - nodes in this community are weakly interconnected._
- **Why does `AppUser` connect `AppUser` to `PermissionCategory`, `Role & User Entities`, `ActivationService`, `ActivationToken`, `Role`, `jakarta.persistence.PrePersist`, `Owner Bootstrap`, `JPA Repositories`, `AppUserDetailsService.java`?**
  _High betweenness centrality (0.031) - this node is a cross-community bridge._
- **Should `PermissionCategory` be split into smaller, more focused modules?**
  _Cohesion score 0.09686609686609686 - nodes in this community are weakly interconnected._
- **Why does `react` connect `react` to `RoleFormDialog.tsx`, `RolesPage.tsx`, `auth/api.ts`, `App Layout & Sidebar`, `App.tsx`, `Frontend Dev Dependencies`, `Frontend Runtime Dependencies`, `React Query Bootstrap`?**
  _High betweenness centrality (0.026) - this node is a cross-community bridge._
- **Should `API Errors & Controllers` be split into smaller, more focused modules?**
  _Cohesion score 0.07420091324200913 - nodes in this community are weakly interconnected._