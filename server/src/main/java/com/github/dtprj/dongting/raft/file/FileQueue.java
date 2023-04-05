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
package com.github.dtprj.dongting.raft.file;

import com.github.dtprj.dongting.common.CloseUtil;
import com.github.dtprj.dongting.log.DtLog;
import com.github.dtprj.dongting.log.DtLogs;
import com.github.dtprj.dongting.raft.client.RaftException;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author huangli
 */
abstract class FileQueue {
    private static final DtLog log = DtLogs.getLogger(FileQueue.class);
    private static final Pattern PATTERN = Pattern.compile("^(\\d{20})$");
    protected final IndexedQueue<LogFile> queue = new IndexedQueue<>();
    protected final File dir;
    protected final Executor ioExecutor;

    protected long queueStartPosition;
    protected long queueEndPosition;

    protected CompletableFuture<LogFile> allocateFuture;

    public FileQueue(File dir, Executor ioExecutor) {
        this.dir = dir;
        this.ioExecutor = ioExecutor;
    }

    protected abstract long getFileSize();

    protected abstract long getWritePos();

    protected abstract int getFileLenShiftBits();

    /**
     * this method will be called in ioExecutor
     */
    protected void afterFileAllocated(File f, FileChannel channel) throws IOException {
    }

    public void init() throws IOException {
        File[] files = dir.listFiles();
        Arrays.sort(files);
        for (File f : files) {
            if (!f.isFile()) {
                continue;
            }
            Matcher matcher = PATTERN.matcher(f.getName());
            if (matcher.matches()) {
                if (f.length() > getFileSize()) {
                    log.error("file size error: {}, size={}", f.getPath(), f.length());
                    throw new RaftException("file size error");
                }
                long startPos = Long.parseLong(matcher.group(1));
                log.info("load file: {}", f.getPath());
                FileChannel channel = FileChannel.open(f.toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE);
                queue.addLast(new LogFile(startPos, startPos + getFileSize(), channel, f.getPath()));
            }
        }
        for (int i = 0; i < queue.size(); i++) {
            LogFile lf = queue.get(i);
            if ((lf.startPos & (getFileSize() - 1)) != 0) {
                throw new RaftException("file start index error: " + lf.startPos);
            }
            if (i != 0 && lf.startPos != queue.get(i - 1).endPos) {
                throw new RaftException("not follow previous file " + lf.startPos);
            }
        }

        if (queue.size() > 0) {
            queueStartPosition = queue.get(0).startPos;
            queueEndPosition = queue.get(queue.size() - 1).endPos;
        }
        tryAllocate();
    }

    protected LogFile getLogFile(long filePos) {
        int index = (int) ((filePos - queueStartPosition) >>> getFileLenShiftBits());
        return queue.get(index);
    }

    private void tryAllocate() {
        if (getWritePos() >= queueEndPosition - getFileSize()) {
            if (allocateFuture == null) {
                allocateFuture = allocate(queueEndPosition);
            } else {
                if (allocateFuture.isDone()) {
                    try {
                        LogFile newFile = allocateFuture.join();
                        queue.addLast(newFile);
                        queueEndPosition = newFile.endPos;
                    } catch (Exception e) {
                        log.error("allocate file error", e);
                    } finally {
                        allocateFuture = null;
                    }
                }
            }
        }
    }

    private void processAllocResult() throws InterruptedException {
        try {
            LogFile newFile = allocateFuture.get();
            queue.addLast(newFile);
            queueEndPosition = newFile.endPos;
        } catch (InterruptedException e) {
            log.info("interrupted while allocate file");
            throw e;
        } catch (Throwable e) {
            log.error("allocate file error", e);
        } finally {
            allocateFuture = null;
        }
    }

    protected boolean ensureWritePosReady() {
        return ensureWritePosReady(getWritePos());
    }

    protected boolean ensureWritePosReady(long pos) {
        try {
            while (pos >= queueEndPosition) {
                if (allocateFuture != null) {
                    processAllocResult();
                } else {
                    log.error("allocate future is null");
                    tryAllocate();
                    processAllocResult();
                }
            }
            // pre allocate next file
            tryAllocate();
            return true;
        } catch (InterruptedException e) {
            return false;
        }
    }

    private CompletableFuture<LogFile> allocate(long currentEndPosition) {
        return CompletableFuture.supplyAsync(() -> {
            RandomAccessFile raf = null;
            FileChannel channel = null;
            try {
                File f = new File(dir, String.valueOf(currentEndPosition));
                raf = new RandomAccessFile(f, "rw");
                raf.setLength(getFileSize());
                channel = FileChannel.open(f.toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE);
                afterFileAllocated(f, channel);
                return new LogFile(currentEndPosition, currentEndPosition + getFileSize(), channel, f.getPath());
            } catch (IOException e) {
                if (channel != null) {
                    CloseUtil.close(channel);
                }
                throw new RaftException(e);
            } finally {
                CloseUtil.close(raf);
            }
        }, ioExecutor);
    }

    public void close() {
        for (int i = 0; i < queue.size(); i++) {
            try {
                queue.get(i).channel.close();
            } catch (IOException e) {
                log.error("close file error", e);
            }
        }
    }
}
