<script setup lang="ts">
import { onMounted, ref } from 'vue'
import {
  gateway,
  type AuditEntry,
  type GatewayRule,
  type GatewayStats,
  type RedactionEntry,
  type RuleUpsert,
} from '@/api/gateway'

const stats = ref<GatewayStats | null>(null)
const rules = ref<GatewayRule[]>([])
const audit = ref<AuditEntry[]>([])
const redactions = ref<RedactionEntry[]>([])
const loadError = ref<string | null>(null)

const editing = ref<Partial<GatewayRule> | null>(null)
const editingForm = ref<RuleUpsert>({
  name: '',
  pattern: '',
  category: 'CUSTOM',
  placeholder: 'REDACTED_CUSTOM',
  enabled: true,
})

const testInput = ref('')
const testModel = ref('openai/gpt-4o-mini')
const testConvId = ref('admin-test-conv')
const testResult = ref<unknown>(null)

const inspecting = ref<AuditEntry | null>(null)

function prettyJson(s: string | null): string {
  if (!s) return '(empty)'
  try {
    return JSON.stringify(JSON.parse(s), null, 2)
  } catch {
    return s
  }
}

async function loadAll() {
  loadError.value = null
  try {
    const [s, r, a, rd] = await Promise.all([
      gateway.stats(),
      gateway.listRules(),
      gateway.audit(50),
      gateway.redactions(50),
    ])
    stats.value = s
    rules.value = r
    audit.value = a
    redactions.value = rd
  } catch (e) {
    loadError.value = (e as Error).message ?? 'Failed to load'
  }
}

function startEdit(rule?: GatewayRule) {
  editing.value = rule ?? {}
  editingForm.value = rule
    ? { name: rule.name, pattern: rule.pattern, category: rule.category, placeholder: rule.placeholder, enabled: rule.enabled }
    : { name: '', pattern: '', category: 'CUSTOM', placeholder: 'REDACTED_CUSTOM', enabled: true }
}

async function saveRule() {
  if (!editing.value) return
  try {
    if (editing.value.id != null) {
      await gateway.updateRule(editing.value.id, editingForm.value)
    } else {
      await gateway.createRule(editingForm.value)
    }
    editing.value = null
    await loadAll()
  } catch (e) {
    alert(`Save failed: ${(e as Error).message}`)
  }
}

async function deleteRule(rule: GatewayRule) {
  if (!confirm(`Delete rule "${rule.name}"?`)) return
  try {
    await gateway.deleteRule(rule.id)
    await loadAll()
  } catch (e) {
    alert(`Delete failed (rule may be builtin): ${(e as Error).message}`)
  }
}

async function runTest() {
  testResult.value = null
  testResult.value = await gateway.testProxy(testModel.value, testInput.value, testConvId.value)
}

function fmtTs(ts: number): string {
  return new Date(ts).toLocaleString()
}

onMounted(loadAll)
</script>

