/*
 * Copyright (C) 2014 The Android Open Source Project
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

package net.kibotu.autonomous.logging.server;

import android.annotation.SuppressLint;
import android.content.res.AssetManager;
import android.net.Uri;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static android.text.TextUtils.isEmpty;


/**
 * Implementation of a very basic HTTP server. The contents are loaded from the assets folder. This
 * server handles one request at a time. It only supports GET method.
 */
public class SimpleWebServer implements Runnable {

    private static final String TAG = SimpleWebServer.class.getSimpleName();

    public static final BlockingQueue<ResponseMessage> queue = new LinkedBlockingQueue<>();

    /**
     * The port number we listen to
     */
    private final int mPort;

    /**
     * {@link android.content.res.AssetManager} for loading files to serve.
     */
    private final AssetManager mAssets;

    /**
     * True if the server is running.
     */
    private boolean mIsRunning;

    /**
     * The {@link java.net.ServerSocket} that we listen to.
     */
    private ServerSocket mServerSocket;

    /**
     * WebServer constructor.
     */
    public SimpleWebServer(int port, AssetManager assets) {
        mPort = port;
        mAssets = assets;
    }

    /**
     * This method starts the web server listening to the specified port.
     */
    public void start() {
        Log.v(TAG, "[start]");
        mIsRunning = true;
        new Thread(this).start();
    }

    /**
     * This method stops the web server
     */
    public void stop() {
        Log.v(TAG, "[stop]");
        try {
            mIsRunning = false;
            if (null != mServerSocket) {
                mServerSocket.close();
                mServerSocket = null;
            }
        } catch (IOException e) {
            Log.e(TAG, "Error closing the server socket.", e);
        }
    }

    @Override
    public void run() {
        Log.v(TAG, "[run]");

        try {
            // Logger.v(TAG, "[run] Found free port at: " + port);

            mServerSocket = new ServerSocket(mPort);
            Log.v(TAG, "[run] Listening to port=" + mServerSocket.getLocalPort());

            while (mIsRunning) {
                Socket socket = mServerSocket.accept();
                handle(socket);
                closeSilently(socket);
            }
        } catch (IOException e) {
            Log.v(TAG, "" + e.getMessage());
        }
    }

    /**
     * Respond to a request from a client.
     *
     * @param socket The client socket.
     * @throws IOException
     */
    private void handle(Socket socket) throws IOException {
        BufferedReader reader = null;
        PrintStream output = null;
        try {
            // Read HTTP headers and parse out the route.
            String pathAndParams = null;
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            String line;
            while (!isEmpty(line = reader.readLine())) {
                if (line.startsWith("GET /")) {
                    int start = line.indexOf('/') + 1;
                    int end = line.indexOf(' ', start);
                    pathAndParams = line.substring(start, end);
                    break;
                }
            }

            // Output stream that we send the response to
            output = new PrintStream(socket.getOutputStream());

            // Prepare the content to send.
            if (isEmpty(pathAndParams)) {
                pathAndParams = "index.html";
            }

            Uri uri = parseUri(pathAndParams);

            Log.v(TAG, "[request] " + uri);

            byte[] bytes = createResponse(pathAndParams);
            if (null == bytes) {
                writeServerError(output);
                return;
            }

            // Send out the content.
            output.println("HTTP/1.0 200 OK");
            output.println("Content-Type: " + detectMimeType(uri.getPath()));
            output.println("Content-Length: " + bytes.length);
            output.println();
            output.write(bytes);
            output.flush();
        } finally {
            closeSilently(output);
            closeSilently(reader);
        }
    }

    public static class ResponseMessage {

        static SimpleDateFormat dateFormat = new SimpleDateFormat("yyy-MM-dd HH:mm:ss.SSS");

        public final String message;
        public final String time;

        public ResponseMessage(String message) {
            this.message = message;
            time = dateFormat.format(new Date());
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            ResponseMessage that = (ResponseMessage) o;

            if (message != null ? !message.equals(that.message) : that.message != null)
                return false;
            return time != null ? time.equals(that.time) : that.time == null;
        }

        @Override
        public int hashCode() {
            int result = message != null ? message.hashCode() : 0;
            result = 31 * result + (time != null ? time.hashCode() : 0);
            return result;
        }

        @Override
        public String toString() {
            return "ResponseMessage{" +
                    "message='" + message + '\'' +
                    ", time='" + time + '\'' +
                    '}';
        }
    }

    private byte[] createResponse(String pathAndParams) throws IOException {

        if (pathAndParams.equals("messages.json")) {

            List<ResponseMessage> list = new ArrayList<>();
            while (!queue.isEmpty())
                list.add(queue.poll());

            String s = GsonUtil.toJson(list);
            Log.v(TAG, "[response] " + s);
            return s.getBytes(StandardCharsets.UTF_8);

        } else
            return loadContent(pathAndParams);
    }

    /**
     * Writes a server error response (HTTP/1.0 500) to the given output stream.
     *
     * @param output The output stream.
     */
    private void writeServerError(PrintStream output) {
        output.println("HTTP/1.0 500 Internal Server Error");
        output.flush();
    }

    /**
     * Loads all the content of {@code fileName}.
     *
     * @param fileName The name of the file.
     * @return The content of the file.
     * @throws IOException
     */
    private byte[] loadContent(String fileName) throws IOException {
        InputStream input = null;
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            input = mAssets.open(fileName);
            byte[] buffer = new byte[1024];
            int size;
            while (-1 != (size = input.read(buffer))) {
                output.write(buffer, 0, size);
            }
            output.flush();
            return output.toByteArray();
        } catch (FileNotFoundException e) {
            return null;
        } finally {
            closeSilently(input);
        }
    }

    /**
     * Detects the MIME type from the {@code fileName}.
     *
     * @param fileName The name of the file.
     * @return A MIME type.
     */
    private String detectMimeType(String fileName) {
        if (isEmpty(fileName)) {
            return null;
        } else if (fileName.endsWith(".html")) {
            return "text/html";
        } else if (fileName.endsWith(".js")) {
            return "application/javascript";
        } else if (fileName.endsWith(".css")) {
            return "text/css";
        } else if (fileName.endsWith(".json")) {
            return "application/json";
        } else {
            return "application/octet-stream";
        }
    }

    private Uri parseUri(String url) throws UnsupportedEncodingException {
        return Uri.parse(URLDecoder.decode(url, "UTF-8"));
    }

    /**
     * Quietly close a {@link AutoCloseable} dealing with nulls and exceptions.
     *
     * @param closeable to be closed.
     */
    @SuppressLint("NewApi")
    public static void closeSilently(final AutoCloseable closeable) {
        try {
            if (null != closeable) {
                Log.v(TAG, "[closeSilently] " + closeable.getClass().getSimpleName());
                closeable.close();
            }
        } catch (final Exception ignore) {
        }
    }
}