package com.facebook.buck.groovy;

import com.facebook.buck.java.CopyResourcesStep;
import com.facebook.buck.java.HasClasspathEntries;
import com.facebook.buck.java.HasJavaAbi;
import com.facebook.buck.java.JarDirectoryStep;
import com.facebook.buck.java.JavaLibrary;
import com.facebook.buck.java.JavaLibraryClasspathProvider;
import com.facebook.buck.java.JavaLibraryRules;
import com.facebook.buck.java.JavaPackageFinder;
import com.facebook.buck.java.ResourcesRootPackageFinder;
import com.facebook.buck.java.abi.AbiWriterProtocol;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AnnotationProcessingData;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildDependencies;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExportDependencies;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.SortedSet;

import javax.annotation.Nullable;

public class GroovyLibrary extends AbstractBuildRule
    implements HasClasspathEntries, ExportDependencies, JavaLibrary {

  private final Optional<Path> outputJar;
  private final ImmutableSortedSet<SourcePath> srcs;
  private final ImmutableSortedSet<SourcePath> resources;
  private final ImmutableSet<BuildTargetPattern> visibilityPatterns;
  private final ImmutableSortedSet<BuildRule> exportedDeps;
  private final ImmutableSortedSet<BuildRule> providedDeps;
  private final Optional<Path> resourcesRoot;
  private final Supplier<ImmutableSetMultimap<JavaLibrary, Path>> outputClasspathEntriesSupplier;
  private final Supplier<ImmutableSetMultimap<JavaLibrary, Path>> declaredClasspathEntriesSupplier;
  private final Supplier<ImmutableSetMultimap<JavaLibrary, Path>> transitiveClasspathEntriesSupplier;

  protected GroovyLibrary(
      BuildRuleParams params,
      ImmutableSortedSet<SourcePath> srcs,
      ImmutableSortedSet<SourcePath> resources,
      ImmutableSortedSet<BuildRule> exportedDeps,
      ImmutableSortedSet<BuildRule> providedDeps,
      Optional<Path> resourcesRoot) {

    super(params);
    this.srcs = srcs;
    this.resources = resources;
    this.exportedDeps = exportedDeps;
    this.providedDeps = providedDeps;
    this.resourcesRoot = resourcesRoot;
    this.visibilityPatterns = Preconditions.checkNotNull(params.getVisibilityPatterns());

    if (!srcs.isEmpty() || !resources.isEmpty()) {
      this.outputJar = Optional.of(getOutputJarPath(getBuildTarget()));
    } else {
      this.outputJar = Optional.absent();
    }

    this.outputClasspathEntriesSupplier =
        Suppliers.memoize(
            new Supplier<ImmutableSetMultimap<JavaLibrary, Path>>() {
              @Override
              public ImmutableSetMultimap<JavaLibrary, Path> get() {
                return JavaLibraryClasspathProvider.getOutputClasspathEntries(
                    GroovyLibrary.this,
                    outputJar);
              }
            });

    this.transitiveClasspathEntriesSupplier =
        Suppliers.memoize(
            new Supplier<ImmutableSetMultimap<JavaLibrary, Path>>() {
              @Override
              public ImmutableSetMultimap<JavaLibrary, Path> get() {
                return JavaLibraryClasspathProvider.getTransitiveClasspathEntries(
                    GroovyLibrary.this,
                    outputJar);
              }
            });

    this.declaredClasspathEntriesSupplier =
        Suppliers.memoize(
            new Supplier<ImmutableSetMultimap<JavaLibrary, Path>>() {
              @Override
              public ImmutableSetMultimap<JavaLibrary, Path> get() {
                return JavaLibraryClasspathProvider.getDeclaredClasspathEntries(
                    GroovyLibrary.this);
              }
            });

  }

  /**
   * Building a java_library() rule entails compiling the .java files specified in the srcs
   * attribute. They are compiled into a directory under
   * {@link com.facebook.buck.util.BuckConstant#BIN_DIR}.
   */
  @Override
  public final ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    ImmutableSetMultimap<JavaLibrary, Path> transitiveClasspathEntries =
        ImmutableSetMultimap.<JavaLibrary, Path>builder()
            .putAll(getTransitiveClasspathEntries())
            .build();

    ImmutableSetMultimap<JavaLibrary, Path> declaredClasspathEntries =
        ImmutableSetMultimap.<JavaLibrary, Path>builder()
            .putAll(getDeclaredClasspathEntries())
            .build();

    // Always create the output directory, even if there are no .groovy files to compile because there
    // might be resources that need to be copied there.
    Path outputDirectory = getClassesDir(getBuildTarget());
    steps.add(new MakeCleanDirectoryStep(outputDirectory));

    // We don't want to add these to the declared or transitive deps, since they're only used at
    // compile time.
    Collection<Path> provided = JavaLibraryClasspathProvider.getJavaLibraryDeps(providedDeps)
        .transformAndConcat(
            new Function<JavaLibrary, Collection<Path>>() {
              @Override
              public Collection<Path> apply(JavaLibrary input) {
                return input.getOutputClasspathEntries().values();
              }
            })
        .filter(Predicates.notNull())
        .toSet();

    ImmutableSet<Path> transitive = ImmutableSet.<Path>builder()
        .addAll(transitiveClasspathEntries.values())
        .addAll(provided)
        .build();

    ImmutableSet<Path> declared = ImmutableSet.<Path>builder()
        .addAll(declaredClasspathEntries.values())
        .addAll(provided)
        .build();

    // This adds the javac command, along with any supporting commands.
    Supplier<Sha1HashCode> abiKeySupplier = createCommandsForCompilation(
        outputDirectory,
        transitive,
        declared,
        context.getBuildDependencies(),
        steps,
        getBuildTarget());

    // If there are resources, then link them to the appropriate place in the classes directory.
    JavaPackageFinder finder = context.getJavaPackageFinder();
    if (resourcesRoot.isPresent()) {
      finder = new ResourcesRootPackageFinder(resourcesRoot.get(), finder);
    }
    steps.add(new CopyResourcesStep(getBuildTarget(), resources, outputDirectory, finder));

    if (outputJar.isPresent()) {
      steps.add(new MakeCleanDirectoryStep(getOutputJarDirPath(getBuildTarget())));
      steps.add(
          new JarDirectoryStep(
              outputJar.get(),
              Collections.singleton(outputDirectory),
          /* mainClass */ null,
          /* manifestFile */ null));
      buildableContext.recordArtifact(outputJar.get());
    }

    Preconditions.checkNotNull(
        abiKeySupplier,
        "abiKeySupplier must be set so that getAbiKey() will " +
            "return a non-null value if this rule builds successfully.");

    JavaLibraryRules.addAccumulateClassNamesStep(this, buildableContext, steps);

    return steps.build();
  }

  /**
   * @param outputDirectory            Directory to write class files to
   * @param transitiveClasspathEntries Classpaths of all transitive dependencies.
   * @param declaredClasspathEntries   Classpaths of all declared dependencies.
   * @param commands                   List of steps to add to.
   * @return a {@link Supplier} that will return the ABI for this rule after javac is executed.
   */
  protected Supplier<Sha1HashCode> createCommandsForCompilation(
      Path outputDirectory,
      ImmutableSet<Path> transitiveClasspathEntries,
      ImmutableSet<Path> declaredClasspathEntries,
      BuildDependencies buildDependencies,
      ImmutableList.Builder<Step> commands,
      BuildTarget target) {

    final GroovycOptions.Builder groovycOptionsBuilder = GroovycOptions.builder();

    // Only run groovyc if there are .groovy files to compile.
    if (!getGroovySrcs().isEmpty()) {
      Path pathToSrcsList = BuildTargets.getGenPath(getBuildTarget(), "__%s__srcs");
      commands.add(new MkdirStep(pathToSrcsList.getParent()));


      Path workingDirectory = BuildTargets.getGenPath(target, "lib__%s____working_directory");
      commands.add(new MakeCleanDirectoryStep(workingDirectory));
      final GroovycStep groovycStep = new ExternalGroovycStep(
          outputDirectory,
          getJavaSrcs(),
          transitiveClasspathEntries,
          declaredClasspathEntries,
          groovycOptionsBuilder.build(),
          Optional.of(target),
          buildDependencies,
          Optional.of(pathToSrcsList),
          target,
          Optional.of(workingDirectory));
      commands.add(groovycStep);

      // Create a supplier that extracts the ABI key from javac after it executes.
      return
          new Supplier<Sha1HashCode>() {
            @Override
            public Sha1HashCode get() {
              return Sha1HashCode.fromHashCode(getRuleKey().getHashCode());
            }
          };
    } else {
      // When there are no .groovy files to compile, the ABI key should be a constant.
      return Suppliers.ofInstance(
          createTotalAbiKey(
              new Sha1HashCode(AbiWriterProtocol.EMPTY_ABI_KEY)));
    }

  }

  @Override
  public ImmutableCollection<Path> getInputsToCompareToOutput() {
    ImmutableList.Builder<Path> builder = ImmutableList.builder();
    builder.addAll(SourcePaths.filterInputsToCompareToOutput(this.srcs));
    builder.addAll(SourcePaths.filterInputsToCompareToOutput(this.resources));
    return builder.build();
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    builder
        .setReflectively("resources", resources)
        .setReflectively("resources_root", resourcesRoot.toString())
            // provided_deps are already included in the rule key, but we need to explicitly call them
            // out as "provided" because changing a dep from provided to transtitive should result in a
            // re-build (otherwise, we'd get a rule key match).
        .setReflectively("provided_deps", providedDeps);
    return builder;
  }

  @Override
  public boolean isVisibleTo(JavaLibrary other) {
    return BuildTargets.isVisibleTo(
        getBuildTarget(),
        visibilityPatterns,
        other.getBuildTarget());
  }

  @Override
  public Sha1HashCode getAbiKey() {
    return Sha1HashCode.fromHashCode(getRuleKey().getHashCode());
  }

  @Override
  @Nullable
  public Path getPathToOutputFile() {
    return outputJar.orNull();
  }

  /**
   * @return directory path relative to the project root where .class files will be generated.
   * The return value does not end with a slash.
   */
  private static Path getClassesDir(BuildTarget target) {
    return BuildTargets.getBinPath(target, "lib__%s__classes");
  }

  @Override
  public ImmutableSortedSet<BuildRule> getExportedDeps() {
    return exportedDeps;
  }

  @Override
  public ImmutableSetMultimap<JavaLibrary, Path> getDeclaredClasspathEntries() {
    return declaredClasspathEntriesSupplier.get();
  }

  @Override
  public ImmutableSetMultimap<JavaLibrary, Path> getOutputClasspathEntries() {
    return outputClasspathEntriesSupplier.get();
  }

  @Override
  public ImmutableSortedSet<SourcePath> getJavaSrcs() {
    return srcs;
  }

  private ImmutableSortedSet<SourcePath> getGroovySrcs() {
    return getJavaSrcs();
  }

  @Override
  public AnnotationProcessingData getAnnotationProcessingData() {
    return AnnotationProcessingData.EMPTY;
  }

  @Override
  public ImmutableSortedMap<String, HashCode> getClassNamesToHashes() {
    throw new UnsupportedOperationException("getClassNamestnot supported in groovy");
  }

  @Override
  public ImmutableSortedSet<BuildRule> getDepsForTransitiveClasspathEntries() {
    return ImmutableSortedSet.copyOf(Sets.union(getDeclaredDeps(), exportedDeps));
  }

  @Override
  public ImmutableSetMultimap<JavaLibrary, Path> getTransitiveClasspathEntries() {
    return transitiveClasspathEntriesSupplier.get();
  }

  private static Path getOutputJarDirPath(BuildTarget target) {
    return BuildTargets.getGenPath(target, "lib__%s__output");
  }

  private static Path getOutputJarPath(BuildTarget target) {
    return Paths.get(
        String.format(
            "%s/%s.jar",
            getOutputJarDirPath(target),
            target.getShortName()));
  }

  /**
   * Creates the total ABI key for this rule. If export_deps is true, the total key is computed by
   * hashing the ABI keys of the dependencies together with the ABI key of this rule. If export_deps
   * is false, the standalone ABI key for this rule is used as the total key.
   *
   * @param abiKey the standalone ABI key for this rule.
   * @return total ABI key containing also the ABI keys of the dependencies.
   */
  protected Sha1HashCode createTotalAbiKey(Sha1HashCode abiKey) {
    if (getExportedDeps().isEmpty()) {
      return abiKey;
    }

    SortedSet<HasBuildTarget> depsForAbiKey = getDepsForAbiKey();

    // Hash the ABI keys of all dependencies together with ABI key for the current rule.
    Hasher hasher = createHasherWithAbiKeyForDeps(depsForAbiKey);
    hasher.putUnencodedChars(abiKey.getHash());
    return new Sha1HashCode(hasher.hash().toString());
  }

  /**
   * Returns a sorted set containing the dependencies which will be hashed in the final ABI key.
   *
   * @return the dependencies to be hashed in the final ABI key.
   */
  private SortedSet<HasBuildTarget> getDepsForAbiKey() {
    SortedSet<HasBuildTarget> rulesWithAbiToConsider = Sets.newTreeSet(BUILD_TARGET_COMPARATOR);
    for (BuildRule dep : Iterables.concat(getDepsForTransitiveClasspathEntries(), providedDeps)) {
      // This looks odd. DummyJavaAbiRule contains a Buildable that isn't a JavaAbiRule.
      if (dep instanceof HasJavaAbi) {
        if (dep instanceof JavaLibrary) {
          JavaLibrary javaRule = (JavaLibrary) dep;
          rulesWithAbiToConsider.addAll(javaRule.getOutputClasspathEntries().keys());
        } else {
          rulesWithAbiToConsider.add(dep);
        }
      }
    }

    // We also need to iterate over inputs that are SourcePaths, since they're only listed as
    // compile-time deps and not in the "deps" field. If any of these change, we should recompile
    // the library, since we will (at least) need to repack it.
    rulesWithAbiToConsider.addAll(
        SourcePaths.filterBuildRuleInputs(Iterables.concat(srcs, resources)));

    return rulesWithAbiToConsider;
  }

  /**
   * Creates a Hasher containing the ABI keys of the dependencies.
   *
   * @param rulesWithAbiToConsider a sorted set containing the dependencies whose ABI key will be
   *                               added to the hasher.
   * @return a Hasher containing the ABI keys of the dependencies.
   */
  private Hasher createHasherWithAbiKeyForDeps(SortedSet<HasBuildTarget> rulesWithAbiToConsider) {
    Hasher hasher = Hashing.sha1().newHasher();

    for (HasBuildTarget candidate : rulesWithAbiToConsider) {
      if (candidate == this) {
        continue;
      }

      if (candidate instanceof HasJavaAbi) {
        Sha1HashCode abiKey = ((HasJavaAbi) candidate).getAbiKey();
        hasher.putUnencodedChars(abiKey.getHash());
      } else if (candidate instanceof BuildRule) {
        HashCode hashCode = ((BuildRule) candidate).getRuleKey().getHashCode();
        hasher.putBytes(hashCode.asBytes());
      }
    }

    return hasher;
  }

}
