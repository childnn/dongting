/**
 * Created on 2022/9/6.
 */
package com.github.dtprj.dongting.buf;

import com.github.dtprj.dongting.common.Timestamp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author <a href="mailto:areyouok@gmail.com">huangli</a>
 */
public class SimpleByteBufferPoolTest {

    private static final Timestamp TS = new Timestamp();

    private SimpleByteBufferPool pool;

    private void plus(SimpleByteBufferPool pool, long millis) {
        Timestamp ts = pool.getTs();
        Timestamp tsNew = new Timestamp(ts.getNanoTime() + millis * 1000 * 1000,
                ts.getWallClockMillis() + millis);
        pool.setTs(tsNew);
    }

    @AfterEach
    public void tearDown() {
        if (pool != null) {
            pool.formatStat();
            pool = null;
        }
    }

    @Test
    public void testConstructor() {
        assertThrows(NullPointerException.class, () -> new SimpleByteBufferPool(TS, false, 0, false, new int[]{1024, 2048},
                new int[]{10, 10}, null, 1000));
        assertThrows(IllegalArgumentException.class, () -> new SimpleByteBufferPool(TS, false, 0, false, new int[]{1024, 2048},
                new int[]{10, 10}, new int[]{10}, 1000));

        assertThrows(IllegalArgumentException.class, () -> new SimpleByteBufferPool(TS, false, 0, false, new int[]{-1},
                new int[]{10}, new int[]{10}, 1000));
        assertThrows(IllegalArgumentException.class, () -> new SimpleByteBufferPool(TS, false, 0, false, new int[]{1024},
                new int[]{-1}, new int[]{10}, 1000));
        assertThrows(IllegalArgumentException.class, () -> new SimpleByteBufferPool(TS, false, 0, false, new int[]{1024},
                new int[]{10}, new int[]{-1}, 1000));
        assertThrows(IllegalArgumentException.class, () -> new SimpleByteBufferPool(TS, false, 0, false, new int[]{1024},
                new int[]{10}, new int[]{10}, -1));

        assertThrows(IllegalArgumentException.class, () -> new SimpleByteBufferPool(TS, false, 0, false, new int[]{1024, 2048},
                new int[]{10, 10}, new int[]{9, 9}, 1000));
    }

    @Test
    public void testBorrow1() {
        pool = new SimpleByteBufferPool(TS, false);
        ByteBuffer buf1 = pool.borrow(1);
        ByteBuffer buf2 = pool.borrow(1024);
        assertEquals(1, buf1.capacity());
        assertEquals(1024, buf2.capacity());
        assertNotSame(buf1, buf2);
        pool.release(buf1);
        pool.release(buf2);
        ByteBuffer buf3 = pool.borrow(1024);
        assertSame(buf3, buf2);
    }

    @Test
    public void testBorrow2() {
        pool = new SimpleByteBufferPool(TS, false);
        ByteBuffer buf1 = pool.borrow(1024);
        ByteBuffer buf2 = pool.borrow(1025);
        assertEquals(1024, buf1.capacity());
        assertEquals(2048, buf2.capacity());
    }

    @Test
    public void testBorrow3() {
        pool = new SimpleByteBufferPool(TS, false, 0, false, new int[]{1024, 2048},
                new int[]{10, 10}, new int[]{10, 10}, 1000);
        ByteBuffer buf1 = pool.borrow(4000);
        pool.release(buf1);
        assertNotSame(buf1, pool.borrow(4000));
    }

    @Test
    public void testRelease() {
        pool = new SimpleByteBufferPool(TS, false, 0, false, new int[]{1024, 2048},
                new int[]{1, 1}, new int[]{2, 2}, 1000);
        ByteBuffer buf1 = pool.borrow(1024);
        ByteBuffer buf2 = pool.borrow(1024);
        ByteBuffer buf3 = pool.borrow(1024);
        pool.release(buf1);
        pool.release(buf2);
        pool.release(buf3);
        assertSame(buf2, pool.borrow(1024));
        assertSame(buf1, pool.borrow(1024));
    }

    @Test
    public void testClean1() {
        pool = new SimpleByteBufferPool(TS, false, 0, false, new int[]{1024, 2048},
                new int[]{1, 1}, new int[]{2, 2}, 1000);
        ByteBuffer buf1 = pool.borrow(1024);
        ByteBuffer buf2 = pool.borrow(1024);
        pool.release(buf1);
        pool.release(buf2);

        // not clean
        plus(pool, 500);
        pool.clean();
        ByteBuffer buf3 = pool.borrow(1024);
        ByteBuffer buf4 = pool.borrow(1024);
        assertSame(buf2, buf3);
        assertSame(buf1, buf4);
        pool.release(buf1);
        pool.release(buf2);
    }

    @Test
    public void testThreshold() {
        pool = new SimpleByteBufferPool(TS, false, 2048);
        ByteBuffer buf = pool.borrow(2047);
        assertEquals(2047, buf.capacity());
        pool.release(buf);
        assertNotSame(buf, pool.borrow(2047));

        buf = pool.borrow(2048);
        assertEquals(2048, buf.capacity());
        pool.release(buf);
        assertNotSame(buf, pool.borrow(2048));

        buf = pool.borrow(2049);
        assertEquals(4096, buf.capacity());
        pool.release(buf);
        assertSame(buf, pool.borrow(4096));
    }

