# Compose UI Review Checklist

Comprehensive checklist for Compose UI review covering accessibility, visual design, UX, adaptive layout, and performance. Based on Android accessibility guidelines, Material 3, and Jetpack Compose best practices.

## Accessibility & Semantics

### Compose Semantics
- [ ] Interactive elements use proper Compose components (`Button`, `IconButton`, `TextButton`) not raw `Box`/`Row` with `Modifier.clickable`
- [ ] `Modifier.clickable` includes `role` parameter when used (e.g., `Role.Button`, `Role.Tab`)
- [ ] `Modifier.semantics` used to provide additional accessibility info where needed
- [ ] Heading semantics applied (`Modifier.semantics { heading() }`) for screen headings
- [ ] State descriptions provided for stateful elements (`Modifier.semantics { stateDescription = "..." }`)
- [ ] `mergeDescendants = true` used to group related elements for TalkBack
- [ ] No redundant semantics (e.g., `contentDescription` on a `Text` composable)

### Content Descriptions
- [ ] Images have meaningful `contentDescription` (not "image" or filename)
- [ ] Decorative images have `contentDescription = null`
- [ ] Icon-only buttons have `contentDescription` on the `IconButton` or `Icon`
- [ ] `contentDescription` describes purpose, not appearance ("Delete record" not "trash can icon")
- [ ] Dynamic content descriptions update with state changes

### TalkBack & Screen Readers
- [ ] All interactive elements reachable via TalkBack swipe navigation
- [ ] Reading order matches visual layout (use `Modifier.semantics { traversalIndex }` if needed)
- [ ] State changes announced (snackbar, error messages, loading completion)
- [ ] Custom actions provided for complex gestures (`Modifier.semantics { customActions }`)
- [ ] Lists announce position ("item 3 of 10") via `LazyColumn` semantics
- [ ] Dialog content announced when opened

### Focus & Keyboard Navigation
- [ ] Focus indicators visible on all interactive elements
- [ ] `Modifier.focusable()` used where needed for non-default focusable elements
- [ ] Focus moves to dialog content when opened, returns to trigger when closed
- [ ] No focus traps (user can always navigate away)
- [ ] D-pad/keyboard navigation works logically (Tab, Arrow keys, Enter/Space)

### Color & Contrast
- [ ] Text contrast >= 4.5:1 for normal text, >= 3:1 for large text (WCAG AA)
- [ ] Checked in both light and dark theme (Material 3 dynamic colors)
- [ ] UI components and icons contrast >= 3:1 against adjacent colors
- [ ] Information not conveyed by color alone (icons, text, shapes as supplements)
- [ ] Disabled states still distinguishable

### Touch Targets
- [ ] All interactive elements >= 48dp touch targets (`Modifier.minimumInteractiveComponentSize()`)
- [ ] Adequate spacing between touch targets (no accidental taps)
- [ ] Primary actions within thumb reach (lower portion of screen)
- [ ] Touch targets not clipped by parent containers

### Forms & Input
- [ ] `OutlinedTextField`/`TextField` has `label` parameter (not just `placeholder`)
- [ ] Error messages displayed with `isError = true` and `supportingText`
- [ ] Required fields indicated visually and via semantics
- [ ] Input validation errors announced to TalkBack

### Motion & Preferences
- [ ] Animations respect `LocalReduceMotion` or system animation scale settings
- [ ] No auto-playing animations without user control
- [ ] Transition durations appropriate (not too fast for comprehension, not too slow)

---

## Visual Design & Material 3 Compliance

### Theme Compliance
- [ ] Colors use `MaterialTheme.colorScheme` (no hardcoded color values)
- [ ] Typography uses `MaterialTheme.typography` (no hardcoded text styles)
- [ ] Shapes use `MaterialTheme.shapes` (no hardcoded `RoundedCornerShape` values)
- [ ] Elevation/shadows use Material 3 tonal elevation (not arbitrary shadow values)
- [ ] Dynamic colors supported on Android 12+ (Material 3 `dynamicColorScheme`)

