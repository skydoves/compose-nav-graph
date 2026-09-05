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
package com.github.skydoves.navgraph.gradle

/**
 * One transition found **lexically** in a single Kotlin source file, with both endpoints still exactly as written
 * (`Profile`, `AppRoute.Detail`, `com.app.Detail`). Resolving a reference to a node id needs the whole route set,
 * which only [InferNavEdgesTask] knows, so this stays deliberately unresolved.
 */
internal data class RawEdge(val fromRef: String, val toRef: String, val line: Int)

/**
 * A `@NavDestination` composable whose body should also be treated as a navigation scope — for apps that pass a
 * back stack / navigator down into the screen instead of wiring transitions in the `entry<T> { }` block.
 *
 * @property routeRef the route this screen renders (already a node id — it comes from the manifest).
 * @property functionName the composable's simple name, used to find the declaration (far more robust than the
 *   line alone, which shifts with annotations); [line] only disambiguates overloads of the same name.
 */
internal data class DestinationHint(val routeRef: String, val functionName: String, val line: Int)

/** Everything [scanKotlinFile] recovers from one file: enough for the caller to resolve [edges] to node ids. */
internal data class FileScan(
  val packageName: String,
  /** Simple name (or `as` alias) → fully-qualified name; star imports are not listed (see the caller's fallback). */
  val imports: Map<String, String>,
  val edges: List<RawEdge>,
  /**
   * Every capitalised reference appearing anywhere inside a navigation scope, paired with that scope's route.
   *
   * Used **only** to corroborate a `@NavEdge` whose transition happens through something this scanner cannot
   * classify as a navigation call — `startActivity(Intent(context, DetailActivity::class.java))`, a helper, a custom
   * wrapper. Seeing the target named inside the source screen's block is enough to know the annotation is not dead.
   * Never used to create an edge: that would trade this scanner's precision for guesswork.
   */
  val mentions: List<RawEdge>,
)

/**
 * The navigation call sites KSP structurally cannot see. `NavGraphProcessor` reads declarations only — Kotlin
 * Symbol Processing exposes no function bodies — so `entry<Home> { … backStack.add(Feed) }` is invisible to it and
 * every transition has to be hand-written as a `@NavEdge`. This recovers those transitions from the source text.
 *
 * It is a **lexical** scanner, not a parser or a type resolver: it masks comments and string literals, tracks brace
 * depth to delimit scopes, and matches navigation calls by **method name** plus a route reference the caller can
 * resolve. Matching by method name (never by receiver name) is what makes it work across styles — the sample calls
 * `backStack.add(Feed)` while the KotlinConf app calls `navigator.add(SessionScreen(it))` through its own wrapper.
 *
 * The precision comes from the caller, not from here: an extracted reference that does not resolve to a known route
 * is **dropped**, never guessed, and never turned into a node. That is why the edges it produces are published as
 * `EdgeConfidence.INFERRED` rather than `ANNOTATED`.
 *
 * Known boundary (by design): a target that is not written literally at the call site — `backStack.add(route)`
 * where `route` is a variable or a function return — cannot be recovered lexically and is silently skipped.
 */
internal object KotlinNavScanner {

  /**
   * Method names treated as "this navigates". Deliberately broad, because the route-set check in
   * [InferNavEdgesTask] is the real filter: `uriHandler.openUri(URLs.TWITTER)` never matches a route, and
   * `rememberNavBackStack(Home)` is not in this set at all (it declares the start destination, not a transition).
   */
  val DEFAULT_NAV_CALLS: Set<String> = setOf(
    "add",
    "addAll",
    "set",
    "setAll",
    "navigate",
    "replaceAll",
    "replace",
    "push",
  )

  private val PACKAGE =
    Regex("""^[ \t]*package[ \t]+([A-Za-z_][A-Za-z0-9_.]*)""", RegexOption.MULTILINE)

  // Groups: 1 = path, 2 = ".*" when a star import, 3 = the `as` alias.
  private val IMPORT = Regex(
    """^[ \t]*import[ \t]+([A-Za-z_][A-Za-z0-9_.]*?)(\.\*)?(?:[ \t]+as[ \t]+([A-Za-z_][A-Za-z0-9_]*))?[ \t]*$""",
    RegexOption.MULTILINE,
  )

