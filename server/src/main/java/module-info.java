module dongting.server {
    requires transitive dongting.client;
    requires transitive dongting.client.java11;

    exports com.github.dtprj.dongting.raft.server;
    exports com.github.dtprj.dongting.raft.store;
    exports com.github.dtprj.dongting.raft.sm;
    exports com.github.dtprj.dongting.dtkv;
}