---
name: code-audit
description: Audits codebase using an agent team with 3 domain-specialized reviewers (security, reliability, quality). Triages open Sentry issues (creates Linear issues for real bugs, resolves/ignores noise). Creates Linear issues in Backlog state for findings. Use when user says "audit", "find bugs", "check security", "review codebase", or "team audit". Higher token cost, faster and deeper analysis. Falls back to single-agent mode if agent teams unavailable.
argument-hint: [optional: specific area like "domain" or "presentation"]
allowed-tools: Read, Glob, Grep, Task, Bash, TeamCreate, TeamDelete, SendMessage, TaskCreate, TaskUpdate, TaskList, TaskGet, mcp__linear__list_teams, mcp__linear__list_issues, mcp__linear__get_issue, mcp__linear__create_issue, mcp__linear__update_issue, mcp__linear__list_issue_labels, mcp__linear__list_issue_statuses, mcp__sentry__find_organizations, mcp__sentry__find_projects, mcp__sentry__search_issues, mcp__sentry__get_issue_details, mcp__sentry__analyze_issue_with_seer, mcp__sentry__update_issue
disable-model-invocation: true
---

Perform a comprehensive code audit using an agent team with domain-specialized reviewers. You are the **team lead/coordinator**. You orchestrate 3 reviewer teammates who scan the codebase in parallel, then you merge findings and create Linear issues.

**If agent teams are unavailable** (TeamCreate fails), fall back to single-agent mode — see "Fallback: Single-Agent Mode" section.

## Pre-flight

1. **Verify Linear MCP** — Call `mcp__linear__list_teams`. If unavailable, STOP and tell the user: "Linear MCP is not connected. Run `/mcp` to reconnect, then re-run this skill."
2. **Discover project info from CLAUDE.md** — Read the LINEAR INTEGRATION section to find:
   - Team name (e.g., "HealthHelper")
   - Issue prefix (e.g., HH-xxx)
   - If LINEAR INTEGRATION section not found, call `mcp__linear__list_teams` to discover the team
3. **Read CLAUDE.md** — Load project-specific rules to audit against (if exists)
4. **Query Linear Backlog** — Get existing issues using `mcp__linear__list_issues` with:
   - `team`: [discovered team name]
   - `state`: "Backlog"
   - `includeArchived`: false
   - For each issue, record: ID, title, labels, priority, description
   - **Audit issues** (labels: Bug, Security, Performance, Convention, Technical Debt) → mark as `pending_validation`
   - **Non-audit issues** (labels: Feature, Improvement) → mark as `preserve` (skip validation)
5. **Discover project structure** — Read `build.gradle.kts`, `gradle/libs.versions.toml`, `.gitignore` in parallel
   - Use Glob with patterns to identify source directories: `app/src/main/kotlin/**/*.kt`, `app/src/test/kotlin/**/*.kt`
   - If no build.gradle.kts, use conventions: `app/src/main/kotlin/`, `app/src/test/kotlin/`
6. **Run `./gradlew lint`** — Capture lint findings for later (dependency/code quality issues)
7. **Discover Sentry context** — Call `mcp__sentry__find_organizations` to discover org slug, then `mcp__sentry__find_projects` with org slug to find the project. If Sentry MCP unavailable, skip Sentry triage later (warn user).
8. **Fetch unresolved Sentry issues** — Call `mcp__sentry__search_issues` with org slug and project slug, query: "unresolved issues". Record each issue's ID, title, URL, event count, user count, last seen date. If none found, skip Sentry Triage phase later.

## Team Setup

### Create the team

Use `TeamCreate`:
- `team_name`: "code-audit"
- `description`: "Parallel code audit with domain-specialized reviewers"

**If TeamCreate fails**, switch to Fallback: Single-Agent Mode (see below).

### Create tasks

Use `TaskCreate` to create 3 review tasks (these track progress for each reviewer):

