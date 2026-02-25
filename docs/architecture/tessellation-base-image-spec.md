# Tessellation Base Image Specification

Pre-build tessellation JARs into a Docker image to reduce E2E CI build time from ~16 minutes to ~10 minutes.

## Problem Statement

The E2E CI workflow (`e2e.yml`) builds tessellation from source on every PR, even though tessellation code rarely changes. This adds significant overhead:

| Run | Started | Completed | Duration |
|-----|---------|-----------|----------|
| 1 | 2026-02-24T00:39:48Z | 2026-02-24T00:55:43Z | 15m 55s |
| 2 | 2026-02-22T22:37:28Z | 2026-02-22T22:54:06Z | 16m 38s |
| 3 | 2026-02-22T22:05:42Z | 2026-02-22T22:22:37Z | 16m 55s |
| 4 | 2026-02-22T21:57:02Z | 2026-02-22T22:13:24Z | 16m 22s |
| 5 | 2026-02-22T21:44:41Z | 2026-02-22T22:01:30Z | 16m 49s |

**Average:** ~16 minutes per run

The tessellation build step accounts for ~6-8 minutes of this time. Since tessellation is pinned at a specific version (`4.0.0-rc.2`), these JARs can be pre-built and cached.

## Scope Clarification

### What CAN Be Pre-Built

**Tessellation JARs only:**
- `global-l0.jar`
- `global-l1.jar`
- `keytool.jar`
- `wallet.jar`

These are built from the pinned `tessellation` repository and do not change between OttoChain PRs.

### What CANNOT Be Pre-Built

**Metagraph JARs (ml0/cl1/dl1):**
- `metagraph-l0.jar`
- `currency-l0.jar`
- `currency-l1.jar`
- `data-l1.jar`

These are built from OttoChain source code via `sbt assembly` and **must** be rebuilt on every PR to pick up code changes. The `just up --metagraph --dl1 --data` command triggers `assemble_all_metagraph` which assembles all 4 metagraph JARs.

### Build Flag Reference

| Flag | Scope | Effect |
|------|-------|--------|
| `SKIP_ASSEMBLY=true` | Tessellation only | Skips building gl0/gl1/keytool/wallet JARs |
| `SKIP_METAGRAPH_ASSEMBLY=true` | OttoChain metagraph | Skips building ml0/cl1/dl1 JARs |
| `PUBLISH=false` | tessellation-sdk | Skips `sdk/publishLocal` (not needed — OttoChain uses metakit) |

**For this optimization:** Set `SKIP_ASSEMBLY=true` + `PUBLISH=false` in `e2e.yml`. Do NOT set `SKIP_METAGRAPH_ASSEMBLY=true`.

## Architecture

### New Workflow: `build-tessellation-base.yml`

A new GitHub Actions workflow in `scasplte2/ottochain` that:

1. Clones tessellation at the pinned version
2. Applies patches
3. Builds tessellation JARs (gl0/gl1/keytool/wallet)
4. Publishes a JAR-only Docker image to GHCR

```yaml
name: Build Tessellation Base

on:
  workflow_dispatch:
    inputs:
      tessellation_version:
        description: 'Tessellation version (without v prefix)'
        required: true
        default: '4.0.0-rc.2'
  push:
    paths:
      - 'e2e-test/patches/**'
      - '.github/workflows/build-tessellation-base.yml'

env:
  REGISTRY: ghcr.io
  IMAGE_NAME: ottobot-ai/tessellation-base

jobs:
  build:
    runs-on: ubuntu-latest
    permissions:
      contents: read
      packages: write
    
    steps:
      - uses: actions/checkout@v4
      
      - name: Set up Java
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '21'
          cache: 'sbt'
      
      - name: Clone tessellation
        run: |
          VERSION="${{ inputs.tessellation_version || '4.0.0-rc.2' }}"
          git clone --depth 1 --branch "v${VERSION}" \
            https://github.com/Constellation-Labs/tessellation.git tessellation
      
      - name: Apply patches
        working-directory: tessellation
        run: |
          VERSION="${{ inputs.tessellation_version || '4.0.0-rc.2' }}"
          for patch in ../e2e-test/patches/v${VERSION}*.patch; do
            [ -f "$patch" ] && git apply "$patch"
          done
      
      - name: Build tessellation JARs
        working-directory: tessellation
        run: |
          sbt assembly
      
      - name: Log in to GHCR
        uses: docker/login-action@v3
        with:
          registry: ${{ env.REGISTRY }}
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}
      
      - name: Set up Docker Buildx
        uses: docker/setup-buildx-action@v3
      
      - name: Build and push
        uses: docker/build-push-action@v5
        with:
          context: tessellation
          file: .github/dockerfiles/tessellation-base.Dockerfile
          push: true
          tags: |
            ${{ env.REGISTRY }}/${{ env.IMAGE_NAME }}:v${{ inputs.tessellation_version || '4.0.0-rc.2' }}
            ${{ env.REGISTRY }}/${{ env.IMAGE_NAME }}:latest
          cache-from: type=gha
          cache-to: type=gha,mode=max
```

