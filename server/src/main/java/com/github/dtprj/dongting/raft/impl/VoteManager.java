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

import com.github.dtprj.dongting.codec.Decoder;
import com.github.dtprj.dongting.codec.PbNoCopyDecoder;
import com.github.dtprj.dongting.common.DtTime;
import com.github.dtprj.dongting.log.BugLog;
import com.github.dtprj.dongting.log.DtLog;
import com.github.dtprj.dongting.log.DtLogs;
import com.github.dtprj.dongting.net.Commands;
import com.github.dtprj.dongting.net.NioClient;
import com.github.dtprj.dongting.net.ReadFrame;
import com.github.dtprj.dongting.raft.rpc.VoteReq;
import com.github.dtprj.dongting.raft.rpc.VoteResp;
import com.github.dtprj.dongting.raft.server.RaftServerConfig;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * @author huangli
 */
public class VoteManager implements BiConsumer<EventType, Object> {

    private static final DtLog log = DtLogs.getLogger(VoteManager.class);
    private final Raft raft;
    private final NioClient client;
    private final RaftStatusImpl raftStatus;
    private final RaftServerConfig config;
    private final int groupId;
    private final RaftExecutor raftExecutor;

    private static final Decoder<VoteResp> RESP_DECODER = new PbNoCopyDecoder<>(c -> new VoteResp.Callback());

    private boolean voting;
    private HashSet<Integer> votes;
    private int votePendingCount;
    private int currentVoteId;

    public VoteManager(RaftServerConfig serverConfig, int groupId, RaftStatusImpl raftStatus,
                       NioClient client, RaftExecutor executor, Raft raft) {
        this.raft = raft;
        this.client = client;
        this.raftStatus = raftStatus;
        this.config = serverConfig;
        this.groupId = groupId;
        this.raftExecutor = executor;
    }

    @Override
    public void accept(EventType eventType, Object o) {
        if (eventType == EventType.cancelVote) {
            cancelVote();
        }
    }

    public void cancelVote() {
        if (voting) {
            log.info("cancel current voting: groupId={}, voteId={}", groupId, currentVoteId);
            voting = false;
            votes = null;
            currentVoteId++;
            votePendingCount = 0;
        }
    }

    private void initStatusForVoting(int count) {
        voting = true;
        currentVoteId++;
        votes = new HashSet<>();
        votes.add(config.getNodeId());
        votePendingCount = count - 1;
    }

    private void descPending(int voteIdOfRequest) {
        if (voteIdOfRequest != currentVoteId) {
            return;
        }
        if (--votePendingCount == 0) {
            voting = false;
            votes = null;
        }
    }

    private int readyCount(Collection<RaftMember> list) {
        int count = 0;
        for (RaftMember member : list) {
            if (member.isReady()) {
                // include self
                count++;
            }
        }
        return count;
    }

    private boolean readyNodeNotEnough(List<RaftMember> list, boolean preVote, boolean jointConsensus) {
        if (list.size() == 0) {
            // for joint consensus, if no member, return true
            return false;
        }
        int count = readyCount(list);
        if (count < RaftUtil.getElectQuorum(list.size())) {
            log.warn("{} only {} node is ready, can't start {}. groupId={}, term={}",
                    jointConsensus ? "[joint consensus]" : "", count,
                    preVote ? "pre-vote" : "vote", groupId, raftStatus.getCurrentTerm());
            return true;
        }
        return false;
    }

    public void tryStartPreVote() {
        if (voting) {
            return;
        }
        if (!MemberManager.validCandidate(raftStatus, config.getNodeId())) {
            log.info("not valid candidate, can't start pre vote. groupId={}, term={}",
                    groupId, raftStatus.getCurrentTerm());
            return;
        }

        // move last elect time 1 seconds, prevent pre-vote too frequently if failed
        long newLastElectTime = raftStatus.getLastElectTime() + TimeUnit.SECONDS.toNanos(1);
        raftStatus.setLastElectTime(newLastElectTime);

        if (readyNodeNotEnough(raftStatus.getMembers(), true, false)) {
            return;
        }
        if (readyNodeNotEnough(raftStatus.getPreparedMembers(), true, true)) {
            return;
        }

        Set<RaftMember> voter = RaftUtil.union(raftStatus.getMembers(), raftStatus.getPreparedMembers());
        initStatusForVoting(readyCount(voter));
        log.info("node ready, start pre vote. groupId={}, term={}, voteId={}",
                groupId, raftStatus.getCurrentTerm(), currentVoteId);
        startPreVote(voter);
    }

