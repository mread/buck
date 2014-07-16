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

package com.facebook.buck.util;

import com.facebook.buck.log.Logger;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Throwables;
import com.google.common.eventbus.EventBus;
import com.google.common.io.ByteStreams;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.util.UUID;

/**
 * A ProjectFilesystemWatcher implementation that uses a local watchman service.
 */
public class WatchmanWatcher implements ProjectFilesystemWatcher {

  private static final Logger LOG = Logger.get(WatchmanWatcher.class);
  private static final int DEFAULT_OVERFLOW_THRESHOLD = 200;

  private final Supplier<Process> watchmanProcessSupplier;
  private final EventBus eventBus;
  private final JsonFactory jsonFactory;
  private final String query;

  /**
   * The maximum number of watchman changes to process in each call to postEvents before
   * giving up and generating an overflow. The goal is to be able to process a reasonable
   * number of human generated changes quickly, but not spend a long time processing lots
   * of changes after a branch switch which will end up invalidating the entire cache
   * anyway. If overflow is negative calls to postEvents will just generate a single
   * overflow event.
   */
  private final int overflow;

  public WatchmanWatcher(ProjectFilesystem filesystem,
                         EventBus fileChangeEventBus) {
    this(createProcessSupplier(),
        fileChangeEventBus,
        DEFAULT_OVERFLOW_THRESHOLD,
        createQuery(filesystem));
  }

  @VisibleForTesting
  WatchmanWatcher(Supplier<Process> processSupplier,
                  EventBus fileChangeEventBus,
                  int overflow,
                  String query) {
    this.watchmanProcessSupplier = Preconditions.checkNotNull(processSupplier);
    this.eventBus = Preconditions.checkNotNull(fileChangeEventBus);
    this.jsonFactory = new JsonFactory();
    this.overflow = overflow;
    this.query = Preconditions.checkNotNull(query);
  }

  private static String createQuery(ProjectFilesystem filesystem) {
    return "[\"query\", \"" +
        MorePaths.absolutify(filesystem.getRootPath()).toString() +
        "\", {\"since\": \"n:buckd" +
        UUID.randomUUID() +
        "\", \"empty_on_fresh_instance\": true, \"fields\": [\"name\", \"exists\", \"new\"]}]";
  }

  private static Supplier<Process> createProcessSupplier() {
    final ProcessBuilder processBuilder = new ProcessBuilder(
        "watchman",
        "--server-encoding=json",
        "--no-pretty",
        "-j");

    return new Supplier<Process>() {
      @Override
      public Process get() {
        try {
          LOG.debug("Starting watchman command: %s", processBuilder.command());
          return processBuilder.start();
        } catch (IOException e) {
          throw Throwables.propagate(e);
        }
      }
    };
  }

