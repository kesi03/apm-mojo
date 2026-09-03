# APM Java Proxy Maven Plugin

This project provides a Java implementation of the `apm-proxy` service and a
Maven goal that starts it with the Elastic APM Java agent attached.

The plugin declares `co.elastic.apm:elastic-apm-agent` as a Maven dependency.
Maven therefore resolves it through the normal local repository, mirrors,
credentials, and proxy configuration, including corporate Artifactory or
JFrog repositories.

## Usage

Install the plugin locally:

```bash
mvn install
```

Start the proxy (the goal stays attached while the proxy runs):

```bash
mvn io.github.kesi03:apm-mojo:0.1.0-SNAPSHOT:start
```

Use the same proxy from a Maven build:

```xml
<plugin>
  <groupId>io.github.kesi03</groupId>
  <artifactId>apm-mojo</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <configuration>
    <port>8200</port>
    <agentServerUrl>http://localhost:8200</agentServerUrl>
    <serviceName>openliberty-app</serviceName>
    <environment>development</environment>
    <waitForProcess>true</waitForProcess>
  </configuration>
  <executions>
    <execution>
      <goals><goal>start</goal></goals>
    </execution>
  </executions>
</plugin>
```

The agent version is controlled by the plugin's
`elastic-apm-agent.version` Maven property (currently `1.54.0`). The goal locates the Maven-resolved artifact in the plugin classloader, copies
it to the configured runtime path, and sets
`JAVA_TOOL_OPTIONS`, adding `-javaagent:<resolved-agent-jar>`. This is the same
mechanism as the Docker example and does not require the proxy command itself
to know about the agent.
Any existing `JAVA_TOOL_OPTIONS` value is preserved. The goal also sets the
agent variables `ELASTIC_APM_SERVER_URL`, `ELASTIC_APM_SERVICE_NAME`, and
`ELASTIC_APM_ENVIRONMENT`. Existing environment variables such as `APM_SERVER`,
`APM_TOKEN`, `APM_FORWARD`, `APM_SAVE_DIR`, and `APM_PRINT` are inherited by
the proxy process. Set `APM_FORWARD=true` and `APM_SERVER` to forward captured
Elastic APM NDJSON to another APM server.

The Java proxy supports:

- `POST /intake/v2/events`
- `POST /intake/v2/rum/events`
- `POST /v1/traces`, `/v1/metrics`, and `/v1/logs`

Captured payloads are written to `APM_SAVE_DIR` (default `./apm_logs`).

## Publishing

The GitHub Actions workflow publishes releases to Maven Central when a GitHub
release is created:

```bash
gh release create v0.1.0 --generate-notes
```

It can also be run manually from the Actions tab by providing a version such
as `0.1.0`. Configure these repository secrets:

- `CENTRAL_USERNAME` and `CENTRAL_PASSWORD`: Maven Central Portal token

Generate the signing key and configure the GitHub secrets with Task:

```bash
task publish-gpg-key
```

This creates a password-protected GPG key, publishes its public key to the
Ubuntu OpenPGP keyserver, stores the base64-encoded private key in
`GPG_PRIVATE_KEY`, stores its passphrase in `GPG_PASSPHRASE`, and removes the
temporary local keyring. The workflow imports that key on the runner for
signing.

The `central` Maven profile creates source and Javadoc artifacts and signs all
published artifacts.
