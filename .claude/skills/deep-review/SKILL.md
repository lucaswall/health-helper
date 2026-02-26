---
name: deep-review
description: Deep, focused analysis of a single screen or feature. Combines code correctness, security, UX, and performance in one unified Opus pass with cross-domain reasoning. Finds bugs that broad audits miss by tracing full data flows and component interactions. Use when user says "deep review", "deeply analyse", "review this screen", "find all bugs in X", or wants thorough analysis of a specific area. Requires a target area argument.
argument-hint: <screen or feature, e.g. "settings screen", "health dashboard", "heart rate detail">
allowed-tools: Read, Glob, Grep, Bash, mcp__linear__list_teams, mcp__linear__list_issues, mcp__linear__get_issue, mcp__linear__create_issue, mcp__linear__update_issue, mcp__linear__list_issue_labels, mcp__linear__list_issue_statuses
disable-model-invocation: true
---

Deep analysis of a focused area. You are Opus analyzing directly — no delegation, no team. The value is YOUR cross-domain reasoning across all related files in one context.

ultrathink

## Pre-flight

1. **Validate argument** — `$ARGUMENTS` is REQUIRED. If empty, STOP: "Please specify a target area to review. Example: `/deep-review health dashboard`"
2. **Verify Linear MCP** — Call `mcp__linear__list_teams`. If unavailable, STOP: "Linear MCP is not connected. Run `/mcp` to reconnect, then re-run."
3. **Read CLAUDE.md** — Load project rules, conventions, and accepted patterns. Extract the team name from the LINEAR INTEGRATION section (look for "Team:" line). If CLAUDE.md doesn't have a LINEAR INTEGRATION section, use the team name from `mcp__linear__list_teams` (there should only be one team in most cases).
4. **Query existing Backlog issues** — `mcp__linear__list_issues` with discovered team name, state "Backlog". Record titles and file paths to avoid creating duplicates.

## Scope Discovery

Trace the full dependency graph for `$ARGUMENTS`. Find EVERY file that participates in the target feature.

### Step 1: Find entry points

Read CLAUDE.md's STRUCTURE section to discover file patterns. Use Glob to locate primary files matching `$ARGUMENTS` based on the discovered patterns. Common patterns include:
- Screens/Composables (e.g., `app/src/main/kotlin/**/ui/**/*.kt`, `app/src/main/kotlin/**/presentation/**/*.kt`)
- ViewModels (e.g., `app/src/main/kotlin/**/viewmodel/*.kt`, `app/src/main/kotlin/**/*ViewModel.kt`)
- Use Cases (e.g., `app/src/main/kotlin/**/domain/usecase/*.kt`)
- Repositories (e.g., `app/src/main/kotlin/**/data/repository/*.kt`)
- Navigation (e.g., `app/src/main/kotlin/**/navigation/*.kt`)

If the argument is ambiguous, use Grep to search for the feature name across the codebase.

### Step 2: Trace imports

Read each entry point. For every import:
- Composable imports → add the composable file
- ViewModel imports → add the ViewModel file
- UseCase/Repository imports → add the domain/data layer files
- Model/Entity imports → add the model file
- Hilt module imports → note the DI configuration
- Utility imports → add the utility module

Follow imports recursively until the full dependency tree is mapped.

### Step 3: Map data flows

For any repository calls, Health Connect API usage, or network requests in ViewModels:
- Extract the data operation
- Find the corresponding repository implementation and data source
- Add the data source AND its dependencies
- Trace the data flow from source (Health Connect, API, Room) through repository to ViewModel to UI

### Step 4: Find related files

For each source file:
- Test file: check for corresponding `*Test.kt` in test path
- DI modules: check for Hilt @Module/@Provides that wire up the dependency
- Navigation graph: check for NavHost/composable() destinations

### Step 5: Output file manifest

List all discovered files grouped by role:
- **Screens/Composables** — UI entry points and components
- **ViewModels** — state management and business logic orchestration
- **Use Cases** — domain layer business rules
- **Repositories** — data access abstraction
- **Data Sources** — Health Connect, Room, API implementations
- **Models/Entities** — data classes, Room entities
- **DI Modules** — Hilt modules and bindings
- **Navigation** — navigation graph definitions
- **Tests** — test files
- **Config** — build config, manifest entries

Output the manifest before proceeding so the user can verify scope.

## Read All Files

Read EVERY file in the manifest. Cross-domain reasoning requires holding all related code in context simultaneously.

Read in dependency order: models → data sources → repositories → use cases → ViewModels → composables → navigation → tests.

## Deep Analysis

Read [references/deep-review-checklist.md](references/deep-review-checklist.md) for the comprehensive cross-domain checklist.

**Critical instruction:** Do NOT analyze each file in isolation. Reason about how files INTERACT. For each finding, trace the impact across the full stack.

### Analysis approach

Walk through the user's journey for this feature:
1. **Arrival** — User navigates to the screen. What loads? What can fail during initial composition? Are permissions checked?
2. **Interaction** — User takes actions. What state changes? What Health Connect or API calls fire? What race conditions exist?
3. **Data processing** — What validates input? What can fail? How do errors propagate back to the UI?
4. **AI integration** — If the feature involves Claude API: trace input preparation → API call → tool loop → response validation → UI rendering. Check tool definitions, system prompts, agentic loop correctness, and response validation (see checklist section 9).
5. **UI update** — Does the Composable handle all state variants? All error types? Loading states? Does it recompose efficiently?
6. **Edge cases** — Empty Health Connect data, concurrent operations, configuration change during async work, no network, large data sets, back navigation during loading

### Findings format

For each finding, record:
- **Severity:** [critical] | [high] | [medium] | [low]
- **Domain:** code | security | ux | performance
- **Location:** file:line (and related files if cross-cutting)
- **Description:** What's wrong
- **Impact:** Who is affected and how
- **Cross-domain note:** How this interacts with other parts of the system (if applicable)

## Create Linear Issues

For each finding, check against existing Backlog issues. Skip if a matching issue already exists.

Use `mcp__linear__create_issue`:

```
team: "<discovered-team-name>"
state: "Backlog"
title: "[Brief description]"
priority: [1-4] (critical=1, high=2, medium=3, low=4)
labels: [mapped label]
description: (format below)
```

**Issue description format:**

```
**Problem:**
[1-2 sentence problem statement]

**Context:**
[File paths with line numbers — include ALL related files, not just the primary location]

**Impact:**
[Who is affected and how — trace the user-facing consequence]

**Acceptance Criteria:**
- [ ] [Verifiable criterion]
- [ ] [Another criterion]
```

**Label mapping:**

| Finding domain | Linear label |
|---------------|-------------|
| code (bugs, logic, coroutines, edge cases) | Bug |
| security (permissions, storage, injection) | Security |
| ux (loading, feedback, accessibility, adaptive layout) | Improvement |
| performance (recomposition, memory, image loading) | Performance |
| convention (CLAUDE.md violation) | Convention |

## Termination

Output this report and STOP:

```
## Deep Review: $ARGUMENTS

**Files analyzed:** N
**Scope:** [list of file groups with counts]

### Findings (ordered by severity)

| # | ID | Severity | Domain | Title |
|---|-----|----------|--------|-------|
| 1 | HH-XX | Critical | code | Brief title |
| 2 | HH-XX | High | ux | Brief title |
| ... | ... | ... | ... | ... |

X issues created | Duplicates skipped: N

### Cross-cutting Observations

[Systemic patterns observed across the feature — e.g., "error handling is inconsistent between the repository and ViewModel" or "loading states exist but don't cover all async paths"]
```

Do not ask follow-up questions. Do not offer to fix issues.
