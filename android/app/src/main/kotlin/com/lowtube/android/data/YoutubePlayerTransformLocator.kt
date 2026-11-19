package com.lowtube.android.data

/** Finds named player functions referenced by common YouTube signature and n-transform call sites. */
internal object YoutubePlayerTransformLocator {
    data class Functions(
        val signature: String?,
        val throttling: String?,
        val signatureArgumentPrefix: String? = null,
        val urlThrottling: String? = null,
    )
    data class ExecutableFunctions(
        val functions: Functions,
        val signatureProgram: String?,
        val throttlingProgram: String?,
        val markerCandidates: List<MarkerCandidate> = emptyList(),
        val markerCandidateNames: List<String> = emptyList(),
        val urlThrottlingProgram: String? = null,
        val requiredDependencies: Set<String> = emptySet(),
    )
    data class MarkerCandidate(val name: String, val program: String, val isAsync: Boolean)
    internal data class UrlTransformDiagnosticCandidate(val name: String, val program: String)

    /** Builds a small, opt-in-only set for executing candidates against synthetic URLs. */
    internal fun locateUrlTransformDiagnosticCandidates(
        source: String,
        requiredDependencies: Set<String> = emptySet(),
        onDiagnosticStage: ((String) -> Unit)? = null,
    ): List<UrlTransformDiagnosticCandidate> {
        require(source.length <= YoutubePlayerScriptCache.MAX_SCRIPT_BYTES) {
            "YouTube player script is larger than 8 MB."
        }
        require(requiredDependencies.size <= MAX_REQUIRED_DEPENDENCIES && requiredDependencies.all(bareIdentifier::matches)) {
            "YouTube player helper identifiers are invalid."
        }
        val index = buildDeclarationIndex(source, onDiagnosticStage)
        val dottedLeaves = index.declarations.keys.filter { it.contains('.') }.groupBy { it.substringAfterLast('.') }
        val resolvedRequiredDependencies = requiredDependencies.map { name ->
            if (name in index.declarations) name
            else dottedLeaves[name]?.singleOrNull()
                ?: throw java.io.IOException("A required YouTube player helper could not be isolated.")
        }.toSet()
        return findReferencedCandidates(source, urlThrottlingCallSites, index.declarations.keys)
            .take(MAX_URL_DIAGNOSTIC_CANDIDATES)
            .mapNotNull { name ->
                runCatching {
                    UrlTransformDiagnosticCandidate(
                        name,
                        buildExecutableProgram(
                            source,
                            index,
                            name,
                            onDiagnosticStage,
                            includeUniqueDottedAliases = true,
                            maxDeclarations = MAX_URL_EXECUTABLE_DECLARATIONS,
                            maxProgramBytes = MAX_URL_EXECUTABLE_PROGRAM_BYTES,
                            additionalRootNames = resolvedRequiredDependencies,
                        ),
                    )
                }.onFailure { failure ->
                    val reason = failure.message.orEmpty().replace(Regex("[^A-Za-z0-9 _-]"), " ").take(96)
                    onDiagnosticStage?.invoke("locatorUrlDiagnosticProgramFailed; name=$name; reason=$reason")
                }.getOrNull()
            }
    }

    private const val IDENTIFIER = "[\$A-Za-z_][\$A-Za-z0-9_]{0,127}"
    private const val FUNCTION_EXPRESSION = "$IDENTIFIER(?:\\.$IDENTIFIER)*"
    private val functionName = Regex("^$FUNCTION_EXPRESSION$")
    private val bareIdentifier = Regex("^$IDENTIFIER$")
    private val signatureCallSites = listOf(
        Regex("\\.sig\\s*\\|\\|\\s*($FUNCTION_EXPRESSION)\\s*\\("),
        Regex("[\"']signature[\"']\\s*,\\s*($FUNCTION_EXPRESSION)\\s*\\("),
        Regex("\\.set\\(\\s*[\"']signature[\"']\\s*,\\s*($FUNCTION_EXPRESSION)\\s*\\("),
        Regex("\\bm\\s*=\\s*($FUNCTION_EXPRESSION)\\(\\s*decodeURIComponent\\(\\s*h\\.s\\s*\\)\\s*\\)"),
        Regex("\\bc&&\\(c=($FUNCTION_EXPRESSION)\\(decodeURIComponent\\(c\\)\\)\\)"),
        // Guarded call sites can pass a numeric mode before the encoded challenge.
        Regex("\\b$IDENTIFIER&&\\($IDENTIFIER=($IDENTIFIER)\\((?:([0-9]+,))?decodeURIComponent\\($IDENTIFIER\\)\\)"),
        // Signature deciphers commonly begin by splitting their input into characters.
        Regex("($IDENTIFIER)\\s*=\\s*function\\(\\s*($IDENTIFIER)\\s*\\)\\s*\\{\\s*\\2\\s*=\\s*\\2\\.split\\([\"']{2}\\)"),
    )
    private val throttlingCallSites = listOf(
        Regex("\\.get\\(\\s*[\"']n[\"']\\s*\\)\\)\\s*&&\\s*\\([^=]{1,80}=\\s*($FUNCTION_EXPRESSION)\\s*\\("),
        Regex("[\"']n[\"']\\s*,\\s*($FUNCTION_EXPRESSION)\\s*\\("),
    )
    private val urlThrottlingCallSites = listOf(
        Regex("\\.url\\s*&&\\s*\\(\\s*$IDENTIFIER\\.url\\s*=\\s*($FUNCTION_EXPRESSION)\\s*\\(\\s*$IDENTIFIER\\.url\\s*\\)\\s*\\)"),
    )
    private val urlNGetter = Regex("\\.get\\s*\\(\\s*[\"']n[\"']\\s*\\)")
    private val urlPathNRewrite = Regex("""\\?/n\\?/""")
    private val throttlingMarker = Regex("\\.set\\(\\s*[\"']alr[\"']\\s*,\\s*[\"']yes[\"']\\s*\\);")
    private val functionAssignment = Regex("\\b($IDENTIFIER)\\s*=\\s*function\\s*\\(")
    private val markerContinuation = Regex("^\\s*$IDENTIFIER&&")
    private const val MARKER_LOOKBACK_CHARS = 64 * 1024
    private const val MAX_URL_DIAGNOSTIC_CANDIDATES = 8

    fun locate(source: String, onDiagnosticStage: ((String) -> Unit)? = null): Functions {
        require(source.length <= YoutubePlayerScriptCache.MAX_SCRIPT_BYTES) { "YouTube player script is larger than 8 MB." }
        val index = buildDeclarationIndex(source, onDiagnosticStage)
        return locateFunctions(source, index, onDiagnosticStage, allowUnverifiedMarker = true)
    }

    fun locateExecutable(
        source: String,
        onDiagnosticStage: ((String) -> Unit)? = null,
        includeMarkerCandidates: Boolean = false,
        includeMarkerPrograms: Boolean = includeMarkerCandidates,
        diagnosticIdentifiers: Set<String> = emptySet(),
        requiredDependencies: Set<String> = emptySet(),
    ): ExecutableFunctions {
        require(source.length <= YoutubePlayerScriptCache.MAX_SCRIPT_BYTES) { "YouTube player script is larger than 8 MB." }
        require(requiredDependencies.size <= MAX_REQUIRED_DEPENDENCIES && requiredDependencies.all(bareIdentifier::matches)) {
            "YouTube player helper identifiers are invalid."
        }
        val index = buildDeclarationIndex(source, onDiagnosticStage)
        if (includeMarkerCandidates) {
            logChallengeMarkerContexts(source, index, onDiagnosticStage)
        }
        val resolvedRequiredDependencies = if (requiredDependencies.isEmpty()) emptySet() else {
            val dottedLeaves = index.declarations.keys.filter { it.contains('.') }.groupBy { it.substringAfterLast('.') }
            requiredDependencies.map { name ->
                if (name in index.declarations) name
                else dottedLeaves[name]?.singleOrNull()
                    ?: throw java.io.IOException("A required YouTube player helper could not be isolated.")
            }.toSet()
        }
        val functions = locateFunctions(source, index, onDiagnosticStage, allowUnverifiedMarker = false)
        return ExecutableFunctions(
            functions = functions,
            signatureProgram = functions.signature?.let { buildExecutableProgram(source, index, it, onDiagnosticStage) },
            throttlingProgram = functions.throttling?.let { buildExecutableProgram(source, index, it, onDiagnosticStage) },
            urlThrottlingProgram = functions.urlThrottling?.let { name ->
                var program: String? = null
                var lastFailure: Exception? = null
                val argumentLimits = if (requiredDependencies.isEmpty()) {
                    URL_FUNCTION_ARGUMENT_LIMITS
                } else {
                    listOf(MAX_URL_FUNCTION_ARGUMENTS, 32)
                }
                for (argumentLimit in argumentLimits) {
                    try {
                        program = buildExecutableProgram(
                            source,
                            index,
                            name,
                            onDiagnosticStage,
                            includeUniqueDottedAliases = true,
                            maxDeclarations = MAX_URL_EXECUTABLE_DECLARATIONS,
                            maxProgramBytes = MAX_URL_EXECUTABLE_PROGRAM_BYTES,
                            maxUrlFunctionArguments = argumentLimit,
                            diagnosticIdentifiers = diagnosticIdentifiers,
                            additionalRootNames = resolvedRequiredDependencies,
                        )
                        break
                    } catch (failure: Exception) {
                        lastFailure = failure
                        val reason = failure.message.orEmpty().replace(Regex("[^A-Za-z0-9 _-]"), " ").take(96)
                        onDiagnosticStage?.invoke(
                            "locatorUrlTransformProgramRetry; name=$name; functionArguments=$argumentLimit; reason=$reason",
                        )
                    }
                }
                if (program == null) {
                    val reason = lastFailure?.message.orEmpty().replace(Regex("[^A-Za-z0-9 _-]"), " ").take(120)
                    onDiagnosticStage?.invoke("locatorUrlTransformProgramFailed; name=$name; reason=$reason")
                }
                program
            },
            markerCandidates = if (includeMarkerPrograms) {
                findMarkerCandidateNames(source, index).mapNotNull { name ->
                    val declaration = index.declarations.getValue(name)
                    val expressionStart = declaration.range.expressionStartToken
                    val isAsync = isWord(source, index.tokens.getOrNull(expressionStart), "async")
                onDiagnosticStage?.invoke(
                    "locatorMarkerCandidate; name=$name; async=$isAsync; " +
                        "preview=${declarationTokenPreview(source, index, name).orEmpty()}",
                )
                    try {
                        MarkerCandidate(
                            name,
                            buildExecutableProgram(
                                source,
                                index,
                                name,
                                onDiagnosticStage,
                                includeDataDependencies = true,
                                maxDeclarations = MAX_DIAGNOSTIC_MARKER_DECLARATIONS,
                                maxProgramBytes = MAX_DIAGNOSTIC_MARKER_PROGRAM_BYTES,
                            ),
                            isAsync,
                        )
                    } catch (failure: Exception) {
                        val reason = failure.message.orEmpty().replace(Regex("[^A-Za-z0-9 _-]"), " ").take(120)
                        onDiagnosticStage?.invoke("locatorMarkerProgramFailed; name=$name; reason=$reason")
                        null
                    }
                }
            } else emptyList(),
            markerCandidateNames = if (includeMarkerCandidates) {
                findMarkerCandidateNames(source, index, includeAsyncCandidates = false)
            } else emptyList(),
            requiredDependencies = requiredDependencies.toSet(),
        )
    }

    /**
     * Finds bounded string-to-string function shapes for opt-in player diagnostics.
     * Candidates are not production transforms until a real call site validates them.
     */
    internal fun findStringOperationCandidates(
        source: String,
        limit: Int = 48,
        onDiagnosticStage: ((String) -> Unit)? = null,
    ): List<String> {
        require(source.length <= YoutubePlayerScriptCache.MAX_SCRIPT_BYTES) {
            "YouTube player script is larger than 8 MB."
        }
        require(limit in 1..MAX_STRING_TRANSFORM_CANDIDATES) { "The player candidate limit is invalid." }
        val index = buildDeclarationIndex(source, null)
        var splitCalls = 0
        var joinCalls = 0
        for (tokenIndex in 0..index.tokens.size - 5) {
            if (!isPunctuation(source, index.tokens[tokenIndex], ".") ||
                !isPunctuation(source, index.tokens[tokenIndex + 2], "(")
            ) continue
            val method = index.tokens[tokenIndex + 1]
            val argument = index.tokens[tokenIndex + 3]
            if (argument.kind != TOKEN_OPAQUE || source.substring(argument.start, argument.end) !in setOf("\"\"", "''") ||
                !isPunctuation(source, index.tokens[tokenIndex + 4], ")")
            ) continue
            val methodName = when {
                isWord(source, method, "split") -> "split"
                isWord(source, method, "join") -> "join"
                else -> continue
            }
            if (methodName == "split") splitCalls++ else joinCalls++
            if ((splitCalls + joinCalls) <= MAX_STRING_OPERATION_CONTEXTS) {
                onDiagnosticStage?.invoke(
                    "locatorStringOperation; kind=$methodName; owners=${enclosingDeclarationNames(index, tokenIndex).joinToString(",")}",
                )
            }
        }
        onDiagnosticStage?.invoke("locatorStringOperationSummary; split=$splitCalls; join=$joinCalls")
        return index.declarations.entries.mapNotNull { (name, declaration) ->
            if (!functionName.matches(name) || declaration.kind !in STRING_TRANSFORM_DECLARATION_KINDS) return@mapNotNull null
            val indexedObjectMethod = declaration.kind == DeclarationKind.OBJECT_ASSIGNMENT && declaration.memberRange != null
            if (declaration.kind == DeclarationKind.OBJECT_ASSIGNMENT && !indexedObjectMethod) return@mapNotNull null
            val range = declaration.memberRange ?: declaration.range
            if (range.startToken !in index.tokens.indices || range.endToken !in index.tokens.indices) return@mapNotNull null
            val charLength = index.tokens[range.endToken].end - index.tokens[range.startToken].start
            if (charLength !in 1..MAX_STRING_TRANSFORM_CHARS) return@mapNotNull null
            val expressionStart = range.expressionStartToken.takeIf { it >= 0 } ?: range.startToken
            if (!indexedObjectMethod && !isFunctionOrArrowAt(source, index.tokens, index.matchingTokens, expressionStart)) return@mapNotNull null
            var hasEmptySplit = false
            var hasEmptyJoin = false
            for (tokenIndex in range.startToken..range.endToken - 3) {
                if (!isPunctuation(source, index.tokens.getOrNull(tokenIndex), ".") ||
                    !isPunctuation(source, index.tokens.getOrNull(tokenIndex + 2), "(")
                ) continue
                val method = index.tokens.getOrNull(tokenIndex + 1) ?: continue
                if (!isWord(source, method, "split") && !isWord(source, method, "join")) continue
                val argument = index.tokens.getOrNull(tokenIndex + 3) ?: continue
                if (argument.kind != TOKEN_OPAQUE || source.substring(argument.start, argument.end) !in setOf("\"\"", "''")) continue
                if (!isPunctuation(source, index.tokens.getOrNull(tokenIndex + 4), ")")) continue
                if (isWord(source, method, "split")) hasEmptySplit = true else hasEmptyJoin = true
                if (hasEmptySplit && hasEmptyJoin) break
            }
            if (hasEmptySplit || hasEmptyJoin) Triple(name, charLength, hasEmptySplit to hasEmptyJoin) else null
        }.sortedWith(compareBy<Triple<String, Int, Pair<Boolean, Boolean>>> { it.second }.thenBy { it.first })
            .take(limit)
            .onEach { candidate ->
                onDiagnosticStage?.invoke(
                    "locatorStringOperationCandidate; name=${candidate.first}; split=${candidate.third.first}; " +
                        "join=${candidate.third.second}; chars=${candidate.second}; " +
                        "preview=${declarationTokenPreview(source, index, candidate.first).orEmpty()}",
                )
            }
            .map { it.first }
    }

