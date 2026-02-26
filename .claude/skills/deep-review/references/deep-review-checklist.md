# Deep Review Checklist

Cross-domain checklist organized around interaction patterns. Each section requires reasoning about how multiple files work together — not just individual file quality.

## 1. Data Flow Integrity

Trace data from source to display and back.

### Layer Contract Alignment
- ViewModel calls UseCase with expected parameters; UseCase returns expected types
- Repository interface matches what the UseCase expects
- Repository implementation returns correct data from Health Connect / Room / API
- Error types from data layer are handled in the ViewModel
- UI observes the correct StateFlow fields from the ViewModel

### Type Safety Across Boundaries
- Shared model classes used consistently across layers (domain models, not leaking data layer types)
- No unsafe casts (`as Type`) that paper over a contract mismatch
- Health Connect record conversions preserve type safety (no lossy conversions)
- External API responses validated before use (external data boundary)
- Room entity ↔ domain model mapping is correct and complete

### Data Transformation
- Date/time values serialized correctly (Health Connect uses `Instant`/`ZoneOffset`)
- Numeric values maintain precision through transformations (steps as Long, heart rate as Double)
- Collections handled correctly when empty, single-element, or large
- Null vs absent data handled consistently across layers

## 2. State Lifecycle

### Staleness
- StateFlow initial values appropriate (don't show stale data from previous screen)
- State refreshed after mutations (write to Health Connect → refresh read)
- ViewModel state reset when navigating to different entity (new screen instance or key change)
- No stale references captured in lambda callbacks
- Optimistic updates (if any) correctly roll back on failure

### Race Conditions
- Rapid user actions don't cause interleaved coroutine results
- Navigation away during pending coroutine doesn't cause state update on cleared ViewModel
- Concurrent mutations to same Health Connect record handled (last-write-wins or conflict detection)
- Coroutine cancellation handled correctly (structured concurrency via viewModelScope)

### Lifecycle
- ViewModel survives configuration changes (screen rotation)
- Coroutines cancelled when ViewModel is cleared (viewModelScope auto-cancellation)
- Flow collection lifecycle-aware (collectAsStateWithLifecycle or repeatOnLifecycle)
- No work done after Activity/Fragment destroyed

## 3. Error Path Completeness

### Data Layer Errors
- Every suspend function in repositories has try/catch at boundary
- Health Connect unavailable/permission denied returns appropriate error
- Room database errors return appropriate error state
- External API errors return appropriate error state
- Errors propagated as sealed class/Result, not raw exceptions to UI

### UI Error Handling
- Error states rendered with user-friendly messages in Composables
- Network errors (offline, timeout) distinguished from data errors
- Error messages include actionable guidance ("Grant permission" / "Check connection")
- Composable handles all UiState variants (Loading, Success, Error, Empty)

### Error Recovery
- User can retry after transient failures without navigating away
- Form/input data preserved after submission failure
- Partial success states handled (e.g., some records saved but others failed)

## 4. Edge Cases

### Empty/Missing Data
- Composable renders correctly with no data (empty Health Connect, no records)
- Empty state has clear messaging and call to action
- First-time user experience works (no Health Connect data, permissions not yet granted)

### Boundary Values
- Very long text handled (truncation, wrapping in Compose)
- Very large numbers display correctly (high step counts, extreme heart rates)
- Zero values displayed correctly (0 steps is valid, not treated as "missing")
- Special characters in user input don't break display or queries
- Multiple rapid submissions handled (debounce or button disable)

### Navigation
- Back navigation preserves expected state
- Deep links work (navigating directly to a specific screen/record)
- Configuration change (rotation) during async work doesn't crash or lose state
- Process death and restoration handled (SavedStateHandle)

## 5. Security Surface

### Permissions & Data Access
- Health Connect permissions checked before every read/write operation
- Permission denial handled gracefully (not crash, show explanation)
- Data access scoped to declared permission types only
- No over-requesting Health Connect permissions

### Input Validation
- User input validated before storage operations
- File imports validated (size, type) before processing
- Intent extras validated before use (deep links, share intents)
- No path traversal via user-controlled file paths
- No SQL injection via Room (use DAO parameterized queries, never rawQuery with user input)

### Sensitive Data
- Health data not logged (check Log/Timber calls)
- Sensitive data not in plaintext SharedPreferences (use EncryptedSharedPreferences)
- API keys/tokens not hardcoded or logged
- Error responses don't leak internal details (stack traces, file paths)
- Backup configuration appropriate (`android:allowBackup` rules)

## 6. User Experience

### Loading States
- Async operations show loading indicators (CircularProgressIndicator, skeleton, disabled button)
- Loading state appears immediately (no gap before indicator shows)
- Partial loading: only the updating section shows loading, not the entire screen

### Feedback
- Every user action has visible feedback (button state change, snackbar, inline message)
- Success confirmations are specific (describe what was done, not just "Success")
- Destructive actions require confirmation dialog
- Long operations show progress or at least a descriptive message

### Accessibility
- Interactive elements use proper Compose semantics (`Modifier.clickable` with role, `Button`, not raw `Box` with `Modifier.clickable`)
- Images have meaningful `contentDescription` (or `null` for decorative)
- Form inputs have associated labels (via `OutlinedTextField` label parameter)
- Focus management correct for dialogs (trap focus, return on close)
- Touch targets >= 48dp (`Modifier.minimumInteractiveComponentSize()`)
- Color not the sole indicator of state or information
- TalkBack announces state changes (snackbar, error messages)

### Adaptive Layout
- Layout works across phone sizes (small to large)
- Touch targets have adequate spacing (no accidental taps)
- No gesture-only interactions (must work with TalkBack)
- Keyboard/D-pad navigation works for accessibility
- Safe area insets handled (system bars, display cutouts)

## 7. Performance

### Recomposition
- No unnecessary recompositions (check lambda stability, parameter types)
- `remember` used for expensive computations
- `derivedStateOf` used for derived values that change less often than their inputs
- Large lists use `LazyColumn`/`LazyRow` with `key` parameter
- Composable parameters are stable types (data classes, primitives, not raw collections)

### Memory
- Large data sets paginated (Health Connect time ranges)
- Images/bitmaps recycled when not visible
- No unbounded in-memory caches
- Compose `rememberSaveable` for surviving process death

### Coroutine Efficiency
- No redundant Health Connect queries on recomposition
- `distinctUntilChanged` on Flows to prevent duplicate processing
- Heavy computation on `Dispatchers.Default`, IO on `Dispatchers.IO`
- No waterfall coroutine calls that could be parallel (`async`/`awaitAll`)

## 8. Logging Coverage

### Across the Feature
- Error paths in repositories log with context (action, inputs, error details)
- Health Connect operations log duration and outcome
- Key state changes logged at INFO level (creates, updates, deletes)
- Debug coverage exists for troubleshooting each layer (no blind spots in data modules)

### Common Issues
- Same error logged at both repository and ViewModel (double-logging)
- Missing context on log statements (what operation was being performed)
- Sensitive data in logs (tokens, raw health data)
- No logging in catch blocks (silent failures invisible in production)
- `println` or `System.out` instead of proper logger (Timber or Android Log)

## 9. AI Integration (Claude API)

When the reviewed feature involves Claude API integration, trace the full AI data flow.

### Tool Definition Quality

- Tool descriptions are detailed (3-4+ sentences minimum) — the single most important factor for tool selection accuracy
- Each parameter has a description with examples of valid values
- Constrained values use `enum` arrays (not free-form descriptions)
- Required vs optional parameters correctly specified
- `input_schema` top-level type is `"object"`
- Tool names follow `^[a-zA-Z0-9_-]{1,64}$` pattern
- `strict: true` set on tool definitions for guaranteed schema conformance (eliminates parsing errors, type mismatches). Requires `additionalProperties: false`. Runtime validation still needed for truncation/refusal edge cases.

### System Prompt & Behavioral Design

- Clear role/persona definition in the system prompt
- Tool usage guidance: when to use each tool, when NOT to use, what information each tool does NOT return
- System prompt in sync with tool definitions (no references to renamed/removed tools)
- No sensitive data in system prompts (API keys, tokens, PII)
- Behavioral rules unambiguous (Claude follows last instruction when conflicting)
- For Claude 4+ models: avoid over-prompting tool use — newer models follow instructions precisely and will overtrigger on aggressive language like "CRITICAL: You MUST use this tool"

### Prompt Caching

- Static content (tools, system prompt) marked with `cache_control: {type: "ephemeral"}` for up to 90% input cost reduction and 85% latency reduction
- Minimum cacheable length met: 1,024 tokens for Sonnet 4/4.5/4.6, 4,096 tokens for Opus 4.6/Haiku 4.5
- In multi-turn conversations, final block of final message marked with `cache_control` each turn
- Cache invalidation triggers understood: changing tool definitions invalidates entire cache; changing tool_choice/images/thinking invalidates message cache
- `cache_creation_input_tokens` and `cache_read_input_tokens` monitored in responses

### Tool Use Lifecycle (Agentic Loop)

- `stop_reason` checked for ALL return values:
  - `"tool_use"` → extract tool calls, execute, send results back
  - `"end_turn"` → extract final text + optional tool output
  - `"max_tokens"` → handle truncation (incomplete tool_use blocks are invalid)
  - `"refusal"` → Claude refused for safety reasons. Handle gracefully with user-facing message (Claude 4+)
  - `"model_context_window_exceeded"` → context window limit hit. Requires conversation compaction or message truncation (Claude 4+)
- `tool_result.tool_use_id` matches corresponding `tool_use.id`
- ALL parallel `tool_result` blocks in a SINGLE user message (splitting across messages degrades future parallel tool behavior)
- `tool_result` content blocks come BEFORE any `text` blocks in user messages (API requirement — violating causes 400 error)
- `is_error: true` set on tool_result for execution failures (Claude handles gracefully)
- Agentic loops capped with max iteration count (prevent infinite tool-call cycles)
- After max iterations reached: return best available response, don't hang

### Response Validation

- `tool_use.input` validated at runtime even when `strict: true` is set — Claude can still produce unexpected shapes on `max_tokens` truncation or `refusal`
- Numeric fields validated as non-negative where appropriate
- String fields checked for non-empty where required
- Keywords normalized (lowercase, deduplicated, capped at max count)
- Handle empty text blocks (Claude may respond with only tool_use, no text)

### AI Data Flow Tracing

Follow data through the full AI pipeline:

1. **Input preparation** — Data formatted, user text sanitized, context prepared
   - Are inputs validated before sending?
   - Is user text sanitized before inclusion in Claude messages?
   - Are conversation items properly typed and ordered?

2. **Claude API call** — System prompt, tools, tool_choice, max_tokens
   - Is tool_choice appropriate for the context? ("tool" for forced, "auto" for optional)
   - Is max_tokens sufficient for the expected response?
   - Are tools appropriate for this call? (no write tools in read-only contexts)
   - Is prompt caching configured on static content (system prompt, tools)?
   - **tool_choice + thinking compatibility:** `{type: "tool"}` and `{type: "any"}` are INCOMPATIBLE with extended/adaptive thinking — only `"auto"` and `"none"` work

3. **Response processing** — Tool calls extracted, validated, executed
   - Are all tool_use blocks processed (not just the first)?
   - Is the tool loop iterated correctly (not returning after first tool call)?
   - Are tool results formatted as clean text for Claude to interpret?

4. **Final extraction** — Text + optional analysis returned to caller
   - Is the final text response extracted from all text blocks (joined)?
   - Is the optional tool output validated before return?

5. **UI rendering** — Response displayed in Compose UI
   - Are AI responses rendered with appropriate Composable?
   - Is the state updated correctly in the ViewModel?

### Cost & Token Management

- max_tokens not unnecessarily large
- Tool definitions concise but complete (sent with every request)
- Context window management: token count checked before API calls
- Rate limiting applied to Claude API calls
- Token usage tracking present and non-blocking
- Prompt caching configured on static content to reduce costs
- Production code pins exact model snapshot IDs — aliases can drift to newer snapshots with behavioral changes

### Model Configuration

- `temperature` and `top_p` NOT used simultaneously (breaking change in Claude 4+)
- For Opus 4.6 and Sonnet 4.6: use `thinking: {type: "adaptive"}` with `output_config: {effort: "..."}` — manual `budget_tokens` deprecated. `effort` levels: `max` (Opus 4.6 only), `high` (default), `medium`, `low`
- For older models (Sonnet 4, Sonnet 4.5, Opus 4.5): use `thinking: {type: "enabled", budget_tokens: N}` when thinking desired

### AI-Specific Security

- No user-controlled text injected raw into system prompts (prompt injection)
- Claude API key loaded from environment/BuildConfig (never hardcoded, never logged)
- Tool results don't expose raw credentials, tokens, or session secrets
- AI-generated content sanitized before rendering
- Rate limiting prevents abuse of expensive Claude API calls

### Error Handling

- Claude API 429 (rate limit) handled with retry/backoff using `Retry-After` header
- Claude API 529 (overloaded) handled separately from 500 — transient, retry with exponential backoff
- Timeouts configured on Claude API client
- Request size under 32MB limit for Messages API

## 10. AI-Generated Code Risks

All code in this project is AI-assisted. When tracing data flows and interactions, watch for these AI-specific patterns:

### Cross-Domain AI Issues
- **Hallucinated APIs** — calls to methods/classes that don't exist or have wrong signatures. Verify against actual library docs.
- **Hallucinated packages** — non-existent dependencies. Verify every dependency references a real artifact in `gradle/libs.versions.toml`.
- **Contract mismatches introduced by AI** — ViewModel assumes data the repository doesn't return, or vice versa
- **Copy-paste patterns** — similar logic duplicated across repositories/ViewModels instead of shared through a use case
- **Missing validation at boundaries** — AI often generates the "happy path" and skips validation of external data (Health Connect records, API responses)
- **Inconsistent error handling** — some error paths return proper states while others silently fail or crash
- **Over-abstraction** — unnecessary wrappers, helpers, or config for one-time operations
- **Java-style patterns** — verbose code that should use idiomatic Kotlin (extension functions, scope functions, null-safe operators)
