# MajorDome

An AI assistant inside Jenkins that can **read and act**: diagnose failed builds, find jobs,
explain test failures, start or abort builds and, for administrators, inspect the system and manage
nodes. It works for regular users and administrators alike, always:

- **with the user's own Jenkins permissions.** Every tool runs as the signed-in user, and tools the
  user cannot use are not even offered to the model;
- **with human confirmation for every change.** The model can only *propose* a change; it runs
  when the user clicks **Confirm**, after permissions are checked again;
- **with an audit trail.** Proposals, confirmations and executions go to a tamper-evident log
  (`$JENKINS_HOME/majordome/audit.jsonl`, hash-chained).

> Status: early development (phase 0). See [docs/ROADMAP.md](docs/ROADMAP.md).

## Model providers

| Provider | Use for |
|---|---|
| Anthropic (Claude) | Claude via the official Anthropic Java SDK. Default model `claude-opus-5-5` |
| OpenAI-compatible | OpenAI, or **self-hosted** models via Ollama, vLLM, llama.cpp server, LM Studio, LocalAI, or AI gateways |

API keys are stored as *Secret text* credentials.

## Built-in tools

| Tool | Kind | Who |
|---|---|---|
| `list_jobs`, `get_job`, `get_build`, `get_build_log`, `get_queue`, `list_nodes` | read | users with Overall/Read (per-item visibility applies) |
| `trigger_build`, `abort_build`, `set_job_enabled` | write (confirmed) | users with Job/Build, Job/Cancel, Job/Configure on that job |
| `get_system_info`, `list_plugins` | read | administrators (Overall/SystemRead) |
| `set_node_offline` | write (confirmed) | users with Agent/Disconnect |

Other plugins can add tools by extending `ReadTool` or `WriteTool` with `@Extension`.

## Setup

1. Install the plugin.
2. *Manage Jenkins → Credentials*: add a **Secret text** with the provider API key (if needed).
3. *Manage Jenkins → System → MajorDome*: choose the provider, model and credential.
4. Grant **MajorDome/Use** to the users who may chat (administrators have it implicitly).

The chat is available at `/majordome` and as a floating button on every page.

Configuration as Code:

```yaml
unclassified:
  majordome:
    provider:
      anthropic:
        credentialsId: anthropic-api-key
        model: claude-opus-5-5
        effort: medium
    allowBuildLogs: true
```

## Development

Requires JDK 21 and Maven 3.9+.

```bash
mvn verify        # build + tests
mvn hpi:run       # Jenkins with the plugin at http://localhost:8080/jenkins/
```

## License

MIT
