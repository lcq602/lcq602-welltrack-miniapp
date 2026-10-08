# WellTrack Implementation Plan

> **For agentic workers:** Execute inline in this session. Steps are tracked as checkboxes.

**Goal:** Build a usable native WeChat mini program backed by isolated FastAPI/SQLite APIs for personal health tracking.

**Architecture:** Keep SQLite and all authorization in FastAPI. Use a local-only signed development token for simulator use; use wx.request for all data operations. Organize API schemas, database/session, auth, routers, and tests as focused modules.

**Tech Stack:** WeChat native WXML/WXSS/JS, Python 3.12, FastAPI, SQLAlchemy, Pydantic, SQLite, uv, pytest.

## Global Constraints

- Listen on `0.0.0.0:8000`; API prefix `/api/v1`.
- Never accept user IDs from clients; every query is constrained by authenticated principal.
- No actual medical diagnosis, extreme diet advice, or weight-loss guarantees.
- No AppID required for local simulator; secrets come from environment.
- Add tests for core API operations and user isolation.

## File Map

- `backend/app/`: app factory, config, database, schemas, auth and API routes; `backend/` owns `pyproject.toml` and `uv.lock`.
- `backend/tests/`: API behavior and isolation tests.
- `miniprogram/`: reusable API helpers and native pages for home, plan, records, exercise, analysis and profile.
- Root: uv project/lock, environment example, ignore rules, startup scripts, readme, append-only modification log.

### Task 1: Backend foundation and identity

- [x] Add backend package, SQLAlchemy entities, SQLite initialization, Pydantic request validation, local auth bootstrap and health route.
- [x] Add tests for profile setup and cross-user isolation; install/lock deps with uv.
- [x] Run targeted pytest and Python compile checks.

### Task 2: Append-only records and plan API

- [x] Add append-only plan versions, daily record creation/read and exercise creation/read with validation and date filters.
- [x] Do not add UPDATE, DELETE, bulk import, or restore writes without separate authorization.
- [x] Test create/read, duplicate date behavior, ownership constraints.
- [x] Run backend suite.

### Task 3: Dashboard, analytics and export

- [x] Add dashboard, calendar/month/week analytics, CSV formula-safe export, JSON backup export, profile/reminder preferences.
- [x] Test representative totals, CSV safety and profile endpoints.
- [x] Run complete backend suite.

### Task 4: Mini-program interface

- [x] Replace starter home with a dashboard and first-use profile setup.
- [x] Add API client/token initialization and pages for plans, daily records, exercise, analysis, profile/export/backup.
- [x] Make navigation usable on phone and display API errors/loading states.
- [x] Validate JSON and JavaScript syntax; inspect WXML event/request coverage.

### Task 5: Documentation and final validation

- [x] Add README, `.env.example`, `.gitignore`, uv scripts/config and append `docs/修改记录.md`.
- [x] Remove AppID from project config and prevent private config, DB, env and exports from being committed.
- [x] Run all API tests, syntax/config checks and smoke test server binding/routes.
- [x] Review API ownership, SQL query scoping, compatibility, and state performance limitation if no benchmark exists.

