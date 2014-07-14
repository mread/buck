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

package com.facebook.buck.apple.clang;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystems;

public final class PrintHeaderMap {

  private PrintHeaderMap() {
  }

  private static void process(File file) throws IOException {
    try (FileInputStream inputStream = new FileInputStream(file)) {
      FileChannel fileChannel = inputStream.getChannel();
      ByteBuffer buffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, file.length());

      HeaderMap map = HeaderMap.deserialize(buffer);
      if (map == null) {
        throw new IOException("Error while parsing header map " + file.toString());
      }
      map.print(System.out);
    }
  }

  public static void main(String[] args) {
    if (args.length < 1) {
      System.out.println("Usage: java -jar hmaptool.jar HEADER-MAP-FILE(S)");
    }
    try {
      for (int i = 0; i < args.length; i++) {
        process(FileSystems.getDefault().getPath(args[i]).toFile());
      }
    } catch (IOException e) {
      System.err.println(e.toString());
      System.exit(-1);
    }
  }
}
