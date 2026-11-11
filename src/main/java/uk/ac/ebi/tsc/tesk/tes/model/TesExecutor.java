package uk.ac.ebi.tsc.tesk.tes.model;

import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.validation.Valid;
import javax.validation.constraints.*;

import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.ABSOLUTE_PATH_MESSAGE;
import static uk.ac.ebi.tsc.tesk.k8s.constant.Constants.ABSOLUTE_PATH_REGEXP;

/**
 * Executor describes a command to be executed, and its environment.
 */
@ApiModel(description = "Executor describes a command to be executed, and its environment.")
@javax.annotation.Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2021-03-24T17:10:08.716Z[Europe/London]")
public class TesExecutor   {
  @JsonProperty("image")
  private String image;

  @JsonProperty("docker_registry")
  private String dockerRegistry;

  @JsonProperty(value = "docker_login", access = JsonProperty.Access.WRITE_ONLY)
  private String dockerLogin;

  @JsonProperty(value = "docker_pass", access = JsonProperty.Access.WRITE_ONLY)
  private String dockerPass;

  @JsonProperty("command")
  @Valid
  private List<String> command = new ArrayList<>();

  @JsonProperty("workdir")
  private String workdir;

  @JsonProperty("stdin")
  private String stdin;

  @JsonProperty("stdout")
  private String stdout;

  @JsonProperty("stderr")
  private String stderr;

  @JsonProperty("env")
  @Valid
  private Map<String, String> env = null;

  @JsonProperty("ignore_error")
  private Boolean ignoreError;

  public TesExecutor image(String image) {
    this.image = image;
    return this;
  }

  /**
   * Name of the container image. The string will be passed as the image argument to the containerization run command. Examples:    - `ubuntu`    - `quay.io/aptible/ubuntu`    - `gcr.io/my-org/my-image`    - `myregistryhost:5000/fedora/httpd:version1.0`
   * @return image
  */
  @ApiModelProperty(example = "ubuntu:22.04", required = true, value = "Name of the container image. The string will be passed as the image argument to the containerization run command. Examples:    - `ubuntu`    - `quay.io/aptible/ubuntu`    - `gcr.io/my-org/my-image`    - `myregistryhost:5000/fedora/httpd:version1.0`")
  @NotNull
  @NotBlank

  public String getImage() {
    return image;
  }

  public void setImage(String image) {
    this.image = image;
  }

  public TesExecutor dockerRegistry(String dockerRegistry) {
    this.dockerRegistry = dockerRegistry;
    return this;
  }

  @ApiModelProperty(value = "Experimental TESK-specific proof-of-concept workaround; do not rely on it in production. Registry address for a private executor image. Supply it with docker_login and docker_pass when creating a task.")
  public String getDockerRegistry() {
    return dockerRegistry;
  }

  public void setDockerRegistry(String dockerRegistry) {
    this.dockerRegistry = dockerRegistry;
  }

  public TesExecutor dockerLogin(String dockerLogin) {
    this.dockerLogin = dockerLogin;
    return this;
  }

  @ApiModelProperty(value = "Experimental TESK-specific proof-of-concept workaround; do not rely on it in production. Registry username, used only when creating the task; never returned.")
  public String getDockerLogin() {
    return dockerLogin;
  }

  public void setDockerLogin(String dockerLogin) {
    this.dockerLogin = dockerLogin;
  }

  public TesExecutor dockerPass(String dockerPass) {
    this.dockerPass = dockerPass;
    return this;
  }

  @ApiModelProperty(value = "Experimental TESK-specific proof-of-concept workaround; do not rely on it in production. Registry password or access token, used only when creating the task; never returned.")
  public String getDockerPass() {
    return dockerPass;
  }

  public void setDockerPass(String dockerPass) {
    this.dockerPass = dockerPass;
  }

  @JsonIgnore
  @AssertTrue(message = "docker_registry, docker_login and docker_pass must all be supplied together")
  public boolean isDockerCredentialsValid() {
    boolean any = dockerRegistry != null || dockerLogin != null || dockerPass != null;
    return !any || (hasText(dockerRegistry) && hasText(dockerLogin) && hasText(dockerPass));
  }

  @JsonIgnore
  @AssertTrue(message = "docker_registry must not contain whitespace or embedded credentials")
  public boolean isDockerRegistryValid() {
    return dockerRegistry == null ||
        (hasText(dockerRegistry) && dockerRegistry.indexOf('@') < 0 &&
            dockerRegistry.codePoints().noneMatch(Character::isWhitespace));
  }

