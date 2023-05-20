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

import com.github.dtprj.dongting.buf.RefBufferFactory;
import com.github.dtprj.dongting.log.BugLog;
import com.github.dtprj.dongting.log.DtLog;
import com.github.dtprj.dongting.log.DtLogs;
import com.github.dtprj.dongting.raft.client.RaftException;
import com.github.dtprj.dongting.raft.impl.RaftExecutor;
import com.github.dtprj.dongting.raft.server.ChecksumException;
import com.github.dtprj.dongting.raft.server.LogItem;
import com.github.dtprj.dongting.raft.server.RaftGroupConfigEx;
import com.github.dtprj.dongting.raft.server.RaftLog;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.zip.CRC32C;

/**
 * @author huangli
 */
class DefaultLogIterator implements RaftLog.LogIterator {
    private static final DtLog log = DtLogs.getLogger(DefaultLogIterator.class);

    private static final int STATE_ITEM_HEADER = 1;
    private static final int STATE_BIZ_HEADER = 2;
    private static final int STATE_BODY = 3;

    private final IdxOps idxFiles;
    private final FileOps logFiles;
    private final RaftExecutor raftExecutor;
    private final RefBufferFactory heapPool;
    private final RaftGroupConfigEx groupConfig;
    private final ByteBuffer readBuffer;

    private final Supplier<Boolean> fullIndicator;
    private final CRC32C crc32c = new CRC32C();
    private final LogHeader header = new LogHeader();

    private long nextIndex = -1;
    private long nextPos = -1;

    // TODO check error handling
    private boolean error;
    private boolean close;

    private int bytes;
    private int limit;
    private int bytesLimit;
    private List<LogItem> result;
    private CompletableFuture<List<LogItem>> future;
    private int state;
    private LogItem item;

    DefaultLogIterator(IdxOps idxFiles, FileOps logFiles, RaftGroupConfigEx groupConfig, Supplier<Boolean> fullIndicator) {
        this.idxFiles = idxFiles;
        this.logFiles = logFiles;
        this.raftExecutor = (RaftExecutor) groupConfig.getRaftExecutor();
        this.readBuffer = groupConfig.getDirectPool().borrow(1024 * 1024);
        this.groupConfig = groupConfig;
        this.heapPool = groupConfig.getHeapPool();
        this.readBuffer.limit(0);
        this.fullIndicator = fullIndicator;
    }

    @Override
    public CompletableFuture<List<LogItem>> next(long index, int limit, int bytesLimit) {
        try {
            if (error || future != null || close) {
                BugLog.getLog().error("iterator state error: {},{},{}", error, future, close);
                throw new RaftException("iterator state error");
            }
            if (nextIndex == -1) {
                nextPos = idxFiles.syncLoadLogPos(index);
                nextIndex = index;
            } else {
                if (nextIndex != index) {
                    throw new RaftException("nextIndex!=index");
                }
            }

            this.result = new ArrayList<>();
            this.future = new CompletableFuture<>();
            this.item = null;
            this.bytes = 0;
            this.limit = limit;
            this.state = STATE_ITEM_HEADER;
            this.bytesLimit = bytesLimit;

            if (readBuffer.hasRemaining()) {
                extractAndLoadNextIfNecessary();
            } else {
                readBuffer.clear();
                loadLogFromStore();
            }
            return future;
        } catch (Throwable e) {
            error = true;
            future = null;
            return CompletableFuture.failedFuture(e);
        }
    }

    private void finish(Throwable ex) {
        error = true;
        future.completeExceptionally(ex);
        future = null;
    }

    private void finishWithCancel() {
        error = true;
        future.cancel(false);
        future = null;
    }

    private void finish(List<LogItem> result, long readSize) {
        future.complete(result);
        nextIndex += result.size();
        nextPos += readSize;
        future = null;
    }

    private void extractAndLoadNextIfNecessary() {
        int oldRemaining = readBuffer.remaining();
        ByteBuffer buf = readBuffer;
        OUT:
        while (true) {
            switch (state) {
                case STATE_ITEM_HEADER:
                    if (buf.remaining() > LogHeader.ITEM_HEADER_SIZE) {
                        extractHeader(buf);
                        crc32c.reset();
                        state = STATE_BIZ_HEADER;
                        if (result.size() > 0 && item.getActualBodySize() + bytes > bytesLimit) {
                            finish(result, oldRemaining - buf.remaining());
                            return;
                        } else {
                            break;
                        }
                    } else {
                        break OUT;
                    }
                case STATE_BIZ_HEADER:
                    if (extractBizHeader()) {
                        crc32c.reset();
                        state = STATE_BODY;
                        break;
                    } else {
                        break OUT;
                    }
                case STATE_BODY:
                    if (extractBizBody()) {
                        if (result.size() >= limit) {
                            finish(result, oldRemaining - buf.remaining());
                            return;
                        } else {
                            state = STATE_ITEM_HEADER;
                            break;
                        }
                    } else {
                        break OUT;
                    }
                default:
                    throw new RaftException("error state:" + state);
            }
        }

        nextPos += oldRemaining - buf.remaining();
        LogFileQueue.prepareNextRead(buf);
        loadLogFromStore();
    }

