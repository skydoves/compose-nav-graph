/*
 * Designed and developed by 2026 skydoves (Jaewoong Eum)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.skydoves.navgraph.ksp

import com.github.skydoves.navgraph.model.EdgeConfidence
import com.github.skydoves.navgraph.model.NavArg
import com.github.skydoves.navgraph.model.NavGraph
import com.github.skydoves.navgraph.model.NavGraphEdge
import com.github.skydoves.navgraph.model.NavNode
import com.github.skydoves.navgraph.model.NavPreviewParam
import com.github.skydoves.navgraph.model.NavPreviewRef
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.FileLocation
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import kotlinx.serialization.json.Json

/**
 * Extracts a Navigation 3 graph statically and emits it as `nav-graph.json` (the contract artifact
 * consumed by `compose-nav-graph-gradle` → `compose-nav-graph-idea`).
 *
 * Reads declaration-level info only — KSP cannot see `entry<T>{}`/`backStack.add` bodies:
 *  - **nodes** = concrete `NavKey` subtypes, both declared in this module and those *referenced* by our
 *    annotations yet declared in a dependency (resolved across modules); **args** = serializable properties
 *    (ctor + body backing-field), honoring `@Transient`/`@SerialName`;
 *  - **click targets** = `@NavDestination` composables (FQN + source location);
 *  - **edges** = `@NavEdge` (incl. the `@Repeatable` `@NavEdge.Container`), validated against nodes;
 *  - **thumbnails** = `@NavPreview` links (PNG paths filled later by `compose-nav-graph-gradle`);
 *  - **start** = `@NavGraphRoot`.
 */
