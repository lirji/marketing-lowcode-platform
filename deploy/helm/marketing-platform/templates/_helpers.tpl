{{- define "marketing.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "marketing.fullname" -}}
{{- printf "%s-%s" .Release.Name (include "marketing.name" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "marketing.labels" -}}
app.kubernetes.io/part-of: marketing-platform
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | quote }}
{{- end -}}

{{- define "marketing.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "marketing.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- required "serviceAccount.name is required when serviceAccount.create=false" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{- define "marketing.image" -}}
{{- printf "%s/%s:%s" $.root.Values.global.imageRegistry $.name $.root.Values.global.imageTag -}}
{{- end -}}
