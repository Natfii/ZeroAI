# eval_script — Agent Tool Reference

Technical reference for the `eval_script` tool available to the ZeroAI agent
during conversations. This document is intended for developers and for the
agent's own context when deciding how and when to use scripting.

---

## Overview

`eval_script` lets the agent write and execute inline Rhai scripts during a
conversation. It's designed for batch computation, multi-tool composition,
and data processing — tasks where a single tool call would be insufficient
but a shell command would be overkill or unsafe.

**When to use eval_script:**
- Batch data processing (parse JSON, filter arrays, compute aggregates)
- Composing multiple host operations in a single call (read memory → process → write storage)
- Iterative logic that would take many round-trips as individual tool calls
- JSON manipulation and transformation
- Any non-OS task where you'd otherwise reach for a shell

**When NOT to use eval_script:**
- OS-level operations (use shell tools instead)
- Simple single-operation queries (just call the host function directly)
- Anything requiring network access beyond the host functions

---

## Tool Schema

```json
{
  "name": "eval_script",
  "description": "Execute a Rhai script in a sandboxed environment...",
  "parameters": {
    "type": "object",
    "required": ["code"],
    "properties": {
      "code": {
        "type": "string",
        "description": "Rhai source code to execute"
      }
    }
  }
}
```

The tool returns the string representation of the script's last expression.

---

## Fixed Capability Set

The agent's eval_script gets a pre-approved set of capabilities — no user
approval dialog is needed for these:

| Capability | Functions Unlocked |
|------------|-------------------|
| `storage.read` | `storage_read(key)` |
| `storage.write` | `storage_write(key, value)`, `storage_delete(key)` |
| `memory.read` | `memories(limit)`, `memories_by_category(cat, limit)`, `memory_recall(query, limit)`, `memory_count()` |
| `memory.write` | `memory_forget(key)` |
| `tools.read` | `tools()` |
| `tools.call` | `tool_call(name, args_json)` |
| `cost.read` | `cost()`, `cost_daily()`, `cost_monthly()`, `budget(est)` |
| `events.read` | `events(limit)` |
| `config.validate` | `validate_config(toml)` |
| `model.chat` *(conditional)* | `send(message)` — only when Nano is available on-device |

### What eval_script CANNOT do

These capabilities are never granted to eval_script:

- `model.read` — no model discovery
- `model.vision` — Nano is text-only
- `channel.read` / `channel.write` — no channel access
- `provider.write` — no provider hot-swapping
- `agent.control` — no e-stop
- `cron.read` / `cron.write` — no scheduling
- `skills.read` / `skills.write` — no skill management
- `auth.read` / `auth.write` — no credential access
- `trace.read` — no trace queries
- Calling `eval_script` itself (denylist prevents recursion)

---

## Nano Routing

When `model.chat` is available, `send(message)` routes **exclusively to
on-device Gemini Nano** — never to a cloud provider. This prevents:

- Unexpected cloud API costs from agent-generated scripts
- Scripts making unbounded LLM calls against paid APIs

If Nano is unavailable (non-Pixel device, ML Kit not ready), `send()` returns
a `CapabilityDenied` error. The agent should handle this gracefully.

**Nano availability** is checked once at the start of each eval_script
invocation via an `AtomicBool` (Acquire/Release ordering). Mid-execution
availability changes are not reflected.

---

## Execution Limits

| Limit | Value |
|-------|-------|
| Operation budget | 10,000,000 (10M) |
| Wall-clock timeout | 30 seconds |
| Max source size | 128 KiB |
| Max output size | 16 KiB (truncated with `[truncated, N bytes total]`) |
| Max string size | 64 KiB |
| Max array size | 1,024 elements |
| Max map size | 256 entries |
| Max call depth | 16 levels |
| Max expression depth | 32 levels |

The 10M operation budget is 100x the default for user scripts, accommodating
the agent's tendency to write more complex batch logic.

---

## Error Categories

Scripts can fail with these error prefixes:

| Prefix | Cause |
|--------|-------|
| `SyntaxError:` | Rhai compilation error (bad syntax) |
| `CapabilityDenied:` | Script tried to use a function it doesn't have access to |
| `Timeout:` | Exceeded 30s wall-clock limit |
| `OperationLimit:` | Exceeded 10M operations |
| `RuntimeError:` | Any other runtime failure (division by zero, type mismatch, host error) |

---

## Execution Flow

