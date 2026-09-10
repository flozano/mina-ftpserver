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

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <strong>Internal class, do not use directly.</strong>
 *
 * Provides support for parsing a passive ports string as well as keeping track
 * of reserved passive ports.
 *
 * @author <a href="http://mina.apache.org">Apache MINA Project</a>
 */
public class PassivePorts {

    private Logger log = LoggerFactory.getLogger(PassivePorts.class);

    private static final int MAX_PORT = 65535;

    private static final Integer MAX_PORT_INTEGER = Integer.valueOf(MAX_PORT);

    private List<Integer> freeList;

    private Set<Integer> usedList;

    private Random r = new Random();

    private String passivePortsString;

    private boolean checkIfBound;

    /**
     * Parse a string containing passive ports
     *
     * @param portsString
     *            A string of passive ports, can contain a single port (as an
     *            integer), multiple ports seperated by commas (e.g.
     *            123,124,125) or ranges of ports, including open ended ranges
     *            (e.g. 123-125, 30000-, -1023). Combinations for single ports
     *            and ranges is also supported.
     * @return A list of Integer objects, based on the parsed string
     * @throws IllegalArgumentException
     *             If any of of the ports in the string is invalid (e.g. not an
     *             integer or too large for a port number)
     */
    private static Set<Integer> parse(final String portsString) {
        Set<Integer> passivePortsList = new HashSet<>();

        boolean inRange = false;
        Integer lastPort = Integer.valueOf(1);
        StringTokenizer st = new StringTokenizer(portsString, ",;-", true);

        while (st.hasMoreTokens()) {
            String token = st.nextToken().trim();

            if (",".equals(token) || ";".equals(token)) {
                if (inRange) {
                    fillRange(passivePortsList, lastPort, MAX_PORT_INTEGER);
                }

                // reset state
                lastPort = Integer.valueOf(1);
                inRange = false;
            } else if ("-".equals(token)) {
                inRange = true;
            } else if (token.length() == 0) {
                // ignore whitespace
            } else {
                Integer port = Integer.valueOf(token);

                verifyPort(port);

                if (inRange) {
                    // add all numbers from last int
                    fillRange(passivePortsList, lastPort, port);

                    inRange = false;
                }

                addPort(passivePortsList, port);

                lastPort = port;
            }
        }

        if (inRange) {
            fillRange(passivePortsList, lastPort, MAX_PORT_INTEGER);
        }

        return passivePortsList;
    }

    /**
     * Returns all configured ports, regardless of reservation state.
     *
     * @return set of configured passive ports
     */
    public synchronized Set<Integer> getPorts() {
        Set<Integer> ports = new HashSet<>(freeList.size() + usedList.size());
        ports.addAll(freeList);
        ports.addAll(usedList);
        return ports;
    }

    /**
     * Fill a range of ports
     */
    private static void fillRange(final Set<Integer> passivePortsList, final Integer beginPort, final Integer endPort) {
        for (int i = beginPort; i <= endPort; i++) {
            addPort(passivePortsList, Integer.valueOf(i));
        }
    }

    /**
     * Add a single port if not already in list
     */
    private static void addPort(final Set<Integer> passivePortsList, final Integer port) {
        passivePortsList.add(port);
    }

    /**
     * Verify that the port is within the range of allowed ports
     */
    private static void verifyPort(final int port) {
        if (port < 0) {
            throw new IllegalArgumentException("Port can not be negative: " + port);
        } else if (port > MAX_PORT) {
            throw new IllegalArgumentException("Port too large: " + port);
        }
    }

    /**
     * Create an instance with a port and the flag that tells
     * if the port should be checked before being used
     *
     * @param passivePorts The port
     * @param checkIfBound The check for port bound flag
     */
    public PassivePorts(final String passivePorts, boolean checkIfBound) {
        this(parse(passivePorts), checkIfBound);

        this.passivePortsString = passivePorts;
    }

    /**
     * Create an instance with a set of ports and the flag that tells
     * if the ports should be checked before being used
     *
     * @param passivePorts The set of ports
     * @param checkIfBound The check for ports bound flag
     */
    public PassivePorts(Set<Integer> passivePorts, boolean checkIfBound) {
        if (passivePorts == null) {
            throw new NullPointerException("passivePorts can not be null");
        } else if (passivePorts.isEmpty()) {
            passivePorts = new HashSet<>();
            passivePorts.add(0);
        }

        this.freeList = new ArrayList<>(passivePorts);
        this.usedList = new HashSet<>(passivePorts.size());

        this.checkIfBound = checkIfBound;
    }

    /**
     * Checks that the port of not bound by another application
     */
    private boolean checkPortUnbound(int port) {
        // is this check disabled?
        if (!checkIfBound) {
            return true;
        }

        // if using 0 port, it will always be available
        if (port == 0) {
            return true;
        }

        ServerSocket ss = null;
        try {
            ss = new ServerSocket(port);
            ss.setReuseAddress(true);
            return true;
        } catch (IOException e) {
            // port probably in use, check next
            return false;
        } finally {
            if (ss != null) {
                try {
                    ss.close();
                } catch (IOException e) {
                    // could not close, check next
                    return false;
                }
            }
        }
    }

