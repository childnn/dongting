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

import com.github.dtprj.dongting.codec.Decoder;
import com.github.dtprj.dongting.common.DtTime;
import com.github.dtprj.dongting.common.DtUtil;
import com.github.dtprj.dongting.log.BugLog;
import com.github.dtprj.dongting.log.DtLog;
import com.github.dtprj.dongting.log.DtLogs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * @author huangli
 */
public class NioClient extends NioNet {

    private static final DtLog log = DtLogs.getLogger(NioClient.class);

    private final NioClientConfig config;
    final NioWorker worker;

    private final CopyOnWriteArrayList<Peer> peers;
    private List<CompletableFuture<Void>> startFutures;

    private DtTime startDeadline;

    public NioClient(NioClientConfig config) {
        super(config);
        this.config = config;
        ArrayList<Peer> list = new ArrayList<>();
        if (config.getHostPorts() != null) {
            for (HostPort hp : config.getHostPorts()) {
                Peer p = new Peer(hp, this);
                list.add(p);
            }
        }
        this.peers = new CopyOnWriteArrayList<>(list);
        this.worker = new NioWorker(nioStatus, config.getName() + "IoWorker", config);
    }

    @Override
    protected void doStart() {
        startDeadline = new DtTime(config.getWaitStartTimeout(), TimeUnit.MILLISECONDS);
        startFutures = new ArrayList<>();
        initBizExecutor();
        worker.start();
        for (Peer peer : peers) {
            startFutures.add(worker.connect(peer, startDeadline));
        }
    }

    public void waitStart() {
        int successCount = 0;
        int timeoutCount = 0;
        int failCount = 0;
        List<Peer> peers = this.peers;
        StringBuilder sb = new StringBuilder(startFutures.size() * 32);
        sb.append("peer status:\n");
        for (int i = 0; i < peers.size(); i++) {
            Peer peer = peers.get(i);
            sb.append(peer.getEndPoint()).append(' ');
            CompletableFuture<Void> f = startFutures.get(i);
            if (!f.isDone()) {
                long restMillis = startDeadline.rest(TimeUnit.MILLISECONDS);
                if (restMillis > 0) {
                    try {
                        f.get(restMillis, TimeUnit.MILLISECONDS);
                        sb.append("connected\n");
                        successCount++;
                    } catch (InterruptedException e) {
                        DtUtil.restoreInterruptStatus();
                        sb.append("interrupted\n");
                        failCount++;
                    }  catch (ExecutionException e) {
                        sb.append("connect fail: ").append(e).append('\n');
                        failCount++;
                    } catch (TimeoutException e) {
                        sb.append("timeout\n");
                        timeoutCount++;
                    }
                } else {
                    sb.append("timeout\n");
                    timeoutCount++;
                }
            } else {
                try {
                    f.getNow(null);
                    sb.append("connected\n");
                    successCount++;
                } catch (CompletionException e) {
                    sb.append("connect fail: ").append(e).append('\n');
                    failCount++;
                }
            }
        }

        if (successCount == 0 && peers.size() > 0) {
            log.error("[{}] start fail: timeoutPeerCount={},failPeerCount={}\n{}",
                    config.getName(), timeoutCount, failCount, sb);
            throw new NetException("init NioClient fail:timeout=" + config.getWaitStartTimeout()
                    + "ms, timeoutConnectionCount=" + timeoutCount + ", failConnectionCount=" + failCount);
        } else {
            log.info("[{}] started: connectPeerCount={}, timeoutPeerCount={}, failPeerCount={}\n{}",
                    config.getName(), successCount, timeoutCount, failCount, sb);
        }
        this.startFutures = null;
    }

    public <T> CompletableFuture<ReadFrame<T>> sendRequest(WriteFrame request, Decoder<T> decoder, DtTime timeout) {
        return sendRequest(null, request, decoder, timeout);
    }

    @SuppressWarnings("unchecked")
    public <T> CompletableFuture<ReadFrame<T>> sendRequest(Peer peer, WriteFrame request, Decoder<T> decoder, DtTime timeout) {
        CompletableFuture<?> f = sendRequest(worker, peer, request, decoder, timeout);
        return (CompletableFuture<ReadFrame<T>>) f;
    }

    @Override
    protected void doStop() {
        DtTime timeout = new DtTime(config.getCloseTimeout(), TimeUnit.MILLISECONDS);
        worker.preStop();
        try {
            long rest = timeout.rest(TimeUnit.MILLISECONDS);
            if (rest > 0) {
                worker.getPreCloseFuture().get(rest, TimeUnit.MILLISECONDS);
                log.info("client {} pre-stop done", config.getName());
            } else {
                log.warn("client {} pre-stop timeout. {}ms", config.getName(), timeout.getTimeout(TimeUnit.MILLISECONDS));
            }
        } catch (InterruptedException e) {
            DtUtil.restoreInterruptStatus();
        } catch (TimeoutException e) {
            log.warn("client {} pre-stop timeout. {}ms", config.getName(), timeout.getTimeout(TimeUnit.MILLISECONDS));
        } catch (ExecutionException e) {
            BugLog.log(e);
        }
        worker.stop();
        try {
            long rest = timeout.rest(TimeUnit.MILLISECONDS);
            if (rest > 0) {
                worker.getThread().join(rest);
            }
        } catch (InterruptedException e) {
            DtUtil.restoreInterruptStatus();
        }
        shutdownBizExecutor(timeout);

        log.info("client {} stopped", config.getName());
    }

    @Override
    protected void forceStop() {
        log.warn("force stop begin");
        forceStopWorker(worker);
        shutdownBizExecutor(new DtTime());
        log.warn("force stop done");
    }

    public List<Peer> getPeers() {
        return Collections.unmodifiableList(peers);
    }

    public CompletableFuture<Peer> addPeer(HostPort hostPort) {
        Objects.requireNonNull(hostPort);
        Peer peer = new Peer(hostPort, this);
        CompletableFuture<Peer> f = new CompletableFuture<>();
        worker.doInIoThread(() -> {
            for (Peer p : peers) {
                if (p.getEndPoint().equals(hostPort)) {
                    f.complete(p);
                    return;
                }
            }
            peers.add(peer);
            f.complete(peer);
        }, f);
        return f;
    }

    public CompletableFuture<Void> removePeer(Peer peer) {
        checkOwner(peer);
        CompletableFuture<Void> f = new CompletableFuture<>();
        worker.doInIoThread(() -> {
            if (!peers.contains(peer)) {
                f.complete(null);
                return;
            }
            if (peer.getDtChannel() != null) {
                worker.close(peer.getDtChannel());
            }
            peers.remove(peer);
            f.complete(null);
        }, f);
        return f;
    }

    public CompletableFuture<Void> connect(Peer peer, DtTime deadline) {
        checkOwner(peer);
        return worker.connect(peer, deadline);
    }

    public CompletableFuture<Void> disconnect(Peer peer) {
        checkOwner(peer);
        return worker.disconnect(peer);
    }

    private void checkOwner(Peer peer) {
        if (peer.getOwner() != this) {
            throw new IllegalArgumentException("the peer is not owned by this client");
        }
    }

    public NioClientConfig getConfig() {
        return config;
    }
}