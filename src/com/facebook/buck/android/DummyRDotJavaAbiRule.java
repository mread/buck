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

import com.facebook.buck.java.JavaAbiRule;
import com.facebook.buck.rules.AbiRule;
import com.facebook.buck.rules.AbstractCachingBuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Buildable;
import com.facebook.buck.rules.Sha1HashCode;
import com.google.common.base.Preconditions;

import java.io.IOException;

import javax.annotation.Nullable;

/**
*
*/
public class DummyRDotJavaAbiRule extends AbstractCachingBuildRule implements AbiRule, JavaAbiRule {

  private final DummyRDotJava dummyRDotJava;

  DummyRDotJavaAbiRule(DummyRDotJava dummyRDotJava, BuildRuleParams params) {
    super(dummyRDotJava, params);
    this.dummyRDotJava = Preconditions.checkNotNull(dummyRDotJava);
  }

  @Override
  public BuildRuleType getType() {
    return BuildRuleType.DUMMY_R_DOT_JAVA;
  }

  @Nullable
  @Override
  public Buildable getBuildable() {
    return dummyRDotJava;
  }

  @Override
  public Sha1HashCode getAbiKey() {
    return dummyRDotJava.getRDotTxtSha1();
  }

  @Override
  public Sha1HashCode getAbiKeyForDeps() throws IOException {
    return dummyRDotJava.getAbiKeyForDeps();
  }
}
