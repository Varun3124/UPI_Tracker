# TransactionEntryActivity MVVM Refactor Action Plan

## Objective
Refactor TransactionEntryActivity into a thin MVVM view while preserving all existing behavior for:
- transaction create/edit
- actor handling (Me/Friend/Merchant)
- split shares (payer/payee)
- category splits
- SMS-locked transaction behavior
- live balance/validation
- persistence and ledger posting side effects

## Non-Goals
- No UI redesign in this phase.
- No functional simplification.
- No migration to Compose in this phase.

## Constraints
- Preserve current behavior exactly.
- Keep Android View/UI APIs out of domain/business classes.
- Use immutable UI state and unidirectional action flow.
- Keep each step independently compilable.

## Baseline and Safety Steps
1. Freeze behavior baseline.
2. List critical user flows and expected outputs.
3. Capture current known edge cases from code.
4. Add/expand tests around current behavior before moving logic.
5. Refactor in small slices and verify after each slice.

## Baseline Behavior Checklist (Must Stay Identical)
- SMS imported transaction amount remains read-only unless amount is zero.
- SMS actor lock behavior for Me endpoints remains unchanged.
- Payer and payee cannot both resolve to Me.
- Merchant toggles keep current transition behavior.
- Share rows support add/remove with duplicate prevention.
- Share totals must match full amount per non-merchant side.
- Category section visibility depends on merchant involvement and my-share > 0.
- Merchant category auto-load behavior remains intact.
- Existing transaction edit path restores shares/categories exactly.
- Save path updates transaction, shares, category splits, and ledger as before.
- Notification cancellation happens after successful save.

## Target Architecture

### Feature Package Layout
- ui/transactionentry/
  - TransactionEntryActivity (view only)
  - TransactionEntryContract.kt (Action, State, Effect)
  - TransactionEntryViewModel.kt
  - TransactionEntryUiMapper.kt
- domain/transactionentry/
  - actor/ActorSelectionService.kt
  - share/ShareManager.kt
  - share/ShareCalculator.kt
  - category/CategorySplitManager.kt
  - validation/TransactionValidator.kt
  - persistence/TransactionPersistenceService.kt
  - persistence/LedgerPostingService.kt
- data/transactionentry/
  - TransactionEntryRepository.kt (interface)
  - RoomTransactionEntryRepository.kt

### Dependency Direction
- Activity -> ViewModel
- ViewModel -> domain services + repository interface
- Repository implementation -> Room/AppDatabase
- Domain services do not depend on Android View classes

## Core Contracts

### Actions (Intent-like)
- ScreenLoaded(transactionId)
- AmountChanged(rawAmount)
- AccountSelected(accountId)
- ToggleMerchant(side, isMerchant)
- AliasChanged(side, text)
- AliasSelected(side, selection)
- AddShare(side)
- RemoveShare(side, rowIndex)
- ShareNameChanged(side, rowIndex, text)
- ShareNameCommitted(side, rowIndex)
- ShareAmountChanged(side, rowIndex, rawAmount)
- CategoryToggled(categoryId, selected)
- CategoryAmountChanged(categoryId, rawAmount)
- SaveClicked
- CloseClicked

### Effects
- ShowMessage(message)
- HideKeyboard
- NavigateBack

### State
Single immutable TransactionEntryUiState includes:
- loading/saving flags
- reference data (friends/merchants/categories/accounts)
- source context (isSmsSource, locks, fallback aliases)
- actor state (types + selected IDs + labels)
- amount/account state
- share state (payerRows, payeeRows)
- category state
- derived balances/validation hints

## Implementation Phases

## Phase 0: Foundation
1. Create feature package and contract files (Action/State/Effect).
2. Add state models for share rows and category rows.
3. Keep old Activity functional while scaffolding is introduced.
4. Verify compile.

Exit criteria:
- No behavior change.
- Project compiles.

## Phase 1: Extract Pure Calculations
1. Move live calculation logic into ShareCalculator.
2. Move helper calculations:
   - suggested share amount
   - section balance text
   - overall allocation message
   - my-share derivation for categories
3. Activity temporarily calls calculator (until ViewModel fully owns state).
4. Add unit tests for calculator using real edge cases.

Exit criteria:
- Balance/remaining/over-allocation text unchanged in all scenarios.

## Phase 2: Extract Validation
1. Move actor and share validation to TransactionValidator.
2. Replace in-Activity toast branching with validator result objects.
3. Map validation result to effect ShowMessage in ViewModel.
4. Add validator unit tests for:
   - missing actor labels
   - Me vs Me invalid case
   - unresolved share participant rows
   - share sum mismatch

Exit criteria:
- Save blocking behavior unchanged.
- Same validation outcomes/messages.

