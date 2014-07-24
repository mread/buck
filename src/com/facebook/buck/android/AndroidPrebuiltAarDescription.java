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

package com.facebook.buck.android;

import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.ConstructorArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePath;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSortedSet;

/**
 * Description for a {@link BuildRule} that wraps an {@code .aar} file as an Android dependency.
 * <p>
 * This represents an Android Library Project packaged as an {@code .aar} bundle as specified by:
 * http://tools.android.com/tech-docs/new-build-system/aar-format. When it is in the packageable
 * deps of an {@link AndroidBinary}, its contents will be included in the generated APK.
 * <p>
 * Note that the {@code aar} may be specified as a {@link SourcePath}, so it could be either
 * a binary {@code .aar} file checked into version control, or a zip file that conforms to the
 * {@code .aar} specification that is generated by another build rule.
 */
public class AndroidPrebuiltAarDescription
    implements Description<AndroidPrebuiltAarDescription.Arg> {

  public static final BuildRuleType TYPE = new BuildRuleType("android_prebuilt_aar");

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {
    return AndroidPrebuiltAarGraphEnhancer.enhance(params, args.aar, resolver);
  }

  public static class Arg implements ConstructorArg {
    public SourcePath aar;
    public Optional<ImmutableSortedSet<BuildRule>> deps;
  }

}
