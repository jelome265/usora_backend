package com.usora.audit.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration
@ConfigurationProperties(prefix = "audit.tenant")
public class TenantConfig {

    private final Map<String, TenantProperties> tenants = new HashMap<>();

    public Map<String, TenantProperties> getTenants() {
        return tenants;
    }

    public TenantProperties getTenant(String tenantId) {
        return tenants.getOrDefault(tenantId, TenantProperties.defaults());
    }

    public static class TenantProperties {
        private String hmacKey;
        private int hotRetentionDays = 90;
        private int coldRetentionYears = 7;
        private boolean blockchainAnchoring = true;
        private String blockchainChannelId = "auditchannel";
        private boolean siemStreaming = true;

        public String getHmacKey() { return hmacKey; }
        public void setHmacKey(String hmacKey) { this.hmacKey = hmacKey; }
        public int getHotRetentionDays() { return hotRetentionDays; }
        public void setHotRetentionDays(int hotRetentionDays) { this.hotRetentionDays = hotRetentionDays; }
        public int getColdRetentionYears() { return coldRetentionYears; }
        public void setColdRetentionYears(int coldRetentionYears) { this.coldRetentionYears = coldRetentionYears; }
        public boolean isBlockchainAnchoring() { return blockchainAnchoring; }
        public void setBlockchainAnchoring(boolean blockchainAnchoring) { this.blockchainAnchoring = blockchainAnchoring; }
        public String getBlockchainChannelId() { return blockchainChannelId; }
        public void setBlockchainChannelId(String blockchainChannelId) { this.blockchainChannelId = blockchainChannelId; }
        public boolean isSiemStreaming() { return siemStreaming; }
        public void setSiemStreaming(boolean siemStreaming) { this.siemStreaming = siemStreaming; }

        public static TenantProperties defaults() {
            TenantProperties defaults = new TenantProperties();
            defaults.hmacKey = "default-hmac-key-change-in-production";
            defaults.hotRetentionDays = 90;
            defaults.coldRetentionYears = 7;
            defaults.blockchainAnchoring = true;
            defaults.blockchainChannelId = "auditchannel";
            defaults.siemStreaming = true;
            return defaults;
        }
    }
}
