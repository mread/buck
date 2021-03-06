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

import com.facebook.buck.apple.AppleBuildRules;
import com.facebook.buck.apple.XcodeProjectConfig;
import com.facebook.buck.apple.XcodeProjectConfigDescription;
import com.facebook.buck.apple.XcodeWorkspaceConfig;
import com.facebook.buck.apple.XcodeWorkspaceConfigDescription;
import com.facebook.buck.apple.xcode.ProjectGenerator;
import com.facebook.buck.apple.xcode.SeparatedProjectsGenerator;
import com.facebook.buck.apple.xcode.WorkspaceAndProjectGenerator;
import com.facebook.buck.command.Project;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.java.JavaLibraryDescription;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.parser.AssociatedRulePredicate;
import com.facebook.buck.parser.AssociatedRulePredicates;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.parser.PartialGraph;
import com.facebook.buck.parser.RuleJsonPredicate;
import com.facebook.buck.parser.RuleJsonPredicates;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.ProjectConfig;
import com.facebook.buck.rules.ProjectConfigDescription;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.io.Files;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ProjectCommand extends AbstractCommandRunner<ProjectCommandOptions> {

  private static final Logger LOG = Logger.get(ProjectCommand.class);

  /**
   * Include java library targets (and android library targets) that use annotation
   * processing.  The sources generated by these annotation processors is needed by
   * IntelliJ.
   */
  private static final RuleJsonPredicate ANNOTATION_PREDICATE = new RuleJsonPredicate() {
    @Override
    public boolean isMatch(
        Map<String, Object> rawParseData,
        BuildRuleType buildRuleType,
        BuildTarget buildTarget) {
      Object rawValue = rawParseData.get(JavaLibraryDescription.ANNOTATION_PROCESSORS);
      return ((rawValue instanceof Iterable) && !Iterables.isEmpty((Iterable<?>) rawValue));
    }
  };

  private static class ActionGraphs {
    private final ActionGraph mainGraph;
    private final Optional<ActionGraph> testGraph;
    private final ActionGraph projectGraph;

    public ActionGraphs(
        ActionGraph mainGraph,
        Optional<ActionGraph> testGraph,
        ActionGraph projectGraph) {
      this.mainGraph = Preconditions.checkNotNull(mainGraph);
      this.testGraph = Preconditions.checkNotNull(testGraph);
      this.projectGraph = Preconditions.checkNotNull(projectGraph);
    }

    public ActionGraph getMainGraph() {
      return mainGraph;
    }

    public Optional<ActionGraph> getTestGraph() {
      return testGraph;
    }

    public ActionGraph getProjectGraph() {
      return projectGraph;
    }
  }

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
      case INTELLIJ:
        return runIntellijProjectGenerator(options);
      case XCODE:
        return runXcodeProjectGenerator(options);
      default:
        // unreachable
        throw new IllegalStateException("'ide' should always be of type 'INTELLIJ' or 'XCODE'");
    }
  }

  /**
   * Run intellij specific project generation actions.
   */
  int runIntellijProjectGenerator(ProjectCommandOptions options)
      throws IOException, InterruptedException {
    // Create an ActionGraph that only contains targets that can be represented as IDE
    // configuration files.
    ActionGraph actionGraph;

    try {
      actionGraph = createPartialGraphs(options).getProjectGraph();
    } catch (BuildTargetException | BuildFileParseException e) {
      throw new HumanReadableException(e);
    }

    ExecutionContext executionContext = createExecutionContext(
        options,
        actionGraph);

    Project project = new Project(
        ImmutableSet.copyOf(
            FluentIterable
                .from(actionGraph.getNodes())
                .filter(
                    new Predicate<BuildRule>() {
                      @Override
                      public boolean apply(BuildRule input) {
                        return input instanceof ProjectConfig;
                      }
                    })
                .transform(
                    new Function<BuildRule, ProjectConfig>() {
                      @Override
                      public ProjectConfig apply(BuildRule input) {
                        return (ProjectConfig) input;
                      }
                    }
                )),
        actionGraph,
        options.getBasePathToAliasMap(),
        options.getJavaPackageFinder(),
        executionContext,
        getProjectFilesystem(),
        options.getPathToDefaultAndroidManifest(),
        options.getPathToPostProcessScript(),
        options.getBuckConfig().getPythonInterpreter(),
        getObjectMapper());

    File tempDir = Files.createTempDir();
    File tempFile = new File(tempDir, "project.json");
    int exitCode;
    try {
      exitCode = project.createIntellijProject(
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


        exitCode = buildCommand.runCommandWithOptions(buildOptions);
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

    if (options.getArguments().isEmpty()) {
      String greenStar = console.getAnsi().asHighlightedSuccessText(" * ");
      getStdErr().printf(
          console.getAnsi().asHighlightedSuccessText("=== Did you know ===") + "\n" +
              greenStar + "You can run `buck project <target>` to generate a minimal project " +
              "just for that target.\n" +
              greenStar + "This will make your IDE faster when working on large projects.\n" +
              greenStar + "See buck project --help for more info.\n" +
              console.getAnsi().asHighlightedSuccessText(
                  "--=* Knowing is half the battle!") + "\n");
    }

    return 0;
  }

  ImmutableList<String> getAnnotationProcessingTargets(ProjectCommandOptions options)
      throws BuildTargetException, BuildFileParseException, IOException, InterruptedException {
    Optional<ImmutableSet<BuildTarget>> buildTargets = getRootsFromOptions(options);
    PartialGraph partialGraph = Iterables.getOnlyElement(
        createPartialGraphs(
          buildTargets,
          Optional.of(ANNOTATION_PREDICATE),
          ImmutableList.<RuleJsonPredicate>of(),
          ImmutableList.<AssociatedRulePredicate>of(),
          getProjectFilesystem(),
          options.getDefaultIncludes(),
          getParser(),
          getBuckEventBus(),
          console,
          environment,
          options.getEnableProfiling()));

    return ImmutableList.copyOf(
        Iterables.transform(
            partialGraph.getTargets(),
            new Function<BuildTarget, String>() {
              @Override
              public String apply(BuildTarget target) {
                return target.getFullyQualifiedName();
              }
            }));
  }

  /**
   * Run xcode specific project generation actions.
   */
  int runXcodeProjectGenerator(ProjectCommandOptions options)
      throws IOException, InterruptedException {
    ActionGraphs actionGraphs;
    try {
      actionGraphs = createPartialGraphs(options);
    } catch (BuildTargetException | BuildFileParseException e) {
      throw new HumanReadableException(e);
    }

    ImmutableSet<BuildTarget> passedInTargetsSet;

    try {
      ImmutableSet<String> argumentsAsBuildTargets = options.getArgumentsFormattedAsBuildTargets();
      passedInTargetsSet = ImmutableSet.copyOf(getBuildTargets(argumentsAsBuildTargets));
    } catch (NoSuchBuildTargetException e) {
      throw new HumanReadableException(e);
    }

    ExecutionContext executionContext = createExecutionContext(
        options,
        actionGraphs.getProjectGraph());

    ImmutableSet.Builder<ProjectGenerator.Option> optionsBuilder = ImmutableSet.builder();
    if (options.getReadOnly()) {
      optionsBuilder.add(ProjectGenerator.Option.GENERATE_READ_ONLY_FILES);
    }
    if (options.isWithTests()) {
      optionsBuilder.add(ProjectGenerator.Option.INCLUDE_TESTS);
    }

    if (options.getCombinedProject() != null) {
      // Generate a single project containing a target and all its dependencies and tests.
      ProjectGenerator projectGenerator = new ProjectGenerator(
          actionGraphs.getProjectGraph().getNodes(),
          passedInTargetsSet,
          getProjectFilesystem(),
          executionContext,
          getProjectFilesystem().getPathForRelativePath(Paths.get("_gen")),
          "GeneratedProject",
          optionsBuilder.addAll(ProjectGenerator.COMBINED_PROJECT_OPTIONS).build());
      projectGenerator.createXcodeProjects();
    } else if (options.getWorkspaceAndProjects()) {
      ImmutableSet<BuildTarget> targets;
      if (passedInTargetsSet.isEmpty()) {
        targets = getAllTargetsOfType(
            actionGraphs.getMainGraph().getNodes(),
            XcodeWorkspaceConfigDescription.TYPE);
      } else {
        targets = passedInTargetsSet;
      }
      LOG.debug("Generating workspace for config targets %s", targets);
      Map<BuildRule, ProjectGenerator> projectGenerators = new HashMap<>();
      for (BuildTarget workspaceConfig : targets) {
        BuildRule workspaceRule =
          actionGraphs.getMainGraph().findBuildRuleByTarget(workspaceConfig);
        if (!(workspaceRule instanceof XcodeWorkspaceConfig)) {
          throw new HumanReadableException(
              "%s must be a xcode_workspace_config",
              workspaceRule.getFullyQualifiedName());
        }
        Iterable<BuildRule> testBuildRules;
        if (actionGraphs.getTestGraph().isPresent()) {
          testBuildRules = actionGraphs.getTestGraph().get().getNodes();
        } else {
          testBuildRules = Collections.emptySet();
        }
        XcodeWorkspaceConfig workspaceConfigRule = (XcodeWorkspaceConfig) workspaceRule;
        WorkspaceAndProjectGenerator generator = new WorkspaceAndProjectGenerator(
            getProjectFilesystem(),
            actionGraphs.getProjectGraph(),
            executionContext,
            workspaceConfigRule,
            optionsBuilder.build(),
            AppleBuildRules.getSourceRuleToTestRulesMap(testBuildRules),
            workspaceConfigRule.getExtraTests()
        );
        generator.generateWorkspaceAndDependentProjects(projectGenerators);
      }
    } else {
      // Generate projects based on xcode_project_config rules, and place them in the same directory
      // as the Buck file.

      ImmutableSet<BuildTarget> targets;
      if (passedInTargetsSet.isEmpty()) {
        targets = getAllTargetsOfType(
            actionGraphs.getProjectGraph().getNodes(),
            XcodeProjectConfigDescription.TYPE);
      } else {
        targets = passedInTargetsSet;
      }

      SeparatedProjectsGenerator projectGenerator = new SeparatedProjectsGenerator(
          getProjectFilesystem(),
          actionGraphs.getProjectGraph(),
          executionContext,
          targets,
          optionsBuilder.build());
      projectGenerator.generateProjects();
    }

    return 0;
  }

  private static ImmutableSet<BuildTarget> getAllTargetsOfType(
      Iterable<BuildRule> nodes,
      BuildRuleType type) {
    ImmutableSet.Builder<BuildTarget> targetsBuilder = ImmutableSet.builder();
    for (BuildRule node : nodes) {
      if (node.getType() == type) {
        targetsBuilder.add(node.getBuildTarget());
      }
    }
    return targetsBuilder.build();
  }

  private Optional<ImmutableSet<BuildTarget>> getRootsFromOptions(ProjectCommandOptions options)
      throws BuildTargetException, IOException {
    Optional<ImmutableSet<BuildTarget>> buildTargets = Optional.absent();
    {
      ImmutableSet<String> argumentsAsBuildTargets = options.getArgumentsFormattedAsBuildTargets();
      if (!argumentsAsBuildTargets.isEmpty()) {
        buildTargets = Optional.of(getBuildTargets(argumentsAsBuildTargets));
      }
    }
    return buildTargets;
  }

  private ActionGraphs createPartialGraphs(final ProjectCommandOptions options)
      throws BuildFileParseException, BuildTargetException, InterruptedException, IOException {
    RuleJsonPredicate projectRootsPredicate;
    RuleJsonPredicate projectPredicate;
    AssociatedRulePredicate associatedProjectPredicate;

    switch (options.getIde()) {
      case INTELLIJ:
        projectRootsPredicate = projectPredicate = RuleJsonPredicates.matchBuildRuleType(
            ProjectConfigDescription.TYPE);
        associatedProjectPredicate = new AssociatedRulePredicate() {
          @Override
          public boolean isMatch(BuildRule buildRule, ActionGraph actionGraph) {
            ProjectConfig projectConfig;
            if (buildRule instanceof ProjectConfig) {
              projectConfig = (ProjectConfig) buildRule;
            } else {
              return false;
            }

            BuildRule projectRule = projectConfig.getProjectRule();
            return (projectRule != null &&
                actionGraph.findBuildRuleByTarget(projectRule.getBuildTarget()) != null);
          }
        };
        break;
      case XCODE:
        final ImmutableSet<String> defaultExcludePaths = options.getDefaultExcludePaths();
        final ImmutableSet<BuildTarget> passedInTargetsSet =
            ImmutableSet.copyOf(getBuildTargets(options.getArgumentsFormattedAsBuildTargets()));

        projectRootsPredicate = new RuleJsonPredicate() {
          @Override
          public boolean isMatch(
              Map<String, Object> rawParseData,
              BuildRuleType buildRuleType,
              BuildTarget buildTarget) {
            BuildRuleType filterType = options.getWorkspaceAndProjects() ?
                XcodeWorkspaceConfigDescription.TYPE :
                XcodeProjectConfigDescription.TYPE;
            if (filterType != buildRuleType) {
              return false;
            }

            String targetName = buildTarget.getFullyQualifiedName();
            for (String prefix : defaultExcludePaths) {
              if (targetName.startsWith("//" + prefix) &&
                  !passedInTargetsSet.contains(buildTarget)) {
                LOG.debug(
                    "Ignoring build target %s (exclude_paths contains %s)",
                    buildTarget,
                    prefix);
                return false;
              }
            }
            return true;
          }
        };
        projectPredicate = RuleJsonPredicates.matchBuildRuleType(
            XcodeProjectConfigDescription.TYPE);
        associatedProjectPredicate = new AssociatedRulePredicate() {
          @Override
          public boolean isMatch(
              BuildRule buildRule, ActionGraph actionGraph) {
            XcodeProjectConfig xcodeProjectConfig;
            if (buildRule instanceof XcodeProjectConfig) {
              xcodeProjectConfig = (XcodeProjectConfig) buildRule;
            } else {
              return false;
            }

            for (BuildRule includedBuildRule : xcodeProjectConfig.getRules()) {
              if (actionGraph.findBuildRuleByTarget(includedBuildRule.getBuildTarget()) != null) {
                return true;
              }
            }

            return false;
          }
        };
        break;
      default:
        // unreachable
        throw new IllegalStateException("'ide' should always be of type 'INTELLIJ' or 'XCODE'");
    }

    Optional<ImmutableSet<BuildTarget>> buildTargets = getRootsFromOptions(options);

    if (options.isWithTests()) {
      ImmutableList<PartialGraph> partialGraphs = createPartialGraphs(
          buildTargets,
          Optional.of(projectRootsPredicate),
          ImmutableList.of(
              RuleJsonPredicates.isTestRule(),
              projectPredicate),
          ImmutableList.of(
              AssociatedRulePredicates.associatedTestsRules(),
              associatedProjectPredicate),
          getProjectFilesystem(),
          options.getDefaultIncludes(),
          getParser(),
          getBuckEventBus(),
          console,
          environment,
          options.getEnableProfiling());
      return new ActionGraphs(
          partialGraphs.get(0).getActionGraph(),
          Optional.of(partialGraphs.get(1).getActionGraph()),
          partialGraphs.get(2).getActionGraph());
    } else {
      ImmutableList<PartialGraph> partialGraphs = createPartialGraphs(
          buildTargets,
          Optional.of(projectRootsPredicate),
          ImmutableList.of(
              projectPredicate),
          ImmutableList.of(
              associatedProjectPredicate),
          getProjectFilesystem(),
          options.getDefaultIncludes(),
          getParser(),
          getBuckEventBus(),
          console,
          environment,
          options.getEnableProfiling());
      return new ActionGraphs(
          partialGraphs.get(0).getActionGraph(),
          Optional.<ActionGraph>absent(),
          partialGraphs.get(1).getActionGraph());
    }
  }

  /**
   * Creates a graph containing the {@link BuildRule}s identified by {@code roots} and their
   * dependencies. Then for each pair of {@link RuleJsonPredicate} in {@code predicates} and
   * {@link AssociatedRulePredicate} in {@code associatedRulePredicates}, rules throughout the
   * project that pass are added to the graph.
   */
  public static ImmutableList<PartialGraph> createPartialGraphs(
      Optional<ImmutableSet<BuildTarget>> rootsOptional,
      Optional<RuleJsonPredicate> rootsPredicate,
      ImmutableList<RuleJsonPredicate> predicates,
      ImmutableList<AssociatedRulePredicate> associatedRulePredicates,
      ProjectFilesystem filesystem,
      Iterable<String> includes,
      Parser parser,
      BuckEventBus eventBus,
      Console console,
      ImmutableMap<String, String> environment,
      boolean enableProfiling)
      throws BuildTargetException, BuildFileParseException, IOException, InterruptedException {
    ImmutableSet<BuildTarget> roots = rootsOptional.or(
        parser.filterAllTargetsInProject(
            filesystem,
            includes,
            rootsPredicate.or(RuleJsonPredicates.alwaysTrue()),
            console,
            environment,
            eventBus,
            enableProfiling));

    ImmutableList.Builder<PartialGraph> graphs = ImmutableList.builder();

    PartialGraph partialGraph = PartialGraph.createPartialGraph(
        roots,
        includes,
        parser,
        eventBus,
        console,
        environment);

    graphs.add(partialGraph);

    for (int i = 0; i < predicates.size(); i++) {
      RuleJsonPredicate predicate = predicates.get(i);
      AssociatedRulePredicate associatedRulePredicate = associatedRulePredicates.get(i);

      PartialGraph associatedPartialGraph = PartialGraph.createPartialGraph(
          predicate,
          filesystem,
          includes,
          parser,
          eventBus,
          console,
          environment,
          enableProfiling);

      ImmutableSet.Builder<BuildTarget> allTargetsBuilder = ImmutableSet.builder();
      allTargetsBuilder.addAll(partialGraph.getTargets());

      for (BuildTarget buildTarget : associatedPartialGraph.getTargets()) {
        BuildRule buildRule = associatedPartialGraph
            .getActionGraph()
            .findBuildRuleByTarget(buildTarget);
        if (associatedRulePredicate.isMatch(buildRule, partialGraph.getActionGraph())) {
          allTargetsBuilder.add(buildRule.getBuildTarget());
        }
      }

      partialGraph = PartialGraph.createPartialGraph(
          allTargetsBuilder.build(),
          includes,
          parser,
          eventBus,
          console,
          environment);

      graphs.add(partialGraph);
    }

    return graphs.build();
  }

  @Override
  String getUsageIntro() {
    return "generates project configuration files for an IDE";
  }
}