    /** Builds one opt-in alr-marker candidate for isolated diagnostics without materializing all candidates. */
    fun locateMarkerCandidate(
        source: String,
        name: String,
        onDiagnosticStage: ((String) -> Unit)? = null,
        diagnosticIdentifiers: Set<String> = emptySet(),
    ): MarkerCandidate? {
        require(source.length <= YoutubePlayerScriptCache.MAX_SCRIPT_BYTES) { "YouTube player script is larger than 8 MB." }
        require(diagnosticIdentifiers.size <= MAX_REQUIRED_DEPENDENCIES && diagnosticIdentifiers.all(bareIdentifier::matches)) {
            "YouTube player diagnostic identifiers are invalid."
        }
        if (!functionName.matches(name)) return null
        val index = buildDeclarationIndex(source, onDiagnosticStage)
        if (name !in findMarkerCandidateNames(source, index)) return null
        val declaration = index.declarations[name] ?: return null
        val expressionStart = declaration.range.expressionStartToken
        val isAsync = isWord(source, index.tokens.getOrNull(expressionStart), "async")
        diagnosticIdentifiers.take(MAX_REQUIRED_DEPENDENCIES).forEach { identifier ->
            val target = index.declarations[identifier]
            onDiagnosticStage?.invoke(
                "locatorDiagnosticIdentifier; root=$name; name=$identifier; indexed=${target?.kind}; " +
                    "preview=${target?.let { declarationTokenPreview(source, index, identifier) }.orEmpty()}",
            )
        }
        return try {
            MarkerCandidate(
                name,
                buildExecutableProgram(
                    source,
                    index,
                    name,
                    onDiagnosticStage,
                    includeDataDependencies = true,
                    maxDeclarations = MAX_DIAGNOSTIC_MARKER_DECLARATIONS,
                    maxProgramBytes = MAX_DIAGNOSTIC_MARKER_PROGRAM_BYTES,
                    diagnosticIdentifiers = diagnosticIdentifiers,
                    additionalRootNames = diagnosticIdentifiers,
                ),
                isAsync,
            )
        } catch (failure: Exception) {
            val reason = failure.message.orEmpty().replace(Regex("[^A-Za-z0-9 _-]"), " ").take(120)
            onDiagnosticStage?.invoke("locatorMarkerProgramFailed; name=$name; reason=$reason")
            null
        }
    }

    private fun findMarkerCandidateNames(
        source: String,
        index: ScriptIndex,
        includeAsyncCandidates: Boolean = true,
    ): List<String> {
        val candidates = LinkedHashSet<String>()
        for (marker in throttlingMarker.findAll(source)) {
            val tokenIndex = tokenIndexAt(index.tokens, marker.range.first)
            if (tokenIndex < 0) continue
            val enclosing = index.declarations.entries.mapNotNull { (name, declaration) ->
                val range = declaration.memberRange ?: declaration.range
                if (tokenIndex < range.startToken || tokenIndex > range.endToken) return@mapNotNull null
                if (declaration.kind == DeclarationKind.OBJECT_ASSIGNMENT && declaration.memberRange == null) return@mapNotNull null
                val expressionStart = declaration.range.expressionStartToken
                if (declaration.kind != DeclarationKind.RAW_FUNCTION &&
                    !isFunctionOrArrowAt(source, index.tokens, index.matchingTokens, expressionStart)
                ) return@mapNotNull null
                val chars = index.tokens[range.endToken].end - index.tokens[range.startToken].start
                if (chars > MAX_MARKER_CANDIDATE_CHARS) return@mapNotNull null
                Triple(name, range, chars)
            }.sortedBy { it.third }
            val shortest = enclosing.firstOrNull() ?: continue
            val canonical = enclosing.firstOrNull { it.second == shortest.second && !it.first.contains('.') } ?: shortest
            val declaration = index.declarations[canonical.first] ?: continue
            val expressionStart = declaration.range.expressionStartToken
            if (!includeAsyncCandidates && isWord(source, index.tokens.getOrNull(expressionStart), "async")) continue
            candidates.add(canonical.first)
            if (candidates.size >= MAX_MARKER_CANDIDATES) break
        }
        return candidates.toList()
    }

    private fun locateFunctions(
        source: String,
        index: ScriptIndex,
        onDiagnosticStage: ((String) -> Unit)?,
        allowUnverifiedMarker: Boolean,
    ): Functions {
        val declarations = index.declarations.keys
        val signatureCandidates = findReferencedCandidates(source, signatureCallSites, declarations)
        val throttlingCandidates = findReferencedCandidates(source, throttlingCallSites, declarations)
        val rawUrlThrottlingCandidates = findReferencedCandidates(source, urlThrottlingCallSites, declarations)
        rawUrlThrottlingCandidates.forEach { name ->
            val declaration = index.declarations[name] ?: return@forEach
            val range = declaration.memberRange ?: declaration.range
            val start = index.tokens.getOrNull(range.startToken)?.start ?: return@forEach
            val end = index.tokens.getOrNull(range.endToken)?.end ?: return@forEach
            val body = source.substring(start, end)
            onDiagnosticStage?.invoke(
                "locatorUrlCandidateShape; name=$name; kind=${declaration.kind}; chars=${body.length}; " +
                    "getN=${urlNGetter.containsMatchIn(body)}; pathN=${urlPathNRewrite.containsMatchIn(body)}",
            )
        }
        val urlThrottlingCandidates = rawUrlThrottlingCandidates
            .filter { name ->
                val declaration = index.declarations[name] ?: return@filter false
                val range = declaration.memberRange ?: declaration.range
                val start = index.tokens.getOrNull(range.startToken)?.start ?: return@filter false
                val end = index.tokens.getOrNull(range.endToken)?.end ?: return@filter false
                val body = source.substring(start, end)
                urlNGetter.containsMatchIn(body) && !urlPathNRewrite.containsMatchIn(body)
            }
        logSignatureLiteralContexts(source, index, onDiagnosticStage)
        logGetNCallsiteContexts(source, index, declarations, onDiagnosticStage)
        logNPropertyContexts(source, index, onDiagnosticStage)
        logNStringLiteralContexts(source, index, onDiagnosticStage)
        logSetNCallsiteContexts(source, index, onDiagnosticStage)
        val markerCandidate = findMarkerReferencedFunction(source, declarations)
        val signature = signatureCandidates.firstOrNull()
        // A nearby `alr=yes` marker can identify some n-transform declarations, but it is not
        // proof that the function is the transform. Never execute that heuristic in the resolver.
        val throttling = throttlingCandidates.firstOrNull()
            ?: if (allowUnverifiedMarker) markerCandidate else null
        onDiagnosticStage?.invoke(
            "locatorCandidates; signature=${signatureCandidates.joinToString(",")}; n=${throttlingCandidates.joinToString(",")}; " +
                "urlN=${urlThrottlingCandidates.joinToString(",")}; marker=$markerCandidate; unverifiedMarkerAllowed=$allowUnverifiedMarker",
        )
        // Detailed source-context logging is intentionally disabled; the callback is metadata-only.
        return Functions(
            signature = signature,
            throttling = throttling,
            signatureArgumentPrefix = findSignatureArgumentPrefix(source, signature, declarations),
            urlThrottling = urlThrottlingCandidates.firstOrNull(),
        )
    }

