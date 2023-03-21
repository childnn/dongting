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

import com.github.dtprj.dongting.common.DtTime;
import com.github.dtprj.dongting.common.IntObjMap;
import com.github.dtprj.dongting.log.BugLog;
import com.github.dtprj.dongting.log.DtLog;
import com.github.dtprj.dongting.log.DtLogs;
import com.github.dtprj.dongting.net.NioClient;
import com.github.dtprj.dongting.net.PeerStatus;
import com.github.dtprj.dongting.net.ReadFrame;
import com.github.dtprj.dongting.raft.client.RaftException;
import com.github.dtprj.dongting.raft.rpc.RaftPingFrameCallback;
import com.github.dtprj.dongting.raft.rpc.RaftPingProcessor;
import com.github.dtprj.dongting.raft.rpc.RaftPingWriteFrame;
import com.github.dtprj.dongting.raft.server.NotLeaderException;
import com.github.dtprj.dongting.raft.server.RaftServerConfig;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptySet;

/**
 * @author huangli
 */
public class MemberManager {
    private static final DtLog log = DtLogs.getLogger(MemberManager.class);
    private final RaftServerConfig serverConfig;
    private final RaftStatus raftStatus;
    private final int groupId;
    private final NioClient client;
    private final RaftExecutor executor;

    private List<RaftMember> jointConsensusObservers;

    private final FutureEventSource futureEventSource;
    private final EventBus eventBus;

    public MemberManager(RaftServerConfig serverConfig, NioClient client, RaftExecutor executor,
                         RaftStatus raftStatus, int groupId, EventBus eventBus) {
        this.serverConfig = serverConfig;
        this.client = client;
        this.executor = executor;
        this.raftStatus = raftStatus;
        this.groupId = groupId;

        this.futureEventSource = new FutureEventSource(executor);
        this.eventBus = eventBus;
    }

    public void init(IntObjMap<RaftNodeEx> allNodes) {
        for (int nodeId : raftStatus.getNodeIdOfMembers()) {
            RaftMember m = new RaftMember(allNodes.get(nodeId));
            raftStatus.getMembers().add(m);
        }
        if (raftStatus.getNodeIdOfObservers().size() > 0) {
            List<RaftMember> observers = new ArrayList<>();
            for (int nodeId : raftStatus.getNodeIdOfObservers()) {
                RaftMember m = new RaftMember(allNodes.get(nodeId));
                observers.add(m);
            }
            raftStatus.setObservers(observers);
        } else {
            raftStatus.setObservers(emptyList());
        }
        raftStatus.setJointConsensusMembers(emptyList());
        computeDuplicatedData();
    }

    private void computeDuplicatedData() {
        ArrayList<RaftMember> replicateList = new ArrayList<>();
        Set<Integer> memberIds = new HashSet<>();
        Set<Integer> observerIds = new HashSet<>();
        Set<Integer> jointMemberIds = new HashSet<>();
        for (RaftMember m : raftStatus.getMembers()) {
            replicateList.add(m);
            memberIds.add(m.getNode().getNodeId());
        }
        for (RaftMember m : raftStatus.getObservers()) {
            replicateList.add(m);
            observerIds.add(m.getNode().getNodeId());
        }
        for (RaftMember m : raftStatus.getJointConsensusMembers()) {
            replicateList.add(m);
            jointMemberIds.add(m.getNode().getNodeId());
        }
        raftStatus.setReplicateList(replicateList);
        raftStatus.setNodeIdOfMembers(memberIds);
        raftStatus.setNodeIdOfObservers(observerIds.size() == 0 ? emptySet() : observerIds);
        raftStatus.setNodeIdOfJointConsensusMembers(jointMemberIds.size() == 0 ? emptySet() : jointMemberIds);
    }

    @SuppressWarnings("ForLoopReplaceableByForEach")
    public void ensureRaftMemberStatus() {
        List<RaftMember> replicateList = raftStatus.getReplicateList();
        int len = replicateList.size();
        for (int i = 0; i < len; i++) {
            RaftMember member = replicateList.get(i);
            check(member);
        }
    }

    private void check(RaftMember member) {
        RaftNodeEx node = member.getNode();
        NodeStatus nodeStatus = node.getStatus();
        if (!nodeStatus.isReady()) {
            setReady(member, false);
        } else if (nodeStatus.getEpoch() != member.getEpoch()) {
            setReady(member, false);
            if (!member.isPinging()) {
                raftPing(node, member, nodeStatus.getEpoch());
            }
        }
    }

