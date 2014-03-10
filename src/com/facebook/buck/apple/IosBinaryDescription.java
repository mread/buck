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

package com.facebook.buck.apple;

import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Buildable;
import com.facebook.buck.rules.ConstructorArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.coercer.AppleSource;
import com.facebook.buck.rules.coercer.Either;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

public class IosBinaryDescription implements Description<IosBinaryDescription.Arg> {
  public static final BuildRuleType TYPE = new BuildRuleType("ios_binary");

  @Override
  public Buildable createBuildable(BuildRuleParams params, Arg args) {
    return new IosBinary(args);
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  public class Arg implements ConstructorArg {
    /**
     * @see com.facebook.buck.apple.XcodeRuleConfiguration#fromRawJsonStructure
     */
    public ImmutableMap<
            String,
        ImmutableList<Either<Path, ImmutableMap<String, String>>>> configs;
    public Path infoPlist;
    public ImmutableList<AppleSource> srcs;
    public ImmutableList<AppleSource> headers;
    public ImmutableSortedSet<String> frameworks;
    public Optional<ImmutableSortedSet<BuildRule>> deps;
  }
}
