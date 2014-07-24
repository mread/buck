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

package com.facebook.buck.rules;

import com.facebook.buck.graph.DefaultImmutableDirectedAcyclicGraph;
import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.model.BuildTarget;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.nio.file.Path;
import java.util.Map;

public class ActionGraph extends DefaultImmutableDirectedAcyclicGraph<BuildRule> {

  private Map<BuildTarget, BuildRule> index;

  public ActionGraph(MutableDirectedGraph<BuildRule> graph) {
    super(graph);
  }

  public BuildRule findBuildRuleByTarget(BuildTarget buildTarget) {
    if (index == null) {
      ImmutableMap.Builder<BuildTarget, BuildRule> builder = ImmutableMap.builder();
      for (BuildRule rule : getNodes()) {
        builder.put(rule.getBuildTarget(), rule);
      }
      index = builder.build();
    }

    return index.get(buildTarget);
  }

  public ImmutableList<BuildRule> getBuildRulesOfBuildableTypeInBasePath(
      Class<? extends BuildRule> klass, Path basePath) {
    ImmutableList.Builder<BuildRule> result = ImmutableList.builder();
    for (BuildRule rule : getNodes()) {
      if (rule.getBuildTarget().getBasePath().equals(basePath) &&
          klass.isInstance(rule)) {
        result.add(rule);
      }
    }
    return result.build();
  }
}
