# MajorDome — Piano d'azione

Plugin Jenkins open source che aggiunge una chat con un assistente AI capace di
**leggere e agire** su Jenkins, sia per utenti normali sia per amministratori,
sempre entro i permessi Jenkins di chi sta chiedendo e con conferma umana per
ogni azione che modifica qualcosa.

Ultimo aggiornamento: 2026-11-24.

---

## 1. Perché un plugin nativo (e cosa cambia rispetto a "Progetto")

Il progetto precedente (`Desktop/Progetto`: estensione Rancher + backend FastAPI)
aveva un RBAC applicativo fatto a mano (whitelist di namespace/kind). Dentro
Jenkins questo non serve: il plugin gira nel controller e ogni tool viene
eseguito **con l'`Authentication` dell'utente collegato**. Quindi:

- un utente senza `Item.BUILD` su un job non può far partire quel job via chat,
  esattamente come dalla UI;
- un admin vede e può usare tool di amministrazione che un utente normale non
  riceve nemmeno nella lista dei tool inviata al modello.

"Admin vs non admin" non è una modalità del plugin: è il modello dei permessi di
Jenkins (Matrix Authorization, Role-based Strategy, ecc.) applicato ai tool.

Cosa riportiamo dal progetto precedente, tradotto in Java:

| Concetto (Progetto) | In MajorDome |
|---|---|
| Tool READ eseguiti subito, tool WRITE solo proposti | Identico: `ReadTool` / `WriteTool` |
| `create_pending_action` → approve/reject | `PendingActionStore`: proposta monouso, con scadenza, legata all'utente |
| Ricontrollo RBAC all'approvazione | Ricontrollo dei permessi Jenkins all'approvazione |
| Audit log append-only con hash chain | `JENKINS_HOME/majordome/audit.jsonl` con hash chain |
| Test anti prompt-injection | Stessi test, con provider LLM finto + `JenkinsRule` |
| Client LLM iniettato (Protocol) | `LlmProvider` come `ExtensionPoint` Jenkins |

## 2. Panorama esistente (verificato a novembre 2026)

- **Concierge** (`jenkinsci/concierge-plugin`, MIT, prima release a novembre 2026):
  onboarding + chat che gira con i permessi dell'utente. Supporta Anthropic,
  OpenAI, xAI, DeepSeek e modelli locali OpenAI-compatible. L'**unica azione di
  scrittura** è proporre l'avvio di una build (con conferma), solo per i job
  messi in lista da un admin.
- **MCP Server** (`jenkinsci/mcp-server-plugin`, MIT, molto attivo): espone i
  tool Jenkins ad agenti esterni via MCP, con i permessi dell'utente, ma **senza
  conferma umana** sulle azioni di scrittura.
- **Resources AI Chatbot** e i progetti GSoC 2025/2026: chatbot sulla
  documentazione e i flussi di lavoro, senza azioni operative.

**Dove si posiziona MajorDome:** un assistente *operativo*. Offre un catalogo ampio
di azioni utente e admin, sempre governate (proposta → conferma → audit). È
estensibile, cioè altri plugin possono contribuire tool, e lascia scegliere il
modello: cloud, self-hosted o un agente portato dall'utente.

## 3. Architettura

```
Browser (widget chat su ogni pagina + pagina /majordome)
   │  POST JSON (crumb CSRF), poi streaming SSE
   ▼
MajorDomeRootAction  ── controlla il permesso MajorDome/Use
   │
ChatService (agent loop)  ── max N iterazioni, budget token
   │            │
   │            └── LlmProvider (ExtensionPoint)
   │                  ├─ AnthropicProvider (SDK Java ufficiale)
   │                  ├─ OpenAiCompatibleProvider (OpenAI, Ollama, vLLM, LM Studio…)
   │                  └─ [Fase 3] ExternalAgentProvider (agente portato dall'utente)
   │
ToolRegistry  ── tutti gli AgentTool (ExtensionPoint), filtrati per i permessi
   │              dell'utente corrente PRIMA di essere mostrati al modello
   ├─ ReadTool  → eseguito subito, come l'utente
   └─ WriteTool → solo PendingAction (monouso, scade in 10 minuti, legata all'utente)
                   └─ approve: ricontrolla i permessi → esegue come chi approva → audit
AuditLog (jsonl + hash chain)    ConversationStore (per utente)
```

Principi non negoziabili:

1. **Nessuna scrittura senza click umano.** Non c'è nessun percorso nel loop
   dell'agente che esegua un `WriteTool`. Va testato esplicitamente.
2. **Permessi controllati tre volte:** quando si filtra la lista dei tool, alla
   proposta e all'approvazione.
3. **I contenuti letti sono dati, non istruzioni.** Log, descrizioni dei job e
   messaggi di commit possono contenere prompt injection. Il system prompt lo
   dice al modello e la regola 1 rende l'attacco innocuo.
4. **I segreti non escono dal controller.** Le credenziali non vengono mai lette
   (solo i metadati), i parametri password non vengono mai impostati né copiati,
   i log vengono redatti prima dell'invio. L'invio dei log al modello è
   disattivabile dall'admin.
5. **Il modello non è fidato.** Gli argomenti dei tool vengono validati lato
   server e le risposte vengono troncate a una dimensione massima.

## 4. Scelte tecniche

| Tema | Scelta | Note |
|---|---|---|
| Baseline Jenkins | `2.568.3` (LTS consigliata), BOM `bom-2.568.x` | Java 21 |
| Parent POM | `org.jenkins-ci.plugins:plugin` 6.x | |
| JSON | `jackson2-api` | **Non** `net.sf.json`, che reinterpreta le stringhe simili a JSON (pericoloso con l'output di un LLM) |
| Segreti | Credentials plugin (Secret text) | Mai chiavi in chiaro nella configurazione |
| Client Anthropic | SDK Java ufficiale `com.anthropic:anthropic-java` | Da verificare i conflitti di classloader in Jenkins; se ci sono, va confezionato come plugin "api" separato |
| Modello di default | `claude-opus-5-5`, configurabile | Con il fallback lato server sui rifiuti |
| Modelli self-hosted | Endpoint OpenAI-compatible | Copre Ollama, vLLM, llama.cpp server e LM Studio senza codice dedicato |
| HTTP | `ProxyConfiguration` di Jenkins | Rispetta il proxy configurato nel controller |
| Frontend | JS vanilla + CSS, nessun build step all'inizio | Compatibile con CSP (niente script inline) |
| Configurazione | `GlobalConfiguration` + `@Symbol("majordome")` | Funziona subito con Configuration as Code |

## 5. Fasi

### Fase 0 — Fondamenta (1–2 settimane)
- [x] Ambiente: JDK 21 + Maven 3.9 su WSL Ubuntu (in `~/opt`), `mvn verify` verde
- [x] `mvn hpi:run` e prova manuale con un modello vero: Ollama 0.40.18 +
      `qwen2.5:3b` in WSL (proposta di build creata e non eseguita senza conferma;
      il modello 3B sbaglia a volte il nome del job). Anthropic: provider testato
      sul formato reale dell'API con un server finto (`AnthropicProviderTest`),
      la prova con una chiave vera resta da fare quando avremo una API key
- [x] Scheletro del plugin (pom, licenza MIT, README, `.gitignore`)
- [x] Nome: **MajorDome** (artifactId `majordome`, package `io.jenkins.plugins.majordome`)
- [x] Repository git + primo commit (https://github.com/SasiMayo1/MajorDome)
- [x] Permesso `MajorDome/Use` (implicito da `Overall/Administer`)
- [x] Configurazione globale: provider, modello, credenziale, limiti (+ JCasC via `@Symbol`)
- [x] `LlmProvider` + `AnthropicProvider` (SDK ufficiale) + `OpenAiCompatibleProvider`
- [x] `AgentTool` / `ReadTool` / `WriteTool` + `ToolRegistry` + 12 tool integrati
- [x] `ChatService` (agent loop) + `ConversationStore` in memoria
- [x] `PendingActionStore` + `AuditLog` con hash chain
- [x] UI: pagina `/majordome` e widget flottante su tutte le pagine (JS senza build step)
- [x] Test `JenkinsRule` con provider finto: visibilità dei tool per ruolo, write
      solo proposte, approvazione, approvazione da parte di un altro utente
      rifiutata, prompt injection, redazione dei segreti, CSRF, permesso Use

### Fase 1 — MVP per sviluppatori e operatori (2–3 settimane)
Tool utente:
- [ ] Lettura: elenco job, stato job, dettaglio build, coda del log (redatta),
      risultati dei test, changeset SCM, stage della pipeline (dipendenza
      opzionale da `workflow-api`), coda di esecuzione, artifact
- [ ] Scrittura: avvio build con parametri, abort, replay/rebuild,
      enable/disable job
- [ ] Caso d'uso guida: **"perché è fallita questa build?"** (log + test +
      changeset + confronto con l'ultima build riuscita)

Piattaforma:
- [ ] Contesto della pagina risolto lato server (sono su `job/x/42` → il modello
      lo sa)
- [ ] Streaming delle risposte (SSE) e richieste non bloccanti
- [ ] Conversazioni persistenti per utente (`JENKINS_HOME/majordome/…`)
- [ ] Budget token per utente e al mese, conteggio dei costi
- [ ] Interfaccia in italiano e inglese

### Fase 2 — Amministratori (2–3 settimane)
- [ ] Lettura: info di sistema, nodi/agent ed executor, plugin e aggiornamenti
      disponibili, security advisories, log di sistema, export della
      configurazione JCasC (senza segreti), metadati delle credenziali
- [ ] Scrittura: nodo offline/online, quiet down / annullamento, installazione o
      aggiornamento dei plugin (proposta), modifica della configurazione di un job
      con **diff XML** mostrato nella card di conferma
- [ ] **Policy per tool** configurabile dall'admin: disabilitato, conferma,
      oppure "quattro occhi" (l'approvazione deve venire da un secondo admin)
- [ ] Pagina admin dell'audit log, con verifica della hash chain
- [ ] **Mai** esporre la Script Console o Groovy arbitrario come tool

### Fase 3 — Modelli self-hosted e agenti personali (da decidere)
Due domande aperte, con una proposta per ciascuna:

1. **"Installiamo noi le immagini LLM?"** Proposta: **no dentro il plugin**. I
   pesi del modello non stanno in un `.hpi` e un LLM non deve girare nella JVM
   del controller. Forniamo invece:
   - un `docker-compose` / chart Helm di riferimento (Ollama o vLLM accanto a
     Jenkins);
   - una lista di modelli open testati sul *nostro* set di tool, con un piccolo
     benchmark (eval) sulla qualità del tool-calling. Questo è il vero valore,
     perché molti modelli piccoli sbagliano il tool calling.
   - idea da valutare: un modello 7–8B su una VM OCI Always Free (Ampere A1,
     4 OCPU / 24 GB, solo CPU) come endpoint di sviluppo condiviso, raggiungibile
     solo via tunnel o reverse proxy con token (Ollama non ha autenticazione).
2. **"L'utente carica un proprio agente?"** Proposta: il `ToolRegistry` è
   indipendente dal loop, quindi si può esporre **lo stesso catalogo di tool**
   ad agenti esterni, per esempio via MCP. Le scritture restano "proposte" che
   un umano conferma in Jenkins. L'agente esterno porta la propria
   intelligenza, MajorDome garantisce la governance. Da valutare se integrarsi con
   `mcp-server-plugin` invece di duplicarlo.

### Fase 4 — Open source e rilascio
- [ ] Repository GitHub pubblico, CI (GitHub Actions + `Jenkinsfile` con
      `buildPlugin`)
- [x] **Nome:** "MajorDome" (2026-11-24). Non deriva da "Jenkins", come chiedono le
      regole di hosting `jenkinsci`, e non esistono plugin Jenkins o prodotti DevOps
      omonimi. Esistono progetti di domotica dal nome simile (MajorDom), quindi è
      meglio usare sempre "MajorDome for Jenkins" o "MajorDome AI" nella comunicazione
- [ ] Hosting request su `jenkinsci`, processo di security review del progetto
      Jenkins
- [ ] Documentazione, screenshot, guida alla configurazione JCasC
- [ ] Prima release (incrementals → update center)

## 6. Rischi principali

| Rischio | Mitigazione |
|---|---|
| Prompt injection da log/commit che induce azioni | Write solo proposte, conferma umana, card con anteprima esatta |
| Fuga di segreti verso il provider LLM | Redazione, niente lettura di credenziali, invio dei log disattivabile, modello self-hosted |
| Richieste lunghe che bloccano i thread HTTP | Streaming/async in Fase 1, timeout, limite di iterazioni |
| Costi | Budget per utente, contatori, modello configurabile |
| Sovrapposizione con Concierge / MCP Server | Focus su azioni ampie con governance e su admin; valutare la collaborazione upstream |
| Manutenzione su più versioni di Jenkins | Una sola baseline LTS, aggiornata ogni circa 6 mesi |
