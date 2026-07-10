# EventRelay — CI Test Pipeline Configuration

This document outlines the GitHub Actions workflow configuration used to run the comprehensive testing suite on every pull request.

---

## 1. Test Pipeline Flow

The CI test pipeline executes 5 validation steps sequentially:

```
[ Git Push / PR ] ──► [ 1. Linter / Format ] ──► [ 2. Unit Tests ] ──► [ 3. Integration Tests ]
                                                                                │
                                                                                ▼
                                                                     [ 4. Contract Tests ]
                                                                                │
                                                                                ▼
                                                                     [ 5. Build Container ]
```

---

## 2. GitHub Actions Workflow (YAML)

Below is the YAML configuration for the test pipeline (`.github/workflows/ci-test.yml`):

```yaml
name: CI Test Pipeline

on:
  push:
    branches: [ main, develop ]
  pull_request:
    branches: [ main, develop ]

jobs:
  test-suite:
    runs-on: ubuntu-latest
    
    services:
      # Start local docker services for integration tests if not using Testcontainers
      redis:
        image: redis:7-alpine
        ports:
          - 6379:6379

    steps:
      - name: Checkout Code
        uses: actions/checkout@v3

      - name: Set up JDK 17
        uses: actions/setup-java@v3
        with:
          java-version: '17'
          distribution: 'temurin'
          cache: 'maven'

      - name: Code Lint and Formatting Check
        run: mvn spotless:check

      - name: Run Unit Tests
        run: mvn test

      - name: Run Integration & Contract Tests
        # Employs Docker-in-Docker to support Testcontainers execution
        run: mvn verify -Pintegration-tests

      - name: Publish Test Reports
        uses: actions/upload-artifact@v3
        if: always()
        with:
          name: junit-test-results
          path: '**/target/surefire-reports/*.xml'
```
---

## 3. Test Failure and Flakiness Mitigation

- **Flaky Test Retries**: The Maven Surefire plugin is configured to automatically rerun failed tests up to 2 times before marking the build as failed.
- **Pipeline Alerts**: If a build fails on the `main` or `develop` branches, Alertmanager sends a notification payload directly to the development team's Slack webhook.
- **Merge Requirements**: GitHub repository rules enforce that the `test-suite` job must pass successfully before a pull request can be squash-merged.