1. **"Security audit"** — Security, permissions & data protection review of the codebase
2. **"Reliability audit"** — Bugs, coroutines, resources, memory leaks, lifecycle issues
3. **"Quality audit"** — Type safety, conventions, logging, tests, dead code

### Spawn 3 reviewer teammates

Use the `Task` tool with `team_name: "code-audit"`, `subagent_type: "general-purpose"`, and `model: "sonnet"` to spawn each reviewer. Give each a `name` and a detailed `prompt` (see Reviewer Prompts below).

Spawn all 3 reviewers in parallel (3 concurrent Task calls in one message).

**IMPORTANT:** Each reviewer prompt MUST include:
- Their specific domain checklist (copied from the Reviewer Prompts section)
- The focus area if `$ARGUMENTS` specifies one
- The list of existing `pending_validation` issues relevant to their domain (so they can validate them)
- Instructions to report findings as a structured message to the lead

### Assign tasks

After spawning, use `TaskUpdate` to assign each task to its reviewer by name.

## Reviewer Prompts

Each reviewer gets a tailored prompt. Include the full text below in each reviewer's spawn prompt, substituting the domain-specific section.

### Common Preamble (include in ALL reviewer prompts)

```
You are a code audit reviewer for this project. Your job is to scan the ENTIRE codebase and find issues in your assigned domain.

RULES:
- Analysis only — do NOT modify any source code
- Do NOT create Linear issues — report findings to the team lead
- No solutions — document problems only, not fixes
- Be specific — include file paths and approximate line numbers
- Be thorough — check every file in scope
- Focus area: {$ARGUMENTS or "entire codebase"}

PROJECT CONTEXT:
- Language: Kotlin 2.2.10
- Framework: Android (Jetpack Compose, Material 3, Hilt DI)
- Build: Gradle 9.1.0 with AGP 9.0.1
- Test: JUnit 5 + MockK
- Architecture: Clean Architecture (domain/data/presentation layers)
- Source path: app/src/main/kotlin/com/healthhelper/app/
- Test path: app/src/test/kotlin/com/healthhelper/app/
- Health Connect SDK for health data
- Navigation Compose for navigation
- StateFlow for reactive state

WORKFLOW:
1. Read CLAUDE.md for project-specific rules
2. Read .claude/skills/code-audit/references/compliance-checklist.md for detailed audit checks in your domain
3. Discover all source files using Glob (check app/src/main/kotlin/**/*.kt)
4. Read each source file systematically
5. Use Grep to search for specific patterns (see your checklist AND the compliance checklist)
6. Validate any existing issues assigned to you (check if code still has the problem)
7. When done, send your findings to the lead using SendMessage

EXISTING ISSUES TO VALIDATE:
{list of pending_validation issues relevant to this reviewer's domain}
For each, check if the referenced code still has the problem. Report as:
- FIXED: [issue ID] - [reason]
- STILL EXISTS: [issue ID]

FINDINGS FORMAT - Send a message to the lead with this structure:
---
DOMAIN: {domain name}
VALIDATED EXISTING ISSUES:
- FIXED: HH-XX - [reason]
- STILL EXISTS: HH-YY

NEW FINDINGS:
1. [category-tag] [priority-tag] [file-path:line] - [description]
2. [category-tag] [priority-tag] [file-path:line] - [description]
...

Category tags: [security], [bug], [coroutine], [memory-leak], [resource-leak], [lifecycle], [edge-case], [type], [convention], [logging], [dependency], [rate-limit], [dead-code], [duplicate], [test], [practice], [docs], [chore], [permission], [injection], [storage]
Priority tags: [critical], [high], [medium], [low]
---
```

### Security Reviewer Prompt (name: "security-reviewer")

Append to the common preamble:

