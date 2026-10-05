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

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.net.ftp.FTPClient;
import org.apache.ftpserver.DataConnectionConfigurationFactory;

/**
 * The passive IP check with a prefix: the control connection comes from 127.0.0.1 and the data
 * connection from another loopback address, in the same /24 or not.
 * <p>
 * A rejected data connection ends the STOR with 551: the check closes the accepted socket and the
 * transfer then fails, as it always has with the exact check.
 * <p>
 * Each test needs its data address to be bindable. Linux routes all of 127.0.0.0/8 to the loopback
 * interface; other systems may only have 127.0.0.1, and there the tests that need another address
 * return without asserting.
 */
public class PassiveIpCheckPrefixTest extends ClientTestTemplate {

    private static final int REJECTED = 551;

    private static final Pattern PASV_PORT = Pattern.compile("\\((\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)\\)");

    private FTPClient control;

    /** The IPv4 prefix length for this test, from its name: ...Prefix24 or ...Prefix32. */
    private int prefixLength() {
        return getName().endsWith("Prefix24") ? 24 : 32;
    }

    @Override
    protected DataConnectionConfigurationFactory createDataConnectionConfigurationFactory() {
        DataConnectionConfigurationFactory factory = new DataConnectionConfigurationFactory();
        factory.setMultiplexPassivePorts(false);
        factory.setPassivePorts("23040-23045");
        factory.setPassiveIpCheck(true);
        factory.setPassiveIpCheckIpv4PrefixLength(prefixLength());
        return factory;
    }

    @Override
    protected boolean isConnectClient() {
        return false;
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        control = createFTPClient();
        control.connect("127.0.0.1", getListenerPort());
        assertTrue(control.login(ADMIN_USERNAME, ADMIN_PASSWORD));
    }

    @Override
    protected void tearDown() throws Exception {
        if (control != null && control.isConnected()) {
            control.disconnect();
        }
        super.tearDown();
    }

    public void testSameAddressIsAcceptedWithPrefix32() throws Exception {
        assertEquals(226, storeWithDataFrom("127.0.0.1"));
    }

    public void testNeighbourIsRejectedWithPrefix32() throws Exception {
        if (!bindable("127.0.0.2")) {
            return;
        }
        assertEquals(REJECTED, storeWithDataFrom("127.0.0.2"));
    }

    public void testNeighbourIsAcceptedWithPrefix24() throws Exception {
        if (!bindable("127.0.0.2")) {
            return;
        }
        assertEquals(226, storeWithDataFrom("127.0.0.2"));
    }

    public void testOtherNetworkIsRejectedWithPrefix24() throws Exception {
        if (!bindable("127.0.1.2")) {
            return;
        }
        assertEquals(REJECTED, storeWithDataFrom("127.0.1.2"));
    }

    /**
     * Sends PASV, opens the data connection from <code>dataAddress</code>, then STOR, and returns
     * the reply that ends the transfer.
     */
    private int storeWithDataFrom(String dataAddress) throws Exception {
        assertEquals(227, control.sendCommand("PASV"));
        Matcher m = PASV_PORT.matcher(control.getReplyString());
        assertTrue(control.getReplyString(), m.find());
        int port = Integer.parseInt(m.group(5)) * 256 + Integer.parseInt(m.group(6));

        try (Socket data = new Socket()) {
            data.bind(new InetSocketAddress(InetAddress.getByName(dataAddress), 0));
            data.connect(new InetSocketAddress("127.0.0.1", port), 10000);

            assertEquals(150, control.sendCommand("STOR", "from-" + dataAddress + ".txt"));
            try {
                OutputStream out = data.getOutputStream();
                out.write("data".getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException rejected) {
                // the server may already have closed it
            }
        }
        return control.getReply();
    }

    private static boolean bindable(String address) {
        try (Socket s = new Socket()) {
            s.bind(new InetSocketAddress(InetAddress.getByName(address), 0));
            return true;
        } catch (IOException e) {
            System.err.println("Skipping: cannot bind " + address + " (" + e.getMessage() + ")");
            return false;
        }
    }
}