    private void startPreVote(Set<RaftMember> voter) {
        for (RaftMember member : voter) {
            if (!member.getNode().isSelf() && member.isReady()) {
                sendRequest(member, true, 0);
            }
        }
    }

    private void sendRequest(RaftMember member, boolean preVote, long leaseStartTime) {
        VoteReq req = new VoteReq();
        int currentTerm = raftStatus.getCurrentTerm();
        req.setGroupId(groupId);
        req.setTerm(preVote ? currentTerm + 1 : currentTerm);
        req.setCandidateId(config.getNodeId());
        req.setLastLogIndex(raftStatus.getLastLogIndex());
        req.setLastLogTerm(raftStatus.getLastLogTerm());
        req.setPreVote(preVote);
        VoteReq.VoteReqWriteFrame wf = new VoteReq.VoteReqWriteFrame(req);
        wf.setCommand(Commands.RAFT_REQUEST_VOTE);
        DtTime timeout = new DtTime(config.getRpcTimeout(), TimeUnit.MILLISECONDS);

        final int voteIdOfRequest = this.currentVoteId;

        CompletableFuture<ReadFrame<VoteResp>> f = client.sendRequest(member.getNode().getPeer(), wf, RESP_DECODER, timeout);
        log.info("send {} request. remoteNode={}, groupId={}, term={}, lastLogIndex={}, lastLogTerm={}",
                preVote ? "pre-vote" : "vote", member.getNode().getNodeId(), groupId,
                currentTerm, req.getLastLogIndex(), req.getLastLogTerm());
        if (preVote) {
            f.whenCompleteAsync((rf, ex) -> processPreVoteResp(rf, ex, member, req, voteIdOfRequest), raftExecutor);
        } else {
            f.whenCompleteAsync((rf, ex) -> processVoteResp(rf, ex, member, req, voteIdOfRequest, leaseStartTime), raftExecutor);
        }
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean checkCandidate() {
        if (MemberManager.validCandidate(raftStatus, config.getNodeId())) {
            return true;
        } else {
            BugLog.getLog().error("not valid candidate, cancel vote. groupId={}, term={}",
                    groupId, raftStatus.getCurrentTerm());
            cancelVote();
            return false;
        }
    }

    private void processPreVoteResp(ReadFrame<VoteResp> rf, Throwable ex, RaftMember remoteMember, VoteReq req, int voteIdOfRequest) {
        if (voteIdOfRequest != currentVoteId) {
            return;
        }
        if (!checkCandidate()) {
            return;
        }
        int currentTerm = raftStatus.getCurrentTerm();
        if (ex == null) {
            VoteResp preVoteResp = rf.getBody();
            if (preVoteResp.isVoteGranted() && raftStatus.getRole() == RaftRole.follower
                    && preVoteResp.getTerm() == req.getTerm()) {
                log.info("receive pre-vote grant success. term={}, remoteNode={}, groupId={}",
                        currentTerm, remoteMember.getNode().getNodeId(), groupId);
                if (voteSuccess(remoteMember.getNode().getNodeId(), true)) {
                    log.info("pre-vote success, start elect. groupId={}. term={}", groupId, currentTerm);
                    startVote();
                }
            } else {
                log.info("receive pre-vote grant fail. term={}, remoteNode={}, groupId={}",
                        currentTerm, remoteMember.getNode().getNodeId(), groupId);
            }
        } else {
            log.warn("pre-vote rpc fail. term={}, remoteNode={}, groupId={}, error={}",
                    currentTerm, remoteMember.getNode().getNodeId(), groupId, ex.toString());
            // don't send more request for simplification
        }
        descPending(voteIdOfRequest);

    }

    private static int getVoteCount(Set<Integer> members, Set<Integer> votes) {
        int count = 0;
        for (int nodeId : votes) {
            if (members.contains(nodeId)) {
                count++;
            }
        }
        return count;
    }

    private boolean voteSuccess(int nodeId, boolean preVote) {
        if (!votes.add(nodeId)) {
            return false;
        }
        int quorum = raftStatus.getElectQuorum();
        int voteCount = getVoteCount(raftStatus.getNodeIdOfMembers(), votes);
        if (raftStatus.getPreparedMembers().size() == 0) {
            log.info("[{}] {} get {} grant of {}", preVote ? "pre-vote" : "vote", groupId, voteCount, quorum);
            return voteCount >= quorum;
        } else {
            int jointQuorum = RaftUtil.getElectQuorum(raftStatus.getPreparedMembers().size());
            int jointVoteCount = getVoteCount(raftStatus.getNodeIdOfPreparedMembers(), votes);
            log.info("[{}] {} get {} grant of {}, joint consensus get {} grant of {}", groupId,
                    preVote ? "pre-vote" : "vote", voteCount, quorum, jointVoteCount, jointQuorum);
            return voteCount >= quorum && jointVoteCount >= jointQuorum;
        }
    }

    private void startVote() {
        if (readyNodeNotEnough(raftStatus.getMembers(), false, false)) {
            cancelVote();
            return;
        }
        if (readyNodeNotEnough(raftStatus.getPreparedMembers(), false, true)) {
            cancelVote();
            return;
        }
        RaftUtil.resetStatus(raftStatus);
        if (raftStatus.getRole() != RaftRole.candidate) {
            log.info("change to candidate. groupId={}, oldTerm={}", groupId, raftStatus.getCurrentTerm());
            raftStatus.setRole(RaftRole.candidate);
        }

        Set<RaftMember> voter = RaftUtil.union(raftStatus.getMembers(), raftStatus.getPreparedMembers());

        raftStatus.setCurrentTerm(raftStatus.getCurrentTerm() + 1);
        raftStatus.setVotedFor(config.getNodeId());
        initStatusForVoting(voter.size());

        if (!StatusUtil.persist(raftStatus)) {
            cancelVote();
            return;
        }

        log.info("start vote. groupId={}, newTerm={}, voteId={}", groupId, raftStatus.getCurrentTerm(), currentVoteId);

        long leaseStartTime = raftStatus.getTs().getNanoTime();
        for (RaftMember member : voter) {
            if (!member.getNode().isSelf()) {
                if (member.isReady()) {
                    sendRequest(member, false, leaseStartTime);
                } else {
                    descPending(currentVoteId);
                }
            } else {
                member.setLastConfirmReqNanos(leaseStartTime);
            }
        }
    }

    private void processVoteResp(ReadFrame<VoteResp> rf, Throwable ex, RaftMember remoteMember,
                                   VoteReq voteReq, int voteIdOfRequest, long leaseStartTime) {
        if (voteIdOfRequest != currentVoteId) {
            return;
        }
        if (!checkCandidate()) {
            return;
        }
        if (ex == null) {
            processVoteResp(rf, remoteMember, voteReq, leaseStartTime);
        } else {
            log.warn("vote rpc fail. groupId={}, term={}, remote={}, error={}",
                    groupId, voteReq.getTerm(), remoteMember.getNode().getHostPort(), ex.toString());
            // don't send more request for simplification
        }
        descPending(voteIdOfRequest);
    }

    private void processVoteResp(ReadFrame<VoteResp> rf, RaftMember remoteMember, VoteReq voteReq, long leaseStartTime) {
        VoteResp voteResp = rf.getBody();
        int remoteTerm = voteResp.getTerm();
        if (remoteTerm < raftStatus.getCurrentTerm()) {
            log.warn("receive outdated vote resp, ignore, remoteTerm={}, reqTerm={}, remoteId={}, groupId={}",
                    voteResp.getTerm(), voteReq.getTerm(), remoteMember.getNode().getNodeId(), groupId);
        } else if (remoteTerm == raftStatus.getCurrentTerm()) {
            if (raftStatus.getRole() != RaftRole.candidate) {
                log.warn("receive vote resp, not candidate, ignore. remoteTerm={}, reqTerm={}, remoteId={}, groupId={}",
                        voteResp.getTerm(), voteReq.getTerm(), remoteMember.getNode().getNodeId(), groupId);
            } else {
                log.info("receive vote resp, granted={}, remoteTerm={}, reqTerm={}, remoteId={}, groupId={}",
                        voteResp.isVoteGranted(), voteResp.getTerm(),
                        voteReq.getTerm(), remoteMember.getNode().getNodeId(), groupId);
                if (voteResp.isVoteGranted()) {
                    votes.add(remoteMember.getNode().getNodeId());
                    remoteMember.setLastConfirmReqNanos(leaseStartTime);

                    if (voteSuccess(remoteMember.getNode().getNodeId(), false)) {
                        log.info("vote success, change to leader. groupId={}, term={}", groupId, raftStatus.getCurrentTerm());
                        RaftUtil.changeToLeader(raftStatus);
                        RaftUtil.updateLease(raftStatus);
                        cancelVote();
                        raft.sendHeartBeat();
                    }
                }
            }
        } else {
            RaftUtil.incrTerm(remoteTerm, raftStatus, -1);
            if (!StatusUtil.persist(raftStatus)) {
                log.error("save status fail when incr term. groupId={}, term={}", groupId, remoteTerm);
            }
        }
    }
}
