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
package org.apache.ftpserver.ssl.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.net.InetAddress;
import java.net.InetSocketAddress;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

import org.apache.mina.core.session.DummySession;
import org.junit.Test;

/**
 * Creating the engine for a new TLS session must not reverse-resolve the client IP: for an IP with no
 * PTR record that lookup blocks for the resolver's whole timeout, on the I/O processor thread and
 * under the filter's lock.
 * <p>
 * An {@link InetAddress} built from raw bytes has no host name until something resolves it, and
 * {@link InetAddress#toString()} shows one once it has ({@code "name/192.0.2.10"} instead of
 * {@code "/192.0.2.10"}). That makes "no lookup happened" checkable without depending on DNS.
 */
public class NoReverseDnsSslFilterTest {

    /** TEST-NET-1 (RFC 5737): never routed, no PTR record anywhere. */
    private static final byte[] CLIENT_IP = { (byte) 192, 0, 2, 10 };

    private static NoReverseDnsSslFilter filter() throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, null, null);
        return new NoReverseDnsSslFilter(context);
    }

    @Test
    public void theEngineIsCreatedWithoutResolvingTheClientIp() throws Exception {
        InetAddress clientIp = InetAddress.getByAddress(CLIENT_IP);
        InetSocketAddress client = new InetSocketAddress(clientIp, 51234);

        SSLEngine engine = filter().createEngine(new DummySession(), client);

        assertEquals("192.0.2.10", engine.getPeerHost());
        assertEquals(51234, engine.getPeerPort());
        assertFalse("a server engine", engine.getUseClientMode());
        assertEquals("the client IP was resolved", "/192.0.2.10", clientIp.toString());
    }

    @Test
    public void anAddressThatCarriesAHostNameKeepsIt() {
        InetSocketAddress named = InetSocketAddress.createUnresolved("pos.example", 2990);
        assertSame(named, NoReverseDnsSslFilter.withoutReverseLookup(named));
    }

    @Test
    public void aResolvedAddressBecomesItsIpString() throws Exception {
        InetSocketAddress resolved = new InetSocketAddress(InetAddress.getByAddress(CLIENT_IP), 2990);

        InetSocketAddress plain = NoReverseDnsSslFilter.withoutReverseLookup(resolved);

        assertTrue(plain.isUnresolved());
        assertEquals("192.0.2.10", plain.getHostName());
        assertEquals(2990, plain.getPort());
    }

    @Test
    public void noAddressStaysNoAddress() {
        assertNull(NoReverseDnsSslFilter.withoutReverseLookup(null));
    }
}
