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

package com.facebook.buck.ocaml;

import com.facebook.buck.cxx.NativeLinkableInput;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleSourcePath;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.step.Step;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;

import javax.annotation.Nullable;

class PrebuiltOCamlLibrary extends AbstractBuildRule implements OCamlLibrary {

  private final String nativeLib;
  private final String bytecodeLib;
  private final SourcePath staticNativeLibraryPath;
  private final SourcePath staticCLibraryPath;
  private final SourcePath bytecodeLibraryPath;
  private final Path libPath;

  public PrebuiltOCamlLibrary(
      BuildRuleParams params,
      String nativeLib,
      String bytecodeLib,
      SourcePath staticNativeLibraryPath,
      SourcePath staticCLibraryPath,
      SourcePath bytecodeLibraryPath,
      Path libPath) {
    super(params);
    this.nativeLib = nativeLib;
    this.bytecodeLib = bytecodeLib;
    this.staticNativeLibraryPath = staticNativeLibraryPath;
    this.staticCLibraryPath = staticCLibraryPath;
    this.bytecodeLibraryPath = bytecodeLibraryPath;
    this.libPath = libPath;
  }

  @Override
  protected ImmutableCollection<Path> getInputsToCompareToOutput() {
    return SourcePaths.filterInputsToCompareToOutput(
        ImmutableList.of(staticCLibraryPath, staticNativeLibraryPath, bytecodeLibraryPath));
  }

  @Override
  protected RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return builder.set("nativeLib", nativeLib)
                  .set("bytecodeLib", bytecodeLib)
                  .set("libPath", libPath.toString());
  }

  @Override
  public NativeLinkableInput getNativeLinkableInput(Type type) {
    Preconditions.checkArgument(
        type == Type.STATIC,
        "Only supporting static linking in OCaml");

    Preconditions.checkState(
        bytecodeLib.equals(
            nativeLib.replaceFirst(
                OCamlCompilables.OCAML_CMXA_REGEX,
                OCamlCompilables.OCAML_CMA)),
        "Bytecode library should have the same name as native library but with a .cma extension"
    );

    // Build the library path and linker arguments that we pass through the
    // {@link NativeLinkable} interface for linking.
    ImmutableList.Builder<SourcePath> librariesBuilder = ImmutableList.builder();
    librariesBuilder.add(new BuildRuleSourcePath(this, staticNativeLibraryPath.resolve()));
    librariesBuilder.add(new BuildRuleSourcePath(this, staticCLibraryPath.resolve()));
    final ImmutableList<SourcePath> libraries = librariesBuilder.build();

    ImmutableList.Builder<String> linkerArgsBuilder = ImmutableList.builder();
    linkerArgsBuilder.add(staticNativeLibraryPath.toString());
    linkerArgsBuilder.add(staticCLibraryPath.toString());
    final ImmutableList<String> linkerArgs = linkerArgsBuilder.build();

    return new NativeLinkableInput(
        /* inputs */ libraries,
        /* args */ linkerArgs);
  }

  @Override
  public Path getIncludeLibDir() {
    return libPath;
  }

  @Override
  public Iterable<String> getBytecodeIncludeDirs() {
    return ImmutableList.of(
        OCamlCompilables.OCAML_INCLUDE_FLAG,
        libPath.toString()
    );
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    return ImmutableList.of();
  }

  @Nullable
  @Override
  public Path getPathToOutputFile() {
    return null;
  }
}
