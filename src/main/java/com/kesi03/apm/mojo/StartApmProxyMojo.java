package com.kesi03.apm.mojo;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

@Mojo(name = "start", defaultPhase = LifecyclePhase.NONE, threadSafe = true)
public class StartApmProxyMojo extends AbstractMojo {
    @Parameter(property = "apm.agentVersion", defaultValue = "${elastic-apm-agent.version}", readonly = true)
    private String agentVersion;

    @Parameter(property = "apm.host", defaultValue = "0.0.0.0")
    private String host;

    @Parameter(property = "apm.port", defaultValue = "8200")
    private int port;

    @Parameter(property = "apm.agentJar", defaultValue = "${project.build.directory}/apm-mojo/elastic-apm-agent.jar")
    private File agentJar;

    @Parameter(property = "apm.wait", defaultValue = "true")
    private boolean waitForProcess;

    @Parameter(property = "apm.agentServerUrl", defaultValue = "http://localhost:8200")
    private String agentServerUrl;

    @Parameter(property = "apm.serviceName", defaultValue = "apm-java-proxy")
    private String serviceName;

    @Parameter(property = "apm.environment", defaultValue = "development")
    private String environment;

    @Override
    public void execute() throws MojoExecutionException {
        try {
            copyResolvedAgent(agentJar.toPath());
            Path codeLocation = Path.of(ApmProxyMain.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            List<String> command = new ArrayList<>();
            command.add(javaExecutable());
            command.add("-cp");
            command.add(codeLocation.toString());
            command.add(ApmProxyMain.class.getName());
            command.add("--host");
            command.add(host);
            command.add("--port");
            command.add(Integer.toString(port));

            getLog().info("Starting Java APM proxy on " + host + ":" + port);
            ProcessBuilder processBuilder = new ProcessBuilder(command).inheritIO();
            String existingToolOptions = processBuilder.environment().getOrDefault("JAVA_TOOL_OPTIONS", "").trim();
            String agentOption = "-javaagent:" + agentJar.getAbsolutePath();
            processBuilder.environment().put("JAVA_TOOL_OPTIONS",
                    existingToolOptions.isEmpty() ? agentOption : existingToolOptions + " " + agentOption);
            processBuilder.environment().put("ELASTIC_APM_SERVER_URL", agentServerUrl);
            processBuilder.environment().put("ELASTIC_APM_SERVICE_NAME", serviceName);
            processBuilder.environment().put("ELASTIC_APM_ENVIRONMENT", environment);
            Process process = processBuilder.start();
            if (waitForProcess) {
                int exitCode = process.waitFor();
                if (exitCode != 0) throw new MojoExecutionException("APM proxy exited with code " + exitCode);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MojoExecutionException("Interrupted while waiting for the APM proxy", e);
        } catch (Exception e) {
            throw new MojoExecutionException("Unable to start the Java APM proxy", e);
        }
    }

    private void copyResolvedAgent(Path target) throws IOException, URISyntaxException, MojoExecutionException {
        URL resource = StartApmProxyMojo.class.getClassLoader()
                .getResource("co/elastic/apm/agent/premain/BootstrapCheck.class");
        if (resource == null || !(resource.openConnection() instanceof JarURLConnection connection)) {
            throw new MojoExecutionException(
                    "Maven resolved the Elastic APM dependency, but its jar file could not be located");
        }
        Path resolvedAgent = Path.of(connection.getJarFileURL().toURI());
        if (target.getParent() != null) Files.createDirectories(target.getParent());
        Files.copy(resolvedAgent, target, StandardCopyOption.REPLACE_EXISTING);
        getLog().info("Using Maven-resolved Elastic APM agent " + agentVersion + " from " + resolvedAgent);
    }

    private static String javaExecutable() {
        String executable = System.getProperty("java.home") + java.io.File.separator + "bin"
                + java.io.File.separator + "java";
        return System.getProperty("os.name").toLowerCase().contains("win") ? executable + ".exe" : executable;
    }
}
