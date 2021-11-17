package com.example.grpcclient;

import com.example.grpcserver.hello.HelloRequest;
import com.example.grpcserver.hello.HelloResponse;
import com.example.grpcserver.hello.HelloServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;

import static io.grpc.netty.shaded.io.grpc.netty.NegotiationType.TLS;

@SpringBootApplication
public class GrpcClientApplication implements ApplicationRunner {

    public static void main(String[] args) {
        SpringApplication.run(GrpcClientApplication.class, args);
    }

    @Override
    public void run(ApplicationArguments args) throws SSLException {

        int gatewayPort = 8090;
        ManagedChannel channel = createSecuredChannel(gatewayPort);

        final HelloResponse response = HelloServiceGrpc.newBlockingStub(channel)
                .hello(HelloRequest.newBuilder().setFirstName("Saul")
                        .setLastName("Hudson").build());

        System.out.println(response.toString());
    }

    private ManagedChannel createSecuredChannel(int port) throws SSLException {
        TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }

                    public void checkClientTrusted(X509Certificate[] certs,
                                                   String authType) {
                    }

                    public void checkServerTrusted(X509Certificate[] certs,
                                                   String authType) {
                    }
                }};

        return NettyChannelBuilder.forAddress("localhost", port)
                .useTransportSecurity().sslContext(
                        GrpcSslContexts.forClient().trustManager(trustAllCerts[0])
                                .build()).negotiationType(TLS).build();
    }
}
