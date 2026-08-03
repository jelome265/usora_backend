package com.usora.tenant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
@ConfigurationProperties(prefix = "tenant")
public class TenantConfig {

    private Provisioning provisioning = new Provisioning();
    private Offboarding offboarding = new Offboarding();
    private Billing billing = new Billing();
    private Suspension suspension = new Suspension();

    public Provisioning getProvisioning() { return provisioning; }
    public void setProvisioning(Provisioning provisioning) { this.provisioning = provisioning; }
    public Offboarding getOffboarding() { return offboarding; }
    public void setOffboarding(Offboarding offboarding) { this.offboarding = offboarding; }
    public Billing getBilling() { return billing; }
    public void setBilling(Billing billing) { this.billing = billing; }
    public Suspension getSuspension() { return suspension; }
    public void setSuspension(Suspension suspension) { this.suspension = suspension; }

    public static class Provisioning {
        private boolean autoApprove = false;
        private String schemaPrefix = "tenant_";
        private String namespacePrefix = "tenant-";
        private String topicPrefix = "";
        private ResourceQuota defaultResourceQuota = new ResourceQuota();

        public boolean isAutoApprove() { return autoApprove; }
        public void setAutoApprove(boolean autoApprove) { this.autoApprove = autoApprove; }
        public String getSchemaPrefix() { return schemaPrefix; }
        public void setSchemaPrefix(String schemaPrefix) { this.schemaPrefix = schemaPrefix; }
        public String getNamespacePrefix() { return namespacePrefix; }
        public void setNamespacePrefix(String namespacePrefix) { this.namespacePrefix = namespacePrefix; }
        public String getTopicPrefix() { return topicPrefix; }
        public void setTopicPrefix(String topicPrefix) { this.topicPrefix = topicPrefix; }
        public ResourceQuota getDefaultResourceQuota() { return defaultResourceQuota; }
        public void setDefaultResourceQuota(ResourceQuota defaultResourceQuota) { this.defaultResourceQuota = defaultResourceQuota; }

        public static class ResourceQuota {
            private String cpu = "10";
            private String memory = "20Gi";
            private String storage = "100Gi";
            private String pods = "50";

            public String getCpu() { return cpu; }
            public void setCpu(String cpu) { this.cpu = cpu; }
            public String getMemory() { return memory; }
            public void setMemory(String memory) { this.memory = memory; }
            public String getStorage() { return storage; }
            public void setStorage(String storage) { this.storage = storage; }
            public String getPods() { return pods; }
            public void setPods(String pods) { this.pods = pods; }
        }
    }

    public static class Offboarding {
        private int gracePeriodDays = 30;
        private boolean purgeImmediately = false;
        private boolean gdprCompliance = true;
        private int auditRetentionYears = 7;

        public int getGracePeriodDays() { return gracePeriodDays; }
        public void setGracePeriodDays(int gracePeriodDays) { this.gracePeriodDays = gracePeriodDays; }
        public boolean isPurgeImmediately() { return purgeImmediately; }
        public void setPurgeImmediately(boolean purgeImmediately) { this.purgeImmediately = purgeImmediately; }
        public boolean isGdprCompliance() { return gdprCompliance; }
        public void setGdprCompliance(boolean gdprCompliance) { this.gdprCompliance = gdprCompliance; }
        public int getAuditRetentionYears() { return auditRetentionYears; }
        public void setAuditRetentionYears(int auditRetentionYears) { this.auditRetentionYears = auditRetentionYears; }
    }

    public static class Billing {
        private String provider = "stripe";
        private String webhookSecret = "${VAULT:stripe_webhook_secret}";
        private String usageReportingInterval = "1h";

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getWebhookSecret() { return webhookSecret; }
        public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }
        public String getUsageReportingInterval() { return usageReportingInterval; }
        public void setUsageReportingInterval(String usageReportingInterval) { this.usageReportingInterval = usageReportingInterval; }
    }

    public static class Suspension {
        private boolean autoSuspendOnPaymentFailure = true;
        private int autoSuspendAfterDays = 7;
        private String dataRetentionDuringSuspension = "90d";

        public boolean isAutoSuspendOnPaymentFailure() { return autoSuspendOnPaymentFailure; }
        public void setAutoSuspendOnPaymentFailure(boolean autoSuspendOnPaymentFailure) { this.autoSuspendOnPaymentFailure = autoSuspendOnPaymentFailure; }
        public int getAutoSuspendAfterDays() { return autoSuspendAfterDays; }
        public void setAutoSuspendAfterDays(int autoSuspendAfterDays) { this.autoSuspendAfterDays = autoSuspendAfterDays; }
        public String getDataRetentionDuringSuspension() { return dataRetentionDuringSuspension; }
        public void setDataRetentionDuringSuspension(String dataRetentionDuringSuspension) { this.dataRetentionDuringSuspension = dataRetentionDuringSuspension; }
    }
}
