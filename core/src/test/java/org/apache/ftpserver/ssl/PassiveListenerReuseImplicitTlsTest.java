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
package org.apache.ftpserver.ssl;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.net.ftp.FTPSClient;
import org.apache.ftpserver.DataConnectionConfigurationFactory;

/**
 * Implicit TLS with an unused passive listener re-advertised: the inherited secure transfer tests,
 * and a PROT P upload through a listener that was advertised twice.
 */
public class PassiveListenerReuseImplicitTlsTest extends ImplicitSecurityTestTemplate {

    private static final Pattern PASV_PORT = Pattern.compile("\\((\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)\\)");

    @Override
    protected String getAuthValue() {
        return "TLSv1.2";
    }

    @Override
    protected DataConnectionConfigurationFactory createDataConnectionConfigurationFactory() {
        DataConnectionConfigurationFactory factory = super.createDataConnectionConfigurationFactory();
        factory.setImplicitSsl(true);
        factory.setMultiplexPassivePorts(false);
        factory.setPassivePorts("50073-50074");
        factory.setPassiveReuseUnusedListener(true);
        return factory;
    }

    @Override
    protected boolean expectDataConnectionSecure() {
        return true;
    }

    private int pasv() throws Exception {
        assertEquals(227, client.sendCommand("PASV"));
        Matcher m = PASV_PORT.matcher(client.getReplyString());
        assertTrue(client.getReplyString(), m.find());
        return Integer.parseInt(m.group(5)) * 256 + Integer.parseInt(m.group(6));
    }

    public void testProtPUploadThroughAReadvertisedListener() throws Exception {
        client.setRemoteVerificationEnabled(false);
        ((FTPSClient) client).execPBSZ(0);
        ((FTPSClient) client).execPROT("P");

        // without reuse the pool may pick the same port again by chance: ask ten times
        int first = pasv();
        for (int i = 0; i < 10; i++) {
            assertEquals("PASV " + (i + 2) + " must re-advertise the listener", first, pasv());
        }

        // storeFile sends a third PASV, which gets the same listener again
        client.enterLocalPassiveMode();
        byte[] data = "secure-data".getBytes(StandardCharsets.UTF_8);
        assertTrue(client.storeFile("tls.txt", new ByteArrayInputStream(data)));
        assertEquals("secure-data", new String(Files.readAllBytes(new java.io.File(ROOT_DIR, "tls.txt").toPath()),
                StandardCharsets.UTF_8));
    }
}
