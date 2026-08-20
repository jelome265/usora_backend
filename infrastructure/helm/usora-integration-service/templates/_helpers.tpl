{{/*
Expand the name of the chart.
*/}}
{{- define "usora-integration-service.name" -}}
{{- .Chart.Name | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Fully qualified app name.
*/}}
{{- define "usora-integration-service.fullname" -}}
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

{{- define "usora-integration-service.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels. component=service — a standard Spring Boot orchestration
service, covered by the base allow-inter-service / allow-kafka-egress /
allow-redis-egress / allow-postgres-egress NetworkPolicies in
infrastructure/k8s/base/network-policies.yml (see usora-gateway's
_helpers.tpl for the fuller explanation of why this label matters).
*/}}
{{- define "usora-integration-service.labels" -}}
helm.sh/chart: {{ include "usora-integration-service.chart" . }}
{{ include "usora-integration-service.selectorLabels" . }}
app.kubernetes.io/component: service
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels.
*/}}
{{- define "usora-integration-service.selectorLabels" -}}
app.kubernetes.io/name: {{ include "usora-integration-service.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Name of the ServiceAccount to use.
*/}}
{{- define "usora-integration-service.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "usora-integration-service.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}
