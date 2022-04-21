package com.example.grpcserver.hello;

import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;


@Configuration
public class GRPCLocalConfiguration {

    @Bean
    public GRPCSSLContext sslContext() throws SSLException {
        TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }

                    public void checkClientTrusted(
                            X509Certificate[] certs,
                            String authType) {
                    }

                    public void checkServerTrusted(
                            X509Certificate[] certs,
                            String authType) {
                    }
                }};

        return new GRPCSSLContext(
                GrpcSslContexts.forClient().trustManager(trustAllCerts[0])
                        .build());
    }

}
