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

package com.facebook.buck.rules.coercer;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePath;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.primitives.Primitives;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.nio.file.Path;

public class TypeCoercerFactory {
  private final TypeCoercer<Path> pathTypeCoercer = new PathTypeCoercer();
  private final TypeCoercer<BuildTarget> buildTargetTypeCoercer = new BuildTargetTypeCoercer();
  private final TypeCoercer<SourcePath> sourcePathTypeCoercer =
      new SourcePathTypeCoercer(buildTargetTypeCoercer, pathTypeCoercer);
  private final TypeCoercer<BuildRule> buildRuleTypeCoercer =
      new BuildRuleTypeCoercer(buildTargetTypeCoercer);

  // This has no implementation, but is here so that constructor succeeds so that it can be queried.
  // This is only used for the visibility field, which is not actually handled by the coercer.
  private final TypeCoercer<BuildTargetPattern> buildTargetPatternTypeCoercer =
      new IdentityTypeCoercer<BuildTargetPattern>(BuildTargetPattern.class) {
        @Override
        public BuildTargetPattern coerce(
            BuildRuleResolver buildRuleResolver, Path pathRelativeToProjectRoot, Object object)
            throws CoerceFailedException {
          throw new UnsupportedOperationException();
        }
      };

  private final TypeCoercer<?>[] nonContainerTypeCoercers = {
      // special classes
      pathTypeCoercer,
      sourcePathTypeCoercer,
      buildTargetTypeCoercer,
      buildRuleTypeCoercer,
      buildTargetPatternTypeCoercer,

      // identity
      new IdentityTypeCoercer<>(String.class),
      new IdentityTypeCoercer<>(Boolean.class),

      // numeric
      new NumberTypeCoercer<>(Integer.class),
      new NumberTypeCoercer<>(Double.class),
      new NumberTypeCoercer<>(Float.class),
      new NumberTypeCoercer<>(Long.class),
      new NumberTypeCoercer<>(Short.class),
      new NumberTypeCoercer<>(Byte.class),
  };

  public TypeCoercer<?> typeCoercerForType(Type type) {
    if (type instanceof TypeVariable) {
      type = ((TypeVariable) type).getBounds()[0];
      if (Object.class.equals(type)) {
        throw new IllegalArgumentException("Generic types must be specific: " + type);
      }
    }

    if (type instanceof WildcardType) {
      type = ((WildcardType) type).getUpperBounds()[0];
      if (Object.class.equals(type)) {
        throw new IllegalArgumentException("Generic types must be specific: " + type);
      }
    }

    if (type instanceof Class) {
      Class<?> rawClass = Primitives.wrap((Class<?>) type);

      TypeCoercer<?> selectedTypeCoercer = null;
      for (TypeCoercer<?> typeCoercer : nonContainerTypeCoercers) {
        if (rawClass.isAssignableFrom(typeCoercer.getOutputClass())) {
          if (selectedTypeCoercer == null) {
            selectedTypeCoercer = typeCoercer;
          } else {
            throw new IllegalArgumentException("multiple coercers matched for type: " + type);
          }
        }
      }
      if (selectedTypeCoercer != null) {
        return selectedTypeCoercer;
      } else {
        throw new IllegalArgumentException("no type coercer for type: " + type);
      }
    } else if (type instanceof ParameterizedType) {
      ParameterizedType parameterizedType = (ParameterizedType) type;

      Type rawType = parameterizedType.getRawType();
      if (!(rawType instanceof Class<?>)) {
        throw new RuntimeException(
            "expected getRawType() to return a class for type: " + parameterizedType);
      }

      Class<?> rawClass = (Class<?>) rawType;
      if (rawClass.equals(Either.class)) {
        Preconditions.checkState(parameterizedType.getActualTypeArguments().length == 2,
            "expected type '%s' to have two parameters", parameterizedType);
        return new EitherTypeCoercer<>(
            typeCoercerForType(parameterizedType.getActualTypeArguments()[0]),
            typeCoercerForType(parameterizedType.getActualTypeArguments()[1]));
      } else if (rawClass.equals(Pair.class)) {
        Preconditions.checkState(parameterizedType.getActualTypeArguments().length == 2,
            "expected type '%s' to have two parameters", parameterizedType);
        return new PairTypeCoercer<>(
            typeCoercerForType(parameterizedType.getActualTypeArguments()[0]),
            typeCoercerForType(parameterizedType.getActualTypeArguments()[1]));
      } else if (rawClass.isAssignableFrom(ImmutableList.class)) {
        return new ListTypeCoercer<>(
            typeCoercerForType(getSingletonTypeParameter(parameterizedType)));
      } else if (rawClass.isAssignableFrom(ImmutableSet.class)) {
        return new SetTypeCoercer<>(
            typeCoercerForType(getSingletonTypeParameter(parameterizedType)));
      } else if (rawClass.isAssignableFrom(ImmutableSortedSet.class)) {
        // SortedSet is tested second because it is a subclass of Set, and therefore can
        // be assigned to something of type Set, but not vice versa.
        Type elementType = getSingletonTypeParameter(parameterizedType);
        return new SortedSetTypeCoercer<>(typeCoercerForComparableType(elementType));
      } else if (rawClass.isAssignableFrom(ImmutableMap.class)) {
        Preconditions.checkState(parameterizedType.getActualTypeArguments().length == 2,
            "expected type '%s' to have two parameters", parameterizedType);
        return new MapTypeCoercer<>(
            typeCoercerForType(parameterizedType.getActualTypeArguments()[0]),
            typeCoercerForType(parameterizedType.getActualTypeArguments()[1]));
      } else {
        throw new IllegalArgumentException("Unhandled type: " + type);
      }
    } else {
      throw new IllegalArgumentException("Cannot create type coercer for type: " + type);
    }
  }

  private <T extends Comparable<T>> TypeCoercer<T> typeCoercerForComparableType(Type type) {
    Preconditions.checkState(
        type instanceof Class && Comparable.class.isAssignableFrom((Class) type),
        "type '%s' should be a class implementing Comparable",
        type);

    @SuppressWarnings("unchecked")
    TypeCoercer<T> typeCoercer = (TypeCoercer<T>) typeCoercerForType(type);
    return typeCoercer;
  }

  private static Type getSingletonTypeParameter(ParameterizedType type) {
    Preconditions.checkState(type.getActualTypeArguments().length == 1,
        "expected type '%s' to have one parameter", type);
    return type.getActualTypeArguments()[0];
  }
}

