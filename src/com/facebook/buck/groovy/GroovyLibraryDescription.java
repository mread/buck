package com.facebook.buck.groovy;

import com.facebook.buck.java.JavaCompilerEnvironment;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

public class GroovyLibraryDescription implements Description<GroovyLibraryDescription.Arg> {

  public static final BuildRuleType TYPE = new BuildRuleType("groovy_library");
  @VisibleForTesting
  final JavaCompilerEnvironment javacEnv;

  public GroovyLibraryDescription(JavaCompilerEnvironment javacEnv) {
    this.javacEnv = Preconditions.checkNotNull(javacEnv);
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {

    return new GroovyLibrary(
        params,
        args.srcs.get(),
        GroovyLibraryDescription.validateResources(args, params.getProjectFilesystem()),
        resolver.getAllRules(args.exportedDeps.get()),
        resolver.getAllRules(args.providedDeps.get()),
        args.resourcesRoot);
  }

  public static ImmutableSortedSet<SourcePath> validateResources(
      Arg arg,
      ProjectFilesystem filesystem) {
    for (Path path : SourcePaths.filterInputsToCompareToOutput(arg.resources.get())) {
      if (!filesystem.exists(path)) {
        throw new HumanReadableException("Error: `resources` argument '%s' does not exist.", path);
      } else if (filesystem.isDirectory(path)) {
        throw new HumanReadableException(
            "Error: a directory is not a valid input to the `resources` argument: %s",
            path);
      }
    }
    return arg.resources.get();
  }


  @SuppressFieldNotInitialized
  public static class Arg {
    public Optional<ImmutableSortedSet<SourcePath>> srcs;
    public Optional<ImmutableSortedSet<SourcePath>> resources;
    public Optional<ImmutableSortedSet<BuildTarget>> deps;
    public Optional<ImmutableSortedSet<BuildTarget>> providedDeps;
    public Optional<ImmutableSortedSet<BuildTarget>> exportedDeps;
    public Optional<Path> resourcesRoot;
  }
}