### Dockerfile: `tessellation-base.Dockerfile`

A minimal image containing only the pre-built JARs:

```dockerfile
# .github/dockerfiles/tessellation-base.Dockerfile
FROM alpine:3.19 AS base

# Create directory structure matching tessellation's assembly output
RUN mkdir -p /jars/global-l0 /jars/global-l1 /jars/keytool /jars/wallet

# Copy JARs from sbt assembly output
COPY modules/global-l0/target/scala-2.13/global-l0-assembly-*.jar /jars/global-l0/
COPY modules/global-l1/target/scala-2.13/global-l1-assembly-*.jar /jars/global-l1/
COPY modules/keytool/target/scala-2.13/keytool-assembly-*.jar /jars/keytool/
COPY modules/wallet/target/scala-2.13/wallet-assembly-*.jar /jars/wallet/

# Final scratch image — just the JARs, nothing else
FROM scratch
COPY --from=base /jars /jars
```

### Image Naming Convention

```
ghcr.io/ottobot-ai/tessellation-base:v{VERSION}
ghcr.io/ottobot-ai/tessellation-base:latest
```

Example: `ghcr.io/ottobot-ai/tessellation-base:v4.0.0-rc.2`

### JAR Extraction Pattern

In `e2e.yml`, extract JARs from the pre-built image using `docker create` + `docker cp`:

```yaml
- name: Extract pre-built tessellation JARs
  run: |
    # Pull the pre-built base image
    docker pull ghcr.io/ottobot-ai/tessellation-base:v${TESSELLATION_VERSION}
    
    # Create a temporary container (doesn't run — just creates filesystem)
    CONTAINER_ID=$(docker create ghcr.io/ottobot-ai/tessellation-base:v${TESSELLATION_VERSION})
    
    # Copy JARs to tessellation directory
    docker cp $CONTAINER_ID:/jars/global-l0/. tessellation/modules/global-l0/target/scala-2.13/
    docker cp $CONTAINER_ID:/jars/global-l1/. tessellation/modules/global-l1/target/scala-2.13/
    docker cp $CONTAINER_ID:/jars/keytool/. tessellation/modules/keytool/target/scala-2.13/
    docker cp $CONTAINER_ID:/jars/wallet/. tessellation/modules/wallet/target/scala-2.13/
    
    # Clean up temporary container
    docker rm $CONTAINER_ID
```

### Updated `e2e.yml` Flow

```yaml
env:
  TESSELLATION_VERSION: "4.0.0-rc.2"

steps:
  # ... checkout, setup java, etc ...

  - name: Clone tessellation
    run: |
      git clone --depth 1 --branch "v${TESSELLATION_VERSION}" \
        https://github.com/Constellation-Labs/tessellation.git tessellation

  - name: Apply tessellation patches
    working-directory: tessellation
    run: |
      git apply ../e2e-test/patches/v4.0.0-rc.2-compile-fix.patch
      git apply ../e2e-test/patches/v4.0.0-rc.2-java21-deps.patch

  - name: Extract pre-built tessellation JARs
    run: |
      # Pull and extract JARs (see pattern above)
      ...

  - name: Start cluster
    working-directory: tessellation
    env:
      SKIP_ASSEMBLY: "true"      # Skip tessellation JAR build
      PUBLISH: "false"           # Skip sdk/publishLocal
    run: |
      just up --metagraph="${GITHUB_WORKSPACE}" --dl1 --data
```

## Design Decisions

### Q1: Should the JAR image be public or require auth?

**Decision: Public (no auth required)**

Rationale:
- Tessellation source is already public (Apache 2.0 license)
- Pre-built JARs contain no secrets or proprietary code
- Simplifies `e2e.yml` — no GHCR auth step needed for pulls
- GHCR public images have no rate limits for authenticated users (GitHub Actions are authenticated)

