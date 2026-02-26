# Priority Assessment Guide

Assess priority independently for each issue. Priority is NOT determined by tag alone.

## Impact x Likelihood Matrix

| | High Likelihood | Medium Likelihood | Low Likelihood |
|---|---|---|---|
| **High Impact** | Critical | Critical | High |
| **Medium Impact** | High | Medium | Medium |
| **Low Impact** | Medium | Low | Low |

## Impact Factors

- Health data loss or corruption → High
- Security breach / data exposure → High
- App crash (uncaught exception) → High
- Incorrect health metrics displayed to user → High
- User-facing errors or broken flows → Medium
- Performance degradation (slow sync, janky UI) → Medium
- Health Connect permission issues → Medium
- Developer inconvenience → Low
- Code maintainability → Low

## Likelihood Factors

- Happens on every app launch → High
- Happens on every Health Connect sync → High
- Happens under normal usage patterns → High
- Happens on specific health data types → Medium
- Happens only on specific Android API levels → Medium
- Happens only under edge conditions (low memory, no network) → Low
- Requires attacker/malicious input → Varies (High if exported component, Low if internal)

## Examples

- `[security]` Health Connect data accessible without permission check → Critical (high impact + high likelihood)
- `[security]` hardcoded API key in BuildConfig → High (high impact + medium likelihood — depends on reverse engineering)
- `[storage]` health data in plain SharedPreferences → Critical (high impact + high likelihood)
- `[permission]` missing runtime permission request for body sensors → High (broken flow + every user)
- `[memory-leak]` Activity context held in ViewModel → Critical (happens on every config change)
- `[memory-leak]` only on error path during background sync → Medium
- `[coroutine]` GlobalScope.launch in ViewModel → High (survives ViewModel, potential crash)
- `[lifecycle]` collecting Flow in onCreate without repeatOnLifecycle → High (runs when app in background)
- `[bug]` wrong date format in debug logs → Low (low impact)
- `[bug]` wrong heart rate calculation displayed to user → Critical (high impact — health data accuracy)
- `[edge-case]` crash on empty Health Connect data → High (first-time users hit this)
- `[type]` force unwrap (!!) on nullable Health Connect response → High (NPE on real devices)
