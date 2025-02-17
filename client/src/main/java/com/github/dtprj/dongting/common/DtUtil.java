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
package com.github.dtprj.dongting.common;

import com.github.dtprj.dongting.log.DtLog;
import com.github.dtprj.dongting.log.DtLogs;

/**
 * @author huangli
 */
public class DtUtil {
    private static final DtLog log = DtLogs.getLogger(DtUtil.class);

    private static final int JAVA_VER = majorVersion(System.getProperty("java.specification.version", "1.8"));

    public static boolean DEBUG = Boolean.parseBoolean(System.getProperty("dt.debug", "false"));

    private static final Runtime RUNTIME = Runtime.getRuntime();
    private static int CPU_COUNT = RUNTIME.availableProcessors();
    private static int cpuCountInvoke;

    public static void close(Object... resources) {
        for (Object res : resources) {
            close(res);
        }
    }

    public static void close(Object res) {
        if (res != null) {
            try {
                if (res instanceof AutoCloseable) {
                    ((AutoCloseable) res).close();
                } else if (res instanceof LifeCircle) {
                    ((LifeCircle) res).stop();
                } else {
                    log.error("unknown resource type:{}", res.getClass());
                }
            } catch (Throwable e) {
                log.error("close fail", e);
            }
        }
    }

    public static int javaVersion() {
        return JAVA_VER;
    }

    // Package-private for testing only
    static int majorVersion(final String javaSpecVersion) {
        final String[] components = javaSpecVersion.split("\\.");
        final int[] version = new int[components.length];
        for (int i = 0; i < components.length; i++) {
            version[i] = Integer.parseInt(components[i]);
        }

        if (version[0] == 1) {
            assert version[1] >= 8;
            return version[1];
        } else {
            return version[0];
        }
    }

    /**
     * Checks that the given argument is strictly positive. If it is not, throws {@link IllegalArgumentException}.
     * Otherwise, returns the argument.
     */
    public static void checkPositive(int i, String name) {
        if (i <= 0) {
            throw new IllegalArgumentException(name + " : " + i + " (expected: > 0)");
        }
    }

    public static void checkPositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " : " + value + " (expected: >= 0)");
        }
    }

    public static void checkPositive(float i, String name) {
        if (i <= 0) {
            throw new IllegalArgumentException(name + " : " + i + " (expected: > 0)");
        }
    }

    public static void checkNotNegative(int i, String name) {
        if (i < 0) {
            throw new IllegalArgumentException(name + " : " + i + " (expected: >= 0)");
        }
    }

    public static void checkNotNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " : " + value + " (expected: >= 0)");
        }
    }

    public static void restoreInterruptStatus() {
        if (!Thread.currentThread().isInterrupted()) {
            Thread.currentThread().interrupt();
        }
    }

    public static int processorCount() {
        if (cpuCountInvoke++ % 100 == 0) {
            CPU_COUNT = RUNTIME.availableProcessors();
        }
        return CPU_COUNT;
    }
}