  /** `entry<Home>`, `entry<Home>(metadata = …)`, and Navigation 2's typed `composable<Home>`. */
  private val ENTRY_SCOPE = Regex("""\b(?:entry|composable)\s*<\s*([A-Za-z_][A-Za-z0-9_.]*)\s*>""")

  /** A type- or object-shaped reference: capitalised, optionally qualified. Feeds [FileScan.mentions]. */
  private val REFERENCE = Regex("""\b[A-Z][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*""")

  /** A UTF-8 byte order mark, which `readText()` keeps at offset 0. */
  private const val BOM = '\uFEFF'

  fun scanKotlinFile(
    source: String,
    navCalls: Set<String> = DEFAULT_NAV_CALLS,
    destinations: List<DestinationHint> = emptyList(),
  ): FileScan {
    val masked = maskLiterals(source)
    val lineStarts = lineStarts(masked)
    val scopes = entryScopes(masked) + destinationScopes(masked, lineStarts, destinations)
    return FileScan(
      packageName = PACKAGE.find(masked)?.groupValues?.get(1).orEmpty(),
      imports = imports(masked),
      edges = if (scopes.isEmpty() || navCalls.isEmpty()) {
        emptyList()
      } else {
        navCallEdges(masked, scopes, navCalls, lineStarts)
      },
      mentions = if (scopes.isEmpty()) emptyList() else scopeMentions(masked, scopes, lineStarts),
    )
  }

