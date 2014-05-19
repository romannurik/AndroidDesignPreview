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

import com.sun.awt.AWTUtilities;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.swing.*;

public class RegionSelector {
    private RegionSelectorFrame frame;
    private OSBinder osBinder;
    private float displayScaleFactor;

    private Rectangle region = new Rectangle(100, 100, 480, 800);
    private Dimension deviceSize = new Dimension(480, 800);
    private RegionChangeCallback regionChangeCallback;

    public static interface RegionChangeCallback {
        public void onRegionChanged(Rectangle region);
        public void onRegionWindowVisibilityChanged(boolean visible);
    }

    public RegionSelector(RegionChangeCallback regionChangeCallback) {
        this.regionChangeCallback = regionChangeCallback;
        osBinder = OSBinder.getBinder(null);
        displayScaleFactor = osBinder.getDisplayScaleFactor();
        setupUI();
    }

    private void setupUI() {
        // http://java.sun.com/developer/technicalArticles/GUI/translucent_shaped_windows/

        try {
            if (!AWTUtilities.isTranslucencySupported(AWTUtilities.Translucency.TRANSLUCENT)) {
                throw new UnsupportedOperationException();
            }

            //perform translucency operations here
            GraphicsEnvironment env =
                    GraphicsEnvironment.getLocalGraphicsEnvironment();
            GraphicsDevice[] devices = env.getScreenDevices();
            GraphicsConfiguration translucencyCapableGC = null;
            for (int i = 0; i < devices.length && translucencyCapableGC == null; i++) {
                GraphicsConfiguration[] configs = devices[i].getConfigurations();
                for (int j = 0; j < configs.length && translucencyCapableGC == null; j++) {
                    if (AWTUtilities.isTranslucencyCapable(configs[j])) {
                        translucencyCapableGC = configs[j];
                    }
                }
            }

            frame = new RegionSelectorFrame(translucencyCapableGC);
            frame.setUndecorated(true);
            AWTUtilities.setWindowOpaque(frame, false);
            frame.getRootPane().putClientProperty("apple.awt.draggableWindowBackground",
                    Boolean.FALSE);
        } catch (NoClassDefFoundError e) {
            frame = new RegionSelectorFrame(null);
            frame.setUndecorated(true);
        } catch (UnsupportedOperationException e) {
            frame = new RegionSelectorFrame(null);
            frame.setUndecorated(true);
        }

        frame.setAlwaysOnTop(true);
        frame.setResizable(true);

        frame.setBounds(region);
        tryLoadFrameConfig();
        region = frame.getBounds();
    }

    public boolean isVisible() {
        return frame.isVisible();
    }

    public void showWindow(boolean show) {
        frame.setVisible(show);
        regionChangeCallback.onRegionWindowVisibilityChanged(show);
    }

    public Rectangle getRegion() {
        return region;
    }

    private void setRegion(Rectangle region) {
        this.region = region;
        this.frame.setLocation(region.getLocation());
        this.frame.setSize(region.getSize());
        if (regionChangeCallback != null) {
            regionChangeCallback.onRegionChanged(region);
        }
    }

    public void requestDeviceSize(Dimension size) {
        double currentScale = region.getWidth() / deviceSize.getWidth();
        Dimension scaledSize = new Dimension(
                (int) (size.width * currentScale),
                (int) (size.height * currentScale));

        deviceSize = new Dimension(size);
        region.setSize(scaledSize);
        this.frame.setSize(scaledSize);
    }

