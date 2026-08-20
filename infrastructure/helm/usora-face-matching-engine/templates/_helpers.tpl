{{/*
Expand the name of the chart.
*/}}
{{- define "usora-face-matching-engine.name" -}}
{{- .Chart.Name | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "usora-face-matching-engine.fullname" -}}
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

{{- define "usora-face-matching-engine.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
component=compute — biometric matching is a CPU/GPU-bound ML inference
workload on the dedicated compute node pool. Needs its own NetworkPolicy,
same reasoning as usora-document-processor and
usora-risk-scoring-engine.
*/}}
{{- define "usora-face-matching-engine.labels" -}}
helm.sh/chart: {{ include "usora-face-matching-engine.chart" . }}
{{ include "usora-face-matching-engine.selectorLabels" . }}
app.kubernetes.io/component: compute
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{- define "usora-face-matching-engine.selectorLabels" -}}
app.kubernetes.io/name: {{ include "usora-face-matching-engine.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{- define "usora-face-matching-engine.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "usora-face-matching-engine.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}
