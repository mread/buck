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

package com.facebook.buck.rules.coercer;

import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;
import java.util.List;

/**
 * A type coercer to handle source entries in an iOS or OS X rule.
 */
public class AppleSourceTypeCoercer implements TypeCoercer<AppleSource> {
  private final TypeCoercer<SourcePath> sourcePathTypeCoercer;
  private final TypeCoercer<Pair<SourcePath, String>> sourcePathWithFlagsTypeCoercer;
  private final TypeCoercer<Pair<String, ImmutableList<AppleSource>>> sourceGroupTypeCoercer;

  AppleSourceTypeCoercer(
      TypeCoercer<SourcePath> sourcePathTypeCoercer,
      TypeCoercer<Pair<SourcePath, String>> sourcePathWithFlagsTypeCoercer,
      TypeCoercer<String> stringTypeCoercer) {
    this.sourcePathTypeCoercer = Preconditions.checkNotNull(sourcePathTypeCoercer);
    this.sourcePathWithFlagsTypeCoercer = Preconditions.checkNotNull(
        sourcePathWithFlagsTypeCoercer);
    this.sourceGroupTypeCoercer = new PairTypeCoercer<String, ImmutableList<AppleSource>>(
        Preconditions.checkNotNull(stringTypeCoercer),
        new ListTypeCoercer<AppleSource>(this));
  }

  @Override
  public Class<AppleSource> getOutputClass() {
    return AppleSource.class;
  }

  @Override
  public boolean hasElementClass(Class<?>... types) {
    return
      sourcePathTypeCoercer.hasElementClass(types) ||
      sourcePathWithFlagsTypeCoercer.hasElementClass(types) ||
      sourceGroupTypeCoercer.hasElementClass(types);
  }

  @Override
  public boolean traverse(Object object, Traversal traversal) {
    if (sourcePathTypeCoercer.traverse(object, traversal)) {
      return true;
    } else if (sourcePathWithFlagsTypeCoercer.traverse(object, traversal)) {
      return true;
    } else if (sourceGroupTypeCoercer.traverse(object, traversal)) {
      return true;
    } else {
      return false;
    }
  }

  @Override
  public Optional<AppleSource> getOptionalValue() {
    return Optional.absent();
  }

  @Override
  @SuppressWarnings("PMD.EmptyCatchBlock")
  public AppleSource coerce(
      BuildRuleResolver buildRuleResolver,
      ProjectFilesystem filesystem,
      Path pathRelativeToProjectRoot,
      Object object) throws CoerceFailedException {
    if (object instanceof AppleSource) {
      return (AppleSource) object;
    }

    // We're expecting one of three types here. They can be differentiated pretty easily.
    if (object instanceof String) {
      return AppleSource.ofSourcePath(sourcePathTypeCoercer.coerce(
              buildRuleResolver,
              filesystem,
              pathRelativeToProjectRoot,
              object));
    }

    // If we get this far, we're dealing with a Pair. We can differentiate the kinds by looking at
    // the second item.

    if (object instanceof List<?>) {
      List<?> list = (List<?>) object;
      Object second = list.size() == 2 ? list.get(1) : null;

      if (second instanceof String) {
        return AppleSource.ofSourcePathWithFlags(
            sourcePathWithFlagsTypeCoercer.coerce(
                buildRuleResolver,
                filesystem,
                pathRelativeToProjectRoot,
                object));
      } else if (second instanceof List) {
        return AppleSource.ofSourceGroup(
            sourceGroupTypeCoercer.coerce(
                buildRuleResolver,
                filesystem,
                pathRelativeToProjectRoot,
                object));
      }
    }

    throw CoerceFailedException.simple(object, getOutputClass());
  }
}
