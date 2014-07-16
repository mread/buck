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

import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.createStrictMock;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.android.AndroidBinaryGraphEnhancer.EnhancementResult;
import com.facebook.buck.java.HasJavaClassHashes;
import com.facebook.buck.java.JavaLibraryBuilder;
import com.facebook.buck.java.JavacOptions;
import com.facebook.buck.java.Keystore;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeRuleKeyBuilderFactory;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.RuleKeyBuilderFactory;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.MoreAsserts;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.nio.file.Paths;
import java.util.Iterator;

public class AndroidBinaryGraphEnhancerTest {

  @Test
  public void testCreateDepsForPreDexing() {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    RuleKeyBuilderFactory ruleKeyBuilderFactory = new FakeRuleKeyBuilderFactory();

    // Create three Java rules, :dep1, :dep2, and :lib. :lib depends on :dep1 and :dep2.
    BuildTarget javaDep1BuildTarget = BuildTarget.builder("//java/com/example", "dep1").build();
    BuildRule javaDep1 = JavaLibraryBuilder
        .createBuilder(javaDep1BuildTarget)
        .addSrc(Paths.get("java/com/example/Dep1.java"))
        .build(ruleResolver);

    BuildTarget javaDep2BuildTarget = BuildTarget.builder("//java/com/example", "dep2").build();
    BuildRule javaDep2 = JavaLibraryBuilder
        .createBuilder(javaDep2BuildTarget)
        .addSrc(Paths.get("java/com/example/Dep2.java"))
        .build(ruleResolver);

    BuildTarget javaLibBuildTarget = BuildTarget.builder("//java/com/example", "lib").build();
    BuildRule javaLib = JavaLibraryBuilder
        .createBuilder(javaLibBuildTarget)
        .addSrc(Paths.get("java/com/example/Lib.java"))
        .addDep(javaDep1)
        .addDep(javaDep2)
        .build(ruleResolver);

    // Assume we are enhancing an android_binary rule whose only dep
    // is //java/com/example:lib, and that //java/com/example:dep2 is in its no_dx list.
    ImmutableSortedSet<BuildRule> originalDeps = ImmutableSortedSet.of(javaLib);
    ImmutableSet<BuildTarget> buildRulesToExcludeFromDex = ImmutableSet.of(javaDep2BuildTarget);
    BuildTarget apkTarget = BuildTarget.builder("//java/com/example", "apk").build();
    BuildRuleParams originalParams = new BuildRuleParams(
        apkTarget,
        originalDeps,
        originalDeps,
        /* visibilityPatterns */ ImmutableSet.<BuildTargetPattern>of(),
        new FakeProjectFilesystem(),
        ruleKeyBuilderFactory,
        AndroidBinaryDescription.TYPE);
    AndroidBinaryGraphEnhancer graphEnhancer = new AndroidBinaryGraphEnhancer(
        originalParams,
        ruleResolver,
        ResourcesFilter.ResourceCompressionMode.DISABLED,
        FilterResourcesStep.ResourceFilter.EMPTY_FILTER,
        createStrictMock(PathSourcePath.class),
        AndroidBinary.PackageType.DEBUG,
        /* cpuFilters */ ImmutableSet.< AndroidBinary.TargetCpuType>of(),
        /* shouldBuildStringSourceMap */ false,
        /* shouldPreDex */ true,
        BuildTargets.getBinPath(apkTarget, "%s/classes.dex"),
        DexSplitMode.NO_SPLIT,
        buildRulesToExcludeFromDex,
        /* resourcesToExclude */ ImmutableSet.<BuildTarget>of(),
        JavacOptions.DEFAULTS,
        /* exopackage */ false,
        createStrictMock(Keystore.class));

    BuildTarget uberRDotJavaTarget =
        BuildTarget.builder("//java/com/example", "apk").setFlavor("uber_r_dot_java").build();
    BuildRuleParams uberRDotJavaParams = new FakeBuildRuleParamsBuilder(uberRDotJavaTarget).build();
    UberRDotJava uberRDotJava = new UberRDotJava(
        uberRDotJavaParams,
        createMock(FilteredResourcesProvider.class),
        ImmutableList.<HasAndroidResourceDeps>of(),
        Suppliers.ofInstance(ImmutableSet.<String>of()),
        JavacOptions.DEFAULTS,
        false,
        false);
    ruleResolver.addToIndex(uberRDotJavaTarget, uberRDotJava);

    AndroidPackageableCollection collection =
        new AndroidPackageableCollector(
            /* collectionRoot */ apkTarget,
            ImmutableSet.of(javaDep2BuildTarget),
            /* resourcesToExclude */ ImmutableSet.<BuildTarget>of())
            .addClasspathEntry(
                ((HasJavaClassHashes) javaDep1), Paths.get("ignored"))
            .addClasspathEntry(
                ((HasJavaClassHashes) javaDep2), Paths.get("ignored"))
            .addClasspathEntry(
                ((HasJavaClassHashes) javaLib), Paths.get("ignored"))
            .build();


    BuildRule preDexMergeRule = graphEnhancer.createPreDexMergeRule(
        uberRDotJava,
        collection);
    BuildTarget dexMergeTarget =
        BuildTarget.builder("//java/com/example", "apk").setFlavor("dex_merge").build();
    BuildRule dexMergeRule = ruleResolver.get(dexMergeTarget);

    assertEquals(dexMergeRule, preDexMergeRule);

    assertEquals(
        "There should be a #dex rule for dep1 and lib, but not dep2 because it is in the no_dx " +
            "list.  And we should depend on uber_r_dot_java.",
        3,
        dexMergeRule.getDeps().size());

    Iterator<BuildRule> depsForPreDexingIter = dexMergeRule.getDeps().iterator();

    BuildRule shouldBeUberRDotJavaRule = depsForPreDexingIter.next();
    assertEquals(uberRDotJava, shouldBeUberRDotJavaRule);

    BuildRule preDexRule1 = depsForPreDexingIter.next();
    assertEquals("//java/com/example:dep1#dex", preDexRule1.getBuildTarget().toString());
    assertNotNull(ruleResolver.get(preDexRule1.getBuildTarget()));

    BuildRule preDexRule2 = depsForPreDexingIter.next();
    assertEquals("//java/com/example:lib#dex", preDexRule2.getBuildTarget().toString());
    assertNotNull(ruleResolver.get(preDexRule2.getBuildTarget()));
  }