internal class NavGraphProcessor(
  private val codeGenerator: CodeGenerator,
  private val logger: KSPLogger,
  private val options: Map<String, String> = emptyMap(),
) : SymbolProcessor {

  private var invoked = false

  // Opt-in: build the node set from ONLY annotated declarations (@NavDestination/@NavPreview) and
  // @NavEdge endpoints, skipping the broad "every NavKey implementor" scan. Lets a consumer get a
  // clean graph of exactly the destinations they annotated instead of nodes for unused/duplicate
  // NavKey classes that no entry references.
  private val annotatedOnly = options[OPT_ANNOTATED_ONLY] == "true"

  // The consumer's Gradle module path (e.g. ":feature:home"), injected by compose-nav-graph-gradle as a KSP arg — KSP itself
  // can't see the Gradle module identity. The preview gallery uses it to group previews by module and to keep
  // node ids unique across modules. Null for a single-module project or when the plugin didn't inject it.
  private val modulePath = options[OPT_MODULE]?.takeIf { it.isNotBlank() && it != ":" }

  override fun process(resolver: Resolver): List<KSAnnotated> {
    if (invoked) return emptyList()
    invoked = true

    val navKeyType = resolver
      .getClassDeclarationByName(resolver.getKSNameFromString(NAV_KEY))
      ?.asStarProjectedType()
    if (navKeyType == null) {
      logger.warn(
        "compose-nav-graph-ksp: $NAV_KEY not on the classpath — emitting an empty nav graph.",
      )
    }

    fun isConcreteNavKey(decl: KSClassDeclaration): Boolean =
      (decl.classKind == ClassKind.CLASS || decl.classKind == ClassKind.OBJECT) &&
        !decl.isCompanionObject &&
        Modifier.ABSTRACT !in decl.modifiers && Modifier.SEALED !in decl.modifiers &&
        navKeyType != null && navKeyType.isAssignableFrom(decl.asStarProjectedType())

    // Local nodes: concrete NavKey subtypes declared in THIS module's sources. Skipped under
    //      `navgraph.annotatedOnly` so the graph holds only annotated/edge-referenced destinations.
    val localNavKeyDecls =
      if (annotatedOnly) {
        emptyList()
      } else {
        resolver.allClassDeclarations().filter(::isConcreteNavKey).toList()
      }

    // @NavDestination → click target (composable FQN + source location) per route
    val clickTargets = mutableMapOf<String, ClickTarget>()
    resolver.getSymbolsWithAnnotation(NAV_DESTINATION, inDepth = true)
      .filterIsInstance<KSFunctionDeclaration>()
      .forEach { fn ->
        val route =
          fn.annotationsNamed(NAV_DESTINATION).firstOrNull()?.typeArgFqn("route") ?: return@forEach
        if (route in clickTargets) {
          logger.warn(
            "compose-nav-graph-ksp: duplicate @NavDestination for route '$route' — keeping the last.",
            fn,
          )
        }
        val loc = fn.location as? FileLocation
        clickTargets[route] = ClickTarget(
          fqn = fn.qualifiedName?.asString() ?: fn.simpleName.asString(),
          file = loc?.filePath,
          line = loc?.lineNumber,
        )
      }

    // @NavPreview → previews per route
    val previews = mutableMapOf<String, MutableList<NavPreviewRef>>()
    resolver.getSymbolsWithAnnotation(NAV_PREVIEW, inDepth = true)
      .filterIsInstance<KSFunctionDeclaration>()
      .forEach { fn ->
        fn.annotationsNamed(NAV_PREVIEW).forEach { ann ->
          val route = ann.typeArgFqn("route") ?: return@forEach
          previews.getOrPut(route) { mutableListOf() }.add(
            NavPreviewRef(
              previewName = fn.simpleName.asString(),
              previewFqn = fn.qualifiedName?.asString(),
              previewMethodFqn = jvmMethodFqn(fn),
              previewParameters = previewParametersOf(fn),
              primary = ann.boolArg("primary"),
              locale = previewLocaleOf(fn),
            ),
          )
        }
      }

    // @NavGraphRoot → start destination(s)
    val startRoutes = mutableSetOf<String>()
    resolver.getSymbolsWithAnnotation(NAV_GRAPH_ROOT, inDepth = true).forEach { owner ->
      val ann = owner.annotationsNamed(NAV_GRAPH_ROOT).firstOrNull() ?: return@forEach
      resolveRouteOrSelf(ann, "route", owner)?.let(startRoutes::add)
    }

    // @NavEdge → raw edges (handles the @Repeatable container); validated once nodes are known
    val rawEdges = mutableListOf<NavGraphEdge>()
    resolver.getSymbolsWithAnnotation(NAV_EDGE, inDepth = true).forEach { owner ->
      owner.navEdgeAnnotations().forEach { ann ->
        val to = ann.typeArgFqn("to")
        if (to == null) {
          logger.error("@NavEdge requires a 'to' route.", owner)
          return@forEach
        }
        val from = resolveRouteOrSelf(ann, "from", owner)
        if (from == null) {
          logger.error(
            "@NavEdge 'from' is unresolved — put it on a NavKey, pass an explicit from=, " +
              "or add @NavDestination to the same composable.",
            owner,
          )
          return@forEach
        }
        rawEdges += NavGraphEdge(
          from = from,
          to = to,
          label = ann.stringArg("label")?.takeIf(String::isNotEmpty),
          confidence = EdgeConfidence.ANNOTATED,
        )
      }
    }
    // Referenced nodes: ANY class referenced by our annotations (route/from/to) and resolvable on the
    //      classpath becomes a node — whether or not it is a NavKey. Two things this unlocks:
    //        (a) cross-module NavKeys declared in a DEPENDENCY (nowinandroid: NavKeys in :feature:*:api, screens in
    //            :impl, wiring in :app) → a connected graph instead of fragments;
    //        (b) NON-NavKey routes reached mid-flow — e.g. an Android `Activity` (`@NavEdge(to = DetailActivity::class)`,
    //            `@NavPreview(route = DetailActivity::class)`) — so a graph can be built purely from annotations
    //            WITHOUT forcing every route to inherit NavKey (lower-friction adoption on an existing app).
    //      Local non-NavKey classes are NOT swept in (they must be referenced), so unrelated classes never become
    //      phantom nodes; a sealed NavKey hierarchy still yields all its subtypes as nodes for free via
    //      `localNavKeyDecls`, so existing baselines don't change.
    val referencedRoutes = buildSet {
      addAll(clickTargets.keys)
      addAll(previews.keys)
      addAll(startRoutes)
      rawEdges.forEach {
        add(it.from)
        add(it.to)
      }
    }
    val referencedDecls = referencedRoutes.mapNotNull { fqn ->
      resolver.getClassDeclarationByName(resolver.getKSNameFromString(fqn))
    }
    val nodeDecls = (localNavKeyDecls + referencedDecls).distinctBy { it.canonicalFqn() }
    val nodeIds = nodeDecls.mapNotNull { it.canonicalFqn() }.toSet()

    startRoutes.filter { it !in nodeIds }
      .forEach {
        logger.warn("compose-nav-graph-ksp: @NavGraphRoot route '$it' did not resolve to a node.")
      }

    val edges = rawEdges.distinct().filter { e ->
      val ok = e.from in nodeIds && e.to in nodeIds
      if (!ok) {
        logger.warn(
          "compose-nav-graph-ksp: dropping @NavEdge ${e.from} → ${e.to} (an endpoint did not resolve to a node).",
        )
      }
      ok
    }.sortedWith(compareBy({ it.from }, { it.to }))

    // Assemble + emit
    val nodes = nodeDecls.mapNotNull { decl ->
      val id = decl.canonicalFqn() ?: return@mapNotNull null
      NavNode(
        id = id,
        route = decl.simpleName.asString(),
        module = modulePath,
        clickTargetFqn = clickTargets[id]?.fqn,
        sourceFile = clickTargets[id]?.file,
        sourceLine = clickTargets[id]?.line,
        // Nav args = a NavKey's serializable properties; a referenced non-NavKey node (e.g. an Activity reached
        // mid-flow) carries none, so don't mine its arbitrary fields.
        args = if (isConcreteNavKey(decl)) extractArgs(decl) else emptyList(),
        previews = previews[id].orEmpty().sortedByDescending { it.primary },
        start = id in startRoutes,
      )
    }.sortedBy { it.route }

    val graph = NavGraph(nodes = nodes, edges = edges)

    // The manifest is a function of the whole module → ALL_FILES (any change clobbers it). Correct
    // for an aggregating, project-wide output; a partial originating-file set would leave it stale.
    codeGenerator.createNewFile(
      dependencies = Dependencies.ALL_FILES,
      packageName = "",
      fileName = "nav-graph",
      extensionName = "json",
    ).use { it.write(JSON.encodeToString(NavGraph.serializer(), graph).toByteArray()) }

    logger.info(
      "compose-nav-graph-ksp: emitted nav-graph.json — ${nodes.size} nodes, ${edges.size} edges.",
    )

    emitPreviewGallery(resolver)
    return emptyList()
  }

  /**
   * Emits `preview-gallery.json`: EVERY `@Preview` composable in the module (not only the `@NavPreview` ones),
   * grouped into a synthetic node per (module, package) so the existing render → merge → aggregate → export
   * pipeline consumes it unchanged. Each node [id] is a dot-free, filesystem-safe slug and each preview's label
   * is unique within its node, which keeps the thumbnail filename (`<id.substringAfterLast('.')>_<label>`) and
   * the `(id, label)` merge key collision-free by construction — so no render/merge code changes.
   *
   * Discovers BOTH direct `@Preview` AND **multipreview** functions — those carrying only a meta-annotation that
   * is itself (directly or transitively) annotated with `@Preview` (androidx's `@PreviewLightDark`,
   * `@PreviewScreenSizes`, …, or a user's custom `@MyDevicePreviews`). `getSymbolsWithAnnotation` is NOT
   * transitive, so multipreview functions never appear in the direct set; we recover them by scanning every
   * module function and testing its annotations via [isPreviewAnnotation] (see [emitPreviewGallery]'s union
   * below). v1 emits ONE representative entry per discovered function (a multipreview is not expanded into its N
   * device-config thumbnails — that's a future enhancement; the goal here is that the function APPEARS in the
   * gallery); a function carrying multiple `@Preview`s likewise renders once. Independent of `navgraph.annotatedOnly`
   * (that flag only narrows the nav-graph node set).
   */
  private fun emitPreviewGallery(resolver: Resolver) {
    val moduleSlug = slug(modulePath ?: "app").ifBlank { "app" }

    // Stable dedupe key reused for direct + multipreview: prefer the qualifiedName, else file+name (not bare
    // simpleName) so two distinct previews that both lack a qualifiedName can't collapse into one.
    fun KSFunctionDeclaration.galleryKey(): String = qualifiedName?.asString()
      ?: "${(location as? FileLocation)?.filePath}#${simpleName.asString()}"

    // 1) DIRECT @Preview — `getSymbolsWithAnnotation(inDepth)` finds top-level + member, but NOT meta-annotated.
    val direct = resolver.getSymbolsWithAnnotation(PREVIEW, inDepth = true)
      .filterIsInstance<KSFunctionDeclaration>()
    val directKeys = direct.map { it.galleryKey() }.toSet()

    // 2) MULTIPREVIEW — every other module function whose annotation set transitively reaches @Preview. Restrict
    //    to functions carrying ≥1 annotation (most have none) and skip those already in the direct set; the
    //    per-call `visited` cycle guard in `isPreviewAnnotation` bounds the meta-annotation walk.
    val multipreview = resolver.allFunctions()
      .filter { it.galleryKey() !in directKeys && it.annotations.any() }
      .filter { fn ->
        fn.annotations.any { ann ->
          val decl = ann.annotationType.resolve().declaration as? KSClassDeclaration
          decl != null && isPreviewAnnotation(decl, mutableSetOf())
        }
      }

    val byPackage = (direct + multipreview)
      // Dedupe a symbol returned more than once (and any direct/multipreview overlap, though we already excluded
      // it) by the same key used for direct discovery.
      .distinctBy { it.galleryKey() }
      .groupBy { it.packageName.asString() }

    val nodes = byPackage.entries.sortedBy { it.key }.map { (pkg, fns) ->
      // A per-node tally disambiguates same-named previews (a member `Class.fn`, or a `#n` suffix on collision),
      // so `(node.id, previewName)` — the render filename + merge key — is injective within the package node.
      val seen = mutableMapOf<String, Int>()
      val previews = fns
        .sortedBy { it.qualifiedName?.asString() ?: it.simpleName.asString() }
        .mapNotNull { fn ->
          val method = jvmMethodFqn(fn) ?: run {
            logger.warn(
              "compose-nav-graph-ksp: skipping @Preview '${fn.qualifiedName?.asString()}' — no JVM method name.",
            )
            return@mapNotNull null
          }
          val owner = fn.parentDeclaration as? KSClassDeclaration
          val base = if (owner != null) {
            "${owner.simpleName.asString()}.${fn.simpleName.asString()}"
          } else {
            fn.simpleName.asString()
          }
          val n = seen.merge(base, 1, Int::plus)!!
          NavPreviewRef(
            previewName = if (n == 1) base else "$base#$n",
            previewFqn = fn.qualifiedName?.asString(),
            previewMethodFqn = method,
            previewParameters = previewParametersOf(fn),
            primary = false,
            locale = previewLocaleOf(fn),
          )
        }
      // The slug is lossy (two packages/modules differing only by `_` vs `.`/`-`/`:` collapse to one), so append
      // a stable hash of the RAW (module, package) to keep node ids injective — otherwise aggregation, which
      // unions by id, would silently fuse the two and drop one package's previews.
      val idHash = Integer.toHexString("${modulePath ?: ""} $pkg".hashCode())
      NavNode(
        id = "${moduleSlug}__${slug(pkg.ifBlank { "_root_" })}__$idHash",
        route = pkg.ifBlank { "<root>" },
        module = modulePath,
        previews = previews,
      )
    }.filter { it.previews.isNotEmpty() }

    val gallery = NavGraph(nodes = nodes, edges = emptyList())
    codeGenerator.createNewFile(
      dependencies = Dependencies.ALL_FILES,
      packageName = "",
      fileName = "preview-gallery",
      extensionName = "json",
    ).use { it.write(JSON.encodeToString(NavGraph.serializer(), gallery).toByteArray()) }

    val previewCount = nodes.sumOf { it.previews.size }
    logger.info(
      "compose-nav-graph-ksp: emitted preview-gallery.json — $previewCount preview(s) in ${nodes.size} package(s).",
    )
  }

  /**
   * True if [decl] is the `@Preview` annotation itself OR a **multipreview** — an annotation that is, directly or
   * transitively, annotated with `@Preview` (e.g. `@PreviewLightDark`, or a user's `@MyDevicePreviews` declared
   * `@Preview @Preview …`). We only ever recurse into *annotation-type* declarations of [decl]'s own annotations,
   * so the walk stays inside the annotation graph.
   *
   * [visited] (keyed by qualified name) is the cycle guard: annotation graphs are cyclic — `@Preview` carries
   * Kotlin's `@Retention`/`@Repeatable`/`@Target`, which carry each other — so without it the recursion would
   * not terminate. We short-circuit the moment `@Preview` is reached and never revisit a name; the standard
   * `kotlin.annotation.*` meta-annotations simply resolve to non-preview leaves and end the branch.
   */
  private fun isPreviewAnnotation(decl: KSClassDeclaration, visited: MutableSet<String>): Boolean {
    val name = decl.qualifiedName?.asString()
    if (name == PREVIEW) return true
    // Unnamed (error/local) annotation, or one already on the current path → not (a new) preview source.
    if (name == null || !visited.add(name)) return false
    return decl.annotations.any { meta ->
      val metaDecl = meta.annotationType.resolve().declaration as? KSClassDeclaration
      metaDecl != null && isPreviewAnnotation(metaDecl, visited)
    }
  }

  /**
   * The `@Preview(locale = …)` qualifier the function's preview declares, or null. Walks the function's
   * annotations in declaration order — recursing into multipreview meta-annotations depth-first (same cycle
   * guard as [isPreviewAnnotation]) — and returns the FIRST non-blank locale found. A function whose
   * `@Preview`s disagree on locale renders one thumbnail, so one locale has to win, and declaration order is
   * the deterministic choice (a meta-annotation listed before a direct `@Preview` wins).
   */
  private fun previewLocaleOf(fn: KSFunctionDeclaration): String? =
    firstPreviewLocale(fn.annotations, mutableSetOf())

  private fun firstPreviewLocale(
    annotations: Sequence<KSAnnotation>,
    visited: MutableSet<String>,
  ): String? {
    for (ann in annotations) {
      val decl = ann.annotationType.resolve().declaration as? KSClassDeclaration ?: continue
      val name = decl.qualifiedName?.asString() ?: continue
      if (name == PREVIEW) {
        ann.stringArg("locale")?.takeIf(String::isNotBlank)?.let { return it }
        continue
      }
      if (!visited.add(name)) continue
      firstPreviewLocale(decl.annotations, visited)?.let { return it }
    }
    return null
  }

  /**
   * Serializable properties = the navigation arguments: primary-constructor properties (in ctor order) then
   * body `val/var` with backing fields (declared order), excluding `@Transient`. Uses *declared* (not
   * inherited) properties to avoid supertype name-collisions.
   */
  private fun extractArgs(decl: KSClassDeclaration): List<NavArg> {
    val declared = decl.declarations.filterIsInstance<KSPropertyDeclaration>()
      .filter { it.hasBackingField }
      .toList()
    val byName = declared.associateBy { it.simpleName.asString() }
    val ctorParams = decl.primaryConstructor?.parameters.orEmpty()
    val ctorNames = ctorParams.mapNotNull { it.name?.asString() }.toSet()

    val args = mutableListOf<NavArg>()
    // 1) constructor properties, in constructor order (optional = the param has a default)
    for (param in ctorParams) {
      val name = param.name?.asString() ?: continue
      val prop = byName[name] ?: continue // a ctor param that isn't a property → not serialized
      if (prop.isTransient()) continue
      args += navArg(prop, optional = param.hasDefault)
    }
    // 2) body backing-field properties not in the constructor (always have an initializer)
    for (prop in declared) {
      val name = prop.simpleName.asString()
      if (name in ctorNames || prop.isTransient()) continue
      args += navArg(prop, optional = true)
    }
    return args
  }

  private fun navArg(prop: KSPropertyDeclaration, optional: Boolean): NavArg {
    val type = prop.type.resolve()
    val typeDecl = type.declaration
    return NavArg(
      name = prop.serialName() ?: prop.simpleName.asString(),
      type = typeDecl.qualifiedName?.asString() ?: typeDecl.simpleName.asString(),
      typeArguments = type.arguments.mapNotNull {
        it.type?.resolve()?.declaration?.qualifiedName?.asString()
      },
      nullable = type.isMarkedNullable,
      optional = optional,
      enum = (typeDecl as? KSClassDeclaration)?.classKind == ClassKind.ENUM_CLASS,
    )
  }

  /** Resolves a `route`/`from` KClass arg; [Unit] (the default) means "the element I'm attached to". */
  private fun resolveRouteOrSelf(ann: KSAnnotation, arg: String, owner: KSAnnotated): String? {
    val fqn = ann.typeArgFqn(arg)
    return if (fqn == null || fqn == UNIT) selfRoute(owner) else fqn
  }

  private fun selfRoute(owner: KSAnnotated): String? = when (owner) {
    is KSClassDeclaration -> owner.canonicalFqn()

    is KSFunctionDeclaration -> owner.annotationsNamed(
      NAV_DESTINATION,
    ).firstOrNull()?.typeArgFqn("route")

    else -> null
  }

  /** All `@NavEdge` on a symbol, unwrapping the `@Repeatable` `@NavEdge.Container` (value is an array). */
  private fun KSAnnotated.navEdgeAnnotations(): List<KSAnnotation> {
    val direct = annotationsNamed(NAV_EDGE)
    val contained = annotationsNamed(NAV_EDGE_CONTAINER)
      .flatMap { container -> container.arg("value").asAnnotationList() }
    return direct + contained
  }

  private data class ClickTarget(val fqn: String, val file: String?, val line: Int?)

  private companion object {
    const val NAV_KEY = "androidx.navigation3.runtime.NavKey"
    const val NAV_DESTINATION = "com.github.skydoves.navgraph.annotations.NavDestination"
    const val NAV_EDGE = "com.github.skydoves.navgraph.annotations.NavEdge"
    const val NAV_EDGE_CONTAINER = "com.github.skydoves.navgraph.annotations.NavEdge.Container"
    const val NAV_PREVIEW = "com.github.skydoves.navgraph.annotations.NavPreview"
    const val NAV_GRAPH_ROOT = "com.github.skydoves.navgraph.annotations.NavGraphRoot"
    const val PREVIEW = "androidx.compose.ui.tooling.preview.Preview"
    const val TRANSIENT = "kotlinx.serialization.Transient"
    const val SERIAL_NAME = "kotlinx.serialization.SerialName"
    const val UNIT = "kotlin.Unit"
    const val OPT_ANNOTATED_ONLY = "navgraph.annotatedOnly"
    const val OPT_MODULE = "navgraph.module"
    val JSON = Json { prettyPrint = true }
  }
}

