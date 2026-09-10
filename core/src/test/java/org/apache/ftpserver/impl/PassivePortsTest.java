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
import java.util.List;

import junit.framework.AssertionFailedError;
import junit.framework.TestCase;

/**
*
* @author <a href="http://mina.apache.org">Apache MINA Project</a>
*
*/
public class PassivePortsTest extends TestCase {

    public void testParseSingleValue() {
        PassivePorts ports = new PassivePorts("123", false);

        assertEquals(123, ports.reserveNextPort());
        assertEquals(-1, ports.reserveNextPort());
    }

    public void testParseMaxValue() {
        PassivePorts ports = new PassivePorts("65535", false);

        assertEquals(65535, ports.reserveNextPort());
        assertEquals(-1, ports.reserveNextPort());
    }

    public void testParseMinValue() {
        PassivePorts ports = new PassivePorts("0", false);

        assertEquals(0, ports.reserveNextPort());
        assertEquals(0, ports.reserveNextPort());
        assertEquals(0, ports.reserveNextPort());
        assertEquals(0, ports.reserveNextPort());
        // should return 0 forever
    }

    public void testParseTooLargeValue() {
        try {
            new PassivePorts("65536", false);
            fail("Must fail due to too high port number");
        } catch (IllegalArgumentException e) {
            // ok
        }
    }

    public void testParseNonNumericValue() {
        try {
            new PassivePorts("foo", false);
            fail("Must fail due to non numerical port number");
        } catch (IllegalArgumentException e) {
            // ok
        }
    }
    
    private void assertContains( List<Integer> valid, Integer testVal ){
        if( !valid.remove(testVal) ){
            throw new AssertionFailedError( "Did not find "+testVal+" in valid list "+valid );
        }
    }
    
    private void assertReserveAll(String portString, int... validPorts) {
        PassivePorts ports = new PassivePorts(portString, false);
        
        List<Integer> valid = valid(validPorts);

        int len = valid.size();
        for(int i = 0; i<len; i++) {
            assertContains(valid, ports.reserveNextPort());
        }
        assertEquals(-1, ports.reserveNextPort());
        assertTrue(valid.isEmpty());

    }
    
    private List<Integer> valid(int... ints) {
        List<Integer> valid = new ArrayList<>();
        for(int i : ints) {
            valid.add(i);
        }
        return valid;
    }

    public void testParseListOfValues() {
        assertReserveAll("123, 456,\t\n789", 123, 456, 789);
    }

    public void testParseListOfValuesDuplicate() {
        assertReserveAll("123, 789, 456, 789", 123, 456, 789);
    }

    public void testParseSimpleRange() {
        assertReserveAll("123-125", 123, 124, 125);
    }

    public void testParseMultipleRanges() {
        assertReserveAll("123-125, 127-128, 130-132", 123, 124, 125, 127, 128, 130, 131, 132);
    }

    public void testParseMixedRangeAndSingle() {
        assertReserveAll("123-125, 126, 128-129", 123, 124, 125, 126, 128, 129);
    }

    public void testParseOverlapingRanges() {
        assertReserveAll("123-125, 124-126", 123, 124, 125, 126);
    }

    public void testParseOverlapingRangesorder() {
        assertReserveAll("124-126, 123-125", 123, 124, 125, 126);
    }

    public void testParseOpenLowerRange() {
        assertReserveAll("9, -3", 1, 2, 3, 9);
    }

    public void testParseOpenUpperRange() {
        assertReserveAll("65533-", 65533, 65534, 65535);
    }

    public void testParseOpenUpperRange3() {
        assertReserveAll("65533-, 65532-", 65532, 65533, 65534, 65535);
    }

    public void testParseOpenUpperRange2() {
        assertReserveAll("65533-, 1", 1, 65533, 65534, 65535);
    }
    
    public void testReserveNextPortBound() throws IOException {
        ServerSocket ss = new ServerSocket(0);
        
        PassivePorts ports = new PassivePorts(Integer.toString(ss.getLocalPort()), true);

        assertEquals(-1, ports.reserveNextPort());
        
        ss.close();
        
        assertEquals(ss.getLocalPort(), ports.reserveNextPort());
    }


    public void testParseRelease() {
        PassivePorts ports = new PassivePorts("123, 456,789", false);

        List<Integer> valid = valid(123, 456, 789);

        assertContains(valid, ports.reserveNextPort());

        int port = ports.reserveNextPort();
        assertContains(valid, port);
        ports.releasePort(port);
        valid.add(port);
        assertContains(valid, ports.reserveNextPort());
        
        assertContains(valid, ports.reserveNextPort());

        assertEquals(-1, ports.reserveNextPort());
        assertEquals(0, valid.size());
    }


    /**
     * A waiter is handed the port another thread gives back, rather than failing outright.
     */
    public void testWaitIsSatisfiedByARelease() throws Exception {
        final PassivePorts ports = new PassivePorts("123", false);
        assertEquals(123, ports.reserveNextPort());

        Thread releaser = new Thread(new Runnable() {
            public void run() {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                ports.releasePort(123);
            }
        });
        releaser.start();

        long start = System.currentTimeMillis();
        int reserved = ports.reserveNextPort(list(123), 5000);
        long elapsed = System.currentTimeMillis() - start;
        releaser.join();

        assertEquals(123, reserved);
        assertTrue("should have waited for the release, waited " + elapsed + "ms", elapsed >= 50);
    }

    /**
     * A pool that stays exhausted still fails, so an outage does not hang the caller forever.
     */
    public void testWaitGivesUpAtTheDeadline() {
        PassivePorts ports = new PassivePorts("123", false);
        assertEquals(123, ports.reserveNextPort());

        long start = System.currentTimeMillis();
        int reserved = ports.reserveNextPort(list(123), 200);
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(-1, reserved);
        assertTrue("should have waited for the deadline, waited " + elapsed + "ms", elapsed >= 150);
    }

    /**
     * Zero keeps the old fail-fast behaviour, so callers that must not block are unaffected.
     */
    public void testZeroTimeoutDoesNotWait() {
        PassivePorts ports = new PassivePorts("123", false);
        assertEquals(123, ports.reserveNextPort());

        long start = System.currentTimeMillis();
        assertEquals(-1, ports.reserveNextPort(list(123), 0));
        long elapsed = System.currentTimeMillis() - start;

        assertTrue("should not have waited, waited " + elapsed + "ms", elapsed < 100);
    }

    /**
     * Releasing a port outside the waiter's allow-list must not satisfy it. Waiters share one
     * monitor and are all woken together, so each has to re-test its own list rather than take
     * whatever came back.
     */
    public void testReleaseOutsideTheAllowListDoesNotSatisfyAWaiter() throws Exception {
        final PassivePorts ports = new PassivePorts("123, 456", false);
        assertEquals(123, ports.reserveNextPort(list(123), 0));
        assertEquals(456, ports.reserveNextPort(list(456), 0));

        Thread releaser = new Thread(new Runnable() {
            public void run() {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                // Not the port the waiter is allowed to use.
                ports.releasePort(456);
            }
        });
        releaser.start();

        int reserved = ports.reserveNextPort(list(123), 300);
        releaser.join();

        assertEquals(-1, reserved);
        // The released port is still free; it simply was not this waiter's to take.
        assertEquals(456, ports.reserveNextPort(list(456), 0));
    }

    private static List<Integer> list(int... values) {
        List<Integer> result = new ArrayList<Integer>();
        for (int value : values) {
            result.add(Integer.valueOf(value));
        }
        return result;
    }
}
