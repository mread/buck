/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.cli;

import com.facebook.buck.apple.XcodeProjectConfigDescription;
import com.facebook.buck.apple.xcode.ProjectGenerator;
import com.facebook.buck.apple.xcode.SeparatedProjectsGenerator;
import com.facebook.buck.apple.xcode.WorkspaceAndProjectGenerator;
import com.facebook.buck.command.Project;
import com.facebook.buck.java.JavaLibraryDescription;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.parser.PartialGraph;
import com.facebook.buck.parser.RawRulePredicate;
import com.facebook.buck.parser.RawRulePredicates;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.ProjectConfigDescription;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.io.Files;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

public class ProjectCommand extends AbstractCommandRunner<ProjectCommandOptions> {


  /**
   * Include java library targets (and android library targets) that use annotation
   * processing.  The sources generated by these annotation processors is needed by
   * IntelliJ.
   */
  private static final RawRulePredicate annotationPredicate = new RawRulePredicate() {

    @Override
    public boolean isMatch(
        Map<String, Object> rawParseData,
        BuildRuleType buildRuleType,
        BuildTarget buildTarget) {
      Object rawValue = rawParseData.get(JavaLibraryDescription.ANNOTATION_PROCESSORS);
      return ((rawValue instanceof Iterable) && !Iterables.isEmpty((Iterable<?>) rawValue));
    }
  };

  public ProjectCommand(CommandRunnerParams params) {
    super(params);
  }

  @Override
  ProjectCommandOptions createOptions(BuckConfig buckConfig) {
    return new ProjectCommandOptions(buckConfig);
  }

  @Override
  int runCommandWithOptionsInternal(ProjectCommandOptions options)
      throws IOException, InterruptedException {
    switch (options.getIde()) {
      case "intellij":
        return runIntellijProjectGenerator(options);
      case "xcode":
        return runXcodeProjectGenerator(options);
      default:
        throw new HumanReadableException(String.format(
            "Unknown IDE `%s` in .buckconfig", options.getIde()));
    }
  }

  /**
   * Run intellij specific project generation actions.
   */
  int runIntellijProjectGenerator(ProjectCommandOptions options)
      throws IOException, InterruptedException {
    // Create a PartialGraph that only contains targets that can be represented as IDE
    // configuration files.
    PartialGraph partialGraph;

    try {
      partialGraph = createPartialGraph(
          RawRulePredicates.matchBuildRuleType(ProjectConfigDescription.TYPE),
          options);
    } catch (BuildTargetException | BuildFileParseException e) {
      throw new HumanReadableException(e);
    }

    ExecutionContext executionContext = createExecutionContext(options,
        partialGraph.getActionGraph());

    Project project = new Project(partialGraph,
        options.getBasePathToAliasMap(),
        options.getJavaPackageFinder(),
        executionContext,
        getProjectFilesystem(),
        options.getPathToDefaultAndroidManifest(),
        options.getPathToPostProcessScript(),
        options.getBuckConfig().getPythonInterpreter());

    File tempDir = Files.createTempDir();
    File tempFile = new File(tempDir, "project.json");
    int exitCode;
    try {
      exitCode = createIntellijProject(project,
          tempFile,
          executionContext.getProcessExecutor(),
          !options.getArgumentsFormattedAsBuildTargets().isEmpty(),
          console.getStdOut(),
          console.getStdErr());
      if (exitCode != 0) {
        return exitCode;
      }

      List<String> additionalInitialTargets = ImmutableList.of();
      if (options.shouldProcessAnnotations()) {
        try {
          additionalInitialTargets = getAnnotationProcessingTargets(options);
        } catch (BuildTargetException | BuildFileParseException e) {
          throw new HumanReadableException(e);
        }
      }

      // Build initial targets.
      if (options.hasInitialTargets() || !additionalInitialTargets.isEmpty()) {
        BuildCommand buildCommand = new BuildCommand(getCommandRunnerParams());
        BuildCommandOptions buildOptions =
            options.createBuildCommandOptionsWithInitialTargets(additionalInitialTargets);


        exitCode = runBuildCommand(buildCommand, buildOptions);
        if (exitCode != 0) {
          return exitCode;
        }
      }
    } finally {
      // Either leave project.json around for debugging or delete it on exit.
      if (console.getVerbosity().shouldPrintOutput()) {
        getStdErr().printf("project.json was written to %s", tempFile.getAbsolutePath());
      } else {
        tempFile.delete();
        tempDir.delete();
      }
    }

    return 0;
  }

  List<String> getAnnotationProcessingTargets(ProjectCommandOptions options)
      throws BuildTargetException, BuildFileParseException, IOException, InterruptedException {
    return ImmutableList.copyOf(
        Iterables.transform(
            createPartialGraph(annotationPredicate, options).getTargets(),
            new Function<BuildTarget, String>() {
              @Override
              public String apply(BuildTarget target) {
                return target.getFullyQualifiedName();
              }
            }));
  }

