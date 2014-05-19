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

package com.google.android.desktop.proofer.os;

import com.apple.eawt.AppEvent;
import com.apple.eawt.Application;
import com.apple.eawt.QuitHandler;
import com.apple.eawt.QuitResponse;
import com.apple.eawt.QuitStrategy;

import java.awt.*;

public class MacBinder extends OSBinder implements QuitHandler {
    Application application;

    public MacBinder(Callbacks callbacks) {
        super(callbacks);

        application = Application.getApplication();
        application.setQuitStrategy(QuitStrategy.SYSTEM_EXIT_0);
        application.setQuitHandler(this);
    }

    @Override
    public float getDisplayScaleFactor() {
        return (Float) Toolkit.getDefaultToolkit().getDesktopProperty(
                "apple.awt.contentScaleFactor");
    }

    public void handleQuitRequestWith(AppEvent.QuitEvent quitEvent, QuitResponse quitResponse) {
        if (callbacks != null) {
            callbacks.onQuit();
        }
    }
}
