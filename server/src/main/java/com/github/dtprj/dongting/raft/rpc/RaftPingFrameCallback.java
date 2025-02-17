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
package com.github.dtprj.dongting.raft.rpc;

import com.github.dtprj.dongting.codec.PbCallback;

import java.util.HashSet;

/**
 * @author huangli
 */
public class RaftPingFrameCallback extends PbCallback<RaftPingFrameCallback> {
    public int groupId;
    public int nodeId;
    public HashSet<Integer> nodeIdOfMembers = new HashSet<>();
    public HashSet<Integer> nodeIdOfObservers = new HashSet<>();

    @Override
    public boolean readFix32(int index, int value) {
        if (index == 1) {
            this.groupId = value;
        }
        if (index == 2) {
            this.nodeId = value;
        } else if (index == 3) {
            nodeIdOfMembers.add(value);
        } else if (index == 4) {
            nodeIdOfObservers.add(value);
        }
        return true;
    }

    @Override
    public RaftPingFrameCallback getResult() {
        return this;
    }
}
