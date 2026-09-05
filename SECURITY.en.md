[한국어](SECURITY.md) · **English**

# Security

## Reporting a vulnerability

**Do not open a public issue.** Issues are public, and a known-but-unfixed vulnerability is an
incident on its own.

Use GitHub's private reporting:

1. the **Security** tab of this repository
2. **Advisories** → **Report a vulnerability**
3. fill in what is listed below

That channel is visible only to the repository's maintainers, and the fix and the disclosure can be
coordinated in the same place.

<!-- TODO: add a contact email for reporters who cannot use a GitHub account -->

Please include:

- what kind of problem it is (authorisation bypass, token handling, SQL, an isolation breach, …)
- the affected commit or version
- how to reproduce it — the request itself, if one request shows it
- what an attacker gets: what they can read, write, or step past
- a mitigation, if you know one

There is no release yet and one maintainer. No response time is promised here, because a promise
that cannot be kept is worse than none. Receipt will be acknowledged, and the timing of the fix and
of disclosure is agreed with the reporter.

## Supported versions

| Version | Status |
|---|---|
| `0.1.0-SNAPSHOT` (`main`) | in development; fixes land here |
| any released version | there are none |

Nothing has been published to Maven Central. If you are using this repository today you are using
`main`, and that is where a fix goes.

## The security surface

Things to know before deploying it. All of it is in the code; this section only says where.

### There is no default signing key

`AIMON_MEMORY_JWT_SECRET` is required and has no default. Blank, or shorter than 32 bytes, and the
application refuses to start (`JwtService`).

That is not there to be inconvenient. A development default in `application.yml` is a signing key
published in the repository: a deployment that forgets the variable boots cleanly, signs production
tokens with it, and anyone who has read the source can mint an admin token. Nothing in the running
system distinguishes that state from a correct one, which is why it is a startup failure rather than
a warning.

```sh
export AIMON_MEMORY_JWT_SECRET=$(openssl rand -base64 48)
```

### Tokens only narrow

Scopes nest four deep: `admin` → `workspace` → `peer` → `session`. A token **cannot mint anything
wider than itself** (`TokenController`). A different workspace, a broader scope, or a peer the caller
does not already speak for is refused.

That is what makes delegation safe. A service holding a workspace token can mint a session token for
one conversation and hand it to a browser, with no admin key anywhere near it.

**There is no revocation list.** Expiry is the only thing that ends a leaked token, so the lifetime
is capped at 30 days (`JwtService.MAX_LIFETIME`). If you believe a token has leaked, rotate
`AIMON_MEMORY_JWT_SECRET`; that invalidates all of them.

### The route allowlist

`RoutePolicy` names a minimum scope for every route, one line each. **A route with no entry is
refused.** `RoutePolicyCoverageTest` walks the live handler mappings and fails the build when a route
is missing from the table.

So adding an endpoint without deciding who may call it fails the build rather than the deployment.

### Isolation is per pair

Every conclusion sits under a `(workspace, observer, observed)` composite foreign key. `alice`'s
memory of herself and `bot`'s memory of `alice` are separate stores. A read that crosses that
boundary is a data leak, so if you have found one, it is a vulnerability as this document means the
word.

### The audit log

Everything that changes a conclusion writes an event. The dreamer edits memory with nobody watching,
and without that log there is no answer to "where did this come from" about a belief no human ever
stated.

### Credentials and model calls

- Provider keys (`OPENAI_API_KEY`, `ANTHROPIC_API_KEY`) are read from the environment only. There are
  no credentials in the repository.
- Tests run with `AIMON_MEMORY_LLM_MODE=replay`, which is the default. CI never calls a model.
- `test-fixtures/llm/` is empty. Check what is in a recorded response before committing it — prompts
  and responses carry conversation content verbatim.

### Operations

The "Secrets" section of `docs/runbook.en.md` covers what a deployment sets and how.

## Out of scope

- The credentials in `docker-compose.yml` (`aimon_memory` / `aimon_memory`) are for local
  development. Running production on them is a configuration mistake, not a vulnerability.
- Without credentials the embedder falls back to a local hashing implementation. The weaker retrieval
  that follows is intended behaviour and is documented in the README.
- What you send to a provider. Deciding what reaches a model is the deployment's call.
