# AGENTS.md

## Project Overview

- This repository is a Halo plugin that integrates Meilisearch as a search engine.
- Backend Java code lives in `src/main/java/run/halo/meilisearch`.
- Plugin metadata and Halo resources live in `src/main/resources`, especially `plugin.yaml` and `extensions/`.
- The Console UI lives in `ui/` and is bundled into the plugin during Gradle builds.
- Generated OpenAPI client code lives in `ui/src/api/generated`; do not hand-edit it unless the task is specifically about generated output.

## Environment

- Use Java 21 and the Gradle wrapper.
- Use Node.js 24 and pnpm 10 for the UI, matching `.github/workflows/ci.yaml`.
- Compatibility is declared in multiple places: `build.gradle`, `src/main/resources/plugin.yaml`, and `ui/package.json`. Keep these changes intentional and aligned when updating Halo versions.

## Setup And Build Commands

- Full build: `./gradlew build`
- Backend tests only: `./gradlew test`
- UI unit tests through Gradle: `./gradlew :ui:pnpmCheck`
- UI install: `pnpm --dir ui install`
- UI build and type-check: `pnpm --dir ui build`
- UI type-check only: `pnpm --dir ui type-check`
- UI lint and format: `pnpm --dir ui lint` and `pnpm --dir ui prettier`

For local Halo plugin development:

- Start Halo with the plugin: `./gradlew haloServer`
- Reload plugin changes: `./gradlew reload`
- Watch and rebuild during development: `./gradlew watch`
- Regenerate the UI API client after backend endpoint changes: `./gradlew generateApiClient`

## Backend Guidelines

- Follow existing Spring and Halo plugin patterns: constructor injection, small components, and explicit logging around external Meilisearch calls.
- Keep plugin settings centered on `MeilisearchProperties`, `settings.yaml`, and the `meilisearch-engine-config` ConfigMap.
- Treat Meilisearch as an external dependency that can be unavailable. Preserve tolerant behavior where search/index operations log failures without breaking Halo core flows.
- Do not log or expose `masterKey` values.
- When adding or changing `CustomEndpoint` routes, update OpenAPI metadata and regenerate `ui/src/api/generated`.
- Prefer Halo plugin APIs and local project patterns over ad hoc infrastructure.

## Frontend Guidelines

- Use Vue 3 with `<script setup lang="ts">`.
- Use `@halo-dev/components`, `@halo-dev/ui-shared`, Vue Query, and the generated API client before introducing new UI or request abstractions.
- Keep Console-facing copy in Chinese unless the surrounding UI is already English.
- Preserve the current UnoCSS usage, including the `:uno:` prefix used in existing Vue templates.
- Use icons from `unplugin-icons` when adding icon UI.
- Do not edit `ui/src/api/generated` by hand; regenerate it from backend OpenAPI changes.

## Testing Expectations

- Run the smallest relevant check while iterating, then run broader checks before handing off meaningful code changes.
- For backend behavior, add or update JUnit tests under `src/test/java`.
- For UI logic, add or update Vitest/component tests when the change has non-trivial behavior.
- `./gradlew :ui:pnpmCheck` currently runs UI unit tests only; run `pnpm --dir ui type-check` or `pnpm --dir ui build` for TypeScript validation.
- For docs-only changes, at least inspect the diff and run `git diff --check`.

## Documentation Lookup

- Use Context7 MCP to fetch current documentation whenever the task asks about a library, framework, SDK, API, CLI tool, or cloud service.
- Start with `resolve-library-id` unless the user provides an exact `/org/project` library ID.
- Pick the best matching library ID by exact name, relevance, snippet count, source reputation, and benchmark score.
- Query docs with the full user question, then answer using the fetched docs.
- Do not use Context7 for ordinary refactors, scripts from scratch, business logic debugging, code review, or general programming concepts.

## Security And Data Handling

- Never commit real Meilisearch master keys, Halo tokens, local URLs with credentials, or generated local config files.
- Keep examples and README snippets on placeholders such as `<your-super-secret-master-key-here>`.
- Be careful with search filters and user-controlled values that become Meilisearch filter expressions.
- Avoid destructive index operations unless the user explicitly asks for them or the existing UI flow clearly confirms the action.

## Pull Request Notes

- Keep changes scoped to the task and avoid unrelated formatting churn.
- Mention the checks you ran in the final response or PR body.
- If changing runtime compatibility, document the affected Halo, Java, Node, pnpm, or Meilisearch version assumptions.