// KSP helpers

private fun Resolver.allClassDeclarations(): Sequence<KSClassDeclaration> = getAllFiles().flatMap {
  it.declarations
}.flatMap { it.containedClasses() }

private fun KSDeclaration.containedClasses(): Sequence<KSClassDeclaration> = sequence {
  if (this@containedClasses is KSClassDeclaration) {
    yield(this@containedClasses)
    declarations.forEach { yieldAll(it.containedClasses()) }
  }
}

/**
 * Every function declared in this module's sources — top-level AND member (recursing classes/objects, e.g. a
 * `@Preview` inside an `object Previews`). Mirrors [allClassDeclarations]; needed because multipreview functions
 * carry no DIRECT `@Preview`, so `getSymbolsWithAnnotation(PREVIEW)` can't surface them — we must enumerate and
 * test each function's annotations ourselves.
 */
private fun Resolver.allFunctions(): Sequence<KSFunctionDeclaration> = getAllFiles().flatMap {
  it.declarations
}.flatMap { it.containedFunctions() }

private fun KSDeclaration.containedFunctions(): Sequence<KSFunctionDeclaration> = sequence {
  if (this@containedFunctions is KSFunctionDeclaration) yield(this@containedFunctions)
  if (this@containedFunctions is KSClassDeclaration) {
    declarations.forEach { yieldAll(it.containedFunctions()) }
  }
}

