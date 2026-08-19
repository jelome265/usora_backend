{{/*
Expand the name of the chart.
*/}}
{{- define "usora-tenant-service.name" -}}
{{- .Chart.Name | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Fully qualified app name.
*/}}
{{- define "usora-tenant-service.fullname" -}}
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

{{- define "usora-tenant-service.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels. component=service — a standard Spring Boot orchestration
service, covered by the base allow-inter-service / allow-kafka-egress /
allow-redis-egress / allow-postgres-egress NetworkPolicies in
infrastructure/k8s/base/network-policies.yml (see usora-gateway's
_helpers.tpl for the fuller explanation of why this label matters).
*/}}
{{- define "usora-tenant-service.labels" -}}
helm.sh/chart: {{ include "usora-tenant-service.chart" . }}
{{ include "usora-tenant-service.selectorLabels" . }}
app.kubernetes.io/component: service
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels.
*/}}
{{- define "usora-tenant-service.selectorLabels" -}}
app.kubernetes.io/name: {{ include "usora-tenant-service.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Name of the ServiceAccount to use.
*/}}
{{- define "usora-tenant-service.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "usora-tenant-service.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}
