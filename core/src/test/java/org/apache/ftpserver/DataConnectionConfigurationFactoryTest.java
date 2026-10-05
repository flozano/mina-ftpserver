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
package org.apache.ftpserver;

import java.util.Arrays;
import java.util.List;

import org.apache.ftpserver.impl.DefaultDataConnectionConfiguration;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DataConnectionConfigurationFactoryTest {

    @Test
    public void maxTotalPassiveReservationsDefaultsToAuto() {
        DataConnectionConfigurationFactory factory = new DataConnectionConfigurationFactory();
        assertEquals(0, factory.getMaxTotalPassiveReservations());
    }

    @Test
    public void negativeMaxTotalPassiveReservationsRejected() {
        DataConnectionConfigurationFactory factory = new DataConnectionConfigurationFactory();
        try {
            factory.setMaxTotalPassiveReservations(-1);
            fail("Negative max total passive reservations should be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void maxTotalPassiveReservationsPropagatesToConfiguration() {
        DataConnectionConfigurationFactory factory = new DataConnectionConfigurationFactory();
        factory.setPassivePorts("2121-2122");
        factory.setMultiplexPassivePorts(true);
        factory.setMaxTotalPassiveReservations(42);

        DataConnectionConfiguration config = factory.createDataConnectionConfiguration();
        assertTrue(config instanceof DefaultDataConnectionConfiguration);
        assertEquals(42, ((DefaultDataConnectionConfiguration) config).getMaxTotalPassiveReservations());
    }

    /**
     * Regression guard for a deadlock that is easy to reintroduce.
     * <p>
     * The waiting overload must NOT be <code>synchronized</code>. Waiting happens inside
     * PassivePorts, but releasing goes through {@link DefaultDataConnectionConfiguration
     * #releasePassivePort(int)}, which IS synchronized on the configuration object. If the waiter
     * held that same monitor, no release could ever run and the wait could only ever end at the
     * timeout. This test fails with -1 if that happens.
     */
    @Test
    public void waitingForAPassivePortDoesNotLockOutTheRelease() throws Exception {
        DataConnectionConfigurationFactory factory = new DataConnectionConfigurationFactory();
        factory.setPassivePorts("60123");
        final DataConnectionConfiguration config = factory.createDataConnectionConfiguration();
        final List<Integer> allowed = Arrays.asList(Integer.valueOf(60123));

        assertEquals(60123, config.requestPassivePort(allowed, 0));

        Thread releaser = new Thread(new Runnable() {
            public void run() {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                config.releasePassivePort(60123);
            }
        });
        releaser.start();

        int reserved = config.requestPassivePort(allowed, 5000);
        releaser.join();

        assertEquals("release was locked out by the waiter", 60123, reserved);
    }

    @Test
    public void passiveIpCheckComparesWholeAddressesByDefault() {
        DataConnectionConfiguration config = new DataConnectionConfigurationFactory().createDataConnectionConfiguration();

        assertEquals(32, config.getPassiveIpCheckIpv4PrefixLength());
        assertEquals(128, config.getPassiveIpCheckIpv6PrefixLength());
    }

    @Test
    public void passiveIpCheckPrefixLengthsReachTheConfiguration() {
        DataConnectionConfigurationFactory factory = new DataConnectionConfigurationFactory();
        factory.setPassiveIpCheckIpv4PrefixLength(24);
        factory.setPassiveIpCheckIpv6PrefixLength(64);
        DataConnectionConfiguration config = factory.createDataConnectionConfiguration();

        assertEquals(24, config.getPassiveIpCheckIpv4PrefixLength());
        assertEquals(64, config.getPassiveIpCheckIpv6PrefixLength());
    }

    @Test
    public void passiveIpCheckPrefixLengthsAreValidated() {
        DataConnectionConfigurationFactory factory = new DataConnectionConfigurationFactory();
        for (int invalid : new int[] {-1, 33}) {
            try {
                factory.setPassiveIpCheckIpv4PrefixLength(invalid);
                fail("accepted IPv4 prefix length " + invalid);
            } catch (FtpServerConfigurationException expected) {
                // expected
            }
        }
        for (int invalid : new int[] {-1, 129}) {
            try {
                factory.setPassiveIpCheckIpv6PrefixLength(invalid);
                fail("accepted IPv6 prefix length " + invalid);
            } catch (FtpServerConfigurationException expected) {
                // expected
            }
        }
    }
}
