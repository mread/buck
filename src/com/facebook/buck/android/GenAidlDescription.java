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

package com.facebook.buck.android;

import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.ConstructorArg;
import com.facebook.buck.rules.Description;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

public class GenAidlDescription implements Description<GenAidlDescription.Arg> {

  public static final BuildRuleType TYPE = new BuildRuleType("gen_aidl");

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> GenAidl createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {
    return new GenAidl(
        params,
        args.aidl,
        args.importPath);
  }

  public static class Arg implements ConstructorArg {
    public Path aidl;

    // import_path is an anomaly: it is a path that is relative to the project root rather than
    // relative to the build file directory.
    public String importPath;

    public Optional<ImmutableSortedSet<BuildRule>> deps;
  }
}
