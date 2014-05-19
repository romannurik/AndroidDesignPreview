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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AdbRunner {
    private static final int ADB_CACHE_VERSION = 1;

    private boolean debug = Util.isDebug();

    private File adbPath;
    private boolean ready = false;

    public AdbRunner() {
        try {
            prepareAdb();
        } catch (ProoferException e) {
            e.printStackTrace();
        }
    }

    private void prepareAdb() throws ProoferException {
        if (debug) {
            System.out.println("Preparing ADB");
        }

        int currentCacheVersion = Util.getCacheVersion();
        boolean forceExtract = currentCacheVersion < ADB_CACHE_VERSION;

        if (debug && forceExtract) {
            System.out.println("Current adb cache is old (version " + currentCacheVersion + "). "
                    + "Upgrading to cache version " + ADB_CACHE_VERSION);
        }

        Util.OS currentOS = Util.getCurrentOS();
        if (currentOS == Util.OS.Other) {
            throw new ProoferException("Unknown operating system, cannot run ADB.");
        }

        switch (currentOS) {
            case Mac:
            case Linux:
                adbPath = extractAssetToCacheDirectory(currentOS.id + "/adb", "adb", forceExtract);
                break;

            case Windows:
                adbPath = extractAssetToCacheDirectory("windows/adb.exe", "adb.exe", forceExtract);
                extractAssetToCacheDirectory("windows/AdbWinApi.dll", "AdbWinApi.dll",
                        forceExtract);
                extractAssetToCacheDirectory("windows/AdbWinUsbApi.dll", "AdbWinUsbApi.dll",
                        forceExtract);
                break;
        }

        if (!adbPath.setExecutable(true)) {
            throw new ProoferException("Error setting ADB binary as executable.");
        }

        Util.putCacheVersion(ADB_CACHE_VERSION);

        ready = true;
    }

    private File extractAssetToCacheDirectory(String assetPath, String filename, boolean force)
            throws ProoferException {
        File outFile = new File(Util.getCacheDirectory(), filename);
        if (force || !outFile.exists()) {
            if (!Util.extractResource("assets/" + assetPath, outFile)) {
                throw new ProoferException("Error extracting to " + outFile.toString());
            }
        }
        return outFile;
    }

    public String adb(String[] args) throws ProoferException {
        if (debug) {
            StringBuilder sb = new StringBuilder();
            sb.append("Calling ADB: adb");

            for (String arg : args) {
                sb.append(" ");
                sb.append(arg);
            }

            System.out.println(sb.toString());
        }

        List<String> argList = new ArrayList<String>();
        argList.add(0, adbPath.getAbsolutePath());
        //argList.add(1, "-e");
        Collections.addAll(argList, args);

        int returnCode;
        Runtime runtime = Runtime.getRuntime();
        Process pr;
        StringBuilder sb = new StringBuilder(0);

        try {
            pr = runtime.exec(argList.toArray(new String[argList.size()]));

            // TODO: do something with pr.getErrorStream if needed.
            BufferedReader reader = new BufferedReader(new InputStreamReader(pr.getInputStream()));

            String line = "";
            while ((line = reader.readLine()) != null) {
                sb.append(line);
                sb.append("\n");
            }

            returnCode = pr.waitFor();

        } catch (InterruptedException e) {
            throw new ProoferException(e);
        } catch (IOException e) {
            throw new ProoferException(e);
        }

        String out = sb.toString();

        if (debug) {
            System.out.println("Output:" + out);
        }

        if (returnCode != 0) {
            throw new ProoferException("ADB returned error code " + returnCode);
        }

        return out;
    }
}
