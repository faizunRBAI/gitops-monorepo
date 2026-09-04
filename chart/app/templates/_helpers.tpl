{{- define "app.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "app.fullname" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "app.labels" -}}
app.kubernetes.io/name: {{ include "app.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end -}}

{{- define "app.selectorLabels" -}}
app.kubernetes.io/name: {{ include "app.name" . }}
{{- end -}}

{{/*
TRAP 5 (user spec §6.3): "the plain-Deployment guard needing to exclude canary".

Both modes are Rollouts. Rendering a Deployment alongside one means two
controllers own the same pods, and Argo reports permanent OutOfSync while the
two fight. This helper is the single source of truth for "is a Rollout in
play?" so no template can disagree with another.

It also refuses BOTH modes at once, which would otherwise render two Rollouts
with the same name and fail at apply time with a confusing duplicate error.
*/}}
{{- define "app.rolloutEnabled" -}}
{{- if and .Values.canary.enabled .Values.blueGreen.enabled -}}
{{- fail "canary.enabled and blueGreen.enabled are mutually exclusive — set at most one to true" -}}
{{- end -}}
{{- if or .Values.canary.enabled .Values.blueGreen.enabled -}}true{{- else -}}false{{- end -}}
{{- end -}}

{{- define "app.releaseMode" -}}
{{- if .Values.env.releaseMode -}}
{{- .Values.env.releaseMode -}}
{{- else if .Values.canary.enabled -}}canary
{{- else if .Values.blueGreen.enabled -}}bluegreen
{{- else -}}plain
{{- end -}}
{{- end -}}

{{/*
Fails the render rather than shipping a pod that will sit in ImagePullBackOff
with an opaque "placeholder" tag.
*/}}
{{- define "app.image" -}}
{{- if not .Values.image.repository -}}
{{- fail "image.repository is required — set it per-environment in the Application's Helm parameters" -}}
{{- end -}}
{{- printf "%s:%s" .Values.image.repository .Values.image.tag -}}
{{- end -}}

{{/*
Shared pod spec, so the Deployment and both Rollout modes cannot drift apart.
*/}}
{{- define "app.podSpec" -}}
securityContext:
  {{- toYaml .Values.podSecurityContext | nindent 2 }}
terminationGracePeriodSeconds: 30
containers:
  - name: app
    image: {{ include "app.image" . | quote }}
    imagePullPolicy: {{ .Values.image.pullPolicy }}
    securityContext:
      {{- toYaml .Values.securityContext | nindent 6 }}
    ports:
      - name: http
        containerPort: {{ .Values.service.targetPort }}
        protocol: TCP
    env:
      - name: APP_VERSION
        value: {{ .Values.image.tag | quote }}
      - name: RELEASE_MODE
        value: {{ include "app.releaseMode" . | quote }}
      - name: POD_NAME
        valueFrom:
          fieldRef:
            fieldPath: metadata.name
      - name: PORT
        value: {{ .Values.service.targetPort | quote }}
    readinessProbe:
      httpGet:
        path: /healthz
        port: http
      initialDelaySeconds: 3
      periodSeconds: 5
      failureThreshold: 3
    livenessProbe:
      httpGet:
        path: /healthz
        port: http
      initialDelaySeconds: 15
      periodSeconds: 20
      failureThreshold: 3
    resources:
      {{- toYaml .Values.resources | nindent 6 }}
{{- end -}}
