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

package com.facebook.buck.util;

import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.zip.CustomZipEntry;
import com.facebook.buck.zip.CustomZipOutputStream;
import com.facebook.buck.zip.ZipOutputStreams;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;
import com.google.common.io.InputSupplier;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.channels.Channels;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/**
 * An injectable service for interacting with the filesystem relative to the project root.
 */
public class ProjectFilesystem {

  /**
   * Controls the behavior of how the source should be treated when copying.
   */
  public enum CopySourceMode {
      /**
       * Copy the single source file into the destination path.
       */
      FILE,

      /**
       * Treat the source as a directory and copy each file inside it
       * to the destination path, which must be a directory.
       */
      DIRECTORY_CONTENTS_ONLY,

      /**
       * Treat the source as a directory. Copy the directory and its
       * contents to the destination path, which must be a directory.
       */
      DIRECTORY_AND_CONTENTS,
  }

  private final Path projectRoot;

  private final Function<Path, Path> pathAbsolutifier;

  private final ImmutableSet<Path> ignorePaths;

  // Defaults to false, and so paths should be valid.
  @VisibleForTesting
  protected boolean ignoreValidityOfPaths;

  /**
   * There should only be one {@link ProjectFilesystem} created per process.
   * <p>
   * When creating a {@code ProjectFilesystem} for a test, rather than create a filesystem with an
   * arbitrary argument for the project root, such as {@code new File(".")}, prefer the creation of
   * a mock filesystem via EasyMock instead. Note that there are cases (such as integration tests)
   * where specifying {@code new File(".")} as the project root might be the appropriate thing.
   */
  public ProjectFilesystem(Path projectRoot, ImmutableSet<Path> ignorePaths) {
    Preconditions.checkArgument(java.nio.file.Files.isDirectory(projectRoot));
    this.projectRoot = projectRoot;
    this.pathAbsolutifier = new Function<Path, Path>() {
      @Override
      public Path apply(Path path) {
        return resolve(path);
      }
    };
    this.ignorePaths = MorePaths.filterForSubpaths(ignorePaths, this.projectRoot);
    this.ignoreValidityOfPaths = false;
  }

  public ProjectFilesystem(Path projectRoot) {
    this(projectRoot, ImmutableSet.<Path>of());
  }

  /**
   * // @deprecated Prefer passing around {@code Path}s instead of {@code File}s or {@code String}s,
   *  replaced by {@link #ProjectFilesystem(java.nio.file.Path)}.
   */
  public ProjectFilesystem(File projectRoot) {
    this(projectRoot.toPath());
  }

  public Path getRootPath() {
    return projectRoot;
  }

  /**
   * @return the specified {@code path} resolved against {@link #getRootPath()} to an absolute path.
   */
  public Path resolve(Path path) {
    return projectRoot.resolve(path).toAbsolutePath().normalize();
  }

  /**
   * @return A {@link Function} that applies {@link #resolve(Path)} to its parameter.
   */
  public Function<Path, Path> getAbsolutifier() {
    return pathAbsolutifier;
  }

  /**
   * // @deprecated Prefer operating on {@code Path}s directly, replaced by {@link #getRootPath()}.
   */
  public File getProjectRoot() {
    return projectRoot.toFile();
  }

  /**
   * @return A {@link ImmutableSet} of {@link Path} objects to have buck ignore.  All paths will be
   *     relative to the {@link ProjectFilesystem#getRootPath()}.
   */
  public ImmutableSet<Path> getIgnorePaths() {
    return ignorePaths;
  }

  /**
   * // @deprecated Prefer operating on {@code Path}s directly, replaced by
   *    {@link #getPathForRelativePath(java.nio.file.Path)}.
   */
  public File getFileForRelativePath(String pathRelativeToProjectRoot) {
    return pathRelativeToProjectRoot.isEmpty()
        ? projectRoot.toFile()
        : getPathForRelativePath(Paths.get(pathRelativeToProjectRoot)).toFile();
  }