<template>
  <div class="page">
    <header class="bar">
      <h1>LLM Gateway — Admin</h1>
      <div class="bar-actions">
        <router-link to="/" class="link">← Chat</router-link>
        <button class="btn" @click="loadAll">Refresh</button>
      </div>
    </header>

    <p v-if="loadError" class="error">Load error: {{ loadError }}</p>

    <section class="card">
      <h2>Stats</h2>
      <div v-if="stats" class="stats-grid">
        <div><b>Active conversations:</b> {{ stats.activeConversations }}</div>
        <div><b>Rate limit:</b> {{ stats.rateLimitRpm }}/min</div>
        <div><b>Total tokens:</b> {{ stats.totalTokens.toLocaleString() }}</div>
        <div><b>Total cost (USD):</b> {{ stats.totalCostUsd.toFixed(6) }}</div>
        <div><b>Requests by status:</b> {{ JSON.stringify(stats.requests) }}</div>
        <div><b>Redactions by rule:</b> {{ JSON.stringify(stats.redactionsByRule) }}</div>
      </div>
    </section>

    <section class="card">
      <h2>Quick proxy test</h2>
      <div class="row">
        <input v-model="testModel" placeholder="model id" />
        <input v-model="testConvId" placeholder="conversation id" />
      </div>
      <textarea v-model="testInput" rows="3" placeholder="Type a message — try including sk-test-key123ABCD or 4111-1111-1111-1111" />
      <button class="btn primary" @click="runTest">Send via gateway</button>
      <pre v-if="testResult" class="result">{{ JSON.stringify(testResult, null, 2) }}</pre>
    </section>

    <section class="card">
      <h2>Regex rules</h2>
      <button class="btn" @click="startEdit()">+ New rule</button>
      <table class="rules">
        <thead>
          <tr><th>ID</th><th>Name</th><th>Category</th><th>Pattern</th><th>Placeholder</th><th>Enabled</th><th>Builtin</th><th>Actions</th></tr>
        </thead>
        <tbody>
          <tr v-for="r in rules" :key="r.id">
            <td>{{ r.id }}</td><td>{{ r.name }}</td><td>{{ r.category }}</td>
            <td><code>{{ r.pattern }}</code></td>
            <td><code>{{ r.placeholder }}</code></td>
            <td>{{ r.enabled ? '✓' : '✗' }}</td>
            <td>{{ r.builtin ? '✓' : '' }}</td>
            <td>
              <button class="btn small" @click="startEdit(r)">Edit</button>
              <button class="btn small danger" :disabled="r.builtin" @click="deleteRule(r)">Delete</button>
            </td>
          </tr>
        </tbody>
      </table>

      <div v-if="editing" class="modal">
        <div class="modal-body card">
          <h3>{{ editing.id ? 'Edit rule' : 'New rule' }}</h3>
          <label>Name <input v-model="editingForm.name" /></label>
          <label>Pattern <input v-model="editingForm.pattern" /></label>
          <label>Category
            <select v-model="editingForm.category">
              <option>API_KEY</option><option>PII</option><option>CUSTOM</option>
            </select>
          </label>
          <label>Placeholder <input v-model="editingForm.placeholder" /></label>
          <label><input type="checkbox" v-model="editingForm.enabled" /> Enabled</label>
          <div class="row">
            <button class="btn primary" @click="saveRule">Save</button>
            <button class="btn" @click="editing = null">Cancel</button>
          </div>
        </div>
      </div>
    </section>

    <section class="card">
      <h2>Recent redactions ({{ redactions.length }})</h2>
      <table>
        <thead><tr><th>ts</th><th>dir</th><th>conv</th><th>rule</th><th>placeholder</th><th>hash</th></tr></thead>
        <tbody>
          <tr v-for="rd in redactions" :key="rd.id">
            <td>{{ fmtTs(rd.ts) }}</td><td>{{ rd.direction }}</td>
            <td><code>{{ rd.conversationId?.slice(0, 8) }}</code></td>
            <td>{{ rd.ruleName }}</td>
            <td><code>{{ rd.placeholder }}</code></td>
            <td><code>{{ rd.originalHash }}</code></td>
          </tr>
        </tbody>
      </table>
    </section>

    <section class="card">
      <h2>Recent audit ({{ audit.length }})</h2>
      <table>
        <thead><tr><th>ts</th><th>status</th><th>model</th><th>ip</th><th>conv</th><th>lat</th><th>block</th><th></th></tr></thead>
        <tbody>
          <tr v-for="a in audit" :key="a.id">
            <td>{{ fmtTs(a.ts) }}</td>
            <td :class="`st-${a.status.toLowerCase()}`">{{ a.status }}</td>
            <td>{{ a.model }}</td><td>{{ a.clientIp }}</td>
            <td><code>{{ a.conversationId?.slice(0, 8) }}</code></td>
            <td>{{ a.latencyMs }}ms</td>
            <td>{{ a.blockReason ?? '' }}</td>
            <td>
              <button
                class="btn small"
                :disabled="!a.upstreamRequestJson && !a.upstreamResponseJson"
                @click="inspecting = a"
              >JSON</button>
            </td>
          </tr>
        </tbody>
      </table>
    </section>

    <div v-if="inspecting" class="modal" @click.self="inspecting = null">
      <div class="modal-body card json-modal">
        <div class="bar">
          <h3>Upstream JSON snapshot — conv <code>{{ inspecting.conversationId?.slice(0, 8) }}</code></h3>
          <button class="btn" @click="inspecting = null">Close</button>
        </div>
        <p class="hint">
          Тут видно, что РЕАЛЬНО улетело в LLM (после InputGuard + system-note про REDACTED)
          и что вернулось (с loggable-content, юзерские плейсхолдеры НЕ развёрнуты).
        </p>
        <div class="json-cols">
          <div class="json-col">
            <h4>→ Request к upstream</h4>
            <pre class="json-pre">{{ prettyJson(inspecting.upstreamRequestJson) }}</pre>
          </div>
          <div class="json-col">
            <h4>← Response от upstream</h4>
            <pre class="json-pre">{{ prettyJson(inspecting.upstreamResponseJson) }}</pre>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page { padding: 16px; max-width: 1280px; margin: 0 auto; display: flex; flex-direction: column; gap: 16px; }
