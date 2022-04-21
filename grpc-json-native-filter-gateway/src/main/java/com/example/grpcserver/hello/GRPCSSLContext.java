package com.example.grpcserver.hello;

import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;

public class GRPCSSLContext {

    private final SslContext sslContext;

    public GRPCSSLContext(SslContext sslContext) {
        this.sslContext = sslContext;
    }

    public SslContext getSslContext() {
        return sslContext;
    }
}