  private static boolean hasText(String value) {
    return value != null && !value.trim().isEmpty();
  }

  public TesExecutor command(List<String> command) {
    this.command = command;
    return this;
  }

  public TesExecutor addCommandItem(String commandItem) {
    if (this.command == null) {
      this.command = new ArrayList<>();
    }
    this.command.add(commandItem);
    return this;
  }

  /**
   * A sequence of program arguments to execute, where the first argument is the program to execute (i.e. argv). Example: ``` {   \"command\" : [\"/bin/md5\", \"/data/file1\"] } ```
   * @return command
  */
  @ApiModelProperty(example = "[\"/bin/md5sum\",\"/data/file1\"]", required = true, value = "A sequence of program arguments to execute, where the first argument is the program to execute (i.e. argv). Example: ``` {   \"command\" : [\"/bin/md5sum\", \"/data/file1\"] } ```")
  @NotNull
  @NotEmpty

  public List<String> getCommand() {
    return command;
  }

  public void setCommand(List<String> command) {
    this.command = command;
  }

  public TesExecutor workdir(String workdir) {
    this.workdir = workdir;
    return this;
  }

  /**
   * The working directory that the command will be executed in. If not defined, the system will default to the directory set by the container image.
   * @return workdir
  */
  @ApiModelProperty(example = "/data/", value = "The working directory that the command will be executed in. If not defined, the system will default to the directory set by the container image.")
  @Pattern(regexp = ABSOLUTE_PATH_REGEXP, message = ABSOLUTE_PATH_MESSAGE)

  public String getWorkdir() {
    return workdir;
  }

  public void setWorkdir(String workdir) {
    this.workdir = workdir;
  }

  public TesExecutor stdin(String stdin) {
    this.stdin = stdin;
    return this;
  }

  /**
   * Path inside the container to a file which will be piped to the executor's stdin. This must be an absolute path. This mechanism could be used in conjunction with the input declaration to process a data file using a tool that expects STDIN.  For example, to get the MD5 sum of a file by reading it into the STDIN ``` {   \"command\" : [\"/bin/md5\"],   \"stdin\" : \"/data/file1\" } ```
   * @return stdin
  */
  @ApiModelProperty(example = "/data/file1", value = "Path inside the container to a file which will be piped to the executor's stdin. This must be an absolute path. This mechanism could be used in conjunction with the input declaration to process a data file using a tool that expects STDIN.  For example, to get the MD5 sum of a file by reading it into the STDIN ``` {   \"command\" : [\"/bin/md5\"],   \"stdin\" : \"/data/file1\" } ```")
  @Pattern(regexp = ABSOLUTE_PATH_REGEXP, message = ABSOLUTE_PATH_MESSAGE)

  public String getStdin() {
    return stdin;
  }

  public void setStdin(String stdin) {
    this.stdin = stdin;
  }

  public TesExecutor stdout(String stdout) {
    this.stdout = stdout;
    return this;
  }

  /**
   * Path inside the container to a file where the executor's stdout will be written to. Must be an absolute path. Example: ``` {   \"stdout\" : \"/tmp/stdout.log\" } ```
   * @return stdout
  */
  @ApiModelProperty(example = "/data/outfile", value = "Path inside the container to a file where the executor's stdout will be written to. Must be an absolute path. Example: ``` {   \"stdout\" : \"/data/outfile\" } ```")
  @Pattern(regexp = ABSOLUTE_PATH_REGEXP, message = ABSOLUTE_PATH_MESSAGE)

  public String getStdout() {
    return stdout;
  }

  public void setStdout(String stdout) {
    this.stdout = stdout;
  }

  public TesExecutor stderr(String stderr) {
    this.stderr = stderr;
    return this;
  }

  /**
   * Path inside the container to a file where the executor's stderr will be written to. Must be an absolute path. Example: ``` {   \"stderr\" : \"/tmp/stderr.log\" } ```
   * @return stderr
  */
  @ApiModelProperty(example = "/tmp/stderr.log", value = "Path inside the container to a file where the executor's stderr will be written to. Must be an absolute path. Example: ``` {   \"stderr\" : \"/tmp/stderr.log\" } ```")
  @Pattern(regexp = ABSOLUTE_PATH_REGEXP, message = ABSOLUTE_PATH_MESSAGE)

