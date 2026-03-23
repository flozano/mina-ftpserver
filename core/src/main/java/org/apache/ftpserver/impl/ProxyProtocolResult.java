package org.apache.ftpserver.impl;

import java.net.InetSocketAddress;

/**
 * Holds the parsed result of a HAProxy PROXY Protocol v2 header.
 */
public record ProxyProtocolResult(
		InetSocketAddress sourceAddress,
		InetSocketAddress destinationAddress,
		boolean local) {

	@Override
	public String toString() {
		if (local) {
			return "ProxyProtocol[LOCAL]";
		}
		return "ProxyProtocol[src=" + sourceAddress + ", dst=" + destinationAddress + "]";
	}
}
