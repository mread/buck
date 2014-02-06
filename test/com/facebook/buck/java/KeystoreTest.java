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

package com.facebook.buck.java;

import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.AbstractCachingBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.FakeAbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.testutil.MoreAsserts;
import com.google.common.collect.ImmutableList;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;

public class KeystoreTest {

  private static AbstractCachingBuildRule createKeystoreRuleForTest() {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    return Keystore
        .newKeystoreBuilder(new FakeAbstractBuildRuleBuilderParams())
        .setBuildTarget(BuildTargetFactory.newInstance("//keystores:debug"))
        .setStore(Paths.get("keystores/debug.keystore"))
        .setProperties(Paths.get("keystores/debug.keystore.properties"))
        .build(ruleResolver);
  }

  @Test
  public void testObservers() {
    AbstractCachingBuildRule rule = createKeystoreRuleForTest();
    assertEquals(BuildRuleType.KEYSTORE, rule.getType());

    Keystore keystore = (Keystore) rule.getBuildable();
    MoreAsserts.assertIterablesEquals(
        ImmutableList.of(Paths.get("keystores/debug.keystore"), Paths.get("keystores/debug.keystore.properties")),
        keystore.getInputsToCompareToOutput());
    assertEquals(Paths.get("keystores/debug.keystore"), keystore.getPathToStore());
    assertEquals(Paths.get("keystores/debug.keystore.properties"),
        keystore.getPathToPropertiesFile());
  }

  @Test
  public void testBuildInternal() throws IOException {
    BuildContext buildContext = createMock(BuildContext.class);

    replay(buildContext);

    AbstractCachingBuildRule keystore = createKeystoreRuleForTest();
    List<Step> buildSteps = keystore.getBuildable().getBuildSteps(buildContext,
        new FakeBuildableContext());
    assertEquals(ImmutableList.<Step>of(), buildSteps);

    verify(buildContext);
  }
}
