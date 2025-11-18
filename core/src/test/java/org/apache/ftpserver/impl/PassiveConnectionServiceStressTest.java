/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.ftpserver.impl;

import org.apache.ftpserver.DataConnectionException;
import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Stress tests for PassiveConnectionService to verify behavior under high concurrency.
 */
public class PassiveConnectionServiceStressTest {

    private PassiveConnectionService service;

    @After
    public void tearDown() {
        if (service != null) {
            service.stop();
        }
    }

    @Test
    public void highConcurrencySinglePort() throws Exception {
        int port = randomPort();
        service = new PassiveConnectionService(Collections.singleton(port), null);
        service.start();

        int clientCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(clientCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(clientCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();

        try {
            // Create many concurrent clients
            for (int i = 0; i < clientCount; i++) {
                final int clientId = i;
                futures.add(executor.submit(() -> {
                    try {
                        InetAddress clientAddr = InetAddress.getByName("127.0.0." + (clientId + 2));

                        // Wait for all threads to be ready
                        startLatch.await();

                        // Register reservation
                        PassiveConnectionService.Reservation res = service.register(clientAddr);

                        // Simulate connection
                        FakeSocket socket = new FakeSocket(clientAddr);
                        service.deliverAccepted(port, socket);

                        Socket delivered = res.await(5000);
                        if (delivered != null) {
                            successCount.incrementAndGet();
                        } else {
                            failureCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        failureCount.incrementAndGet();
                    } finally {
                        doneLatch.countDown();
                    }
                }));
            }

            // Start all threads simultaneously
            startLatch.countDown();

            // Wait for all to complete
            assertTrue("All threads should complete within 30 seconds",
                    doneLatch.await(30, TimeUnit.SECONDS));

            // Verify results
            assertTrue("Should have at least some successes", successCount.get() > 0);
            assertEquals("All attempts should complete", clientCount,
                    successCount.get() + failureCount.get());

        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void highConcurrencyMultiplePorts() throws Exception {
        Set<Integer> ports = new HashSet<>();
        for (int i = 0; i < 10; i++) {
            ports.add(randomPort());
        }

        service = new PassiveConnectionService(ports, null);
        service.start();

        int clientCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(clientCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(clientCount);
        AtomicInteger successCount = new AtomicInteger(0);

        try {
            for (int i = 0; i < clientCount; i++) {
                final int clientId = i;
                executor.submit(() -> {
                    try {
                        InetAddress clientAddr = InetAddress.getByName("127.0.0." + ((clientId % 250) + 2));

                        startLatch.await();

                        PassiveConnectionService.Reservation res = service.register(clientAddr);
                        FakeSocket socket = new FakeSocket(clientAddr);
                        service.deliverAccepted(res.getPort(), socket);

                        Socket delivered = res.await(5000);
                        if (delivered != null) {
                            successCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        // Some may fail due to per-IP limits, that's ok
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            assertTrue("All threads should complete", doneLatch.await(30, TimeUnit.SECONDS));
            assertTrue("Should have many successes", successCount.get() >= 10);

        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void rapidRegisterCancelCycles() throws Exception {
        int port = randomPort();
        service = new PassiveConnectionService(Collections.singleton(port), null);
        service.start();

        int cycleCount = 100;
        ExecutorService executor = Executors.newFixedThreadPool(5);
        AtomicInteger successCount = new AtomicInteger(0);

        try {
            List<Future<?>> futures = new ArrayList<>();

            for (int i = 0; i < cycleCount; i++) {
                final int iteration = i;
                futures.add(executor.submit(() -> {
                    try {
                        InetAddress clientAddr = InetAddress.getByName("127.0.0." + ((iteration % 250) + 2));
                        PassiveConnectionService.Reservation res = service.register(clientAddr);

                        // Random delay to create contention
                        if (iteration % 3 == 0) {
                            Thread.sleep(1);
                        }

                        service.cancel(res);
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        // Some failures are ok due to contention
                    }
                }));
            }

            // Wait for all to complete
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }

            assertTrue("Should complete most cycles successfully", successCount.get() >= cycleCount * 0.9);

        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void sustainedLoadFor30Seconds() throws Exception {
        Set<Integer> ports = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            ports.add(randomPort());
        }

        service = new PassiveConnectionService(ports, null);
        service.start();

        ExecutorService executor = Executors.newFixedThreadPool(10);
        AtomicInteger totalOperations = new AtomicInteger(0);
        AtomicInteger errors = new AtomicInteger(0);
        long endTime = System.currentTimeMillis() + 30000; // 30 seconds

        try {
            List<Future<?>> workers = new ArrayList<>();

            for (int i = 0; i < 10; i++) {
                final int workerId = i;
                workers.add(executor.submit(() -> {
                    int operationCount = 0;
                    while (System.currentTimeMillis() < endTime) {
                        try {
                            InetAddress clientAddr = InetAddress.getByName(
                                    "127.0.0." + ((workerId * 25 + operationCount) % 250 + 2));

                            PassiveConnectionService.Reservation res = service.register(clientAddr);

                            // Simulate some work
                            Thread.sleep(10);

                            service.cancel(res);
                            operationCount++;
                            totalOperations.incrementAndGet();
                        } catch (DataConnectionException e) {
                            // Expected when per-IP limit is reached
                        } catch (Exception e) {
                            errors.incrementAndGet();
                        }
                    }
                    return operationCount;
                }));
            }

            // Wait for all workers to finish
            for (Future<?> worker : workers) {
                worker.get(35, TimeUnit.SECONDS);
            }

            assertTrue("Should complete many operations", totalOperations.get() > 100);
            assertTrue("Error rate should be low", errors.get() < totalOperations.get() * 0.05);

        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void memoryLeakCheck() throws Exception {
        int port = randomPort();
        service = new PassiveConnectionService(Collections.singleton(port), null);
        service.start();

        Runtime runtime = Runtime.getRuntime();
        runtime.gc();
        Thread.sleep(100);
        long initialMemory = runtime.totalMemory() - runtime.freeMemory();

        // Perform many registration/cancellation cycles
        for (int i = 0; i < 1000; i++) {
            InetAddress clientAddr = InetAddress.getByName("127.0.0." + ((i % 250) + 2));
            PassiveConnectionService.Reservation res = service.register(clientAddr);
            service.cancel(res);
        }

        runtime.gc();
        Thread.sleep(100);
        long finalMemory = runtime.totalMemory() - runtime.freeMemory();

        long memoryGrowth = finalMemory - initialMemory;

        // Memory should not grow significantly (allowing 5MB growth for normal variance)
        assertTrue("Memory growth should be minimal after 1000 cycles: " + memoryGrowth + " bytes",
                memoryGrowth < 5 * 1024 * 1024);
    }

    private int randomPort() throws IOException {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }

    private static class FakeSocket extends Socket {
        private final InetAddress remote;

        FakeSocket(InetAddress remote) {
            this.remote = remote;
        }

        @Override
        public InetSocketAddress getRemoteSocketAddress() {
            return new InetSocketAddress(remote, 12345);
        }
    }
}
