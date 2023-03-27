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

import com.github.dtprj.dongting.common.CloseUtil;
import com.github.dtprj.dongting.common.Pair;
import com.github.dtprj.dongting.common.Timestamp;
import com.github.dtprj.dongting.log.BugLog;
import com.github.dtprj.dongting.log.DtLog;
import com.github.dtprj.dongting.log.DtLogs;
import com.github.dtprj.dongting.raft.client.RaftException;
import com.github.dtprj.dongting.raft.server.LogItem;
import com.github.dtprj.dongting.raft.server.RaftGroupConfig;
import com.github.dtprj.dongting.raft.server.RaftInput;
import com.github.dtprj.dongting.raft.server.RaftLog;
import com.github.dtprj.dongting.raft.server.RaftOutput;
import com.github.dtprj.dongting.raft.server.RaftServerConfig;
import com.github.dtprj.dongting.raft.server.StateMachine;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * @author huangli
 */
public class RaftGroupThread extends Thread {
    private static final DtLog log = DtLogs.getLogger(RaftGroupThread.class);

    private final Random random = new Random();

    private final RaftServerConfig config;
    private final RaftGroupConfig groupConfig;
    private final RaftStatus raftStatus;
    private final Raft raft;
    private final MemberManager memberManager;
    private final VoteManager voteManager;
    private final StateMachine stateMachine;
    private final RaftLog raftLog;

    private final long heartbeatIntervalNanos;
    private final long electTimeoutNanos;

    // TODO optimise blocking queue
    private final LinkedBlockingQueue<Object> queue;

    public RaftGroupThread(RaftServerConfig serverConfig, RaftGroupConfig groupConfig, RaftStatus raftStatus,
                           RaftLog raftLog, StateMachine stateMachine, RaftExecutor raftExecutor,
                           Raft raft, MemberManager memberManager, VoteManager voteManager) {
        this.config = serverConfig;
        this.groupConfig = groupConfig;
        this.raftStatus = raftStatus;
        this.queue = raftExecutor.getQueue();
        this.raft = raft;
        this.memberManager = memberManager;
        this.stateMachine = stateMachine;
        this.raftLog = raftLog;

        electTimeoutNanos = Duration.ofMillis(config.getElectTimeout()).toNanos();
        raftStatus.setElectTimeoutNanos(electTimeoutNanos);
        heartbeatIntervalNanos = Duration.ofMillis(config.getHeartbeatInterval()).toNanos();

        this.voteManager = voteManager;
    }

    public void init() {
        StatusUtil.initStatusFileChannel(groupConfig.getDataDir(), groupConfig.getStatusFile(), raftStatus);

        Pair<Integer, Long> initResult = raftLog.init();
        stateMachine.init(raftLog);
        raftStatus.setLastLogTerm(initResult.getLeft());
        raftStatus.setLastLogIndex(initResult.getRight());
    }

    public void waitReady() {
        int electQuorum = raftStatus.getElectQuorum();
        if (electQuorum <= 1) {
            return;
        }
        try {
            memberManager.createReadyFuture(electQuorum).get();
        } catch (Exception e) {
            throw new RaftException(e);
        }
    }

    @Override
    public void run() {
        try {
            if (raftStatus.getElectQuorum() == 1 && raftStatus.getNodeIdOfMembers().contains(config.getNodeId())) {
                RaftUtil.changeToLeader(raftStatus);
                raft.sendHeartBeat();
            }
            run0();
        } catch (Throwable e) {
            BugLog.getLog().error("raft thread error", e);
        } finally {
            CloseUtil.close(raftStatus.getStatusFileLock(), raftStatus.getRandomAccessStatusFile());
        }
    }

