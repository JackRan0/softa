# AI Starter

LLM chat integration for Softa apps, built on Spring AI. Multi-provider,
database-driven, with conversation management and streaming.

Add the dependency (same version as your other Softa starters):

```xml
<dependency>
    <groupId>io.softa</groupId>
    <artifactId>ai-starter</artifactId>
    <version>${softa.version}</version>
</dependency>
```

Auto-configuration activates when the starter (plus the Spring AI provider jars
you need) is on the classpath — no enabling flag required.

## Providers

Selected per model via the `AiModelProvider` enum:

- **OpenAI** (GPT)
- **Azure OpenAI** (OpenAI driver, configurable base URL)
- **OpenAI-compatible** (Qwen, Moonshot, ChatGLM, ZhiPu, …)
- **DeepSeek**
- **Anthropic** (Claude)

## Concepts

The module is model-driven — credentials and endpoints live in the database, so
you can switch models without a restart.

| Entity | What it holds |
|---|---|
| `AiModel` | A backend LLM: provider, API key (encrypted), base URL, context window, timeout, and per-1M-token input/output pricing for cost tracking |
| `AiRobot` | A configured assistant: system prompt, which `AiModel` it uses, and generation params (temperature, output-token limit, penalties, stream flag) |
| `AiConversation` | A multi-turn conversation with rolled-up token counts |
| `AiMessage` | One user/assistant message: role, content, token usage, status; `parentId` supports threaded replies |

You create `AiModel` and `AiRobot` rows (via their CRUD APIs / seed data), then
chat against a robot.

## REST API

Exposed by `AiRobotController` (package `io.softa.starter.ai`):

**Non-streaming** — one request/response:
```http
POST /AiRobot/chat
{ "robotId": 123, "conversationId": null, "content": "Hello?" }
```
`conversationId: null` starts a new conversation. Returns the assistant
`AiMessage` with content and token usage populated.

**Streaming** — a two-step flow (persist, then stream):
```http
POST /AiRobot/persistChatMessage
{ "robotId": 123, "conversationId": null, "content": "Explain SSE" }
→ { "conversationId": ..., "userMessageId": 111, "aiMessageId": 222 }

POST /AiRobot/streamChat
{ "userMessageId": 111, "aiMessageId": 222 }
→ Server-Sent Events: text deltas, terminated by [DONE]
```
The user message is persisted in step 1; the assistant message is created there
too and finalized (content + usage) when the stream completes. Token usage rolls
up to the conversation.

## Programmatic use

Inject `AiRobotService` (package `io.softa.starter.ai.service`):

```java
AiMessage reply = aiRobotService.chat(userMessage);      // sync
SseEmitter stream = aiRobotService.streamChat(request);  // streaming
```

Prior turns (up to `historyWindowSize`) are replayed to the model automatically.

## Configuration

```yaml
softa:
  ai:
    history-window-size: 20     # prior messages replayed per turn (default 20)
    response-timeout: 60000     # SSE stream timeout in ms (default 60s)
```

Model credentials, endpoints, and timeouts are **per-`AiModel` row** in the
database, not in `application.yml` — edit the row to switch providers or rotate
keys at runtime.

## Scope

Intentionally focused on conversation management + streaming chat over a
flexible multi-provider abstraction. It does **not** ship embeddings, tool/function
calling, or RAG out of the box.