    private void loadLogFromStore() {
        long pos = nextPos;
        long rest = logFiles.restInCurrentFile(pos);
        if (rest <= 0) {
            log.error("rest is illegal. pos={}", pos);
            finish(new RaftException("rest is illegal."));
            return;
        }
        LogFile logFile = logFiles.getLogFile(pos);
        long fileStartPos = logFiles.filePos(pos);
        ByteBuffer readBuffer = this.readBuffer;
        if (rest < readBuffer.remaining()) {
            readBuffer.limit((int) (readBuffer.position() + rest));
        }
        AsyncIoTask t = new AsyncIoTask(readBuffer, fileStartPos, logFile, fullIndicator);
        logFile.use++;
        t.exec().whenCompleteAsync((v, ex) -> resumeAfterLoad(logFile, ex), raftExecutor);
    }

    private void resumeAfterLoad(LogFile logFile, Throwable ex) {
        try {
            logFile.use--;
            if (fullIndicator.get()) {
                finishWithCancel();
            } else if (ex != null) {
                finish(ex);
            } else {
                readBuffer.flip();
                extractAndLoadNextIfNecessary();
            }
        } catch (Throwable e) {
            finish(e);
        }
    }

    private void extractHeader(ByteBuffer readBuffer) {
        LogHeader header = this.header;
        header.read(readBuffer);
        if (!header.crcMatch()) {
            throw new ChecksumException();
        }

        int bodyLen = header.bodyLen;
        if (!header.checkHeader(logFiles.filePos(nextPos), logFiles.fileLength())) {
            throw new RaftException("invalid log item length: totalLen=" + header.totalLen + ", nextPos=" + nextPos);
        }

        LogItem li = new LogItem();
        this.item = li;
        li.setIndex(header.index);
        li.setType(header.type);
        li.setTerm(header.term);
        li.setPrevLogTerm(header.prevLogTerm);
        li.setTimestamp(header.timestamp);

        int bizHeaderLen = header.bizHeaderLen;
        li.setActualHeaderSize(bizHeaderLen);
        if (bizHeaderLen > 0) {
            li.setHeaderBuffer(heapPool.create(bizHeaderLen));
        }

        li.setActualBodySize(bodyLen);
        if (bodyLen > 0) {
            li.setBodyBuffer(heapPool.create(bodyLen));
        }
    }

    private boolean extractBizHeader() {
        int bizHeaderLen = header.bizHeaderLen;
        if (bizHeaderLen == 0) {
            return true;
        }
        ByteBuffer destBuf = item.getHeaderBuffer().getBuffer();
        return readData(bizHeaderLen, destBuf);
    }

    private boolean readData(int dataLen, ByteBuffer destBuf) {
        int read = destBuf.position();
        int restBytes = dataLen - read;
        ByteBuffer buf = readBuffer;
        if (restBytes > 0 && buf.remaining() > 0) {
            LogFileQueue.updateCrc(crc32c, buf, buf.position(), restBytes);
        }
        if (buf.remaining() >= restBytes + 4) {
            buf.get(destBuf.array(), read, restBytes);
            destBuf.limit(dataLen);
            destBuf.position(0);
            if (crc32c.getValue() != buf.getInt()) {
                throw new ChecksumException("crc32c not match");
            }
            return true;
        } else {
            int restBodyLen = Math.min(restBytes, buf.remaining());
            buf.get(destBuf.array(), read, restBodyLen);
            destBuf.position(read + restBodyLen);
            return false;
        }
    }

    private boolean extractBizBody() {
        int bodyLen = header.bodyLen;
        if (bodyLen == 0) {
            result.add(item);
            item = null;
            return true;
        }
        ByteBuffer destBuf = item.getBodyBuffer().getBuffer();
        boolean readFinish = readData(bodyLen, destBuf);
        if(readFinish){
            result.add(item);
            bytes += bodyLen;
            item = null;
        }
        return readFinish;
    }

    @Override
    public void close() {
        if (close) {
            BugLog.getLog().error("iterator has closed");
        } else {
            groupConfig.getDirectPool().release(readBuffer);
        }
        close = true;
    }
}