  /**
   * Calls {@link Project#createIntellijProject}
   *
   * This is factored into a separate method for testing purposes.
   */
  @VisibleForTesting
  int createIntellijProject(Project project,
      File jsonTemplate,
      ProcessExecutor processExecutor,
      boolean generateMinimalProject,
      PrintStream stdOut,
      PrintStream stdErr)
      throws IOException, InterruptedException {
    return project.createIntellijProject(
        jsonTemplate,
        processExecutor,
        generateMinimalProject,
        stdOut,
        stdErr);
  }

  /**
   * Run xcode specific project generation actions.
   */
  int runXcodeProjectGenerator(ProjectCommandOptions options)
      throws IOException, InterruptedException {


    PartialGraph partialGraph;
    try {
      partialGraph = PartialGraph.createFullGraph(getProjectFilesystem(),
          options.getDefaultIncludes(),
          getParser(),
          getBuckEventBus());
    } catch (BuildTargetException | BuildFileParseException e) {
      throw new HumanReadableException(e);
    }

    ImmutableSet<BuildTarget> passedInTargetsSet;

    try {
      List<String> argumentsAsBuildTargets = options.getArgumentsFormattedAsBuildTargets();
      passedInTargetsSet = ImmutableSet.copyOf(getBuildTargets(argumentsAsBuildTargets));
    } catch (NoSuchBuildTargetException e) {
      throw new HumanReadableException(e);
    }

    ExecutionContext executionContext = createExecutionContext(options,
        partialGraph.getActionGraph());

    ImmutableSet.Builder<ProjectGenerator.Option> optionsBuilder = ImmutableSet.builder();
    if (options.getReadOnly()) {
      optionsBuilder.add(ProjectGenerator.Option.GENERATE_READ_ONLY_FILES);
    }

    if (options.getCombinedProject() != null) {
      // Generate a single project containing a target and all its dependencies and tests.
      ProjectGenerator projectGenerator = new ProjectGenerator(
          partialGraph,
          passedInTargetsSet,
          getProjectFilesystem(),
          executionContext,
          getProjectFilesystem().getPathForRelativePath(Paths.get("_gen")),
          "GeneratedProject",
          optionsBuilder.addAll(ProjectGenerator.COMBINED_PROJECT_OPTIONS).build());
      projectGenerator.createXcodeProjects();
    } else if (options.getWorkspaceAndProjects()) {
      WorkspaceAndProjectGenerator generator = new WorkspaceAndProjectGenerator(
          getProjectFilesystem(),
          partialGraph,
          executionContext,
          Iterables.getOnlyElement(passedInTargetsSet),
          optionsBuilder.build());
      generator.generateWorkspaceAndDependentProjects();
    } else {
      // Generate projects based on xcode_project_config rules, and place them in the same directory
      // as the Buck file.

      ImmutableSet<BuildTarget> targets;
      if (passedInTargetsSet.isEmpty()) {
        ImmutableSet.Builder<BuildTarget> targetsBuilder = ImmutableSet.builder();
        for (BuildRule node : partialGraph.getActionGraph().getNodes()) {
          if (node.getType() == XcodeProjectConfigDescription.TYPE) {
            targetsBuilder.add(node.getBuildTarget());
          }
        }
        targets = targetsBuilder.build();
      } else {
        targets = passedInTargetsSet;
      }

      SeparatedProjectsGenerator projectGenerator = new SeparatedProjectsGenerator(
          getProjectFilesystem(),
          partialGraph,
          executionContext,
          targets,
          optionsBuilder.build());
      ImmutableSet<Path> generatedProjectPaths = projectGenerator.generateProjects();
      for (Path path : generatedProjectPaths) {
        console.getStdOut().println(path.toString());
      }
    }

    return 0;
  }

  /**
   * Calls {@link BuildCommand#runCommandWithOptions}
   *
   * This is factored into a separate method for testing purposes.
   */
  @VisibleForTesting
  int runBuildCommand(BuildCommand buildCommand, BuildCommandOptions options)
      throws IOException, InterruptedException {
    return buildCommand.runCommandWithOptions(options);
  }

  @VisibleForTesting
  PartialGraph createPartialGraph(RawRulePredicate rulePredicate, ProjectCommandOptions options)
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    List<String> argumentsAsBuildTargets = options.getArgumentsFormattedAsBuildTargets();

    if (argumentsAsBuildTargets.isEmpty()) {
      return PartialGraph.createPartialGraph(
          rulePredicate,
          getProjectFilesystem(),
          options.getDefaultIncludes(),
          getParser(),
          getBuckEventBus());
    } else {
      // If build targets were specified, generate a partial intellij project that contains the
      // files needed to build the build targets specified.
      ImmutableList<BuildTarget> targets = getBuildTargets(argumentsAsBuildTargets);

      if (options.isWithTests()) {
        return PartialGraph.createPartialGraphFromRootsWithTests(
            targets,
            rulePredicate,
            getProjectFilesystem(),
            options.getDefaultIncludes(),
            getParser(),
            getBuckEventBus());
      } else {
        return PartialGraph.createPartialGraphFromRoots(targets,
            rulePredicate,
            options.getDefaultIncludes(),
            getParser(),
            getBuckEventBus());
      }
    }
  }

  @Override
  String getUsageIntro() {
    return "generates project configuration files for an IDE";
  }
}
