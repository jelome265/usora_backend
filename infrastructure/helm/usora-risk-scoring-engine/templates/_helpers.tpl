{{/*
Expand the name of the chart.
*/}}
{{- define "usora-risk-scoring-engine.name" -}}
{{- .Chart.Name | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "usora-risk-scoring-engine.fullname" -}}
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

{{- define "usora-risk-scoring-engine.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
component=compute — CPU/GPU-bound ML inference workload, scheduled on the
dedicated compute node pool (see nodeSelector/tolerations in values.yaml).
Like usora-document-processor, this needs its own NetworkPolicy since the
base allow-inter-service rule only covers component=service.
*/}}
{{- define "usora-risk-scoring-engine.labels" -}}
helm.sh/chart: {{ include "usora-risk-scoring-engine.chart" . }}
{{ include "usora-risk-scoring-engine.selectorLabels" . }}
app.kubernetes.io/component: compute
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{- define "usora-risk-scoring-engine.selectorLabels" -}}
app.kubernetes.io/name: {{ include "usora-risk-scoring-engine.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{- define "usora-risk-scoring-engine.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "usora-risk-scoring-engine.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}