    @Test
    public void testClean2() {
        pool = new SimpleByteBufferPool(TS, false, 0, false, new int[]{1024, 2048},
                new int[]{1, 1}, new int[]{3, 3}, 1000);
        ByteBuffer buf1 = pool.borrow(1024);
        ByteBuffer buf2 = pool.borrow(1024);
        ByteBuffer buf3 = pool.borrow(1024);
        pool.release(buf1);
        pool.release(buf2);
        pool.release(buf3);

        //clean 2 buffer
        for (int i = 0; i < 5; i++) {
            plus(pool, 1001);
            pool.clean();
            ByteBuffer buf4 = pool.borrow(1024);
            ByteBuffer buf5 = pool.borrow(1024);
            ByteBuffer buf6 = pool.borrow(1024);
            assertSame(buf3, buf4);
            assertTrue(buf5 != buf1 && buf5 != buf2 && buf5 != buf3);
            assertTrue(buf6 != buf1 && buf6 != buf2 && buf6 != buf3);
            buf1 = buf4;
            buf2 = buf5;
            buf3 = buf6;
            pool.release(buf1);
            pool.release(buf2);
            pool.release(buf3);
        }
    }

    @Test
    public void testClean3() {
        pool = new SimpleByteBufferPool(TS, false, 0, false, new int[]{1024, 2048},
                new int[]{1, 1}, new int[]{2, 2}, 1000);
        ByteBuffer buf1 = pool.borrow(2048);
        ByteBuffer buf2 = pool.borrow(2048);
        pool.release(buf1);
        pool.release(buf2);

        //clean 1 buffer
        for (int i = 0; i < 5; i++) {
            plus(pool, 1001);
            pool.clean();
            ByteBuffer buf3 = pool.borrow(2048);
            ByteBuffer buf4 = pool.borrow(2048);
            assertSame(buf2, buf3);
            assertTrue(buf4 != buf1 && buf4 != buf2);
            buf1 = buf3;
            buf2 = buf4;
            pool.release(buf1);
            pool.release(buf2);
        }
    }

    @Test
    public void testClean4() {
        pool = new SimpleByteBufferPool(TS, false, 0, false, new int[]{1024, 2048},
                new int[]{0, 0}, new int[]{2, 2}, 1000);
        ByteBuffer buf1 = pool.borrow(2048);
        ByteBuffer buf2 = pool.borrow(2048);
        pool.release(buf1);
        pool.release(buf2);

        //clean 2 buffer
        for (int i = 0; i < 5; i++) {
            plus(pool, 1001);
            pool.clean();
            ByteBuffer buf3 = pool.borrow(2048);
            ByteBuffer buf4 = pool.borrow(2048);
            assertTrue(buf3 != buf1 && buf3 != buf2);
            assertTrue(buf4 != buf1 && buf4 != buf2);
            buf1 = buf3;
            buf2 = buf4;
            pool.release(buf1);
            pool.release(buf2);
        }
    }

    @Test
    public void testThreadSafe() throws Exception {
        pool = new SimpleByteBufferPool(TS, false, 0, true, new int[]{16, 32, 64, 128},
                new int[]{1, 1, 1, 1}, new int[]{20, 20, 20, 20}, 1000);
        threadSafeTest(pool, 128);
    }

    public static void threadSafeTest(ByteBufferPool pool, int maxCapacity) throws Exception {
        int threadNum = 2;
        ExecutorService executorService = Executors.newFixedThreadPool(threadNum);
        CountDownLatch countDownLatch = new CountDownLatch(threadNum);
        try {
            Runnable runnable = () -> {
                try {
                    for (int i = 0; i < 1000; i++) {
                        Random r = new Random();
                        ByteBuffer bb = pool.borrow(r.nextInt(maxCapacity) + 1);
                        int pos = bb.position();
                        if (pos > 0) {
                            throw new IllegalStateException();
                        } else {
                            bb.position(1);
                        }
                        pool.release(bb);
                        pool.clean();
                    }
                    countDownLatch.countDown();
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            };
            for (int i = 0; i < threadNum; i++) {
                executorService.submit(runnable);
            }
            assertTrue(countDownLatch.await(2000, TimeUnit.MILLISECONDS));
        } finally {
            executorService.shutdown();
        }
    }

    public static void main(String[] args) {
        int[] bufSize = new int[]{32 * 1024, 64 * 1024, 128 * 1024, 256 * 1024, 512 * 1024, 1024 * 1024, 2 * 1024 * 1024, 4 * 1024 * 1024};
        int[] minCount = new int[]{16, 8, 4, 2, 1, 0, 0, 0};
        int[] maxCount = new int[]{128, 128, 64, 64, 32, 16, 8, 4};
        long totalMax = 0;
        long totalMin = 0;
        for (int i = 0; i < bufSize.length; i++) {
            totalMax += (long) bufSize[i] * maxCount[i];
            totalMin += (long) bufSize[i] * minCount[i];
        }
        System.out.printf("max:%,d\nmin:%,d", totalMax, totalMin);
    }

}
