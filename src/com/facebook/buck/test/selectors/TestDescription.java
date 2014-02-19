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

package com.facebook.buck.test.selectors;

/**
 * A non-JUnit specific way of describing a test-method inside a test-class.  This meants that the
 * test-selectors code does not need a dependency on JUnit.
 */
public class TestDescription {

   private final String className;
   private final String methodName;

   public TestDescription(String className, String methodName) {
     this.className = className;
     this.methodName = methodName;
   }

   public String getClassName() {
     return className;
   }

   public String getMethodName() {
     return methodName;
   }
 }
