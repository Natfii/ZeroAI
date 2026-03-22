# Rhai Scripting Guide

ZeroAI embeds a [Rhai](https://rhai.rs) scripting engine that lets you automate tasks,
compose tools, and extend the agent with custom logic — all sandboxed and
capability-gated so scripts can't do anything you haven't approved.

---

## Quick Start

Create a `.rhai` file in your workspace's `workflows/` directory:

```
{data_dir}/workspace/workflows/hello.rhai
```

```rhai
// hello.rhai — your first script
let v = version();
let s = status();
`ZeroAI ${v} is running: ${s}`
```

The last expression is the script's return value. Run it from the Terminal tab
or let the agent invoke it via `eval_script`.

---

## Where Scripts Live

| Location | Purpose |
|----------|---------|
| `workspace/workflows/*.rhai` | Standalone automation scripts |
| `workspace/skills/{name}/SKILL.toml` | Skill manifest (declares metadata, capabilities, triggers) |
| `workspace/skills/{name}/*.rhai` | Rhai source files referenced by a skill manifest |

---

## Capabilities

Scripts declare which host APIs they need. Safe capabilities are granted
automatically; dangerous ones require a one-time approval notification.

### Safe Capabilities (auto-granted)

| Capability | What It Unlocks |
|------------|----------------|
| `storage.read` | Read from script-scoped key-value store |
| `storage.write` | Write/delete in script-scoped key-value store |
| `memory.read` | Query agent long-term memory |
| `memory.write` | Delete memory entries |
| `cost.read` | Query API spend (daily, monthly, budget) |
| `events.read` | Query recent agent events |
| `config.validate` | Validate a TOML config string |
| `tools.read` | List available tools |
| `agent.read` | Query daemon status, version, health |

### Dangerous Capabilities (require approval)

| Capability | Risk | What It Unlocks |
|------------|------|----------------|
| `tools.call` | Executes arbitrary tools (shell, file, HTTP) | `tool_call(name, args_json)` |
| `cron.write` | Creates/modifies background scheduled tasks | `cron_add()`, `cron_remove()`, etc. |
| `auth.read` | Reads stored credentials | `auth_list()` |
| `auth.write` | Deletes stored credentials | `auth_remove()` |

### Other Capabilities

| Capability | Description |
|------------|-------------|
| `model.chat` | Send text to the configured LLM (also gates `send_vision()`) |
| `model.read` | Discover available models |
| `provider.write` | Hot-swap the active provider/model |
| `channel.read` | Query channel user allowlists |
| `channel.write` | Bind a user identity to a channel |
| `cron.read` | List/inspect scheduled jobs |
| `skills.read` | List installed skills |
| `skills.write` | Install/remove skills |
| `agent.control` | Emergency stop (e-stop) |
| `trace.read` | Query execution traces |

### Declaring Capabilities in a Skill Manifest

```toml
[skill]
name = "my-automation"
version = "0.1.0"
permissions = ["storage.write", "memory.read", "tools.call"]
```

When a script requests a dangerous capability for the first time, ZeroAI
sends an Android notification with Approve / Deny actions. Once approved, the
grant is stored (keyed by script name + SHA-256 content hash) and won't be
asked again unless the script changes.

---

## Host Functions

These are the functions available to call from Rhai scripts. Each requires
the corresponding capability.

### Agent Status & Config (`agent.read`, `config.validate`)

```rhai
status()                              // JSON: {"running": true, ...}
version()                             // "0.1.6"
config()                              // Full running TOML as string
validate_config(toml)                 // Validation result
health()                              // Component health JSON
health_component("gateway")           // Single component health
doctor()                              // Channel diagnostics
```

### LLM Chat (`model.chat`, `model.read`, `provider.write`)

```rhai
send("What is 2+2?")                 // Send text, get response
send_vision("Describe this", images, mime_types)  // Vision request
models("openai")                      // List models for provider
swap_provider("anthropic", "claude-sonnet-4-20250514")
```

### Memory (`memory.read`, `memory.write`)

```rhai
memories(10)                          // Last 10 memories as JSON
memories_by_category("facts", 5)      // Filter by category
memory_recall("search query", 5)      // Semantic search
memory_count()                        // Total count
memory_forget("key")                  // Delete → true/false
```

### Script-Scoped Storage (`storage.read`, `storage.write`)

Each script gets its own isolated SQLite key-value store. Keys don't collide
across scripts.

```rhai
storage_write("counter", "42");       // Upsert
let val = storage_read("counter");    // "42" (or "" if missing)
storage_delete("counter");            // true if existed
```

**Quotas:** 1,000 keys per script, 64 KiB per value, 10 MiB total per script.

### Tools (`tools.read`, `tools.call`)

```rhai
tools()                               // JSON array of available tools
tool_call("file_read", `{"path": "/tmp/data.txt"}`)  // Invoke a tool
```

### Cost Tracking (`cost.read`)

```rhai
cost()                                // Full cost summary JSON
cost_daily()                          // Today's spend as float
cost_monthly()                        // This month's spend
budget(0.50)                          // Check if $0.50 is within budget
```

### Scheduling (`cron.read`, `cron.write`)

```rhai
cron_list()                           // All jobs as JSON
cron_get("job-id")                    // Single job detail
cron_add("0 9 * * *", "send('Good morning')")  // 5-field cron
cron_oneshot("5m", "send('Reminder')")          // One-time delay
cron_add_every(60000, "send('Tick')")           // Every 60s
cron_remove("job-id")
cron_pause("job-id")
cron_resume("job-id")
```

### Skills (`skills.read`, `skills.write`)

```rhai
skills()                              // Installed skills as JSON
skill_tools("my-skill")              // Tools provided by a skill
skill_install("https://github.com/user/skill")
skill_remove("my-skill")
```

### Events & Traces (`events.read`, `trace.read`)

```rhai
events(20)                            // Last 20 agent events
traces(10)                            // Recent execution traces
traces_filter("error", 10)           // Filter by event type
```

### Auth (`auth.read`, `auth.write`)

```rhai
auth_list()                           // Stored profiles as JSON
auth_remove("openai", "default")      // Delete a profile
```

### Emergency Control (`agent.control`)

```rhai
estop()                               // Engage emergency stop
estop_status()                        // {"engaged": bool, ...}
estop_resume()                        // Resume after e-stop
```

---

## Triggers

Skills can register triggers so scripts run automatically.

### Cron Triggers

```toml
[[triggers]]
kind = "cron"
schedule = "0 9 * * *"    # 5-field cron: minute hour day month weekday
```

### Event Triggers

```toml
[[triggers]]
kind = "channel_event"
event = "message"          # message, tool_call, error
channel = "telegram"       # optional — omit for all channels

[[triggers]]
kind = "provider_event"
event = "error"
provider = "openai"        # optional
```

### Manual Triggers

```toml
[[triggers]]
kind = "manual"            # on-demand only
```

---

## Security Limits

Every script runs inside a sandbox with hard limits:

| Limit | Default Scripts | Agent eval_script |
|-------|----------------|-------------------|
| Operation budget | 100,000 | 10,000,000 |
| Wall-clock timeout | 30 seconds | 30 seconds |
| Max source size | 128 KiB | 128 KiB |
| Max string size | 64 KiB | 64 KiB |
| Max array size | 1,024 elements | 1,024 elements |
| Max map size | 256 entries | 256 entries |
| Max call depth | 16 levels | 16 levels |
| Max expression depth | 32 levels | 32 levels |
| Storage keys | 1,000 per script | 1,000 per script |
| Storage total | 10 MiB per script | 10 MiB per script |
| Output truncation | — | 16 KiB |

Additional security:
- **Path sandboxing** via cap-std — scripts can't escape their workspace
- **SSRF guard** — DNS-resolved URL validation blocks private IPs and localhost
- **SHA-256 hash-bound grants** — capability approvals are invalidated if the script changes
- **Tool denylist** — `eval_script` cannot call itself (no recursive execution)

---

## Rhai Language Reference

### Variables & Constants

```rhai
let x = 42;              // mutable
const MAX = 100;          // immutable constant
```

### Types

| Type | Examples |
|------|---------|
| Integer (i64) | `42`, `0xFF`, `0b1010`, `1_000_000` |
| Float (f64) | `3.14`, `-1.0e10` |
| Boolean | `true`, `false` |
| String | `"hello"`, `` `interpolated ${x}` `` |
| Character | `'c'` |
| Array | `[1, "two", true]` |
| Object map | `#{ key: "value", num: 42 }` |
| Unit (null) | `()` |
| Range | `0..10` (exclusive), `0..=10` (inclusive) |

Check type: `type_of(x)` → `"i64"`, `"string"`, etc.

### Operators

```rhai
// Arithmetic
+  -  *  /  %  **

// Comparison
==  !=  <  >  <=  >=

// Logical (short-circuit)
&&  ||  !

// Null-coalescing
x ?? "default"            // returns x unless x is ()

// Elvis (safe chaining)
obj?.field?.method()      // returns () if any part is ()

// Membership
"sub" in "substring"      // true
42 in [1, 42, 3]          // true
"key" in #{ key: 1 }      // true
```

### Strings

```rhai
let s = "escape sequences: \n \t \\";
let r = #"raw string, no escapes"#;
let m = `multi-line with ${expression}`;

s.len;                    // character count
s.contains("esc");        // true
s.split(" ");             // array of words
s.trim();                 // strip whitespace
s.to_upper();             // "ESCAPE SEQUENCES: ..."
s.replace("old", "new");
s[0];                     // first character
s[1..4];                  // slice
```

### Arrays

```rhai
let a = [1, 2, 3];
a.push(4);
a.pop();                  // removes + returns last
a.len;                    // 3
a.filter(|v| v > 1);     // [2, 3]
a.map(|v| v * 2);        // [2, 4, 6]
a.reduce(|acc, v| acc + v, 0);  // 6
a.sort();
a.contains(2);            // true
a.index_of(2);            // 1
```

### Object Maps

```rhai
let m = #{ name: "zero", version: 1 };
m.name;                   // "zero"
m["name"];                // "zero"
m.new_field = true;       // add property
m.keys();                 // ["name", "version", "new_field"]
m.values();               // ["zero", 1, true]
m.contains("name");       // true
m.remove("version");
```

### Control Flow

```rhai
// if/else (also an expression)
let label = if active { "on" } else { "off" };

// switch (hash-based, O(1))
switch command {
    "start" => start(),
    "stop" | "quit" => stop(),
    42 if lucky => jackpot(),
    _ => unknown(),
}

// for..in
for i in 0..10 { print(i); }
for item in my_array { print(item); }
for (item, index) in my_array { print(`${index}: ${item}`); }

// while
while x > 0 { x -= 1; }

// loop (infinite, break to exit)
loop {
    x -= 1;
    if x == 0 { break x; }   // break with return value
}
```

### Functions

```rhai
fn add(x, y) {
    x + y                 // last expression = return value
}

fn greet(name) {
    return `Hello, ${name}!`;
}

// Closures (capture outer variables)
let factor = 10;
let scale = |x| x * factor;
scale.call(5);            // 50
```

Functions must be defined at global scope. Parameters are passed by value.
Functions cannot access outer variables (use closures for that).

### Error Handling

```rhai
try {
    let x = 10 / 0;
} catch (err) {
    print(`Error: ${err}`);
}

throw "something went wrong";  // throw custom error
```

---

## Skill Manifest Reference (SKILL.toml)

```toml
[skill]
name = "my-automation"
version = "0.1.0"
description = "What this skill does"
permissions = ["storage.write", "memory.read"]

[[scripts]]
name = "main"
path = "main.rhai"
runtime = "rhai"

[[triggers]]
kind = "cron"
schedule = "0 9 * * *"

[[triggers]]
kind = "channel_event"
event = "message"
channel = "telegram"

[limits]
max_operations = 10000000
max_call_levels = 16
max_expr_depth = 32
max_string_size = 65536
max_array_size = 1024
max_map_size = 256
max_script_bytes = 131072
```

---

## Examples

### Daily Cost Reporter

```toml
# SKILL.toml
[skill]
name = "cost-reporter"
version = "0.1.0"
permissions = ["cost.read", "storage.write"]

[[scripts]]
name = "report"
path = "report.rhai"
runtime = "rhai"

[[triggers]]
kind = "cron"
schedule = "0 23 * * *"
```

```rhai
// report.rhai — runs at 11 PM daily
let today = cost_daily();
let month = cost_monthly();
let prev = storage_read("yesterday_cost");
let prev_f = if prev == "" { 0.0 } else { parse_float(prev) };  // Rhai builtin
let delta = today - prev_f;

storage_write("yesterday_cost", today.to_string());

let sign = if delta >= 0.0 { "+" } else { "" };
`Daily: $${today} (${sign}$${delta}) | Monthly: $${month}`
```

### Memory Backup to Storage

```rhai
// backup.rhai — snapshot recent memories to script storage
let mems = memories(50);
storage_write("backup_latest", mems);
storage_write("backup_count", memory_count().to_string());
`Backed up ${memory_count()} memories`
```

### Multi-Tool Composition

```rhai
// compose.rhai — read a file, summarize with LLM, store result
let content = tool_call("file_read", `{"path": "workspace/notes.md"}`);
let summary = send(`Summarize this in 2 sentences:\n${content}`);
storage_write("notes_summary", summary);
summary
```
