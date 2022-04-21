package com.example.grpcserver.hello;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.protobuf.ProtobufFactory;
import com.fasterxml.jackson.dataformat.protobuf.schema.ProtobufSchema;
import com.fasterxml.jackson.dataformat.protobuf.schema.ProtobufSchemaLoader;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ClientCalls;
import io.netty.buffer.PooledByteBufAllocator;
import org.reactivestreams.Publisher;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.NettyWriteResponseFilter;
import org.springframework.cloud.gateway.filter.OrderedGatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.ResolvableType;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Objects;
import java.util.function.Function;

import static io.grpc.netty.shaded.io.grpc.netty.NegotiationType.TLS;
import static org.springframework.cloud.gateway.support.GatewayToStringStyler.filterToStringCreator;

@Component
public class JSONToGRPCFilterFactory extends AbstractGatewayFilterFactory<JSONToGRPCFilterFactory.Config> {

    private final GRPCSSLContext sslContext;

    public JSONToGRPCFilterFactory(GRPCSSLContext sslContext) {
        super(Config.class);
        this.sslContext = sslContext;
    }

    @Override
    public GatewayFilter apply(JSONToGRPCFilterFactory.Config config) {
        GatewayFilter filter = new GatewayFilter() {
            @Override
            public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
                GRPCResponseDecorator modifiedResponse = new GRPCResponseDecorator(exchange, config);

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


    public static class Config {

        private String protoDescriptor;
        private String protoFile;
        private String service;
        private String method;

        public String getProtoDescriptor() {
            return protoDescriptor;
        }

        public void setProtoDescriptor(String protoDescriptor) {
            this.protoDescriptor = protoDescriptor;
        }

        public String getProtoFile() {
            return protoFile;
        }

        public void setProtoFile(String protoFile) {
            this.protoFile = protoFile;
        }

        public String getService() {
            return service;
        }

        public void setService(String service) {
            this.service = service;
        }

        public String getMethod() {
            return method;
        }

        public void setMethod(String method) {
            this.method = method;
        }
    }

    class GRPCResponseDecorator extends ServerHttpResponseDecorator {

        private final ServerWebExchange exchange;
        private final ObjectMapper objectMapper;
        private final ProtobufSchema schema;
        private final Descriptors.Descriptor descriptor;
        private final MethodDescriptor<DynamicMessage, DynamicMessage> methodDescriptor;

        public GRPCResponseDecorator(ServerWebExchange exchange, Config config) {
            super(exchange.getResponse());
            this.exchange = exchange;
            try {
                File descriptorFile = getFile(config.getProtoDescriptor());
                File protoFile = getFile(config.getProtoFile());
                schema = ProtobufSchemaLoader.std.load(protoFile);

                objectMapper = new ObjectMapper(new ProtobufFactory());
                objectMapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);

                InputStream targetStream = new FileInputStream(descriptorFile);
                descriptor = DescriptorProtos.FileDescriptorProto.parseFrom(targetStream)
                        .getDescriptorForType();

                MethodDescriptor.Marshaller<DynamicMessage> marshaller =
                        ProtoUtils.marshaller(DynamicMessage.newBuilder(descriptor).buildPartial());
                methodDescriptor = MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                        .setType(MethodDescriptor.MethodType.UNKNOWN)
                        .setFullMethodName(MethodDescriptor.generateFullMethodName(config.getService(), config.getMethod()))
                        .setRequestMarshaller(marshaller)
                        .setResponseMarshaller(marshaller)
                        .build();

            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
            exchange.getResponse().getHeaders().set("Content-Type", "application/json");

            URI requestURI = ((Route) exchange.getAttributes().get(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR)).getUri();

            ManagedChannel channel = createChannelChannel(requestURI.getHost(), requestURI.getPort());
            return getDelegate().writeWith(
                    deserializeJSONRequest()
                            .map(callGRPCServerWithChannel(channel))
                            .map(serialiseGRPCResponse())
                            .map(wrapGRPCResponse())
                            .cast(DataBuffer.class)
                            .last());
        }

        private Function<JsonNode, DynamicMessage> callGRPCServerWithChannel(ManagedChannel channel) {
            return jsonRequest -> {
                try {
                    byte[] request = objectMapper.writer(schema).writeValueAsBytes(jsonRequest);

                    ClientCall<DynamicMessage, DynamicMessage> clientCall = channel.newCall(methodDescriptor, CallOptions.DEFAULT);
                    return ClientCalls.blockingUnaryCall(clientCall, DynamicMessage.parseFrom(descriptor, request));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            };
        }

        private Function<DynamicMessage, Object> serialiseGRPCResponse() {
            return gRPCResponse -> {
                try {
                    return objectMapper.readerFor(JsonNode.class)
                            .with(schema)
                            .readValue(gRPCResponse.toByteArray());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            };
        }

        private Flux<JsonNode> deserializeJSONRequest() {
            return exchange.getRequest()
                    .getBody()
                    .mapNotNull(dataBufferBody -> {
                        ResolvableType targetType = ResolvableType.forType(JsonNode.class);
                        return new Jackson2JsonDecoder()
                                .decode(dataBufferBody, targetType, null, null);
                    })
                    .cast(JsonNode.class);
        }

        private Function<Object, DataBuffer> wrapGRPCResponse() {
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

        private File getFile(String config) {
            return new File(config);
        }

        private ManagedChannel createChannelChannel(String host, int port) {
            return NettyChannelBuilder.forAddress(host, port)
                    .useTransportSecurity().sslContext(sslContext.getSslContext())
                    .negotiationType(TLS)
                    .build();
        }

    }
}
