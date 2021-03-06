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

package com.facebook.buck.java.abi;

/**
 * Shared information between {@link AbiWriter} and its callers.
 */
public class AbiWriterProtocol {

  private AbiWriterProtocol() {}

  public static final String ABI_ANNOTATION_PROCESSOR_CLASS_NAME =
      "com.facebook.buck.java.abi.AbiWriter";

  public static final String PARAM_ABI_OUTPUT_FILE =
      "buck.output_abi_file";

  /**
   * The integrity of this value is verified by {@code com.facebook.buck.java.abi.AbiWriterTest}.
   */
  public static final String EMPTY_ABI_KEY = "da39a3ee5e6b4b0d3255bfef95601890afd80709";
}
