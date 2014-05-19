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

import com.google.android.desktop.proofer.Util;

import java.lang.reflect.InvocationTargetException;

public abstract class OSBinder {
    protected Callbacks callbacks;

    public static interface Callbacks {
        public void onQuit();
    }

    public static OSBinder getBinder(Callbacks callbacks) {
        try {
            switch (Util.getCurrentOS()) {
                case Mac:
                    return MacBinder.class.getConstructor(Callbacks.class)
                            .newInstance(callbacks);
            }
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
        } catch (InstantiationException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }

        return new DefaultBinder(callbacks);
    }

    protected OSBinder(Callbacks callbacks) {
        this.callbacks = callbacks;
    }

    public abstract float getDisplayScaleFactor();
}
