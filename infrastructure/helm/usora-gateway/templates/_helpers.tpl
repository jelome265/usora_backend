{{/*
Expand the name of the chart.
*/}}
{{- define "usora-gateway.name" -}}
{{- .Chart.Name | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Fully qualified app name — used to generate the Deployment/Service/etc. name.
*/}}
{{- define "usora-gateway.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{- define "usora-gateway.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels applied to every resource in this chart.

app.kubernetes.io/component=service matches the selector used by the
allow-inter-service / allow-kafka-egress / allow-redis-egress NetworkPolicies
in infrastructure/k8s/base/network-policies.yml. Without it, the
namespace's default-deny-all policy blocks this pod's egress to Redis and
Kafka entirely — both of which this service actually connects to (see
RedisConfig/KafkaConfig in config/mod.rs) — even though the app-level
config wires them up correctly. This was found while writing this chart
and is why it's called out explicitly here rather than left implicit.
*/}}
{{- define "usora-gateway.labels" -}}
helm.sh/chart: {{ include "usora-gateway.chart" . }}
{{ include "usora-gateway.selectorLabels" . }}
app.kubernetes.io/component: service
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels — used by Deployment.spec.selector, Service.spec.selector,
and PodDisruptionBudget.spec.selector. Must never change across releases of
the same install, or the Deployment selector becomes immutable-field-invalid
on upgrade.
*/}}
{{- define "usora-gateway.selectorLabels" -}}
app.kubernetes.io/name: {{ include "usora-gateway.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Name of the ServiceAccount to use.
*/}}
{{- define "usora-gateway.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "usora-gateway.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}
