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

import com.facebook.buck.java.HasJavaClassHashes;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.hash.HashCode;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

public class AndroidPackageableCollector {

  private final ImmutableList.Builder<BuildTarget> resourcesWithNonEmptyResDir =
      ImmutableList.builder();
  private final ImmutableList.Builder<BuildTarget> resourcesWithAssets = ImmutableList.builder();
  private final ImmutableList.Builder<Path> resourceDirectories = ImmutableList.builder();
  private final ImmutableSet.Builder<Path> whitelistedStringDirectories = ImmutableSet.builder();
  private final ImmutableSet.Builder<String> rDotJavaPackages = ImmutableSet.builder();
  private final ImmutableCollection.Builder<Supplier<String>> rDotJavaPackageSuppliers =
      ImmutableList.builder();
  private final ImmutableSet.Builder<Path> nativeLibsDirectories = ImmutableSet.builder();
  private final ImmutableSet.Builder<Path> nativeLibAssetsDirectories = ImmutableSet.builder();
  private final ImmutableSet.Builder<Path> assetsDirectories = ImmutableSet.builder();
  private final ImmutableSet.Builder<Path> manifestFiles = ImmutableSet.builder();
  private final ImmutableSet.Builder<Path> proguardConfigs = ImmutableSet.builder();
  private final ImmutableSet.Builder<Path> classpathEntriesToDex = ImmutableSet.builder();
  private final ImmutableSet.Builder<Path> noDxClasspathEntries = ImmutableSet.builder();

  // Map is used instead of ImmutableMap.Builder for its containsKey() method.
  private final Map<String, ImmutableMap<String, Object>> buildConfigs = Maps.newHashMap();

  private final ImmutableSet.Builder<Path> pathsToThirdPartyJars = ImmutableSet.builder();
  private final ImmutableSet.Builder<HasJavaClassHashes> javaClassHashesProviders =
      ImmutableSet.builder();

  private final BuildTarget collectionRoot;
  private final ImmutableSet<BuildTarget> buildTargetsToExcludeFromDex;
  private final ImmutableSet<BuildTarget> resourcesToExclude;

  @VisibleForTesting
  AndroidPackageableCollector(BuildTarget collectionRoot) {
    this(collectionRoot, ImmutableSet.<BuildTarget>of(), ImmutableSet.<BuildTarget>of());
  }

  /**
   * @param resourcesToExclude Only relevant to {@link AndroidInstrumentationApk} which needs to
   *     remove resources that are already included in the
   *     {@link AndroidInstrumentationApkDescription.Arg#apk}
   */
  public AndroidPackageableCollector(
      BuildTarget collectionRoot,
      ImmutableSet<BuildTarget> buildTargetsToExcludeFromDex,
      ImmutableSet<BuildTarget> resourcesToExclude) {
    this.collectionRoot = Preconditions.checkNotNull(collectionRoot);
    this.buildTargetsToExcludeFromDex = Preconditions.checkNotNull(buildTargetsToExcludeFromDex);
    this.resourcesToExclude = Preconditions.checkNotNull(resourcesToExclude);
  }

  public void addPackageables(Iterable<AndroidPackageable> packageables) {
    Set<AndroidPackageable> explored = Sets.newHashSet();

    for (AndroidPackageable packageable : packageables) {
      postOrderTraverse(packageable, explored);
    }
  }

  private void postOrderTraverse(
      AndroidPackageable packageable,
      Set<AndroidPackageable> explored) {
    if (explored.contains(packageable)) {
      return;
    }
    explored.add(packageable);

    for (AndroidPackageable dep : packageable.getRequiredPackageables()) {
      postOrderTraverse(dep, explored);
    }
    packageable.addToCollector(this);
  }

  /**
   * Returns all {@link BuildRule}s of the given rules that are {@link AndroidPackageable}.
   * Helper for implementations of AndroidPackageable that just want to return all of their
   * packagable dependencies.
   */
  public static Iterable<AndroidPackageable> getPackageableRules(Iterable<BuildRule> rules) {
    return FluentIterable.from(rules)
        .filter(AndroidPackageable.class)
        .toList();
  }

  public AndroidPackageableCollector addStringWhitelistedResourceDirectory(
      BuildTarget owner,
      Path resourceDir,
      Supplier<String> rDotJavaPackage) {
    if (resourcesToExclude.contains(owner)) {
      return this;
    }

    whitelistedStringDirectories.add(resourceDir);
    doAddResourceDirectory(owner, resourceDir);
    rDotJavaPackageSuppliers.add(rDotJavaPackage);
    return this;
  }

  public AndroidPackageableCollector addResourceDirectory(
      BuildTarget owner,
      Path resourceDir,
      String rDotJavaPackage) {
    if (resourcesToExclude.contains(owner)) {
      return this;
    }

    doAddResourceDirectory(owner, resourceDir);
    rDotJavaPackages.add(rDotJavaPackage);
    return this;
  }

  public AndroidPackageableCollector addResourceDirectory(
      BuildTarget owner,
      Path resourceDir,
      Supplier<String> rDotJavaPackageSupplier) {
    if (resourcesToExclude.contains(owner)) {
      return this;
    }

    doAddResourceDirectory(owner, resourceDir);
    rDotJavaPackageSuppliers.add(rDotJavaPackageSupplier);
    return this;
  }

  private void doAddResourceDirectory(BuildTarget owner, Path resourceDir) {
    resourcesWithNonEmptyResDir.add(owner);
    resourceDirectories.add(resourceDir);
  }

  public AndroidPackageableCollector addNativeLibsDirectory(Path nativeLibDir) {
    nativeLibsDirectories.add(nativeLibDir);
    return this;
  }

