/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.apple;

import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.step.Step;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;

public class XcodeWorkspaceConfig extends AbstractBuildRule {

  private final BuildRule srcTarget;
  private final ImmutableMap<SchemeActionType, String> actionConfigNames;

  protected XcodeWorkspaceConfig(BuildRuleParams params, XcodeWorkspaceConfigDescription.Arg arg) {
    super(params);
    this.srcTarget = Preconditions.checkNotNull(arg.srcTarget);

    // Start out with the default action config names..
    Map<SchemeActionType, String> newActionConfigNames = new HashMap<>(
        SchemeActionType.DEFAULT_CONFIG_NAMES);
    if (Preconditions.checkNotNull(arg.actionConfigNames).isPresent()) {
      // And override them with any provided in the "action_config_names" map.
      newActionConfigNames.putAll(arg.actionConfigNames.get());
    }

    this.actionConfigNames = ImmutableMap.copyOf(newActionConfigNames);
  }

  public BuildRule getSrcTarget() {
    return srcTarget;
  }

  public ImmutableMap<SchemeActionType, String> getActionConfigNames() {
    return actionConfigNames;
  }

  @Override
  public ImmutableCollection<Path> getInputsToCompareToOutput() {
    return ImmutableSet.of();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    return ImmutableList.of();
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return builder;
  }

  @Nullable
  @Override
  public Path getPathToOutputFile() {
    return null;
  }
}
