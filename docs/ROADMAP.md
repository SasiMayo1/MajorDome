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
      sul formato reale dell'API con un server finto (`AnthropicProviderTest`).
      Non avremo una API key: le prove con modelli veri passano solo da modelli
      self-hosted (vedi Fase 3)
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

Ambiente di sviluppo (promemoria):
- [ ] Alzare la memoria di WSL: il PC ha 16 GB, WSL di default ne prende la metà
      (~7,7 GB) e con Jenkins + Ollama si è bloccato una volta. In
      `%USERPROFILE%\.wslconfig`, sezione `[wsl2]`: `memory=10GB` e `swap=4GB`
      (restano ~6 GB a Windows), poi `wsl --shutdown`. Serve anche per un 3B
      addestrato o un 7B in locale

### Fase 1 — MVP per sviluppatori e operatori (2–3 settimane)
Tool utente:
- [ ] Lettura: elenco job, stato job, dettaglio build, coda del log (redatta),
      risultati dei test, changeset SCM, stage della pipeline (dipendenza
      opzionale da `workflow-api`), coda di esecuzione, artifact
- [ ] Scrittura: avvio build con parametri, abort, replay/rebuild,
      enable/disable job
- [x] Caso d'uso guida: **"perché è fallita questa build?"** (log + test +
      changeset + confronto con l'ultima build riuscita): tool `diagnose_build`
      che mette insieme tutto in una chiamata (ultima build riuscita, da quante
      build fallisce, commit da allora, test nuovi vs già rotti, righe di errore
      del log redatte). Provato con `qwen2.5:3b`: diagnosi corretta in ~26 s su CPU
- [x] Job non trovato → "forse intendevi…?" con i soli job visibili all'utente
      (anticipato dalla Fase 3). Con il 3B: suggerisce il nome giusto ma chiede
      conferma invece di riprovare da solo

Piattaforma:
- [x] Contesto della pagina risolto lato server (sono su `job/x/42` → il modello
      lo sa), solo per i job che l'utente può leggere; anche permalink come
      `lastFailedBuild`
- [x] Streaming delle risposte (SSE): endpoint `POST /majordome/chatStream` con
      eventi `text` / `tool` / `proposal` / `done` / `error`; il widget mostra il
      testo man mano e quale tool sta girando. Streaming token per token con il
      provider OpenAI-compatible (disattivabile); provato con Ollama: primo testo
      a ~50 s invece di vedere tutto a ~73 s
- [ ] Streaming token per token anche per il provider Anthropic (ora riceve gli
      stessi eventi, ma il testo arriva in un pezzo solo)
- [ ] Richieste non bloccanti: oggi un turno occupa un thread HTTP di Jenkins
      per tutta la sua durata
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
     perché molti modelli piccoli sbagliano il tool calling;
   - eventualmente un **nostro modello addestrato** sui tool di MajorDome
     (vedi il piano qui sotto), pubblicato a parte, mai dentro il plugin.
2. **"L'utente carica un proprio agente?"** Proposta: il `ToolRegistry` è
   indipendente dal loop, quindi si può esporre **lo stesso catalogo di tool**
   ad agenti esterni, per esempio via MCP. Le scritture restano "proposte" che
   un umano conferma in Jenkins. L'agente esterno porta la propria
   intelligenza, MajorDome garantisce la governance. Da valutare se integrarsi con
   `mcp-server-plugin` invece di duplicarlo.