    private fun buildExecutableProgram(
        source: String,
        index: ScriptIndex,
        rootName: String,
        onDiagnosticStage: ((String) -> Unit)?,
        includeDataDependencies: Boolean = false,
        includeUniqueDottedAliases: Boolean = false,
        maxDeclarations: Int = MAX_EXECUTABLE_DECLARATIONS,
        maxProgramBytes: Int = MAX_EXECUTABLE_PROGRAM_BYTES,
        maxUrlFunctionArguments: Int = MAX_URL_FUNCTION_ARGUMENTS,
        diagnosticIdentifiers: Set<String> = emptySet(),
        additionalRootNames: Set<String> = emptySet(),
    ): String {
        val pending = java.util.ArrayDeque<Pair<String, Boolean>>()
        val prioritizedPending = java.util.ArrayDeque<Pair<String, Boolean>>()
        val visitedNames = HashSet<String>()
        val expandedNames = HashSet<String>()
        val callArgumentDependencyNames = HashSet<String>()
        val constructorArgumentDependencyNames = HashSet<String>()
        val assignedFunctionDependencyNames = HashSet<String>()
        val complexInitializerOwners = HashMap<String, MutableSet<String>>()
        val unresolvedCallArguments = LinkedHashSet<String>()
        val unresolvedCalls = LinkedHashSet<String>()
        val locallyShadowedDeclarations = LinkedHashSet<String>()
        val skippedDeclarations = LinkedHashSet<String>()
        val targetSymbolEvents = ArrayList<String>()
        val dottedAliases = LinkedHashMap<String, String>()
        val globalObjectAliases = LinkedHashMap<String, String>()
        val uniqueDottedDeclarations = if (includeUniqueDottedAliases) {
            index.declarations.keys.asSequence()
                .filter { it.contains('.') }
                .groupBy { it.substringAfterLast('.') }
                .mapNotNull { (leaf, names) -> names.singleOrNull()?.let { leaf to it } }
                .toMap()
        } else emptyMap()
        var skippedComplexInitializers = 0
        var skippedBrowserInitializers = 0
        val selected = LinkedHashMap<SourceRange, IndexedDeclaration>()
        pending.addLast(rootName to false)
        additionalRootNames.forEach { prioritizedPending.addLast(it to true) }
        while (prioritizedPending.isNotEmpty() || pending.isNotEmpty()) {
            val (name, shallow) = if (prioritizedPending.isNotEmpty()) prioritizedPending.removeFirst() else pending.removeFirst()
            val declaration = index.declarations[name]
                ?: throw java.io.IOException("YouTube player transform code could not be extracted.")
            val deferFunctionBody = shallow && isFunctionValue(source, index, declaration) &&
                !isClassValue(source, index, declaration)
            if (!visitedNames.add(name)) {
                if (deferFunctionBody || !expandedNames.add(name)) continue
            } else if (!deferFunctionBody) {
                expandedNames.add(name)
            }
            if (visitedNames.size > maxDeclarations) {
                val selectedSourceChars = selected.keys.sumOf { range ->
                    index.tokens[range.endToken].end - index.tokens[range.startToken].start
                }
                onDiagnosticStage?.invoke(
                    "locatorDependencyLimit; root=$rootName; dependency=$name; count=${visitedNames.size}; selected=${selected.size}; selectedChars=$selectedSourceChars; selectedKinds=${selected.values.groupingBy { it.kind }.eachCount()}; kind=${index.declarations[name]?.kind}",
                )
                throw java.io.IOException("YouTube player transform has too many helper dependencies.")
            }
            if (!selected.containsKey(declaration.range)) selected[declaration.range] = declaration
            if (deferFunctionBody) continue
            val currentRange = declaration.range
            val isClassDeclaration = includeUniqueDottedAliases &&
                (declaration.kind == DeclarationKind.ROOT_ASSIGNMENT ||
                    declaration.kind == DeclarationKind.DOTTED_ASSIGNMENT) &&
                isWord(source, index.tokens.getOrNull(currentRange.expressionStartToken), "class")
            val dependencyRange = if (isClassDeclaration) currentRange else declaration.memberRange ?: currentRange
            val localScopes = collectLocalIdentifiers(source, index, dependencyRange)
            for (tokenIndex in dependencyRange.startToken..dependencyRange.endToken) {
                val token = index.tokens[tokenIndex]
                if (token.kind != TOKEN_IDENTIFIER) continue
                val tokenIdentifier = source.substring(token.start, token.end)
                val isDiagnosticTarget = tokenIdentifier in diagnosticIdentifiers
                val isConstructorCallbackReference = includeUniqueDottedAliases &&
                    isConstructorArgumentReference(source, index, dependencyRange, tokenIndex)
                val isLocalIdentifier = localScopes.any { scope ->
                        tokenIndex in scope.range.startToken..scope.range.endToken && tokenIdentifier in scope.names
                    }
                if (isLocalIdentifier && tokenIdentifier in index.declarations && locallyShadowedDeclarations.size < 32) {
                    locallyShadowedDeclarations.add(tokenIdentifier)
                }
                if (isDiagnosticTarget && targetSymbolEvents.size < 24) {
                    val indexedKind = index.declarations[tokenIdentifier]?.kind
                    targetSymbolEvents.add(
                        "owner=$name; local=$isLocalIdentifier; indexed=$indexedKind; " +
                            "before=${diagnosticTokenLabel(source, index.tokens, tokenIndex - 1)}; " +
                            "after=${diagnosticTokenLabel(source, index.tokens, tokenIndex + 1)}; " +
                            "parentheses=${diagnosticParentheses(source, index, dependencyRange, tokenIndex)}; token=$tokenIndex",
                    )
                }
                if (isLocalIdentifier &&
                    !((includeDataDependencies || includeUniqueDottedAliases) &&
                        isWord(source, index.tokens.getOrNull(tokenIndex - 1), "extends")) &&
                    !isConstructorCallbackReference
                ) continue
                if (isPunctuation(source, index.tokens.getOrNull(tokenIndex - 1), ".") ||
                    isPunctuation(source, index.tokens.getOrNull(tokenIndex - 1), "?.")
                ) continue
                val (reference, lastIndex) = readDottedNameFrom(source, index.tokens, tokenIndex)
                val exactDependency = index.declarations[reference]
                val baseDependencyCandidate = if ((includeDataDependencies || includeUniqueDottedAliases) && exactDependency == null) {
                    index.declarations[tokenIdentifier]
                } else null
                val baseDependency = baseDependencyCandidate?.takeIf { candidate ->
                    includeDataDependencies ||
                        (includeUniqueDottedAliases && (
                            (candidate.kind == DeclarationKind.VALUE_ASSIGNMENT &&
                                (simpleIdentifierInitializer(source, index, candidate.range) != null ||
                                    isSafeLiteralInitializer(source, index, candidate.range) ||
                                    isSafeOptionalPerformanceFallback(source, index, candidate.range) ||
                                    isSafeEmptyConstructorInitializer(source, index, candidate.range) ||
                                    isBoundedStaticStringSplitInitializer(source, index, candidate.range) ||
                                    (index.tokens[candidate.range.endToken].end -
                                        index.tokens[candidate.range.startToken].start <= MAX_BOUNDED_MEMBER_CONSTRUCTOR_CHARS &&
                                        isBoundedLiteralConstructorInitializer(source, index, candidate.range)))) ||
                                (candidate.kind == DeclarationKind.OBJECT_ASSIGNMENT &&
                                    index.tokens[candidate.range.endToken].end -
                                    index.tokens[candidate.range.startToken].start <= MAX_DOTTED_PARENT_INITIALIZER_CHARS) ||
                            (candidate.kind in setOf(
                                DeclarationKind.ROOT_ASSIGNMENT,
                                DeclarationKind.DOTTED_ASSIGNMENT,
                                DeclarationKind.RAW_FUNCTION,
                                DeclarationKind.RAW_CLASS,
                            ) &&
                                index.tokens[candidate.range.endToken].end -
                                    index.tokens[candidate.range.startToken].start <= MAX_DOTTED_PARENT_INITIALIZER_CHARS &&
                                isFunctionValue(source, index, candidate))
                            ))
                }
                if (isDiagnosticTarget && baseDependencyCandidate != null && baseDependency == null &&
                    targetSymbolEvents.size < 24
                ) {
                    val candidateRange = baseDependencyCandidate.range
                    val candidateChars = index.tokens[candidateRange.endToken].end - index.tokens[candidateRange.startToken].start
                    targetSymbolEvents.add(
                        "baseRejected; owner=$name; symbol=$tokenIdentifier; kind=${baseDependencyCandidate.kind}; " +
                            "chars=$candidateChars; simpleAlias=${simpleIdentifierInitializer(source, index, candidateRange) != null}; " +
                            "emptyConstructor=${isSafeEmptyConstructorInitializer(source, index, candidateRange)}; " +
                            "boundedLiteralConstructor=${isBoundedLiteralConstructorInitializer(source, index, candidateRange)}; " +
                            "function=${isFunctionValue(source, index, baseDependencyCandidate)}",
                    )
                }
                val globalObjectAliasName = reference.takeIf {
                    includeUniqueDottedAliases && exactDependency == null && it.startsWith("g.") &&
                        it.count { char -> char == '.' } == 1
                }?.substringAfterLast('.')
                val globalObjectAliasDependency = globalObjectAliasName?.let { candidateName ->
                    index.declarations[candidateName]?.takeIf { isFunctionValue(source, index, it) }
                }
                val dottedAliasDependency = if (tokenIdentifier !in RESERVED_WORDS && exactDependency == null && baseDependency == null) {
                    uniqueDottedDeclarations[tokenIdentifier]
                } else null
                val dependencyName = when {
                    exactDependency != null -> reference
                    globalObjectAliasDependency != null -> globalObjectAliasName
                    baseDependency?.kind == DeclarationKind.VALUE_ASSIGNMENT ||
                        baseDependency?.kind == DeclarationKind.DOTTED_VALUE_ASSIGNMENT ||
                        baseDependency?.kind == DeclarationKind.OBJECT_ASSIGNMENT ||
                        baseDependency?.kind == DeclarationKind.ROOT_ASSIGNMENT ||
                        baseDependency?.kind == DeclarationKind.DOTTED_ASSIGNMENT ||
                        baseDependency?.kind == DeclarationKind.RAW_FUNCTION ||
                        baseDependency?.kind == DeclarationKind.RAW_CLASS -> tokenIdentifier
                    dottedAliasDependency != null -> dottedAliasDependency
                    else -> {
                        if (includeUniqueDottedAliases && tokenIdentifier !in RESERVED_WORDS &&
                            tokenIdentifier !in KNOWN_GLOBALS &&
                            (isPunctuation(source, index.tokens.getOrNull(lastIndex + 1), "(") ||
                                isWord(source, index.tokens.getOrNull(tokenIndex - 1), "new")) &&
                            unresolvedCalls.size < 32
                        ) unresolvedCalls.add(tokenIdentifier)
                        if (includeDataDependencies && unresolvedCallArguments.size < 16 &&
                            isCallArgumentReference(source, index, dependencyRange, tokenIndex) &&
                            unresolvedCallArguments.add(tokenIdentifier)
                        ) {
                            val dottedCandidates = index.declarations.keys
                                .filter { it.substringAfterLast('.') == tokenIdentifier }
                                .take(4)
                            onDiagnosticStage?.invoke(
                                "locatorUnresolvedArgument; root=$rootName; name=$tokenIdentifier; " +
                                    "dottedCandidates=${dottedCandidates.joinToString(",")}",
                            )
                        }
                        continue
                    }
                }
                if (dottedAliasDependency != null && dottedAliases.size < MAX_UNIQUE_DOTTED_ALIASES) {
                    dottedAliases[tokenIdentifier] = dottedAliasDependency
                }
                if (globalObjectAliasDependency != null && globalObjectAliases.size < MAX_UNIQUE_DOTTED_ALIASES) {
                    globalObjectAliases[reference] = dependencyName
                }
                val dependency = index.declarations[dependencyName]
                val isCall = isPunctuation(source, index.tokens.getOrNull(lastIndex + 1), "(") ||
                    isCommaOperatorCallCallee(source, index, tokenIndex, lastIndex)
                val isConstructorUse = isWord(source, index.tokens.getOrNull(tokenIndex - 1), "new")
                val aliasTarget = if ((includeDataDependencies || includeUniqueDottedAliases) &&
                    tokenIndex == currentRange.expressionStartToken
                ) {
                    simpleIdentifierInitializer(source, index, currentRange)
                } else null
                if (dependency == null) {
                    if (isDiagnosticTarget && targetSymbolEvents.size < 24) {
                        targetSymbolEvents.add("owner=$name; resolved=$dependencyName; indexed=false; token=$tokenIndex")
                    }
                    if (includeDataDependencies && unresolvedCallArguments.size < 16 &&
                        isCallArgumentReference(source, index, dependencyRange, tokenIndex) &&
                        unresolvedCallArguments.add(tokenIdentifier)
                    ) {
                        val dottedCandidates = index.declarations.keys
                            .filter { it.substringAfterLast('.') == tokenIdentifier }
                            .take(4)
                        onDiagnosticStage?.invoke(
                            "locatorUnresolvedArgument; root=$rootName; name=$tokenIdentifier; " +
                                "dottedCandidates=${dottedCandidates.joinToString(",")}",
                        )
                    }
                    continue
                }
                val isAliasValueDependency = aliasTarget != null && reference == aliasTarget && dependencyName == aliasTarget
                val isSimpleAliasValue = (dependency.kind == DeclarationKind.VALUE_ASSIGNMENT ||
                    dependency.kind == DeclarationKind.DOTTED_VALUE_ASSIGNMENT) &&
                    simpleIdentifierInitializer(source, index, dependency.range) != null
                val isSafeOptionalConstructorValue = includeUniqueDottedAliases &&
                    dependency.kind == DeclarationKind.VALUE_ASSIGNMENT &&
                    (isSafeTypeofConstructorFallback(source, index, dependency.range) ||
                        isSafeEmptyConstructorInitializer(source, index, dependency.range))
                val isSafeOptionalPerformanceValue = includeUniqueDottedAliases &&
                    dependency.kind == DeclarationKind.VALUE_ASSIGNMENT &&
                    isSafeOptionalPerformanceFallback(source, index, dependency.range)
                val isClassBase = (includeDataDependencies || includeUniqueDottedAliases) &&
                    isWord(source, index.tokens.getOrNull(tokenIndex - 1), "extends")
                val dependencyChars = index.tokens[dependency.range.endToken].end -
                    index.tokens[dependency.range.startToken].start
                val isBoundedMemberConstructorValue = includeUniqueDottedAliases &&
                    reference != dependencyName && dependency.kind == DeclarationKind.VALUE_ASSIGNMENT &&
                    dependencyChars <= MAX_BOUNDED_MEMBER_CONSTRUCTOR_CHARS &&
                    isBoundedLiteralConstructorInitializer(source, index, dependency.range)
                val isCallArgument = (includeDataDependencies || includeUniqueDottedAliases) &&
                    isCallArgumentReference(source, index, dependencyRange, tokenIndex)
                val isConstructorArgument = isConstructorCallbackReference
                val maxCallArgumentDependencies = if (includeUniqueDottedAliases) {
                    maxUrlFunctionArguments + MAX_URL_FUNCTION_DEPENDENCY_RESERVE
                } else MAX_DIAGNOSTIC_FUNCTION_ARGUMENTS
                val isFunctionFactoryArgument = isCallArgument &&
                    dependency.kind == DeclarationKind.VALUE_ASSIGNMENT &&
                    isZeroArgumentFunctionFactory(source, index, dependency.range)
                val isCallArgumentDependency = isCallArgument &&
                    (isFunctionValue(source, index, dependency) || isFunctionFactoryArgument) &&
                    dependencyChars <= MAX_DIAGNOSTIC_FUNCTION_ARGUMENT_CHARS &&
                    (dependencyName in callArgumentDependencyNames ||
                        callArgumentDependencyNames.size < maxCallArgumentDependencies)
                val isConstructorArgumentDependency = isConstructorArgument &&
                    isFunctionValue(source, index, dependency) &&
                    dependencyChars <= MAX_DIAGNOSTIC_FUNCTION_ARGUMENT_CHARS &&
                    (dependencyName in constructorArgumentDependencyNames ||
                        constructorArgumentDependencyNames.size < MAX_URL_CONSTRUCTOR_FUNCTION_ARGUMENTS)
                val isAssignedFunctionDependency = includeUniqueDottedAliases &&
                    isPunctuation(source, index.tokens.getOrNull(tokenIndex - 1), "=") &&
                    isFunctionValue(source, index, dependency) &&
                    dependencyChars <= MAX_DIAGNOSTIC_FUNCTION_ARGUMENT_CHARS &&
                    (dependencyName in assignedFunctionDependencyNames ||
                        assignedFunctionDependencyNames.size < maxUrlFunctionArguments + MAX_URL_FUNCTION_DEPENDENCY_RESERVE)
                val isObjectPropertyFunctionDependency = (includeUniqueDottedAliases || includeDataDependencies) &&
                    isPunctuation(source, index.tokens.getOrNull(tokenIndex - 1), ":") &&
                    (isPunctuation(source, index.tokens.getOrNull(tokenIndex + 1), ",") ||
                        isPunctuation(source, index.tokens.getOrNull(tokenIndex + 1), "}")) &&
                    isFunctionValue(source, index, dependency) &&
                    dependencyChars <= MAX_DIAGNOSTIC_FUNCTION_ARGUMENT_CHARS
                val isMemberAccess = includeDataDependencies && (reference != dependencyName ||
                    isPunctuation(source, index.tokens.getOrNull(lastIndex + 1), ".") ||
                    isPunctuation(source, index.tokens.getOrNull(lastIndex + 1), "["))
                val isExplicitDiagnosticDependency = includeDataDependencies && isDiagnosticTarget &&
                    reference == dependencyName && dependencyChars <= MAX_DIAGNOSTIC_FUNCTION_ARGUMENT_CHARS
                val isFunctionMemberDependency = includeUniqueDottedAliases && reference != dependencyName &&
                    isFunctionValue(source, index, dependency)
                val isSafeGlobalBootstrap = dependency.kind == DeclarationKind.DOTTED_VALUE_ASSIGNMENT &&
                    (isSafeGlobalObjectInitializer(source, index, dependency.range) ||
                        isSafeSelfFallbackObjectInitializer(source, index, dependency) ||
                        safeDottedPrototypeAliasOwner(source, index, dependency) != null)
                val isSafeDottedLiteral = (dependency.kind == DeclarationKind.DOTTED_VALUE_ASSIGNMENT ||
                    dependency.kind == DeclarationKind.OBJECT_ASSIGNMENT) &&
                    includeUniqueDottedAliases && dependencyChars <= MAX_DOTTED_LITERAL_CHARS &&
                    isSafeLiteralInitializer(source, index, dependency.range)
                val isLiteralValue = (dependency.kind == DeclarationKind.VALUE_ASSIGNMENT ||
                    (dependency.kind == DeclarationKind.DOTTED_VALUE_ASSIGNMENT &&
                        (includeDataDependencies || isSafeGlobalBootstrap || isSafeDottedLiteral)) ||
                    (dependency.kind == DeclarationKind.OBJECT_ASSIGNMENT && isSafeDottedLiteral)) &&
                    ((dependency.kind != DeclarationKind.DOTTED_VALUE_ASSIGNMENT &&
                        dependency.kind != DeclarationKind.OBJECT_ASSIGNMENT) ||
                        dependencyChars <= MAX_DOTTED_LITERAL_CHARS) &&
                    (isSafeLiteralInitializer(source, index, dependency.range) ||
                                    (dependency.kind == DeclarationKind.VALUE_ASSIGNMENT &&
                            isBoundedStaticStringSplitInitializer(source, index, dependency.range)) ||
                        isSafeGlobalBootstrap)
                var includeSharedComplexValue = false
                if (dependency.kind == DeclarationKind.VALUE_ASSIGNMENT ||
                    (dependency.kind == DeclarationKind.DOTTED_VALUE_ASSIGNMENT &&
                        (includeDataDependencies || isSafeGlobalBootstrap || isSafeDottedLiteral)) ||
                    (dependency.kind == DeclarationKind.OBJECT_ASSIGNMENT && isSafeDottedLiteral)
                ) {
                    val browserGlobal = firstBrowserGlobal(source, index, dependency.range)
                    if (browserGlobal != null && !isSafeGlobalBootstrap && !isSafeOptionalPerformanceValue) {
                        skippedBrowserInitializers++
                        if (isDiagnosticTarget && targetSymbolEvents.size < 24) {
                            targetSymbolEvents.add("skip=browserInitializer; owner=$name; target=$dependencyName; global=$browserGlobal; chars=$dependencyChars")
                        }
                        continue
                    }
                    if (includeDataDependencies && dependency.kind == DeclarationKind.DOTTED_VALUE_ASSIGNMENT &&
                        !isCall && !isLiteralValue && !isClassBase && !isCallArgumentDependency
                    ) {
                        skippedComplexInitializers++
                        continue
                    }
                    val owners = complexInitializerOwners.getOrPut(dependencyName) { HashSet() }
                    owners.add(name)
                    includeSharedComplexValue = !isCall && !isLiteralValue &&
                        (isMemberAccess || isClassBase || isCallArgumentDependency || isSimpleAliasValue ||
                            isSafeOptionalConstructorValue || isSafeOptionalPerformanceValue || isExplicitDiagnosticDependency ||
                            isBoundedMemberConstructorValue || owners.size >= 2)
                    if (!isCall && !isLiteralValue && !isMemberAccess && !isClassBase &&
                        !isCallArgumentDependency && !isSimpleAliasValue && !isSafeOptionalConstructorValue &&
                        !isSafeOptionalPerformanceValue && !isExplicitDiagnosticDependency &&
                        !isBoundedMemberConstructorValue && owners.size < 2
                    ) {
                        skippedComplexInitializers++
                        if (isDiagnosticTarget && targetSymbolEvents.size < 24) {
                            targetSymbolEvents.add(
                                "skip=singleOwnerInitializer; owner=$name; target=$dependencyName; chars=$dependencyChars; " +
                                    "exprStart=${diagnosticTokenLabel(source, index.tokens, dependency.range.expressionStartToken)}; " +
                                    "literal=${isSafeLiteralInitializer(source, index, dependency.range)}; " +
                                    "function=${isFunctionValue(source, index, dependency)}; " +
                                    "shape=${diagnosticRangeShape(source, index, dependency.range)}; " +
                                    "staticStringSplit=${isBoundedStaticStringSplitInitializer(source, index, dependency.range)}",
                            )
                        }
                        continue
                    }
                }
            val shouldIncludeDependency = isCall || isConstructorUse || isAliasValueDependency || isSimpleAliasValue ||
                isSafeOptionalConstructorValue || isSafeOptionalPerformanceValue || isClassBase || isCallArgumentDependency ||
                isConstructorArgumentDependency || isAssignedFunctionDependency || isObjectPropertyFunctionDependency ||
                isFunctionMemberDependency || isBoundedMemberConstructorValue || isMemberAccess ||
                dependency.kind == DeclarationKind.OBJECT_ASSIGNMENT ||
                dependency.kind == DeclarationKind.UNINITIALIZED_VALUE || isLiteralValue || includeSharedComplexValue
            if (isDiagnosticTarget && targetSymbolEvents.size < 24) {
                val isInsideOwnerRange = dependency.range.startToken >= currentRange.startToken &&
                    dependency.range.endToken <= currentRange.endToken
                targetSymbolEvents.add(
                    "edge; owner=$name; target=$dependencyName; indexed=${dependency.kind}; call=$isCall; " +
                        "constructor=$isConstructorUse; callArgument=$isCallArgumentDependency; rawCallArgument=$isCallArgument; " +
                        "functionValue=${isFunctionValue(source, index, dependency)}; chars=$dependencyChars; " +
                        "argumentCount=${callArgumentDependencyNames.size}; argumentCap=$maxCallArgumentDependencies; " +
                        "constructorArgument=$isConstructorArgumentDependency; assignedFunction=$isAssignedFunctionDependency; " +
                        "insideOwner=$isInsideOwnerRange; included=$shouldIncludeDependency; token=$tokenIndex",
                )
            }
                if (dependency.range.startToken >= currentRange.startToken &&
                    dependency.range.endToken <= currentRange.endToken
                ) continue
                // Include direct calls, bounded function/class arguments and values, small data
                // arguments, class bases, helper objects, and module constants.
                if (shouldIncludeDependency) {
                    if (isDiagnosticTarget && targetSymbolEvents.size < 24) {
                        targetSymbolEvents.add(
                            "owner=$name; resolved=$dependencyName; indexed=true; included=true; call=$isCall; " +
                                "callArgument=$isCallArgumentDependency; constructorArgument=$isConstructorArgumentDependency; token=$tokenIndex",
                        )
                    }
                    if (isCallArgumentDependency) callArgumentDependencyNames.add(dependencyName)
                    if (isConstructorArgumentDependency) constructorArgumentDependencyNames.add(dependencyName)
                    if (isAssignedFunctionDependency) assignedFunctionDependencyNames.add(dependencyName)
                    val shallowFunctionDependency = isFunctionValue(source, index, dependency) &&
                        !isClassValue(source, index, dependency) && !isCall &&
                        (isCallArgumentDependency || isConstructorArgumentDependency ||
                            isAssignedFunctionDependency || isObjectPropertyFunctionDependency)
                    val prioritizeDependency = includeUniqueDottedAliases &&
                        (isCallArgumentDependency || isConstructorArgumentDependency || isAssignedFunctionDependency ||
                            isObjectPropertyFunctionDependency || isSafeGlobalBootstrap)
                    val queuedDependency = dependencyName to shallowFunctionDependency
                    if (prioritizeDependency) prioritizedPending.addLast(queuedDependency) else pending.addLast(queuedDependency)
                    if (includeUniqueDottedAliases) {
                        includeSafeDottedAncestors(source, index, dependencyName, selected, pending)
                    }
                } else if (includeUniqueDottedAliases && skippedDeclarations.size < 32) {
                    skippedDeclarations.add("$dependencyName:${dependency.kind}")
                    if (isDiagnosticTarget && targetSymbolEvents.size < 24) {
                        targetSymbolEvents.add(
                            "owner=$name; resolved=$dependencyName; indexed=true; included=false; call=$isCall; " +
                                "callArgument=$isCallArgumentDependency; constructorArgument=$isConstructorArgumentDependency; token=$tokenIndex",
                        )
                    }
                }
            }
        }

        val ordered = selected.values.sortedBy { it.range.startToken }
        val prelude = buildString {
            append("var g={};var window=this;\n")
            if (includeUniqueDottedAliases) {
                append("var navigator={userAgent:\"\",vendor:\"\",platform:\"\",language:\"en\",languages:[\"en\"],onLine:true,hardwareConcurrency:2};\n")
            }
        }
        val baseProgram = buildString {
            append(prelude)
            for (declaration in ordered) {
                val rendered = renderDeclaration(source, index, declaration)
                append(rendered).append("\n")
                if (length > maxProgramBytes) {
                    onDiagnosticStage?.invoke(
                        "locatorProgramSizeLimit; chars=$length; declarations=${ordered.size}; " +
                            "dependencies=${visitedNames.size}; max=$maxProgramBytes",
                    )
                    throw java.io.IOException("YouTube player transform helpers exceed the safe runtime size limit.")
                }
            }
            for ((alias, dottedName) in dottedAliases) {
                append("var ").append(alias).append('=').append(dottedName).append(";\n")
                if (length > maxProgramBytes) {
                    onDiagnosticStage?.invoke(
                        "locatorProgramSizeLimit; chars=$length; declarations=${ordered.size}; " +
                            "dependencies=${visitedNames.size}; max=$maxProgramBytes",
                    )
                    throw java.io.IOException("YouTube player transform helpers exceed the safe runtime size limit.")
                }
            }
            for ((alias, target) in globalObjectAliases) {
                append(alias).append('=').append(target).append(";\n")
                if (length > maxProgramBytes) {
                    throw java.io.IOException("YouTube player transform helpers exceed the safe runtime size limit.")
                }
            }
        }
        val isolatedGlobalFallbacks = if (includeUniqueDottedAliases) {
            findSafeIsolatedGlobalFallbacks(baseProgram)
        } else emptyList()
        val program = if (isolatedGlobalFallbacks.isEmpty()) baseProgram else buildString {
            append(prelude)
            for ((name, value) in isolatedGlobalFallbacks) {
                append("var ").append(name).append('=').append(value).append(";\n")
            }
            append(baseProgram.substring(prelude.length))
        }
        if (program.length > maxProgramBytes) {
            onDiagnosticStage?.invoke(
                "locatorProgramSizeLimit; chars=${program.length}; declarations=${ordered.size}; " +
                    "dependencies=${visitedNames.size}; max=$maxProgramBytes",
            )
            throw java.io.IOException("YouTube player transform helpers exceed the safe runtime size limit.")
        }
        onDiagnosticStage?.invoke(
            "locatorProgram; dependencies=${visitedNames.size}; declarations=${ordered.size}; functionArguments=${callArgumentDependencyNames.size}; constructorFunctions=${constructorArgumentDependencyNames.size}; chars=${program.length}; isolatedGlobals=${isolatedGlobalFallbacks.size}; skippedComplex=$skippedComplexInitializers; skippedBrowser=$skippedBrowserInitializers",
        )
        if (includeUniqueDottedAliases) {
            onDiagnosticStage?.invoke(
                "locatorDependencyAudit; unresolvedCalls=${unresolvedCalls.joinToString(",")}; " +
                    "shadowedDeclarations=${locallyShadowedDeclarations.joinToString(",")}; " +
                    "skippedDeclarations=${skippedDeclarations.joinToString(",")}",
            )
        }
        if (targetSymbolEvents.isNotEmpty()) {
            onDiagnosticStage?.invoke("locatorTargetSymbol; name=${diagnosticIdentifiers.firstOrNull()}; events=${targetSymbolEvents.joinToString("|")}")
        }
        return program
    }

