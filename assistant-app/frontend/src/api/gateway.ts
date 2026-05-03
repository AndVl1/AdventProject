import axios from 'axios'

export interface GatewayRule {
  id: number
  name: string
  pattern: string
  category: string
  placeholder: string
  enabled: boolean
  builtin: boolean
  createdAt: number
}

export interface RuleUpsert {
  name: string
  pattern: string
  category: string
  placeholder: string
  enabled: boolean
}

export interface GatewayStats {
  requests: Record<string, number>
  totalCostUsd: number
  totalTokens: number
  byModel: Array<Record<string, unknown>>
  redactionsByRule: Record<string, number>
  activeConversations: number
  rateLimitRpm: number
  byModelAudit?: Array<{
    model: string | null
    endpointType: string | null
    count: number
    avgLatencyMs: number | null
    totalTokens: number | null
    promptTokens: number | null
    completionTokens: number | null
  }>
}

export interface AuditEntry {
  id: number
  ts: number
  conversationId: string | null
  clientIp: string | null
  model: string | null
  requestText: string | null
  responseText: string | null
  status: string
  blockReason: string | null
  inputFindings: string | null
  outputFindings: string | null
  latencyMs: number | null
  upstreamRequestJson: string | null
  upstreamResponseJson: string | null
  endpointType: string | null
  routedUpstream: string | null
  promptTokens: number | null
  completionTokens: number | null
  totalTokens: number | null
}

export interface RouteConfig {
  pattern: string
  baseUrl: string
}

export interface GatewayRoutes {
  default: string
  routes: RouteConfig[]
  allowedHosts: string[]
}

export interface ConnectionExamples {
  claudeCodeEnv: string
  curlAnthropic: string
  openaiSdkPython: string
  openaiSdkNode: string
}

export interface ConnectionInfo {
  baseUrl: string
  source: string
  anthropicEndpoint: string
  openaiBaseUrl: string
  examples: ConnectionExamples
}

export interface RedactionEntry {
  id: number
  ts: number
  conversationId: string | null
  direction: string
  ruleName: string
  placeholder: string
  originalHash: string
}

const gw = axios.create({
  baseURL: '/gw',
  headers: { 'Content-Type': 'application/json' },
})

export const gateway = {
  stats(): Promise<GatewayStats> {
    return gw.get<GatewayStats>('/api/admin/stats').then((r) => r.data)
  },
  listRules(): Promise<GatewayRule[]> {
    return gw.get<GatewayRule[]>('/api/admin/rules').then((r) => r.data)
  },
  createRule(body: RuleUpsert): Promise<GatewayRule> {
    return gw.post<GatewayRule>('/api/admin/rules', body).then((r) => r.data)
  },
  updateRule(id: number, body: RuleUpsert): Promise<void> {
    return gw.put(`/api/admin/rules/${id}`, body).then(() => undefined)
  },
  deleteRule(id: number): Promise<void> {
    return gw.delete(`/api/admin/rules/${id}`).then(() => undefined)
  },
  routes(): Promise<GatewayRoutes> {
    return gw.get<GatewayRoutes>('/api/admin/routes').then((r) => r.data)
  },
  connectionInfo(): Promise<ConnectionInfo> {
    return gw.get<ConnectionInfo>('/api/admin/connection-info').then((r) => r.data)
  },
  audit(limit = 100, opts?: { endpointType?: string; model?: string }): Promise<AuditEntry[]> {
    const params = new URLSearchParams({ limit: String(limit) })
    if (opts?.endpointType) params.set('endpointType', opts.endpointType)
    if (opts?.model) params.set('model', opts.model)
    return gw.get<AuditEntry[]>(`/api/admin/audit?${params.toString()}`).then((r) => r.data)
  },
  redactions(limit = 100): Promise<RedactionEntry[]> {
    return gw.get<RedactionEntry[]>(`/api/admin/redactions?limit=${limit}`).then((r) => r.data)
  },
  testProxy(model: string, message: string, conversationId: string): Promise<unknown> {
    return gw
      .post(
        '/v1/chat/completions',
        { model, messages: [{ role: 'user', content: message }] },
        { headers: { 'X-Conversation-Id': conversationId } },
      )
      .then((r) => ({ status: r.status, headers: r.headers, body: r.data }))
      .catch((err) => ({ error: true, status: err.response?.status, body: err.response?.data }))
  },
}