  /**
   * // @deprecated Prefer operating on {@code Path}s directly, replaced by
   *    {@link #getPathForRelativePath(java.nio.file.Path)}.
   */
  public File getFileForRelativePath(Path pathRelativeToProjectRoot) {
    return getPathForRelativePath(pathRelativeToProjectRoot).toFile();
  }

  public Path getPathForRelativePath(Path pathRelativeToProjectRoot) {
    return projectRoot.resolve(pathRelativeToProjectRoot);
  }

  /**
   * As {@link #getFileForRelativePath(java.nio.file.Path)}, but with the added twist that the
   * existence of the path is checked before returning.
   */
  public Path getPathForRelativeExistingPath(Path pathRelativeToProjectRoot) {
    Path file = getPathForRelativePath(pathRelativeToProjectRoot);

    if (ignoreValidityOfPaths) {
      return file;
    }

    // TODO(mbolin): Eliminate this temporary exemption for symbolic links.
    if (java.nio.file.Files.isSymbolicLink(file)) {
      return file;
    }

    if (!java.nio.file.Files.exists(file)) {
      throw new RuntimeException(
          String.format("Not an ordinary file: '%s'.", pathRelativeToProjectRoot));
    }

    return file;
  }

  public boolean exists(Path pathRelativeToProjectRoot) {
    return java.nio.file.Files.exists(getPathForRelativePath(pathRelativeToProjectRoot));
  }

  public long getFileSize(Path pathRelativeToProjectRoot) throws IOException {
    Path path = getPathForRelativePath(pathRelativeToProjectRoot);
    if (!java.nio.file.Files.isRegularFile(path)) {
      throw new IOException("Cannot get size of " + path + " because it is not an ordinary file.");
    }
    return java.nio.file.Files.size(path);
  }

  /**
   * Deletes a file specified by its path relative to the project root.
   * @param pathRelativeToProjectRoot path to the file
   * @return true if the file was successfully deleted, false otherwise
   */
  public boolean deleteFileAtPath(Path pathRelativeToProjectRoot) {
    try {
      java.nio.file.Files.delete(getPathForRelativePath(pathRelativeToProjectRoot));
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  public Properties readPropertiesFile(Path pathToPropertiesFileRelativeToProjectRoot)
      throws IOException {
    Properties properties = new Properties();
    Path propertiesFile = getPathForRelativePath(pathToPropertiesFileRelativeToProjectRoot);
    if (java.nio.file.Files.exists(propertiesFile)) {
      properties.load(java.nio.file.Files.newBufferedReader(propertiesFile, Charsets.UTF_8));
      return properties;
    } else {
      throw new FileNotFoundException(propertiesFile.toString());
    }
  }

  /**
   * Checks whether there is a normal file at the specified path.
   */
  public boolean isFile(Path pathRelativeToProjectRoot) {
    return java.nio.file.Files.isRegularFile(
        getPathForRelativePath(pathRelativeToProjectRoot));
  }

  /**
   * Similar to {@link #walkFileTree(Path, FileVisitor)} except this takes in a path relative to
   * the project root.
   */
  public void walkRelativeFileTree(
      Path pathRelativeToProjectRoot,
      final FileVisitor<Path> fileVisitor) throws IOException {
    walkRelativeFileTree(pathRelativeToProjectRoot,
        EnumSet.of(FileVisitOption.FOLLOW_LINKS),
        fileVisitor);
  }

  private void walkRelativeFileTree(
      Path pathRelativeToProjectRoot,
      EnumSet<FileVisitOption> visitOptions,
      final FileVisitor<Path> fileVisitor) throws IOException {
    Path rootPath = getPathForRelativePath(pathRelativeToProjectRoot);
    java.nio.file.Files.walkFileTree(
        rootPath,
        visitOptions,
        Integer.MAX_VALUE,
        new FileVisitor<Path>() {
          @Override
          public FileVisitResult preVisitDirectory(
              Path dir, BasicFileAttributes attrs) throws IOException {
            return fileVisitor.preVisitDirectory(projectRoot.relativize(dir), attrs);
          }

          @Override
          public FileVisitResult visitFile(
              Path file, BasicFileAttributes attrs) throws IOException {
            return fileVisitor.visitFile(projectRoot.relativize(file), attrs);
          }

          @Override
          public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
            return fileVisitor.visitFileFailed(projectRoot.relativize(file), exc);
          }

          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
            return fileVisitor.postVisitDirectory(projectRoot.relativize(dir), exc);
          }
        });
  }

