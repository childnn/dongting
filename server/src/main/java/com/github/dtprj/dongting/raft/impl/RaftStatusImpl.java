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
package com.github.dtprj.dongting.raft.impl;

import com.github.dtprj.dongting.common.Timestamp;
import com.github.dtprj.dongting.raft.server.RaftStatus;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * @author huangli
 */
public class RaftStatusImpl extends RaftStatus {

    private volatile ShareStatus shareStatus;
    private volatile boolean error;

    private boolean installSnapshot;

    private RaftRole role; // shared
    private RaftMember currentLeader; // shared
    private final Timestamp ts = new Timestamp();
    private int electQuorum;
    private int rwQuorum;

    private RaftMember self;
    private List<RaftMember> members;
    private List<RaftMember> observers;
    private List<RaftMember> preparedMembers;
    private List<RaftMember> preparedObservers;

    private Set<Integer> nodeIdOfMembers;
    private Set<Integer> nodeIdOfObservers;
    private Set<Integer> nodeIdOfPreparedMembers;
    private Set<Integer> nodeIdOfPreparedObservers;

    private List<RaftMember> replicateList;

    private PendingMap pendingRequests = new PendingMap();
    private long firstIndexOfCurrentTerm;
    private CompletableFuture<Void> firstCommitOfApplied; // shared

    private boolean shareStatusUpdated;
    private long electTimeoutNanos; // shared

    private long leaseStartNanos; // shared
    private long[] leaseComputeArray = new long[0];

    private long lastElectTime;
    private long heartbeatTime;

    private long lastLogIndex;
    private int lastLogTerm;

    private StatusFile statusFile;

    private RaftExecutor raftExecutor;

    private boolean holdRequest;

    public RaftStatusImpl() {
        lastElectTime = ts.getNanoTime();
        heartbeatTime = ts.getNanoTime();
    }

    public void copyShareStatus() {
        if (shareStatusUpdated) {
            ShareStatus ss = new ShareStatus();
            ss.role = role;
            ss.lastApplied = lastApplied;
            ss.leaseEndNanos = leaseStartNanos + electTimeoutNanos;
            ss.currentLeader = currentLeader;
            ss.firstCommitOfApplied = firstCommitOfApplied;

            this.shareStatusUpdated = false;
            this.shareStatus = ss;
        }
    }

    public void setLastApplied(long lastApplied) {
        this.lastApplied = lastApplied;
        this.shareStatusUpdated = true;
    }

    public void setRole(RaftRole role) {
        this.role = role;
        this.shareStatusUpdated = true;
    }

    public void setLeaseStartNanos(long leaseStartNanos) {
        this.leaseStartNanos = leaseStartNanos;
        this.shareStatusUpdated = true;
    }

    public void setCurrentLeader(RaftMember currentLeader) {
        this.currentLeader = currentLeader;
        this.shareStatusUpdated = true;
    }

    public void setElectTimeoutNanos(long electTimeoutNanos) {
        this.electTimeoutNanos = electTimeoutNanos;
        this.shareStatusUpdated = true;
    }

    public void setFirstCommitOfApplied(CompletableFuture<Void> firstCommitOfApplied) {
        this.firstCommitOfApplied = firstCommitOfApplied;
        this.shareStatusUpdated = true;
    }

    //------------------------- simple getters and setters--------------------------------

    public boolean isError() {
        return error;
    }

    public void setError(boolean errorState) {
        this.error = errorState;
    }

    public void setStop(boolean stop) {
        this.stop = stop;
    }

    public void setCurrentTerm(int currentTerm) {
        this.currentTerm = currentTerm;
    }

    public void setVotedFor(int votedFor) {
        this.votedFor = votedFor;
    }

    public void setCommitIndex(long commitIndex) {
        this.commitIndex = commitIndex;
    }

    public RaftRole getRole() {
        return role;
    }

    public int getElectQuorum() {
        return electQuorum;
    }

    public int getRwQuorum() {
        return rwQuorum;
    }

    public void setElectQuorum(int electQuorum) {
        this.electQuorum = electQuorum;
    }

    public void setRwQuorum(int rwQuorum) {
        this.rwQuorum = rwQuorum;
    }

    public long getHeartbeatTime() {
        return heartbeatTime;
    }

    public void setHeartbeatTime(long heartbeatTime) {
        this.heartbeatTime = heartbeatTime;
    }

    public long getLastElectTime() {
        return lastElectTime;
    }

    public void setLastElectTime(long lastElectTime) {
        this.lastElectTime = lastElectTime;
    }