  @Test
  public void testAllBuildablesExceptPreDexRule() {
    // Create an android_build_config() as a dependency of the android_binary().
    BuildTarget buildConfigBuildTarget = BuildTarget.builder("//java/com/example", "cfg").build();
    BuildRuleParams buildConfigParams = new FakeBuildRuleParamsBuilder(buildConfigBuildTarget)
        .build();
    AndroidBuildConfigJavaLibrary buildConfigJavaLibrary = AndroidBuildConfigDescription
        .createBuildRule(
          buildConfigParams,
          "com.example.buck",
          /* useConstantExpressions */ false,
          /* constants */ ImmutableMap.<String, Object>of());

    BuildTarget apkTarget = BuildTargetFactory.newInstance("//java/com/example:apk");
    BuildRuleParams originalParams = new FakeBuildRuleParamsBuilder(apkTarget)
        .setDeps(ImmutableSortedSet.<BuildRule>of(buildConfigJavaLibrary))
        .build();
    BuildRuleResolver ruleResolver = new BuildRuleResolver();

    // set it up.
    Keystore keystore = createStrictMock(Keystore.class);
    AndroidBinaryGraphEnhancer graphEnhancer = new AndroidBinaryGraphEnhancer(
        originalParams,
        ruleResolver,
        ResourcesFilter.ResourceCompressionMode.ENABLED_WITH_STRINGS_AS_ASSETS,
        FilterResourcesStep.ResourceFilter.EMPTY_FILTER,
        new TestSourcePath("AndroidManifest.xml"),
        AndroidBinary.PackageType.DEBUG,
        /* cpuFilters */ ImmutableSet.<AndroidBinary.TargetCpuType>of(),
        /* shouldBuildStringSourceMap */ false,
        /* shouldPreDex */ false,
        BuildTargets.getBinPath(apkTarget, "%s/classes.dex"),
        DexSplitMode.NO_SPLIT,
        /* buildRulesToExcludeFromDex */ ImmutableSet.<BuildTarget>of(),
        /* resourcesToExclude */ ImmutableSet.<BuildTarget>of(),
        JavacOptions.DEFAULTS,
        /* exopackage */ true,
        keystore);
    replay(keystore);
    EnhancementResult result = graphEnhancer.createAdditionalBuildables();

    // Verify that android_build_config() was processed correctly.
    AndroidPackageableCollection packageableCollection = result.getPackageableCollection();
    String flavor = "buildconfig_com_example_buck";
    assertEquals(
        "The only classpath entry to dex should be the one from the AndroidBuildConfigJavaLibrary" +
            " created via graph enhancement.",
        ImmutableSet.of(Paths.get(
            "buck-out/gen/java/com/example/lib__apk#" + flavor + "__output/apk#" + flavor + ".jar")
        ),
        packageableCollection.classpathEntriesToDex);
    BuildTarget enhancedBuildConfigTarget = BuildTarget.builder(apkTarget).setFlavor(flavor)
        .build();
    BuildRule enhancedBuildConfigRule = ruleResolver.get(enhancedBuildConfigTarget);
    assertTrue(enhancedBuildConfigRule instanceof AndroidBuildConfigJavaLibrary);
    AndroidBuildConfigJavaLibrary enhancedBuildConfigJavaLibrary =
        (AndroidBuildConfigJavaLibrary) enhancedBuildConfigRule;
    AndroidBuildConfig androidBuildConfig = enhancedBuildConfigJavaLibrary.getAndroidBuildConfig();
    assertEquals("com.example.buck", androidBuildConfig.getJavaPackage());
    assertTrue(androidBuildConfig.isUseConstantExpressions());
    assertEquals(
        "IS_EXOPACKAGE defaults to false, but should now be true. DEBUG should still be true.",
        ImmutableMap.of("DEBUG", Boolean.TRUE, "IS_EXOPACKAGE", Boolean.TRUE),
        androidBuildConfig.getConstants());

    ImmutableSortedSet<BuildRule> finalDeps = result.getFinalDeps();
    // Verify that the only dep is computeExopackageDepsAbi
    assertEquals(1, finalDeps.size());
    BuildRule computeExopackageDepsAbiRule =
        findRuleOfType(ruleResolver, ComputeExopackageDepsAbi.class);
    assertEquals(computeExopackageDepsAbiRule, finalDeps.first());

    FilteredResourcesProvider resourcesProvider = result.getFilteredResourcesProvider();
    assertTrue(resourcesProvider instanceof ResourcesFilter);
    BuildRule resourcesFilterRule = findRuleOfType(ruleResolver, ResourcesFilter.class);

    BuildRule uberRDotJavaRule = findRuleOfType(ruleResolver, UberRDotJava.class);
    MoreAsserts.assertDepends(
        "UberRDotJava must depend on ResourcesFilter",
        uberRDotJavaRule,
        resourcesFilterRule);

    BuildRule packageStringAssetsRule =
        findRuleOfType(ruleResolver, PackageStringAssets.class);
    MoreAsserts.assertDepends(
        "PackageStringAssets must depend on ResourcesFilter",
        packageStringAssetsRule,
        uberRDotJavaRule);

    BuildRule aaptPackageResourcesRule =
        findRuleOfType(ruleResolver, AaptPackageResources.class);
    MoreAsserts.assertDepends(
        "AaptPackageResources must depend on ResourcesFilter",
        aaptPackageResourcesRule,
        resourcesFilterRule);

    assertFalse(result.getPreDexMerge().isPresent());

    MoreAsserts.assertDepends(
        "ComputeExopackageDepsAbi must depend on ResourcesFilter",
        computeExopackageDepsAbiRule,
        resourcesFilterRule);
    MoreAsserts.assertDepends(
        "ComputeExopackageDepsAbi must depend on UberRDotJava",
        computeExopackageDepsAbiRule,
        uberRDotJavaRule);
    MoreAsserts.assertDepends(
        "ComputeExopackageDepsAbi must depend on PackageStringAssets",
        computeExopackageDepsAbiRule,
        packageStringAssetsRule);
    MoreAsserts.assertDepends(
        "ComputeExopackageDepsAbi must depend on AaptPackageResources",
        computeExopackageDepsAbiRule,
        aaptPackageResourcesRule);

    assertTrue(result.getPackageStringAssets().isPresent());
    assertTrue(result.getComputeExopackageDepsAbi().isPresent());

    verify(keystore);
  }

  private BuildRule findRuleOfType(BuildRuleResolver ruleResolver, Class<?> ruleClass) {
    for (BuildRule rule : ruleResolver.getBuildRules()) {
      if (ruleClass.isAssignableFrom(rule.getClass())) {
        return rule;
      }
    }
    fail("Could not find build rule of type " + ruleClass.getCanonicalName());
    return null;
  }
}
