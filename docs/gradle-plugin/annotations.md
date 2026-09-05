# Annotations

The nav graph is reconstructed from four annotations in `com.github.skydoves:compose-nav-graph-annotations` (added automatically by the Gradle plugin). They exist because Compose navigation is imperative: the route to composable wiring lives inside `entry<Route> { Screen() }` lambdas and transitions are `backStack.add(...)` calls in arbitrary function bodies. These are function *bodies*, which a static processor like KSP cannot read. The annotations are the explicit, refactor safe declarations that let the toolkit see your graph.

Two kinds of classes become nodes:

- **Navigation 3 `NavKey` implementors** declared in the module are picked up **automatically**, with no annotation needed for the node itself.
- **Any other class referenced by an annotation** (a `route`, `from`, or `to` argument) also becomes a node: a Navigation 2 route, a plain Activity, anything resolvable on the classpath. A route does not need to implement `NavKey`, so an existing app lights up without refactoring.

| Annotation | Attach to | What it declares |
|------------|-----------|------------------|
| `@NavDestination(route)` | the screen composable | the click target for a route: where double click to source jumps |
| `@NavEdge(to, from, label)` | the source screen or its route class | a navigation transition (edge) between two routes; repeatable |
| `@NavPreview(route, primary)` | a `@Preview` composable | links that preview to a route so its render becomes the node thumbnail |
| `@NavGraphRoot(route)` | the start route class or its screen | the graph's start destination |

## `@NavDestination`

Marks the top level composable that renders a destination. This is what gives each node a **click target**: selecting the route's node in the flow map jumps to this function. It's required because the route to composable link lives inside an `entry<Route> { … }` lambda body, which KSP can't see. The annotation records the function's fully qualified name and source location instead.

```kotlin
@NavDestination(route = Profile::class)
@Composable
fun ProfileScreen(state: ProfileState) { /* … */ }
```

The node's typed arguments come from the **route class** (see [Typed arguments](#typed-arguments) below), not from the composable's parameters.

## `@NavEdge`

Declares a navigation transition (edge) between two destinations. Because transitions are imperative `backStack.add(...)` calls inside function bodies, edges aren't statically decidable in general, so they're taken from this explicit annotation.

Apply it on the **source**: either the source's route class declaration or its screen composable. `from` defaults to `Unit::class`, meaning "the route of whatever I'm attached to":

- on a screen composable, the source is taken from that same function's `@NavDestination(route)`;
- on a route class (for example a `NavKey`), the source is that class itself.

`@NavEdge` is **repeatable**, so a screen with several outgoing transitions carries several of them:

```kotlin
@NavEdge(to = Profile::class)  // from = this screen's route
@NavEdge(to = Settings::class, label = "menu")
@NavDestination(route = Feed::class)
@Composable
fun FeedScreen() { /* … */ }
```

The optional `label` is a human readable description of the transition (e.g. the triggering action), drawn on the edge in the graph.

!!! warning "Give `from` explicitly when there's no `@NavDestination`"

    On a screen composable that has **no** `@NavDestination`, the default `from` is undefined and `compose-nav-graph-ksp` reports an error. Either add `@NavDestination(route)` to that function, or pass an explicit `from` to the edge: `@NavEdge(from = Feed::class, to = Profile::class)`.

!!! note "Edges to non-NavKey destinations"

    `to` (and `from`) can reference any class, not only `NavKey` types. `@NavEdge(to = DetailActivity::class)` adds a plain Activity to the graph as a node, which is how mixed Navigation 2 / Activity flows are drawn without refactoring.

## `@NavPreview`

Links a `@Preview` composable to the route it depicts, so the generated graph can use that preview's rendered image as the node's **thumbnail**. There's no intrinsic connection between a `@Preview` function and an `entry<Route> { … }`, and nothing can infer it, so this annotation is the explicit link.

```kotlin
@NavPreview(route = Profile::class, primary = true)
@Preview
@Composable
fun ProfileScreenPreview() {
    ProfileScreen(ProfileState.Preview)
}
```

Set `primary = true` (default `false`) on the preview you want as the canonical thumbnail. When a route has several previews (light/dark, empty/loaded), exactly one of them should be `primary`.

The thumbnail also honors the preview's **`@Preview(locale = …)`** qualifier, declared directly or through a
multipreview meta-annotation (a custom `@DevicePreview` carrying `@Preview(locale = "ko")`, for example). Both
render backends apply it, so a localized preview renders with the same string and drawable resources Android
Studio's preview would show. When a function's `@Preview`s declare different locales, the first one wins (one
function renders one thumbnail).

!!! note "Why a separate preview function"

    The renderer reads `@NavPreview` via reflection on the discovered preview at render time, so it's `RUNTIME`-retained. You annotate the **preview** (not the screen) so the toolkit renders the screen exactly as you'd preview it in Android Studio, with its sample/fake state wired in. `@PreviewParameter` providers are honored.

## `@NavGraphRoot`

Marks the graph's **start destination**, highlighted in the flow map (accent border + ★ glyph by default). Attach it to the start route class with no argument, or to a screen composable (where the route is taken from the same function's `@NavDestination`), or to any element with an explicit `route`:

```kotlin
// On the route class:
@NavGraphRoot
data object Feed : NavKey

// …or on the screen composable (route inferred from @NavDestination):
@NavGraphRoot
@NavDestination(route = Feed::class)
@Composable
fun FeedScreen() { /* … */ }
```

!!! note "One start per project"

    `@NavGraphRoot` assumes a single start destination per project.

## Typed Arguments

Each node's arguments are extracted statically from its **route class**, mirroring what kotlinx.serialization would serialize:

- **Primary constructor properties**, in constructor order. A parameter with a default value is marked **optional**.
- **Body `val`/`var` properties with a backing field**, in declared order, always optional.
- `@Transient` properties are excluded, and `@SerialName` renames are honored.
- Each argument carries its type, type arguments, nullability, and whether it's an enum, and is drawn UML style on the node.

```kotlin
@Serializable
data class Profile(
    val userId: String,  // userId: String
    val tab: Tab = Tab.Posts,  // tab: Tab (enum, optional)
) : NavKey
```

A referenced non-`NavKey` node (for example an Activity reached mid flow) carries no arguments; its arbitrary fields aren't mined.

## Putting It Together

A fully annotated start screen with a thumbnail and two outgoing transitions:

```kotlin
@NavGraphRoot
@NavDestination(route = Feed::class)
@NavEdge(to = Profile::class, label = "open profile")
@NavEdge(to = Settings::class, label = "menu")
@Composable
fun FeedScreen() { /* … */ }

@NavPreview(route = Feed::class, primary = true)
@Preview
@Composable
fun FeedScreenPreview() {
    FeedScreen()
}
```

Run `./gradlew :app:generateNavGraph` and this screen appears as the start node, with its rendered thumbnail and two labeled edges to `Profile` and `Settings`.

## Inferred Transitions

You don't have to declare every transition. By default the plugin also **reads your navigation call sites** and adds
what it finds to the graph, so applying the plugin to an unannotated app already produces a connected flow.

This exists because KSP reads declarations only. It cannot see inside a function body, so
`entry<Home> { … backStack.add(Feed) }` is invisible to it and, without inference, every arrow in a 40 screen app
has to be hand written. Instead, the `inferNavEdges` task scans your Kotlin sources for navigation calls inside a
navigation scope and resolves each target against the routes already in the graph:

```kotlin
entryProvider {
    entry<Home> {                                        // scope: Home
        HomeScreen(
            onOpenFeed = { backStack.add(Feed) },        // inferred: Home → Feed
            onOpenSettings = { backStack.add(Settings) } // inferred: Home → Settings
        )
    }
}
```

Two kinds of scope are read: an `entry<Route> { }` block (Navigation 3, including `entry<Route>(metadata = …) { }`)
or a typed `composable<Route> { }` block (Navigation 2), and the body of a `@NavDestination` composable, for apps
that pass the back stack down into the screen instead.

Matching is by **method name**, never by receiver, so a custom navigator works with no configuration:

```kotlin
entry<Schedule> {
    ScheduleScreen(onSession = { navigator.add(SessionScreen(it)) })  // inferred: Schedule → SessionScreen
}
```

### Inferred vs declared

An inferred transition is drawn **dashed** everywhere, so it is never mistaken for one you declared:

| | Declared `@NavEdge` | Inferred call site |
|---|---|---|
| Graph, HTML, PNG | solid arrow | dashed arrow |
| Mermaid export | `-->` | `-.->` |
| `.nav` baseline | recorded | **not** recorded (see [`baselineIncludesInferred`](configuration.md#baselineincludesinferred)) |
| Can carry a label | yes | no |

A declared `@NavEdge` always wins over an inferred duplicate of the same transition, so adding an annotation is how
you attach a label or override what was read.

### What it can and can't read

Inference is deliberately conservative: a reference that doesn't resolve to a route already in the graph is
**dropped**, never guessed, so it can never invent a destination. Two things follow from that:

- A target that isn't written literally at the call site, such as `backStack.add(route)` where `route` is a variable
  or a function's return value, can't be recovered. Declare those with `@NavEdge`.
- Navigation wired up outside a screen's block, such as a bottom navigation bar switching top level tabs, or an
  `Activity` started with an `Intent`, is not attributed to a screen. Declare those with `@NavEdge` too.
- Inference is **scoped to one module**. Each module resolves against the routes its own extraction knows: the ones
  it declares, plus any route from another module that one of its annotations names. So in a multi module app, an
  `entry<FeatureRoute> { }` in `:app` referencing a route `:app` never mentions in an annotation is not inferred —
  give that one a `@NavEdge`, which also brings the route into `:app`'s graph so the rest of that screen's call
  sites resolve. (Reading dependency modules' routes would mean depending on their build outputs, which would drag
  every dependency's thumbnail render into `check`.)

Run with `--info` to see the transitions it looked for and didn't find.

Turn it off with [`inferEdges`](configuration.md#inferedges) if you want the graph to show only what you declared.