    private fun includeSafeDottedAncestors(
        source: String,
        index: ScriptIndex,
        name: String,
        selected: LinkedHashMap<SourceRange, IndexedDeclaration>,
        pending: java.util.ArrayDeque<Pair<String, Boolean>>,
    ) {
        var parentName = name.substringBeforeLast('.', "")
        var depth = 0
        while (parentName.isNotEmpty() && depth++ < 4) {
            val parent = index.declarations[parentName]
            if (parent != null) {
                val parentChars = index.tokens[parent.range.endToken].end -
                    index.tokens[parent.range.startToken].start
                val isSafeParentInitializer =
                    (parent.kind == DeclarationKind.DOTTED_ASSIGNMENT && isFunctionValue(source, index, parent)) ||
                        ((parent.kind == DeclarationKind.DOTTED_VALUE_ASSIGNMENT ||
                            parent.kind == DeclarationKind.OBJECT_ASSIGNMENT) &&
                            parentChars <= MAX_DOTTED_LITERAL_CHARS &&
                            (isSafeLiteralInitializer(source, index, parent.range) ||
                                (parent.kind == DeclarationKind.DOTTED_VALUE_ASSIGNMENT &&
                                    (isSafeGlobalObjectInitializer(source, index, parent.range) ||
                                        isSafeSelfFallbackObjectInitializer(source, index, parent) ||
                                        safeDottedPrototypeAliasOwner(source, index, parent) != null))))
                if (isSafeParentInitializer && parentChars <= MAX_DOTTED_PARENT_INITIALIZER_CHARS) {
                    selected.putIfAbsent(parent.range, parent)
                    pending.addFirst(parentName to false)
                    safeDottedPrototypeAliasOwner(source, index, parent)?.let { ownerName ->
                        val owner = index.declarations[ownerName]
                        if (owner != null) {
                            val ownerChars = index.tokens[owner.range.endToken].end -
                                index.tokens[owner.range.startToken].start
                            if (ownerChars <= MAX_DOTTED_PARENT_INITIALIZER_CHARS) {
                                selected.putIfAbsent(owner.range, owner)
                            }
                        }
                    }
                }
            }
            parentName = parentName.substringBeforeLast('.', "")
        }
    }

    private fun renderDeclaration(source: String, index: ScriptIndex, declaration: IndexedDeclaration): String {
        val range = declaration.range
        val end = index.tokens[range.endToken].end
        return when (declaration.kind) {
            DeclarationKind.RAW_FUNCTION, DeclarationKind.RAW_CLASS -> source.substring(index.tokens[range.startToken].start, end)
            DeclarationKind.UNINITIALIZED_VALUE -> "var ${declaration.assignmentName};"
            DeclarationKind.ROOT_ASSIGNMENT, DeclarationKind.VALUE_ASSIGNMENT ->
                "var ${declaration.assignmentName}=" + source.substring(index.tokens[range.expressionStartToken].start, end) + ";"
            DeclarationKind.DOTTED_ASSIGNMENT, DeclarationKind.DOTTED_VALUE_ASSIGNMENT ->
                declaration.assignmentName + "=" + source.substring(index.tokens[range.expressionStartToken].start, end) + ";"
            DeclarationKind.OBJECT_ASSIGNMENT -> {
                val expression = source.substring(index.tokens[range.expressionStartToken].start, end)
                val prefix = if (declaration.assignmentName.contains('.')) "${declaration.assignmentName}="
                else "var ${declaration.assignmentName}="
                prefix + expression + ";"
            }
        }
    }

    private fun readDottedNameFrom(source: String, tokens: List<JsToken>, startIndex: Int): Pair<String, Int> {
        var endIndex = startIndex
        var segmentCount = 1
        while (isPunctuation(source, tokens.getOrNull(endIndex + 1), ".") &&
            tokens.getOrNull(endIndex + 2)?.kind == TOKEN_IDENTIFIER && segmentCount < 5
        ) {
            endIndex += 2
            segmentCount++
        }
        return source.substring(tokens[startIndex].start, tokens[endIndex].end) to endIndex
    }

    private fun isCommaOperatorCallCallee(
        source: String,
        index: ScriptIndex,
        nameStartToken: Int,
        nameEndToken: Int,
    ): Boolean {
        val groupedClose = nameEndToken + 1
        if (!isPunctuation(source, index.tokens.getOrNull(groupedClose), ")") ||
            !isPunctuation(source, index.tokens.getOrNull(groupedClose + 1), "(")
        ) return false
        val groupedOpen = index.matchingTokens.getOrElse(groupedClose) { -1 }
        if (groupedOpen < 0 || groupedOpen + 3 != nameStartToken) return false
        val receiver = index.tokens.getOrNull(groupedOpen + 1) ?: return false
        return source.substring(receiver.start, receiver.end) == "0" &&
            isPunctuation(source, index.tokens.getOrNull(groupedOpen + 2), ",")
    }

    private fun collectLocalIdentifiers(source: String, index: ScriptIndex, range: SourceRange): List<LocalFunctionScope> {
        val tokens = index.tokens
        val scopes = arrayListOf(LocalFunctionScope(range, HashSet()))

        fun scopeFor(tokenIndex: Int, excluding: LocalFunctionScope? = null): LocalFunctionScope = scopes
            .asSequence()
            .filter { it !== excluding && tokenIndex in it.range.startToken..it.range.endToken }
            .minByOrNull { it.range.endToken - it.range.startToken }
            ?: scopes.first()

        fun addScope(start: Int, end: Int, parameterStart: Int, parameterEnd: Int): LocalFunctionScope {
            val scope = scopes.firstOrNull { it.range.startToken == start && it.range.endToken == end }
                ?: LocalFunctionScope(SourceRange(start, end), HashSet()).also(scopes::add)
            addParameterIdentifiers(source, tokens, parameterStart, parameterEnd, scope.names)
            return scope
        }

        // Keep nested callable bindings local to their own body. A same-named parameter or
        // variable inside a callback must not hide a module helper used by the outer transform.
        for (tokenIndex in range.startToken..range.endToken) {
            val token = tokens[tokenIndex]
            if (isWord(source, token, "function")) {
                var cursor = tokenIndex + 1
                if (isPunctuation(source, tokens.getOrNull(cursor), "*")) cursor++
                val nameToken = tokens.getOrNull(cursor)?.takeIf { it.kind == TOKEN_IDENTIFIER }
                val name = nameToken?.let { source.substring(it.start, it.end) }
                if (nameToken != null) cursor++
                while (cursor <= range.endToken && !isPunctuation(source, tokens.getOrNull(cursor), "(")) cursor++
                val closeParen = index.matchingTokens.getOrElse(cursor) { -1 }
                val bodyOpen = closeParen + 1
                val bodyClose = if (closeParen >= 0 && isPunctuation(source, tokens.getOrNull(bodyOpen), "{")) {
                    index.matchingTokens.getOrElse(bodyOpen) { -1 }
                } else -1
                if (bodyClose >= 0 && bodyClose <= range.endToken) {
                    val start = if (isWord(source, tokens.getOrNull(tokenIndex - 1), "async")) tokenIndex - 1 else tokenIndex
                    val scope = addScope(start, bodyClose, cursor + 1, closeParen - 1)
                    if (name != null) {
                        scope.names.add(name)
                        scopeFor(start, scope).names.add(name)
                    }
                }
            }

            if (isPunctuation(source, token, "=>")) {
                val previous = tokenIndex - 1
                val parameterOpen = if (isPunctuation(source, tokens.getOrNull(previous), ")")) {
                    index.matchingTokens.getOrElse(previous) { -1 }
                } else -1
                val parameterStart = if (parameterOpen >= 0) parameterOpen + 1 else previous
                val parameterEnd = if (parameterOpen >= 0) previous - 1 else previous
                val start = if (isWord(source, tokens.getOrNull(parameterStart - 1), "async")) parameterStart - 1 else parameterStart
                val bodyStart = tokenIndex + 1
                val bodyEnd = functionOrArrowEnd(source, tokens, index.matchingTokens, parameterStart)
                if (bodyEnd >= bodyStart && bodyEnd <= range.endToken) {
                    addScope(start, bodyEnd, parameterStart, parameterEnd)
                }
            }

            if (token.kind == TOKEN_IDENTIFIER && source.substring(token.start, token.end) !in CALL_CONTROL_KEYWORDS &&
                isPunctuation(source, tokens.getOrNull(tokenIndex + 1), "(")) {
                val closeParen = index.matchingTokens.getOrElse(tokenIndex + 1) { -1 }
                val bodyOpen = closeParen + 1
                val bodyClose = if (closeParen > tokenIndex && isPunctuation(source, tokens.getOrNull(bodyOpen), "{")) {
                    index.matchingTokens.getOrElse(bodyOpen) { -1 }
                } else -1
                if (bodyClose >= 0 && bodyClose <= range.endToken) {
                    addScope(tokenIndex, bodyClose, tokenIndex + 2, closeParen - 1)
                }
            }
        }

        for (tokenIndex in range.startToken..range.endToken) {
            val token = tokens[tokenIndex]
            if (!isWord(source, token, "var") && !isWord(source, token, "let") && !isWord(source, token, "const")) continue
            var declaratorIndex = tokenIndex + 1
            while (declaratorIndex <= range.endToken && tokens[declaratorIndex].kind == TOKEN_IDENTIFIER) {
                val name = tokens[declaratorIndex]
                scopeFor(declaratorIndex).names.add(source.substring(name.start, name.end))
                val assignment = assignmentTarget(source, tokens, declaratorIndex)
                val initializerEnd = assignment?.let {
                    variableInitializerEnd(source, tokens, index.matchingTokens, it.rhsIndex)
                } ?: declaratorIndex
                declaratorIndex = initializerEnd + 1
                if (isPunctuation(source, tokens.getOrNull(declaratorIndex), ",")) {
                    declaratorIndex++
                } else {
                    break
                }
            }
        }
        return scopes
    }

    private fun firstBrowserGlobal(source: String, index: ScriptIndex, range: SourceRange): String? {
        for (tokenIndex in range.startToken..range.endToken) {
            val token = index.tokens[tokenIndex]
            if (token.kind != TOKEN_IDENTIFIER) continue
            if (isPunctuation(source, index.tokens.getOrNull(tokenIndex - 1), ".") ||
                isPunctuation(source, index.tokens.getOrNull(tokenIndex - 1), "?.")
            ) continue
            val identifier = source.substring(token.start, token.end)
            if (identifier in BROWSER_GLOBALS) return identifier
        }
        return null
    }

    private fun findSafeIsolatedGlobalFallbacks(program: String): List<Pair<String, String>> {
        val tokens = tokenize(program)
        val matchingTokens = buildMatchingPairs(program, tokens)
        val lazyStateNames = findSafeLazyGlobalStateNames(program, tokens, matchingTokens)
        val isolatedFallbacks = LinkedHashMap<String, String>()
        val unsafeReferences = HashSet<String>()
        for (tokenIndex in tokens.indices) {
            val token = tokens[tokenIndex]
            if (token.kind != TOKEN_IDENTIFIER) continue
            val name = program.substring(token.start, token.end)
            if (name in KNOWN_GLOBALS || name in RESERVED_WORDS) continue
            val isOptionalProperty = isPunctuation(program, tokens.getOrNull(tokenIndex + 1), "?.") &&
                !isPunctuation(program, tokens.getOrNull(tokenIndex + 2), "(")
            val isSpreadOperand = isPunctuation(program, tokens.getOrNull(tokenIndex - 1), "...")
            val keyToken = tokens.getOrNull(tokenIndex - 2)
            val isCallbackValue = isPunctuation(program, tokens.getOrNull(tokenIndex - 1), ":") &&
                keyToken != null && keyToken.kind == TOKEN_IDENTIFIER &&
                program.substring(keyToken.start, keyToken.end) in CALLBACK_PROPERTY_NAMES ||
                isSafeNoOpCallbackArgument(program, tokens, matchingTokens, tokenIndex)
            val isLazyState = name in lazyStateNames
            val fallback = when {
                isSpreadOperand -> "[]"
                isOptionalProperty -> "{}"
                isCallbackValue -> "function(){}"
                isLazyState -> "undefined"
                else -> null
            }
            if (fallback == null) {
                unsafeReferences.add(name)
            } else if (name !in unsafeReferences) {
                val current = isolatedFallbacks[name]
                isolatedFallbacks[name] = when {
                    current == null || current == fallback -> fallback
                    else -> ""
                }
                if (isolatedFallbacks[name] == "") unsafeReferences.add(name)
            }
        }
        return isolatedFallbacks
            .filterKeys { it !in unsafeReferences }
            .toSortedMap()
            .entries
            .map { it.key to it.value }
    }

    private fun findSafeLazyGlobalStateNames(
        source: String,
        tokens: List<JsToken>,
        matchingTokens: IntArray,
    ): Set<String> {
        val guardedNames = HashSet<String>()
        for (tokenIndex in 0 until tokens.size - 5) {
            if (!isWord(source, tokens[tokenIndex], "if") ||
                !isPunctuation(source, tokens.getOrNull(tokenIndex + 1), "(") ||
                !isPunctuation(source, tokens.getOrNull(tokenIndex + 2), "!")
            ) continue
            val nameToken = tokens.getOrNull(tokenIndex + 3) ?: continue
            if (nameToken.kind != TOKEN_IDENTIFIER ||
                !isPunctuation(source, tokens.getOrNull(tokenIndex + 4), ")") ||
                matchingTokens.getOrElse(tokenIndex + 1) { -1 } != tokenIndex + 4
            ) continue
            guardedNames.add(source.substring(nameToken.start, nameToken.end))
        }
        if (guardedNames.isEmpty()) return emptySet()

        val safeNames = HashSet<String>()
        for (name in guardedNames) {
            var hasAssignment = false
            for (tokenIndex in tokens.indices) {
                val token = tokens[tokenIndex]
                if (token.kind != TOKEN_IDENTIFIER || source.substring(token.start, token.end) != name) continue
                val isAssignmentTarget = isPunctuation(source, tokens.getOrNull(tokenIndex + 1), "=") &&
                    !isPunctuation(source, tokens.getOrNull(tokenIndex - 1), ".")
                if (isAssignmentTarget) hasAssignment = true
            }
            if (hasAssignment) safeNames.add(name)
        }
        return safeNames
    }

    private fun isSafeNoOpCallbackArgument(
        source: String,
        tokens: List<JsToken>,
        matchingTokens: IntArray,
        tokenIndex: Int,
    ): Boolean {
        val value = tokens.getOrNull(tokenIndex) ?: return false
        if (value.kind != TOKEN_IDENTIFIER || !source[value.start].isUpperCase()) return false
        if (!isPunctuation(source, tokens.getOrNull(tokenIndex + 1), ")")) return false
        var openParen = tokenIndex - 1
        while (openParen >= 0 && openParen >= tokenIndex - 64) {
            if (isPunctuation(source, tokens.getOrNull(openParen), "(") &&
                matchingTokens.getOrElse(openParen) { -1 } == tokenIndex + 1
            ) break
            openParen--
        }
        if (openParen < 0 || openParen < tokenIndex - 64) return false
        val targetEnd = openParen - 1
        val targetName = readDottedNameEndingAt(source, tokens, targetEnd) ?: return false
        val targetStart = dottedNameStartIndex(source, tokens, targetEnd)
        return isWord(source, tokens.getOrNull(targetStart - 1), "new") &&
            targetName.substringAfterLast('.').firstOrNull()?.isUpperCase() == true
    }