#### Dove gira il modello (deciso il 2026-11-26)
Il plugin gira nel controller Jenkins ed esegue i tool; **il modello è un servizio
esterno scelto dall'admin di ogni installazione**: un'API cloud (con la chiave e
il conto dell'azienda) oppure un endpoint self-hosted (Ollama, vLLM… sui server
dell'azienda). Noi **non ospitiamo** modelli per gli utenti, per costi, privacy e
sicurezza. Senza un provider configurato il plugin non invia nulla all'esterno.

#### Piano modelli self-hosted
Per noi l'unica strada per provare con modelli veri, perché non avremo una API key.

1. **Endpoint di sviluppo su OCI Always Free**
   - [ ] VM Ampere A1 (ARM, oggi **2 OCPU / 12 GB**, solo CPU) con Ollama
   - [ ] Raggiungibile solo via tunnel SSH o reverse proxy con token (Ollama non
         ha autenticazione; il provider OpenAI-compatible accetta una credenziale)
   - [ ] Misurare la latenza reale (circa 3k token di prompt a ogni turno) e
         verificare che Ollama riusi il prefisso del prompt tra un turno e l'altro
   - [ ] Confrontare un modello 7–8B (es. `qwen2.5:7b`) con un paio di
         alternative della stessa taglia
   - Rischi: capacità Always Free spesso esaurita, VM inattive recuperate da Oracle
2. **Benchmark (eval) del tool calling**, da fare prima di tutto il resto
   - [ ] Set fisso di domande con le chiamate ai tool e le risposte attese
         (es. "perché è fallita X?", "fai partire X" → solo proposta)
   - [ ] Metriche: tool giusti con argomenti giusti, nessuna azione senza
         conferma, tempo di risposta, **nessun nome di tool nella risposta**
         (il 3B dice "usa trigger_build" invece di offrirsi), **nessun dettaglio
         inventato** rispetto ai dati dei tool, qualità della lingua (italiano)
   - [ ] Eseguibile su qualsiasi provider, per confrontare i modelli
3. **Rimedi economici**, utili a tutti i modelli (anticipabili in Fase 1)
   - [x] Job non trovato → il tool suggerisce i nomi simili ("forse intendevi
         `demo-fail`?"): fatto nella Fase 1
   - [ ] Descrizioni dei tool più brevi, per avere un prompt più corto e quindi
         risposte più veloci su CPU
   - [ ] Valutare di inviare al modello solo i tool pertinenti alla domanda
4. **Fine-tuning di un modello leggero (1,5–4B) sui nostri tool**, solo se dopo
   i punti 2–3 resta un divario misurabile
   - [ ] Scegliere il modello base **anche per la licenza**: deve permettere uso
         commerciale e redistribuzione (Apache 2.0 / MIT). Qwen2.5 3B ha una
         licenza solo per ricerca: da escludere. Da verificare al momento:
         Qwen3-4B, IBM Granite, Phi-4-mini, SmolLM3, Llama 3.2 3B (licenza propria)
   - [ ] Dataset: qualche centinaio di conversazioni con i *nostri* tool,
         generate da una Jenkins di test con job finti (nessun dato reale)
   - [ ] Addestramento LoRA/QLoRA su GPU gratuita (Colab o Kaggle, es. Unsloth);
         né il PC né la VM OCI hanno una GPU
   - [ ] Export in GGUF e import in Ollama; il benchmark deve mostrare il
         miglioramento prima della pubblicazione
   - Quando conviene: per le domande "di cultura Jenkins" meglio la
     documentazione al momento della domanda (RAG) che il fine-tuning
5. **Distribuzione del modello addestrato**
   - [ ] Pubblicato a parte, **non nel `.hpi`** (circa 2 GB): Hugging Face (pesi,
         GGUF, scheda del modello con limiti e risultati del benchmark) e
         registro Ollama (`ollama pull <utente>/majordome:3b`)
   - [ ] Versioni legate al catalogo dei tool (es. `majordome:3b-tools1`):
         ri-addestrare quando i tool cambiano in modo significativo
   - [ ] Pubblicare anche il dataset, così altri possono addestrare altri modelli
6. **Esperienza per chi installa il plugin**
   - [ ] `docker-compose` di riferimento: Jenkins + Ollama già collegati
   - [ ] Lista di modelli consigliati per hardware (con GPU / solo CPU), presa
         dal benchmark
   - [ ] Messaggi chiari quando il provider manca o non risponde, che dicano
         all'utente cosa chiedere all'admin

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
