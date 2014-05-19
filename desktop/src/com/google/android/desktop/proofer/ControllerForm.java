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
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.dnd.DropTargetListener;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import javax.imageio.ImageIO;
import javax.swing.*;

public class ControllerForm
        implements WindowListener, OSBinder.Callbacks, Proofer.ProoferCallbacks,
        RegionSelector.RegionChangeCallback {
    private boolean debug = Util.isDebug();

    private JFrame frame;
    private JPanel contentPanel;
    private JButton reinstallButton;
    private JLabel statusLabel;
    private JButton sourceButton;
    private JRadioButton localFileSourceButton;
    private JRadioButton screenCaptureSourceButton;

    private RegionSelector regionSelector;
    private Proofer proofer;

    public ControllerForm() {
        OSBinder.getBinder(this);
        setupUI();
        setupProofer();
    }

    public static void main(String[] args) {
        // OSX only
//        System.setProperty("com.apple.mrj.application.apple.menu.about.name",
//                "Android Design Preview");
        new ControllerForm();
    }

    private void setupProofer() {
        proofer = new Proofer(this);

        try {
            proofer.setupPortForwarding();
        } catch (ProoferException e) {
            e.printStackTrace();
        }

        try {
            proofer.installAndroidApp(false);
            proofer.runAndroidApp();
        } catch (ProoferException e) {
            e.printStackTrace();
        }

        proofer.startConnectionLoop();
        proofer.setRequestedSourceRegion(regionSelector.getRegion());
    }

    private void setupUI() {
        frame = new JFrame(ControllerForm.class.getName());
        frame.setTitle("Android Design Preview");
        frame.setIconImages(Arrays.asList(Util.getAppIconMipmap()));
        frame.setAlwaysOnTop(true);
        frame.setMinimumSize(new Dimension(250, 200));

        frame.setLocationByPlatform(true);
        tryLoadFrameConfig();

        frame.setContentPane(contentPanel);
        frame.setResizable(false);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setVisible(true);
        frame.addWindowListener(this);

        reinstallButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent actionEvent) {
                try {
                    proofer.installAndroidApp(true);
                    proofer.runAndroidApp();
                } catch (ProoferException e) {
                    JOptionPane.showMessageDialog(frame,
                            "Couldn't install the app: " + e.getMessage()
                                    + "\n"
                                    + "\nPlease make sure your device is connected over USB and "
                                    + "\nthat USB debugging is enabled on your device under "
                                    + "\nSettings > Applications > Development or"
                                    + "\nSettings > Developer options.",
                            "Android Design Preview",
                            JOptionPane.ERROR_MESSAGE);
                    e.printStackTrace();
                }
            }
        });

        regionSelector = new RegionSelector(this);

        ActionListener sourceTypeChangeListener = new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent actionEvent) {
                switchSourceType(actionEvent.getActionCommand());
            }
        };

        localFileSourceButton.setActionCommand(Proofer.SOURCE_TYPE_FILE);
        localFileSourceButton.addActionListener(sourceTypeChangeListener);
        screenCaptureSourceButton.setActionCommand(Proofer.SOURCE_TYPE_SCREEN);
        screenCaptureSourceButton.addActionListener(sourceTypeChangeListener);

        sourceButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent actionEvent) {
                if (Proofer.SOURCE_TYPE_FILE.equals(proofer.getSourceType())) {
                    // use the native file dialog on the mac
                    FileDialog dialog = new FileDialog(frame,
                            "Select Mockup File", FileDialog.LOAD);
                    dialog.setFilenameFilter(new FilenameFilter() {
                        @Override
                        public boolean accept(File file, String s) {
                            s = s.toLowerCase();
                            return s.endsWith(".png")
                                    || s.endsWith(".jpg")
                                    || s.endsWith(".gif")
                                    || s.endsWith(".jpeg");
                        }
                    });
                    dialog.setAlwaysOnTop(true);
                    dialog.setModalityType(Dialog.ModalityType.APPLICATION_MODAL);
                    dialog.setVisible(true);
                    loadFile(new File(dialog.getDirectory(), dialog.getFile()));

                } else {
                    regionSelector.showWindow(!regionSelector.isVisible());
                }
            }
        });

        new DropTarget(frame, fileDropListener);
    }

    private DropTargetListener fileDropListener = new DropTargetAdapter() {
        @Override
        public void drop(DropTargetDropEvent event) {
            // http://blog.christoffer.me/2011/01/drag-and-dropping-files-to-java-desktop.html
            event.acceptDrop(DnDConstants.ACTION_COPY | DnDConstants.ACTION_LINK);
            Transferable transferable = event.getTransferable();
            DataFlavor[] flavors = transferable.getTransferDataFlavors();
            for (DataFlavor flavor : flavors) {
                try {
                    if (flavor.isFlavorJavaFileListType()) {
                        List<File> files = (List<File>) transferable.getTransferData(flavor);
                        if (files.size() > 0) {
                            File file = files.get(0);
                            localFileSourceButton.setSelected(true);
                            switchSourceType(Proofer.SOURCE_TYPE_FILE);
                            loadFile(file);
                            event.dropComplete(true);
                        }
                    }
                } catch (IOException e) {
                    if (debug) {
                        e.printStackTrace();
                    }
                } catch (UnsupportedFlavorException e) {
                    if (debug) {
                        e.printStackTrace();
                    }
                }
            }
            event.rejectDrop();
        }
    };

    private void loadFile(File file) {
        BufferedImage bi;
        try {
            bi = ImageIO.read(file);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(frame,
                    "Error loading image.", "Android Design Preview", JOptionPane.ERROR_MESSAGE);
            return;
        }
        proofer.setImage(file, bi);
        updateSourceButtonUI();
    }

    private void switchSourceType(String sourceType) {
        if (sourceType.equals(proofer.getSourceType())) {
            return;
        }

        proofer.setSourceType(sourceType);

        if (!Proofer.SOURCE_TYPE_SCREEN.equals(sourceType)) {
            regionSelector.showWindow(false);
        }

        updateSourceButtonUI();
    }

    private void trySaveFrameConfig() {
        try {
            Properties props = new Properties();
            props.setProperty("x", String.valueOf(frame.getX()));
            props.setProperty("y", String.valueOf(frame.getY()));
            props.storeToXML(new FileOutputStream(
                    new File(Util.getCacheDirectory(), "config.xml")), null);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void tryLoadFrameConfig() {
        try {
            Properties props = new Properties();
            props.loadFromXML(
                    new FileInputStream(new File(Util.getCacheDirectory(), "config.xml")));
            frame.setLocation(
                    Integer.parseInt(props.getProperty("x", String.valueOf(frame.getX()))),
                    Integer.parseInt(props.getProperty("y", String.valueOf(frame.getY()))));
        } catch (FileNotFoundException ignored) {
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void windowClosing(WindowEvent windowEvent) {
        onQuit();
    }

    public void onQuit() {
        try {
            proofer.killAndroidApp();
        } catch (ProoferException e) {
            e.printStackTrace();
        }

        trySaveFrameConfig();
        frame.dispose();
        System.exit(0);
    }

    public void windowOpened(WindowEvent windowEvent) {
    }

    public void windowClosed(WindowEvent windowEvent) {
    }

    public void windowIconified(WindowEvent windowEvent) {
    }

    public void windowDeiconified(WindowEvent windowEvent) {
    }

    public void windowActivated(WindowEvent windowEvent) {
    }

    public void windowDeactivated(WindowEvent windowEvent) {
    }

    public void onStateChange(Proofer.State newState) {
        switch (newState) {
            case ConnectedActive:
                statusLabel.setText("Connected, active");
                break;
            case ConnectedIdle:
                statusLabel.setText("Connected, inactive");
                break;
            case Disconnected:
                statusLabel.setText("Disconnected");
                break;
            case Unknown:
                statusLabel.setText("N/A");
                break;
        }
    }

    public void onDeviceSizeChanged(Dimension size) {
        regionSelector.requestDeviceSize(size);
    }

    public void onRegionChanged(Rectangle region) {
        if (proofer != null) {
            proofer.setRequestedSourceRegion(region);
        }
    }

    @Override
    public void onRegionWindowVisibilityChanged(boolean visible) {
        updateSourceButtonUI();
    }

    private void updateSourceButtonUI() {
        String sourceType = proofer.getSourceType();
        if (Proofer.SOURCE_TYPE_FILE.equals(sourceType)) {
            File currentFile = proofer.getFile();
            if (currentFile != null) {
                sourceButton.setText(currentFile.getName());
            } else {
                sourceButton.setText("Choose File");
                sourceButton.setMnemonic('C');
            }

        } else if (Proofer.SOURCE_TYPE_SCREEN.equals(sourceType)) {
            if (regionSelector.isVisible()) {
                sourceButton.setText("Close Mirror Region Window");
            } else {
                sourceButton.setText("Select Mirror Region");
            }
            sourceButton.setMnemonic('M');
        }
    }

    {
// GUI initializer generated by IntelliJ IDEA GUI Designer
// >>> IMPORTANT!! <<<
// DO NOT EDIT OR ADD ANY CODE HERE!
        $$$setupUI$$$();
    }

    /**
     * Method generated by IntelliJ IDEA GUI Designer >>> IMPORTANT!! <<< DO NOT edit this method OR
     * call it in your code!
     *
     * @noinspection ALL
     */
    private void $$$setupUI$$$() {
        contentPanel = new JPanel();
        contentPanel.setLayout(new GridBagLayout());
        reinstallButton = new JButton();
        reinstallButton.setText("Re-install App");
        reinstallButton.setMnemonic('R');
        reinstallButton.setDisplayedMnemonicIndex(0);
        GridBagConstraints gbc;
        gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 5;
        gbc.gridwidth = 3;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(0, 8, 8, 8);
        contentPanel.add(reinstallButton, gbc);
        sourceButton = new JButton();
        sourceButton.setText("Select Mirror Region");
        sourceButton.setMnemonic('M');
        sourceButton.setDisplayedMnemonicIndex(7);
        gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 3;
        gbc.gridwidth = 3;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(0, 8, 0, 8);
        contentPanel.add(sourceButton, gbc);
        final JSeparator separator1 = new JSeparator();
        gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 4;
        gbc.gridwidth = 3;
        gbc.fill = GridBagConstraints.BOTH;
        gbc.insets = new Insets(4, 0, 4, 0);
        contentPanel.add(separator1, gbc);
        final JSeparator separator2 = new JSeparator();
        gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.gridwidth = 3;
        gbc.fill = GridBagConstraints.BOTH;
        gbc.insets = new Insets(4, 0, 4, 0);
        contentPanel.add(separator2, gbc);
        screenCaptureSourceButton = new JRadioButton();
        screenCaptureSourceButton.setSelected(true);
        screenCaptureSourceButton.setText("Screen");
        screenCaptureSourceButton.setMnemonic('S');
        screenCaptureSourceButton.setDisplayedMnemonicIndex(0);
        gbc = new GridBagConstraints();
        gbc.gridx = 1;
        gbc.gridy = 2;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(0, 0, 4, 0);
        contentPanel.add(screenCaptureSourceButton, gbc);
        localFileSourceButton = new JRadioButton();
        localFileSourceButton.setText("File");
        localFileSourceButton.setMnemonic('F');
        localFileSourceButton.setDisplayedMnemonicIndex(0);
        gbc = new GridBagConstraints();
        gbc.gridx = 2;
        gbc.gridy = 2;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(0, 0, 4, 8);
        contentPanel.add(localFileSourceButton, gbc);
        final JLabel label1 = new JLabel();
        label1.setForeground(new Color(-10066330));
        label1.setText("Source:");
        gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.insets = new Insets(0, 8, 4, 4);
        contentPanel.add(label1, gbc);
        final JLabel label2 = new JLabel();
        label2.setForeground(new Color(-10066330));
        label2.setText("Status:");
        gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(8, 8, 0, 4);
        contentPanel.add(label2, gbc);
        statusLabel = new JLabel();
        statusLabel.setFont(new Font(statusLabel.getFont().getName(), Font.BOLD,
                statusLabel.getFont().getSize()));
        statusLabel.setHorizontalAlignment(0);
        statusLabel.setHorizontalTextPosition(11);
        statusLabel.setText("N/A");
        gbc = new GridBagConstraints();
        gbc.gridx = 1;
        gbc.gridy = 0;
        gbc.gridwidth = 2;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(8, 0, 0, 0);
        contentPanel.add(statusLabel, gbc);
        ButtonGroup buttonGroup;
        buttonGroup = new ButtonGroup();
        buttonGroup.add(localFileSourceButton);
        buttonGroup.add(screenCaptureSourceButton);
        buttonGroup.add(localFileSourceButton);
    }

    /**
     * @noinspection ALL
     */
    public JComponent $$$getRootComponent$$$() {
        return contentPanel;
    }
}
