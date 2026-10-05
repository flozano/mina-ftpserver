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

import java.net.InetSocketAddress;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.ssl.SslFilter;

/**
 * <strong>Internal class, do not use directly.</strong>
 * <p>
 * An {@link SslFilter} that never looks up the client's host name.
 * <p>
 * MINA's {@code SslFilter.createEngine} (2.2.5 to at least 2.2.9) creates the engine with
 * {@code addr.getHostName()}, a reverse DNS lookup of the client IP. It does so in
 * {@code onConnected}, which is {@code synchronized} on the filter and, for implicit FTPS, runs on
 * the I/O processor thread. For a client IP without a PTR record the lookup only gives up after the
 * resolver times out (11-20 s measured in production). Meanwhile that processor serves none of its
 * sessions, so their replies are not written, and every other new TLS connection queues on the
 * filter's lock and stalls its own processor too. Lookups for several such clients in the same minute
 * run back to back.
 * <p>
 * A server-side engine does not need the peer's name: it is only a hint for session caching. The
 * engine is created from an unresolved address that carries the IP string, which
 * {@code getHostName()} returns as is, without a lookup.
 */
public class NoReverseDnsSslFilter extends SslFilter {

    public NoReverseDnsSslFilter(SSLContext sslContext) {
        super(sslContext);
    }

    @Override
    protected SSLEngine createEngine(IoSession session, InetSocketAddress addr) {
        return super.createEngine(session, withoutReverseLookup(addr));
    }

    /**
     * @return {@code addr} as an unresolved address whose host is its IP string (or its host name,
     *         if it was created with one), so that {@code getHostName()} does not query DNS
     */
    static InetSocketAddress withoutReverseLookup(InetSocketAddress addr) {
        if (addr == null || addr.isUnresolved()) {
            return addr;
        }
        return InetSocketAddress.createUnresolved(addr.getHostString(), addr.getPort());
    }
}
