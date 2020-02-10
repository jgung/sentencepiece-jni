/* Copyright 2017 The TensorFlow Authors. All Rights Reserved.

Modifications copyright (C) 2020 James Gung.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at
    http://www.apache.org/licenses/LICENSE-2.0
Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
==============================================================================*/

package com.github.google.sentencepiece;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Helper class for loading the SentencePiece Java native library, shamelessly following TensorFlow's
 * Java native library loader.
 *
 * <p>The Java SentencePiece bindings require a native (JNI) library. This library
 * (libsentencepiece_jni.so on Linux, libsentencepiece_jni.dylib on OS X, sentencepiece_jni.dll on Windows)
 * can be made available to the JVM using the java.library.path System property (e.g., using
 * -Djava.library.path command-line argument). However, doing so requires an additional step of
 * configuration.
 *
 * <p>Alternatively, the native libraries can be packaed in a .jar, making them easily usable from
 * build systems like Maven. However, in such cases, the native library has to be extracted from the
 * .jar archive.
 *
 * <p>NativeLibrary.load() takes care of this. First looking for the library in java.library.path
 * and failing that, it tries to find the OS and architecture specific version of the library in the
 * set of ClassLoader resources (under com/github/google/sentencepiece/native/OS-ARCH). The resources paths used for
 * lookup must be consistent with any packaging (such as on Maven Central) of the SentencePiece Java
 * native libraries.
 */
final class NativeLibrary {
  private static final boolean DEBUG =
      System.getProperty("com.github.google.sentencepiece.NativeLibrary.DEBUG") != null;
  private static final String JNI_LIBNAME = "sentencepiece_jni";

  public static void load() {
    if (isLoaded() || tryLoadLibrary()) {
      // Doesn't matter how, but it seems the native code is loaded, so nothing else to do.
      return;
    }
    // Native code is not present, perhaps it has been packaged into the .jar file containing this.
    // Extract the JNI library itself
    final String jniLibName = System.mapLibraryName(JNI_LIBNAME);
    final String jniResourceName = makeResourceName(jniLibName);
    log("jniResourceName: " + jniResourceName);
    final InputStream jniResource =
        NativeLibrary.class.getClassLoader().getResourceAsStream(jniResourceName);
    if (jniResource == null) {
      throw new UnsatisfiedLinkError(
          String.format(
              "Cannot find SentencePiece native library for OS: %s, architecture: %s. Additional"
                  + " information on attempts to find the native library can be obtained by adding"
                  + " com.github.google.sentencepiece.NativeLibrary.DEBUG=1 to the system properties of the JVM.",
              os(), architecture()));
    }
    try {
      // Create a temporary directory for the extracted resource and its dependencies.
      final File tempPath = createTemporaryDirectory();
      // Deletions are in the reverse order of requests, so we need to request that the directory be
      // deleted first, so that it is empty when the request is fulfilled.
      tempPath.deleteOnExit();
      final String tempDirectory = tempPath.getCanonicalPath();
      System.load(extractResource(jniResource, jniLibName, tempDirectory));
    } catch (IOException e) {
      throw new UnsatisfiedLinkError(
          String.format(
              "Unable to extract native library into a temporary file (%s)", e.toString()));
    }
  }

  private static boolean tryLoadLibrary() {
    try {
      System.loadLibrary(JNI_LIBNAME);
      return true;
    } catch (UnsatisfiedLinkError e) {
      log("tryLoadLibraryFailed: " + e.getMessage());
      return false;
    }
  }

  private static boolean isLoaded() {
    try {
      SentencePieceJNI.sppDtor(SentencePieceJNI.sppCtor());
      log("isLoaded: true");
      return true;
    } catch (UnsatisfiedLinkError e) {
      return false;
    }
  }

  private static String extractResource(
      InputStream resource, String resourceName, String extractToDirectory) throws IOException {
    final File dst = new File(extractToDirectory, resourceName);
    dst.deleteOnExit();
    final String dstPath = dst.toString();
    log("extracting native library to: " + dstPath);
    final long nbytes = copy(resource, dst);
    log(String.format("copied %d bytes to %s", nbytes, dstPath));
    return dstPath;
  }

  private static String os() {
    final String p = System.getProperty("os.name").toLowerCase();
    if (p.contains("linux")) {
      return "linux";
    } else if (p.contains("os x") || p.contains("darwin")) {
      return "darwin";
    } else if (p.contains("windows")) {
      return "windows";
    } else {
      return p.replaceAll("\\s", "");
    }
  }

  private static String architecture() {
    final String arch = System.getProperty("os.arch").toLowerCase();
    return (arch.equals("amd64")) ? "x86_64" : arch;
  }

  private static void log(String msg) {
    if (DEBUG) {
      System.err.println("com.github.google.sentencepiece.NativeLibrary: " + msg);
    }
  }

  private static String makeResourceName(String baseName) {
    return "com/github/google/sentencepiece/native/" + String.format("%s-%s/", os(), architecture()) + baseName;
  }

  private static long copy(InputStream src, File dstFile) throws IOException {
    FileOutputStream dst = new FileOutputStream(dstFile);
    try {
      byte[] buffer = new byte[1 << 20]; // 1MB
      long ret = 0;
      int n;
      while ((n = src.read(buffer)) >= 0) {
        dst.write(buffer, 0, n);
        ret += n;
      }
      return ret;
    } finally {
      dst.close();
      src.close();
    }
  }

  // Shamelessly adapted from Guava to avoid using java.nio, for Android API
  // compatibility.
  private static File createTemporaryDirectory() {
    File baseDirectory = new File(System.getProperty("java.io.tmpdir"));
    String directoryName = "sentencepiece_native_libraries-" + System.currentTimeMillis() + "-";
    for (int attempt = 0; attempt < 1000; attempt++) {
      File temporaryDirectory = new File(baseDirectory, directoryName + attempt);
      if (temporaryDirectory.mkdir()) {
        return temporaryDirectory;
      }
    }
    throw new IllegalStateException(
        "Could not create a temporary directory (tried to make "
            + directoryName
            + "*) to extract SentencePiece native libraries.");
  }

  private NativeLibrary() {}

}