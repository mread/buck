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

package com.facebook.buck.parser;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.TestRule;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.io.IOException;
import java.util.List;

/**
 * A subgraph of the full action graph, which is also a valid action graph.
 */
public class PartialGraph {

  private final ActionGraph graph;
  private final List<BuildTarget> targets;

  @VisibleForTesting
  PartialGraph(ActionGraph graph, List<BuildTarget> targets) {
    this.graph = graph;
    this.targets = ImmutableList.copyOf(targets);
  }

  public ActionGraph getActionGraph() {
    return graph;
  }

  public List<BuildTarget> getTargets() {
    return targets;
  }

  public static PartialGraph createFullGraph(
      ProjectFilesystem projectFilesystem,
      Iterable<String> includes,
      Parser parser,
      BuckEventBus eventBus)
      throws BuildTargetException, BuildFileParseException, IOException, InterruptedException {
    return createPartialGraph(RawRulePredicates.alwaysTrue(),
        projectFilesystem,
        includes,
        parser,
        eventBus);
  }

  public static PartialGraph createPartialGraph(
      RawRulePredicate predicate,
      ProjectFilesystem filesystem,
      Iterable<String> includes,
      Parser parser,
      BuckEventBus eventBus)
      throws BuildTargetException, BuildFileParseException, IOException, InterruptedException {
    Preconditions.checkNotNull(predicate);

    List<BuildTarget> targets = parser.filterAllTargetsInProject(filesystem,
        includes,
        predicate);

    return parseAndCreateGraphFromTargets(targets, includes, parser, eventBus);
  }

  /**
   * Creates a partial graph of all {@link BuildRule}s that are either
   * transitive dependencies or tests for (rules that pass {@code predicate} and are contained in
   * BUCK files that contain transitive dependencies of the {@link BuildTarget}s defined in
   * {@code roots}).
   */
  public static PartialGraph createPartialGraphFromRootsWithTests(
      Iterable<BuildTarget> roots,
      RawRulePredicate predicate,
      ProjectFilesystem filesystem,
      Iterable<String> includes,
      Parser parser,
      BuckEventBus eventBus)
      throws BuildTargetException, BuildFileParseException, IOException, InterruptedException {
    Preconditions.checkNotNull(predicate);

    Iterable<BuildTarget> buildTargets = parser.filterTargetsInProjectFromRoots(
        roots, includes, eventBus, RawRulePredicates.alwaysTrue());
    ActionGraph buildGraph =
        parseAndCreateGraphFromTargets(buildTargets, includes, parser, eventBus)
            .getActionGraph();

    // We have to enumerate all test targets, and see which ones refer to a rule in our build graph
    // with it's src_under_test field.
    ImmutableList.Builder<BuildTarget> buildAndTestTargetsBuilder =
        ImmutableList.<BuildTarget>builder()
            .addAll(roots);

    PartialGraph testGraph = PartialGraph.createPartialGraph(
        RawRulePredicates.isTestRule(),
        filesystem,
        includes,
        parser,
        eventBus);

    ActionGraph testActionGraph = testGraph.getActionGraph();

    // Iterate through all possible test targets, looking for ones who's src_under_test intersects
    // with our build graph.
    for (BuildTarget buildTarget : testGraph.getTargets()) {
      TestRule testRule =
          (TestRule) testActionGraph.findBuildRuleByTarget(buildTarget);
      for (BuildRule buildRuleUnderTest : testRule.getSourceUnderTest()) {
        if (buildGraph.findBuildRuleByTarget(buildRuleUnderTest.getBuildTarget()) != null) {
          buildAndTestTargetsBuilder.add(testRule.getBuildTarget());
          break;
        }
      }
    }

    Iterable<BuildTarget> allTargets = parser.filterTargetsInProjectFromRoots(
        buildAndTestTargetsBuilder.build(), includes, eventBus, predicate);
    return parseAndCreateGraphFromTargets(allTargets, includes, parser, eventBus);
  }

  /**
   * Creates a partial graph of all {@link BuildRule}s that are transitive
   * dependencies of (rules that pass {@code predicate} and are contained in BUCK files that contain
   * transitive dependencies of the {@link BuildTarget}s defined in {@code roots}).

   */
  public static PartialGraph createPartialGraphFromRoots(
      Iterable<BuildTarget> roots,
      RawRulePredicate predicate,

      Iterable<String> includes,
      Parser parser,
      BuckEventBus eventBus)
      throws BuildTargetException, BuildFileParseException, IOException, InterruptedException {
    Preconditions.checkNotNull(predicate);

    Iterable<BuildTarget> targets = parser.filterTargetsInProjectFromRoots(
        roots, includes, eventBus, predicate);

    return parseAndCreateGraphFromTargets(targets, includes, parser, eventBus);
  }


  /**
   * Like {@link #createPartialGraphFromRootsWithTests}, but trades accuracy for speed.
   *
   * <p>The graph returned from this method will include all transitive deps of the roots, but
   * might also include rules that are not actually dependencies.  This looseness allows us to
   * run faster by avoiding a post-hoc filtering step.
   */
  public static PartialGraph createPartialGraphIncludingRoots(
      Iterable<BuildTarget> roots,
      Iterable<String> includes,
      Parser parser,
      BuckEventBus eventBus)
      throws BuildTargetException, BuildFileParseException, IOException, InterruptedException {
    return parseAndCreateGraphFromTargets(roots, includes, parser, eventBus);
  }

  private static PartialGraph parseAndCreateGraphFromTargets(
      Iterable<BuildTarget> targets,
      Iterable<String> includes,
      Parser parser,
      BuckEventBus eventBus)
      throws BuildTargetException, BuildFileParseException, IOException, InterruptedException {

    Preconditions.checkNotNull(parser);

    // Now that the Parser is loaded up with the set of all build rules, use it to create a
    // DependencyGraph of only the targets we want to build.
    ActionGraph graph = parser.parseBuildFilesForTargets(targets, includes, eventBus);

    return new PartialGraph(graph, ImmutableList.copyOf(targets));
  }
}