### Visual Consistency
- [ ] Consistent spacing throughout (use a spacing scale, not random dp values)
- [ ] Consistent elevation hierarchy (cards, surfaces, dialogs)
- [ ] Button variants used correctly (Filled for primary, Outlined for secondary, Text for tertiary)
- [ ] Icon style consistent (all outlined or all filled, consistent size)
- [ ] Similar UI patterns solved the same way everywhere

### Visual Hierarchy & Composition
- [ ] Primary action/content on each screen is immediately obvious
- [ ] Heading sizes create clear visual levels
- [ ] Whitespace used intentionally to group related elements and separate unrelated ones
- [ ] Visual weight distribution: screens feel balanced
- [ ] Element proportions feel deliberate
- [ ] Alignment consistent (elements snap to a grid or align with neighbors)

### Typography
- [ ] Font sizes follow Material 3 type scale (displayLarge → labelSmall)
- [ ] Body text readable at all screen sizes
- [ ] Line height appropriate for readability
- [ ] Long text content handles overflow (ellipsis, wrapping)
- [ ] Heading hierarchy visually matches semantic hierarchy
- [ ] Text line lengths comfortable for reading

### Spacing & Layout
- [ ] Consistent padding within similar component types
- [ ] Adequate whitespace between sections
- [ ] Content has appropriate max-width on large screens/tablets
- [ ] Layouts handle varying content lengths (short and long text)
- [ ] No content overflow causing horizontal scrolling
- [ ] Spacing within groups tighter than spacing between groups (proximity principle)

### Color & Theme
- [ ] All colors from `MaterialTheme.colorScheme` (no `Color(0xFFXXXXXX)` literals)
- [ ] Dark theme: all components render correctly
- [ ] Dark theme: elevation uses tonal overlay (Material 3 standard)
- [ ] Dark theme: images and icons visible with sufficient contrast
- [ ] System theme preference (`isSystemInDarkTheme()`) respected as default

---

## UX Patterns & User Flows

### Core User Flow
- [ ] Primary user flow has clear step-by-step progression
- [ ] User knows current state/step
- [ ] Back navigation available and works correctly
- [ ] Confirmation before committing irreversible actions
- [ ] Success feedback after completing actions

### Cognitive Load
- [ ] Each screen has one clear primary action
- [ ] Progressive disclosure: advanced options hidden until needed
- [ ] Manageable number of choices per screen
- [ ] Related information grouped logically
- [ ] Next step always obvious

### Microcopy & Content
- [ ] Button labels describe action outcome (specific verb, not "Submit")
- [ ] Error messages explain what went wrong AND what to do next
- [ ] Empty states guide the user with specific next action
- [ ] Loading messages set expectations where possible
- [ ] Confirmations are specific (describe what was done)
- [ ] Terminology consistent throughout

### Feedback & States
- [ ] Loading states for all async operations (CircularProgressIndicator, skeleton, shimmer)
- [ ] Loading indicators appear immediately (no empty gap)
- [ ] Success confirmations for significant actions (Snackbar, state change)
- [ ] Error messages are user-friendly (not technical)
- [ ] Disabled elements visually distinct
- [ ] Every user action has visible feedback

### Error Handling UI
- [ ] Network errors handled gracefully (offline, timeout)
- [ ] API/data errors show user-friendly messages
- [ ] Retry option available for transient failures
- [ ] Input validation shows inline errors
- [ ] Error states are recoverable without navigating away

### Empty States
- [ ] Empty data lists have clear call to action
- [ ] First-use states have helpful prompts
- [ ] Permission-required states explain why and offer action
- [ ] Missing Health Connect data has appropriate messaging

### Adaptive Layout
- [ ] Layout adapts across phone sizes (small to large screens)
- [ ] Content accessible on narrow screens without horizontal scroll
- [ ] Navigation adapts for screen size (bottom nav, rail, drawer)
- [ ] Images/graphics scale appropriately
- [ ] Works in both portrait and landscape
- [ ] WindowSizeClass used for adaptive decisions (if supporting tablets)