    private void run0() {
        Timestamp ts = raftStatus.getTs();
        long lastCleanTime = ts.getNanoTime();
        ArrayList<RaftTask> rwTasks = new ArrayList<>(32);
        ArrayList<Runnable> runnables = new ArrayList<>(32);
        ArrayList<Object> queueData = new ArrayList<>(32);
        boolean poll = true;
        while (!raftStatus.isStop()) {
            if (raftStatus.getRole() != RaftRole.observer) {
                memberManager.ensureRaftMemberStatus();
            }

            try {
                poll = pollAndRefreshTs(ts, queueData, poll);
            } catch (InterruptedException e) {
                return;
            }
            if (!process(rwTasks, runnables, queueData)) {
                return;
            }
            if (queueData.size() > 0) {
                ts.refresh(1);
                queueData.clear();
            }
            if (ts.getNanoTime() - lastCleanTime > 5 * 1000 * 1000) {
                raftStatus.getPendingRequests().cleanPending(raftStatus,
                        config.getMaxPendingWrites(), config.getMaxPendingWriteBytes());
                idle(ts);
                lastCleanTime = ts.getNanoTime();
            }
        }
        stateMachine.shutdown();
        raftLog.shutdown();
    }

    private boolean process(ArrayList<RaftTask> rwTasks, ArrayList<Runnable> runnables, ArrayList<Object> queueData) {
        RaftStatus raftStatus = this.raftStatus;
        int len = queueData.size();
        for (int i = 0; i < len; i++) {
            Object o = queueData.get(i);
            if (o instanceof RaftTask) {
                rwTasks.add((RaftTask) o);
            } else if (o instanceof Runnable) {
                runnables.add((Runnable) o);
            } else {
                BugLog.getLog().error("type error: {}", o.getClass());
                return false;
            }
        }

        // the sequence of RaftTask and Runnable is reordered, but it will not affect the linearizability
        if (rwTasks.size() > 0) {
            if (!raftStatus.isHoldRequest()) {
                raft.raftExec(rwTasks);
                rwTasks.clear();
                raftStatus.copyShareStatus();
            }
        }
        len = runnables.size();
        if (len > 0) {
            for (int i = 0; i < len; i++) {
                runnables.get(i).run();
            }
            runnables.clear();
            raftStatus.copyShareStatus();
        }
        return true;
    }

    private boolean pollAndRefreshTs(Timestamp ts, ArrayList<Object> queueData, boolean poll) throws InterruptedException {
        long oldNanos = ts.getNanoTime();
        if (poll) {
            Object o = queue.poll(50, TimeUnit.MILLISECONDS);
            if (o != null) {
                queueData.add(o);
            }
        } else {
            queue.drainTo(queueData);
        }

        ts.refresh(1);
        return ts.getNanoTime() - oldNanos > 2 * 1000 * 1000 || queueData.size() == 0;
    }

    public void requestShutdown() {
        raftStatus.setStop(true);
        log.info("request raft thread shutdown");
    }

    private void idle(Timestamp ts) {
        RaftStatus raftStatus = this.raftStatus;
        if (raftStatus.isError()) {
            return;
        }
        if (raftStatus.getElectQuorum() <= 1) {
            return;
        }
        long roundTimeNanos = ts.getNanoTime();

        RaftRole role = raftStatus.getRole();
        if (roundTimeNanos - raftStatus.getHeartbeatTime() > heartbeatIntervalNanos) {
            if (role == RaftRole.leader) {
                raftStatus.setHeartbeatTime(roundTimeNanos);
                raft.sendHeartBeat();
                raftStatus.copyShareStatus();
            }
        }
        if (role == RaftRole.follower || role == RaftRole.candidate) {
            if (roundTimeNanos - raftStatus.getLastElectTime() > electTimeoutNanos + random.nextInt(200)) {
                voteManager.tryStartPreVote();
                raftStatus.copyShareStatus();
            }
        }
    }

    public CompletableFuture<RaftOutput> submitRaftTask(RaftInput input) {
        CompletableFuture<RaftOutput> f = new CompletableFuture<>();
        RaftTask t = new RaftTask(raftStatus.getTs(), LogItem.TYPE_NORMAL, input, f);
        queue.offer(t);
        return f;
    }
}