    private fun isSafeLiteralInitializer(source: String, index: ScriptIndex, range: SourceRange): Boolean {
        if (range.expressionStartToken < range.startToken || range.expressionStartToken > range.endToken) return false
        val literalIdentifiers = setOf("true", "false", "null", "undefined", "NaN", "Infinity")
        val safePunctuation = setOf("(", ")", "[", "]", "{", "}", ",", ":", "+", "-", "*", "/", "%", "|", "&", "^", "~", "!", "<", ">", "==", "===", "!=", "!==", "<=", ">=")
        for (tokenIndex in range.expressionStartToken..range.endToken) {
            val token = index.tokens[tokenIndex]
            if (token.kind == TOKEN_OPAQUE) continue
            if (token.kind == TOKEN_IDENTIFIER) {
                if (source.substring(token.start, token.end) !in literalIdentifiers) return false
            } else if (source.substring(token.start, token.end) !in safePunctuation) {
                return false
            }
        }
        return true
    }

    private fun isBoundedStaticStringSplitInitializer(source: String, index: ScriptIndex, range: SourceRange): Boolean {
        val start = range.expressionStartToken
        if (start < range.startToken || start + 5 != range.endToken) return false
        val expressionStart = index.tokens.getOrNull(start)?.start ?: return false
        val expressionEnd = index.tokens.getOrNull(range.endToken)?.end ?: return false
        if (expressionEnd - expressionStart > MAX_STATIC_STRING_SPLIT_CHARS) return false
        if (!isPunctuation(source, index.tokens.getOrNull(start + 1), ".") ||
            !isWord(source, index.tokens.getOrNull(start + 2), "split") ||
            !isPunctuation(source, index.tokens.getOrNull(start + 3), "(") ||
            !isPunctuation(source, index.tokens.getOrNull(start + 5), ")")
        ) return false
        val receiver = index.tokens.getOrNull(start) ?: return false
        val argument = index.tokens.getOrNull(start + 4) ?: return false
        return isQuotedStringLiteral(source, receiver) && isQuotedStringLiteral(source, argument)
    }

    private fun isQuotedStringLiteral(source: String, token: JsToken): Boolean {
        if (token.kind != TOKEN_OPAQUE || token.end - token.start < 2) return false
        val quote = source[token.start]
        return quote in setOf('\'', '"') && source[token.end - 1] == quote
    }

    private fun isSafeGlobalObjectInitializer(source: String, index: ScriptIndex, range: SourceRange): Boolean {
        if (range.expressionStartToken < range.startToken || range.expressionStartToken > range.endToken) return false
        if (range.endToken - range.expressionStartToken != 2) return false
        val first = index.tokens[range.expressionStartToken]
        val operator = index.tokens[range.expressionStartToken + 1]
        val last = index.tokens[range.expressionStartToken + 2]
        return first.kind == TOKEN_IDENTIFIER && source.substring(first.start, first.end) == "this" &&
            source.substring(operator.start, operator.end) == "||" &&
            last.kind == TOKEN_IDENTIFIER && source.substring(last.start, last.end) == "self"
    }

    private fun isSafeSelfFallbackObjectInitializer(
        source: String,
        index: ScriptIndex,
        declaration: IndexedDeclaration,
    ): Boolean {
        if (declaration.kind != DeclarationKind.DOTTED_VALUE_ASSIGNMENT) return false
        val range = declaration.range
        val start = range.expressionStartToken
        if (start < range.startToken || start > range.endToken) return false
        val (fallbackName, nameEnd) = readDottedNameFrom(source, index.tokens, start)
        if (fallbackName != declaration.assignmentName ||
            !isPunctuation(source, index.tokens.getOrNull(nameEnd + 1), "||")
        ) return false
        if (range.endToken - nameEnd == 3 &&
            isPunctuation(source, index.tokens.getOrNull(nameEnd + 2), "{") &&
            isPunctuation(source, index.tokens.getOrNull(nameEnd + 3), "}")
        ) return true
        if (range.endToken - nameEnd != 5 ||
            !isWord(source, index.tokens.getOrNull(nameEnd + 2), "new")
        ) return false
        val constructorToken = index.tokens.getOrNull(nameEnd + 3) ?: return false
        if (constructorToken.kind != TOKEN_IDENTIFIER) return false
        val constructorName = source.substring(constructorToken.start, constructorToken.end)
        if (constructorName !in SAFE_EMPTY_CONSTRUCTORS) return false
        return isPunctuation(source, index.tokens.getOrNull(nameEnd + 4), "(") &&
            isPunctuation(source, index.tokens.getOrNull(nameEnd + 5), ")")
    }

    private fun safeDottedPrototypeAliasOwner(
        source: String,
        index: ScriptIndex,
        declaration: IndexedDeclaration,
    ): String? {
        if (declaration.kind != DeclarationKind.DOTTED_VALUE_ASSIGNMENT) return null
        val (targetName, targetEnd) = readDottedNameFrom(source, index.tokens, declaration.range.expressionStartToken)
        if (targetEnd != declaration.range.endToken || !targetName.endsWith(".prototype")) return null
        val ownerName = targetName.removeSuffix(".prototype")
        val owner = index.declarations[ownerName] ?: return null
        if (owner.kind != DeclarationKind.DOTTED_ASSIGNMENT &&
            owner.kind != DeclarationKind.ROOT_ASSIGNMENT &&
            owner.kind != DeclarationKind.RAW_FUNCTION &&
            owner.kind != DeclarationKind.RAW_CLASS
        ) return null
        return ownerName.takeIf { isFunctionValue(source, index, owner) }
    }

    private fun simpleIdentifierInitializer(source: String, index: ScriptIndex, range: SourceRange): String? {
        if (range.expressionStartToken < range.startToken || range.expressionStartToken > range.endToken) return null
        val (target, targetEnd) = readDottedNameFrom(source, index.tokens, range.expressionStartToken)
        if (targetEnd != range.endToken) return null
        return target
    }

    private fun isSafeTypeofConstructorFallback(source: String, index: ScriptIndex, range: SourceRange): Boolean {
        val start = range.expressionStartToken
        if (start < range.startToken || !isWord(source, index.tokens.getOrNull(start), "typeof")) return false
        val constructor = index.tokens.getOrNull(start + 1) ?: return false
        if (constructor.kind != TOKEN_IDENTIFIER) return false
        val constructorName = source.substring(constructor.start, constructor.end)
        if (!isPunctuation(source, index.tokens.getOrNull(start + 2), "!==") &&
            !isPunctuation(source, index.tokens.getOrNull(start + 2), "!=")
        ) return false
        val undefinedLiteral = index.tokens.getOrNull(start + 3) ?: return false
        if (undefinedLiteral.kind != TOKEN_OPAQUE ||
            source.substring(undefinedLiteral.start, undefinedLiteral.end) !in setOf("\"undefined\"", "'undefined'")
        ) return false
        if (!isPunctuation(source, index.tokens.getOrNull(start + 4), "?") ||
            !isWord(source, index.tokens.getOrNull(start + 5), "new")
        ) return false
        val constructed = index.tokens.getOrNull(start + 6) ?: return false
        if (constructed.kind != TOKEN_IDENTIFIER ||
            source.substring(constructed.start, constructed.end) != constructorName
        ) return false
        if (!isPunctuation(source, index.tokens.getOrNull(start + 7), ":")) return false
        val fallback = index.tokens.getOrNull(start + 8) ?: return false
        return range.endToken == start + 8 &&
            (isWord(source, fallback, "null") || isWord(source, fallback, "undefined"))
    }

    private fun isSafeEmptyConstructorInitializer(source: String, index: ScriptIndex, range: SourceRange): Boolean {
        val start = range.expressionStartToken
        if (start < range.startToken || !isWord(source, index.tokens.getOrNull(start), "new")) return false
        val constructor = index.tokens.getOrNull(start + 1) ?: return false
        if (constructor.kind != TOKEN_IDENTIFIER) return false
        if (source.substring(constructor.start, constructor.end) !in SAFE_EMPTY_CONSTRUCTORS) return false
        return when {
            start + 1 == range.endToken -> true
            isPunctuation(source, index.tokens.getOrNull(start + 2), "(") &&
                isPunctuation(source, index.tokens.getOrNull(start + 3), ")") &&
                start + 3 == range.endToken -> true
            else -> false
        }
    }

    private fun isBoundedLiteralConstructorInitializer(
        source: String,
        index: ScriptIndex,
        range: SourceRange,
    ): Boolean {
        val start = range.expressionStartToken
        if (start < range.startToken || !isWord(source, index.tokens.getOrNull(start), "new")) return false
        val calleeStart = start + 1
        val (calleeName, calleeEnd) = readDottedNameFrom(source, index.tokens, calleeStart)
        if (calleeName.isEmpty() || !isPunctuation(source, index.tokens.getOrNull(calleeEnd + 1), "(")) return false
        val constructor = index.declarations[calleeName] ?: return false
        if (!isFunctionValue(source, index, constructor)) return false
        val openParen = calleeEnd + 1
        val closeParen = index.matchingTokens.getOrElse(openParen) { -1 }
        if (closeParen != range.endToken || closeParen <= openParen + 1) return false
        var sawArgument = false
        var expectsArgument = true
        for (tokenIndex in openParen + 1 until closeParen) {
            val token = index.tokens[tokenIndex]
            when {
                token.kind == TOKEN_OPAQUE -> {
                    val literal = source.substring(token.start, token.end)
                    val staticString = literal.length >= 2 && literal.first() in setOf('\'', '"') &&
                        literal.last() == literal.first()
                    val numericLiteral = literal.firstOrNull()?.isDigit() == true
                    if (!expectsArgument || (!staticString && !numericLiteral)) return false
                    sawArgument = true
                    expectsArgument = false
                }
                token.kind == TOKEN_IDENTIFIER && source.substring(token.start, token.end) in
                    setOf("true", "false", "null", "undefined") -> {
                    if (!expectsArgument) return false
                    sawArgument = true
                    expectsArgument = false
                }
                isPunctuation(source, token, ",") -> {
                    if (expectsArgument) return false
                    expectsArgument = true
                }
                else -> return false
            }
        }
        return sawArgument && !expectsArgument
    }

    private fun isSafeOptionalPerformanceFallback(source: String, index: ScriptIndex, range: SourceRange): Boolean {
        var tokenIndex = range.expressionStartToken
        if (tokenIndex < range.startToken || tokenIndex > range.endToken) return false
        var performanceTerms = 0
        var termCount = 0
        while (tokenIndex <= range.endToken && termCount++ < MAX_SAFE_PERFORMANCE_FALLBACK_TERMS) {
            if (isWord(source, index.tokens.getOrNull(tokenIndex), "new")) {
                val constructor = index.tokens.getOrNull(tokenIndex + 1) ?: return false
                if (constructor.kind != TOKEN_IDENTIFIER) return false
                val constructorName = source.substring(constructor.start, constructor.end)
                val constructorDeclaration = index.declarations[constructorName] ?: return false
                if (!isFunctionValue(source, index, constructorDeclaration)) return false
                tokenIndex += 2
                if (isPunctuation(source, index.tokens.getOrNull(tokenIndex), "(")) {
                    if (!isPunctuation(source, index.tokens.getOrNull(tokenIndex + 1), ")")) return false
                    tokenIndex += 2
                }
            } else {
                val (name, endIndex) = readDottedNameFrom(source, index.tokens, tokenIndex)
                if (endIndex == tokenIndex) return false
                val property = name.substringAfterLast('.')
                if (property !in SAFE_PERFORMANCE_PROPERTIES) return false
                performanceTerms++
                tokenIndex = endIndex + 1
            }
            if (tokenIndex > range.endToken) break
            if (!isPunctuation(source, index.tokens.getOrNull(tokenIndex), "||")) return false
            tokenIndex++
        }
        return termCount <= MAX_SAFE_PERFORMANCE_FALLBACK_TERMS && performanceTerms > 0 &&
            tokenIndex > range.endToken
    }

    private fun isSafeGlobalAliasInitializer(
        source: String,
        tokens: List<JsToken>,
        expressionStartToken: Int,
        expressionEndToken: Int,
    ): Boolean {
        if (expressionStartToken < 0 || expressionStartToken != expressionEndToken) return false
        val initializer = tokens.getOrNull(expressionStartToken) ?: return false
        if (isWord(source, initializer, "this")) return true
        if (initializer.kind != TOKEN_IDENTIFIER) return false
        return source.substring(initializer.start, initializer.end) in SAFE_GLOBAL_ALIAS_NAMES
    }

    private fun isZeroArgumentFunctionFactory(source: String, index: ScriptIndex, range: SourceRange): Boolean {
        val start = range.expressionStartToken
        if (start < range.startToken || start > range.endToken) return false
        val (calleeName, calleeEnd) = readDottedNameFrom(source, index.tokens, start)
        if (calleeName.contains('.') || calleeEnd + 2 != range.endToken ||
            !isPunctuation(source, index.tokens.getOrNull(calleeEnd + 1), "(") ||
            !isPunctuation(source, index.tokens.getOrNull(calleeEnd + 2), ")")
        ) return false
        val factory = index.declarations[calleeName] ?: return false
        val factoryChars = index.tokens[factory.range.endToken].end - index.tokens[factory.range.startToken].start
        return factoryChars <= MAX_DIAGNOSTIC_FUNCTION_ARGUMENT_CHARS && isFunctionValue(source, index, factory)
    }

    private fun isFunctionValue(source: String, index: ScriptIndex, declaration: IndexedDeclaration): Boolean {
        if (declaration.kind == DeclarationKind.RAW_FUNCTION || declaration.kind == DeclarationKind.RAW_CLASS) return true
        if (declaration.kind == DeclarationKind.VALUE_ASSIGNMENT &&
            isObjectFrozenFunctionInitializer(source, index, declaration.range)
        ) return true
        var expressionStart = declaration.range.expressionStartToken
        if (expressionStart < declaration.range.startToken || expressionStart > declaration.range.endToken) return false
        var assignmentDepth = 0
        while (expressionStart <= declaration.range.endToken && assignmentDepth++ < 8) {
            if (isWord(source, index.tokens.getOrNull(expressionStart), "function") ||
                isWord(source, index.tokens.getOrNull(expressionStart), "class") ||
                isFunctionOrArrowAt(source, index.tokens, index.matchingTokens, expressionStart)
            ) return true
            if (index.tokens.getOrNull(expressionStart)?.kind != TOKEN_IDENTIFIER ||
                !isPunctuation(source, index.tokens.getOrNull(expressionStart + 1), "=")
            ) return false
            expressionStart += 2
        }
        return false
    }

    private fun isClassValue(source: String, index: ScriptIndex, declaration: IndexedDeclaration): Boolean {
        if (declaration.kind == DeclarationKind.RAW_CLASS) return true
        var expressionStart = declaration.range.expressionStartToken
        if (expressionStart < declaration.range.startToken || expressionStart > declaration.range.endToken) return false
        var assignmentDepth = 0
        while (expressionStart <= declaration.range.endToken && assignmentDepth++ < 8) {
            if (isWord(source, index.tokens.getOrNull(expressionStart), "class")) return true
            val (calleeName, calleeEnd) = readDottedNameFrom(source, index.tokens, expressionStart)
            if (calleeName == "Object.freeze" &&
                isPunctuation(source, index.tokens.getOrNull(calleeEnd + 1), "(") &&
                isWord(source, index.tokens.getOrNull(calleeEnd + 2), "class")
            ) return true
            if (index.tokens.getOrNull(expressionStart)?.kind != TOKEN_IDENTIFIER ||
                !isPunctuation(source, index.tokens.getOrNull(expressionStart + 1), "=")
            ) return false
            expressionStart += 2
        }
        return false
    }

    private fun isObjectFrozenFunctionInitializer(source: String, index: ScriptIndex, range: SourceRange): Boolean {
        val start = range.expressionStartToken
        if (start < range.startToken) return false
        val (calleeName, calleeEnd) = readDottedNameFrom(source, index.tokens, start)
        if (calleeName != "Object.freeze" ||
            !isPunctuation(source, index.tokens.getOrNull(calleeEnd + 1), "(")
        ) return false
        val openParen = calleeEnd + 1
        val closeParen = index.matchingTokens.getOrElse(openParen) { -1 }
        if (closeParen != range.endToken || closeParen <= openParen + 1) return false
        val functionStart = openParen + 1
        if (isFunctionOrArrowAt(source, index.tokens, index.matchingTokens, functionStart)) {
            return functionOrArrowEnd(source, index.tokens, index.matchingTokens, functionStart) == closeParen - 1
        }
        return classExpressionEnd(source, index.tokens, index.matchingTokens, functionStart) == closeParen - 1
    }