/** The `@PreviewParameter` arguments of a preview function — the renderer needs each provider to instantiate
 *  the composable's sample value (else a parameterized `@NavPreview` renders blank). */
private fun previewParametersOf(fn: KSFunctionDeclaration): List<NavPreviewParam> =
  fn.parameters.mapNotNull { p ->
    val pp =
      p.annotationsNamed("androidx.compose.ui.tooling.preview.PreviewParameter").firstOrNull()
        ?: return@mapNotNull null
    val provider = pp.typeArgFqn("provider") ?: return@mapNotNull null
    NavPreviewParam(name = p.name?.asString() ?: "", provider = provider)
  }

/** A filesystem-safe, dot-free slug: every char that isn't a letter/digit/underscore becomes `_`. Dot-free is
 *  load-bearing — the render derives a thumbnail name from `id.substringAfterLast('.')`, so a dotted id would be
 *  truncated to its last segment and could collide across sibling packages. */
private fun slug(s: String): String =
  s.map { if (it.isLetterOrDigit() || it == '_') it else '_' }.joinToString("")

/**
 * The JVM method FQN of a `@Preview` function, as the standalone Layoutlib renderer (`compose-preview-renderer`)
 * resolves it: it splits on the final `.` into <class>/<method>, so a top-level fun must be addressed via its
 * **file facade** (`HomeScreen.kt` → `…HomeScreenKt`), not its Kotlin FQN (`…HomeScreenPreview`, which would
 * mis-split into class `…HomeScreen` / method `Preview`). A member preview uses its enclosing class FQN.
 */