    public long getLastLogIndex() {
        return lastLogIndex;
    }

    public void setLastLogIndex(long lastLogIndex) {
        this.lastLogIndex = lastLogIndex;
    }

    public int getLastLogTerm() {
        return lastLogTerm;
    }

    public void setLastLogTerm(int lastLogTerm) {
        this.lastLogTerm = lastLogTerm;
    }

    public List<RaftMember> getMembers() {
        return members;
    }

    public void setMembers(List<RaftMember> members) {
        this.members = members;
    }

    public List<RaftMember> getObservers() {
        return observers;
    }

    public void setObservers(List<RaftMember> observers) {
        this.observers = observers;
    }

    public List<RaftMember> getPreparedMembers() {
        return preparedMembers;
    }

    public void setPreparedMembers(List<RaftMember> preparedMembers) {
        this.preparedMembers = preparedMembers;
    }

    public Set<Integer> getNodeIdOfMembers() {
        return nodeIdOfMembers;
    }

    public void setNodeIdOfMembers(Set<Integer> nodeIdOfMembers) {
        this.nodeIdOfMembers = nodeIdOfMembers;
    }

    public Set<Integer> getNodeIdOfObservers() {
        return nodeIdOfObservers;
    }

    public void setNodeIdOfObservers(Set<Integer> nodeIdOfObservers) {
        this.nodeIdOfObservers = nodeIdOfObservers;
    }

    public Set<Integer> getNodeIdOfPreparedMembers() {
        return nodeIdOfPreparedMembers;
    }

    public void setNodeIdOfPreparedMembers(Set<Integer> nodeIdOfPreparedMembers) {
        this.nodeIdOfPreparedMembers = nodeIdOfPreparedMembers;
    }

    public List<RaftMember> getReplicateList() {
        return replicateList;
    }

    public void setReplicateList(List<RaftMember> replicateList) {
        this.replicateList = replicateList;
    }

    public PendingMap getPendingRequests() {
        return pendingRequests;
    }

    public void setPendingRequests(PendingMap pendingRequests) {
        this.pendingRequests = pendingRequests;
    }

    public long getFirstIndexOfCurrentTerm() {
        return firstIndexOfCurrentTerm;
    }

    public void setFirstIndexOfCurrentTerm(long firstIndexOfCurrentTerm) {
        this.firstIndexOfCurrentTerm = firstIndexOfCurrentTerm;
    }

    public Timestamp getTs() {
        return ts;
    }

    public ShareStatus getShareStatus() {
        return shareStatus;
    }

    public RaftMember getCurrentLeader() {
        return currentLeader;
    }

    public long getElectTimeoutNanos() {
        return electTimeoutNanos;
    }

    public CompletableFuture<Void> getFirstCommitOfApplied() {
        return firstCommitOfApplied;
    }

    public boolean isInstallSnapshot() {
        return installSnapshot;
    }

    public void setInstallSnapshot(boolean installSnapshot) {
        this.installSnapshot = installSnapshot;
    }

    public RaftExecutor getRaftExecutor() {
        return raftExecutor;
    }

    public void setRaftExecutor(RaftExecutor raftExecutor) {
        this.raftExecutor = raftExecutor;
    }

    public StatusFile getStatusFile() {
        return statusFile;
    }

    public void setStatusFile(StatusFile statusFile) {
        this.statusFile = statusFile;
    }

    public long[] getLeaseComputeArray() {
        return leaseComputeArray;
    }

    public void setLeaseComputeArray(long[] leaseComputeArray) {
        this.leaseComputeArray = leaseComputeArray;
    }

    public long getLeaseStartNanos() {
        return leaseStartNanos;
    }

    public boolean isHoldRequest() {
        return holdRequest;
    }

    public void setHoldRequest(boolean holdRequest) {
        this.holdRequest = holdRequest;
    }

    public RaftMember getSelf() {
        return self;
    }

    public void setSelf(RaftMember self) {
        this.self = self;
    }

    public void setGroupId(int groupId) {
        this.groupId = groupId;
    }

    public Set<Integer> getNodeIdOfPreparedObservers() {
        return nodeIdOfPreparedObservers;
    }

    public void setNodeIdOfPreparedObservers(Set<Integer> nodeIdOfPreparedObservers) {
        this.nodeIdOfPreparedObservers = nodeIdOfPreparedObservers;
    }

    public List<RaftMember> getPreparedObservers() {
        return preparedObservers;
    }

    public void setPreparedObservers(List<RaftMember> preparedObservers) {
        this.preparedObservers = preparedObservers;
    }

}