    private fun classBodyOpenIndex(
        source: String,
        tokens: List<JsToken>,
        matchingTokens: IntArray,
        startIndex: Int,
    ): Int {
        var tokenIndex = startIndex
        while (tokenIndex < tokens.size) {
            if (isPunctuation(source, tokens.getOrNull(tokenIndex), "{")) return tokenIndex
            if (isPunctuation(source, tokens.getOrNull(tokenIndex), "(") ||
                isPunctuation(source, tokens.getOrNull(tokenIndex), "[")
            ) {
                val close = matchingTokens.getOrElse(tokenIndex) { -1 }
                if (close < 0) return -1
                tokenIndex = close + 1
                continue
            }
            if (isPunctuation(source, tokens.getOrNull(tokenIndex), ";")) return -1
            tokenIndex++
        }
        return -1
    }

    private fun classExpressionEnd(
        source: String,
        tokens: List<JsToken>,
        matchingTokens: IntArray,
        expressionStart: Int,
    ): Int {
        if (!isWord(source, tokens.getOrNull(expressionStart), "class")) return -1
        val bodyOpen = classBodyOpenIndex(source, tokens, matchingTokens, expressionStart + 1)
        return if (bodyOpen >= 0) matchingTokens.getOrElse(bodyOpen) { -1 } else -1
    }

    private fun isCallArgumentReference(
        source: String,
        index: ScriptIndex,
        scope: SourceRange,
        tokenIndex: Int,
    ): Boolean {
        for (openIndex in tokenIndex - 1 downTo scope.startToken) {
            if (!isPunctuation(source, index.tokens.getOrNull(openIndex), "(")) continue
            val closeIndex = index.matchingTokens.getOrElse(openIndex) { -1 }
            if (closeIndex <= tokenIndex) continue
            val calleeIndex = openIndex - 1
            val callee = index.tokens.getOrNull(calleeIndex) ?: continue
            if (callee.kind == TOKEN_IDENTIFIER) {
                val calleeName = source.substring(callee.start, callee.end)
                if (calleeName !in CALL_CONTROL_KEYWORDS) return true
                continue
            }
            // Parenthesized values and computed members can put a grouping close or `]` between
            // the call's opening parenthesis and its callee. Skip grouping pairs that are part
            // of the argument expression, and recognize callable expressions ending in `)`/`]`.
            if (isPunctuation(source, callee, "]")) {
                val openBracket = index.matchingTokens.getOrElse(calleeIndex) { -1 }
                if (openBracket > scope.startToken) return true
                continue
            }
            if (isPunctuation(source, callee, ")")) {
                val groupedOpen = index.matchingTokens.getOrElse(calleeIndex) { -1 }
                if (groupedOpen <= scope.startToken) continue
                val groupedCallee = index.tokens.getOrNull(groupedOpen + 1) ?: continue
                if (isWord(source, groupedCallee, "function") || isWord(source, groupedCallee, "class") ||
                    groupedCallee.kind == TOKEN_IDENTIFIER || isPunctuation(source, groupedCallee, "(")
                ) return true
            }
        }
        return false
    }

    private fun isConstructorArgumentReference(
        source: String,
        index: ScriptIndex,
        scope: SourceRange,
        tokenIndex: Int,
    ): Boolean {
        for (openIndex in tokenIndex - 1 downTo scope.startToken) {
            if (!isPunctuation(source, index.tokens.getOrNull(openIndex), "(")) continue
            if (index.matchingTokens.getOrElse(openIndex) { -1 } != tokenIndex + 1) continue
            val targetStart = dottedNameStartIndex(source, index.tokens, openIndex - 1)
            return targetStart >= scope.startToken &&
                isWord(source, index.tokens.getOrNull(targetStart - 1), "new")
        }
        return false
    }

    private fun addParameterIdentifiers(source: String, tokens: List<JsToken>, start: Int, end: Int, target: MutableSet<String>) {
        for (tokenIndex in start..end) {
            val token = tokens.getOrNull(tokenIndex) ?: continue
            if (token.kind == TOKEN_IDENTIFIER) target.add(source.substring(token.start, token.end))
        }
    }

    private fun findMarkerReferencedFunction(source: String, declarations: Set<String>): String? {
        for (marker in throttlingMarker.findAll(source)) {
            val continuationStart = marker.range.last + 1
            val continuationEnd = minOf(source.length, continuationStart + 256)
            if (!markerContinuation.containsMatchIn(source.substring(continuationStart, continuationEnd))) continue

            val searchStart = maxOf(0, marker.range.first - MARKER_LOOKBACK_CHARS)
            val prefix = source.substring(searchStart, marker.range.first)
            val candidate = functionAssignment.findAll(prefix).lastOrNull()?.groupValues?.get(1)
            if (candidate != null && candidate in declarations) return candidate
        }
        return null
    }

    private fun findSignatureArgumentPrefix(source: String, function: String?, declarations: Set<String>): String? {
        if (function == null) return null
        val pattern = Regex("\\b$IDENTIFIER&&\\($IDENTIFIER=($IDENTIFIER)\\((?:([0-9]+,))?decodeURIComponent\\($IDENTIFIER\\)\\)")
        return pattern.findAll(source).firstOrNull { match ->
            match.groupValues[1] == function && match.groupValues[1] in declarations
        }?.groups?.get(2)?.value
    }

    private fun findReferencedCandidates(source: String, patterns: List<Regex>, declarations: Set<String>): List<String> {
        val candidates = LinkedHashSet<String>()
        for (pattern in patterns) {
            for (match in pattern.findAll(source)) {
                val name = match.groupValues.getOrNull(1) ?: continue
                if (name in declarations && functionName.matches(name)) candidates.add(name)
                if (candidates.size >= MAX_DIAGNOSTIC_CANDIDATES) return candidates.toList()
            }
        }
        return candidates.toList()
    }

    private fun logGetNCallsiteContexts(
        source: String,
        index: ScriptIndex,
        declarations: Set<String>,
        onDiagnosticStage: ((String) -> Unit)?,
    ) {
        if (onDiagnosticStage == null) return
        var logged = 0
        val referencedNames = LinkedHashSet<String>()
        for (tokenIndex in 0 until index.tokens.size - 4) {
            if (!isPunctuation(source, index.tokens[tokenIndex], ".") ||
                !isWord(source, index.tokens.getOrNull(tokenIndex + 1), "get") ||
                !isPunctuation(source, index.tokens.getOrNull(tokenIndex + 2), "(")
            ) continue
            val keyToken = index.tokens.getOrNull(tokenIndex + 3) ?: continue
            if (keyToken.kind != TOKEN_OPAQUE || source.substring(keyToken.start, keyToken.end) !in setOf("\"n\"", "'n'")) continue
            if (!isPunctuation(source, index.tokens.getOrNull(tokenIndex + 4), ")")) continue

            val before = ArrayList<String>()
            for (openIndex in maxOf(0, tokenIndex - 96)..tokenIndex) {
                if (!isPunctuation(source, index.tokens.getOrNull(openIndex), "(")) continue
                if (index.matchingTokens.getOrElse(openIndex) { -1 } <= tokenIndex + 4) continue
                val caller = readDottedNameEndingAt(source, index.tokens, openIndex - 1) ?: continue
                if (caller in declarations) before.add("$caller@${tokenIndex - openIndex}")
            }

            val after = ArrayList<String>()
            for (callIndex in tokenIndex + 5..minOf(index.tokens.lastIndex - 1, tokenIndex + 48)) {
                if (index.tokens[callIndex].kind != TOKEN_IDENTIFIER ||
                    !isPunctuation(source, index.tokens.getOrNull(callIndex + 1), "(")
                ) continue
                val (callee, _) = readDottedNameFrom(source, index.tokens, callIndex)
                if (callee in declarations) after.add("$callee@${callIndex - tokenIndex}")
            }

            val window = (maxOf(0, tokenIndex - 6)..minOf(index.tokens.lastIndex, tokenIndex + 18)).joinToString(" ") { currentIndex ->
                val token = index.tokens[currentIndex]
                when (token.kind) {
                    TOKEN_IDENTIFIER -> source.substring(token.start, token.end)
                    TOKEN_OPAQUE -> "<literal>"
                    else -> source.substring(token.start, token.end)
                }
            }
            val owners = enclosingDeclarationNames(index, tokenIndex)
            owners.mapTo(referencedNames) { it.substringBefore(':') }
            onDiagnosticStage("locatorGetNContext; index=$logged; owners=${owners.joinToString(",")}; before=${before.take(6).joinToString(",")}; after=${after.take(6).joinToString(",")}; tokens=$window")
            if (++logged >= 4) break
        }
        logCallReferenceContexts(source, index, referencedNames, onDiagnosticStage)
    }

    private fun logNPropertyContexts(
        source: String,
        index: ScriptIndex,
        onDiagnosticStage: ((String) -> Unit)?,
    ) {
        if (onDiagnosticStage == null) return
        var logged = 0
        for (tokenIndex in 0 until index.tokens.size - 1) {
            if (!isPunctuation(source, index.tokens[tokenIndex], ".") ||
                !isWord(source, index.tokens.getOrNull(tokenIndex + 1), "n")
            ) continue
            val start = maxOf(0, tokenIndex - 8)
            val end = minOf(index.tokens.lastIndex, tokenIndex + 10)
            val window = (start..end).joinToString(" ") { currentIndex ->
                val token = index.tokens[currentIndex]
                when (token.kind) {
                    TOKEN_IDENTIFIER -> source.substring(token.start, token.end)
                    TOKEN_OPAQUE -> "<literal>"
                    else -> source.substring(token.start, token.end)
                }
            }
            val owners = enclosingDeclarationNames(index, tokenIndex)
            onDiagnosticStage("locatorNPropertyContext; owners=${owners.joinToString(",")}; tokens=$window")
            if (++logged >= 8) break
        }
    }

    private fun logNStringLiteralContexts(
        source: String,
        index: ScriptIndex,
        onDiagnosticStage: ((String) -> Unit)?,
    ) {
        if (onDiagnosticStage == null) return
        var logged = 0
        for (tokenIndex in index.tokens.indices) {
            val token = index.tokens[tokenIndex]
            if (token.kind != TOKEN_OPAQUE || source.substring(token.start, token.end) !in setOf("\"n\"", "'n'")) continue
            val start = maxOf(0, tokenIndex - 8)
            val end = minOf(index.tokens.lastIndex, tokenIndex + 10)
            val window = (start..end).joinToString(" ") { currentIndex ->
                val current = index.tokens[currentIndex]
                when (current.kind) {
                    TOKEN_IDENTIFIER -> source.substring(current.start, current.end)
                    TOKEN_OPAQUE -> "<literal>"
                    else -> source.substring(current.start, current.end)
                }
            }
            val owners = enclosingDeclarationNames(index, tokenIndex)
            onDiagnosticStage("locatorNStringContext; owners=${owners.joinToString(",")}; tokens=$window")
            if (++logged >= 12) break
        }
    }

    /** Reports only token shapes and enclosing minified symbols around signature-related literals. */
    private fun logSignatureLiteralContexts(
        source: String,
        index: ScriptIndex,
        onDiagnosticStage: ((String) -> Unit)?,
    ) {
        if (onDiagnosticStage == null) return
        var logged = 0
        var signatureLiterals = 0
        var cipherLiterals = 0
        var cipherIdentifiers = 0
        for (tokenIndex in index.tokens.indices) {
            val token = index.tokens[tokenIndex]
            val literal = source.substring(token.start, token.end)
            val kind = when (literal) {
                "signatureCipher" -> if (token.kind == TOKEN_IDENTIFIER) "signatureCipher_identifier" else continue
                "\"signature\"", "'signature'" -> if (token.kind == TOKEN_OPAQUE) "signature" else continue
                "\"signatureCipher\"", "'signatureCipher'" -> if (token.kind == TOKEN_OPAQUE) "signatureCipher" else continue
                else -> continue
            }
            when (kind) {
                "signature" -> signatureLiterals++
                "signatureCipher" -> cipherLiterals++
                else -> cipherIdentifiers++
            }
            if (logged >= MAX_SIGNATURE_CONTEXTS) continue
            val owners = enclosingDeclarationNames(index, tokenIndex)
            val start = maxOf(0, tokenIndex - 12)
            val end = minOf(index.tokens.lastIndex, tokenIndex + 24)
            val shape = (start..end).joinToString(" ") { currentIndex ->
                val current = index.tokens[currentIndex]
                if (current.kind == TOKEN_OPAQUE) "<literal>" else source.substring(current.start, current.end)
            }
            val previews = owners.take(2).mapNotNull { owner ->
                declarationTokenPreview(source, index, owner.substringBefore(':'))?.take(360)
            }
            onDiagnosticStage(
                "locatorSignatureLiteral; kind=$kind; owners=${owners.joinToString(",")}; previews=${previews.joinToString(" || ")}; tokens=$shape",
            )
            logged++
        }
        onDiagnosticStage(
            "locatorSignatureLiteralSummary; signature=$signatureLiterals; signatureCipher=$cipherLiterals; " +
                "signatureCipherIdentifiers=$cipherIdentifiers; contexts=$logged",
        )
    }

    private fun logSetNCallsiteContexts(
        source: String,
        index: ScriptIndex,
        onDiagnosticStage: ((String) -> Unit)?,
    ) {
        if (onDiagnosticStage == null) return
        var logged = 0
        for (tokenIndex in 0 until index.tokens.size - 5) {
            if (!isPunctuation(source, index.tokens[tokenIndex], ".") ||
                !isWord(source, index.tokens.getOrNull(tokenIndex + 1), "set") ||
                !isPunctuation(source, index.tokens.getOrNull(tokenIndex + 2), "(")
            ) continue
            val key = index.tokens.getOrNull(tokenIndex + 3) ?: continue
            if (key.kind != TOKEN_OPAQUE || source.substring(key.start, key.end) !in setOf("\"n\"", "'n'")) continue
            val owners = enclosingDeclarationNames(index, tokenIndex)
            val window = (maxOf(0, tokenIndex - 10)..minOf(index.tokens.lastIndex, tokenIndex + 22)).joinToString(" ") { currentIndex ->
                val token = index.tokens[currentIndex]
                if (token.kind == TOKEN_OPAQUE) "<literal>" else source.substring(token.start, token.end)
            }
            val previews = owners.mapNotNull { owner -> declarationTokenPreview(source, index, owner.substringBefore(':')) }
            onDiagnosticStage(
                "locatorSetNContext; owners=${owners.joinToString(",")}; previews=${previews.joinToString(" || ")}; tokens=$window",
            )
            if (++logged >= 8) break
        }
    }

    private fun logChallengeMarkerContexts(
        source: String,
        index: ScriptIndex,
        onDiagnosticStage: ((String) -> Unit)?,
    ) {
        if (onDiagnosticStage == null) return
        var logged = 0
        val referencedNames = LinkedHashSet<String>()
        for (marker in throttlingMarker.findAll(source)) {
            val tokenIndex = tokenIndexAt(index.tokens, marker.range.first)
            if (tokenIndex < 0) continue
            val searchStart = maxOf(0, marker.range.first - MARKER_LOOKBACK_CHARS)
            val prefix = source.substring(searchStart, marker.range.first)
            val assignedCandidate = functionAssignment.findAll(prefix).lastOrNull()?.groupValues?.get(1)
            if (assignedCandidate != null) referencedNames.add(assignedCandidate)
            val endIndex = minOf(index.tokens.lastIndex, tokenIndex + 8)
            val window = (maxOf(0, tokenIndex - 8)..endIndex).joinToString(" ") { currentIndex ->
                val token = index.tokens[currentIndex]
                if (token.kind == TOKEN_OPAQUE) "<literal>" else source.substring(token.start, token.end)
            }
            val owners = enclosingDeclarationNames(index, tokenIndex)
            owners.mapTo(referencedNames) { it.substringBefore(':') }
            val previews = owners.mapNotNull { owner -> declarationTokenPreview(source, index, owner.substringBefore(':')) }
            onDiagnosticStage("locatorAlrMarker; index=$logged; assigned=$assignedCandidate; owners=${owners.joinToString(",")}; previews=${previews.joinToString(" || ")}; tokens=$window")
            if (++logged >= MAX_DIAGNOSTIC_CANDIDATES) return
        }
        logCallReferenceContexts(source, index, referencedNames, onDiagnosticStage)
    }

