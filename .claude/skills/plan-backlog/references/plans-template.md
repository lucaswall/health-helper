# PLANS.md Structure Template

Use this template when writing PLANS.md:

```markdown
# Implementation Plan

**Status:** IN_PROGRESS
**Branch:** feat/PROJ-123-short-description
**Issues:** PROJ-123, PROJ-456
**Sentry Issues:** [Sentry issue URLs] (if originating from Sentry, otherwise omit this line)
**Created:** YYYY-MM-DD
**Last Updated:** YYYY-MM-DD

## Summary

Brief description of what this plan implements and why.

## Issues

### PROJ-123: Issue Title

**Priority:** High/Medium/Low
**Labels:** Bug, Feature, etc.
**Description:** Copy or summarize the issue description from Linear.

**Acceptance Criteria:**
- [ ] Criterion 1
- [ ] Criterion 2
- [ ] Criterion 3

### PROJ-456: Issue Title

(Same structure for additional issues)

## Prerequisites

List anything that must be true before starting implementation:
- [ ] Gradle dependencies are up to date
- [ ] gradle/libs.versions.toml has required library versions
- [ ] Build compiles successfully

## Implementation Tasks

### Task 1: [Short description]

**Issue:** PROJ-123
**Files:**
- `app/src/main/kotlin/com/healthhelper/app/domain/usecase/SomeUseCase.kt` (create)
- `app/src/test/kotlin/com/healthhelper/app/domain/usecase/SomeUseCaseTest.kt` (create)

**TDD Steps:**

1. **RED** - Write failing test:
   - Create `app/src/test/kotlin/com/healthhelper/app/domain/usecase/SomeUseCaseTest.kt`
   - Test that [specific behavior]
   - Run: `./gradlew test --tests "com.healthhelper.app.domain.usecase.SomeUseCaseTest"`
   - Verify: Test fails with [expected error]

2. **GREEN** - Make it pass:
   - Create `app/src/main/kotlin/com/healthhelper/app/domain/usecase/SomeUseCase.kt`
   - Implement [specific logic]
   - Run: `./gradlew test --tests "com.healthhelper.app.domain.usecase.SomeUseCaseTest"`
   - Verify: Test passes

3. **REFACTOR** - Clean up:
   - Extract [shared logic] if needed
   - Ensure naming follows project conventions

**Defensive Requirements:**
- [What exceptions can this code throw? How should they be handled?]
- [What external calls need timeouts? Specify timeout values.]
- [What edge cases must be tested? (empty data, null, concurrent access)]
- [What permissions/state must be checked before the operation?]
- [Omit this section only for pure config/cleanup tasks with no runtime behavior]

**Notes:**
- Use [specific pattern] from existing codebase
- Reference: `app/src/main/kotlin/com/healthhelper/app/domain/usecase/ExistingExample.kt`

### Task 2: [Short description]

(Same structure for each task)

### Task N: Integration & Verification

**Issue:** PROJ-123, PROJ-456
**Files:**
- Various files from previous tasks

**Steps:**

1. Run full test suite: `./gradlew test`
2. Run linter: `./gradlew lint`
3. Build check: `./gradlew assembleDebug`
4. Manual verification steps:
   - [ ] Step 1
   - [ ] Step 2

## MCP Usage During Implementation

Document which MCP tools the implementer should use:

| MCP Server | Tool | Purpose |
|------------|------|---------|
| Linear | `update_issue` | Move issues to "In Progress" when starting, "Done" when complete |

## Error Handling

| Error Scenario | Expected Behavior | Test Coverage |
|---------------|-------------------|---------------|
| Invalid input | Return validation error | Unit test |
| Network failure | Retry with backoff | Unit test |
| Missing data | Show appropriate empty state | Unit test |

## Risks & Open Questions

- [ ] Risk/Question 1: Description and mitigation
- [ ] Risk/Question 2: Description and mitigation

## Scope Boundaries

**In Scope:**
- Items explicitly mentioned in the issues

**Out of Scope:**
- Items NOT part of the current issues
- Future enhancements mentioned but not planned
```
