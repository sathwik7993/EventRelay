# EventRelay — Contributing Guide

This document provides guide details for developers who want to contribute to the EventRelay platform code.

---

## 1. Code Contribution Lifecycle

We follow a structured pull request (PR) process to maintain code quality:

```
[ Fork / Branch ] ──► [ Local Code & Test ] ──► [ Submit Pull Request ]
                                                        │
                                                        ▼
                                             [ CI Build & Test Pipeline ]
                                                        │
                                                        ▼
                                             [ Peer Review (2 Approvals) ]
                                                        │
                                                        ▼
                                             [ Squash & Merge to develop ]
```

---

## 2. Commit & Styling Guidelines Adherence

- **Google Java Style Guide**: All Java changes must format clean. Run `mvn spotless:apply` locally before committing to check formatting rules.
- **Conventional Commits**: Ensure commit messages start with type prefixes (e.g., `feat:`, `fix:`, `docs:`, `test:`).
- **Test Coverage**: New features must include unit and integration tests targetting at least $80\%$ line coverage.
