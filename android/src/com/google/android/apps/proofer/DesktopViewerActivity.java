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

package com.google.android.apps.proofer;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnTouchListener;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public class DesktopViewerActivity extends Activity implements
        ViewTreeObserver.OnGlobalLayoutListener {
    private static final String TAG = "DesktopViewerActivity";
    private static final int PORT_DEVICE = 7800;

    private View mTargetView;
    private TextView mStatusTextView;

    private boolean mKillServer;

    private boolean mWasAtSomePointConnected = false;
    private boolean mConnected = false;

    private int mOffsetX;
    private int mOffsetY;

    private int mWidth;
    private int mHeight;

    private final Object mDataSyncObject = new Object();
    private byte[] mImageData;

    private SystemUiHider mSystemUiHider;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB) {
            // Pre-Honeycomb this is the best we can do.
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }

        setContentView(R.layout.main);

        mStatusTextView = (TextView) findViewById(R.id.status_text);

        mTargetView = findViewById(R.id.target);
        mTargetView.setOnTouchListener(mTouchListener);
        mTargetView.getViewTreeObserver().addOnGlobalLayoutListener(this);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            mSystemUiHider = new SystemUiHider(mTargetView);
            mSystemUiHider.setup(getWindow());
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        mKillServer = false;
        new Thread(mSocketThreadRunnable).start();
    }

    public void onPause() {
        super.onPause();
        mKillServer = true;
    }

    private OnTouchListener mTouchListener = new OnTouchListener() {
        float mDownX;
        float mDownY;
        float mDownOffsetX;
        float mDownOffsetY;

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (mSystemUiHider != null) {
                mSystemUiHider.delay();
            }

            int action = event.getAction();
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    mDownX = event.getX();
                    mDownY = event.getY();
                    mDownOffsetX = mOffsetX;
                    mDownOffsetY = mOffsetY;
                    break;

                case MotionEvent.ACTION_MOVE:
                    mOffsetX = (int) (mDownOffsetX + (mDownX - event.getX()));
                    mOffsetY = (int) (mDownOffsetY + (mDownY - event.getY()));
                    if (mOffsetX < 0) {
                        mOffsetX = 0;
                    }
                    if (mOffsetY < 0) {
                        mOffsetY = 0;
                    }
                    break;
            }
            return true;
        }
    };

    private Handler mHandler = new Handler() {
        public void handleMessage(Message msg) {
            Bitmap bm = (Bitmap) msg.obj;

            if (bm == null) {
                // Not connected
                if (mConnected) {
                    // Disconnected (was previously connected)
                    Toast.makeText(DesktopViewerActivity.this,
                            R.string.toast_disconnected, Toast.LENGTH_SHORT).show();
                }
            } else {
                // Connected
                //noinspection deprecation
                mTargetView.setBackgroundDrawable(new BitmapDrawable(getResources(), bm));
                mStatusTextView.setVisibility(View.GONE);

                if (!mConnected && mWasAtSomePointConnected) {
                    // Reconnected (was at some point connected, then connection list, now it's
                    // back)
                    Toast.makeText(DesktopViewerActivity.this,
                            R.string.toast_reconnected, Toast.LENGTH_SHORT).show();
                }

                mWasAtSomePointConnected = true;
            }

            mConnected = (bm != null);
        }
    };

    public void onGlobalLayout() {
        updateDimensions();
    }

    private void updateDimensions() {
        synchronized (mDataSyncObject) {
            mWidth = mTargetView.getWidth();
            mHeight = mTargetView.getHeight();
            mImageData = new byte[mWidth * mHeight * 3];
        }
    }

    private int readFully(BufferedInputStream bis, byte[] data, int offset, int len)
            throws IOException {
        int count = 0;
        int got = 0;
        while (count < len) {
            got = bis.read(data, count, len - count);

            if (got >= 0) {
                count += got;
            } else {
                break;
            }
        }

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Got " + got + " bytes");
        }
        return got;
    }

    private Runnable mSocketThreadRunnable = new Runnable() {
        public void run() {
            while (true) {
                ServerSocket server = null;

                try {
                    Thread.sleep(1000);
                    server = new ServerSocket(PORT_DEVICE);
                } catch (Exception e) {
                    Log.e(TAG, "Error creating server socket", e);
                    continue;
                }

                while (true) {
                    try {
                        Socket socket = server.accept();
                        Log.i(TAG, "Got connection request");
                        BufferedInputStream bis = new BufferedInputStream(socket.getInputStream());
                        DataOutputStream dos = new DataOutputStream(socket.getOutputStream());

                        while (!mKillServer) {
                            Thread.sleep(50);
                            synchronized (mDataSyncObject) {
                                dos.writeInt(mOffsetX);
                                dos.writeInt(mOffsetY);
                                dos.writeInt(mWidth);
                                dos.writeInt(mHeight);
                                dos.flush();

                                if (Log.isLoggable(TAG, Log.DEBUG)) {
                                    Log.d(TAG, "Wrote request");
                                }

                                byte[] inlen = new byte[4];
                                readFully(bis, inlen, 0, 4);
                                int len = ((inlen[0] & 0xFF) << 24) | ((inlen[1] & 0xFF) << 16)
                                        | ((inlen[2] & 0xFF) << 8) | (inlen[3] & 0xFF);
                                readFully(bis, mImageData, 0, len);

                                Bitmap bm = BitmapFactory.decodeByteArray(mImageData, 0, len);
                                mHandler.sendMessage(mHandler.obtainMessage(1, bm));
                            }
                        }

                        bis.close();
                        dos.close();
                        socket.close();
                        server.close();
                        return;
                    } catch (Exception e) {
                        Log.e(TAG, "Exception transferring data", e);
                        mHandler.sendMessage(mHandler.obtainMessage(1, null));
                    }
                }
            }
        }
    };
}