    /**
     * Reserve the next port, picked at random from the free ports.
     *
     * @return The reserved port
     */
    public synchronized int reserveNextPort() {
        // create a copy of the free ports, so that we can keep track of the tested ports
        List<Integer> freeCopy = new ArrayList<>(freeList);

        // Loop until we have found a port, or exhausted all available ports
        while (freeCopy.size() > 0) {
            // Otherwise, pick one at random
            int i = r.nextInt(freeCopy.size());
            Integer ret = freeCopy.get(i);

            if (ret == 0) {
                // "Any" port should not be removed from our free list,
                // nor added to the used list
                return 0;

            } else if (checkPortUnbound(ret)) {
                // Not used by someone else, so lets reserve it and return it
                freeList.remove(ret);
                usedList.add(ret);
                return ret;

            } else {
                freeCopy.remove(i);
                // log port unavailable, but left in pool
                log.warn("Passive port in use by another process: " + ret);
            }
        }

        return -1;
    }

    /**
     * Reserve a port from a caller-supplied allow-list.
     * <p>
     * This is a restriction, not a hint. Only a port present in <code>allowedPorts</code> can be
     * reserved; when every one of them is unavailable this returns -1 rather than falling back to
     * the rest of the pool. Callers restricting a session to a subset therefore get a failure they
     * can report, never a port outside the subset.
     * <p>
     * The entries are tried in the order given, so a caller may also express a preference within
     * what it allows. Entries that are not among this instance's configured ports are ignored.
     * <p>
     * There is deliberately no value meaning "any port". Callers that intend no restriction use
     * {@link #reserveNextPort()} instead; nothing should pass an empty list, and nothing should
     * pass <code>null</code>. Both are tolerated rather than thrown, and both allow nothing, so a
     * caller whose allow-list computation goes wrong gets a visible failure instead of the run of
     * the whole pool.
     *
     * @param allowedPorts
     *            The only ports that may be reserved, most preferred first. Required.
     * @return The reserved port, or -1 if none of the allowed ports could be reserved
     */
    public synchronized int reserveNextPort(final List<Integer> allowedPorts) {
        return reserveNextPort(allowedPorts, 0L);
    }

    /**
     * Reserve a port from the caller's allow-list, waiting up to <code>timeoutMillis</code> for one
     * to be released if none is free right now.
     * <p>
     * A pool small enough to be exhausted by a burst fails every caller that arrives during it,
     * even though ports are typically held only briefly. Waiting turns that cliff into a short
     * queue: the caller is woken by {@link #releasePort(int)} as soon as a port comes back, and
     * gives up with -1 once the deadline passes, so a genuine outage still fails rather than
     * hanging.
     * <p>
     * A timeout of 0 (or less) does not wait at all and behaves exactly like
     * {@link #reserveNextPort(List)}.
     * <p>
     * Each waiter re-tests <em>its own</em> allow-list on every wake-up. That is what makes this
     * safe when callers have different allow-lists over one pool: a release only satisfies the
     * waiters whose list actually contains the released port, and the rest go back to waiting
     * instead of consuming it.
     *
     * @param allowedPorts
     *            The only ports that may be reserved, most preferred first. Required.
     * @param timeoutMillis
     *            How long to wait for a port to be released; 0 or less does not wait
     * @return The reserved port, or -1 if none became available before the deadline
     */
    public synchronized int reserveNextPort(final List<Integer> allowedPorts, final long timeoutMillis) {
        if (allowedPorts == null || allowedPorts.isEmpty()) {
            return -1;
        }

        if (timeoutMillis <= 0L) {
            return tryReserve(allowedPorts);
        }

        // Monotonic, so a wall-clock step (an NTP correction, a VM resuming) can neither cut the
        // wait short nor stretch it past the caller's bound.
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);

        for (;;) {
            int reserved = tryReserve(allowedPorts);

            if (reserved != -1) {
                return reserved;
            }

            long remainingNanos = deadline - System.nanoTime();

            if (remainingNanos <= 0L) {
                return -1;
            }

            try {
                // Releases this monitor, so releasePort() can get in and hand a port back. Rounded
                // up because wait(0) means "wait forever": a sub-millisecond remainder must never be
                // truncated into an unbounded wait.
                wait(TimeUnit.NANOSECONDS.toMillis(remainingNanos) + 1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }
    }

    /**
     * One pass over the allow-list. Must be called while holding this object's monitor.
     *
     * @param allowedPorts
     *            The only ports that may be reserved, most preferred first
     * @return The reserved port, or -1 if none of them is currently free
     */
    private int tryReserve(final List<Integer> allowedPorts) {
        for (Integer candidate : allowedPorts) {
            if (candidate == null || !freeList.contains(candidate)) {
                // already reserved, or not one of our ports
                continue;
            }

            if (candidate == 0) {
                // "Any" port should not be removed from our free list,
                // nor added to the used list
                return 0;
            }

            if (checkPortUnbound(candidate)) {
                freeList.remove(candidate);
                usedList.add(candidate);
                return candidate;
            }

            // log port unavailable, but left in pool
            log.warn("Passive port in use by another process: " + candidate);
        }

        return -1;
    }

    /**
     * Release a port
     *
     * @param port The port to release
     */
    public synchronized void releasePort(final int port) {
        if (port == 0) {
            // Ignore port 0 being released,
            // since its not put on the used list

        } else if (usedList.remove(port)) {
            freeList.add(port);
            // Wake everyone waiting in reserveNextPort(List, long). They cannot be woken
            // selectively because each has its own allow-list, so each re-tests its own on wake
            // and the ones this port does not satisfy go back to waiting.
            notifyAll();

        } else {
            // log attempt to release unused port
            log.warn("Releasing unreserved passive port: " + port);
        }
    }

    @Override
    public String toString() {
        if (passivePortsString != null) {
            return passivePortsString;
        }

        StringBuilder sb = new StringBuilder();

        for (Integer port : freeList) {
            sb.append(port);
            sb.append(",");
        }
        // remove the last ,
        sb.deleteCharAt(sb.length() - 1);
        return sb.toString();
    }

}
