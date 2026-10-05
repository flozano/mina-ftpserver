<!--
Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements.  See the NOTICE file distributed with
this work for additional information regarding copyright ownership.
The ASF licenses this file to You under the Apache License, Version 2.0
(the "License"); you may not use this file except in compliance with
the License.  You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->

# Multiplexed Passive Ports

## Problem
The current passive (PASV/EPSV) implementation reserves a passive port for a single client at a time. This forces a wide passive port range to serve many concurrent clients and does not allow per-client reuse of the same passive port.

## Goal
- Allow passive ports to be reused concurrently by different clients when their IPs differ.
- Routing of incoming passive connections must use `(passivePort, clientIp)` instead of only `passivePort`.
- Per-port-per-IP concurrency is **1**. Per-IP concurrency is capped by the number of configured passive ports.
- Maintain backward compatibility by keeping the existing behavior when the feature is disabled.

## Design Overview
- **Registry**: Track pending passive transfers keyed by `(port, clientIp) -> session/token`. Maintain per-port map of client IP to session to enforce per-port-per-IP = 1 and enable port reuse by different client IPs. Track per-IP total across ports to enforce the IP-level cap.
- **Config**: Add `multiplexPassivePorts` (default `false`). When enabled, per-IP limit defaults to `passivePorts.size()`. Keep existing passive port range configuration untouched.
- **PASV/EPSV flow**:
  - Allocate a port from `PassivePorts` (unchanged).
  - Register `(port, clientIp)` in the registry. Reject with 4xx if a slot already exists for that IP on the same port or if the per-IP cap is reached.
  - Reply to the client with the chosen port.
- **Accept flow**:
  - Each passive `ServerSocket` accept looks up `(localPort, remoteIp)` in the registry.
  - On hit: bind the socket to that session and remove the mapping.
  - On miss: close immediately and log (protects against hijack or stale connects).
- **Cleanup**:
  - On successful data channel establishment, timeout/cancel, or session close, remove the registry mapping.
  - When the last mapping on a port is removed and no server socket is kept, release the port via `PassivePorts.releasePort`.
- **Security/Robustness**:
  - Remote IP must match the control session IP (existing passive IP check aligns with this).
  - Support IPv4/IPv6 address equality and normalization.
  - Short timeout/dismiss unknown connections, log drops.
- **Compatibility**:
  - Feature guarded by `multiplexPassivePorts=false` by default.
  - Legacy behavior (single-client-per-port) remains unchanged when disabled.

## Testing Plan
- **Unit**: Registry behaviors (register/lookup/remove, duplicate IP on same port rejected, different IP allowed on same port, per-IP cap enforced, cleanup releases slots).
- **Integration** (embedded server):
  - Two clients with different IPs reuse the same passive port; each maps to the correct session.
  - Same IP retries on the same port before completion is rejected.
  - Per-IP cap is enforced at `passivePorts.size()`.
  - Unknown IP connecting to a shared passive port is dropped.
- **Regression**: With `multiplexPassivePorts=false`, behavior matches current single-use-per-port semantics.

## Work Phases
1) Add config flag and multiplex-aware registry component.
2) Wire PASV/EPSV to register `(port, clientIp)` and enforce limits.
3) Update accept routing to match on `(port, remoteIp)` and drop unknowns.
4) Add cleanup hooks and tests; document the new configuration flag and limits.
