package com.facebook.buck.groovy;

import static com.facebook.buck.rules.BuildableProperties.Kind.LIBRARY;

import com.facebook.buck.java.AnnotationProcessingParams;
import com.facebook.buck.java.CopyResourcesStep;
import com.facebook.buck.java.HasClasspathEntries;
import com.facebook.buck.java.JarDirectoryStep;
import com.facebook.buck.java.JavaLibrary;
import com.facebook.buck.java.JavaLibraryClasspathProvider;
import com.facebook.buck.java.JavaLibraryRules;
import com.facebook.buck.java.JavaPackageFinder;
import com.facebook.buck.java.ResourcesRootPackageFinder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildDependencies;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.ExportDependencies;
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Predicates;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;
import com.google.common.hash.HashCode;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;

import javax.annotation.Nullable;

public class GroovyLibrary extends AbstractBuildRule
    implements HasClasspathEntries, ExportDependencies, JavaLibrary {

  private static final BuildableProperties OUTPUT_TYPE = new BuildableProperties(LIBRARY);

  private final Optional<Path> outputJar;
  private final ImmutableSortedSet<SourcePath> srcs;
  private final ImmutableSortedSet<SourcePath> resources;
  private final ImmutableSortedSet<BuildRule> exportedDeps;
  private final ImmutableSortedSet<BuildRule> providedDeps;
  private final Optional<Path> resourcesRoot;
  private final Supplier<ImmutableSetMultimap<JavaLibrary, Path>> outputClasspathEntriesSupplier;
  private final Supplier<ImmutableSetMultimap<JavaLibrary, Path>> declaredClasspathEntriesSupplier;
  private final Supplier<ImmutableSetMultimap<JavaLibrary, Path>> transitiveClasspathEntriesSupplier;

  protected GroovyLibrary(
      BuildRuleParams params,
      SourcePathResolver resolver,
      Set<? extends SourcePath> srcs,
      Set<? extends SourcePath> resources,
      ImmutableSortedSet<BuildRule> exportedDeps,
      ImmutableSortedSet<BuildRule> providedDeps,
      Optional<Path> resourcesRoot) {

    super(params, resolver);
    this.srcs = ImmutableSortedSet.copyOf(srcs);
    this.resources = ImmutableSortedSet.copyOf(resources);
    this.exportedDeps = exportedDeps;
    this.providedDeps = providedDeps;
    this.resourcesRoot = resourcesRoot;

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
   * t   * {@link com.facebook.buck.util.BuckConstant#SCRATCH_DIR}.
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
    createCommandsForCompilation(
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
    steps.add(
        new CopyResourcesStep(
            getResolver(),
            getBuildTarget(),
            resources,
            outputDirectory,
            finder));

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
  protected void createCommandsForCompilation(
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
          getGroovySrcs(),
          transitiveClasspathEntries,
          declaredClasspathEntries,
          groovycOptionsBuilder.build(),
          Optional.of(target),
          buildDependencies,
          Optional.of(pathToSrcsList),
          target,
          Optional.of(workingDirectory));
      commands.add(groovycStep);
    }
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
    return BuildTargets.getScratchPath(target, "lib__%s__classes");
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
  public ImmutableSortedSet<Path> getJavaSrcs() {
    return ImmutableSortedSet.copyOf(getResolver().getAllPaths(srcs));
  }

  private ImmutableSortedSet<Path> getGroovySrcs() {
    return getJavaSrcs();
  }

  @Override
  public AnnotationProcessingParams getAnnotationProcessingParams() {
    return AnnotationProcessingParams.EMPTY;
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

  @Override
  public BuildableProperties getProperties() {
    return OUTPUT_TYPE;
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

}
