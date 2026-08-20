{{/*
Expand the name of the chart.
*/}}
{{- define "usora-core.name" -}}
{{- .Chart.Name | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Fully qualified app name — used to generate the Deployment/Service/etc. name.
*/}}
{{- define "usora-core.fullname" -}}
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

{{- define "usora-core.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels applied to every resource in this chart.

app.kubernetes.io/component=service matches the selector used by the
allow-inter-service / allow-kafka-egress / allow-redis-egress /
allow-postgres-egress NetworkPolicies in
infrastructure/k8s/base/network-policies.yml — see the same note in the
usora-gateway chart's _helpers.tpl for why this must be present, not
implicit.
*/}}
{{- define "usora-core.labels" -}}
helm.sh/chart: {{ include "usora-core.chart" . }}
{{ include "usora-core.selectorLabels" . }}
app.kubernetes.io/component: service
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels — used by Deployment.spec.selector, Service.spec.selector,
and PodDisruptionBudget.spec.selector. Must never change across releases of
the same install.
*/}}
{{- define "usora-core.selectorLabels" -}}
app.kubernetes.io/name: {{ include "usora-core.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Name of the ServiceAccount to use.
*/}}
{{- define "usora-core.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "usora-core.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}