private fun jvmMethodFqn(fn: KSFunctionDeclaration): String? {
  val method = fn.simpleName.asString()
  val owner = fn.parentDeclaration as? KSClassDeclaration
  if (owner != null) {
    // A member preview: the renderer splits methodFQN on the last '.' then `loadClass(owner)`, so the owner must
    // be its JVM **binary** name — nested classes / `Companion` are joined by '$', not '.'.
    val nesting = generateSequence(owner) { it.parentDeclaration as? KSClassDeclaration }
      .map { it.simpleName.asString() }.toList().asReversed()
    if (nesting.isEmpty()) return null
    val pkg = owner.packageName.asString()
    val binaryOwner = (if (pkg.isBlank()) "" else "$pkg.") + nesting.joinToString("\$")
    return "$binaryOwner.$method"
  }
  val file = fn.containingFile ?: return null
  val pkg = fn.packageName.asString()
  return (if (pkg.isBlank()) "" else "$pkg.") + facadeClassName(file) + "." + method
}

/** Kotlin's top-level file facade class name: `@file:JvmName("X")` → `X`, else `<FileName>` sanitized to a
 *  valid identifier, first char upper-cased, with the `Kt` suffix (`home-screen.kt` → `Home_screenKt`). */
private fun facadeClassName(file: KSFile): String {
  (file.annotations.firstOrNull { it.shortName.asString() == "JvmName" }?.arg("name") as? String)
    ?.takeIf { it.isNotBlank() }
    ?.let { return it }
  val base = file.fileName.substringBeforeLast('.')
  val sanitized = base.map { if (it.isLetterOrDigit() || it == '_') it else '_' }.joinToString("")
  return sanitized.replaceFirstChar { it.uppercaseChar() } + "Kt"
}

