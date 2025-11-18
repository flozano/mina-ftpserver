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
package org.apache.ftpserver.clienttests;

import org.apache.ftpserver.DataConnectionConfigurationFactory;

/**
 * Verifies that passive mode works correctly when multiplexPassivePorts is disabled (false).
 * This ensures backward compatibility with the original single-client-per-port behavior.
 *
 * Extends StoreTest to inherit all 23 file storage/upload tests and runs them in passive mode
 * with multiplexing explicitly disabled.
 */
public class StoreMultiplexDisabledTest extends StoreTest {

    @Override
    protected DataConnectionConfigurationFactory createDataConnectionConfigurationFactory() {
        DataConnectionConfigurationFactory factory = new DataConnectionConfigurationFactory();
        // Explicitly disable multiplexing to test backward compatibility
        factory.setMultiplexPassivePorts(false);
        // Use default passiveIpCheck=false for local testing
        factory.setPassivePorts("50000-50010");
        return factory;
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        client.setRemoteVerificationEnabled(false);
        client.enterLocalPassiveMode();
    }
}
