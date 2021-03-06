/*
 * Copyright 2013-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.cxx;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.python.PythonPackageComponents;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleSourcePath;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.rules.SymlinkTree;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.io.Files;

import java.nio.file.Path;
import java.nio.file.Paths;

public class CxxDescriptionEnhancer {

  private static final Flavor HEADER_SYMLINK_TREE_FLAVOR = new Flavor("header-symlink-tree");
  private static final BuildRuleType LEX_TYPE = new BuildRuleType("lex");
  private static final BuildRuleType YACC_TYPE = new BuildRuleType("yacc");
  private static final Flavor CXX_LINK_BINARY_FLAVOR = new Flavor("binary");

  private CxxDescriptionEnhancer() {}

  /**
   * @return the {@link BuildTarget} to use for the {@link BuildRule} generating the
   *    symlink tree of headers.
   */
  public static BuildTarget createHeaderSymlinkTreeTarget(BuildTarget target) {
    return BuildTargets.extendFlavoredBuildTarget(target, HEADER_SYMLINK_TREE_FLAVOR);
  }

  /**
   * @return the {@link Path} to use for the symlink tree of headers.
   */
  public static Path getHeaderSymlinkTreePath(BuildTarget target) {
    return BuildTargets.getGenPath(
        createHeaderSymlinkTreeTarget(target),
        "%s");
  }

  /**
   * @return a map of header locations to input {@link SourcePath} objects formed by parsing the
   *    input {@link SourcePath} objects for the "headers" parameter.
   */
  public static ImmutableMap<Path, SourcePath> parseHeaders(
      BuildTarget target,
      Iterable<SourcePath> inputs) {

    return CxxPreprocessables.resolveHeaderMap(
        target,
        SourcePaths.getSourcePathNames(
            target,
            "headers",
            inputs));
  }

  /**
   * @return a list {@link CxxSource} objects formed by parsing the input {@link SourcePath}
   *    objects for the "srcs" parameter.
   */
  public static ImmutableList<CxxSource> parseCxxSources(
      BuildTarget target,
      Iterable<SourcePath> inputs) {

    return CxxCompilableEnhancer.resolveCxxSources(
        SourcePaths.getSourcePathNames(
            target,
            "srcs",
            inputs));
  }

  @VisibleForTesting
  protected static BuildTarget createLexBuildTarget(BuildTarget target, String name) {
    return BuildTargets.extendFlavoredBuildTarget(
        target.getUnflavoredTarget(),
        new Flavor(
            String.format(
                "lex-%s",
                name.replace('/', '-').replace('.', '-'))));
  }

  @VisibleForTesting
  protected static BuildTarget createYaccBuildTarget(BuildTarget target, String name) {
    return BuildTargets.extendFlavoredBuildTarget(
        target.getUnflavoredTarget(),
        new Flavor(
            String.format(
                "yacc-%s",
                name.replace('/', '-').replace('.', '-'))));
  }

  /**
   * @return the output path prefix to use for yacc generated files.
   */
  @VisibleForTesting
  protected static Path getYaccOutputPrefix(BuildTarget target, String name) {
    BuildTarget flavoredTarget = createYaccBuildTarget(target, name);
    return BuildTargets.getGenPath(flavoredTarget, "%s/" + name);
  }

  /**
   * @return the output path to use for the lex generated C/C++ source.
   */
  @VisibleForTesting
  protected static Path getLexSourceOutputPath(BuildTarget target, String name) {
    BuildTarget flavoredTarget = createLexBuildTarget(target, name);
    return BuildTargets.getGenPath(flavoredTarget, "%s/" + name + ".cc");
  }

  /**
   * @return the output path to use for the lex generated C/C++ header.
   */
  @VisibleForTesting
  protected static Path getLexHeaderOutputPath(BuildTarget target, String name) {
    BuildTarget flavoredTarget = createLexBuildTarget(target, name);
    return BuildTargets.getGenPath(flavoredTarget, "%s/" + name + ".h");
  }

  /**
   * Generate {@link Lex} and {@link Yacc} rules generating C/C++ sources from the
   * given lex/yacc sources.
   *
   * @return {@link CxxHeaderSourceSpec} containing the generated headers/sources
   */
  public static CxxHeaderSourceSpec createLexYaccBuildRules(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxPlatform config,
      ImmutableList<String> lexFlags,
      ImmutableMap<String, SourcePath> lexSrcs,
      ImmutableList<String> yaccFlags,
      ImmutableMap<String, SourcePath> yaccSrcs) {

    ImmutableList.Builder<CxxSource> lexYaccCxxSourcesBuilder = ImmutableList.builder();
    ImmutableMap.Builder<Path, SourcePath> lexYaccHeadersBuilder = ImmutableMap.builder();

    // Loop over all lex sources, generating build rule for each one and adding the sources
    // and headers it generates to our bookkeeping maps.
    for (ImmutableMap.Entry<String, SourcePath> ent : lexSrcs.entrySet()) {
      final String name = ent.getKey();
      final SourcePath source = ent.getValue();

      BuildTarget target = createLexBuildTarget(params.getBuildTarget(), name);
      Path outputSource = getLexSourceOutputPath(target, name);
      Path outputHeader = getLexHeaderOutputPath(target, name);

      // Create the build rule to run lex on this source and add it to the resolver.
      Lex lex = new Lex(
          params.copyWithChanges(
              LEX_TYPE,
              target,
              ImmutableSortedSet.copyOf(
                  SourcePaths.filterBuildRuleInputs(ImmutableList.of(source))),
              ImmutableSortedSet.<BuildRule>of()),
          config.getLex(),
          ImmutableList.<String>builder()
              .addAll(config.getLexFlags())
              .addAll(lexFlags)
              .build(),
          outputSource,
          outputHeader,
          source);
      resolver.addToIndex(lex);

      // Record the output source and header as {@link BuildRuleSourcePath} objects.
      lexYaccCxxSourcesBuilder.add(new CxxSource(
          name + ".cc",
          new BuildRuleSourcePath(lex, outputSource)));
      lexYaccHeadersBuilder.put(
          params.getBuildTarget().getBasePath().resolve(name + ".h"),
          new BuildRuleSourcePath(lex, outputHeader));
    }

    // Loop over all yaccc sources, generating build rule for each one and adding the sources
    // and headers it generates to our bookkeeping maps.
    for (ImmutableMap.Entry<String, SourcePath> ent : yaccSrcs.entrySet()) {
      final String name = ent.getKey();
      final SourcePath source = ent.getValue();

      BuildTarget target = createYaccBuildTarget(params.getBuildTarget(), name);
      Path outputPrefix = getYaccOutputPrefix(target, Files.getNameWithoutExtension(name));

      // Create the build rule to run yacc on this source and add it to the resolver.
      Yacc yacc = new Yacc(
          params.copyWithChanges(
              YACC_TYPE,
              target,
              ImmutableSortedSet.copyOf(
                  SourcePaths.filterBuildRuleInputs(ImmutableList.of(source))),
              ImmutableSortedSet.<BuildRule>of()),
          config.getYacc(),
          ImmutableList.<String>builder()
              .addAll(config.getYaccFlags())
              .addAll(yaccFlags)
              .build(),
          outputPrefix,
          source);
      resolver.addToIndex(yacc);

      // Record the output source and header as {@link BuildRuleSourcePath} objects.
      lexYaccCxxSourcesBuilder.add(
          new CxxSource(
              name + ".cc",
              new BuildRuleSourcePath(yacc, Yacc.getSourceOutputPath(outputPrefix))));

      lexYaccHeadersBuilder.put(
          params.getBuildTarget().getBasePath().resolve(name + ".h"),
          new BuildRuleSourcePath(yacc, Yacc.getHeaderOutputPath(outputPrefix)));
    }

    return new CxxHeaderSourceSpec(
        lexYaccHeadersBuilder.build(),
        lexYaccCxxSourcesBuilder.build());
  }

  public static SymlinkTree createHeaderSymlinkTreeBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      ImmutableMap<Path, SourcePath> headers) {

    // Setup the header and symlink tree rules
    BuildTarget headerSymlinkTreeTarget = createHeaderSymlinkTreeTarget(params.getBuildTarget());
    Path headerSymlinkTreeRoot = getHeaderSymlinkTreePath(params.getBuildTarget());
    final SymlinkTree headerSymlinkTree = CxxPreprocessables.createHeaderSymlinkTreeBuildRule(
        headerSymlinkTreeTarget,
        params,
        headerSymlinkTreeRoot,
        headers);
    resolver.addToIndex(headerSymlinkTree);

    return headerSymlinkTree;
  }

  public static CxxPreprocessorInput combineCxxPreprocessorInput(
      BuildRuleParams params,
      CxxPlatform config,
      ImmutableList<String> preprocessorFlags,
      SymlinkTree headerSymlinkTree,
      ImmutableMap<Path, SourcePath> headers) {

    // Write the compile rules for all C/C++ sources in this rule.
    CxxPreprocessorInput cxxPreprocessorInputFromDeps =
        CxxPreprocessables.getTransitiveCxxPreprocessorInput(
            FluentIterable.from(params.getDeps())
                .filter(Predicates.instanceOf(CxxPreprocessorDep.class)));

    return CxxPreprocessorInput.concat(
        ImmutableList.of(
            new CxxPreprocessorInput(
                ImmutableSet.of(headerSymlinkTree.getBuildTarget()),
                /* cppflags */ ImmutableList.<String>builder()
                    .addAll(config.getCppflags())
                    .addAll(preprocessorFlags)
                    .build(),
                /* cxxppflags */ ImmutableList.<String>builder()
                    .addAll(config.getCxxppflags())
                    .addAll(preprocessorFlags)
                    .build(),
                /* includes */ headers,
                /* includeRoots */ ImmutableList.of(headerSymlinkTree.getRoot()),
                /* systemIncludeRoots */ ImmutableList.<Path>of()),
            cxxPreprocessorInputFromDeps));

  }

  /**
   * Build up the rules to track headers and compile sources for descriptions which handle C/C++
   * sources and headers.
   *
   * @return a list of {@link SourcePath} objects representing the object files from the result of
   *    compiling the given C/C++ source.
   */
  public static ImmutableList<SourcePath> createPreprocessAndCompileBuildRules(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxPlatform config,
      CxxPreprocessorInput cxxPreprocessorInput,
      ImmutableList<String> compilerFlags,
      boolean pic,
      ImmutableList<CxxSource> sources) {

    ImmutableSortedSet<BuildRule> objectRules = CxxCompilableEnhancer.createCompileBuildRules(
        params,
        resolver,
        config.getCxx(),
        cxxPreprocessorInput,
        compilerFlags,
        pic,
        sources);
    resolver.addAllToIndex(objectRules);

    return FluentIterable.from(objectRules)
        .transform(SourcePaths.TO_BUILD_RULE_SOURCE_PATH)
        .toList();
  }

  private static final Flavor STATIC_FLAVOR = new Flavor("static");
  private static final Flavor SHARED_FLAVOR = new Flavor("shared");

  public static BuildTarget createStaticLibraryBuildTarget(BuildTarget target) {
    return BuildTargets.extendFlavoredBuildTarget(target, STATIC_FLAVOR);
  }

  public static BuildTarget createSharedLibraryBuildTarget(BuildTarget target) {
    return BuildTargets.extendFlavoredBuildTarget(target, SHARED_FLAVOR);
  }

  public static String getSharedLibrarySoname(BuildTarget target) {
    return String.format(
        "lib%s_%s.so",
        target.getBaseName().substring(2).replace('/', '_'),
        target.getShortNameOnly());
  }

  public static Path getSharedLibraryOutputPath(BuildTarget target) {
    String name = String.format("lib%s.so", target.getShortNameOnly());
    return BuildTargets.getBinPath(target, "%s/" + name);
  }

  public static CxxLibrary createCxxLibraryBuildRules(
      final BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      ImmutableList<String> preprocessorFlags,
      final ImmutableList<String> propagatedPpFlags,
      final ImmutableMap<Path, SourcePath> headers,
      ImmutableList<String> compilerFlags,
      ImmutableList<CxxSource> sources,
      final boolean linkWhole) {

    // Setup the header symlink tree and combine all the preprocessor input from this rule
    // and all dependencies.
    final SymlinkTree headerSymlinkTree =
        createHeaderSymlinkTreeBuildRule(params, resolver, headers);
    CxxPreprocessorInput cxxPreprocessorInput = combineCxxPreprocessorInput(
        params,
        cxxPlatform,
        preprocessorFlags,
        headerSymlinkTree,
        headers);

    // Create rules for compiling the non-PIC object files.
    ImmutableList<SourcePath> objects = createPreprocessAndCompileBuildRules(
        params,
        resolver,
        cxxPlatform,
        cxxPreprocessorInput,
        compilerFlags,
        /* pic */ false,
        sources);

    // Write a build rule to create the archive for this C/C++ library.
    final BuildTarget staticLibraryTarget = createStaticLibraryBuildTarget(params.getBuildTarget());
    final Path staticLibraryPath =  Archives.getArchiveOutputPath(staticLibraryTarget);
    final Archive staticLibraryBuildRule = Archives.createArchiveRule(
        staticLibraryTarget,
        params,
        cxxPlatform.getAr(),
        staticLibraryPath,
        objects);
    resolver.addToIndex(staticLibraryBuildRule);

    // Create rules for compiling the PIC object files.
    ImmutableList<SourcePath> picObjects = createPreprocessAndCompileBuildRules(
        params,
        resolver,
        cxxPlatform,
        cxxPreprocessorInput,
        compilerFlags,
        /* pic */ true,
        sources);

    // Setup the rules to link the shared library.
    final BuildTarget sharedLibraryTarget = createSharedLibraryBuildTarget(params.getBuildTarget());
    final String sharedLibrarySoname = getSharedLibrarySoname(params.getBuildTarget());
    final Path sharedLibraryPath = getSharedLibraryOutputPath(params.getBuildTarget());
    final CxxLink sharedLibraryBuildRule = CxxLinkableEnhancer.createCxxLinkableBuildRule(
        params,
        cxxPlatform.getCxxld(),
        cxxPlatform.getCxxldflags(),
        cxxPlatform.getLdflags(),
        sharedLibraryTarget,
        CxxLinkableEnhancer.LinkType.SHARED,
        Optional.of(sharedLibrarySoname),
        sharedLibraryPath,
        picObjects,
        NativeLinkable.Type.SHARED,
        params.getDeps());
    resolver.addToIndex(sharedLibraryBuildRule);

    // Create the CppLibrary rule that dependents can references from the action graph
    // to get information about this rule (e.g. how this rule contributes to the C/C++
    // preprocessor or linker).  Long-term this should probably be collapsed into the
    // TargetGraph when it becomes exposed to build rule creation.
    return new CxxLibrary(params) {

      @Override
      public CxxPreprocessorInput getCxxPreprocessorInput() {
        return new CxxPreprocessorInput(
            ImmutableSet.of(headerSymlinkTree.getBuildTarget()),
            propagatedPpFlags,
            propagatedPpFlags,
            headers,
            ImmutableList.of(
                CxxDescriptionEnhancer.getHeaderSymlinkTreePath(params.getBuildTarget())),
            ImmutableList.<Path>of());
      }

      @Override
      public NativeLinkableInput getNativeLinkableInput(NativeLinkable.Type type) {

        // Build up the arguments used to link this library.  If we're linking the
        // whole archive, wrap the library argument in the necessary "ld" flags.
        ImmutableList.Builder<String> linkerArgsBuilder = ImmutableList.builder();
        if (linkWhole && type == Type.STATIC) {
          linkerArgsBuilder.add("--whole-archive");
        }
        linkerArgsBuilder.add(
            type == Type.STATIC ?
                staticLibraryPath.toString() :
                sharedLibraryPath.toString());
        if (linkWhole && type == Type.STATIC) {
          linkerArgsBuilder.add("--no-whole-archive");
        }
        final ImmutableList<String> linkerArgs = linkerArgsBuilder.build();

        return new NativeLinkableInput(
            ImmutableList.<SourcePath>of(
                new BuildRuleSourcePath(
                    type == Type.STATIC ?
                        staticLibraryBuildRule :
                        sharedLibraryBuildRule)),
            linkerArgs);
      }

      @Override
      public PythonPackageComponents getPythonPackageComponents() {
        return new PythonPackageComponents(
            ImmutableMap.<Path, SourcePath>of(),
            ImmutableMap.<Path, SourcePath>of(),
            ImmutableMap.<Path, SourcePath>of(
                Paths.get(sharedLibrarySoname),
                new BuildRuleSourcePath(sharedLibraryBuildRule)));
      }

    };
  }

  @VisibleForTesting
  protected static Path getOutputPath(BuildTarget target) {
    return BuildTargets.getBinPath(target, "%s/" + target.getShortName());
  }

  @VisibleForTesting
  protected static BuildTarget createCxxLinkTarget(BuildTarget target) {
    return BuildTargets.extendFlavoredBuildTarget(target, CXX_LINK_BINARY_FLAVOR);
  }

  public static CxxLink createBuildRulesForCxxBinaryDescriptionArg(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      CxxBinaryDescription.Arg args) {

    // Extract the C/C++ sources from the constructor arg.
    ImmutableList<CxxSource> srcs =
        CxxDescriptionEnhancer.parseCxxSources(
            params.getBuildTarget(),
            args.srcs.or(ImmutableList.<SourcePath>of()));

    // Extract the header map from the our constructor arg.
    ImmutableMap<Path, SourcePath> headers =
        CxxDescriptionEnhancer.parseHeaders(
            params.getBuildTarget(),
            args.headers.or((ImmutableList.<SourcePath>of())));

    // Extract the lex sources.
    ImmutableMap<String, SourcePath> lexSrcs =
        SourcePaths.getSourcePathNames(
            params.getBuildTarget(),
            "lexSrcs",
            args.lexSrcs.or(ImmutableList.<SourcePath>of()));

    // Extract the yacc sources.
    ImmutableMap<String, SourcePath> yaccSrcs =
        SourcePaths.getSourcePathNames(
            params.getBuildTarget(),
            "yaccSrcs",
            args.yaccSrcs.or(ImmutableList.<SourcePath>of()));

    // Setup the rules to run lex/yacc.
    CxxHeaderSourceSpec lexYaccSources =
        CxxDescriptionEnhancer.createLexYaccBuildRules(
            params,
            resolver,
            cxxPlatform,
            ImmutableList.<String>of(),
            lexSrcs,
            ImmutableList.<String>of(),
            yaccSrcs);

    // Setup the header symlink tree and combine all the preprocessor input from this rule
    // and all dependencies.
    SymlinkTree headerSymlinkTree = CxxDescriptionEnhancer.createHeaderSymlinkTreeBuildRule(
        params,
        resolver,
        headers);
    CxxPreprocessorInput cxxPreprocessorInput = CxxDescriptionEnhancer.combineCxxPreprocessorInput(
        params,
        cxxPlatform,
        args.preprocessorFlags.or(ImmutableList.<String>of()),
        headerSymlinkTree,
        ImmutableMap.<Path, SourcePath>builder()
            .putAll(headers)
            .putAll(lexYaccSources.getCxxHeaders())
            .build());

    // Generate the rules for setting up and headers, preprocessing, and compiling the input
    // sources and return the source paths for the object files.
    ImmutableList<SourcePath> objects =
        CxxDescriptionEnhancer.createPreprocessAndCompileBuildRules(
            params,
            resolver,
            cxxPlatform,
            cxxPreprocessorInput,
            args.compilerFlags.or(ImmutableList.<String>of()),
            /* pic */ false,
            ImmutableList.<CxxSource>builder()
                .addAll(srcs)
                .addAll(lexYaccSources.getCxxSources())
                .build());

    // Generate the final link rule.  We use the top-level target as the link rule's
    // target, so that it corresponds to the actual binary we build.
    Path output = getOutputPath(params.getBuildTarget());
    CxxLink cxxLink = CxxLinkableEnhancer.createCxxLinkableBuildRule(
        params,
        cxxPlatform.getCxxld(),
        cxxPlatform.getCxxldflags(),
        cxxPlatform.getLdflags(),
        createCxxLinkTarget(params.getBuildTarget()),
        CxxLinkableEnhancer.LinkType.EXECUTABLE,
        Optional.<String>absent(),
        output,
        objects,
        NativeLinkable.Type.STATIC,
        params.getDeps());
    resolver.addToIndex(cxxLink);

    return cxxLink;
  }

}
