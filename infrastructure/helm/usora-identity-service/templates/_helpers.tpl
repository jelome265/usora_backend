{{/*
Expand the name of the chart.
*/}}
{{- define "usora-identity-service.name" -}}
{{- .Chart.Name | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Fully qualified app name.
*/}}
{{- define "usora-identity-service.fullname" -}}
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

{{- define "usora-identity-service.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels. component=service — a standard Spring Boot orchestration
service, covered by the base allow-inter-service / allow-kafka-egress /
allow-redis-egress / allow-postgres-egress NetworkPolicies in
infrastructure/k8s/base/network-policies.yml (see usora-gateway's
_helpers.tpl for the fuller explanation of why this label matters).
*/}}
{{- define "usora-identity-service.labels" -}}
helm.sh/chart: {{ include "usora-identity-service.chart" . }}
{{ include "usora-identity-service.selectorLabels" . }}
app.kubernetes.io/component: service
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels.
*/}}
{{- define "usora-identity-service.selectorLabels" -}}
app.kubernetes.io/name: {{ include "usora-identity-service.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Name of the ServiceAccount to use.
*/}}
{{- define "usora-identity-service.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "usora-identity-service.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}
