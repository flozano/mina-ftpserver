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
}