    private fun logCallReferenceContexts(
        source: String,
        index: ScriptIndex,
        names: Set<String>,
        onDiagnosticStage: ((String) -> Unit),
    ) {
        if (names.isEmpty()) return
        val locations = HashMap<String, MutableList<Int>>()
        for (name in names) locations[name] = ArrayList()
        for (tokenIndex in 0 until index.tokens.size - 1) {
            val token = index.tokens[tokenIndex]
            if (token.kind != TOKEN_IDENTIFIER || !isPunctuation(source, index.tokens.getOrNull(tokenIndex + 1), "(")) continue
            val name = source.substring(token.start, token.end)
            locations[name]?.let { found -> if (found.size < 8) found.add(tokenIndex) }
        }
        for ((name, references) in locations) {
            for ((referenceIndex, tokenIndex) in references.withIndex()) {
                val start = maxOf(0, tokenIndex - 8)
                val end = minOf(index.tokens.lastIndex, tokenIndex + 12)
                val window = (start..end).joinToString(" ") { currentIndex ->
                    val token = index.tokens[currentIndex]
                    if (token.kind == TOKEN_OPAQUE) "<literal>" else source.substring(token.start, token.end)
                }
                val owners = enclosingDeclarationNames(index, tokenIndex)
                onDiagnosticStage("locatorFunctionReference; name=$name; index=$referenceIndex; owners=${owners.joinToString(",")}; tokens=$window")
            }
        }
    }

    private fun enclosingDeclarationNames(index: ScriptIndex, tokenIndex: Int): List<String> =
        index.declarations.entries.mapNotNull { (name, declaration) ->
            val range = declaration.memberRange ?: declaration.range
            if (tokenIndex < range.startToken || tokenIndex > range.endToken) return@mapNotNull null
            val span = index.tokens[range.endToken].end - index.tokens[range.startToken].start
            "$name:${declaration.kind}:$span"
        }.sortedBy { it.substringAfterLast(':').toIntOrNull() ?: Int.MAX_VALUE }.take(4)

    private fun tokenIndexAt(tokens: List<JsToken>, sourceOffset: Int): Int {
        var low = 0
        var high = tokens.lastIndex
        while (low <= high) {
            val middle = (low + high) ushr 1
            val token = tokens[middle]
            when {
                sourceOffset < token.start -> high = middle - 1
                sourceOffset >= token.end -> low = middle + 1
                else -> return middle
            }
        }
        return -1
    }

    private fun declarationTokenPreview(source: String, index: ScriptIndex, name: String): String? {
        val declaration = index.declarations[name] ?: return null
        if (declaration.kind == DeclarationKind.OBJECT_ASSIGNMENT) return null
        val range = declaration.memberRange ?: declaration.range
        val chars = index.tokens[range.endToken].end - index.tokens[range.startToken].start
        if (chars > 4096) return null
        val preview = (range.startToken..minOf(range.endToken, range.startToken + 120)).joinToString(" ") { tokenIndex ->
            val token = index.tokens[tokenIndex]
            if (token.kind == TOKEN_OPAQUE) "<literal>" else source.substring(token.start, token.end)
        }
        return "$name=$preview".take(320)
    }

    private fun buildDeclarationIndex(source: String, onDiagnosticStage: ((String) -> Unit)?): ScriptIndex {
        val tokenizationStartedAt = System.nanoTime()
        val tokens = tokenize(source)
        onDiagnosticStage?.invoke(
            "locatorTokens; wallMs=${(System.nanoTime() - tokenizationStartedAt) / 1_000_000L}; count=${tokens.size}",
        )
        val matchingTokens = buildMatchingPairs(source, tokens)

        val declarations = HashMap<String, IndexedDeclaration>()
        val objectScopes = java.util.ArrayDeque<ObjectScope>()
        val moduleScopeDepth = if (source.contains("var _yt_player={};(function(g){") || source.contains("})(_yt_player);")) 1 else 0
        onDiagnosticStage?.invoke("locatorModuleScope; depth=$moduleScopeDepth")
        var braceDepth = 0
        var parenthesisDepth = 0
        var bracketDepth = 0
        for (index in tokens.indices) {
            val token = tokens[index]
            if (braceDepth <= moduleScopeDepth && parenthesisDepth == moduleScopeDepth && isWord(source, token, "function")) {
                var nameIndex = index + 1
                if (isPunctuation(source, tokens.getOrNull(nameIndex), "*")) nameIndex++
                val nameToken = tokens.getOrNull(nameIndex)
                if (nameToken?.kind == TOKEN_IDENTIFIER && isPunctuation(source, tokens.getOrNull(nameIndex + 1), "(")) {
                    val name = source.substring(nameToken.start, nameToken.end)
                    val close = matchingTokens.getOrElse(nameIndex + 1) { -1 }
                    val bodyOpen = if (close >= 0 && isPunctuation(source, tokens.getOrNull(close + 1), "{")) close + 1 else -1
                    val bodyClose = if (bodyOpen >= 0) matchingTokens.getOrElse(bodyOpen) { -1 } else -1
                    if (bodyClose >= 0 && functionName.matches(name)) {
                        declarations[name] = IndexedDeclaration(
                            SourceRange(index, bodyClose),
                            DeclarationKind.RAW_FUNCTION,
                            name,
                        )
                    }
                }
            }

            val previousToken = tokens.getOrNull(index - 1)
            val isClassDeclarationPosition = index == 0 ||
                isPunctuation(source, previousToken, ";") ||
                isPunctuation(source, previousToken, "{") ||
                isPunctuation(source, previousToken, "}") ||
                isWord(source, previousToken, "export") ||
                isWord(source, previousToken, "default")
            if (braceDepth <= moduleScopeDepth && parenthesisDepth == moduleScopeDepth && isClassDeclarationPosition &&
                isWord(source, token, "class")
            ) {
                val nameToken = tokens.getOrNull(index + 1)
                val bodyOpen = classBodyOpenIndex(source, tokens, matchingTokens, index + 1)
                val bodyClose = if (bodyOpen >= 0) matchingTokens.getOrElse(bodyOpen) { -1 } else -1
                if (nameToken?.kind == TOKEN_IDENTIFIER && !isWord(source, nameToken, "extends") && bodyClose >= 0) {
                    val name = source.substring(nameToken.start, nameToken.end)
                    if (functionName.matches(name)) {
                        declarations[name] = IndexedDeclaration(
                            SourceRange(index, bodyClose),
                            DeclarationKind.RAW_CLASS,
                            name,
                        )
                    }
                }
            }

            if (token.kind == TOKEN_IDENTIFIER) {
                if (braceDepth <= moduleScopeDepth &&
                    (isWord(source, token, "var") || isWord(source, token, "let") || isWord(source, token, "const"))
                ) {
                    var declaratorIndex = index + 1
                    while (declaratorIndex < tokens.size && tokens[declaratorIndex].kind == TOKEN_IDENTIFIER) {
                        val assignment = assignmentTarget(source, tokens, declaratorIndex)
                        if (assignment == null) {
                            val name = source.substring(tokens[declaratorIndex].start, tokens[declaratorIndex].end)
                            val next = tokens.getOrNull(declaratorIndex + 1)
                            if (functionName.matches(name) &&
                                (isPunctuation(source, next, ",") || isPunctuation(source, next, ";") ||
                                    declaratorIndex == tokens.lastIndex)
                            ) {
                                declarations.putIfAbsent(
                                    name,
                                    IndexedDeclaration(
                                        SourceRange(declaratorIndex, declaratorIndex, declaratorIndex),
                                        DeclarationKind.UNINITIALIZED_VALUE,
                                        name,
                                    ),
                                )
                            }
                            if (isPunctuation(source, next, ",")) {
                                declaratorIndex += 2
                                continue
                            }
                            break
                        }
                        val initializerEnd = variableInitializerEnd(source, tokens, matchingTokens, assignment.rhsIndex)
                        if (initializerEnd >= assignment.rhsIndex && functionName.matches(assignment.name)) {
                            declarations.putIfAbsent(
                                assignment.name,
                                IndexedDeclaration(
                                    SourceRange(declaratorIndex, initializerEnd, assignment.rhsIndex),
                                    DeclarationKind.VALUE_ASSIGNMENT,
                                    assignment.name,
                                ),
                            )
                        }
                        declaratorIndex = initializerEnd + 1
                        if (isPunctuation(source, tokens.getOrNull(declaratorIndex), ",")) {
                            declaratorIndex++
                        } else {
                            break
                        }
                    }
                }

                if (braceDepth <= moduleScopeDepth && parenthesisDepth == moduleScopeDepth && bracketDepth == 0) {
                    val assignment = assignmentTarget(source, tokens, index)
                    if (assignment != null && functionName.matches(assignment.name)) {
                        val expressionEnd = functionOrArrowEnd(source, tokens, matchingTokens, assignment.rhsIndex)
                        val classEnd = classExpressionEnd(source, tokens, matchingTokens, assignment.rhsIndex)
                        val isFunctionOrArrow = isFunctionOrArrowAt(source, tokens, matchingTokens, assignment.rhsIndex) &&
                            expressionEnd >= assignment.rhsIndex
                        if (isFunctionOrArrow || classEnd >= assignment.rhsIndex) {
                            val initializerEnd = if (classEnd >= assignment.rhsIndex) classEnd else expressionEnd
                            declarations[assignment.name] = IndexedDeclaration(
                                SourceRange(index, initializerEnd, assignment.rhsIndex),
                                if (assignment.name.contains('.')) DeclarationKind.DOTTED_ASSIGNMENT else DeclarationKind.ROOT_ASSIGNMENT,
                                assignment.name,
                            )
                        } else if (assignment.name.contains('.')) {
                            val initializerEnd = variableInitializerEnd(source, tokens, matchingTokens, assignment.rhsIndex)
                            if (initializerEnd >= assignment.rhsIndex) {
                                declarations.putIfAbsent(
                                    assignment.name,
                                    IndexedDeclaration(
                                        SourceRange(index, initializerEnd, assignment.rhsIndex),
                                DeclarationKind.DOTTED_VALUE_ASSIGNMENT,
                                        assignment.name,
                                    ),
                                )
                            }
                        } else {
                            val initializerEnd = variableInitializerEnd(source, tokens, matchingTokens, assignment.rhsIndex)
                            if (isSafeGlobalAliasInitializer(source, tokens, assignment.rhsIndex, initializerEnd)) {
                                declarations[assignment.name] = IndexedDeclaration(
                                    SourceRange(index, initializerEnd, assignment.rhsIndex),
                                    DeclarationKind.VALUE_ASSIGNMENT,
                                    assignment.name,
                                )
                            }
                        }
                    }
                }

                val scope = objectScopes.peekLast()
                if (scope != null && scope.depth == braceDepth) {
                    val key = source.substring(token.start, token.end)
                    val next = index + 1
                    if (isPunctuation(source, tokens.getOrNull(next), ":") &&
                        isFunctionOrArrowAt(source, tokens, matchingTokens, next + 1)
                    ) {
                        val methodName = "${scope.owner}.$key"
                        scope.methods.add(methodName)
                        val end = functionOrArrowEnd(source, tokens, matchingTokens, next + 1)
                        if (end >= next + 1) scope.methodRanges[methodName] = SourceRange(next + 1, end)
                    } else if (isPunctuation(source, tokens.getOrNull(next), "(")) {
                        val close = matchingTokens.getOrElse(next) { -1 }
                        if (close >= 0 && isPunctuation(source, tokens.getOrNull(close + 1), "{")) {
                            val bodyClose = matchingTokens.getOrElse(close + 1) { -1 }
                            if (bodyClose >= 0) {
                                val methodName = "${scope.owner}.$key"
                                scope.methods.add(methodName)
                                scope.methodRanges[methodName] = SourceRange(index, bodyClose)
                            }
                        }
                    }
                }
            }

            if (isPunctuation(source, token, "[")) {
                val equalsIndex = index - 1
                val owner = if (braceDepth == moduleScopeDepth && parenthesisDepth == moduleScopeDepth && bracketDepth == 0 &&
                    isPunctuation(source, tokens.getOrNull(equalsIndex), "=")
                ) {
                    readDottedNameEndingAt(source, tokens, equalsIndex - 1)
                } else null
                val close = matchingTokens.getOrElse(index) { -1 }
                if (owner != null && close >= 0 && functionName.matches(owner)) {
                    val ownerStartIndex = dottedNameStartIndex(source, tokens, equalsIndex - 1)
                    val range = SourceRange(ownerStartIndex, close, index)
                    declarations[owner] = IndexedDeclaration(range, DeclarationKind.OBJECT_ASSIGNMENT, owner)
                }
            } else if (isPunctuation(source, token, "{")) {
                val equalsIndex = index - 1
                val owner = if (isPunctuation(source, tokens.getOrNull(equalsIndex), "=")) {
                    readDottedNameEndingAt(source, tokens, equalsIndex - 1)
                } else null
                val parentDepth = braceDepth
                braceDepth++
                if (parentDepth == moduleScopeDepth && parenthesisDepth == moduleScopeDepth && bracketDepth == 0 &&
                    owner != null && functionName.matches(owner)
                ) {
                    val ownerStartIndex = dottedNameStartIndex(source, tokens, equalsIndex - 1)
                    objectScopes.addLast(ObjectScope(owner, braceDepth, index, ownerStartIndex))
                }
            } else if (isPunctuation(source, token, "}")) {
                val scope = objectScopes.peekLast()
                if (scope != null && scope.depth == braceDepth) {
                    objectScopes.removeLast()
                    val range = SourceRange(scope.assignmentStartIndex, index, scope.openIndex)
                    val declaration = IndexedDeclaration(range, DeclarationKind.OBJECT_ASSIGNMENT, scope.owner)
                    declarations[scope.owner] = declaration
                    for (method in scope.methods) {
                        declarations[method] = declaration.copy(memberRange = scope.methodRanges[method])
                    }
                }
                braceDepth = (braceDepth - 1).coerceAtLeast(0)
            }
            if (isPunctuation(source, token, "(")) parenthesisDepth++
            else if (isPunctuation(source, token, ")")) parenthesisDepth = (parenthesisDepth - 1).coerceAtLeast(0)
            if (isPunctuation(source, token, "[")) bracketDepth++
            else if (isPunctuation(source, token, "]")) bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
        }

        onDiagnosticStage?.invoke("locatorDeclarations; count=${declarations.size}")
        return ScriptIndex(tokens, matchingTokens, declarations)
    }

    private fun tokenize(source: String): List<JsToken> {
        val tokens = ArrayList<JsToken>(minOf(source.length / 3, 1_250_000))
        var index = 0
        while (index < source.length) {
            val char = source[index]
            if (char.isWhitespace()) {
                index++
                continue
            }

            if (char == '/' && index + 1 < source.length) {
                val next = source[index + 1]
                if (next == '/') {
                    index += 2
                    while (index < source.length && source[index] != '\n' && source[index] != '\r') index++
                    continue
                }
                if (next == '*') {
                    index += 2
                    while (index + 1 < source.length && !(source[index] == '*' && source[index + 1] == '/')) index++
                    index = (index + 2).coerceAtMost(source.length)
                    continue
                }
                if (canStartRegularExpression(source, tokens)) {
                    val start = index
                    index = skipRegularExpression(source, index)
                    tokens.add(JsToken(start, index, TOKEN_OPAQUE))
                    continue
                }
            }

            if (char == '\'' || char == '"') {
                val start = index
                index = skipQuoted(source, index, char)
                tokens.add(JsToken(start, index, TOKEN_OPAQUE))
                continue
            }
            if (char == '`') {
                val start = index
                index = skipQuoted(source, index, '`')
                tokens.add(JsToken(start, index, TOKEN_OPAQUE))
                continue
            }
            if (isIdentifierStart(char)) {
                val start = index++
                while (index < source.length && isIdentifierPart(source[index])) index++
                tokens.add(JsToken(start, index, TOKEN_IDENTIFIER))
                continue
            }
            if (char.isDigit()) {
                val start = index++
                while (index < source.length && (source[index].isLetterOrDigit() || source[index] == '.' || source[index] == '_')) index++
                tokens.add(JsToken(start, index, TOKEN_OPAQUE))
                continue
            }

            val start = index
            index += multiCharacterPunctuationAt(source, index)
            tokens.add(JsToken(start, index, TOKEN_PUNCTUATION))
        }
        return tokens
    }

    private fun canStartRegularExpression(source: String, tokens: List<JsToken>): Boolean {
        val previous = tokens.lastOrNull() ?: return true
        if (previous.kind == TOKEN_IDENTIFIER) {
            return isWord(source, previous, "return") || isWord(source, previous, "throw") ||
                isWord(source, previous, "case") || isWord(source, previous, "yield") ||
                isWord(source, previous, "await") || isWord(source, previous, "else")
        }
        return isPunctuation(source, previous, "(") || isPunctuation(source, previous, "[") ||
            isPunctuation(source, previous, "{") || isPunctuation(source, previous, "=") ||
            isPunctuation(source, previous, ":") || isPunctuation(source, previous, ",") ||
            isPunctuation(source, previous, ";") || isPunctuation(source, previous, "!") ||
            isPunctuation(source, previous, "?") || isPunctuation(source, previous, "=>") ||
            isPunctuation(source, previous, "&&") || isPunctuation(source, previous, "||") ||
            isPunctuation(source, previous, "??")
    }

    private fun skipRegularExpression(source: String, start: Int): Int {
        var index = start + 1
        var escaped = false
        var inCharacterClass = false
        while (index < source.length) {
            val char = source[index++]
            if (escaped) {
                escaped = false
            } else if (char == '\\') {
                escaped = true
            } else if (char == '[') {
                inCharacterClass = true
            } else if (char == ']') {
                inCharacterClass = false
            } else if (char == '/' && !inCharacterClass) {
                while (index < source.length && isIdentifierPart(source[index])) index++
                return index
            } else if (char == '\n' || char == '\r') {
                return index
            }
        }
        return index
    }

