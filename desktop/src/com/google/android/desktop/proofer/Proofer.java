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

import com.google.android.desktop.proofer.os.OSBinder;

import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.net.Socket;

import javax.imageio.ImageIO;

public class Proofer {
    public static final String SOURCE_TYPE_FILE = "file";
    public static final String SOURCE_TYPE_SCREEN = "screen";

    private boolean debug = Util.isDebug();

    private AdbRunner adbRunner;
    private ProoferClient client;

    private String sourceType = SOURCE_TYPE_SCREEN;
    private File file;
    private State state = State.Unknown;
    private ProoferCallbacks prooferCallbacks;

    public static interface ProoferCallbacks {
        public void onStateChange(State newState);
        public void onDeviceSizeChanged(Dimension size);
    }

    public static enum State {
        ConnectedActive,
        ConnectedIdle,
        Disconnected,
        Unknown,
    }

    public Proofer(ProoferCallbacks prooferCallbacks) {
        this.adbRunner = new AdbRunner();
        this.client = new ProoferClient();
        this.prooferCallbacks = prooferCallbacks;
    }

    public void startConnectionLoop() {
        new Thread(new Runnable() {
            public void run() {
                while (true) {
                    try {
                        client.connectAndWaitForRequests();
                    } catch (CannotConnectException e) {
                        // Can't connect to device, try re-setting up port forwarding.
                        // If no devices are connected, this will fail.
                        try {
                            setupPortForwarding();
                        } catch (ProoferException e2) {
                            // If we get an error here, we're disconnected.
                            updateState(State.Disconnected);
                        }
                    }

                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        }).start();
    }

    public void runAndroidApp() throws ProoferException {
        adbRunner.adb(new String[]{
                "shell", "am", "start",
                "-a", "android.intent.action.MAIN",
                "-c", "android.intent.category.LAUNCHER",
                "-n", Config.ANDROID_APP_PACKAGE_NAME + "/.DesktopViewerActivity"
        });
    }

    public void killAndroidApp() throws ProoferException {
        adbRunner.adb(new String[]{
                "shell", "am", "force-stop",
                Config.ANDROID_APP_PACKAGE_NAME
        });
    }

    public void uninstallAndroidApp() throws ProoferException {
        adbRunner.adb(new String[]{
                "uninstall", Config.ANDROID_APP_PACKAGE_NAME
        });
    }

    public void installAndroidApp(boolean force) throws ProoferException {
        if (force || !isAndroidAppInstalled()) {
            File apkPath = new File(Util.getCacheDirectory(), "Proofer.apk");
            if (Util.extractResource("assets/Proofer.apk", apkPath)) {
                adbRunner.adb(new String[]{
                        "install", "-r", apkPath.toString()
                });
            } else {
                throw new ProoferException("Error extracting Android APK.");
            }
        }
    }

    public void setupPortForwarding() throws ProoferException {
        try {
            adbRunner.adb(new String[]{
                    "forward", "tcp:" + Config.PORT_LOCAL, "tcp:" + Config.PORT_DEVICE
            });
        } catch (ProoferException e) {
            throw new ProoferException("Couldn't automatically setup port forwarding. "
                    + "You'll need to "
                    + "manually run "
                    + "\"adb forward tcp:" + Config.PORT_LOCAL + " "
                    + "tcp:" + Config.PORT_DEVICE + "\" "
                    + "on the command line.", e);
        }
    }

    public boolean isAndroidAppInstalled() throws ProoferException {
        String out = adbRunner.adb(new String[]{
                "shell", "pm", "list", "packages"
        });
        return out.contains(Config.ANDROID_APP_PACKAGE_NAME);
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public String getSourceType() {
        return sourceType;
    }

    public File getFile() {
        return file;
    }

    public void setRequestedSourceRegion(Rectangle region) {
        client.setRequestedSourceRegion(region);
    }

    public void setImage(File file, BufferedImage image) {
        this.file = file;
        client.setImage(image);
    }

    private void updateState(State newState) {
        if (this.state != newState && debug) {
            switch (newState) {
                case ConnectedActive:
                    System.out.println("State: Connected and active");
                    break;
                case ConnectedIdle:
                    System.out.println("State: Connected and idle");
                    break;
                case Disconnected:
                    System.out.println("State: Disconnected");
                    break;
            }
        }

        if (this.state != newState && prooferCallbacks != null) {
            prooferCallbacks.onStateChange(newState);
        }

        this.state = newState;
    }

    public static class CannotConnectException extends ProoferException {
        public CannotConnectException(Throwable throwable) {
            super(throwable);
        }
    }

    private class ProoferClient {
        private Rectangle requestedSourceRegion = new Rectangle(0, 0, 0, 0);
        private BufferedImage forcedImage;
        private Robot robot;
        private Rectangle screenBounds;
        private Dimension currentDeviceSize = new Dimension();

        public ProoferClient() {
            try {
                this.robot = new Robot();
            } catch (AWTException e) {
                System.err.println("Error getting robot.");
                e.printStackTrace();
                System.exit(1);
            }

            GraphicsEnvironment environment = GraphicsEnvironment.getLocalGraphicsEnvironment();
            GraphicsDevice[] screenDevices = environment.getScreenDevices();

            Rectangle2D tempBounds = new Rectangle();
            for (GraphicsDevice screenDevice : screenDevices) {
                tempBounds = tempBounds.createUnion(
                        screenDevice.getDefaultConfiguration().getBounds());
            }
            screenBounds = tempBounds.getBounds();
        }

        public void setRequestedSourceRegion(Rectangle region) {
            requestedSourceRegion = region;
        }

        public void setImage(BufferedImage image) {
            this.forcedImage = image;
        }

        public void connectAndWaitForRequests() throws CannotConnectException {
            Socket socket;

            // Establish the connection.
            try {
                socket = new Socket("localhost", Config.PORT_LOCAL);
            } catch (IOException e) {
                throw new CannotConnectException(e);
            }

            if (debug) {
                System.out.println(
                        "Local socket established " + socket.getRemoteSocketAddress().toString());
            }

            // Wait for requests.
            try {
                DataInputStream dis = new DataInputStream(socket.getInputStream());
                BufferedOutputStream bos = new BufferedOutputStream(socket.getOutputStream());
                Dimension deviceSize = new Dimension();

                while (true) {
                    // Try processing a request.
                    dis.readInt(); // unused x
                    dis.readInt(); // unused y

                    deviceSize.width = dis.readInt();
                    deviceSize.height = dis.readInt();

                    // If we reach this point, we didn't hit an IOException and we've received
                    // a request from the device.

                    if (!deviceSize.equals(currentDeviceSize) && prooferCallbacks != null) {
                        prooferCallbacks.onDeviceSizeChanged(deviceSize);
                        if (debug) {
                            System.out.println("Got device size: " + currentDeviceSize.width
                                    + "x" + currentDeviceSize.height);
                        }
                        currentDeviceSize = new Dimension(deviceSize);
                    }

                    updateState(State.ConnectedActive);

                    if (deviceSize.width > 1 && deviceSize.height > 1) {
                        BufferedImage bi;
                        if (SOURCE_TYPE_FILE.equals(sourceType)) {
                            bi = forcedImage;
                        } else {
                            bi = capture();
                        }

                        ByteArrayOutputStream baos = new ByteArrayOutputStream();

                        if (bi != null) {
                            if (bi.getWidth() != currentDeviceSize.width ||
                                    bi.getHeight() != currentDeviceSize.height) {
                                // Scale the bitmap
                                BufferedImage resized = new BufferedImage(
                                        currentDeviceSize.width,
                                        currentDeviceSize.height,
                                        bi.getType());
                                Graphics2D g2d = resized.createGraphics();
                                g2d.setRenderingHint(
                                        RenderingHints.KEY_INTERPOLATION,
                                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                                g2d.drawImage(
                                        bi,
                                        0, 0, currentDeviceSize.width, currentDeviceSize.height,
                                        0, 0, bi.getWidth(), bi.getHeight(),
                                        null);
                                g2d.dispose();
                                bi = resized;
                            }

                            ImageIO.write(bi, "PNG", baos);
                        } else {
                            baos.write(new byte[]{0});
                        }

                        byte[] out = baos.toByteArray();
                        int len = out.length;
                        byte[] outlen = new byte[4];
                        outlen[0] = (byte) ((len >> 24) & 0xFF);
                        outlen[1] = (byte) ((len >> 16) & 0xFF);
                        outlen[2] = (byte) ((len >> 8) & 0xFF);
                        outlen[3] = (byte) (len & 0xFF);

                        if (debug) {
                            System.out.println("Writing " + len + " bytes.");
                        }

                        bos.write(outlen, 0, 4);
                        bos.write(out, 0, len);
                        bos.flush();
                    }

                    // This loop will exit only when an IOException is thrown, indicating there's
                    // nothing further to read.
                }
            } catch (IOException e) {
                // If we're not "connected", this just means we haven't received any requests yet
                // on the socket, so there's no error to log.
                if (debug) {
                    System.out.println("No activity.");
                }
            }

            // No (or no more) requests.
            updateState(State.ConnectedIdle);
        }

        private BufferedImage capture() {
            Rectangle captureRect = new Rectangle(
                    Math.max(screenBounds.x, requestedSourceRegion.x),
                    Math.max(screenBounds.y, requestedSourceRegion.y),
                    requestedSourceRegion.width,
                    requestedSourceRegion.height);

            if (captureRect.x + captureRect.width > screenBounds.x + screenBounds.width) {
                captureRect.x = screenBounds.x + screenBounds.width - captureRect.width;
            }

            if (captureRect.y + captureRect.height > screenBounds.y + screenBounds.height) {
                captureRect.y = screenBounds.y + screenBounds.height - captureRect.height;
            }

            long before = System.currentTimeMillis();
            BufferedImage bi = robot.createScreenCapture(captureRect);
            long after = System.currentTimeMillis();

            if (debug) {
                System.out.println("Capture time: " + (after - before) + " msec");
            }

            return bi;
        }
    }
}

