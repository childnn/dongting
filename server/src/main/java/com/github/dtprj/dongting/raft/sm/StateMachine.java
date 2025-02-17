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
package com.github.dtprj.dongting.raft.sm;

import com.github.dtprj.dongting.buf.RefBuffer;
import com.github.dtprj.dongting.raft.server.RaftInput;

/**
 * All method defined in this class is called in raft thread except createEncoder/createDecoder method.
 *
 * @author huangli
 */
public interface StateMachine extends AutoCloseable, RaftCodecFactory {

    /**
     * this method is called in raft thread.
     */
    Object exec(long index, RaftInput input);

    /**
     * this method is called in raft thread.
     */
    void installSnapshot(long lastIncludeIndex, int lastIncludeTerm, long offset, boolean done, RefBuffer data);

    /**
     * this method is called in raft thread.
     */
    Snapshot takeSnapshot(int currentTerm);

}