    private fun skipQuoted(source: String, start: Int, quote: Char): Int {
        var index = start + 1
        while (index < source.length) {
            val char = source[index++]
            if (char == '\\') {
                if (index < source.length) index++
            } else if (char == quote) {
                break
            }
        }
        return index
    }

    private fun multiCharacterPunctuationAt(source: String, index: Int): Int {
        for (value in MULTI_CHARACTER_PUNCTUATION) if (source.startsWith(value, index)) return value.length
        return 1
    }

    private fun assignmentTarget(source: String, tokens: List<JsToken>, startIndex: Int): AssignmentTarget? {
        if (isPunctuation(source, tokens.getOrNull(startIndex - 1), ".")) return null
        var endIndex = startIndex
        var segmentCount = 1
        while (isPunctuation(source, tokens.getOrNull(endIndex + 1), ".") &&
            tokens.getOrNull(endIndex + 2)?.kind == TOKEN_IDENTIFIER && segmentCount < 5
        ) {
            endIndex += 2
            segmentCount++
        }
        val equalsIndex = endIndex + 1
        if (!isPunctuation(source, tokens.getOrNull(equalsIndex), "=")) return null
        val rhsIndex = equalsIndex + 1
        return AssignmentTarget(source.substring(tokens[startIndex].start, tokens[endIndex].end), rhsIndex)
    }

    private fun variableInitializerEnd(
        source: String,
        tokens: List<JsToken>,
        matchingTokens: IntArray,
        startIndex: Int,
    ): Int {
        var index = startIndex
        while (index < tokens.size) {
            val token = tokens[index]
            if (isPunctuation(source, token, "(") || isPunctuation(source, token, "[") || isPunctuation(source, token, "{")) {
                val close = matchingTokens.getOrElse(index) { -1 }
                if (close < 0) return index - 1
                index = close + 1
                continue
            }
            if (isPunctuation(source, token, ",") || isPunctuation(source, token, ";")) return index - 1
            index++
        }
        return tokens.lastIndex
    }

    private fun isFunctionOrArrowAt(source: String, tokens: List<JsToken>, matchingTokens: IntArray, startIndex: Int): Boolean {
        var index = startIndex
        if (isWord(source, tokens.getOrNull(index), "async")) index++
        if (isWord(source, tokens.getOrNull(index), "function")) return true
        if (tokens.getOrNull(index)?.kind == TOKEN_IDENTIFIER) return isPunctuation(source, tokens.getOrNull(index + 1), "=>")
        if (!isPunctuation(source, tokens.getOrNull(index), "(")) return false
        val close = matchingTokens.getOrElse(index) { -1 }
        return close >= 0 && isPunctuation(source, tokens.getOrNull(close + 1), "=>")
    }

    private fun functionOrArrowEnd(source: String, tokens: List<JsToken>, matchingTokens: IntArray, startIndex: Int): Int {
        var index = startIndex
        if (isWord(source, tokens.getOrNull(index), "async")) index++
        if (isWord(source, tokens.getOrNull(index), "function")) {
            var openParen = index + 1
            if (isPunctuation(source, tokens.getOrNull(openParen), "*")) openParen++
            if (tokens.getOrNull(openParen)?.kind == TOKEN_IDENTIFIER) openParen++
            while (openParen < tokens.size && !isPunctuation(source, tokens.getOrNull(openParen), "(")) openParen++
            val closeParen = matchingTokens.getOrElse(openParen) { -1 }
            val bodyOpen = if (closeParen >= 0) closeParen + 1 else -1
            return if (bodyOpen >= 0 && isPunctuation(source, tokens.getOrNull(bodyOpen), "{")) {
                matchingTokens.getOrElse(bodyOpen) { -1 }
            } else -1
        }

        val arrowIndex = if (isPunctuation(source, tokens.getOrNull(index), "(")) {
            val close = matchingTokens.getOrElse(index) { -1 }
            if (close >= 0 && isPunctuation(source, tokens.getOrNull(close + 1), "=>")) close + 1 else -1
        } else if (tokens.getOrNull(index)?.kind == TOKEN_IDENTIFIER &&
            isPunctuation(source, tokens.getOrNull(index + 1), "=>")
        ) index + 1 else -1
        if (arrowIndex < 0) return -1

        val bodyIndex = arrowIndex + 1
        if (isPunctuation(source, tokens.getOrNull(bodyIndex), "{")) {
            return matchingTokens.getOrElse(bodyIndex) { -1 }
        }
        var indexInExpression = bodyIndex
        while (indexInExpression < tokens.size) {
            val token = tokens[indexInExpression]
            if (isPunctuation(source, token, "(") || isPunctuation(source, token, "[") ||
                isPunctuation(source, token, "{")) {
                val close = matchingTokens.getOrElse(indexInExpression) { -1 }
                if (close < 0) return indexInExpression - 1
                indexInExpression = close + 1
                continue
            }
            if (isPunctuation(source, token, ",") || isPunctuation(source, token, ";") ||
                isPunctuation(source, token, ")") || isPunctuation(source, token, "]") ||
                isPunctuation(source, token, "}")) return indexInExpression - 1
            indexInExpression++
        }
        return tokens.lastIndex
    }

    private fun buildMatchingPairs(source: String, tokens: List<JsToken>): IntArray {
        val pairs = IntArray(tokens.size) { -1 }
        val stack = IntArray(tokens.size)
        var stackSize = 0
        for (index in tokens.indices) {
            val token = tokens[index]
            if (isPunctuation(source, token, "(") || isPunctuation(source, token, "[") || isPunctuation(source, token, "{")) {
                stack[stackSize++] = index
                continue
            }
            val closing = when {
                isPunctuation(source, token, ")") -> ')'
                isPunctuation(source, token, "]") -> ']'
                isPunctuation(source, token, "}") -> '}'
                else -> '\u0000'
            }
            if (closing == '\u0000' || stackSize == 0) continue
            val openIndex = stack[stackSize - 1]
            val openChar = source[tokens[openIndex].start]
            val matching = (openChar == '(' && closing == ')') || (openChar == '[' && closing == ']') ||
                (openChar == '{' && closing == '}')
            if (matching) {
                stackSize--
                pairs[openIndex] = index
                pairs[index] = openIndex
            }
        }
        return pairs
    }

    private fun readDottedNameEndingAt(source: String, tokens: List<JsToken>, endIndex: Int): String? {
        if (tokens.getOrNull(endIndex)?.kind != TOKEN_IDENTIFIER) return null
        var startIndex = endIndex
        var segments = 1
        while (startIndex >= 2 && isPunctuation(source, tokens[startIndex - 1], ".") &&
            tokens[startIndex - 2].kind == TOKEN_IDENTIFIER && segments < 5
        ) {
            startIndex -= 2
            segments++
        }
        return source.substring(tokens[startIndex].start, tokens[endIndex].end)
    }

    private fun isWord(source: String, token: JsToken?, word: String): Boolean =
        token?.kind == TOKEN_IDENTIFIER && token.end - token.start == word.length &&
            source.regionMatches(token.start, word, 0, word.length)

    private fun isPunctuation(source: String, token: JsToken?, punctuation: String): Boolean =
        token?.kind == TOKEN_PUNCTUATION && token.end - token.start == punctuation.length &&
            source.regionMatches(token.start, punctuation, 0, punctuation.length)

    private fun dottedNameStartIndex(source: String, tokens: List<JsToken>, endIndex: Int): Int {
        var startIndex = endIndex
        var segments = 1
        while (startIndex >= 2 && isPunctuation(source, tokens.getOrNull(startIndex - 1), ".") &&
            tokens.getOrNull(startIndex - 2)?.kind == TOKEN_IDENTIFIER && segments < 5
        ) {
            startIndex -= 2
            segments++
        }
        return startIndex
    }

    private data class JsToken(val start: Int, val end: Int, val kind: Int)
    private data class AssignmentTarget(val name: String, val rhsIndex: Int)
    private data class SourceRange(val startToken: Int, val endToken: Int, val expressionStartToken: Int = -1)
    private data class LocalFunctionScope(val range: SourceRange, val names: MutableSet<String>)
    private data class IndexedDeclaration(
        val range: SourceRange,
        val kind: DeclarationKind,
        val assignmentName: String,
        val memberRange: SourceRange? = null,
    )
    private data class ScriptIndex(
        val tokens: List<JsToken>,
        val matchingTokens: IntArray,
        val declarations: Map<String, IndexedDeclaration>,
    )
    private data class ObjectScope(
        val owner: String,
        val depth: Int,
        val openIndex: Int,
        val assignmentStartIndex: Int,
        val methods: MutableSet<String> = HashSet(),
        val methodRanges: MutableMap<String, SourceRange> = HashMap(),
    )
    private enum class DeclarationKind {
        RAW_FUNCTION,
        RAW_CLASS,
        ROOT_ASSIGNMENT,
        VALUE_ASSIGNMENT,
        DOTTED_ASSIGNMENT,
        DOTTED_VALUE_ASSIGNMENT,
        OBJECT_ASSIGNMENT,
        UNINITIALIZED_VALUE,
    }

    private val MULTI_CHARACTER_PUNCTUATION = arrayOf("===", "!==", ">>>", "...", "=>", "?.", "??=", "&&=", "||=", "==", "!=", "<=", ">=", "&&", "||", "??", "++", "--", "+=", "-=", "*=", "/=", "**", "<<", ">>")
    private const val TOKEN_PUNCTUATION = 0
    private const val TOKEN_IDENTIFIER = 1
    private const val TOKEN_OPAQUE = 2
    private const val MAX_EXECUTABLE_DECLARATIONS = 2048
    private const val MAX_URL_EXECUTABLE_DECLARATIONS = MAX_EXECUTABLE_DECLARATIONS
    private const val MAX_REQUIRED_DEPENDENCIES = 8
    private const val MAX_EXECUTABLE_PROGRAM_BYTES = 512 * 1024
    private const val MAX_URL_EXECUTABLE_PROGRAM_BYTES = MAX_EXECUTABLE_PROGRAM_BYTES
    private const val MAX_DIAGNOSTIC_MARKER_DECLARATIONS = 4096
    private const val MAX_DIAGNOSTIC_MARKER_PROGRAM_BYTES = 1024 * 1024
    private const val MAX_DIAGNOSTIC_FUNCTION_ARGUMENT_CHARS = 8 * 1024
    private const val MAX_DIAGNOSTIC_FUNCTION_ARGUMENTS = 512
    private const val MAX_URL_FUNCTION_ARGUMENTS = 64
    private const val MAX_URL_CONSTRUCTOR_FUNCTION_ARGUMENTS = 64
    private const val MAX_URL_FUNCTION_DEPENDENCY_RESERVE = 64
    private val URL_FUNCTION_ARGUMENT_LIMITS = listOf(512, 256, 192, 128, MAX_URL_FUNCTION_ARGUMENTS, 32)
    private const val MAX_DIAGNOSTIC_CANDIDATES = 12
    private const val MAX_SIGNATURE_CONTEXTS = 8
    private const val MAX_STRING_TRANSFORM_CANDIDATES = 64
    private const val MAX_STRING_TRANSFORM_CHARS = 32 * 1024
    private const val MAX_STRING_OPERATION_CONTEXTS = 20
    private const val MAX_UNIQUE_DOTTED_ALIASES = 64
    private const val MAX_MARKER_CANDIDATES = 12
    private const val MAX_MARKER_CANDIDATE_CHARS = 32 * 1024
    private const val MAX_DOTTED_LITERAL_CHARS = 256
    private const val MAX_STATIC_STRING_SPLIT_CHARS = 512
    private const val MAX_DOTTED_PARENT_INITIALIZER_CHARS = 16 * 1024
    private const val MAX_BOUNDED_MEMBER_CONSTRUCTOR_CHARS = 512
    private const val MAX_SAFE_PERFORMANCE_FALLBACK_TERMS = 8
    private val BROWSER_GLOBALS = setOf("document", "navigator", "location", "crypto", "atob", "btoa", "performance", "XMLHttpRequest", "fetch", "globalThis")
    private val KNOWN_GLOBALS = setOf(
        "g", "window", "self", "globalThis", "document", "navigator", "location", "crypto", "atob", "btoa",
        "performance", "XMLHttpRequest", "fetch", "Headers", "Request", "Response", "URL", "URLSearchParams",
        "AbortController", "TextEncoder", "TextDecoder", "Blob", "File", "FormData", "Event", "CustomEvent",
        "Element", "Node", "HTMLElement", "MutationObserver", "DOMParser", "Image", "Audio", "WebSocket", "Worker",
        "SharedWorker", "setTimeout", "clearTimeout", "setInterval", "clearInterval", "requestAnimationFrame",
        "cancelAnimationFrame", "queueMicrotask", "console", "Map", "Set", "WeakMap", "WeakSet", "Object", "Array",
        "Function", "Number", "String", "Boolean", "BigInt", "Symbol", "Math", "JSON", "Date", "RegExp", "Error",
        "TypeError", "RangeError", "ReferenceError", "SyntaxError", "Promise", "Reflect", "Proxy", "Intl", "Atomics",
        "ArrayBuffer", "SharedArrayBuffer", "DataView", "Int8Array", "Uint8Array", "Uint8ClampedArray", "Int16Array",
        "Uint16Array", "Int32Array", "Uint32Array", "Float32Array", "Float64Array", "parseInt", "parseFloat", "isNaN",
        "isFinite", "eval", "undefined", "NaN", "Infinity",
    )
    private val SAFE_EMPTY_CONSTRUCTORS = setOf("Map", "Set", "WeakMap", "WeakSet", "Object", "Array")
    private val SAFE_GLOBAL_ALIAS_NAMES = setOf("window", "self", "globalThis", "g", "document", "navigator", "location")
    private val SAFE_PERFORMANCE_PROPERTIES = setOf(
        "performance", "mozPerformance", "msPerformance", "webkitPerformance",
    )
    private val CALLBACK_PROPERTY_NAMES = setOf("callback", "handler", "onSuccess", "onError", "onComplete", "done")
    private val STRING_TRANSFORM_DECLARATION_KINDS = setOf(
        DeclarationKind.RAW_FUNCTION,
        DeclarationKind.VALUE_ASSIGNMENT,
        DeclarationKind.ROOT_ASSIGNMENT,
        DeclarationKind.DOTTED_ASSIGNMENT,
        DeclarationKind.OBJECT_ASSIGNMENT,
    )
    private val RESERVED_WORDS = setOf(
        "await", "break", "case", "catch", "class", "const", "continue", "debugger", "default", "delete", "do", "else",
        "enum", "export", "extends", "false", "finally", "for", "function", "if", "implements", "import", "in", "instanceof",
        "interface", "let", "new", "null", "package", "private", "protected", "public", "return", "static", "super", "switch",
        "this", "throw", "true", "try", "typeof", "var", "void", "while", "with", "yield",
    )
    private val CALL_CONTROL_KEYWORDS = setOf("if", "for", "while", "switch", "catch", "function", "with")

    private fun isIdentifierStart(char: Char): Boolean = char == '$' || char == '_' || char.isLetter()
    private fun isIdentifierPart(char: Char): Boolean = isIdentifierStart(char) || char.isDigit()

    private fun diagnosticTokenLabel(source: String, tokens: List<JsToken>, tokenIndex: Int): String {
        val token = tokens.getOrNull(tokenIndex) ?: return "none"
        if (token.kind == TOKEN_OPAQUE) return "literal"
        val value = source.substring(token.start, token.end)
        if (token.kind == TOKEN_PUNCTUATION) return value
        return value.takeIf {
            it in CALL_CONTROL_KEYWORDS || it in setOf("return", "new", "var", "let", "const", "this", "super", "typeof", "await")
        } ?: "identifier"
    }

    private fun diagnosticParentheses(source: String, index: ScriptIndex, range: SourceRange, tokenIndex: Int): String =
        (tokenIndex - 1 downTo range.startToken).asSequence()
            .filter { open ->
                isPunctuation(source, index.tokens.getOrNull(open), "(") &&
                    index.matchingTokens.getOrElse(open) { -1 } > tokenIndex
            }
            .take(6)
            .joinToString(",") { open ->
                val close = index.matchingTokens.getOrElse(open) { -1 }
                "$open:${diagnosticTokenValue(source, index.tokens, open - 1)}:" +
                    diagnosticTokenLabel(source, index.tokens, close + 1)
            }

    private fun diagnosticTokenValue(source: String, tokens: List<JsToken>, tokenIndex: Int): String {
        val token = tokens.getOrNull(tokenIndex) ?: return "none"
        return source.substring(token.start, token.end).take(48)
    }

    private fun diagnosticRangeShape(source: String, index: ScriptIndex, range: SourceRange): String =
        (range.expressionStartToken..range.endToken).take(12)
            .joinToString(separator = " ") { tokenIndex ->
                diagnosticTokenLabel(source, index.tokens, tokenIndex)
            }

}
