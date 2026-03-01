# HealthHelper

Android app that interfaces with Health Connect to push and read health data.

## Build & Test

```bash
./gradlew test              # Run unit tests (JUnit 5 + MockK)
./gradlew assembleDebug     # Build debug APK
./gradlew installDebug      # Build + install on connected device
```

Run tests before every commit.

## Architecture

MVVM + Clean Architecture: `domain/` (pure Kotlin) → `data/` (Health Connect) → `presentation/` (Compose + ViewModels).

Source root: `app/src/main/kotlin/com/healthhelper/app/`

## Gotchas

IMPORTANT — these cause build failures or subtle bugs if ignored:

- **AGP 9 bundles Kotlin 2.2.10** — do NOT add `kotlin-android` plugin separately
- **Hilt must be 2.59+** for AGP 9 compatibility (BaseExtension removed in AGP 9)
- **KSP + AGP 9** requires `android.disallowKotlinSourceSets=false` in `gradle.properties`
- **JUnit 5 on Gradle 9** needs `junit-platform-launcher` as `testRuntimeOnly` dependency
- **Health Connect Metadata** — use `Metadata.manualEntry()` / `Metadata.autoRecorded()` factories; the constructor is internal
- **Health Connect rate limits** — use changelog sync, don't delete-and-rewrite records
- All dependency versions managed in `gradle/libs.versions.toml`

## Git

- **No Co-Authored-By** — Never add `Co-Authored-By` trailers to commit messages, PR descriptions, or anywhere else

## Conventions

- **Trailing commas**: on all multi-line parameter lists
- **Naming**: UseCase, Repository, Screen, ViewModel suffixes
- **Error handling**: try-catch at data layer boundaries; domain layer returns Boolean/Result
- **DI**: Constructor injection via `@Inject`; Hilt modules in `di/` package
- **State**: `StateFlow` in ViewModels, `collectAsState()` in Compose
- **Compose**: `hiltViewModel()` for ViewModel injection in screens

## Testing

TDD with domain-first testing:

- **Domain** (use cases, models): Pure JUnit 5 + MockK — no Android deps
- **ViewModels**: JUnit 5 + Turbine + MockK
- **Repository/Health Connect**: Robolectric if needed
- **UI**: Compose testing rules on device/emulator

Tests: `app/src/test/` (unit), `app/src/androidTest/` (instrumented).

## Health Connect

- Permissions in `AndroidManifest.xml` (READ/WRITE for Steps and HeartRate)
- `ViewPermissionUsageActivity` alias required for privacy policy display
- Min SDK 28 required

## Linear Integration

| Field | Value |
|---|---|
| Team | Health Helper |
| Issue prefix | HEA-xxx |

## Skills

Invoke with `/<skill-name>`. All skills in `.claude/skills/<name>/SKILL.md`.

| Skill | Description |
|---|---|
| `plan-inline` | Create implementation plan from a Linear issue or description |
| `plan-fix` | Create a fix plan from a bug report or failing test |
| `plan-backlog` | Plan a batch of Backlog issues into an implementation plan |
| `add-to-backlog` | Quick-add an issue to Linear Backlog |
| `backlog-refine` | Refine and prioritize the Linear Backlog |
| `pull-from-roadmap` | Pull issues from Roadmap into the current sprint |
| `plan-implement` | Implement the current plan (TDD workflow) |
| `plan-review-implementation` | QA review of completed implementation (3 specialized reviewers) |
| `code-audit` | Full codebase security and quality audit |
| `deep-review` | Deep analysis of a single screen or feature |
| `frontend-review` | Compose UI review (accessibility, design, performance) |
| `investigate` | Research and investigate a topic or bug |
| `push-to-production` | Release a new version with debug APK on GitHub |
| `tools-improve` | Create or improve Claude Code skills, agents, and CLAUDE.md |

## Agents

Subagents in `.claude/agents/<name>.md`. Spawned by skills or via the Task tool.

| Agent | Description |
|---|---|
| `verifier` | Runs tests, lint, and build; reports results |
| `bug-hunter` | Finds bugs in uncommitted changes (security, logic, coroutines) |
| `pr-creator` | Creates GitHub PRs with branch, commit, push, and Linear integration |