  /**
   * Query Watchman for file change events. If too many events are pending or an error occurs
   * an overflow event is posted to the EventBus signalling that events may have been lost
   * (and so typically caches must be cleared to avoid inconsistency). Interruptions and
   * IOExceptions are propagated to callers, but typically if overflow events are handled
   * conservatively by subscribers then no other remedial action is required.
   */
  @Override
  public void postEvents() throws IOException, InterruptedException {
    Process watchmanProcess = watchmanProcessSupplier.get();
    try {
      LOG.debug("Writing query to Watchman: %s", query);
      watchmanProcess.getOutputStream().write(query.getBytes(Charsets.US_ASCII));
      watchmanProcess.getOutputStream().close();
      LOG.debug("Parsing JSON output from Watchman");
      JsonParser jsonParser = jsonFactory.createJsonParser(watchmanProcess.getInputStream());
      PathEventBuilder builder = new PathEventBuilder();
      JsonToken token = jsonParser.nextToken();
      /*
       * Watchman returns changes as an array of JSON objects with potentially unstable key
       * ordering:
       * {
       *     "files": [
       *     {
       *         "new": false,
       *         "exists": true,
       *         "name": "bin/buckd",
       *     },
       *     ]
       * }
       * A simple way to parse these changes is to collect the relevant values from each object
       * in a builder and then build an event when the end of a JSON object is reached. When the end
       * of the enclosing JSON object is processed the builder will not contain a complete event, so
       * the object end token will be ignored.
       */
      int eventCount = 0;
      while (token != null) {
        if (eventCount > overflow) {
          eventBus.post(createOverflowEvent());
          watchmanProcess.destroy();
          return;
        }
        switch (token) {
          case FIELD_NAME:
            String fieldName = jsonParser.getCurrentName();
            switch (fieldName) {
              case "name":
                File file = new File(jsonParser.nextTextValue());
                if (!file.isDirectory()) {
                  builder.setPath(file.toPath());
                }
                break;
              case "new":
                if (jsonParser.nextBooleanValue()) {
                  builder.setCreationEvent();
                }
                break;
              case "exists":
                if (!jsonParser.nextBooleanValue()) {
                  builder.setDeletionEvent();
                }
                break;
              case "error":
                WatchmanWatcherException e = new WatchmanWatcherException(
                    jsonParser.nextTextValue());
                LOG.error(e, "Error in Watchman output");
                throw e;
            }
            break;
          case END_OBJECT:
            if (builder.canBuild()) {
              eventBus.post(builder.build());
              ++eventCount;
            }
            builder = new PathEventBuilder();
            break;
          // $CASES-OMITTED$
          default:
            break;
        }
        token = jsonParser.nextToken();
      }
      int watchmanExitCode;
      LOG.debug("Posted %d Watchman events. Waiting for subprocess to exit...", eventCount);
      watchmanExitCode = watchmanProcess.waitFor();
      if (watchmanExitCode != 0) {
        LOG.error("Watchman exited with error code %d", watchmanExitCode);
        eventBus.post(createOverflowEvent()); // Events may have been lost, signal overflow.
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ByteStreams.copy(watchmanProcess.getErrorStream(), buffer);
        throw new WatchmanWatcherException(
            "Watchman failed with exit code " + watchmanExitCode + ": " + buffer.toString());
      } else {
        LOG.debug("Watchman exited cleanly.");
      }
    } catch (InterruptedException e) {
      LOG.warn(e, "Killing Watchman process on interrupted exception");
      eventBus.post(createOverflowEvent()); // Events may have been lost, signal overflow.
      watchmanProcess.destroy();
      Thread.currentThread().interrupt();
    } catch (IOException e) {
      LOG.error(e, "Killing Watchman process on I/O exception");
      eventBus.post(createOverflowEvent()); // Events may have been lost, signal overflow.
      watchmanProcess.destroy();
      throw e;
    }
  }

  private WatchEvent<Object> createOverflowEvent() {
    return new WatchEvent<Object>() {

      @Override
      public Kind<Object> kind() {
        return StandardWatchEventKinds.OVERFLOW;
      }

      @Override
      public int count() {
        return 1;
      }

      @Override
      public Object context() {
        return null;
      }
    };
  }

  @Override
  public void close() throws IOException {
  }

  private static class PathEventBuilder {

    private WatchEvent.Kind<Path> kind;
    private Path path;

    PathEventBuilder() {
      this.kind = StandardWatchEventKinds.ENTRY_MODIFY;
    }

    public void setCreationEvent() {
      if (kind != StandardWatchEventKinds.ENTRY_DELETE) {
        kind = StandardWatchEventKinds.ENTRY_CREATE;
      }
    }

    public void setDeletionEvent() {
      kind = StandardWatchEventKinds.ENTRY_DELETE;
    }

    public void setPath(Path path) {
      this.path = path;
    }

    public WatchEvent<Path> build() {
      Preconditions.checkNotNull(path);
      return new WatchEvent<Path>() {
        @Override
        public Kind<Path> kind() {
          return kind;
        }

        @Override
        public int count() {
          return 1;
        }

        @Override
        public Path context() {
          return path;
        }
      };
    }

    public boolean canBuild() {
      return path != null;
    }
  }

  /**
   * @return true if "watchman --version" can be executed successfully
   */
  public static boolean isWatchmanAvailable() throws InterruptedException {
    try {
      LOG.debug("Checking if Watchman is available..");
      boolean available = new ProcessBuilder("watchman", "--version").start().waitFor() == 0;
      LOG.debug("Watchman available: %d", available);
      return available;
    } catch (IOException e) {
      LOG.error(e, "Could not check if Watchman is available");
      return false; // Could not execute watchman.
    }
  }
}
