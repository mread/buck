/*
 * Copyright 2012-present Facebook, Inc.
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

import static org.junit.Assert.assertEquals;

import com.facebook.buck.java.JavaLibraryBuilder;
import com.facebook.buck.java.Keystore;
import com.facebook.buck.java.KeystoreBuilder;
import com.facebook.buck.java.PrebuiltJarBuilder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.util.BuckConstant;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.nio.file.Paths;

public class AndroidPackageableCollectorTest {

  /**
   * This is a regression test to ensure that an additional 1 second startup cost is not
   * re-introduced to fb4a.
   */
  @Test
  public void testFindTransitiveDependencies() {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();

    // Create an AndroidBinaryRule that transitively depends on two prebuilt JARs. One of the two
    // prebuilt JARs will be listed in the AndroidBinaryRule's no_dx list.
    BuildTarget guavaTarget = BuildTargetFactory.newInstance("//third_party/guava:guava");
    BuildRule guavaRule =
        PrebuiltJarBuilder.createBuilder(guavaTarget)
        .setBinaryJar(Paths.get("third_party/guava/guava-10.0.1.jar"))
        .build(ruleResolver);

    BuildRule jsr305Rule = PrebuiltJarBuilder
        .createBuilder(BuildTargetFactory.newInstance("//third_party/jsr-305:jsr-305"))
        .setBinaryJar(Paths.get("third_party/jsr-305/jsr305.jar"))
        .build(ruleResolver);

    BuildRule ndkLibrary =
        NdkLibraryBuilder.createNdkLibrary(BuildTargetFactory.newInstance(
                "//java/com/facebook/native_library:library"))
            .addSrc(Paths.get("Android.mk"))
            .setIsAsset(false).build();
    ruleResolver.addToIndex(ndkLibrary.getBuildTarget(), ndkLibrary);

    BuildTarget prebuiltNativeLibraryTarget =
        BuildTargetFactory.newInstance("//java/com/facebook/prebuilt_native_library:library");
    BuildRule prebuiltNativeLibraryBuild =
        PrebuiltNativeLibraryBuilder.newBuilder(prebuiltNativeLibraryTarget)
        .setNativeLibs(Paths.get("/java/com/facebook/prebuilt_native_library/libs"))
        .setIsAsset(true)
        .build();
    ruleResolver.addToIndex(prebuiltNativeLibraryTarget, prebuiltNativeLibraryBuild);

    BuildRule libraryRule = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/src/com/facebook:example"))
        .setProguardConfig(Paths.get("debug.pro"))
        .addSrc(Paths.get("Example.java"))
        .addDep(guavaRule)
        .addDep(jsr305Rule)
        .addDep(prebuiltNativeLibraryBuild)
        .addDep(ndkLibrary)
        .build(ruleResolver);

    BuildRule manifestRule = ruleResolver.addToIndex(
        AndroidResourceRuleBuilder.newBuilder()
            .setBuildTarget(BuildTargetFactory.newInstance("//java/src/com/facebook:res"))
            .setManifest(
                new PathSourcePath(Paths.get("java/src/com/facebook/module/AndroidManifest.xml")))
            .setAssets(Paths.get("assets"))
            .build());

    BuildTarget keystoreTarget = BuildTargetFactory.newInstance("//keystore:debug");
    BuildRule keystore = KeystoreBuilder.createBuilder(keystoreTarget)
        .setStore(Paths.get("keystore/debug.keystore"))
        .setProperties(Paths.get("keystore/debug.keystore.properties"))
        .build(ruleResolver);

    ImmutableSortedSet<BuildRule> originalDeps = ImmutableSortedSet.of(libraryRule, manifestRule);
    AndroidBinary binaryRule = (AndroidBinary) AndroidBinaryBuilder.createBuilder(
        BuildTargetFactory.newInstance("//java/src/com/facebook:app"))
        .setOriginalDeps(originalDeps)
        .setBuildTargetsToExcludeFromDex(
            ImmutableSet.of(BuildTargetFactory.newInstance("//third_party/guava:guava")))
        .setManifest(new TestSourcePath("java/src/com/facebook/AndroidManifest.xml"))
        .setTarget("Google Inc.:Google APIs:16")
        .setKeystore((Keystore) keystore)
        .build(ruleResolver);

    // Verify that the correct transitive dependencies are found.
    AndroidPackageableCollection packageableCollection =
        binaryRule.getAndroidPackageableCollection();
    assertEquals(
        "Because guava was passed to no_dx, it should not be in the classpathEntriesToDex list",
        ImmutableSet.of(
            Paths.get("third_party/jsr-305/jsr305.jar"),
            BuckConstant.GEN_PATH.resolve(
                "java/src/com/facebook/lib__example__output/example.jar")),
        packageableCollection.classpathEntriesToDex);
    assertEquals(
        "Because guava was passed to no_dx, it should not be treated as a third-party JAR whose " +
            "resources need to be extracted and repacked in the APK. If this is done, then code " +
            "in the guava-10.0.1.dex.1.jar in the APK's assets/ tmp may try to load the resource " +
            "from the APK as a ZipFileEntry rather than as a resource within " +
            "guava-10.0.1.dex.1.jar. Loading a resource in this way could take substantially " +
            "longer. Specifically, this was observed to take over one second longer to load " +
            "the resource in fb4a. Because the resource was loaded on startup, this introduced a " +
            "substantial regression in the startup time for the fb4a app.",
        ImmutableSet.of(Paths.get("third_party/jsr-305/jsr305.jar")),
        packageableCollection.pathsToThirdPartyJars);
    assertEquals(
        "Because assets directory was passed an AndroidResourceRule it should be added to the " +
            "transitive dependencies",
        ImmutableSet.of(Paths.get("assets")),
        packageableCollection.assetsDirectories);
    assertEquals(
        "Because manifest file was passed an AndroidResourceRule it should be added to the " +
            "transitive dependencies",
        ImmutableSet.of(Paths.get("java/src/com/facebook/module/AndroidManifest.xml")),
        packageableCollection.manifestFiles);
    assertEquals(
        "Because a native library was declared as a dependency, it should be added to the " +
            "transitive dependencies.",
        ImmutableSet.of(((NativeLibraryBuildRule) ndkLibrary).getLibraryPath()),
        packageableCollection.nativeLibsDirectories);
    assertEquals(
        "Because a prebuilt native library  was declared as a dependency (and asset), it should " +
            "be added to the transitive dependecies.",
        ImmutableSet.of(((NativeLibraryBuildRule) prebuiltNativeLibraryBuild)
            .getLibraryPath()),
        packageableCollection.nativeLibAssetsDirectories);
    assertEquals(
        ImmutableSet.of(Paths.get("debug.pro")),
        packageableCollection.proguardConfigs);
  }

  /**
   * Create the following dependency graph of {@link AndroidResource}s:
   * <pre>
   *    A
   *  / | \
   * B  |  D
   *  \ | /
   *    C
   * </pre>
   * Note that an ordinary breadth-first traversal would yield either {@code A B C D} or
   * {@code A D C B}. However, either of these would be <em>wrong</em> in this case because we need
   * to be sure that we perform a topological sort, the resulting traversal of which is either
   * {@code A B D C} or {@code A D B C}.
   * <p>
   * The reason for the correct result being reversed is because we want the resources with the most
   * dependencies listed first on the path, so that they're used in preference to the ones that they
   * depend on (presumably, the reason for extending the initial set of resources was to override
   * values).
   */
  @Test
  public void testGetAndroidResourceDeps() {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    BuildRule c = ruleResolver.addToIndex(
        AndroidResourceRuleBuilder.newBuilder()
            .setBuildTarget(BuildTargetFactory.newInstance("//:c"))
            .setRes(Paths.get("res_c"))
            .setRDotJavaPackage("com.facebook")
            .build());

    BuildRule b = ruleResolver.addToIndex(
        AndroidResourceRuleBuilder.newBuilder()
            .setBuildTarget(BuildTargetFactory.newInstance("//:b"))
            .setRes(Paths.get("res_b"))
            .setRDotJavaPackage("com.facebook")
            .setDeps(ImmutableSortedSet.of(c))
            .build());

    BuildRule d = ruleResolver.addToIndex(
        AndroidResourceRuleBuilder.newBuilder()
            .setBuildTarget(BuildTargetFactory.newInstance("//:d"))
            .setRes(Paths.get("res_d"))
            .setRDotJavaPackage("com.facebook")
            .setDeps(ImmutableSortedSet.of(c))
            .build());

    AndroidResource a = ruleResolver.addToIndex(
        AndroidResourceRuleBuilder.newBuilder()
            .setBuildTarget(BuildTargetFactory.newInstance("//:a"))
            .setRes(Paths.get("res_a"))
            .setRDotJavaPackage("com.facebook")
            .setDeps(ImmutableSortedSet.of(b, c, d))
            .build());

    AndroidPackageableCollector collector = new AndroidPackageableCollector(a.getBuildTarget());
    collector.addPackageables(ImmutableList.<AndroidPackageable>of(a));

    // Note that a topological sort for a DAG is not guaranteed to be unique, but we order nodes
    // within the same depth of the search.
    ImmutableList<BuildTarget> result = FluentIterable.from(ImmutableList.of(a, d, b, c))
        .transform(BuildTarget.TO_TARGET)
        .toList();

    assertEquals(
        String.format("Android resources should be topologically sorted."),
        result,
        collector.build().resourceDetails.resourcesWithNonEmptyResDir);

    // Introduce an AndroidBinaryRule that depends on A and C and verify that the same topological
    // sort results. This verifies that both AndroidResourceRule.getAndroidResourceDeps does the
    // right thing when it gets a non-AndroidResourceRule as well as an AndroidResourceRule.
    BuildTarget keystoreTarget = BuildTargetFactory.newInstance("//keystore:debug");
    BuildRule keystore = KeystoreBuilder.createBuilder(keystoreTarget)
        .setStore(Paths.get("keystore/debug.keystore"))
        .setProperties(Paths.get("keystore/debug.keystore.properties"))
        .build(ruleResolver);

    ImmutableSortedSet<BuildRule> declaredDeps = ImmutableSortedSet.of(a, c);
    AndroidBinary androidBinary = (AndroidBinary) AndroidBinaryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//:e"))
        .setManifest(new TestSourcePath("AndroidManfiest.xml"))
        .setTarget("Google Inc.:Google APIs:16")
        .setKeystore((Keystore) keystore)
        .setOriginalDeps(declaredDeps)
        .build(ruleResolver);

    assertEquals(
        String.format("Android resources should be topologically sorted."),
        result,
        androidBinary
            .getAndroidPackageableCollection()
            .resourceDetails
            .resourcesWithNonEmptyResDir);
  }

  /**
   * If the keystore rule depends on an android_library, and an android_binary uses that keystore,
   * the keystore's android_library should not contribute to the classpath of the android_binary.
   */
  @Test
  public void testGraphForAndroidBinaryExcludesKeystoreDeps() {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();

    BuildTarget androidLibraryKeystoreTarget =
        BuildTarget.builder("//java/com/keystore/base", "base").build();
    BuildRule androidLibraryKeystore = AndroidLibraryBuilder
        .createBuilder(androidLibraryKeystoreTarget)
        .addSrc(Paths.get("java/com/facebook/keystore/Base.java"))
        .build(ruleResolver);

    BuildTarget keystoreTarget = BuildTarget.builder("//keystore", "debug").build();
    BuildRule keystore = KeystoreBuilder.createBuilder(keystoreTarget)
        .setStore(Paths.get("keystore/debug.keystore"))
        .setProperties(Paths.get("keystore/debug.keystore.properties"))
        .addDep(androidLibraryKeystore)
        .build(ruleResolver);

    BuildTarget androidLibraryTarget =
        BuildTarget.builder("//java/com/facebook/base", "base").build();
    BuildRule androidLibrary = AndroidLibraryBuilder.createBuilder(androidLibraryTarget)
        .addSrc(Paths.get("java/com/facebook/base/Base.java"))
        .build(ruleResolver);

    ImmutableSortedSet<BuildRule> originalDeps = ImmutableSortedSet.of(androidLibrary);
    AndroidBinary androidBinary = (AndroidBinary) AndroidBinaryBuilder.createBuilder(
        BuildTarget.builder("//apps/sample", "app").build())
        .setManifest(new TestSourcePath("apps/sample/AndroidManifest.xml"))
        .setTarget("Google Inc.:Google APIs:16")
        .setOriginalDeps(originalDeps)
        .setKeystore((Keystore) keystore)
        .build(ruleResolver);

    AndroidPackageableCollection packageableCollection =
        androidBinary.getAndroidPackageableCollection();
    assertEquals(
        "Classpath entries should include facebook/base but not keystore/base.",
        ImmutableSet.of(
            BuckConstant.GEN_PATH.resolve("java/com/facebook/base/lib__base__output/base.jar")),
        packageableCollection.classpathEntriesToDex);
  }
}
