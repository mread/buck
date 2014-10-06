package com.facebook.buck.groovy;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.java.JavaLibrary;
import com.facebook.buck.java.JavaSourceJar;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

public class GroovyLibraryDescription implements Description<GroovyLibraryDescription.Arg> {

  public static final BuildRuleType TYPE = BuildRuleType.of("groovy_library");

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

    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    BuildTarget target = params.getBuildTarget();

    // We know that the flavour we're being asked to create is valid, since the check is done when
    // creating the action graph from the target graph.

    if (target.getFlavors().contains(JavaLibrary.SRC_JAR)) {
      return new JavaSourceJar(params, pathResolver, args.srcs.get());
    }

    return new GroovyLibrary(
        params,
        pathResolver,
        args.srcs.get(),
        validateResources(pathResolver, args, params.getProjectFilesystem()),
        resolver.getAllRules(args.exportedDeps.get()),
        resolver.getAllRules(args.providedDeps.get()),
        args.resourcesRoot);
  }

  public static ImmutableSortedSet<SourcePath> validateResources(
      SourcePathResolver resolver,
      Arg arg,
      ProjectFilesystem filesystem) {
    for (Path path : resolver.filterInputsToCompareToOutput(arg.resources.get())) {
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
    public Optional<ImmutableList<String>> extraArguments;
    public Optional<ImmutableSortedSet<BuildTarget>> deps;
    public Optional<ImmutableSortedSet<BuildTarget>> providedDeps;
    public Optional<ImmutableSortedSet<BuildTarget>> exportedDeps;
    public Optional<Path> resourcesRoot;
  }
}
