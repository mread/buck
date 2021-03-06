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

package com.facebook.buck.cxx;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.util.MoreIterables;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import java.nio.file.Path;

public class CxxLinkableEnhancer {

  // Utility class doesn't instantiate.
  private CxxLinkableEnhancer() {}

  /**
   * Represents the link types.
   */
  public static enum LinkType {

    // Link as standalone executable.
    EXECUTABLE,

    // Link as shared library, which can be loaded into a process image.
    SHARED,

  }

  /**
   * Construct a {@link CxxLink} rule that builds a native linkable from top-level input objects
   * and a dependency tree of {@link NativeLinkable} dependencies.
   */
  public static CxxLink createCxxLinkableBuildRule(
      BuildRuleParams params,
      SourcePath linker,
      ImmutableList<String> cxxLdFlags,
      ImmutableList<String> ldFlags,
      BuildTarget target,
      LinkType linkType,
      Optional<String> soname,
      Path output,
      Iterable<SourcePath> objects,
      NativeLinkable.Type depType,
      Iterable<BuildRule> nativeLinkableDeps) {

    // Soname should only ever be set when linking a "shared" library.
    Preconditions.checkState(!soname.isPresent() || linkType.equals(LinkType.SHARED));

    // Collect and topologically sort our deps that contribute to the link.
    NativeLinkableInput linkableInput =
        NativeLinkables.getTransitiveNativeLinkableInput(
            nativeLinkableDeps,
            depType,
            /* reverse */ true);
    ImmutableList<SourcePath> allInputs =
        ImmutableList.<SourcePath>builder()
            .addAll(objects)
            .addAll(linkableInput.getInputs())
            .build();

    // Construct our link build rule params.  The important part here is combining the build rules
    // that construct our object file inputs and also the deps that build our dependencies.
    BuildRuleParams linkParams = params.copyWithChanges(
        NativeLinkable.NATIVE_LINKABLE_TYPE,
        target,
        // Add dependencies for build rules generating the object files and inputs from
        // dependencies.
        ImmutableSortedSet.copyOf(SourcePaths.filterBuildRuleInputs(allInputs)),
        ImmutableSortedSet.<BuildRule>of());

    // Build up the arguments to pass to the linker.
    ImmutableList.Builder<String> argsBuilder = ImmutableList.builder();
    if (linkType == LinkType.SHARED) {
      argsBuilder.add("-shared");
    }
    if (soname.isPresent()) {
      argsBuilder.add("-Xlinker", "-soname=" + soname.get());
    }
    argsBuilder.addAll(cxxLdFlags);
    argsBuilder.addAll(
        MoreIterables.zipAndConcat(
            Iterables.cycle("-Xlinker"),
            ldFlags));
    argsBuilder.addAll(
        MoreIterables.zipAndConcat(
            Iterables.cycle("-Xlinker"),
            Iterables.concat(
                FluentIterable.from(objects)
                    .transform(SourcePaths.TO_PATH)
                    .transform(Functions.toStringFunction()),
                linkableInput.getArgs())));
    ImmutableList<String> args = argsBuilder.build();

    // Build the C/C++ link step.
    return new CxxLink(
        linkParams,
        linker,
        output,
        allInputs,
        args);
  }

}