.bar { display: flex; align-items: center; justify-content: space-between; padding-bottom: 8px; border-bottom: 1px solid var(--color-border); }
.bar-actions { display: flex; gap: 8px; align-items: center; }
.link { color: var(--color-accent); text-decoration: none; }
.card { background: var(--color-surface); border: 1px solid var(--color-border); border-radius: 8px; padding: 16px; }
h1, h2, h3 { margin-bottom: 12px; }
.stats-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 8px; }
.row { display: flex; gap: 8px; align-items: center; margin: 8px 0; }
input, textarea, select {
  padding: 6px 10px; border: 1px solid var(--color-border); background: var(--color-surface-alt);
  color: var(--color-text); border-radius: 4px; font-family: inherit; width: 100%;
}
textarea { resize: vertical; }
.btn {
  padding: 6px 12px; border: 1px solid var(--color-border); background: var(--color-surface-alt);
  color: var(--color-text); border-radius: 4px; cursor: pointer;
}
.btn.primary { background: var(--color-accent); color: var(--color-accent-text); border-color: var(--color-accent); }
.btn.small { padding: 2px 6px; font-size: 12px; }
.btn.danger { color: var(--color-error); }
.btn:disabled { opacity: 0.4; cursor: not-allowed; }
.error { color: var(--color-error); background: var(--color-error-bg); padding: 8px; border-radius: 4px; }
table { width: 100%; border-collapse: collapse; font-size: 13px; }
th, td { text-align: left; padding: 4px 6px; border-bottom: 1px solid var(--color-border); vertical-align: top; }
code { background: var(--color-surface-alt); padding: 1px 4px; border-radius: 2px; font-size: 11px; }
.result { background: var(--color-surface-alt); padding: 8px; border-radius: 4px; max-height: 300px; overflow: auto; font-size: 12px; }
.st-ok { color: green; } .st-blocked { color: var(--color-error); } .st-rate_limited { color: var(--color-warn); } .st-error { color: var(--color-error); }
.modal { position: fixed; inset: 0; background: rgba(0,0,0,0.5); display: flex; align-items: center; justify-content: center; z-index: 100; }
.modal-body { width: 480px; display: flex; flex-direction: column; gap: 8px; }
.json-modal { width: 90vw; max-width: 1400px; max-height: 90vh; }
.json-modal .bar { padding-bottom: 8px; border-bottom: 1px solid var(--color-border); }
.json-cols { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; overflow: auto; }
.json-col h4 { margin-bottom: 6px; font-size: 13px; }
.json-pre {
  background: var(--color-surface-alt); padding: 8px; border-radius: 4px;
  font-size: 11px; max-height: 70vh; overflow: auto; white-space: pre-wrap; word-break: break-word;
}
.hint { font-size: 12px; color: var(--color-text-muted, #888); margin-bottom: 4px; }
label { display: block; font-size: 13px; margin: 4px 0; }
</style>