## Phase 3: Extract Actor + Share Mutation Rules
1. Move merchant toggle state transitions to ActorSelectionService.
2. Move share row mutations (add/remove/update/commit) to ShareManager.
3. Preserve duplicate prevention and Me uniqueness rules.
4. Keep Activity rendering dynamic rows, but use ViewModel-owned row data.

Exit criteria:
- UI interactions produce identical row behavior.

## Phase 4: Extract Category Split Rules
1. Move category visibility and auto-load decisions to CategorySplitManager.
2. Keep DB fetch in repository/use case; manager decides state changes.
3. Preserve shouldAutoloadMerchantCategories behavior semantics.

Exit criteria:
- Category panel visibility and defaults match current behavior.

## Phase 5: Extract Persistence and Ledger Posting
1. Create TransactionPersistenceService to orchestrate save transaction flow.
2. Move actor resolution, unresolved share row resolution, and entity mapping.
3. Move category split persistence and alias linking.
4. Move ledger logic to LedgerPostingService.
5. Keep transaction boundary and ordering behavior identical.

Exit criteria:
- DB writes and ledger outputs match baseline.
- Create and edit both work.

## Phase 6: Move Coroutine Ownership to ViewModel
1. Shift all non-trivial coroutine launches from Activity to ViewModel.
2. Activity only observes state/effects and dispatches actions.
3. Keep lifecycle-aware observation in Activity.

Exit criteria:
- No business coroutine orchestration in Activity.

## Phase 7: Full Unidirectional Flow
1. Replace direct UI-side mutations with action dispatches only.
2. Ensure every listener maps to a TransactionEntryAction.
3. Recompute derived state in ViewModel after each action.

Exit criteria:
- Activity is thin and deterministic.

## Phase 8: Optional UI Structure Improvement
1. Replace manual dynamic share/category view inflation with RecyclerView adapters.
2. Keep behavior and state contracts unchanged.
3. Treat as separate PR if risk is high.

Exit criteria:
- Cleaner rendering path with no business logic regression.

## Testing Strategy

### Unit Tests
- ShareCalculator
- TransactionValidator
- ActorSelectionService
- ShareManager
- CategorySplitManager
- LedgerPostingService (rule matrix)

### Integration Tests (Room-backed)
- save new manual transaction
- edit existing transaction with shares/splits
- sms-origin transaction with lock constraints
- merchant-category linking persistence
- notification cancellation post-save

### Regression Scenario Matrix
- payer=Me, payee=Friend (no splits)
- payer=Friend, payee=Me (repayment path)
- payer=Me with secondary payer friends
- payee=Me with secondary payee friends
- merchant involved with category split enabled
- both sides non-merchant with complete share allocation

## Risk Register and Mitigations

Risk: Behavioral drift in ledger settlement logic
- Mitigation: Snapshot expected deltas per scenario before extraction; unit-test each case.

Risk: Inconsistent share row state during dynamic rendering
- Mitigation: Make ViewModel state canonical; Activity renders only from state.

Risk: SMS lock/fallback alias regressions
- Mitigation: Add dedicated tests and explicit state fields for lock + fallback labels.

Risk: Validation message mismatch
- Mitigation: Centralize messages in validator constants and compare with baseline.

## Refactor Checklist by Deliverable

### Deliverable 1: Responsibility Analysis
- Completed before code changes and kept in architecture notes.

### Deliverable 2: Proposed Architecture
- Feature package structure defined.

### Deliverable 3: Class Design
- Service responsibilities explicitly scoped.

### Deliverable 4: Activity Responsibilities
- Render + observe + dispatch only.

### Deliverable 5: Incremental Refactoring
- Execute phases 0 to 8 with compile verification each phase.

### Deliverable 6: Code Extraction Process
For each extraction:
1. Capture source slice.
2. Explain move rationale.
3. Add new class implementation.
4. Rewire caller and verify behavior.

### Deliverable 7: Preserve Functionality
- Run baseline checklist + regression matrix each phase.

### Deliverable 8: Code Smells
- Document and track removed smells after each phase.

### Deliverable 9: Dependency Diagram
- Update final architecture diagram at completion.

## Suggested Execution Order (Practical)
1. Contracts + state scaffold
2. Calculator extraction
3. Validator extraction
4. Actor/share managers
5. Category manager
6. Persistence + ledger services
7. ViewModel action reducer wiring
8. Activity thinning
9. Optional RecyclerView migration

## Completion Criteria
- Activity has no business rules or persistence logic.
- ViewModel is source of truth for all form state.
- Domain services are test-covered and Android-free.
- All baseline behaviors pass regression checks.
- Code remains readable, modular, and SOLID-aligned.
