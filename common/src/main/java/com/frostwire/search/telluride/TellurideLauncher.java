/*
 * Created by Angel Leon (@gubatron)
 * Copyright (c) 2007-2021, FrostWire(R). All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.frostwire.search.telluride;

import com.frostwire.concurrent.concurrent.ThreadExecutor;
import com.frostwire.util.HttpClientFactory;
import com.frostwire.util.Logger;
import com.frostwire.util.http.HttpClient;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A Java process builder and parser for the telluride cloud downloader.
 * Launches the process passing the appropiate options, parses its output to dispatch events to a TellurideListener
 */
public final class TellurideLauncher {

    private static final Logger LOG = Logger.getLogger(TellurideLauncher.class);

    public static AtomicBoolean SERVER_UP = new AtomicBoolean(false);

    public static void shutdownServer(final int port) {
        ThreadExecutor.startThread(() -> {
            HttpClient httpClient = HttpClientFactory.newInstance();
            try {
                httpClient.get(String.format("http://127.0.0.1:%d/?shutdown=1", port));
                SERVER_UP.set(false);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }, "TellurideLauncher::shutDownServer(" + port + ")");
    }

    public static void launchServer(final File executable,
                                    final int port,
                                    final File saveDirectory) {
        if (SERVER_UP.get()) {
            LOG.info("TellurideLauncher::launchServer aborted, server already up.");
            return;
        }
        checkExecutable(executable);
        if (port < 8080) {
            throw new IllegalArgumentException("TellurideLauncher::launchServer Please use a port greater or equal to 8080 (do not run frostwire as root). Telluride's default port number is 47999");
        }
        ThreadExecutor.startThread(() -> {
                    ProcessBuilder processBuilder = new ProcessBuilder(executable.getAbsolutePath(), "--server", String.valueOf(port));
                    processBuilder.redirectErrorStream(true);
                    if (saveDirectory != null && saveDirectory.isDirectory() && saveDirectory.canWrite()) {
                        processBuilder.directory(saveDirectory);
                    }
                    try {
                        Process process = processBuilder.start();
                        // The telluride process doesn't start right away, we wait 8 seconds to be safe before
                        // pinging to set the SERVER_UP flag to true.
                        // TellurideSearchPerformer waits for up to 10 seconds to give up
                        Thread.currentThread().sleep(8000);
                        LOG.info("TellurideLauncher::launchServer is process alive: " + process.isAlive());
                        LOG.info("TellurideLauncher::launchServer RPC server should be up at http://127.0.0.1:" + port);
                        LOG.info("TellurideLauncher::launchServer pinging...");
                        SERVER_UP.set(TellurideLauncher.checkIfUpAlready(port));
                        if (!SERVER_UP.get()) {
                            LOG.info("TellurideLauncher::launchServer could not get a pong back from Telluride");
                        } else {
                            LOG.info("TellurideLauncher::launchServer success, got a pong back from Telluride");
                        }

                    } catch (Throwable e) {
                        SERVER_UP.set(false);
                        LOG.error("TellurideLauncher::launchServer error: " + e.getMessage(),e);
                    }
                },
                "telluride-server-on-port-" + port);
    }

    /**
     * We're not using this method anymore, we now communicate with Telluride via HTTP
     * We're leaving this code for unit test purposes.
     *
     * @param metaOnly Get only metadata about the downloadUrl. If set to true, it ignores audioOnly
     */
    public static void launch(final File executable,
                              final String downloadUrl,
                              final File saveDirectory,
                              final boolean audioOnly,
                              final boolean metaOnly,
                              final boolean verboseOutput,
                              TellurideListener processListener) {
        checkExecutable(executable);
        ThreadExecutor.startThread(
                launchRunnable(
                        executable,
                        downloadUrl,
                        saveDirectory,
                        audioOnly,
                        metaOnly,
                        verboseOutput,
                        processListener),
                "telluride-process-adapter:" + downloadUrl);
    }

    private static void checkExecutable(final File executable) {
        if (executable == null) {
            throw new IllegalArgumentException("TellurideLauncher::checkExecutable executable path is null, no telluride to launch");
        }
        if (!executable.isFile()) {
            throw new IllegalArgumentException("TellurideLauncher::checkExecutable " + executable + " is not a file");
        }
        if (!executable.canExecute()) {
            throw new IllegalArgumentException("TellurideLauncher::checkExecutable " + executable + " is not executable");
        }
    }

    private static Runnable launchRunnable(final File executable,
                                           final String downloadUrl,
                                           final File saveDirectory,
                                           final boolean audioOnly,
                                           final boolean metaOnly,
                                           final boolean verboseOutput,
                                           TellurideListener processListener) {
        return () -> {
            ProcessBuilder processBuilder = new ProcessBuilder(executable.getAbsolutePath(), downloadUrl);
            processBuilder.redirectErrorStream(true); // merge stderr into stdout
            if (audioOnly) {
                processBuilder = new ProcessBuilder(
                        executable.getAbsolutePath(),
                        "-a",
                        downloadUrl);
            }
            if (metaOnly) {
                processBuilder = new ProcessBuilder(
                        executable.getAbsolutePath(),
                        "-m",
                        downloadUrl);
            }
            if (saveDirectory != null && saveDirectory.isDirectory() && saveDirectory.canWrite()) {
                processBuilder.directory(saveDirectory);
            }
            try {
                final Process process = processBuilder.start();
                final InputStream stdOut = process.getInputStream();
                final BufferedReader reader = new BufferedReader(new InputStreamReader(stdOut));
                TellurideParser parser = null;
                if (processListener != null) {
                    parser = new TellurideParser(processListener, metaOnly);
                }
                String line;
                while ((line = reader.readLine()) != null) {
                    if (verboseOutput) {
                        System.out.println("TellurideLauncher::launchRunnable [" + executable.getName() + "] " + line);
                    }
                    if (processListener != null && processListener.aborted()) {
                        process.destroyForcibly();
                        break;
                    }
                    if (parser != null) {
                        parser.parse(line);
                    }
                }
                process.waitFor();
                if (verboseOutput) {
                    System.out.println("TellurideLauncher::launchRunnable Exit-Code: " + process.exitValue());
                }
                if (parser != null) {
                    parser.done();
                }
                if (processListener != null) {
                    processListener.onFinished(process.exitValue());
                }
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
            }
        };
    }

    private static class TelluridePong {
        int build;
        String message;
    }

    public static boolean checkIfUpAlready(int port) {
        HttpClient httpClient = HttpClientFactory.newInstance();
        try {
            LOG.info("TellurideLauncher.checkIfUpAlready() checking...");
            String json = httpClient.get(String.format("http://127.0.0.1:%d/ping", port), 250);
            Gson gson = new GsonBuilder().create();
            TelluridePong telluridePong = gson.fromJson(json, TelluridePong.class);
            return telluridePong != null && telluridePong.message.equalsIgnoreCase("pong");
        } catch (IOException e) {
            LOG.info("TellurideLauncher.checkIfUpAlready() not up, let's go.\n" + e.getMessage());
            return false;
        }
    }
}
