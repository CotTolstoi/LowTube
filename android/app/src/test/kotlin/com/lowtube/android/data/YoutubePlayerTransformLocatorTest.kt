package com.lowtube.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YoutubePlayerTransformLocatorTest {
    @Test
    fun locatesSignatureAndThrottlingFunctionDeclarations() {
        val source = """
            function sig(a) { return a.split('').reverse().join(''); }
            var ns = function(a) { return a.slice(1); };
            x.sig||sig(a);
            x.get("n"))&&(b=ns(a));
        """.trimIndent()

        assertEquals(YoutubePlayerTransformLocator.Functions("sig", "ns"), YoutubePlayerTransformLocator.locate(source))
    }

    @Test
    fun locatesSetSignatureCallAndIgnoresUndeclaredCandidates() {
        val source = """
            const transform = function(a) { return a; };
            x.set("signature", transform(a));
            x.get("n"))&&(b=missing(a));
        """.trimIndent()

        assertEquals(YoutubePlayerTransformLocator.Functions("transform", null), YoutubePlayerTransformLocator.locate(source))
    }

    @Test
    fun returnsNoFunctionsWhenCallSitesAreUnrecognized() {
        val source = "function sig(a) { return a; } function ns(a) { return a; }"

        assertEquals(YoutubePlayerTransformLocator.Functions(null, null), YoutubePlayerTransformLocator.locate(source))
    }

    @Test
    fun locatesHelperObjectMethodsAndArrowTransforms() {
        val source = """
            var Dec = { swap: function(a) { return a.split('').reverse().join(''); } };
            const Th = { short(a) { return a.slice(2); } };
            x.sig||Dec.swap(a);
            x.get("n"))&&(b=Th.short(a));
        """.trimIndent()

        assertEquals(YoutubePlayerTransformLocator.Functions("Dec.swap", "Th.short"), YoutubePlayerTransformLocator.locate(source))
    }

    @Test
    fun locatesArrowFunctionDeclarations() {
        val source = """
            const sigFn = (a) => a.split('').reverse().join('');
            let nFn = a => a.slice(1);
            x.set("signature", sigFn(a));
            x.get("n"))&&(b=nFn(a));
        """.trimIndent()

        assertEquals(YoutubePlayerTransformLocator.Functions("sigFn", "nFn"), YoutubePlayerTransformLocator.locate(source))
    }

    @Test
    fun locatesSignatureFunctionBySplitBodyAndNFunctionByPlayerMarker() {
        val source = """
            var sigDecode=function(a){a=a.split("");a.reverse();return a.join("")};
            var nDecode=function(a,b){var x=a;helper.set("alr","yes");q&&x;return x};
        """.trimIndent()

        assertEquals(
            YoutubePlayerTransformLocator.Functions("sigDecode", "nDecode"),
            YoutubePlayerTransformLocator.locate(source),
        )
    }

    @Test
    fun executableLocatorDoesNotUsePlayerMarkerWithoutNCallSite() {
        val source = "var nDecode=function(a){var x=a;helper.set(\"alr\",\"yes\");q&&x;return x};"

        val executable = YoutubePlayerTransformLocator.locateExecutable(source)

        assertNull(executable.functions.throttling)
        assertNull(executable.throttlingProgram)
    }

    @Test
    fun markerCandidatesAreOptInAndUseTheEnclosingFunction() {
        val source = "var nDecode=function(a){helper.set(\"alr\",\"yes\");return a};"

        val production = YoutubePlayerTransformLocator.locateExecutable(source)
        val diagnostic = YoutubePlayerTransformLocator.locateExecutable(source, includeMarkerCandidates = true)

        assertTrue(production.markerCandidates.isEmpty())
        assertEquals(listOf("nDecode"), diagnostic.markerCandidates.map { it.name })
        assertFalse(diagnostic.markerCandidates.single().isAsync)
        assertTrue(diagnostic.markerCandidates.single().program.contains("var nDecode=function"))
    }

    @Test
    fun productionMarkerCandidatesExcludeAsyncFunctionsButDiagnosticsKeepThem() {
        val source = "var nDecode=async function(a){helper.set(\"alr\",\"yes\");return a};"

        val executable = YoutubePlayerTransformLocator.locateExecutable(source, includeMarkerCandidates = true)

        assertTrue(executable.markerCandidateNames.isEmpty())
        assertEquals(listOf("nDecode"), executable.markerCandidates.map { it.name })
        assertTrue(executable.markerCandidates.single().isAsync)
    }

    @Test
    fun locatesPlayerMarkerInLargeSingleLineBundle() {
        val repeatedFunctions = "var helper=function(a){return a};".repeat(40_000)
        val source = repeatedFunctions +
            "var nDecode=function(a){var x=a;helper.set(\"alr\",\"yes\");q&&x;return x};"

        assertEquals("nDecode", YoutubePlayerTransformLocator.locate(source).throttling)
    }

    @Test
    fun extractsTransformWithOnlyItsHelperObject() {
        val source = """
            var Dec={swap:function(a){return a.split('').reverse().join('')}};
            function transform(a){return Dec.swap(a)};
            var unrelated=function(a){return a.repeat(1000)};
            x.sig||transform(a);
        """.trimIndent()

        val executable = YoutubePlayerTransformLocator.locateExecutable(source)
        val program = executable.signatureProgram.orEmpty()

        assertEquals("transform", executable.functions.signature)
        assertTrue(program.contains("var Dec="))
        assertTrue(program.contains("function transform"))
        assertFalse(program.contains("unrelated"))
    }

    @Test
    fun extractsModuleConstantsReferencedByTransform() {
        val source = "const Bo=7; function transform(a){return a+Bo;} x.get(\"n\"))&&(b=transform(a));"

        val executable = YoutubePlayerTransformLocator.locateExecutable(source)
        val program = executable.throttlingProgram.orEmpty()

        assertEquals("transform", executable.functions.throttling)
        assertTrue(program.contains("var Bo=7;"))
        assertTrue(program.contains("function transform"))
    }

    @Test
    fun ignoresModuleNamesShadowedByTransformParameters() {
        val source = "const z_=document.createElement(\"div\"); function transform(z_){return z_;} x.get(\"n\"))&&(b=transform(a));"
        val localSource = "const z_=document.createElement(\"div\"); function transform(a){var x=1,z_=a;return z_;} x.get(\"n\"))&&(b=transform(a));"

        val program = YoutubePlayerTransformLocator.locateExecutable(source).throttlingProgram.orEmpty()
        val localProgram = YoutubePlayerTransformLocator.locateExecutable(localSource).throttlingProgram.orEmpty()

        assertFalse(program.contains("var z_="))
        assertFalse(localProgram.contains("var z_="))
        assertTrue(program.contains("function transform"))
    }

    @Test
    fun omitsBrowserSideEffectInitializersFromTransformProgram() {
        val source = "const z_=document.createElement(\"div\"); function transform(a){return a+z_;} x.get(\"n\"))&&(b=transform(a));"

        val program = YoutubePlayerTransformLocator.locateExecutable(source).throttlingProgram.orEmpty()

        assertFalse(program.contains("var z_="))
        assertTrue(program.contains("function transform"))
    }

    @Test
    fun includesSharedComplexValueReferencedByMultipleHelpers() {
        val source = "var complex=make(); function one(a){return a+complex;} function two(a){return a+complex;} function transform(a){return one(a)+two(a);} x.get(\"n\"))&&(b=transform(a));"

        val program = YoutubePlayerTransformLocator.locateExecutable(source).throttlingProgram.orEmpty()

        assertTrue(program.contains("var complex=make();"))
        assertTrue(program.contains("function one"))
        assertTrue(program.contains("function two"))
    }

    @Test
    fun includesComplexModuleValueUsedForComputedMemberAccess() {
        val source = "var table=makeTable(); function makeTable(){return {run:function(a){return a.slice(1)}}} function transform(a){helper.set(\"alr\",\"yes\");return a[table.run](a)} x.get(\"n\"))&&(b=transform(a));"

        val program = YoutubePlayerTransformLocator.locateExecutable(source, includeMarkerCandidates = true)
            .markerCandidates.single().program

        assertTrue(program.contains("var table=makeTable();"))
        assertTrue(program.contains("function makeTable"))
        assertTrue(program.contains("function transform"))
    }

    @Test
    fun includesExplicitlyRequestedSingleOwnerComplexValueInMarkerProbe() {
        val source = "var qbb=make(function(a){return a.slice(1)},1); function transform(a){helper.set(\"alr\",\"yes\");qbb&&a;return a}"

        val candidate = YoutubePlayerTransformLocator.locateMarkerCandidate(
            source,
            "transform",
            diagnosticIdentifiers = setOf("qbb"),
        )

        assertTrue(candidate?.program.orEmpty().contains("var qbb=make(function(a){return a.slice(1)},1);"))
    }

    @Test
    fun includesReferencedDottedModuleValues() {
        val source = "g.value=7; function transform(a){helper.set(\"alr\",\"yes\");return a+g.value} x.get(\"n\"))&&(b=transform(a));"

        val program = YoutubePlayerTransformLocator.locateExecutable(source, includeMarkerCandidates = true)
            .markerCandidates.single().program

        assertTrue(program.contains("g.value=7;"))
        assertTrue(program.contains("function transform"))
    }

    @Test
    fun includesSmallGlobalObjectBootstrapForDiagnosticCandidate() {
        val source = "g.Ga=this||self; function transform(a){helper.set(\"alr\",\"yes\");return g.Ga? a.slice(1):a} x.get(\"n\"))&&(b=transform(a));"

        val program = YoutubePlayerTransformLocator.locateExecutable(source, includeMarkerCandidates = true)
            .markerCandidates.single().program

        assertTrue(program.contains("g.Ga=this||self;"))
        assertTrue(program.contains("function transform"))
    }

    @Test
    fun includesNamedFunctionPassedAsCallArgumentForDiagnosticCandidate() {
        val source = "function callback(a){return a.slice(1)} function transform(a){helper.set(\"alr\",\"yes\");return dispatch(a,callback)}"

        val program = YoutubePlayerTransformLocator.locateExecutable(source, includeMarkerCandidates = true)
            .markerCandidates.single().program

        assertTrue(program.contains("function callback"))
        assertTrue(program.contains("function transform"))
    }

    @Test
    fun includesObjectPropertyCallbackFromHelperForMarkerCandidate() {
        val source = "function callback(a){return a.slice(1)} function configure(){return {transform:callback,unused:null}} function transform(a){helper.set(\"alr\",\"yes\");q&&a;var handlers=configure();return handlers.transform(a)}"

        val program = YoutubePlayerTransformLocator.locateExecutable(source, includeMarkerCandidates = true)
            .markerCandidates.single().program

        assertTrue(program.contains("function callback(a)"))
        assertTrue(program.contains("function configure()"))
    }

    @Test
    fun doesNotTreatReservedDottedPropertyAsRootAssignment() {
        val source = "g.finally=function(a){return a.slice(1)}; function transform(a){helper.set(\"alr\",\"yes\");return g.finally(a)}"

        val program = YoutubePlayerTransformLocator.locateExecutable(source, includeMarkerCandidates = true)
            .markerCandidates.single().program

        assertTrue(program.contains("g.finally=function"))
        assertFalse(program.contains("var finally="))
    }

    @Test
    fun includesClassDeclarationUsedAsClassExpressionBase() {
        val source = "class Base{} var Derived=class extends Base{}; function transform(a){helper.set(\"alr\",\"yes\");return new Derived(a)}"

        val program = YoutubePlayerTransformLocator.locateExecutable(source, includeMarkerCandidates = true)
            .markerCandidates.single().program

        assertTrue(program.contains("class Base{}"))
        assertTrue(program.contains("var Derived=class extends Base{}"))
    }

    @Test
    fun followsSimpleIdentifierAliasForDiagnosticCandidate() {
        val source = "function helper(a){return a.slice(1)} var alias=helper; function transform(a){helper.set(\"alr\",\"yes\");return alias(a)}"

        val program = YoutubePlayerTransformLocator.locateExecutable(source, includeMarkerCandidates = true)
            .markerCandidates.single().program

        assertTrue(program.contains("function helper"))
        assertTrue(program.contains("var alias=helper;"))
    }

    @Test
    fun includesClassExpressionUsedAsClassBase() {
        val source = "var Base=class{}; var Derived=class extends Base{}; function transform(a){helper.set(\"alr\",\"yes\");return new Derived(a)}"

        val program = YoutubePlayerTransformLocator.locateExecutable(source, includeMarkerCandidates = true)
            .markerCandidates.single().program

        assertTrue(program.contains("var Base=class{}"))
        assertTrue(program.contains("var Derived=class extends Base{}"))
    }

    @Test
    fun followsChainedClassValuePassedAsCallArgument() {
        val source = "class Parent{}; var Child=Child=class extends Parent{}; function transform(a){helper.set(\"alr\",\"yes\");return invoke(Child,a)}"

        val program = YoutubePlayerTransformLocator.locateExecutable(source, includeMarkerCandidates = true)
            .markerCandidates.single().program

        assertTrue(program.contains("class Parent{}"))
        assertTrue(program.contains("var Child=class extends Parent{}"))
    }

    @Test
    fun locatesUnqualifiedClassExpressionAssignment() {
        val source = "class Parent{}; var Child; Child=class extends Parent{}; function transform(a){helper.set(\"alr\",\"yes\");return new Child(a)}"

        val program = YoutubePlayerTransformLocator.locateExecutable(source, includeMarkerCandidates = true)
            .markerCandidates.single().program

        assertTrue(program.contains("var Child=class extends Parent{};"))
        assertTrue(program.contains("class Parent{}"))
    }

    @Test
    fun locatesSignatureFunctionFromCipherCallSite() {
        val source = """
            var nDecode=function(a){return a.slice(1)};
            m=nDecode(decodeURIComponent(h.s));
        """.trimIndent()

        assertEquals(YoutubePlayerTransformLocator.Functions("nDecode", null), YoutubePlayerTransformLocator.locate(source))
    }
    @Test
    fun locatesCurrentGuardedCallSitesAndNestedAlrMarker() {
        val source = """
            function sig(a) { return a.reverse(); }
            function sigMode(a,b) { return a.reverse(); }
            var nDecode=function(a){if(a){if(a.length){return a.slice(1)}}var x={};helper.set("alr","yes");q&&x;return a};
            x&&(b=sig(decodeURIComponent(a)));
            y&&(c=sigMode(3,decodeURIComponent(d)));
        """.trimIndent()

        assertEquals(
            YoutubePlayerTransformLocator.Functions("sig", "nDecode"),
            YoutubePlayerTransformLocator.locate(source),
        )
    }
    @Test
    fun keepsNumericSignatureCallArgumentsForRuntime() {
        val source = """
            function sigMode(a,b) { return a.reverse(); }
            y&&(c=sigMode(3,decodeURIComponent(d)));
        """.trimIndent()

        assertEquals(
            YoutubePlayerTransformLocator.Functions("sigMode", null, "3,"),
            YoutubePlayerTransformLocator.locate(source),
        )
    }

    @Test
    fun recognizesCommaSeparatedFunctionAssignments() {
        val source = """
            var start=1,sig=function(a){a=a.split("");return a.reverse().join("")};
            x.sig||sig(a);
        """.trimIndent()

        assertEquals(
            YoutubePlayerTransformLocator.Functions("sig", null),
            YoutubePlayerTransformLocator.locate(source),
        )
    }

    @Test
    fun locatesUrlNormalizerThatReadsTheNParameter() {
        val source = """
            g.ni=class{constructor(){this.ready=true}};
            var rewriteUrl=function(url){var child=class extends ni{};var query=new Query(url);var n=query.get("n");if(n)query.set("n",n.split("").reverse().join(""));return query.toString()};
            function consume(entry){entry.url&&(entry.url=rewriteUrl(entry.url))}
        """.trimIndent()

        val executable = YoutubePlayerTransformLocator.locateExecutable(source)

        assertEquals("rewriteUrl", executable.functions.urlThrottling)
        assertTrue(executable.urlThrottlingProgram.orEmpty().contains("rewriteUrl"))
        assertTrue(executable.urlThrottlingProgram.orEmpty().contains("g.ni=class"))
        assertTrue(executable.urlThrottlingProgram.orEmpty().contains("var ni=g.ni"))
    }

    @Test
    fun includesOnlySafeGlobalBootstrapForUrlCandidateWithoutGeneralDataDependencies() {
        val source = "g.Ga=this||self; function rewriteUrl(url){var query=new Query(url);var n=query.get(\"n\");if(g.Ga&&n)query.set(\"n\",n.split(\"\").reverse().join(\"\"));return query.toString()} function consume(entry){entry.url&&(entry.url=rewriteUrl(entry.url))}"

        val executable = YoutubePlayerTransformLocator.locateExecutable(source)

        assertEquals("rewriteUrl", executable.functions.urlThrottling)
        assertTrue(executable.urlThrottlingProgram.orEmpty().contains("g.Ga=this||self;"))
    }

    @Test
    fun includesSmallFunctionOwnerForReferencedDottedUrlHelper() {
        val source = "g.Module=function(){this.ready=true};g.Module.open=function(value){return value};function rewriteUrl(url){var query=new Query(url);var n=query.get(\"n\");if(n)query.set(\"n\",g.Module.open(n));return query.toString()}function consume(entry){entry.url&&(entry.url=rewriteUrl(entry.url))}"

        val executable = YoutubePlayerTransformLocator.locateExecutable(source)
        val program = executable.urlThrottlingProgram.orEmpty()

        assertEquals("rewriteUrl", executable.functions.urlThrottling)
        assertTrue(program.contains("g.Module=function(){this.ready=true};"))
        assertTrue(program.contains("g.Module.open=function(value){return value};"))
    }

    @Test
    fun includesConstructorUsedWithoutParentheses() {
        val source = "g.Module=function(){this.open=function(value){return value}};function rewriteUrl(url){var module=new g.Module;var query=new Query(url);var n=query.get(\"n\");if(n)query.set(\"n\",module.open(n));return query.toString()}function consume(entry){entry.url&&(entry.url=rewriteUrl(entry.url))}"

        val executable = YoutubePlayerTransformLocator.locateExecutable(source)

        assertEquals("rewriteUrl", executable.functions.urlThrottling)
        assertTrue(executable.urlThrottlingProgram.orEmpty().contains("g.Module=function(){this.open=function(value){return value}};"))
    }

    @Test
    fun mapsRootClassToDottedGlobalParentInUrlTransformClosure() {
        val source = "function Parent(){this.ready=true}g.Child=class extends g.Parent{method(g){return g}};g.Child.prototype.transform=function(value){return value};function rewriteUrl(url){var query=new Query(url);var n=query.get(\"n\");if(n)query.set(\"n\",g.Child.prototype.transform(n));return query.toString()}function consume(entry){entry.url&&(entry.url=rewriteUrl(entry.url))}"

        val executable = YoutubePlayerTransformLocator.locateExecutable(source)
        val program = executable.urlThrottlingProgram.orEmpty()

        assertEquals("rewriteUrl", executable.functions.urlThrottling)
        assertTrue(program.contains("function Parent()"))
        assertTrue(program.contains("g.Parent=Parent;"))
    }

    @Test
    fun includesSmallLiteralOwnerForReferencedDottedUrlHelper() {
        val source = "g.Module={};g.Module.open=function(value){return value};function rewriteUrl(url){var query=new Query(url);var n=query.get(\"n\");if(n)query.set(\"n\",g.Module.open(n));return query.toString()}function consume(entry){entry.url&&(entry.url=rewriteUrl(entry.url))}"

        val executable = YoutubePlayerTransformLocator.locateExecutable(source)
        val program = executable.urlThrottlingProgram.orEmpty()

        assertEquals("rewriteUrl", executable.functions.urlThrottling)
        assertTrue(program.contains("g.Module={};"))
        assertTrue(program.contains("g.Module.open=function(value){return value};"))
    }

    @Test
    fun includesSelfFallbackObjectBootstrapForReferencedUrlHelper() {
        val source = "g.Module=g.Module||{};g.Module.open=function(value){return value};function rewriteUrl(url){var query=new Query(url);var n=query.get(\"n\");if(n)query.set(\"n\",g.Module.open(n));return query.toString()}function consume(entry){entry.url&&(entry.url=rewriteUrl(entry.url))}"

        val executable = YoutubePlayerTransformLocator.locateExecutable(source)
        val program = executable.urlThrottlingProgram.orEmpty()

        assertEquals("rewriteUrl", executable.functions.urlThrottling)
        assertTrue(program.contains("g.Module=g.Module||{};"))
        assertTrue(program.contains("g.Module.open=function(value){return value};"))
    }

    @Test
    fun includesSelfFallbackNativeCollectionBootstrapForReferencedUrlHelper() {
        val source = "g.Module=g.Module||new Map();g.Module.open=function(value){return value};function rewriteUrl(url){var query=new Query(url);var n=query.get(\"n\");if(n)query.set(\"n\",g.Module.open(n));return query.toString()}function consume(entry){entry.url&&(entry.url=rewriteUrl(entry.url))}"

        val executable = YoutubePlayerTransformLocator.locateExecutable(source)
        val program = executable.urlThrottlingProgram.orEmpty()

        assertEquals("rewriteUrl", executable.functions.urlThrottling)
        assertTrue(program.contains("g.Module=g.Module||new Map();"))
        assertTrue(program.contains("g.Module.open=function(value){return value};"))
    }

    @Test
    fun includesLocallyDeclaredConstructorForDottedPrototypeAlias() {
        val source = "g.Parent=function(){};g.Module=g.Parent.prototype;g.Module.open=function(value){return value};function rewriteUrl(url){var query=new Query(url);var n=query.get(\"n\");if(n)query.set(\"n\",g.Module.open(n));return query.toString()}function consume(entry){entry.url&&(entry.url=rewriteUrl(entry.url))}"

        val executable = YoutubePlayerTransformLocator.locateExecutable(source)
        val program = executable.urlThrottlingProgram.orEmpty()

        assertEquals("rewriteUrl", executable.functions.urlThrottling)
        assertTrue(program.contains("g.Parent=function(){};"))
        assertTrue(program.contains("g.Module=g.Parent.prototype;"))
        assertTrue(program.contains("g.Module.open=function(value){return value};"))
    }

    @Test
    fun suppliesEmptyObjectForUnboundOptionalConfigurationGlobal() {
        val source = "function rewriteUrl(url){var config=Eib?.yt?.config_||{};var query=new Query(url);var n=query.get(\"n\");if(config&&n)query.set(\"n\",n.split(\"\").reverse().join(\"\"));return query.toString()}function consume(entry){entry.url&&(entry.url=rewriteUrl(entry.url))}"

        val executable = YoutubePlayerTransformLocator.locateExecutable(source)

        assertEquals("rewriteUrl", executable.functions.urlThrottling)
        assertTrue(executable.urlThrottlingProgram.orEmpty().contains("var Eib={};"))
    }

    @Test
    fun doesNotStubGlobalUsedOutsideOptionalPropertyAccess() {
        val source = "function rewriteUrl(url){var config=Eib?.yt;var query=new Query(url);var n=query.get(\"n\");if(n)query.set(\"n\",Eib(n));return query.toString()}function consume(entry){entry.url&&(entry.url=rewriteUrl(entry.url))}"

        val executable = YoutubePlayerTransformLocator.locateExecutable(source)

        assertEquals("rewriteUrl", executable.functions.urlThrottling)
        assertFalse(executable.urlThrottlingProgram.orEmpty().contains("var Eib={};"))
    }

    @Test
    fun suppliesEmptyIterableForUnboundSpreadOnlyGlobal() {
        val source = "function rewriteUrl(url){var flags=[...Fib];var query=new Query(url);var n=query.get(\"n\");if(flags.length&&n)query.set(\"n\",n.split(\"\").reverse().join(\"\"));return query.toString()}function consume(entry){entry.url&&(entry.url=rewriteUrl(entry.url))}"

        val executable = YoutubePlayerTransformLocator.locateExecutable(source)

        assertEquals("rewriteUrl", executable.functions.urlThrottling)
        assertTrue(executable.urlThrottlingProgram.orEmpty().contains("var Fib=[];"))
    }

    @Test
    fun suppliesNoOpForUnboundCallbackOnlyGlobal() {
        val source = "function rewriteUrl(url){var hooks={callback:So};var query=new Query(url);var n=query.get(\"n\");if(hooks.callback&&n)query.set(\"n\",n.split(\"\").reverse().join(\"\"));return query.toString()}function consume(entry){entry.url&&(entry.url=rewriteUrl(entry.url))}"

        val executable = YoutubePlayerTransformLocator.locateExecutable(source)

        assertEquals("rewriteUrl", executable.functions.urlThrottling)
        assertTrue(executable.urlThrottlingProgram.orEmpty().contains("var So=function(){};"))
    }

    @Test
    fun suppliesNoOpForUnboundFinalConstructorCallback() {
        val source = "function rewriteUrl(url){var hook=new Uo(1,2,3,4,So);var query=new Query(url);var n=query.get(\"n\");if(hook&&n)query.set(\"n\",n.split(\"\").reverse().join(\"\"));return query.toString()}function consume(entry){entry.url&&(entry.url=rewriteUrl(entry.url))}"

        val executable = YoutubePlayerTransformLocator.locateExecutable(source)

        assertEquals("rewriteUrl", executable.functions.urlThrottling)
        assertTrue(executable.urlThrottlingProgram.orEmpty().contains("var So=function(){};"))
    }

    @Test
    fun includesBoundedFunctionArgumentInUrlTransformClosure() {
        val source = "function callback(value){return value.split(\"\").reverse().join(\"\")}function dispatch(value,fn){return fn(value)}function rewriteUrl(url){var query=new Query(url);var n=query.get(\"n\");if(n)query.set(\"n\",dispatch(n,callback));return query.toString()}function consume(entry){entry.url&&(entry.url=rewriteUrl(entry.url))}"

        val executable = YoutubePlayerTransformLocator.locateExecutable(source)

        assertEquals("rewriteUrl", executable.functions.urlThrottling)
        assertTrue(executable.urlThrottlingProgram.orEmpty().contains("function callback(value)"))
    }

    @Test
    fun includesSimpleAliasInitializerInUrlTransformClosure() {
        val source = "function callback(value){return value.split(\"\").reverse().join(\"\")}var callbackAlias=callback;function rewriteUrl(url){var query=new Query(url);var n=query.get(\"n\");if(n)query.set(\"n\",callbackAlias(n));return query.toString()}function consume(entry){entry.url&&(entry.url=rewriteUrl(entry.url))}"

        val executable = YoutubePlayerTransformLocator.locateExecutable(source)
        val program = executable.urlThrottlingProgram.orEmpty()

        assertEquals("rewriteUrl", executable.functions.urlThrottling)
        assertTrue(program.contains("var callbackAlias=callback;"))
        assertTrue(program.contains("function callback(value)"))
    }

    @Test
    fun followsDottedFunctionAliasInUrlTransformClosure() {
        val source = "g.Module=function(value){return value.split(\"\").reverse().join(\"\")};var callbackAlias=g.Module;function rewriteUrl(url){var query=new Query(url);var n=query.get(\"n\");if(n)query.set(\"n\",callbackAlias(n));return query.toString()}function consume(entry){entry.url&&(entry.url=rewriteUrl(entry.url))}"

        val executable = YoutubePlayerTransformLocator.locateExecutable(source)
        val program = executable.urlThrottlingProgram.orEmpty()

        assertEquals("rewriteUrl", executable.functions.urlThrottling)
        assertTrue(program.contains("var callbackAlias=g.Module;"))
        assertTrue(program.contains("g.Module=function(value)"))
    }

    @Test
    fun includesGuardedOptionalNativeConstructorInUrlTransformClosure() {
        val source = "var encoderFactory=typeof TextEncoder!==\"undefined\"?new TextEncoder:null;function rewriteUrl(url){var query=new Query(url);var n=query.get(\"n\");var encoder=encoderFactory?encoderFactory:null;if(n)query.set(\"n\",n.split(\"\").reverse().join(\"\"));return query.toString()}function consume(entry){entry.url&&(entry.url=rewriteUrl(entry.url))}"

        val diagnostics = mutableListOf<String>()
        val executable = YoutubePlayerTransformLocator.locateExecutable(source, diagnostics::add)

        assertEquals("rewriteUrl", executable.functions.urlThrottling)
        assertTrue(diagnostics.joinToString("\n") + "\n" + executable.urlThrottlingProgram.orEmpty(), executable.urlThrottlingProgram.orEmpty().contains("var encoderFactory=typeof TextEncoder!==\"undefined\"?new TextEncoder:null;"))
    }

    @Test
    fun includesBoundedBrowserPerformanceFallbackInUrlTransformClosure() {
        val source = "var scope=g;function Perf(){this.now=function(){return 1}}var optionalPerformance=scope.performance||scope.mozPerformance||scope.msPerformance||scope.webkitPerformance||new Perf;function rewriteUrl(url){var query=new Query(url);var n=query.get(\"n\");var tick=optionalPerformance.now();if(n&&tick)query.set(\"n\",n.split(\"\").reverse().join(\"\"));return query.toString()}function consume(entry){entry.url&&(entry.url=rewriteUrl(entry.url))}"

        val diagnostics = mutableListOf<String>()
        val executable = YoutubePlayerTransformLocator.locateExecutable(source, diagnostics::add)
        val program = executable.urlThrottlingProgram.orEmpty()

        assertEquals("rewriteUrl", executable.functions.urlThrottling)
        assertTrue(diagnostics.joinToString("\n") + "\n" + program, program.contains("var optionalPerformance=scope.performance||scope.mozPerformance||scope.msPerformance||scope.webkitPerformance||new Perf;"))
        assertTrue(diagnostics.joinToString("\n") + "\n" + program, program.contains("function Perf()"))
    }

    @Test
    fun followsSafeTopLevelBrowserGlobalAssignmentFromUninitializedVariable() {
        val source = "var scope;scope=window;function rewriteUrl(url){var query=new Query(url);var n=query.get(\"n\");var started=scope.performance&&scope.performance.now();if(n&&started)query.set(\"n\",n.split(\"\").reverse().join(\"\"));return query.toString()}function consume(entry){entry.url&&(entry.url=rewriteUrl(entry.url))}"

        val executable = YoutubePlayerTransformLocator.locateExecutable(source)
        val program = executable.urlThrottlingProgram.orEmpty()

        assertEquals("rewriteUrl", executable.functions.urlThrottling)
        assertTrue(program.contains("var scope=window;"))
        assertFalse(program.contains("var scope;"))
    }

    @Test
    fun includesCommaOperatorCalledDottedFunctionInUrlTransformClosure() {
        val source = "g.Qa=function(value){return value.split(\"\").reverse().join(\"\")};function rewriteUrl(url){var query=new Query(url);var n=query.get(\"n\");if(n)query.set(\"n\",(0,g.Qa)(n));return query.toString()}function consume(entry){entry.url&&(entry.url=rewriteUrl(entry.url))}"

        val executable = YoutubePlayerTransformLocator.locateExecutable(source)
        val program = executable.urlThrottlingProgram.orEmpty()

        assertEquals("rewriteUrl", executable.functions.urlThrottling)
        assertTrue(program.contains("g.Qa=function(value)"))
        assertTrue(program.contains("(0,g.Qa)(n)"))
    }

    @Test
    fun includesFunctionValueAssignedByLazyDottedHelper() {
        val source = "function callback(value){return value.split(\"\").reverse().join(\"\")}g.Qa=function(value){g.Qa=callback;return g.Qa(value)};function rewriteUrl(url){var query=new Query(url);var n=query.get(\"n\");if(n)query.set(\"n\",(0,g.Qa)(n));return query.toString()}function consume(entry){entry.url&&(entry.url=rewriteUrl(entry.url))}"

        val executable = YoutubePlayerTransformLocator.locateExecutable(source)
        val program = executable.urlThrottlingProgram.orEmpty()

        assertEquals("rewriteUrl", executable.functions.urlThrottling)
        assertTrue(program.contains("function callback(value)"))
        assertTrue(program.contains("g.Qa=callback"))
    }

    @Test
    fun includesFunctionValueStoredInObjectProperty() {
        val source = "function callback(value){return value.split(\"\").reverse().join(\"\")}var helpers={transform:callback};function rewriteUrl(url){var query=new Query(url);var n=query.get(\"n\");if(n)query.set(\"n\",helpers.transform(n));return query.toString()}function consume(entry){entry.url&&(entry.url=rewriteUrl(entry.url))}"

        val executable = YoutubePlayerTransformLocator.locateExecutable(source)
        val program = executable.urlThrottlingProgram.orEmpty()

        assertEquals("rewriteUrl", executable.functions.urlThrottling)
        assertTrue(program.contains("function callback(value)"))
    }

    @Test
    fun includesExplicitlyRequiredRootDependency() {
        val source = "var Wca=function(value){return value.split(\"\").reverse().join(\"\")};function rewriteUrl(url){var query=new Query(url);var n=query.get(\"n\");if(n)query.set(\"n\",n.split(\"\").reverse().join(\"\"));return query.toString()}function consume(entry){entry.url&&(entry.url=rewriteUrl(entry.url))}"

        val executable = YoutubePlayerTransformLocator.locateExecutable(
            source,
            requiredDependencies = setOf("Wca"),
        )
        val program = executable.urlThrottlingProgram.orEmpty()

        assertEquals(setOf("Wca"), executable.requiredDependencies)
        assertTrue(program.contains("var Wca=function(value)"))
    }

    @Test
    fun defersCallbackBodyDependenciesUntilCallbackIsNeeded() {
        val source = "function hidden(value){return value.split(\"\").reverse().join(\"\")}function callback(value){return hidden(value)}function dispatch(value,fn){return fn(value)}function rewriteUrl(url){var query=new Query(url);var n=query.get(\"n\");if(n)query.set(\"n\",dispatch(n,callback));return query.toString()}function consume(entry){entry.url&&(entry.url=rewriteUrl(entry.url))}"

        val program = YoutubePlayerTransformLocator.locateExecutable(source)
            .urlThrottlingProgram.orEmpty()

        assertTrue(program.contains("function callback(value)"))
        assertFalse(program.contains("function hidden(value)"))
    }

    @Test
    fun rejectsUnknownRequiredRootDependency() {
        val source = "function rewriteUrl(url){return url}function consume(entry){entry.url&&(entry.url=rewriteUrl(entry.url))}"

        val result = runCatching {
            YoutubePlayerTransformLocator.locateExecutable(
                source,
                requiredDependencies = setOf("missingHelper"),
            )
        }

        assertTrue(result.isFailure)
    }

    @Test
    fun resolvesRequiredDependencyThroughUniqueDottedGlobal() {
        val source = "var g={};g.Wca=function(value){return value.split(\"\").reverse().join(\"\")};function rewriteUrl(url){var query=new Query(url);var n=query.get(\"n\");if(n)query.set(\"n\",g.Wca(n));return query.toString()}function consume(entry){entry.url&&(entry.url=rewriteUrl(entry.url))}"

        val executable = YoutubePlayerTransformLocator.locateExecutable(
            source,
            requiredDependencies = setOf("Wca"),
        )
        val program = executable.urlThrottlingProgram.orEmpty()

        assertTrue(program.contains("g.Wca=function(value)"))
    }

    @Test
    fun includesCallbackStoredInObjectReturnedByHelperFunction() {
        val source = "function callback(value){return value.split(\"\").reverse().join(\"\")}function makeHandlers(){return {transform:callback,unused:null}}function rewriteUrl(url){var query=new Query(url);var n=query.get(\"n\");if(n)query.set(\"n\",makeHandlers().transform(n));return query.toString()}function consume(entry){entry.url&&(entry.url=rewriteUrl(entry.url))}"

        val executable = YoutubePlayerTransformLocator.locateExecutable(source)
        val program = executable.urlThrottlingProgram.orEmpty()

        assertEquals("rewriteUrl", executable.functions.urlThrottling)
        assertTrue(program.contains("function callback(value)"))
        assertTrue(program.contains("function makeHandlers()"))
    }

    @Test
    fun includesZeroArgumentFactoryValuePassedToCall() {
        val source = "function makeCallback(){return function(value){return value.split(\"\").reverse().join(\"\")}}var callback=makeCallback();function dispatch(value,fn){return fn(value)}function rewriteUrl(url){var query=new Query(url);var n=query.get(\"n\");if(n)query.set(\"n\",dispatch(n,callback));return query.toString()}function consume(entry){entry.url&&(entry.url=rewriteUrl(entry.url))}"

        val executable = YoutubePlayerTransformLocator.locateExecutable(source)
        val program = executable.urlThrottlingProgram.orEmpty()

        assertEquals("rewriteUrl", executable.functions.urlThrottling)
        assertTrue(program.contains("var callback=makeCallback();"))
        assertTrue(program.contains("function makeCallback()"))
    }

    @Test
    fun includesFunctionOwnerReferencedThroughDottedMember() {
        val source = "function TypeClass(){}function rewriteUrl(url){var query=new Query(url);var n=query.get(\"n\");var name=TypeClass.name;if(n&&name)query.set(\"n\",n.split(\"\").reverse().join(\"\"));return query.toString()}function consume(entry){entry.url&&(entry.url=rewriteUrl(entry.url))}"

        val executable = YoutubePlayerTransformLocator.locateExecutable(source)
        val program = executable.urlThrottlingProgram.orEmpty()

        assertEquals("rewriteUrl", executable.functions.urlThrottling)
        assertTrue(program.contains("function TypeClass()"))
    }

    @Test
    fun includesBoundedLiteralConstructorValueReferencedThroughMember() {
        val source = "g.y_=function(name){function Named(){}Named.name=name;return Named};var Wgb=new g.y_(\"Wgb\");function rewriteUrl(url){var query=new Query(url);var n=query.get(\"n\");var name=Wgb.name;if(n&&name)query.set(\"n\",n.split(\"\").reverse().join(\"\"));return query.toString()}function consume(entry){entry.url&&(entry.url=rewriteUrl(entry.url))}"

        val executable = YoutubePlayerTransformLocator.locateExecutable(source)
        val program = executable.urlThrottlingProgram.orEmpty()

        assertEquals("rewriteUrl", executable.functions.urlThrottling)
        assertTrue(program.take(5000), program.contains("var Wgb=new g.y_(\"Wgb\");"))
        assertTrue(program.contains("g.y_=function(name)"))
    }

    @Test
    fun excludesDynamicConstructorValueReferencedThroughMember() {
        val source = "g.y_=function(name){function Named(){}Named.name=name;return Named};var Wgb=new g.y_(inputName);function rewriteUrl(url){var query=new Query(url);var n=query.get(\"n\");var name=Wgb.name;if(n&&name)query.set(\"n\",n.split(\"\").reverse().join(\"\"));return query.toString()}function consume(entry){entry.url&&(entry.url=rewriteUrl(entry.url))}"

        val executable = YoutubePlayerTransformLocator.locateExecutable(source)
        val program = executable.urlThrottlingProgram.orEmpty()

        assertEquals("rewriteUrl", executable.functions.urlThrottling)
        assertFalse(program.contains("var Wgb=new g.y_(inputName);"))
    }

    @Test
    fun includesGlobalIterableReadFromForOfFunctionBody() {
        val source = "var Uqa=[1,2];function firstValue(){for(var value of Uqa){return value}}function rewriteUrl(url){var query=new Query(url);var n=query.get(\"n\");var value=firstValue();if(n&&value)query.set(\"n\",n.split(\"\").reverse().join(\"\"));return query.toString()}function consume(entry){entry.url&&(entry.url=rewriteUrl(entry.url))}"

        val executable = YoutubePlayerTransformLocator.locateExecutable(source)
        val program = executable.urlThrottlingProgram.orEmpty()

        assertEquals("rewriteUrl", executable.functions.urlThrottling)
        assertTrue(program.contains("var Uqa=[1,2];"))
    }

    @Test
    fun includesBoundedStaticStringSplitInitializer() {
        val source = "var Fib=\"alpha beta\".split(\" \");function rewriteUrl(url){var query=new Query(url);var n=query.get(\"n\");if(n&&Fib.length)query.set(\"n\",n.split(\"\").reverse().join(\"\"));return query.toString()}function consume(entry){entry.url&&(entry.url=rewriteUrl(entry.url))}"

        val executable = YoutubePlayerTransformLocator.locateExecutable(source)
        val program = executable.urlThrottlingProgram.orEmpty()

        assertEquals("rewriteUrl", executable.functions.urlThrottling)
        assertTrue(program.contains("var Fib=\"alpha beta\".split(\" \");"))
    }

    @Test
    fun includesSafeLiteralGlobalReferencedThroughMemberAccess() {
        val source = "var enabled=false;function rewriteUrl(url){var query=new Query(url);var n=query.get(\"n\");if(n&&enabled.active)query.set(\"n\",n.split(\"\").reverse().join(\"\"));return query.toString()}function consume(entry){entry.url&&(entry.url=rewriteUrl(entry.url))}"

        val executable = YoutubePlayerTransformLocator.locateExecutable(source)
        val program = executable.urlThrottlingProgram.orEmpty()

        assertEquals("rewriteUrl", executable.functions.urlThrottling)
        assertTrue(program.contains("var enabled=false;"))
    }

    @Test
    fun excludesDynamicStringSplitInitializer() {
        val source = "var Fib=seed.split(\" \");function rewriteUrl(url){var query=new Query(url);var n=query.get(\"n\");if(n&&Fib.length)query.set(\"n\",n.split(\"\").reverse().join(\"\"));return query.toString()}function consume(entry){entry.url&&(entry.url=rewriteUrl(entry.url))}"

        val executable = YoutubePlayerTransformLocator.locateExecutable(source)
        val program = executable.urlThrottlingProgram.orEmpty()

        assertEquals("rewriteUrl", executable.functions.urlThrottling)
        assertFalse(program.contains("var Fib=seed.split(\" \");"))
    }

    @Test
    fun includesFunctionWrappedInObjectFreezeWhenPassedAsCallback() {
        val source = "var qbb=Object.freeze(function(value){return value});function dispatch(fn,value){return fn(value)}function rewriteUrl(url){var query=new Query(url);var n=query.get(\"n\");if(n)query.set(\"n\",dispatch(qbb,n));return query.toString()}function consume(entry){entry.url&&(entry.url=rewriteUrl(entry.url))}"

        val executable = YoutubePlayerTransformLocator.locateExecutable(source)
        val program = executable.urlThrottlingProgram.orEmpty()

        assertEquals("rewriteUrl", executable.functions.urlThrottling)
        assertTrue(program.contains("var qbb=Object.freeze(function(value){return value});"))
    }

    @Test
    fun doesNotTreatArbitraryFunctionWrapperAsFrozenFunctionValue() {
        val source = "var qbb=customFreeze(function(value){return value});function dispatch(fn,value){return fn(value)}function rewriteUrl(url){var query=new Query(url);var n=query.get(\"n\");if(n)query.set(\"n\",dispatch(qbb,n));return query.toString()}function consume(entry){entry.url&&(entry.url=rewriteUrl(entry.url))}"

        val executable = YoutubePlayerTransformLocator.locateExecutable(source)
        val program = executable.urlThrottlingProgram.orEmpty()

        assertEquals("rewriteUrl", executable.functions.urlThrottling)
        assertFalse(program.contains("var qbb=customFreeze(function(value){return value});"))
    }

    @Test
    fun nestedFunctionParameterDoesNotHideOuterGlobalDependency() {
        val source = "function Wca(value){return value.split(\"\").reverse().join(\"\")}function rewriteUrl(url){var query=new Query(url);var n=query.get(\"n\");function shadow(Wca){return Wca(\"local\")}if(n)query.set(\"n\",Wca(n));return query.toString()}function consume(entry){entry.url&&(entry.url=rewriteUrl(entry.url))}"

        val executable = YoutubePlayerTransformLocator.locateExecutable(source)
        val program = executable.urlThrottlingProgram.orEmpty()

        assertEquals("rewriteUrl", executable.functions.urlThrottling)
        assertTrue(program.contains("function Wca(value)"))
        assertTrue(program.contains("function shadow(Wca)"))
    }

    @Test
    fun includesEmptyNativeCollectionReadThroughMemberAccess() {
        val source = "var Pqa=new Set;function trackMessage(value){Pqa.add(value);return Pqa.has(value)}function rewriteUrl(url){var query=new Query(url);var n=query.get(\"n\");var tracked=trackMessage(n);if(n&&tracked)query.set(\"n\",n.split(\"\").reverse().join(\"\"));return query.toString()}function consume(entry){entry.url&&(entry.url=rewriteUrl(entry.url))}"

        val executable = YoutubePlayerTransformLocator.locateExecutable(source)
        val program = executable.urlThrottlingProgram.orEmpty()

        assertEquals("rewriteUrl", executable.functions.urlThrottling)
        assertTrue(program.contains("var Pqa=new Set;"))
    }

    @Test
    fun doesNotIncludeNativeCollectionWithDynamicInitializerArgument() {
        val source = "var Pqa=new Set(seed);function trackMessage(value){Pqa.add(value);return Pqa.has(value)}function rewriteUrl(url){var query=new Query(url);var n=query.get(\"n\");var tracked=trackMessage(n);if(n&&tracked)query.set(\"n\",n.split(\"\").reverse().join(\"\"));return query.toString()}function consume(entry){entry.url&&(entry.url=rewriteUrl(entry.url))}"

        val executable = YoutubePlayerTransformLocator.locateExecutable(source)
        val program = executable.urlThrottlingProgram.orEmpty()

        assertEquals("rewriteUrl", executable.functions.urlThrottling)
        assertFalse(program.contains("var Pqa=new Set(seed);"))
    }

    @Test
    fun includesUninitializedTopLevelVariableInUrlTransformClosure() {
        val source = "var counter,cache;function rewriteUrl(url){if(!counter)counter=1;var query=new Query(url);var n=query.get(\"n\");if(n)query.set(\"n\",n.split(\"\").reverse().join(\"\"));return query.toString()}function consume(entry){entry.url&&(entry.url=rewriteUrl(entry.url))}"

        val executable = YoutubePlayerTransformLocator.locateExecutable(source)

        assertEquals("rewriteUrl", executable.functions.urlThrottling)
        assertTrue(executable.urlThrottlingProgram.orEmpty().contains("var counter;"))
    }

    @Test
    fun suppliesUndefinedForIsolatedLazyModuleState() {
        val source = "function moduleState(){if(!state){var value=state=new Object()}return state}function rewriteUrl(url){var value=moduleState();var query=new Query(url);var n=query.get(\"n\");if(n)query.set(\"n\",n.split(\"\").reverse().join(\"\"));return query.toString()}function consume(entry){entry.url&&(entry.url=rewriteUrl(entry.url))}"

        val executable = YoutubePlayerTransformLocator.locateExecutable(source)

        assertEquals("rewriteUrl", executable.functions.urlThrottling)
        assertTrue(executable.urlThrottlingProgram.orEmpty().contains("var state=undefined;"))
    }

    @Test
    fun doesNotStubGuardedStateWithoutLazyAssignment() {
        val source = "function moduleState(){if(!state){var value=new Object()}return state}function rewriteUrl(url){var value=moduleState();var query=new Query(url);var n=query.get(\"n\");if(n)query.set(\"n\",n.split(\"\").reverse().join(\"\"));return query.toString()}function consume(entry){entry.url&&(entry.url=rewriteUrl(entry.url))}"

        val executable = YoutubePlayerTransformLocator.locateExecutable(source)

        assertEquals("rewriteUrl", executable.functions.urlThrottling)
        assertFalse(executable.urlThrottlingProgram.orEmpty().contains("var state=undefined;"))
    }

    @Test
    fun prioritizesFunctionPassedToNativeConstructor() {
        val source = "function cp(value){return value+1}function rewriteUrl(url){var hook=new Uo(1,2,3,4,cp);var query=new Query(url);var n=query.get(\"n\");if(hook&&n)query.set(\"n\",n.split(\"\").reverse().join(\"\"));return query.toString()}function consume(entry){entry.url&&(entry.url=rewriteUrl(entry.url))}"

        val executable = YoutubePlayerTransformLocator.locateExecutable(source)

        assertEquals("rewriteUrl", executable.functions.urlThrottling)
        assertTrue(executable.urlThrottlingProgram.orEmpty().contains("function cp(value)"))
    }

    @Test
    fun includesFunctionPassedAsParenthesizedCallArgument() {
        val source = "function callback(value){return value.split(\"\").reverse().join(\"\")}function dispatch(fn,value){return fn(value)}function rewriteUrl(url){var query=new Query(url);var n=query.get(\"n\");if(n)query.set(\"n\",dispatch((callback),n));return query.toString()}function consume(entry){entry.url&&(entry.url=rewriteUrl(entry.url))}"

        val executable = YoutubePlayerTransformLocator.locateExecutable(source)
        val program = executable.urlThrottlingProgram.orEmpty()

        assertEquals("rewriteUrl", executable.functions.urlThrottling)
        assertTrue(program.contains("function callback(value)"))
        assertTrue(program.contains("function dispatch(fn,value)"))
    }

    @Test
    fun includesCallbackForComputedMemberCallWithGrouping() {
        val source = "function callback(value){return value.split(\"\").reverse().join(\"\")}function dispatch(fn,value){return fn(value)}var handlers={run:dispatch};function rewriteUrl(url){var query=new Query(url);var n=query.get(\"n\");if(n)query.set(\"n\",handlers[\"run\"]((callback),n));return query.toString()}function consume(entry){entry.url&&(entry.url=rewriteUrl(entry.url))}"

        val executable = YoutubePlayerTransformLocator.locateExecutable(source)
        val program = executable.urlThrottlingProgram.orEmpty()

        assertEquals("rewriteUrl", executable.functions.urlThrottling)
        assertTrue(program.contains("function callback(value)"))
        assertTrue(program.contains("function dispatch(fn,value)"))
    }

    @Test
    fun nestedControlConditionDoesNotHideCallbackClosureDependency() {
        val source = "function callback(value){return value}function register(fn){return fn}function rewriteUrl(url){var query=new Query(url);var n=query.get(\"n\");var result=register(function(){if((n,callback))return n});if(n)query.set(\"n\",result());return query.toString()}function consume(entry){entry.url&&(entry.url=rewriteUrl(entry.url))}"

        val executable = YoutubePlayerTransformLocator.locateExecutable(source)
        val program = executable.urlThrottlingProgram.orEmpty()

        assertEquals("rewriteUrl", executable.functions.urlThrottling)
        assertTrue(program.contains("function callback(value)"))
        assertTrue(program.contains("function register(fn)"))
    }

    @Test
    fun includesConstructorCallbackDespiteOtherNestedParameterWithSameName() {
        val source = "function callback(value){return value}function moduleSetup(){function local(callback){return callback}var hook=new Uo(1,2,3,4,callback);return hook}function rewriteUrl(url){var hook=moduleSetup();var query=new Query(url);var n=query.get(\"n\");if(n)query.set(\"n\",n.split(\"\").reverse().join(\"\"));return hook&&query.toString()}function consume(entry){entry.url&&(entry.url=rewriteUrl(entry.url))}"

        val executable = YoutubePlayerTransformLocator.locateExecutable(source)
        val program = executable.urlThrottlingProgram.orEmpty()

        assertEquals("rewriteUrl", executable.functions.urlThrottling)
        assertTrue(program.contains("function callback(value)"))
        assertTrue(program.contains("new Uo(1,2,3,4,callback)"))
    }

    @Test
    fun doesNotExtractAssignmentNestedInsideTopLevelCall() {
        val source = "var _yt_player={};(function(g){foo(o0.ytcsi.tick=Iw);function rewriteUrl(url){var query=new Query(url);var n=query.get(\"n\");if(n)query.set(\"n\",o0.ytcsi.tick(n));return query.toString()}function consume(entry){entry.url&&(entry.url=rewriteUrl(entry.url))}})(_yt_player);"

        val diagnostics = mutableListOf<String>()
        val executable = YoutubePlayerTransformLocator.locateExecutable(source, diagnostics::add)
        val program = executable.urlThrottlingProgram.orEmpty()

        assertEquals(diagnostics.joinToString("\n"), "rewriteUrl", executable.functions.urlThrottling)
        assertFalse(program.contains("o0.ytcsi.tick=Iw)"))
        assertFalse(program.contains("foo("))
    }

    @Test
    fun doesNotPromoteGenericUrlNormalizerWithoutNGetter() {
        val source = "function normalize(url){return url.trim()} function consume(entry){entry.url&&(entry.url=normalize(entry.url))}"

        assertNull(YoutubePlayerTransformLocator.locateExecutable(source).functions.urlThrottling)
    }

    @Test
    fun doesNotTreatManifestPathNRewriterAsQueryNTransform() {
        val source = """
            function rewritePathN(url){
                try {
                    var n=(new Query(url,true)).get("n");
                    if(n){var match=url.match(/\/n\/([^/]+)/);if(match&&match[1]&&match[1]!==n)return url.replace(/\/n\/([^/]+)/,"/n/"+n)}
                } catch(error) {}
                return url;
            }
            function consume(entry){entry.url&&(entry.url=rewritePathN(entry.url))}
        """.trimIndent()

        val functions = YoutubePlayerTransformLocator.locateExecutable(source).functions
        assertNull(functions.urlThrottling)
        assertEquals("rewritePathN", functions.urlPathThrottling)
    }

}