1. Agent decides to call `eval_script` with Rhai code
2. Approval check via autonomy config (Full autonomy = auto-approved)
3. `EvalScriptTool::execute()` validates code (rejects empty or >128 KiB)
4. Snapshots Nano availability (`AtomicBool` Acquire load)
5. Builds fixed capability set via `build_agent_capabilities(nano_available)`
6. Creates `AgentScriptHost` (routes `send()` to Nano, denies `send_vision()`)
7. Spawns blocking task (`tokio::task::spawn_blocking`) to avoid async deadlock
8. Rhai engine evaluates the script with capability enforcement per host call
9. Returns `ToolResult { success, output, error }`

---

## Security Model

### Capability Enforcement

Every host function call goes through `require_capability()`. If the script's
manifest doesn't include the required capability, the call fails immediately
with `CapabilityDenied`. There is no way to escalate privileges mid-execution.

### No User Approval for Agent Scripts

Unlike user-authored skill scripts, eval_script's fixed capabilities skip the
Android approval notification flow. The rationale: the agent's capability set
is curated and doesn't include the most dangerous operations (cron.write,
auth.write). The `tools.call` capability is included because the agent already
has access to tools through the normal tool-calling flow.

### Isolation from User Scripts

Agent eval_script runs with `AgentScriptHost`, a separate host implementation
from `ReplScriptHost` (used for user/REPL scripts). Key differences:

- `send()` routes to Nano (agent) vs. cloud provider (REPL)
- No dangerous capability approval gate (agent) vs. notification flow (user)
- Storage is scoped to a synthetic script name, not shared with user scripts

---

## Available Host Functions

### Storage (script-scoped)

```rhai
storage_read("key")                   // → String ("" if not found)
storage_write("key", "value")         // → "ok"
storage_delete("key")                 // → true/false
```

### Memory

```rhai
memories(10)                          // Last N as JSON
memories_by_category("facts", 5)      // By category
memory_recall("query", 5)            // Semantic search
memory_count()                        // → i64
memory_forget("key")                  // → bool
```

### Tools

```rhai
tools()                               // Available tools as JSON
tool_call("file_read", `{"path": "/tmp/data.txt"}`)
```

### Cost

```rhai
cost()                                // Full summary JSON
cost_daily()                          // → f64
cost_monthly()                        // → f64
budget(0.50)                          // Budget check
```

### Events

```rhai
events(20)                            // Recent events as JSON
```

### Config

```rhai
validate_config(toml_string)          // Validation result
```

### LLM (Nano only, when available)

```rhai
send("Summarize this text: ...")      // → String response from Nano
```

---

## Patterns & Examples

### Batch Memory Search + Analysis

```rhai
let results = memory_recall("project deadlines", 20);
let count = memory_count();

// Parse and filter (results is a JSON string)
let summary = send(`Given these ${count} total memories, summarize the
deadlines found in: ${results}`);

storage_write("deadline_analysis", summary);
summary
```

### Multi-Tool Composition

```rhai
let file = tool_call("file_read", `{"path": "workspace/config.toml"}`);
let valid = validate_config(file);

if valid.contains("error") {
    `Config has errors: ${valid}`
} else {
    let costs = cost();
    `Config valid. Current costs: ${costs}`
}
```

### Data Processing Loop

```rhai
let data = storage_read("raw_data");
let lines = data.split("\n");
let results = [];

for line in lines {
    if line.contains("ERROR") {
        results.push(line.trim());
    }
}

let output = results.len.to_string() + " errors found:\n";
for (err, i) in results {
    output += `${i + 1}. ${err}\n`;
}
output
```

### Conditional Nano Usage

```rhai
// Gracefully handle Nano unavailability
let analysis = try {
    send("Classify this as urgent or routine: " + storage_read("latest_event"))
} catch {
    "unknown"  // Nano unavailable, fall back to simple default
};

storage_write("classification", analysis);
analysis
```

---

## Registration Sites (for developers)

The eval_script tool is registered at five locations:

1. **Session registry** (`session.rs`) — `Box::new(EvalScriptTool::new())`
2. **Tool descriptions in agent loop** (`loop_.rs`) — two copies: verbose system prompt description and compact inline description
3. **Global extra tools factory** (`runtime.rs`) — registered via `set_global_extra_tools_factory()` during daemon init
4. **FFI public exports** (`lib.rs`) — `eval_script()` and `eval_script_with_capabilities()` for direct Kotlin/REPL invocation
5. **Module declaration** (`lib.rs`) — `mod eval_script_tool;`

All five must be updated atomically when changing the tool.
