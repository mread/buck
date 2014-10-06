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

package com.facebook.buck.groovy;

import com.facebook.buck.java.JavaCompilerEnvironment;
import com.facebook.buck.rules.AnnotationProcessingData;
import com.facebook.buck.rules.RuleKey;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;

import javax.annotation.Nullable;

/**
 * Represents the command line options that should be passed to javac. Note that the options do not
 * include either the classpath or the directory for storing class files.
 */
public class GroovycOptions {

  // Fields are initialized in order. We need the default java target level to have been set.
  public static final GroovycOptions DEFAULTS = GroovycOptions.builder().build();

  private final JavaCompilerEnvironment javacEnv;
  private final boolean debug;
  private final boolean verbose;
  private final AnnotationProcessingData annotationProcessingData;
  private final Optional<String> bootclasspath;

  private GroovycOptions(
      JavaCompilerEnvironment javacEnv,
      boolean debug,
      boolean verbose,
      Optional<String> bootclasspath,
      AnnotationProcessingData annotationProcessingData) {
    this.javacEnv = Preconditions.checkNotNull(javacEnv);
    this.debug = debug;
    this.verbose = verbose;
    this.bootclasspath = Preconditions.checkNotNull(bootclasspath);
    this.annotationProcessingData = Preconditions.checkNotNull(annotationProcessingData);
  }

  public JavaCompilerEnvironment getJavaCompilerEnvironment() {
    return javacEnv;
  }

  public RuleKey.Builder appendToRuleKey(RuleKey.Builder builder) {
    // TODO(simons): Include bootclasspath params.
    builder.set("sourceLevel", javacEnv.getSourceLevel())
        .set("targetLevel", javacEnv.getTargetLevel())
        .set("debug", debug)
        .set("javacVersion", javacEnv.getJavacVersion().transform(
            Functions.toStringFunction()).orNull());

    return annotationProcessingData.appendToRuleKey(builder);
  }

  public AnnotationProcessingData getAnnotationProcessingData() {
    return annotationProcessingData;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static Builder builder(GroovycOptions options) {
    Builder builder = builder();

    builder.setVerboseOutput(options.verbose);
    if (!options.debug) {
      builder.setProductionBuild();
    }

    builder.setAnnotationProcessingData(options.annotationProcessingData);
    builder.setBootclasspath(options.bootclasspath.orNull());

    builder.setJavaCompilerEnvironment(options.getJavaCompilerEnvironment());

    return builder;
  }

  public static class Builder {
    private boolean debug = true;
    private boolean verbose = false;
    private Optional<String> bootclasspath = Optional.absent();
    private AnnotationProcessingData annotationProcessingData = AnnotationProcessingData.EMPTY;
    private JavaCompilerEnvironment javacEnv = JavaCompilerEnvironment.DEFAULT;

    private Builder() {
    }

    public Builder setProductionBuild() {
      debug = false;
      return this;
    }

    public Builder setVerboseOutput(boolean verbose) {
      this.verbose = verbose;
      return this;
    }

    public Builder setBootclasspath(@Nullable String bootclasspath) {
      this.bootclasspath = Optional.fromNullable(bootclasspath);
      return this;
    }

    public Builder setAnnotationProcessingData(AnnotationProcessingData annotationProcessingData) {
      this.annotationProcessingData = annotationProcessingData;
      return this;
    }

    public Builder setJavaCompilerEnvironment(JavaCompilerEnvironment javacEnv) {
      this.javacEnv = javacEnv;
      return this;
    }

    public GroovycOptions build() {
      return new GroovycOptions(
          javacEnv,
          debug,
          verbose,
          bootclasspath,
          annotationProcessingData);
    }
  }
}
