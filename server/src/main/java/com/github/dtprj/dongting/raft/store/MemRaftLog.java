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
package com.github.dtprj.dongting.raft.store;

import com.github.dtprj.dongting.codec.Encoder;
import com.github.dtprj.dongting.common.Pair;
import com.github.dtprj.dongting.common.Timestamp;
import com.github.dtprj.dongting.raft.server.LogItem;
import com.github.dtprj.dongting.raft.server.RaftException;
import com.github.dtprj.dongting.raft.server.RaftGroupConfigEx;
import com.github.dtprj.dongting.raft.server.RaftStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * @author huangli
 */
public class MemRaftLog implements RaftLog {

    private final IndexedQueue<MemLog> logs;
    private final Timestamp ts;
    private final RaftStatus raftStatus;
    private final RaftGroupConfigEx groupConfig;
    private final int maxItems;
    private boolean closed;

    private static final class MemLog {
        LogItem item;
        long deleteTimestamp;
        int flowControlSize;
    }


    public MemRaftLog(RaftGroupConfigEx groupConfig, int maxItems) {
        this.ts = groupConfig.getTs();
        this.raftStatus = groupConfig.getRaftStatus();
        this.groupConfig = groupConfig;
        this.maxItems = maxItems;
        logs = new IndexedQueue<>();
    }

    @Override
    public Pair<Integer, Long> init(Supplier<Boolean> cancelInit) throws Exception {
        return new Pair<>(0, 0L);
    }

    @Override
    public void append(List<LogItem> list) throws Exception {
        IndexedQueue<MemLog> logs = this.logs;
        //noinspection ForLoopReplaceableByForEach
        for (int i = 0; i < list.size(); i++) {
            LogItem logItem = list.get(i);
            MemLog it = new MemLog();
            it.item = logItem;
            it.item.retain();
            logs.addLast(it);
        }
        if (logs.size() > maxItems) {
            doDelete();
        }
    }

    @Override
    public LogIterator openIterator(Supplier<Boolean> cancelIndicator) {
        return new LogIterator() {
            @Override
            public CompletableFuture<List<LogItem>> next(long index, int limit, int bytesLimit) {
                if (closed || cancelIndicator.get()) {
                    return CompletableFuture.failedFuture(new CancellationException());
                }
                IndexedQueue<MemLog> logs = MemRaftLog.this.logs;
                if (logs.size() == 0) {
                    return CompletableFuture.failedFuture(new RaftException("no logs"));
                }
                MemLog first = logs.get(0);
                int logIndex = (int) (index - first.item.getIndex());
                if (logIndex < 0 || logIndex > logs.size()) {
                    return CompletableFuture.failedFuture(new RaftException("bad index " + index +
                            ", fist index is " + first.item.getIndex()));
                }
                ArrayList<LogItem> list = new ArrayList<>(limit);
                while (logIndex < logs.size()) {
                    MemLog it = logs.get(logIndex);
                    LogItem li = it.item;
                    if (it.flowControlSize == 0 && li.getType() == LogItem.TYPE_NORMAL) {
                        @SuppressWarnings("rawtypes")
                        Encoder encoder = groupConfig.getCodecFactory().createEncoder(li.getBizType(), false);
                        //noinspection unchecked
                        it.flowControlSize = encoder.actualSize(li.getBody());
                    }
                    bytesLimit -= it.flowControlSize;
                    if (list.size() > 0 && bytesLimit < 0) {
                        break;
                    }
                    li.retain();
                    list.add(li);
                    logIndex++;
                    if (list.size() >= limit) {
                        break;
                    }
                }
                return CompletableFuture.completedFuture(list);
            }

            @Override
            public void close() {
            }
        };
    }

    @Override
    public CompletableFuture<Pair<Integer, Long>> findReplicatePos(int suggestTerm, long suggestIndex,
                                                                   int lastTerm, long lastIndex,
                                                                   Supplier<Boolean> cancelIndicator) {
        return CompletableFuture.completedFuture(findReplicatePos0(suggestTerm, suggestIndex));
    }

    private Pair<Integer, Long> findReplicatePos0(int suggestTerm, long suggestIndex) {
        IndexedQueue<MemLog> logs = this.logs;
        if (logs.size() == 0) {
            return null;
        }
        LogItem first = logs.get(0).item;
        LogItem last = logs.get(logs.size() - 1).item;
        int c = LogFileQueue.compare(last.getTerm(), last.getIndex(), suggestTerm, suggestIndex);
        if (c < 0) {
            return null;
        }
        c = LogFileQueue.compare(first.getTerm(), first.getIndex(), suggestTerm, suggestIndex);
        if (c > 0) {
            return null;
        }
        int left = 0;
        int right = logs.size() - 1;
        while (left < right) {
            int mid = (left + right + 1) >>> 1;
            MemLog memLog = logs.get(mid);
            if (memLog.deleteTimestamp > 0) {
                left = mid + 1;
            }
            LogItem i = memLog.item;

            c = LogFileQueue.compare(i.getTerm(), i.getIndex(), suggestTerm, suggestIndex);
            if (c < 0) {
                left = mid;
            } else if (c > 0) {
                right = mid - 1;
            } else {
                return new Pair<>(i.getTerm(), i.getIndex());
            }
        }
        if (left >= logs.size()) {
            return null;
        }
        MemLog memLog = logs.get(left);
        if (memLog.deleteTimestamp > 0) {
            return null;
        }
        LogItem li = memLog.item;
        return new Pair<>(li.getTerm(), li.getIndex());
    }

    @Override
    public void markTruncateByIndex(long index, long delayMillis) {
        markDelete(delayMillis, li -> li.getIndex() < index);
    }

    @Override
    public void markTruncateByTimestamp(long timestampMillis, long delayMillis) {
        markDelete(delayMillis, li -> li.getTimestamp() < timestampMillis
                && li.getIndex() < raftStatus.getLastApplied());
    }

    private void markDelete(long delayMillis, Predicate<LogItem> predicate) {
        IndexedQueue<MemLog> logs = this.logs;
        int len = logs.size();
        long deleteTimestamp = ts.getWallClockMillis() + delayMillis;
        for (int i = 0; i < len; i++) {
            MemLog memLog = logs.get(i);
            if (predicate.test(memLog.item)) {
                if (memLog.deleteTimestamp == 0) {
                    memLog.deleteTimestamp = deleteTimestamp;
                } else {
                    memLog.deleteTimestamp = Math.min(deleteTimestamp, memLog.deleteTimestamp);
                }
            } else {
                return;
            }
        }
    }

    @Override
    public void doDelete() {
        IndexedQueue<MemLog> logs = this.logs;
        MemLog memLog;
        while ((memLog = logs.get(0)) != null) {
            if (memLog.deleteTimestamp > 0 && memLog.deleteTimestamp < ts.getWallClockMillis()) {
                memLog.item.release();
                logs.removeFirst();
            } else if (logs.size() > maxItems && memLog.item.getIndex() < raftStatus.getLastApplied()) {
                memLog.item.release();
                logs.removeFirst();
            } else {
                break;
            }
        }
    }

    @Override
    public void close() throws Exception {
        closed = true;
        MemLog i;
        while ((i = logs.removeFirst()) != null) {
            i.item.release();
        }
    }
}
