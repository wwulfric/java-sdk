/*
 * Copyright (c) Microsoft Corporation.
 * Licensed under the MIT License.
 */

package io.dapr.it;

import io.dapr.config.Properties;
import org.apache.commons.lang3.tuple.ImmutablePair;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static io.dapr.it.Retry.callWithRetry;


public class DaprRun implements Stoppable {

  private static final String DAPR_SUCCESS_MESSAGE = "You're up and running!";

  private static final String DAPR_RUN = "dapr run --app-id %s --components-path ./components";

  // the arg in -Dexec.args is the app's port
  private static final String DAPR_COMMAND =
      " -- mvn exec:java -D exec.mainClass=%s -D exec.classpathScope=test -D exec.args=\"%s\"";

  private final DaprPorts ports;

  private final String appName;

  private final int maxWaitMilliseconds;

  private final AtomicBoolean started;

  private final Command startCommand;

  private final Command listCommand;

  private final Command stopCommand;

  private DaprRun(String testName,
                  DaprPorts ports,
                  String successMessage,
                  Class serviceClass,
                  int maxWaitMilliseconds) {
    // The app name needs to be deterministic since we depend on it to kill previous runs.
    this.appName = serviceClass == null ? testName : String.format("%s_%s", testName, serviceClass.getSimpleName());
    this.startCommand =
        new Command(successMessage, buildDaprCommand(this.appName, serviceClass, ports));
    this.listCommand = new Command(
      this.appName,
      "dapr list");
    this.stopCommand = new Command(
        "app stopped successfully",
        "dapr stop --app-id " + this.appName);
    this.ports = ports;
    this.maxWaitMilliseconds = maxWaitMilliseconds;
    this.started = new AtomicBoolean(false);
  }

  public void start() throws InterruptedException, IOException {
    long start = System.currentTimeMillis();
    // First, try to stop previous run (if left running).
    this.stop();
    // Wait for the previous run to kill the prior process.
    long timeLeft = this.maxWaitMilliseconds - (System.currentTimeMillis() - start);
    callWithRetry(() -> {
      System.out.println("Checking if previous run for Dapr application has stopped ...");
      try {
        this.listCommand.run();
        throw new RuntimeException("Previous run for app has not stopped yet!");
      } catch (IllegalStateException e) {
        // Success because we the list command did not find the app id.
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }, timeLeft);

    System.out.println("Starting dapr application ...");
    this.startCommand.run();
    this.started.set(true);

    timeLeft = this.maxWaitMilliseconds - (System.currentTimeMillis() - start);
    callWithRetry(() -> {
      System.out.println("Checking if Dapr application has started ...");
      try {
        this.listCommand.run();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }, timeLeft);

    if (this.ports.getAppPort() != null) {
      timeLeft = this.maxWaitMilliseconds - (System.currentTimeMillis() - start);
      callWithRetry(() -> {
        System.out.println("Checking if app is listening on port ...");
        assertListeningOnPort(this.ports.getAppPort());
      }, timeLeft);
    }

    if (this.ports.getHttpPort() != null) {
      timeLeft = this.maxWaitMilliseconds - (System.currentTimeMillis() - start);
      callWithRetry(() -> {
        System.out.println("Checking if Dapr is listening on HTTP port ...");
        assertListeningOnPort(this.ports.getHttpPort());
      }, timeLeft);
    }

    if (this.ports.getGrpcPort() != null) {
      timeLeft = this.maxWaitMilliseconds - (System.currentTimeMillis() - start);
      callWithRetry(() -> {
        System.out.println("Checking if Dapr is listening on GRPC port ...");
        assertListeningOnPort(this.ports.getGrpcPort());
      }, timeLeft);
    }
    System.out.println("Dapr application started.");
  }

  @Override
  public void stop() throws InterruptedException, IOException {
    System.out.println("Stopping dapr application ...");
    try {
      this.stopCommand.run();

      System.out.println("Dapr application stopped.");
    } catch (RuntimeException e) {
      System.out.println("Could not stop app " + this.appName + ": " + e.getMessage());
    }
  }

  public void use() {
    if (this.ports.getHttpPort() != null) {
      System.getProperties().setProperty("dapr.http.port", String.valueOf(this.ports.getHttpPort()));
    }
    if (this.ports.getGrpcPort() != null) {
      System.getProperties().setProperty("dapr.grpc.port", String.valueOf(this.ports.getGrpcPort()));
    }
    System.getProperties().setProperty("dapr.grpc.enabled", Boolean.TRUE.toString());
  }

  public void switchToGRPC() {
    System.getProperties().setProperty("dapr.grpc.enabled", Boolean.TRUE.toString());
  }

  public void switchToHTTP() {
    System.getProperties().setProperty("dapr.grpc.enabled", Boolean.FALSE.toString());
  }

  public int getGrpcPort() {
    return ports.getGrpcPort();
  }

  public int getHttpPort() {
    return ports.getHttpPort();
  }

  public int getAppPort() {
    return ports.getAppPort();
  }

  public String getAppName() {
    return appName;
  }

  private static String buildDaprCommand(String appName, Class serviceClass, DaprPorts ports) {
    StringBuilder stringBuilder = new StringBuilder(String.format(DAPR_RUN, appName))
        .append(ports.getAppPort() != null ? " --app-port " + ports.getAppPort() : "")
        .append(ports.getHttpPort() != null ? " --dapr-http-port " + ports.getHttpPort() : "")
        .append(ports.getGrpcPort() != null ? " --dapr-grpc-port " + ports.getGrpcPort() : "")
        .append(serviceClass == null ? "" :
            String.format(DAPR_COMMAND, serviceClass.getCanonicalName(),
                ports.getAppPort() != null ? ports.getAppPort().toString() : ""));
    return stringBuilder.toString();
  }

  private static void assertListeningOnPort(int port) {
    System.out.printf("Checking port %d ...\n", port);

    java.net.SocketAddress socketAddress = new java.net.InetSocketAddress(Properties.SIDECAR_IP.get(), port);
    try (java.net.Socket socket = new java.net.Socket()) {
      socket.connect(socketAddress, 1000);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    System.out.printf("Confirmed listening on port %d.\n", port);
  }

  static class Builder {

    private final String testName;

    private final Supplier<DaprPorts> portsSupplier;

    private final String successMessage;

    private final int maxWaitMilliseconds;

    private Class serviceClass;

    Builder(
        String testName,
        Supplier<DaprPorts> portsSupplier,
        String successMessage,
        int maxWaitMilliseconds) {
      this.testName = testName;
      this.portsSupplier = portsSupplier;
      this.successMessage = successMessage;
      this.maxWaitMilliseconds = maxWaitMilliseconds;
    }

    public Builder withServiceClass(Class serviceClass) {
      this.serviceClass = serviceClass;
      return this;
    }

    DaprRun build() {
      return new DaprRun(
              this.testName,
              this.portsSupplier.get(),
              this.successMessage,
              this.serviceClass,
              this.maxWaitMilliseconds);
    }

    /**
     * Builds app and dapr run separately. It can be useful to force the restart of one of them.
     * @return Pair of AppRun and DaprRun.
     */
    ImmutablePair<AppRun, DaprRun> splitBuild() {
      DaprPorts ports = this.portsSupplier.get();
      AppRun appRun = new AppRun(
              ports,
              this.successMessage,
              this.serviceClass,
              this.maxWaitMilliseconds);

      DaprRun daprRun = new DaprRun(
              this.testName,
              ports,
              DAPR_SUCCESS_MESSAGE,
              null,
              this.maxWaitMilliseconds);

      return new ImmutablePair<>(appRun, daprRun);
    }
  }
}
