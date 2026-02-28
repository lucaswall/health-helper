# Changelog Guidelines

This is a **product changelog** — every entry must describe something a user of the app would notice or care about. Think "what changed when I open the app?" not "what code was written."

## INCLUDE

- New features or screens users interact with
- Changes to existing user-visible behavior or UI
- Bug fixes that affected users
- Performance improvements users would notice
- New health data types supported

## Key Principle: Net Effect from Production

The changelog describes the **net difference between current production and the new release** — NOT a commit-by-commit replay of the development cycle. Always compare against `origin/release` (production) when deciding what to include.

**Development-internal churn gets zero entries.** Examples:

- Bug introduced in commit A, fixed in commit B → neither appears (production never had the bug)
- Feature implemented, then reworked or redesigned before release → one entry describing the final version, not the journey
- Code added then removed within the same cycle → zero entries
- Fix for a regression that only existed in development → zero entries

When in doubt, ask: "Would a user on the current production version notice this change?" If not, skip it.

## EXCLUDE — never add entries for

- Development-internal fixes (bugs that only existed during development, never in production)
- Changes that cancel each other out within the release cycle
- Rework/iteration on features introduced in the same cycle (only describe the final result)
- Internal component/utility names (describe what the user sees, not `FooViewModel`)
- Skill, tooling, or Claude Code changes
- Infrastructure changes (CI, build config, internal architecture)
- Internal implementation details (metadata storage, defensive checks, logging)
- Linear issue numbers (e.g., HEA-224) — meaningless to users

## Writing Style

- Describe from the user's perspective: "Blood pressure readings now sync to Health Connect" not "Added BloodPressureSyncUseCase"
- Never expose class names, ViewModel names, or package paths
- One commit can map to zero entries (if purely internal) or one entry
- Multiple commits can be grouped into a single entry
