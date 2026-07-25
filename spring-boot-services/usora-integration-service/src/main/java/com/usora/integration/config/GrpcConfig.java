package com.usora.integration.config;

import io.grpc.*;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class GrpcConfig {

    @Bean
    public ServerInterceptor grpcTenantInterceptor() {
        return new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                    ServerCall<ReqT, RespT> call,
                    Metadata headers,
                    ServerCallHandler<ReqT, RespT> next) {
                String tenantId = headers.get(Metadata.Key.of("X-Tenant-Id", Metadata.ASCII_STRING_MARSHALLER));
                if (tenantId != null) {
                    Context context = Context.current().withValue(
                            Context.key("tenantId"), tenantId);
                    return Contexts.interceptCall(context, call, headers, next);
                }
                return next.startCall(call, headers);
            }
        };
    }

    @Bean
    public ServerInterceptor grpcTimeoutInterceptor() {
        return new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                    ServerCall<ReqT, RespT> call,
                    Metadata headers,
                    ServerCallHandler<ReqT, RespT> next) {
                call.setCompression("gzip");
                return next.startCall(call, headers);
            }
        };
    }

    @GrpcGlobalServerInterceptor
    ServerInterceptor[] globalInterceptors() {
        return new ServerInterceptor[]{grpcTenantInterceptor(), grpcTimeoutInterceptor()};
    }

    @Bean
    public ManagedChannel bankingChannel() {
        return ManagedChannelBuilder.forAddress("banking-service", 9090)
                .usePlaintext()
                .keepAliveTime(30, TimeUnit.SECONDS)
                .keepAliveTimeout(10, TimeUnit.SECONDS)
                .maxRetryAttempts(3)
                .build();
    }

    @Bean
    public ManagedChannel governmentChannel() {
        return ManagedChannelBuilder.forAddress("government-service", 9091)
                .usePlaintext()
                .keepAliveTime(30, TimeUnit.SECONDS)
                .keepAliveTimeout(10, TimeUnit.SECONDS)
                .maxRetryAttempts(3)
                .build();
    }

    @Bean
    public ManagedChannel creditChannel() {
        return ManagedChannelBuilder.forAddress("credit-service", 9092)
                .usePlaintext()
                .keepAliveTime(30, TimeUnit.SECONDS)
                .keepAliveTimeout(10, TimeUnit.SECONDS)
                .maxRetryAttempts(3)
                .build();
    }
}