### Q2: Who triggers the first `build-tessellation-base.yml` run?

**Decision: Manual dispatch initially, then automated on patch changes**

Triggers:
1. **Manual dispatch** — First run and version bumps (input: `tessellation_version`)
2. **Push to patches** — Automatically rebuilds when patches change

Workflow:
1. When bumping `TESSELLATION_VERSION` in `e2e.yml`:
   a. Manually trigger `build-tessellation-base.yml` with new version
   b. Wait for image to publish
   c. Update `e2e.yml` to use new version

### Q3: Phase 2 sbt incremental compile cache — same card or separate?

**Decision: Separate card**

Rationale:
- Phase 1 (this spec) targets ~8-10 minutes — achievable without incremental cache
- sbt incremental compile caching is complex (cache invalidation, CI cache size limits)
- Better to ship Phase 1, measure results, then assess if Phase 2 is needed
- If Phase 2 is pursued, it would benefit metagraph builds (which change every PR)

## GHA Cache Configuration

Both workflows benefit from `cache-from: type=gha`:

| Workflow | Cache Benefit |
|----------|---------------|
| `build-tessellation-base.yml` | Base layers cached; saves ~1-2 min on rebuilds (rare) |
| `e2e.yml` final docker build | Tessellation JAR layers now cache-stable (previously invalidated every run) |

## Acceptance Criteria

- [ ] **AC1:** New `build-tessellation-base.yml` workflow publishes `ghcr.io/ottobot-ai/tessellation-base:v4.0.0-rc.2`
- [ ] **AC2:** New `.github/dockerfiles/tessellation-base.Dockerfile` creates minimal JAR-only image
- [ ] **AC3:** E2E CI completes in ≤10 minutes (measured over 5 consecutive runs)
- [ ] **AC4:** `e2e.yml` uses `SKIP_ASSEMBLY=true` + `PUBLISH=false` environment variables
- [ ] **AC5:** `e2e.yml` extracts JARs via `docker create` + `docker cp` pattern
- [ ] **AC6:** Tessellation build step is skipped (verified in CI logs)
- [ ] **AC7:** Metagraph JARs (ml0/cl1/dl1) are still built from OttoChain source
- [ ] **AC8:** Pre-built image is publicly accessible (no auth required for pulls)
- [ ] **AC9:** `build-tessellation-base.yml` triggers on patch file changes

## Phase 2: sbt Incremental Compile Cache (Future)

**Out of scope for this card.** If further optimization is needed after Phase 1:

- Cache `~/.sbt` and `~/.ivy2` directories
- Cache `target/` directories with proper invalidation keys
- Expected additional savings: ~2-3 minutes on metagraph assembly
- Recommend creating a separate card for Phase 2

## Testing Strategy

1. **Workflow validation:**
   - Manual trigger of `build-tessellation-base.yml`
   - Verify image published to GHCR
   - Verify image contains expected JARs

2. **E2E integration:**
   - Run E2E workflow on a test branch
   - Verify tessellation build is skipped in logs
   - Verify metagraph JARs are built
   - Verify all E2E tests pass

3. **Performance measurement:**
   - Record 5 consecutive E2E run times
   - Calculate average (target: ≤10 min)
   - Compare to baseline (~16 min)

## Rollback Plan

If issues arise:
1. Remove `SKIP_ASSEMBLY` from `e2e.yml`
2. Remove JAR extraction step
3. E2E workflow reverts to building tessellation from source

No data loss or service impact — CI simply takes longer.

## Implementation Checklist

- [ ] Create `.github/dockerfiles/tessellation-base.Dockerfile`
- [ ] Create `.github/workflows/build-tessellation-base.yml`
- [ ] Trigger initial build, verify image at GHCR
- [ ] Update `e2e.yml` with JAR extraction and `SKIP_ASSEMBLY=true`
- [ ] Run E2E, verify tests pass
- [ ] Measure 5 runs, verify ≤10 min average
- [ ] Update this spec with actual measured times

## See Also

- [e2e.yml](.github/workflows/e2e.yml) — Current E2E workflow
- [tessellation justfile](https://github.com/Constellation-Labs/tessellation/blob/main/justfile) — `just up` command and assembly scripts
- [@research feasibility analysis](https://trello.com/c/69967833) — Full technical investigation
