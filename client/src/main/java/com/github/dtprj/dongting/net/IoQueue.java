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
package com.github.dtprj.dongting.net;

import com.github.dtprj.dongting.log.DtLog;
import com.github.dtprj.dongting.log.DtLogs;
import com.github.dtprj.dongting.queue.MpscLinkedQueue;

import java.util.ArrayList;

/**
 * @author huangli
 */
class IoQueue {
    private static final DtLog log = DtLogs.getLogger(IoQueue.class);
    private final MpscLinkedQueue<Object> queue = MpscLinkedQueue.newInstance();
    private final ArrayList<DtChannel> channels;
    private final boolean server;
    private int invokeIndex;

    private volatile boolean close;

    public IoQueue(ArrayList<DtChannel> channels) {
        this.channels = channels;
        this.server = channels == null;
    }

    public void writeFromBizThread(WriteData data) {
        if (!close) {
            queue.offer(data);
        } else if (data.getFuture() != null) {
            data.getFuture().completeExceptionally(new NetException("IoQueue closed"));
        }
    }

    public void scheduleFromBizThread(Runnable runnable) throws NetException {
        if (!close) {
            queue.offer(runnable);
        } else {
            throw new NetException("IoQueue closed");
        }
    }

    public void dispatchActions() {
        Object data;
        while ((data = queue.relaxedPoll()) != null) {
            if (data instanceof WriteData) {
                processWriteData((WriteData) data);
            } else {
                ((Runnable) data).run();
            }
        }
    }

    private void processWriteData(WriteData wo) {
        WriteFrame frame = wo.getData();
        DtChannel dtc = wo.getDtc();
        if (dtc == null) {
            Peer peer = wo.getPeer();
            if (peer == null) {
                if (!server && frame.getFrameType() == FrameType.TYPE_REQ) {
                    dtc = selectChannel();
                    if (dtc == null) {
                        wo.getData().clean();
                        wo.getFuture().completeExceptionally(new NetException("no available channel"));
                        return;
                    }
                } else {
                    log.error("no peer set");
                    if (frame.getFrameType() == FrameType.TYPE_REQ) {
                        wo.getData().clean();
                        wo.getFuture().completeExceptionally(new NetException("no peer set"));
                    }
                    return;
                }
            } else {
                dtc = peer.getDtChannel();
                if (dtc == null) {
                    if (frame.getFrameType() == FrameType.TYPE_REQ) {
                        wo.getData().clean();
                        wo.getFuture().completeExceptionally(new NetException("not connected"));
                    }
                    return;
                }
            }
            wo.setDtc(dtc);
        }

        if (dtc.isClosed()) {
            if (wo.getFuture() != null) {
                wo.getData().clean();
                wo.getFuture().completeExceptionally(new NetException("channel closed during dispatch"));
            }
            return;
        }
        dtc.getSubQueue().enqueue(wo);
    }

    private DtChannel selectChannel() {
        ArrayList<DtChannel> list = this.channels;
        int size = list.size();
        if (size == 0) {
            return null;
        }
        int idx = invokeIndex;
        if (idx < size) {
            invokeIndex = idx + 1;
            return list.get(idx);
        } else {
            invokeIndex = 0;
            return list.get(0);
        }
    }

    public void close() {
        close = true;
    }
}