### Touch & Gesture
- [ ] All interactive elements >= 48dp touch targets
- [ ] Adequate spacing between touch targets (>= 8dp)
- [ ] Primary actions within thumb reach
- [ ] Swipe/gesture interactions have button alternatives
- [ ] No gesture-only interactions (must work with TalkBack)
- [ ] Safe area insets handled (system bars, display cutouts via `WindowInsets`)

---

## Performance & Optimization

### Recomposition
- [ ] No unnecessary recompositions — check parameter stability:
  - Data classes are stable (all vals, no mutable collections)
  - Lambda parameters are stable (use `remember` for lambdas referencing unstable state)
  - Collections wrapped in stable holders or use `ImmutableList` from kotlinx-collections
- [ ] `remember` used for expensive computations
- [ ] `derivedStateOf` used for derived values that change less often than inputs
- [ ] `key` parameter provided on `LazyColumn`/`LazyRow` items
- [ ] Composable functions don't perform side effects during composition

### State Management
- [ ] StateFlow collected with `collectAsStateWithLifecycle()` (lifecycle-aware)
- [ ] No state hoisting violations (state owned at appropriate level)
- [ ] `rememberSaveable` used for state that should survive process death
- [ ] State changes don't trigger unnecessary recomposition cascades

### Lists & Scrolling
- [ ] Large lists use `LazyColumn`/`LazyRow` (not `Column`/`Row` with `forEach`)
- [ ] `LazyColumn` items have stable `key` parameter
- [ ] `contentType` specified for heterogeneous lists (helps recycling)
- [ ] No nested scrollable containers in same direction

### Image & Resource Loading
- [ ] Images loaded asynchronously (Coil `AsyncImage` or similar)
- [ ] Placeholder shown during image loading
- [ ] Image sizes appropriate for display dimensions (not loading 4000px for 200dp container)
- [ ] No bitmap loading on Main thread

### Coroutine Efficiency (UI-related)
- [ ] No redundant data fetches on recomposition
- [ ] `LaunchedEffect` keys are correct (don't re-trigger unnecessarily)
- [ ] `snapshotFlow` used to observe Compose state in coroutines
- [ ] Heavy computation not done during composition (use `remember` + `LaunchedEffect`)

### Memory
- [ ] No context/Activity leaks through captured references
- [ ] Large data sets paginated
- [ ] No unbounded in-memory caches in ViewModels
- [ ] Bitmaps/images recycled when composable leaves composition

---

## Search Patterns Quick Reference

Use Grep on UI files to find common issues:

| Pattern | What It Finds |
|---------|---------------|
| `Box.*Modifier\.clickable\|Row.*Modifier\.clickable` | Clickable containers that should be `Button`/`IconButton` |
| `Color\(0x\|Color\(0X` | Hardcoded color values (should use `MaterialTheme.colorScheme`) |
| `contentDescription\s*=\s*""` | Empty content descriptions (should be `null` for decorative) |
| `\.dp\)` followed by specific values | Audit spacing for consistency |
| `fontSize\s*=\s*\d+\.sp` | Hardcoded font sizes (should use `MaterialTheme.typography`) |
| `RoundedCornerShape\(\d+` | Hardcoded shapes (should use `MaterialTheme.shapes`) |
| `collectAsState\(\)` | Should be `collectAsStateWithLifecycle()` for lifecycle awareness |
| `LazyColumn\|LazyRow` without `key` | Missing key parameter on lazy list items |
| `Column.*forEach\|Row.*forEach` | Non-lazy list that should be `LazyColumn`/`LazyRow` |
| `mutableStateOf\|mutableStateListOf` | Mutable state to audit for stability |
| `GlobalScope` | Unstructured coroutine scope |
| `println\|System\.out` | Debug logging left in production code |
| `Modifier\s*$` | Empty modifier (check if accessibility modifiers are needed) |
| `remember\s*\{` | Remembered values to audit for correct keys |
| `LaunchedEffect\(Unit\)` | Effect that only runs once — verify this is intentional |
