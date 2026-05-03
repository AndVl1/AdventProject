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
}

export interface AuditEntry {
  id: number
  ts: number
  conversationId: string | null
  clientIp: string | null
  model: string | null
  requestText: string | null
  redactedText: string | null
  responseText: string | null
  status: string
  blockReason: string | null
  inputFindings: string | null
  outputFindings: string | null
  latencyMs: number | null
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
  audit(limit = 100): Promise<AuditEntry[]> {
    return gw.get<AuditEntry[]>(`/api/admin/audit?limit=${limit}`).then((r) => r.data)
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
