package com.example.grpcserver.hello;

import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManagerFactory;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;

@Configuration
public class GRPCSSLContextConfiguration {

    @Bean
    @ConditionalOnMissingBean(GRPCSSLContext.class)
    public GRPCSSLContext sslContext() throws SSLException, KeyStoreException, NoSuchAlgorithmException {
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(KeyStore.getInstance(KeyStore.getDefaultType()));

        return new GRPCSSLContext(GrpcSslContexts.forClient()
                .trustManager(trustManagerFactory)
                .build());
    }

}
