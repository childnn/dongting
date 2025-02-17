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

import java.io.File;
import java.nio.channels.AsynchronousFileChannel;

/**
 * @author huangli
 */
class LogFile {
    final File file;
    final AsynchronousFileChannel channel;
    final long startPos;
    final long endPos;

    int use = 0;

    long firstTimestamp;
    long firstIndex;
    int firstTerm;

    long deleteTimestamp;

    public LogFile(long startPos, long endPos, AsynchronousFileChannel channel, File file) {
        this.startPos = startPos;
        this.endPos = endPos;
        this.channel = channel;
        this.file = file;
    }
}