```
YOUR DOMAIN: Security, Permissions & Data Protection

Focus areas (full details in compliance-checklist.md):
- Android Permissions — Health Connect permissions, runtime permission handling, permission rationale
- Intent Security — explicit intents, intent filter validation, PendingIntent security (FLAG_IMMUTABLE)
- Data Storage — no sensitive data in SharedPreferences without encryption, EncryptedSharedPreferences for tokens
- Content Provider Security — exported providers protected, URI permissions scoped
- Network Security — certificate pinning, network security config, no cleartext traffic
- ProGuard/R8 — obfuscation enabled for release builds, sensitive class rules
- Secrets & Credentials — hardcoded secrets, API keys, secrets in logs/errors
- Input Validation — SQL injection (Room queries), path traversal, deep link validation
- Health Data Privacy — Health Connect data handled per Google Play policies, minimal data collection

Search patterns (use Grep):
- `password|secret|api.?key|token` (case insensitive) — potential hardcoded secrets
- `Intent\(` without explicit component — implicit intent risk
- `PendingIntent` without `FLAG_IMMUTABLE` — PendingIntent vulnerability
- `getSharedPreferences|SharedPreferences` — potential insecure storage
- `MODE_WORLD_READABLE|MODE_WORLD_WRITABLE` — insecure file permissions
- `exported\s*=\s*true` — exported components to verify
- `android:allowBackup` — backup security check
- `cleartext|usesCleartextTraffic` — cleartext traffic
- `Log\.(d|v|i|w|e)\(` containing sensitive data — secrets in logs
- `@Query|@RawQuery` with string concatenation — SQL injection in Room
```

### Reliability Reviewer Prompt (name: "reliability-reviewer")

Append to the common preamble:

```
YOUR DOMAIN: Bugs, Coroutines, Resources & Reliability

Focus areas (full details in compliance-checklist.md):
- Logic errors — off-by-one, empty collections, wrong comparisons
- Null safety — nullable types, platform types from Java interop, missing null checks
- Race conditions — shared mutable state, concurrent coroutine access
- Coroutine issues — unstructured concurrency, missing CoroutineExceptionHandler, wrong dispatcher
- Lifecycle issues — collecting flows in wrong lifecycle state, not using repeatOnLifecycle
- Memory leaks — Activity/Context references in ViewModels, static references to Views
- Resource leaks — unclosed Cursors, InputStreams, database connections
- Lifecycle awareness — not respecting Activity/Fragment lifecycle, leaking in onResume without cleanup in onPause
- Health Connect session management — HealthConnectClient lifecycle, permission result handling
- Boundary conditions — empty inputs, max-size, negative/zero

Search patterns (use Grep):
- `GlobalScope` — unstructured concurrency (should use viewModelScope or lifecycleScope)
- `launch\s*\{|async\s*\{` without structured scope — verify scope usage
- `Dispatchers\.IO|Dispatchers\.Main|Dispatchers\.Default` — verify correct dispatcher
- `collect\s*\{` — verify lifecycle-aware collection (repeatOnLifecycle)
- `mutableStateOf|MutableStateFlow|MutableSharedFlow` — shared state (check thread safety)
- `viewModelScope|lifecycleScope` — verify proper scope usage
- `withContext\(` — verify dispatcher switching
- `try\s*\{` in coroutines — verify exception handling
- `remember\s*\{` — check for stale state captures in Compose
- `LaunchedEffect|DisposableEffect|SideEffect` — verify cleanup and key usage
```

### Quality Reviewer Prompt (name: "quality-reviewer")

Append to the common preamble:

```
YOUR DOMAIN: Type Safety, Conventions, Logging & Test Quality

Focus areas (full details in compliance-checklist.md):

TYPE SAFETY:
- Unsafe casts (`as` without `is` check, `as?` not used for nullable casts)
- Platform types from Java interop (nullable annotations missing)
- Sealed class/interface exhaustive `when` handling
- External data used without validation (API responses, Health Connect data)
- Missing runtime validation for data class construction

CLAUDE.md COMPLIANCE (read CLAUDE.md first!):
- Package structure, naming conventions, error handling patterns
- Hilt module organization, ViewModel patterns, all project-specific rules

LOGGING:
- Wrong logger (println/System.out vs Timber or project logger)
- Wrong log levels, missing logs in error paths
- Double-logging (same error at multiple layers)
- Sensitive data in logs (health data, tokens, PII)
- Log overflow risks (logging in loops, large objects)

TEST QUALITY (if tests exist):
- Tests with no meaningful assertions or that always pass
- Mocks that hide real bugs, missing edge case coverage
- JUnit 5 assertions used correctly
- MockK verify/capture used appropriately

AI INTEGRATION (Claude API — if AI code in scope):
- Tool definitions detailed with parameter descriptions and enums
- stop_reason handled for all values (tool_use, end_turn, max_tokens, refusal, model_context_window_exceeded)
- Agentic loops capped, tool_use.input validated at runtime
- Token usage recorded, API key from env var, no user input in system prompts

Search patterns (use Grep):
- `as [A-Z]` without preceding `is` check — unsafe type cast
- `!!` — force unwrap (potential NPE)
- `@Suppress` — suppressed warnings (verify justified)
- `println|System\.out|System\.err` — should use proper logger
- `catch\s*\([^)]*\)\s*\{[^}]*\}` — empty catch blocks
- `TODO|FIXME|HACK` — unfinished code
- `@Inject` — verify Hilt injection patterns
- `@HiltViewModel` — verify ViewModel conventions
- `stop_reason` — verify all values handled (if AI integration exists)
- `max_tokens` — verify reasonable limits (if AI integration exists)
```

## Coordination (while reviewers work)

While waiting for reviewer messages:
1. Reviewer messages are **automatically delivered** to you — do NOT poll or manually check inbox
2. Teammates go idle after each turn — this is normal. An idle notification does NOT mean they are done. They are done when they send their findings message.
3. Track progress via `TaskList` — check which tasks are in progress vs completed
4. As each reviewer sends findings, acknowledge receipt
5. Wait until ALL 3 reviewers have reported before proceeding to merge

**If a reviewer gets stuck or stops without reporting:** Send them a message asking for their findings. If they don't respond, note that domain as "incomplete" in the final report.

## Merge & Deduplicate

Once all reviewer findings are collected:

### Validate existing issues

Combine validation results from all 3 reviewers:
- Issues reported as FIXED by any reviewer → close in Linear with comment
- Issues reported as STILL EXISTS → carry forward

### Classify pending existing issues

| Status | Criteria | Action |
|--------|----------|--------|
| `superseded` | New finding covers same issue | Close issue (new finding wins) |
| `needs_update` | Issue exists but line numbers or severity changed | Update issue description/priority |
| `still_valid` | Issue unchanged, no overlapping new finding | Keep as-is |

### Deduplicate new findings

- Same code location reported by multiple reviewers → merge into the one with higher priority
- Same root cause manifesting in multiple locations → create one issue covering all locations

### Reassess priorities

| | High Likelihood | Medium Likelihood | Low Likelihood |
|---|---|---|---|
| **High Impact** | Critical | Critical | High |
| **Medium Impact** | High | Medium | Medium |
| **Low Impact** | Medium | Low | Low |

## Create Linear Issues

For each new finding, use `mcp__linear__create_issue`:

```
team: [discovered team name]
state: "Backlog"
title: "[Brief description of the issue]"
description: (see Issue Description Format below)
priority: [1|2|3|4] (mapped from critical/high/medium/low)
labels: [Mapped label(s)]
```

**Issue Description Format:**

```
**Problem:**
[Clear, specific problem statement — 1-2 sentences]

**Context:**
[Affected file paths with line numbers, e.g. `app/src/main/kotlin/com/healthhelper/app/data/repository/HealthRepository.kt:120-135`]

**Impact:**
[Why this matters — user-facing impact, data integrity, security risk, etc.]

**Acceptance Criteria:**
- [ ] [Specific, verifiable criterion — e.g. "Health data queries handle empty results without crash"]
- [ ] [Another criterion]
```

**Label Mapping:**

| Category Tags | Linear Label |
|---------------|--------------|
| `[security]`, `[dependency]`, `[permission]`, `[injection]`, `[storage]` | Security |
| `[bug]`, `[coroutine]`, `[lifecycle]`, `[edge-case]`, `[type]`, `[logging]` | Bug |
| `[memory-leak]`, `[resource-leak]`, `[rate-limit]` | Performance |
| `[convention]` | Convention |
| `[dead-code]`, `[duplicate]`, `[test]`, `[practice]`, `[docs]`, `[chore]` | Technical Debt |

**Priority Mapping:**
- `[critical]` → 1 (Urgent)
- `[high]` → 2 (High)
- `[medium]` → 3 (Medium)
- `[low]` → 4 (Low)

**Rules:**
- NO solutions in issue descriptions — acceptance criteria define "done", not how to get there
- Include file paths with line numbers in Context
- One issue per distinct finding

## Sentry Triage

After creating Linear issues from audit findings, triage all unresolved Sentry issues discovered in pre-flight. The lead handles this directly (not reviewers).

**Skip this section if:** Sentry MCP was unavailable during pre-flight, or no unresolved Sentry issues were found.

### For each unresolved Sentry issue:

1. **Get details** — Call `mcp__sentry__get_issue_details` to get the full stacktrace and context
2. **Locate in codebase** — Read the referenced files/lines from the stacktrace
3. **Cross-reference** — Check if:
   - An audit finding already covers this issue (from the reviewer phase)
   - A Linear issue already exists for this (from pre-flight backlog query)
4. **Decide disposition:**

| Disposition | When | Action |
|---|---|---|
| **Fix needed** | Real bug in current code, not yet tracked | Create Linear issue with Sentry link (see format below) |
| **Already tracked** | Linear issue already exists for this | Skip — note in report |
| **Already covered** | Audit finding already captures this | Skip — audit finding handles it |
| **Already fixed** | Code has been changed, or a completed plan already addresses it | `mcp__sentry__update_issue` with `status: "resolved"` |
| **Noise/transient** | One-off error, expected behavior, test data, transient network issue | `mcp__sentry__update_issue` with `status: "ignored"` |

### Linear Issue Format (for fix-needed Sentry issues)

Use `mcp__linear__create_issue` following the add-to-backlog pattern:

```
team: [discovered team name]
state: "Backlog"
title: "[Brief description from Sentry issue]"
priority: [1|2|3|4] based on event count, user impact, severity
labels: [Bug]
```

**Description format:**

```
**Problem:**
[What is happening — from Sentry stacktrace and context]

**Sentry Issue:**
[Sentry issue URL] — [event count] events, [user count] users, last seen [date]

**Context:**
[Affected file paths with line numbers from stacktrace]

**Impact:**
[User-facing impact based on event frequency and severity]

**Acceptance Criteria:**
- [ ] [Specific fix criterion]
- [ ] Error no longer appears in Sentry after fix deployed
```

## Shutdown Team

After all Linear issues are created and Sentry triage is complete:
1. Send shutdown requests to all 3 reviewers using `SendMessage` with `type: "shutdown_request"`
2. Wait for shutdown confirmations
3. Use `TeamDelete` to remove team resources

## Fallback: Single-Agent Mode

If `TeamCreate` fails (agent teams unavailable), perform the audit sequentially as a single agent:

1. **Inform user:** "Agent teams unavailable. Running audit in single-agent mode."
2. **Validate existing issues** — For each `pending_validation` issue, check if the referenced code still has the problem. Close fixed issues, carry forward valid ones.
3. **Systematic exploration** — Use Task tool with `subagent_type=Explore` to examine each discovered area. Look for:
   - Logic errors, null handling, race conditions
   - Security vulnerabilities (intent injection, insecure storage, exposed secrets, missing permissions)
   - Unhandled edge cases and boundary conditions
   - Type safety issues (unsafe casts, unvalidated external data, platform types)
   - Dead or duplicate code
   - Memory leaks, resource leaks, coroutine issues
   - Lifecycle issues, lifecycle-aware collection problems
   - Logging issues
   See [references/compliance-checklist.md](references/compliance-checklist.md) for detailed checks.
4. **CLAUDE.md compliance** — Check project-specific rules
5. **Merge, deduplicate, reprioritize** — Same process as team mode (see Merge & Deduplicate section)
6. **Create Linear issues** — Same process as team mode (see Create Linear Issues section)
7. **Sentry triage** — Same process as team mode (see Sentry Triage section)

## Error Handling

| Situation | Action |
|-----------|--------|
| Linear MCP not connected | STOP — tell user to run `/mcp` |
| No build.gradle.kts found | Use conventions: `app/src/main/kotlin/`, `app/src/test/kotlin/` |
| `./gradlew lint` fails | Note skip, continue |
| CLAUDE.md doesn't exist | Skip project-specific checks (tell quality-reviewer) |
| Linear Backlog query fails | Continue with fresh audit (no existing issues) |
| No existing Backlog issues | Start fresh (skip validation in reviewer prompts) |
| TeamCreate fails | Switch to single-agent fallback mode |
| Reviewer stops without reporting | Send follow-up message, note domain as incomplete |
| Referenced file no longer exists | Mark issue as `fixed`, close in Linear |
| Cannot determine if issue is fixed | Keep as `still_valid` |
| Large codebase (>1000 files) | Tell reviewers to focus on `$ARGUMENTS` area or entry points |
| Sentry MCP not connected | Skip Sentry triage, warn user |
| No unresolved Sentry issues | Skip Sentry triage phase |
| Sentry issue references deleted file | Mark as `resolved` |
| Cannot determine if Sentry issue is fixed | Create Linear issue to investigate |

## Rules

- **Analysis only** — Do NOT modify source code
- **No solutions** — Document problems, not fixes
- **Lead handles all Linear writes** — Reviewers NEVER create issues directly
- **Deduplicate before creating** — No duplicate issues in Linear
- **Be thorough** — Every file in scope must be checked
- **Sentry triage is lead-only** — Reviewers never interact with Sentry; the lead triages all Sentry issues after merging audit findings
- **Sentry issues that need fixes get Linear issues** — Always include the Sentry issue URL in the description so downstream planning skills can track it

## Termination

Output this report and STOP:

```
## Code Audit Report

**Team:** 3 reviewers (security, reliability, quality)
[OR: **Mode:** single-agent (team unavailable)]
**Preserved:** P non-audit issues (features, improvements)

### Existing Backlog Issues

- A kept (still valid)
- B closed (fixed or superseded)
- C updated (description/priority changed)

### New Issues (ordered by priority)

| # | ID | Priority | Label | Title |
|---|-----|----------|-------|-------|
| 1 | HH-N1 | Urgent | Security | Brief title |
| 2 | HH-N2 | High | Bug | Brief title |
| ... | ... | ... | ... | ... |

X issues total | Duplicates merged: M | Findings dropped: N

### Sentry Triage

| # | Sentry Issue | Disposition | Action |
|---|---|---|---|
| 1 | HEALTH-HELPER-N | Fix needed | Created HH-XX in Backlog |
| 2 | HEALTH-HELPER-N | Already fixed | Resolved in Sentry |
| 3 | HEALTH-HELPER-N | Noise | Ignored in Sentry |
| ... | ... | ... | ... |

[OR: No unresolved Sentry issues found.]
[OR: Sentry MCP unavailable — triage skipped.]

Next step: Review Backlog in Linear and use `plan-backlog` to create implementation plans.
```

Do not ask follow-up questions. Do not offer to fix issues.
