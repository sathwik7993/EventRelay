# EventRelay — Release Strategy

This document outlines the release versioning, branching model, conventional commit standards, and backward-compatibility guidelines for EventRelay.

---

## 1. Versioning Standards (Semantic Versioning)

EventRelay adheres strictly to Semantic Versioning (SemVer) 2.0.0:

$$\text{Version Format} = \text{MAJOR} . \text{MINOR} . \text{PATCH}$$

- **MAJOR**: Incompatibilities on public-facing REST APIs or databases requiring schema refactoring.
- **MINOR**: Backward-compatible functionality (e.g., new endpoints, caching, rate limiting options).
- **PATCH**: Backward-compatible bug fixes and security patching.

---

## 2. Commit Standards (Conventional Commits)

To automate changelog updates and release notes, developers must format commit messages:

```
type(scope): description

[Optional body explaining rationale]
[Optional footer detailing issue number]
```

- **Types**:
  - `feat`: A new feature (e.g., `feat(auth): add API Key scopes`).
  - `fix`: A bug fix (e.g., `fix(retry): repair decorrelated jitter bounds`).
  - `docs`: Documentation updates.
  - `test`: Adding or adjusting tests.
  - `refactor`: Code restructuring with no behavior changes.

---

## 3. Branching Lifecycle and Releases

```
develop ────────┬───────────────┬───────► [ Active Dev Workspace ]
                │               │
                ▼               ▼
feature/auth ───┘  release/v1.2 └───► main (Tagged: v1.2.0)
```

1. **Development**: Feature branches (`feature/`) merge into the `develop` branch.
2. **Release Preparation**: When preparing a release, branch `release/vX.Y` from `develop`. Perform QA, code freeze, and update documentation.
3. **Deployment**: Release branches merge to `main` and are tagged with the release number (e.g., `v1.2.0`). This trigger builds and publishes artifacts.
4. **Hotfixes**: Critical bugs in production are branched directly from `main` (`hotfix/`), patched, and merged to both `main` and `develop`.
