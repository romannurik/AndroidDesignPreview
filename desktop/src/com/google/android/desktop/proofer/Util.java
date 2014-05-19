/*
 * Copyright 2012 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.desktop.proofer;

import java.awt.*;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;

import javax.imageio.ImageIO;

public class Util {
    public static boolean isDebug() {
        return "1".equals(System.getenv("PROOFER_DEBUG"));
    }

    public static boolean extractResource(String path, File to) {
        try {
            InputStream in = Util.class.getClassLoader().getResourceAsStream(path);
            if (in == null) {
                throw new FileNotFoundException(path);
            }

            OutputStream out = new FileOutputStream(to);

            byte[] buf = new byte[1024];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }

            in.close();
            out.close();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            return false;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    public static Image[] getAppIconMipmap() {
        try {
            return new Image[]{
                    ImageIO.read(
                            Util.class.getClassLoader().getResourceAsStream("assets/icon_16.png")),
                    ImageIO.read(
                            Util.class.getClassLoader().getResourceAsStream("assets/icon_32.png")),
                    ImageIO.read(
                            Util.class.getClassLoader().getResourceAsStream("assets/icon_128.png")),
                    ImageIO.read(
                            Util.class.getClassLoader().getResourceAsStream("assets/icon_512.png")),
            };
        } catch (IOException e) {
            return new Image[]{};
        }
    }

    // Cache directory code

    private static File cacheDirectory;

    static {
        // Determine/create cache directory

        // Default to root in user's home directory
        File rootDir = new File(System.getProperty("user.home"));
        if (!rootDir.exists() && rootDir.canWrite()) {
            // If not writable, root in current working directory
            rootDir = new File(System.getProperty("user.dir"));
            if (!rootDir.exists() && rootDir.canWrite()) {
                // TODO: create temporary directory somewhere if this fails
                System.err.println("No home directory and can't write to current directory.");
                System.exit(1);
            }
        }

        // The actual cache directory will be ${ROOT}/.android/proofer
        cacheDirectory = new File(new File(rootDir, ".android"), "proofer");
        if (!cacheDirectory.exists()) {
            cacheDirectory.mkdirs();
        }
        cacheDirectory.setWritable(true);
    }

    public static int getCacheVersion() {
        try {
            InputStream in = new FileInputStream(new File(getCacheDirectory(), "cache_version"));
            BufferedReader reader = new BufferedReader(new InputStreamReader(in));
            return Integer.parseInt(reader.readLine());
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (NumberFormatException e) {
            e.printStackTrace();
        }

        return 0;
    }

    public static void putCacheVersion(int version) {
        try {
            OutputStream out = new FileOutputStream(new File(getCacheDirectory(), "cache_version"));
            out.write(Integer.toString(version).getBytes());
            out.close();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static File getCacheDirectory() {
        return cacheDirectory;
    }

    // Which OS are we running on? (used to unpack different ADB binaries)

    public enum OS {
        Windows("windows"),
        Linux("linux"),
        Mac("mac"),
        Other(null);

        public String id;
        
        OS(String id) {
            this.id = id;
        }
    }

    private static OS currentOS;

    static {
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (osName.contains("windows")) {
            currentOS = OS.Windows;
        } else if (osName.contains("linux")) {
            currentOS = OS.Linux;
        } else if (osName.contains("mac") || osName.contains("darwin")) {
            currentOS = OS.Mac;
        } else {
            currentOS = OS.Other;
        }
    }

    public static OS getCurrentOS() {
        return currentOS;
    }
}