  public AndroidPackageableCollector addNativeLibAssetsDirectory(Path nativeLibAssetsDir) {
    nativeLibAssetsDirectories.add(nativeLibAssetsDir);
    return this;
  }

  public AndroidPackageableCollector addAssetsDirectory(BuildTarget owner, Path assetsDirectory) {
    if (resourcesToExclude.contains(owner)) {
      return this;
    }

    resourcesWithAssets.add(owner);
    assetsDirectories.add(assetsDirectory);
    return this;
  }

  public AndroidPackageableCollector addManifestFile(BuildTarget owner, Path manifestFile) {
    if (!buildTargetsToExcludeFromDex.contains(owner) &&
        !resourcesToExclude.contains(owner)) {
      manifestFiles.add(manifestFile);
    }
    return this;
  }

  public AndroidPackageableCollector addProguardConfig(BuildTarget owner, Path proguardConfig) {
    if (!buildTargetsToExcludeFromDex.contains(owner)) {
      proguardConfigs.add(proguardConfig);
    }
    return this;
  }

  public AndroidPackageableCollector addClasspathEntry(
      HasJavaClassHashes hasJavaClassHashes,
      Path classpathEntry) {
    if (buildTargetsToExcludeFromDex.contains(hasJavaClassHashes.getBuildTarget())) {
      noDxClasspathEntries.add(classpathEntry);
    } else {
      classpathEntriesToDex.add(classpathEntry);
      javaClassHashesProviders.add(hasJavaClassHashes);
    }
    return this;
  }

  public AndroidPackageableCollector addPathToThirdPartyJar(
      BuildTarget owner,
      Path pathToThirdPartyJar) {
    if (buildTargetsToExcludeFromDex.contains(owner)) {
      noDxClasspathEntries.add(pathToThirdPartyJar);
    } else {
      pathsToThirdPartyJars.add(pathToThirdPartyJar);
    }
    return this;
  }

  public void addBuildConfig(String javaPackage, ImmutableMap<String, Object> constants) {
    Preconditions.checkNotNull(javaPackage);
    Preconditions.checkNotNull(constants);
    if (buildConfigs.containsKey(javaPackage)) {
      throw new HumanReadableException(
          "Multiple android_build_config() rules with the same package %s in the " +
              "transitive deps of %s.",
          javaPackage,
          collectionRoot);
    }
    buildConfigs.put(javaPackage, constants);
  }

  public AndroidPackageableCollection build() {
    final ImmutableSet<HasJavaClassHashes> javaClassProviders = javaClassHashesProviders.build();
    Supplier<Map<String, HashCode>> classNamesToHashesSupplier = Suppliers.memoize(
        new Supplier<Map<String, HashCode>>() {
          @Override
          public Map<String, HashCode> get() {
            ImmutableMap.Builder<String, HashCode> builder = ImmutableMap.builder();
            for (HasJavaClassHashes hasJavaClassHashes : javaClassProviders) {
              builder.putAll(hasJavaClassHashes.getClassNamesToHashes());
            }
            return builder.build();
          }
        });

    ImmutableList.Builder<BuildTarget> resourcesWithEmptyResButNonEmptyAssetsDir =
        ImmutableList.builder();
    ImmutableSet<BuildTarget> resources = ImmutableSet.copyOf(resourcesWithNonEmptyResDir.build());
    for (BuildTarget buildTarget : resourcesWithAssets.build()) {
      if (!resources.contains(buildTarget)) {
        resourcesWithEmptyResButNonEmptyAssetsDir.add(buildTarget);
      }
    }

    final ImmutableSet<String> knownRDotJavaPackages = rDotJavaPackages.build();
    final ImmutableCollection<Supplier<String>> knownRDotJavaPackageSuppliers =
        rDotJavaPackageSuppliers.build();
    boolean hasRDotJavaPackages = !knownRDotJavaPackages.isEmpty() ||
        !knownRDotJavaPackageSuppliers.isEmpty();
    Supplier<ImmutableSet<String>> rDotJavaPackagesSupplier = Suppliers.memoize(
        new Supplier<ImmutableSet<String>>() {
          @Override
          public ImmutableSet<String> get() {
            ImmutableSet.Builder<String> allRDotJavaPackages = ImmutableSet.builder();
            allRDotJavaPackages.addAll(knownRDotJavaPackages);
            for (Supplier<String> supplier : knownRDotJavaPackageSuppliers) {
              allRDotJavaPackages.add(supplier.get());
            }
            return allRDotJavaPackages.build();
          }
    });

    return new AndroidPackageableCollection(
        // Reverse the resource directories/targets collections because we perform a post-order
        // traversal of the action graph, and we need to return these collections topologically
        // sorted.
        new AndroidPackageableCollection.ResourceDetails(
            resourceDirectories.build().reverse(),
            whitelistedStringDirectories.build(),
            rDotJavaPackagesSupplier,
            hasRDotJavaPackages,
            resourcesWithNonEmptyResDir.build().reverse(),
            resourcesWithEmptyResButNonEmptyAssetsDir.build().reverse()),
        nativeLibsDirectories.build(),
        nativeLibAssetsDirectories.build(),
        assetsDirectories.build(),
        manifestFiles.build(),
        proguardConfigs.build(),
        classpathEntriesToDex.build(),
        noDxClasspathEntries.build(),
        ImmutableMap.copyOf(buildConfigs),
        pathsToThirdPartyJars.build(),
        FluentIterable.from(javaClassProviders).transform(BuildTarget.TO_TARGET).toSet(),
        classNamesToHashesSupplier);
    }
}
