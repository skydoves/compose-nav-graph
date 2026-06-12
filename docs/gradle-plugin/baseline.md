# Nav Baseline

The nav baseline brings navigation changes into code review. It's modeled on Kotlin's binary compatibility validator (`apiDump` / `apiCheck`): you commit a human readable `.nav` snapshot of your navigation graph, and CI fails the build if a change drifts from it. Without it, a refactor that quietly adds a screen, drops an edge, or renames a route slips through review unnoticed, because nothing in a normal diff shows the *shape* of your navigation changing.

## How It Works

Two Gradle tasks power the baseline. The **`navDump`** task writes a snapshot of your current graph (destinations, their typed arguments, and the transitions between them) to a committed `.nav` file. The **`navCheck`** task regenerates the graph and compares it against that snapshot, reporting (and, by default, failing on) any difference.

| Task | Purpose |
|------|---------|
| `navDump` | Writes the current nav graph to the committed `.nav` baseline |
| `navCheck` | Compares the current graph against the baseline and fails on drift |

Think of `navDump` as "save the current navigation" and `navCheck` as "has the navigation changed since the last save?" `navCheck` is wired into the standard `check` task, so it runs as part of your normal verification.

Both tasks read the structure extracted by the KSP processor directly: they capture nodes, edges, and typed arguments, but never render thumbnails. That keeps them fast enough for every `check` run and every CI build.

## Step 1: Create a Baseline

Generate the baseline once to capture your current navigation as the "known good" state:

```bash
./gradlew :app:navDump
```

This writes a `.nav` file at `app/nav/app.nav` (configurable via `navgraph { baselineFile.set(...) }`). The file is intentionally human readable so it diffs cleanly in pull requests. When someone changes navigation, reviewers see exactly which destinations or edges moved.

**Commit this file to git** so it becomes the shared baseline for your team:

```bash
git add app/nav/app.nav
git commit -m "Add nav baseline for app module"
```

### What the baseline looks like

The format is deliberately simple: one `dest` line per destination (with its typed arguments and the `start`
marker), and one `edge` line per transition (with its label, when one is declared). This is the actual baseline
committed for the [`samples/sample/`](https://github.com/skydoves/compose-nav-graph/tree/main/samples/sample) module:

```
dest Article  args=(id: String, section: Section = …, query: String? = …, bookmarked: Boolean = …)
dest Feed
dest Home  start
dest Profile  args=(userId: String)
dest ProfileDetailActivity
dest Settings
edge Feed -> Profile
edge Home -> Feed
edge Home -> Settings
edge Profile -> Article  "Test Label"
edge Profile -> ProfileDetailActivity  "View Detail"
edge Profile -> Settings
edge Settings -> Home  "home"
```

Lines are sorted, so the same graph always produces the same file, and a navigation change always produces a
minimal, readable diff. Comment lines starting with `#` are ignored by `navCheck`.

## Step 2: Check for Changes

The `navCheck` task compares the current graph against the baseline. Because it's wired into `check`, it runs with your normal verification, or you can invoke it directly:

```bash
./gradlew :app:navCheck
```

**If nothing changed**, the task passes. **If the graph drifted**, meaning a destination or edge was added, removed, or changed, the task reports the difference and, by default, **fails the build**, preventing the unreviewed navigation change from merging. The failure prints a `- removed` / `+ added` diff against the baseline and tells you to re-run `navDump` if the change is intentional:

```
navgraph: navigation graph changed — app/nav/app.nav is out of date:

  - edge Profile -> Settings
  + dest Onboarding
  + edge Home -> Onboarding  "first run"

Run :app:navDump to update the baseline, then review the diff.
```

When a change *is* intentional, you update the baseline deliberately and commit it:

```bash
./gradlew :app:navDump
git add app/nav/app.nav
git commit -m "Update nav baseline: add Settings screen"
```

This turns every navigation change into a **documented decision** in git history rather than an accidental drift.

## Configuration

The baseline tasks are configured through the `navgraph { }` block. See [Configuration](configuration.md) for full details.

```kotlin
navgraph {
    // Where the committed snapshot lives (default: nav/<module>.nav)
    baselineFile.set(layout.projectDirectory.file("nav/app.nav"))

    // Fail the build on drift (default: true). false → warn only.
    failOnNavChange.set(true)

    // Treat a missing baseline as a skip instead of a failure (default: false)
    allowMissingBaseline.set(false)
}
```

### `failOnNavChange`

By default, `navCheck` fails the build when the graph drifts from the baseline. This is the right behavior for CI, where unreviewed navigation changes shouldn't be merged. Setting it to `false` switches to warning only mode: the drift is still reported, but the build succeeds. A common pattern is strict on CI, warning only locally:

```kotlin
navgraph {
    failOnNavChange.set(System.getenv("CI") == "true")
}
```

### `allowMissingBaseline`

When `true`, running `navCheck` before any `.nav` file exists is a silent skip instead of a failure. This is handy during initial adoption: you can wire `navCheck` into CI before every module has a committed baseline.

```kotlin
navgraph {
    allowMissingBaseline.set(true)
}
```

## Multi Module Projects

Each module gets its own `.nav` baseline, keeping diffs small and scoped to the module that changed:

```
project/
├── app/nav/app.nav
├── feature-feed/nav/feature-feed.nav
└── feature-profile/nav/feature-profile.nav
```

Run `navCheck` for everything at once (the typical CI configuration), or target a single module:

```bash
./gradlew navCheck  # all modules
./gradlew :feature-feed:navCheck
```

Each module's baseline is independent, so updating one doesn't force updates to the others.

## CI Integration

Because `navCheck` is wired into `check`, most CI setups validate navigation for free. To make the gate explicit
(or to run it without the rest of `check`), add a dedicated step:

```yaml
# .github/workflows/ci.yml
- name: Validate navigation baseline
  run: ./gradlew navCheck
```

Both baseline tasks skip thumbnail rendering entirely, so this step costs only the KSP extraction. A typical
review flow then looks like:

1. A contributor changes navigation (adds a screen, removes an edge, renames a route).
2. CI fails `navCheck` with the diff above, so the change can't merge silently.
3. The contributor runs `navDump`, commits the updated `.nav` file, and the **baseline diff itself becomes part of
   the pull request**, where reviewers can read exactly how the app's flow changed.

!!! note "Reviewing navigation in PRs"

    The point of the `.nav` file is the diff. When a pull request changes navigation, the updated `.nav` snapshot shows reviewers precisely what changed, whether a new destination, a removed edge, or a renamed route, in a format that reads like a description of the app's flow, not compiler output.
