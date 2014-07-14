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

package com.facebook.buck.rules;

import com.facebook.buck.android.AndroidBinaryDescription;
import com.facebook.buck.android.AndroidBuildConfigDescription;
import com.facebook.buck.android.AndroidInstrumentationApkDescription;
import com.facebook.buck.android.AndroidLibraryDescription;
import com.facebook.buck.android.AndroidManifestDescription;
import com.facebook.buck.android.AndroidResourceDescription;
import com.facebook.buck.android.ApkGenruleDescription;
import com.facebook.buck.android.GenAidlDescription;
import com.facebook.buck.android.NdkLibraryDescription;
import com.facebook.buck.android.PrebuiltNativeLibraryDescription;
import com.facebook.buck.android.RobolectricTestDescription;
import com.facebook.buck.apple.AppleAssetCatalogDescription;
import com.facebook.buck.apple.CoreDataModelDescription;
import com.facebook.buck.apple.IosBinaryDescription;
import com.facebook.buck.apple.IosLibraryDescription;
import com.facebook.buck.apple.IosPostprocessResourcesDescription;
import com.facebook.buck.apple.IosResourceDescription;
import com.facebook.buck.apple.IosTestDescription;
import com.facebook.buck.apple.MacosxBinaryDescription;
import com.facebook.buck.apple.MacosxFrameworkDescription;
import com.facebook.buck.apple.OsxResourceDescription;
import com.facebook.buck.apple.XcodeNativeDescription;
import com.facebook.buck.apple.XcodeProjectConfigDescription;
import com.facebook.buck.apple.XcodeWorkspaceConfigDescription;
import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cpp.CppBinaryDescription;
import com.facebook.buck.cpp.CppLibraryDescription;
import com.facebook.buck.extension.BuckExtensionDescription;
import com.facebook.buck.gwt.GwtBinaryDescription;
import com.facebook.buck.java.JavaBinaryDescription;
import com.facebook.buck.java.JavaCompilerEnvironment;
import com.facebook.buck.java.JavaLibraryDescription;
import com.facebook.buck.java.JavaTestDescription;
import com.facebook.buck.java.JavacOptions;
import com.facebook.buck.java.KeystoreDescription;
import com.facebook.buck.java.PrebuiltJarDescription;
import com.facebook.buck.parcelable.GenParcelableDescription;
import com.facebook.buck.python.PythonBinaryDescription;
import com.facebook.buck.python.PythonLibraryDescription;
import com.facebook.buck.shell.ExportFileDescription;
import com.facebook.buck.shell.GenruleDescription;
import com.facebook.buck.shell.ShBinaryDescription;
import com.facebook.buck.shell.ShTestDescription;
import com.facebook.buck.util.AndroidDirectoryResolver;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;

import java.util.Map;

/**
 * A registry of all the build rules types understood by Buck.
 */
public class KnownBuildRuleTypes {

  private final ImmutableMap<BuildRuleType, Description<?>> descriptions;
  private final ImmutableMap<String, BuildRuleType> types;
  private static volatile KnownBuildRuleTypes defaultRules = null;

  private KnownBuildRuleTypes(
      Map<BuildRuleType, Description<?>> descriptions,
      Map<String, BuildRuleType> types) {
    this.descriptions = ImmutableMap.copyOf(descriptions);
    this.types = ImmutableMap.copyOf(types);
  }

  public BuildRuleType getBuildRuleType(String named) {
    BuildRuleType type = types.get(named);
    if (type == null) {
      throw new HumanReadableException("Unable to find build rule type: " + named);
    }
    return type;
  }

  public Description<? extends ConstructorArg> getDescription(BuildRuleType buildRuleType) {
    Description<?> description = descriptions.get(buildRuleType);
    if (description == null) {
      throw new HumanReadableException(
          "Unable to find description for build rule type: " + buildRuleType);
    }
    return description;
  }

  public ImmutableSet<Description<?>> getAllDescriptions() {
    return ImmutableSet.copyOf(descriptions.values());
  }

  public static Builder builder() {
    return new Builder();
  }

  @VisibleForTesting
  static void resetDefaultInstance() {
    defaultRules = null;
  }

