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
package com.github.dtprj.dongting.codec;

import java.nio.ByteBuffer;
import java.util.function.Function;

/**
 * @author huangli
 */
public class PbNoCopyDecoder<T> implements Decoder<T> {

    private final Function<DecodeContext, PbCallback<T>> callbackCreator;

    public PbNoCopyDecoder(Function<DecodeContext, PbCallback<T>> callbackCreator) {
        this.callbackCreator = callbackCreator;
    }

    @Override
    public T decode(DecodeContext context, ByteBuffer buffer, int bodyLen, int currentPos) {
        PbParser parser;
        PbCallback<T> callback;
        if (currentPos == 0) {
            callback = callbackCreator.apply(context);
            parser = context.createOrResetPbParser(callback, bodyLen);
        } else {
            parser = context.getPbParser();
            callback = parser.getCallback();
        }
        boolean end = buffer.remaining() >= bodyLen - currentPos;
        parser.parse(buffer);
        if (end) {
            return callback.getResult();
        } else {
            return null;
        }
    }

}