/**
 * The canonical, dot-separated fully-qualified name including every enclosing class — e.g. a nested
 * `Outer.Inner` resolves to `pkg.Outer.Inner`, not the bare `pkg.Inner`. Built by walking
 * `parentDeclaration` rather than reading [KSDeclaration.qualifiedName], whose nested-class result is
 * KSP-version-dependent (some resolvers drop the enclosing class from a source-scanned declaration),
 * so the NavKey scan and the `@NavDestination`/`@NavEdge` route resolution can disagree on the same
 * class and emit a duplicate orphan node. This derivation makes both paths agree.
 */
private fun KSClassDeclaration.canonicalFqn(): String? {
  val nesting = generateSequence(this) { it.parentDeclaration as? KSClassDeclaration }
    .map { it.simpleName.asString() }.toList().asReversed()
  if (nesting.isEmpty()) return null
  val pkg = packageName.asString()
  return (if (pkg.isBlank()) "" else "$pkg.") + nesting.joinToString(".")
}

private fun KSAnnotated.annotationsNamed(fqn: String): List<KSAnnotation> = annotations.filter {
  it.qualifiedName() ==
    fqn
}.toList()

private fun KSAnnotation.qualifiedName(): String? =
  annotationType.resolve().declaration.qualifiedName?.asString()