  public String getStderr() {
    return stderr;
  }

  public void setStderr(String stderr) {
    this.stderr = stderr;
  }

  public TesExecutor ignoreError(Boolean ignoreError) {
    this.ignoreError = ignoreError;
    return this;
  }

  /**
   * Whether to run the next executor, and finish the task, after this executor fails (TES 1.1).
   * TESK stops at the first executor that fails, so it accepts only `false`.
   * @return ignoreError
  */
  @ApiModelProperty(value = "Whether errors of this executor are ignored, and the next one runs. TESK doesn't support it: only false is accepted.")
  public Boolean getIgnoreError() {
    return ignoreError;
  }

  public void setIgnoreError(Boolean ignoreError) {
    this.ignoreError = ignoreError;
  }

  /**
   * Refuses `ignore_error: true`, rather than running the task differently than asked.
   * @return whether the executor doesn't ask to ignore its errors
   */
  @JsonIgnore
  @AssertTrue(message = "ignore_error: true is not supported; TESK stops at the first executor that fails")
  public boolean isIgnoreErrorUnset() {
    return ignoreError == null || !ignoreError;
  }

  public TesExecutor env(Map<String, String> env) {
    this.env = env;
    return this;
  }

  public TesExecutor putEnvItem(String key, String envItem) {
    if (this.env == null) {
      this.env = new HashMap<>();
    }
    this.env.put(key, envItem);
    return this;
  }

  /**
   * Enviromental variables to set within the container. Example: ``` {   \"env\" : {     \"ENV_CONFIG_PATH\" : \"/data/config.file\",     \"BLASTDB\" : \"/data/GRC38\",     \"HMMERDB\" : \"/data/hmmer\"   } } ```
   * @return env
  */
  @ApiModelProperty(example = "{\"BLASTDB\":\"/data/GRC38\",\"HMMERDB\":\"/data/hmmer\"}", value = "Enviromental variables to set within the container. Example: ``` {   \"env\" : {     \"ENV_CONFIG_PATH\" : \"/data/config.file\",     \"BLASTDB\" : \"/data/GRC38\",     \"HMMERDB\" : \"/data/hmmer\"   } } ```")


  public Map<String, String> getEnv() {
    return env;
  }

  public void setEnv(Map<String, String> env) {
    this.env = env;
  }


  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    TesExecutor tesExecutor = (TesExecutor) o;
    return Objects.equals(this.image, tesExecutor.image) &&
        Objects.equals(this.dockerRegistry, tesExecutor.dockerRegistry) &&
        Objects.equals(this.dockerLogin, tesExecutor.dockerLogin) &&
        Objects.equals(this.dockerPass, tesExecutor.dockerPass) &&
        Objects.equals(this.command, tesExecutor.command) &&
        Objects.equals(this.workdir, tesExecutor.workdir) &&
        Objects.equals(this.stdin, tesExecutor.stdin) &&
        Objects.equals(this.stdout, tesExecutor.stdout) &&
        Objects.equals(this.stderr, tesExecutor.stderr) &&
        Objects.equals(this.env, tesExecutor.env) &&
        Objects.equals(this.ignoreError, tesExecutor.ignoreError);
  }

  @Override
  public int hashCode() {
    return Objects.hash(image, dockerRegistry, dockerLogin, dockerPass, command, workdir, stdin, stdout, stderr, env, ignoreError);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class TesExecutor {\n");
    
    sb.append("    image: ").append(toIndentedString(image)).append("\n");
    sb.append("    docker_registry: ").append(toIndentedString(dockerRegistry)).append("\n");
    sb.append("    command: ").append(toIndentedString(command)).append("\n");
    sb.append("    workdir: ").append(toIndentedString(workdir)).append("\n");
    sb.append("    stdin: ").append(toIndentedString(stdin)).append("\n");
    sb.append("    stdout: ").append(toIndentedString(stdout)).append("\n");
    sb.append("    stderr: ").append(toIndentedString(stderr)).append("\n");
    sb.append("    env: ").append(toIndentedString(env)).append("\n");
    sb.append("    ignoreError: ").append(toIndentedString(ignoreError)).append("\n");
    sb.append("}");
    return sb.toString();
  }

  /**
   * Convert the given object to string with each line indented by 4 spaces
   * (except the first line).
   */
  private String toIndentedString(Object o) {
    if (o == null) {
      return "null";
    }
    return o.toString().replace("\n", "\n    ");
  }
}

