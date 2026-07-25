package com.usora.identity.config;

import io.grpc.ServerInterceptor;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GrpcConfig {

    @Bean
    @GrpcGlobalServerInterceptor
    public ServerInterceptor tenantGrpcInterceptor() {
        return (call, headers, next) -> {
            var tenantId = headers.get(io.grpc.Metadata.Key.of("X-Tenant-Id",
                    io.grpc.Metadata.ASCII_STRING_MARSHALLER));
            if (tenantId != null) {
                var ctx = com.usora.identity.security.TenantContext.getContext();
                ctx.setTenantId(tenantId);
            }
            try {
                return next.startCall(call, headers);
            } finally {
                com.usora.identity.security.TenantContext.clear();
            }
        };
    }
}
