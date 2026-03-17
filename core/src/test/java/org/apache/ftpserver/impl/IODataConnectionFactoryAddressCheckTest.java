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

import org.junit.Test;

import java.net.Inet6Address;
import java.net.InetAddress;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IODataConnectionFactoryAddressCheckTest {

    @Test
    public void passiveIpCheckTreatsMappedIpv4AsSameAddress() throws Exception {
        InetAddress ipv4 = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        InetAddress mappedIpv4 = mappedIpv4(127, 0, 0, 1);

        assertTrue(IODataConnectionFactory.isSameAddressForPassiveIpCheck(ipv4, mappedIpv4));
        assertTrue(IODataConnectionFactory.isSameAddressForPassiveIpCheck(mappedIpv4, ipv4));
    }

    @Test
    public void passiveIpCheckRejectsDifferentAddresses() throws Exception {
        InetAddress expected = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        InetAddress actual = InetAddress.getByAddress(new byte[] {127, 0, 0, 2});

        assertFalse(IODataConnectionFactory.isSameAddressForPassiveIpCheck(expected, actual));
    }

    @Test
    public void passiveIpCheckSupportsNativeIpv6Equality() throws Exception {
        InetAddress expected = InetAddress.getByName("::1");
        InetAddress actual = InetAddress.getByName("0:0:0:0:0:0:0:1");

        assertTrue(IODataConnectionFactory.isSameAddressForPassiveIpCheck(expected, actual));
    }

    private InetAddress mappedIpv4(int a, int b, int c, int d) throws Exception {
        byte[] mapped = new byte[16];
        mapped[10] = (byte) 0xFF;
        mapped[11] = (byte) 0xFF;
        mapped[12] = (byte) a;
        mapped[13] = (byte) b;
        mapped[14] = (byte) c;
        mapped[15] = (byte) d;
        return Inet6Address.getByAddress(null, mapped, -1);
    }
}
