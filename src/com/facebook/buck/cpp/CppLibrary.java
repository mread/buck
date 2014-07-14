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

package com.facebook.buck.cpp;

import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.step.Step;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.util.List;

/**
 * Create a c++ static library (.a file).
 */
public class CppLibrary extends AbstractNativeBuildRule {

  public CppLibrary(
      BuildRuleParams params,
      ImmutableSortedSet<SourcePath> srcs,
      ImmutableSortedSet<SourcePath> headers) {
    super(params, srcs, headers, ImmutableMap.<SourcePath, String>of());
  }

  @Override
  protected String getCompiler() {
    return DEFAULT_CPP_COMPILER;
  }

  @Override
  protected List<Step> getFinalBuildSteps(ImmutableSortedSet<Path> files, Path outputFile) {
    return ImmutableList.<Step>of(new ArStep(files, outputFile));
  }

  @Override
  protected String getOutputFileNameFormat() {
    return "lib%s.a";
  }
}