    private void raftPing(RaftNodeEx raftNodeEx, RaftMember member, int nodeEpochWhenStartPing) {
        if (raftNodeEx.getPeer().getStatus() != PeerStatus.connected) {
            setReady(member, false);
            return;
        }

        member.setPinging(true);
        DtTime timeout = new DtTime(serverConfig.getRpcTimeout(), TimeUnit.MILLISECONDS);
        RaftPingWriteFrame f = new RaftPingWriteFrame(groupId, serverConfig.getNodeId(),
                raftStatus.getNodeIdOfMembers(), raftStatus.getNodeIdOfObservers());
        client.sendRequest(raftNodeEx.getPeer(), f, RaftPingProcessor.DECODER, timeout)
                .whenCompleteAsync((rf, ex) -> processPingResult(raftNodeEx, member, rf, ex, nodeEpochWhenStartPing), executor);
    }

    private void processPingResult(RaftNodeEx raftNodeEx, RaftMember member,
                                   ReadFrame rf, Throwable ex, int nodeEpochWhenStartPing) {
        RaftPingFrameCallback callback = (RaftPingFrameCallback) rf.getBody();
        executor.schedule(() -> member.setPinging(false), 1000);
        if (ex != null) {
            log.warn("raft ping fail, remote={}", raftNodeEx.getHostPort(), ex);
            setReady(member, false);
        } else {
            if (callback.nodeId == 0 && callback.groupId == 0) {
                log.error("raft ping error, groupId or nodeId not found, groupId={}, remote={}",
                        groupId, raftNodeEx.getHostPort());
                setReady(member, false);
            } else if (checkRemoteConfig(callback)) {
                NodeStatus currentNodeStatus = member.getNode().getStatus();
                if (currentNodeStatus.isReady() && nodeEpochWhenStartPing == currentNodeStatus.getEpoch()) {
                    log.info("raft ping success, id={}, remote={}", callback.nodeId, raftNodeEx.getHostPort());
                    setReady(member, true);
                    member.setEpoch(nodeEpochWhenStartPing);
                } else {
                    log.warn("raft ping success but current node status not match. "
                                    + "id={}, remoteHost={}, nodeReady={}, nodeEpoch={}, pingEpoch={}",
                            callback.nodeId, raftNodeEx.getHostPort(), currentNodeStatus.isReady(),
                            currentNodeStatus.getEpoch(), nodeEpochWhenStartPing);
                    setReady(member, false);
                }
            } else {
                log.error("raft ping error, group ids not match: remote={}, localIds={}, remoteIds={}, localObservers={}, remoteObservers={}",
                        raftNodeEx, raftStatus.getNodeIdOfMembers(), callback.nodeIdOfMembers, raftStatus.getNodeIdOfObservers(), callback.nodeIdOfObservers);
                setReady(member, false);
            }
        }
    }

    private boolean checkRemoteConfig(RaftPingFrameCallback callback) {
        if (serverConfig.isStaticConfig()) {
            return raftStatus.getNodeIdOfMembers().equals(callback.nodeIdOfMembers)
                    && raftStatus.getNodeIdOfObservers().equals(callback.nodeIdOfObservers);
        }
        return false;
    }

    public void setReady(RaftMember member, boolean ready) {
        if (ready == member.isReady()) {
            return;
        }
        member.setReady(ready);
        futureEventSource.fireInExecutorThread();
    }

    private int getReadyCount(List<RaftMember> list) {
        int count = 0;
        for (RaftMember m : list) {
            if (m.isReady()) {
                count++;
            }
        }
        return count;
    }

    public CompletableFuture<Void> createReadyFuture(int targetReadyCount) {
        return futureEventSource.registerInOtherThreads(() -> getReadyCount(raftStatus.getMembers()) >= targetReadyCount);
    }

    public boolean checkLeader(int nodeId) {
        RaftMember leader = raftStatus.getCurrentLeader();
        if (leader != null && leader.getNode().getNodeId() == nodeId) {
            return true;
        }
        return raftStatus.getNodeIdOfMembers().contains(nodeId) || raftStatus.getNodeIdOfJointConsensusMembers().contains(nodeId);
    }

    public static boolean validCandidate(RaftStatus raftStatus, int nodeId) {
        if (raftStatus.getNodeIdOfJointConsensusMembers().size() > 0) {
            return raftStatus.getNodeIdOfJointConsensusMembers().contains(nodeId);
        } else {
            return raftStatus.getNodeIdOfMembers().contains(nodeId);
        }
    }

    private boolean hasPreparedState() {
        return raftStatus.getNodeIdOfJointConsensusMembers().size() != 0
                || jointConsensusObservers.size() != 0
                || raftStatus.getJointConsensusMembers().size() != 0;
    }

