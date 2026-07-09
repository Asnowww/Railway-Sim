package com.railwaysim.localnet;

public interface LocalNetAdapter {

    String id();

    ProtocolFamily family();

    boolean configured();

    boolean enabled();

    boolean running();

    void start();

    void stop();

    LocalNetHealth health();

    LocalNetReplayResult replay(byte[] payload);
}
