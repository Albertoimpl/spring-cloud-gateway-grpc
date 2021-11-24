package com.example.grpcserver.hello;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.netty.buffer.PooledByteBufAllocator;
import org.reactivestreams.Publisher;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.NettyWriteResponseFilter;
import org.springframework.cloud.gateway.filter.OrderedGatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.core.ResolvableType;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.Serializable;
import java.net.URI;
import java.security.cert.X509Certificate;
import java.util.Objects;
import java.util.function.Function;

import static io.grpc.netty.shaded.io.grpc.netty.NegotiationType.TLS;
import static org.springframework.cloud.gateway.support.GatewayToStringStyler.filterToStringCreator;

@Component
public class JSONToGRPCFilterFactory extends AbstractGatewayFilterFactory<Object> {

    @Override
    public GatewayFilter apply(Object config) {
        GatewayFilter filter = new GatewayFilter() {
            @Override
            public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
                GRPCResponseDecorator modifiedResponse = new GRPCResponseDecorator(exchange);

                return modifiedResponse
                        .writeWith(exchange.getRequest().getBody())
                        .then(chain.filter(exchange.mutate()
                                .response(modifiedResponse).build()));
            }

            @Override
            public String toString() {
                return filterToStringCreator(
                        JSONToGRPCFilterFactory.this)
                        .toString();
            }
        };

        int order = NettyWriteResponseFilter.WRITE_RESPONSE_FILTER_ORDER - 1;
        return new OrderedGatewayFilter(filter, order);
    }

    @Override
    public String name() {
        return "JSONToGRPCFilter";
    }

    static class GRPCResponseDecorator extends ServerHttpResponseDecorator {

        private final ServerWebExchange exchange;

        public GRPCResponseDecorator(ServerWebExchange exchange) {
            super(exchange.getResponse());
            this.exchange = exchange;
        }

        @Override
        public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
            exchange.getResponse().getHeaders().set("Content-Type", "application/json");

            URI requestURI = exchange.getRequest().getURI();
            ManagedChannel channel = createSecuredChannel(requestURI.getHost(), 6565);

            return getDelegate().writeWith(deserializeJSONRequest()
                    .map(jsonRequest -> {
                        String firstName = jsonRequest.getFirstName();
                        String lastName = jsonRequest.getLastName();
                        return HelloServiceGrpc.newBlockingStub(channel)
                                .hello(HelloRequest.newBuilder()
                                        .setFirstName(firstName)
                                        .setLastName(lastName)
                                        .build());
                    })
                    .map(this::serialiseJSONResponse)
                    .map(wrapGRPCResponse())
                    .cast(DataBuffer.class)
                    .last());
        }

        private Flux<PersonHello> deserializeJSONRequest() {
            return exchange.getRequest()
                    .getBody()
                    .mapNotNull(dataBufferBody -> {
                        ResolvableType targetType = ResolvableType.forType(PersonHello.class);
                        return new Jackson2JsonDecoder()
                                .decode(dataBufferBody, targetType, null, null);
                    })
                    .cast(PersonHello.class);
        }

        private GreetingHello serialiseJSONResponse(HelloResponse gRPCResponse) {
            return new GreetingHello(gRPCResponse.getGreeting());
        }

        private Function<GreetingHello, DataBuffer> wrapGRPCResponse() {
            return jsonResponse -> {
                try {
                    return new NettyDataBufferFactory(new PooledByteBufAllocator())
                            .wrap(Objects.requireNonNull(new ObjectMapper()
                                    .writeValueAsBytes(jsonResponse)));
                } catch (JsonProcessingException e) {
                    return new NettyDataBufferFactory(new PooledByteBufAllocator())
                            .allocateBuffer();
                }
            };
        }

        private ManagedChannel createSecuredChannel(String host, int port) {
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

            try {
                return NettyChannelBuilder.forAddress(host, port)
                        .useTransportSecurity().sslContext(
                                GrpcSslContexts.forClient().trustManager(trustAllCerts[0])
                                        .build()).negotiationType(TLS).build();
            } catch (SSLException e) {
                e.printStackTrace();
            }
            throw new RuntimeException();
        }

    }

    static class PersonHello {

        private String firstName;
        private String lastName;

        public PersonHello() {
        }

        public PersonHello(String firstName, String lastName) {
            this.firstName = firstName;
            this.lastName = lastName;
        }

        public String getFirstName() {
            return firstName;
        }

        public void setFirstName(String firstName) {
            this.firstName = firstName;
        }

        public String getLastName() {
            return lastName;
        }

        public void setLastName(String lastName) {
            this.lastName = lastName;
        }
    }

    static class GreetingHello implements Serializable {

        private static final long serialVersionUID = 1L;

        private String greeting;

        public GreetingHello() {
        }

        public GreetingHello(String greeting) {
            this.greeting = greeting;
        }

        public String getGreeting() {
            return greeting;
        }

        public void setGreeting(String greeting) {
            this.greeting = greeting;
        }
    }

}