    void trySaveFrameConfig() {
        try {
            Properties props = new Properties();
            props.setProperty("x", String.valueOf(frame.getX()));
            props.setProperty("y", String.valueOf(frame.getY()));
            props.setProperty("scale", String.valueOf(
                    frame.getWidth() * 1f / deviceSize.getWidth() * displayScaleFactor));
            props.storeToXML(new FileOutputStream(
                    new File(Util.getCacheDirectory(), "region.xml")), null);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();

    private Runnable saveFrameConfigRunnable = new Runnable() {
        @Override
        public void run() {
            trySaveFrameConfig();
        }
    };

    private ScheduledFuture<?> saveFrameConfigScheduleHandle;

    void delayedTrySaveFrameConfig() {
        if (saveFrameConfigScheduleHandle != null) {
            saveFrameConfigScheduleHandle.cancel(false);
        }

        saveFrameConfigScheduleHandle = worker
                .schedule(saveFrameConfigRunnable, 1, TimeUnit.SECONDS);
    }

    void tryLoadFrameConfig() {
        try {
            Properties props = new Properties();
            props.loadFromXML(
                    new FileInputStream(new File(Util.getCacheDirectory(), "region.xml")));
            frame.setLocation(
                    Integer.parseInt(props.getProperty("x", String.valueOf(frame.getX()))),
                    Integer.parseInt(props.getProperty("y", String.valueOf(frame.getY()))));
            double scale = Double.parseDouble(props.getProperty("scale", "1"));
            this.frame.setSize(
                    (int) (scale / displayScaleFactor * deviceSize.width),
                    (int) (scale / displayScaleFactor * deviceSize.height));
        } catch (FileNotFoundException ignored) {
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private class RegionSelectorFrame extends JFrame implements
            MouseListener, MouseMotionListener, KeyListener {
        private static final int LINE_SPACING_PIXELS = 8;

        private static final int RESIZE_GRIP_SIZE_PIXELS = 20;

        private static final int HIT_TEST_HAS_N = 0x1;
        private static final int HIT_TEST_HAS_S = 0x2;
        private static final int HIT_TEST_HAS_E = 0x10;
        private static final int HIT_TEST_HAS_W = 0x20;

        private static final int HIT_TEST_NONE = 0;
        private static final int HIT_TEST_NE = HIT_TEST_HAS_N | HIT_TEST_HAS_E;
        private static final int HIT_TEST_SE = HIT_TEST_HAS_S | HIT_TEST_HAS_E;
        private static final int HIT_TEST_NW = HIT_TEST_HAS_N | HIT_TEST_HAS_W;
        private static final int HIT_TEST_SW = HIT_TEST_HAS_S | HIT_TEST_HAS_W;

        private Paint strokePaint;
        private Paint fillPaint;
        private Stroke stroke;
        private Font fontTitle;
        private Font fontSubtitle;

        private Point startDragPoint;
        private Point startLocation;
        private Dimension startSize;
        private int startHitTest;

        private RegionSelectorFrame(GraphicsConfiguration graphicsConfiguration) {
            super(graphicsConfiguration);
            fillPaint = new Color(0, 0, 0, 32);
            strokePaint = new Color(255, 0, 0, 128);
            stroke = new BasicStroke(5, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER);
            fontTitle = new Font(Font.DIALOG, Font.BOLD, 30);
            fontSubtitle = new Font(Font.DIALOG, Font.BOLD, 16);

            addMouseListener(this);
            addMouseMotionListener(this);
            addKeyListener(this);
        }

        @Override
        public void paint(Graphics graphics) {
            if (graphics instanceof Graphics2D) {
                Graphics2D g2d = (Graphics2D) graphics;
                g2d.clearRect(0, 0, getWidth(), getHeight());
                g2d.setPaint(fillPaint);
                g2d.fillRect(0, 0, getWidth(), getHeight());

                g2d.setPaint(strokePaint);
                g2d.setStroke(stroke);
                g2d.drawRect(0, 0, getWidth() - 1, getHeight() - 1);

                int y = getHeight() / 2;
                // Line 1
                g2d.setFont(fontTitle);
                String s = deviceSize.width
                        + "\u00d7"
                        + deviceSize.height;
                Rectangle2D r = fontTitle.getStringBounds(s, g2d.getFontRenderContext());
                g2d.drawString(s, (int) (getWidth() - r.getWidth()) / 2, y);

                // Line 2
                g2d.setFont(fontSubtitle);
                int scaledWidth = (int) (getWidth() * displayScaleFactor);
                if (scaledWidth != deviceSize.width) {
                    s = "(" + (100 * scaledWidth / deviceSize.width) + "%)";
                } else {
                    s = "(Drag corners to resize)";
                }
                r = fontSubtitle.getStringBounds(s, g2d.getFontRenderContext());
                y += r.getHeight() + LINE_SPACING_PIXELS;
                g2d.drawString(s, (int) (getWidth() - r.getWidth()) / 2, y);

                // Line 3
                s = "(Double-click or ESC to hide)";
                r = fontSubtitle.getStringBounds(s, g2d.getFontRenderContext());
                y += r.getHeight() + LINE_SPACING_PIXELS;
                g2d.drawString(s, (int) (getWidth() - r.getWidth()) / 2, y);

            } else {
                super.paint(graphics);
            }
        }

        // http://www.java2s.com/Tutorial/Java/0240__Swing/Dragandmoveaframefromitscontentarea.htm

        public void mousePressed(MouseEvent mouseEvent) {
            startHitTest = hitTest(mouseEvent.getX(), mouseEvent.getY());
            startDragPoint = getScreenLocation(mouseEvent);
            startLocation = getLocation();
            startSize = getSize();

            if (mouseEvent.getClickCount() == 2) {
                showWindow(false);
            }
        }

        public void mouseDragged(MouseEvent mouseEvent) {
            Point current = getScreenLocation(mouseEvent);

            if (startHitTest == HIT_TEST_NONE) {
                // Moving
                Point newLocation = new Point(
                        startLocation.x + current.x - startDragPoint.x,
                        startLocation.y + current.y - startDragPoint.y);
                region.setLocation(newLocation);
                setRegion(region);

            } else {
                // Resizing. Maintain aspect ratio
                boolean n = (startHitTest & HIT_TEST_HAS_N) != 0;
                boolean w = (startHitTest & HIT_TEST_HAS_W) != 0;

                Dimension newSize = new Dimension(
                        startSize.width + (w ? -1 : 1) * (current.x - startDragPoint.x),
                        startSize.height + (n ? -1 : 1) * (current.y - startDragPoint.y));
                int keepAspectWidth = (int) (deviceSize.width * newSize.getHeight()
                        / deviceSize.height);
                int keepAspectHeight = (int) (deviceSize.height * newSize.getWidth()
                        / deviceSize.width);

                if (keepAspectHeight <= newSize.height) {
                    newSize.height = keepAspectHeight;
                } else {
                    newSize.width = keepAspectWidth;
                }

                // Lock to 25% increments (or possibly 33% increments)
                float naturalFrac = (float) (newSize.height / deviceSize.getHeight());
                float frac = Math.round(naturalFrac * 4 * displayScaleFactor) / (4f * displayScaleFactor);
                float frac3 = Math.round(naturalFrac * 3 * displayScaleFactor) / (3f * displayScaleFactor);
                frac = (Math.abs(naturalFrac - frac3) < Math.abs(naturalFrac - frac))
                        ? frac3 : frac;
                frac = Math.max(0.25f / displayScaleFactor, frac);
                newSize.width = (int) (deviceSize.getWidth() * frac);
                newSize.height = (int) (deviceSize.getHeight() * frac);

                Point newLocation = new Point(
                        w ? (startLocation.x - newSize.width + startSize.width) : startLocation.x,
                        n ? (startLocation.y - newSize.height + startSize.height) : startLocation.y);

                setRegion(new Rectangle(newLocation, newSize));
            }

            delayedTrySaveFrameConfig();
        }

        private Point getScreenLocation(MouseEvent e) {
            Point cursor = e.getPoint();
            Point targetLocation = getLocationOnScreen();
            return new Point(
                    (int) (targetLocation.getX() + cursor.getX()),
                    (int) (targetLocation.getY() + cursor.getY()));
        }

        private int hitTest(int x, int y) {
            if (x <= RESIZE_GRIP_SIZE_PIXELS) {
                if (y <= RESIZE_GRIP_SIZE_PIXELS) {
                    return HIT_TEST_NW;
                } else if (y >= getHeight() - RESIZE_GRIP_SIZE_PIXELS) {
                    return HIT_TEST_SW;
                }
            } else if (x >= getWidth() - RESIZE_GRIP_SIZE_PIXELS) {
                if (y <= RESIZE_GRIP_SIZE_PIXELS) {
                    return HIT_TEST_NE;
                } else if (y >= getHeight() - RESIZE_GRIP_SIZE_PIXELS) {
                    return HIT_TEST_SE;
                }
            }
            return HIT_TEST_NONE;
        }

        public void mouseMoved(MouseEvent mouseEvent) {
            switch (hitTest(mouseEvent.getX(), mouseEvent.getY())) {
                case HIT_TEST_NE:
                    setCursor(Cursor.getPredefinedCursor(Cursor.NE_RESIZE_CURSOR));
                    break;
                case HIT_TEST_NW:
                    setCursor(Cursor.getPredefinedCursor(Cursor.NW_RESIZE_CURSOR));
                    break;
                case HIT_TEST_SE:
                    setCursor(Cursor.getPredefinedCursor(Cursor.SE_RESIZE_CURSOR));
                    break;
                case HIT_TEST_SW:
                    setCursor(Cursor.getPredefinedCursor(Cursor.SW_RESIZE_CURSOR));
                    break;
                default:
                    setCursor(Cursor.getDefaultCursor());
            }
        }

        public void mouseClicked(MouseEvent mouseEvent) {
        }

        public void mouseReleased(MouseEvent mouseEvent) {
        }

        public void mouseEntered(MouseEvent mouseEvent) {
        }

        public void mouseExited(MouseEvent mouseEvent) {
        }

        public void keyTyped(KeyEvent keyEvent) {
        }

        public void keyPressed(KeyEvent keyEvent) {
            if (keyEvent.getKeyCode() == KeyEvent.VK_ESCAPE) {
                showWindow(false);
                return;
            }

            Point newLocation = getLocation();

            int val = keyEvent.isShiftDown() ? 10 : 1;

            switch (keyEvent.getKeyCode()) {
                case KeyEvent.VK_UP:
                    newLocation.y -= val;
                    break;
                case KeyEvent.VK_LEFT:
                    newLocation.x -= val;
                    break;
                case KeyEvent.VK_DOWN:
                    newLocation.y += val;
                    break;
                case KeyEvent.VK_RIGHT:
                    newLocation.x += val;
                    break;
                default:
                    return;
            }

            region.setLocation(newLocation);
            setRegion(region);
            delayedTrySaveFrameConfig();
        }

        public void keyReleased(KeyEvent keyEvent) {
        }
    }
}