  /**
   * Allows {@link java.nio.file.Files#walkFileTree} to be faked in tests.
   */
  public void walkFileTree(Path root, FileVisitor<Path> fileVisitor) throws IOException {
    java.nio.file.Files.walkFileTree(root, fileVisitor);
  }

  public Set<Path> getFilesUnderPath(Path pathRelativeToProjectRoot) throws IOException {
    return getFilesUnderPath(pathRelativeToProjectRoot, Predicates.<Path>alwaysTrue());
  }

  public Set<Path> getFilesUnderPath(
      Path pathRelativeToProjectRoot,
      Predicate<Path> predicate) throws IOException {
    return getFilesUnderPath(
        pathRelativeToProjectRoot,
        predicate,
        EnumSet.of(FileVisitOption.FOLLOW_LINKS));
  }

  public Set<Path> getFilesUnderPath(
      Path pathRelativeToProjectRoot,
      final Predicate<Path> predicate,
      EnumSet<FileVisitOption> visitOptions) throws IOException {
    final ImmutableSet.Builder<Path> paths = ImmutableSet.builder();
    walkRelativeFileTree(
        getPathForRelativePath(pathRelativeToProjectRoot),
        visitOptions,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) {
            if (predicate.apply(path)) {
              paths.add(path);
            }
            return FileVisitResult.CONTINUE;
          }
        });
    return paths.build();
  }

  /**
   * Allows {@link java.nio.file.Files#isDirectory} to be faked in tests.
   */
  public boolean isDirectory(Path child, LinkOption... linkOptions) {
    return java.nio.file.Files.isDirectory(child, linkOptions);
  }

  /**
   * Allows {@link java.io.File#listFiles} to be faked in tests.
   *
   * // @deprecated Replaced by {@link #getDirectoryContents}
   */
  public File[] listFiles(Path pathRelativeToProjectRoot) {
    Collection<Path> paths = getDirectoryContents(pathRelativeToProjectRoot);
    if (paths == null) {
      return null;
    }

    File[] result = new File[paths.size()];
    return Collections2.transform(paths, new Function<Path, File>() {
      @Override
      public File apply(Path input) {
        return input.toFile();
      }
    }).toArray(result);
  }

  public Collection<Path> getDirectoryContents(Path pathRelativeToProjectRoot) {
    Path path = getPathForRelativePath(pathRelativeToProjectRoot);
    try (DirectoryStream<Path> stream = java.nio.file.Files.newDirectoryStream(path)) {
      return ImmutableList.copyOf(stream);
    } catch (IOException e) {
      return null;
    }
  }

  public long getLastModifiedTime(Path pathRelativeToProjectRoot) throws IOException {
    Path path = getPathForRelativePath(pathRelativeToProjectRoot);
    return java.nio.file.Files.getLastModifiedTime(path).toMillis();
  }

  /**
   * Recursively delete everything under the specified path.
   */
  public void rmdir(Path pathRelativeToProjectRoot) throws IOException {
    MoreFiles.rmdir(resolve(pathRelativeToProjectRoot));
  }

  /**
   * Resolves the relative path against the project root and then calls
   * {@link java.nio.file.Files#createDirectories(java.nio.file.Path,
   *            java.nio.file.attribute.FileAttribute[])}
   */
  public void mkdirs(Path pathRelativeToProjectRoot) throws IOException {
    java.nio.file.Files.createDirectories(resolve(pathRelativeToProjectRoot));
  }

  /**
   * // @deprecated Prefer operating on {@code Path}s directly, replaced by
   *  {@link #createParentDirs(java.nio.file.Path)}.
   */
  public void createParentDirs(String pathRelativeToProjectRoot) throws IOException {
    Path file = getPathForRelativePath(Paths.get(pathRelativeToProjectRoot));
    mkdirs(file.getParent());
  }

  /**
   * @param pathRelativeToProjectRoot Must identify a file, not a directory. (Unfortunately, we have
   *     no way to assert this because the path is not expected to exist yet.)
   */
  public void createParentDirs(Path pathRelativeToProjectRoot) throws IOException {
    Path file = resolve(pathRelativeToProjectRoot);
    Path directory = file.getParent();
    mkdirs(directory);
  }

  /**
   * Writes each line in {@code lines} with a trailing newline to a file at the specified path.
   * <p>
   * The parent path of {@code pathRelativeToProjectRoot} must exist.
   */
  public void writeLinesToPath(
      Iterable<String> lines,
      Path pathRelativeToProjectRoot,
      FileAttribute<?>... attrs)
      throws IOException {
    try (Writer writer =
         new BufferedWriter(
             new OutputStreamWriter(newFileOutputStream(pathRelativeToProjectRoot, attrs)))) {
      for (String line : lines) {
        writer.write(line);
        writer.write('\n');
      }
    }
  }

  public void writeContentsToPath(
      String contents,
      Path pathRelativeToProjectRoot,
      FileAttribute<?>... attrs)
      throws IOException {
    writeBytesToPath(contents.getBytes(Charsets.UTF_8), pathRelativeToProjectRoot, attrs);
  }

  public void writeBytesToPath(
      byte[] bytes,
      Path pathRelativeToProjectRoot,
      FileAttribute<?>... attrs) throws IOException {
    try (OutputStream outputStream = newFileOutputStream(pathRelativeToProjectRoot, attrs)) {
      outputStream.write(bytes);
    }
  }

  public OutputStream newFileOutputStream(
      Path pathRelativeToProjectRoot,
      FileAttribute<?>... attrs)
    throws IOException {
    return new BufferedOutputStream(
        Channels.newOutputStream(
            java.nio.file.Files.newByteChannel(
                getPathForRelativePath(pathRelativeToProjectRoot),
                ImmutableSet.<OpenOption>of(
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE),
                attrs)));
  }

  public <A extends BasicFileAttributes> A readAttributes(
      Path pathRelativeToProjectRoot,
      Class<A> type,
      LinkOption... options)
    throws IOException {
    return java.nio.file.Files.readAttributes(
        getPathForRelativePath(pathRelativeToProjectRoot), type, options);
  }

  public InputStream newFileInputStream(Path pathRelativeToProjectRoot)
    throws IOException {
    return new BufferedInputStream(
        java.nio.file.Files.newInputStream(getPathForRelativePath(pathRelativeToProjectRoot)));
  }

  /**
   * @param inputStream Source of the bytes. This method does not close this stream.
   */
  public void copyToPath(final InputStream inputStream, Path pathRelativeToProjectRoot)
      throws IOException {
    java.nio.file.Files.copy(inputStream, getPathForRelativePath(pathRelativeToProjectRoot));
  }

  public Optional<String> readFileIfItExists(Path pathRelativeToProjectRoot) {
    Path fileToRead = getPathForRelativePath(pathRelativeToProjectRoot);
    return readFileIfItExists(fileToRead, pathRelativeToProjectRoot.toString());
  }

  private Optional<String> readFileIfItExists(Path fileToRead, String pathRelativeToProjectRoot) {
    if (java.nio.file.Files.isRegularFile(fileToRead)) {
      String contents;
      try {
        contents = new String(java.nio.file.Files.readAllBytes(fileToRead), Charsets.UTF_8);
      } catch (IOException e) {
        // Alternatively, we could return Optional.absent(), though something seems suspicious if we
        // have already verified that fileToRead is a file and then we cannot read it.
        throw new RuntimeException("Error reading " + pathRelativeToProjectRoot, e);
      }
      return Optional.of(contents);
    } else {
      return Optional.absent();
    }
  }

  /**
   * Attempts to open the file for future read access. Returns {@link Optional#absent()} if the file
   * does not exist.
   */
  public Optional<Reader> getReaderIfFileExists(Path pathRelativeToProjectRoot) {
    Path fileToRead = getPathForRelativePath(pathRelativeToProjectRoot);
    if (java.nio.file.Files.isRegularFile(fileToRead)) {
      try {
        return Optional.of(
            (Reader) new BufferedReader(
                new InputStreamReader(newFileInputStream(pathRelativeToProjectRoot))));
      } catch (Exception e) {
        throw new RuntimeException("Error reading " + pathRelativeToProjectRoot, e);
      }
    } else {
      return Optional.absent();
    }
  }

  /**
   * Attempts to read the first line of the file specified by the relative path. If the file does
   * not exist, is empty, or encounters an error while being read, {@link Optional#absent()} is
   * returned. Otherwise, an {@link Optional} with the first line of the file will be returned.
   *
   * // @deprecated PRefero operation on {@code Path}s directly, replaced by
   *  {@link #readFirstLine(java.nio.file.Path)}
   */
  public Optional<String> readFirstLine(String pathRelativeToProjectRoot) {
    return readFirstLine(Paths.get(pathRelativeToProjectRoot));
  }

  /**
   * Attempts to read the first line of the file specified by the relative path. If the file does
   * not exist, is empty, or encounters an error while being read, {@link Optional#absent()} is
   * returned. Otherwise, an {@link Optional} with the first line of the file will be returned.
   */
  public Optional<String> readFirstLine(Path pathRelativeToProjectRoot) {
    Preconditions.checkNotNull(pathRelativeToProjectRoot);
    Path file = getPathForRelativePath(pathRelativeToProjectRoot);
    return readFirstLineFromFile(file);
  }

  /**
   * Attempts to read the first line of the specified file. If the file does not exist, is empty,
   * or encounters an error while being read, {@link Optional#absent()} is returned. Otherwise, an
   * {@link Optional} with the first line of the file will be returned.
   */
  public Optional<String> readFirstLineFromFile(Path file) {
    try {
      return Optional.fromNullable(
          java.nio.file.Files.newBufferedReader(file, Charsets.UTF_8).readLine());
    } catch (IOException e) {
      // Because the file is not even guaranteed to exist, swallow the IOException.
      return Optional.absent();
    }
  }

  public List<String> readLines(Path pathRelativeToProjectRoot) throws IOException {
    Path file = getPathForRelativePath(pathRelativeToProjectRoot);
    return java.nio.file.Files.readAllLines(file, Charsets.UTF_8);
  }

  /**
   * // @deprecated Prefer operation on {@code Path}s directly, replaced by
   *  {@link java.nio.file.Files#newInputStream(java.nio.file.Path, java.nio.file.OpenOption...)}.
   */
  public InputSupplier<? extends InputStream> getInputSupplierForRelativePath(Path path) {
    Path file = getPathForRelativePath(path);
    return Files.newInputStreamSupplier(file.toFile());
  }

  public String computeSha1(Path pathRelativeToProjectRoot) throws IOException {
    Path fileToHash = getPathForRelativePath(pathRelativeToProjectRoot);
    return Hashing.sha1().hashBytes(java.nio.file.Files.readAllBytes(fileToHash)).toString();
  }

  /**
   * @param event The event to be tested.
   * @return true if event is a path change notification.
   */
  public boolean isPathChangeEvent(WatchEvent<?> event) {
    return event.kind() == StandardWatchEventKinds.ENTRY_CREATE ||
        event.kind() == StandardWatchEventKinds.ENTRY_MODIFY ||
        event.kind() == StandardWatchEventKinds.ENTRY_DELETE;
  }

  public void copy(Path source, Path target, CopySourceMode sourceMode) throws IOException {
    switch (sourceMode) {
      case FILE:
        java.nio.file.Files.copy(
            resolve(source),
            resolve(target),
            StandardCopyOption.REPLACE_EXISTING);
        break;
      case DIRECTORY_CONTENTS_ONLY:
        MoreFiles.copyRecursively(resolve(source), resolve(target));
        break;
      case DIRECTORY_AND_CONTENTS:
        MoreFiles.copyRecursively(resolve(source), resolve(target.resolve(source.getFileName())));
        break;
    }
  }

  public void move(Path source, Path target, CopyOption... options) throws IOException {
    java.nio.file.Files.move(resolve(source), resolve(target), options);

  }

  public void copyFolder(Path source, Path target) throws IOException {
    copy(source, target, CopySourceMode.DIRECTORY_CONTENTS_ONLY);
  }

  public void copyFile(Path source, Path target) throws IOException {
    copy(source, target, CopySourceMode.FILE);
  }

  public void createSymLink(Path sourcePath, Path targetPath, boolean force)
      throws IOException {
    if (force) {
      java.nio.file.Files.deleteIfExists(targetPath);
    }
    if (Platform.detect() == Platform.WINDOWS) {
      if (isDirectory(sourcePath)) {
        // Creating symlinks to directories on Windows requires escalated privileges. We're just
        // going to have to copy things recursively.
        MoreFiles.copyRecursively(sourcePath, targetPath);
      } else {
        java.nio.file.Files.createLink(targetPath, sourcePath);
      }
    } else {
      java.nio.file.Files.createSymbolicLink(targetPath, sourcePath);
    }
  }

  /**
   * Takes a sequence of paths relative to the project root and writes a zip file to {@code out}
   * with the contents and structure that matches that of the specified paths.
   */
  public void createZip(Iterable<Path> pathsToIncludeInZip, File out) throws IOException {
    Preconditions.checkState(!Iterables.isEmpty(pathsToIncludeInZip));
    try (CustomZipOutputStream zip = ZipOutputStreams.newOutputStream(out)) {
      for (Path path : pathsToIncludeInZip) {
        CustomZipEntry entry = new CustomZipEntry(path.toString());

        // Support executable files.  If we detect this file is executable, store this
        // information as 0100 in the field typically used in zip implementations for
        // POSIX file permissions.  We'll use this information when unzipping.
        Path full = getPathForRelativePath(path);
        File file = full.toFile();
        if (file.canExecute()) {
          entry.setExternalAttributes(MorePosixFilePermissions.toMode(
              EnumSet.of(PosixFilePermission.OWNER_EXECUTE)) << 16);
        }

        zip.putNextEntry(entry);
        try (InputStream input = java.nio.file.Files.newInputStream(getPathForRelativePath(path))) {
          ByteStreams.copy(input, zip);
        }
        zip.closeEntry();
      }
    }
  }

  /**
   *
   * @param event the event to format.
   * @return the formatted event context string.
   */
  public String createContextString(WatchEvent<?> event) {
    if (isPathChangeEvent(event)) {
      Path path = (Path) event.context();
      return path.toAbsolutePath().normalize().toString();
    }
    return String.valueOf(event.context());
  }


  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }

    if (!(other instanceof ProjectFilesystem)) {
      return false;
    }

    ProjectFilesystem that = (ProjectFilesystem) other;

    return Objects.equals(projectRoot, that.projectRoot) &&
        Objects.equals(ignorePaths, that.ignorePaths);
  }

  @Override
  public int hashCode() {
    return Objects.hash(projectRoot, ignorePaths);
  }

}