  private fun imports(masked: String): Map<String, String> = buildMap {
    IMPORT.findAll(masked).forEach { match ->
      val (path, star, alias) = match.destructured
      if (star.isNotEmpty()) return@forEach
      val simple = alias.ifEmpty { path.substringAfterLast('.') }
      if (simple.isNotEmpty()) put(simple, path)
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Scopes
  // ---------------------------------------------------------------------------------------------

  /** A region of the file whose navigation calls all originate from [routeRef]. */
  private data class Scope(val routeRef: String, val body: IntRange)

  private fun entryScopes(masked: String): List<Scope> =
    ENTRY_SCOPE.findAll(masked).mapNotNull { match ->
      blockAfter(masked, match.range.last + 1)?.let { Scope(match.groupValues[1], it) }
    }.toList()

  /**
   * The body of each `@NavDestination` composable. Located by `fun <name>` (the occurrence nearest the manifest's
   * line, so overloads and shifted annotations both resolve), then bounded to that declaration by [functionBody].
   */
  private fun destinationScopes(
    masked: String,
    lineStarts: List<Int>,
    destinations: List<DestinationHint>,
  ): List<Scope> = destinations.mapNotNull { hint ->
    // Tolerates `fun <T : Bound<T>> Name(`, `fun Receiver.Name(`, and the plain `fun Name(`.
    val declaration = Regex("""\bfun\b""")
      .findAll(masked)
      .mapNotNull { match -> declarationOf(masked, match.range.last + 1, hint.functionName) }
      .minByOrNull { kotlin.math.abs(lineOf(lineStarts, it) - hint.line) }
      ?: return@mapNotNull null
    val afterParameters = skipBalanced(masked, declaration, '(', ')') ?: return@mapNotNull null
    functionBody(masked, afterParameters)?.let { Scope(hint.routeRef, it) }
  }

  /**
   * The index of `(` opening the parameter list, when the `fun` at [from] declares [name] — after an optional
   * `<…>` type-parameter list (balanced, so `<T : Bound<T>>` is handled) and an optional extension receiver.
   */
  private fun declarationOf(masked: String, from: Int, name: String): Int? {
    var i = skipSpace(masked, from)
    if (masked.getOrNull(i) ==
      '<'
    ) {
      i = skipSpace(masked, skipBalanced(masked, i, '<', '>') ?: return null)
    }
    // The receiver, if any, is part of the same dotted chain as the name; take the last segment.
    var last: String? = null
    var lastAt = i
    while (true) {
      val segment = readIdentifier(masked, i) ?: break
      last = segment
      lastAt = i
      i = skipSpace(masked, i + segment.length)
      if (masked.getOrNull(i) != '.') break
      i = skipSpace(masked, i + 1)
    }
    if (last != name) return null
    return masked.indexOf('(', lastAt).takeIf {
      it >= 0 &&
        it == skipSpace(masked, lastAt + last.length)
    }
  }

  /**
   * The region of a function that its navigation calls can live in, given [afterParameters] (just past the `)`).
   *
   * A block body is its `{ … }`. An expression body is bounded to the expression itself — the identifier chain plus
   * at most one `( … )` argument list and one trailing `{ … }` lambda — so `= Column { … }` and
   * `= Foo(onClick = { … })` are both covered while `= Text(state.title)` correctly yields nothing. Without that
   * bound the search runs past the declaration and attributes the *next* function's body to this route.
   *
   * A declaration with **no** body at all — `expect fun` on a KMP `commonMain` screen, `external fun`, an
   * interface method — has neither `{` nor `=`, so the scan must also stop at anything that can only begin the
   * *next* declaration. Otherwise it adopts that declaration's block and publishes a transition this screen
   * never makes, which is worse than the missing edge returning null costs us.
   */
  private fun functionBody(masked: String, afterParameters: Int): IntRange? {
    // Skip a return type: anything up to the `{` or `=` that opens the body.
    var i = afterParameters
    while (i < masked.length) {
      val c = masked[i]
      if (c == '{' || c == '=' || c == '}' || c == '@') break
      if (startsDeclaration(masked, i)) return null
      i++
    }
    when (masked.getOrNull(i)) {
      '{' -> {
        val end = skipBalanced(masked, i, '{', '}') ?: return null
        return (i + 1) until (end - 1)
      }

      '=' -> {
        var cursor = skipSpace(masked, i + 1)
        val start = cursor
        while (readIdentifier(masked, cursor) != null) {
          cursor += readIdentifier(masked, cursor)!!.length
          if (masked.getOrNull(cursor) != '.') break
          cursor++
        }
        if (masked.getOrNull(cursor) ==
          '('
        ) {
          cursor = skipBalanced(masked, cursor, '(', ')') ?: return null
        }
        val braceAt = skipSpace(masked, cursor)
        if (masked.getOrNull(braceAt) ==
          '{'
        ) {
          cursor = skipBalanced(masked, braceAt, '{', '}') ?: return null
        }
        return if (cursor > start) start until cursor else null
      }

      else -> return null
    }
  }

  /**
   * The `{ … }` block that **immediately** follows [from], allowing only whitespace and one balanced `( … )` (an
   * `entry<T>(metadata = …)` argument list) in between. Returns the range between the braces.
   *
   * "Immediately" is the whole point: a scan that kept looking would attach an unrelated later block — a `Box { }`
   * on the next line, say — to a route whose `entry<T>` had no lambda at all.
   */
  private fun blockAfter(masked: String, from: Int): IntRange? {
    var i = skipSpace(masked, from)
    if (masked.getOrNull(i) ==
      '('
    ) {
      i = skipSpace(masked, skipBalanced(masked, i, '(', ')') ?: return null)
    }
    if (masked.getOrNull(i) != '{') return null
    val end = skipBalanced(masked, i, '{', '}') ?: return null
    return (i + 1) until (end - 1)
  }

  /** Index just past the [close] matching the [open] at [start], or null when unbalanced. */
  private fun skipBalanced(masked: String, start: Int, open: Char, close: Char): Int? {
    var depth = 0
    var i = start
    while (i < masked.length) {
      when (masked[i]) {
        open -> depth++

        close -> {
          depth--
          if (depth == 0) return i + 1
        }
      }
      i++
    }
    return null
  }

  // ---------------------------------------------------------------------------------------------
  // Navigation calls
  // ---------------------------------------------------------------------------------------------

  private fun navCallEdges(
    masked: String,
    scopes: List<Scope>,
    navCalls: Set<String>,
    lineStarts: List<Int>,
  ): List<RawEdge> {
    val call = Regex("""\b(?:${navCalls.joinToString("|") { Regex.escape(it) }})\s*\(""")
    return call.findAll(masked).mapNotNull { match ->
      val openParen = match.range.last
      val scope = innermostScope(scopes, openParen) ?: return@mapNotNull null
      val target = firstArgumentRef(masked, openParen) ?: return@mapNotNull null
      RawEdge(scope.routeRef, target, lineOf(lineStarts, openParen))
    }.toList()
  }

  /** Every capitalised (i.e. type- or object-shaped) reference inside a scope — see [FileScan.mentions]. */
  private fun scopeMentions(
    masked: String,
    scopes: List<Scope>,
    lineStarts: List<Int>,
  ): List<RawEdge> = REFERENCE.findAll(masked).mapNotNull { match ->
    val at = match.range.first
    val scope = innermostScope(scopes, at) ?: return@mapNotNull null
    RawEdge(scope.routeRef, match.value, lineOf(lineStarts, at))
  }.distinctBy { it.fromRef to it.toRef }.toList()

  /** The innermost enclosing scope, so a nested `entry<B> { }` attributes to B rather than to its parent. */
  private fun innermostScope(scopes: List<Scope>, offset: Int): Scope? =
    scopes.filter { offset in it.body }.minByOrNull { it.body.last - it.body.first }

  /**
   * The leading dotted identifier of the call's first argument — `Profile` in `add(Profile(userId))`,
   * `ScheduleScreen` in `set(ScheduleScreen)`, `Detail` in `navigate(route = Detail)`. Anything that is not a plain
   * reference (`add(it.route)`, `add(routes.first())`) still returns a string here, and is dropped by the caller
   * because it will not match a known route.
   */
  private fun firstArgumentRef(masked: String, openParen: Int): String? {
    var i = skipSpace(masked, openParen + 1)
    // A named argument (`navigate(route = Detail)`) — step over the name, but not over an `==` comparison.
    val name = readIdentifier(masked, i)
    if (name != null) {
      val afterName = skipSpace(masked, i + name.length)
      if (masked.getOrNull(afterName) == '=' && masked.getOrNull(afterName + 1) != '=') {
        i = skipSpace(masked, afterName + 1)
      }
    }
    val head = readIdentifier(masked, i) ?: return null
    val reference = StringBuilder(head)
    var cursor = i + head.length
    // A chain may be wrapped or commented across lines (`AppRoute\n  .Detail`); comments are already blanked.
    while (true) {
      val dot = skipSpace(masked, cursor)
      if (masked.getOrNull(dot) != '.') break
      val segmentAt = skipSpace(masked, dot + 1)
      val segment = readIdentifier(masked, segmentAt) ?: break
      reference.append('.').append(segment)
      cursor = segmentAt + segment.length
    }
    return reference.toString()
  }

  /** Keywords that can only open a new declaration, never appear inside a return type. */
  private val DECLARATION_KEYWORDS =
    listOf("fun", "val", "var", "class", "object", "interface", "expect", "actual")

  /** Whether [at] is the start of a whole-word [DECLARATION_KEYWORDS] token — the boundary checks keep the `fun`
   *  inside a return type like `KFunction0` from ending the scan. */
  private fun startsDeclaration(masked: String, at: Int): Boolean {
    fun isWordChar(c: Char?) = c != null && (c.isLetterOrDigit() || c == '_')
    if (isWordChar(masked.getOrNull(at - 1))) return false
    return DECLARATION_KEYWORDS.any { keyword ->
      masked.startsWith(keyword, at) && !isWordChar(masked.getOrNull(at + keyword.length))
    }
  }

  private fun readIdentifier(masked: String, at: Int): String? {
    if (at >= masked.length || !(masked[at].isLetter() || masked[at] == '_')) return null
    var end = at
    while (end < masked.length && (masked[end].isLetterOrDigit() || masked[end] == '_')) end++
    return masked.substring(at, end)
  }

  private fun skipSpace(masked: String, at: Int): Int {
    var i = at
    while (i < masked.length && masked[i].isWhitespace()) i++
    return i
  }

  // ---------------------------------------------------------------------------------------------
  // Line mapping
  // ---------------------------------------------------------------------------------------------

  private fun lineStarts(text: String): List<Int> = buildList {
    add(0)
    text.forEachIndexed { index, c -> if (c == '\n') add(index + 1) }
  }

  /** 1-based line containing [offset]. */
  private fun lineOf(lineStarts: List<Int>, offset: Int): Int {
    val found = lineStarts.binarySearch(offset)
    return if (found >= 0) found + 1 else -found - 1
  }

  // ---------------------------------------------------------------------------------------------
  // Literal masking
  // ---------------------------------------------------------------------------------------------

  /**
   * The source with every comment, string, and character literal blanked to spaces (newlines kept), so brace
   * counting and identifier matching can never be thrown off by a `{` in a comment or a `"` in a string. Offsets
   * and line numbers are preserved exactly, so the result indexes identically to the input.
   *
   * `${ … }` template contents are deliberately left as code — they *are* code — while the `${` and its `}` are
   * blanked, keeping braces balanced for the scope scan.
   */
  internal fun maskLiterals(source: String): String =
    // A UTF-8 BOM survives readText() and would keep `^package` from ever matching; blank it, keeping the offset.
    Masker(if (source.startsWith(BOM)) " " + source.substring(1) else source).run()

  private class Masker(private val source: String) {
    private val out = source.toCharArray()
    private var i = 0

    fun run(): String {
      code(insideTemplate = false)
      return String(out)
    }

    private fun code(insideTemplate: Boolean) {
      var depth = 0
      while (i < source.length) {
        val c = source[i]
        when {
          c == '/' && peek(1) == '/' -> lineComment()

          c == '/' && peek(1) == '*' -> blockComment()

          c == '"' && peek(1) == '"' && peek(2) == '"' -> rawString()

          c == '"' -> string()

          c == '`' -> backtickName()

          c == '\'' -> charLiteral()

          c == '{' -> {
            depth++
            i++
          }

          c == '}' -> {
            if (insideTemplate && depth == 0) return
            depth--
            i++
          }

          else -> i++
        }
      }
    }

    private fun lineComment() {
      val start = i
      while (i < source.length && source[i] != '\n') i++
      blank(start, i)
    }

    private fun blockComment() {
      val start = i
      i += 2
      var depth = 1
      while (i < source.length && depth > 0) {
        when {
          source[i] == '/' && peek(1) == '*' -> {
            depth++
            i += 2
          }

          source[i] == '*' && peek(1) == '/' -> {
            depth--
            i += 2
          }

          else -> i++
        }
      }
      blank(start, i)
    }

    private fun charLiteral() {
      val start = i
      i++
      while (i < source.length && source[i] != '\'' && source[i] != '\n') {
        i += if (source[i] == '\\') 2 else 1
      }
      if (i < source.length && source[i] == '\'') i++
      blank(start, i)
    }

    /**
     * A backtick-quoted identifier (`` `isn't ready` ``), consumed whole. Kotlin allows an apostrophe inside one,
     * and without this it would open a phantom char literal and blank the rest of the line — taking any `{` on it
     * with it, and silently truncating the enclosing scope.
     */
    private fun backtickName() {
      val start = i
      i++
      while (i < source.length && source[i] != '`' && source[i] != '\n') i++
      if (i < source.length && source[i] == '`') i++
      blank(start, i)
    }

    private fun string() {
      var segment = i
      i++
      while (i < source.length) {
        when {
          source[i] == '\\' -> i = minOf(i + 2, source.length)

          // An unterminated literal can only come from unparseable source; stop at the line to stay in step.
          source[i] == '\n' -> {
            blank(segment, i)
            return
          }

          source[i] == '"' -> {
            i++
            blank(segment, i)
            return
          }

          source[i] == '$' && peek(1) == '{' -> segment = template(segment)

          else -> i++
        }
      }
      blank(segment, i)
    }

    private fun rawString() {
      var segment = i
      i += 3
      while (i < source.length) {
        when {
          source[i] == '"' && peek(1) == '"' && peek(2) == '"' -> {
            i += 3
            while (i < source.length && source[i] == '"') i++
            blank(segment, i)
            return
          }

          source[i] == '$' && peek(1) == '{' -> segment = template(segment)

          else -> i++
        }
      }
      blank(segment, i)
    }

    /** Blank the literal text up to `${`, blank the delimiters themselves, and let [code] run inside. */
    private fun template(segment: Int): Int {
      blank(segment, i)
      blank(i, i + 2)
      i += 2
      code(insideTemplate = true)
      if (i < source.length && source[i] == '}') {
        blank(i, i + 1)
        i++
      }
      return i
    }

    private fun peek(offset: Int): Char? = source.getOrNull(i + offset)

    private fun blank(from: Int, to: Int) {
      for (k in from until minOf(to, out.size)) {
        if (out[k] != '\n') out[k] = ' '
      }
    }
  }
}