/** `@Transient`/`@SerialName` target PROPERTY — check the property and (for `@get:`) its getter. */
private fun KSPropertyDeclaration.isTransient(): Boolean =
  hasAnnotation("kotlinx.serialization.Transient") ||
    getter?.hasAnnotation("kotlinx.serialization.Transient") == true

private fun KSPropertyDeclaration.serialName(): String? =
  serialNameOf(this) ?: getter?.let(::serialNameOf)

private fun serialNameOf(annotated: KSAnnotated): String? = annotated.annotations.firstOrNull {
  it.qualifiedName() ==
    "kotlinx.serialization.SerialName"
}
  ?.arg("value") as? String

private fun KSAnnotated.hasAnnotation(fqn: String): Boolean = annotations.any {
  it.qualifiedName() ==
    fqn
}

private fun KSAnnotation.typeArgFqn(name: String): String? {
  val decl = (arg(name) as? KSType)?.declaration ?: return null
  return (decl as? KSClassDeclaration)?.canonicalFqn() ?: decl.qualifiedName?.asString()
}

private fun KSAnnotation.stringArg(name: String): String? = arg(name) as? String

private fun KSAnnotation.boolArg(name: String): Boolean = arg(name) as? Boolean ?: false

private fun KSAnnotation.arg(name: String): Any? = arguments.firstOrNull {
  it.name?.asString() ==
    name
}?.value

/** An annotation-array argument value is an `Array` (per the KSP contract) but some versions surface a `List`. */
private fun Any?.asAnnotationList(): List<KSAnnotation> = when (this) {
  is List<*> -> filterIsInstance<KSAnnotation>()
  is Array<*> -> filterIsInstance<KSAnnotation>()
  else -> emptyList()
}
