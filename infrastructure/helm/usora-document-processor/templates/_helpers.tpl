{{/*
Expand the name of the chart.
*/}}
{{- define "usora-document-processor.name" -}}
{{- .Chart.Name | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Fully qualified app name — used to generate the Deployment/Service/etc. name.
*/}}
{{- define "usora-document-processor.fullname" -}}
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

{{- define "usora-document-processor.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels applied to every resource in this chart.

app.kubernetes.io/component=compute (not "service") — this pod tolerates
the "dedicated=compute" taint and is scheduled onto the compute node pool
(see affinity/tolerations/nodeSelector in values.yaml, all pre-existing),
so it should carry the label that reflects that, matching the
"service"/"compute" distinction the base NetworkPolicies
(infrastructure/k8s/base/network-policies.yml) already use for Kafka/
Postgres/Redis egress. IMPORTANT: unlike "service", "compute" is NOT
covered by the base allow-inter-service ingress rule (that rule's
podSelector only matches component=service) — so this chart ships its own
networkpolicy.yaml granting the equivalent ingress explicitly, rather than
mislabeling this pod as "service" just to piggyback on a rule that wasn't
written with it in mind. See networkpolicy.yaml for the reasoning; this
was found and fixed while writing this chart.
*/}}
{{- define "usora-document-processor.labels" -}}
helm.sh/chart: {{ include "usora-document-processor.chart" . }}
{{ include "usora-document-processor.selectorLabels" . }}
app.kubernetes.io/component: compute
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
{{- define "usora-document-processor.selectorLabels" -}}
app.kubernetes.io/name: {{ include "usora-document-processor.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Name of the ServiceAccount to use.
*/}}
{{- define "usora-document-processor.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "usora-document-processor.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}