  @VisibleForTesting
  static KnownBuildRuleTypes replaceDefaultInstance(
      BuckConfig config,
      AndroidDirectoryResolver androidDirectoryResolver,
      JavaCompilerEnvironment javacEnv) {
    resetDefaultInstance();
    return createInstance(config, androidDirectoryResolver, javacEnv);
  }


  public static KnownBuildRuleTypes createInstance(
      BuckConfig config,
      AndroidDirectoryResolver androidDirectoryResolver,
      JavaCompilerEnvironment javacEnv) {
    // Fast path
    if (defaultRules == null) {
      // Slow path
      synchronized (KnownBuildRuleTypes.class) {
        if (defaultRules == null) {
          defaultRules = createBuilder(config, androidDirectoryResolver, javacEnv).build();
        }
      }
    }

    return defaultRules;
  }

  @VisibleForTesting
  static Builder createBuilder(
      BuckConfig config,
      AndroidDirectoryResolver androidDirectoryResolver,
      JavaCompilerEnvironment javacEnv) {

    Optional<String> ndkVersion = config.getNdkVersion();
    // If a NDK version isn't specified, we've got to reach into the runtime environment to find
    // out which one we will end up using.
    if (!ndkVersion.isPresent()) {
      ndkVersion = androidDirectoryResolver.getNdkVersion();
    }

    Builder builder = builder();

    JavacOptions androidBinaryOptions = JavacOptions.builder(JavacOptions.DEFAULTS)
        .setJavaCompilerEnviornment(javacEnv)
        .build();
    builder.register(new AndroidBinaryDescription(
            androidBinaryOptions,
            config.getProguardJarOverride()));
    builder.register(new AndroidBuildConfigDescription());
    builder.register(new AndroidInstrumentationApkDescription());
    builder.register(new AndroidLibraryDescription(javacEnv));
    builder.register(new AndroidManifestDescription());
    builder.register(new AndroidResourceDescription());
    builder.register(new ApkGenruleDescription());
    builder.register(new AppleAssetCatalogDescription());
    builder.register(new BuckExtensionDescription());
    builder.register(new CoreDataModelDescription());
    builder.register(new CppBinaryDescription());
    builder.register(new CppLibraryDescription());
    builder.register(new ExportFileDescription());
    builder.register(new GenruleDescription());
    builder.register(new GenAidlDescription());
    builder.register(new GenParcelableDescription());
    builder.register(new GwtBinaryDescription());
    builder.register(new KeystoreDescription());
    builder.register(new JavaBinaryDescription());
    builder.register(new JavaLibraryDescription(javacEnv));
    builder.register(new JavaTestDescription(javacEnv));
    builder.register(new IosBinaryDescription());
    builder.register(new IosLibraryDescription());
    builder.register(new IosPostprocessResourcesDescription());
    builder.register(new IosResourceDescription());
    builder.register(new IosTestDescription());
    builder.register(new JavaBinaryDescription());
    builder.register(new MacosxBinaryDescription());
    builder.register(new MacosxFrameworkDescription());
    builder.register(new NdkLibraryDescription(ndkVersion));
    builder.register(new OsxResourceDescription());
    builder.register(new PrebuiltJarDescription());
    builder.register(new PrebuiltNativeLibraryDescription());
    builder.register(new ProjectConfigDescription());
    builder.register(new PythonBinaryDescription());
    builder.register(new PythonLibraryDescription());
    builder.register(new RobolectricTestDescription(javacEnv));
    builder.register(new ShBinaryDescription());
    builder.register(new ShTestDescription());
    builder.register(new XcodeNativeDescription());
    builder.register(new XcodeProjectConfigDescription());
    builder.register(new XcodeWorkspaceConfigDescription());

    return builder;
  }

  public static class Builder {
    private final Map<BuildRuleType, Description<?>> descriptions;
    private final Map<String, BuildRuleType> types;

    protected Builder() {
      this.descriptions = Maps.newConcurrentMap();
      this.types = Maps.newConcurrentMap();
    }

    public void register(Description<?> description) {
      Preconditions.checkNotNull(description);
      BuildRuleType type = description.getBuildRuleType();
      types.put(type.getName(), type);
      descriptions.put(type, description);
    }

    public KnownBuildRuleTypes build() {
      return new KnownBuildRuleTypes(descriptions, types);
    }
  }
}
