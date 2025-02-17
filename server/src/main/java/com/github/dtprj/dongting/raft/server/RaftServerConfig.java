/*
 * Copyright The Dongting Project
 *
 * The Dongting Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.github.dtprj.dongting.raft.server;

import com.github.dtprj.dongting.buf.ByteBufferPool;
import com.github.dtprj.dongting.buf.TwoLevelPool;
import com.github.dtprj.dongting.common.Timestamp;

import java.util.function.BiFunction;

/**
 * @author huangli
 */
@SuppressWarnings("unused")
public class RaftServerConfig {
    private String servers;
    private int raftPort;
    private int nodeId;
    private long electTimeout = 15 * 1000;
    private long rpcTimeout = 5 * 1000;
    private long connectTimeout = 2000;
    private long heartbeatInterval = 2000;
    private int maxReplicateItems = 3000;
    private long maxReplicateBytes = 16 * 1024 * 1024;
    private int singleReplicateLimit = 1800 * 1024;

    private int maxPendingWrites = 10000;
    private long maxPendingWriteBytes = 256 * 1024 * 1024;

    private boolean checkSelf = true;
    private boolean staticConfig = true;

    private int ioThreads = Math.max(Runtime.getRuntime().availableProcessors() * 5, 30);

    private BiFunction<Timestamp, Boolean, ByteBufferPool> poolFactory = TwoLevelPool.getDefaultFactory();

    public String getServers() {
        return servers;
    }

    public void setServers(String servers) {
        this.servers = servers;
    }

    public int getRaftPort() {
        return raftPort;
    }

    public void setRaftPort(int raftPort) {
        this.raftPort = raftPort;
    }

    public int getNodeId() {
        return nodeId;
    }

    public void setNodeId(int nodeId) {
        this.nodeId = nodeId;
    }

    public long getElectTimeout() {
        return electTimeout;
    }

    public void setElectTimeout(long electTimeout) {
        this.electTimeout = electTimeout;
    }

    public long getRpcTimeout() {
        return rpcTimeout;
    }

    public void setRpcTimeout(long rpcTimeout) {
        this.rpcTimeout = rpcTimeout;
    }

    public long getHeartbeatInterval() {
        return heartbeatInterval;
    }

    public void setHeartbeatInterval(long heartbeatInterval) {
        this.heartbeatInterval = heartbeatInterval;
    }

    public int getMaxReplicateItems() {
        return maxReplicateItems;
    }

    public void setMaxReplicateItems(int maxReplicateItems) {
        this.maxReplicateItems = maxReplicateItems;
    }

    public long getMaxReplicateBytes() {
        return maxReplicateBytes;
    }

    public void setMaxReplicateBytes(long maxReplicateBytes) {
        this.maxReplicateBytes = maxReplicateBytes;
    }

    public int getMaxPendingWrites() {
        return maxPendingWrites;
    }

    public void setMaxPendingWrites(int maxPendingWrites) {
        this.maxPendingWrites = maxPendingWrites;
    }

    public long getMaxPendingWriteBytes() {
        return maxPendingWriteBytes;
    }

    public void setMaxPendingWriteBytes(long maxPendingWriteBytes) {
        this.maxPendingWriteBytes = maxPendingWriteBytes;
    }

    public long getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(long connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public int getSingleReplicateLimit() {
        return singleReplicateLimit;
    }

    public void setSingleReplicateLimit(int singleReplicateLimit) {
        this.singleReplicateLimit = singleReplicateLimit;
    }

    public boolean isCheckSelf() {
        return checkSelf;
    }

    public void setCheckSelf(boolean checkSelf) {
        this.checkSelf = checkSelf;
    }

    public boolean isStaticConfig() {
        return staticConfig;
    }

    public void setStaticConfig(boolean staticConfig) {
        this.staticConfig = staticConfig;
    }

    public BiFunction<Timestamp, Boolean, ByteBufferPool> getPoolFactory() {
        return poolFactory;
    }

    public void setPoolFactory(BiFunction<Timestamp, Boolean, ByteBufferPool> poolFactory) {
        this.poolFactory = poolFactory;
    }

    public int getIoThreads() {
        return ioThreads;
    }

    public void setIoThreads(int ioThreads) {
        this.ioThreads = ioThreads;
    }
}