    public CompletableFuture<Void> prepareJointConsensus(List<RaftNodeEx> newMemberNodes, List<RaftNodeEx> newObserverNodes) {
        CompletableFuture<Void> f = new CompletableFuture<>();
        executor.execute(() -> {
            if (hasPreparedState()) {
                BugLog.getLog().error("joint consensus is prepared");
                f.completeExceptionally(new IllegalStateException("joint consensus is prepared"));
                return;
            }

            IntObjMap<RaftMember> currentNodes = new IntObjMap<>();
            for (RaftMember m : raftStatus.getMembers()) {
                currentNodes.put(m.getNode().getNodeId(), m);
            }
            for (RaftMember m : raftStatus.getObservers()) {
                currentNodes.put(m.getNode().getNodeId(), m);
            }

            List<RaftMember> newMembers = new ArrayList<>();
            List<RaftMember> newObservers = new ArrayList<>();
            for (RaftNodeEx node : newMemberNodes) {
                RaftMember m = currentNodes.get(node.getNodeId());
                newMembers.add(Objects.requireNonNullElseGet(m, () -> new RaftMember(node)));
            }
            for (RaftNodeEx node : newObserverNodes) {
                RaftMember m = currentNodes.get(node.getNodeId());
                newObservers.add(Objects.requireNonNullElseGet(m, () -> new RaftMember(node)));
            }
            raftStatus.setJointConsensusMembers(newMembers);
            this.jointConsensusObservers = newObservers;

            computeDuplicatedData();

            eventBus.fire(EventType.cancelVote, null);
            f.complete(null);
        });
        return f;
    }

    public CompletableFuture<Set<Integer>> dropJointConsensus() {
        CompletableFuture<Set<Integer>> f = new CompletableFuture<>();
        executor.execute(() -> {
            if (!hasPreparedState()) {
                BugLog.getLog().error("joint consensus not prepared");
                f.completeExceptionally(new IllegalStateException("joint consensus not prepared"));
                return;
            }
            HashSet<Integer> ids = new HashSet<>(raftStatus.getNodeIdOfJointConsensusMembers());
            for (RaftMember m : jointConsensusObservers) {
                ids.add(m.getNode().getNodeId());
            }

            raftStatus.setJointConsensusMembers(emptyList());
            this.jointConsensusObservers = emptyList();
            computeDuplicatedData();
            eventBus.fire(EventType.cancelVote, null);
            f.complete(ids);
        });
        return f;
    }

    public CompletableFuture<Set<Integer>> commitJointConsensus() {
        CompletableFuture<Set<Integer>> f = new CompletableFuture<>();
        executor.execute(() -> {
            if (!hasPreparedState()) {
                BugLog.getLog().error("joint consensus not prepared");
                f.completeExceptionally(new IllegalStateException("joint consensus not prepared"));
                return;
            }

            HashSet<Integer> ids = new HashSet<>(raftStatus.getNodeIdOfMembers());
            ids.addAll(raftStatus.getNodeIdOfObservers());

            raftStatus.setMembers(raftStatus.getJointConsensusMembers());
            raftStatus.setObservers(jointConsensusObservers);

            raftStatus.setJointConsensusMembers(emptyList());
            this.jointConsensusObservers = emptyList();
            computeDuplicatedData();
            eventBus.fire(EventType.cancelVote, null);
            f.complete(ids);
        });
        return f;
    }

    public void transferLeadership(int nodeId, CompletableFuture<Void> f, DtTime deadline) {
        Runnable r = () -> {
            if (raftStatus.getRole() != RaftRole.leader) {
                raftStatus.setHoldRequest(false);
                f.completeExceptionally(new NotLeaderException(RaftUtil.getLeader(raftStatus.getCurrentLeader())));
                return;
            }
            RaftMember newLeader = null;
            for (RaftMember m : raftStatus.getMembers()) {
                if (m.getNode().getNodeId() == nodeId) {
                    newLeader = m;
                    break;
                }
            }
            if (newLeader == null) {
                for (RaftMember m : raftStatus.getJointConsensusMembers()) {
                    if (m.getNode().getNodeId() == nodeId) {
                        newLeader = m;
                        break;
                    }
                }
            }
            if (newLeader == null) {
                raftStatus.setHoldRequest(false);
                f.completeExceptionally(new RaftException("nodeId not found: " + nodeId));
                return;
            }
            if (deadline.isTimeout()) {
                raftStatus.setHoldRequest(false);
                f.completeExceptionally(new RaftException("transfer leader timeout"));
                return;
            }
            if (f.isCancelled()) {
                raftStatus.setHoldRequest(false);
                return;
            }

            boolean lastLogCommit = raftStatus.getCommitIndex() == raftStatus.getLastLogIndex();
            boolean newLeaderHasLastLog = newLeader.getMatchIndex() == raftStatus.getLastLogIndex();

            if (newLeader.isReady() && lastLogCommit && newLeaderHasLastLog) {
                // TODO send transfer leader request
                raftStatus.setHoldRequest(false);
                RaftUtil.changeToFollower(raftStatus, newLeader.getNode().getNodeId());
            } else {
                // retry
                transferLeadership(nodeId, f, deadline);
            }
        };
        executor.schedule(r, 3);
    }


}
