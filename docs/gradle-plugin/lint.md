# Nav Lint

The `.nav` baseline reviews how navigation **changed**. `navLint` looks at what it **is**: it walks the merged
graph and reports screens the user can't reach, a missing or duplicated start destination, and routes no
`@NavDestination` binds.

The distinction matters, because `navCheck` is perfectly happy with a broken graph. An unreachable screen is
recorded in the baseline just like any other, so it passes `check` on every build, forever. Nothing today asks
whether the graph is actually navigable.

```bash
./gradlew :app:navLint
```

```
navgraph: 3 navigation lint finding(s):

  [unreachable] Checkout
      no path from Home
  [unbound-route] Orphan
      declared as a route but no @NavDestination binds a screen to it
  [multiple-starts] Onboarding
      marked start, and so are 1 other destination(s)

Silence a rule with navgraph { navLintDisabledRules }, or one route with navgraph { navLintIgnoredRoutes }.
```

## Rules

| Rule id | What it catches |
|---|---|
| `unreachable` | No directed path from a start destination reaches the screen. You added it and forgot to wire it, or deleted the last transition into it. |
| `no-start` | No destination is marked `start` — nothing carries `@NavGraphRoot`, so the graph has no anchor. Reachability can't run without it, so it is reported alone. |
| `multiple-starts` | More than one destination is marked `start`. Easy to hit in a multi module app, where the flag is unioned across every module that declares a root. |
| `unbound-route` | A route exists in the graph but no `@NavDestination` binds a screen to it. |

### `unreachable` is not the exports' "Unconnected screens"

They answer different questions, and the sets genuinely differ. The [exports](export.md) split off screens with
**no transition at all** so they don't clutter the picture — that is a decluttering device, and it ignores edge
direction and the start destination entirely. Two screens that link to each other but that nothing reaches from
`Home` are drawn inside the flow, and lint reports both. Lint asks the question that matters to a user: *can I
get there*.

## Warns by default

`navLint` warns; it does not fail. Turn it into a gate where you want one:

```kotlin
navgraph {
    failOnNavLint.set(true)  // default: false (warn)
}
```

## Why it isn't in `check`

`navCheck` runs on every `check` because it reads the render-free extracted manifest. `navLint` reads the
**aggregated** graph — every module merged — because that is the only place the question can be answered
honestly: in a single module's own manifest a route another module owns appears as a bare stub, indistinguishable
from a genuinely unbound route, so a thin `:app` would report almost every screen. Gating `check` on the
aggregated graph would make `check` build every dependency module's graph, thumbnail renders included.

So run it as its own CI step:

```yaml
- run: ./gradlew check navLint
```

If your build has no dependency modules to drag in, or you accept the cost, wire it up:

```kotlin
navgraph {
    lintOnCheck.set(true)  // default: false
}
```

When lint runs on a module that isn't aggregating, the report says so, so a partial-graph finding can be
discounted rather than chased.

## Suppressing

Turn off a rule everywhere, or exempt individual routes by FQN:

```kotlin
navgraph {
    navLintDisabledRules.add("unbound-route")
    navLintIgnoredRoutes.add("com.app.DeepLinkOnly")
}
```

An ignored route reports nothing from any rule. Use it for a destination that is unwired on purpose — one reached
only by a deep link, or a screen still being built.
